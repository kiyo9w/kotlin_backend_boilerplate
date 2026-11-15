package com.example.server.core

import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BillingTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:billing_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool(): Unit {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun memoryEntitlementStoreSatisfiesWebhookDedupeAliasAndGraceContract(): Unit = runBlocking {
        val identities = MemoryIdentityStore()
        verifyEntitlementContract(identities, MemoryEntitlementStore(identities::canonicalOwner))
    }

    @Test
    fun sqlEntitlementStoreSatisfiesWebhookDedupeAliasAndGraceContract(): Unit = runBlocking {
        val identities = SqlIdentityStore()
        verifyEntitlementContract(identities, SqlEntitlementStore(identities::canonicalOwner))
    }

    private suspend fun verifyEntitlementContract(identities: IdentityStore, store: EntitlementStore) {
        val now = 1_760_000_000_000L
        identities.aliasOwner(aliasOwnerId = "guest-uuid-1", canonicalOwnerId = "owner-canonical", now = now)

        // 1. Renewal arriving with old StoreKit appAccountToken resolves to canonical owner.
        val applied = store.applyWebhookEvent(
            provider = "apple_iap",
            eventId = "notif-1",
            now = now,
            upsert = EntitlementUpsert(
                externalSubId = "orig-tx-100",
                ownerId = "guest-uuid-1",
                planCode = "pro_monthly",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEnd = now + 30L * 86_400_000L,
            ),
        )
        assertEquals(WebhookApplyOutcome.APPLIED, applied)
        assertEquals(1, store.activeForOwner("owner-canonical", now).size)
        assertEquals(1, store.activeForOwner("guest-uuid-1", now).size)

        // 2. Duplicate webhook delivery with identical (provider, eventId) is ignored even if payload differs.
        val duplicate = store.applyWebhookEvent(
            provider = "apple_iap",
            eventId = "notif-1",
            now = now + 1_000L,
            upsert = EntitlementUpsert(
                externalSubId = "orig-tx-100",
                ownerId = "owner-canonical",
                planCode = "pro_monthly",
                status = SubscriptionStatus.REVOKED,
                currentPeriodEnd = now + 30L * 86_400_000L,
            ),
        )
        assertEquals(WebhookApplyOutcome.DUPLICATE, duplicate)
        assertEquals(SubscriptionStatus.ACTIVE, store.activeForOwner("owner-canonical", now).single().status)

        // 3. Canceled keeps access until currentPeriodEnd, then drops out.
        store.applyWebhookEvent(
            provider = "apple_iap",
            eventId = "notif-2",
            now = now + 2_000L,
            upsert = EntitlementUpsert(
                externalSubId = "orig-tx-100",
                ownerId = "owner-canonical",
                planCode = "pro_monthly",
                status = SubscriptionStatus.CANCELED,
                currentPeriodEnd = now + 10_000L,
            ),
        )
        assertEquals(1, store.activeForOwner("owner-canonical", now + 9_999L).size)
        assertEquals(0, store.activeForOwner("owner-canonical", now + 10_000L).size)

        // 4. Billing retry GRACE_PERIOD stays active until graceUntil; REVOKED cuts access immediately.
        store.applyWebhookEvent(
            provider = "apple_iap",
            eventId = "notif-3",
            now = now + 11_000L,
            upsert = EntitlementUpsert(
                externalSubId = "orig-tx-100",
                ownerId = "owner-canonical",
                planCode = "pro_monthly",
                status = SubscriptionStatus.GRACE_PERIOD,
                currentPeriodEnd = now + 10_000L,
                graceUntil = now + 50_000L,
            ),
        )
        assertEquals(1, store.activeForOwner("owner-canonical", now + 49_999L).size)
        assertEquals(0, store.activeForOwner("owner-canonical", now + 50_000L).size)

        store.applyWebhookEvent(
            provider = "apple_iap",
            eventId = "notif-4",
            now = now + 12_000L,
            upsert = EntitlementUpsert(
                externalSubId = "orig-tx-100",
                ownerId = "owner-canonical",
                planCode = "pro_monthly",
                status = SubscriptionStatus.REVOKED,
                currentPeriodEnd = now + 900_000L,
                graceUntil = now + 900_000L,
            ),
        )
        assertEquals(0, store.activeForOwner("owner-canonical", now + 12_000L).size)
    }

    @Test
    fun memoryCreditBalanceStoreDrainsEarliestExpiringLotsAndRefusesPartialSpend(): Unit = runBlocking {
        verifyCreditBalanceContract(MemoryCreditBalanceStore())
    }

    @Test
    fun sqlCreditBalanceStoreDrainsEarliestExpiringLotsAndRefusesPartialSpend(): Unit = runBlocking {
        verifyCreditBalanceContract(SqlCreditBalanceStore())
    }

    private suspend fun verifyCreditBalanceContract(store: CreditBalanceStore) {
        val owner = "owner-${UUID.randomUUID()}"
        val now = 1_760_000_000_000L
        val dayMs = 86_400_000L

        // Promotional non-expiring grant (50 units) + two 180-day prepaid packs (100 expiring sooner, 80 later)
        // + one already-expired pack (200 units) that must never count.
        assertEquals(CreditGrantOutcome.GRANTED, store.grant(owner, "promo-welcome", 50L, expiresAt = null, now = now))
        assertEquals(
            CreditGrantOutcome.DUPLICATE,
            store.grant(owner, "promo-welcome", 50L, expiresAt = null, now = now + 1),
            "replaying the same order/sourceRef never double-grants credits",
        )
        assertEquals(CreditGrantOutcome.GRANTED, store.grant(owner, "pack-later", 80L, expiresAt = now + 170 * dayMs, now = now + 2))
        assertEquals(CreditGrantOutcome.GRANTED, store.grant(owner, "pack-sooner", 100L, expiresAt = now + 30 * dayMs, now = now + 3))
        assertEquals(CreditGrantOutcome.GRANTED, store.grant(owner, "pack-expired", 200L, expiresAt = now - 1L, now = now - 180 * dayMs))

        assertEquals(230L, store.available(owner, now))

        // Spending 120 drains all 100 of pack-sooner + 20 of pack-later, leaving 60 in pack-later + 50 non-expiring.
        assertTrue(store.trySpend(owner, 120L, now))
        assertEquals(110L, store.available(owner, now))

        // Jump past pack-later's 170-day expiry: only the 50 non-expiring promo units remain!
        // Had trySpend drained promo-welcome before pack-sooner/pack-later, fewer than 50 would survive here.
        val afterPacksExpire = now + 171 * dayMs
        assertEquals(50L, store.available(owner, afterPacksExpire))

        // Spending more than available refuses atomically with zero partial deduction.
        assertFalse(store.trySpend(owner, 51L, afterPacksExpire))
        assertEquals(50L, store.available(owner, afterPacksExpire))
        assertTrue(store.trySpend(owner, 50L, afterPacksExpire))
        assertEquals(0L, store.available(owner, afterPacksExpire))
    }

    @Test
    fun appleStoreJwsVerifierValidatesRealPkixChainAndRefusesRogueTamperedOrExpiredChains(): Unit {
        val validTimeMs = Instant.parse("2027-06-01T00:00:00Z").toEpochMilli()
        val verifier = assertNotNull(
            AppleStoreJwsVerifier.fromConfig(
                config = StoreEventsConfig(
                    trustUnverified = false,
                    appleRootPem = TEST_ROOT_PEM,
                    appleRootPath = "",
                ),
                expectedBundleIds = setOf("app.qoloa.ios"),
                validationClock = { validTimeMs },
            ),
        )

        val fullChainJws = signStoreJws(
            x5cPems = listOf(TEST_LEAF_PEM, TEST_INTERMEDIATE_PEM, TEST_ROOT_PEM),
            payloadJson = """{"notificationType":"SUBSCRIBED","data":{"bundleId":"app.qoloa.ios","environment":"Production"}}""",
        )
        val decoded = assertNotNull(verifier.verify(fullChainJws))
        assertEquals("SUBSCRIBED", (decoded["notificationType"] as JsonPrimitive).content)

        // Chain omitting the trailing self-signed root (leaf + intermediate) also validates against TrustAnchor.
        val twoCertJws = signStoreJws(
            x5cPems = listOf(TEST_LEAF_PEM, TEST_INTERMEDIATE_PEM),
            payloadJson = """{"bundleId":"app.qoloa.ios","transactionId":"20000001"}""",
        )
        assertNotNull(verifier.verify(twoCertJws))

        // Wrong bundleId is rejected.
        val wrongBundleJws = signStoreJws(
            x5cPems = listOf(TEST_LEAF_PEM, TEST_INTERMEDIATE_PEM, TEST_ROOT_PEM),
            payloadJson = """{"bundleId":"app.other.ios","transactionId":"20000001"}""",
        )
        assertNull(verifier.verify(wrongBundleJws))

        // Tampered payload bytes with valid chain are rejected.
        val segments = fullChainJws.split('.')
        val tamperedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"bundleId":"app.qoloa.ios","tampered":true}""".toByteArray(Charsets.UTF_8))
        assertNull(verifier.verify("${segments[0]}.$tamperedPayload.${segments[2]}"))

        // Verifier anchored on a different Root CA rejects the chain.
        val rogueAnchored = AppleStoreJwsVerifier(
            trustedRootCertificates = AppleStoreJwsVerifier.parseX509Certificates(ROGUE_ROOT_PEM),
            expectedBundleIds = setOf("app.qoloa.ios"),
            validationClock = { validTimeMs },
        )
        assertNull(rogueAnchored.verify(fullChainJws))

        // Expired certificate chain (clock past 2036 notAfter) is rejected by PKIX.
        val expiredClockVerifier = AppleStoreJwsVerifier(
            trustedRootCertificates = AppleStoreJwsVerifier.parseX509Certificates(TEST_ROOT_PEM),
            expectedBundleIds = setOf("app.qoloa.ios"),
            validationClock = { Instant.parse("2040-01-01T00:00:00Z").toEpochMilli() },
        )
        assertNull(expiredClockVerifier.verify(fullChainJws))

        // Unconfigured StoreEventsConfig fails closed (returns null) and file-backed config loads cleanly.
        assertNull(AppleStoreJwsVerifier.fromConfig(StoreEventsConfig(false, "", "")))
        val tmpRoot = Files.createTempFile("apple-root-", ".pem")
        try {
            Files.writeString(tmpRoot, TEST_ROOT_PEM)
            val fromFile = assertNotNull(
                AppleStoreJwsVerifier.fromConfig(
                    StoreEventsConfig(false, "", tmpRoot.toAbsolutePath().toString()),
                    expectedBundleIds = setOf("app.qoloa.ios"),
                    validationClock = { validTimeMs },
                ),
            )
            assertNotNull(fromFile.verify(fullChainJws))
        } finally {
            Files.deleteIfExists(tmpRoot)
        }
    }

    private fun signStoreJws(x5cPems: List<String>, payloadJson: String): String {
        val x5cElements = x5cPems.map { pem ->
            pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .filterNot { it.isWhitespace() }
        }
        val headerJson = buildJsonObject {
            put("alg", "ES256")
            put("x5c", JsonArray(x5cElements.map(::JsonPrimitive)))
        }.toString()

        val keyBody = TEST_LEAF_KEY_PEM
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot { it.isWhitespace() }
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyBody)))

        val hSeg = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.toByteArray(Charsets.UTF_8))
        val pSeg = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(privateKey)
            update("$hSeg.$pSeg".toByteArray(Charsets.US_ASCII))
            sign()
        }
        val sSeg = Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
        return "$hSeg.$pSeg.$sSeg"
    }

    private companion object {
        val TEST_ROOT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBoDCCAUWgAwIBAgIUf+w9bP+PZ8/RG9nB0GIQBW2TLiUwCgYIKoZIzj0EAwIw
            HTEbMBkGA1UEAwwSVGVzdCBBcHBsZSBSb290IENBMB4XDTI2MTExNTAxNTAzM1oX
            DTM2MTExMjAxNTAzM1owHTEbMBkGA1UEAwwSVGVzdCBBcHBsZSBSb290IENBMFkw
            EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8eTlxRhMzECt6UvVjIdkpCPm8GfomtP6
            5rRRcR1xKM/vNI/jrCEBZjUGARn4fUdAxw+9FWsFD5VEZt9kQUQWOaNjMGEwDwYD
            VR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFEcrjCena3XS
            2ITIElQyXi3iFOVgMB8GA1UdIwQYMBaAFEcrjCena3XS2ITIElQyXi3iFOVgMAoG
            CCqGSM49BAMCA0kAMEYCIQDOk5o8kfIgG6cBS7Br/s2F5xUycGwwOR6wceCqdwfT
            kgIhAJvEDnMNsaEtJN31/ObOn1YxYbOeWXPD3vkjf0OtRXYi
            -----END CERTIFICATE-----
        """.trimIndent()

        val TEST_INTERMEDIATE_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBqDCCAU+gAwIBAgIUYaQPJ8/N+9P2XDez7G9mWmY0EgcwCgYIKoZIzj0EAwIw
            HTEbMBkGA1UEAwwSVGVzdCBBcHBsZSBSb290IENBMB4XDTI2MTExNTAxNTAzM1oX
            DTM2MTExMjAxNTAzM1owJzElMCMGA1UEAwwcVGVzdCBBcHBsZSBXV0RSIEludGVy
            bWVkaWF0ZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDbSWxgTMlkVQteN7ic4
            PO3j0W4LPv4WVb2g736uhuQahYw20tmCQ+PQT43aDAk6rwq2Nj2CWVM8w+1QPSsq
            GSSjYzBhMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMB0GA1UdDgQW
            BBRIwlivcGzdlgLHFWUTpsBCjD0kGTAfBgNVHSMEGDAWgBRHK4wnp2t10tiEyBJU
            Ml4t4hTlYDAKBggqhkjOPQQDAgNHADBEAiBnAOeydRMpn0T2i0UWC85blU6beccl
            AKXf2+hHJv5IKAIgHqVM0I2fhfdO5ZRfBJwqfIolu6nBbRsG6ZWAmBy43Ks=
            -----END CERTIFICATE-----
        """.trimIndent()

        val TEST_LEAF_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBqDCCAU+gAwIBAgIUOXPnZQsgWeVB5+119wjH+IKV/nEwCgYIKoZIzj0EAwIw
            JzElMCMGA1UEAwwcVGVzdCBBcHBsZSBXV0RSIEludGVybWVkaWF0ZTAeFw0yNjEx
            MTUwMTUwMzNaFw0zNjExMTIwMTUwMzNaMCAxHjAcBgNVBAMMFVRlc3QgQXBwIFN0
            b3JlIFNpZ25lcjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDfO13dcYaowRy+X
            6Bu38aty7U4QMLlIeYwK2fQ6E54XTo6Rpde4ExUBeXOiCswIDK5aAeZyq4vVTEqh
            4Dtojh2jYDBeMAwGA1UdEwEB/wQCMAAwDgYDVR0PAQH/BAQDAgeAMB0GA1UdDgQW
            BBQY+HRk9rGT6Xpbh5JWlW3MbneLnzAfBgNVHSMEGDAWgBRIwlivcGzdlgLHFWUT
            psBCjD0kGTAKBggqhkjOPQQDAgNHADBEAiB92eIS4j1/+6Czxf2IGIf07k100sx1
            XT5dbJfOC8wqOwIgWsi43SIoktr1spCTUO++qYuVTpGnaPsiJF4P9WaXqyc=
            -----END CERTIFICATE-----
        """.trimIndent()

        val TEST_LEAF_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg8MeIn5Og5pDP+Ov6
            REzu6rxE/uCSH+I0Gn5BFpmUwLmhRANCAAQ3ztd3XGGqMEcvl+gbt/Grcu1OEDC5
            SHmMCtn0OhOeF06OkaXXuBMVAXlzogrMCAyuWgHmcquL1UxKoeA7aI4d
            -----END PRIVATE KEY-----
        """.trimIndent()

        val ROGUE_ROOT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBlTCCATugAwIBAgIUUNS7NInaIOHW1dxtFsdTQiXK4HUwCgYIKoZIzj0EAwIw
            GDEWMBQGA1UEAwwNUm9ndWUgUm9vdCBDQTAeFw0yNjExMTUwMTUwMzNaFw0zNjEx
            MTIwMTUwMzNaMBgxFjAUBgNVBAMMDVJvZ3VlIFJvb3QgQ0EwWTATBgcqhkjOPQIB
            BggqhkjOPQMBBwNCAATclm7sqaDXPwQqdspjXY1wbyNv66GbcUaboPiDugLpIk/e
            rxl9w85UypjGlOeKQF7Sei26HTr4XF0E3vGmUGBOo2MwYTAPBgNVHRMBAf8EBTAD
            AQH/MA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQUUWijsMFRaPImZnbNUXIu1O6o
            MKAwHwYDVR0jBBgwFoAUUWijsMFRaPImZnbNUXIu1O6oMKAwCgYIKoZIzj0EAwID
            SAAwRQIgNgOfJ58KYbmPwJeujBD3Q+9Od34gaGlB94zG6KdoSYACIQCVF+oGY/6I
            RZMt+4dF+b6yCskLQtccRL0qY1wrMSLhyQ==
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
