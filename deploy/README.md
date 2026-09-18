# Deploy

One target, one person. The server is a stateless JVM behind nginx, with
Postgres beside it. Pick either Docker Compose or PM2 — not both on one host.

## What is here

| File | Job |
| --- | --- |
| `Dockerfile` | Multi-stage build of `:server:installDist` into a JRE runtime image. |
| `docker-compose.yml` | Server + Postgres, named volume, healthchecks, loopback-only port. |
| `nginx/qoloa.conf` | TLS termination, proxy headers, and the correlation-id passthrough. |
| `ecosystem.config.js` | PM2 process config for a host that runs the distribution directly. |
| `deploy.sh` | Build, start, and **verify readiness**; a deploy that is not ready fails. |

## Docker Compose

```bash
cp .env.example .env      # then fill POSTGRES_PASSWORD and any credentials
deploy/deploy.sh
```

- The database lives in the named volume `qoloa-postgres`. `docker compose down`
  keeps it; `down -v` erases it and asks first.
- The server binds `127.0.0.1:8081`; nginx is the only public listener.
- `deploy.sh` waits for `/ready`, so a container that cannot reach Postgres
  fails the deploy instead of silently serving errors.

## PM2

```bash
cd mobile && ./gradlew :server:installDist
pm2 start deploy/ecosystem.config.js && pm2 save
```

PM2 runs the installed `bin/server` launcher as a single process. Scale out with
another host behind nginx — the durable scheduler is single-flight across
instances, so running several on one host buys nothing.

## Secrets

No credential lives in this directory. `deploy/.env` is gitignored and holds the
values `docker-compose.yml` references; PM2 and nginx take theirs from the host
environment or a read-only mount. The full key set is `mobile/.env.example` and
`mobile/.env.production.example`.

## Health

- `/health` — liveness: the process answers.
- `/ready` — readiness: the product probe is satisfied (the database is
  reachable). The load balancer should route on this one.
