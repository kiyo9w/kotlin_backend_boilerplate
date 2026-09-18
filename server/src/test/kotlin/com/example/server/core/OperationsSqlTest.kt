package com.example.server.core

import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The operations report is a plain SQL script, `analytics/operations.sql`, over
 * the queue and the schedule cursor. This test seeds an H2 database through the
 * real Flyway migrations, executes every statement, and asserts the facts, so
 * the script cannot drift from the schema unnoticed.
 *
 * Seeded world (relative to now, with hour-scale margins so the clock cannot
 * flake): one waiting job, one healthy running job, one running job whose lease
 * already passed, one succeeded and one FAILED job, and three schedules (two
 * enabled, one of them due, plus one disabled).
 */
class OperationsSqlTest {

    private val hourMs = 3_600_000L

    private val ds = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:core_ops_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (ds as? AutoCloseable)?.close()
    }

    @Test
    fun operationsScriptReportsQueueAndScheduleHealthOnSeededRows() {
        seedWorld()
        val metrics = runOperationsScript()

        listOf(
            "jobs_pending", "jobs_running", "jobs_stale_lease", "jobs_retried",
            "jobs_terminal_failed", "jobs_terminal_succeeded",
            "schedules_enabled", "schedules_disabled", "schedules_due",
        ).forEach { stage ->
            assertNotNull(metrics[stage], "operations must report the $stage fact")
        }

        assertEquals(1.0, metrics.getValue("jobs_pending"), 0.0001)
        assertEquals(2.0, metrics.getValue("jobs_running"), 0.0001)
        assertEquals(1.0, metrics.getValue("jobs_stale_lease"), 0.0001, "only the past lease is stale")
        assertEquals(2.0, metrics.getValue("jobs_retried"), 0.0001, "attempts 2 and 3 are retries")
        assertEquals(1.0, metrics.getValue("jobs_terminal_failed"), 0.0001)
        assertEquals(1.0, metrics.getValue("jobs_terminal_succeeded"), 0.0001)

        assertEquals(2.0, metrics.getValue("schedules_enabled"), 0.0001)
        assertEquals(1.0, metrics.getValue("schedules_disabled"), 0.0001)
        assertEquals(1.0, metrics.getValue("schedules_due"), 0.0001, "only the enabled past cursor is due")
    }

    @Test
    fun operationsScriptIsHonestAboutAnEmptyDatabase() {
        val metrics = runOperationsScript()
        assertTrue(metrics.isNotEmpty(), "the script must still report every fact with no rows")
        metrics.forEach { (stage, value) ->
            assertEquals(0.0, value, 0.0001, "$stage must be 0 with no rows")
        }
    }

    private fun seedWorld() {
        val now = Instant.now()
        transaction {
            var index = 0
            fun job(status: JobState, attempt: Int, lease: Instant? = null) {
                index += 1
                GenericJobs.insert {
                    it[id] = kotlin.uuid.Uuid.random()
                    it[key] = "ops-test-$index"
                    it[jobType] = "example/echo"
                    it[GenericJobs.status] = status.name
                    it[GenericJobs.attempt] = attempt
                    it[leaseExpiresAt] = lease
                    it[result] = ""
                    it[payload] = "{}"
                    it[createdAt] = now
                }
            }

            job(JobState.PENDING, attempt = 0)
            job(JobState.RUNNING, attempt = 2, lease = now.plusMillis(hourMs))
            job(JobState.RUNNING, attempt = 3, lease = now.minusMillis(hourMs))
            job(JobState.SUCCEEDED, attempt = 1)
            job(JobState.FAILED, attempt = 1)

            var scheduleIndex = 0
            fun schedule(enabled: Boolean, nextRunAt: Instant) {
                scheduleIndex += 1
                Schedules.insert {
                    it[name] = "ops-schedule-$scheduleIndex"
                    it[cadence] = Cadence.DAILY.name
                    it[zoneId] = "UTC"
                    it[hour] = 3
                    it[minute] = 0
                    it[jobType] = "example/echo"
                    it[payload] = "{}"
                    it[Schedules.enabled] = enabled
                    it[Schedules.nextRunAt] = nextRunAt
                }
            }

            schedule(enabled = true, nextRunAt = now.minusMillis(hourMs))
            schedule(enabled = true, nextRunAt = now.plusMillis(hourMs))
            schedule(enabled = false, nextRunAt = now.minusMillis(hourMs))
        }
    }

    private fun runOperationsScript(): Map<String, Double> {
        val script = javaClass.classLoader.getResourceAsStream("analytics/operations.sql")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        assertNotNull(script, "analytics/operations.sql is missing from the server classpath")
        // Strip comment lines before splitting on ";", so a stray semicolon in
        // prose cannot silently cut a statement in half.
        val anonymous = script.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
        val statements = anonymous.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(statements.isNotEmpty(), "operations script must contain at least one statement")
        val metrics = LinkedHashMap<String, Double>()
        ds.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                for (sql in statements) {
                    val hasRows = statement.execute(sql)
                    if (!hasRows) continue
                    statement.resultSet.use { rows ->
                        while (rows.next()) {
                            metrics[rows.getString("stage")] = rows.getDouble("metric")
                        }
                    }
                }
            }
        }
        return metrics
    }
}
