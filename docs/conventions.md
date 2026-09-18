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

## Persistence

- Postgres in production, H2 in tests. Flyway migrations are the only schema
  path. Exposed maps rows; Hikari pools. A blank `DATABASE_URL` is the explicit
  local-only memory mode — never a production durable path.

## Tests

Two layers:

- **Fast, no infrastructure.** Everything under `core/` and the example API
  tests run on H2 (Postgres mode) and in-process HTTP. `./gradlew test`.
- **Real database (optional).** Point `DATABASE_URL` at a real Postgres and run
  the same suite; the SQL tests use whatever the environment provides.

A new convention ships with the test that enforces it. A convention without a
test is a suggestion.

## The seams

| Seam | Generic | Product supplies |
| --- | --- | --- |
| `ProductCatalog` | resolving product-specific context | the product's catalog |
| `JobHandler` | routing a `job_type` to work | the handlers |
| `BatchGate<T>` | refuse-the-batch rule | the product's gate |
| `ModelGateway` | one model-call contract, keys server-side | the provider client |
| `ReadinessProbe` | readiness question | what "ready" means |
| `RouteGuard` | policy enforcement | what "authenticated" means |
