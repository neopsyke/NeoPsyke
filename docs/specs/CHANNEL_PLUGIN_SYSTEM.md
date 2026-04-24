# Channel Plugin System

**Status:** Draft
**Date:** 2026-04-15
**Scope:** Communication-channel architecture for inbound and outbound user contact

## Purpose

This specification defines a generic channel plugin system for NeoPsyke.

The design goal is to keep the main agent independent from concrete contact
surfaces such as the web dashboard, Telegram, or a future local voice channel.
NeoPsyke should reason over normalized text plus typed channel metadata, not
over transport-specific APIs or media pipelines.

This spec recommends:

- building a generic channel plugin system
- exposing only normalized text plus typed channel metadata to NeoPsyke
- using static registry plus config enablement rather than full autodiscovery

If voice later grows into multiple channel variants, a shared speech/media layer
may be extracted at that time. That future extraction is explicitly out of
scope for this spec.

## Problem Statement

The current runtime already contains the right architectural intuition, but it
is not yet generalized into a reusable channel system.

Today:

- inbound web chat and Telegram both normalize into `ConversationContext` and
  enqueue text into the sensory path
- outbound user contact is routed through `contact_user` and
  `ConversationOutputGateway`
- channel handling is still hardcoded in the runtime and output gateway
- `webapp` and `telegram` are treated as special cases instead of first-class
  channel implementations

This creates several problems:

- adding a new channel requires editing hardcoded routing branches
- channel-specific runtime concerns are mixed into the composition root
- modality differences such as text vs voice are not modeled explicitly enough
  for planner shaping
- the system does not yet provide one stable contract for channel authors

## Goals

- Define one generic plugin contract for communication channels.
- Keep NeoPsyke's core agent ignorant of transport details.
- Require all inbound channels to normalize to text before entering the agent.
- Preserve typed runtime metadata so the planner can distinguish voice from text
  without text heuristics.
- Support both inbound and outbound delivery through the same channel plugin.
- Allow channel-specific session mapping and security/policy mapping.
- Allow channels to be enabled or disabled through config.
- Keep the initial discovery model simple, explicit, and debuggable.
- Provide a clean migration path for existing `webapp` and `telegram` support.

## Non-Goals

- Designing the concrete `local-voice` implementation.
- Introducing a general STT/TTS/VAD/wake-word provider API in the core.
- Full autodiscovery from arbitrary directories or third-party plugin bundles.
- Creating a plugin marketplace or remote plugin installation model.
- Changing planner semantics beyond adding typed channel metadata.
- Changing approval policy semantics beyond moving mapping logic behind channel
  boundaries.

## Locked Decisions

- Communication channels are not action plugins.
- The agent core consumes normalized text plus typed channel metadata only.
- A channel plugin may internally encapsulate media pipelines, protocol clients,
  or transport adapters.
- Voice-specific media components remain internal to the voice channel plugin in
  the first iteration.
- The initial discovery model is static registry plus config enablement.
- Planner-visible modality must come from typed runtime metadata, never from
  deterministic text heuristics.

## Core Principles

### Channels are boundary adapters

A communication channel is an adapter between an external surface and NeoPsyke's
conversation model.

It owns:

- ingress from the external surface
- egress back to the surface
- session and address mapping
- channel-specific authentication and security mapping
- policy-scope mapping and principal mapping

It does not own:

- planner logic
- approval semantics
- long-term memory behavior
- agent-side conversation reasoning

### The agent only sees normalized conversation inputs

All inbound channel data must be normalized into a common shape before entering
the sensory path.

At minimum, the normalized inbound payload contains:

- text content
- `ConversationContext`
- typed channel metadata
- source identifier
- optional timestamps and channel event ids

The agent must not know whether the text came from:

- a keyboard
- a webhook
- a bot API
- a microphone
- an audio transcription pipeline

The agent may know:

- whether the current conversation modality is text or voice
- whether the surface is direct chat, group, shared workspace, or other typed
  runtime metadata

### Modality is first-class metadata

The planner should be able to adapt to voice vs text through structured context,
not by guessing from the user message.

Examples of planner-relevant modality effects:

- voice replies should usually be shorter and more speakable
- voice replies should avoid raw URLs and long enumerations unless necessary
- voice may prefer more confirmation and turn-taking
- text may tolerate more dense formatting and higher information density

### One user-output action

NeoPsyke should continue to use one generic user-contact action
(`contact_user`) for user-visible delivery.

Channel plugins decide how that text is delivered on the concrete surface.

