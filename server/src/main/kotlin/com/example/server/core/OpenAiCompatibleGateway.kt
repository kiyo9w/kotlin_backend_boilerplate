package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
) : StreamingModelGateway {

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

    override fun stream(system: String, user: String): Flow<String> = flow {
        val response = http.post("${config.baseUrl.trimEnd('/')}/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                ChatStreamRequest(
                    model = config.model,
                    messages = listOf(
                        ChatMessage(role = "system", content = system),
                        ChatMessage(role = "user", content = user),
                    ),
                    maxTokens = config.maxOutputTokens,
                    stream = true,
                ),
            )
        }
        if (!response.status.isSuccess()) {
            error("model stream failed with ${response.status}")
        }
        var sawContent = false
        val lines = response.bodyAsChannel()
        while (true) {
            val line = lines.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break
            val chunk = streamJson.decodeFromString<ChatStreamChunk>(payload)
            val content = chunk.choices.firstOrNull()?.delta?.content ?: continue
            sawContent = true
            emit(content)
        }
        if (!sawContent) error("empty completion")
    }
}

private val streamJson = Json { ignoreUnknownKeys = true }

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

/**
 * `stream` has no default on purpose: a caller's Json may not encode defaults,
 * and a streaming request that silently drops the flag is not a stream.
 */
@Serializable
private data class ChatStreamRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean,
)

@Serializable
private data class ChatStreamChunk(val choices: List<ChatStreamChoice> = emptyList())

@Serializable
private data class ChatStreamChoice(val delta: ChatStreamDelta? = null)

@Serializable
private data class ChatStreamDelta(val content: String? = null)
