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
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

class EmailTelemetryLifecycleTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:lifecycle_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool(): Unit {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun memoryEmailSuppressionStoreIsolatesStreamsAndPreservesHigherSeverity(): Unit = runBlocking {
        verifySuppressionContract(MemoryEmailSuppressionStore())
    }

    @Test
    fun sqlEmailSuppressionStoreIsolatesStreamsAndPreservesHigherSeverity(): Unit = runBlocking {
        verifySuppressionContract(SqlEmailSuppressionStore())
    }

    private suspend fun verifySuppressionContract(suppressions: EmailSuppressionStore) {
        val delegate = MemoryMailer()
        val mailer = SuppressionGuardedMailer(delegate, suppressions)

        // Marketing mail without RFC 8058 unsubscribe URL is rejected at construction time.
        assertFailsWith<IllegalArgumentException> {
            EmailMessage(
                to = "reader@acme.test",
                from = "news@updates.acme.test",
                subject = "[QC] Weekly release",
                textBody = "New features",
                stream = MailStream.MARKETING,
                idempotencyKey = "mkt-missing-unsub",
                oneClickUnsubscribeUrl = null,
            )
        }

        // 1. Marketing UNSUBSCRIBE blocks marketing mail but STILL delivers 6-digit login codes.
        suppressions.suppress("Reader@Acme.Test", EmailSuppressionReason.UNSUBSCRIBE, "unsub-1", now = 1_000L)
        val blockedPromo = mailer.send(
            EmailMessage(
                to = "reader@acme.test",
                from = "news@updates.acme.test",
                subject = "Weekly digest",
                textBody = "Digest",
                stream = MailStream.MARKETING,
                idempotencyKey = "mkt-1",
                oneClickUnsubscribeUrl = "https://acme.test/v1/email/unsubscribe?token=abc",
            ),
        )
        assertEquals(MailSendResult.Suppressed(EmailSuppressionReason.UNSUBSCRIBE), blockedPromo)

        val loginOtp = mailer.send(
            EmailMessage(
                to = "reader@acme.test",
                from = "login@mail.acme.test",
                subject = "Your sign-in code",
                textBody = "Code: 482910",
                stream = MailStream.TRANSACTIONAL,
                idempotencyKey = "otp-1",
            ),
        )
        assertIs<MailSendResult.Sent>(loginOtp)
        assertEquals(1, delegate.sentMessages().size)

        // 2. Hard bounce upgrades suppression to block ALL streams, and a later UNSUBSCRIBE cannot downgrade it.
        suppressions.suppress("reader@acme.test", EmailSuppressionReason.HARD_BOUNCE, "ses-bounce-1", now = 2_000L)
        suppressions.suppress("reader@acme.test", EmailSuppressionReason.UNSUBSCRIBE, "late-unsub", now = 3_000L)

        val blockedOtp = mailer.send(
            EmailMessage(
                to = "reader@acme.test",
                from = "login@mail.acme.test",
                subject = "Your sign-in code",
                textBody = "Code: 193847",
                stream = MailStream.TRANSACTIONAL,
                idempotencyKey = "otp-2",
            ),
        )
        assertEquals(MailSendResult.Suppressed(EmailSuppressionReason.HARD_BOUNCE), blockedOtp)

        suppressions.remove("reader@acme.test")
        assertNull(suppressions.find("reader@acme.test"))
    }

