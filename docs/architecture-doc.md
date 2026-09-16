# Architecture Doc (HLD) — ChatVoice

**Status:** Living document, current as of the check_order_status ConversationState migration. Supersedes the original pre-implementation draft, which described a single hardcoded pass-through executor.

## System overview

```
                     +-------------------------------+
   Browser widget -->|  Spring: ConversationController |
   (index.html,      |  (REST: /start, /messages,      |
    polling)         |   /turns)                        |
                     +----------------+----------------+
                                      | publishes turn
                                      v
                     +-------------------------------+
                     |  Kafka: conversation-events     |
                     |  (partitioned by conversation_id)|
                     +----------------+----------------+
                                      v
                     +-------------------------------+
                     |  ConversationEventConsumer      |
                     +----------------+----------------+
                                      v
                     +-------------------------------+
                     |  GraphExecutor                  |
                     |  - bounded multi-hop dispatch    |
                     |    loop per turn (chains several |
                     |    node transitions in one turn  |
                     |    when nothing needs user input)|
                     |  - InputBoundaryValidator runs    |
                     |    before any input-consuming     |
                     |    node: is this turn still on-   |
                     |    topic, or has intent changed?  |
                     +----------------+----------------+
                                      v
              +-----------------------------------------+
              |                                           |
              v                                           v
   +--------------------+                      +----------------------+
   | intent_classification|                     | check_order_status /  |
   | (router flow)         |                     | process_return         |
   +--------------------+                      | (NodeHandler-per-node) |
                                                +-----------+------------+
                                                            v
                                    +----------------------------------------+
                                    |  ConversationState (check_order_status  |
                                    |  only, so far) -- activeIntent,          |
                                    |  activeFocus, known entities. Lives      |
                                    |  ALONGSIDE the execution graph, not      |
                                    |  inside current_node. See D21.           |
                                    +----------------------------------------+
                                                            v
                     +-------------------------------+
                     |  Postgres: conversation, turn,   |
                     |  slot, tool_invocation           |
                     |  (original schema, still live)   |
                     |  + conversation_state,            |
                     |  conversation_entity (D21, new)   |
                     +----------------+----------------+
                                      v
                     +-------------------------------+
                     |  Order Service (Flask + SQLite)  |
                     |  external, network boundary       |
                     +-------------------------------+

  Observability (Loki/Promtail/Grafana + Tempo) watches the whole
  stack via Docker log scraping and OTLP trace export -- see the
  dedicated Observability notes.
```

## Layer responsibilities, as actually built

