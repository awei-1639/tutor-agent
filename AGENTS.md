# Repository Guidelines

## Project Structure & Module Organization

- `backend/` contains the Java 21 Spring Boot API, domain modules, Flyway migrations (`src/main/resources/db/migration`), and JUnit tests under `src/test/java`.
- `frontend/` contains the React 18 + TypeScript + Vite SPA; browser tests live in `frontend/e2e`.
- `graph_data/` stores knowledge-graph seed data; `evals/` stores retrieval, routing, and citation evaluation assets.
- `docs/` contains architecture, operations, API, security, and evaluation guidance. `scripts/` contains data and operations utilities.

## Build, Test, and Development Commands

From the repository root, start dependencies with `docker compose --env-file .env up -d`.

```powershell
cd backend; mvn test                 # unit tests
cd backend; mvn verify               # tests plus JaCoCo verification/reporting
cd backend; mvn spring-boot:run      # local API on port 8180
cd frontend; npm install             # install locked frontend dependencies
cd frontend; npm run dev             # Vite development server
cd frontend; npm run lint; npm run lint:types; npm run build
cd frontend; npm run e2e             # Playwright browser tests
```

Integration tests requiring Docker can be run with `mvn test -DrunIntegrationTests=true -Dtest='*IT'`.
On this Windows + WSL setup, run them inside WSL so Testcontainers can reach Docker: `wsl -d Ubuntu -- bash scripts/verify-interview-integration-wsl.sh`; this helper runs the Docker integration test without a Surefire fork to avoid WSL docker-java shutdown delays.

## Coding Style & Naming Conventions

Use four spaces and Java conventions in `backend`: `PascalCase` types, `camelCase` methods/fields, immutable records for API contracts, and package names rooted at `com.tutor`. Keep LLM calls behind `LlmGateway`; enforce purpose-specific timeouts, token bounds, and deterministic fallbacks at service boundaries. Use two-space TypeScript/TSX formatting, `PascalCase` React components, and `camelCase` hooks/helpers. Run ESLint and TypeScript checks before submitting frontend changes.

## Testing Guidelines

Name Java tests `*Test` and integration tests `*IT`; colocate them with the module they cover. Add regression tests for failure, cancellation, authorization, token-budget, and idempotency paths—not only happy paths. Frontend end-to-end specs belong in `frontend/e2e` and should avoid dependence on real external LLM responses.

## Commit & Pull Request Guidelines

Follow the existing Conventional Commit style (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`), with a concise imperative subject. PRs should explain the behavior change, list verification commands, identify migrations/configuration changes, and include screenshots or recordings for UI changes. Call out API, security, PII, token-cost, and fallback impacts explicitly.

## Security & Configuration

Copy `.env.example` to `.env`; never commit secrets, real resumes, tokens, or production logs. Production requires explicit JWT, resume-encryption, database, and LLM keys, disables development login and `/internal/*`, and should run behind HTTPS. New endpoints must preserve authentication, user scoping, CSRF protection, request-size limits, and safe error messages.
