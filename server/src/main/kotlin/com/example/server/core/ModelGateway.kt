package com.example.server.core

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