Examples:

- web chat plugin writes to the dashboard session
- Telegram plugin sends a Telegram message
- local voice plugin converts reply text into spoken output

### Simple discovery beats magical discovery

For the first iteration, plugin discovery should be explicit and predictable.

The runtime should know about a fixed registry of channel plugin factories and
instantiate only those enabled in configuration.

This keeps:

- startup deterministic
- debugging straightforward
- configuration validation clear
- operational behavior legible

## Target Architecture

### 1. Channel Plugin Contract

Each channel plugin must implement a stable contract with four required
responsibilities:

- ingress adapter
- egress adapter
- session/address mapping
- security/policy mapping

Suggested top-level shape:

```kotlin
interface ChannelPlugin {
    val descriptor: ChannelDescriptor

    fun createRuntime(context: ChannelPluginContext): ChannelRuntime
}

data class ChannelDescriptor(
    val id: String,
    val displayName: String,
    val supportsInbound: Boolean,
    val supportsOutbound: Boolean,
    val modality: ChannelModality,
)

interface ChannelRuntime : AutoCloseable {
    fun start()
}
```

The plugin contract is intentionally runtime-oriented. A channel plugin is a
composition unit, not a planner tool.

### 2. Ingress Adapter

The ingress side receives external events and translates them into normalized
inputs for NeoPsyke.

Suggested normalized shape:

```kotlin
data class NormalizedChannelInput(
    val text: String,
    val source: String,
    val priority: InputPriority,
    val conversationContext: ConversationContext,
    val receivedAtMs: Long,
    val externalEventId: String? = null,
)
```

The channel runtime is responsible for:

- validating channel-specific authenticity
- normalizing content into text
- resolving session identity
- resolving interlocutor identity
- constructing the correct `ConversationContext`
- routing through approvals when required
- ultimately submitting the normalized text to the sensory path

### 3. Egress Adapter

The egress side accepts normalized reply text from NeoPsyke and delivers it to
the concrete surface.

Suggested shape:

```kotlin
interface ChannelEgressAdapter {
    suspend fun deliver(
        text: String,
        conversationContext: ConversationContext,
    ): ConversationDeliveryResult
}
```

The existing `contact_user` action remains the only agent-level delivery action.
The output gateway resolves the correct channel adapter from the current
conversation context.

### 4. Session and Address Mapping

Each channel owns the mapping between external addresses and NeoPsyke session
identity.

Examples:

- dashboard chat session id -> NeoPsyke session id
- Telegram chat id -> NeoPsyke session id
- local voice device/session -> NeoPsyke session id

This mapping belongs in the channel layer because it depends on external
surface semantics and should not leak into planner or action code.

### 5. Security and Policy Mapping

Each channel maps its external identity and trust model into typed internal
security state.

Each plugin must construct:

- principal identity
- principal role
- channel provider id
- channel surface
- channel transport
- modality
- policy scope
- instruction trust

That mapping feeds the existing approval and planner machinery via
`ConversationContext`.

## Typed Channel Metadata

The existing `ConversationContext` and `ChannelRef` should be extended so the
planner can reliably distinguish contact surfaces and modality.

This spec recommends introducing:

```kotlin
enum class ChannelModality {
    TEXT_CHAT,
    VOICE,
}
```

And extending `ChannelRef` with:

```kotlin
val modality: ChannelModality
```

This field is planner-visible metadata.

It must be propagated through:

- inbound message construction
- `ConversationContext`
- planner context shaping
- output routing
- recording/replay artifacts when channel metadata is persisted

## Planner Alignment

Planner behavior must be able to see typed channel metadata. This is required
so future planner shaping can adapt safely to modality without violating the
repository's routing rules.

The planner must not infer voice or text from:

- punctuation style
- message length
- user wording
- filler words
- channel-specific text artifacts

Instead, planner prompts should receive structured context such as:

- `provider=telegram`
- `surface=direct`
- `transport=chat`
- `modality=text_chat`

or:

- `provider=local-voice`
- `surface=direct`
- `transport=api`
- `modality=voice`

This is the only allowed mechanism for modality-aware planner behavior in this
design.

## Discovery and Registration Model

### Chosen Model

The initial model is:

- static registry of known channel plugin factories
- config-driven enablement of plugins

This avoids the complexity of full autodiscovery while preserving a real plugin
boundary.

### Why This Model

This model is preferred because:

- NeoPsyke currently has a small number of first-party channels
- startup behavior remains obvious
- configuration errors are easy to diagnose
- there is no need yet to load arbitrary third-party channel code
- the architectural win comes from stable contracts, not from runtime scanning

