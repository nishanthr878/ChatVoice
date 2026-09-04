# Capability Gaps: What Else Voice AI Vendor's Real Code Does That This Project Doesn't Yet

**Status:** Audit, not a decision. Complements `voice-channel/*`, `tool-calling-migration.md`, and `agent-behavior-and-guardrails.md` — those cover orchestration paradigm, voice transport, and greeting/keywords/guardrails respectively. This doc closes out the remaining unread parts of `target-main` (`tools/knowledge.tsx`, `genesys-utils/int-summary.ts`, `integrations/api-observability.ts`, `tests/order-status.tests.ts`, `goals/order-status/order-lookup-on-start.ts`) and confirms five further gaps, ranked by how much this project is currently missing them.

## 1. Knowledge/FAQ answering — grounded retrieval, not a flow (the biggest actual hole)

**Current state:** zero. This project has exactly two flows, both narrowly transactional (`check_order_status`, `process_return`). A policy or FAQ question — "what's your return policy," "do you price match" — has no path today: it either gets misclassified into a flow it doesn't belong in, or falls into `IntentClassificationFlow`'s `OTHER` catch-all, which goes straight to "I'm not able to help with that directly — let me connect you with a human agent." Every informational question currently costs an unnecessary human escalation.

**Voice AI Vendor's shape (`tools/knowledge.tsx`):** `AnswerFromTargetKnowledge` is a distinct tool `type: "knowledge"` (not `lookup`/`action`), backed by a `knowledgeSource` (an indexed content source) with `retrievalOptions.llmVisiblePartitionParams` scoping what the LLM sees, and an `onResults` hook that records which article IDs actually got surfaced this conversation — feeding the transfer payload's `KA` field, direct continuity with the escalation-payload work in `voice-channel/architecture-doc.md` §"Escalation/Handoff". It's registered flatly alongside every transactional tool (`tools/index.ts`) — just another thing the agent can reach for, not a separate flow with its own state machine.

