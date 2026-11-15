package com.example.server.core

import java.util.UUID
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.neq
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
    val rawPayload = text("raw_payload").nullable()
    val outcome = text("outcome").nullable()
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
    val cancelAtPeriodEnd = bool("cancel_at_period_end")
    val sourceTimestamp = long("source_timestamp").nullable()
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

internal object CreditReservations : Table("credit_reservations") {
    val id = text("id")
    val ownerId = text("owner_id")
    val reservedUnits = long("reserved_units")
    val actualUnits = long("actual_units").nullable()
    val status = text("status")
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
    val settledAt = long("settled_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object CreditLedger : Table("credit_ledger") {
    val id = text("id")
    val ownerId = text("owner_id")
    val entryType = text("entry_type")
    val deltaUnits = long("delta_units")
    val referenceId = text("reference_id")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed [EntitlementStore] over `webhook_events` and `entitlements`
 * (`V8__commercial_backbone.sql` + `V9__production_saas_hardening.sql`).
 *
 * Inserting into `webhook_events` (with raw replay payload and outcome) and
 * upserting `entitlements` share one database transaction, while monotonic
 * `source_timestamp` checks prevent out-of-order webhook deliveries from
 * regressing newer subscription state.
 */
class SqlEntitlementStore(
    private val canonicalOwner: suspend (String) -> String = { it },
) : EntitlementStore {

    override suspend fun applyWebhookEvent(
        provider: String,
        eventId: String,
        now: Long,
        upsert: EntitlementUpsert?,
        rawPayload: String?,
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
                        it[WebhookEvents.rawPayload] = rawPayload
                        it[outcome] = WebhookApplyOutcome.APPLIED.name
                    }

                    var finalOutcome = WebhookApplyOutcome.APPLIED
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
                                it[cancelAtPeriodEnd] = upsert.cancelAtPeriodEnd
                                it[sourceTimestamp] = upsert.sourceTimestamp
                            }
                        } else {
                            val existingSourceTs = existing[Entitlements.sourceTimestamp]
                            val incomingSourceTs = upsert.sourceTimestamp
                            if (existingSourceTs != null && incomingSourceTs != null && incomingSourceTs < existingSourceTs) {
                                finalOutcome = WebhookApplyOutcome.STALE_IGNORED
                                WebhookEvents.update({
                                    (WebhookEvents.provider eq cleanProvider) and
                                        (WebhookEvents.eventId eq cleanEventId)
                                }) {
                                    it[outcome] = finalOutcome.name
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
                                    it[cancelAtPeriodEnd] = upsert.cancelAtPeriodEnd
                                    it[sourceTimestamp] = incomingSourceTs ?: existingSourceTs
                                }
                            }
                        }
                    }
                    finalOutcome
                }
            } catch (race: ExposedSQLException) {
                if (eventExists(cleanProvider, cleanEventId)) return WebhookApplyOutcome.DUPLICATE
                if (++attempt >= UPSERT_RACE_ATTEMPTS) throw race
            }
        }
    }

    override suspend fun webhookEvent(provider: String, eventId: String): StoredWebhookEvent? = coreDbQuery {
        WebhookEvents.selectAll()
            .where {
                (WebhookEvents.provider eq provider.trim()) and
                    (WebhookEvents.eventId eq eventId.trim())
            }
            .firstOrNull()
            ?.let { row ->
                StoredWebhookEvent(
                    provider = row[WebhookEvents.provider],
                    eventId = row[WebhookEvents.eventId],
                    receivedAt = row[WebhookEvents.receivedAt],
                    rawPayload = row[WebhookEvents.rawPayload],
                    outcome = row[WebhookEvents.outcome]?.let(WebhookApplyOutcome::valueOf),
                )
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
        cancelAtPeriodEnd = this[Entitlements.cancelAtPeriodEnd],
        sourceTimestamp = this[Entitlements.sourceTimestamp],
    )

    private companion object {
        const val UPSERT_RACE_ATTEMPTS = 3
    }
}

