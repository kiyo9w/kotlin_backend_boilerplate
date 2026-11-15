package com.example.server.core

import com.example.server.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a resolved session identifies. */
data class AuthSubject(
    val ownerId: String,
    val deviceId: String? = null,
    val roles: Set<String> = emptySet(),
    val sessionId: String? = null,
    val sudoUntil: Long? = null,
    val impersonatorId: String? = null,
) {
    fun hasSudoAt(now: Long): Boolean = sudoUntil != null && sudoUntil > now
}

/** One issued pair. Raw values are returned once; only hashes persist. */
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
)

/**
 * Token lifetimes and security windows.
 *
 * - [accessMs]: 15 minutes
 * - [refreshMs]: 30 days
 * - [reuseGraceMs]: 10 seconds for benign multi-tab or flaky-network retries before
 *   replaying a rotated refresh token revokes the entire token family (`RFC 9700` §4.14.2)
 * - [sudoMs]: 15 minutes for step-up re-authentication on sensitive mutations
 */
data class TokenTtl(
    val accessMs: Long = 15 * 60_000L,
    val refreshMs: Long = 30L * 24 * 3600_000L,
    val reuseGraceMs: Long = 10_000L,
    val sudoMs: Long = 15 * 60_000L,
)

/** One persisted session row. accessHash/refreshHash are SHA-256 hex of the raw tokens. */
data class SessionRow(
    val id: String,
    val ownerId: String,
    val deviceId: String?,
    val accessHash: String,
    val refreshHash: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val rotatedFrom: String? = null,
    val revokedAt: Long? = null,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    val familyId: String = id,
    val rotatedAt: Long? = null,
    val sudoUntil: Long? = null,
    val ipAddress: String? = null,
    val userAgent: String? = null,
)

/**
 * Persistence seam for sessions. [MemorySessionStore] ships for the explicit
 * local-only mode; [SqlSessionStore] ships for the durable path. A store never
 * sees a raw token: it is handed SHA-256 hashes and looks rows up by them.
 */
interface SessionStore {

    /** Persist one freshly issued row. */
    suspend fun insert(row: SessionRow)

    /** The row whose access token hashes to [hash], live or revoked; null when none. */
    suspend fun byAccessHash(hash: String): SessionRow?

    /** The row whose refresh token hashes to [hash], live or revoked; null when none. */
    suspend fun byRefreshHash(hash: String): SessionRow?

    /** Every row whose `rotatedFrom` points to [parentId]. */
    suspend fun childrenOf(parentId: String): List<SessionRow>

    /**
     * Atomically mark [oldId] rotated and insert [next]; a stale rotation is
     * refused with [StaleClaimException].
     */
    suspend fun rotate(oldId: String, next: SessionRow, now: Long)

    /** Mark one row revoked. Idempotent; a missing or already-revoked row is a no-op. */
    suspend fun revoke(id: String, now: Long)

    /** Mark every live row in [familyId] revoked (`RFC 9700` refresh-token reuse defense). */
    suspend fun revokeFamily(familyId: String, now: Long)

    /** Mark one specific session belonging to [ownerId] revoked; false when missing, foreign, or already revoked. */
    suspend fun revokeForOwner(ownerId: String, sessionId: String, now: Long): Boolean

    /** Mark every live row for [ownerId] revoked, leaving other owners untouched. */
    suspend fun revokeAll(ownerId: String, now: Long)

    /** Every unrevoked, non-refresh-expired session row for [ownerId]. */
    suspend fun activeForOwner(ownerId: String, now: Long): List<SessionRow>

    /** Record step-up `sudo` verification until [sudoUntil] on [sessionId] owned by [ownerId]. */
    suspend fun elevateSudo(ownerId: String, sessionId: String, sudoUntil: Long, now: Long): Boolean

    /** Record that the row [id] was used at [now]. */
    suspend fun touch(id: String, now: Long)
}

/**
 * In-memory [SessionStore] for the explicit local-only mode and for tests. The
 * rotation compare-and-set is a synchronized check-and-set, so the same rule
 * the SQL store enforces with `WHERE id = ? AND revoked_at IS NULL` holds here.
 */
class MemorySessionStore : SessionStore {

    private val lock = Any()
    private val rows = ConcurrentHashMap<String, SessionRow>()

    override suspend fun insert(row: SessionRow) {
        synchronized(lock) { rows[row.id] = row }
    }

    override suspend fun byAccessHash(hash: String): SessionRow? =
        rows.values.firstOrNull { it.accessHash == hash }

    override suspend fun byRefreshHash(hash: String): SessionRow? =
        rows.values.firstOrNull { it.refreshHash == hash }

    override suspend fun childrenOf(parentId: String): List<SessionRow> = synchronized(lock) {
        rows.values.filter { it.rotatedFrom == parentId }
    }

