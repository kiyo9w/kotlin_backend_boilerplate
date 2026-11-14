package com.example.server

import com.example.server.core.CoreComposition
import com.example.server.core.FailClosedWebhooks
import com.example.server.core.HmacWebhookVerifier
import com.example.server.core.JobHandlerRegistry
import com.example.server.core.MemoryJobStore
import com.example.server.core.MemoryScheduleStore
import com.example.server.core.NoOpSpendGate
import com.example.server.core.ProblemDetail
import com.example.server.core.Scheduler
import com.example.server.core.StandardWebhooksVerifier
import com.example.server.core.WebhookVerifier
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
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * Webhook intake end to end: a signed delivery is accepted and enqueued, an
 * unsigned or mis-signed one is refused, an unconfigured endpoint refuses
 * everything, and a replayed delivery collapses onto the same job.
 */
class WebhookIntakeTest {

    private val secret = "test-webhook-secret"
    private val body = """{"event":"paid","id":"evt_1"}"""

    private fun sign(raw: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(raw.toByteArray()).joinToString("") { "%02x".format(it) }
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

    private fun withApp(
        verifier: WebhookVerifier = HmacWebhookVerifier(provider = "example", secret = secret),
        test: suspend (HttpClient) -> Unit,
    ): Unit = testApplication {
        application {
            module(
                memoryComposition(),
                webhooks = verifier,
            )
        }
        test(
            createClient {
                install(ContentNegotiation) {
                    json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
                }
            },
        )
    }

    private suspend fun HttpClient.deliver(raw: String, signature: String?) =
        post("/v1/example/webhooks") {
            contentType(ContentType.Application.Json)
            setBody(raw)
            if (signature != null) header("X-Signature-256", signature)
        }

    @Test
    fun aSignedDeliveryIsAcceptedAndEnqueued(): Unit = withApp { client ->
        val accepted = client.deliver(body, sign(body))

        assertEquals(HttpStatusCode.Accepted, accepted.status)
        val jobId = accepted.body<JobAcceptedDto>().jobId
        val status = client.get("/v1/example/jobs/$jobId") { header("X-Client-Id", "c") }
        assertEquals(HttpStatusCode.OK, status.status)
    }

    @Test
    fun aReplayedDeliveryCollapsesOntoTheSameJob(): Unit = withApp { client ->
        val first = client.deliver(body, sign(body)).body<JobAcceptedDto>().jobId
        val second = client.deliver(body, sign(body)).body<JobAcceptedDto>().jobId

        assertEquals(first, second, "the provider's delivery identity is the dedupe key")
    }

    @Test
    fun standardWebhooksDeliveryDedupesByMessageIdOverHttp(): Unit {
        val keyBytes = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
        val whsec = "whsec_${Base64.getEncoder().encodeToString(keyBytes)}"
        val nowSeconds = 1_760_000_000L
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(keyBytes, "HmacSHA256"))
        }
        val sig = "v1," + Base64.getEncoder().encodeToString(
            mac.doFinal("msg_polar_42.$nowSeconds.$body".toByteArray(Charsets.UTF_8)),
        )
        val verifier = StandardWebhooksVerifier(
            provider = "polar",
            secret = whsec,
            clock = { nowSeconds * 1_000L },
        )

        withApp(verifier = verifier) { client ->
            val payload = this@WebhookIntakeTest.body
            suspend fun postPolar() = client.post("/v1/example/webhooks") {
                contentType(ContentType.Application.Json)
                header("Webhook-Id", "msg_polar_42")
                header("Webhook-Timestamp", nowSeconds.toString())
                header("Webhook-Signature", sig)
                setBody(payload)
            }

            val first = postPolar()
            val second = postPolar()
            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(first.body<JobAcceptedDto>().jobId, second.body<JobAcceptedDto>().jobId)
        }
    }

    @Test
    fun anUnsignedOrMisSignedDeliveryIsRefused(): Unit = withApp { client ->
        val missing = client.deliver(body, null)
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("INVALID_WEBHOOK", missing.body<ProblemDetail>().code)

        val wrong = client.deliver(body, sign("""{"event":"other"}"""))
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("INVALID_WEBHOOK", wrong.status.let { wrong.body<ProblemDetail>().code })
    }

    @Test
    fun anUnconfiguredEndpointRefusesEvenAWellFormedSignature(): Unit =
        withApp(verifier = FailClosedWebhooks) { client ->
            val refused = client.deliver(body, sign(body))

            assertEquals(
                HttpStatusCode.Unauthorized,
                refused.status,
                "no verifier means no trust, whatever the delivery looks like",
            )
        }
}
