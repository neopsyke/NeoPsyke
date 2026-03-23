package ai.neopsyke.integrations.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class OAuthPendingAuthorization(
    val nonce: String,
    val provider: String,
    val ownerEmail: String,
    val codeVerifier: String,
    val scopes: Set<String>,
    val issuedAtEpochSec: Long,
    val expiresAtEpochSec: Long,
)

class OAuthPendingAuthorizationStore(
    private val rootDir: Path,
    encryptionSecret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val encryptionKey = SecretKeySpec(
        MessageDigest.getInstance(KEY_DIGEST_ALGORITHM)
            .digest(encryptionSecret.toByteArray(StandardCharsets.UTF_8)),
        KEY_ALGORITHM,
    )

    fun save(record: OAuthPendingAuthorization) {
        require(record.nonce.isNotBlank()) { "OAuth pending authorization nonce must not be blank." }
        require(record.provider.isNotBlank()) { "OAuth pending authorization provider must not be blank." }
        require(record.codeVerifier.isNotBlank()) { "OAuth pending authorization code verifier must not be blank." }

        ensureRestrictedDirectory(rootDir)
        val serialized = mapper.writeValueAsBytes(record)
        val encrypted = encrypt(serialized)
        val target = fileForNonce(record.nonce)
        atomicWrite(target, encrypted)
    }

    fun load(nonce: String): OAuthPendingAuthorization? {
        val target = fileForNonce(nonce)
        if (!Files.exists(target)) {
            return null
        }
        val encrypted = Files.readString(target, StandardCharsets.UTF_8)
        val record = runCatching {
            mapper.readValue<OAuthPendingAuthorization>(decrypt(encrypted))
        }.getOrNull() ?: return null
        if (record.expiresAtEpochSec <= clock.instant().epochSecond) {
            Files.deleteIfExists(target)
            return null
        }
        return record
    }

    fun consume(nonce: String): OAuthPendingAuthorization? {
        val record = load(nonce) ?: return null
        Files.deleteIfExists(fileForNonce(nonce))
        return record
    }

    private fun fileForNonce(nonce: String): Path {
        val safeNonce = nonce.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { error("OAuth pending authorization nonce is invalid.") }
        return rootDir.resolve("$safeNonce.enc")
    }

    private fun encrypt(payload: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(payload)
        return listOf(
            CURRENT_FORMAT_VERSION.toString(),
            base64UrlEncoder.encodeToString(iv),
            base64UrlEncoder.encodeToString(ciphertext),
        ).joinToString(separator = ".")
    }

    private fun decrypt(serializedCiphertext: String): ByteArray {
        val parts = serializedCiphertext.split('.')
        require(parts.size == 3) { "Invalid encrypted OAuth pending authorization payload." }
        require(parts[0] == CURRENT_FORMAT_VERSION.toString()) { "Unsupported encrypted payload version." }
        val iv = base64UrlDecoder.decode(parts[1])
        val ciphertext = base64UrlDecoder.decode(parts[2])
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun ensureRestrictedDirectory(dir: Path) {
        Files.createDirectories(dir)
        if (supportsPosix(dir)) {
            Files.setPosixFilePermissions(dir, DIRECTORY_PERMISSIONS)
        }
    }

    private fun atomicWrite(target: Path, content: String) {
        ensureRestrictedDirectory(target.parent)
        val tempFile = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8)
            if (supportsPosix(tempFile)) {
                Files.setPosixFilePermissions(tempFile, FILE_PERMISSIONS)
            }
            try {
                Files.move(
                    tempFile,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            if (supportsPosix(target)) {
                Files.setPosixFilePermissions(target, FILE_PERMISSIONS)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun supportsPosix(path: Path): Boolean =
        runCatching {
            FileSystems.getDefault()
                .supportedFileAttributeViews()
                .contains("posix") && Files.exists(path)
        }.getOrDefault(false)

    companion object {
        private const val CURRENT_FORMAT_VERSION: Int = 1
        private const val KEY_DIGEST_ALGORITHM: String = "SHA-256"
        private const val KEY_ALGORITHM: String = "AES"
        private const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS: Int = 128
        private const val IV_BYTES: Int = 12

        private val mapper = jacksonObjectMapper()
        private val secureRandom = SecureRandom()
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
        private val base64UrlDecoder = Base64.getUrlDecoder()
        private val DIRECTORY_PERMISSIONS: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rwx------")
        private val FILE_PERMISSIONS: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rw-------")
    }
}