**REST/Kafka boundary.** `ConversationController` exposes `POST /{id}/start` (writes a fixed greeting turn directly, bypassing Kafka entirely -- deliberate, since there's no user message to route on turn one), `POST /{id}/messages` (publishes to Kafka), `GET /{id}/turns` (read-only turn history, polled by the widget).

**GraphExecutor.** The real orchestration core. Not a single fixed transition (as originally planned) -- a bounded loop (`MAX_HOPS_PER_TURN`) that re-dispatches within one Kafka message's processing as long as the current node keeps changing, stopping the moment a node asks a real question (node unchanged) or a flow completes (resets to `intent_classification`). This is what lets a single message like "check order 1001, what items are in it" complete the entire flow in one turn instead of requiring several round-trips. Before dispatching to any node a `Flow` marks as input-consuming, `InputBoundaryValidator` runs first -- a narrow LLM call asking only "does this input still belong to the current task, or has the user's intent changed" -- and on a genuine topic switch, resets routing back through `intent_classification` without losing already-collected conversation state.

**Flow / NodeHandler.** Each flow (`intent_classification`, `check_order_status`, `process_return`) is a `Map<String, NodeHandler>` -- deliberately not a config-driven YAML graph as originally planned; three concrete flows never demonstrated enough shared structure to justify that generalization (see decisions-log D6/D1). Node-level logic follows a consistent slot-filling pattern (decisions-log D19): extract everything extractable from a message in one LLM call, check what's still missing, ask only for that -- rather than one slot per node with a rigid question-per-turn shape.

**ConversationState (check_order_status only, migration to process_return pending).** The newest, most significant architectural layer (decisions-log D21). Separates *what the conversation is about* (`activeIntent`, `activeFocus` as an explicit typed entity reference, a running list of known `entities`) from *what execution step is running* (still `current_node`, unchanged in spirit). This is what allows `check_order_status` to correctly handle "what about the other one" -- a real coreference resolution against known entities -- something the original node-name-as-state design could not represent even in principle. Backed by `ConversationStateRepository`, atomic per-turn `applyUpdate` patches (mirroring a pure-merge pattern found in Voice AI Vendor's own real code), and Postgres optimistic concurrency control via an explicit `version` column.

**Original conversation-state schema (still live, unchanged).** `conversation` (flow_type, current_node, status), `turn` (full history), `slot` (generic key-value, still used for execution-scoped values like cached order lookup results), `tool_invocation` (idempotent tool-call log). See the dedicated Layer 2 design doc for full schema/rationale -- still accurate for what it covers; `conversation_state`/`conversation_entity` are a separate, additive layer on top, not a replacement.

**Order Service (external).** Real Flask + SQLite app, separate deployable, fully Dockerized. `OrderLookupHelper` provides idempotency-checked lookup + free-text-to-catalog item matching, shared by both flows.

**Observability.** Loki/Promtail/Grafana for logs (container stdout scraped via Docker service discovery, no app code changes needed), Tempo for distributed traces (Spring's `spring-boot-starter-opentelemetry`, HTTP-level spans auto-instrumented; Kafka-consumer-thread spans currently NOT propagating -- open, diagnosed issue, see context-doc). Deliberately no metrics backend yet.

## What changed from the original plan, and why

The original HLD (Layers 1-5, YAML-config-driven graph, Layer 5 as a deferred "evaluation/observability" afterthought) was written pre-implementation. Real, load-bearing deviations:

- **No config-driven graph.** Three flows never showed enough shared structure to justify it; `Map<String, NodeHandler>` per flow has been sufficient, and premature generalization was explicitly avoided (decisions-log D1, D6).
- **GraphExecutor grew a real multi-hop chaining loop**, not present in the original single-dispatch design -- added after live testing showed information given in one message was being silently discarded and re-asked for on the next turn, a genuine, demo-visible bug, not a nice-to-have.
- **A whole new conversational-state layer (ConversationState) was added on top of the original Layer 2 schema**, not substituted for it -- the original plan didn't anticipate needing to separate conversational context from execution state; this only became clear after live bugs (multi-order fabrication, inability to resolve "the other one") made the conflation obvious. See decisions-log D21 for the full reasoning trail, including a genuine mid-discussion architectural critique that was researched, partially accepted, and partially pushed back on with evidence rather than taken at face value.
- **Observability (originally "Layer 5, deferred, not detailed") became a real, substantial, working part of the system** -- a full self-hosted Loki+Tempo+Grafana stack, not a conceptual placeholder, built specifically because manual live-debugging (adding print statements, rebuilding, reproducing) was identified as a real, recurring cost worth solving properly.
- **Containerization moved from "not this phase" to fully done** -- both the Spring app and the Flask service are Dockerized and wired into `docker-compose.yml` alongside Kafka/Postgres/the observability stack, proven working end-to-end locally.

## Known technical debt (tracked, not yet fixed)

- `process_return` still uses the pre-D21 `SlotRepository`-based order-identity model -- migration to `ConversationState` is the clearly-defined next step.
- Kafka message-level idempotency gap -- duplicate `turn` rows possible on message redelivery (confirmed live).
- `OrderLookupHelper`'s idempotency cache key doesn't include the order ID itself.
- Distributed tracing doesn't propagate across the Kafka publish->consume boundary -- diagnosed (likely tied to using a manually configured listener container instead of `@KafkaListener`), not yet fixed.
- `InputBoundaryValidator` (D20) has not been re-verified for correct composition with the newer `ConversationState` model on `check_order_status`.


----

# Architecture Doc (HLD) — Voice/Telephony Addendum

**Status:** Living document, pre-implementation. Extends the existing HLD. Voice is a second inbound transport, parallel to the existing REST/Kafka one, feeding the same `GraphExecutor` — see D22-D26 in the decisions log for the reasoning behind every choice below.

## System overview (addendum)

```
   Twilio PSTN -->|  TwilioWebhookController (TwiML) |
                  +----------------+-----------------+
                                   v
                  +----------------------------------+
                  |  VoiceStreamHandler (WebSocket,    |
                  |  Twilio Media Streams protocol)    |
                  +----------------+-------------------+
                                   v
                  +----------------------------------+
                  |  VoiceSessionManager                |
                  |  - owns callSid <-> conversationId   |
                  |  - state: LISTENING/PROCESSING/      |
                  |    SPEAKING (D25)                    |
                  |  - dispatches step() off the frame-  |
                  |    reading thread (D25)              |
                  +----+------------------------+--------+
                       |                         |
                       v                         v
              DeepgramSttClient          DeepgramTtsClient
              (mulaw/8000 in)            (mulaw/8000 out, D22)
                       |
                       v speech_final(text)
              +--------------------------+
              |  GraphExecutor.step()     |  <-- D23: additive-only
              |  (channel="voice" — see   |      IF branch (a) holds.
              |   D23 open item)          |      Verify before build.
              +--------------------------+
```

The chat path is unchanged: `HTTP → Kafka → ConversationEventConsumer → GraphExecutor.step()`. Voice is a second, parallel entry point into the same `step()`, not a modification of the chat path.

## Layer responsibilities, voice-specific

**`TwilioWebhookController`.** Handles Twilio's inbound-call webhook, returns TwiML directing Twilio to open a Media Streams WebSocket. Pure transport — no domain knowledge.

**`VoiceStreamHandler`.** WebSocket endpoint speaking Twilio's Media Streams protocol (`start`/`media`/`stop` events). Delegates everything to `VoiceSessionManager`.

**`VoiceSessionManager`.** Owns exactly two identities: `callSid` (Twilio transport identity) and `conversationId` (durable domain identity, generated once at call-start, not lazily — mirrors the existing `turnId`-ownership boundary already established for `GraphExecutor`). Owns the `LISTENING`/`PROCESSING`/`SPEAKING` state machine (D25). Does not call `conversationRepository.create()` directly — `step()` already does this lazily on first call, same as chat.

**`DeepgramSttClient` / `DeepgramTtsClient`.** Same port-shaped pattern as the existing `LlmClient`/`GroqLlmClient` — the project's existing hexagonal boundary (per the HLD's Layer 3 discussion) extended to a third external system, not a new pattern.

**`GraphExecutor.step()`.** Unchanged in principle — same method chat's `ConversationEventConsumer` already calls. Whether it's unchanged *in practice* (zero code edits) depends on how `channel` is currently threaded through to `conversationRepository.create()` — see D23's open item. Do not treat this box as settled until that's checked.

## Codec contract (D22)

```
Twilio Media Streams   8kHz, mono, μ-law (mulaw)
Deepgram STT connection:  encoding=mulaw, sample_rate=8000
Deepgram TTS connection:  encoding=mulaw, sample_rate=8000
```

Raw base64-decoded Twilio bytes go straight to Deepgram STT. Raw Deepgram TTS output bytes go straight into Twilio `media` messages, re-base64'd. No PCM intermediate anywhere in this path.

## State machine (D25)

```
LISTENING
   | speech_final(text)
   v
PROCESSING  ---- step() runs on a dedicated per-session executor,
   |              NOT the WebSocket frame-reading thread
   | response text
   v
SPEAKING    ---- Deepgram TTS streaming; interim STT still live
   |              in parallel, for barge-in detection
   |
   +-- barge-in: interim transcript arrives while SPEAKING
   |     -> send Twilio `clear`, cancel TTS stream, -> LISTENING
   |
   +-- TTS stream completes naturally -> LISTENING
```

Barge-in during `PROCESSING` (no buffered audio yet to clear) is explicitly undesigned — do not assume it degrades gracefully.

## Known technical debt — voice-specific

- **`conversation.channel` exists** (`VARCHAR(16) NOT NULL`, no default) — confirmed against the real schema. What's still open: whether `GraphExecutor.step()` currently hardcodes `channel="chat"` or already threads it as a parameter. Blocks confirming D23's "zero changes to `GraphExecutor`" claim. Check `GraphExecutor.java` directly before Commit 2 of the voice build.
- **D24's per-`conversationId` sequential guarantee** for `step()` calls is asserted by design intent, not proven the way D13's Kafka-partition case was proven (live rebalance tests, concurrent-send tests).
- **D26's latency budget is unmeasured.** `step()`'s real worst-case hop count (up to 5 sequential LLM calls, per the existing decisions log) has not been checked against voice's sub-~1s tolerance. Recommended: measure via the existing widget before committing to the transport implementation.
- **`PROCESSING`-window barge-in** — undesigned, not merely unimplemented.

## Implementation order (gated)

1. Measure D26 — real worst-case `step()` latency through the existing widget.
2. Resolve D23 — check `GraphExecutor.step()`'s actual signature and its call to `create()`.
3. Transport skeleton (`TwilioWebhookController`, `VoiceStreamHandler`, `VoiceSessionManager`) — no STT/TTS yet.
4. Deepgram STT wired in, logged only — `GraphExecutor` not yet called.
5. `step()` wired in, dispatched off the frame-reading thread (D25).
6. Deepgram TTS wired in.
7. Barge-in for the `SPEAKING` state only.
8. `PROCESSING`-window barge-in, as its own deliberate step — not bundled into step 7.

