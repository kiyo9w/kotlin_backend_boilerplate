-- Production SaaS hardening tables and columns:
--   * sessions: token-family reuse tracking (RFC 9700), step-up sudo window, device context
--   * webhook_events & entitlements: raw replay payload, outcome, cancel_at_period_end, source_timestamp
--   * credit_reservations & credit_ledger: two-phase AI holds (reserve -> commit/release) and audit journal
--   * workspaces, workspace_members, workspace_invites: day-one multi-tenancy with personal default workspace
--   * api_keys: prefixed SHA-256 keys with scopes, rotation grace window, and throttled last_used_at
--   * audit_events: append-only security, support-impersonation (RFC 8693), and Visa CE 3.0 evidence log
--
-- Portable across H2 (tests) and Postgres.

ALTER TABLE sessions ADD COLUMN family_id TEXT;
UPDATE sessions SET family_id = id WHERE family_id IS NULL;
ALTER TABLE sessions ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE sessions ADD COLUMN rotated_at BIGINT;
ALTER TABLE sessions ADD COLUMN sudo_until BIGINT;
ALTER TABLE sessions ADD COLUMN ip_address TEXT;
ALTER TABLE sessions ADD COLUMN user_agent TEXT;
CREATE INDEX sessions_family_idx ON sessions (family_id);

ALTER TABLE webhook_events ADD COLUMN raw_payload TEXT;
ALTER TABLE webhook_events ADD COLUMN outcome TEXT;

ALTER TABLE entitlements ADD COLUMN cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE entitlements ADD COLUMN source_timestamp BIGINT;

CREATE TABLE credit_reservations (
    id             TEXT   PRIMARY KEY,
    owner_id       TEXT   NOT NULL,
    reserved_units BIGINT NOT NULL,
    actual_units   BIGINT,
    status         TEXT   NOT NULL,
    expires_at     BIGINT NOT NULL,
    created_at     BIGINT NOT NULL,
    settled_at     BIGINT
);

CREATE INDEX credit_reservations_owner_status_idx ON credit_reservations (owner_id, status, expires_at);

CREATE TABLE credit_ledger (
    id           TEXT   PRIMARY KEY,
    owner_id     TEXT   NOT NULL,
    entry_type   TEXT   NOT NULL,
    delta_units  BIGINT NOT NULL,
    reference_id TEXT   NOT NULL,
    created_at   BIGINT NOT NULL
);

CREATE INDEX credit_ledger_owner_time_idx ON credit_ledger (owner_id, created_at);

CREATE TABLE workspaces (
    id                  TEXT   PRIMARY KEY,
    slug                TEXT   NOT NULL UNIQUE,
    name                TEXT   NOT NULL,
    personal_owner_id   TEXT   UNIQUE,
    plan_code           TEXT   NOT NULL,
    billing_customer_id TEXT   UNIQUE,
    created_at          BIGINT NOT NULL,
    updated_at          BIGINT NOT NULL
);

CREATE TABLE workspace_members (
    workspace_id TEXT   NOT NULL,
    owner_id     TEXT   NOT NULL,
    role         TEXT   NOT NULL,
    created_at   BIGINT NOT NULL,
    PRIMARY KEY (workspace_id, owner_id)
);

CREATE INDEX workspace_members_owner_idx ON workspace_members (owner_id);

CREATE TABLE workspace_invites (
    id            TEXT   PRIMARY KEY,
    workspace_id  TEXT   NOT NULL,
    invited_email TEXT   NOT NULL,
    role          TEXT   NOT NULL,
    token_hash    TEXT   NOT NULL UNIQUE,
    invited_by    TEXT   NOT NULL,
    expires_at    BIGINT NOT NULL,
    accepted_at   BIGINT,
    revoked_at    BIGINT,
    created_at    BIGINT NOT NULL
);

CREATE INDEX workspace_invites_workspace_idx ON workspace_invites (workspace_id);

CREATE TABLE api_keys (
    id                  TEXT   PRIMARY KEY,
    workspace_id        TEXT   NOT NULL,
    created_by_owner_id TEXT   NOT NULL,
    name                TEXT   NOT NULL,
    key_prefix          TEXT   NOT NULL,
    key_hash            TEXT   NOT NULL UNIQUE,
    scopes_csv          TEXT   NOT NULL,
    expires_at          BIGINT,
    grace_expires_at    BIGINT,
    revoked_at          BIGINT,
    last_used_at        BIGINT,
    created_at          BIGINT NOT NULL
);

CREATE INDEX api_keys_workspace_idx ON api_keys (workspace_id);

CREATE TABLE audit_events (
    id              TEXT   PRIMARY KEY,
    workspace_id    TEXT,
    owner_id        TEXT,
    actor_id        TEXT   NOT NULL,
    impersonator_id TEXT,
    action          TEXT   NOT NULL,
    target_type     TEXT,
    target_id       TEXT,
    ip_address      TEXT,
    device_id       TEXT,
    user_agent      TEXT,
    metadata_json   TEXT,
    created_at      BIGINT NOT NULL
);

CREATE INDEX audit_events_workspace_time_idx ON audit_events (workspace_id, created_at);
CREATE INDEX audit_events_owner_time_idx ON audit_events (owner_id, created_at);
