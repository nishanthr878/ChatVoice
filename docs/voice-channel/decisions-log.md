# Decisions Log — Voice Channel

**Status:** Draft, pre-implementation. Every decision here is grounded in reading real Voice AI Vendor Agent SDK voice code (`target-main/target-main/agents/base`: `main.tsx`, `transfer.tsx`, `dtmf.ts`, `store.ts`, `genesys-utils/genesys-client.ts`, `genesys-utils/transfer-utils.ts`, `genesys-utils/call-end.ts`, `voice-synthesis.ts`, `tests/dtmf.tests.ts`), not from assumption or from Voice AI Vendor's marketing description. Where a decision confirms, corrects, or extends an entry in `../decisions-log.md`, that's called out explicitly. Format: Decision → Alternatives considered → Why → Confidence.

---

### D1 — Voice ingress uses a distinct event taxonomy (`start` / `message` / `inactivity` / `hang-up`), not a single unified `turn` abstraction

**Alternatives considered:** keep `../architecture-doc.md`'s Layer 1 promise ("converts channel-specific input into a unified `turn` representation... nothing downstream needs to know which channel it came from") and force every voice event into a synthetic turn row.
**Why:** `main.tsx`'s `onClientEvent` switches on four event types with genuinely different shapes and different downstream handling — `inactivity` carries a tick count and drives a re-engagement counter with no `speaker`/`content` at all; `hang-up` triggers terminal reporting, not a conversational response; `start` triggers an *agent-initiated* turn with no user input to respond to. Only `message` actually maps onto this project's existing `turn` concept. Treating all four as "just another turn, differently shaped" would corrupt the one thing `turn` currently guarantees — a clean speaker/content/sequence history usable for progressive disclosure into LLM prompts.
**Confidence:** high — directly observed in the reference implementation, not inferred.

---

### D2 — DTMF acceptability is per-node state, resolved by pure code, not a global channel policy or an LLM decision

**Alternatives considered:** (a) treat DTMF digits as ordinary text and let the LLM/classifier interpret them like any spoken input; (b) a single global "voice channel accepts DTMF" flag.
**Why:** `dtmf.ts`'s `canAcceptDtmfAsOrderNumber(text, acceptingOrderNumberDtmf)` and `main.tsx`'s `acceptingOrderNumberDtmf` store flag show this is state-dependent, not global — DTMF `"0"` means "transfer me" almost everywhere, but means "part of my order number" specifically while `collect_order_id`-equivalent nodes are active, and means neither during, e.g., a yes/no confirmation. `tests/dtmf.tests.ts`'s `keypadDigitDoesNotSelectOption` scenario shows the real failure mode of *not* doing this: a guest pressing "2" while being offered two options must not be silently interpreted as "select option 2" — it must be clarified. Resolving this via LLM judgment would be exactly the kind of policy the LLM can get wrong under variation; resolving it via pure code reading `current_node`/state extends this project's own `../decisions-log.md` D7 principle (conditional logic is pure code, never an LLM call) into a new domain, rather than requiring a new principle.
**Confidence:** high on the "pure code, not LLM" half (direct extension of an already-high-confidence project principle); medium on the exact mechanism (per-node capability flag vs. a richer per-node "accepted input shapes" declaration) — the latter is architecturally cleaner but unbuilt in either codebase as reviewed.

---

### D3 — Voice-specific transient state (inactivity ticks, DTMF-acceptance flags, verification-wait flags) is modeled as ordinary `slot` rows, not a new table

**Alternatives considered:** a dedicated `voice_state` table or JSONB column on `conversation` mirroring Voice AI Vendor's `Store` type's voice-only fields (`voiceInactivityCount`, `acceptingOrderNumberDtmf`, `awaitingVerification`, `speedbumped`).
**Why:** these fields are small, frequently-mutated, single-conversation-scoped key-value pairs — exactly what `../decisions-log.md` D4 already designed `slot` to hold generically. Voice AI Vendor needed a typed `Store` object because their platform's state primitive (`useRootStore`/`capture()`) is a typed object, not because the *data itself* demands typed columns. This project's key-value `slot` table already fits without any schema change — a case where the existing generic design absorbs a new requirement for free, which D4's own "revisit only if a third flow proves the model insufficient" caveat did not anticipate needing to cover voice-only bookkeeping too, but does.
**Confidence:** high — this is a direct fit-check against already-read code, not new design.

---

### D4 — Introduce a Response Composition step between a node's result and channel output, rather than keeping "the handler's returned `String` is the final response" for voice

