# Local Voice Channel Plugin

**Status:** Stub
**Date:** 2026-04-15
**Scope:** Local headphone-and-microphone voice channel built on the generic channel plugin system

## Purpose

This document is a stub for the future `local-voice` channel plugin
specification.

Its purpose is to reserve the intended architectural shape and constraints for
the first voice channel implementation without prematurely locking media-stack
details.

The authoritative architectural dependency for this feature is
[CHANNEL_PLUGIN_SYSTEM.md](./CHANNEL_PLUGIN_SYSTEM.md).

## Locked Direction

The `local-voice` implementation should be one channel plugin in the generic
channel plugin system.

The plugin should:

- fully encapsulate mic capture, playback, STT, TTS, VAD, wake-word logic, and
  related audio/session concerns internally
- expose only normalized text plus typed channel metadata to NeoPsyke
- present itself to NeoPsyke as a voice-modality communication surface

NeoPsyke should not gain first-class STT, TTS, VAD, or wake-word provider APIs
as part of this feature.

## Intended Scope

The future full spec should define:

- local device ownership model
- push-to-talk vs continuous listening expectations
- turn detection behavior
- interruption and barge-in behavior
- normalized ingress rules from audio to text
- outbound speech playback rules from text to audio
- session and identity mapping for local voice conversations
- policy and security mapping for the local voice surface
- failure behavior when local audio or media tools are unavailable

## Out of Scope For This Stub

- selecting the concrete STT engine
- selecting the concrete TTS engine
- defining wake-word behavior in detail
- choosing between local-only and remote-backed media internals
- defining cross-plugin reusable speech/media contracts

Those details belong in the future implementation spec for this plugin.

## Assumptions

- the plugin runs on the same machine as NeoPsyke
- the user interacts through directly connected microphone/headphones or
  equivalent local audio devices
- the plugin normalizes audio input into text before the agent sees it
- the planner can see typed metadata indicating that the modality is voice

## Required Alignment With Channel System

The future `local-voice` implementation must align with the generic channel
system by providing:

- an ingress adapter that emits normalized text
- an egress adapter that speaks reply text back to the user
- session/address mapping for the local voice session
- security and policy mapping into `ConversationContext`

## Future Spec Contents

The full `local-voice` spec should eventually answer:

- what runtime lifecycle the plugin uses
- what local UX mode is supported first
- how audio buffering and turn completion work
- how reply playback is interrupted by new user speech
- how transcripts and spoken replies are surfaced to the dashboard
- how testability and deterministic non-live validation will work

## Acceptance Target For The Future Full Spec

The final implementation spec should make it possible to build `local-voice`
without changing the core channel-plugin architecture and without introducing a
shared speech/media abstraction into NeoPsyke's main agent.
