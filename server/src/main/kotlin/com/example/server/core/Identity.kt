package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Verified external identity extracted from an OIDC ID token.
 *
 * [emailVerified] is true only when [email] is non-blank and the provider
 * asserted verification; callers must still bind accounts on ([issuer], [subject])
 * rather than raw email strings.
 */
data class VerifiedIdentity(
    val issuer: String,
    val subject: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
) {
    init {
        require(issuer.isNotBlank()) { "issuer must not be blank" }
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(!emailVerified || !email.isNullOrBlank()) { "emailVerified requires a non-blank email" }
    }
}

/**
 * Verifies a raw OIDC ID token (Sign in with Apple, Google Credential Manager,
 * LINE Login with `id_token_key_type=JWK`, or an internal JWKS issuer) and
 * returns a [VerifiedIdentity] only when signature and claims hold.
 */
fun interface IdentityVerifier {
    suspend fun verify(idToken: String): VerifiedIdentity?
}

/** Default production guard before provider keys are configured: refuses every token. */
object FailClosedIdentities : IdentityVerifier {
    override suspend fun verify(idToken: String): VerifiedIdentity? = null
}

/** One public JWK entry from a provider's JWKS document. */
@Serializable
data class JwkKey(
    val kty: String,
    val kid: String? = null,
    val use: String? = null,
    val alg: String? = null,
    val crv: String? = null,
    val n: String? = null,
    val e: String? = null,
    val x: String? = null,
    val y: String? = null,
)

/** Supplies public keys for [JwksIdentityVerifier]. */
fun interface JwksSource {
    suspend fun keys(forceRefresh: Boolean): List<JwkKey>
}

/** Fixed in-memory JWKS for deterministic tests and pinned internal issuers. */
class StaticJwksSource(private val keys: List<JwkKey>) : JwksSource {
    override suspend fun keys(forceRefresh: Boolean): List<JwkKey> = keys
}

/**
 * Fetches and caches a remote `jwks_uri` document.
 *
 * On an unknown `kid`, [JwksIdentityVerifier] asks for `forceRefresh = true`;
 * [minRefreshIntervalMs] caps how often that path hits the network so random
 * `kid` tokens cannot turn into upstream floods, and [mutex] collapses
 * concurrent refreshes during a real provider key rotation into one fetch.
 */