**Alternatives considered:** keep the current model (handler returns final text, `phraseNaturally` is called ad hoc inside handlers when LLM wording is wanted) and have voice's TTS layer speak whatever string comes out, unmodified.
**Why:** Voice AI Vendor's code never does this. `main.tsx`'s `synthesisRewriteRules` rewrite digit sequences into speakable form (`<SpellGrouped>` tags, "ending in 1234" → spelled digits) *before* synthesis — a transformation chat output never needs and a node handler has no business doing itself. Response *mode* also varies per message, not per channel: the greeting and the transfer handoff line are `mode="verbatim"` with `disallowInterruptions`, inactivity re-engagement is `mode="paraphrase"` — meaning "can this be interrupted" and "is the wording fixed or LLM-varied" are per-response flags a composition step must carry, not properties of the channel as a whole. Locale/persona selection is also per-response (`greetingPersonaForLocale`), since one conversation can mix languages mid-call.
**Confidence:** high that this is structurally necessary for voice; medium on exact placement (a genuinely new "Layer 3.5," vs. folding it into an expanded Layer 1v output path) — flagged in the architecture doc as the one required new layer, not merely an extension of an existing one.

---

### D5 — Voice escalation requires a structured handoff payload built by pure-code derivation from conversation state/tags, not the current `status = 'escalated'` flag alone

**Alternatives considered:** reuse the existing escalation model unchanged — an `escalation` node sets `conversation.status = 'escalated'`, conversation pauses, a human reads the Postgres row.
**Why:** `transfer.tsx`'s `executeTransfer` and `genesys-utils/transfer-utils.ts` build a ~20-field payload (order/item matrix with position-indexed columns, transfer reason, transfer cause resolved from intent tags via a fixed priority order, fraud hold, language, API call/error history) split across two purposes — Genesys "participant data" (a human agent's screen-pop) and UUI routing headers (which queue the call lands in) — every field derived by named pure-code functions (`deriveTransferCause`, `deriveTransferSegment`, `deriveFraudHold`, `deriveTransferBusinessCapability`) reading tags and store state, never asked of the LLM. A human picking up a *live transferred call* has no transcript to read yet, unlike a human reading an escalated conversation's Postgres row asynchronously — the richer contract exists because the receiving context is genuinely poorer, not because Voice AI Vendor over-engineered it.
**Confidence:** high that some structured payload is necessary for any real voice handoff; low-to-unknown on this project's actual receiving system, since there is no Genesys-equivalent target to integrate with yet (see architecture doc's open questions) — the payload *shape* is the transferable lesson, not the Genesys-specific field names.

---

### D6 — Two more Voice AI Vendor-observed mechanisms are adopted as-is, not merely inspired by: proactive system-initiated turns, and terminal-state reporting fired from multiple independent paths

**What was requested/deferred previously:** `../decisions-log.md` D18 explicitly deferred "VA speaks first" as a real, separate feature requiring new mechanism, reasoning it would likely need "a new endpoint... that writes an initial agent turn directly to Postgres, bypassing Kafka/GraphExecutor."
**What Voice AI Vendor's code shows:** `main.tsx`'s `case "start":` does exactly this — `generateAgentResponse(<VoiceGreetingRoot />)` fires an agent-authored turn with zero preceding user input, entirely outside the reactive message-triggered path. D18's reasoning is confirmed correct, not superseded — this decision exists to close D18 out with a concrete reference shape (an event-triggered code path parallel to, not inside, `GraphExecutor.step()`) rather than leave it as an open question.
**Separately, terminal-state reporting:** `genesys-utils/call-end.ts`'s `writeCallEndParticipantData` is called from three independent places — the `hang-up` client event, the inactivity-goodbye completion, and (structurally) a normal agent-completed end — each capable of reaching call-end first, guarded by a single `already-written` tag check so exactly one payload is ever sent per call. The current chat-only design has no equivalent need (chat has no hang-up/inactivity events), so this is new cross-cutting surface area: any voice "conversation ended" reporting must be wired to every terminal path independently, not just the happy-path flow completion this project's Layer 3 currently assumes.
**Alternatives considered (for reporting):** wire reporting only to the one terminal path that exists today (successful flow completion via the graph reaching a terminal node).
**Why rejected:** would silently under-report the majority of real voice call endings — most real calls end by hang-up or inactivity, not by the graph reaching a designed terminal node.
**Confidence:** high on both halves — both are direct, load-bearing patterns in the reference implementation, not incidental style choices.

---

### D7 — `slot.filled_at`/`source_turn_id` already satisfy the per-field capture-timestamp requirement the voice handoff payload needs; no schema change required

