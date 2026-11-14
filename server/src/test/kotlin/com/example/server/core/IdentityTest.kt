package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * OIDC JWKS verification (`RS256` + `ES256`), key-rotation caching, and
 * `(issuer, subject)` identity binding / guest-merge aliasing across both
 * [MemoryIdentityStore] and [SqlIdentityStore].
 */
class IdentityTest {

    private var nowMs = 1_760_000_000_000L

    private val rsaPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherRsaPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val ecPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun failClosedRefusesEveryToken(): Unit = runBlocking {
        val token = signEs256(
            kid = "apple-1",
            claimsJson = """{"iss":"https://appleid.apple.com","sub":"u1","aud":"app.bundle","exp":1760000600}""",
        )
        assertNull(FailClosedIdentities.verify(token))
    }

    @Test
    fun verifiesRs256GoogleAndEs256AppleTokensThroughCompositeVerifier(): Unit = runBlocking {
        val appleVerifier = JwksIdentityVerifier(
            jwks = StaticJwksSource(listOf(ecJwk(ecPair.public as ECPublicKey, kid = "apple-k1"))),
            allowedIssuers = setOf("https://appleid.apple.com"),
            allowedAudiences = setOf("app.qoloa.ios", "app.qoloa.web"),
            clock = { nowMs },
        )
        val googleVerifier = JwksIdentityVerifier(
            jwks = StaticJwksSource(listOf(rsaJwk(rsaPair.public as RSAPublicKey, kid = "google-k1"))),
            allowedIssuers = setOf("https://accounts.google.com", "accounts.google.com"),
            allowedAudiences = setOf("google-web-client-id"),
            allowedAuthorizedParties = setOf("google-web-client-id", "google-android-play-id"),
            clock = { nowMs },
        )
        val composite = CompositeIdentityVerifier(listOf(appleVerifier, googleVerifier))

        val appleToken = signEs256(
            kid = "apple-k1",
            claimsJson = """
                {
                  "iss": "https://appleid.apple.com",
                  "sub": "001234.apple.sub",
                  "aud": ["app.qoloa.ios", "other.client"],
                  "email": "relay@privaterelay.appleid.com",
                  "email_verified": "true",
                  "iat": 1760000000,
                  "exp": 1760000600
                }
            """.trimIndent(),
        )
        val appleId = assertNotNull(composite.verify(appleToken))
        assertEquals("https://appleid.apple.com", appleId.issuer)
        assertEquals("001234.apple.sub", appleId.subject)
        assertEquals("relay@privaterelay.appleid.com", appleId.email)
        assertTrue(appleId.emailVerified, "Apple's string 'true' email_verified must be recognized")

        val googleToken = signRs256(
            kid = "google-k1",
            claimsJson = """
                {
                  "iss": "https://accounts.google.com",
                  "sub": "google-sub-99",
                  "aud": "google-web-client-id",
                  "azp": "google-android-play-id",
                  "email": "reader@acme.test",
                  "email_verified": true,
                  "iat": 1760000000,
                  "exp": 1760000600
                }
            """.trimIndent(),
        )
        val googleId = assertNotNull(composite.verify(googleToken))
        assertEquals("google-sub-99", googleId.subject)
        assertTrue(googleId.emailVerified)
    }

