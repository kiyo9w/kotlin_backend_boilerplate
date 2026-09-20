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
data class AuthSubject(val ownerId: String, val deviceId: String? = null, val roles: Set<String> = emptySet())

/** One issued pair. Raw values are returned once; only hashes persist. */
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
)

/** Token lifetimes. Defaults: access 15 minutes, refresh 30 days. */
data class TokenTtl(val accessMs: Long = 15 * 60_000L, val refreshMs: Long = 30L * 24 * 3600_000L)

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

    /**
     * Atomically mark [oldId] rotated and insert [next]; a stale rotation is
     * refused. The refusal is [StaleClaimException]: the compare-and-set found
     * the row already revoked, so the caller must not hand out [next].
     */
    suspend fun rotate(oldId: String, next: SessionRow, now: Long)

    /** Mark one row revoked. Idempotent; a missing or already-revoked row is a no-op. */
    suspend fun revoke(id: String, now: Long)

    /** Mark every live row for [ownerId] revoked, leaving other owners untouched. */
    suspend fun revokeAll(ownerId: String, now: Long)

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

    // Lookups scan; memory mode is one process and holds one process's sessions.
    override suspend fun byAccessHash(hash: String): SessionRow? =
        rows.values.firstOrNull { it.accessHash == hash }

    override suspend fun byRefreshHash(hash: String): SessionRow? =
        rows.values.firstOrNull { it.refreshHash == hash }

    override suspend fun rotate(oldId: String, next: SessionRow, now: Long) {
        synchronized(lock) {
            val current = rows[oldId] ?: throw StaleClaimException("session $oldId not found")
            if (current.revokedAt != null) {
                throw StaleClaimException("session $oldId is already rotated or revoked")
            }
            rows[oldId] = current.copy(revokedAt = now)
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

    override suspend fun revokeAll(ownerId: String, now: Long) {
        synchronized(lock) {
            rows.forEach { (id, row) ->
                if (row.ownerId == ownerId && row.revokedAt == null) {
                    rows[id] = row.copy(revokedAt = now)
                }
            }
        }
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
 * Lookup is by hash, so a plain equality comparison is enough. The stored value
 * is the digest of a 256-bit random token; guessing a preimage is the attack,
 * and a timing side channel on the comparison does not help with that, so no
 * constant-time compare is needed (unlike the shared secret in
 * [HmacWebhookVerifier]).
 */
class SessionService(
    private val store: SessionStore,
    private val ttl: TokenTtl = TokenTtl(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val random = SecureRandom()

    /** Mint and persist a pair. The raw tokens leave here exactly once. */
    suspend fun issue(ownerId: String, deviceId: String? = null): SessionTokens {
        val now = clock()
        val access = newToken()
        val refresh = newToken()
        store.insert(
            SessionRow(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                deviceId = deviceId,
                accessHash = hash(access),
                refreshHash = hash(refresh),
                accessExpiresAt = now + ttl.accessMs,
                refreshExpiresAt = now + ttl.refreshMs,
                createdAt = now,
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
        return AuthSubject(ownerId = row.ownerId, deviceId = row.deviceId)
    }

    /**
     * Rotates: the presented refresh token is invalid immediately; returns null
     * when it is not live. The revocation of the presented row and the insert of
     * the successor are one atomic step, so a replayed token — or a race between
     * two refreshes — finds the row revoked and is refused. The presented token
     * dies even if the returned pair is never used.
     */
    suspend fun refresh(refreshToken: String): SessionTokens? {
        val now = clock()
        val current = store.byRefreshHash(hash(refreshToken)) ?: return null
        if (current.revokedAt != null || current.refreshExpiresAt <= now) return null
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
        )
        return try {
            store.rotate(current.id, next, now)
            SessionTokens(access, refresh, next.accessExpiresAt, next.refreshExpiresAt)
        } catch (refused: StaleClaimException) {
            // Another refresh rotated the presented token first, so this
            // successor was never persisted and there is nothing to return.
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
