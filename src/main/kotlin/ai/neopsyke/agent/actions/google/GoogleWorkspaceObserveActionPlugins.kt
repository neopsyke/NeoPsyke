package ai.neopsyke.agent.actions.google

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.actions.ActionCapability
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionDeterministicReview
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.ActionPluginHealth
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.AgentActionPluginFactory
import ai.neopsyke.agent.actions.SecretHandle
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ContentKind
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SourceDescriptor
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.support.ExternalContentPipeline
import ai.neopsyke.integrations.google.CalendarEventsRequest
import ai.neopsyke.integrations.google.GmailSearchRequest
import ai.neopsyke.integrations.google.GoogleWorkspaceApiClient
import ai.neopsyke.integrations.google.GoogleWorkspaceCredentialStore
import ai.neopsyke.agent.support.PromptInjectionDefense
import java.nio.file.Paths

private const val GMAIL_SEARCH_ACTION_ID: String = "gmail_observe_search"
private const val GMAIL_MESSAGE_ACTION_ID: String = "gmail_observe_message"
private const val CALENDAR_EVENTS_ACTION_ID: String = "calendar_observe_events"

abstract class GoogleWorkspaceObserveActionPlugin(
    final override val descriptor: ActionDescriptor,
    private val clientFactory: () -> GoogleWorkspaceApiClient?,
) : AgentActionPlugin {
    override suspend fun healthCheck(): ActionPluginHealth {
        val client = clientFactory()
            ?: return ActionPluginHealth(available = false, detail = "Google Workspace integration is not configured.")
        return try {
            val email = client.currentAuthorizedEmail()
            ActionPluginHealth(available = true, detail = "Google Workspace authorized for $email.")
        } catch (ex: Exception) {
            ActionPluginHealth(
                available = false,
                detail = ex.message ?: "Google Workspace authorization is unavailable.",
            )
        }
    }

    protected fun activeClient(): GoogleWorkspaceApiClient =
        clientFactory() ?: throw IllegalStateException("Google Workspace integration is not configured.")

    protected fun success(text: String): ActionOutcome =
        ActionOutcome(
            statusSummary = PromptInjectionDefense.sanitizeExternalText(text, MAX_RESULT_CHARS),
            executionStatus = ActionExecutionStatus.SUCCESS,
            effects = setOf(ActionEffect.TASK_PROGRESS, ActionEffect.EVIDENCE_GATHERED),
            observedEvidence = true,
            resultArtifacts = listOf(
                ExternalContentPipeline.ingest(
                    text = text,
                    maxChars = MAX_RESULT_CHARS,
                    source = SourceDescriptor(
                        provider = "google_workspace",
                        contentKind = ContentKind.RECORD,
                        objectType = descriptor.actionType.id,
                    ),
                )
            ),
        )

    protected fun failure(message: String): ActionOutcome =
        ActionOutcome(
            statusSummary = message,
            executionStatus = ActionExecutionStatus.FAILED,
            actionErrorCategory = "google_workspace_failed",
        )

    companion object {
        private const val MAX_RESULT_CHARS: Int = 1_500
    }
}

class GmailObserveSearchActionPlugin(
    clientFactory: () -> GoogleWorkspaceApiClient?,
) : GoogleWorkspaceObserveActionPlugin(
    descriptor = ActionDescriptor(
        actionType = ActionType(GMAIL_SEARCH_ACTION_ID),
        dispatchable = true,
        plannerDescription = "gmail_observe_search: search Gmail messages with a JSON payload.",
        payloadGuidance = "JSON object with query, label_ids optional, and max_results optional.",
        payloadSchemaExample = """{"query":"from:alice newer_than:7d","max_results":10}""",
        requiresFollowUpThought = true,
        followUpPrefix = "Gmail search completed.",
        capabilities = setOf(ActionCapability.GATHERS_EVIDENCE),
        effectClass = ActionEffectClass.OBSERVE,
    ),
    clientFactory = clientFactory,
) {
    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        val payload = try {
            mapper.readValue<GmailSearchPayload>(action.payload)
        } catch (_: Exception) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "gmail_observe_search_payload_invalid_json",
                reason = "gmail_observe_search payload must be valid JSON.",
            )
        }
        if (payload.maxResults != null && payload.maxResults !in 1..25) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "gmail_observe_search_max_results_out_of_bounds",
                reason = "gmail_observe_search max_results must be between 1 and 25.",
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome =
        try {
            val payload = mapper.readValue<GmailSearchPayload>(action.payload)
            success(
                activeClient().searchMessages(
                    GmailSearchRequest(
                        query = payload.query?.trim().orEmpty(),
                        labelIds = payload.labelIds.orEmpty(),
                        maxResults = payload.maxResults ?: 10,
                    )
                )
            )
        } catch (ex: Exception) {
            failure("Gmail search failed: ${ex.message ?: "unknown error"}")
        }
}

