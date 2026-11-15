package com.example.server.core

import java.util.UUID
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

internal object WebhookEvents : Table("webhook_events") {
    val provider = text("provider")
    val eventId = text("event_id")
    val receivedAt = long("received_at")
    override val primaryKey = PrimaryKey(provider, eventId)
}

internal object Entitlements : Table("entitlements") {
    val provider = text("provider")
    val externalSubId = text("external_sub_id")
    val ownerId = text("owner_id")
    val planCode = text("plan_code")
    val status = text("status")
    val quantity = integer("quantity")
    val currentPeriodEnd = long("current_period_end")
    val graceUntil = long("grace_until").nullable()
    val lastEventId = text("last_event_id")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(provider, externalSubId)
}

internal object CreditGrants : Table("credit_grants") {
    val id = text("id")
    val ownerId = text("owner_id")
    val sourceRef = text("source_ref").uniqueIndex()
    val grantedUnits = long("granted_units")
    val remainingUnits = long("remaining_units")
    val expiresAt = long("expires_at").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed [EntitlementStore] over `webhook_events` and `entitlements`
 * (`V8__commercial_backbone.sql`).
 *
 * Inserting into `webhook_events` and upserting `entitlements` share one database
 * transaction so an application crash or retry can neither lose an entitlement
 * update nor apply the same event twice.
 */
class SqlEntitlementStore(
    private val canonicalOwner: suspend (String) -> String = { it },
) : EntitlementStore {

    override suspend fun applyWebhookEvent(
        provider: String,
        eventId: String,
        now: Long,
        upsert: EntitlementUpsert?,
    ): WebhookApplyOutcome {
        val cleanProvider = provider.trim().also { require(it.isNotEmpty()) { "provider must not be blank" } }
        val cleanEventId = eventId.trim().also { require(it.isNotEmpty()) { "eventId must not be blank" } }
        val resolvedOwner = upsert?.let { canonicalOwner(it.ownerId) }

        if (eventExists(cleanProvider, cleanEventId)) return WebhookApplyOutcome.DUPLICATE

        var attempt = 0
        while (true) {
            try {
                return coreDbQuery {
                    val alreadySeen = WebhookEvents.selectAll()
                        .where { (WebhookEvents.provider eq cleanProvider) and (WebhookEvents.eventId eq cleanEventId) }
                        .firstOrNull()
                    if (alreadySeen != null) return@coreDbQuery WebhookApplyOutcome.DUPLICATE

                    WebhookEvents.insert {
                        it[WebhookEvents.provider] = cleanProvider
                        it[WebhookEvents.eventId] = cleanEventId
                        it[receivedAt] = now
                    }

                    if (upsert != null && resolvedOwner != null) {
                        val existing = Entitlements.selectAll()
                            .where {
                                (Entitlements.provider eq cleanProvider) and
                                    (Entitlements.externalSubId eq upsert.externalSubId)
                            }
                            .forUpdate()
                            .firstOrNull()
                        if (existing == null) {
                            Entitlements.insert {
                                it[Entitlements.provider] = cleanProvider
                                it[externalSubId] = upsert.externalSubId
                                it[ownerId] = resolvedOwner
                                it[planCode] = upsert.planCode
                                it[status] = upsert.status.name
                                it[quantity] = upsert.quantity
                                it[currentPeriodEnd] = upsert.currentPeriodEnd
                                it[graceUntil] = upsert.graceUntil
                                it[lastEventId] = cleanEventId
                                it[updatedAt] = now
                            }
                        } else {
                            Entitlements.update({
                                (Entitlements.provider eq cleanProvider) and
                                    (Entitlements.externalSubId eq upsert.externalSubId)
                            }) {
                                it[ownerId] = resolvedOwner
                                it[planCode] = upsert.planCode
                                it[status] = upsert.status.name
                                it[quantity] = upsert.quantity
                                it[currentPeriodEnd] = upsert.currentPeriodEnd
                                it[graceUntil] = upsert.graceUntil
                                it[lastEventId] = cleanEventId
                                it[updatedAt] = now
                            }
                        }
                    }
                    WebhookApplyOutcome.APPLIED
                }
            } catch (race: ExposedSQLException) {
                if (eventExists(cleanProvider, cleanEventId)) return WebhookApplyOutcome.DUPLICATE
                if (++attempt >= UPSERT_RACE_ATTEMPTS) throw race
            }
        }
    }

    override suspend fun activeForOwner(ownerId: String, now: Long): List<EntitlementRow> =
        allForOwner(ownerId).filter { it.isEntitledAt(now) }

    override suspend fun allForOwner(ownerId: String): List<EntitlementRow> {
        val resolved = canonicalOwner(ownerId)
        return coreDbQuery {
            Entitlements.selectAll()
                .where { Entitlements.ownerId eq resolved }
                .map { it.toEntitlementRow() }
        }
    }

    override suspend fun deleteAllForOwner(ownerId: String) {
        val resolved = canonicalOwner(ownerId)
        coreDbQuery {
            Entitlements.deleteWhere {
                (Entitlements.ownerId eq resolved) or (Entitlements.ownerId eq ownerId)
            }
        }
    }

    private suspend fun eventExists(provider: String, eventId: String): Boolean = coreDbQuery {
        WebhookEvents.selectAll()
            .where { (WebhookEvents.provider eq provider) and (WebhookEvents.eventId eq eventId) }
            .firstOrNull() != null
    }

    private fun ResultRow.toEntitlementRow() = EntitlementRow(
        provider = this[Entitlements.provider],
        externalSubId = this[Entitlements.externalSubId],
        ownerId = this[Entitlements.ownerId],
        planCode = this[Entitlements.planCode],
        status = SubscriptionStatus.valueOf(this[Entitlements.status]),
        quantity = this[Entitlements.quantity],
        currentPeriodEnd = this[Entitlements.currentPeriodEnd],
        graceUntil = this[Entitlements.graceUntil],
        lastEventId = this[Entitlements.lastEventId],
        updatedAt = this[Entitlements.updatedAt],
    )

    private companion object {
        const val UPSERT_RACE_ATTEMPTS = 3
    }
}

/**
 * SQL-backed [CreditBalanceStore] over `credit_grants` (`V8__commercial_backbone.sql`).
 *
 * `trySpend` locks every lot for the owner `FOR UPDATE` in one transaction so
 * concurrent spends serialize cleanly, drains finite-expiry lots in earliest-expiry
 * order before non-expiring lots, and aborts with zero partial writes when the
 * live balance is insufficient.
 */
class SqlCreditBalanceStore : CreditBalanceStore {

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

