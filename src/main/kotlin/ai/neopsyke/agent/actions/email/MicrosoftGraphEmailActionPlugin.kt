package ai.neopsyke.agent.actions.email

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ai.neopsyke.agent.actions.ActionCapability
import ai.neopsyke.agent.support.ActionPayloadSecurity
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionDeterministicReview
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.ActionPluginHealth
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.AgentActionPluginFactory
import ai.neopsyke.agent.actions.SecretHandle
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.InstructionTrust
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.support.TextSecurity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class MicrosoftGraphEmailActionPlugin(
    private val env: Map<String, String>,
) : AgentActionPlugin {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val graphConfig = Config.fromEnv(env)

    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType("email_send"),
        dispatchable = true,
        plannerDescription = "email_send: payload is JSON with sender, recipients, subject, and body.",
        payloadGuidance = "JSON object: sender/from, to[], cc[]?, bcc[]?, subject, body_text or body_html.",
        payloadSchemaExample = """{"sender":"employee@company.com","to":["user@example.com"],"subject":"Status update","body_text":"Done."}""",
        requiresFollowUpThought = false,
        followUpPrefix = "Email send completed.",
        superegoDirectives = listOf(
            "Deny EMAIL_SEND when recipients are missing or ambiguous.",
            "Deny EMAIL_SEND when payload includes inline secrets, credentials, or key material.",
            "Deny EMAIL_SEND to out-of-policy recipient domains when domain restrictions are configured.",
            "Allow EMAIL_SEND only when sender identity is explicit or a configured default sender exists."
        ),
        capabilities = setOf(ActionCapability.PRODUCES_USER_OUTPUT),
        effectClass = ActionEffectClass.COMMIT_PRIVATE,
        supportsAutonomousCommit = true,
        allowedInstructionTrust = setOf(InstructionTrust.TRUSTED_INSTRUCTION),
    )

    override suspend fun healthCheck(): ActionPluginHealth {
        if (!graphConfig.enabled) {
            return ActionPluginHealth(
                available = false,
                detail = "Microsoft Graph email disabled by MS_GRAPH_EMAIL_ENABLED."
            )
        }
        val missing = mutableListOf<String>()
        if (graphConfig.tenantId.isBlank()) missing += "MS_GRAPH_TENANT_ID"
        if (graphConfig.clientId.isBlank()) missing += "MS_GRAPH_CLIENT_ID"
        if (graphConfig.clientSecret.isBlank()) missing += "MS_GRAPH_CLIENT_SECRET"
        if (missing.isNotEmpty()) {
            return ActionPluginHealth(
                available = false,
                detail = "Microsoft Graph email missing required env vars: ${missing.joinToString(", ")}"
            )
        }
        return ActionPluginHealth(
            available = true,
            detail = "Microsoft Graph email configured."
        )
    }

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview? {
        if (action.type != descriptor.actionType) return null
        val parsed = try {
            mapper.readValue<EmailPayload>(action.payload)
        } catch (_: Exception) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "email_send_payload_invalid_json",
                reason = "EMAIL_SEND payload must be valid JSON."
            )
        }
        val recipients = (parsed.to.orEmpty() + parsed.cc.orEmpty() + parsed.bcc.orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (recipients.isEmpty()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "email_send_recipients_missing",
                reason = "EMAIL_SEND requires at least one recipient in to/cc/bcc."
            )
        }
        if (parsed.subject?.trim().isNullOrBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "email_send_subject_missing",
                reason = "EMAIL_SEND requires a non-blank subject."
            )
        }
        val hasBody = !parsed.bodyText?.trim().isNullOrBlank() || !parsed.bodyHtml?.trim().isNullOrBlank()
        if (!hasBody) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "email_send_body_missing",
                reason = "EMAIL_SEND requires body_text or body_html."
            )
        }
        val sender = resolveSender(parsed)
        if (sender.isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "email_send_sender_missing",
                reason = "EMAIL_SEND requires sender or configured default sender."
            )
        }
        val combined = listOfNotNull(parsed.subject, parsed.bodyText, parsed.bodyHtml).joinToString("\n")
        if (ActionPayloadSecurity.containsInlineSecretMaterial(combined)) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "email_send_inline_secret_material",
                reason = "EMAIL_SEND payload appears to include secret material."
            )
        }
        if (graphConfig.allowedDomains.isNotEmpty()) {
            val disallowed = recipients.filterNot { recipient ->
                val domain = recipient.substringAfter('@', "").lowercase(Locale.ROOT)
                domain.isNotBlank() && domain in graphConfig.allowedDomains
            }
            if (disallowed.isNotEmpty()) {
                return ActionDeterministicReview(
                    allow = false,
                    ruleId = "email_send_domain_not_allowed",
                    reason = "EMAIL_SEND recipient domains are outside allowed policy."
                )
            }
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val health = healthCheck()
        if (!health.available) {
            return ActionOutcome(
                statusSummary = "Email send unavailable: ${health.detail}",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        }
        val payload = try {
            mapper.readValue<EmailPayload>(action.payload)
        } catch (_: Exception) {
            return ActionOutcome(
                statusSummary = "Email send failed: invalid JSON payload.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        }
        val sender = resolveSender(payload)
        if (sender.isBlank()) {
            return ActionOutcome(
                statusSummary = "Email send failed: missing sender.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        }
        val recipients = payload.to.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        if (recipients.isEmpty()) {
            return ActionOutcome(
                statusSummary = "Email send failed: at least one 'to' recipient is required.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        }
        return withContext(Dispatchers.IO) {
            val token = try {
                obtainAccessToken()
            } catch (ex: Exception) {
                logger.warn(ex) { "Microsoft Graph token retrieval failed." }
                return@withContext ActionOutcome(
                    statusSummary = "Email send failed: unable to obtain Microsoft Graph access token.",
                    executionStatus = ActionExecutionStatus.FAILED,
                )
            }
            val response = try {
                sendMail(token = token, sender = sender, payload = payload)
            } catch (ex: Exception) {
                logger.warn(ex) { "Microsoft Graph sendMail call failed." }
                return@withContext ActionOutcome(
                    statusSummary = "Email send failed: ${ex.message ?: "sendMail call failed"}",
                    executionStatus = ActionExecutionStatus.FAILED,
                )
            }
            if (response.success) {
                ActionOutcome(
                    statusSummary = "Email sent via Microsoft Graph. sender=$sender recipients=${recipients.size}",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                    effects = setOf(ActionEffect.TASK_PROGRESS, ActionEffect.USER_MESSAGE_DELIVERED),
                )
            } else {
                ActionOutcome(
                    statusSummary = "Email send failed: ${response.message}",
                    executionStatus = ActionExecutionStatus.FAILED,
                )
            }
        }
    }

    private fun resolveSender(payload: EmailPayload): String =
        payload.sender?.trim().orEmpty()
            .ifBlank { payload.onBehalfOf?.trim().orEmpty() }
            .ifBlank { graphConfig.defaultSender }

    private fun obtainAccessToken(): String {
        val form = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", graphConfig.clientId)
            .add("client_secret", graphConfig.clientSecret)
            .add("scope", graphConfig.scope)
            .build()
        val request = Request.Builder()
            .url("${graphConfig.authBaseUrl}/${graphConfig.tenantId}/oauth2/v2.0/token")
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("token endpoint failed (${response.code})")
            }
            val parsed: TokenResponse = mapper.readValue(body)
            return parsed.accessToken?.trim().orEmpty()
                .ifBlank { throw IllegalStateException("token endpoint returned no access_token") }
        }
    }

    private fun sendMail(token: String, sender: String, payload: EmailPayload): GraphResponse {
        val messageBodyContent = payload.bodyHtml?.takeIf { it.isNotBlank() } ?: payload.bodyText.orEmpty()
        val contentType = if (!payload.bodyHtml.isNullOrBlank()) "HTML" else "Text"
        val bodyPayload = mapOf(
            "message" to mapOf(
                "subject" to payload.subject.orEmpty(),
                "body" to mapOf(
                    "contentType" to contentType,
                    "content" to messageBodyContent
                ),
                "toRecipients" to payload.to.orEmpty().map { recipient ->
                    mapOf("emailAddress" to mapOf("address" to recipient.trim()))
                },
                "ccRecipients" to payload.cc.orEmpty().map { recipient ->
                    mapOf("emailAddress" to mapOf("address" to recipient.trim()))
                },
                "bccRecipients" to payload.bcc.orEmpty().map { recipient ->
                    mapOf("emailAddress" to mapOf("address" to recipient.trim()))
                },
                "replyTo" to payload.replyTo.orEmpty().map { recipient ->
                    mapOf("emailAddress" to mapOf("address" to recipient.trim()))
                }
            ),
            "saveToSentItems" to (payload.saveToSentItems ?: true)
        )
        val encodedSender = URLEncoder.encode(sender, StandardCharsets.UTF_8)
        val request = Request.Builder()
            .url("${graphConfig.graphBaseUrl}/users/$encodedSender/sendMail")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(mapper.writeValueAsString(bodyPayload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code in 200..299 || response.code == 202) {
                return GraphResponse(success = true, message = "accepted")
            }
            val preview = TextSecurity.preview(body, 180)
            return GraphResponse(success = false, message = "Microsoft Graph sendMail failed (${response.code}): $preview")
        }
    }

    private data class Config(
        val enabled: Boolean,
        val tenantId: String,
        val clientId: String,
        val clientSecret: String,
        val scope: String,
        val defaultSender: String,
        val allowedDomains: Set<String>,
        val authBaseUrl: String,
        val graphBaseUrl: String,
    ) {
        companion object {
            fun fromEnv(env: Map<String, String>): Config =
                Config(
                    enabled = env["MS_GRAPH_EMAIL_ENABLED"]?.trim()?.lowercase(Locale.ROOT) in setOf("1", "true", "yes"),
                    tenantId = env["MS_GRAPH_TENANT_ID"]?.trim().orEmpty(),
                    clientId = env["MS_GRAPH_CLIENT_ID"]?.trim().orEmpty(),
                    clientSecret = env["MS_GRAPH_CLIENT_SECRET"]?.trim().orEmpty(),
                    scope = env["MS_GRAPH_SCOPE"]?.trim().orEmpty()
                        .ifBlank { "https://graph.microsoft.com/.default" },
                    defaultSender = env["MS_GRAPH_DEFAULT_SENDER"]?.trim().orEmpty(),
                    allowedDomains = env["MS_GRAPH_ALLOWED_RECIPIENT_DOMAINS"]
                        ?.split(',')
                        ?.map { it.trim().lowercase(Locale.ROOT) }
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        ?: emptySet(),
                    authBaseUrl = env["MS_GRAPH_AUTH_BASE_URL"]?.trim().orEmpty()
                        .ifBlank { "https://login.microsoftonline.com" },
                    graphBaseUrl = env["MS_GRAPH_BASE_URL"]?.trim().orEmpty()
                        .ifBlank { "https://graph.microsoft.com/v1.0" }
                )
        }
    }

    private data class EmailPayload(
        val sender: String? = null,
        @field:JsonProperty("on_behalf_of")
        val onBehalfOf: String? = null,
        val to: List<String>? = null,
        val cc: List<String>? = null,
        val bcc: List<String>? = null,
        val subject: String? = null,
        @field:JsonProperty("body_text")
        val bodyText: String? = null,
        @field:JsonProperty("body_html")
        val bodyHtml: String? = null,
        @field:JsonProperty("reply_to")
        val replyTo: List<String>? = null,
        @field:JsonProperty("save_to_sent_items")
        val saveToSentItems: Boolean? = null,
    )

    private data class TokenResponse(
        @field:JsonProperty("access_token")
        val accessToken: String? = null,
    )

    private data class GraphResponse(
        val success: Boolean,
        val message: String,
    )

    companion object {
        val requiredSecretHandles: Set<SecretHandle> = setOf(
            SecretHandle("MS_GRAPH_EMAIL_ENABLED"),
            SecretHandle("MS_GRAPH_TENANT_ID"),
            SecretHandle("MS_GRAPH_CLIENT_ID"),
            SecretHandle("MS_GRAPH_CLIENT_SECRET"),
            SecretHandle("MS_GRAPH_SCOPE"),
            SecretHandle("MS_GRAPH_DEFAULT_SENDER"),
            SecretHandle("MS_GRAPH_ALLOWED_RECIPIENT_DOMAINS"),
            SecretHandle("MS_GRAPH_AUTH_BASE_URL"),
            SecretHandle("MS_GRAPH_BASE_URL"),
        )
        private const val DEFAULT_TIMEOUT_SEC: Long = 20
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class MicrosoftGraphEmailActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        MicrosoftGraphEmailActionPlugin(
            env = context.secretProvider.materialize(MicrosoftGraphEmailActionPlugin.requiredSecretHandles)
        )
}
