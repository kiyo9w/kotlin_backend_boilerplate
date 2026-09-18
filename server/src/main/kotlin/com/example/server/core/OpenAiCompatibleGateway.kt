package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One OpenAI-compatible chat-completions client.
 *
 * Every product that calls a model needs the same four things and none of them
 * are product-specific: the key stays server-side, the request is bounded by a
 * timeout, the output space is reserved, and an empty completion is an error
 * rather than an empty answer. The product owns the prompt and the schema; this
 * owns the transport.
 *
 * Works against any OpenAI-compatible endpoint — the vendor is configuration,
 * not code. A product that needs a provider-specific flow (an OAuth handshake,
 * a vendor SDK) implements [ModelGateway] itself instead of using this.
 */
class OpenAiCompatibleGateway(
    private val http: HttpClient,
    private val config: ModelConfig,
) : ModelGateway {

    override suspend fun complete(system: String, user: String): String {
        val response: ChatResponse = http.post("${config.baseUrl.trimEnd('/')}/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                ChatRequest(
                    model = config.model,
                    messages = listOf(
                        ChatMessage(role = "system", content = system),
                        ChatMessage(role = "user", content = user),
                    ),
                    maxTokens = config.maxOutputTokens,
                ),
            )
        }.body()
        return response.choices.firstOrNull()?.message?.content
            ?: error("empty completion")
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)
