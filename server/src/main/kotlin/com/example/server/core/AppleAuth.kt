package com.example.server.core

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mints short-lived `ES256` `client_secret` JWTs from an Apple `.p8` private key
 * for Sign in with Apple token exchange and account-deletion token revocation.
 *
 * Apple rejects `client_secret` JWTs whose `exp - iat` exceeds 15,777,000 seconds
 * (180 days). Minting fresh secrets per call (default 1 hour) eliminates the
 * recurring 180-day production outage caused by static pre-signed secrets in `.env`.
 */
class AppleClientSecretSigner(
    private val teamId: String,
    private val clientId: String,
    private val keyId: String,
    privateKeyPem: String,
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val privateKey: ECPrivateKey

    init {
        require(teamId.isNotBlank()) { "teamId must not be blank" }
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(ttlSeconds in 1L..MAX_TTL_SECONDS) {
            "Apple client_secret ttlSeconds must be between 1 and $MAX_TTL_SECONDS"
        }
        privateKey = parseP256PrivateKey(privateKeyPem)
    }

    /** Return a freshly signed `ES256` JWT valid for [ttlSeconds] from [clock]. */
    fun mint(): String {
        val nowSeconds = clock() / 1_000L
        val headerJson = appleCompactJson.encodeToString(
            buildJsonObject {
                put("alg", "ES256")
                put("kid", keyId)
                put("typ", "JWT")
            },
        )
        val payloadJson = appleCompactJson.encodeToString(
            buildJsonObject {
                put("iss", teamId)
                put("iat", nowSeconds)
                put("exp", nowSeconds + ttlSeconds)
                put("aud", APPLE_AUDIENCE)
                put("sub", clientId)
            },
        )
        val headerSegment = encodeBase64Url(headerJson.toByteArray(Charsets.UTF_8))
        val payloadSegment = encodeBase64Url(payloadJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerSegment.$payloadSegment".toByteArray(Charsets.US_ASCII)
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(privateKey)
            update(signingInput)
            sign()
        }
        return "$headerSegment.$payloadSegment.${encodeBase64Url(signature)}"
    }

    companion object {
        const val DEFAULT_TTL_SECONDS: Long = 3_600L
        const val MAX_TTL_SECONDS: Long = 15_777_000L
        const val APPLE_AUDIENCE: String = "https://appleid.apple.com"

        private fun parseP256PrivateKey(pem: String): ECPrivateKey {
            val body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .filterNot { it.isWhitespace() }
            require(body.isNotEmpty()) { "Apple .p8 privateKeyPem must not be empty" }
            val der = Base64.getDecoder().decode(body)
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
            require(key is ECPrivateKey && key.params.order.bitLength() == 256) {
                "Apple .p8 key must be a P-256 EC private key"
            }
            return key
        }
    }
}

/** Hint passed to Apple's `/auth/revoke` endpoint during account deletion. */
enum class AppleTokenTypeHint(val wireValue: String) {
    REFRESH_TOKEN("refresh_token"),
    ACCESS_TOKEN("access_token"),
}

/**
 * Revokes a user's Sign in with Apple token during in-app account deletion
 * (App Store Review Guideline `5.1.1(v)`).
 */
fun interface AppleTokenRevoker {
    suspend fun revoke(token: String, tokenTypeHint: AppleTokenTypeHint): Boolean

    suspend fun revoke(token: String): Boolean = revoke(token, AppleTokenTypeHint.REFRESH_TOKEN)
}

/** Local-only no-op revoker used when Sign in with Apple is not configured. */
object NoOpAppleTokenRevoker : AppleTokenRevoker {
    override suspend fun revoke(token: String, tokenTypeHint: AppleTokenTypeHint): Boolean = true
}

/**
 * Calls Apple's `POST /auth/revoke` endpoint with a freshly minted `ES256`
 * `client_secret` so revocation never fails due to an expired static secret.
 */
class HttpAppleTokenRevoker(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val secretSigner: AppleClientSecretSigner,
    private val revokeUrl: String = DEFAULT_REVOKE_URL,
) : AppleTokenRevoker {

    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(revokeUrl.isNotBlank()) { "revokeUrl must not be blank" }
    }

    override suspend fun revoke(token: String, tokenTypeHint: AppleTokenTypeHint): Boolean {
        val trimmed = token.trim()
        require(trimmed.isNotEmpty()) { "token must not be blank" }
        val response = runCatching {
            httpClient.submitForm(
                url = revokeUrl,
                formParameters = Parameters.build {
                    append("client_id", clientId)
                    append("client_secret", secretSigner.mint())
                    append("token", trimmed)
                    append("token_type_hint", tokenTypeHint.wireValue)
                },
            )
        }.getOrNull() ?: return false
        return response.status.isSuccess()
    }

    companion object {
        const val DEFAULT_REVOKE_URL: String = "https://appleid.apple.com/auth/revoke"
    }
}

private val appleCompactJson = Json { explicitNulls = false }

private fun encodeBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
