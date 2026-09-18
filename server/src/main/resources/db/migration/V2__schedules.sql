-- Durable schedules for the core scheduler (backend boilerplate, slice 3).
--
-- The scheduler enqueues into the existing jobs table; it never executes work.
-- next_run_at is the cursor the tick reads and compare-and-sets, which is what
-- makes a tick single-flight across instances without a broker or advisory lock.
--
-- cadence + hour/minute + optional day fields describe a civil firing time in
-- zone_id. A product owns job_type and payload.
--
-- Portable across H2 (tests) and Postgres (S0).

CREATE TABLE schedules (
    name          TEXT PRIMARY KEY,
    cadence       TEXT NOT NULL,
    zone_id       TEXT NOT NULL,
    hour_of_day   INT NOT NULL,
    minute_of_hour INT NOT NULL,
    day_of_week   TEXT,
    day_of_month  INT,
    month_of_year TEXT,
    job_type      TEXT NOT NULL,
    payload       TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at   TIMESTAMP NOT NULL
);

-- The tick's only query is "enabled rows whose cursor is due, oldest first".
CREATE INDEX schedules_due_idx ON schedules (enabled, next_run_at);
