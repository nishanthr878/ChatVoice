---
tags: [observability, lgtm, spring-boot-4, docker, grafana, tempo, loki, debugging]
project: ChatVoice / VA Order Support Agent
status: logs-and-traces-working, metrics-not-built, correlation-blocked
---

# LGTM Observability Stack

Grafana's LGTM stack (**L**oki, **G**rafana, **T**empo, **M**imir/Prometheus) added to the existing Kafka/Postgres/Spring/Flask Docker Compose setup. Built as a real portfolio piece, not a demo shortcut — the smaller "just log LLM calls to a Postgres table" approach was deliberately abandoned in favor of this once demo timing pressure was lifted.

**Current state:** Logs (Loki+Promtail+Grafana) — ✅ working, confirmed live.
Traces (Tempo) — ✅ working, confirmed live.
Metrics (Prometheus/Mimir) — ⛔ not built, explicitly deprioritized.
Log↔trace correlation — 🟡 blocked on a real architectural gap (see bottom).

---

## Why this over the simple alternative

Considered first: a small `llm_call_log` Postgres table + a `LoggingLlmClient` decorator wrapping the real `LlmClient`, logging every prompt/response. Fast, would've unblocked same-night bug-hunting.

Chosen instead: the full LGTM stack, once it was confirmed the demo deadline wasn't actually forcing the smaller option. Reasoning — this project already has a genuinely interesting multi-service trace story (Kafka message → Spring handler chain → Groq HTTP call → Postgres write → dynamic LLM-driven branch), and "I stood up Loki/Tempo/Grafana on a real distributed system" is a stronger, more current portfolio line than a bespoke logging table.

---

## Architecture

```
Browser widget
     │ HTTP POST
     ▼
Spring orchestrator ──── Kafka (conversation-events) ──── ConversationEventConsumer
     │  (HTTP span             (⚠ NOT traced — manual                │
     │   auto-instrumented)     ConcurrentMessageListenerContainer,   │
     │                          not @KafkaListener — see bottom)      │
     │                                                                 │
     ▼                                                                 ▼
 stdout logs ──► Promtail ──► Loki ──► Grafana                  Groq / Postgres / Flask
                                  ▲        (untraced business logic happens here)
 OTLP traces ─────────────────► Tempo ──┘
```

---

## docker-compose.yml additions

```yaml
services:
  # ... existing postgres, kafka, kafka-topic-init, kafka-ui, flask-orders, orchestrator ...

  loki:
    image: grafana/loki:3.0.0
    container_name: agent-platform-loki
    ports:
      - "3100:3100"
    command: -config.file=/etc/loki/local-config.yaml
    volumes:
      - loki_data:/loki

  promtail:
    image: grafana/promtail:3.0.0
    container_name: agent-platform-promtail
    volumes:
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
      - /var/run/docker.sock:/var/run/docker.sock
      - ./promtail-config.yaml:/etc/promtail/config.yaml
    command: -config.file=/etc/promtail/config.yaml
    depends_on:
      - loki

  grafana:
    image: grafana/grafana:11.0.0
    container_name: agent-platform-grafana
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Admin
    volumes:
      - grafana_data:/var/lib/grafana
    depends_on:
      - loki

  tempo:
    image: grafana/tempo:2.5.0
    container_name: agent-platform-tempo
    command: -config.file=/etc/tempo/tempo.yaml
    volumes:
      - ./tempo-config.yaml:/etc/tempo/tempo.yaml
      - tempo_data:/var/tempo
    ports:
      - "3200:3200"   # Tempo query API
      - "4317:4317"   # OTLP gRPC receiver
      - "4318:4318"   # OTLP HTTP receiver — this is the one Spring actually uses

volumes:
  pg_data:
  loki_data:
  grafana_data:
  tempo_data:
```

**Note (unresolved, pre-existing, unrelated to LGTM):** the single-broker Kafka container intermittently reports `unhealthy` on a fresh `docker compose up`, blocking anything with `depends_on: kafka: condition: service_healthy`. Workaround used repeatedly: `docker compose up -d kafka` alone, wait, then bring up the rest. Worth investigating properly at some point — likely a KRaft single-node startup timing issue under Docker Desktop/WSL2.