    override suspend fun rotate(oldId: String, next: SessionRow, now: Long) {
        synchronized(lock) {
            val current = rows[oldId] ?: throw StaleClaimException("session $oldId not found")
            if (current.revokedAt != null) {
                throw StaleClaimException("session $oldId is already rotated or revoked")
            }
            rows[oldId] = current.copy(revokedAt = now, rotatedAt = now)
            rows[next.id] = next
        }
    }

    override suspend fun revoke(id: String, now: Long) {
        synchronized(lock) {
            rows[id]?.let { row ->
                if (row.revokedAt == null) rows[id] = row.copy(revokedAt = now)
            }
        }
    }

    override suspend fun revokeFamily(familyId: String, now: Long) {
        synchronized(lock) {
            rows.forEach { (id, row) ->
                if (row.familyId == familyId && row.revokedAt == null) {
                    rows[id] = row.copy(revokedAt = now)
                }
            }
        }
    }

    override suspend fun revokeForOwner(ownerId: String, sessionId: String, now: Long): Boolean =
        synchronized(lock) {
            val current = rows[sessionId] ?: return@synchronized false
            if (current.ownerId != ownerId || current.revokedAt != null) return@synchronized false
            rows[sessionId] = current.copy(revokedAt = now)
            true
        }

    override suspend fun revokeAll(ownerId: String, now: Long) {
        synchronized(lock) {
            rows.forEach { (id, row) ->
                if (row.ownerId == ownerId && row.revokedAt == null) {
                    rows[id] = row.copy(revokedAt = now)
                }
            }
        }
    }

    override suspend fun activeForOwner(ownerId: String, now: Long): List<SessionRow> =
        synchronized(lock) {
            rows.values
                .filter { it.ownerId == ownerId && it.revokedAt == null && it.refreshExpiresAt > now }
                .sortedByDescending { it.lastUsedAt ?: it.createdAt }
        }

    override suspend fun elevateSudo(ownerId: String, sessionId: String, sudoUntil: Long, now: Long): Boolean =
        synchronized(lock) {
            val current = rows[sessionId] ?: return@synchronized false
            if (current.ownerId != ownerId || current.revokedAt != null || current.refreshExpiresAt <= now) {
                return@synchronized false
            }
            rows[sessionId] = current.copy(sudoUntil = sudoUntil)
            true
        }

    override suspend fun touch(id: String, now: Long) {
        synchronized(lock) {
            rows[id]?.let { rows[id] = it.copy(lastUsedAt = now) }
        }
    }
}

/**
 * Issues, resolves, rotates, and revokes sessions. Only this class sees raw
 * tokens: they are minted here, returned once, and persisted only as SHA-256
 * hashes.
 *
 * Implements `RFC 9700` §4.14.2 refresh-token rotation with token-family reuse
 * revocation: replaying an already-rotated refresh token outside [TokenTtl.reuseGraceMs]
 * (or after its successor has already rotated) revokes every live token in that
 * `familyId` while leaving the owner's other device families untouched.
 */