**Alternatives considered:** add a Voice AI Vendor-style `fieldTimestamps: { [field]: epochMs }` map, mirroring `store.ts`'s `capture()` function which stamps `Date.now()` onto a fixed `TIMESTAMPED_FIELDS` set on every patch.
**Why:** the handoff payload (D5) needs to report *when* each field was captured, falling back to hand-off time for fields never set (`capturedAt` helper in `transfer.tsx`). Voice AI Vendor had to hand-build this because their state primitive is a single merged object with no native per-field provenance. This project's `slot` table already carries `filled_at` and `source_turn_id` per slot, per `../layer2-conversation-state-design.md` — strictly finer-grained than Voice AI Vendor's per-patch-call timestamp (which loses precision when a single `capture()` call stamps multiple fields at once, all sharing one epoch — visible in `transfer-utils.ts`'s `buildParticipantData` needing to nudge colliding epochs apart with a `while (used.has(epoch))` loop, a workaround this project's design doesn't need). Worth logging explicitly as a place the existing design is already ahead of the reference implementation, not just "not behind."
**Confidence:** high — verified against both schemas directly, not inferred.

---

### D8 — Turn-taking latency must be a dynamic, node-aware signal reaching from Layer 3 down into Layer 1v transport config, not a fixed transport constant

**Alternatives considered:** pick one fixed response latency for all of voice, uniformly.
**Why:** `main.tsx`'s `OrderNumberLatencyMonitor` bumps `voice.updateMinResponseLatencyMs(5000)` specifically while the agent is collecting a spoken order number (giving the guest time to read digits aloud with natural pauses) and resets it after a successful lookup. This is domain knowledge living at Layer 3 (which node is active) that must reach back down into Layer 1v — the reverse data-flow direction from `../architecture-doc.md`'s "nothing downstream needs to know the channel" framing, which only ever describes flow moving up the stack. Voice genuinely needs a return channel.
**Confidence:** high that the need is real (directly observed); unresolved how cleanly this composes with a config-driven graph (`../decisions-log.md` D6) — a YAML node definition would need to declare latency hints, which is a new kind of node metadata beyond what's flagged so far.

---

### D9 — Barge-in/interruption handling needs an explicit per-response policy and a dedicated interrupt-classification hook distinguishing DTMF from genuine speech

**Alternatives considered:** rely on a single default interruption behavior for all voice responses.
**Why:** `main.tsx`'s `onVoiceClassifyInterrupt` special-cases DTMF text (`isDtmfText(text)`) to force a `RESET` classification, specifically so a keypad press is never misread as the guest interrupting to speak. Separately, `disallowInterruptions` is set per-`Respond` block (the legal-disclosure greeting, the Spanish language offer) — meaning "can the guest talk over this" is a per-message authoring decision, not a channel-wide default. Chat has no analogous concept at all (there is no "the user started typing while the agent's response was still streaming" interrupt to classify in this project's current turn-based, fully-synchronous request/response model).
**Confidence:** high that this is required, net-new surface area for voice with zero chat precedent to extend.

---

### D10 — Layer 4 (tool/action layer) and the Order Service boundary require zero voice-specific changes

**Alternatives considered:** none seriously — included for completeness, since it's tempting to assume "adding a channel" touches every layer.
**Why:** nothing in any voice-specific file reviewed (`main.tsx`, `transfer.tsx`, `dtmf.ts`, `genesys-utils/*`, `store.ts`) touches tool invocation, idempotency, or the equivalent of this project's Flask order service. Order/return/price-match tool logic in Voice AI Vendor's own codebase (per `../Voice AI Vendor-implementation-comparison.md`, reviewing `tools/return.tsx` etc.) is channel-agnostic there too. This is a genuine confirmation, not an assumption: `../decisions-log.md` D7 (pure-code conditionals) and D8 (idempotency key at the tool boundary) need no voice-aware rework.
**Confidence:** high — absence of any counter-evidence across every voice-specific file read is itself the evidence here.

---

## Summary: what this means for build sequencing

None of the above should be read as "build all of this before starting." Consistent with `../decisions-log.md` D1's own sequencing philosophy (two concrete flows before generalizing), the honest next step is a trivial voice skeleton — one flow, one Layer 1v event (`message` only, no DTMF/inactivity/hang-up yet), no response composition beyond passthrough — to find out which of D1–D9 above actually bites first in this project's own stack, rather than building the full voice architecture speculatively. D6 (proactive greeting) is the one item with the most direct, already-deferred prior art (`../decisions-log.md` D18) and is the natural first real voice-specific mechanism to build, once the trivial skeleton proves the transport layer works at all.
