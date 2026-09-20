-- Operation-id idempotency registry (OperationStore). One row per owner and
-- operation id, registered first-writer-wins: the composite primary key
-- (owner_id, operation_id) lets exactly one begin insert land, and every later
-- request either attaches to that row or conflicts on a mismatched kind or
-- payload hash.
--
-- state is one of RUNNING, COMPLETE, FAILED, CANCELLED. Completion, failure,
-- and cancellation are transitions from RUNNING only, made atomic by an
-- UPDATE whose WHERE clause carries the RUNNING predicate, so a terminal row
-- is never overwritten. created_at and updated_at are epoch millis.
--
-- Portable across H2 (tests) and Postgres.

CREATE TABLE operations (
    owner_id        TEXT NOT NULL,
    operation_id    TEXT NOT NULL,
    kind            TEXT NOT NULL,
    payload_hash    TEXT NOT NULL,
    state           TEXT NOT NULL,
    result_json     TEXT,
    failure_code    TEXT,
    failure_message TEXT,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    PRIMARY KEY (owner_id, operation_id)
);