**Recommendation:** even without adopting the Voice AI Vendor tool-calling paradigm wholesale (`tool-calling-migration.md`), add an `answer_from_knowledge` node/flow reachable from `intent_classification`'s `OTHER` bucket (or a new dedicated bucket) — retrieval against even a small indexed set of markdown policy docs is enough to prove the pattern, consistent with this project's own D1 philosophy (two concrete examples before generalizing). Critically, reuse the ground-truth-constrained response principle this project already learned the hard way (`decisions-log.md` D19's `handleRespondWithDetails` redesign after a hallucination bug): answer only from retrieved content, never from the model's free-standing knowledge, or a plausible-sounding but wrong policy statement becomes a real risk.

## 2. Dedicated conversation summarization for handoff (with its own redaction rule)

**Current state:** escalation just flips `conversation.status = 'escalated'`; nothing produces a human-readable account of what happened for whoever picks it up. The earlier `voice-channel` docs named a `summary` field in the transfer payload but didn't explain how it gets produced — this is the missing piece.

**Voice AI Vendor's shape (`genesys-utils/int-summary.ts`):** `summarizeConversation` is a dedicated LLM call, entirely separate from the turn-by-turn response-generating calls, parameterized by situation (`Transfer`/`Resolved`/`HungUp`/`Inactive`/`Abuse`, each a one-line framing) plus a shared, non-negotiable rule set:
- Exclude fraud, holds, security controls, thresholds, and internal policy from the summary — a redaction guardrail specific to *this one output*, distinct from the conversation's general guardrails.
- Always write in English, even for a Spanish conversation — with an explicit comment on *why* this needed to be a hard rule rather than an assumption: "a summary written by the conversation's own model turn follows the call's active language... The summarizer below is a standalone task that response-language guidance cannot reach." They hit this as a real bug (a Spanish call producing a Spanish summary despite the param description demanding English) before fixing it structurally.
- Computed once and cached (`useSummarizeConversationOnce`) so every write path that needs it (transfer, call-end) shares one summary instead of each regenerating its own.

**Recommendation:** this is missing for chat-only escalation *today*, not just for voice. A `summarizeForHandoff(conversationId, situation)` helper — its own LLM call, its own prompt, explicit redaction rules (never mention the approval threshold, never mention internal fraud/risk signals), computed once and cached — would make an `escalated` conversation row in Postgres actually useful to whoever reads it, instead of requiring them to reconstruct what happened from raw turn history.

## 3. Structured per-external-call observability tagging (not just boundary logging)

**Current state:** `decisions-log.md` D12 established boundary-only *logging* (one line in, one line out) around the Kafka consumer — that's process-level logging, not structured, queryable tagging of each individual external API call.

**Voice AI Vendor's shape (`integrations/api-observability.ts`):** every upstream call gets a canonical, service-namespaced slug (`voice-assist:check-return-eligibility`, `tgs-orders:get-order-detail`, ...), recorded via `recordApiCall`/`recordApiError` "from the client's request choke point" — the same one-shared-location principle from `agent-behavior-and-guardrails.md` §0, applied to observability rather than safety. `omitPresent` collapses repeat calls to the same endpoint into a single tag per conversation. This feeds the transfer payload's `api_call_history`/`error` fields and, implicitly, whatever conversation-level analytics later query these tags. Error tags deliberately carry only status + code, never a raw response body — an explicit, named discipline: "low-cardinality and PII-free, keeping the tag set clean."

**Recommendation:** this project's `OrderServiceClient` (the Flask boundary) is the direct analogue. Add a `recordApiCall(operationSlug)`/`recordApiError(operationSlug, status, code)` pair, called from `OrderServiceClient`'s one implementation — not duplicated per flow handler — writing to a small tags table or appended onto the existing `tool_invocation` row. Cheap to add now, and directly useful the next time D13's still-open repartitioning risk or any other production question needs "what actually got called, and did it fail" without grepping raw logs.

## 4. A real evaluation framework — this is a concrete design for `architecture-doc.md`'s already-deferred "Layer 5"

**Current state:** `architecture-doc.md` explicitly stubs this out and stops: *"Layer 5 — Evaluation/Observability... scores turns against rubrics for policy violations and hallucinated actions. Not part of the current build phase."* No design exists yet — this is the first concrete proposal for it.

**Voice AI Vendor's shape (`tests/order-status.tests.ts`: 25 scenarios alone, same pattern repeated across `return.tests.ts`, `price-match.tests.ts`, `faq-knowledge.tests.ts`, etc.):** a `describe(title, tags, scenarios[])` DSL where each `Scenario` combines four distinct pieces:
1. **A simulated guest persona as free-text instructions**, not a scripted fixed input — e.g. *"You want to know which items are in your order... If the agent asks whether you mean a recent order it found, say no, it is a different order..."* The "user" side is itself an LLM playing a role, so it naturally explores realistic variation (correcting a wrong guess, declining an offer, asking a follow-up) without every branch needing to be hand-scripted.
2. **Exact tag assertions** (`assertions: ["order-status:fraud-transfer", "transfer"]`, negatable with `!`, prefix-negatable with `!^`) for anything that must be a deterministic decision — this is the same spirit as this project's own D7 principle and its existing `GraphExecutorReDispatchTest` habit of asserting exact node/state sequences, just applied at the conversation-scenario level instead of the unit level.
3. **Loose natural-language `expectedOutcomes`** for anything about wording/tone allowed to vary — an explicit comment names why: *"so they show what the agent conveys without breaking on wording changes."* Presumably LLM-graded against the actual transcript, not string-matched.
4. **`expectedRootStore` assertions** (`match.isSet()` / `match.isNotSet()`) checking final internal state alongside conversation-visible behavior — one scenario verifies both the black-box conversation *and* the white-box state in the same assertion block.

**Recommendation:** this is directly buildable on top of what this project already does well. The exact-tag-assertion half and the state-assertion half are essentially `GraphExecutorReDispatchTest`'s existing habit, just not yet reusable as a general harness. What's genuinely missing is the simulated-persona-as-LLM half: a small harness that drives a scripted persona (as an LLM, not fixed strings) through `GraphExecutor.step()` in a loop, then asserts on the resulting `slot`/`conversation` Postgres state (already this project's strength) plus a loose LLM-graded check on the final response text. The two-tier assertion strategy — exact where it must be deterministic, loose where wording is allowed to vary — is the one idea most worth deliberately carrying over, independent of the specific DSL shape.

## 5. Proactive, identity-based prefetching with single-flight coalescing (smaller — note only)

**Current state:** nothing — every lookup is guest-triggered and cold, one at a time.

**Voice AI Vendor's shape (`goals/order-status/order-lookup-on-start.ts`):** on a known ANI (caller ID), order numbers and details are prefetched non-blocking at call start, overlapping the greeting; a later `GetOrderStatus` call for a prefetched order serves straight from cache with zero extra round-trip. Coalesced single-flight per conversation+turn via a module-level in-flight `Map`, so the greeting's own prefetch attempt and the goal agent's first-turn attempt (it's deliberately mounted from both places, as a retry safety net) don't double-fetch.

**Recommendation:** lower priority than §1–4 — a latency optimization, not a correctness/capability gap. This project's chat channel has no ANI-equivalent identity signal at session start today (a logged-in user ID could play the same role later). Worth keeping in mind specifically once voice lands, since ANI is voice-native — not worth acting on now.

## Also worth naming, not detailing: CMS-driven externalized content

Voice AI Vendor pulls locale messages, personas, and guardrail copy from a `content` object (`content.$sdk?.agentVoice?.persona[locale]`, `content.errors?.abuseDetectionMode`) rather than hardcoding every string as a source constant — copy lives in a reviewable, non-code location, editable without a redeploy. This is the natural next step of `agent-behavior-and-guardrails.md`'s "guardrail-triggered exits are always literal strings" recommendation, once this project accumulates enough hardcoded literals to be worth centrally managing. Not urgent at current scale (two flows, a handful of literal strings) — flagged here so it isn't rediscovered as a surprise later, not because it needs action now.

## Summary, ranked by gap size × impact

1. **Knowledge/FAQ tool** — the only genuine "this capability doesn't exist at all" gap; every informational question currently over-escalates.
2. **Evaluation framework** — directly fills an already-acknowledged doc gap (Layer 5) with a concrete, precedented design; high leverage relative to effort given this project's existing state-assertion testing habits.
3. **Handoff summarization** — small to build, meaningfully improves escalation quality *today*, for chat, not only once voice exists.
4. **API call observability tagging** — cheap, mechanical, pure upside, no design risk.
5. **Identity-based prefetch** — real but lower priority; revisit once voice/ANI exists.
6. **CMS-style content externalization** — noted for later, not now.
