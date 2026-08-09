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

**Status: D10's own validation step is complete.** The trivial Kafka→Postgres skeleton (no Layer 3 graph yet) is built and verified: raw Kafka CLI test confirmed same-key messages land on the same partition in strict offset order and different keys land on different partitions (see `d5-partition-ordering-validation.md`); the Spring Boot consumer correctly persists `conversation`/`turn` rows with `ON CONFLICT DO NOTHING` idempotent conversation creation and gap-free, correctly-ordered `sequence_number` assignment, verified independently across two different conversation_ids processed in the same run. Not yet tested: multiple consumer instances in the same group (rebalance behavior), and message arrival under genuine concurrent/rapid-fire load rather than manual one-at-a-time sends — both are open gaps, not assumed-safe.

---

### D11 — Hexagonal architecture for Layer 3 domain logic only, not uniformly across the system

**Alternatives considered:** apply hexagonal/ports-and-adapters architecture across the whole system uniformly; or skip it entirely and just write direct Kafka/Postgres/Flask calls inline.
**Why:** Layer 3's domain logic (`GraphExecutor`, node handlers, conditional/threshold checks, idempotency-check decisions) is pure logic that shouldn't be coupled to how Kafka delivers messages or how Postgres stores rows — defining ports (`ConversationRepository`, `ToolInvocationRepository`, `OrderServiceClient`, `LlmClient`) lets this be TDD'd fast against in-memory fakes with no infra running. Applying the same pattern to the bare Kafka consumer skeleton (build step 1-3) would be counterproductive — that step exists specifically to validate real Kafka-partition-ordering + Postgres-transaction behavior, and mocking it behind a port would prove the fake works, not that the real infra guarantee holds.
**Confidence:** high for Layer 3 domain logic; explicitly not applied to the infra-validation steps already built (Layer 1/2 skeleton).
**Testing strategy split:**
- Domain logic (Layer 3 handlers, conditional logic, idempotency decisions) → classic TDD against port interfaces + fakes.
- Adapters (Kafka consumer/producer, Postgres repos, Flask client) → integration tests against real/containerized infra (Testcontainers or Docker Compose), written after domain logic is solid.

**Known cost accepted:** boilerplate (DTOs/mappers between domain model and Postgres rows, interface definitions) beyond what a quick script would need — accepted here as deliberate practice of a pattern already used professionally (Grouper), not claimed as free or objectively required at this project's size.

---

### D12 — Log at the consumer's entry/exit boundary only, not inside private helper methods

**Alternatives considered:** no logging (relying on exceptions surfacing failures); logging inside every private method (`ensureConversationExists`, `insertTurn`) for fine-grained visibility.
**Why:** the success path was previously silent — debugging required querying Postgres directly to confirm a message was even received, let alone processed correctly. A log line at the top of `onMessage` (received, with conversation_id and raw payload) and one at the end (persisted successfully) gives enough signal to know whether a message was received and whether it completed, without flooding output with internal step-by-step noise that a boundary log already implies. Logging inside every private method was rejected as over-instrumentation for no added diagnostic value at this stage — add `debug`-level detail later only if a specific bug requires it.
**Confidence:** high for the boundary-only placement. Log level chosen as `info` (not `debug`) is a conscious tradeoff for a learning project — acceptable at current volume, explicitly not the right call for a system processing high message throughput in production.

---

### D13 — `sequence_number` assignment via `SELECT MAX + 1` then `INSERT` (two statements, not atomic) is safe only under single-threaded-per-partition consumption

**Alternatives considered:** a Postgres sequence or trigger-based auto-increment; row-level locking around the read-then-write.
**Why not fixed now:** the two-statement read-then-write pattern is only race-free because Spring Kafka defaults to one consumer thread per listener container — no `concurrency` property has been set on `@KafkaListener`, so exactly one thread processes messages for this listener at a time, regardless of partition count. This has been empirically relied upon, not just assumed: all validation testing so far occurred under this default.
**Explicit constraint going forward:** this becomes a live race condition the moment `concurrency` is increased on this listener, or the app is horizontally scaled without repartitioning the sequencing logic. A code comment has been added at the point of the SQL noting this. Must be revisited (Postgres sequence, or row-locking, or moving sequencing to a DB constraint/trigger) before any change to consumer concurrency or instance count.
**Confidence:** high — correct as-is, but fragile to a specific, easy-to-make future change. Treat as documented technical debt, not resolved.

---

### D14 — Stack pinned to current versions (Spring Boot 4.1.0 / Jackson 3, `tools.jackson.*` packages) rather than deliberately targeting an older, more commonly-documented version

**Alternatives considered:** target Spring Boot 3.5.x (more tutorials/StackOverflow coverage available) for a smoother learning experience.
**Why:** 3.5.x reached OSS end-of-life June 30, 2026 — building new learning infrastructure on an EOL line was rejected outright regardless of tutorial availability. Building on the current stack surfaced real, worth-knowing breaking changes rather than hiding them: Jackson 3's package rename (`com.fasterxml.jackson.*` → `tools.jackson.*`) and its shift from checked (`JsonProcessingException`/`IOException`) to unchecked (`JacksonException`/`RuntimeException`) exceptions caused two real debugging sessions during setup — both are now understood, not just patched around.
**Confidence:** high — accepted the short-term friction of less tutorial coverage as a worthwhile tradeoff for staying current and for the genuine learning value of hitting real breaking changes firsthand.