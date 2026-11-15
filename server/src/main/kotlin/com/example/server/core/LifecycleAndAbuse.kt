package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** Verified Cloudflare Turnstile challenge metadata. */
data class TurnstileVerification(
    val challengeTs: String? = null,
    val hostname: String? = null,
    val action: String? = null,
)

/**
 * Bot-protection gate placed in front of checkout-session creation, free-trial
 * grants, and email sign-in code issuance so card-testing bots cannot run up
 * fraud-screening fees or dispute ratios.
 */
fun interface TurnstileVerifier {
    suspend fun verify(responseToken: String, remoteIp: String?): TurnstileVerification?
}

/** Default production guard before a Turnstile secret key is configured. */
object FailClosedTurnstile : TurnstileVerifier {
    override suspend fun verify(responseToken: String, remoteIp: String?): TurnstileVerification? = null
}

/**
 * Verifies single-use Cloudflare Turnstile tokens against `/turnstile/v0/siteverify`,
 * optionally pinning [expectedHostname] and [expectedAction] so tokens solved on
 * one widget or host cannot be replayed against another endpoint.
 */
class CloudflareTurnstileVerifier(
    private val httpClient: HttpClient,
    private val secretKey: String,
    private val expectedHostname: String? = null,
    private val expectedAction: String? = null,
    private val verifyUrl: String = DEFAULT_VERIFY_URL,
) : TurnstileVerifier {

    init {
        require(secretKey.isNotBlank()) { "Turnstile secretKey must not be blank" }
        require(verifyUrl.isNotBlank()) { "Turnstile verifyUrl must not be blank" }
    }

    override suspend fun verify(responseToken: String, remoteIp: String?): TurnstileVerification? {
        val token = responseToken.trim()
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) return null

        val response = runCatching {
            httpClient.submitForm(
                url = verifyUrl,
                formParameters = Parameters.build {
                    append("secret", secretKey)
                    append("response", token)
                    remoteIp?.trim()?.takeIf { it.isNotEmpty() }?.let { append("remoteip", it) }
                },
            )
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null

        val bodyObj = runCatching {
            lifecycleJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }.getOrNull() ?: return null

        val success = (bodyObj["success"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.booleanOrNull == true
        if (!success) return null

        val hostname = (bodyObj["hostname"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val action = (bodyObj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val challengeTs = (bodyObj["challenge_ts"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        if (expectedHostname != null && hostname != expectedHostname) return null
        if (expectedAction != null && action != expectedAction) return null

        return TurnstileVerification(
            challengeTs = challengeTs,
            hostname = hostname,
            action = action,
        )
    }

    companion object {
        const val MAX_TOKEN_LENGTH: Int = 2048
        const val DEFAULT_VERIFY_URL: String = "https://challenges.cloudflare.com/turnstile/v0/siteverify"
    }
}

/**
 * Remote capability and build-gate envelope served from `/config` or `/v1/me`
 * so mobile and desktop clients can retire broken builds, pause purchases, or
 * toggle upstream connectors without waiting on App Store or Play review.
 */
@Serializable
data class ClientCapabilities(
    val minSupportedBuild: Int,
    val recommendedBuild: Int = minSupportedBuild,
    val signInEnabled: Boolean = true,
    val purchaseEnabled: Boolean = true,
    val features: Map<String, Boolean> = emptyMap(),
) {
    init {
        require(minSupportedBuild >= 1) { "minSupportedBuild must be >= 1" }
        require(recommendedBuild >= minSupportedBuild) {
            "recommendedBuild ($recommendedBuild) must be >= minSupportedBuild ($minSupportedBuild)"
        }
    }

    fun isBuildSupported(clientBuild: Int): Boolean = clientBuild >= minSupportedBuild

    fun isUpdateRecommended(clientBuild: Int): Boolean =
        clientBuild in minSupportedBuild until recommendedBuild

    fun isFeatureEnabled(flag: String): Boolean = features[flag] == true
}

/** Summary returned by [AccountDeletionService.deleteAccount]. */
data class AccountDeletionReport(
    val canonicalOwnerId: String,
    val appleTokenRevoked: Boolean,
    val deletedAt: Long,
)

/**
 * Coordinates complete first-party account deletion (App Store Review Guideline
 * `5.1.1(v)` and Google Play User Data deletion policy).
 *
 * Execution order is intentionally retry-safe:
 * 1. Revoke external Sign in with Apple credentials first (failing fast before
 *    local identity links disappear if Apple's revocation endpoint is unreachable).
 * 2. Revoke every active session token for both the presented and canonical owner.
 * 3. Purge first-party documents, credits, entitlements, AI usage logs, and any
 *    product-domain tables via `deleteProductData`.
 * 4. Unlink `(issuer, subject)` OIDC bindings last so a retried deletion after a
 *    transient failure still resolves the canonical owner.
 */
class AccountDeletionService(
    private val sessions: SessionStore,
    private val identities: IdentityStore,
    private val documents: DocumentStore? = null,
    private val entitlements: EntitlementStore? = null,
    private val credits: CreditBalanceStore? = null,
    private val aiUsage: AiUsageStore? = null,
    private val appleRevoker: AppleTokenRevoker = NoOpAppleTokenRevoker,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun deleteAccount(
        ownerId: String,
        appleToken: String? = null,
        appleTokenHint: AppleTokenTypeHint = AppleTokenTypeHint.REFRESH_TOKEN,
        deleteProductData: suspend (canonicalOwnerId: String) -> Unit = {},
    ): AccountDeletionReport {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        val canonical = identities.canonicalOwner(ownerId)
        val trimmedApple = appleToken?.trim()?.takeIf { it.isNotEmpty() }
        val appleRevoked = if (trimmedApple != null) {
            val ok = appleRevoker.revoke(trimmedApple, appleTokenHint)
            check(ok) { "Apple token revocation failed for owner $canonical" }
            true
        } else {
            false
        }

        val now = clock()
        sessions.revokeAll(canonical, now)
        if (ownerId != canonical) {
            sessions.revokeAll(ownerId, now)
        }

        documents?.deleteAllForOwner(canonical)
        if (ownerId != canonical) documents?.deleteAllForOwner(ownerId)

        credits?.deleteAllForOwner(canonical)
        if (ownerId != canonical) credits?.deleteAllForOwner(ownerId)

        entitlements?.deleteAllForOwner(canonical)
        if (ownerId != canonical) entitlements?.deleteAllForOwner(ownerId)

        aiUsage?.deleteAllForOwner(canonical)
        if (ownerId != canonical) aiUsage?.deleteAllForOwner(ownerId)

        deleteProductData(canonical)
        identities.unlinkAll(canonical)

        return AccountDeletionReport(
            canonicalOwnerId = canonical,
            appleTokenRevoked = appleRevoked,
            deletedAt = now,
        )
    }
}

private val lifecycleJson = Json { ignoreUnknownKeys = true }
