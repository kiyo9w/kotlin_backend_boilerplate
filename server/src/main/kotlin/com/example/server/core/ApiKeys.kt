package com.example.server.core

import java.security.SecureRandom
import java.util.UUID

/** Persisted API key metadata (`V9__production_saas_hardening.sql`); raw key never stored. */
data class ApiKeyRow(
    val id: String,
    val workspaceId: String,
    val createdByOwnerId: String,
    val name: String,
    val keyPrefix: String,
    val keyHash: String,
    val scopes: Set<String>,
    val expiresAt: Long? = null,
    val graceExpiresAt: Long? = null,
    val revokedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val createdAt: Long,
) {
    fun isActiveAt(now: Long): Boolean =
        revokedAt == null &&
            (expiresAt == null || expiresAt > now) &&
            (graceExpiresAt == null || graceExpiresAt > now)
}

/** Returned once on [ApiKeyService.issue] or [ApiKeyService.rotate]. */
data class MintedApiKey(
    val record: ApiKeyRow,
    val rawKey: String,
)

/** Verified caller context resolved from a live API key. */
data class AuthenticatedApiKey(
    val keyId: String,
    val workspaceId: String,
    val createdByOwnerId: String,
    val scopes: Set<String>,
)

/** Persistence seam for hashed workspace API keys. */
interface ApiKeyStore {
    suspend fun insert(row: ApiKeyRow)

    suspend fun byId(workspaceId: String, keyId: String): ApiKeyRow?

    suspend fun byHash(keyHash: String): ApiKeyRow?

    suspend fun listForWorkspace(workspaceId: String): List<ApiKeyRow>

    /**
     * Atomically mark [oldKeyId] in [workspaceId] to expire at [graceExpiresAt]
     * and insert [replacement]. Returns `false` if [oldKeyId] is missing, belongs
     * to another workspace, or is already revoked.
     */
    suspend fun rotate(
        workspaceId: String,
        oldKeyId: String,
        graceExpiresAt: Long,
        replacement: ApiKeyRow,
    ): Boolean

    suspend fun revoke(workspaceId: String, keyId: String, now: Long): Boolean

    suspend fun revokeAllForCreator(ownerId: String, now: Long)

    suspend fun deleteAllForWorkspace(workspaceId: String)

    suspend fun touchLastUsed(keyId: String, now: Long)
}

/** In-memory [ApiKeyStore] for local-only mode and fast unit tests. */
class MemoryApiKeyStore : ApiKeyStore {

    private val lock = Any()
    private val rows = LinkedHashMap<String, ApiKeyRow>()

    override suspend fun insert(row: ApiKeyRow) {
        synchronized(lock) {
            check(rows.values.none { it.keyHash == row.keyHash }) { "duplicate keyHash" }
            rows[row.id] = row
        }
    }

    override suspend fun byId(workspaceId: String, keyId: String): ApiKeyRow? =
        synchronized(lock) { rows[keyId]?.takeIf { it.workspaceId == workspaceId } }

    override suspend fun byHash(keyHash: String): ApiKeyRow? =
        synchronized(lock) { rows.values.firstOrNull { it.keyHash == keyHash } }

    override suspend fun listForWorkspace(workspaceId: String): List<ApiKeyRow> =
        synchronized(lock) {
            rows.values.filter { it.workspaceId == workspaceId }.sortedByDescending { it.createdAt }
        }

    override suspend fun rotate(
        workspaceId: String,
        oldKeyId: String,
        graceExpiresAt: Long,
        replacement: ApiKeyRow,
    ): Boolean = synchronized(lock) {
        val current = rows[oldKeyId] ?: return@synchronized false
        if (current.workspaceId != workspaceId || current.revokedAt != null) return@synchronized false
        rows[oldKeyId] = current.copy(graceExpiresAt = graceExpiresAt)
        rows[replacement.id] = replacement
        true
    }

    override suspend fun revoke(workspaceId: String, keyId: String, now: Long): Boolean =
        synchronized(lock) {
            val current = rows[keyId] ?: return@synchronized false
            if (current.workspaceId != workspaceId || current.revokedAt != null) return@synchronized false
            rows[keyId] = current.copy(revokedAt = now)
            true
        }

