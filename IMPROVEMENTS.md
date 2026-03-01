# Agent Reasoning Limitations and Improvement Roadmap

As of **March 1, 2026**, your agent is a solid **single-trajectory deliberation loop** with safety gating and memory hooks, but it is still below current state-of-the-art reasoning patterns on search, verification, and self-correction.

## Workflow implementation note
- A project-local workflow meta-layer (`freud/`) has been added to accelerate feature loops while keeping separation from Psyke runtime code.
- Default run artifacts for this repository are now written under `.psyke/runs/freud/`.
- Deterministic scenario gating is defined in `freud/scenarios/v1/psyke-agent-scenarios.tsv`.

## Main limitations / bottlenecks (code-backed)
1. **Forced terminal answers can be generic and low-utility.**
   The fallback path can emit a boilerplate answer instead of task-specific synthesis ([Ego.kt:859](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/ego/Ego.kt:859)).

2. **No correctness verifier before final answer.**
   `answer` executes directly once proposed ([Ego.kt:321](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/ego/Ego.kt:321), [MotorCortex.kt:90](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/cortex/motor/MotorCortex.kt:90)); there is no explicit fact/logic judge loop.

3. **Reasoning is mostly single-path (no branch-and-select).**
   Planner outputs one of `thought|action|noop` ([CognitionModels.kt:23](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/core/CognitionModels.kt:23)); no native multi-candidate search, voting, or backtracking.

4. **Meta-reasoner intervention is late and weakly binding.**
   It starts only after step thresholds ([Ego.kt:757](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/ego/Ego.kt:757), [AgentConfig.kt:26](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/core/AgentConfig.kt:26)), and even "finalize_now" can be translated into another thought instead of hard finalization ([Ego.kt:814](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/ego/Ego.kt:814)).

5. **JSON-parse fragility creates noop loops.**
   Planner parse failures become `Noop` ([LlmEgoPlanner.kt:143](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/ego/LlmEgoPlanner.kt:143)), and noops are re-enqueued as thoughts ([Ego.kt:478](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/ego/Ego.kt:478)).

6. **Long-term recall on thought steps depends on explicit self-query.**
   If planner does not request recall query, memory recall is skipped ([Ego.kt:651](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/ego/Ego.kt:651)), which can miss useful context.

7. **Eval signal is currently weak for deep reasoning quality.**
   Logic mode tasks are mostly schema/feedback-carry checks ([ReasoningSelfEval.kt:508](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/eval/ReasoningSelfEval.kt:508)); one model-mode ledger run failed ([reasoning-eval-20260228T132500Z.json](/Users/victor.toral/atomitl/ai/psyke/.psyke/evals/reasoning/runs/reasoning-eval-20260228T132500Z.json)).

8. **Latency/cost can explode in long loops.**
   With `maxLoopStepsPerInput=180` ([AgentConfig.kt:4](/Users/victor.toral/atomitl/ai/psyke/src/main/kotlin/psyke/agent/core/AgentConfig.kt:4)) and serial planner/meta/superego/memory calls, worst-case deliberation is expensive and slow.

## How it compares to main SOTA reasoning concepts
1. **CoT-style intermediate reasoning:** Partial support (thought queue).
2. **Plan-first decomposition (PS):** Weak/implicit only.
3. **Self-consistency / majority over multiple traces:** Missing.
4. **Tree/graph search (ToT/LATS/GoT):** Missing.
5. **Verifier/process-supervised checking:** Missing.
6. **Iterative self-feedback (Self-Refine/Reflexion):** Partial (meta pressure), not answer-quality-driven.
7. **Long-horizon memory architecture:** Basic rolling summary + recall, below modern memory-manager designs.

## External ideas to incorporate (ordered by impact/complexity)
1. **Add verifier + one repair pass before `answer`** (Very high impact, Medium complexity).
   Use a second model pass to check logical consistency and only then emit final answer.
   Related: process supervision / verifier ideas ([Let's Verify Step by Step](https://arxiv.org/abs/2305.20050)).

2. **Add explicit Plan-then-Solve schema** (High impact, Low-Medium complexity).
   Extend planner JSON with `plan_steps` then `execution`; require plan before complex tasks.
   Related: [Plan-and-Solve Prompting](https://arxiv.org/abs/2305.04091).

3. **Add self-consistency for hard prompts** (High impact, Medium complexity).
   Sample 3-5 reasoning traces and select consensus answer.
   Related: [Self-Consistency](https://arxiv.org/abs/2203.11171).

4. **Add iterative self-feedback on final drafts** (Medium-High impact, Medium complexity).
   Critique-answer-revise loop with capped iterations and regression checks.
   Related: [Self-Refine](https://arxiv.org/abs/2303.17651), [Reflexion](https://arxiv.org/abs/2303.11366).

5. **Add uncertainty calibration + abstain/escalate path** (Medium impact, Low complexity).
   Emit confidence + rationale quality score; trigger clarification when low confidence.

6. **Introduce bounded branch search for complex reasoning** (Very high impact, High complexity).
   Beam/tree search over thought states with value scoring and pruning.
   Related: [Tree of Thoughts](https://arxiv.org/abs/2305.10601), [LATS](https://arxiv.org/abs/2310.04406), [Graph of Thoughts](https://arxiv.org/abs/2308.09687).

7. **Long-term: process-reward or RL-based reasoning policy improvements** (Very high potential, Very high complexity).
   Related: [STaR](https://arxiv.org/abs/2203.14465), [DeepSeek-R1](https://arxiv.org/abs/2501.12948).

## Primary references used
- [Chain-of-Thought Prompting Elicits Reasoning in LLMs](https://arxiv.org/abs/2201.11903)
- [ReAct](https://arxiv.org/abs/2210.03629)
- [Self-Consistency](https://arxiv.org/abs/2203.11171)
- [Plan-and-Solve Prompting](https://arxiv.org/abs/2305.04091)
- [Tree of Thoughts](https://arxiv.org/abs/2305.10601)
- [Reflexion](https://arxiv.org/abs/2303.11366)
- [Self-Refine](https://arxiv.org/abs/2303.17651)
- [Let's Verify Step by Step](https://arxiv.org/abs/2305.20050)
- [Language Agent Tree Search](https://arxiv.org/abs/2310.04406)
- [Graph of Thoughts](https://arxiv.org/abs/2308.09687)
- [MemGPT](https://arxiv.org/abs/2310.08560)
- [DeepSeek-R1](https://arxiv.org/abs/2501.12948)
