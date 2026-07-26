# Context Doc — AI Agent Platform (Learning Project)

**Status:** Draft, pre-implementation. Revise as reality diverges.

## What this is

A learning project to understand the architecture underneath platforms like Sierra and Decagon (enterprise conversational AI agent platforms). Not a portfolio piece, not a startup attempt — the goal is to reverse-engineer and actually build the hard parts, not just read about them.

## Why this shape

Sierra/Decagon's real IP is not "LLM + tools." It's a policy-bounded orchestration engine where the LLM handles narrow reasoning tasks (classification, slot-filling, response generation) inside a structurally-enforced state graph, backed by durable conversation state and validated, idempotent tool execution. The goal of this project is to build a small, honest version of that — not a demo that looks impressive but falls apart the moment a conversation needs to remember something from three turns ago, or a tool gets called twice.

## Current scope

- **Channel:** chat only. Voice (SIP/RTP, STT/TTS) is deferred entirely — it's a separate, latency-sensitive subsystem and mixing it in now would mean building two hard things shallowly instead of one thing properly.
- **Flows:** exactly two — `check_order_status` (read-only) and `process_return` (mutating, requires human approval above a value threshold). Two is the minimum needed to find real shared structure vs. flow-specific logic without guessing at a generalized platform prematurely.
- **Order service:** a real, separately deployed Flask + SQLite application — not an in-process mock — specifically so idempotency and retry handling have to be solved for real, not skipped.

## Explicit non-goals right now

- No multi-tenancy, auth, billing, or anything enterprise-SaaS-shaped.
- No generalized "tool plugin marketplace" or dynamic flow registry — config-driven graph per flow is enough until a third flow proves it isn't.
- No voice.
- No production-grade deployment (Docker/K8s/etc.) — this is a local learning build first.

## Existing relevant background

- Prior experience: real-time LLM evaluation platform (Java 21 virtual threads, Spring AI/Groq, Kafka KRaft, Postgres JSONB) — informs the stack choice below.
- Prior experience: Kafka/Avro event processing (Bankstream) — informs the per-conversation-ordering approach via Kafka partitioning.
- Prior experience: custom SIP/RTP voice pipeline (Groq Whisper STT, Piper TTS, Groq Llama) — exists, but deliberately not used yet; voice is Phase 2 at earliest.

## Stack

Java/Spring (not Python), Kafka, Postgres for the core orchestrator. Flask + SQLite for the order service (separate deployable, intentionally simple/different stack — mirrors a realistic scenario where the agent platform integrates with an external system it doesn't control).