    override suspend fun revokeAllForCreator(ownerId: String, now: Long) {
        synchronized(lock) {
            rows.forEach { (id, row) ->
                if (row.createdByOwnerId == ownerId && row.revokedAt == null) {
                    rows[id] = row.copy(revokedAt = now)
                }
            }
        }
    }

    override suspend fun deleteAllForWorkspace(workspaceId: String) {
        synchronized(lock) {
            rows.entries.removeIf { it.value.workspaceId == workspaceId }
        }
    }

    override suspend fun touchLastUsed(keyId: String, now: Long) {
        synchronized(lock) {
            rows[keyId]?.let { rows[keyId] = it.copy(lastUsedAt = now) }
        }
    }
}

/**
 * Mints, authenticates, rotates, and revokes prefixed workspace API keys
 * (`<productPrefix>_live_<publicId>_<secret>`).
 *
 * Design guarantees drawn from production SaaS deployments:
 * 1. **Zero-DB format gate:** malformed tokens fail regex validation in memory
 *    before touching Postgres.
 * 2. **Fast `SHA-256` hashing:** 256-bit random secrets need no slow KDF (`bcrypt`
 *    on API hot paths creates self-inflicted CPU DoS).
 * 3. **Live creator role clamping:** when [workspaces] is wired, the key's scopes
 *    are intersected with its creator's *current* workspace role on every call, so
 *    demoting or removing a team member immediately restricts keys they minted.
 * 4. **Zero-downtime rotation:** [rotate] grants a bounded grace window (24 hours
 *    by default) on the outgoing key while returning its replacement.
 * 5. **Write-throttled `lastUsedAt`:** updates `last_used_at` at most once per
 *    [touchMinIntervalMs] (`60s` default) so high-RPS traffic never contends on row locks.
 */
