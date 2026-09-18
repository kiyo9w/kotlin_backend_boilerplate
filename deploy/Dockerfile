# the service server — production image.
#
# Build from the mobile/ directory so the Gradle build can see shared/ and
# server/ together:
#
#   docker build -f deploy/Dockerfile -t qoloa-server mobile
#
# The runtime image carries only the installed distribution, not the source or
# the Gradle cache.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

# Run as a non-root user: the process needs no write access to the image.
RUN useradd --system --create-home --uid 10001 qoloa
COPY --from=build --chown=qoloa:qoloa /app/server/build/install/server/ ./
USER qoloa

ENV PORT=8080
EXPOSE 8080

# Liveness only: readiness is the load balancer's concern and needs the
# database. A container that cannot answer /health is restarted.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["./bin/server"]
