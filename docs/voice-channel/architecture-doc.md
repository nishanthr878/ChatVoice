# Architecture Doc — Voice Channel (HLD)

**Status:** Draft, pre-implementation. Written before any voice code exists, grounded directly in `target-main`'s real Voice AI Vendor Agent SDK voice code (`main.tsx`, `transfer.tsx`, `dtmf.ts`, `store.ts`, `genesys-utils/*`, `voice-synthesis.ts`, `dtmf.tests.ts`) rather than assumption. Supersedes the "voice later" placeholder in `../architecture-doc.md` and the deferral reasoning in `../context-doc.md`/`../decisions-log.md` D2 — see `decisions-log.md` in this folder for what each of those docs got right vs. wrong once real voice code was read.

## The headline finding

`../architecture-doc.md`'s Layer 1 is described as: *"Converts channel-specific input into a unified `turn` representation as early as possible, so nothing downstream needs to know which channel it came from."* That assumption does not survive contact with how Voice AI Vendor's own voice agent is actually built. Voice is not chat-with-a-different-input-adapter. Concretely, in `main.tsx`'s `onClientEvent`:

- Voice has event types with **no chat equivalent at all** — `start` (call connects, before any user input), `inactivity` (silence ticks), `hang-up` (caller disconnected) — alongside `message`. Only `message` maps onto this project's `turn` concept.
- Voice has an **input modality chat doesn't** — DTMF keypad digits (`dtmf.ts`) — which must be gated by conversation state (`acceptingOrderNumberDtmf`) and disambiguated from ordinary text *before* it reaches slot-filling or LLM classification.
- Voice has **output constraints chat doesn't** — TTS rewriting for spoken digits (`voice-synthesis.ts`'s `spellGrouped`/`spellDigits`), per-locale voice personas, uninterruptible/verbatim segments — meaning "the node handler returns a String, ship it to the user" (this project's current model, `../Voice AI Vendor-implementation-comparison.md` §2) cannot survive unmodified.
- Voice has **domain logic reaching back down into transport** — `OrderNumberLatencyMonitor` in `main.tsx` bumps `voice.updateMinResponseLatencyMs(5000)` specifically while collecting a spoken order number. Layer 3 (which node is active) has to influence Layer 1 (turn-taking timing), which is the reverse of "nothing downstream needs to know the channel."
- Voice has an **escalation contract chat doesn't need** — a live human agent picks up the call with no transcript in front of them unless a structured payload (`transfer.tsx`'s `executeTransfer`, `genesys-utils/transfer-utils.ts`) rides along via Genesys UUI headers and participant data. This project's current escalation model (`status = 'escalated'`, conversation paused) is sufficient for a human reading Postgres; it is not sufficient for a warm SIP handoff.

None of this means the current Layer 2/3 design (graph-as-config, slot table, `GraphExecutor`) is wrong — it means voice is **additive new surface area**, not a drop-in Layer 1 swap. The decisions below are organized around exactly the seams Voice AI Vendor's real code draws.

## System overview (voice additions layered on the existing diagram)

```
                     ┌───────────────────────────────────────────┐
  Caller (SIP/RTP)──▶│  Layer 1v: Voice Transport                  │
                     │  - SIP/RTP session, STT                     │
                     │  - Event taxonomy: start / message /        │
                     │    inactivity / hang-up  (NOT unified        │
                     │    into "turn" — see D1)                     │
                     │  - DTMF extraction (dtmf.ts pattern)         │
                     │  - Turn-taking control surface:              │
                     │    interrupt classification, barge-in,       │
                     │    dynamic min-response-latency (D8)         │
                     └──────────────┬───────────────┬──────────────┘
                                    │ message event   │ non-message events
                                    │ (→ turn, as      │ (start/inactivity/
                                    │  today)          │  hang-up — new path)
                                    ▼                  ▼
                     ┌─────────────────────────────────────────────┐
                     │  Layer 2: Conversation State Manager          │
                     │  (unchanged schema + additive channel_state — │
                     │   see D3/D7)                                  │
                     └──────────────┬─────────────────────────────┘
                                    ▼
                     ┌─────────────────────────────────────────────┐
                     │  Layer 3: Orchestration/Policy Engine          │
                     │  - node capability declarations extended:      │
                     │    "does this node accept DTMF" (D2)           │
                     │  - proactive/system-initiated turn support     │
                     │    for voice `start` (D6, resolves ../          │
                     │    decisions-log.md D18)                        │
                     └──────────────┬─────────────────────────────┘
                                    ▼
                     ┌─────────────────────────────────────────────┐
                     │  Layer 3.5 (NEW): Response Composition          │
                     │  - per-channel rendering of a node's result     │
                     │  - chat: string passthrough (today's model)     │
                     │  - voice: synthesis rewrite rules, persona/      │
                     │    locale selection, interruptible vs verbatim  │
                     │    mode  (D4)                                   │
                     └──────────────┬─────────────────────────────┘
                                    ▼
                     ┌─────────────────────────────────────────────┐
                     │  Layer 4: Tool/Action Layer                    │
                     │  (UNCHANGED — see D9. Order Service, idempotency│
                     │   keys, D7/D8 threshold-gate logic all reused   │
                     │   as-is; voice work never touches this layer)   │
                     └──────────────┬─────────────────────────────┘
                                    ▼
                     ┌─────────────────────────────────────────────┐
                     │  Escalation/Handoff (voice-specific, D5)        │
                     │  - structured payload builder (participant     │
                     │    data + routing headers), derived by pure     │
                     │    code from conversation state/tags — not an  │
                     │    LLM decision (consistent with ../decisions- │
                     │    log.md D7)                                   │
                     │  - fires from multiple independent terminal     │
                     │    paths: transfer, hang-up, inactivity-goodbye│
                     │    (D6)                                         │
                     └─────────────────────────────────────────────┘
```

