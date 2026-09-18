package com.example.server.core

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.util.UUID

/** The correlation header every request and every log line carries. */
const val REQUEST_ID_HEADER = "X-Request-Id"

private val RequestIdKey = AttributeKey<String>("RequestId")
private val RequestStartKey = AttributeKey<Long>("RequestStartNanos")

/**
 * A bound on a caller-supplied request id: short, and made only of characters
 * that are safe in a header and a log line. Anything else is replaced with a
 * generated id rather than trusted and echoed.
 */
private val SAFE_REQUEST_ID = Regex("[A-Za-z0-9._-]{1,128}")

/** The current request's correlation id, or a generated placeholder outside a request. */
val ApplicationCall.requestId: String
    get() = attributes.getOrNull(RequestIdKey) ?: "unknown"

/** One structured access-log record. */
data class RequestLogLine(
    val requestId: String,
    val method: String,
    val path: String,
    val status: Int,
    val durationMs: Long,
)

/**
 * Emits one structured line per completed request. The format is explicit
 * key=value so any log shipper can index it without a parser config.
 */
object RequestLog {
    private val log = LoggerFactory.getLogger("http.request")

    fun emit(line: RequestLogLine) {
        log.info(
            "method={} path={} status={} duration_ms={} request_id={}",
            line.method,
            line.path,
            line.status,
            line.durationMs,
            line.requestId,
        )
    }
}

class RequestIdConfig {
    /** Overridable so a test can assert the structured line without a log backend. */
    var sink: (RequestLogLine) -> Unit = RequestLog::emit
}

/**
 * Assigns a request id, echoes it in the response, and logs one structured
 * line when the response is sent. Installed once on the application, before
 * routing, so every call - including failures rendered by StatusPages - is
 * correlated.
 */
val RequestId = createApplicationPlugin(
    name = "RequestId",
    createConfiguration = ::RequestIdConfig,
) {
    onCall { call ->
        val incoming = call.request.headers[REQUEST_ID_HEADER]
        val id = incoming?.takeIf { SAFE_REQUEST_ID.matches(it) } ?: UUID.randomUUID().toString()
        call.attributes.put(RequestIdKey, id)
        call.attributes.put(RequestStartKey, System.nanoTime())
        call.response.headers.append(REQUEST_ID_HEADER, id)
    }
    on(ResponseSent) { call ->
        val started = call.attributes.getOrNull(RequestStartKey) ?: System.nanoTime()
        pluginConfig.sink(
            RequestLogLine(
                requestId = call.attributes.getOrNull(RequestIdKey) ?: "unknown",
                method = call.request.httpMethod.value,
                path = call.request.path(),
                status = call.response.status()?.value ?: 0,
                durationMs = (System.nanoTime() - started) / 1_000_000,
            ),
        )
    }
}
