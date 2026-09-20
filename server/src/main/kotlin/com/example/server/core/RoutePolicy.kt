package com.example.server.core

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.createRouteFromPath
import io.ktor.util.AttributeKey

/**
 * Who may call a route. Declared on the endpoint itself and enforced before the
 * handler runs, so a new endpoint cannot forget the check and an auditor reads
 * the policy from the route table rather than from the handler body.
 */
enum class RoutePolicy {
    /** No credential required: health, static catalogs, the session bootstrap. */
    PUBLIC,

    /** A registered caller: the request carries a device identity the store knows. */
    AUTHENTICATED,

    /** An operator caller. Reserved for administrative routes. */
    ADMIN,
}

/**
 * Resolves and authorizes the caller for a protected route.
 *
 * Implementations throw their own refusal (rendered by StatusPages) rather than
 * returning a boolean, so a denial can never be silently ignored. The guard is
 * the single place a product states "what counts as authenticated"; the route
 * table states "which routes need it".
 */
fun interface RouteGuard {
    suspend fun authorize(call: ApplicationCall, policy: RoutePolicy)
}

private val RouteGuardKey = AttributeKey<RouteGuard>("RouteGuard")

/**
 * Publishes the app-wide guard to the routing tree. Call once on the routing
 * root, before declaring routes:
 *
 * ```kotlin
 * routing {
 *     installRouteGuard(myGuard)
 *     get("/health", RoutePolicy.PUBLIC) { ... }
 * }
 * ```
 */
fun Route.installRouteGuard(guard: RouteGuard) {
    attributes.put(RouteGuardKey, guard)
}

class RoutePolicyConfig {
    var policy: RoutePolicy = RoutePolicy.PUBLIC
}

/**
 * Route-scoped enforcement. Installed per endpoint by the [get] / [post] /
 * [put] / [patch] / [delete] helpers below, so the policy travels with the
 * route it protects and sibling endpoints never share one policy by accident.
 */
private val RoutePolicyPlugin = createRouteScopedPlugin(
    name = "RoutePolicyPlugin",
    createConfiguration = ::RoutePolicyConfig,
) {
    val policyRoute = requireNotNull(route) { "route policy plugin installed without a route" }
    onCall { call ->
        val policy = pluginConfig.policy
        if (policy == RoutePolicy.PUBLIC) return@onCall
        val guard = policyRoute.lineage()
            .firstNotNullOfOrNull { it.attributes.getOrNull(RouteGuardKey) }
            ?: error("installRouteGuard must run on the routing root before routes are declared")
        guard.authorize(call, policy)
    }
}

/** Declare a GET endpoint with an explicit route policy. */
fun Route.get(path: String, policy: RoutePolicy, block: suspend RoutingContext.() -> Unit) =
    endpoint(HttpMethod.Get, path, policy, block)

/** Declare a POST endpoint with an explicit route policy. */
fun Route.post(path: String, policy: RoutePolicy, block: suspend RoutingContext.() -> Unit) =
    endpoint(HttpMethod.Post, path, policy, block)

/** Declare a PUT endpoint with an explicit route policy. */
fun Route.put(path: String, policy: RoutePolicy, block: suspend RoutingContext.() -> Unit) =
    endpoint(HttpMethod.Put, path, policy, block)

/** Declare a PATCH endpoint with an explicit route policy. */
fun Route.patch(path: String, policy: RoutePolicy, block: suspend RoutingContext.() -> Unit) =
    endpoint(HttpMethod.Patch, path, policy, block)

/** Declare a DELETE endpoint with an explicit route policy. */
fun Route.delete(path: String, policy: RoutePolicy, block: suspend RoutingContext.() -> Unit) =
    endpoint(HttpMethod.Delete, path, policy, block)

/**
 * Attach the policy to its own method node. A dedicated node per endpoint is
 * what lets siblings declare different policies: two routes on the same path
 * (say a public GET and an authenticated POST) each install the plugin on their
 * own leaf instead of racing for one parent.
 */
private fun Route.endpoint(
    method: HttpMethod,
    path: String,
    policy: RoutePolicy,
    block: suspend RoutingContext.() -> Unit,
) {
    val leaf = createRouteFromPath(path).createChild(HttpMethodRouteSelector(method))
    leaf.install(RoutePolicyPlugin) { this.policy = policy }
    leaf.handle(block)
}
