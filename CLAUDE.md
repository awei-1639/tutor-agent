# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

CareerPilot / Tutor Agent — an AI learning & job-search coach. Java 21 + Spring Boot 3.4 backend (`com.tutor`, modular monolith), React 18 + Vite frontend, PostgreSQL 16 (pgvector + pg_trgm) + Neo4j 5 for hybrid retrieval, DeepSeek (chat) + SiliconFlow (embed/rerank) as LLM providers.

Documentation is written in Chinese; code comments are mixed Chinese/English. `AGENTS.md` and `docs/contributing.md` hold the repo's own contribution rules — read them before large changes.

## Commands

No Maven wrapper; use the system `mvn` (3.9+) and Java 21. Node 20+.

```bash
# Dependencies (repo root)
docker compose --env-file .env up -d postgres neo4j

# Backend (in backend/)
mvn spring-boot:run                              # API on http://localhost:8180
mvn test                                         # unit tests
mvn verify                                       # tests + JaCoCo report + coverage gate
mvn -Dtest=ChatServiceTest test                  # single test class
mvn -Dtest=ChatServiceTest#methodName test        # single test method
mvn test -DrunIntegrationTests=true -Dtest='*IT' # Docker/Testcontainers integration tests

# Frontend (in frontend/)
npm install
npm run dev                                      # http://localhost:5173
npm run lint && npm run lint:types && npm run build
npm run e2e                                      # Playwright; boots its own dev server on 4173
npx playwright test e2e/security.spec.ts         # single spec

# Combined verification (repo root, PowerShell)
.\scripts\verify.ps1 [-SkipFrontend|-SkipBackend]
```

`*IT` tests are gated by `@EnabledIfSystemProperty(named = "runIntegrationTests")`, so they silently no-op without the flag. On this Windows + WSL setup run them inside WSL so Testcontainers can reach Docker: `wsl -d Ubuntu -- bash scripts/verify-interview-integration-wsl.sh`.

`mvn verify` enforces JaCoCo **40% line / 30% branch** on the whole bundle (`backend/pom.xml`) — that is the real gate, not the 80% target in the global rules.

Evals run against the live backend with `INTERNAL_ENDPOINTS_ENABLED=true`:

```bash
# One-command runner (WSL): starts postgres+neo4j, idempotently seeds, starts the
# backend, runs the eval, and diffs against the previous results baseline.
bash scripts/eval-local.sh [--retrieval|--citation|--all] [--smoke] [--force-seed]

# Or invoke the underlying scripts directly against an already-running backend:
node evals/run_eval.mjs [--smoke|--ci]      # RAG pipelines: vector_only / fused / fused_rerank / agentic
node evals/run_citation_eval.mjs            # citation faithfulness — burns DeepSeek + judge tokens
node evals/run_interview_score_eval.mjs --input <gold.json> --min-reviewers 2 --ci
```

`eval-local.sh` needs `docker`, `node`, and `java` on PATH (WSL, not Windows Git Bash — the host has no docker). `run_eval.mjs` only exercises retrieval (`/internal/retrieve`); it does NOT go through prompt assembly, so `ContextPlanner`/prompt changes must be validated with `run_citation_eval.mjs` (which goes through `/chat`).

## Configuration

Copy `.env.example` → `.env`. Backend reads plain env vars, so PowerShell needs an explicit import loop (see `docs/local-development.md` §4). Nearly every tunable in `backend/src/main/resources/application.yml` is `${ENV_VAR:default}` — change behavior there, not in code.

Port is **8180**, not 8080. Vite proxies `/api/*` → `http://127.0.0.1:8180` and **strips the `/api` prefix**, so backend paths carry no prefix.

`prod` profile (`application-prod.yml` + `ProductionConfigurationValidator`) fails fast unless `JWT_SECRET`, `RESUME_ENC_KEY`, `POSTGRES_PASSWORD`, `NEO4J_PASSWORD`, `DEEPSEEK_API_KEY`, `SILICONFLOW_API_KEY` are set, and force-disables `/auth/dev-login` and `/internal/*`.

## Architecture

### Module boundaries

`backend/src/main/java/com/tutor/` is organized **by business capability**, not by layer — each of `auth chat context contract expert retrieval memory profile resume plan push interview knowledge eval admin llm guard tool config` owns its controllers, services, and domain logic. Do not add a cross-cutting `common`/`utils` package to dodge a boundary; shared stable types go in `contract`.

`ArchitectureBoundaryTest` (ArchUnit) enforces three rules that will fail the build:
- `retrieval..` must not depend on `chat..`
- `knowledge..` must not depend on `chat..`
- no `*Controller` may depend on `llm..`

### Chat turn orchestration

`ChatController` (SSE) → `ChatService.turn()` coordinates: history/summary load → `ProfileService` snapshot → memory recall → `IntentRouter` + `RoutingPolicy` → retrieval → either `ExpertRunner` (concurrent experts) + `Aggregator`, or direct streaming answer → `CitationGuard` → persist → async post-turn tasks (profile update, episode summarization, `TraceRecorder`).

The SSE event contract is public API: `meta`, `stage`, `clarify`, `citation`, `token` (carries `seq`), `done`, `error`. `citation` carries `sid`, and answers reference evidence as `[S#]`. Changing these events requires a design note per `docs/project-structure.md` §5. Turns are durable — an SSE disconnect cancels the stream via `CancellationToken` but already-persisted work stands, and `/chat/turns/{turnId}` reports status.