class HttpJwksSource(
    private val httpClient: HttpClient,
    private val jwksUrl: String,
    private val cacheTtlMs: Long = 3_600_000L,
    private val minRefreshIntervalMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : JwksSource {

    private val mutex = Mutex()
    private var cachedKeys: List<JwkKey> = emptyList()
    private var fetchedAtMs: Long = Long.MIN_VALUE

    override suspend fun keys(forceRefresh: Boolean): List<JwkKey> = mutex.withLock {
        val now = clock()
        val hasCache = fetchedAtMs != Long.MIN_VALUE
        val fresh = hasCache && (now - fetchedAtMs) < cacheTtlMs
        val cooledDown = !hasCache || (now - fetchedAtMs) >= minRefreshIntervalMs
        if (fresh && (!forceRefresh || !cooledDown)) {
            return@withLock cachedKeys
        }
        val response = runCatching { httpClient.get(jwksUrl) }.getOrNull()
        if (response == null || !response.status.isSuccess()) {
            return@withLock cachedKeys
        }
        val parsed = runCatching {
            jwksJson.decodeFromString(JwksDocument.serializer(), response.bodyAsText()).keys
        }.getOrNull() ?: return@withLock cachedKeys
        cachedKeys = parsed
        fetchedAtMs = now
        cachedKeys
    }

    @Serializable
    private data class JwksDocument(
        @SerialName("keys") val keys: List<JwkKey> = emptyList(),
    )
}

/**
 * Standard-library OIDC JWS verifier supporting `RS256` and `ES256` (`P-256`).
 *
 * Enforces algorithm-to-key-type pinning (never `none` or symmetric `HS*`),
 * exact issuer membership, audience intersection, optional authorized-party
 * (`azp`) allowlisting for multi-client providers such as Google, and clock-skew
 * bounded `exp` / `nbf` / `iat` checks.
 */
class JwksIdentityVerifier(
    private val jwks: JwksSource,
    val allowedIssuers: Set<String>,
    private val allowedAudiences: Set<String>,
    private val allowedAuthorizedParties: Set<String> = emptySet(),
    private val clockSkewSeconds: Long = 60L,
    private val clock: () -> Long = System::currentTimeMillis,
) : IdentityVerifier {

    init {
        require(allowedIssuers.isNotEmpty()) { "allowedIssuers must not be empty" }
        require(allowedAudiences.isNotEmpty()) { "allowedAudiences must not be empty" }
        require(clockSkewSeconds >= 0) { "clockSkewSeconds must be non-negative" }
    }

    override suspend fun verify(idToken: String): VerifiedIdentity? {
        val parts = idToken.trim().split('.')
        if (parts.size != 3) return null
        val headerObj = decodeJsonObject(parts[0]) ?: return null
        val payloadObj = decodeJsonObject(parts[1]) ?: return null
        val signatureBytes = decodeBase64Url(parts[2])?.takeIf { it.isNotEmpty() } ?: return null

        val alg = headerObj.stringClaim("alg") ?: return null
        if (alg != "RS256" && alg != "ES256") return null
        val kid = headerObj.stringClaim("kid")

        val issuer = payloadObj.stringClaim("iss")?.takeIf { it in allowedIssuers } ?: return null
        val subject = payloadObj.stringClaim("sub")?.takeIf { it.isNotBlank() } ?: return null
        val audiences = payloadObj.audienceClaims() ?: return null
        if (audiences.none { it in allowedAudiences }) return null

        val azp = payloadObj.stringClaim("azp")
        if (allowedAuthorizedParties.isNotEmpty()) {
            if (azp == null || azp !in allowedAuthorizedParties) return null
        } else if (audiences.size > 1 && azp != null && azp !in allowedAudiences) {
            return null
        }

        val nowSeconds = clock() / 1_000L
        val exp = payloadObj.longClaimStrict("exp") ?: return null
        if (nowSeconds >= exp + clockSkewSeconds) return null
        if ("nbf" in payloadObj) {
            val nbf = payloadObj.longClaimStrict("nbf") ?: return null
            if (nowSeconds < nbf - clockSkewSeconds) return null
        }
        if ("iat" in payloadObj) {
            val iat = payloadObj.longClaimStrict("iat") ?: return null
            if (iat > nowSeconds + clockSkewSeconds) return null
        }

        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        if (!verifyWithJwks(alg, kid, signingInput, signatureBytes)) return null

        val rawEmail = payloadObj.stringClaim("email")?.trim()?.takeIf { it.isNotEmpty() }
        val emailVerified = rawEmail != null && payloadObj.verifiedBooleanClaim("email_verified")
        return VerifiedIdentity(
            issuer = issuer,
            subject = subject,
            email = rawEmail,
            emailVerified = emailVerified,
        )
    }

    private suspend fun verifyWithJwks(
        alg: String,
        kid: String?,
        signingInput: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        val initial = matchingKeys(jwks.keys(forceRefresh = false), alg, kid)
        if (initial.any { verifySignature(it, alg, signingInput, signatureBytes) }) return true
        if (initial.isNotEmpty()) return false
        val refreshed = matchingKeys(jwks.keys(forceRefresh = true), alg, kid)
        return refreshed.any { verifySignature(it, alg, signingInput, signatureBytes) }
    }

    private fun matchingKeys(keys: List<JwkKey>, alg: String, kid: String?): List<JwkKey> =
        keys.filter { key ->
            (key.use == null || key.use == "sig") &&
                (key.alg == null || key.alg == alg) &&
                (kid == null || key.kid == kid) &&
                when (alg) {
                    "RS256" -> key.kty == "RSA"
                    "ES256" -> key.kty == "EC" && key.crv == "P-256"
                    else -> false
                }
        }

    private fun verifySignature(
        key: JwkKey,
        alg: String,
        signingInput: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean = runCatching {
        when (alg) {
            "RS256" -> {
                val nBytes = decodeBase64Url(key.n ?: return false) ?: return false
                val eBytes = decodeBase64Url(key.e ?: return false) ?: return false
                val modulus = BigInteger(1, nBytes)
                val exponent = BigInteger(1, eBytes)
                if (modulus.bitLength() < MIN_RSA_BITS || exponent <= BigInteger.ONE) return false
                val publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(RSAPublicKeySpec(modulus, exponent))
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(publicKey)
                    update(signingInput)
                    verify(signatureBytes)
                }
            }
            "ES256" -> {
                if (signatureBytes.size != ES256_SIGNATURE_BYTES) return false
                val xBytes = decodeBase64Url(key.x ?: return false) ?: return false
                val yBytes = decodeBase64Url(key.y ?: return false) ?: return false
                if (xBytes.size != P256_COORD_BYTES || yBytes.size != P256_COORD_BYTES) return false
                val point = ECPoint(BigInteger(1, xBytes), BigInteger(1, yBytes))
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(ECPublicKeySpec(point, p256Spec))
                Signature.getInstance("SHA256withECDSAinP1363Format").run {
                    initVerify(publicKey)
                    update(signingInput)
                    verify(signatureBytes)
                }
            }
            else -> false
        }
    }.getOrDefault(false)

    private companion object {
        const val MIN_RSA_BITS = 2048
        const val P256_COORD_BYTES = 32
        const val ES256_SIGNATURE_BYTES = 64

        val p256Spec: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
        }
    }
}

