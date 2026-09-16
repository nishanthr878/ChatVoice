# Context Doc — ChatVoice (AI Agent Platform, Learning Project)

**Status:** Living document, current as of the check_order_status ConversationState migration. Supersedes the original pre-implementation draft.

## What this is

A learning project to understand the architecture underneath platforms like Voice AI Vendor and Voice AI Vendor (enterprise conversational AI agent platforms) — built from scratch, not a tutorial follow-along. Also a real, demoable portfolio piece for a backend/distributed-systems job search: three working conversational flows, a full observability stack, and a from-scratch dialogue-state architecture, all containerized and deployable.

## Why this shape

Early framing (see decisions-log D-series) was that Voice AI Vendor/Voice AI Vendor's real IP is a policy-bounded orchestration engine -- the LLM handles narrow reasoning tasks inside a structurally-enforced graph, backed by durable conversation state and validated, idempotent tool execution. A direct code-level comparison against Voice AI Vendor's actual published agent code later confirmed and refined this: Voice AI Vendor's real architecture is *prerequisite-gated tool selection* (the model still chooses which eligible tool to call, constrained by declared prerequisites), not the stricter, fully-coded state machine this project builds. This project's version is a deliberately more deterministic sibling design, not an attempted clone -- see decisions-log D19/D20 for the full reasoning and the production-guidance research (Microsoft's agentic-workflow guidance, a 2026 comparative study) that backs the tradeoff.

**The single deepest lesson of the project, arrived at the hard way (decisions-log D21):** an early design mistake was letting the execution state machine's `current_node` implicitly double as *conversational* state (what the conversation is about, which entity is in focus) as well as *execution* state (which step is running). Those are genuinely different concerns, and conflating them was the root cause of most of the harder live bugs hit later in the project (multi-order confusion, inability to resolve "the other one," fabricated restrictions). The fix -- a real `ConversationState` domain model (tracks `activeIntent`, `activeFocus`, and known `entities` per conversation) sitting alongside, not instead of, the execution graph -- is the project's current architectural centerpiece.

## Current scope

- **Channel:** chat only, via a real browser widget (`index.html`, served from the Spring app) talking over a REST API backed by Kafka. Voice is deferred entirely -- a separate SIP/RTP pipeline exists from prior work but is intentionally not integrated here.
- **Flows:** three. `intent_classification` (the router -- classifies a fresh conversation's first message and dispatches to the correct flow, or escalates if it recognizes neither), `check_order_status` (read-only, fully migrated to the ConversationState model), and `process_return` (mutating, requires deterministic code-level approval above a $10 threshold -- still on the older per-flow slot-based order-identity model; migration to ConversationState is the next planned step, deliberately sequenced after check_order_status proved the design).
- **Order service:** a real, separately deployed Flask + SQLite application -- not an in-process mock -- specifically so idempotency and retry handling have to be solved for real, not skipped. Containerized alongside everything else.
- **Observability:** a real, self-hosted LGTM-style stack (Loki + Promtail + Grafana for logs, Tempo for distributed tracing) running via Docker Compose alongside the app, Kafka, and Postgres. Metrics (Prometheus/Mimir) deliberately not built -- logs and traces cover the actual debugging need this project has had.
- **Deployment:** fully containerized (multi-stage Spring Boot Docker build, Flask Docker build, both wired into `docker-compose.yml` with the existing Kafka/Postgres/observability services), proven working end-to-end locally; Hetzner deployment is the next real step once the remaining live bugs below are closed.

## Explicit non-goals right now

- No multi-tenancy, auth, billing, or anything enterprise-SaaS-shaped.
- No generalized "tool plugin marketplace" or dynamic flow registry.
- No voice.
- Proactive VA-initiated greeting (the widget shows a real, fixed greeting on load via a dedicated `/start` endpoint that writes directly to conversation history, bypassing Kafka -- this was built and shipped; see decisions-log for the design reasoning).
- Metrics/Prometheus -- logs + traces cover the current need.
- SSE for the widget (currently polling-based, deliberately, to prove the simpler mechanism first; SSE is a planned but not-yet-built upgrade).

## Known live gaps, honestly tracked (see decisions-log for full detail)

- `process_return` has not yet been migrated to the `ConversationState` model -- it still tracks order identity via `SlotRepository`, meaning it doesn't yet benefit from the multi-order/coreference handling `check_order_status` now has.
- Mid-flow topic switching ("digression handling") exists via `InputBoundaryValidator` (decisions-log D20) but was built and tested before the `ConversationState` redesign -- worth re-verifying it composes correctly with the new model.
- Kafka message-level idempotency: `ToolInvocationRepository` protects individual tool calls, but raw Kafka message redelivery (confirmed live, more than once) can still create duplicate `turn` rows. Not yet fixed.
- `OrderLookupHelper`'s cache key doesn't include the order ID itself -- a genuine latent correctness gap surfaced while debugging an unrelated issue, not yet tightened.
- Distributed tracing doesn't currently propagate across the Kafka publish->consume boundary -- HTTP-level spans are captured, but the actual business logic (running on the Kafka consumer thread) isn't, likely because `ConversationEventConsumer` uses a manually configured listener container rather than `@KafkaListener`, which is what Spring Boot 4's Kafka auto-instrumentation targets. Diagnosed, not yet fixed.

## Existing relevant background

- Prior experience: real-time LLM evaluation platform (Java 21 virtual threads, Spring AI/Groq, Kafka KRaft, Postgres JSONB, 11 scorers, deployed on Hetzner) -- informs both the stack choice below and a planned future integration (routing ChatVoice's own LLM calls through that evaluator for real-time hallucination/faithfulness scoring).
- Prior experience: Kafka/Avro event processing -- informs the per-conversation-ordering approach via Kafka partitioning.
- Prior experience: custom SIP/RTP voice pipeline (Groq Whisper STT, Piper TTS, Groq Llama) -- exists, deliberately not integrated yet.

## Stack

Java 21, Spring Boot 4.1.0 (Spring Framework 7, Jackson 3 with `tools.jackson.*` packages), Spring AI (Groq via the OpenAI-compatible endpoint), Kafka (KRaft mode), Postgres, Flask + SQLite (order service), Docker Compose for full local/deployment orchestration, Loki/Promtail/Grafana/Tempo for observability.

Deliberately built on current (not EOL) framework versions throughout. This has been a genuinely significant, recurring cost across the project -- well over half a dozen distinct Spring Boot 4 breaking-change discoveries (Jackson package rename and unchecked exceptions, the `-webmvc` starter rename, `RestClient.Builder`'s module split, `TestRestTemplate`'s repackaging plus a new required opt-in annotation, Testcontainers 2.0's artifact renames, and -- most extensively -- five distinct issues chasing down correct OpenTelemetry/OTLP configuration for Spring Boot 4) -- each one found and fixed by direct, evidence-based debugging rather than avoided by targeting an older, more-documented version. See decisions-log for the full, precise trail of each one; the debugging methodology that emerged (ask the running app what it actually loaded via Actuator, confirm dependencies resolved before suspecting config, check the receiver's logs before the sender's, bypass UI layers and query backends directly) is arguably as valuable a takeaway as any single fix.