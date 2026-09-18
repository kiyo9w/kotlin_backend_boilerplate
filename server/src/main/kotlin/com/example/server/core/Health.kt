package com.example.server.core

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/**
 * Readiness is a question about dependencies, not about the process existing.
 * `/health` answers "the process is up" (liveness); `/ready` answers "this
 * instance can serve traffic" by running the product's probe. A probe that
 * throws is not ready: fail closed, never optimistic.
 */
fun interface ReadinessProbe {
    suspend fun ready(): Boolean
}

/** A probe for a deployment with no external dependency (memory mode). */
val AlwaysReady: ReadinessProbe = ReadinessProbe { true }

@Serializable
data class ReadinessDto(val ok: Boolean)

/**
 * Registers `GET /ready`: 200 when the probe answers true, 503 when it does
 * not or throws. Declared PUBLIC so a load balancer needs no credential.
 */
fun Route.readyRoute(probe: ReadinessProbe) {
    get("/ready", RoutePolicy.PUBLIC) {
        val ready = runCatching { probe.ready() }.getOrDefault(false)
        call.respond(
            if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            ReadinessDto(ok = ready),
        )
    }
}