### LLM access

Every model call goes through `llm/LlmGateway` (implements `EmbeddingGateway`, `JsonGenerationGateway`, `StreamingGenerationGateway`, `RerankGateway`, `RetrievalJudge`), keyed by the `contract/Purpose` enum (`CHAT ROUTER EXPERT SUMMARY EXTRACT JUDGE EMBED RERANK PLAN`). Purpose drives model routing, timeout, and input/output token bounds — all configured under `llm.*` in `application.yml` — plus `LlmBudgetGuard` (daily + per-turn caps) and `LlmConcurrencyGate`. Structured JSON output goes through `llm/structured/StructuredOutputService` with schemas in `StructuredSchemaRegistry`.

Never call a provider SDK directly from a service; add a purpose or a gateway method instead.

### Retrieval

`retrieval/fusion/FusedRetriever` combines pgvector dense, pg_trgm sparse, and a whitelisted one-hop Neo4j expansion via RRF, with per-source quotas, graph-score decay, and dedup. Rerank runs **only** for resource-recommendation queries (`ResourceQueryClassifier`). Learning-path queries use `retrieval/agentic/AgenticRetriever`, capped server-side at 3 hops — the LLM may only emit a structured stop/rewrite decision, never a raw graph query. All knobs live under `tutor.retrieval.profile.*`; they are experimental and must be re-calibrated against `evals/rag_testset.json` rather than tuned by intuition.

`retrieval/resilience/Neo4jResilience` applies a per-query timeout plus an in-process circuit breaker. While open, graph expansion returns empty results and the request continues on PostgreSQL — but `/readyz` still returns 503 so an unready instance is never treated as healthy. `/healthz` is liveness only.

### Memory

Local episode memory is always the authority and the fallback. Mem0 is opt-in (`MEM0_ENABLED` + per-user consent) and degrades on timeout/circuit-break. Writes and deletes go through a transactional outbox with memory generations and fencing-token leases so a crashed worker's job can be reclaimed; deletes write local tombstones first to stop stale remote results from flowing back.

### Tool calling

Off by default (`CHAT_TOOL_LOOP_ENABLED=false`). `ToolCallLoop` runs a strict-JSON protocol, max 3 steps, over `ToolRegistry` + `ToolExecutor`. Every tool declares a `contract/ToolSpec` with a `SideEffect` level: L0 read-only, L1 internal write, L2 external action requiring user confirmation. L1/L2 claim an idempotency key (`tool_idempotency`) and every call is audited to `tool_calls` with an args digest. Tool results are re-injected into the model as untrusted data.

### Auth

JWT in the HttpOnly `tutor_access` cookie (`AuthInterceptor`) plus CSRF double-submit on authenticated writes (`CsrfInterceptor`), both registered in `WebConfig` on `/**`. `/auth/*`, `/healthz`, `/readyz` skip auth; `/internal/*` requires `INTERNAL_ENDPOINTS_ENABLED` and is loopback-only by default, returning 404 otherwise. Business endpoints must never fall back to `DEV_USER_ID`. New endpoints have to preserve user scoping, request-size limits, and non-leaking error messages.

### Frontend

`src/pages/*` are route pages, `src/components` holds only genuinely cross-page UI, and **all** backend access plus SSE parsing lives in `src/lib/api.ts` — do not re-implement cookie, CSRF, SSE, or error-formatting logic in a page. Markdown is rendered through `src/lib/markdown.ts` (marked + DOMPurify); `frontend/e2e/security.spec.ts` is the regression for that.

## Repository conventions

Flyway migrations in `backend/src/main/resources/db/migration/V{n}__{description}.sql` are **immutable once merged** — `scripts/check-flyway-migration-immutability.sh` fails CI on any non-`A` change to that directory. Always add a new version (currently at V62).

Backend style: 4 spaces, `PascalCase` types, `camelCase` members, records for API contracts and DTOs, constructor injection. Frontend: 2 spaces, `PascalCase` components, `camelCase` hooks/helpers. Tests are `*Test` (unit) / `*IT` (Testcontainers), colocated with the module under `src/test/java/com/tutor/<module>`. Cover failure, cancellation, authorization, token-budget, and idempotency paths, not just happy paths.

Retrieval-affecting changes (chunking, embeddings, RRF params, graph relations/quotas, routing, rerank, multi-hop stop conditions) require an eval run recorded with dataset version, mode, Recall/Hit/MRR, latency, affected slices, and Badcase deltas. Evals must call the real pipeline — never copy ranking logic into eval code.

`experiments/` is throwaway spikes not referenced by production code. `graph_data/` holds reviewable seed JSON; `evals/results/`, `evals/private/`, `.runtime/`, and generated SQL/Cypher are gitignored and should not be committed.

## Directory notes

- `docs/README.md` is the documentation index; `docs/architecture.md` has full request sequence diagrams, `docs/api-reference.md` the endpoint/SSE contract, `docs/operations.md` degradation runbooks, `docs/evaluation.md` the eval gates, `docs/decisions.md` the ADRs (why hybrid retrieval, bounded multi-hop, Mem0-as-enhancement, Neo4j fallback, OSS-for-originals).
- `scripts/README.md` documents each script's side effects. Scripts must never be a runtime dependency of the backend.
- Root-level `src/main/java/com/tutor/tool/` is an empty stray directory, not a source root — the backend lives entirely under `backend/`.
