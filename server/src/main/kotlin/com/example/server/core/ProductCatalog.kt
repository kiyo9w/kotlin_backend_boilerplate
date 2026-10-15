package com.example.server.core

/**
 * Product catalog seam. The server reaches into a product's domain through this
 * interface rather than importing product types directly.
 *
 * The template ships the interface; the product ships the implementation.
 */
interface ProductCatalog {

    /**
     * Authored fallback ids for a job whose model call failed or was refused.
     * Returns an empty list when the product has no authored stock for the
     * given context. Never returns another product's content.
     *
     * @param context opaque product-specific context
     */
    fun authoredFallbackIds(context: String?): List<String>

    /**
     * Resolve a product-specific pair or context string to a canonical form.
     * Returns null when the context is not supported.
     */
    fun resolveContext(raw: String?): String?
}
