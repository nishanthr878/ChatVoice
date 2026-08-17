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

1. **✅ Done, fully verified, including previously-open gaps.** Layer 2 schema + bare Kafka consumer skeleton. Verified: partition-key ordering (raw CLI), idempotent conversation creation and gap-free sequence_number assignment across concurrent conversations, multi-instance rebalance behavior (partition reassignment on join and on instance failure), and genuine concurrent-load message arrival (5 backgrounded concurrent sends, correctly serialized). See `d5-partition-ordering-validation.md` and decisions-log D10/D13. Stack: Spring Boot 4.1.0, Java 21, Jackson 3 (decisions-log D14).
2. **✅ Done, verified end-to-end.** Trivial single-node Layer 3 pass-through — `GraphExecutor` with hexagonal ports (`ConversationRepository`, `TurnRepository`), TDD'd against fakes, Postgres adapters integration-tested via Testcontainers, and `ConversationEventConsumer` fully rewired to depend on `GraphExecutor` alone (no direct JDBC remaining in the consumer). Confirmed via real Kafka message → correct `conversation`/`turn` rows in Postgres. See decisions-log D15/D16.
3. **Next up.** Full `check_order_status` flow (read-only, no approval gate) — first flow with real node branching; `GraphExecutor.step()` is currently fully hardcoded and must be generalized from a single fixed transition into an actual graph-as-config model.
4. Full `process_return` flow (mutating, approval gate) — proves the harder path (idempotency, escalation, conditional branching).
5. Revisit this doc and `decisions-log.md` against what was actually learned; write the Layer 3 detailed design doc at that point, not before.

## Known technical debt (tracked, not yet fixed)

- Default `contextLoads()` test implicitly depends on live Kafka/Postgres infra being up — will hang or fail in CI or on a machine without Docker Compose running. Needs tagging as an integration test or removal, per D11's testing split. Still open as of D15.
- `sequence_number` assignment's real dependency is same-partition serialization, not raw consumer thread count (see decisions-log D13) — safe under current tests, but untested against topic repartitioning after conversations are already in flight.