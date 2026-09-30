package com.example.server

import com.example.server.core.CoreComposition
import com.example.server.core.JobHandlerRegistry
import com.example.server.core.MemoryJobStore
import com.example.server.core.MemoryScheduleStore
import com.example.server.core.NoOpSpendGate
import com.example.server.core.ProblemDetail
import com.example.server.core.ReadinessProbe
import com.example.server.core.Scheduler
import com.example.server.core.Worker
import com.example.server.example.ExampleEchoHandler
import com.example.server.example.ExampleJobTypes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire bound must answer 413 for every oversized body, including a
 * streamed one with no Content-Length. The refusal is enforced by
 * RequestBodyBound, which counts bytes in the request coroutine instead of
 * Ktor's RequestBodyLimit writer coroutine - with the writer, a fast reader
 * can observe a truncated stream as a parse failure instead of the limit
 * exception. The burst is kept as a determinism guard: one pass is not proof.
 */
class RequestLimitTest {

    @Test
    fun aDeclaredLengthOverTheBoundIsRefusedBeforeParsing() = testApplication {
        application {
            module(memoryComposition(), readiness = ReadinessProbe { true })
        }
        val oversized = """{"key":"k","payload":"${"a".repeat(MAX_REQUEST_BYTES.toInt())}"}"""
        val refused = client.post("/v1/example/jobs") {
            header("X-Client-Id", "client-1")
            setBody(object : OutgoingContent.ByteArrayContent() {
                override val contentType = ContentType.Application.Json
                override fun bytes() = oversized.encodeToByteArray()
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, refused.status)
    }

    @Test
    fun aStreamedBodyOverTheBoundIsRefusedOnEveryRequest() = testApplication {
        application {
            module(memoryComposition(), readiness = ReadinessProbe { true })
        }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
            }
        }
        // ReadChannelContent deliberately sends no Content-Length, so only the
        // streaming cap can refuse it. The ignored field carries the excess.
        val raw = """{"key":"k","extra":"${"a".repeat(MAX_REQUEST_BYTES.toInt())}"}"""
        val responses = coroutineScope {
            List(24) {
                async {
                    client.post("/v1/example/jobs") {
                        header("X-Client-Id", "client-1")
                        setBody(object : OutgoingContent.ReadChannelContent() {
                            override val contentType = ContentType.Application.Json
                            override fun readFrom() = ByteReadChannel(raw)
                        })
                    }
                }
            }.map { it.await() }
        }
        responses.forEachIndexed { attempt, refused ->
            assertEquals(HttpStatusCode.PayloadTooLarge, refused.status, "attempt $attempt")
            assertEquals("REQUEST_TOO_LARGE", refused.body<ProblemDetail>().code, "attempt $attempt")
        }
    }

    private fun memoryComposition(): CoreComposition {
        val store = MemoryJobStore()
        val registry = JobHandlerRegistry().register(ExampleJobTypes.ECHO, ExampleEchoHandler)
        return CoreComposition(
            jobStore = store,
            spendGate = NoOpSpendGate,
            worker = Worker(store, registry),
            scheduler = Scheduler(MemoryScheduleStore(), store),
            memoryMode = true,
        )
    }
}
