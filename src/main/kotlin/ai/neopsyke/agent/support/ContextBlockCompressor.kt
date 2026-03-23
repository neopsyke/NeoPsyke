package ai.neopsyke.agent.support

object ContextBlockCompressor {
    data class Result(
        val text: String,
        val originalChars: Int,
        val compressedChars: Int,
    ) {
        val compressed: Boolean
            get() = compressedChars < originalChars

        val omittedChars: Int
            get() = (originalChars - compressedChars).coerceAtLeast(0)
    }

    fun compress(text: String, maxChars: Int): Result {
        val normalized = text.trim()
        if (maxChars <= 0 || normalized.isBlank()) {
            return Result(text = "", originalChars = normalized.length, compressedChars = 0)
        }
        if (normalized.length <= maxChars) {
            return Result(
                text = normalized,
                originalChars = normalized.length,
                compressedChars = normalized.length
            )
        }

        val marker = compressionMarker(normalized.length - maxChars)
        val remainingBudget = (maxChars - marker.length).coerceAtLeast(MIN_SEGMENT_CHARS * 2)
        val headChars = (remainingBudget * HEAD_RATIO).toInt().coerceAtLeast(MIN_SEGMENT_CHARS)
        val tailChars = (remainingBudget - headChars).coerceAtLeast(MIN_SEGMENT_CHARS)

        val compressed = buildString {
            append(normalized.take(headChars).trimEnd())
            append('\n')
            append(marker)
            append('\n')
            append(normalized.takeLast(tailChars).trimStart())
        }.let { TextSecurity.clamp(it, maxChars) }

        return Result(
            text = compressed,
            originalChars = normalized.length,
            compressedChars = compressed.length
        )
    }

    private fun compressionMarker(approxOmittedChars: Int): String =
        "[...compressed for memory advisor; omitted~${approxOmittedChars.coerceAtLeast(0)} chars...]"

    private const val MIN_SEGMENT_CHARS: Int = 80
    private const val HEAD_RATIO: Double = 0.60
}

