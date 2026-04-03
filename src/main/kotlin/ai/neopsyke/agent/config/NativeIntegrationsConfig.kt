package ai.neopsyke.agent.config

data class NativeIntegrationsConfig(
    val telegram: TelegramChannelConfig = TelegramChannelConfig(),
    val googleWorkspace: GoogleWorkspaceConfig = GoogleWorkspaceConfig(),
)

enum class TelegramIngressMode {
    WEBHOOK,
    POLLING,
}

data class TelegramChannelConfig(
    val enabled: Boolean = false,
    val mode: TelegramIngressMode = TelegramIngressMode.WEBHOOK,
    val webhookPath: String = DEFAULT_WEBHOOK_PATH,
    val ownerChatId: String = "",
    val ownerUserId: String = "",
    val botTokenHandle: String = DEFAULT_BOT_TOKEN_HANDLE,
    val webhookSecretHandle: String = DEFAULT_WEBHOOK_SECRET_HANDLE,
    val policyScope: ai.neopsyke.agent.model.PolicyScope = ai.neopsyke.agent.model.PolicyScope.DEFAULT,
    val sessionIdPrefix: String = DEFAULT_SESSION_ID_PREFIX,
    val requireDirectChat: Boolean = true,
    val dropUnauthorizedMessages: Boolean = true,
    val pollTimeoutSeconds: Int = DEFAULT_POLL_TIMEOUT_SECONDS,
    val pollRetryDelayMs: Long = DEFAULT_POLL_RETRY_DELAY_MS,
) {
    companion object {
        const val DEFAULT_WEBHOOK_PATH: String = "/api/channels/telegram/webhook"
        const val DEFAULT_BOT_TOKEN_HANDLE: String = "TELEGRAM_BOT_TOKEN"
        const val DEFAULT_WEBHOOK_SECRET_HANDLE: String = "TELEGRAM_WEBHOOK_SECRET"
        // Telegram inherits the top-level policyScope from AgentConfig by default.
        // Override via NEOPSYKE_TELEGRAM_POLICY_SCOPE_ID env var or YAML.
        const val DEFAULT_PRINCIPAL_ID: String = "telegram-owner"
        const val DEFAULT_SESSION_ID_PREFIX: String = "telegram"
        const val DEFAULT_POLL_TIMEOUT_SECONDS: Int = 25
        const val DEFAULT_POLL_RETRY_DELAY_MS: Long = 1_000L
    }
}

data class GoogleWorkspaceConfig(
    val enabled: Boolean = false,
    val tokenStoreDir: String = DEFAULT_TOKEN_STORE_DIR,
    val allowedOwnerEmail: String = "",
    val publicBaseUrl: String = "",
    val oauthStartPath: String = DEFAULT_START_PATH,
    val oauthClientIdHandle: String = DEFAULT_OAUTH_CLIENT_ID_HANDLE,
    val oauthClientSecretHandle: String = DEFAULT_OAUTH_CLIENT_SECRET_HANDLE,
    val oauthStateSigningSecretHandle: String = DEFAULT_OAUTH_STATE_SIGNING_SECRET_HANDLE,
    val oauthTokenEncryptionSecretHandle: String = DEFAULT_OAUTH_TOKEN_ENCRYPTION_SECRET_HANDLE,
    val callbackPath: String = DEFAULT_CALLBACK_PATH,
    val authorizationBaseUrl: String = DEFAULT_AUTHORIZATION_BASE_URL,
    val tokenBaseUrl: String = DEFAULT_TOKEN_BASE_URL,
    val requirePkce: Boolean = true,
    val requireRefreshToken: Boolean = true,
    val oauthStateTtlSeconds: Long = DEFAULT_OAUTH_STATE_TTL_SECONDS,
    val scopes: Set<String> = DEFAULT_READONLY_SCOPES,
) {
    companion object {
        const val DEFAULT_TOKEN_STORE_DIR: String = ".neopsyke/auth/google"
        const val DEFAULT_OAUTH_CLIENT_ID_HANDLE: String = "GOOGLE_OAUTH_CLIENT_ID"
        const val DEFAULT_OAUTH_CLIENT_SECRET_HANDLE: String = "GOOGLE_OAUTH_CLIENT_SECRET"
        const val DEFAULT_OAUTH_STATE_SIGNING_SECRET_HANDLE: String = "GOOGLE_OAUTH_STATE_SIGNING_SECRET"
        const val DEFAULT_OAUTH_TOKEN_ENCRYPTION_SECRET_HANDLE: String = "GOOGLE_OAUTH_TOKEN_ENCRYPTION_SECRET"
        const val DEFAULT_START_PATH: String = "/api/channels/google/oauth/start"
        const val DEFAULT_CALLBACK_PATH: String = "/api/channels/google/oauth/callback"
        const val DEFAULT_AUTHORIZATION_BASE_URL: String = "https://accounts.google.com/o/oauth2/v2/auth"
        const val DEFAULT_TOKEN_BASE_URL: String = "https://oauth2.googleapis.com/token"
        const val DEFAULT_OAUTH_STATE_TTL_SECONDS: Long = 600L
        val DEFAULT_READONLY_SCOPES: Set<String> = setOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/calendar.readonly",
        )
    }
}
