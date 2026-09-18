# ChatVoice — Conversational Order-Support Agent Platform

A from-scratch, deterministic-core conversational agent platform, built to understand and demonstrate the architecture underneath enterprise voice AI agent vendors — not a framework wrapper, not a tutorial project.

**What it does:** a chat widget and a real phone number where users can check order status and start returns, backed by a real distributed system: Kafka-ordered conversation events, a Spring Boot orchestrator with a bounded, LLM-guided state machine, a real external order service, and a self-hosted observability stack.

**What it's for:** demonstrating real backend/distributed-systems engineering — Kafka partition semantics, idempotency, hexagonal architecture, optimistic concurrency, containerized deployment, real-time telephony streaming — using an LLM-powered conversational agent as the vehicle, not the point. See `docs/context-doc.md` for the full reasoning.

## Live

Chat: [chatvoice.nishanthraj.in](https://chatvoice.nishanthraj.in) — deployed and confirmed working.
Voice: a real Twilio number, streaming audio via Media Streams into the same orchestrator — deployed and confirmed working with a real test call.

## Architecture, in one paragraph

Every user message — from the chat widget or a live phone call — publishes to Kafka, keyed by conversation ID for strict per-conversation ordering. A consumer dispatches it through `GraphExecutor`, a bounded state-machine loop that chains several node transitions within one message when nothing needs further user input, and stops the moment it does. Business logic is a coded, deterministic graph — the LLM only ever handles narrow language tasks (extraction, classification, phrasing), never decides consequential business rules (see `docs/decisions-log.md` D7). A separate `ConversationState` layer, fully migrated across both `check_order_status` and `process_return`, tracks what the conversation is actually *about* — active intent, focused entity, known entities — distinct from which execution step is running, enabling real coreference resolution ("what about the other one?") that a node-name-only state model structurally could not support. The voice channel reuses this exact same orchestration core; incoming call audio is streamed over WebSocket (Twilio Media Streams), transcribed in real time (Deepgram STT), fed through the identical `GraphExecutor` pipeline chat uses, and spoken back (Deepgram TTS) — one conversational engine, two channels.

Full architecture doc: `docs/architecture-doc.md`. Full, precise decision history (including real bugs found and fixed, with root causes): `docs/decisions-log.md`.

## Stack

Java 21 · Spring Boot 4.1 · Spring AI (Groq) · Kafka (KRaft) · Postgres · Flask + SQLite (order service) · Docker Compose · Loki/Promtail/Grafana/Tempo (observability) · Twilio Media Streams + Deepgram (voice)

## Running it

```bash
# .env (repo root) needs:
# GROQ_API_KEY=your-key
# DEEPGRAM_API_KEY=your-key
# VOICE_PUBLIC_HOST=your-domain-or-tunnel-host

docker compose up -d --build
```

This brings up: Postgres, Kafka (+ topic init), the Flask order service, the Spring orchestrator (chat + voice), and the full observability stack.

- Chat widget: `http://localhost:8080/index.html`
- Grafana (logs + traces): `http://localhost:3000` (anonymous admin access)

For real telephony, `VOICE_PUBLIC_HOST` needs to be a real, publicly reachable HTTPS/WSS hostname (a domain behind a reverse proxy with TLS, or a tunnel like ngrok for local testing) — Twilio's webhook and media stream both require it.

Useful checks while it's running:
```bash
docker compose ps
docker exec -it agent-platform-postgres psql -U agent -d agent_platform -c '\dt'
docker exec -it agent-platform-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group conversation-state-consumer
```

## What's real vs. what's known-open

This project is built and documented with the same standard applied throughout: verify with real evidence, log honestly what isn't done. Currently working and live-verified, in production: order lookup, multi-item questions, mid-conversation order switching (including pronoun-style references like "the other one"), return processing with a real deterministic approval threshold, full observability (logs + traces), and both chat and voice confirmed working end-to-end against the real deployed server — not just local dev. Both `check_order_status` and `process_return` are fully migrated to the `ConversationState` model.

Currently open, tracked precisely in `docs/decisions-log.md`: a Kafka message-redelivery duplicate-turn edge case; trace propagation doesn't yet cross the Kafka consumer boundary; the voice layer's reliability on longer, multi-turn calls hasn't been fully hardened (confirmed working for real calls, but two known local test sessions showed abnormal media-connection closures worth investigating further). Nothing here is hidden — the decision log is the actual, honest project history, not a cleaned-up summary.

## Project docs

- `docs/context-doc.md` — what this is, why it's shaped this way, current scope and known gaps
- `docs/architecture-doc.md` — system diagram and layer responsibilities, as actually built
- `docs/decisions-log.md` — the real, detailed decision and debugging history — every architectural choice, every bug found, every root cause, in order
- `docs/layer2-conversation-state-design.md` — the original persistence schema design

<img width="5679" height="5634" alt="diagram" src="https://github.com/user-attachments/assets/536c4a61-ef5e-4c27-be2b-502d99909d99" />
