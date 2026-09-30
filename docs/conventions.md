# Conventions

One page. A new contributor reads this and knows where things go.

## The module anatomy

One domain, one folder, always the same files:

```text
<domain>/
  <Domain>Routes.kt    # transport only: parse, delegate, shape the response
  <Domain>Service.kt   # the rules: what is true, what is allowed
  <Domain>Store.kt     # the interface: persistence; no SQL in the service
  <Domain>Dto.kt       # the wire types, @Serializable
```

Two sentences are the whole architecture:

- **A route may not contain a business rule.** It parses the request, calls the
  service, and shapes the response.
- **A service may not import a database type.** Persistence goes through the
  store interface.

The generic machinery every product reuses lives in `core/`; a product's own
domain lives in `<domain>/` and its wiring in `Application.kt`. The example
under `example/` is the smallest working domain.

## Naming

| Thing | Rule | Example |
| --- | --- | --- |
| Migration | `V<n>__snake_case.sql`, monotonic, never edited after landing | `V3__add_bookmarks.sql` |
| Endpoint | `/v1/<plural-resource>` | `POST /v1/bookmarks` |
| Job type | `<domain>/<verb>` | `content/daily` |
| Config object | One per concern, defaults in code | `ModelConfig` |
| Environment key | `SCREAMING_SNAKE`, product-prefixed for product keys | `DATABASE_URL` |

## Configuration and secrets

- Every environment key is bound once in `core/ServerConfig.kt`. Nothing calls
  `System.getenv` at the point of use.
- `.env.example` and `.env.production.example` stay in sync with
  `ConfigKey.all`; `ConfigExampleTest` fails when a bound key is missing from
  either file. That test is what stops the pair from rotting.
- **A secret never enters the repository and never enters a client build.** The
  production secret lives in the host's secret store and reaches the process as
  an environment variable. `deploy/.env` is gitignored.

## Errors

Every failure answers with the RFC 7807 `ProblemDetail` envelope and a stable
machine `code` (`UNAUTHORIZED`, `NOT_FOUND`, `VALIDATION_ERROR`, …). A client
branches on the code, never on the message. Throw `ApiException` from a route
or service; `StatusPages` renders it. Unhandled exceptions answer a fixed
`Internal server error` and never leak internals.

## Webhooks

Intake verifies before it trusts. The verifier receives the **raw** body (a
signature covers the bytes the provider sent; re-encoding a parsed body changes
them) and returns a `VerifiedWebhook` only when the signature proves against it.
`null` means refused, and the route answers a refusal — an unverified payload is
never decoded and applied. `HmacWebhookVerifier` is the shared-secret shape
every provider uses; a blank secret wires `FailClosedWebhooks`, so an endpoint
that cannot verify refuses everything. The delivery identity (`VerifiedWebhook.eventId`)
is the dedupe key, so a replay collapses onto the same job.

## Route policy

Every endpoint declares `PUBLIC`, `AUTHENTICATED`, or `ADMIN` at the route:

```kotlin
routing {
    installRouteGuard(myGuard)
    get("/health", RoutePolicy.PUBLIC) { ... }
    post("/v1/bookmarks", RoutePolicy.AUTHENTICATED) { ... }
}
```

The guard runs **before** the handler. A refused request never reaches product
code. A new endpoint cannot forget the check.

## Request bodies

Every request body is bounded by `RequestBodyLimit` (`MAX_REQUEST_BYTES` in
`Application.kt`). A declared `Content-Length` over the bound is refused before
the handler runs; a chunked or lengthless body is cut while it streams, so an
ignored JSON field cannot smuggle an oversized payload past a typed `receive`.

The refusal must always answer `413 REQUEST_TOO_LARGE`. It cannot be trusted
to surface as `PayloadTooLargeException`: the limiter proxies the body through
a writer coroutine, and whether that exception reaches `StatusPages` directly
or wrapped inside `BadRequestException` by the content converter is a
scheduling race (observed on Ktor 3.5.1, roughly one request in five under
parallel load). That is why the `BadRequestException` handler walks the cause
chain instead of matching the thrown type - the same convention applies to any
new handler that could mask a refusal inside another exception.

## Jobs and schedules

- Work that leaves the request goes through the queue: `JobStore.enqueue` with a
  dedupe `key`, a `job_type`, and an opaque `payload`.
- A worker claims with a lease and fences every write by attempt number; a stale
  attempt throws `StaleClaimException` and never overwrites the live one.
- A quality gate either ships its kept items or refuses the whole batch
  (`BatchGate.decide`); it never ships a partial batch.
- Recurring work is a durable schedule, never an in-process timer. The
  scheduler enqueues into the queue and never executes work itself. Cadences are
  daily / weekly / monthly / yearly at a civil time in a named zone.

## Observability

- Every call carries `X-Request-Id` (inbound when safe, generated otherwise),
  echoed on the response, and written into one structured `key=value` log line.
- `/health` is liveness. `/ready` is readiness: it answers 503 when the
  product's `ReadinessProbe` is not satisfied.

## Reports

