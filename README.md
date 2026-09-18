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
```

## Start a new product

```bash
tools/new-product.sh --name "My Product" --package com.acme.myproduct /path/to/new-product
cd /path/to/new-product && ./gradlew :server:test
```

The generator rewrites the project name, package, database name, and
environment-key prefix, and refuses to overwrite a non-empty directory.

## The conventions in one breath

Typed bound configuration; the RFC 7807 `ProblemDetail` envelope with stable
codes; declarative route policy (`PUBLIC` / `AUTHENTICATED` / `ADMIN`) enforced
before the handler; a durable lease-and-fence job queue; a durable scheduler
that enqueues into that queue; a batch gate that refuses rather than ships a
partial batch; a bounded prompt budget; webhook intake that verifies the raw
body and refuses by default; structured logs with a request id; `/health` and
`/ready`. Every
one has a test.

Full detail: [`docs/conventions.md`](docs/conventions.md). Other docs:
[`docs/topology.md`](docs/topology.md), [`docs/config.md`](docs/config.md),
[`docs/deploy.md`](docs/deploy.md).

## Provenance

Extracted 2026-09-18 from a production Kotlin/Ktor service. Mechanisms that came
from other studied systems are noted in the code where they live.
