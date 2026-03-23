package ai.neopsyke.integrations.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OAuthStateCodecTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-03-23T20:45:00Z"), ZoneOffset.UTC)

    @Test
    fun `issues and verifies signed oauth state`() {
        val codec = OAuthStateCodec(signingSecret = "super-secret", clock = fixedClock)

        val token = codec.issue(
            provider = "google-workspace",
            nonce = "nonce-123",
            redirectPath = "/api/channels/google/oauth/callback",
            ownerEmail = "owner@example.com",
            ttlSeconds = 600L,
        )

        val payload = codec.verify(
            token = token,
            expectedProvider = "google-workspace",
            expectedRedirectPath = "/api/channels/google/oauth/callback",
            expectedOwnerEmail = "owner@example.com",
        )

        assertNotNull(payload)
        assertEquals("google-workspace", payload.provider)
        assertEquals("nonce-123", payload.nonce)
        assertEquals("owner@example.com", payload.ownerEmail)
        assertEquals(fixedClock.instant().epochSecond, payload.issuedAtEpochSec)
        assertEquals(fixedClock.instant().epochSecond + 600L, payload.expiresAtEpochSec)
    }

    @Test
    fun `rejects tampered oauth state`() {
        val codec = OAuthStateCodec(signingSecret = "super-secret", clock = fixedClock)
        val token = codec.issue(
            provider = "google-workspace",
            nonce = "nonce-123",
            redirectPath = "/api/channels/google/oauth/callback",
            ownerEmail = "owner@example.com",
            ttlSeconds = 600L,
        )

        val tampered = token.dropLast(1) + if (token.last() == 'A') "B" else "A"

        val payload = codec.verify(
            token = tampered,
            expectedProvider = "google-workspace",
            expectedRedirectPath = "/api/channels/google/oauth/callback",
            expectedOwnerEmail = "owner@example.com",
        )

        assertNull(payload)
    }

    @Test
    fun `rejects expired oauth state`() {
        val issuedClock = Clock.fixed(Instant.parse("2026-03-23T20:45:00Z"), ZoneOffset.UTC)
        val codec = OAuthStateCodec(signingSecret = "super-secret", clock = issuedClock)
        val token = codec.issue(
            provider = "google-workspace",
            nonce = "nonce-123",
            redirectPath = "/api/channels/google/oauth/callback",
            ownerEmail = "owner@example.com",
            ttlSeconds = 10L,
        )

        val expiredCodec = OAuthStateCodec(
            signingSecret = "super-secret",
            clock = Clock.fixed(Instant.parse("2026-03-23T20:45:11Z"), ZoneOffset.UTC),
        )

        val payload = expiredCodec.verify(
            token = token,
            expectedProvider = "google-workspace",
            expectedRedirectPath = "/api/channels/google/oauth/callback",
            expectedOwnerEmail = "owner@example.com",
        )

        assertNull(payload)
    }
}
