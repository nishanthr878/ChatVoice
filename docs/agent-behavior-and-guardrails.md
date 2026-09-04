# Agent Behavior Contract: Greeting, Keyword/Rule-Following, and Guardrails

**Status:** Proposal — none of this exists in the current implementation yet. Verified: no guardrail, moderation, PII-policy, or keyword-shortcut code anywhere in `orchestrator/src/main` today. Grounded in `target-main`'s real source (`main.tsx`, `language.tsx`, `dtmf.ts`, `moderation/input/abuse-defend-action.tsx`, `constants.ts`) — not from Voice AI Vendor's marketing description. Complements `tool-calling-migration.md` (which covers *what runs*) and `voice-channel/*` (which covers *voice transport*) — this doc covers *what the agent is never allowed to get wrong*, regardless of which orchestration paradigm this project ends up on.

## 0. The one principle that actually answers "how do I make sure it never misses it"

Every reliability guarantee in Voice AI Vendor's reviewed code shares one shape: **it runs at a fixed chokepoint every turn passes through, unconditionally — never inside a flow-specific handler that a future flow author could simply forget to include.** Nothing is enforced by "the LLM was told and should remember." Two concrete techniques implement this:

1. **Pure-code pre-filtering before the LLM ever runs**, for anything pattern-matchable (keywords, exact phrases, digit sequences). Decided by regex/string match, not requested via prompt — see `dtmf.ts`'s `DTMF_TRANSFER_PATTERN` and `language.tsx`'s `SPANISH_REQUEST_PATTERNS`/`ENGLISH_REQUEST_PATTERNS`, both checked *before* any LLM call, with the LLM only used as a fallback for genuinely ambiguous cases.
2. **A mandatory hook wired once, centrally, at the platform/agent-config level** — not opted into per flow or per tool. `main.tsx`'s `useCustomAbuseDetectionProps: () => ({ foundAbuseDefendAction: abuseDefendAction })` is set once on the whole agent; every conversation, present and future, passes through it. There is no per-flow "remember to call the moderation check" step to skip.

Everything below is this principle applied to three concrete areas. Where this project's current code violates it (nothing is centralized; nothing runs before the LLM), that's called out explicitly as the gap to close, not just a stylistic difference.

## 1. Greeting

**Current state:** `IntentClassificationFlow.handleClassify` treats `GREETING` as one of four fixed classification buckets and returns a literal string directly — `"Hi! I'm VA, how can I help you today?"` — not `phraseNaturally`'d. That's already correct by accident: it's reactive-only (fires when the user says "hi" first), and it's already hardcoded text rather than LLM-composed.

**Voice AI Vendor's shape (`main.tsx`'s `VoiceGreetingRoot`, fired proactively on the `start` event, per `voice-channel/decisions-log.md` D6):** the greeting is not one LLM-composed paragraph — it's an ordered list of purpose-specific segments, each its own `<Respond mode="verbatim" disallowInterruptions>` block:
1. Legal/compliance intro (call-recording notice, privacy-policy pointer) — `ENGLISH_GREETING_INTRO`, a hardcoded constant.
2. A language-offer segment, spoken in a *different* locale/persona than the rest, so a Spanish sentence doesn't bleed accent into the surrounding English (`greetingPersonaForLocale`).
3. An optional flash message.
4. The actual "how can I help" prompt.

Every segment is `verbatim` (never LLM-paraphrased) and `disallowInterruptions` (the guest cannot barge past the legal notice). This matters specifically *because* the intro is compliance-relevant — a paraphrased recording disclosure might not satisfy the actual legal requirement, so it is never handed to an LLM at all.

**Recommendation:**
- Keep chat's `GREETING` bucket as a hardcoded literal — do not let it start going through `phraseNaturally`; a paraphrased greeting risks drifting from approved copy as flow logic changes around it over time.
- When voice lands, structure the greeting as an ordered list of typed segments (`GreetingSegment{text, verbatim, interruptible}`) rather than one composed string — this mirrors `VoiceGreetingRoot` directly and gives a concrete, unit-testable home for "the disclosure segment must be present and byte-identical to the approved copy," instead of a hope embedded in a prompt.
- Any greeting copy with compliance weight never passes through an LLM call, full stop — hardcode it as a constant, exactly like `ENGLISH_GREETING_INTRO`.

## 2. Keyword / rule-following behavior

