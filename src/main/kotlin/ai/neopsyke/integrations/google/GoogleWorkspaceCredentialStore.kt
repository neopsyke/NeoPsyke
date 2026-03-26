package ai.neopsyke.integrations.google

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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class GoogleWorkspaceCredentialRecord(
    val ownerEmail: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val scopes: Set<String>,
    val expiresAtEpochSec: Long?,
    val issuedAtEpochSec: Long,
)

class GoogleWorkspaceCredentialStore(
    private val rootDir: Path,
    encryptionSecret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val encryptionKey = SecretKeySpec(
        MessageDigest.getInstance(KEY_DIGEST_ALGORITHM)
            .digest(encryptionSecret.toByteArray(StandardCharsets.UTF_8)),
        KEY_ALGORITHM,
    )

    fun save(record: GoogleWorkspaceCredentialRecord) {
        require(record.ownerEmail.isNotBlank()) { "Google Workspace owner email must not be blank." }
        require(record.accessToken.isNotBlank()) { "Google Workspace access token must not be blank." }
        require(record.refreshToken.isNotBlank()) { "Google Workspace refresh token must not be blank." }
        ensureRestrictedDirectory(rootDir)
        val encrypted = encrypt(mapper.writeValueAsBytes(record))
        atomicWrite(rootDir.resolve(CREDENTIAL_FILE_NAME), encrypted)
    }

    fun load(): GoogleWorkspaceCredentialRecord? {
        val file = rootDir.resolve(CREDENTIAL_FILE_NAME)
        if (!Files.exists(file)) {
            return null
        }
        val encrypted = Files.readString(file, StandardCharsets.UTF_8)
        return runCatching {
            mapper.readValue<GoogleWorkspaceCredentialRecord>(decrypt(encrypted))
        }.getOrNull()
    }

    fun clear() {
        Files.deleteIfExists(rootDir.resolve(CREDENTIAL_FILE_NAME))
    }

    fun hasValidCredential(): Boolean {
        val record = load() ?: return false
        val expiresAt = record.expiresAtEpochSec ?: return true
        return expiresAt > clock.instant().epochSecond
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
        ).joinToString(".")
    }

    private fun decrypt(serialized: String): ByteArray {
        val parts = serialized.split('.')
        require(parts.size == 3) { "Invalid encrypted Google credential payload." }
        require(parts[0] == CURRENT_FORMAT_VERSION.toString()) { "Unsupported credential payload version." }
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
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
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
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix") && Files.exists(path)
        }.getOrDefault(false)

    companion object {
        private const val CURRENT_FORMAT_VERSION: Int = 1
        private const val KEY_DIGEST_ALGORITHM: String = "SHA-256"
        private const val KEY_ALGORITHM: String = "AES"
        private const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS: Int = 128
        private const val IV_BYTES: Int = 12
        private const val CREDENTIAL_FILE_NAME: String = "credentials.enc"

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
