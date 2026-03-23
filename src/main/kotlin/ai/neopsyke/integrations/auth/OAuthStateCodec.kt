package ai.neopsyke.integrations.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class OAuthStatePayload(
    val provider: String,
    val nonce: String,
    val redirectPath: String,
    val ownerEmail: String,
    val issuedAtEpochSec: Long,
    val expiresAtEpochSec: Long,
)

class OAuthStateCodec(
    signingSecret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val signingKey = SecretKeySpec(
        MessageDigest.getInstance(KEY_DIGEST_ALGORITHM)
            .digest(signingSecret.toByteArray(StandardCharsets.UTF_8)),
        HMAC_ALGORITHM,
    )

    fun issue(
        provider: String,
        nonce: String,
        redirectPath: String,
        ownerEmail: String,
        ttlSeconds: Long,
    ): String {
        require(provider.isNotBlank()) { "OAuth state provider must not be blank." }
        require(nonce.isNotBlank()) { "OAuth state nonce must not be blank." }
        require(redirectPath.isNotBlank()) { "OAuth state redirectPath must not be blank." }
        require(ownerEmail.isNotBlank()) { "OAuth state ownerEmail must not be blank." }
        require(ttlSeconds > 0L) { "OAuth state ttlSeconds must be positive." }

        val now = clock.instant().epochSecond
        val payload = OAuthStatePayload(
            provider = provider,
            nonce = nonce,
            redirectPath = redirectPath,
            ownerEmail = ownerEmail,
            issuedAtEpochSec = now,
            expiresAtEpochSec = now + ttlSeconds,
        )
        val payloadBytes = mapper.writeValueAsBytes(payload)
        val signature = sign(payloadBytes)
        return "${base64UrlEncoder.encodeToString(payloadBytes)}.${base64UrlEncoder.encodeToString(signature)}"
    }

    fun verify(
        token: String,
        expectedProvider: String,
        expectedRedirectPath: String,
        expectedOwnerEmail: String,
    ): OAuthStatePayload? {
        val parts = token.split('.')
        if (parts.size != 2) {
            return null
        }
        val payloadBytes = runCatching { base64UrlDecoder.decode(parts[0]) }.getOrNull() ?: return null
        val expectedSignature = runCatching { base64UrlDecoder.decode(parts[1]) }.getOrNull() ?: return null
        val actualSignature = sign(payloadBytes)
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            return null
        }
        val payload = runCatching { mapper.readValue<OAuthStatePayload>(payloadBytes) }.getOrNull() ?: return null
        if (payload.provider != expectedProvider) return null
        if (payload.redirectPath != expectedRedirectPath) return null
        if (!payload.ownerEmail.equals(expectedOwnerEmail, ignoreCase = true)) return null
        if (payload.expiresAtEpochSec <= clock.instant().epochSecond) return null
        return payload
    }

    private fun sign(payloadBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(signingKey)
        return mac.doFinal(payloadBytes)
    }

    companion object {
        private const val KEY_DIGEST_ALGORITHM: String = "SHA-256"
        private const val HMAC_ALGORITHM: String = "HmacSHA256"

        private val mapper = jacksonObjectMapper()
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
        private val base64UrlDecoder = Base64.getUrlDecoder()
    }
}
