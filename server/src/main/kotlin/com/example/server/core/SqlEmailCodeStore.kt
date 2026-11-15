package com.example.server.core

import java.security.MessageDigest
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

internal object EmailCodes : Table("email_codes") {
    val id = text("id")
    val email = text("email")
    val purpose = text("purpose")
    val codeHash = text("code_hash")
    val attempts = integer("attempts")
    val maxAttempts = integer("max_attempts")
    val expiresAt = long("expires_at")
    val consumedAt = long("consumed_at").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed [EmailCodeStore] over `email_codes` (`V8__commercial_backbone.sql`).
 *
 * Verification locks the active unconsumed row `FOR UPDATE` so parallel guesses
 * cannot bypass `max_attempts`, and consumption predicates on `consumed_at IS NULL`
 * so concurrent submissions of the valid code let at most one caller succeed.
 */
class SqlEmailCodeStore : EmailCodeStore {

    override suspend fun issueReplacingOpen(row: EmailCodeRow) {
        coreDbQuery {
            EmailCodes.update({
                (EmailCodes.email eq row.email) and
                    (EmailCodes.purpose eq row.purpose) and
                    EmailCodes.consumedAt.isNull()
            }) {
                it[consumedAt] = row.createdAt
            }
            EmailCodes.insert {
                it[id] = row.id
                it[email] = row.email
                it[purpose] = row.purpose
                it[codeHash] = row.codeHash
                it[attempts] = row.attempts
                it[maxAttempts] = row.maxAttempts
                it[expiresAt] = row.expiresAt
                it[consumedAt] = row.consumedAt
                it[createdAt] = row.createdAt
            }
        }
    }

    override suspend fun verifyAndConsume(
        email: String,
        purpose: String,
        codeHash: String,
        now: Long,
    ): EmailCodeCheck = coreDbQuery {
        val active = EmailCodes.selectAll()
            .where {
                (EmailCodes.email eq email) and
                    (EmailCodes.purpose eq purpose) and
                    EmailCodes.consumedAt.isNull()
            }
            .orderBy(EmailCodes.createdAt, SortOrder.DESC)
            .forUpdate()
            .firstOrNull()
            ?: return@coreDbQuery EmailCodeCheck.ExpiredOrMissing

        val id = active[EmailCodes.id]
        val expiresAt = active[EmailCodes.expiresAt]
        val attempts = active[EmailCodes.attempts]
        val maxAttempts = active[EmailCodes.maxAttempts]
        val storedHash = active[EmailCodes.codeHash]

        if (expiresAt <= now) {
            EmailCodes.update({ (EmailCodes.id eq id) and EmailCodes.consumedAt.isNull() }) {
                it[consumedAt] = now
            }
            return@coreDbQuery EmailCodeCheck.ExpiredOrMissing
        }
        if (attempts >= maxAttempts) {
            EmailCodes.update({ (EmailCodes.id eq id) and EmailCodes.consumedAt.isNull() }) {
                it[consumedAt] = now
            }
            return@coreDbQuery EmailCodeCheck.LockedOut
        }

        val matched = MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.US_ASCII),
            codeHash.toByteArray(Charsets.US_ASCII),
        )
        if (matched) {
            val updated = EmailCodes.update({ (EmailCodes.id eq id) and EmailCodes.consumedAt.isNull() }) {
                it[consumedAt] = now
            }
            return@coreDbQuery if (updated == 1) EmailCodeCheck.Verified else EmailCodeCheck.ExpiredOrMissing
        }

        val nextAttempts = attempts + 1
        val exhausted = nextAttempts >= maxAttempts
        EmailCodes.update({ (EmailCodes.id eq id) and EmailCodes.consumedAt.isNull() }) {
            it[EmailCodes.attempts] = nextAttempts
            if (exhausted) it[consumedAt] = now
        }
        if (exhausted) EmailCodeCheck.LockedOut else EmailCodeCheck.InvalidCode
    }
}
