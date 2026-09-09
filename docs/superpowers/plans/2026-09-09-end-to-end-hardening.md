# Knowledge Platform End-to-End Hardening Plan

## Goal

Close the final operational and security gaps after Plans 1–5: background ingestion must not exhaust online retrieval resources, failures must be observable without logging sensitive content, retrieval behavior must have repeatable Chinese evaluation fixtures, and local operators must be able to start, diagnose, back up and restore the platform.

## Scope and constraints

- Keep MySQL as the publication truth and keep the existing controlled-degradation matrix.
- Do not log questions, document text, vectors, cookies, tokens, signatures or secrets.
- Metrics may use only bounded tags such as operation, stage, outcome and degradation mode; never tenant, user, document, request or exception messages.
- Limit ingestion before a database lease is claimed. Rejected wake-ups remain recoverable through the due-task scanner.
- Preserve the manually triggered publication workflow.
- Infrastructure tests that require the full local stack are documented as operator gates; unit and contract tests remain runnable without Docker or external models.

## Task 1: Bounded ingestion bulkhead

- Add a validated `max-concurrent-tasks` ingestion property with a conservative default of 1.
- Add a fair semaphore-based ingestion bulkhead.
- Acquire the permit before claiming a database task and always release it in `finally`.
- Add concurrency tests proving excess work is rejected without claiming a lease and permits recover after exceptions.

## Task 2: Low-cardinality observability

- Add the Prometheus Micrometer registry so the already exposed actuator endpoint is real.
- Add a `KnowledgeMetrics` adapter for retrieval duration/outcome/degradation, ingestion duration/outcome/stage, and ingestion-bulkhead rejection.
- Instrument `HybridRetrievalService` and `IngestionWorker` without placing sensitive values in tags.
- Add registry tests that assert metric names/tags and explicitly reject high-cardinality identity tags.

## Task 3: Fault and security regression matrix

- Extend retrieval tests for each supported failure combination, final publication-pointer filtering and search-log failure isolation.
- Add internal API tests for cross-tenant identity tampering, nonce replay/fail-closed behavior and malformed signatures.
- Add prompt-injection evidence tests at the Agent tool boundary: evidence remains labelled untrusted, structured citations remain server generated, and no-evidence output stays fixed.

## Task 4: Chinese retrieval evaluation kit

- Add a versioned JSONL golden-set template covering refund, logistics, invoice, product and deliberate no-answer questions.
- Add a local evaluation script that calls the signed/internal endpoint through a supplied runner, calculates Recall@5, first-hit rate, refusal accuracy and unsupported-answer rate, and emits no raw question/text to logs.
- Document how to replace placeholder document IDs after importing the tenant's real policies and how to establish release thresholds.

## Task 5: Runtime limits and operations

- Add restart policies, CPU/memory limits and log rotation to local Compose services.
- Add a complete startup/health/fault-injection checklist for MySQL, Redis, RabbitMQ, MinIO, OCR, Elasticsearch, Milvus, Ollama, Reranker, Knowledge Service, Agent, admin web and desktop.
- Add MySQL, MinIO, Elasticsearch and Milvus backup/restore instructions, with MySQL restored first and indexes treated as rebuildable derivatives.
- Add an acceptance traceability matrix mapping every design criterion to an automated test or a documented operator check.

## Verification gates

1. `mvn test` passes for Knowledge Service.
2. OCR service tests pass.
3. Admin lint, typecheck, unit tests, production build and Playwright flows pass.
4. Agent full Maven test suite passes.
5. Desktop full unit suite, typecheck and production build pass.
6. `docker compose config` validates with placeholder secrets supplied through process environment.
7. `git diff --check` and secret scans pass in every changed repository.

