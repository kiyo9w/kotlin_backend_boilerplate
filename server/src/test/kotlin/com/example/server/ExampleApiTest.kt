package com.example.server

import com.example.server.core.CoreComposition
import com.example.server.core.JobHandlerRegistry
import com.example.server.core.MemoryJobStore
import com.example.server.core.MemoryScheduleStore
import com.example.server.core.NoOpSpendGate
import com.example.server.core.ProblemDetail
import com.example.server.core.ReadinessProbe
import com.example.server.core.REQUEST_ID_HEADER
import com.example.server.core.Scheduler
import com.example.server.core.Worker
import com.example.server.example.ExampleEchoHandler
import com.example.server.example.ExampleJobTypes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The example module exercises every convention end to end: the RFC 7807
 * envelope, the declarative route policy, the request id, `/health` and
 * `/ready`, and a job round trip through the queue.
 */
class ExampleApiTest {

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

    private fun withApp(
        composition: CoreComposition = memoryComposition(),
        readiness: ReadinessProbe = ReadinessProbe { true },
        test: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application { module(composition, readiness = readiness) }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
            }
        }
        test(client)
    }

    @Test
    fun healthIsUpAndReadyFollowsTheProbe() = testApplication {
        application { module(memoryComposition(), readiness = ReadinessProbe { false }) }
        val client = createClient { }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)
    }

    @Test
    fun aPublicRouteNeedsNoCredential() = withApp { client ->
        assertEquals(HttpStatusCode.OK, client.get("/v1/example/meta").status)
    }

    @Test
    fun anAuthenticatedRouteRefusesBeforeTheHandlerRuns() = withApp { client ->
        val refused = client.post("/v1/example/jobs") {
            contentType(ContentType.Application.Json)
            setBody(ExampleJobRequest(key = "k", payload = "hello"))
        }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals("UNAUTHORIZED", refused.body<ProblemDetail>().code)

        val accepted = client.post("/v1/example/jobs") {
            header("X-Client-Id", "client-1")
            contentType(ContentType.Application.Json)
            setBody(ExampleJobRequest(key = "k", payload = "hello"))
        }
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        val jobId = accepted.body<JobAcceptedDto>().jobId
        val status = client.get("/v1/example/jobs/$jobId") { header("X-Client-Id", "client-1") }
        assertEquals(HttpStatusCode.OK, status.status)
        assertEquals(jobId, status.body<JobStatusDto>().id)
    }

    @Test
    fun errorsUseTheProblemDetailEnvelope() = withApp { client ->
        val response = client.post("/v1/example/jobs") { contentType(ContentType.Application.Json) }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(
            "application/problem+json",
            response.contentType()?.withoutParameters()?.toString(),
        )
        val problem = response.body<ProblemDetail>()
        assertEquals("about:blank", problem.type)
        assertEquals(401, problem.status)
        assertEquals("UNAUTHORIZED", problem.code)
        assertEquals("/v1/example/jobs", problem.instance)
    }

    @Test
    fun theRequestIdIsEchoedAndGeneratedWhenAbsent() = withApp { client ->
        val echoed = client.get("/health") { header(REQUEST_ID_HEADER, "abc-123") }
            .headers[REQUEST_ID_HEADER]
        assertEquals("abc-123", echoed)

        val generated = client.get("/health").headers[REQUEST_ID_HEADER]
        assertTrue(generated != null && generated.matches(Regex("[A-Za-z0-9._-]{1,128}")))
    }
}
