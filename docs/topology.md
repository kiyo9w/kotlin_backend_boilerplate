# Topology

## Boxes

```mermaid
flowchart LR
  Client["Client"] -->|"HTTPS /v1/*"| Proxy["nginx"]
  Proxy --> API["Ktor API"]
  API --> DB[("Postgres")]
  API -->|"enqueue"| Jobs[("jobs table")]
  Worker["Worker (in-process)"] -->|"claim / finish"| Jobs
  Scheduler["Scheduler (in-process)"] -->|"enqueue per period"| Jobs
  API -->|"key stays server-side"| Model["Model gateway"]
```

- **The request path** never waits on the model. A request enqueues a job and
  returns `202`; a worker executes it.
- **The worker** is in-process and claims one job per tick from the same
  `jobs` table. The lease-and-fence makes several instances safe.
- **The scheduler** runs in-process, once a minute per instance, and enqueues
  into the same `jobs` table. The compare-and-set on `next_run_at` makes a tick
  single-flight across instances, so extra instances are safe and buy nothing.
- **Postgres** is the only durable store. A blank `DATABASE_URL` is the explicit
  local-only memory mode.

## Request lifecycle

```mermaid
sequenceDiagram
  participant C as Client
  participant A as Ktor API
  participant S as StatusPages
  participant Q as jobs
  participant W as Worker

  C->>A: POST /v1/example/jobs
  A->>A: route policy (AUTHENTICATED) guard
  A->>Q: enqueue(key, job_type, payload)
  A-->>C: 202 { jobId }
  W->>Q: claim with lease
  W->>W: handler
  W->>Q: finish(attempt fence)
  C->>A: GET /v1/example/jobs/{id}
  A-->>C: 200 { status, outcome }
```

A refusal anywhere becomes the ProblemDetail envelope through `StatusPages`.

## Deploy

One host runs Postgres and the API behind nginx, or the API under PM2. See
[`deploy.md`](deploy.md).
