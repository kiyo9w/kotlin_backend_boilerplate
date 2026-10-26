#!/usr/bin/env bash
# Builds the server image, starts it and waits for /health. Run it before merging
# changes to the server or deploy files.
set -euo pipefail
cd "$(dirname "$0")/.."

name=boilerplate-check
port=${CHECK_PORT:-18080}
docker build -f deploy/Dockerfile -t boilerplate-server:check .
docker rm -f "$name" >/dev/null 2>&1 || true
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$name" -p "$port:8080" boilerplate-server:check >/dev/null
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$port/health" >/dev/null 2>&1; then
    echo "server healthy after ${i}s"
    exit 0
  fi
  sleep 1
done
echo "server never became healthy" >&2
docker logs "$name" >&2 || true
exit 1
