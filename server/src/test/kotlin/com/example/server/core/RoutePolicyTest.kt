package com.example.server.core

import com.example.server.ApiException
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The endpoint helpers carry the policy with the route. PUT and PATCH get the
 * same proof as GET/POST/DELETE: the guard runs before the handler, so a
 * refused request never reaches product code and an allowed one does.
 */
class RoutePolicyTest {

    private val guard = RouteGuard { call, policy ->
        if (policy == RoutePolicy.AUTHENTICATED && call.request.header("X-Client-Id").isNullOrBlank()) {
            throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "missing client id")
        }
    }

    /** The production wiring renders refusals; a bare test app must too. */
    private fun Application.refusalsAsProblemDetail() {
        install(StatusPages) {
            exception<ApiException> { call, cause ->
                call.respondProblem(call.problemDetail(cause.status, cause.code, cause.message))
            }
        }
    }

    @Test
    fun putEnforcesTheDeclaredPolicyBeforeTheHandler() = testApplication {
        application {
            refusalsAsProblemDetail()
            routing {
                installRouteGuard(guard)
                put("/v1/example/things", RoutePolicy.AUTHENTICATED) { call.respondText("put-ok") }
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.put("/v1/example/things").status)

        val allowed = client.put("/v1/example/things") { header("X-Client-Id", "client-1") }
        assertEquals(HttpStatusCode.OK, allowed.status)
        assertEquals("put-ok", allowed.bodyAsText())
    }

    @Test
    fun patchEnforcesTheDeclaredPolicyBeforeTheHandler() = testApplication {
        application {
            refusalsAsProblemDetail()
            routing {
                installRouteGuard(guard)
                patch("/v1/example/things", RoutePolicy.AUTHENTICATED) { call.respondText("patch-ok") }
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.patch("/v1/example/things").status)

        val allowed = client.patch("/v1/example/things") { header("X-Client-Id", "client-1") }
        assertEquals(HttpStatusCode.OK, allowed.status)
        assertEquals("patch-ok", allowed.bodyAsText())
    }
}
