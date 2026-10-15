package com.example.server.core

/**
 * What a [JobHandler] produces. [result] is the opaque product output stored
 * on the job row. [outcome] is a product-defined label (for example, GENERATED
 * or AUTHORED_FALLBACK). [fact] is an optional integer recorded beside the job.
 */
data class JobResult(
    val result: String,
    val outcome: String,
    val fact: Int? = null,
)

/**
 * One job type, one handler. The registry routes a claimed [JobRecord] to the
 * handler registered for its [JobRecord.jobType].
 */
fun interface JobHandler {
    suspend fun handle(job: JobRecord): JobResult
}

/**
 * Immutable registry. Build it at composition time; the worker reads it on
 * every tick.
 */
class JobHandlerRegistry(
    private val handlers: Map<String, JobHandler> = emptyMap(),
) {
    fun handler(jobType: String): JobHandler? = handlers[jobType]

    fun register(jobType: String, handler: JobHandler): JobHandlerRegistry =
        JobHandlerRegistry(handlers + (jobType to handler))

    val jobTypes: Set<String> get() = handlers.keys
}
