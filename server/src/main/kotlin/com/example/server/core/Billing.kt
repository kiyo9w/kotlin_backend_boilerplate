package com.example.server.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Normalized lifecycle states across Polar, Stripe, Apple StoreKit 2, and Google Play Billing. */
enum class SubscriptionStatus {
    TRIALING,
    ACTIVE,
    PAST_DUE,
    GRACE_PERIOD,
    CANCELED,
    EXPIRED,
    REVOKED,
}

/** Mutation payload passed to [EntitlementStore.applyWebhookEvent]. */
data class EntitlementUpsert(
    val externalSubId: String,
    val ownerId: String,
    val planCode: String,
    val status: SubscriptionStatus,
    val quantity: Int = 1,
    val currentPeriodEnd: Long,
    val graceUntil: Long? = null,
) {
    init {
        require(externalSubId.isNotBlank()) { "externalSubId must not be blank" }
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(planCode.isNotBlank()) { "planCode must not be blank" }
        require(quantity > 0) { "quantity must be positive" }
    }
}

/** Persisted first-party subscription state (`V8__commercial_backbone.sql`). */
data class EntitlementRow(
    val provider: String,
    val externalSubId: String,
    val ownerId: String,
    val planCode: String,
    val status: SubscriptionStatus,
    val quantity: Int,
    val currentPeriodEnd: Long,
    val graceUntil: Long?,
    val lastEventId: String,
    val updatedAt: Long,
) {
    /**
     * Whether this subscription grants access at [now].
     *
     * - `TRIALING`, `ACTIVE`, and `CANCELED` grant access until [currentPeriodEnd]
     *   (canceling auto-renew never strips already-paid time).
     * - `PAST_DUE` and `GRACE_PERIOD` grant access until `graceUntil ?: currentPeriodEnd`.
     * - `REVOKED` (refund or Family Sharing removal) and `EXPIRED` cut access immediately.
     */
    fun isEntitledAt(now: Long): Boolean = when (status) {
        SubscriptionStatus.TRIALING,
        SubscriptionStatus.ACTIVE,
        SubscriptionStatus.CANCELED -> now < currentPeriodEnd
        SubscriptionStatus.PAST_DUE,
        SubscriptionStatus.GRACE_PERIOD -> now < (graceUntil ?: currentPeriodEnd)
        SubscriptionStatus.EXPIRED,
        SubscriptionStatus.REVOKED -> false
    }
}

/** Whether a webhook event mutated state or was skipped because `(provider, eventId)` already landed. */
enum class WebhookApplyOutcome {
    APPLIED,
    DUPLICATE,
}

/**
 * First-party subscription ledger paired with atomic `(provider, eventId)`
 * webhook deduplication.
 *
 * Plan state changes only from verified provider events, never from a client
 * redirect after checkout. Passing `identityStore::canonicalOwner` as the owner
 * resolver ensures renewal webhooks carrying an older guest `appAccountToken`
 * or `obfuscatedAccountId` resolve to the merged canonical owner.
 */
interface EntitlementStore {
    /**
     * Record `(provider, eventId)` and apply [upsert] (when non-null) atomically.
     * Replays of the same `(provider, eventId)` return [WebhookApplyOutcome.DUPLICATE]
     * without overwriting newer state.
     */
    suspend fun applyWebhookEvent(
        provider: String,
        eventId: String,
        now: Long = System.currentTimeMillis(),
        upsert: EntitlementUpsert? = null,
    ): WebhookApplyOutcome

    /** Every subscription row for [ownerId] that grants access at [now]. */
    suspend fun activeForOwner(ownerId: String, now: Long = System.currentTimeMillis()): List<EntitlementRow>

    /** Every subscription row for [ownerId], active or historical. */
    suspend fun allForOwner(ownerId: String): List<EntitlementRow>

    /** Remove subscription rows for [ownerId] during account deletion. */
    suspend fun deleteAllForOwner(ownerId: String)
}

