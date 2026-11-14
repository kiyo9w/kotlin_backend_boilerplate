package com.example.server.core

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

private object Identities : Table("identities") {
    val issuer = text("issuer")
    val subject = text("subject")
    val ownerId = text("owner_id")
    val email = text("email").nullable()
    val emailVerified = bool("email_verified")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(issuer, subject)
}

private object OwnerAliases : Table("owner_aliases") {
    val aliasOwnerId = text("alias_owner_id")
    val canonicalOwnerId = text("canonical_owner_id")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(aliasOwnerId)
}

/**
 * SQL-backed [IdentityStore] over `identities` and `owner_aliases`
 * (`V7__identities.sql`).
 *
 * First-sight binding uses the `(issuer, subject)` primary key as the
 * concurrency gate, matching [SqlOperationStore]: if two requests race to bind
 * the same subject, one transaction commits and the other rolls back cleanly on
 * the unique constraint and reads the winner in a fresh transaction.
 */
class SqlIdentityStore : IdentityStore {

    override suspend fun authenticateOrCreate(
        identity: VerifiedIdentity,
        newOwnerId: () -> String,
        now: Long,
    ): AuthenticatedOwner {
        findBinding(identity.issuer, identity.subject)?.let { existing ->
            return AuthenticatedOwner(canonicalOwner(existing.ownerId), created = false)
        }
        val candidate = canonicalOwner(newOwnerId())
        try {
            coreDbQuery {
                Identities.insert {
                    it[issuer] = identity.issuer
                    it[subject] = identity.subject
                    it[ownerId] = candidate
                    it[email] = identity.email
                    it[emailVerified] = identity.emailVerified
                    it[createdAt] = now
                }
            }
            return AuthenticatedOwner(candidate, created = true)
        } catch (race: ExposedSQLException) {
            val winner = findBinding(identity.issuer, identity.subject) ?: throw race
            return AuthenticatedOwner(canonicalOwner(winner.ownerId), created = false)
        }
    }

    override suspend fun linkGuest(
        guestOwnerId: String,
        identity: VerifiedIdentity,
        now: Long,
    ): GuestLinkResult {
        val guest = canonicalOwner(guestOwnerId)
        findBinding(identity.issuer, identity.subject)?.let { existing ->
            return resolveExistingForGuest(existing, guest)
        }
        try {
            coreDbQuery {
                Identities.insert {
                    it[issuer] = identity.issuer
                    it[subject] = identity.subject
                    it[ownerId] = guest
                    it[email] = identity.email
                    it[emailVerified] = identity.emailVerified
                    it[createdAt] = now
                }
            }
            return GuestLinkResult.Promoted(guest)
        } catch (race: ExposedSQLException) {
            val winner = findBinding(identity.issuer, identity.subject) ?: throw race
            return resolveExistingForGuest(winner, guest)
        }
    }

    override suspend fun aliasOwner(
        aliasOwnerId: String,
        canonicalOwnerId: String,
        now: Long,
    ) {
        val source = aliasOwnerId.trim()
        val rawTarget = canonicalOwnerId.trim()
        require(source.isNotEmpty() && rawTarget.isNotEmpty()) { "owner ids must not be blank" }
        coreDbQuery {
            val target = OwnerAliases.selectAll()
                .where { OwnerAliases.aliasOwnerId eq rawTarget }
                .firstOrNull()
                ?.get(OwnerAliases.canonicalOwnerId)
                ?: rawTarget
            if (source == target) return@coreDbQuery

            OwnerAliases.update({ OwnerAliases.canonicalOwnerId eq source }) {
                it[OwnerAliases.canonicalOwnerId] = target
            }
            val existingAlias = OwnerAliases.selectAll()
                .where { OwnerAliases.aliasOwnerId eq source }
                .firstOrNull()
            if (existingAlias == null) {
                OwnerAliases.insert {
                    it[OwnerAliases.aliasOwnerId] = source
                    it[OwnerAliases.canonicalOwnerId] = target
                    it[createdAt] = now
                }
            } else if (existingAlias[OwnerAliases.canonicalOwnerId] != target) {
                OwnerAliases.update({ OwnerAliases.aliasOwnerId eq source }) {
                    it[OwnerAliases.canonicalOwnerId] = target
                }
            }
            Identities.update({ Identities.ownerId eq source }) {
                it[ownerId] = target
            }
        }
    }

    override suspend fun canonicalOwner(ownerId: String): String = coreDbQuery {
        OwnerAliases.selectAll()
            .where { OwnerAliases.aliasOwnerId eq ownerId }
            .firstOrNull()
            ?.get(OwnerAliases.canonicalOwnerId)
            ?: ownerId
    }

    override suspend fun unlinkAll(ownerId: String) {
        val canonical = canonicalOwner(ownerId)
        coreDbQuery {
            Identities.deleteWhere {
                (Identities.ownerId eq canonical) or (Identities.ownerId eq ownerId)
            }
        }
    }

    private suspend fun resolveExistingForGuest(existing: IdentityRow, guest: String): GuestLinkResult {
        val owner = canonicalOwner(existing.ownerId)
        return if (owner == guest) {
            GuestLinkResult.Promoted(guest)
        } else {
            GuestLinkResult.Conflict(existingOwnerId = owner, guestOwnerId = guest)
        }
    }

    private suspend fun findBinding(issuer: String, subject: String): IdentityRow? = coreDbQuery {
        Identities.selectAll()
            .where { (Identities.issuer eq issuer) and (Identities.subject eq subject) }
            .firstOrNull()
            ?.toRow()
    }

    private fun ResultRow.toRow() = IdentityRow(
        issuer = this[Identities.issuer],
        subject = this[Identities.subject],
        ownerId = this[Identities.ownerId],
        email = this[Identities.email],
        emailVerified = this[Identities.emailVerified],
        createdAt = this[Identities.createdAt],
    )
}