Facts about background work are **plain SQL resources**, not a dashboard or an
admin API. `server/src/main/resources/analytics/operations.sql` reports queue and
schedule health (`jobs_pending`, `jobs_running`, `jobs_stale_lease`,
`jobs_retried`, terminal states, `schedules_due`). The same script runs on H2 in
tests and on Postgres in production, and `OperationsSqlTest` keeps it from
drifting from the schema.

Add a product-specific stage (outcomes, rejection counts) to your own report over
your own columns rather than growing a shared one. Keep a semicolon out of every
comment: the report is executed by splitting on `;`.

## Deliberate non-choices

These are decisions, not omissions. Each has a trigger that would reverse it;
until the trigger fires, adding the thing is over-engineering.

| Not adopted | Why | Trigger that reverses it |
| --- | --- | --- |
| A scheduler library (Quartz, db-scheduler, JobRunr) | One `schedules` table plus a compare-and-set cursor covers daily/weekly/monthly/yearly, and it shares the queue's table and semantics. JobRunr is also LGPLv3 | Recurring work must survive multi-instance deployment in a way the cursor cannot express, or cron expressions become a real requirement |
| A message broker (Redis, RabbitMQ, Kafka) | The Postgres queue is durable, transactional with the business write, and one fewer system to run | Measured throughput the Postgres queue cannot carry |
| A server DI framework | Explicit composition in `Application.kt` is readable and needs no reflection | The object graph outgrows a screen of code |
| A `:contract` module by default | The server can depend on the product's shared module directly; splitting early adds a module for no benefit | The server starts dragging client-only dependencies (UI, device APIs) into its build |
| An OpenAPI/codegen pipeline | Kotlin clients share the DTO module directly, so there is nothing to generate | A non-Kotlin client (for example Flutter) appears |
| Testcontainers by default | The H2 fast path runs everywhere with no infrastructure; the production claims are proven by the opt-in Postgres suite | The Postgres suite needs to run in CI on every push |
| A second deployable worker | The worker is in-process and the queue is single-flight; scaling out means another host, not another service | Worker load must scale independently of HTTP load |
| Event sourcing, CQRS, a generic repository layer | The product domains are small and readable; these add indirection before it pays | A domain's read and write models genuinely diverge |

The queue itself is the same decision: a hand-rolled lease-and-fence store
(one table, compare-and-set, heartbeat) rather than a library. It is small,
tested on both engines, and shares the product's `jobs` table, which a library
would not.

## Persistence

- Postgres in production, H2 in tests. Flyway migrations are the only schema
  path. Exposed maps rows; Hikari pools. A blank `DATABASE_URL` is the explicit
  local-only memory mode — never a production durable path.

## Tests

Two layers:

- **Fast, no infrastructure.** Everything under `core/` and the example API
  tests run on H2 (Postgres mode) and in-process HTTP. `./gradlew test`.
- **Real Postgres (opt-in).** `PostgresIntegrationTest` proves what H2 cannot:
  the migrations apply, the report's SQL is portable, and the queue/scheduler
  compare-and-set holds under real concurrent transactions. It is skipped unless
  `TEST_POSTGRES_URL` is set:

  ```bash
  docker run -d --name tpl-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=boilerplate \
    -p 5433:5432 postgres:16-alpine
  TEST_POSTGRES_URL=jdbc:postgresql://localhost:5433/boilerplate \
    TEST_POSTGRES_USER=postgres TEST_POSTGRES_PASSWORD=postgres \
    ./gradlew :server:test --tests '*PostgresIntegrationTest'
  ```

  Integration tests share one database, so each one resets the tables it uses.
  Without that, "a fresh database" is not fresh and a concurrency test can hand
  two winners two different rows.

A new convention ships with the test that enforces it. A convention without a
test is a suggestion.

**Write every test body as `Unit`.** A Kotlin test whose last expression returns
a value (an `assertIs`, a `runBlocking` ending in one) infers a non-`Unit`
return type, and JUnit5 **silently ignores non-void test methods** — the suite
stays green while the test never runs. Use `runBlocking<Unit> { ... }` and count
the test cases in the report, not just the build status.

## The seams

| Seam | Generic | Product supplies |
| --- | --- | --- |
| `ProductCatalog` | resolving product-specific context | the product's catalog |
| `JobHandler` | routing a `job_type` to work | the handlers |
| `BatchGate<T>` | refuse-the-batch rule | the product's gate |
| `ModelGateway` | one model-call contract, keys server-side | the prompt and schema (an OpenAI-compatible implementation ships here) |
| `WebhookVerifier` | verify-before-trust intake, fail closed | the provider's secret and signature scheme |
| `ReadinessProbe` | readiness question | what "ready" means |
| `RouteGuard` | policy enforcement | what "authenticated" means |
| `SessionStore` | session persistence; token issue, rotation, and revocation live in `SessionService` | how a caller bootstraps to an owner id (device id, install key, verified provider subject) |
| `DocumentStore` | owner-scoped versioned documents with compare-and-set | the document schema, what a version means, and how large a payload may grow |
| `OperationStore` | operation-id idempotency: first-writer-wins, attach, conflict, terminal states | the operation kinds and what the payload hash covers |
| `StreamingModelGateway` | content deltas from the model; `respondSse` writes the wire | the prompt and the frame payloads |
| `SpendGate` | per-subject per-period caps; memory and SQL implementations ship | the kinds, the caps, and how a subject is proven to exist |
