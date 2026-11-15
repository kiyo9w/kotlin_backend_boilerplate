package com.example.server.core

import java.security.MessageDigest
import java.util.UUID

/**
 * One immutable security, administrative, or billing-footprint audit event
 * (`V9__production_saas_hardening.sql`).
 *
 * - [impersonatorId] records the human operator during bounded support impersonation
 *   (`RFC 8693` `act` claim) so support mutations are never confused with customer actions.
 * - [ipAddress] and [deviceId] retain the session footprint needed to contest
 *   friendly-fraud chargebacks under Visa Compelling Evidence 3.0 (`CE 3.0`,
 *   matching undisputed usage `120..365` days prior to the disputed charge).
 */
data class AuditEventRow(
    val id: String = UUID.randomUUID().toString(),
    val workspaceId: String? = null,
    val ownerId: String? = null,
    val actorId: String,
    val impersonatorId: String? = null,
    val action: String,
    val targetType: String? = null,
    val targetId: String? = null,
    val ipAddress: String? = null,
    val deviceId: String? = null,
    val userAgent: String? = null,
    val metadataJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    init {
        require(actorId.isNotBlank()) { "actorId must not be blank" }
        require(action.isNotBlank()) { "action must not be blank" }
    }
}

/**
 * Append-only audit log with Visa `CE 3.0` evidence lookup and privacy-erasure
 * pseudonymization (`GDPR` Art. 17 / Vietnam `Decree 356/2025/ND-CP`).
 */
interface AuditLogStore {

    suspend fun append(event: AuditEventRow): AuditEventRow

    suspend fun forWorkspace(workspaceId: String, limit: Int = 100): List<AuditEventRow>

    suspend fun forOwner(ownerId: String, limit: Int = 100): List<AuditEventRow>

    /**
     * Find prior events for [ownerId] inside the Visa Compelling Evidence 3.0
     * window (`[disputedAt - maxAgeDays, disputedAt - minAgeDays]`, `120..365`
     * days by default) whose [ipAddress] or [deviceId] matches the disputed session.
     */
    suspend fun findCompellingEvidence(
        ownerId: String,
        disputedAt: Long,
        ipAddress: String? = null,
        deviceId: String? = null,
        minAgeDays: Int = 120,
        maxAgeDays: Int = 365,
    ): List<AuditEventRow>

    /**
     * Scrub personal network/device identifiers (`ipAddress`, `deviceId`, `userAgent`,
     * `metadataJson`) and replace [ownerId] with an irreversible tombstone hash so
     * security/billing timelines stay intact after account deletion without retaining PII.
     */
    suspend fun pseudonymizeForOwner(ownerId: String): Int
}

/** In-memory [AuditLogStore] for local-only mode and fast unit tests. */
class MemoryAuditLogStore : AuditLogStore {

    private val lock = Any()
    private val events = ArrayList<AuditEventRow>()

    override suspend fun append(event: AuditEventRow): AuditEventRow {
        synchronized(lock) {
            events += event
            return event
        }
    }

    override suspend fun forWorkspace(workspaceId: String, limit: Int): List<AuditEventRow> {
        require(limit > 0) { "limit must be positive" }
        return synchronized(lock) {
            events.filter { it.workspaceId == workspaceId }
                .sortedByDescending { it.createdAt }
                .take(limit)
        }
    }

    override suspend fun forOwner(ownerId: String, limit: Int): List<AuditEventRow> {
        require(limit > 0) { "limit must be positive" }
        return synchronized(lock) {
            events.filter { it.ownerId == ownerId }
                .sortedByDescending { it.createdAt }
                .take(limit)
        }
    }

    override suspend fun findCompellingEvidence(
        ownerId: String,
        disputedAt: Long,
        ipAddress: String?,
        deviceId: String?,
        minAgeDays: Int,
        maxAgeDays: Int,
    ): List<AuditEventRow> {
        val cleanIp = ipAddress?.trim()?.takeIf { it.isNotEmpty() }
        val cleanDevice = deviceId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanIp == null && cleanDevice == null) return emptyList()
        require(minAgeDays >= 0 && maxAgeDays >= minAgeDays) { "invalid CE 3.0 window" }

        val windowStart = disputedAt - maxAgeDays * DAY_MS
        val windowEnd = disputedAt - minAgeDays * DAY_MS
        return synchronized(lock) {
            events.filter { row ->
                row.ownerId == ownerId &&
                    row.createdAt in windowStart..windowEnd &&
                    ((cleanIp != null && row.ipAddress == cleanIp) ||
                        (cleanDevice != null && row.deviceId == cleanDevice))
            }.sortedByDescending { it.createdAt }
        }
    }

    override suspend fun pseudonymizeForOwner(ownerId: String): Int {
        val tombstone = deletedActorPseudonym(ownerId)
        return synchronized(lock) {
            var count = 0
            for (idx in events.indices) {
                val current = events[idx]
                if (current.ownerId == ownerId || current.actorId == ownerId) {
                    events[idx] = current.copy(
                        ownerId = if (current.ownerId == ownerId) tombstone else current.ownerId,
                        actorId = if (current.actorId == ownerId) tombstone else current.actorId,
                        ipAddress = null,
                        deviceId = null,
                        userAgent = null,
                        metadataJson = null,
                    )
                    count++
                }
            }
            count
        }
    }

    private companion object {
        const val DAY_MS: Long = 86_400_000L
    }
}

internal fun deletedActorPseudonym(ownerId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ownerId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)
    return "deleted:$digest"
}
