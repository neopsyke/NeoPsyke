package psyke.agent

typealias AgentConfig = psyke.agent.core.AgentConfig
typealias PlannerConfig = psyke.agent.core.PlannerConfig
typealias SuperegoConfig = psyke.agent.core.SuperegoConfig
typealias MemoryConfig = psyke.agent.core.MemoryConfig
typealias MetaReasonerConfig = psyke.agent.core.MetaReasonerConfig
typealias Urgency = psyke.agent.core.Urgency
typealias ActionType = psyke.agent.core.ActionType
typealias InputPriority = psyke.agent.core.InputPriority
typealias PendingInput = psyke.agent.core.PendingInput
typealias PendingThought = psyke.agent.core.PendingThought
typealias PendingAction = psyke.agent.core.PendingAction
typealias QueueState = psyke.agent.core.QueueState
typealias QueueSnapshot = psyke.agent.core.QueueSnapshot
typealias LoopTask = psyke.agent.core.LoopTask
typealias EgoTrigger = psyke.agent.core.EgoTrigger
typealias EgoDecision = psyke.agent.core.EgoDecision
typealias DialogueRole = psyke.agent.core.DialogueRole
typealias DialogueTurn = psyke.agent.core.DialogueTurn
typealias PlannerContext = psyke.agent.core.PlannerContext
typealias SuperegoContext = psyke.agent.core.SuperegoContext
typealias DeliberationState = psyke.agent.core.DeliberationState
typealias ActionOutcome = psyke.agent.core.ActionOutcome

typealias Ego = psyke.agent.ego.Ego
typealias LlmEgoPlanner = psyke.agent.ego.LlmEgoPlanner
typealias AttentionScheduler = psyke.agent.ego.AttentionScheduler
typealias DeliberationProgressMonitor = psyke.agent.ego.DeliberationProgressMonitor
typealias MetaReasoner = psyke.agent.ego.MetaReasoner
typealias MetaReasonerVerdict = psyke.agent.ego.MetaReasonerVerdict
typealias MetaReasonerAssessment = psyke.agent.ego.MetaReasonerAssessment
typealias LlmMetaReasoner = psyke.agent.ego.LlmMetaReasoner

typealias Superego = psyke.agent.superego.Superego
typealias SuperegoPolicy = psyke.agent.superego.SuperegoPolicy

typealias SensoryInput = psyke.agent.cortex.sensory.SensoryInput
typealias SensorySignal = psyke.agent.cortex.sensory.SensorySignal
typealias SensoryInputSource = psyke.agent.cortex.sensory.SensoryInputSource
typealias StdinSensoryInputSource = psyke.agent.cortex.sensory.StdinSensoryInputSource
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
