package com.example.server.core

import java.security.SecureRandom
import java.util.UUID

/**
 * Workspace membership roles ordered by authority (`rank`), used both for
 * route authorization and to clamp API-key scopes to the creator's live role.
 */
enum class WorkspaceRole(val rank: Int) {
    VIEWER(1),
    MEMBER(2),
    BILLING(3),
    ADMIN(4),
    OWNER(5);

    fun defaultAllowedKeyScopes(): Set<String> = when (this) {
        OWNER, ADMIN -> setOf("*")
        BILLING -> setOf("billing:read", "billing:write")
        MEMBER -> setOf("read", "write")
        VIEWER -> setOf("read")
    }
}

/** Thrown when an operation would leave a workspace with zero `OWNER` members. */
class LastWorkspaceOwnerException(message: String) : RuntimeException(message)

/** Thrown when creating a workspace whose normalized `slug` is already claimed. */
class WorkspaceSlugConflictException(val slug: String) :
    RuntimeException("workspace slug already taken: $slug")

/** Persisted workspace tenant row (`V9__production_saas_hardening.sql`). */
data class WorkspaceRow(
    val id: String,
    val slug: String,
    val name: String,
    val personalOwnerId: String? = null,
    val planCode: String = "free",
    val billingCustomerId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One member binding inside a workspace. */
data class WorkspaceMemberRow(
    val workspaceId: String,
    val ownerId: String,
    val role: WorkspaceRole,
    val createdAt: Long,
)

/** Persisted email-bound invitation record (raw token never stored). */
data class WorkspaceInviteRow(
    val id: String,
    val workspaceId: String,
    val invitedEmail: String,
    val role: WorkspaceRole,
    val invitedBy: String,
    val expiresAt: Long,
    val acceptedAt: Long? = null,
    val revokedAt: Long? = null,
    val createdAt: Long,
)

/** Returned once when issuing an invite; only `SHA-256(rawToken)` persists. */
data class IssuedWorkspaceInvite(
    val invite: WorkspaceInviteRow,
    val rawToken: String,
)

/** Outcome of redeeming a workspace invitation on an explicit `POST` action. */
enum class InviteAcceptOutcome {
    ACCEPTED,
    INVALID_OR_EXPIRED,
    EMAIL_MISMATCH,
    ALREADY_USED,
}

data class InviteAcceptResult(
    val outcome: InviteAcceptOutcome,
    val membership: WorkspaceMemberRow? = null,
)

/**
 * Day-one multi-tenant workspace boundary (`V9__production_saas_hardening.sql`).
 *
 * Every user automatically receives an invisible 1:1 personal workspace via
 * [ensurePersonalWorkspace] so solo consumer/prosumer flows stay zero-friction
 * while subscriptions, credit wallets, API keys, and audit logs already hang off
 * `workspace_id` without a later `user_id -> workspace_id` schema migration.
 *
 * **Tenancy bridge across `core/` stores:** In solo-only products, pass
 * [AuthSubject.ownerId] directly into [DocumentStore], [EntitlementStore],
 * [CreditBalanceStore], and [SpendGate]. In workspace-scoped products, authorize
 * the caller via [memberRole] and pass [WorkspaceRow.id] as `ownerId` into those
 * same stores so personal and shared workspaces share one persistence contract.
 */
interface WorkspaceStore {

    /** Return (or idempotently create) the 1:1 personal workspace for [ownerId]. */
    suspend fun ensurePersonalWorkspace(
        ownerId: String,
        defaultName: String = "Personal",
        defaultPlanCode: String = "free",
        now: Long = System.currentTimeMillis(),
    ): WorkspaceRow

    /** Create a shared workspace and enroll [creatorOwnerId] as [WorkspaceRole.OWNER]. */
    suspend fun createWorkspace(
        creatorOwnerId: String,
        slug: String,
        name: String,
        planCode: String = "free",
        now: Long = System.currentTimeMillis(),
    ): WorkspaceRow

    suspend fun byId(workspaceId: String): WorkspaceRow?

    suspend fun bySlug(slug: String): WorkspaceRow?

    suspend fun byBillingCustomer(billingCustomerId: String): WorkspaceRow?

    suspend fun workspacesForOwner(ownerId: String): List<WorkspaceRow>

    suspend fun memberRole(workspaceId: String, ownerId: String): WorkspaceRole?

    suspend fun members(workspaceId: String): List<WorkspaceMemberRow>

    /** Eagerly bind a provider `cus_...` customer ID prior to Checkout session creation. */
    suspend fun bindBillingCustomer(
        workspaceId: String,
        billingCustomerId: String,
        now: Long = System.currentTimeMillis(),
    ): WorkspaceRow?

    suspend fun updatePlan(
        workspaceId: String,
        planCode: String,
        now: Long = System.currentTimeMillis(),
    ): WorkspaceRow?

    /**
     * Add or update [ownerId]'s role in [workspaceId]. Throws
     * [LastWorkspaceOwnerException] if demoting the last remaining `OWNER`.
     */
    suspend fun upsertMember(
        workspaceId: String,
        ownerId: String,
        role: WorkspaceRole,
        now: Long = System.currentTimeMillis(),
    ): WorkspaceMemberRow

    /**
     * Remove [ownerId] from [workspaceId]. Throws [LastWorkspaceOwnerException]
     * if removing the last remaining `OWNER`.
     */
    suspend fun removeMember(workspaceId: String, ownerId: String): Boolean

    /** Mint a hashed invitation token scoped to [invitedEmail]. */
    suspend fun issueInvite(
        workspaceId: String,
        invitedEmail: String,
        role: WorkspaceRole,
        invitedBy: String,
        ttlMs: Long = DEFAULT_INVITE_TTL_MS,
        now: Long = System.currentTimeMillis(),
    ): IssuedWorkspaceInvite

    /**
     * Redeem [rawToken] on an explicit state-changing `POST`. Link-scanner `GET`/`HEAD`
     * previews never call this method, and an authenticated user whose [verifiedEmail]
     * does not match `invitedEmail` receives [InviteAcceptOutcome.EMAIL_MISMATCH]
     * without burning the token.
     */
    suspend fun acceptInvite(
        rawToken: String,
        acceptingOwnerId: String,
        verifiedEmail: String,
        now: Long = System.currentTimeMillis(),
    ): InviteAcceptResult

    suspend fun revokeInvite(
        workspaceId: String,
        inviteId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean

    /**
     * Fail fast with [LastWorkspaceOwnerException] if [ownerId] is the sole `OWNER`
     * of any multi-member workspace (called before destructive account deletion steps).
     */
    suspend fun assertDeletableForOwner(ownerId: String)

    /**
     * Delete solo/personal workspaces owned solely by [ownerId], remove [ownerId]
     * from shared workspaces that retain at least one other `OWNER`, and return
     * the deleted solo workspace IDs.
     */
    suspend fun deleteOrLeaveForOwner(ownerId: String): List<String>

    companion object {
        const val DEFAULT_INVITE_TTL_MS: Long = 7L * 24 * 3600_000L
    }
}

/** In-memory [WorkspaceStore] for local-only mode and fast unit tests. */
class MemoryWorkspaceStore : WorkspaceStore {

    private data class MemberKey(val workspaceId: String, val ownerId: String)
    private data class StoredInvite(val row: WorkspaceInviteRow, val tokenHash: String)

    private val lock = Any()
    private val random = SecureRandom()
    private val workspaces = LinkedHashMap<String, WorkspaceRow>()
    private val members = LinkedHashMap<MemberKey, WorkspaceMemberRow>()
    private val invites = LinkedHashMap<String, StoredInvite>()

    override suspend fun ensurePersonalWorkspace(
        ownerId: String,
        defaultName: String,
        defaultPlanCode: String,
        now: Long,
    ): WorkspaceRow {
        val cleanOwner = ownerId.trim().also { require(it.isNotEmpty()) { "ownerId must not be blank" } }
        synchronized(lock) {
            workspaces.values.firstOrNull { it.personalOwnerId == cleanOwner }?.let { return it }
            val wsId = UUID.randomUUID().toString()
            val row = WorkspaceRow(
                id = wsId,
                slug = personalWorkspaceSlug(cleanOwner),
                name = defaultName.trim().ifEmpty { "Personal" },
                personalOwnerId = cleanOwner,
                planCode = defaultPlanCode.trim().ifEmpty { "free" },
                createdAt = now,
                updatedAt = now,
            )
            workspaces[wsId] = row
            members[MemberKey(wsId, cleanOwner)] = WorkspaceMemberRow(
                workspaceId = wsId,
                ownerId = cleanOwner,
                role = WorkspaceRole.OWNER,
                createdAt = now,
            )
            return row
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
        synchronized(lock) {
            if (workspaces.values.any { it.slug == normalizedSlug }) {
                throw WorkspaceSlugConflictException(normalizedSlug)
            }
            val wsId = UUID.randomUUID().toString()
            val row = WorkspaceRow(
                id = wsId,
                slug = normalizedSlug,
                name = cleanName,
                personalOwnerId = null,
                planCode = cleanPlan,
                createdAt = now,
                updatedAt = now,
            )
            workspaces[wsId] = row
            members[MemberKey(wsId, cleanCreator)] = WorkspaceMemberRow(
                workspaceId = wsId,
                ownerId = cleanCreator,
                role = WorkspaceRole.OWNER,
                createdAt = now,
            )
            return row
        }
    }

    override suspend fun byId(workspaceId: String): WorkspaceRow? =
        synchronized(lock) { workspaces[workspaceId] }

    override suspend fun bySlug(slug: String): WorkspaceRow? {
        val clean = slug.trim().lowercase()
        return synchronized(lock) { workspaces.values.firstOrNull { it.slug == clean } }
    }

    override suspend fun byBillingCustomer(billingCustomerId: String): WorkspaceRow? {
        val clean = billingCustomerId.trim()
        if (clean.isEmpty()) return null
        return synchronized(lock) { workspaces.values.firstOrNull { it.billingCustomerId == clean } }
    }

    override suspend fun workspacesForOwner(ownerId: String): List<WorkspaceRow> =
        synchronized(lock) {
            val wsIds = members.values.filter { it.ownerId == ownerId }.map { it.workspaceId }.toSet()
            workspaces.values.filter { it.id in wsIds }.sortedBy { it.createdAt }
        }

    override suspend fun memberRole(workspaceId: String, ownerId: String): WorkspaceRole? =
        synchronized(lock) { members[MemberKey(workspaceId, ownerId)]?.role }

    override suspend fun members(workspaceId: String): List<WorkspaceMemberRow> =
        synchronized(lock) {
            members.values.filter { it.workspaceId == workspaceId }.sortedBy { it.createdAt }
        }

    override suspend fun bindBillingCustomer(
        workspaceId: String,
        billingCustomerId: String,
        now: Long,
    ): WorkspaceRow? {
        val cleanCustomer = billingCustomerId.trim().also {
            require(it.isNotEmpty()) { "billingCustomerId must not be blank" }
        }
        synchronized(lock) {
            val existing = workspaces[workspaceId] ?: return null
            val conflict = workspaces.values.firstOrNull {
                it.billingCustomerId == cleanCustomer && it.id != workspaceId
            }
            check(conflict == null) { "billingCustomerId $cleanCustomer is already bound to another workspace" }
            val updated = existing.copy(billingCustomerId = cleanCustomer, updatedAt = now)
            workspaces[workspaceId] = updated
            return updated
        }
    }

    override suspend fun updatePlan(workspaceId: String, planCode: String, now: Long): WorkspaceRow? {
        val cleanPlan = planCode.trim().also { require(it.isNotEmpty()) { "planCode must not be blank" } }
        synchronized(lock) {
            val existing = workspaces[workspaceId] ?: return null
            val updated = existing.copy(planCode = cleanPlan, updatedAt = now)
            workspaces[workspaceId] = updated
            return updated
        }
    }

    override suspend fun upsertMember(
        workspaceId: String,
        ownerId: String,
        role: WorkspaceRole,
        now: Long,
    ): WorkspaceMemberRow {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        synchronized(lock) {
            requireNotNull(workspaces[workspaceId]) { "workspace $workspaceId not found" }
            val key = MemberKey(workspaceId, ownerId)
            val current = members[key]
            if (current?.role == WorkspaceRole.OWNER && role != WorkspaceRole.OWNER) {
                val ownersCount = members.values.count {
                    it.workspaceId == workspaceId && it.role == WorkspaceRole.OWNER
                }
                if (ownersCount <= 1) {
                    throw LastWorkspaceOwnerException(
                        "cannot demote the sole owner of workspace $workspaceId",
                    )
                }
            }
            val updated = WorkspaceMemberRow(
                workspaceId = workspaceId,
                ownerId = ownerId,
                role = role,
                createdAt = current?.createdAt ?: now,
            )
            members[key] = updated
            return updated
        }
    }

    override suspend fun removeMember(workspaceId: String, ownerId: String): Boolean =
        synchronized(lock) {
            val key = MemberKey(workspaceId, ownerId)
            val current = members[key] ?: return@synchronized false
            if (current.role == WorkspaceRole.OWNER) {
                val ownersCount = members.values.count {
                    it.workspaceId == workspaceId && it.role == WorkspaceRole.OWNER
                }
                if (ownersCount <= 1) {
                    throw LastWorkspaceOwnerException(
                        "cannot remove the sole owner of workspace $workspaceId",
                    )
                }
            }
            members.remove(key) != null
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
        synchronized(lock) {
            requireNotNull(workspaces[workspaceId]) { "workspace $workspaceId not found" }
            val rawToken = randomBase64UrlToken(random)
            val row = WorkspaceInviteRow(
                id = UUID.randomUUID().toString(),
                workspaceId = workspaceId,
                invitedEmail = cleanEmail,
                role = role,
                invitedBy = invitedBy,
                expiresAt = now + ttlMs,
                createdAt = now,
            )
            invites[row.id] = StoredInvite(row, sha256Hex(rawToken))
            return IssuedWorkspaceInvite(row, rawToken)
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
        val tokenHash = sha256Hex(cleanToken)
        synchronized(lock) {
            val stored = invites.values.firstOrNull { it.tokenHash == tokenHash }
                ?: return InviteAcceptResult(InviteAcceptOutcome.INVALID_OR_EXPIRED)
            val invite = stored.row
            if (invite.revokedAt != null || invite.expiresAt <= now) {
                return InviteAcceptResult(InviteAcceptOutcome.INVALID_OR_EXPIRED)
            }
            if (invite.acceptedAt != null) {
                return InviteAcceptResult(InviteAcceptOutcome.ALREADY_USED)
            }
            if (normalizedEmail != invite.invitedEmail) {
                return InviteAcceptResult(InviteAcceptOutcome.EMAIL_MISMATCH)
            }
            invites[invite.id] = stored.copy(row = invite.copy(acceptedAt = now))
            val memberKey = MemberKey(invite.workspaceId, acceptingOwnerId)
            val existingMember = members[memberKey]
            val effectiveRole = if (existingMember != null && existingMember.role.rank >= invite.role.rank) {
                existingMember.role
            } else {
                invite.role
            }
            val membership = WorkspaceMemberRow(
                workspaceId = invite.workspaceId,
                ownerId = acceptingOwnerId,
                role = effectiveRole,
                createdAt = existingMember?.createdAt ?: now,
            )
            members[memberKey] = membership
            return InviteAcceptResult(InviteAcceptOutcome.ACCEPTED, membership)
        }
    }

    override suspend fun revokeInvite(workspaceId: String, inviteId: String, now: Long): Boolean =
        synchronized(lock) {
            val stored = invites[inviteId] ?: return@synchronized false
            if (stored.row.workspaceId != workspaceId || stored.row.revokedAt != null || stored.row.acceptedAt != null) {
                return@synchronized false
            }
            invites[inviteId] = stored.copy(row = stored.row.copy(revokedAt = now))
            true
        }

    override suspend fun assertDeletableForOwner(ownerId: String) {
        synchronized(lock) {
            checkDeletableLocked(ownerId)
        }
    }

    override suspend fun deleteOrLeaveForOwner(ownerId: String): List<String> =
        synchronized(lock) {
            checkDeletableLocked(ownerId)
            val ownedMemberships = members.values.filter { it.ownerId == ownerId }.toList()
            val deletedWorkspaceIds = ArrayList<String>()
            for (membership in ownedMemberships) {
                val wsMembers = members.values.filter { it.workspaceId == membership.workspaceId }
                if (wsMembers.size <= 1) {
                    workspaces.remove(membership.workspaceId)
                    members.entries.removeIf { it.key.workspaceId == membership.workspaceId }
                    invites.entries.removeIf { it.value.row.workspaceId == membership.workspaceId }
                    deletedWorkspaceIds += membership.workspaceId
                } else {
                    members.remove(MemberKey(membership.workspaceId, ownerId))
                }
            }
            deletedWorkspaceIds
        }

    private fun checkDeletableLocked(ownerId: String) {
        val ownerMemberships = members.values.filter {
            it.ownerId == ownerId && it.role == WorkspaceRole.OWNER
        }
        for (membership in ownerMemberships) {
            val wsMembers = members.values.filter { it.workspaceId == membership.workspaceId }
            val ownerCount = wsMembers.count { it.role == WorkspaceRole.OWNER }
            if (wsMembers.size > 1 && ownerCount <= 1) {
                throw LastWorkspaceOwnerException(
                    "transfer ownership or delete shared workspace ${membership.workspaceId} before deleting account",
                )
            }
        }
    }
}

internal fun normalizeWorkspaceSlug(raw: String): String {
    val normalized = raw.trim().lowercase()
    require(WORKSPACE_SLUG_REGEX.matches(normalized)) {
        "workspace slug must be 3..50 lowercase alphanumeric or hyphen characters: $raw"
    }
    return normalized
}

internal fun personalWorkspaceSlug(ownerId: String): String =
    "personal-${sha256Hex(ownerId).take(16)}"

private val WORKSPACE_SLUG_REGEX = Regex("^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$")
