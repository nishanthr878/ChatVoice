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
- No production-grade deployment (Docker/K8s/etc.) — this is a local learning build first. **Update:** this is now actively being reconsidered — a live server demo is planned, which will require containerizing both the Spring app and the Flask order service (see decisions-log D18 and the architecture doc's latest status for current scope).
- Proactive VA-initiated greeting (VA speaks first before any user input) is deferred — see decisions-log D18. The system remains purely reactive for now: every turn is a response to an incoming user message, never system-initiated.

**Note on this doc's currency:** this file was written pre-implementation and is now stale in several places (e.g. "exactly two flows" — a third, `intent_classification`, was added and is core to routing; scope has grown to include a REST API and demo widget). Treat `decisions-log.md` and direct project state as the source of truth over this file's specific claims; this doc captures original intent/reasoning, not current status.

## Existing relevant background

- Prior experience: real-time LLM evaluation platform (Java 21 virtual threads, Spring AI/Groq, Kafka KRaft, Postgres JSONB) — informs the stack choice below.
- Prior experience: Kafka/Avro event processing (Bankstream) — informs the per-conversation-ordering approach via Kafka partitioning.
- Prior experience: custom SIP/RTP voice pipeline (Groq Whisper STT, Piper TTS, Groq Llama) — exists, but deliberately not used yet; voice is Phase 2 at earliest.

## Stack

Java 21, Spring Boot 4.1.0 (Spring Framework 7, Jackson 3 with `tools.jackson.*` packages and unchecked `JacksonException`), Kafka (KRaft mode, no Zookeeper), Postgres for the core orchestrator. Flask + SQLite for the order service (separate deployable, intentionally simple/different stack — mirrors a realistic scenario where the agent platform integrates with an external system it doesn't control).

Deliberately built on current (not EOL) framework versions — see decisions-log D14 for the real breaking-change gotchas this surfaced (Jackson package rename, checked→unchecked exception shift) and why they were worth hitting directly rather than avoided by targeting an older, more-documented version.