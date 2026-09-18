package com.example.server.example

import com.example.server.core.JobHandler
import com.example.server.core.JobResult
import com.example.server.core.ProductCatalog

/**
 * The worked example a new product replaces:
 *
 * - an [ExampleProductCatalog] for the product-specific data the core must not
 *   know about ([ProductCatalog] is the seam);
 * - an example [JobHandler] the registry routes `example/echo` jobs to.
 *
 * Replace both with the real product's catalog and handlers. Keep the seam
 * shapes.
 */
object ExampleProductCatalog : ProductCatalog {
    override fun authoredFallbackIds(context: String?): List<String> = emptyList()

    override fun resolveContext(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }
}

/** Echoes the payload back as the result. The smallest job that proves routing. */
val ExampleEchoHandler = JobHandler { job ->
    JobResult(result = job.payload.orEmpty(), outcome = "ECHOED")
}

object ExampleJobTypes {
    const val ECHO = "example/echo"
}
