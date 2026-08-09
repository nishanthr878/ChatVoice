# Architecture Doc (HLD) — AI Agent Platform

**Status:** Draft, pre-implementation. Layer 2 is detailed and considered stable; Layers 3-4 are HLD-only until the trivial skeleton proves the approach — see `decisions-log.md` D10.

## System overview

```
                     ┌─────────────────────────────┐
   User (chat) ───▶  │  Layer 1: Channel/Transport   │
                     │  (WebSocket/SSE session)      │
                     └──────────────┬───────────────┘
                                    │ turn (speaker, text, timestamp)
                                    ▼
                     ┌─────────────────────────────┐
                     │  Kafka: conversation-events   │
                     │  (partitioned by conv_id)     │
                     └──────────────┬───────────────┘
                                    ▼
                     ┌─────────────────────────────┐
                     │  Layer 2: Conversation State  │
                     │  Manager (Postgres)           │
                     │  - conversation, turn, slot,  │
                     │    tool_invocation tables     │
                     └──────────────┬───────────────┘
                                    ▼
                     ┌─────────────────────────────┐
                     │  Layer 3: Orchestration/      │
                     │  Policy Engine                │
                     │  - graph-as-YAML per flow      │
                     │  - node handlers (LLM classify,│
                     │    slot-fill, conditional,     │
                     │    tool-call, escalation)       │
                     └──────────────┬───────────────┘
                                    ▼
                     ┌─────────────────────────────┐
                     │  Layer 4: Tool/Action Layer    │
                     │  - validation + idempotency    │
                     │  - policy gate (approval check) │
                     └──────────────┬───────────────┘
                                    ▼
                     ┌─────────────────────────────┐
                     │  Order Service (Flask+SQLite)  │
                     │  external, network boundary     │
                     └─────────────────────────────┘

   (Layer 5 — Evaluation/Observability — scores turns against
    rubrics; hangs off Layer 2/3, not detailed in this phase.)
```

## Layer responsibilities (summary — full detail in prior chat / Layer 2 doc)

**Layer 1 — Channel/Transport.** Converts channel-specific input (chat WebSocket now; voice RTP/SIP later) into a unified `turn` representation as early as possible, so nothing downstream needs to know which channel it came from.

**Layer 2 — Conversation State Manager.** Durable, queryable Postgres state: `conversation` (current_node, status, flow_type), `turn` (history), `slot` (extracted values, generic key-value), `tool_invocation` (idempotent tool call log). Ordering guaranteed per conversation via Kafka partitioning on `conversation_id`. Full schema and rationale: see `layer2-conversation-state-design.md`.

**Layer 3 — Orchestration/Policy Engine.** A directed graph per flow, defined as YAML config, not hardcoded logic. Node types: `llm_classify`, `llm_slot_fill`, `conditional` (pure code, never an LLM decision — see D7), `tool_call`, `escalation`. `GraphExecutor.step(conversationId, turn)` reads `current_node` from Layer 2, dispatches to the matching handler, persists the result. This is what makes "check refund amount, require approval above $500" a structural guarantee instead of a prompt instruction.

**Layer 4 — Tool/Action Layer.** Validates LLM-proposed tool arguments against schema, checks `tool_invocation` for idempotency before executing, enforces policy gates (e.g., approval required) as a code-level guard independent of the graph, then calls the external service with an idempotency key.

**Order Service (external).** Real Flask + SQLite app, separate deployable. Exposes `POST /orders/{id}/returns` etc., honors `Idempotency-Key` header, returns the stored result on replay rather than re-executing.

**Layer 5 — Evaluation/Observability.** Deferred in detail — conceptually: score turns/tool-calls against rubrics for policy violations and hallucinated actions. Not part of the current build phase.

## Data flow for one turn (concrete example: `process_return`)

1. User sends chat message → Layer 1 wraps as `turn`, publishes to Kafka keyed by `conversation_id`.
2. Consumer reads `conversation.current_node` from Postgres.
3. `GraphExecutor` dispatches to the node handler for `current_node` (e.g., `collect_return_reason` → `LlmSlotFillHandler`).
4. Handler calls LLM with narrow context (progressive disclosure — only this node's relevant slots/instructions, not full history), extracts `return_reason`, writes a `slot` row.
5. Graph transitions to `check_return_value` (a `conditional` node — pure code comparison against threshold, no LLM call).
6. If above threshold → `human_approval_required` (escalation, conversation paused, `status = escalated`).
7. If below threshold or after approval → `execute_return` (`tool_call` node) → Layer 4 checks idempotency, calls Flask order service with idempotency key, records `tool_invocation`.
8. Result flows back → `confirm` node generates final response via LLM → returned to user through Layer 1.

## Build sequencing (current plan and status)

1. **✅ Done, verified.** Layer 2 schema + bare Kafka consumer skeleton (turn in → `turn` row written). Raw Kafka CLI test confirmed partition-key ordering (see `d5-partition-ordering-validation.md`); Spring Boot consumer verified end-to-end against real Postgres + Kafka — idempotent conversation creation, gap-free ordered `sequence_number` assignment, and independent state across two concurrently-active conversation_ids. Stack: Spring Boot 4.1.0, Java 21, Jackson 3 (see decisions-log D14).
2. **Not yet started, folded into step 1's remaining gaps.** Two things deferred, not forgotten: (a) multi-instance consumer group rebalance behavior untested — only one app instance has been run; (b) no genuine concurrent-load test — all validation sends were manual, one message at a time.
3. **Next up.** Trivial single-node Layer 3 pass-through (fixed `current_node`, canned response, per D11's hexagonal-for-domain-logic-only split, TDD against fakes) — proves the graph-executor pattern before any real branching logic.
4. Full `check_order_status` flow (read-only, no approval gate) — simplest real flow.
5. Full `process_return` flow (mutating, approval gate) — proves the harder path (idempotency, escalation, conditional branching).
6. Revisit this doc and `decisions-log.md` against what was actually learned; write the Layer 3 detailed design doc at that point, not before.

## Known technical debt (tracked, not yet fixed)

- Default `contextLoads()` test implicitly depends on live Kafka/Postgres infra being up — will hang or fail in CI or on a machine without Docker Compose running. Needs tagging as an integration test or removal, per D11's testing split.
- `sequence_number` assignment is safe only under Spring Kafka's default single-thread-per-listener consumption — see decisions-log D13. Do not increase listener concurrency without addressing this first.