package com.example.server.core

/**
 * Product catalog seam. The server reaches into a product's domain through this
 * interface rather than importing product types directly.
 *
 * the reference product's implementation (`ExampleProductCatalog`) wraps `SampleFeed`,
 * `matchesSpace`, and `LanguageSpaceCatalog`. A second product implements the
 * same interface with its own domain. The template ships the interface; the
 * product ships the implementation.
 *
 * Lifted from the brief's seam table: "SampleFeed / matchesSpace /
 * LanguageSpaceCatalog imports in the server → Moved behind product
 * repositories. A second product has no language spaces."
 */
interface ProductCatalog {

    /**
     * Authored fallback ids for a job whose model call failed or was refused.
     * Returns an empty list when the product has no authored stock for the
     * given context. Never returns another product's content.
     *
     * @param context opaque product-specific context (the reference product: the language pair)
     */
    fun authoredFallbackIds(context: String?): List<String>

    /**
     * Resolve a product-specific pair or context string to a canonical form.
     * Returns null when the context is not supported.
     *
     * the reference product: resolves (l1, l2) to a `LanguageSpaceId`.
     * A second product: resolves whatever its domain needs.
     */
    fun resolveContext(raw: String?): String?
}
