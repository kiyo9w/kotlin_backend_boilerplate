package com.example.server

import io.ktor.http.HttpStatusCode

/**
 * The one refusal type a route or service throws. StatusPages renders it as the
 * RFC 7807 ProblemDetail envelope; [code] is the stable machine code a client
 * branches on.
 */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)
