package com.example.server.core

/**
 * Generic job record. Every product's jobs table carries these columns; the
 * product adds its own domain columns beside them.
 *
 * [jobType] routes the job to a [JobHandler] in the registry. [payload] is the
 * opaque product-specific input the handler receives. [result] is the opaque
 * product-specific output the handler writes on success.
 */
data class JobRecord(
    val id: String,
    val key: String,
    val jobType: String,
    val status: JobState,
    val attempt: Int,
    val leaseExpiresAtEpochMs: Long?,
    val lastError: String?,
    val result: String?,
    val outcome: String?,
    val payload: String?,
    val createdAtEpochMs: Long,
)
