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

---

### D18 — Proactive VA-initiated greeting deferred (VA speaks first, before any user message)

**What was requested:** either the user or the VA should be able to start the conversation — i.e., on conversation creation, the widget could show a greeting from the VA with zero user input yet, not just react to what the user types first.
**Why deferred rather than built now:** the entire system is currently reactive by design — `GraphExecutor.step()` only ever runs in response to an incoming Kafka message; there is no mechanism for the system to emit a turn unprompted. Building this requires new surface area: most likely a new endpoint (e.g. `POST /api/conversations/{id}/start`) that writes an initial `agent` turn directly to Postgres, bypassing Kafka/GraphExecutor entirely, since there is no user message to trigger the normal path. This is a real, separate feature, not a small addition to the classifier (compare to D-adjacent "GREETING as a fourth classification outcome," which was built immediately since it fits the existing reactive request/response shape with no new mechanism).
**Status:** explicitly deferred, to be picked up as its own scoped piece of work, likely alongside or after the widget UI, since the UI's first-load behavior is what actually determines whether this is needed (does the widget show a greeting immediately on page load with no user input, or wait for the user to type first).

**Status: D10's own validation step is complete, including the previously-open gaps.** The trivial Kafka→Postgres skeleton (no Layer 3 graph yet) is built and verified: raw Kafka CLI test confirmed same-key messages land on the same partition in strict offset order and different keys land on different partitions (see `d5-partition-ordering-validation.md`); the Spring Boot consumer correctly persists `conversation`/`turn` rows with `ON CONFLICT DO NOTHING` idempotent conversation creation and gap-free, correctly-ordered `sequence_number` assignment, verified independently across multiple concurrently-active conversation_ids. **Multi-instance rebalance tested and confirmed:** running two app instances in the same consumer group, `kafka-consumer-groups.sh --describe` showed partitions correctly redistributed (2-and-1 split) when the second instance joined, and full reassignment to the survivor when the first instance was killed. **Concurrent-load tested and confirmed:** 5 messages fired via backgrounded shell processes for the same conversation_id landed with `sequence_number` 1-5, no gaps or duplicates, strictly increasing timestamps — the two-statement sequence-assignment pattern held under genuine concurrency, not just manual one-at-a-time sends (see decisions-log D13, now updated from "reasoned-safe" to "observed-safe").

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

### D15 — Layer 3's trivial pass-through built with real hexagonal ports and TDD, then rewired end-to-end

**What was built:** `ConversationRepository` and `TurnRepository` as domain-layer ports; `GraphExecutor` (now `@Component`) as the sole domain-logic consumer of both, holding references to them (not implementing them — has-a, not is-a); `PostgresConversationRepository` and `PostgresTurnRepository` as real adapters; `InMemoryConversationRepository`/`InMemoryTurnRepository` as test fakes. `ConversationEventConsumer` rewired to depend on `GraphExecutor` alone — all direct `JdbcTemplate` calls removed from the consumer, which now only parses the Kafka payload and delegates.
**Testing:** `GraphExecutor` unit-tested against fakes (2 tests: trivial flow, new-conversation creation-then-transition); `PostgresConversationRepository` integration-tested against a real Postgres via Testcontainers (4 tests: schema present, create+exists, update+get, get-after-create). All adapter tests use a per-test/per-`@BeforeEach` freshly-generated UUID rather than a shared hardcoded ID — a real test-isolation bug was found and fixed during this work (multiple tests sharing one hardcoded conversation_id, with no guaranteed JUnit execution order, meant later tests could silently depend on state left by earlier ones, or collide on duplicate-key inserts).
**Verified end-to-end:** a real Kafka message run through the full chain (Kafka → `ConversationEventConsumer` → `GraphExecutor` → both Postgres adapters) produced a `conversation` row with `current_node = 'confirm'` and a correctly-sequenced `turn` row — confirmed by direct query, not by log output alone.
**Turn-persistence placement decision:** turn-insertion logic (previously living directly in the Kafka consumer) was moved into the domain layer via `TurnRepository`, called by `GraphExecutor.step()` alongside the conversation create/transition logic — reasoned as belonging to the same "conversation state" responsibility `GraphExecutor` already owns, rather than staying a separate adapter-only concern.
**Known limitation, explicit:** `GraphExecutor.step()` is still fully hardcoded — one fixed transition to `"confirm"`, `input` parameter unused, no real branching. This is the trivial pass-through only; real graph logic starts with the `check_order_status` flow, not yet built.

---

### D16 — Further current-stack breaking changes discovered while adding Testcontainers (building on D14)

