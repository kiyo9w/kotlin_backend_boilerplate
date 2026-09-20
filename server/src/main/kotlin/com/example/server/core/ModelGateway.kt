package com.example.server.core

import kotlinx.coroutines.flow.Flow

/**
 * Generic model gateway. Every product that calls a model implements this
 * interface; the template ships one OpenAI-compatible implementation. Keys
 * stay server-side only — never in a client build, never in source.
 *
 * Lifted from the reference product's `XaiChat` shape (the reference ranking: "depend: Ktor
 * client → api.x.ai. Keys stay server-side."). The interface is the seam; the
 * product owns the prompt and the schema.
 */
interface ModelGateway {
    /**
     * Send one completion request. [system] is the system prompt; [user] is the
     * user message. Returns the raw model output (the product parses it).
     *
     * Implementations must throw on transport failure, never return a partial
     * or silently empty result.
     */
    suspend fun complete(system: String, user: String): String
}

/**
 * A gateway that can stream content deltas. Implementations must throw on
 * transport failure, never emit a silent empty result.
 */
interface StreamingModelGateway : ModelGateway {
    /**
     * Content deltas in order. The stream ends after the last delta; an empty
     * stream is an error.
     */
    fun stream(system: String, user: String): Flow<String>
}
