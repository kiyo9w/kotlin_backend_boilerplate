package com.example.server.core

import java.util.UUID

/**
 * One immutable security, administrative, or billing-footprint audit event
 * (`V9__production_saas_hardening.sql`).
 *
 * - [impersonatorId] records the human operator during bounded support impersonation
 *   (`RFC 8693` `act` claim) so support mutations are never confused with customer actions.
 * - [ipAddress] and [deviceId] retain the session footprint needed to contest
 *   friendly-fraud chargebacks (such as Visa Compelling Evidence 3.0).
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
 * Append-only audit log with customer session-footprint lookup and privacy-erasure
 * pseudonymization (`GDPR` Art. 17 / Vietnam `Decree 356/2025/ND-CP`).
 */
interface AuditLogStore {

    suspend fun append(event: AuditEventRow): AuditEventRow

    suspend fun forWorkspace(workspaceId: String, limit: Int = 100): List<AuditEventRow>

    suspend fun forOwner(ownerId: String, limit: Int = 100): List<AuditEventRow>

    /**
     * Find direct customer events (`impersonatorId == null`) for [ownerId] inside
     * `[fromEpochMs, toEpochMs]` whose [ipAddress] or [deviceId] matches.
     */
    suspend fun findMatchingFootprint(
        ownerId: String,
        fromEpochMs: Long,
        toEpochMs: Long,
        ipAddress: String? = null,
        deviceId: String? = null,
    ): List<AuditEventRow>

    /**
     * Scrub personal network/device identifiers (`ipAddress`, `deviceId`, `userAgent`,
     * `metadataJson`) and replace [ownerId] with an irreversible tombstone hash so
     * security/billing timelines stay intact after account deletion without retaining PII.
     */
    suspend fun pseudonymizeForOwner(ownerId: String): Int
}

const val VISA_CE3_MIN_AGE_DAYS: Int = 120
const val VISA_CE3_MAX_AGE_DAYS: Int = 365
private const val AUDIT_DAY_MS: Long = 86_400_000L

/**
 * Convenience query over [AuditLogStore.findMatchingFootprint] for Visa Compelling
 * Evidence 3.0 (`120..365` days prior to [disputedAt], excluding support impersonation).
 */
suspend fun AuditLogStore.findVisaCompellingEvidence(
    ownerId: String,
    disputedAt: Long,
    ipAddress: String? = null,
    deviceId: String? = null,
    minAgeDays: Int = VISA_CE3_MIN_AGE_DAYS,
    maxAgeDays: Int = VISA_CE3_MAX_AGE_DAYS,
): List<AuditEventRow> {
    require(minAgeDays >= 0 && maxAgeDays >= minAgeDays) {
        "invalid evidence age window: $minAgeDays..$maxAgeDays"
    }
    return findMatchingFootprint(
        ownerId = ownerId,
        fromEpochMs = disputedAt - maxAgeDays * AUDIT_DAY_MS,
        toEpochMs = disputedAt - minAgeDays * AUDIT_DAY_MS,
        ipAddress = ipAddress,
        deviceId = deviceId,
    )
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

    override suspend fun findMatchingFootprint(
        ownerId: String,
        fromEpochMs: Long,
        toEpochMs: Long,
        ipAddress: String?,
        deviceId: String?,
    ): List<AuditEventRow> {
        require(toEpochMs >= fromEpochMs) { "toEpochMs must be >= fromEpochMs" }
        val cleanIp = ipAddress?.trim()?.takeIf { it.isNotEmpty() }
        val cleanDevice = deviceId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanIp == null && cleanDevice == null) return emptyList()

        return synchronized(lock) {
            events.filter { row ->
                row.ownerId == ownerId &&
                    row.impersonatorId == null &&
                    row.createdAt in fromEpochMs..toEpochMs &&
                    ((cleanIp != null && row.ipAddress == cleanIp) ||
                        (cleanDevice != null && row.deviceId == cleanDevice))
            }.sortedByDescending { it.createdAt }
        }
    }

    override suspend fun pseudonymizeForOwner(ownerId: String): Int {
        val tombstone = deletedActorPseudonym(ownerId)
        if (ownerId == tombstone) return 0
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
}

internal fun deletedActorPseudonym(ownerId: String): String =
    "deleted:${sha256Hex(ownerId).take(12)}"