/** In-memory [EntitlementStore] for local-only mode and fast unit tests. */
class MemoryEntitlementStore(
    private val canonicalOwner: suspend (String) -> String = { it },
) : EntitlementStore {

    private data class EventKey(val provider: String, val eventId: String)
    private data class SubKey(val provider: String, val externalSubId: String)

    private val lock = Any()
    private val seenEvents = HashSet<EventKey>()
    private val rows = LinkedHashMap<SubKey, EntitlementRow>()

    override suspend fun applyWebhookEvent(
        provider: String,
        eventId: String,
        now: Long,
        upsert: EntitlementUpsert?,
    ): WebhookApplyOutcome {
        val cleanProvider = provider.trim().also { require(it.isNotEmpty()) { "provider must not be blank" } }
        val cleanEventId = eventId.trim().also { require(it.isNotEmpty()) { "eventId must not be blank" } }
        val resolvedOwner = upsert?.let { canonicalOwner(it.ownerId) }
        synchronized(lock) {
            if (!seenEvents.add(EventKey(cleanProvider, cleanEventId))) {
                return WebhookApplyOutcome.DUPLICATE
            }
            if (upsert != null && resolvedOwner != null) {
                rows[SubKey(cleanProvider, upsert.externalSubId)] = EntitlementRow(
                    provider = cleanProvider,
                    externalSubId = upsert.externalSubId,
                    ownerId = resolvedOwner,
                    planCode = upsert.planCode,
                    status = upsert.status,
                    quantity = upsert.quantity,
                    currentPeriodEnd = upsert.currentPeriodEnd,
                    graceUntil = upsert.graceUntil,
                    lastEventId = cleanEventId,
                    updatedAt = now,
                )
            }
            return WebhookApplyOutcome.APPLIED
        }
    }

    override suspend fun activeForOwner(ownerId: String, now: Long): List<EntitlementRow> {
        val resolved = canonicalOwner(ownerId)
        return synchronized(lock) {
            rows.values.filter { it.ownerId == resolved && it.isEntitledAt(now) }
        }
    }

    override suspend fun allForOwner(ownerId: String): List<EntitlementRow> {
        val resolved = canonicalOwner(ownerId)
        return synchronized(lock) {
            rows.values.filter { it.ownerId == resolved }
        }
    }

    override suspend fun deleteAllForOwner(ownerId: String) {
        val resolved = canonicalOwner(ownerId)
        synchronized(lock) {
            rows.entries.removeIf { it.value.ownerId == resolved || it.value.ownerId == ownerId }
        }
    }
}

/** One granted lot of prepaid or plan-included credits (`V8__commercial_backbone.sql`). */
data class CreditGrantRow(
    val id: String,
    val ownerId: String,
    val sourceRef: String,
    val grantedUnits: Long,
    val remainingUnits: Long,
    val expiresAt: Long?,
    val createdAt: Long,
)

/** Outcome of granting a credit lot keyed by an idempotent [sourceRef] (order/transaction id). */
enum class CreditGrantOutcome {
    GRANTED,
    DUPLICATE,
}

/**
 * First-party credit balance ledger for hybrid subscription + top-up AI products.
 *
 * Purchased top-up packs can carry a 180-day `expiresAt` ceiling so Japanese sales
 * stay outside prepaid-instrument registration (`資金決済法`). [trySpend] drains
 * earliest-expiring live lots first before touching non-expiring promotional grants,
 * and never partially deducts when total live balance falls short.
 */
interface CreditBalanceStore {
    suspend fun grant(
        ownerId: String,
        sourceRef: String,
        units: Long,
        expiresAt: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): CreditGrantOutcome

    suspend fun available(ownerId: String, now: Long = System.currentTimeMillis()): Long

    suspend fun trySpend(
        ownerId: String,
        units: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean

    suspend fun deleteAllForOwner(ownerId: String)
}

/** In-memory [CreditBalanceStore] for local-only mode and fast unit tests. */
class MemoryCreditBalanceStore : CreditBalanceStore {

    private val lock = Any()
    private val grantsByRef = LinkedHashMap<String, CreditGrantRow>()

    override suspend fun grant(
        ownerId: String,
        sourceRef: String,
        units: Long,
        expiresAt: Long?,
        now: Long,
    ): CreditGrantOutcome {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(sourceRef.isNotBlank()) { "sourceRef must not be blank" }
        require(units > 0L) { "units must be positive" }
        synchronized(lock) {
            if (sourceRef in grantsByRef) return CreditGrantOutcome.DUPLICATE
            grantsByRef[sourceRef] = CreditGrantRow(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                sourceRef = sourceRef,
                grantedUnits = units,
                remainingUnits = units,
                expiresAt = expiresAt,
                createdAt = now,
            )
            return CreditGrantOutcome.GRANTED
        }
    }

    override suspend fun available(ownerId: String, now: Long): Long = synchronized(lock) {
        grantsByRef.values
            .filter { it.ownerId == ownerId && it.isLiveAt(now) }
            .sumOf { it.remainingUnits }
    }

    override suspend fun trySpend(ownerId: String, units: Long, now: Long): Boolean {
        require(units > 0L) { "units must be positive" }
        synchronized(lock) {
            val live = grantsByRef.values
                .filter { it.ownerId == ownerId && it.isLiveAt(now) }
                .sortedWith(creditDrainOrder)
            if (live.sumOf { it.remainingUnits } < units) return false

            var remainingToSpend = units
            for (lot in live) {
                if (remainingToSpend == 0L) break
                val deduct = minOf(remainingToSpend, lot.remainingUnits)
                grantsByRef[lot.sourceRef] = lot.copy(remainingUnits = lot.remainingUnits - deduct)
                remainingToSpend -= deduct
            }
            return true
        }
    }