class GmailObserveMessageActionPlugin(
    clientFactory: () -> GoogleWorkspaceApiClient?,
) : GoogleWorkspaceObserveActionPlugin(
    descriptor = ActionDescriptor(
        actionType = ActionType(GMAIL_MESSAGE_ACTION_ID),
        dispatchable = true,
        plannerDescription = "gmail_observe_message: fetch a Gmail message by id.",
        payloadGuidance = "JSON object with required message_id field.",
        payloadSchemaExample = """{"message_id":"185f6ab42b9"}""",
        requiresFollowUpThought = true,
        followUpPrefix = "Gmail message retrieval completed.",
        capabilities = setOf(ActionCapability.GATHERS_EVIDENCE),
        effectClass = ActionEffectClass.OBSERVE,
    ),
    clientFactory = clientFactory,
) {
    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        val payload = try {
            mapper.readValue<GmailMessagePayload>(action.payload)
        } catch (_: Exception) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "gmail_observe_message_payload_invalid_json",
                reason = "gmail_observe_message payload must be valid JSON.",
            )
        }
        if (payload.messageId?.trim().isNullOrBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "gmail_observe_message_message_id_missing",
                reason = "gmail_observe_message requires a non-blank message_id.",
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome =
        try {
            val payload = mapper.readValue<GmailMessagePayload>(action.payload)
            success(activeClient().getMessage(payload.messageId.orEmpty().trim()))
        } catch (ex: Exception) {
            failure("Gmail message retrieval failed: ${ex.message ?: "unknown error"}")
        }
}

class CalendarObserveEventsActionPlugin(
    clientFactory: () -> GoogleWorkspaceApiClient?,
) : GoogleWorkspaceObserveActionPlugin(
    descriptor = ActionDescriptor(
        actionType = ActionType(CALENDAR_EVENTS_ACTION_ID),
        dispatchable = true,
        plannerDescription = "calendar_observe_events: list upcoming Google Calendar events with a JSON payload.",
        payloadGuidance = "JSON object with optional calendar_id, time_min, time_max, query, and max_results.",
        payloadSchemaExample = """{"calendar_id":"primary","max_results":10}""",
        requiresFollowUpThought = true,
        followUpPrefix = "Calendar event retrieval completed.",
        capabilities = setOf(ActionCapability.GATHERS_EVIDENCE),
        effectClass = ActionEffectClass.OBSERVE,
    ),
    clientFactory = clientFactory,
) {
    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        val payload = try {
            mapper.readValue<CalendarEventsPayload>(action.payload)
        } catch (_: Exception) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "calendar_observe_events_payload_invalid_json",
                reason = "calendar_observe_events payload must be valid JSON.",
            )
        }
        if (payload.maxResults != null && payload.maxResults !in 1..25) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "calendar_observe_events_max_results_out_of_bounds",
                reason = "calendar_observe_events max_results must be between 1 and 25.",
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome =
        try {
            val payload = mapper.readValue<CalendarEventsPayload>(action.payload)
            success(
                activeClient().listEvents(
                    CalendarEventsRequest(
                        calendarId = payload.calendarId?.trim().orEmpty().ifBlank { "primary" },
                        timeMinIso = payload.timeMin?.trim().takeUnless { it.isNullOrBlank() },
                        timeMaxIso = payload.timeMax?.trim().takeUnless { it.isNullOrBlank() },
                        maxResults = payload.maxResults ?: 10,
                        query = payload.query?.trim().takeUnless { it.isNullOrBlank() },
                    )
                )
            )
        } catch (ex: Exception) {
            failure("Calendar event retrieval failed: ${ex.message ?: "unknown error"}")
        }
}

private object GoogleWorkspaceClientFactory {
    fun fromContext(context: ActionPluginFactoryContext): GoogleWorkspaceApiClient? {
        val config = context.config.nativeIntegrations.googleWorkspace
        if (!config.enabled) {
            return null
        }
        val clientId = context.secretProvider.read(SecretHandle(config.oauthClientIdHandle)).orEmpty()
        val clientSecret = context.secretProvider.read(SecretHandle(config.oauthClientSecretHandle)).orEmpty()
        val tokenEncryptionSecret = context.secretProvider.read(SecretHandle(config.oauthTokenEncryptionSecretHandle)).orEmpty()
        if (clientId.isBlank() || clientSecret.isBlank() || tokenEncryptionSecret.isBlank()) {
            return null
        }
        val credentialStore = GoogleWorkspaceCredentialStore(
            rootDir = Paths.get(config.tokenStoreDir),
            encryptionSecret = tokenEncryptionSecret,
        )
        return GoogleWorkspaceApiClient(
            clientId = clientId,
            clientSecret = clientSecret,
            tokenBaseUrl = config.tokenBaseUrl,
            credentialStore = credentialStore,
        )
    }
}

class GmailObserveSearchActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        GmailObserveSearchActionPlugin(clientFactory = { GoogleWorkspaceClientFactory.fromContext(context) })
}

class GmailObserveMessageActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        GmailObserveMessageActionPlugin(clientFactory = { GoogleWorkspaceClientFactory.fromContext(context) })
}

class CalendarObserveEventsActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        CalendarObserveEventsActionPlugin(clientFactory = { GoogleWorkspaceClientFactory.fromContext(context) })
}

private data class GmailSearchPayload(
    val query: String? = null,
    @field:JsonProperty("label_ids")
    val labelIds: List<String>? = null,
    @field:JsonProperty("max_results")
    val maxResults: Int? = null,
)

private data class GmailMessagePayload(
    @field:JsonProperty("message_id")
    val messageId: String? = null,
)

private data class CalendarEventsPayload(
    @field:JsonProperty("calendar_id")
    val calendarId: String? = null,
    @field:JsonProperty("time_min")
    val timeMin: String? = null,
    @field:JsonProperty("time_max")
    val timeMax: String? = null,
    val query: String? = null,
    @field:JsonProperty("max_results")
    val maxResults: Int? = null,
)

private val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