    @Test
    fun refusesWrongKeyAlgConfusionBadClaimsAndExpiry(): Unit = runBlocking {
        val verifier = JwksIdentityVerifier(
            jwks = StaticJwksSource(
                listOf(
                    rsaJwk(rsaPair.public as RSAPublicKey, kid = "rsa-1"),
                    ecJwk(ecPair.public as ECPublicKey, kid = "ec-1"),
                ),
            ),
            allowedIssuers = setOf("https://accounts.google.com"),
            allowedAudiences = setOf("web-client"),
            allowedAuthorizedParties = setOf("web-client", "ios-client"),
            clockSkewSeconds = 60L,
            clock = { nowMs },
        )

        // Signed with another RSA key claiming kid=rsa-1
        val wrongKey = signRs256(
            kid = "rsa-1",
            keyPair = otherRsaPair,
            claimsJson = """{"iss":"https://accounts.google.com","sub":"s1","aud":"web-client","azp":"ios-client","exp":1760000600}""",
        )
        assertNull(verifier.verify(wrongKey))

        // alg=none and alg=HS256 must never pass
        val headerNone = b64Url("""{"alg":"none","kid":"rsa-1"}""".toByteArray())
        val validPayload = b64Url(
            """{"iss":"https://accounts.google.com","sub":"s1","aud":"web-client","azp":"ios-client","exp":1760000600}""".toByteArray(),
        )
        assertNull(verifier.verify("$headerNone.$validPayload.c2ln"))

        // Disallowed azp (Android debug client not in production allowlist)
        val badAzp = signRs256(
            kid = "rsa-1",
            claimsJson = """{"iss":"https://accounts.google.com","sub":"s1","aud":"web-client","azp":"rogue-client","exp":1760000600}""",
        )
        assertNull(verifier.verify(badAzp))

        // Missing azp when allowedAuthorizedParties is configured
        val missingAzp = signRs256(
            kid = "rsa-1",
            claimsJson = """{"iss":"https://accounts.google.com","sub":"s1","aud":"web-client","exp":1760000600}""",
        )
        assertNull(verifier.verify(missingAzp))

        // Wrong audience
        val badAud = signRs256(
            kid = "rsa-1",
            claimsJson = """{"iss":"https://accounts.google.com","sub":"s1","aud":"other-app","azp":"ios-client","exp":1760000600}""",
        )
        assertNull(verifier.verify(badAud))

        // Expired past clock skew (now = 1_760_000_000, skew = 60s -> exp 1_759_999_940 fails)
        val expired = signRs256(
            kid = "rsa-1",
            claimsJson = """{"iss":"https://accounts.google.com","sub":"s1","aud":"web-client","azp":"ios-client","exp":1759999940}""",
        )
        assertNull(verifier.verify(expired))

        // Future nbf beyond skew fails
        val notYetValid = signRs256(
            kid = "rsa-1",
            claimsJson = """{"iss":"https://accounts.google.com","sub":"s1","aud":"web-client","azp":"ios-client","nbf":1760000100,"exp":1760000600}""",
        )
        assertNull(verifier.verify(notYetValid))

        // Unverified email stays emailVerified=false
        val unverifiedEmailToken = signRs256(
            kid = "rsa-1",
            claimsJson = """{"iss":"https://accounts.google.com","sub":"s1","aud":"web-client","azp":"ios-client","email":"u@acme.test","email_verified":false,"exp":1760000600}""",
        )
        val unverified = assertNotNull(verifier.verify(unverifiedEmailToken))
        assertEquals("u@acme.test", unverified.email)
        assertFalse(unverified.emailVerified)
    }

