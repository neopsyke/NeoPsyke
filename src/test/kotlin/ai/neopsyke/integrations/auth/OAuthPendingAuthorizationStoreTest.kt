package ai.neopsyke.integrations.auth

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthPendingAuthorizationStoreTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-03-23T20:45:00Z"), ZoneOffset.UTC)

    @Test
    fun `stores and consumes encrypted pending authorization`() {
        val rootDir = Files.createTempDirectory("neopsyke-oauth-pending")
        val store = OAuthPendingAuthorizationStore(
            rootDir = rootDir,
            encryptionSecret = "test-encryption-secret",
            clock = fixedClock,
        )
        val record = OAuthPendingAuthorization(
            nonce = "nonce-123",
            provider = "google-workspace",
            ownerEmail = "owner@example.com",
            codeVerifier = "pkce-code-verifier",
            scopes = setOf("scope-a", "scope-b"),
            issuedAtEpochSec = fixedClock.instant().epochSecond,
            expiresAtEpochSec = fixedClock.instant().epochSecond + 600L,
        )

        store.save(record)

        val encryptedPayload = Files.readString(rootDir.resolve("nonce-123.enc"))
        assertFalse(encryptedPayload.contains("pkce-code-verifier"))

        val loaded = store.load("nonce-123")
        assertNotNull(loaded)
        assertEquals(record, loaded)

        val consumed = store.consume("nonce-123")
        assertEquals(record, consumed)
        assertNull(store.load("nonce-123"))
        assertFalse(rootDir.resolve("nonce-123.enc").exists())
    }

    @Test
    fun `expired pending authorization is removed on load`() {
        val rootDir = Files.createTempDirectory("neopsyke-oauth-pending-expired")
        val store = OAuthPendingAuthorizationStore(
            rootDir = rootDir,
            encryptionSecret = "test-encryption-secret",
            clock = fixedClock,
        )
        val record = OAuthPendingAuthorization(
            nonce = "expired-123",
            provider = "google-workspace",
            ownerEmail = "owner@example.com",
            codeVerifier = "pkce-code-verifier",
            scopes = setOf("scope-a"),
            issuedAtEpochSec = fixedClock.instant().epochSecond - 60L,
            expiresAtEpochSec = fixedClock.instant().epochSecond - 1L,
        )

        store.save(record)

        assertNull(store.load("expired-123"))
        assertFalse(rootDir.resolve("expired-123.enc").exists())
    }

    @Test
    fun `encrypted store creates restricted directory`() {
        val rootDir = Files.createTempDirectory("neopsyke-oauth-perms").resolve("nested")
        val store = OAuthPendingAuthorizationStore(
            rootDir = rootDir,
            encryptionSecret = "test-encryption-secret",
            clock = fixedClock,
        )

        store.save(
            OAuthPendingAuthorization(
                nonce = "nonce-123",
                provider = "google-workspace",
                ownerEmail = "owner@example.com",
                codeVerifier = "pkce-code-verifier",
                scopes = setOf("scope-a"),
                issuedAtEpochSec = fixedClock.instant().epochSecond,
                expiresAtEpochSec = fixedClock.instant().epochSecond + 600L,
            )
        )

        assertTrue(rootDir.exists())
    }
}
