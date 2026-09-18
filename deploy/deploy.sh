#!/usr/bin/env bash
#
# the service server — compose deploy that verifies itself.
#
#   deploy/deploy.sh            build, start, wait for readiness, report
#   deploy/deploy.sh --no-build start what is already built and verify
#
# Exit codes:
#   0  the new build answered /ready
#   1  a precondition is missing (docker, compose file, secret)
#   2  the build or the start failed
#   3  the server never became ready inside the timeout
#
# Readiness, not liveness: a container that started but cannot reach Postgres
# is a failed deploy, not a successful one.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f docker-compose.yml)
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-90}"
SERVER_PORT="${SERVER_PORT:-8081}"
BUILD=1
[[ "${1:-}" == "--no-build" ]] && BUILD=0

fail() { printf 'deploy: %s\n' "$1" >&2; exit "${2:-1}"; }

command -v docker >/dev/null 2>&1 || fail "docker is not on PATH" 1
[[ -f docker-compose.yml ]] || fail "docker-compose.yml is missing" 1
[[ -f .env ]] || fail "deploy/.env is missing (POSTGRES_PASSWORD and credentials live there)" 1

if [[ "$BUILD" == "1" ]]; then
  echo "deploy: building images"
  "${COMPOSE[@]}" build || fail "image build failed" 2
fi

echo "deploy: starting services"
"${COMPOSE[@]}" up -d || fail "compose up failed" 2

echo "deploy: waiting up to ${READY_TIMEOUT_SECONDS}s for /ready"
deadline=$(( $(date +%s) + READY_TIMEOUT_SECONDS ))
until curl -fsS "http://127.0.0.1:${SERVER_PORT}/ready" >/dev/null 2>&1; do
  if (( $(date +%s) >= deadline )); then
    echo "deploy: last 40 log lines" >&2
    "${COMPOSE[@]}" logs --tail=40 server >&2 || true
    fail "server did not become ready in ${READY_TIMEOUT_SECONDS}s" 3
  fi
  sleep 2
done

echo "deploy: server is ready on 127.0.0.1:${SERVER_PORT}"
curl -fsS "http://127.0.0.1:${SERVER_PORT}/health" >/dev/null && echo "deploy: /health ok"
