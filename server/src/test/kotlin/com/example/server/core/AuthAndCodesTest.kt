package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AuthAndCodesTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:auth_codes_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool(): Unit {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun appleClientSecretSignerMintsValidEs256JwtAndRejectsOver180DayTtl(): Unit = runBlocking {
        val ecPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val pem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(ecPair.private.encoded))
            appendLine("-----END PRIVATE KEY-----")
        }

        assertFailsWith<IllegalArgumentException>("TTL above Apple's 180-day ceiling must be refused") {
            AppleClientSecretSigner(
                teamId = "TEAM123456",
                clientId = "app.qoloa.web",
                keyId = "KEY9876543",
                privateKeyPem = pem,
                ttlSeconds = AppleClientSecretSigner.MAX_TTL_SECONDS + 1L,
            )
        }

        val nowMs = 1_760_000_000_000L
        val signer = AppleClientSecretSigner(
            teamId = "TEAM123456",
            clientId = "app.qoloa.web",
            keyId = "KEY9876543",
            privateKeyPem = pem,
            ttlSeconds = 3_600L,
            clock = { nowMs },
        )
        val secretJwt = signer.mint()

        // Verify the minted client_secret with JwksIdentityVerifier using the corresponding P-256 public key.
        val pub = ecPair.public as ECPublicKey
        val verifier = JwksIdentityVerifier(
            jwks = StaticJwksSource(
                listOf(
                    JwkKey(
                        kty = "EC",
                        kid = "KEY9876543",
                        use = "sig",
                        alg = "ES256",
                        crv = "P-256",
                        x = fixedCoord(pub.w.affineX),
                        y = fixedCoord(pub.w.affineY),
                    ),
                ),
            ),
            allowedIssuers = setOf("TEAM123456"),
            allowedAudiences = setOf(AppleClientSecretSigner.APPLE_AUDIENCE),
            clock = { nowMs },
        )
        val verified = assertNotNull(verifier.verify(secretJwt))
        assertEquals("TEAM123456", verified.issuer)
        assertEquals("app.qoloa.web", verified.subject)
    }

    @Test
    fun httpAppleTokenRevokerSubmitsFreshClientSecretAndHandlesFailure(): Unit = runBlocking {
        val ecPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val pem = Base64.getEncoder().encodeToString(ecPair.private.encoded)
        val signer = AppleClientSecretSigner(
            teamId = "TEAM123456",
            clientId = "app.qoloa.ios",
            keyId = "KEY1",
            privateKeyPem = pem,
            clock = { 1_760_000_000_000L },
        )

        var capturedForm = ""
        val okClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val bytes = (request.body as OutgoingContent.ByteArrayContent).bytes()
                    capturedForm = String(bytes, Charsets.UTF_8)
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
                }
            }
        }
        val revoker = HttpAppleTokenRevoker(okClient, clientId = "app.qoloa.ios", secretSigner = signer)
        assertTrue(revoker.revoke("rt_apple_123", AppleTokenTypeHint.REFRESH_TOKEN))

        val params = capturedForm.parseUrlEncodedParameters()
        assertEquals("app.qoloa.ios", params["client_id"])
        assertEquals("rt_apple_123", params["token"])
        assertEquals("refresh_token", params["token_type_hint"])
        assertEquals(3, params["client_secret"].orEmpty().split('.').size)

        val failClient = HttpClient(MockEngine) {
            engine {
                addHandler { respond("bad_request", HttpStatusCode.BadRequest) }
            }
        }
        val failingRevoker = HttpAppleTokenRevoker(failClient, clientId = "app.qoloa.ios", secretSigner = signer)
        assertFalse(failingRevoker.revoke("rt_apple_123"))
    }

    @Test
    fun memoryEmailCodeStoreSatisfiesOtpLifecycleAndLockoutContract(): Unit = runBlocking {
        verifyEmailCodeContract(MemoryEmailCodeStore())
    }

    @Test
    fun sqlEmailCodeStoreSatisfiesOtpLifecycleAndLockoutContract(): Unit = runBlocking {
        verifyEmailCodeContract(SqlEmailCodeStore())
    }

    private suspend fun verifyEmailCodeContract(store: EmailCodeStore) {
        var now = 1_760_000_000_000L
        val service = EmailCodeService(
            store = store,
            ttlMs = 600_000L,
            maxAttempts = 3,
            hashPepper = "test-pepper",
            clock = { now },
        )

        // 1. Re-issuing retires the earlier open code immediately.
        val first = service.issue("  Alice@Acme.Test ")
        assertEquals("alice@acme.test", first.email)
        assertEquals(6, first.code.length)
        assertTrue(first.code.all { it.isDigit() })

        now += 1_000L
        val second = service.issue("alice@acme.test")
        if (first.code != second.code) {
            assertEquals(EmailCodeCheck.InvalidCode, service.verify("alice@acme.test", first.code))
        }

        // 2. Fresh issue + cross-purpose and cross-recipient isolation + single-use consumption.
        now += 1_000L
        val valid = service.issue("alice@acme.test", purpose = "sign_in")
        assertEquals(
            EmailCodeCheck.ExpiredOrMissing,
            service.verify("alice@acme.test", valid.code, purpose = "email_change"),
            "a sign_in code must not verify under another purpose",
        )
        assertEquals(
            EmailCodeCheck.ExpiredOrMissing,
            service.verify("bob@acme.test", valid.code, purpose = "sign_in"),
        )
        assertEquals(EmailCodeCheck.Verified, service.verify("ALICE@ACME.TEST", valid.code, purpose = "sign_in"))
        assertEquals(
            EmailCodeCheck.ExpiredOrMissing,
            service.verify("alice@acme.test", valid.code, purpose = "sign_in"),
            "a consumed code cannot be replayed",
        )

        // 3. Brute-force lockout after maxAttempts = 3 wrong guesses.
        now += 1_000L
        val target = service.issue("lockout@acme.test")
        val wrong = if (target.code == "000000") "999999" else "000000"
        assertEquals(EmailCodeCheck.InvalidCode, service.verify("lockout@acme.test", wrong))
        assertEquals(EmailCodeCheck.InvalidCode, service.verify("lockout@acme.test", wrong))
        assertEquals(EmailCodeCheck.LockedOut, service.verify("lockout@acme.test", wrong))
        assertEquals(
            EmailCodeCheck.ExpiredOrMissing,
            service.verify("lockout@acme.test", target.code),
            "even the real code is refused once attempt lockout burns the row",
        )

        // 4. Expired code is refused cleanly.
        val expiring = service.issue("ttl@acme.test")
        now += 600_000L
        assertEquals(EmailCodeCheck.ExpiredOrMissing, service.verify("ttl@acme.test", expiring.code))
    }

    private fun fixedCoord(value: BigInteger): String {
        val raw = value.toByteArray()
        val stripped = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        val padded = ByteArray(32)
        stripped.copyInto(padded, destinationOffset = 32 - stripped.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(padded)
    }
}
