package com.example.server.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Persisted hashed 6-digit email verification code (`V8__commercial_backbone.sql`). */
data class EmailCodeRow(
    val id: String,
    val email: String,
    val purpose: String,
    val codeHash: String,
    val attempts: Int,
    val maxAttempts: Int,
    val expiresAt: Long,
    val consumedAt: Long?,
    val createdAt: Long,
)

/** Freshly minted email code; [code] is returned once for delivery and never stored in plaintext. */
data class IssuedEmailCode(
    val id: String,
    val email: String,
    val code: String,
    val expiresAt: Long,
)

/** Outcome of verifying a user-entered 6-digit email code. */
sealed interface EmailCodeCheck {
    /** Code matched inside its validity window and has now been atomically consumed. */
    data object Verified : EmailCodeCheck

    /** Code did not match; attempts were incremented and retries remain. */
    data object InvalidCode : EmailCodeCheck

    /** No open code exists for `(email, purpose)`, or its TTL has elapsed. */
    data object ExpiredOrMissing : EmailCodeCheck

    /** Attempt ceiling reached; the code is burned and the user must request a new one. */
    data object LockedOut : EmailCodeCheck
}

/**
 * Persistence seam for 6-digit email sign-in / verification codes.
 *
 * Codes replace magic links so corporate security scanners (such as Outlook
 * SafeLinks) cannot pre-click and burn one-time tokens before the recipient
 * opens the message, and mobile users stay inside the app instead of jumping to
 * an external browser tab.
 */
interface EmailCodeStore {
    /** Retire any prior open code for `(row.email, row.purpose)` and insert [row] atomically. */
    suspend fun issueReplacingOpen(row: EmailCodeRow)

    /**
     * Atomically check [codeHash] against the active code for `(email, purpose)`.
     *
     * Consumes the code on a match, or increments `attempts` on a mismatch
     * (locking and retiring the code once `attempts` reaches `maxAttempts`).
     */
    suspend fun verifyAndConsume(
        email: String,
        purpose: String,
        codeHash: String,
        now: Long,
    ): EmailCodeCheck
}

/** In-memory [EmailCodeStore] for local-only mode and fast unit tests. */
class MemoryEmailCodeStore : EmailCodeStore {

    private val lock = Any()
    private val rows = LinkedHashMap<String, EmailCodeRow>()

    override suspend fun issueReplacingOpen(row: EmailCodeRow) {
        synchronized(lock) {
            rows.entries.forEach { entry ->
                val cur = entry.value
                if (cur.email == row.email && cur.purpose == row.purpose && cur.consumedAt == null) {
                    entry.setValue(cur.copy(consumedAt = row.createdAt))
                }
            }
            rows[row.id] = row
        }
    }

    override suspend fun verifyAndConsume(
        email: String,
        purpose: String,
        codeHash: String,
        now: Long,
    ): EmailCodeCheck {
        synchronized(lock) {
            val active = rows.values
                .filter { it.email == email && it.purpose == purpose && it.consumedAt == null }
                .maxByOrNull { it.createdAt }
                ?: return EmailCodeCheck.ExpiredOrMissing

            if (active.expiresAt <= now) {
                rows[active.id] = active.copy(consumedAt = now)
                return EmailCodeCheck.ExpiredOrMissing
            }
            if (active.attempts >= active.maxAttempts) {
                rows[active.id] = active.copy(consumedAt = now)
                return EmailCodeCheck.LockedOut
            }
            val matched = MessageDigest.isEqual(
                active.codeHash.toByteArray(Charsets.US_ASCII),
                codeHash.toByteArray(Charsets.US_ASCII),
            )
            if (matched) {
                rows[active.id] = active.copy(consumedAt = now)
                return EmailCodeCheck.Verified
            }
            val nextAttempts = active.attempts + 1
            val exhausted = nextAttempts >= active.maxAttempts
            rows[active.id] = active.copy(
                attempts = nextAttempts,
                consumedAt = if (exhausted) now else null,
            )
            return if (exhausted) EmailCodeCheck.LockedOut else EmailCodeCheck.InvalidCode
        }
    }
}

/**
 * Mints and verifies hashed 6-digit email OTP codes.
 *
 * Raw codes never touch [EmailCodeStore]; digests bind `(normalizedEmail, purpose, code)`
 * together with an optional server [hashPepper] so a 6-digit digest cannot be
 * compared across recipients or purposes.
 */
class EmailCodeService(
    private val store: EmailCodeStore,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val hashPepper: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    init {
        require(ttlMs > 0) { "ttlMs must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    suspend fun issue(email: String, purpose: String = DEFAULT_PURPOSE): IssuedEmailCode {
        val normalizedEmail = normalizeEmailAddress(email)
        val normalizedPurpose = purpose.trim().also { require(it.isNotEmpty()) { "purpose must not be blank" } }
        val now = clock()
        val expiresAt = now + ttlMs
        val code = "%06d".format(random.nextInt(1_000_000))
        val id = UUID.randomUUID().toString()
        store.issueReplacingOpen(
            EmailCodeRow(
                id = id,
                email = normalizedEmail,
                purpose = normalizedPurpose,
                codeHash = digest(normalizedEmail, normalizedPurpose, code),
                attempts = 0,
                maxAttempts = maxAttempts,
                expiresAt = expiresAt,
                consumedAt = null,
                createdAt = now,
            ),
        )
        return IssuedEmailCode(id = id, email = normalizedEmail, code = code, expiresAt = expiresAt)
    }

    suspend fun verify(
        email: String,
        code: String,
        purpose: String = DEFAULT_PURPOSE,
    ): EmailCodeCheck {
        val normalizedEmail = normalizeEmailAddress(email)
        val normalizedPurpose = purpose.trim().also { require(it.isNotEmpty()) { "purpose must not be blank" } }
        val trimmedCode = code.trim()
        val candidateHash = if (trimmedCode.length == CODE_DIGITS && trimmedCode.all(Char::isDigit)) {
            digest(normalizedEmail, normalizedPurpose, trimmedCode)
        } else {
            MALFORMED_CODE_SENTINEL
        }
        return store.verifyAndConsume(
            email = normalizedEmail,
            purpose = normalizedPurpose,
            codeHash = candidateHash,
            now = clock(),
        )
    }

    private fun digest(email: String, purpose: String, code: String): String =
        sha256Hex("${email.length}:$email|${purpose.length}:$purpose|$code|$hashPepper")

    companion object {
        const val CODE_DIGITS: Int = 6
        const val DEFAULT_TTL_MS: Long = 10 * 60_000L
        const val DEFAULT_MAX_ATTEMPTS: Int = 5
        const val DEFAULT_PURPOSE: String = "sign_in"
        private val MALFORMED_CODE_SENTINEL: String = "invalid:" + "0".repeat(56)
    }
}