        if (grantExists(sourceRef)) return CreditGrantOutcome.DUPLICATE
        return try {
            coreDbQuery {
                CreditGrants.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[CreditGrants.ownerId] = ownerId
                    it[CreditGrants.sourceRef] = sourceRef
                    it[grantedUnits] = units
                    it[remainingUnits] = units
                    it[CreditGrants.expiresAt] = expiresAt
                    it[createdAt] = now
                }
            }
            CreditGrantOutcome.GRANTED
        } catch (race: ExposedSQLException) {
            if (grantExists(sourceRef)) CreditGrantOutcome.DUPLICATE else throw race
        }
    }

    override suspend fun available(ownerId: String, now: Long): Long = coreDbQuery {
        CreditGrants.selectAll()
            .where { CreditGrants.ownerId eq ownerId }
            .map { it.toCreditGrantRow() }
            .filter { it.isLiveAt(now) }
            .sumOf { it.remainingUnits }
    }

    override suspend fun trySpend(ownerId: String, units: Long, now: Long): Boolean {
        require(units > 0L) { "units must be positive" }
        return coreDbQuery {
            val live = CreditGrants.selectAll()
                .where { CreditGrants.ownerId eq ownerId }
                .forUpdate()
                .map { it.toCreditGrantRow() }
                .filter { it.isLiveAt(now) }
                .sortedWith(creditDrainOrder)
            if (live.sumOf { it.remainingUnits } < units) return@coreDbQuery false

            var remainingToSpend = units
            for (lot in live) {
                if (remainingToSpend == 0L) break
                val deduct = minOf(remainingToSpend, lot.remainingUnits)
                CreditGrants.update({ CreditGrants.id eq lot.id }) {
                    it[remainingUnits] = lot.remainingUnits - deduct
                }
                remainingToSpend -= deduct
            }
            true
        }
    }

    override suspend fun deleteAllForOwner(ownerId: String) {
        coreDbQuery {
            CreditGrants.deleteWhere { CreditGrants.ownerId eq ownerId }
        }
    }

    private suspend fun grantExists(sourceRef: String): Boolean = coreDbQuery {
        CreditGrants.selectAll()
            .where { CreditGrants.sourceRef eq sourceRef }
            .firstOrNull() != null
    }

    private fun ResultRow.toCreditGrantRow() = CreditGrantRow(
        id = this[CreditGrants.id],
        ownerId = this[CreditGrants.ownerId],
        sourceRef = this[CreditGrants.sourceRef],
        grantedUnits = this[CreditGrants.grantedUnits],
        remainingUnits = this[CreditGrants.remainingUnits],
        expiresAt = this[CreditGrants.expiresAt],
        createdAt = this[CreditGrants.createdAt],
    )
}
