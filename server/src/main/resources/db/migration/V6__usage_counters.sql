-- Durable spend counters for the core spend gate (backend boilerplate, slice 5).
--
-- One row per subject per period per kind: subject_id is the caller the
-- product charges (device id, user id, api key), period_key is the counter's
-- reset window (the UTC civil day by default), and kind is the metered thing
-- (for example "interpret"). count is the units spent this period. The primary
-- key makes the row the unit of contention, so a charge can lock it and decide
-- against the cap without a second table.
--
-- Portable across H2 (tests) and Postgres (S0).

CREATE TABLE usage_counters (
    subject_id TEXT NOT NULL,
    period_key TEXT NOT NULL,
    kind       TEXT NOT NULL,
    count      INT NOT NULL,
    PRIMARY KEY (subject_id, period_key, kind)
);
