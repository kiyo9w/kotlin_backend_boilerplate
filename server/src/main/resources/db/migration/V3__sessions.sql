-- Bearer sessions. One row is one issued pair: access/refresh lookup by the
-- SHA-256 hash of the raw token, which is never stored. The service resolves,
-- rotates, and revokes against these rows; expiry and revocation are checked
-- by the service, so the columns are facts, not constraints.
--
-- Rotation compare-and-sets on revoked_at: the presented row is revoked and
-- the successor (rotated_from = the old row) is inserted in one transaction,
-- so a replayed refresh token finds a revoked row and is refused.
--
-- Portable across H2 (tests) and Postgres.

CREATE TABLE sessions (
    id                 TEXT PRIMARY KEY,
    owner_id           TEXT NOT NULL,
    device_id          TEXT,
    access_hash        TEXT NOT NULL UNIQUE,
    refresh_hash       TEXT NOT NULL UNIQUE,
    access_expires_at  BIGINT NOT NULL,
    refresh_expires_at BIGINT NOT NULL,
    rotated_from       TEXT,
    revoked_at         BIGINT,
    created_at         BIGINT NOT NULL,
    last_used_at       BIGINT
);

-- Revoke-all reads by owner; the liveness filter rides along on revoked_at.
CREATE INDEX sessions_owner_idx ON sessions (owner_id);
CREATE INDEX sessions_revoked_idx ON sessions (revoked_at);