**Current state:** zero pure-code keyword handling exists anywhere in the project. There is no guaranteed path from "the user says 'let me talk to a human'" to an escalation — it depends entirely on `IntentClassificationFlow`'s single LLM call correctly bucketing that phrase (it would presumably fall into `OTHER`, whose default response does offer a human connection — but that's a byproduct of the `OTHER` catch-all's wording, not a designed guarantee; a differently-worded request has no such safety net).

**Voice AI Vendor's shape, evidenced twice, same pattern both times:**
- `language.tsx`: `SPANISH_REQUEST_PATTERNS`/`ENGLISH_REQUEST_PATTERNS` are explicit regex arrays (`\b(cambiar a|speak(?: to me)?(?: in)?|switch to|...) (spanish|espanol)\b`, etc.), checked in `explicitLocaleRequest` *before* any LLM involvement. Only when there's no direct pattern match does the code fall back to a probabilistic language detector — and even that fallback is gated by `isSubstantiveLanguageMessage` (a lexical-evidence check) so short/neutral utterances never trigger a wasted synchronous LLM call at all.
- `dtmf.ts`: `DTMF_TRANSFER_PATTERN` (`0`, `#`, or repeats of either) is matched at the transport boundary, before slot-filling or classification ever sees the message.
- `<Rule content="A request to change language is not a transfer request." />` / `<Policy content="You support English and neutral Latin American Spanish." />` — declarative components re-rendered on every turn of that component tree. The rule is structurally present every single time, not "included this once because someone remembered to add it to a prompt template" — there is no code path that renders the flow without also rendering the rule.
- `<Supervisor fn={(_ctx, controls) => controls.instruct(responseInstruction)} />` — a per-turn, code-computed instruction (which locale is currently active) injected fresh every turn, rather than baked into a static system prompt that can silently go stale as state changes.
- An explicit, hard-won phrasing lesson, left as a comment in `language.tsx`: a bare imperative as the *last* thing before the model acts ("Respond only in English.") reads as "reply now" and can suppress required tool calls that should have run first (their own TGS issue 46). Rule instructions should be phrased as **constraints on the output**, not **commands to act immediately**.

