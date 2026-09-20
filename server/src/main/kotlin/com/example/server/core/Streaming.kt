package com.example.server.core

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.flow.Flow

/**
 * One SSE frame. `event` is the wire event name; `data` is the product's JSON.
 * The core does not own product payload shapes.
 */
sealed interface StreamMessage {
    val event: String
    val data: String
}

data class StreamStarted(override val data: String) : StreamMessage { override val event = "started" }
data class StreamDelta(override val data: String) : StreamMessage { override val event = "delta" }
data class StreamComplete(override val data: String) : StreamMessage { override val event = "complete" }
data class StreamFailed(override val data: String) : StreamMessage { override val event = "error" }

/**
 * Writes [flow] as text/event-stream frames (`event:` + `data:` + blank line),
 * flushing per frame. Implemented with respondTextWriter rather than the SSE
 * plugin so a product needs no application-level install. Frames already sent
 * are never taken back: a failure after headers is a terminal `error` frame,
 * not an exception the server tries to render as JSON.
 */
suspend fun ApplicationCall.respondSse(flow: Flow<StreamMessage>) {
    respondTextWriter(ContentType.Text.EventStream) {
        flow.collect { message ->
            write("event: ${message.event}\n")
            // A data value with newlines is multiple `data:` lines per the SSE
            // grammar; the product's JSON is single-line in practice.
            message.data.lineSequence().forEach { line -> write("data: $line\n") }
            write("\n")
            flush()
        }
    }
}
