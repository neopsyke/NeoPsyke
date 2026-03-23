package ai.neopsyke.agent.support

import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatRole

object PromptBudgetAllocator {
    private const val MESSAGE_OVERHEAD_TOKENS = 4

    enum class Band(val rank: Int) {
        REQUIRED_CORE(3),
        REQUIRED_CONTEXT(2),
        OPTIONAL(1)
    }

    enum class Importance(val rank: Int) {
        HIGH(3),
        MEDIUM(2),
        LOW(1)
    }

    data class Section(
        val key: String,
        val role: ChatRole,
        val content: String,
        val band: Band = Band.OPTIONAL,
        val importance: Importance = Importance.MEDIUM,
        val floorTokens: Int = 0,
    )

    data class AllocationResult(
        val messages: List<ChatMessage>,
        val diagnostics: Diagnostics,
    )

    data class Diagnostics(
        val maxTokens: Int,
        val sectionCount: Int,
        val emittedMessageCount: Int,
        val estimatedContentTokens: Int,
        val estimatedTotalCost: Int,
        val allocatedContentTokens: Int,
        val allocatedTotalCost: Int,
        val reservedFloorCost: Int,
        val floorBudgetFeasible: Boolean,
        val usedSingleMessageFallback: Boolean,
        val degradationPath: List<String>,
        val droppedSectionCount: Int,
        val floorViolationCount: Int,
        val bands: Map<Band, BandDiagnostics>,
        val sections: List<SectionDiagnostics>,
    ) {
        fun toTelemetryData(callSite: String): Map<String, Any?> {
            val bandMap = bands.mapKeys { (band, _) -> band.name.lowercase() }
                .mapValues { (_, stats) ->
                    mapOf(
                        "section_count" to stats.sectionCount,
                        "dropped_section_count" to stats.droppedSectionCount,
                        "allocated_content_tokens" to stats.allocatedContentTokens,
                        "reserved_floor_tokens" to stats.reservedFloorTokens,
                    )
                }
            val degradation = if (degradationPath.isEmpty()) "none" else degradationPath.joinToString(",")
            return mapOf(
                "call_site" to callSite,
                "max_tokens" to maxTokens,
                "section_count" to sectionCount,
                "emitted_message_count" to emittedMessageCount,
                "estimated_content_tokens" to estimatedContentTokens,
                "estimated_total_cost" to estimatedTotalCost,
                "allocated_content_tokens" to allocatedContentTokens,
                "allocated_total_cost" to allocatedTotalCost,
                "reserved_floor_cost" to reservedFloorCost,
                "floor_budget_feasible" to floorBudgetFeasible,
                "single_message_fallback" to usedSingleMessageFallback,
                "degradation_path" to degradation,
                "dropped_section_count" to droppedSectionCount,
                "floor_violation_count" to floorViolationCount,
                "bands" to bandMap,
            )
        }
    }

    data class BandDiagnostics(
        val sectionCount: Int,
        val droppedSectionCount: Int,
        val allocatedContentTokens: Int,
        val reservedFloorTokens: Int,
    )

    data class SectionDiagnostics(
        val key: String,
        val band: Band,
        val importance: Importance,
        val estimatedTokens: Int,
        val reservedFloorTokens: Int,
        val allocatedTokens: Int,
        val emitted: Boolean,
        val floorViolated: Boolean,
    )

