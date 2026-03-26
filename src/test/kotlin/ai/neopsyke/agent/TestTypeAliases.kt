package ai.neopsyke.agent

typealias AgentConfig = ai.neopsyke.agent.config.AgentConfig
typealias PlannerConfig = ai.neopsyke.agent.config.PlannerConfig
typealias SuperegoConfig = ai.neopsyke.agent.config.SuperegoConfig
typealias MemoryConfig = ai.neopsyke.agent.config.MemoryConfig
typealias ScratchpadConfig = ai.neopsyke.agent.config.ScratchpadConfig
typealias MetaReasonerConfig = ai.neopsyke.agent.config.MetaReasonerConfig
typealias Urgency = ai.neopsyke.agent.model.Urgency
typealias ActionType = ai.neopsyke.agent.model.ActionType
typealias ActionEffect = ai.neopsyke.agent.model.ActionEffect
typealias ActionExecutionStatus = ai.neopsyke.agent.model.ActionExecutionStatus
typealias InputPriority = ai.neopsyke.agent.model.InputPriority
typealias PendingInput = ai.neopsyke.agent.model.PendingInput
typealias PendingThought = ai.neopsyke.agent.model.PendingThought
typealias PendingAction = ai.neopsyke.agent.model.PendingAction
typealias QueueState = ai.neopsyke.agent.model.QueueState
typealias QueueSnapshot = ai.neopsyke.agent.model.QueueSnapshot
typealias LoopTask = ai.neopsyke.agent.model.LoopTask
typealias EgoTrigger = ai.neopsyke.agent.model.EgoTrigger
typealias EgoDecision = ai.neopsyke.agent.model.EgoDecision
typealias DialogueRole = ai.neopsyke.agent.model.DialogueRole
typealias DialogueTurn = ai.neopsyke.agent.model.DialogueTurn
typealias PlannerContext = ai.neopsyke.agent.model.PlannerContext
typealias SuperegoContext = ai.neopsyke.agent.model.SuperegoContext
typealias DeliberationState = ai.neopsyke.agent.model.DeliberationState
typealias ActionOutcome = ai.neopsyke.agent.model.ActionOutcome

typealias Ego = ai.neopsyke.agent.ego.Ego
typealias LlmEgoPlanner = ai.neopsyke.agent.ego.LlmEgoPlanner
typealias AttentionScheduler = ai.neopsyke.agent.ego.AttentionScheduler
typealias DeliberationProgressMonitor = ai.neopsyke.agent.ego.DeliberationProgressMonitor
typealias MetaReasoner = ai.neopsyke.agent.ego.MetaReasoner
typealias MetaReasonerVerdict = ai.neopsyke.agent.ego.MetaReasonerVerdict
typealias MetaReasonerAssessment = ai.neopsyke.agent.ego.MetaReasonerAssessment
typealias LlmMetaReasoner = ai.neopsyke.agent.ego.LlmMetaReasoner
typealias ScratchpadFinalizer = ai.neopsyke.agent.ego.ScratchpadFinalizer
typealias ScratchpadFinalizerRequest = ai.neopsyke.agent.ego.ScratchpadFinalizerRequest
typealias ScratchpadFinalizerResult = ai.neopsyke.agent.ego.ScratchpadFinalizerResult
typealias LlmScratchpadFinalizer = ai.neopsyke.agent.ego.LlmScratchpadFinalizer

typealias Superego = ai.neopsyke.agent.superego.Superego
typealias SuperegoPolicy = ai.neopsyke.agent.superego.SuperegoPolicy

typealias SensoryInput = ai.neopsyke.agent.cortex.sensory.SensoryInput
typealias Signal = ai.neopsyke.agent.cortex.sensory.Signal
typealias CognitiveSignal = ai.neopsyke.agent.cortex.sensory.CognitiveSignal
typealias RuntimeControlSignal = ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
typealias GoalRuntimeCue = ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
typealias SignalSource = ai.neopsyke.agent.cortex.sensory.SignalSource
typealias StdinSignalSource = ai.neopsyke.agent.cortex.sensory.StdinSignalSource
typealias AsyncSignalSource = ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
typealias SensoryCortex = ai.neopsyke.agent.cortex.sensory.SensoryCortex

typealias MotorCortex = ai.neopsyke.agent.cortex.motor.MotorCortex
typealias ActionImplementationStatus = ai.neopsyke.agent.cortex.motor.ActionImplementationStatus

typealias MemoryStore = ai.neopsyke.agent.memory.shortterm.MemoryStore
typealias MemoryStats = ai.neopsyke.agent.memory.shortterm.MemoryStats

typealias MemoryCapability = ai.neopsyke.agent.memory.longterm.MemoryCapability
typealias RecallRequest = ai.neopsyke.agent.memory.longterm.RecallRequest
typealias MemoryRecallQuery = ai.neopsyke.agent.memory.longterm.RecallRequest
typealias RecallResult = ai.neopsyke.agent.memory.longterm.RecallResult
typealias MemoryRecall = ai.neopsyke.agent.memory.longterm.RecallResult
typealias ImprintRequest = ai.neopsyke.agent.memory.longterm.ImprintRequest
typealias ImprintResult = ai.neopsyke.agent.memory.longterm.ImprintResult
typealias NarrativeImprint = ai.neopsyke.agent.memory.longterm.NarrativeImprint
typealias MemoryImprint = ai.neopsyke.agent.memory.longterm.NarrativeImprint
typealias Hippocampus = ai.neopsyke.agent.memory.longterm.Hippocampus
typealias NoopHippocampus = ai.neopsyke.agent.memory.longterm.NoopHippocampus
typealias McpHippocampus = ai.neopsyke.agent.memory.longterm.McpHippocampus
typealias LongTermMemoryAdvisor = ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
typealias LongTermMemoryAssessmentContext = ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentContext
typealias LongTermMemoryAssessmentDecision = ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentDecision
typealias LongTermMemorySubject = ai.neopsyke.agent.memory.longterm.LongTermMemorySubject
typealias LlmLongTermMemoryAdvisor = ai.neopsyke.agent.memory.longterm.LlmLongTermMemoryAdvisor

typealias FetchTool = ai.neopsyke.agent.cortex.motor.actions.fetch.FetchTool
typealias FetchOutcome = ai.neopsyke.agent.cortex.motor.actions.fetch.FetchOutcome
typealias FetchErrorCategory = ai.neopsyke.agent.cortex.motor.actions.fetch.FetchErrorCategory
typealias ToolHealthStatus = ai.neopsyke.agent.cortex.motor.actions.fetch.ToolHealthStatus
typealias McpStdioClient = ai.neopsyke.agent.cortex.motor.actions.mcp.McpStdioClient
typealias NativeFetchTool = ai.neopsyke.agent.cortex.motor.actions.fetch.NativeFetchTool

typealias PromptBudgetAllocator = ai.neopsyke.agent.support.PromptBudgetAllocator
typealias TextSecurity = ai.neopsyke.agent.support.TextSecurity

fun encodeMcpArguments(arguments: Map<String, Any?>): Map<String, Any> =
   ai.neopsyke.agent.cortex.motor.actions.mcp.encodeMcpArguments(arguments)
