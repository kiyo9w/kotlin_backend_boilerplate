-- Commercial SaaS backbone tables:
--   * email_codes: hashed 6-digit OTP codes with attempt caps and single-use consumption
--   * webhook_events: provider + event_id deduplication recorded atomically with mutations
--   * entitlements: first-party subscription ledger fed by verified webhooks
--   * credit_grants: prepaid / allowance credit lots with optional expiry (FIFO drain)
--   * email_suppressions: hard-bounce, complaint, and marketing-unsubscribe blocklist
--   * ai_usage_log: token and cost accounting with zero prompt or completion text
--
-- Portable across H2 (tests) and Postgres.

CREATE TABLE email_codes (
    id           TEXT   PRIMARY KEY,
    email        TEXT   NOT NULL,
    purpose      TEXT   NOT NULL,
    code_hash    TEXT   NOT NULL,
    attempts     INT    NOT NULL,
    max_attempts INT    NOT NULL,
    expires_at   BIGINT NOT NULL,
    consumed_at  BIGINT,
    created_at   BIGINT NOT NULL
);

CREATE INDEX email_codes_lookup_idx ON email_codes (email, purpose);

CREATE TABLE webhook_events (
    provider    TEXT   NOT NULL,
    event_id    TEXT   NOT NULL,
    received_at BIGINT NOT NULL,
    PRIMARY KEY (provider, event_id)
);

CREATE TABLE entitlements (
    provider           TEXT   NOT NULL,
    external_sub_id    TEXT   NOT NULL,
    owner_id           TEXT   NOT NULL,
    plan_code          TEXT   NOT NULL,
    status             TEXT   NOT NULL,
    quantity           INT    NOT NULL,
    current_period_end BIGINT NOT NULL,
    grace_until        BIGINT,
    last_event_id      TEXT   NOT NULL,
    updated_at         BIGINT NOT NULL,
    PRIMARY KEY (provider, external_sub_id)
);

CREATE INDEX entitlements_owner_idx ON entitlements (owner_id);

CREATE TABLE credit_grants (
    id              TEXT   PRIMARY KEY,
    owner_id        TEXT   NOT NULL,
    source_ref      TEXT   NOT NULL UNIQUE,
    granted_units   BIGINT NOT NULL,
    remaining_units BIGINT NOT NULL,
    expires_at      BIGINT,
    created_at      BIGINT NOT NULL
);

CREATE INDEX credit_grants_owner_expiry_idx ON credit_grants (owner_id, expires_at);

CREATE TABLE email_suppressions (
    email           TEXT   PRIMARY KEY,
    reason          TEXT   NOT NULL,
    source_event_id TEXT,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL
);

CREATE TABLE ai_usage_log (
    id            TEXT   PRIMARY KEY,
    owner_id      TEXT,
    model         TEXT   NOT NULL,
    input_tokens  INT    NOT NULL,
    output_tokens INT    NOT NULL,
    cost_micros   BIGINT NOT NULL,
    latency_ms    BIGINT NOT NULL,
    outcome       TEXT   NOT NULL,
    created_at    BIGINT NOT NULL
);

CREATE INDEX ai_usage_owner_time_idx ON ai_usage_log (owner_id, created_at);