class ApiKeyService(
    private val store: ApiKeyStore,
    private val workspaces: WorkspaceStore? = null,
    private val productPrefix: String = DEFAULT_PRODUCT_PREFIX,
    private val touchMinIntervalMs: Long = DEFAULT_TOUCH_MIN_INTERVAL_MS,
    private val roleScopePolicy: (WorkspaceRole) -> Set<String> = WorkspaceRole::defaultAllowedKeyScopes,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val random = SecureRandom()
    private val expectedMarker = "${productPrefix}_live_"

    init {
        require(PRODUCT_PREFIX_REGEX.matches(productPrefix)) {
            "productPrefix must match [a-z][a-z0-9]{1,11}: $productPrefix"
        }
        require(touchMinIntervalMs >= 0L) { "touchMinIntervalMs must be >= 0" }
    }

    suspend fun issue(
        workspaceId: String,
        createdByOwnerId: String,
        name: String,
        scopes: Set<String>,
        expiresAt: Long? = null,
    ): MintedApiKey {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(createdByOwnerId.isNotBlank()) { "createdByOwnerId must not be blank" }
        val cleanName = name.trim().also { require(it.isNotEmpty()) { "name must not be blank" } }
        val cleanScopes = normalizeScopes(scopes)

        if (workspaces != null) {
            val creatorRole = workspaces.memberRole(workspaceId, createdByOwnerId)
                ?: throw IllegalArgumentException("creator $createdByOwnerId is not a member of workspace $workspaceId")
            val allowed = roleScopePolicy(creatorRole)
            if ("*" !in allowed && !allowed.containsAll(cleanScopes)) {
                throw IllegalArgumentException("requested scopes $cleanScopes exceed creator role $creatorRole")
            }
        }

        val now = clock()
        val (displayPrefix, rawKey) = generateRawKey()
        val row = ApiKeyRow(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            createdByOwnerId = createdByOwnerId,
            name = cleanName,
            keyPrefix = displayPrefix,
            keyHash = sha256Hex(rawKey),
            scopes = cleanScopes,
            expiresAt = expiresAt,
            createdAt = now,
        )
        store.insert(row)
        return MintedApiKey(row, rawKey)
    }

    suspend fun authenticate(
        rawKey: String,
        requiredScope: String? = null,
    ): AuthenticatedApiKey? {
        val candidate = rawKey.trim()
        if (!looksLikeValidKey(candidate)) return null

        val now = clock()
        val row = store.byHash(sha256Hex(candidate)) ?: return null
        if (!row.isActiveAt(now)) return null

        val effectiveScopes = if (workspaces != null) {
            val creatorRole = workspaces.memberRole(row.workspaceId, row.createdByOwnerId) ?: return null
            val allowedByRole = roleScopePolicy(creatorRole)
            if ("*" in allowedByRole) {
                row.scopes
            } else {
                row.scopes.intersect(allowedByRole)
            }
        } else {
            row.scopes
        }
        if (effectiveScopes.isEmpty()) return null

        if (requiredScope != null && "*" !in effectiveScopes && requiredScope !in effectiveScopes) {
            return null
        }

        val previousTouch = row.lastUsedAt
        if (previousTouch == null || (now - previousTouch) >= touchMinIntervalMs) {
            store.touchLastUsed(row.id, now)
        }

        return AuthenticatedApiKey(
            keyId = row.id,
            workspaceId = row.workspaceId,
            createdByOwnerId = row.createdByOwnerId,
            scopes = effectiveScopes,
        )
    }

    suspend fun rotate(
        workspaceId: String,
        keyId: String,
        graceMs: Long = DEFAULT_ROTATION_GRACE_MS,
    ): MintedApiKey? {
        require(graceMs >= 0L) { "graceMs must be >= 0" }
        val existing = store.byId(workspaceId, keyId) ?: return null
        val now = clock()
        if (!existing.isActiveAt(now)) return null

        val (displayPrefix, rawKey) = generateRawKey()
        val replacement = ApiKeyRow(
            id = UUID.randomUUID().toString(),
            workspaceId = existing.workspaceId,
            createdByOwnerId = existing.createdByOwnerId,
            name = existing.name,
            keyPrefix = displayPrefix,
            keyHash = sha256Hex(rawKey),
            scopes = existing.scopes,
            expiresAt = existing.expiresAt,
            createdAt = now,
        )
        val ok = store.rotate(
            workspaceId = workspaceId,
            oldKeyId = keyId,
            graceExpiresAt = now + graceMs,
            replacement = replacement,
        )
        return if (ok) MintedApiKey(replacement, rawKey) else null
    }

    suspend fun revoke(workspaceId: String, keyId: String): Boolean =
        store.revoke(workspaceId, keyId, clock())

    suspend fun listForWorkspace(workspaceId: String): List<ApiKeyRow> =
        store.listForWorkspace(workspaceId)

    internal fun looksLikeValidKey(candidate: String): Boolean {
        if (!candidate.startsWith(expectedMarker)) return false
        val rest = candidate.substring(expectedMarker.length)
        if (rest.length < PUBLIC_ID_LENGTH + 1 + MIN_SECRET_LENGTH) return false
        if (rest[PUBLIC_ID_LENGTH] != '_') return false
        val publicId = rest.substring(0, PUBLIC_ID_LENGTH)
        val secret = rest.substring(PUBLIC_ID_LENGTH + 1)
        return publicId.all { it in 'a'..'z' || it in '0'..'9' } &&
            secret.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
    }

    private fun generateRawKey(): Pair<String, String> {
        val publicId = buildString(PUBLIC_ID_LENGTH) {
            repeat(PUBLIC_ID_LENGTH) {
                append(PUBLIC_ID_ALPHABET[random.nextInt(PUBLIC_ID_ALPHABET.length)])
            }
        }
        val secret = randomBase64UrlToken(random)
        val displayPrefix = "${expectedMarker}$publicId"
        return displayPrefix to "${displayPrefix}_$secret"
    }

    private fun normalizeScopes(scopes: Set<String>): Set<String> {
        val cleaned = scopes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        require(cleaned.isNotEmpty()) { "scopes must not be empty" }
        require(cleaned.none { ',' in it }) { "scope names must not contain commas" }
        return cleaned
    }

    companion object {
        const val DEFAULT_PRODUCT_PREFIX: String = "sk"
        const val DEFAULT_TOUCH_MIN_INTERVAL_MS: Long = 60_000L
        const val DEFAULT_ROTATION_GRACE_MS: Long = 24L * 3600_000L
        private const val PUBLIC_ID_LENGTH = 12
        private const val MIN_SECRET_LENGTH = 32
        private const val PUBLIC_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val PRODUCT_PREFIX_REGEX = Regex("^[a-z][a-z0-9]{1,11}$")
    }
}
