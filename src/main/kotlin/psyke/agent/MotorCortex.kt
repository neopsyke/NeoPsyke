package psyke.agent

class MotorCortex(
    private val webSearchProvider: WebSearchProvider,
    private val output: (String) -> Unit = ::println,
) {
    fun execute(action: PendingAction, searchResultCount: Int): ActionOutcome {
        return when (action.type) {
            ActionType.ANSWER -> {
                output("ego> ${action.payload}")
                ActionOutcome(
                    statusSummary = "Answer delivered to interlocutor.",
                    assistantOutput = action.payload
                )
            }

            ActionType.WEB_SEARCH -> {
                val result = webSearchProvider.search(action.payload, searchResultCount)
                val snippets = if (result.snippets.isEmpty()) {
                    "no snippets"
                } else {
                    result.snippets.joinToString(" | ")
                }
                ActionOutcome(
                    statusSummary = "Web search summary: ${result.summary}; snippets: $snippets"
                )
            }
        }
    }
}
