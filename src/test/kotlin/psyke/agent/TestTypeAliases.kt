package psyke.agent

typealias AgentConfig = psyke.agent.config.AgentConfig
typealias PlannerConfig = psyke.agent.config.PlannerConfig
typealias SuperegoConfig = psyke.agent.config.SuperegoConfig
typealias MemoryConfig = psyke.agent.config.MemoryConfig
typealias TaskWorkspaceConfig = psyke.agent.config.TaskWorkspaceConfig
typealias MetaReasonerConfig = psyke.agent.config.MetaReasonerConfig
typealias Urgency = psyke.agent.model.Urgency
typealias ActionType = psyke.agent.model.ActionType
typealias ActionEffect = psyke.agent.model.ActionEffect
typealias ActionExecutionStatus = psyke.agent.model.ActionExecutionStatus
typealias InputPriority = psyke.agent.model.InputPriority
typealias PendingInput = psyke.agent.model.PendingInput
typealias PendingThought = psyke.agent.model.PendingThought
typealias PendingAction = psyke.agent.model.PendingAction
typealias QueueState = psyke.agent.model.QueueState
typealias QueueSnapshot = psyke.agent.model.QueueSnapshot
typealias LoopTask = psyke.agent.model.LoopTask
typealias EgoTrigger = psyke.agent.model.EgoTrigger
typealias EgoDecision = psyke.agent.model.EgoDecision
typealias DialogueRole = psyke.agent.model.DialogueRole
typealias DialogueTurn = psyke.agent.model.DialogueTurn
typealias PlannerContext = psyke.agent.model.PlannerContext
typealias SuperegoContext = psyke.agent.model.SuperegoContext
typealias DeliberationState = psyke.agent.model.DeliberationState
typealias ActionOutcome = psyke.agent.model.ActionOutcome

typealias Ego = psyke.agent.ego.Ego
typealias LlmEgoPlanner = psyke.agent.ego.LlmEgoPlanner
typealias AttentionScheduler = psyke.agent.ego.AttentionScheduler
typealias DeliberationProgressMonitor = psyke.agent.ego.DeliberationProgressMonitor
typealias MetaReasoner = psyke.agent.ego.MetaReasoner
typealias MetaReasonerVerdict = psyke.agent.ego.MetaReasonerVerdict
typealias MetaReasonerAssessment = psyke.agent.ego.MetaReasonerAssessment
typealias LlmMetaReasoner = psyke.agent.ego.LlmMetaReasoner
typealias TaskWorkspaceFinalizer = psyke.agent.ego.TaskWorkspaceFinalizer
typealias TaskWorkspaceFinalizerRequest = psyke.agent.ego.TaskWorkspaceFinalizerRequest
typealias TaskWorkspaceFinalizerResult = psyke.agent.ego.TaskWorkspaceFinalizerResult
typealias LlmTaskWorkspaceFinalizer = psyke.agent.ego.LlmTaskWorkspaceFinalizer

typealias Superego = psyke.agent.superego.Superego
typealias SuperegoPolicy = psyke.agent.superego.SuperegoPolicy

typealias SensoryInput = psyke.agent.cortex.sensory.SensoryInput
typealias Signal = psyke.agent.cortex.sensory.Signal
typealias SensorySignal = psyke.agent.cortex.sensory.SensorySignal
typealias SystemSignal = psyke.agent.cortex.sensory.SystemSignal
typealias ProjectSignal = psyke.agent.cortex.sensory.ProjectSignal
typealias SignalSource = psyke.agent.cortex.sensory.SignalSource
typealias StdinSignalSource = psyke.agent.cortex.sensory.StdinSignalSource
typealias AsyncSignalSource = psyke.agent.cortex.sensory.AsyncSignalSource
@Suppress("DEPRECATION") typealias SensoryInputSource = psyke.agent.cortex.sensory.SensoryInputSource
@Suppress("DEPRECATION") typealias StdinSensoryInputSource = psyke.agent.cortex.sensory.StdinSensoryInputSource
typealias SensoryCortex = psyke.agent.cortex.sensory.SensoryCortex

typealias MotorCortex = psyke.agent.cortex.motor.MotorCortex
typealias ActionImplementationStatus = psyke.agent.cortex.motor.ActionImplementationStatus

typealias MemoryStore = psyke.agent.memory.shortterm.MemoryStore
typealias MemoryStats = psyke.agent.memory.shortterm.MemoryStats

typealias MemoryRecallQuery = psyke.agent.memory.longterm.MemoryRecallQuery
typealias MemoryRecall = psyke.agent.memory.longterm.MemoryRecall
typealias MemoryImprint = psyke.agent.memory.longterm.MemoryImprint
typealias Hippocampus = psyke.agent.memory.longterm.Hippocampus
typealias NoopHippocampus = psyke.agent.memory.longterm.NoopHippocampus
typealias McpHippocampus = psyke.agent.memory.longterm.McpHippocampus
typealias LongTermMemoryAdvisor = psyke.agent.memory.longterm.LongTermMemoryAdvisor
typealias LongTermMemoryAssessmentContext = psyke.agent.memory.longterm.LongTermMemoryAssessmentContext
typealias LongTermMemoryAssessmentDecision = psyke.agent.memory.longterm.LongTermMemoryAssessmentDecision
typealias LlmLongTermMemoryAdvisor = psyke.agent.memory.longterm.LlmLongTermMemoryAdvisor

typealias McpTimeTool = psyke.agent.tools.mcp.McpTimeTool
typealias FetchTool = psyke.agent.tools.mcp.FetchTool
typealias FetchOutcome = psyke.agent.tools.mcp.FetchOutcome
typealias FetchErrorCategory = psyke.agent.tools.mcp.FetchErrorCategory
typealias ToolHealthStatus = psyke.agent.tools.mcp.ToolHealthStatus
typealias McpStdioClient = psyke.agent.tools.mcp.McpStdioClient
typealias SdkMcpTimeTool = psyke.agent.tools.mcp.SdkMcpTimeTool
typealias NativeFetchTool = psyke.agent.tools.mcp.NativeFetchTool

typealias PromptBudgetAllocator = psyke.agent.support.PromptBudgetAllocator
typealias TextSecurity = psyke.agent.support.TextSecurity

fun encodeMcpArguments(arguments: Map<String, Any?>): Map<String, Any> =
    psyke.agent.tools.mcp.encodeMcpArguments(arguments)
