# Deploy

See `deploy/README.md` for the operational page. In short:

- **Compose:** `cp deploy/.env.example deploy/.env`, fill the secrets, then
  `deploy/deploy.sh`. It builds, starts, waits for `/ready`, and fails the
  deploy when the server cannot reach its database.
- **PM2:** `./gradlew :server:installDist` then
  `pm2 start deploy/ecosystem.config.js`.
- **Image only:** `docker build -f deploy/Dockerfile -t <name> server`.

The named volume keeps the database across `down`; `down -v` erases it.

`/health` is liveness; `/ready` is readiness. Route the load balancer on
`/ready`.