/**
 * SQL-backed [CreditBalanceStore] over `credit_grants`, `credit_reservations`,
 * and `credit_ledger` (`V8__commercial_backbone.sql` + `V9__production_saas_hardening.sql`).
 *
 * Every mutating spend, reservation, commit, and release acquires locks in a fixed
 * `credit_grants -> credit_reservations` order so concurrent multi-stream AI holds
 * serialize cleanly on Postgres without AB-BA deadlocks.
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
                CreditLedger.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[CreditLedger.ownerId] = ownerId
                    it[entryType] = CreditLedgerEntryType.GRANT.name
                    it[deltaUnits] = units
                    it[referenceId] = sourceRef
                    it[createdAt] = now
                }
            }
            CreditGrantOutcome.GRANTED
        } catch (race: ExposedSQLException) {
            if (grantExists(sourceRef)) CreditGrantOutcome.DUPLICATE else throw race
        }
    }

    override suspend fun available(ownerId: String, now: Long): Long = coreDbQuery {
        val gross = CreditGrants.selectAll()
            .where { CreditGrants.ownerId eq ownerId }
            .map { it.toCreditGrantRow() }
            .filter { it.isLiveAt(now) }
            .sumOf { it.remainingUnits }
        val held = activeHeldUnitsQuery(ownerId, now)
        (gross - held).coerceAtLeast(0L)
    }

    override suspend fun trySpend(ownerId: String, units: Long, now: Long): Boolean {
        require(units > 0L) { "units must be positive" }
        return coreDbQuery {
            val live = lockLiveGrants(ownerId, now)
            val held = activeHeldUnitsQuery(ownerId, now)
            if (live.sumOf { it.remainingUnits } - held < units) return@coreDbQuery false

            drainLotsSql(live, units)
            CreditLedger.insert {
                it[id] = UUID.randomUUID().toString()
                it[CreditLedger.ownerId] = ownerId
                it[entryType] = CreditLedgerEntryType.SPEND.name
                it[deltaUnits] = -units
                it[referenceId] = "spend:${UUID.randomUUID()}"
                it[createdAt] = now
            }
            true
        }
    }

    override suspend fun reserve(
        ownerId: String,
        units: Long,
        ttlMs: Long,
        now: Long,
    ): CreditReservationRow? {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(units > 0L) { "units must be positive" }
        require(ttlMs > 0L) { "ttlMs must be positive" }
        return coreDbQuery {
            val live = lockLiveGrants(ownerId, now)
            val held = activeHeldUnitsQuery(ownerId, now)
            if (live.sumOf { it.remainingUnits } - held < units) return@coreDbQuery null

            val resId = UUID.randomUUID().toString()
            val expiresAt = now + ttlMs
            CreditReservations.insert {
                it[id] = resId
                it[CreditReservations.ownerId] = ownerId
                it[reservedUnits] = units
                it[actualUnits] = null
                it[status] = CreditReservationStatus.RESERVED.name
                it[CreditReservations.expiresAt] = expiresAt
                it[createdAt] = now
                it[settledAt] = null
            }
            CreditReservationRow(
                id = resId,
                ownerId = ownerId,
                reservedUnits = units,
                status = CreditReservationStatus.RESERVED,
                expiresAt = expiresAt,
                createdAt = now,
            )
        }
    }

    override suspend fun commitReservation(
        reservationId: String,
        actualUnits: Long,
        now: Long,
    ): CreditCommitOutcome {
        require(reservationId.isNotBlank()) { "reservationId must not be blank" }
        require(actualUnits >= 0L) { "actualUnits must be >= 0" }

        val targetOwner = coreDbQuery {
            CreditReservations.selectAll()
                .where { CreditReservations.id eq reservationId }
                .firstOrNull()
                ?.get(CreditReservations.ownerId)
        } ?: return CreditCommitOutcome.NOT_FOUND

        return coreDbQuery {
            val live = lockLiveGrants(targetOwner, now)
            val current = CreditReservations.selectAll()
                .where { CreditReservations.id eq reservationId }
                .forUpdate()
                .firstOrNull()
                ?.toReservationRow()
                ?: return@coreDbQuery CreditCommitOutcome.NOT_FOUND

            when (current.status) {
                CreditReservationStatus.COMMITTED -> return@coreDbQuery CreditCommitOutcome.ALREADY_COMMITTED
                CreditReservationStatus.RELEASED -> return@coreDbQuery CreditCommitOutcome.ALREADY_RELEASED
                CreditReservationStatus.EXPIRED -> return@coreDbQuery CreditCommitOutcome.EXPIRED_INSUFFICIENT
                CreditReservationStatus.RESERVED -> Unit
            }
            require(actualUnits <= current.reservedUnits) {
                "actualUnits ($actualUnits) cannot exceed reservedUnits (${current.reservedUnits})"
            }

            if (actualUnits > 0L) {
                val otherHeld = activeHeldUnitsQuery(targetOwner, now, excludeId = reservationId)
                if (live.sumOf { it.remainingUnits } - otherHeld < actualUnits) {
                    CreditReservations.update({ CreditReservations.id eq reservationId }) {
                        it[status] = CreditReservationStatus.EXPIRED.name
                        it[settledAt] = now
                    }
                    return@coreDbQuery CreditCommitOutcome.EXPIRED_INSUFFICIENT
                }
                drainLotsSql(live, actualUnits)
                CreditLedger.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[ownerId] = targetOwner
                    it[entryType] = CreditLedgerEntryType.COMMIT.name
                    it[deltaUnits] = -actualUnits
                    it[referenceId] = reservationId
                    it[createdAt] = now
                }
            }

            CreditReservations.update({ CreditReservations.id eq reservationId }) {
                it[CreditReservations.actualUnits] = actualUnits
                it[status] = CreditReservationStatus.COMMITTED.name
                it[settledAt] = now
            }
            CreditCommitOutcome.COMMITTED
        }
    }

    override suspend fun releaseReservation(reservationId: String, now: Long): Boolean {
        val targetOwner = coreDbQuery {
            CreditReservations.selectAll()
                .where { CreditReservations.id eq reservationId }
                .firstOrNull()
                ?.get(CreditReservations.ownerId)
        } ?: return false

        return coreDbQuery {
            lockLiveGrants(targetOwner, now)
            val updated = CreditReservations.update({
                (CreditReservations.id eq reservationId) and
                    (CreditReservations.status eq CreditReservationStatus.RESERVED.name)
            }) {
                it[actualUnits] = 0L
                it[status] = CreditReservationStatus.RELEASED.name
                it[settledAt] = now
            }
            updated == 1
        }
    }

    override suspend fun ledgerForOwner(ownerId: String): List<CreditLedgerEntry> = coreDbQuery {
        CreditLedger.selectAll()
            .where { CreditLedger.ownerId eq ownerId }
            .orderBy(CreditLedger.createdAt to SortOrder.ASC, CreditLedger.id to SortOrder.ASC)
            .map { row ->
                CreditLedgerEntry(
                    id = row[CreditLedger.id],
                    ownerId = row[CreditLedger.ownerId],
                    entryType = CreditLedgerEntryType.valueOf(row[CreditLedger.entryType]),
                    deltaUnits = row[CreditLedger.deltaUnits],
                    referenceId = row[CreditLedger.referenceId],
                    createdAt = row[CreditLedger.createdAt],
                )
            }
    }

    override suspend fun deleteAllForOwner(ownerId: String) {
        coreDbQuery {
            CreditGrants.deleteWhere { CreditGrants.ownerId eq ownerId }
            CreditReservations.deleteWhere { CreditReservations.ownerId eq ownerId }
            CreditLedger.deleteWhere { CreditLedger.ownerId eq ownerId }
        }
    }

    private fun lockLiveGrants(ownerId: String, now: Long): List<CreditGrantRow> =
        CreditGrants.selectAll()
            .where { CreditGrants.ownerId eq ownerId }
            .forUpdate()
            .map { it.toCreditGrantRow() }
            .filter { it.isLiveAt(now) }
            .sortedWith(creditDrainOrder)

    private fun activeHeldUnitsQuery(ownerId: String, now: Long, excludeId: String? = null): Long {
        val baseCondition = (CreditReservations.ownerId eq ownerId) and
            (CreditReservations.status eq CreditReservationStatus.RESERVED.name) and
            (CreditReservations.expiresAt greater now)
        val condition = if (excludeId != null) {
            baseCondition and (CreditReservations.id neq excludeId)
        } else {
            baseCondition
        }
        return CreditReservations.selectAll()
            .where { condition }
            .sumOf { it[CreditReservations.reservedUnits] }
    }

    private fun drainLotsSql(live: List<CreditGrantRow>, units: Long) {
        var remainingToSpend = units
        for (lot in live) {
            if (remainingToSpend == 0L) break
            val deduct = minOf(remainingToSpend, lot.remainingUnits)
            CreditGrants.update({ CreditGrants.id eq lot.id }) {
                it[remainingUnits] = lot.remainingUnits - deduct
            }
            remainingToSpend -= deduct
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

    private fun ResultRow.toReservationRow() = CreditReservationRow(
        id = this[CreditReservations.id],
        ownerId = this[CreditReservations.ownerId],
        reservedUnits = this[CreditReservations.reservedUnits],
        actualUnits = this[CreditReservations.actualUnits],
        status = CreditReservationStatus.valueOf(this[CreditReservations.status]),
        expiresAt = this[CreditReservations.expiresAt],
        createdAt = this[CreditReservations.createdAt],
        settledAt = this[CreditReservations.settledAt],
    )
}