## Layer-by-layer detail

### Layer 1v — Voice Transport (new)

Responsibilities beyond today's Layer 1:

1. **Event taxonomy, not just turns.** `start`, `message`, `inactivity`, `hang-up` are distinct, each with different payloads and different downstream handling. Forcing `inactivity`/`hang-up` into synthetic "turn" rows (speaker/content) would misrepresent them — they have no speaker and no content, only a reason. See D1.
2. **DTMF extraction and pre-classification.** Mirrors `dtmf.ts`: detect whether a message is DTMF at all (`isDtmfText`-equivalent), whether it's a bare transfer request (`0`/`#`/repeated variants — `isDtmfTransferRequest`), and hand the raw digit string downstream tagged as DTMF rather than merged silently into ordinary text. Whether a given DTMF string is *acceptable* as a particular slot's value is **not** Layer 1v's decision — that's state-dependent (see D2) and belongs to Layer 3.
3. **Turn-taking control surface exposed both ways.** Not just "convert input to turn" — Layer 1v must also accept configuration signals from upstream (dynamic response latency, interruptible-vs-not per response) rather than only pushing events up. See D8.
4. **Interrupt classification.** A dedicated hook analogous to `onVoiceClassifyInterrupt`: DTMF digits arriving mid-response must not be classified as a spoken barge-in interrupt. See D9.

### Layer 2 — Conversation State Manager (additive only)