**What was hit, on top of D14's Jackson 3/Spring Boot 4 findings:**
- Testcontainers itself is now at a major version 2.0, which renamed module artifacts (e.g. `org.testcontainers:junit-jupiter` → `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`) — Spring Boot 4.1's BOM manages only the new names, so declaring the old artifact IDs resolved with no version at all.
- Testcontainers 2.0 also restructured its Java API: container classes moved out of the shared `org.testcontainers.containers` package into per-technology packages (e.g. `PostgreSQLContainer` is now `org.testcontainers.postgresql.PostgreSQLContainer`), and most container classes dropped their generic self-type parameter (`PostgreSQLContainer<?>` → plain `PostgreSQLContainer`, no diamond operator on construction).
  **Why this is being logged as its own decision rather than just a bug fix:** this is the third and fourth instance this session of a real, current major-version ecosystem change (after Jackson 3's package rename and Spring Boot 4's starter renaming in D14) — confirms D14's accepted tradeoff (building on current, non-EOL versions costs real debugging time against breaking changes that pre-date most tutorials/training data) as an ongoing pattern, not a one-off. The generalizable lesson, not just the four specific fixes: when a dependency error looks like "should obviously work but doesn't" (missing version, unresolvable symbol, unexpected API shape), check for a recent major-version rename in that library before assuming a typo or logic error.
  **Confidence:** high that this pattern will recur again on this stack; each individual fix confirmed working via successful build/test runs, not just reasoned from documentation.

---

### D17 — Awaitility's `untilAsserted` only auto-retries on `AssertionError` by default, not on arbitrary exceptions

**What happened:** `ConversationEventConsumerTest` (the first genuinely asynchronous integration test built this session — publish to Kafka, then poll Postgres for a result produced by a background consumer thread) failed instantly on every attempt, regardless of `await().atMost(...)` timeout length (tried 10s, 30s, 60s — no difference). Root cause: the assertion block's first poll ran before the consumer had processed the message, so the `SELECT ... queryForObject` legitimately found zero rows and threw `EmptyResultDataAccessException` — a `RuntimeException`, not an `AssertionError`. Awaitility's `untilAsserted` only catches and retries on `AssertionError` by default; any other exception type propagates immediately and fails the test, bypassing the timeout/polling mechanism entirely. This produced a misleading symptom (looks like a timing/rebalance/context-caching problem, since it "fails after the consumer clearly worked" per the logs) that cost significant debugging time chasing wrong theories before the actual timestamps (failure within ~1s of the first poll, not near the timeout boundary) were read closely enough to reveal the real cause.
**Fix:** add `.ignoreExceptions()` to the `await()` chain, which makes Awaitility retry on any exception thrown inside the assertion block, not just failed assertions.
**Generalizable lesson, worth more than the specific fix:** a "wait and retry" utility's retry scope is not necessarily "retry on anything that goes wrong" — it may only cover the specific failure type it was designed around (here, assertion failures specifically). When a test against asynchronous infrastructure fails suspiciously fast relative to its configured timeout, check what exception type was actually thrown and whether the retry mechanism's documented scope actually covers it, before investigating timing, infrastructure readiness, or test isolation as the cause.
**Confidence:** high — confirmed by a clean full-suite run (15/15 passing) after the fix, with the same underlying test logic otherwise unchanged.

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
**Confidence:** high, and empirically confirmed — not just reasoned. Tested under: (a) two live app instances in the same consumer group with a real rebalance (partitions correctly redistributed and reassigned on instance failure, verified via `kafka-consumer-groups.sh --describe`), and (b) 5 messages fired concurrently via backgrounded processes for one conversation_id, landing with sequence_number 1-5, no gaps or duplicates. The actual guarantee this depends on is narrower than originally stated: it is not "single global consumer thread" but "Kafka never assigns the same partition to two consumers in the same group simultaneously" — meaning it holds even with multiple app instances or higher `concurrency`, as long as messages for one conversation_id continue to land on one partition. The real risk this pattern remains exposed to is **repartitioning an existing topic** (adding partitions after conversations are in flight), which would change the key→partition hash and could split one conversation's messages across partitions — untested, and a more relevant risk than consumer thread count.

---

### D14 — Stack pinned to current versions (Spring Boot 4.1.0 / Jackson 3, `tools.jackson.*` packages) rather than deliberately targeting an older, more commonly-documented version

**Alternatives considered:** target Spring Boot 3.5.x (more tutorials/StackOverflow coverage available) for a smoother learning experience.
**Why:** 3.5.x reached OSS end-of-life June 30, 2026 — building new learning infrastructure on an EOL line was rejected outright regardless of tutorial availability. Building on the current stack surfaced real, worth-knowing breaking changes rather than hiding them: Jackson 3's package rename (`com.fasterxml.jackson.*` → `tools.jackson.*`) and its shift from checked (`JsonProcessingException`/`IOException`) to unchecked (`JacksonException`/`RuntimeException`) exceptions caused two real debugging sessions during setup — both are now understood, not just patched around.
**Confidence:** high — accepted the short-term friction of less tutorial coverage as a worthwhile tradeoff for staying current and for the genuine learning value of hitting real breaking changes firsthand.