---

## promtail-config.yaml

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: docker
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 5s
    relabel_configs:
      - source_labels: ['__meta_docker_container_name']
        regex: '/(.*)'
        target_label: 'container'
```

Uses Docker **service discovery** against the Docker socket, not a raw filesystem log-path mount. This mattered: the more commonly-tutorialized approach (mounting `/var/lib/docker/containers` and having Promtail read files directly) is Linux-path-specific and was a real worry under Docker Desktop on Windows — but the socket-based `docker_sd_configs` approach worked cleanly on the first attempt, no fix needed.

---

## tempo-config.yaml

```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
        http:

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces

compactor:
  compaction:
    block_retention: 24h
```

---

## build.gradle additions

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
```

> ⚠️ Do **not** add `io.opentelemetry:opentelemetry-exporter-otlp` manually alongside these. Boot 4's `spring-boot-starter-opentelemetry` already bundles OTLP export for metrics and traces — adding the raw exporter dependency separately was tried first and is associated with a real, documented Spring Boot team bug (`NoSuchBeanDefinitionException: No qualifying bean of type OpenTelemetry`) when the starter isn't also present. Use the single starter, nothing else.

---

## application.yml — final, working config

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9094}
    consumer:
      group-id: conversation-state-consumer
      auto-offset-reset: earliest

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/agent_platform
    username: agent
    password: agent

  ai:
    openai:
      api-key: ${GROQ_API_KEY}
      base-url: https://api.groq.com/openai/v1
      chat:
        options:
          model: openai/gpt-oss-20b

