package com.example.server.core

/**
 * What a [JobHandler] produces. [result] is the opaque product output stored
 * on the job row. [outcome] is a product-defined label (the reference product: GENERATED,
 * AUTHORED_FALLBACK). [fact] is an optional integer the store records beside
 * the job (the reference product: QA rejection count).
 */
data class JobResult(
    val result: String,
    val outcome: String,
    val fact: Int? = null,
)

/**
 * One job type, one handler. The registry routes a claimed [JobRecord] to the
 * handler registered for its [JobRecord.jobType].
 *
 * Lifted from the brief's seam: "A job-handler registry: a job type string
 * maps to a handler. A boilerplate cannot know what a job produces."
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
