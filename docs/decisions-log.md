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

---

### D19 — Reframed node design around standard "slot filling" pattern (multi-slot extraction per turn), after researching how production conversational AI systems actually do this

**What prompted it:** live testing surfaced repeated unnatural interactions — the VA re-asking for information (order number, item) the user had already supplied in an earlier or the same message, because each "collect" node only ever checked its own single target slot, never the flow's full slot set.
**Research finding:** this is a long-established, named pattern in conversational AI — "slot filling" — not a novel problem. Confirmed via industry examples (e.g. LivePerson's Conversation Builder: if a user's initial message already contains a value for a slot, that slot is filled automatically and its corresponding question is skipped) and via LangGraph's node design (nodes receive/can read the *entire* shared state, not just their own narrow inputs, so any node can check any previously-filled field without needing to call another node's method directly).
**What was initially proposed vs. what was actually adopted:** the first idea considered was direct node-to-node method chaining (one handler calling into the next handler's method within the same turn) — rejected as introducing real coupling between a flow's internal node names and whichever node triggers the chain. The adopted design instead has each "collect"-type node extract *all* of its flow's relevant slots from the current message (not just its own one target), save whatever it finds, then check which required slots are still empty to decide whether to ask a follow-up question or proceed straight to the flow's action step. This requires no cross-node method calls — a node only ever reads its own flow's slots via SlotRepository, which the architecture already supported; the gap was purely that "collect" nodes never checked more than their one named slot.
**Practical consequence for check_order_status:** collect_order_id and collect_item are being merged into a single multi-slot-extraction node (working name handleCollectDetails), removing the separate match_item confirmation step as redundant, once both order_id and item are known in one pass, transition straight to lookup_order.
**Also deferred, separately:** two related but distinct improvements were identified and intentionally sequenced after this reframe — (1) a bounded retry mechanism for graph dead-ends (e.g. order-not-found should loop back to collect_order_id a bounded number of times before escalating, rather than escalating immediately with no recovery path), and (2) conversation history + a persistent system/persona prompt prepended to every LLM call (via a new TurnRepository.getRecentTurns method and a shared PromptBuilder helper in domain.shared), both still pending implementation as of this decision being logged.
**Update — confirmation step designed but PARKED, not implemented, pending further discussion:** discussed the real risk that LLM extraction (especially item matching) can hallucinate, and concluded the mitigation isn't "achieve zero hallucination" but "bound the damage": deterministic guardrails (D7's threshold check) and ground-truth validation (order lookup and item matching both check against real Flask data, so a hallucinated value that doesn't match anything real correctly fails rather than being silently trusted) were already structurally in place. A confirm_item node (yes/no classification, bounded retry, loops back to collect_details on repeated "no") was designed for check_order_status specifically to guard the remaining gap — a hallucination that happens to accidentally match something real but wrong. A real open question was raised but not resolved: should this same protection exist in process_return too, since it independently does its own item matching with the identical hallucination risk, and if so should the underlying yes/no-classification logic live in OrderLookupHelper (domain.shared) as a reusable method rather than being duplicated per flow? Not convinced enough to build yet — parked. check_order_status's actual implementation, for now, goes straight from lookup_order to respond_with_item_details with no confirmation step, same as the original (pre-confirmation-discussion) design. Revisit this after slot-filling (collect_details) is implemented and live-tested.
**Update — check_order_status's slot-filling merge implemented, tested, verified:** handleCollectOrderId and handleCollectItem replaced with a single handleCollectDetails, extracting order_id and item mention together via one LLM call in a structured two-line format (ORDER_ID: .../ITEM: ...), checking both slots and asking only for whichever is still missing. Two new tests written and passing (order+item in one message; order-only then item in a separate turn, using two real sequential handlerFor calls to simulate genuine separate user turns). The two old CheckOrderStatusFlowTest end-to-end tests (fullCheckOrderStatusFlowEndToEnd, flowWorksAcrossDifferentTurnIds) initially failed after this change — root-caused correctly: they were stale, still queuing bare single-value LLM responses ("1001") that predated the new ORDER_ID:/ITEM: structured format, not a bug in the new logic. Deleted rather than patched, since the two new tests already cover the same scenarios correctly. Full suite 27/27 passing.
**Update — mid-flow digression handling confirmed as a real gap, deferred:** verified that switching topics mid-flow (e.g. asking to start a return while inside check_order_status) does not work — GraphExecutor only re-classifies when flow_type is "intent_classification", and no node currently detects an off-topic message and bails to re-classification. Explicitly not building this now, per prior sequencing decision; process_return still needs the same collect_details slot-filling merge check_order_status just received, and that remains the next actual step before any further scope is added.
**Update — full multi-hop chaining implemented in GraphExecutor, real bug found and fixed (turn-based architecture was silently discarding first-message information):** GraphExecutor was rewritten from single/double dispatch into a bounded loop (MAX_HOPS_PER_TURN=5) that keeps re-dispatching within one step() call as long as current_node keeps changing, stopping when a handler asks a real question (node unchanged) or a flow completes (resets to intent_classification). This fixed a real, user-visible bug: previously, information already present in a user's first message (e.g. "check status of order 1001, running shoes") was discarded when intent classification routed to a flow, forcing the user to repeat themselves turn after turn even though slot-filling extraction was working correctly underneath — this was the root cause of the very first live-test complaint from earlier in the day. Root-caused correctly after two false starts where GraphExecutor.java was told to be updated but the change wasn't actually saved to the real file (confirmed twice by pasting the file back and finding the old version still in place) — the actual lesson: always paste back the real current file when a fix "doesn't seem to work," don't assume a described change was applied.
**Update — separately, a real hallucination bug found and fixed: phraseNaturally (the natural-response-wording helper) was fabricating specific facts (a fake order number, "#12345") it was never given, because its prompts only ever contained a fixed instruction with no real conversation data.** Confirmed via direct Postgres slot inspection that the underlying state was always correct — the fabrication was isolated to generated response text, not data. Fixed by constraining every phraseNaturally instruction to explicitly forbid stating specific numbers/facts it wasn't given.
**Update — major architectural redesign of item handling, informed by production research (this session searched and grounded design decisions against real industry sources for the first time):** the exact-match bug in item lookup (extracted "running shoes" not matching catalog "Running Shoes") and the inability to handle open-ended questions ("what items are in there") were both root-caused to the same design flaw — gating the final response behind a rigid matched_item_description slot. Fixed by removing item extraction from handleCollectDetails entirely (now only extracts order_id, single LLM call) and rewriting handleRespondWithDetails to hand the full real order data plus raw user input to one LLM call that reasons and answers directly — read-only, ground-truth-constrained, no rigid slot-matching required. This is a real production pattern, not a workaround: research confirmed rigid per-phrasing branch systems are a known anti-pattern, and D7's "LLM for language, code for deterministic decisions" principle was independently confirmed as current best practice before this research was done. The read-only vs. mutating-action boundary (this change is safe because nothing is being decided/mutated, only described) was explicitly reaffirmed as the line that must never move.
**Update — Windows console encoding artifact (ÔÇ») investigated and correctly deprioritized:** traced through several hypotheses (chcp 65001, -Dfile.encoding=UTF-8, -Dlogging.charset.console=UTF-8) with mixed/no success, then confirmed via direct Postgres query that the actual stored data and therefore the real API response are completely clean UTF-8 — the corruption is confined to how the Windows terminal renders Kafka consumer log lines, never reaches a browser-based widget or any real client. Correctly deprioritized as cosmetic-only, not pursued further.
**Update — comprehensive scenario test coverage added:** CheckOrderStatusFlowScenarioTest (11 tests covering all three redesigned methods' success/failure/edge branches, including a defensive test proving a stale order_id slot can't be overwritten by a later hallucinated extraction) and a rebuilt GraphExecutorReDispatchTest (5 tests covering full-chain single-message completion, waiting when a slot is missing, slots arriving across separate turns, greeting not triggering re-dispatch, and order-not-found correctly halting the chain at escalation). Two now-stale test files (old CheckOrderStatusFlowTest methods testing the pre-redesign two-slot extraction) identified and deleted rather than patched. Full suite 34/34 passing — the entire redesigned architecture (multi-hop chaining, simplified extraction, open-ended reasoning, hallucination-guarded phrasing) is now under real, current, passing test coverage, not just live-verified once and left untested.
**Update — natural-language phrasing added to both flows' collect_details, applying the "extraction flexible, transitions rigid, phrasing flexible" principle consistently:** hardcoded response strings for "ask for missing slot X" branches replaced with a small phraseNaturally(instruction) helper — one extra LLM call per branch that generates varied, friendly phrasing for a fixed, code-decided target (which slot to ask about is still deterministic; only the wording is now LLM-generated). Explicit tradeoff accepted: this roughly doubles LLM calls/latency/cost per turn in the worst case, judged acceptable for a demo, not necessarily for production traffic. Existing tests updated: exact-string assertions against the old hardcoded responses were loosened to content-based or non-blank checks where wording is now genuinely variable, while assertions on real underlying data (slot values, final response content like price/item name) were kept as-is. Full suite 27/27 confirmed passing after the change. Still outstanding, unchanged: live-testing all of this (slot-filling + natural phrasing) against the real Groq model rather than only the queued fake — next actual step.

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

---

### D20 — Input-boundary validation for mid-flow digressions: hybrid deterministic-core / adaptive-boundary architecture

**Problem:** `GraphExecutor`'s coded state machine has no mechanism for a user switching topics mid-flow (e.g. asking to check order status while in the middle of collecting return details) — every input-consuming node blindly processes whatever it receives as if it belongs to the current task, producing wrong/confused behavior when it doesn't. Confirmed via live testing before this decision, not hypothetical.

**Considered and rejected:** replacing the coded graph with an LLM-driven agent loop (Voice AI Vendor-style prerequisite-gated tool selection, where the model chooses the next action each turn). Rejected after researching current production guidance, not just internal reasoning — multiple independent current sources converge on the same distinction: deterministic orchestration is the better fit for workflows with known stages, explicit invariants, and verifiable intermediate states (this project's order-status/return flows are a clean example of this category), while LLM-controlled loops earn their cost only when the actual sequence of steps is genuinely unknown in advance. An empirical 2026 study comparing both approaches on a structured task found deterministic execution held similar functional accuracy while improving worst-case robustness, reducing variability, and cutting token cost substantially. The correct framing, refined through discussion: the deciding factor is the predictability of the underlying business process, not a universal "graphs beat agents" claim — and this project's two flows sit firmly in the predictable category. A code-level comparison against Voice AI Vendor's actual reviewed implementation (`main.tsx`, `store.ts`, tool definitions) also clarified that Voice AI Vendor's own architecture is not "LLM freely decides everything" either — it declares prerequisites and typed tool params as structural constraints, leaving only *sequencing/selection* to the model, a materially different (and more constrained) design than an unconstrained agent loop.

**Design adopted — narrow adaptive layer at the input boundary, not a rewrite:** every node that reads raw user `input` (not every node — nodes with no free-text input to validate, like `lookup_order`/`check_threshold`/`auto_process`, are exempt) is checked by a new `InputBoundaryValidator` before its own handler runs. The validator answers exactly one narrow question — "does this input still make sense given the current step, or has the user's intent changed" — returning a CONTINUE/SWITCH decision plus, on SWITCH, which new intent it looks like. On SWITCH, `GraphExecutor` resets `flow_type`/`current_node` to `intent_classification`/`classify` and lets the normal classification flow re-route, rather than the validator directly setting the new `flow_type` itself — deliberately keeping the adaptive layer from acquiring business-logic authority it shouldn't have (LLM decides "what changed"; code alone decides "what that means for state").

**Key implementation decisions, each deliberately narrow in scope:**
- `Flow` interface gains `nodeConsumesInput(nodeName)` so `GraphExecutor` has one central enforcement point (inside its dispatch loop) rather than each node independently remembering to self-check — avoids the failure mode where a future node is added and someone forgets to wire in the digression check.
- Slot-scoping stays conversation-scoped (not flow-scoped) for v1, on an explicit documented invariant: a conversation maintains a single shared order context; collected slots survive an intent switch and are NOT cleared on SWITCH; an existing non-empty slot is never silently overwritten by a later extraction (this matches `handleCollectDetails`'s existing `existingOrderId.isEmpty()` guard exactly — the invariant was deliberately written to match the code's real current behavior, not the other way around, after initially considering and rejecting an "overwrite on new value" version as unnecessarily aggressive for an order workflow). Explicitly rejected: moving to `conversation_id`+`flow_type`-scoped slots to support fully independent suspended flows — assessed as solving a collision problem the current two business flows don't actually create, and as propagating into `SlotRepository`, every node handler, prompts, and tests for no present benefit. Revisit only if a real requirement needs two simultaneously meaningful order contexts in one conversation.
- The validator's own suggested `newIntent` is deliberately NOT wired directly into `flow_type` — SWITCH always routes back through the real `IntentClassificationFlow.handleClassify`, even though this means re-spending one LLM call re-deriving intent the validator arguably already determined. Chosen over the cheaper alternative (skip re-classification, act on the validator's intent directly) because that would duplicate `handleClassify`'s own transition/greeting-handling logic in a second place. Flagged as a known, accepted inefficiency for v1, not an oversight.
- Explicit invariant, since it constrains all future input-consuming nodes: input validation happens strictly before node execution. A SWITCH means the interrupted node's own turn-specific side effects (transitions, slot writes, natural-language responses) never run that turn. Verified safe for both of the current flows' input-consuming nodes; any future input-consuming node with an important side effect must be checked against this contract before being added.

**Confidence:** high on the overall shape (grounded in researched current production guidance, not just internal reasoning); the v1 invariants (conversation-scoped slots, no-overwrite, re-classify-on-switch) are explicitly named as v1-scoped simplifications to revisit if real requirements outgrow them, not permanent architectural commitments.