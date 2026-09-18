-- Operations: queue and schedule health.
--
-- Plain SQL over the tables that already exist: jobs, schedules. No SDK, no new
-- table, no window functions, so the same script runs on H2 in tests and on
-- Postgres in production. Every row labels one fact in column "stage" with its
-- value in "metric".
--
-- This is the answer to "what is stuck?" without a dashboard or a separate admin
-- service: the queue and the schedule cursor are queries. The reference systems
-- put this behind a client or a UI. The facts are the same.
--
-- These stages cover the generic queue mechanics. A product adds its own
-- quality stages (outcomes, rejection counts) over its own columns.
--
-- Keep a semicolon out of every comment: the report is executed by splitting on
-- ";", so one in prose would cut a statement in half.
--
-- An empty database answers 0.

-- Jobs waiting for a worker.
SELECT 'jobs_pending' AS stage, CAST(COUNT(*) AS DOUBLE PRECISION) AS metric
FROM jobs WHERE status = 'PENDING'
UNION ALL
-- Waiting jobs older than an hour: a stuck queue, not a busy one.
-- Not claimed: the threshold is a policy, not a fact. Edit the interval here
-- when the product's expectation changes.
SELECT 'jobs_pending_older_than_1h', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM jobs WHERE status = 'PENDING' AND created_at <= CURRENT_TIMESTAMP - INTERVAL '1' HOUR
UNION ALL
-- Jobs a worker currently owns.
SELECT 'jobs_running', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM jobs WHERE status = 'RUNNING'
UNION ALL
-- Running jobs whose lease already passed: the holder stopped renewing, so the
-- next claim reclaims them. This is the "stuck" signal.
-- Not claimed: a stale lease is not a failure. The work is retried, not lost.
SELECT 'jobs_stale_lease', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM jobs WHERE status = 'RUNNING' AND lease_expires_at <= CURRENT_TIMESTAMP
UNION ALL
-- Jobs claimed more than once, including a reclaim after a crash.
-- Not claimed: this is not a failure count. Eventually-successful jobs appear too.
SELECT 'jobs_retried', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM jobs WHERE attempt > 1
UNION ALL
-- Jobs in the terminal FAILED state.
SELECT 'jobs_terminal_failed', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM jobs WHERE status = 'FAILED'
UNION ALL
-- Jobs that reached a terminal success.
SELECT 'jobs_terminal_succeeded', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM jobs WHERE status = 'SUCCEEDED'
UNION ALL
-- Enabled schedules.
SELECT 'schedules_enabled', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM schedules WHERE enabled = TRUE
UNION ALL
-- Disabled schedules are kept, never fired.
SELECT 'schedules_disabled', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM schedules WHERE enabled = FALSE
UNION ALL
-- Enabled schedules whose cursor has passed: the next tick enqueues them
-- (exactly one instance wins the compare-and-set).
SELECT 'schedules_due', CAST(COUNT(*) AS DOUBLE PRECISION)
FROM schedules WHERE enabled = TRUE AND next_run_at <= CURRENT_TIMESTAMP;
