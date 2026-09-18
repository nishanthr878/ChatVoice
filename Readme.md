# ChatVoice — Conversational Order-Support Agent Platform

A from-scratch, deterministic-core conversational agent platform, built to understand and demonstrate the architecture underneath enterprise agent platforms like Sierra and Decagon — not a framework wrapper, not a tutorial project.

**What it does:** a chat widget where users can check order status and start returns, backed by a real distributed system: Kafka-ordered conversation events, a Spring Boot orchestrator with a bounded, LLM-guided state machine, a real external order service, and a self-hosted observability stack.

**What it's for:** demonstrating real backend/distributed-systems engineering — Kafka partition semantics, idempotency, hexagonal architecture, optimistic concurrency, containerized deployment — using an LLM-powered conversational agent as the vehicle, not the point. See `docs/context-doc.md` for the full reasoning.

## Architecture, in one paragraph

Every user message publishes to Kafka, keyed by conversation ID for strict per-conversation ordering. A consumer dispatches it through `GraphExecutor`, a bounded state-machine loop that chains several node transitions within one message when nothing needs further user input, and stops the moment it does. Business logic is a coded, deterministic graph — the LLM only ever handles narrow language tasks (extraction, classification, phrasing), never decides consequential business rules (see `docs/decisions-log.md` D7). A separate `ConversationState` layer (currently live on the `check_order_status` flow) tracks what the conversation is actually *about* — active intent, focused entity, known entities — distinct from which execution step is running, enabling real coreference resolution ("what about the other one?") that a node-name-only state model structurally could not support.

Full architecture doc: `docs/architecture-doc.md`. Full, precise decision history (including real bugs found and fixed, with root causes): `docs/decisions-log.md`.

## Stack

Java 21 · Spring Boot 4.1 · Spring AI (Groq) · Kafka (KRaft) · Postgres · Flask + SQLite (order service) · Docker Compose · Loki/Promtail/Grafana/Tempo (observability)

## Running it

```bash
# .env (repo root) needs: GROQ_API_KEY=your-key

docker compose up -d --build
```

This brings up: Postgres, Kafka (+ topic init, + Kafka UI on :8090), the Flask order service, the Spring orchestrator, and the full observability stack.

- Chat widget: `http://localhost:8080/index.html`
- Grafana (logs + traces): `http://localhost:3000` (anonymous admin access)
- Kafka UI: `http://localhost:8090`

Useful checks while it's running:
```bash
docker compose ps
docker exec -it agent-platform-postgres psql -U agent -d agent_platform -c '\dt'
docker exec -it agent-platform-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group conversation-state-consumer
```

## What's real vs. what's known-open

This project is built and documented with the same standard applied throughout: verify with real evidence, log honestly what isn't done. Currently working and live-verified: order lookup, multi-item questions, mid-conversation order switching (including pronoun-style references like "the other one"), return processing with a real deterministic approval threshold, full observability (logs + traces). Both `check_order_status` and `process_return` are fully migrated to the `ConversationState` model. Currently open, tracked precisely in `docs/decisions-log.md`: a Kafka message-redelivery duplicate-turn edge case; trace propagation doesn't yet cross the Kafka consumer boundary. A telephony voice layer (Twilio + Deepgram) is under active development but **not yet confirmed working end-to-end** — nothing here is hidden, the decision log is the actual, honest project history, not a cleaned-up summary.

## Project docs

- `docs/context-doc.md` — what this is, why it's shaped this way, current scope and known gaps
- `docs/architecture-doc.md` — system diagram and layer responsibilities, as actually built
- `docs/decisions-log.md` — the real, detailed decision and debugging history — every architectural choice, every bug found, every root cause, in order
- `docs/layer2-conversation-state-design.md` — the original persistence schema design

<img width="5679" height="5634" alt="diagram" src="https://github.com/user-attachments/assets/536c4a61-ef5e-4c27-be2b-502d99909d99" /> 
