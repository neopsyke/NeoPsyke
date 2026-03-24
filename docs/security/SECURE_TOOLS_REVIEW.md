# NeoPsyke Secure Tools Review

> **Purpose:** Define the security architecture for new NeoPsyke action plugins
> (Morning Briefing, Email/Inbox Management, Content & Social Media Automation)
> based on lessons learned from OpenClaw, OpenAI/MCP, and the broader AI agent
> security landscape.
>
> **Date:** 2026-03-22
> **Status:** Proposal — awaiting review

---

## Table of Contents

1. [Motivation & Planned Features](#1-motivation--planned-features)
2. [Threat Landscape: OpenClaw Findings](#2-threat-landscape-openclaw-findings)
3. [Threat Landscape: OpenAI & MCP Findings](#3-threat-landscape-openai--mcp-findings)
4. [NeoPsyke Architecture Mapping](#4-neopsyke-architecture-mapping)
5. [Vulnerability-to-Feature Matrix](#5-vulnerability-to-feature-matrix)
6. [Proposed Security Upgrades](#6-proposed-security-upgrades)
7. [Implementation Roadmap](#7-implementation-roadmap)
8. [External References](#8-external-references)

---

## 1. Motivation & Planned Features

Three high-value use cases drive the next round of action plugins:

| Feature | Description | Key Integrations |
|---------|-------------|------------------|
| **Morning Briefing** | Scheduled pull of calendar, email, weather, news, tasks → formatted digest via Telegram/WhatsApp | Calendar API, Email API, Weather API, News API, Telegram Bot API, WhatsApp Business API |
| **Email / Inbox Management** | Process thousands of emails: unsubscribe from noise, categorize by urgency, draft replies | Email API (IMAP/Graph), Unsubscribe endpoints, Draft composition |
| **Content & Social Media** | RSS → platform-specific posts for X/LinkedIn; newsletter drafting with deduplication | RSS feeds, X API, LinkedIn API, Newsletter platform API |

All three features share a critical property: **they process untrusted external
content (emails, RSS, web pages) and produce high-blast-radius outputs (public
posts, sent emails, persistent memory changes).**

---

## 2. Threat Landscape: OpenClaw Findings

### 2.1 What Is OpenClaw

OpenClaw (originally "Clawdbot", then "Moltbot") is an open-source autonomous AI
agent framework created by Peter Steinberger. It reached 247,000+ GitHub stars by
March 2026, making it the fastest-growing project in GitHub history. It transforms
any LLM into a persistent always-on agent communicating through Telegram, WhatsApp,
Slack, Discord, Signal, iMessage, and Teams.

Its architecture has three layers:
- **Tools** — typed functions the agent calls (26 built-in: `exec`, `browser`,
  `web_search`, `message`, etc.)
- **Skills** — markdown `SKILL.md` files injected into the system prompt
- **Plugins** — packages registering channels, model providers, tools, and skills

OpenClaw has a public marketplace called **ClawHub** with 10,700+ community skills.

### 2.2 Critical Vulnerabilities

#### CVE-2026-25253 — 1-Click RCE via Auth Token Theft (CVSS 8.8)
The Control UI trusted a `gatewayURL` query parameter and sent auth tokens over
WebSocket without origin verification. A single visit to a malicious page was
enough to fully compromise the victim.

- **NVD:** https://nvd.nist.gov/vuln/detail/CVE-2026-25253
- **Writeup:** https://depthfirst.com/post/1-click-rce-to-steal-your-moltbot-data-and-keys
- **SonicWall:** https://www.sonicwall.com/blog/openclaw-auth-token-theft-leading-to-rce-cve-2026-25253

#### "ClawJacked" — Full Agent Takeover via WebSocket (High)
Any website could brute-force the gateway password on localhost (rate limiter
exempts localhost), register as a trusted device with no user prompt, and gain
full control.

- **Oasis Security:** https://www.oasis.security/blog/openclaw-vulnerability

#### CVE-2026-32064 — Unauthenticated VNC Access (CVSS 7.7)
Sandbox browser sessions exposed via unauthenticated VNC.

#### CVE-2026-32056 — Shell Env Variable Injection (CVSS 7.5)
Shell startup environment variable injection bypassed command allowlists.

#### CVE-2026-32055 — Workspace Path Traversal (High)
Crafted symlinks enabled path traversal beyond workspace boundaries.

#### CVE-2026-32048 — Sandbox Escape via Cross-Agent Spawning (High)
Cross-agent session spawning escaped sandboxes.

#### Additional tracked vulnerabilities
All 17 GHSAs tracked at: https://github.com/jgamblin/OpenClawCVEs/

A January 2026 audit found **512 vulnerabilities, 8 critical**.

### 2.3 ClawHub Supply Chain Attack ("ClawHavoc")

Koi Security audited all 2,857 skills on ClawHub and found **341 malicious
entries**, with 335 traced to a single coordinated operation ("ClawHavoc"). By
mid-February 2026, confirmed malicious skills grew to **824+ across 10,700+
skills**.

A Snyk study ("ToxicSkills") found that **36.82% of 9,234 published skills
contained security flaws**, with **91% of malicious skills combining prompt
injection with traditional malware**.

Skills masqueraded as cryptocurrency trading tools and delivered info-stealing
malware (Atomic Stealer on macOS, password-protected ZIP payloads on Windows),
exfiltrating crypto wallets, seed phrases, macOS Keychain data, browser passwords,
and cloud credentials.

- **The Hacker News:** https://thehackernews.com/2026/02/researchers-find-341-malicious-clawhub.html
- **Snyk ToxicSkills:** https://snyk.io/articles/clawdhub-malicious-campaign-ai-agent-skills/
- **Socket.dev:** https://socket.dev/blog/openclaw-skill-marketplace-emerges-as-active-malware-vector
- **VirusTotal:** https://blog.virustotal.com/2026/02/from-automation-to-infection-how.html

### 2.4 Prompt Injection → Persistent Backdoor via SOUL.md

OpenClaw's context file (`SOUL.md`) is writable by the agent. Researchers
demonstrated that a crafted email or web page tricks the agent into modifying
`SOUL.md` and creating scheduled tasks that re-inject attacker logic **across
restarts**. The backdoor survives reboots.

- **Cisco:** https://blogs.cisco.com/ai/personal-ai-agents-like-openclaw-are-a-security-nightmare
- **Penligent AI:** https://www.penligent.ai/hackinglabs/the-openclaw-prompt-injection-problem-persistence-tool-hijack-and-the-security-boundary-that-doesnt-exist/
- **Eye Security:** https://www.eye.security/blog/log-poisoning-openclaw-ai-agent-injection-risk
- **Giskard:** https://www.giskard.ai/knowledge/openclaw-security-vulnerabilities-include-data-leakage-and-prompt-injection-risks

### 2.5 Exposed Instances — 390,000+ on Public Internet

SecurityScorecard's STRIKE team found 390,000+ OpenClaw instances accessible from
the public internet, with 243,000+ still live. Out of the box, OpenClaw binds to
`0.0.0.0:18789` (all interfaces). Researchers found instances leaking API keys,
Telegram bot tokens, Slack accounts, and months of chat histories, with full admin
command execution.

- **SecurityScorecard:** https://securityscorecard.com/blog/beyond-the-hype-moltbots-real-risk-is-exposed-infrastructure-not-ai-superintelligence/
- **The Register:** https://www.theregister.com/2026/02/09/openclaw_instances_exposed_vibe_code/
- **Bitsight:** https://www.bitsight.com/blog/openclaw-ai-security-risks-exposed-instances

### 2.6 Log Poisoning

Externally supplied header values were stored verbatim in OpenClaw logs. When the
agent reads logs for debugging, injected content becomes part of the model's
reasoning context.

### 2.7 Moltbook Data Breach

Moltbook (social network for OpenClaw agents) suffered a breach: a Supabase
database deployed without Row Level Security exposed **1.5 million API keys,
35,000 email addresses, and 4.75 million records**. Malicious prompt injections
were hidden in posts to trick visiting agents into sharing data.

- **Fortune:** https://fortune.com/2026/01/31/ai-agent-moltbot-clawdbot-openclaw-data-privacy-security-nightmare-moltbook-social-network/
- **Wiz discovery & patch:** referenced in Fortune article

### 2.8 Regulatory Warnings

| Authority | Stance | Reference |
|-----------|--------|-----------|
| Dutch DPA (AP) | "Trojan Horse" — warns against use | https://www.autoriteitpersoonsgegevens.nl/en/current/ap-warns-of-major-security-risks-with-ai-agents-like-openclaw |
| Microsoft | Advises against running with primary accounts | https://www.microsoft.com/en-us/security/blog/2026/02/19/running-openclaw-safely-identity-isolation-runtime-risk/ |
| CrowdStrike | Detailed security team guidance | https://www.crowdstrike.com/en-us/blog/what-security-teams-need-to-know-about-openclaw-ai-super-agent/ |
| Kaspersky | Unsafe; treat as public-facing server | https://www.kaspersky.com/blog/openclaw-vulnerabilities-exposed/55263/ |
| China CNCERT | Warning about weak default configs | — |
| Tsinghua/Ant Group | Five-layer security framework for OpenClaw | https://www.marktechpost.com/2026/03/18/tsinghua-and-ant-group-researchers-unveil-a-five-layer-lifecycle-oriented-security-framework-to-mitigate-autonomous-llm-agent-vulnerabilities-in-openclaw/ |

---

## 3. Threat Landscape: OpenAI & MCP Findings

### 3.1 OpenAI Tool-Use Vulnerabilities

**Indirect Prompt Injection (Greshake et al., 2023):** Demonstrated that tools
retrieving external content (web, email, documents) create an indirect injection
channel. The attacker never talks to the model directly — the model fetches
attacker-controlled content that contains instructions.

**Plugin/GPT Security Gaps:**
- No permission scoping per tool — a plugin with `read_email` access could also
  trigger `send_email` within the same plugin boundary.
- OAuth token over-scoping — tokens granted to one plugin were sometimes valid
  across plugin boundaries.
- No content provenance — the model couldn't distinguish between user instructions
  and content fetched via tools.

**Confused Deputy Problem:** The LLM acts as a "confused deputy" — it has the
authority to act (send email, post content, modify files) but relies on
untrusted input to decide what actions to take.

### 3.2 MCP (Model Context Protocol) Security Concerns

**Tool Poisoning Attacks:** MCP tools include description fields injected into the
LLM context. A malicious MCP server can embed hidden instructions in tool
descriptions (using non-printable characters or misleading framing) that instruct
the model to exfiltrate data or override safety guidelines.

**Rug-Pull / Server Mutation:** An MCP server approved during initial review can
change its tool descriptions at any time. After trust is established, the server
mutates descriptions to include malicious instructions.

**Cross-Server Data Leakage:** When multiple MCP servers are connected, one server's
tool can request data from another server's tool via the LLM. No isolation between
MCP server contexts exists in the base protocol.

**No Signature Verification:** MCP has no built-in mechanism to verify that a tool's
code matches its description, or that tool descriptions haven't been tampered with
since review.

### 3.3 General AI Agent Security Research

**CaMeL Framework (Google DeepMind, 2025):** Proposes separating the LLM reasoning
layer from the data flow layer. External data is tagged with provenance labels
(`TRUSTED`/`TAINTED`/`SANITIZED`) that propagate through the pipeline. Tainted
data cannot be used as arguments to high-privilege tools without explicit
sanitization. Relevant paper:
https://arxiv.org/abs/2503.18813

**OWASP Top 10 for LLM Applications (2025 edition):**
- LLM01: Prompt Injection (direct + indirect)
- LLM07: Insecure Plugin Design (insufficient input validation, excessive permissions)
- LLM08: Excessive Agency (tools with too much capability, no human-in-the-loop)
- Reference: https://genai.owasp.org/resource/owasp-top-10-for-llm-applications-2025/

---

## 4. NeoPsyke Architecture Mapping

### 4.1 Current Action Pipeline

```
User Input → SensoryCortex → Ego.runLoop()
  → AttentionScheduler (priority: Inputs > Impulses > Goals > Thoughts > Actions)
  → Planner → EgoDecision.ProposeAction
  → DecisionDispatcher → PendingAction queued
  → ActionReviewPipeline:
      1. Scratchpad Final Pass (CONTACT_USER only)
      2. DecisionVerifier (evidence sufficiency)
      3. SuperegoDeterministicConscience (hard-deny gates)
         a. Shape validation
         b. Id-origin policy allowlist
         c. Plugin's deterministicReview()
      4. SuperegoReviewEngine (LLM-based ethical review)
      5. MotorCortex.execute() → ActionRegistry → Plugin.execute()
      6. Post-execute: evidence recording, memory journal, follow-up thought
```

### 4.2 Current Security Stack

| Layer | Component | File | Function |
|-------|-----------|------|----------|
| Input sanitization | `SensoryCortex` | `cortex/sensory/SensoryCortex.kt` | Validates conversation context, linguistic input |
| Prompt injection | `PromptInjectionDefense` | `support/PromptInjectionDefense.kt` | Scans for instruction_override, prompt_exfiltration, tool_abuse, role_spoofing |
| Payload security | `ActionPayloadSecurity` | `support/ActionPayloadSecurity.kt` | Detects secret/PII exfil, blocks localhost/RFC1918, sensitive endpoints/params |
| Text normalization | `TextSecurity` | `support/TextSecurity.kt` | Clamp, preview (truncate for logs), token estimation |
| Deterministic deny | `SuperegoDeterministicConscience` | `superego/SuperegoDeterministicConscience.kt` | Shape validation, Id-origin policy, plugin deterministic review |
| Ethical review | `SuperegoReviewEngine` | `superego/Superego.kt` | Two-stage LLM review (primary + escalation) |
| Network isolation | `WebsiteFetchActionPlugin` | `actions/plugins/WebsiteFetchActionPlugin.kt` | HTTPS-only, blocks private IPs, sensitive endpoints |
| Env isolation | `NpmCommandIsolation` | `support/NpmCommandIsolation.kt` | Strips credentials from env, scoped cache |
| Evidence verification | `DecisionVerifier` | `ego/DecisionVerifier.kt` | Volatility assessment, evidence sufficiency gates |
| Loop prevention | `DeliberationEngine` | `ego/DeliberationEngine.kt` | Stale streak, repeat signature, decision pressure, cooldowns |

### 4.3 Current Plugin Registry

Plugins are discovered at startup via Java `ServiceLoader<AgentActionPluginFactory>`:

```
META-INF/services/ai.neopsyke.agent.actions.AgentActionPluginFactory
├── ContactUserActionPluginFactory
├── ResolutionDraftActionPluginFactory
├── WebSearchActionPluginFactory
├── McpTimeActionPluginFactory
├── WebsiteFetchActionPluginFactory
├── ReflectActionPluginFactory
├── GoalOperationActionPluginFactory
└── MicrosoftGraphEmailActionPluginFactory
```

**This is fundamentally safer than OpenClaw's runtime-loaded skills** — plugins are
compiled, version-controlled, and reviewed.

### 4.4 Key Data Models for Security Context

```kotlin
// Action origin tracking (current)
enum class ActionOrigin { USER, ID, SYSTEM, GOAL }

// Action capabilities (current)
enum class ActionCapability {
    PRODUCES_USER_OUTPUT,
    GATHERS_EVIDENCE,
}

// Action effects (current)
enum class ActionEffect {
    TASK_PROGRESS,
    EVIDENCE_GATHERED,
    DURABLE_MEMORY_SAVED,
    USER_MESSAGE_DELIVERED,
}
```

### 4.5 Current Gaps

| Gap | Description | Exploitable By |
|-----|-------------|----------------|
| **Planner-context provenance carriage still partial** | Root-scoped thread taint now exists, but prompt/scratchpad provenance is not yet fully typed end-to-end | Residual confused-deputy risk |
| **No blast radius classification** | `REFLECT` and `EMAIL_SEND` treated with same framework | Excessive agency |
| **Rate limiting rollout still incomplete** | Centralized action-control rate limits now exist, but the policy surface is still coarse and not yet time-window based | Hijacked agent amplification |
| **Generic inbound channel authentication not generalized** | Telegram owner webhook auth is implemented, but the same framework is not yet abstracted for future channels | Webhook injection when new channels are added carelessly |
| **Sanitization rollout still incomplete for future paths** | Current web/google/MCP/connector observe paths route through a unified external-content pipeline, but future integrations must keep using it | Log/memory poisoning |
| **`ActionCapability` too coarse** | Only `PRODUCES_USER_OUTPUT` and `GATHERS_EVIDENCE` — no `EXTERNAL_BROADCAST`, `PERSISTENT_WRITE`, etc. | Insufficient review granularity |

---

## 5. Vulnerability-to-Feature Matrix

This matrix maps each discovered vulnerability class to the planned features and
rates the risk if the feature is built **without** the corresponding fix.

| # | Vulnerability Class | Source | Morning Briefing | Email Mgmt | Social/Content | Risk Without Fix |
|---|---------------------|--------|:----------------:|:----------:|:--------------:|:----------------:|
| V1 | Persistent backdoor via memory/goals | OpenClaw SOUL.md | ★★★ | ★★★★★ | ★★★★ | **CRITICAL** |
| V2 | Supply chain skill poisoning | OpenClaw ClawHavoc | ★ | ★ | ★ | LOW (today) |
| V3 | Inbound channel auth bypass | OpenClaw ClawJacked | ★★★★★ | ★★★ | ★★ | **HIGH** |
| V4 | Log/memory poisoning | OpenClaw + Eye Security | ★★★ | ★★★★ | ★★★ | MODERATE |
| V5 | Confused deputy / excessive agency | OpenAI + OWASP LLM08 | ★★★ | ★★★★★ | ★★★★★ | **HIGH** |
| V6 | Tool poisoning via descriptions | MCP | ★★ | ★★ | ★★ | MODERATE |
| V7 | Cross-tool data leakage | MCP cross-server | ★★★ | ★★★★ | ★★★ | MODERATE |
| V8 | OAuth/credential over-scoping | OpenAI plugins | ★★★ | ★★★★ | ★★★★ | **HIGH** |
| V9 | Default-insecure network exposure | OpenClaw 0.0.0.0 | ★★★★ | ★★ | ★★ | MODERATE |
| V10 | Indirect prompt injection | Greshake et al. | ★★★ | ★★★★★ | ★★★★ | **CRITICAL** |
| V11 | Rug-pull / server mutation | MCP | ★ | ★ | ★ | LOW (no marketplace) |

**Legend:** ★ = minimal exposure, ★★★★★ = maximum exposure

---

## 6. Proposed Security Upgrades

### P0 — Must complete BEFORE adding any new action plugins

#### P0.1 — Tainted Context Propagation (fixes V1, V10)

**Problem:** OpenClaw's most devastating attack was prompt injection via email →
persistent backdoor in SOUL.md. NeoPsyke has the same path: email content →
`REFLECT` action → persistent memory, or → `GOAL_OPERATION` → scheduled task.

**Solution:** Add a `tainted: Boolean` field to the action execution context that
propagates when the current deliberation cycle involves any external content.

```kotlin
// New field on ActionExecutionContext or PendingAction metadata
data class ContentProvenance(
    val taintLevel: TaintLevel,         // TRUSTED, TAINTED, SANITIZED
    val sourceType: ContentSourceType,  // USER_DIRECT, FETCHED_WEB, EMAIL_BODY,
                                        // RSS_FEED, CALENDAR_EVENT, API_RESPONSE
    val sourceId: String? = null,       // URL, email message-id, etc.
)

enum class TaintLevel { TRUSTED, TAINTED, SANITIZED }
```

**When `taintLevel == TAINTED`:**
- `REFLECT` actions: hard-deny payloads containing URLs, email addresses,
  scheduling patterns, or instruction-like language ("always", "never",
  "from now on", "remember to")
- `GOAL_OPERATION` actions: hard-deny CREATE and REVISE_PLAN operations entirely
- `CONTACT_USER` actions: append a taint warning to output
- `EMAIL_SEND` / `SOCIAL_POST` actions: require content to come from a
  deterministic template, not raw LLM generation over tainted input

**Implementation path:**
1. Add `ContentProvenance` to `PendingAction` metadata
2. Set provenance in each plugin's `execute()` when processing external content
3. Check provenance in `SuperegoDeterministicConscience` before existing gates
4. Add taint-aware rules to `ReflectActionPlugin.deterministicReview()`
5. Add taint-aware rules to `GoalOperationActionPlugin.deterministicReview()`

**Key files to modify:**
- `ActionPluginContracts.kt` — add `ContentProvenance` to context
- `QueueModels.kt` — carry provenance on `PendingAction`
- `SuperegoDeterministicConscience.kt` — add taint-aware gate
- `ReflectActionPlugin.kt` — taint-aware deterministic review
- `GoalOperationActionPlugin.kt` — taint-aware deterministic review

#### P0.2 — Action Blast Radius Classification (fixes V5)

**Problem:** OpenClaw treats `exec` (shell commands) and `web_search` (read-only)
with the same permission model. NeoPsyke's `ActionCapability` enum is too coarse
(`PRODUCES_USER_OUTPUT`, `GATHERS_EVIDENCE`) for the new features.

**Solution:** Extend `ActionCapability` with blast radius categories and apply
proportional review.

```kotlin
enum class ActionCapability {
    // Existing
    PRODUCES_USER_OUTPUT,
    GATHERS_EVIDENCE,

    // New blast radius categories
    READS_EXTERNAL_DATA,        // Fetches from external APIs (calendar, weather)
    MODIFIES_INTERNAL_STATE,    // Writes to memory, goals, scratchpad
    SENDS_PRIVATE_MESSAGE,      // Telegram, WhatsApp, email to known recipient
    BROADCASTS_PUBLIC_CONTENT,  // Social media posts, public forum comments
    MODIFIES_EXTERNAL_STATE,    // Unsubscribe actions, email archive/delete
    MANAGES_CREDENTIALS,        // OAuth token refresh, API key rotation
}
```

**Review escalation by blast radius:**

| Blast Radius | Deterministic Review | Superego Review | Human Confirmation |
|---|---|---|---|
| `READS_EXTERNAL_DATA` | Standard | Optional (skip for known-safe APIs) | No |
| `MODIFIES_INTERNAL_STATE` | Taint-aware (P0.1) | Always | No |
| `SENDS_PRIVATE_MESSAGE` | Standard + recipient validation | Always | Optional (configurable) |
| `BROADCASTS_PUBLIC_CONTENT` | Standard + content policy | Always + escalation stage | **Yes — always** |
| `MODIFIES_EXTERNAL_STATE` | Standard + rate limit | Always | First-N then auto |
| `MANAGES_CREDENTIALS` | N/A — prohibited for LLM | N/A | N/A — human-only |

**Key files to modify:**
- `Enums.kt` — extend `ActionCapability`
- `ActionReviewPipeline.kt` — route review intensity by capability set
- All new plugin descriptors — declare accurate capabilities

#### P0.3 — Per-Action-Type Rate Limiting (fixes V5 amplification)

**Problem:** A hijacked agent could send 50 emails or 20 social posts in a
deliberation loop before any other safety mechanism catches it.

**Solution:** Add configurable rate limits to `ActionReviewPipeline`.

```kotlin
data class ActionRateLimit(
    val actionType: ActionType,
    val maxPerCycle: Int,           // Per deliberation cycle (single input)
    val maxPerHour: Int,            // Rolling window
    val maxPerDay: Int,             // Rolling window
    val cooldownAfterDenyMs: Long,  // Backoff after hitting limit
)
```

**Suggested defaults for new actions:**

| Action | Per Cycle | Per Hour | Per Day |
|--------|-----------|----------|---------|
| `EMAIL_SEND` | 5 | 20 | 100 |
| `EMAIL_UNSUBSCRIBE` | 10 | 50 | 200 |
| `SOCIAL_POST` | 2 | 5 | 20 |
| `TELEGRAM_SEND` | 3 | 10 | 50 |
| `REFLECT` | 3 | 10 | 50 |
| `GOAL_OPERATION` (CREATE) | 1 | 3 | 10 |

**Key files to modify:**
- `ActionReviewPipeline.kt` — add rate limit check before deterministic review
- New config section in `AgentConfig` for rate limit overrides

#### P0.4 — Universal Input Sanitization (fixes V4, V10)

**Problem:** `PromptInjectionDefense.sanitizeExternalText()` is applied to
`WEBSITE_FETCH` results and action follow-up signals, but NOT to email subjects,
email bodies, RSS titles, RSS content, calendar event names, or weather
descriptions. All of these are attacker-controlled strings.

**Solution:** Create a `TaintedContentPipeline` that wraps all external content
before it enters any memory, log, or planner context.

```kotlin
object TaintedContentPipeline {
    fun ingest(
        raw: String,
        source: ContentSourceType,
        maxChars: Int = 4000,
    ): SanitizedContent {
        val injection = PromptInjectionDefense.scan(raw)
        val clamped = TextSecurity.clamp(raw, maxChars)
        val sanitized = PromptInjectionDefense.sanitizeExternalText(clamped, maxChars)
        val framed = PromptInjectionDefense.asUntrustedDataBlock(sanitized, maxChars)
        return SanitizedContent(
            text = framed,
            originalLength = raw.length,
            injectionSignals = injection.signals,
            source = source,
            taintLevel = if (injection.signals.isEmpty()) TaintLevel.SANITIZED
                         else TaintLevel.TAINTED,
        )
    }
}
```

**Must be applied to:**
- Every email subject, body, sender, and header processed by Email Management
- Every RSS title, description, and content snippet
- Every calendar event title, description, and attendee list
- Every weather/news API response body
- Every Telegram/WhatsApp incoming message before it reaches SensoryCortex

**Key files to modify:**
- New file: `support/TaintedContentPipeline.kt`
- Every new action plugin's `execute()` method must call `TaintedContentPipeline.ingest()`
- `MemorySystem.kt` — record taint level alongside episodic entries
- `Ego.kt` — propagate taint level through deliberation cycle

---

### P1 — Must complete WHEN adding messaging channel integrations

#### P1.1 — Inbound Channel Authentication (fixes V3, V9)

**Problem:** OpenClaw's gateway accepted commands from any WebSocket client.
NeoPsyke adding Telegram/WhatsApp webhooks creates an internet-facing attack
surface.

**Solution:** Multi-layer authentication for inbound channels.

```
Internet → Webhook Receiver (separate process) → Signature Verification
  → Rate Limiting → IP Allowlist (optional) → Authenticated Payload
  → IPC to Agent (Unix socket / loopback-only HTTP)
  → SensoryCortex.processInbound()
```

**Requirements:**
1. **Transport-layer auth** — verify Telegram Bot API webhook signatures
   (`X-Telegram-Bot-Api-Secret-Token`), WhatsApp Business API signatures
   (HMAC-SHA256), before any content processing.
2. **Separate process** — the webhook receiver is NOT the agent. It's a minimal
   HTTP server that validates signatures and forwards authenticated payloads over
   a local-only channel.
3. **Default binding** — `127.0.0.1` only. External exposure requires explicit
   opt-in via config + a reverse proxy (nginx/caddy).
4. **Rate limiting on inbound** — per-sender, per-channel, independent of action
   rate limits.

**Key files to create:**
- `channels/telegram/TelegramWebhookReceiver.kt`
- `channels/whatsapp/WhatsAppWebhookReceiver.kt`
- `channels/InboundChannelAuthenticator.kt`
- Config additions to `agent-runtime.yaml`

#### P1.2 — Credential Isolation Per Integration (fixes V8)

**Problem:** OpenAI plugins shared OAuth scopes. OpenClaw exposed all credentials
via a single compromised instance.

**Solution:** Each integration gets isolated credentials with minimal scopes.

**Requirements:**
1. OAuth tokens stored in OS keychain (macOS Keychain, Linux Secret Service),
   never in env vars or config files.
2. Each integration's token has minimum viable scopes:
   - Gmail: `gmail.readonly` + `gmail.send` (NOT `gmail.modify` or full access)
   - Calendar: `calendar.readonly`
   - X/Twitter: `tweet.write` (NOT `dm.read` or `users.read`)
   - LinkedIn: `w_member_social` only
3. Token refresh handled by a dedicated `CredentialManager` that never exposes
   raw tokens to the LLM or plugin payloads.
4. Extend `ActionPayloadSecurity` regex patterns for new token formats:
   Telegram bot tokens (`[0-9]{8,10}:[a-zA-Z0-9_-]{35}`), WhatsApp tokens,
   social API keys.

**Key files to modify:**
- `support/ActionPayloadSecurity.kt` — add token patterns
- New: `credentials/CredentialManager.kt`
- New: `credentials/KeychainStore.kt`

---

### P2 — Architectural hardening (ongoing)

#### P2.1 — CaMeL-Inspired Provenance Tracking (fixes V1, V7, V10)

**Problem:** NeoPsyke (like all current agent frameworks) treats data uniformly
once it enters the planner context. The LLM cannot reliably distinguish between
"user said to post this" and "an email contained the text 'post this'."

**Solution:** Implement provenance tags from the CaMeL framework at the data flow
level, not just at the LLM prompt level.

```kotlin
sealed interface ProvenanceTag {
    data class Trusted(val source: String) : ProvenanceTag      // User input, config
    data class Tainted(val source: String) : ProvenanceTag      // External content
    data class Sanitized(val source: String, val method: String) : ProvenanceTag
}
```

**Enforcement rule:** When constructing action payloads for `BROADCASTS_PUBLIC_CONTENT`
or `SENDS_PRIVATE_MESSAGE` actions, the payload builder must verify that all
variable content fields have provenance `TRUSTED` or `SANITIZED` — never raw
`TAINTED`.

**This is the most architecturally significant change** and should be designed
carefully. Consider adding provenance tracking to the `ScratchpadStore` and
`MemorySystem` so that recalled memories carry their original provenance.

Reference: CaMeL paper — https://arxiv.org/abs/2503.18813

#### P2.2 — MCP Tool Description Pinning (fixes V6, V11)

**Problem:** MCP tool descriptions can be mutated by the server after initial
approval (rug-pull). NeoPsyke uses MCP for time queries today, and may add
more MCP integrations.

**Solution:**
1. On first connection to an MCP server, hash all tool descriptions.
2. On every subsequent connection, verify hashes match.
3. If descriptions change, quarantine the server and alert the user.
4. Never auto-approve changed descriptions.

**Key files to modify:**
- MCP client initialization code
- New: `support/McpToolPinning.kt`

#### P2.3 — Deterministic Output Templates for High-Risk Actions (fixes V5, V10)

**Problem:** Allowing the LLM to freely compose email bodies or social posts
from tainted input is the core confused-deputy risk. The LLM might include
attacker instructions verbatim.

**Solution:** For `BROADCASTS_PUBLIC_CONTENT` and `SENDS_PRIVATE_MESSAGE` actions,
use **deterministic templates** with clearly defined variable slots:

```kotlin
data class OutputTemplate(
    val id: String,
    val templateText: String,       // "New blog post: {{title}} — {{summary}}"
    val slots: Map<String, SlotConstraint>,
)

data class SlotConstraint(
    val maxLength: Int,
    val allowedProvenance: Set<TaintLevel>,  // e.g., only TRUSTED or SANITIZED
    val sanitizationRequired: Boolean,
    val regexPattern: Regex? = null,
)
```

The LLM fills slots, but the template structure and constraints are
deterministic and cannot be overridden by tainted content.

#### P2.4 — Human-in-the-Loop for Irreversible Actions

**Problem:** OpenClaw executed `exec`, email sends, and social posts without
confirmation. Once sent, they cannot be recalled.

**Solution:** Add an `ActionConfirmationGate` to `ActionReviewPipeline` for
actions marked with `BROADCASTS_PUBLIC_CONTENT` or `MODIFIES_EXTERNAL_STATE`.

```kotlin
interface ActionConfirmationGate {
    /**
     * Returns true if the user confirmed the action.
     * Implementation depends on channel: CLI prompt, Telegram inline keyboard,
     * WhatsApp quick reply, etc.
     */
    suspend fun requestConfirmation(
        action: PendingAction,
        summary: String,
        channel: ConfirmationChannel,
    ): Boolean
}
```

**Behavior:**
- Social posts: ALWAYS require confirmation
- Email sends: Configurable (default: first 5 per session require confirmation,
  then auto-approve if all 5 were approved)
- Email unsubscribe: Configurable (default: first 10 require confirmation)
- Telegram/WhatsApp sends: Configurable per recipient

---

## 7. Implementation Roadmap

```
Phase 0 — Security Foundation (before any new plugins)
├── P0.1  Tainted context propagation
├── P0.2  Blast radius classification (extend ActionCapability)
├── P0.3  Per-action-type rate limiting
└── P0.4  Universal input sanitization (TaintedContentPipeline)

Phase 1 — Morning Briefing (lowest risk, read-mostly)
├── P1.1  Inbound channel auth (Telegram webhook receiver)
├── CalendarReadActionPlugin (READS_EXTERNAL_DATA)
├── WeatherReadActionPlugin (READS_EXTERNAL_DATA)
├── NewsReadActionPlugin (READS_EXTERNAL_DATA)
├── TelegramSendActionPlugin (SENDS_PRIVATE_MESSAGE)
├── P1.2  Credential isolation for calendar + weather + news APIs
└── Integration test: injected calendar event → verify no persistent backdoor

Phase 2 — Email Management (highest risk)
├── EmailReadActionPlugin (READS_EXTERNAL_DATA)
├── EmailCategorizeActionPlugin (MODIFIES_INTERNAL_STATE)
├── EmailDraftReplyActionPlugin (SENDS_PRIVATE_MESSAGE, requires P2.3 templates)
├── EmailUnsubscribeActionPlugin (MODIFIES_EXTERNAL_STATE)
├── P1.2  Credential isolation for email (minimal OAuth scopes)
├── P2.4  Human-in-the-loop for send + unsubscribe
└── Red team: crafted emails attempting persistent backdoor, mass exfil, social post

Phase 3 — Social & Content Automation (highest blast radius)
├── RssFeedReadActionPlugin (READS_EXTERNAL_DATA)
├── SocialPostActionPlugin (BROADCASTS_PUBLIC_CONTENT, requires P2.3 + P2.4)
├── NewsletterDraftActionPlugin (SENDS_PRIVATE_MESSAGE + templates)
├── P2.1  Full CaMeL provenance tracking
├── P2.2  MCP tool description pinning
└── Red team: poisoned RSS → verify cannot create unauthorized social posts

Phase 4 — Hardening & Audit
├── P2.3  Deterministic templates for all output actions
├── External security audit
├── Penetration testing (prompt injection + traditional)
└── Ongoing: monitor ClawHub/MCP vulnerability disclosures for new patterns
```

---

## 8. External References

### OpenClaw

| Resource | URL |
|----------|-----|
| CVE-2026-25253 (1-click RCE) | https://nvd.nist.gov/vuln/detail/CVE-2026-25253 |
| CVE tracker (all 17 GHSAs) | https://github.com/jgamblin/OpenClawCVEs/ |
| ClawJacked (Oasis Security) | https://www.oasis.security/blog/openclaw-vulnerability |
| SOUL.md persistent backdoor (Penligent) | https://www.penligent.ai/hackinglabs/the-openclaw-prompt-injection-problem-persistence-tool-hijack-and-the-security-boundary-that-doesnt-exist/ |
| Log poisoning (Eye Security) | https://www.eye.security/blog/log-poisoning-openclaw-ai-agent-injection-risk |
| ClawHavoc supply chain (Hacker News) | https://thehackernews.com/2026/02/researchers-find-341-malicious-clawhub.html |
| ToxicSkills study (Snyk) | https://snyk.io/articles/clawdhub-malicious-campaign-ai-agent-skills/ |
| 390k exposed instances (SecurityScorecard) | https://securityscorecard.com/blog/beyond-the-hype-moltbots-real-risk-is-exposed-infrastructure-not-ai-superintelligence/ |
| Cisco security analysis | https://blogs.cisco.com/ai/personal-ai-agents-like-openclaw-are-a-security-nightmare |
| Kaspersky analysis | https://www.kaspersky.com/blog/openclaw-vulnerabilities-exposed/55263/ |
| Microsoft safe-usage guidance | https://www.microsoft.com/en-us/security/blog/2026/02/19/running-openclaw-safely-identity-isolation-runtime-risk/ |
| CrowdStrike security team guide | https://www.crowdstrike.com/en-us/blog/what-security-teams-need-to-know-about-openclaw-ai-super-agent/ |
| Dutch DPA warning | https://www.autoriteitpersoonsgegevens.nl/en/current/ap-warns-of-major-security-risks-with-ai-agents-like-openclaw |
| Tsinghua five-layer framework | https://www.marktechpost.com/2026/03/18/tsinghua-and-ant-group-researchers-unveil-a-five-layer-lifecycle-oriented-security-framework-to-mitigate-autonomous-llm-agent-vulnerabilities-in-openclaw/ |
| VirusTotal malware analysis | https://blog.virustotal.com/2026/02/from-automation-to-infection-how.html |
| Socket.dev marketplace analysis | https://socket.dev/blog/openclaw-skill-marketplace-emerges-as-active-malware-vector |
| Moltbook breach (Fortune) | https://fortune.com/2026/01/31/ai-agent-moltbot-clawdbot-openclaw-data-privacy-security-nightmare-moltbook-social-network/ |
| Giskard vulnerability analysis | https://www.giskard.ai/knowledge/openclaw-security-vulnerabilities-include-data-leakage-and-prompt-injection-risks |

### OpenAI & MCP

| Resource | URL |
|----------|-----|
| Greshake et al. — Indirect Prompt Injection | https://arxiv.org/abs/2302.12173 |
| OWASP Top 10 for LLM Applications 2025 | https://genai.owasp.org/resource/owasp-top-10-for-llm-applications-2025/ |
| MCP tool poisoning (Invariant Labs) | https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks |
| CaMeL framework (Google DeepMind) | https://arxiv.org/abs/2503.18813 |

### NeoPsyke Internal Files

| Component | Path (relative to project root) |
|-----------|------|
| Action registry | `src/main/kotlin/ai/neopsyke/agent/actions/ActionRegistry.kt` |
| Plugin contracts | `src/main/kotlin/ai/neopsyke/agent/actions/ActionPluginContracts.kt` |
| Review pipeline | `src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt` |
| Decision verifier | `src/main/kotlin/ai/neopsyke/agent/ego/DecisionVerifier.kt` |
| Superego | `src/main/kotlin/ai/neopsyke/agent/superego/Superego.kt` |
| Superego policy | `src/main/kotlin/ai/neopsyke/agent/superego/SuperegoPolicy.kt` |
| Deterministic conscience | `src/main/kotlin/ai/neopsyke/agent/superego/SuperegoDeterministicConscience.kt` |
| Prompt injection defense | `src/main/kotlin/ai/neopsyke/agent/support/PromptInjectionDefense.kt` |
| Action payload security | `src/main/kotlin/ai/neopsyke/agent/support/ActionPayloadSecurity.kt` |
| Text security | `src/main/kotlin/ai/neopsyke/agent/support/TextSecurity.kt` |
| Motor cortex | `src/main/kotlin/ai/neopsyke/agent/cortex/motor/MotorCortex.kt` |
| Deliberation engine | `src/main/kotlin/ai/neopsyke/agent/ego/DeliberationEngine.kt` |
| Memory system | `src/main/kotlin/ai/neopsyke/agent/ego/MemorySystem.kt` |
| Ego orchestrator | `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt` |
| Goal models | `src/main/kotlin/ai/neopsyke/agent/goal/GoalModels.kt` |
| Goals gateway | `src/main/kotlin/ai/neopsyke/agent/goal/GoalsGateway.kt` |
| Async actions | `src/main/kotlin/ai/neopsyke/agent/actions/async/AsyncActionContracts.kt` |
| ServiceLoader config | `src/main/resources/META-INF/services/ai.neopsyke.agent.actions.AgentActionPluginFactory` |
| Reflect plugin | `src/main/kotlin/ai/neopsyke/agent/actions/plugins/ReflectActionPlugin.kt` |
| Goal operation plugin | `src/main/kotlin/ai/neopsyke/agent/actions/plugins/GoalOperationActionPlugin.kt` |
| Website fetch plugin | `src/main/kotlin/ai/neopsyke/agent/actions/plugins/WebsiteFetchActionPlugin.kt` |
| MS Graph email plugin | `src/main/kotlin/ai/neopsyke/agent/actions/plugins/MicrosoftGraphEmailActionPlugin.kt` |
