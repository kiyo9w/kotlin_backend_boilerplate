package com.example.server.core

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Generic jobs table for the template. Carries the queue columns every product
 * needs: id, dedup key, job type, status, attempt fence, lease, result,
 * outcome, payload, and created_at.
 *
 * V13__generic_jobs.sql adds `job_type` and `payload` so [SqlJobStore] can
 * route and carry opaque input without a schema fork.
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