    @Test
    fun resendMailerSendsIdempotencyKeyAndRfc8058HeadersAndUnsubscribeTokenVerifies(): Unit = runBlocking {
        var nowMs = 1_760_000_000_000L
        val codec = UnsubscribeTokenCodec("super-secret-unsub-key", defaultTtlSeconds = 3_600L, clock = { nowMs })
        val token = codec.sign("  Subscriber@Acme.Test ")
        assertEquals("subscriber@acme.test", codec.verify(token))
        assertNull(codec.verify("${token}tampered"))
        nowMs += 3_600_000L
        assertNull(codec.verify(token), "expired unsubscribe token must return null")

        var capturedIdempotency = ""
        var capturedAuth = ""
        var capturedBody = ""
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedAuth = request.headers[HttpHeaders.Authorization].orEmpty()
                    capturedIdempotency = request.headers["Idempotency-Key"].orEmpty()
                    capturedBody = String((request.body as OutgoingContent.ByteArrayContent).bytes(), Charsets.UTF_8)
                    respond(
                        """{"id":"re_msg_99"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val resend = ResendMailer(http, apiKey = "re_test_key")
        val unsubUrl = "https://api.acme.test/v1/email/unsubscribe?token=signed"
        val res = resend.send(
            EmailMessage(
                to = "Subscriber@Acme.Test",
                from = "Qoloa <hello@updates.acme.test>",
                subject = "Spring update",
                textBody = "Hello",
                stream = MailStream.MARKETING,
                idempotencyKey = "campaign-2026-u1",
                oneClickUnsubscribeUrl = unsubUrl,
            ),
        )
        assertEquals(MailSendResult.Sent("re_msg_99"), res)
        assertEquals("Bearer re_test_key", capturedAuth)
        assertEquals("campaign-2026-u1", capturedIdempotency)
        val parsed = Json.parseToJsonElement(capturedBody).jsonObject
        val headers = parsed["headers"]!!.jsonObject
        assertEquals("<$unsubUrl>", (headers["List-Unsubscribe"] as JsonPrimitive).content)
        assertEquals("List-Unsubscribe=One-Click", (headers["List-Unsubscribe-Post"] as JsonPrimitive).content)
    }

    @Test
    fun postHogAnalyticsForcesStatelessFlagOnAnonymousEventsAndAiUsageSummarizesCleanly(): Unit = runBlocking {
        val payloads = ArrayList<String>()
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    payloads += String((request.body as OutgoingContent.ByteArrayContent).bytes(), Charsets.UTF_8)
                    respond("""{"status":1}""", HttpStatusCode.OK)
                }
            }
        }
        val sink = PostHogAnalyticsSink(http, projectApiKey = "phc_test_123")

        // Even if caller mistakenly passes $process_person_profile=true on an anonymous event, sink overrides to false.
        assertTrue(
            sink.capture(
                AnalyticsEvent(
                    event = "paywall_viewed",
                    distinctId = "anon-guest-1",
                    identified = false,
                    properties = mapOf("\$process_person_profile" to JsonPrimitive(true)),
                    timestampMs = 1_760_000_000_000L,
                ),
            ),
        )
        assertTrue(
            sink.capture(
                AnalyticsEvent(
                    event = "purchase_completed",
                    distinctId = "owner-1",
                    identified = true,
                    timestampMs = 1_760_000_001_000L,
                ),
            ),
        )

        val anonProps = Json.parseToJsonElement(payloads[0]).jsonObject["properties"]!!.jsonObject
        val idProps = Json.parseToJsonElement(payloads[1]).jsonObject["properties"]!!.jsonObject
        assertEquals(false, (anonProps["\$process_person_profile"] as JsonPrimitive).booleanOrNull)
        assertEquals(true, (idProps["\$process_person_profile"] as JsonPrimitive).booleanOrNull)

        for (store in listOf(MemoryAiUsageStore(), SqlAiUsageStore())) {
            val owner = "owner-${UUID.randomUUID()}"
            store.record(AiUsageRecord(ownerId = owner, model = "gpt-4o-mini", inputTokens = 400, outputTokens = 100, costMicros = 120L, latencyMs = 340L, outcome = "ok", createdAt = 1_000L))
            store.record(AiUsageRecord(ownerId = owner, model = "gpt-4o-mini", inputTokens = 600, outputTokens = 250, costMicros = 230L, latencyMs = 510L, outcome = "ok", createdAt = 5_000L))
            assertEquals(AiUsageSummary(calls = 2, inputTokens = 1_000L, outputTokens = 350L, costMicros = 350L), store.summarizeForOwner(owner))
            assertEquals(AiUsageSummary(calls = 1, inputTokens = 600L, outputTokens = 250L, costMicros = 230L), store.summarizeForOwner(owner, sinceEpochMs = 2_000L))
        }
    }

    @Test
    fun turnstileVerifierClientCapabilitiesAndRetrySafeAccountDeletionCascade(): Unit = runBlocking {
        assertNull(FailClosedTurnstile.verify("tok", "203.0.113.10"))

        val turnstileHttp = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val form = String((request.body as OutgoingContent.ByteArrayContent).bytes(), Charsets.UTF_8)
                        .parseUrlEncodedParameters()
                    val body = if (form["response"] == "valid-token") {
                        """{"success":true,"hostname":"acme.test","action":"checkout","challenge_ts":"2026-11-15T00:00:00Z"}"""
                    } else {
                        """{"success":false}"""
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
            }
        }
        val verifier = CloudflareTurnstileVerifier(
            httpClient = turnstileHttp,
            secretKey = "0x4AAAAAA_secret",
            expectedHostname = "acme.test",
            expectedAction = "checkout",
        )
        assertNotNull(verifier.verify("valid-token", "203.0.113.10"))
        assertNull(verifier.verify("wrong-token", "203.0.113.10"))
        assertNull(verifier.verify("x".repeat(CloudflareTurnstileVerifier.MAX_TOKEN_LENGTH + 1), null))

        val wrongActionVerifier = CloudflareTurnstileVerifier(
            httpClient = turnstileHttp,
            secretKey = "0x4AAAAAA_secret",
            expectedHostname = "acme.test",
            expectedAction = "signup",
        )
        assertNull(wrongActionVerifier.verify("valid-token", "203.0.113.10"))

        val caps = ClientCapabilities(
            minSupportedBuild = 42,
            recommendedBuild = 45,
            signInEnabled = true,
            purchaseEnabled = false,
            features = mapOf("voice_roleplay" to true),
        )
        assertFalse(caps.isBuildSupported(41))
        assertTrue(caps.isBuildSupported(42))
        assertTrue(caps.isUpdateRecommended(43))
        assertFalse(caps.isUpdateRecommended(45))
        assertTrue(caps.isFeatureEnabled("voice_roleplay"))
        assertFalse(caps.isFeatureEnabled("unknown_flag"))

        // Full 5.1.1(v) account deletion cascade across SQL stores:
        // 1) If Apple token revocation fails first, local account data stays intact so user/worker can retry.
        val sessionsStore = SqlSessionStore()
        val sessions = SessionService(sessionsStore) { 1_760_000_000_000L }
        val identities = SqlIdentityStore()
        val documents = SqlDocumentStore()
        val entitlements = SqlEntitlementStore(identities::canonicalOwner)
        val credits = SqlCreditBalanceStore()
        val aiUsage = SqlAiUsageStore()

        val canonical = "owner-del-${UUID.randomUUID()}"
        val appleIdentity = VerifiedIdentity("https://appleid.apple.com", "sub-$canonical", "u@acme.test", true)
        identities.authenticateOrCreate(appleIdentity, { canonical }, now = 1_000L)
        val issuedSession = sessions.issue(canonical)
        documents.put(canonical, "profile", 1, """{"name":"Minh"}""", baseVersion = 0L, now = 1_000L)
        entitlements.applyWebhookEvent(
            "polar",
            "evt-$canonical",
            1_000L,
            EntitlementUpsert("sub-$canonical", canonical, "pro", SubscriptionStatus.ACTIVE, currentPeriodEnd = 9_000_000L),
        )
        credits.grant(canonical, "grant-$canonical", 100L, now = 1_000L)
        aiUsage.record(AiUsageRecord(ownerId = canonical, model = "gpt-4o-mini", inputTokens = 10, outputTokens = 10, costMicros = 5L, latencyMs = 50L, outcome = "ok"))

        var appleUp = false
        val deletion = AccountDeletionService(
            sessions = sessionsStore,
            identities = identities,
            documents = documents,
            entitlements = entitlements,
            credits = credits,
            aiUsage = aiUsage,
            appleRevoker = AppleTokenRevoker { _, _ -> appleUp },
            clock = { 2_000L },
        )

        assertFailsWith<IllegalStateException>("transient Apple revoke failure must abort before wiping local identity") {
            deletion.deleteAccount(canonical, appleToken = "rt_apple_live")
        }
        assertNotNull(sessions.authenticate(issuedSession.accessToken), "session remains intact for retry")
        assertNotNull(documents.get(canonical, "profile"))

        // 2) Retry once Apple revocation succeeds: sessions, documents, credits, entitlements, AI usage,
        // product hook, and OIDC identity bindings are all purged cleanly.
        appleUp = true
        var productHookOwner: String? = null
        val report = deletion.deleteAccount(canonical, appleToken = "rt_apple_live") { owner ->
            productHookOwner = owner
        }
        assertTrue(report.appleTokenRevoked)
        assertEquals(canonical, report.canonicalOwnerId)
        assertEquals(canonical, productHookOwner)
        assertNull(sessions.authenticate(issuedSession.accessToken))
        assertNull(documents.get(canonical, "profile"))
        assertEquals(0L, credits.available(canonical, 2_000L))
        assertTrue(entitlements.allForOwner(canonical).isEmpty())
        assertEquals(0, aiUsage.summarizeForOwner(canonical).calls)
        assertTrue(identities.authenticateOrCreate(appleIdentity, { "recreated-$canonical" }, now = 3_000L).created)
    }
}