### Suggested Runtime Shape

```kotlin
interface ChannelPluginFactory {
    val pluginId: String
    fun create(context: ChannelPluginContext): ChannelPlugin
}

object ChannelPluginRegistry {
    val factories: Map<String, ChannelPluginFactory> = mapOf(
        WebappChannelPluginFactory.pluginId to WebappChannelPluginFactory,
        TelegramChannelPluginFactory.pluginId to TelegramChannelPluginFactory,
        LocalVoiceChannelPluginFactory.pluginId to LocalVoiceChannelPluginFactory,
    )
}
```

Runtime composition loads only the channel ids enabled in config.

## Configuration Model

The top-level runtime should move away from one-off hardcoded channel wiring and
toward a unified channel configuration section.

Illustrative shape:

```yaml
agent:
  channels:
    enabled:
      - webapp
      - telegram
      - local-voice

    webapp:
      enabled: true

    telegram:
      enabled: true
      mode: polling
      owner_chat_id: "12345"
      owner_user_id: "12345"
      policy_scope_id: default

    local-voice:
      enabled: false
      policy_scope_id: default
```

The final config structure may reuse existing config fields during migration,
but the target direction is one channel-oriented configuration surface.

## Runtime Composition

The composition root should create a `ChannelRuntimeRegistry` or similar
coordinator responsible for:

- validating configured channel ids against the static registry
- instantiating enabled plugins
- starting plugin runtimes
- exposing outbound delivery adapters
- exposing channel availability to user-contact resolution

Suggested shape:

```kotlin
class ChannelRuntimeRegistry(
    private val runtimesById: Map<String, ChannelRuntimeHandle>,
) : AutoCloseable
```

Where each handle can expose:

- descriptor
- runtime
- optional inbound runtime
- optional outbound adapter
- availability status

## Migration Plan

### Phase 1: Introduce generic contracts

- Add channel descriptors and plugin contracts.
- Add `ChannelModality` to typed channel metadata.
- Add a channel runtime registry in the composition root.

### Phase 2: Migrate existing channels

- Repackage dashboard chat as a `webapp` channel plugin.
- Repackage Telegram ingress/egress as a `telegram` channel plugin.
- Remove hardcoded channel branches from the output gateway and resolver.

### Phase 3: Add first new plugin

- Implement `local-voice` as the first plugin built on the new system.

## Existing Components To Reuse

The current runtime already has reusable pieces that should be retained:

- `ConversationContext` as the central conversation identity object
- `contact_user` as the generic user-output action
- `ConversationOutputGateway` as the delivery abstraction point
- approval routing based on `ConversationContext`
- `AsyncSignalSource.submitInput(...)` as the normalized ingress path

This spec does not replace those ideas. It generalizes how channels plug into
them.

## Explicit Out-of-Scope Future Direction

If voice later expands into multiple channel variants such as:

- local desktop voice
- browser voice
- phone or telephony voice
- remote voice gateway

then a shared speech/media abstraction may be extracted later.

That future direction may define reusable interfaces for:

- STT
- TTS
- VAD
- wake word

That extraction is not part of this spec. The first version keeps all such
media concerns internal to the concrete voice channel plugin.

## Risks and Failure Modes

- Over-generalizing the plugin contract too early and making simple channels
  harder to implement.
- Leaving hardcoded routing branches in place and ending up with a hybrid system
  that is neither generic nor simple.
- Failing to make modality typed and planner-visible, which would push future
  behavior back toward text heuristics.
- Letting channel plugins bypass approval or security mapping contracts.
- Treating media internals as part of the core too early and coupling NeoPsyke
  to audio implementation details.

## Acceptance Criteria

- A generic `ChannelPlugin` contract exists and is used by runtime composition.
- `webapp` and `telegram` can be expressed as channel plugins using that
  contract.
- NeoPsyke core receives normalized text plus typed channel metadata only.
- `ConversationContext` carries typed modality metadata for planner use.
- `contact_user` remains the agent-level user-delivery action.
- Outbound routing no longer depends on hardcoded `webapp` and `telegram`
  branches in the architecture design.
- Plugin enablement is config-driven through an explicit registry model.
- This spec does not require a shared speech/media provider API.

## Success Condition

After this refactor, adding a new communication surface should mean:

- implement one channel plugin
- register it in the static registry
- enable it in config

without changing planner semantics, sensory semantics, or the main agent loop.