    fun allocate(sections: List<Section>, maxTokens: Int): AllocationResult {
        if (sections.isEmpty() || maxTokens <= 0) {
            return AllocationResult(
                messages = emptyList(),
                diagnostics = emptyDiagnostics(maxTokens = maxTokens)
            )
        }

        val mutable = sections.mapIndexedNotNull { index, section ->
            val normalized = section.content.trim()
            if (normalized.isBlank()) {
                null
            } else {
                val estimated = TextSecurity.estimateTokens(normalized)
                val normalizedSection = section.copy(
                    key = section.key.ifBlank { "section_$index" },
                    content = normalized,
                )
                MutableSection(
                    index = index,
                    section = normalizedSection,
                    estimatedTokens = estimated,
                    reservedFloorTokens = resolveReservedFloorTokens(normalizedSection, estimated),
                    allocatedTokens = estimated,
                )
            }
        }

        if (mutable.isEmpty()) {
            return AllocationResult(
                messages = emptyList(),
                diagnostics = emptyDiagnostics(maxTokens = maxTokens)
            )
        }

        val degradation = mutableListOf<String>()
        val reservedFloorCost = totalCostForReservedFloors(mutable)
        val floorBudgetFeasible = reservedFloorCost <= maxTokens

        if (floorBudgetFeasible) {
            trimTierToBudget(mutable, maxTokens, Band.OPTIONAL, degradation)
            trimTierToBudget(mutable, maxTokens, Band.REQUIRED_CONTEXT, degradation)
            trimTierToBudget(mutable, maxTokens, Band.REQUIRED_CORE, degradation)
        }

        val usedFallback = totalCost(mutable) > maxTokens || !floorBudgetFeasible
        if (usedFallback) {
            applySingleMessageFallback(mutable, maxTokens)
            degradation += DEGRADATION_SINGLE_MESSAGE_FALLBACK
        }

        val sectionMessages = mutable.mapNotNull { item ->
            if (item.allocatedTokens <= 0) {
                return@mapNotNull null
            }
            val clamped = TextSecurity.clampToTokenBudget(item.section.content, item.allocatedTokens)
            if (clamped.isBlank()) {
                item.allocatedTokens = 0
                return@mapNotNull null
            }
            ChatMessage(role = item.section.role, content = clamped)
        }

        if (sectionMessages.isEmpty() && maxTokens > 0) {
            val fallbackItem = mutable.maxWithOrNull(sectionFallbackComparator)
            if (fallbackItem != null) {
                fallbackItem.allocatedTokens = 1
                val clamped = TextSecurity.clampToTokenBudget(fallbackItem.section.content, fallbackItem.allocatedTokens)
                if (clamped.isNotBlank()) {
                    val diagnostics = buildDiagnostics(
                        sections = mutable,
                        messages = listOf(ChatMessage(role = fallbackItem.section.role, content = clamped)),
                        maxTokens = maxTokens,
                        reservedFloorCost = reservedFloorCost,
                        floorBudgetFeasible = floorBudgetFeasible,
                        degradationPath = (degradation + DEGRADATION_SINGLE_MESSAGE_FALLBACK).distinct(),
                        usedSingleMessageFallback = true,
                    )
                    return AllocationResult(messages = listOf(ChatMessage(role = fallbackItem.section.role, content = clamped)), diagnostics = diagnostics)
                }
            }
        }

        val mergedMessages = mergeByRole(sectionMessages)
        val diagnostics = buildDiagnostics(
            sections = mutable,
            messages = mergedMessages,
            maxTokens = maxTokens,
            reservedFloorCost = reservedFloorCost,
            floorBudgetFeasible = floorBudgetFeasible,
            degradationPath = degradation,
            usedSingleMessageFallback = usedFallback,
        )
        return AllocationResult(messages = mergedMessages, diagnostics = diagnostics)
    }

    private fun emptyDiagnostics(maxTokens: Int): Diagnostics =
        Diagnostics(
            maxTokens = maxTokens,
            sectionCount = 0,
            emittedMessageCount = 0,
            estimatedContentTokens = 0,
            estimatedTotalCost = 0,
            allocatedContentTokens = 0,
            allocatedTotalCost = 0,
            reservedFloorCost = 0,
            floorBudgetFeasible = true,
            usedSingleMessageFallback = false,
            degradationPath = emptyList(),
            droppedSectionCount = 0,
            floorViolationCount = 0,
            bands = emptyMap(),
            sections = emptyList(),
        )

    private fun resolveReservedFloorTokens(section: Section, estimatedTokens: Int): Int {
        val requested = section.floorTokens.coerceAtLeast(0)
        val normalizedFloor = when (section.band) {
            Band.REQUIRED_CORE,
            Band.REQUIRED_CONTEXT,
            -> maxOf(1, requested)

            Band.OPTIONAL -> requested
        }
        return minOf(estimatedTokens, normalizedFloor)
    }

    private fun totalCostForReservedFloors(sections: List<MutableSection>): Int =
        sections.sumOf { item ->
            if (item.reservedFloorTokens <= 0) {
                0
            } else {
                item.reservedFloorTokens + MESSAGE_OVERHEAD_TOKENS
            }
        }

    private fun trimTierToBudget(
        sections: List<MutableSection>,
        maxTokens: Int,
        band: Band,
        degradationPath: MutableList<String>,
    ) {
        var trimmed = false
        while (totalCost(sections) > maxTokens) {
            val candidate = sections
                .asSequence()
                .filter { it.section.band == band && it.allocatedTokens > it.reservedFloorTokens }
                .minWithOrNull(trimCandidateComparator)
                ?: break

            if (candidate.allocatedTokens <= 0) {
                continue
            }
            if (candidate.reservedFloorTokens == 0 && candidate.allocatedTokens == 1) {
                candidate.allocatedTokens = 0
            } else {
                candidate.allocatedTokens -= 1
            }
            trimmed = true
        }

        if (trimmed) {
            degradationPath += when (band) {
                Band.OPTIONAL -> DEGRADATION_TRIM_OPTIONAL
                Band.REQUIRED_CONTEXT -> DEGRADATION_TRIM_REQUIRED_CONTEXT
                Band.REQUIRED_CORE -> DEGRADATION_TRIM_REQUIRED_CORE
            }
        }
    }