**Recommendation (Java-shaped):**
- Any phrase that must deterministically produce a specific outcome (explicit transfer requests, opt-out/"stop" commands) gets a `KeywordMatcher` step that runs inside `GraphExecutor.step()`, before `dispatch(...)` hands the turn to a node handler — same chokepoint as §0. A match here can short-circuit straight to `escalate_to_agent` with zero dependency on `IntentClassificationFlow`'s LLM call getting it right. This is a direct, low-cost analogue of `DTMF_TRANSFER_PATTERN`/`explicitLocaleRequest`.
- Any standing constraint that must hold across an entire flow (this project already has one, informally: `decisions-log.md` D19's fix constraining `phraseNaturally` to "never state a specific number/fact you weren't given," after a real hallucination bug) should live as one shared constant referenced by every prompt it applies to — the Java equivalent of `<Rule>` — rather than re-typed per flow or left as a one-off patch on the handler that happened to break first. A new flow added later must not be able to *omit* it by not thinking to type it.
- Adopt the phrasing lesson directly and permanently: word constraints as properties of the output ("your response must be in English," "never state a dollar figure you were not given"), never as a trailing command to act now.

## 3. Guardrails — the direct answer to "how do I implement so it never misses it"

**Current state:** none. No PII policy, no abuse/moderation detection, no deny-listed topics. Every response in both flows is produced by an unconstrained LLM call with no safety layer above it.

**Voice AI Vendor's defense-in-depth, in order of how each piece contributes to "never misses it":**

1. **Category/threshold definitions live in one governed, config-level place, not scattered in code.** Per the comment in `abuse-defend-action.tsx`: "Guardrails stays configured in the Agent Studio panel: categories, thresholds, per-category modes and all copy still come from `sdk:abuse`." `abuseDefendAction`'s code overrides only the *mechanism* of hand-off (Target's Genesys SIP transfer instead of a generic platform event); detection and the threshold decision itself (`chooseAbuseRefocusInstruction`) stays in one shared helper, called the same way regardless of which flow the guest was in when it tripped.
2. **The check is wired once, centrally, on the whole agent — not opted into per flow.** `useCustomAbuseDetectionProps: () => ({ foundAbuseDefendAction: abuseDefendAction })` sits in `main.tsx`'s top-level agent config. A new Goal/flow added to this agent later inherits it automatically; there is no per-flow step to remember.
3. **A reviewed, default-deny data policy**, not inferred per tool. `SafetyConfigDefault.sensitivePii` is extended with `TARGET_CURRENTLY_UNNEEDED_PII` (name, email, phone, address, DOB, account/loyalty ID, credentials, government ID) with the comment: *"No currently supported interaction requires these additional categories, so keep them deny-listed until a reviewed journey explicitly needs one."* Default-deny; loosening requires a deliberate, reviewed change — never an accidental omission by whoever wrote a new tool's prompt.
4. **Threshold/count-based escalation, not a binary trip-wire.** `isConversationAbusive` plus a running count feed `chooseAbuseRefocusInstruction`, so one borderline message gets a refocus attempt before anything as severe as transfer or termination — the same bounded-retry-before-escalation philosophy this project already applies elsewhere (`decisions-log.md` D19's retry-then-escalate pattern for order lookups).
5. **The actual safety-critical output is never LLM-generated text.** The abuse termination/transfer message is either the exact Guardrails-panel-configured string or a hardcoded fallback (`DEFAULT_TERMINATE_MESSAGE = "I'm sorry, but I can't help you with that."`), sent through `<Respond mode="verbatim">` — the LLM cannot paraphrase, soften, or drop it. **This is the single most direct mechanism for "never misses it":** don't ask an LLM to say the right thing under pressure; hand it fixed, reviewed text it is mechanically required to speak unmodified.

**Recommendation (Java-shaped, concrete):**
1. Define a `GuardrailPolicy` as data (a config class, or a small table) — deny-listed data categories the agent must never solicit/store, plus any abuse-style category/threshold list — living in one place, never embedded piecemeal inside flow prompts. Default-deny: nothing is addable to an "OK to ask for" set without a deliberate, reviewed change.
2. Add **one** mandatory guardrail check inside `GraphExecutor.step()`, before `dispatch(...)` runs for the turn. This is the chokepoint every conversation and every current and future `Flow` implementation passes through — a new flow cannot bypass it by omission, because it isn't that flow's responsibility to call it. (This placement decision is paradigm-independent: if `tool-calling-migration.md`'s Voice AI Vendor-style pilot goes ahead, the equivalent chokepoint is the tool-dispatcher that checks prerequisites before every tool call — the check belongs there, never duplicated per tool.)
3. When a guardrail trips, respond with a hardcoded, reviewed literal string — never route it through `phraseNaturally` or any other LLM call. This project already does this correctly, incidentally, for `handleEscalateToAgent`'s fixed strings; make it a rule ("guardrail-triggered exits are always literal, never LLM-generated") instead of a coincidence of how one handler happened to be written.
4. Tag every guardrail trip as its own logged, queryable event — mirroring `addAgentTags(["transfer:abuse"])` — so guardrail behavior is auditable independent of conversation content. A guardrail with no observability is one whose "never misses it" claim can't actually be checked.
5. Write a test in the shape of `GraphExecutorReDispatchTest`, but for the guardrail chokepoint specifically: assert that *every* registered `Flow`/node combination still triggers the guardrail check when a trip condition is present. This is what makes "never misses it" a checked property instead of a hope — a future flow that somehow bypasses the shared chokepoint fails a test, rather than surfacing as a production incident.

## Summary — where each mechanism belongs

| Concern | Enforcement mechanism | Chokepoint (must be one place, not per-flow) |
|---|---|---|
| Compliance-relevant greeting copy | Hardcoded, `verbatim`, never LLM-composed | Greeting construction, not a flow node |
| Deterministic keyword triggers (transfer phrases, opt-out) | Pure-code pattern match, LLM only as fallback | `GraphExecutor.step()`, before `dispatch(...)` |
| Standing per-flow constraints (no fabricated facts, language lock) | One shared constant/helper referenced everywhere it applies | Shared prompt-building helper, not re-typed per flow |
| PII / data-collection policy | Config-level deny-list, default-deny | One `GuardrailPolicy`, referenced by every prompt-building path |
| Abuse/moderation detection | Threshold+count based, centrally wired | `GraphExecutor.step()` (or the tool-dispatcher, if migrated) |
| Guardrail-triggered exit copy | Hardcoded literal string, never `phraseNaturally`'d | Same shared exit path every guardrail trip uses |
