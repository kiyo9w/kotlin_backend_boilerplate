package com.example.server.core

import java.security.SecureRandom
import java.util.UUID
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

internal object Workspaces : Table("workspaces") {
    val id = text("id")
    val slug = text("slug").uniqueIndex()
    val name = text("name")
    val personalOwnerId = text("personal_owner_id").nullable().uniqueIndex()
    val planCode = text("plan_code")
    val billingCustomerId = text("billing_customer_id").nullable().uniqueIndex()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object WorkspaceMembers : Table("workspace_members") {
    val workspaceId = text("workspace_id")
    val ownerId = text("owner_id")
    val role = text("role")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(workspaceId, ownerId)
}

internal object WorkspaceInvites : Table("workspace_invites") {
    val id = text("id")
    val workspaceId = text("workspace_id")
    val invitedEmail = text("invited_email")
    val role = text("role")
    val tokenHash = text("token_hash").uniqueIndex()
    val invitedBy = text("invited_by")
    val expiresAt = long("expires_at")
    val acceptedAt = long("accepted_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed [WorkspaceStore] over `workspaces`, `workspace_members`, and
 * `workspace_invites` (`V9__production_saas_hardening.sql`).
 *
 * Role demotions, member removals, and account deletions lock `workspace_members`
 * rows `FOR UPDATE` so concurrent requests cannot race the last `OWNER` out of a
 * workspace.
 */
class SqlWorkspaceStore : WorkspaceStore {

    private val random = SecureRandom()

    override suspend fun ensurePersonalWorkspace(
        ownerId: String,
        defaultName: String,
        defaultPlanCode: String,
        now: Long,
    ): WorkspaceRow {
        val cleanOwner = ownerId.trim().also { require(it.isNotEmpty()) { "ownerId must not be blank" } }
        findPersonal(cleanOwner)?.let { return it }

        return try {
            coreDbQuery {
                Workspaces.selectAll()
                    .where { Workspaces.personalOwnerId eq cleanOwner }
                    .firstOrNull()
                    ?.toWorkspaceRow()
                    ?.let { return@coreDbQuery it }

                val wsId = UUID.randomUUID().toString()
                val slug = personalWorkspaceSlug(cleanOwner)
                val cleanName = defaultName.trim().ifEmpty { "Personal" }
                val cleanPlan = defaultPlanCode.trim().ifEmpty { "free" }

                Workspaces.insert {
                    it[id] = wsId
                    it[Workspaces.slug] = slug
                    it[name] = cleanName
                    it[personalOwnerId] = cleanOwner
                    it[planCode] = cleanPlan
                    it[billingCustomerId] = null
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                WorkspaceMembers.insert {
                    it[workspaceId] = wsId
                    it[WorkspaceMembers.ownerId] = cleanOwner
                    it[role] = WorkspaceRole.OWNER.name
                    it[createdAt] = now
                }
                WorkspaceRow(
                    id = wsId,
                    slug = slug,
                    name = cleanName,
                    personalOwnerId = cleanOwner,
                    planCode = cleanPlan,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        } catch (race: ExposedSQLException) {
            findPersonal(cleanOwner) ?: throw race
        }
    }

    override suspend fun createWorkspace(
        creatorOwnerId: String,
        slug: String,
        name: String,
        planCode: String,
        now: Long,
    ): WorkspaceRow {
        val cleanCreator = creatorOwnerId.trim().also { require(it.isNotEmpty()) { "creatorOwnerId must not be blank" } }
        val normalizedSlug = normalizeWorkspaceSlug(slug)
        val cleanName = name.trim().also { require(it.isNotEmpty()) { "name must not be blank" } }
        val cleanPlan = planCode.trim().also { require(it.isNotEmpty()) { "planCode must not be blank" } }

        if (bySlug(normalizedSlug) != null) throw WorkspaceSlugConflictException(normalizedSlug)

        return try {
            coreDbQuery {
                val wsId = UUID.randomUUID().toString()
                Workspaces.insert {
                    it[id] = wsId
                    it[Workspaces.slug] = normalizedSlug
                    it[Workspaces.name] = cleanName
                    it[personalOwnerId] = null
                    it[Workspaces.planCode] = cleanPlan
                    it[billingCustomerId] = null
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                WorkspaceMembers.insert {
                    it[workspaceId] = wsId
                    it[ownerId] = cleanCreator
                    it[role] = WorkspaceRole.OWNER.name
                    it[createdAt] = now
                }
                WorkspaceRow(
                    id = wsId,
                    slug = normalizedSlug,
                    name = cleanName,
                    personalOwnerId = null,
                    planCode = cleanPlan,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        } catch (race: ExposedSQLException) {
            if (bySlug(normalizedSlug) != null) throw WorkspaceSlugConflictException(normalizedSlug)
            throw race
        }
    }

    override suspend fun byId(workspaceId: String): WorkspaceRow? = coreDbQuery {
        Workspaces.selectAll()
            .where { Workspaces.id eq workspaceId }
            .firstOrNull()
            ?.toWorkspaceRow()
    }

    override suspend fun bySlug(slug: String): WorkspaceRow? = coreDbQuery {
        val clean = slug.trim().lowercase()
        Workspaces.selectAll()
            .where { Workspaces.slug eq clean }
            .firstOrNull()
            ?.toWorkspaceRow()
    }

    override suspend fun byBillingCustomer(billingCustomerId: String): WorkspaceRow? = coreDbQuery {
        val clean = billingCustomerId.trim()
        if (clean.isEmpty()) return@coreDbQuery null
        Workspaces.selectAll()
            .where { Workspaces.billingCustomerId eq clean }
            .firstOrNull()
            ?.toWorkspaceRow()
    }

    override suspend fun workspacesForOwner(ownerId: String): List<WorkspaceRow> = coreDbQuery {
        val wsIds = WorkspaceMembers.selectAll()
            .where { WorkspaceMembers.ownerId eq ownerId }
            .map { it[WorkspaceMembers.workspaceId] }
        if (wsIds.isEmpty()) return@coreDbQuery emptyList()
        Workspaces.selectAll()
            .where { Workspaces.id inList wsIds }
            .orderBy(Workspaces.createdAt, SortOrder.ASC)
            .map { it.toWorkspaceRow() }
    }

    override suspend fun memberRole(workspaceId: String, ownerId: String): WorkspaceRole? = coreDbQuery {
        WorkspaceMembers.selectAll()
            .where {
                (WorkspaceMembers.workspaceId eq workspaceId) and
                    (WorkspaceMembers.ownerId eq ownerId)
            }
            .firstOrNull()
            ?.let { WorkspaceRole.valueOf(it[WorkspaceMembers.role]) }
    }

    override suspend fun members(workspaceId: String): List<WorkspaceMemberRow> = coreDbQuery {
        WorkspaceMembers.selectAll()
            .where { WorkspaceMembers.workspaceId eq workspaceId }
            .orderBy(WorkspaceMembers.createdAt, SortOrder.ASC)
            .map { it.toMemberRow() }
    }

    override suspend fun bindBillingCustomer(
        workspaceId: String,
        billingCustomerId: String,
        now: Long,
    ): WorkspaceRow? {
        val cleanCustomer = billingCustomerId.trim().also {
            require(it.isNotEmpty()) { "billingCustomerId must not be blank" }
        }
        return coreDbQuery {
            val updated = Workspaces.update({ Workspaces.id eq workspaceId }) {
                it[Workspaces.billingCustomerId] = cleanCustomer
                it[updatedAt] = now
            }
            if (updated == 0) return@coreDbQuery null
            Workspaces.selectAll().where { Workspaces.id eq workspaceId }.single().toWorkspaceRow()
        }
    }

    override suspend fun updatePlan(workspaceId: String, planCode: String, now: Long): WorkspaceRow? {
        val cleanPlan = planCode.trim().also { require(it.isNotEmpty()) { "planCode must not be blank" } }
        return coreDbQuery {
            val updated = Workspaces.update({ Workspaces.id eq workspaceId }) {
                it[Workspaces.planCode] = cleanPlan
                it[updatedAt] = now
            }
            if (updated == 0) return@coreDbQuery null
            Workspaces.selectAll().where { Workspaces.id eq workspaceId }.single().toWorkspaceRow()
        }
    }

    override suspend fun upsertMember(
        workspaceId: String,
        ownerId: String,
        role: WorkspaceRole,
        now: Long,
    ): WorkspaceMemberRow {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        return coreDbQuery {
            val wsExists = Workspaces.selectAll().where { Workspaces.id eq workspaceId }.firstOrNull() != null
            require(wsExists) { "workspace $workspaceId not found" }

            val allMembers = WorkspaceMembers.selectAll()
                .where { WorkspaceMembers.workspaceId eq workspaceId }
                .forUpdate()
                .map { it.toMemberRow() }
            val current = allMembers.firstOrNull { it.ownerId == ownerId }

            if (current?.role == WorkspaceRole.OWNER && role != WorkspaceRole.OWNER) {
                val ownersCount = allMembers.count { it.role == WorkspaceRole.OWNER }
                if (ownersCount <= 1) {
                    throw LastWorkspaceOwnerException(
                        "cannot demote the sole owner of workspace $workspaceId",
                    )
                }
            }

            if (current == null) {
                WorkspaceMembers.insert {
                    it[WorkspaceMembers.workspaceId] = workspaceId
                    it[WorkspaceMembers.ownerId] = ownerId
                    it[WorkspaceMembers.role] = role.name
                    it[createdAt] = now
                }
                WorkspaceMemberRow(workspaceId, ownerId, role, now)
            } else {
                WorkspaceMembers.update({
                    (WorkspaceMembers.workspaceId eq workspaceId) and
                        (WorkspaceMembers.ownerId eq ownerId)
                }) {
                    it[WorkspaceMembers.role] = role.name
                }
                current.copy(role = role)
            }
        }
    }

    override suspend fun removeMember(workspaceId: String, ownerId: String): Boolean = coreDbQuery {
        val allMembers = WorkspaceMembers.selectAll()
            .where { WorkspaceMembers.workspaceId eq workspaceId }
            .forUpdate()
            .map { it.toMemberRow() }
        val current = allMembers.firstOrNull { it.ownerId == ownerId } ?: return@coreDbQuery false
        if (current.role == WorkspaceRole.OWNER) {
            val ownersCount = allMembers.count { it.role == WorkspaceRole.OWNER }
            if (ownersCount <= 1) {
                throw LastWorkspaceOwnerException(
                    "cannot remove the sole owner of workspace $workspaceId",
                )
            }
        }
        WorkspaceMembers.deleteWhere {
            (WorkspaceMembers.workspaceId eq workspaceId) and
                (WorkspaceMembers.ownerId eq ownerId)
        } == 1
    }

    override suspend fun issueInvite(
        workspaceId: String,
        invitedEmail: String,
        role: WorkspaceRole,
        invitedBy: String,
        ttlMs: Long,
        now: Long,
    ): IssuedWorkspaceInvite {
        val cleanEmail = normalizeEmailAddress(invitedEmail)
        require(invitedBy.isNotBlank()) { "invitedBy must not be blank" }
        require(ttlMs > 0L) { "ttlMs must be positive" }

        return coreDbQuery {
            val wsExists = Workspaces.selectAll().where { Workspaces.id eq workspaceId }.firstOrNull() != null
            require(wsExists) { "workspace $workspaceId not found" }

            val rawToken = randomBase64UrlToken(random)
            val inviteId = UUID.randomUUID().toString()
            val expiresAt = now + ttlMs
            WorkspaceInvites.insert {
                it[id] = inviteId
                it[WorkspaceInvites.workspaceId] = workspaceId
                it[WorkspaceInvites.invitedEmail] = cleanEmail
                it[WorkspaceInvites.role] = role.name
                it[tokenHash] = sha256Hex(rawToken)
                it[WorkspaceInvites.invitedBy] = invitedBy
                it[WorkspaceInvites.expiresAt] = expiresAt
                it[acceptedAt] = null
                it[revokedAt] = null
                it[createdAt] = now
            }
            IssuedWorkspaceInvite(
                invite = WorkspaceInviteRow(
                    id = inviteId,
                    workspaceId = workspaceId,
                    invitedEmail = cleanEmail,
                    role = role,
                    invitedBy = invitedBy,
                    expiresAt = expiresAt,
                    createdAt = now,
                ),
                rawToken = rawToken,
            )
        }
    }

    override suspend fun acceptInvite(
        rawToken: String,
        acceptingOwnerId: String,
        verifiedEmail: String,
        now: Long,
    ): InviteAcceptResult {
        val cleanToken = rawToken.trim()
        if (cleanToken.isEmpty()) return InviteAcceptResult(InviteAcceptOutcome.INVALID_OR_EXPIRED)
        require(acceptingOwnerId.isNotBlank()) { "acceptingOwnerId must not be blank" }
        val normalizedEmail = normalizeEmailAddress(verifiedEmail)
        val hash = sha256Hex(cleanToken)

        return coreDbQuery {
            val inviteRow = WorkspaceInvites.selectAll()
                .where { WorkspaceInvites.tokenHash eq hash }
                .forUpdate()
                .firstOrNull()
                ?.toInviteRow()
                ?: return@coreDbQuery InviteAcceptResult(InviteAcceptOutcome.INVALID_OR_EXPIRED)

            if (inviteRow.revokedAt != null || inviteRow.expiresAt <= now) {
                return@coreDbQuery InviteAcceptResult(InviteAcceptOutcome.INVALID_OR_EXPIRED)
            }
            if (inviteRow.acceptedAt != null) {
                return@coreDbQuery InviteAcceptResult(InviteAcceptOutcome.ALREADY_USED)
            }
            if (normalizedEmail != inviteRow.invitedEmail) {
                return@coreDbQuery InviteAcceptResult(InviteAcceptOutcome.EMAIL_MISMATCH)
            }

            WorkspaceInvites.update({ WorkspaceInvites.id eq inviteRow.id }) {
                it[acceptedAt] = now
            }

            val existing = WorkspaceMembers.selectAll()
                .where {
                    (WorkspaceMembers.workspaceId eq inviteRow.workspaceId) and
                        (WorkspaceMembers.ownerId eq acceptingOwnerId)
                }
                .forUpdate()
                .firstOrNull()
                ?.toMemberRow()

            val effectiveRole = if (existing != null && existing.role.rank >= inviteRow.role.rank) {
                existing.role
            } else {
                inviteRow.role
            }

            val membership = if (existing == null) {
                WorkspaceMembers.insert {
                    it[workspaceId] = inviteRow.workspaceId
                    it[ownerId] = acceptingOwnerId
                    it[role] = effectiveRole.name
                    it[createdAt] = now
                }
                WorkspaceMemberRow(inviteRow.workspaceId, acceptingOwnerId, effectiveRole, now)
            } else {
                WorkspaceMembers.update({
                    (WorkspaceMembers.workspaceId eq inviteRow.workspaceId) and
                        (WorkspaceMembers.ownerId eq acceptingOwnerId)
                }) {
                    it[role] = effectiveRole.name
                }
                existing.copy(role = effectiveRole)
            }

            InviteAcceptResult(InviteAcceptOutcome.ACCEPTED, membership)
        }
    }

    override suspend fun revokeInvite(workspaceId: String, inviteId: String, now: Long): Boolean =
        coreDbQuery {
            WorkspaceInvites.update({
                (WorkspaceInvites.id eq inviteId) and
                    (WorkspaceInvites.workspaceId eq workspaceId) and
                    WorkspaceInvites.revokedAt.isNull() and
                    WorkspaceInvites.acceptedAt.isNull()
            }) {
                it[revokedAt] = now
            } == 1
        }

    override suspend fun assertDeletableForOwner(ownerId: String) {
        coreDbQuery {
            assertDeletableInTx(ownerId)
        }
    }

    override suspend fun deleteOrLeaveForOwner(ownerId: String): List<String> = coreDbQuery {
        val memberships = assertDeletableInTx(ownerId)
        val deletedWorkspaceIds = ArrayList<String>()
        for (membership in memberships) {
            val wsMembers = WorkspaceMembers.selectAll()
                .where { WorkspaceMembers.workspaceId eq membership.workspaceId }
                .forUpdate()
                .map { it.toMemberRow() }
            if (wsMembers.size <= 1) {
                WorkspaceInvites.deleteWhere { WorkspaceInvites.workspaceId eq membership.workspaceId }
                WorkspaceMembers.deleteWhere { WorkspaceMembers.workspaceId eq membership.workspaceId }
                Workspaces.deleteWhere { Workspaces.id eq membership.workspaceId }
                deletedWorkspaceIds += membership.workspaceId
            } else {
                WorkspaceMembers.deleteWhere {
                    (WorkspaceMembers.workspaceId eq membership.workspaceId) and
                        (WorkspaceMembers.ownerId eq ownerId)
                }
            }
        }
        deletedWorkspaceIds
    }

    private fun assertDeletableInTx(ownerId: String): List<WorkspaceMemberRow> {
        val memberships = WorkspaceMembers.selectAll()
            .where { WorkspaceMembers.ownerId eq ownerId }
            .forUpdate()
            .map { it.toMemberRow() }
        for (membership in memberships) {
            if (membership.role != WorkspaceRole.OWNER) continue
            val wsMembers = WorkspaceMembers.selectAll()
                .where { WorkspaceMembers.workspaceId eq membership.workspaceId }
                .forUpdate()
                .map { it.toMemberRow() }
            val ownerCount = wsMembers.count { it.role == WorkspaceRole.OWNER }
            if (wsMembers.size > 1 && ownerCount <= 1) {
                throw LastWorkspaceOwnerException(
                    "transfer ownership or delete shared workspace ${membership.workspaceId} before deleting account",
                )
            }
        }
        return memberships
    }

    private suspend fun findPersonal(ownerId: String): WorkspaceRow? = coreDbQuery {
        Workspaces.selectAll()
            .where { Workspaces.personalOwnerId eq ownerId }
            .firstOrNull()
            ?.toWorkspaceRow()
    }

    private fun ResultRow.toWorkspaceRow() = WorkspaceRow(
        id = this[Workspaces.id],
        slug = this[Workspaces.slug],
        name = this[Workspaces.name],
        personalOwnerId = this[Workspaces.personalOwnerId],
        planCode = this[Workspaces.planCode],
        billingCustomerId = this[Workspaces.billingCustomerId],
        createdAt = this[Workspaces.createdAt],
        updatedAt = this[Workspaces.updatedAt],
    )

    private fun ResultRow.toMemberRow() = WorkspaceMemberRow(
        workspaceId = this[WorkspaceMembers.workspaceId],
        ownerId = this[WorkspaceMembers.ownerId],
        role = WorkspaceRole.valueOf(this[WorkspaceMembers.role]),
        createdAt = this[WorkspaceMembers.createdAt],
    )

    private fun ResultRow.toInviteRow() = WorkspaceInviteRow(
        id = this[WorkspaceInvites.id],
        workspaceId = this[WorkspaceInvites.workspaceId],
        invitedEmail = this[WorkspaceInvites.invitedEmail],
        role = WorkspaceRole.valueOf(this[WorkspaceInvites.role]),
        invitedBy = this[WorkspaceInvites.invitedBy],
        expiresAt = this[WorkspaceInvites.expiresAt],
        acceptedAt = this[WorkspaceInvites.acceptedAt],
        revokedAt = this[WorkspaceInvites.revokedAt],
        createdAt = this[WorkspaceInvites.createdAt],
    )
}