    private fun applySingleMessageFallback(
        sections: List<MutableSection>,
        maxTokens: Int,
    ) {
        sections.forEach { it.allocatedTokens = 0 }
        if (maxTokens <= 0) {
            return
        }
        val fallback = sections.maxWithOrNull(sectionFallbackComparator) ?: return
        fallback.allocatedTokens = resolveSingleMessageContentBudget(maxTokens, fallback.estimatedTokens)
    }

    private fun resolveSingleMessageContentBudget(maxTokens: Int, estimatedTokens: Int): Int {
        if (maxTokens <= 0) {
            return 0
        }
        val contentBudget = maxTokens - MESSAGE_OVERHEAD_TOKENS
        if (contentBudget <= 0) {
            return 1
        }
        return minOf(estimatedTokens, maxOf(1, contentBudget))
    }

    private fun buildDiagnostics(
        sections: List<MutableSection>,
        messages: List<ChatMessage>,
        maxTokens: Int,
        reservedFloorCost: Int,
        floorBudgetFeasible: Boolean,
        degradationPath: List<String>,
        usedSingleMessageFallback: Boolean,
    ): Diagnostics {
        val sectionDiagnostics = sections.map { item ->
            val floorViolated = item.allocatedTokens < item.reservedFloorTokens
            SectionDiagnostics(
                key = item.section.key,
                band = item.section.band,
                importance = item.section.importance,
                estimatedTokens = item.estimatedTokens,
                reservedFloorTokens = item.reservedFloorTokens,
                allocatedTokens = item.allocatedTokens,
                emitted = item.allocatedTokens > 0,
                floorViolated = floorViolated,
            )
        }

        val bandDiagnostics = Band.entries.associateWith { band ->
            val scoped = sections.filter { it.section.band == band }
            BandDiagnostics(
                sectionCount = scoped.size,
                droppedSectionCount = scoped.count { it.allocatedTokens <= 0 },
                allocatedContentTokens = scoped.sumOf { it.allocatedTokens.coerceAtLeast(0) },
                reservedFloorTokens = scoped.sumOf { it.reservedFloorTokens.coerceAtLeast(0) },
            )
        }

        return Diagnostics(
            maxTokens = maxTokens,
            sectionCount = sections.size,
            emittedMessageCount = messages.size,
            estimatedContentTokens = sections.sumOf { it.estimatedTokens },
            estimatedTotalCost = sections.sumOf { it.estimatedTokens + MESSAGE_OVERHEAD_TOKENS },
            allocatedContentTokens = sections.sumOf { it.allocatedTokens.coerceAtLeast(0) },
            allocatedTotalCost = totalCost(sections),
            reservedFloorCost = reservedFloorCost,
            floorBudgetFeasible = floorBudgetFeasible,
            usedSingleMessageFallback = usedSingleMessageFallback,
            degradationPath = degradationPath.distinct(),
            droppedSectionCount = sections.count { it.allocatedTokens <= 0 },
            floorViolationCount = sectionDiagnostics.count { it.floorViolated },
            bands = bandDiagnostics,
            sections = sectionDiagnostics,
        )
    }

    private fun totalCost(sections: List<MutableSection>): Int =
        sections.sumOf { item ->
            if (item.allocatedTokens <= 0) {
                0
            } else {
                item.allocatedTokens + MESSAGE_OVERHEAD_TOKENS
            }
        }

    private fun mergeByRole(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) {
            return messages
        }
        val merged = mutableListOf<ChatMessage>()
        messages.forEach { message ->
            val last = merged.lastOrNull()
            if (last != null && last.role == message.role) {
                merged[merged.lastIndex] = last.copy(content = "${last.content}\n\n${message.content}")
            } else {
                merged.add(message)
            }
        }
        return merged
    }

    private data class MutableSection(
        val index: Int,
        val section: Section,
        val estimatedTokens: Int,
        val reservedFloorTokens: Int,
        var allocatedTokens: Int,
    )

    private val trimCandidateComparator =
        compareBy<MutableSection> { it.section.importance.rank }
            .thenBy { it.index }
            .thenByDescending { it.allocatedTokens }

    private val sectionFallbackComparator =
        compareBy<MutableSection> { it.section.band.rank }
            .thenBy { it.section.importance.rank }
            .thenBy { it.reservedFloorTokens }
            .thenBy { it.estimatedTokens }
            .thenByDescending { it.index }

    private const val DEGRADATION_TRIM_OPTIONAL = "trim_optional"
    private const val DEGRADATION_TRIM_REQUIRED_CONTEXT = "trim_required_context"
    private const val DEGRADATION_TRIM_REQUIRED_CORE = "trim_required_core"
    private const val DEGRADATION_SINGLE_MESSAGE_FALLBACK = "single_message_fallback"
}