The existing schema (`conversation`, `turn`, `slot`, `tool_invocation` — see `../layer2-conversation-state-design.md`) is **not replaced**. Two additive needs surface from real Voice AI Vendor voice state (`store.ts`'s `Store` type):

- **Per-conversation transient channel state** — `voiceInactivityCount`, `acceptingOrderNumberDtmf`, `awaitingVerification`, `speedbumped` in Voice AI Vendor's `Store` are exactly the kind of small, frequently-mutated, node-scoped flags this project's `slot` table already models as generic key-value. No new table is needed — these are just more slots, written/read like any other (`voiceInactivityCount` as a numeric slot, `acceptingOrderNumberDtmf` as a boolean slot). Confirms D4's generic-slot-table bet is *already* the right shape for this. See D3.
- **Per-field capture timestamps for the escalation payload.** Voice AI Vendor hand-rolls this in `store.ts`'s `capture()` (a `TIMESTAMPED_FIELDS` set, stamped via `Date.now()` on every patch). This project's `slot.filled_at` / `source_turn_id` columns **already give this for free**, and arguably with better fidelity (per-field, not per-patch-call). No schema change needed — worth naming explicitly as a place the current design is already ahead. See D7.

### Layer 3 — Orchestration/Policy Engine (extended)

1. **Node capability declarations.** `../Voice AI Vendor-implementation-comparison.md` §8 already flagged that `Map<String, NodeHandler>` should eventually grow into `Map<String, NodeDefinition>` (handler + required-slots + description) if D6 (graph-as-config) is revisited. Voice is the concrete forcing function: a node now also needs to declare *which extra input modality it accepts* (e.g., `collect_order_id` accepts DTMF; `confirm_transfer` does not). See D2.
2. **Proactive/system-initiated turns.** Voice's `start` event triggers a real, unprompted agent turn (`VoiceGreetingRoot` in `main.tsx`) with zero preceding user input — the exact mechanism `../decisions-log.md` D18 speculated about ("a new endpoint... writes an initial agent turn directly, bypassing Kafka/GraphExecutor"). Voice AI Vendor's actual code confirms that reasoning and gives it a concrete shape: an event-triggered code path outside the normal reactive `GraphExecutor.step()` loop, not a new node type inside the graph. See D6.
3. **Pure-code DTMF semantics, not LLM interpretation.** Whether "0" means "transfer me" vs. "part of my order number" is resolved by conditional code reading conversation state (`acceptingOrderNumberDtmf`), exactly like this project's own D7 principle (conditional/branching logic is pure code, never an LLM call). No new principle needed — just a new place D7 applies. See D2.

### Layer 3.5 — Response Composition (new layer, voice-only in practice)

Today, a node handler returns a bare `String` and that string *is* the response (`../Voice AI Vendor-implementation-comparison.md` §2). For chat this stays true. For voice, Voice AI Vendor never ships a node's raw text straight to TTS:

- `synthesisRewriteRules` in `main.tsx` rewrite digit sequences (`<SpellGrouped>` tags, "ending in 1234") into speakable form before synthesis (`voice-synthesis.ts`).
- Response mode varies per message: `verbatim` + `disallowInterruptible` for the legal-disclosure greeting and the transfer handoff line, `paraphrase` for inactivity re-engagement — i.e., "is this response allowed to be interrupted, and is its wording fixed or LLM-varied" is a **per-response**, not per-channel, decision.
- Persona/locale is selected per response (`greetingPersonaForLocale`), not fixed for the whole conversation, because a single conversation can mix languages mid-call (`LanguageSwitching`).

This project's existing `phraseNaturally` pattern (ad hoc LLM call inside a handler, flagged as duplicated in `../Voice AI Vendor-implementation-comparison.md` §5) is the seed of this layer, not something to discard — it just needs to become an explicit, shared step between "node produces a result" and "channel renders it," parameterized by channel and by per-response flags (interruptible, verbatim-vs-paraphrase). See D4.

### Layer 4 — Tool/Action Layer (unchanged)

Nothing in Voice AI Vendor's voice-specific code (`main.tsx`, `transfer.tsx`, `dtmf.ts`, `genesys-utils/*`) touches tool execution or idempotency. Order lookup, return processing, and threshold-gate logic (`../decisions-log.md` D7/D8) are channel-agnostic in Voice AI Vendor's own implementation too. Confirms this layer needs zero voice-specific rework. See D10.

### Escalation/Handoff — voice-specific rebuild (not a Layer 4 concern)

Currently: an `escalation` node sets `conversation.status = 'escalated'` and pauses — sufficient when a human picks the conversation up by reading Postgres. Voice needs a materially richer contract, evidenced directly by `transfer.tsx`'s `executeTransfer` and `genesys-utils/transfer-utils.ts`:

- A **structured payload** (`buildTargetParticipantData`) of ~20 fields — order/item matrix, transfer reason/cause/segment, fraud hold, language, API call/error history — built by pure-code derivation functions (`deriveTransferCause`, `deriveTransferSegment`, `deriveFraudHold`, ...) from conversation tags/state, never by asking the LLM to produce the payload.
- **Routing metadata** (UUI headers) separate from the reporting payload (participant data attributes) — two different consumers (the telephony routing flow vs. the human agent's screen-pop) with two different shapes, sent together.
- A **call-end reporting path independent of transfer** (`genesys-utils/call-end.ts`'s `writeCallEndParticipantData`), fired from `hang-up`, from the inactivity goodbye, and (implied) from a normal agent-completed end — each producing the *same* payload shape, with a dedup guard (`already-written`) so exactly one write happens per call regardless of which path got there first.

See D5 and D6.

## What stays exactly as-is

- Layer 2 schema (`conversation`, `turn`, `slot`, `tool_invocation`) — additive slots only, no new tables, no column changes.
- Layer 4 and the Order Service boundary — completely untouched.
- D7 (pure-code conditionals, never LLM) and D8 (idempotency key at the tool boundary) — both directly reused, not just "still valid" but actively load-bearing for the new voice-specific pure-code derivation logic (transfer cause/segment) and the call-end dedup guard.
- The `channel` field already present on `conversation` (per `../layer2-conversation-state-design.md`) — already anticipates exactly this moment.

## Known open questions (not resolved by reading Voice AI Vendor's code — genuinely undecided)

- This project has no Genesys/telephony platform to integrate with — the *shape* of a structured handoff payload is confirmed as necessary, but the actual receiving system (if any) is undetermined. A stub/logged payload (mirroring how Voice AI Vendor logs the payload when `contactId`/`genesysConvId` is absent) may be the right placeholder rather than building toward a specific telephony vendor prematurely.
- Voice AI Vendor's own SIP/RTP/STT/TTS pipeline experience (`../context-doc.md`: Groq Whisper STT, Piper TTS, Groq Llama) predates this project and is a different stack than Voice AI Vendor's own (Deepgram `nova-3`, per `main.tsx`'s `onVoiceCheck`). Whether to reuse the existing pipeline as Layer 1v's implementation or evaluate alternatives is unresolved — this doc only establishes the architecture the pipeline needs to plug into, not which pipeline.
- Barge-in/interrupt classification (D9) and dynamic latency tuning (D8) both assume a voice SDK/runtime that exposes hooks for them (as `@Voice AI Vendor/agent` does). Whether the existing custom SIP/RTP pipeline exposes equivalent seams, or needs new work to do so, is unverified.
