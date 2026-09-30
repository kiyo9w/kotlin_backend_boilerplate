package com.example.server

import com.example.server.core.CoreComposition
import com.example.server.core.JobHandlerRegistry
import com.example.server.core.NoOpSpendGate
import com.example.server.core.ReadinessProbe
import com.example.server.core.RequestId
import com.example.server.core.RouteGuard
import com.example.server.core.FailClosedWebhooks
import com.example.server.core.HmacWebhookVerifier
import com.example.server.core.RoutePolicy
import com.example.server.core.WebhookVerifier
import com.example.server.core.ServerConfig
import com.example.server.core.coreCompositionFromEnv
import com.example.server.core.get
import com.example.server.core.installRouteGuard
import com.example.server.core.post
import com.example.server.core.problemDetail
import com.example.server.core.readyRoute
import com.example.server.core.respondProblem
import com.example.server.example.ExampleEchoHandler
import com.example.server.example.ExampleJobTypes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.sql.DataSource

/**
 * Example product HTTP API. Copy this file, rename the package, and replace the
 * example routes, DTOs, and handlers. The wiring below is the convention:
 * typed config, the RFC 7807 envelope, the request id, declarative route
 * policy, `/health` and `/ready`, the worker loop, and the scheduler loop.
 */
fun main() {
    val env = ServerEnv::get
    val config = ServerConfig.fromEnv(env)
    val composition = coreCompositionFromEnv(
        env = env,
        registry = JobHandlerRegistry().register(ExampleJobTypes.ECHO, ExampleEchoHandler),
        spendGateFactory = { NoOpSpendGate },
    )
    val port = env("PORT").trim().toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(composition, config)
    }.start(wait = true)
}

fun Application.module(
    composition: CoreComposition,
    config: ServerConfig = ServerConfig.fromEnv(ServerEnv::get),
    readiness: ReadinessProbe = dataSourceReadiness(composition.dataSource),
    /**
     * Webhook intake. The default refuses every delivery: an endpoint that
     * cannot verify must not apply. A product wires [HmacWebhookVerifier] with
     * its provider's secret.
     */
    webhooks: WebhookVerifier = if (config.webhooks.secret.isBlank()) {
        FailClosedWebhooks
    } else {
        HmacWebhookVerifier(provider = "example", secret = config.webhooks.secret)
    },
) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
    }
    install(RequestId)
    // Every request body is bounded. A declared Content-Length is refused up
    // front; a chunked body is cut while it streams. Ktor's limiter proxies
    // the body through a writer coroutine, and the converter may already be
    // reading when the cap trips, so the refusal can surface wrapped inside
    // BadRequestException. The handlers below match on the cause chain, not
    // on the exception type the pipeline happens to raise - do not collapse
    // them back into one typed handler.
    install(RequestBodyLimit) {
        bodyLimit { MAX_REQUEST_BYTES }
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondProblem(call.problemDetail(cause.status, cause.code, cause.message))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondProblem(call.requestTooLarge())
        }
        exception<BadRequestException> { call, cause ->
            if (cause.requestLimitExceeded()) {
                call.respondProblem(call.requestTooLarge())
            } else {
                call.respondProblem(
                    call.problemDetail(HttpStatusCode.BadRequest, "BAD_REQUEST", "request could not be understood"),
                )
            }
        }
        exception<Throwable> { call, cause ->
            if (cause is kotlin.coroutines.cancellation.CancellationException) throw cause
            call.respondProblem(
                call.problemDetail(HttpStatusCode.InternalServerError, "INTERNAL", "Internal server error"),
            )
        }
    }
    routing {
        installRouteGuard(exampleRouteGuard(config))
        get("/health", RoutePolicy.PUBLIC) { call.respond(HealthDto()) }
        readyRoute(readiness)
        get("/v1/example/meta", RoutePolicy.PUBLIC) {
            call.respond(ExampleMetaDto(memoryMode = composition.memoryMode))
        }
        post("/v1/example/jobs", RoutePolicy.AUTHENTICATED) {
            val body = call.receive<ExampleJobRequest>()
            val job = composition.jobStore.enqueue(
                key = "example:${body.key}",
                jobType = ExampleJobTypes.ECHO,
                payload = body.payload,
            )
            call.respond(HttpStatusCode.Accepted, JobAcceptedDto(job.id))
        }
        get("/v1/example/jobs/{id}", RoutePolicy.AUTHENTICATED) {
            val job = composition.jobStore.job(call.parameters["id"].orEmpty())
                ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "job not found")
            call.respond(JobStatusDto(id = job.id, status = job.status.name, outcome = job.outcome))
        }
        // Webhook intake: verify the raw bytes, then enqueue. Never decode or
        // apply an unverified body — a refusal is a refusal.
        post("/v1/example/webhooks", RoutePolicy.PUBLIC) {
            val raw = call.receiveText()
            val signature = call.request.header("X-Signature-256")
            val verified = webhooks.verify(raw, signature)
                ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_WEBHOOK", "webhook rejected")
            val job = composition.jobStore.enqueue(
                key = "webhook:${verified.provider}:${verified.eventId}",
                jobType = ExampleJobTypes.ECHO,
                payload = verified.rawBody,
            )
            call.respond(HttpStatusCode.Accepted, JobAcceptedDto(job.id))
        }
    }
    composition.worker?.let { worker ->
        launch {
            while (isActive) {
                runCatching { worker.tick() }
                delay(250)
            }
        }
    }
    launch { composition.scheduler.runLoop() }
}

/**
 * The example guard. Replace it with the product's own account or credential
 * check. ADMIN stays closed until a product mints an operator credential, so it
 * fails closed rather than pretending to be protected.
 */
private fun exampleRouteGuard(config: ServerConfig): RouteGuard = RouteGuard { call, policy ->
    when (policy) {
        RoutePolicy.PUBLIC -> Unit
        RoutePolicy.AUTHENTICATED -> {
            val clientId = call.request.header("X-Client-Id").orEmpty()
            if (clientId.isBlank()) {
                throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "missing client id")
            }
        }
        RoutePolicy.ADMIN -> throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "admin only")
    }
}

/** Readiness for a service that may or may not have a database. Memory mode is ready. */
fun dataSourceReadiness(dataSource: DataSource?): ReadinessProbe = ReadinessProbe {
    if (dataSource == null) {
        true
    } else {
        runCatching { dataSource.connection.use { it.isValid(2) } }.getOrDefault(false)
    }
}

/**
 * The wire bound for one request body, in bytes. Deliberately a constant, not
 * an environment key: the limit is a product contract, not an operator dial.
 */
internal const val MAX_REQUEST_BYTES: Long = 200_000L

private fun Throwable.requestLimitExceeded(): Boolean =
    generateSequence(this) { it.cause }.any { it is PayloadTooLargeException }

private fun ApplicationCall.requestTooLarge() =
    problemDetail(
        HttpStatusCode.PayloadTooLarge,
        "REQUEST_TOO_LARGE",
        "request body exceeds the allowed size",
    )

@Serializable
data class HealthDto(val ok: Boolean = true)

@Serializable
data class ExampleMetaDto(val service: String = "kotlin_backend_boilerplate", val memoryMode: Boolean)

@Serializable
data class ExampleJobRequest(val key: String, val payload: String = "")

@Serializable
data class JobAcceptedDto(val jobId: String)

@Serializable
data class JobStatusDto(val id: String, val status: String, val outcome: String? = null)
