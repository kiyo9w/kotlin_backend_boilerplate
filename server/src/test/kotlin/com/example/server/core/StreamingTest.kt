package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSE over a real test application: the frames reach the client in order with
 * the event-stream content type, and a failure is a frame rather than an
 * exception. The streaming gateway is proven against a canned SSE body.
 */
class StreamingTest {

    @Test
    fun sseFramesAreWrittenInOrderWithTheEventStreamContentType() = testApplication {
        application {
            routing {
                get("/v1/stream", RoutePolicy.PUBLIC) {
                    call.respondSse(
                        flowOf(
                            StreamStarted("""{"interpretation":"s-1"}"""),
                            StreamDelta("""{"text":"Hel"}"""),
                            StreamDelta("""{"text":"lo"}"""),
                            StreamComplete("""{"interpretation":"s-1"}"""),
                        ),
                    )
                }
            }
        }

        val response = client.get("/v1/stream")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/event-stream", response.contentType()?.withoutParameters()?.toString())
        assertEquals(
            "event: started\ndata: {\"interpretation\":\"s-1\"}\n\n" +
                "event: delta\ndata: {\"text\":\"Hel\"}\n\n" +
                "event: delta\ndata: {\"text\":\"lo\"}\n\n" +
                "event: complete\ndata: {\"interpretation\":\"s-1\"}\n\n",
            response.bodyAsText(),
        )
    }

    @Test
    fun aFailedFrameIsDeliveredAsAFrameNotAnException() = testApplication {
        application {
            routing {
                get("/v1/stream", RoutePolicy.PUBLIC) {
                    call.respondSse(
                        flowOf(
                            StreamStarted("{}"),
                            StreamFailed("""{"code":"MODEL_UNAVAILABLE"}"""),
                        ),
                    )
                }
            }
        }

        val response = client.get("/v1/stream")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "event: started\ndata: {}\n\nevent: error\ndata: {\"code\":\"MODEL_UNAVAILABLE\"}\n\n",
            response.bodyAsText(),
        )
    }

    private val config = ModelConfig(
        apiKey = "sk-test",
        baseUrl = "https://model.example/v1",
        model = "gpt-4o-mini",
        contextTokens = 8_000,
        maxOutputTokens = 512,
    )

    private fun gateway(engine: MockEngine) = OpenAiCompatibleGateway(
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        config,
    )

    @Test
    fun streamEmitsTheContentDeltasInOrder() = runBlocking<Unit> {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = (request.body as TextContent).text
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
                    "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

        val deltas = gateway(engine).stream("be brief", "say hello").toList()

        assertEquals(listOf("Hel", "lo"), deltas)
        val root = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("true", root["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun anEmptyStreamIsAnErrorNotAnEmptyAnswer() = runBlocking<Unit> {
        val engine = MockEngine {
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

        val failure = assertFailsWith<IllegalStateException> {
            gateway(engine).stream("s", "u").toList()
        }
        assertTrue("empty completion" in failure.message.orEmpty())
    }

    @Test
    fun aNonSuccessResponseThrows() = runBlocking<Unit> {
        val engine = MockEngine {
            respond(
                content = "upstream down",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }

        assertFailsWith<IllegalStateException> {
            gateway(engine).stream("s", "u").toList()
        }
    }
}
