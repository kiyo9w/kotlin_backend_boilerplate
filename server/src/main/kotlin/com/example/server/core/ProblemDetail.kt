package com.example.server.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * RFC 7807 Problem Details error envelope. `code` is an extension member:
 * the stable machine code the KMP client branches on instead of parsing a
 * message. `detail` is human-readable and never load-bearing.
 */
@Serializable
data class ProblemDetail(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val code: String,
)

fun ApplicationCall.problemDetail(status: HttpStatusCode, code: String, detail: String?): ProblemDetail =
    ProblemDetail(
        title = status.description,
        status = status.value,
        detail = detail,
        instance = request.path(),
        code = code,
    )

private val problemJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

suspend fun ApplicationCall.respondProblem(
    problem: ProblemDetail,
    extensions: JsonObject = JsonObject(emptyMap()),
) {
    val fields = problemJson.encodeToJsonElement(problem) as JsonObject
    respondText(
        problemJson.encodeToString(JsonObject(extensions + fields)),
        ContentType("application", "problem+json"),
        HttpStatusCode.fromValue(problem.status),
    )
}