class SessionService(
    private val store: SessionStore,
    private val ttl: TokenTtl = TokenTtl(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val random = SecureRandom()

    /** Mint and persist a pair. The raw tokens leave here exactly once. */
    suspend fun issue(
        ownerId: String,
        deviceId: String? = null,
        ipAddress: String? = null,
        userAgent: String? = null,
        sudoOnIssue: Boolean = false,
    ): SessionTokens {
        val now = clock()
        val access = newToken()
        val refresh = newToken()
        val rowId = UUID.randomUUID().toString()
        store.insert(
            SessionRow(
                id = rowId,
                ownerId = ownerId,
                deviceId = deviceId,
                accessHash = hash(access),
                refreshHash = hash(refresh),
                accessExpiresAt = now + ttl.accessMs,
                refreshExpiresAt = now + ttl.refreshMs,
                createdAt = now,
                familyId = rowId,
                sudoUntil = if (sudoOnIssue) now + ttl.sudoMs else null,
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )
        return SessionTokens(access, refresh, now + ttl.accessMs, now + ttl.refreshMs)
    }

    /** Resolves a live access token. Returns null for unknown, expired, or revoked. */
    suspend fun authenticate(accessToken: String): AuthSubject? {
        val now = clock()
        val row = store.byAccessHash(hash(accessToken)) ?: return null
        if (row.revokedAt != null || row.accessExpiresAt <= now) return null
        store.touch(row.id, now)
        return AuthSubject(
            ownerId = row.ownerId,
            deviceId = row.deviceId,
            sessionId = row.id,
            sudoUntil = row.sudoUntil,
        )
    }

    /**
     * Rotates a live refresh token and enforces `RFC 9700` reuse detection on
     * already-rotated tokens.
     */
    suspend fun refresh(
        refreshToken: String,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): SessionTokens? {
        val now = clock()
        val current = store.byRefreshHash(hash(refreshToken)) ?: return null
        if (current.refreshExpiresAt <= now) return null
        if (current.revokedAt != null) {
            val rotatedAt = current.rotatedAt
            if (rotatedAt != null) {
                val graceExpired = (now - rotatedAt) > ttl.reuseGraceMs
                val successorAlreadyAdvanced = store.childrenOf(current.id).any { it.rotatedAt != null }
                if (graceExpired || successorAlreadyAdvanced) {
                    store.revokeFamily(current.familyId, now)
                }
            }
            return null
        }
        val access = newToken()
        val refresh = newToken()
        val next = SessionRow(
            id = UUID.randomUUID().toString(),
            ownerId = current.ownerId,
            deviceId = current.deviceId,
            accessHash = hash(access),
            refreshHash = hash(refresh),
            accessExpiresAt = now + ttl.accessMs,
            refreshExpiresAt = now + ttl.refreshMs,
            rotatedFrom = current.id,
            createdAt = now,
            lastUsedAt = now,
            familyId = current.familyId,
            sudoUntil = current.sudoUntil,
            ipAddress = ipAddress ?: current.ipAddress,
            userAgent = userAgent ?: current.userAgent,
        )
        return try {
            store.rotate(current.id, next, now)
            SessionTokens(access, refresh, next.accessExpiresAt, next.refreshExpiresAt)
        } catch (refused: StaleClaimException) {
            null
        }
    }

    /**
     * Revoke the session the presented access token belongs to. False when the
     * token is unknown or its row is already revoked.
     */
    suspend fun revoke(accessToken: String): Boolean {
        val row = store.byAccessHash(hash(accessToken)) ?: return false
        if (row.revokedAt != null) return false
        store.revoke(row.id, clock())
        return true
    }

    /** Revoke one specific session owned by [ownerId] (device management screen). */
    suspend fun revokeSession(ownerId: String, sessionId: String): Boolean =
        store.revokeForOwner(ownerId, sessionId, clock())

    /** List active device sessions for [ownerId]. */
    suspend fun activeSessions(ownerId: String): List<SessionRow> =
        store.activeForOwner(ownerId, clock())

    /** Mark [sessionId] owned by [ownerId] as `sudo`-verified for [sudoTtlMs]. */
    suspend fun elevateSudo(ownerId: String, sessionId: String, sudoTtlMs: Long = ttl.sudoMs): Boolean {
        require(sudoTtlMs > 0L) { "sudoTtlMs must be positive" }
        val now = clock()
        return store.elevateSudo(ownerId, sessionId, sudoUntil = now + sudoTtlMs, now = now)
    }

    /** Revoke every live session for [ownerId], on every device. */
    suspend fun revokeAll(ownerId: String) {
        store.revokeAll(ownerId, clock())
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Attribute key the guard publishes so handlers can read the caller:
 *
 * ```kotlin
 * val subject = call.attributes[AuthSubjectKey]
 * ```
 */
val AuthSubjectKey = AttributeKey<AuthSubject>("AuthSubject")

/**
 * Require that the authenticated caller holds an unexpired step-up `sudo`
 * proof on the current session (`403 SUDO_REQUIRED` otherwise).
 */
fun ApplicationCall.requireSudo(now: Long = System.currentTimeMillis()): AuthSubject {
    val subject = attributes.getOrNull(AuthSubjectKey)
        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "authentication required")
    if (!subject.hasSudoAt(now)) {
        throw ApiException(
            HttpStatusCode.Forbidden,
            "SUDO_REQUIRED",
            "recent re-authentication required for this action",
        )
    }
    return subject
}

/**
 * Bearer-token [RouteGuard]. Resolves `Authorization: Bearer <token>` through
 * [SessionService], attaches the [AuthSubject] under [AuthSubjectKey], and then
 * enforces the route's declared policy. A missing or invalid credential is
 * `401 UNAUTHORIZED`; a live session without the required role is
 * `403 FORBIDDEN`. The token itself is never logged.
 *
 * ADMIN needs the `admin` role, which this shell never mints: an admin route
 * fails closed until a product extends the subject's roles.
 */
class BearerRouteGuard(private val sessions: SessionService) : RouteGuard {

    override suspend fun authorize(call: ApplicationCall, policy: RoutePolicy) {
        if (policy == RoutePolicy.PUBLIC) return
        val token = call.request.header(AUTHORIZATION_HEADER).orEmpty().bearerToken()
            ?: throw unauthorized("missing bearer token")
        val subject = sessions.authenticate(token) ?: throw unauthorized("invalid or expired bearer token")
        call.attributes.put(AuthSubjectKey, subject)
        if (policy == RoutePolicy.ADMIN && ADMIN_ROLE !in subject.roles) {
            throw ApiException(
                HttpStatusCode.Forbidden,
                "FORBIDDEN",
                "admin role required",
            )
        }
    }

    private fun unauthorized(detail: String) = ApiException(
        HttpStatusCode.Unauthorized,
        "UNAUTHORIZED",
        detail,
    )
}

private const val AUTHORIZATION_HEADER = "Authorization"
private const val ADMIN_ROLE = "admin"
private const val BEARER_PREFIX = "Bearer "

/** `Bearer <token>` in any scheme casing; null when the header is not a bearer header. */
private fun String.bearerToken(): String? {
    if (!regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) return null
    return substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
}
