-- Generic jobs queue. The lease-and-fence mechanism and the dedupe identity
-- live in JobStore; this table is its storage.
--
-- user_id is nullable on purpose: a scheduled or system job has no owning
-- learner. job_key is the dedupe identity — two enqueues with the same key
-- collapse to one row.
--
-- Portable across H2 (tests) and Postgres.

CREATE TABLE jobs (
    id               UUID PRIMARY KEY,
    user_id          UUID,
    job_key          TEXT NOT NULL,
    job_type         TEXT,
    status           TEXT NOT NULL,
    attempt          INT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMP,
    last_error       TEXT,
    result_card_ids  TEXT NOT NULL DEFAULT '',
    outcome          TEXT,
    payload          TEXT,
    created_at       TIMESTAMP NOT NULL,
    UNIQUE (job_key)
);

CREATE INDEX jobs_claim_idx ON jobs (status, created_at);