/**
 * Routes a token across provider-specific [JwksIdentityVerifier] instances by
 * matching the unverified `iss` claim first so an Apple token never triggers a
 * Google JWKS lookup or cross-provider claim check.
 */
class CompositeIdentityVerifier(
    private val verifiers: List<JwksIdentityVerifier>,
) : IdentityVerifier {
    override suspend fun verify(idToken: String): VerifiedIdentity? {
        val parts = idToken.trim().split('.')
        if (parts.size != 3) return null
        val issuer = decodeJsonObject(parts[1])?.stringClaim("iss") ?: return null
        val matching = verifiers.filter { issuer in it.allowedIssuers }
        for (verifier in matching) {
            verifier.verify(idToken)?.let { return it }
        }
        return null
    }
}

/** Outcome of signing in when no guest session is being promoted. */
data class AuthenticatedOwner(val ownerId: String, val created: Boolean)

/**
 * Outcome of linking a verified external identity onto an existing guest
 * `ownerId`.
 */
sealed interface GuestLinkResult {
    /** The guest account now owns `(issuer, subject)` directly. */
    data class Promoted(val ownerId: String) : GuestLinkResult

    /**
     * `(issuer, subject)` already belongs to [existingOwnerId], distinct from
     * [guestOwnerId]. No state was mutated; the product can switch sessions or
     * merge guest records into [existingOwnerId] and call [IdentityStore.aliasOwner].
     */
    data class Conflict(val existingOwnerId: String, val guestOwnerId: String) : GuestLinkResult
}

/** Persisted binding between an external `(issuer, subject)` and a first-party `ownerId`. */
data class IdentityRow(
    val issuer: String,
    val subject: String,
    val ownerId: String,
    val email: String?,
    val emailVerified: Boolean,
    val createdAt: Long,
)

/**
 * Persistence seam for external `(issuer, subject)` bindings and guest-merge
 * aliases (`owner_aliases`).
 *
 * Account resolution keys strictly on `(issuer, subject)` and never on raw
 * `email`, preventing pre-account takeover (`RFC 9700`). When a guest account
 * merges into an existing owner, [aliasOwner] records an immutable redirect so
 * StoreKit `appAccountToken` and Play `obfuscatedAccountId` renewal webhooks
 * continue to find the canonical buyer.
 */
interface IdentityStore {

    /**
     * Resolve `(issuer, subject)` to its canonical owner, or atomically bind it
     * to [newOwnerId] on first sight.
     */
    suspend fun authenticateOrCreate(
        identity: VerifiedIdentity,
        newOwnerId: () -> String,
        now: Long = System.currentTimeMillis(),
    ): AuthenticatedOwner

    /**
     * Promote [guestOwnerId] in place when `(issuer, subject)` is unclaimed, or
     * report [GuestLinkResult.Conflict] when another owner already holds it.
     */
    suspend fun linkGuest(
        guestOwnerId: String,
        identity: VerifiedIdentity,
        now: Long = System.currentTimeMillis(),
    ): GuestLinkResult

    /**
     * Redirect [aliasOwnerId] to [canonicalOwnerId], flattening any existing
     * aliases that pointed to [aliasOwnerId] and moving its identity bindings.
     */
    suspend fun aliasOwner(
        aliasOwnerId: String,
        canonicalOwnerId: String,
        now: Long = System.currentTimeMillis(),
    )

    /** Return the canonical owner for [ownerId] (`ownerId` itself when unaliased). */
    suspend fun canonicalOwner(ownerId: String): String

    /** Remove every external identity binding for [ownerId] during account deletion. */
    suspend fun unlinkAll(ownerId: String)
}

/** In-memory [IdentityStore] for local-only mode and fast unit tests. */
class MemoryIdentityStore : IdentityStore {

    private data class SubjectKey(val issuer: String, val subject: String)

    private val lock = Any()
    private val bindings = HashMap<SubjectKey, IdentityRow>()
    private val aliases = HashMap<String, String>()

