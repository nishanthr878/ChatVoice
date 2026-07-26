# Decisions Log — AI Agent Platform

**Status:** Draft, pre-implementation. Each entry may be revisited once the trivial skeleton is running — noted where a decision is high-confidence vs. provisional.

Format: Decision → Alternatives considered → Why → Confidence.

---

### D1 — Build two concrete flows before any generalized platform abstraction

**Alternatives considered:** design a generic, use-case-agnostic engine upfront (plugin-style tool/flow registry).
**Why:** abstraction designed before a second concrete example is usually the wrong abstraction — you can't yet know what actually varies vs. what you're guessing varies. Two flows (`check_order_status`, `process_return`) is the minimum needed to find real shared structure.
**Confidence:** high. This is a sequencing decision, not a technical one — low risk to commit to.

---

### D2 — Chat-only first; voice deferred entirely

**Alternatives considered:** build voice and chat together from the start (already have a working SIP/STT/TTS pipeline).
**Why:** voice adds a latency-sensitive audio subsystem (barge-in, turn-taking, VAD) on top of a conversation engine that hasn't been proven yet. Building both at once produces two shallow systems instead of one deep one. Layer 2's schema includes a `channel` field so this isn't a rewrite later, just a deferred integration.
**Confidence:** high.

---

### D3 — Conversation state as durable Postgres tables, not context-window-as-memory

**Alternatives considered:** rely on LLM context/conversation history as the source of truth for state.
**Why:** context is not state — it's not queryable, not auditable, and disappears if the process restarts or context gets truncated. Needed: `conversation`, `turn`, `slot`, `tool_invocation` tables as the actual source of truth; LLM context is constructed *from* this on each call (progressive disclosure), not the other way around.
**Confidence:** high.

---

### D4 — `slot` as a generic key-value table, not per-flow columns

**Alternatives considered:** flow-specific tables/columns (e.g., a `return_reason` column only relevant to `process_return`).
**Why:** both flows' data fits `(slot_name, slot_value)` JSONB without a schema change; which slots a flow needs is config (Layer 3), not schema. Minimum genericity justified by two flows.
**Confidence:** medium — if a third flow needs structured/relational slot data (not just scalar values), this may need revisiting.

---

### D5 — Kafka topic partitioned by `conversation_id` for ordering, not an actor model

**Alternatives considered:** in-memory actor-per-conversation (Akka-style or a ConcurrentHashMap of single-threaded executors).
**Why:** Kafka partition ordering gives strict per-conversation sequencing for free, reusing existing Kafka/event-processing experience (Bankstream). An actor model is more moving parts for the same guarantee at this scale.
**Confidence:** medium — correct for a learning project's load; would need re-evaluation under real concurrent-conversation load (not a near-term concern).

---

### D6 — Policy graph as data (YAML config) + node-type handlers, not hardcoded per-intent Java

**Alternatives considered:** hardcode each flow's logic directly in Java (switch statements per intent); or reach for a full workflow engine (Temporal, Camunda) immediately.
**Why:** config-driven graph makes the two flows reusable through the same `GraphExecutor` with zero code changes — the actual proof of "generalized enough." A full workflow engine is real infrastructure overhead not yet justified; upgrade only once the simple version's pain (e.g., needing retries-with-backoff, or the graph outgrowing readability as YAML) is actually felt.
**Confidence:** medium — YAML-as-config is untested; may prove awkward once loops/retries are needed (flagged in advance, not yet solved).

---

### D7 — Conditional/branching logic (e.g., refund threshold check) is pure code, never an LLM call

**Alternatives considered:** let the LLM decide whether a refund amount requires approval, via prompt instruction.
**Why:** enterprises (and this design) need deterministic guarantees — "a refund over $500 can never bypass human approval" must be structurally unreachable, not dependent on the LLM following an instruction correctly every time. This is the core reason the graph exists at all rather than a single large LLM prompt.
**Confidence:** high — non-negotiable design principle, not a preference.

---

### D8 — Idempotency enforced at the tool-call boundary via a unique key, checked before execution

**Alternatives considered:** trust the LLM/orchestrator not to call a mutating tool twice; rely on Flask service to dedupe internally without an explicit key.
**Why:** voice/chat connections drop and retries happen; a real order service must not double-process a return. `tool_invocation.idempotency_key` (unique, checked pre-execution) plus a matching check in the Flask service closes this at both ends.
**Confidence:** high.

---

### D9 — Order service is a real, separately deployed Flask + SQLite app, not an in-process mock

**Alternatives considered:** hardcoded in-process Java class simulating order responses.
**Why:** a real network boundary forces the idempotency/retry problem to be solved for real. An in-process fake would let this be skipped, defeating the point of the exercise.
**Confidence:** high. Known tradeoff accepted: SQLite's single-writer lock will cause contention under concurrent load — expected, not a bug, and out of scope to fix (not a production system).

---

### D10 — Document Layer 2 fully before Layer 3, and validate with a trivial single-node pass-through before writing the Layer 3 doc

**Alternatives considered:** write full HLD for all layers before any code.
**Why:** Layer 2 (schema + concurrency model) is stable enough to document confidently now. Layer 3's graph model is not yet proven — documenting it in detail before running even a trivial version risks documenting guesses. Build the skeleton first, then document what's actually true.
**Confidence:** high — process decision, not technical.
