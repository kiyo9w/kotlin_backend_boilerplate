# kotlin_backend_boilerplate

A Kotlin + Ktor backend starter extracted from a working product, not a
diagram. It carries the conventions a new service needs on day one and the
generic machinery every product reuses.

## What is here

| Path | Job |
| --- | --- |
| `server/src/main/kotlin/com/example/server/core/` | Generic, product-agnostic machinery. |
| `server/src/main/kotlin/com/example/server/example/` | The smallest example domain to copy. |
| `server/src/main/kotlin/com/example/server/Application.kt` | Product wiring: config, errors, routes, workers. |
| `deploy/` | Docker, compose, nginx, PM2, and a self-verifying deploy script. |
| `docs/conventions.md` | **Read this first.** Where things go. |
| `tools/new-product.sh` | Stamp a new product from this template. |
| `.env.example` / `.env.production.example` | The documented key pair. |

## Run it

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
./gradlew :server:test          # the whole suite, H2, no infrastructure

./gradlew :server:run           # local-only memory mode (blank DATABASE_URL)

DATABASE_URL=jdbc:postgresql://localhost:5432/example \
  ./gradlew :server:run         # against Postgres

scripts/check.sh                # run JVM tests, build the server image, and check /health
```

The check includes generated queue invariants and the worker-recovery replay, then starts a uniquely named temporary container and removes it on exit. Deployment is verified by running the image, not by matching strings in deployment files.

## Start a new product

```bash
tools/new-product.sh --name "My Product" --package com.acme.myproduct /path/to/new-product
cd /path/to/new-product && ./gradlew :server:test
```

The generator rewrites the project name, package, database name, and
environment-key prefix, and refuses to overwrite a non-empty directory.

## The conventions in one breath

Requests pass through typed bound configuration, declarative route policies (`PUBLIC`, `AUTHENTICATED`, `ADMIN`), bounded request-body checks, and RFC 7807 `ProblemDetail` error responses with stable machine codes. Sessions persist only `SHA-256` token digests with `RFC 9700` refresh-token family reuse revocation, active device listing, and step-up `sudo` windows. External sign-in covers OIDC JWKS ID-token verification (`RS256` and `ES256`), dynamic Apple `.p8` `client_secret` signing and `/auth/revoke`, hashed six-digit email codes, `(issuer, subject)` identity bindings with guest `owner_aliases`, day-one `workspaces` (including automatic 1:1 personal workspaces and sole-owner guards), and role-clamped workspace API keys with a 24-hour rotation grace window.

Commercial and operational plumbing lives behind small store and client interfaces: subscription entitlements with atomic webhook deduplication, raw payload archiving, and monotonic timestamp fencing, integer-unit credit grants with earliest-expiry FIFO drain and two-phase `reserve` / `commit` / `release` holds, and StoreKit 2 `x5c` JWS verification. Outbound mail runs through Resend with `RFC 8058` one-click unsubscribe tokens and stream-aware bounce and complaint suppressions, paired with PostHog Cloud EU stateless-anonymous analytics, zero-prompt AI token and cost accounting, Cloudflare Turnstile verification, weighted token-bucket rate limiting, append-only `audit_events` with support-impersonation provenance and privacy-erasure pseudonymization, and a retry-safe `5.1.1(v)` account deletion cascade.

Domain state and background work share the same Postgres database: owner- or workspace-scoped versioned documents with compare-and-set, operation-id idempotency, a lease-and-fence job queue that doubles as a transactional outbox (`enqueueInTransaction`) alongside a durable scheduler, an all-or-nothing batch gate, a per-subject spend gate, SSE streaming with bounded prompt budgets, fail-closed webhook signature verification (`StandardWebhooksVerifier` and `HmacWebhookVerifier`), structured `X-Request-Id` logs, `/health`, and `/ready`. Every convention ships with a test.

Full detail: [`docs/conventions.md`](docs/conventions.md). Other docs:
[`docs/topology.md`](docs/topology.md), [`docs/config.md`](docs/config.md),
[`docs/deploy.md`](docs/deploy.md).