    override suspend fun authenticateOrCreate(
        identity: VerifiedIdentity,
        newOwnerId: () -> String,
        now: Long,
    ): AuthenticatedOwner = synchronized(lock) {
        val key = SubjectKey(identity.issuer, identity.subject)
        val existing = bindings[key]
        if (existing != null) {
            return AuthenticatedOwner(resolveLocked(existing.ownerId), created = false)
        }
        val owner = resolveLocked(newOwnerId())
        bindings[key] = IdentityRow(
            issuer = identity.issuer,
            subject = identity.subject,
            ownerId = owner,
            email = identity.email,
            emailVerified = identity.emailVerified,
            createdAt = now,
        )
        AuthenticatedOwner(owner, created = true)
    }

    override suspend fun linkGuest(
        guestOwnerId: String,
        identity: VerifiedIdentity,
        now: Long,
    ): GuestLinkResult = synchronized(lock) {
        val guest = resolveLocked(guestOwnerId)
        val key = SubjectKey(identity.issuer, identity.subject)
        val existing = bindings[key]
        if (existing != null) {
            val owner = resolveLocked(existing.ownerId)
            return if (owner == guest) {
                GuestLinkResult.Promoted(guest)
            } else {
                GuestLinkResult.Conflict(existingOwnerId = owner, guestOwnerId = guest)
            }
        }
        bindings[key] = IdentityRow(
            issuer = identity.issuer,
            subject = identity.subject,
            ownerId = guest,
            email = identity.email,
            emailVerified = identity.emailVerified,
            createdAt = now,
        )
        GuestLinkResult.Promoted(guest)
    }

    override suspend fun aliasOwner(
        aliasOwnerId: String,
        canonicalOwnerId: String,
        now: Long,
    ) {
        synchronized(lock) {
            val target = resolveLocked(canonicalOwnerId)
            val source = aliasOwnerId.trim()
            require(source.isNotEmpty() && target.isNotEmpty()) { "owner ids must not be blank" }
            if (source == target) return@synchronized
            aliases.entries.forEach { entry ->
                if (entry.value == source) entry.setValue(target)
            }
            aliases[source] = target
            bindings.entries.forEach { entry ->
                if (entry.value.ownerId == source) {
                    entry.setValue(entry.value.copy(ownerId = target))
                }
            }
        }
    }

    override suspend fun canonicalOwner(ownerId: String): String =
        synchronized(lock) { resolveLocked(ownerId) }

    override suspend fun unlinkAll(ownerId: String) {
        synchronized(lock) {
            val canonical = resolveLocked(ownerId)
            bindings.entries.removeIf { it.value.ownerId == canonical || it.value.ownerId == ownerId }
        }
    }

    private fun resolveLocked(ownerId: String): String = aliases[ownerId] ?: ownerId
}

private val jwksJson = Json { ignoreUnknownKeys = true }

private fun decodeBase64Url(segment: String): ByteArray? {
    if (segment.isEmpty()) return ByteArray(0)
    return runCatching { Base64.getUrlDecoder().decode(segment) }.getOrNull()
}

private fun decodeJsonObject(segment: String): JsonObject? {
    val bytes = decodeBase64Url(segment)?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        jwksJson.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    }.getOrNull()
}

private fun JsonObject.stringClaim(name: String): String? {
    val element = this[name] as? JsonPrimitive ?: return null
    if (!element.isString) return null
    return element.content
}

private fun JsonObject.longClaimStrict(name: String): Long? {
    val element = this[name] as? JsonPrimitive ?: return null
    if (element.isString) return null
    return element.longOrNull
}

/**
 * Apple encodes `email_verified` as `"true"` on some token flows while Google
 * and standard OIDC providers send boolean `true`; accept only those two forms.
 */
private fun JsonObject.verifiedBooleanClaim(name: String): Boolean {
    val element = this[name] as? JsonPrimitive ?: return false
    return if (element.isString) element.content == "true" else element.booleanOrNull == true
}

private fun JsonObject.audienceClaims(): List<String>? {
    val raw: JsonElement = this["aud"] ?: return null
    return when (raw) {
        is JsonPrimitive -> {
            if (!raw.isString || raw.content.isBlank()) null else listOf(raw.content)
        }
        is JsonArray -> {
            if (raw.isEmpty()) return null
            val list = raw.map { item ->
                val prim = item as? JsonPrimitive ?: return null
                if (!prim.isString || prim.content.isBlank()) return null
                prim.content
            }
            list
        }
        else -> null
    }
}