    @Test
    fun httpJwksSourceRefreshesOnRotatedKidOnlyAfterCooldownAndIgnoresForgedKnownKid(): Unit = runBlocking {
        val fetches = AtomicInteger(0)
        var publishedKeys = listOf(ecJwk(ecPair.public as ECPublicKey, kid = "k1"))
        val rotatedEc: KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    fetches.incrementAndGet()
                    val body = """{"keys":${Json.encodeToString(publishedKeys)}}"""
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val source = HttpJwksSource(
            httpClient = client,
            jwksUrl = "https://id.acme.test/.well-known/jwks.json",
            cacheTtlMs = 3_600_000L,
            minRefreshIntervalMs = 30_000L,
            clock = { nowMs },
        )
        val verifier = JwksIdentityVerifier(
            jwks = source,
            allowedIssuers = setOf("https://id.acme.test"),
            allowedAudiences = setOf("app"),
            clock = { nowMs },
        )

        val tokenK1 = signEs256(
            kid = "k1",
            keyPair = ecPair,
            claimsJson = """{"iss":"https://id.acme.test","sub":"u1","aud":"app","exp":1760000600}""",
        )
        assertNotNull(verifier.verify(tokenK1))
        assertEquals(1, fetches.get(), "cold start fetches JWKS once")

        // Forged signature on an already-cached kid must NOT trigger a refresh even after cooldown.
        nowMs += 45_000L
        val forgedKnownKid = signEs256(
            kid = "k1",
            keyPair = rotatedEc,
            claimsJson = """{"iss":"https://id.acme.test","sub":"u1","aud":"app","exp":1760000600}""",
        )
        assertNull(verifier.verify(forgedKnownKid))
        assertEquals(1, fetches.get(), "forged signature on cached kid must not refetch")

        // Provider rotates to k2: first call after cooldown fetches k2 once and verifies.
        publishedKeys = listOf(
            ecJwk(ecPair.public as ECPublicKey, kid = "k1"),
            ecJwk(rotatedEc.public as ECPublicKey, kid = "k2"),
        )
        val tokenK2 = signEs256(
            kid = "k2",
            keyPair = rotatedEc,
            claimsJson = """{"iss":"https://id.acme.test","sub":"u2","aud":"app","exp":1760000600}""",
        )
        assertNotNull(verifier.verify(tokenK2))
        assertEquals(2, fetches.get(), "rotated kid triggers one refresh after cooldown")

        // Random unknown kid spam 5 seconds later is blocked by minRefreshIntervalMs.
        nowMs += 5_000L
        val spamUnknownKid = signEs256(
            kid = "attacker-random-kid",
            keyPair = rotatedEc,
            claimsJson = """{"iss":"https://id.acme.test","sub":"u2","aud":"app","exp":1760000600}""",
        )
        assertNull(verifier.verify(spamUnknownKid))
        assertEquals(2, fetches.get(), "unknown kid within cooldown must not hit upstream")
    }

    private fun rsaJwk(pub: RSAPublicKey, kid: String) = JwkKey(
        kty = "RSA",
        kid = kid,
        use = "sig",
        alg = "RS256",
        n = b64UrlUnsigned(pub.modulus),
        e = b64UrlUnsigned(pub.publicExponent),
    )

    private fun ecJwk(pub: ECPublicKey, kid: String) = JwkKey(
        kty = "EC",
        kid = kid,
        use = "sig",
        alg = "ES256",
        crv = "P-256",
        x = b64UrlFixed(pub.w.affineX, 32),
        y = b64UrlFixed(pub.w.affineY, 32),
    )

