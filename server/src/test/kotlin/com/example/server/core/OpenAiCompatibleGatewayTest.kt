package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The OpenAI-compatible gateway: the four things that are never product
 * decisions — key server-side, reserved output, both messages sent, and an
 * empty completion treated as an error rather than an empty answer.
 */
class OpenAiCompatibleGatewayTest {

    private val config = ModelConfig(
        apiKey = "sk-test",
        baseUrl = "https://model.example/v1",
        model = "gpt-4o-mini",
        contextTokens = 8_000,
        maxOutputTokens = 512,
    )

    private fun client(bodies: MutableList<String>, content: String = "hello"): HttpClient =
        HttpClient(
            MockEngine { request ->
                bodies += (request.body as TextContent).text
                respond(
                    content = """{"choices":[{"message":{"role":"assistant","content":"$content"}}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun itSendsBothMessagesAndReservesTheOutput() = runBlocking {
        val bodies = mutableListOf<String>()
        val gateway = OpenAiCompatibleGateway(client(bodies), config)

        val answer = gateway.complete(system = "be brief", user = "say hello")

        assertEquals("hello", answer)
        val root = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(config.model, root["model"]!!.jsonPrimitive.content)
        assertEquals(config.maxOutputTokens, root["max_tokens"]!!.jsonPrimitive.int)
        val messages = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("be brief", messages[0]["content"]!!.jsonPrimitive.content)
        assertEquals("say hello", messages[1]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun theKeyTravelsAsABearerAndThePathIsJoinedOnce() = runBlocking {
        val bodies = mutableListOf<String>()
        var url = ""
        val http = HttpClient(
            MockEngine { request ->
                url = request.url.toString()
                bodies += (request.body as TextContent).text
                respond(
                    content = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        // A trailing slash on the configured base must not double the separator.
        OpenAiCompatibleGateway(http, config.copy(baseUrl = "https://model.example/v1/"))
            .complete("s", "u")

        assertEquals("https://model.example/v1/chat/completions", url)
    }

    @Test
    fun anEmptyCompletionIsAnErrorNotAnEmptyAnswer() = runBlocking {
        val http = HttpClient(
            MockEngine {
                respond(
                    content = """{"choices":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val failure = assertFailsWith<IllegalStateException> {
            OpenAiCompatibleGateway(http, config).complete("s", "u")
        }
        assertTrue("empty completion" in failure.message.orEmpty())
    }
}