order-service:
  base-url: ${ORDER_SERVICE_BASE_URL:http://localhost:5001}

management:
  endpoints:
    web:
      exposure:
        include: env, configprops
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://tempo:4318/v1/traces   # NOTE: port 4318 (HTTP), NOT 4317 (gRPC)
    resource-attributes:
      service.name: va-orchestrator
  otlp:
    metrics:
      export:
        enabled: false   # no metrics backend built yet — this silences the auto-enabled exporter
```

**`management:` must be a top-level key, a sibling of `spring:` — NOT nested inside it.** This was the very first bug (see below): indenting `management:` as a child of `spring:` is syntactically valid YAML, so nothing errors, it just silently does nothing.

---

## The five real Spring Boot 4 bugs, in the order found

This is the actually reusable part of this note — every one of these cost real debugging time, and every one was confirmed with direct evidence (not guessed) before moving to the next.

### 1. YAML nesting — `management:` indented under `spring:`
Silent failure, no error anywhere, since an unrecognized nested YAML key just becomes unused data. Fix: keep `management:` at column 0, a sibling of `spring:`, not a child.

### 2. Boot 3 vs Boot 4 property path for the OTLP endpoint
- Boot 3 (what most tutorials/blog posts still show): `management.otlp.tracing.endpoint`
- **Boot 4 (correct, confirmed via Spring's own current docs)**: `management.opentelemetry.tracing.export.otlp.endpoint`

Trap: plenty of current-looking search results still document the Boot 3 path. Always cross-check against something explicitly dated/titled for Boot 4.

### 3. `endpoint` vs `endpoints` typo
`management.endpoint.web.exposure.include` (singular) is a *real*, different Actuator namespace (per-endpoint config, e.g. `management.endpoint.health.show-details`) — not an error, just silently the wrong property. Correct: `management.endpoints.web.exposure.include` (plural) — controls which endpoints are exposed at all.

### 4. Missing starter dependency
Manually assembling `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` (the Boot-3-era pattern most tutorials show) is not equivalent to Boot 4's single `spring-boot-starter-opentelemetry`. Confirmed via a live Spring Boot team GitHub issue: omitting the starter can leave the `OpenTelemetry` SDK bean itself unconstructed — nothing crashes, exports just silently do nothing.

### 5. Wrong OTLP port + missing path — the actual final fix
- Wrong (what was tried first): `http://tempo:4317` — no path.
- **Correct**: `http://tempo:4318/v1/traces`

Port `4317` is Tempo's **gRPC-only** receiver. `spring-boot-starter-opentelemetry`'s default transport is **HTTP/protobuf**, which needs port `4318` and the OTLP-spec-required `/v1/traces` path. Sending HTTP to a gRPC-only port fails at a low enough protocol level that it doesn't surface as a normal, loggable application error — hence the long silent-failure chase.

**Diagnostic technique that actually cracked bugs #2–4**, worth reusing next time something Boot-4-related silently does nothing:
```bash
# Expose actuator endpoints, then ask Spring directly what properties it loaded
curl http://localhost:8080/actuator/env | grep -i "otlp\|opentelemetry"

# Confirm a dependency actually resolved, and at what version
./gradlew dependencies --configuration runtimeClasspath | grep -i "spring-boot-starter-opentelemetry"

# Check the receiving side's own logs for any ingestion event, not just query traffic
docker compose logs tempo | grep -v "search_handlers\|handler.go"

# Bypass Grafana's UI entirely — ask Tempo's API directly
curl http://localhost:3200/api/search?tags=
```

---

## Two follow-up fixes applied same night

- **`unknown_service` in traces** → fixed via `management.opentelemetry.resource-attributes.service.name: va-orchestrator`.
- **Recurring `OtlpMeterRegistry ... Failed to publish metrics` warnings** → `spring-boot-starter-opentelemetry` auto-enables metrics export by default, pointed at `localhost:4318` (wrong — that's the container's own loopback, not Tempo, and Tempo can't process metrics anyway). Not a bug in anything built here — an unrequested third telemetry signal with nowhere to go. Silenced via `management.otlp.metrics.export.enabled: false`.

---

## Verified working — real evidence, not assumed

- Grafana Explore → Loki: real live container log lines visible, including a genuine Kafka `NOT_COORDINATOR` rebalance warning.
- Grafana Explore → Tempo: real captured trace for `http post /api/conversations/{conversationId}/messages`, ~1.05s duration (matching real Groq call latency), correct `http.url`/`http.method`/`http.status` span tags, correct `service.name`.

---

## What's still open

### 1. Log ↔ trace correlation — blocked on a real gap, not a config issue
**Plan (standard Grafana pattern, both directions required):**
- Loki data source → *Derived fields* → regex-extract a trace ID from log text → internal link to Tempo.
- Tempo data source → *Trace to logs* → point at Loki, configure a small time-shift window (e.g. `-2s`/`+2s`) and tag mapping (`service.name` → Loki's `container` label).

**Blocker found:** checked the prerequisite (Spring auto-injects `[service,traceId,spanId]` into log lines once tracing is active) — it's present but **empty** specifically for `ConversationEventConsumer`'s Kafka-consumer-thread log lines. Meaning: the one HTTP span captured is real, but none of the actual business logic (GraphExecutor dispatch, LLM calls, Postgres writes — all of which happen on the Kafka consumer thread, not the HTTP thread) is traced at all.

**Likely cause (researched, not yet independently confirmed):** multiple current sources confirm `spring-boot-starter-opentelemetry` includes automatic Kafka producer/consumer tracing with context propagation via message headers — but specifically scoped to `@KafkaListener`-annotated methods. `ConversationEventConsumer` uses a manually configured `ConcurrentMessageListenerContainer`, not the `@KafkaListener` annotation (a design decision from earlier in the project). Plausible explanation for why auto-instrumentation isn't attaching, not yet proven.

**Next real step:** either switch to `@KafkaListener` and see if the trace context starts propagating, or manually propagate trace context through Kafka message headers if staying with the manual container approach.

### 2. Metrics (Prometheus/Mimir)
Not built. Explicitly deprioritized — logs + traces cover the actual current debugging need; metrics/dashboards are a genuine but separate future addition.

### 3. Deployment-time log visibility (Hetzner)
Not yet addressed. `docker compose logs` only shows what's in a container's live buffer — doesn't persist across container removal/host reboot on its own. Needs a real decision before relying on it in production: a log volume, or accept `docker compose logs -f` live-tailing as the only view.