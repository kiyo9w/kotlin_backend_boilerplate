package com.example.server.core

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Generic jobs table for the template. Carries the queue columns every product
 * needs: id, dedup key, job type, status, attempt fence, lease, result,
 * outcome, payload, and created_at.
 *
 * the reference product's existing `jobs` table (migration V1) already carries these columns
 * under slightly different names (`job_key` for `key`, `result_card_ids` for
 * `result`). V13__generic_jobs.sql adds `job_type` and `payload` so the
 * generic [SqlJobStore] can route and carry opaque input without a schema fork.
 *
 * A new product starts from this table directly; the reference product's product-specific
 * columns (l1, l2, seed_text, proficiency, qa_rejected) live beside them.
 */
object GenericJobs : Table("jobs") {
    val id = uuid("id")
    val userId = uuid("user_id").nullable()
    val key = text("job_key")
    val jobType = text("job_type").nullable()
    val status = text("status")
    val attempt = integer("attempt")
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val lastError = text("last_error").nullable()
    val result = text("result_card_ids")
    val outcome = text("outcome").nullable()
    val payload = text("payload").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