    override suspend fun deleteAllForOwner(ownerId: String) {
        synchronized(lock) {
            grantsByRef.entries.removeIf { it.value.ownerId == ownerId }
        }
    }
}

internal fun CreditGrantRow.isLiveAt(now: Long): Boolean =
    remainingUnits > 0L && (expiresAt == null || expiresAt > now)

internal val creditDrainOrder: Comparator<CreditGrantRow> = compareBy(
    { it.expiresAt == null },
    { it.expiresAt ?: Long.MAX_VALUE },
    { it.createdAt },
    { it.id },
)

/**
 * Verifies Apple App Store Server Notifications V2 and StoreKit 2 `signedPayload` /
 * `signedTransactionInfo` JWS strings against pinned Apple Root CA certificate(s)
 * (`APPLE_ROOT_CA_PEM` / `APPLE_ROOT_CA_PATH` in [StoreEventsConfig]).
 *
 * Validates the `x5c` X.509 certificate path via PKIX, verifies the `ES256`
 * signature with the leaf certificate's P-256 key, and enforces [expectedBundleIds]
 * when supplied so cross-app notifications are rejected.
 */
class AppleStoreJwsVerifier(
    trustedRootCertificates: Set<X509Certificate>,
    private val expectedBundleIds: Set<String> = emptySet(),
    private val validationClock: (() -> Long)? = System::currentTimeMillis,
) {
    private val trustAnchors: Set<TrustAnchor>
    private val trustAnchorEncoded: Set<ByteBuffer>

    init {
        require(trustedRootCertificates.isNotEmpty()) { "trustedRootCertificates must not be empty" }
        trustAnchors = trustedRootCertificates.map { TrustAnchor(it, null) }.toSet()
        trustAnchorEncoded = trustedRootCertificates.map { ByteBuffer.wrap(it.encoded) }.toSet()
    }

    /** Return the verified JWS payload [JsonObject], or `null` when signature, chain, or bundle fails. */
    fun verify(signedPayload: String): JsonObject? {
        val parts = signedPayload.trim().split('.')
        if (parts.size != 3) return null
        val header = decodeJwsJson(parts[0]) ?: return null
        val payload = decodeJwsJson(parts[1]) ?: return null
        val signature = decodeJwsBase64Url(parts[2])?.takeIf { it.size == ES256_SIGNATURE_BYTES } ?: return null

        if (header.stringField("alg") != "ES256") return null
        val x5c = header["x5c"] as? JsonArray ?: return null
        if (x5c.size < 2) return null

        val certFactory = CertificateFactory.getInstance("X.509")
        val certs = x5c.map { element ->
            val b64 = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val der = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
            runCatching {
                certFactory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            }.getOrNull() ?: return null
        }

        val pathCerts = certs.dropLastWhile { ByteBuffer.wrap(it.encoded) in trustAnchorEncoded }
        if (pathCerts.isEmpty()) return null

        val chainValid = runCatching {
            val certPath = certFactory.generateCertPath(pathCerts)
            val params = PKIXParameters(trustAnchors).apply {
                isRevocationEnabled = false
                validationClock?.let { nowFn -> date = Date(nowFn()) }
            }
            CertPathValidator.getInstance("PKIX").validate(certPath, params)
            true
        }.getOrDefault(false)
        if (!chainValid) return null

        val leafKey = certs[0].publicKey
        if (leafKey !is ECPublicKey || leafKey.params.order.bitLength() != 256) return null

        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        val sigOk = runCatching {
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(leafKey)
                update(signingInput)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!sigOk) return null

        if (expectedBundleIds.isNotEmpty()) {
            val bundleId = payload.stringField("bundleId")
                ?: (payload["data"] as? JsonObject)?.stringField("bundleId")
                ?: return null
            if (bundleId !in expectedBundleIds) return null
        }
        return payload
    }

    companion object {
        private const val ES256_SIGNATURE_BYTES = 64

        /**
         * Build a verifier from [StoreEventsConfig], loading PEM root certificates
         * from `appleRootPem` or `appleRootPath`. Returns `null` when neither is
         * populated so unconfigured environments fail closed.
         */
        fun fromConfig(
            config: StoreEventsConfig,
            expectedBundleIds: Set<String> = emptySet(),
            validationClock: (() -> Long)? = System::currentTimeMillis,
        ): AppleStoreJwsVerifier? {
            val pemText = when {
                config.appleRootPem.isNotBlank() -> config.appleRootPem
                config.appleRootPath.isNotBlank() -> {
                    val file = File(config.appleRootPath)
                    if (!file.isFile) return null
                    file.readText(Charsets.UTF_8)
                }
                else -> return null
            }
            val roots = parseX509Certificates(pemText)
            if (roots.isEmpty()) return null
            return AppleStoreJwsVerifier(roots, expectedBundleIds, validationClock)
        }

        fun parseX509Certificates(pem: String): Set<X509Certificate> {
            val trimmed = pem.trim()
            if (trimmed.isEmpty()) return emptySet()
            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificates(ByteArrayInputStream(trimmed.toByteArray(Charsets.UTF_8)))
                .filterIsInstance<X509Certificate>()
                .toSet()
        }
    }
}

private val billingJson = Json { ignoreUnknownKeys = true }

private fun decodeJwsBase64Url(segment: String): ByteArray? =
    runCatching { Base64.getUrlDecoder().decode(segment) }.getOrNull()

private fun decodeJwsJson(segment: String): JsonObject? {
    val bytes = decodeJwsBase64Url(segment)?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        billingJson.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    }.getOrNull()
}

private fun JsonObject.stringField(name: String): String? {
    val prim = this[name] as? JsonPrimitive ?: return null
    return if (prim.isString) prim.content else null
}
