package com.example.server.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

private val LOWER_HEX: HexFormat = HexFormat.of()

/** Lowercase 64-character `SHA-256` hex digest of [input] encoded as UTF-8. */
internal fun sha256Hex(input: String): String =
    LOWER_HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))

/** URL-safe, unpadded Base64 token over [byteLength] random bytes (43 characters for 32 bytes). */
internal fun randomBase64UrlToken(random: SecureRandom, byteLength: Int = 32): String {
    require(byteLength > 0) { "byteLength must be positive" }
    val bytes = ByteArray(byteLength)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Trim and lowercase an email address, rejecting missing local/domain parts,
 * multiple `@` signs, or internal whitespace.
 */
internal fun normalizeEmailAddress(raw: String): String {
    val clean = raw.trim().lowercase()
    val at = clean.indexOf('@')
    require(
        at in 1 until clean.lastIndex &&
            clean.lastIndexOf('@') == at &&
            !clean.any(Char::isWhitespace),
    ) {
        "email address must contain non-empty local and domain parts"
    }
    return clean
}
