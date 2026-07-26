# Layer 2 — Conversation State Manager: High-Level Design

## Purpose

Durable, queryable source of truth for a conversation's state — independent of any single LLM call or process. Layer 3 (orchestration) reads/writes this to decide what happens next; it does not hold state itself.

## Scope of this document

Layer 2 only. Two concrete flows will run on top of it: `check_order_status` (read-only) and `process_return` (mutating, requires approval above a threshold). Voice is deferred — schema includes a `channel` field to avoid a rewrite later, but no voice-specific logic is designed here.

## Data Model

```sql
CREATE TABLE conversation (
    conversation_id UUID PRIMARY KEY,
    channel VARCHAR(16) NOT NULL,          -- 'chat' (voice later)
    flow_type VARCHAR(64) NOT NULL,        -- 'check_order_status' | 'process_return'
    current_node VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,           -- active/escalated/resolved/abandoned
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE turn (
    turn_id UUID PRIMARY KEY,
    conversation_id UUID REFERENCES conversation(conversation_id),
    speaker VARCHAR(8) NOT NULL,           -- user/agent/system
    content TEXT NOT NULL,
    sequence_number INT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(conversation_id, sequence_number)
);

CREATE TABLE slot (
    conversation_id UUID REFERENCES conversation(conversation_id),
    slot_name VARCHAR(64) NOT NULL,
    slot_value JSONB NOT NULL,
    source_turn_id UUID REFERENCES turn(turn_id),
    filled_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (conversation_id, slot_name)
);

CREATE TABLE tool_invocation (
    invocation_id UUID PRIMARY KEY,
    conversation_id UUID REFERENCES conversation(conversation_id),
    idempotency_key VARCHAR(128) UNIQUE NOT NULL,  -- conversation_id:turn_id:tool_name
    tool_name VARCHAR(64) NOT NULL,
    arguments JSONB NOT NULL,
    result JSONB,
    status VARCHAR(16) NOT NULL,           -- pending/approved/executed/failed/rejected
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);
```

### Design decisions and rationale

- **`slot` is a generic key-value table, not per-flow columns.** Both `order_id` (check_order_status) and `return_reason` (process_return) fit `(slot_name, slot_value)` without a schema change. Which slots a flow needs is config (Layer 3's concern), not schema. This is deliberately the minimum genericity that two flows justify — not a guess at future flexibility.
- **`tool_invocation.idempotency_key` is unique and checked before every execution.** This is the mechanism that prevents double-processing a return if a retry happens after a dropped connection. Non-negotiable for any mutating tool call.
- **`source_turn_id` on `slot`.** Traceability: when a slot value is wrong, you need to know which turn produced it, for debugging and for audit if this were ever a real system.
- **No `flow_specific` tables.** If a third flow proves the key-value slot model insufficient, that's the trigger to revisit — not before.

## Concurrency Model

**Problem:** a second user message can arrive while a tool call from the first is still in flight. State must be processed in strict order per conversation.

**Decision:** Kafka topic `conversation-events`, partitioned by `conversation_id`. One consumer group. Because Kafka guarantees ordering within a partition, all events for a given conversation are processed strictly in sequence by whichever consumer owns that partition — no in-memory actor system needed.

**Flow per incoming turn:**
1. Turn arrives (chat message) → published to `conversation-events`, keyed by `conversation_id`.
2. Consumer for that partition reads current `conversation` row (current_node, status) from Postgres.
3. Consumer invokes Layer 3's `GraphExecutor.step()` for that node.
4. Consumer persists updated `current_node`, any new `slot` rows, and `turn` row — in a single transaction.

**Explicitly deferred:** actor-model-per-conversation, distributed locking beyond partition ordering, multi-instance conversation affinity beyond Kafka partition assignment. Add only if the single-consumer-group model proves insufficient under real load.

## Explicitly Out of Scope (for this document)

- Layer 3 graph/node execution logic (separate doc, once the trivial single-node pass-through is built and the graph-as-config model is validated)
- Layer 4 tool contract and Flask/SQLite service design (designed in chat, not yet its own doc)
- Voice channel specifics
- Multi-tenancy, auth, billing — not relevant to a learning project

## Immediate Next Step

Build this schema plus a trivial single-node Layer 3 pass-through (one node, no branching, no LLM call — just prove turn → state update → response round-trips through Kafka → Postgres → back out) end-to-end before writing the Layer 3 design doc. Validate the concurrency model works in practice before documenting more of the graph engine on top of it.