    private fun signRs256(kid: String, claimsJson: String, keyPair: KeyPair = rsaPair): String {
        val header = b64Url("""{"alg":"RS256","kid":"$kid","typ":"JWT"}""".toByteArray())
        val payload = b64Url(claimsJson.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update("$header.$payload".toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$header.$payload.${b64Url(sig)}"
    }

    private fun signEs256(kid: String, claimsJson: String, keyPair: KeyPair = ecPair): String {
        val header = b64Url("""{"alg":"ES256","kid":"$kid","typ":"JWT"}""".toByteArray())
        val payload = b64Url(claimsJson.toByteArray())
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(keyPair.private)
            update("$header.$payload".toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$header.$payload.${b64Url(sig)}"
    }

    private fun b64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun b64UrlUnsigned(value: BigInteger): String {
        val raw = value.toByteArray()
        val stripped = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        return b64Url(stripped)
    }

    private fun b64UrlFixed(value: BigInteger, size: Int): String {
        val raw = value.toByteArray()
        val stripped = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        val out = ByteArray(size)
        stripped.copyInto(out, destinationOffset = size - stripped.size)
        return b64Url(out)
    }
}

/**
 * Shared contract suite executed against both [MemoryIdentityStore] and
 * [SqlIdentityStore] (H2 PostgreSQL mode).
 */
class IdentityStoreContractTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:identities_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun memoryStoreSatisfiesPreAtoGuestPromotionAndAliasFlattening(): Unit = runBlocking {
        verifyContract(MemoryIdentityStore())
    }

    @Test
    fun sqlStoreSatisfiesPreAtoGuestPromotionAndAliasFlattening(): Unit = runBlocking {
        verifyContract(SqlIdentityStore())
    }

    private suspend fun verifyContract(store: IdentityStore) {
        val now = 1_760_000_000_000L

        // 1. Pre-ATO resistance (RFC 9700): matching raw email across issuers never merges accounts.
        val unverifiedProvider = VerifiedIdentity(
            issuer = "https://unverified.idp.test",
            subject = "attacker-sub",
            email = "victim@acme.test",
            emailVerified = false,
        )
        val googleVictim = VerifiedIdentity(
            issuer = "https://accounts.google.com",
            subject = "victim-google-sub",
            email = "victim@acme.test",
            emailVerified = true,
        )
        val first = store.authenticateOrCreate(unverifiedProvider, { "owner-attacker" }, now)
        val second = store.authenticateOrCreate(googleVictim, { "owner-victim" }, now + 1)
        assertTrue(first.created)
        assertTrue(second.created)
        assertNotEquals(
            first.ownerId,
            second.ownerId,
            "identical email across issuers must never bind onto the earlier owner",
        )
        assertEquals(
            second.ownerId,
            store.authenticateOrCreate(googleVictim, { "unused" }, now + 2).ownerId,
        )

        // 2. Guest promotion in-place + idempotent retry + collision detection.
        val appleId = VerifiedIdentity(
            issuer = "https://appleid.apple.com",
            subject = "apple-sub-1",
            email = "a@privaterelay.appleid.com",
            emailVerified = true,
        )
        assertEquals(GuestLinkResult.Promoted("guest-a"), store.linkGuest("guest-a", appleId, now + 3))
        assertEquals(
            GuestLinkResult.Promoted("guest-a"),
            store.linkGuest("guest-a", appleId, now + 4),
            "retrying the same guest link is idempotent",
        )

        val conflict = store.linkGuest("guest-b", appleId, now + 5)
        assertEquals(
            GuestLinkResult.Conflict(existingOwnerId = "guest-a", guestOwnerId = "guest-b"),
            conflict,
            "a second guest linking an already-claimed subject gets an explicit Conflict",
        )

        // 3. Transitive alias flattening preserves StoreKit appAccountToken lookup across merges.
        // Suppose guest-b merges into guest-a, and later guest-a merges into canonical-root.
        store.aliasOwner(aliasOwnerId = "guest-b", canonicalOwnerId = "guest-a", now = now + 6)
        assertEquals("guest-a", store.canonicalOwner("guest-b"))

        store.aliasOwner(aliasOwnerId = "guest-a", canonicalOwnerId = "canonical-root", now = now + 7)
        assertEquals("canonical-root", store.canonicalOwner("guest-a"))
        assertEquals(
            "canonical-root",
            store.canonicalOwner("guest-b"),
            "older StoreKit appAccountToken aliases flatten to the new canonical owner",
        )
        assertEquals(
            "canonical-root",
            store.authenticateOrCreate(appleId, { "unused" }, now + 8).ownerId,
            "existing identity bindings move to the canonical owner on merge",
        )

        // 4. Account deletion unlinks identities cleanly so re-registration starts fresh.
        store.unlinkAll("canonical-root")
        val recreated = store.authenticateOrCreate(appleId, { "fresh-owner" }, now + 9)
        assertIs<AuthenticatedOwner>(recreated)
        assertTrue(recreated.created)
        assertEquals("fresh-owner", recreated.ownerId)
    }
}
