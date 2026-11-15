package com.example.server.core

import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

/**
 * The real-Postgres path.
 *
 * Every other test runs on H2 in PostgreSQL mode, which is fast but is not the
 * production engine. This one proves the claims H2 cannot:
 *
 * - the Flyway migrations apply to real Postgres;
 * - the operations report is genuinely portable (its `INTERVAL` and
 *   `CURRENT_TIMESTAMP` expressions, not just its syntax);
 * - the queue's compare-and-set gives exactly one owner under real concurrent
 *   transactions;
 * - the scheduler's compare-and-set lets exactly one instance enqueue.
 *
 * It is skipped unless `TEST_POSTGRES_URL` is set, so the fast suite stays
 * infrastructure-free. Run it against a container:
 *
 * ```
 * docker run -d --name tpl-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=boilerplate \
 *   -p 5433:5432 postgres:16-alpine
 * TEST_POSTGRES_URL=jdbc:postgresql://localhost:5433/boilerplate \
 *   TEST_POSTGRES_USER=postgres TEST_POSTGRES_PASSWORD=postgres \
 *   ./gradlew :server:test --tests '*PostgresIntegrationTest'
 * ```
 *
 * The suite shares one database, so every test starts from empty queue and
 * schedule tables. Without that reset, "a fresh database" is not fresh and a
 * concurrent-claim test can hand two winners two different rows.
 */
class PostgresIntegrationTest {

    private val url: String = System.getenv("TEST_POSTGRES_URL").orEmpty()

    private lateinit var pool: DataSource

    @BeforeEach
    fun setUp() {
        assumeTrue(url.isNotBlank(), "TEST_POSTGRES_URL is not set")
        pool = openCoreDataSource(
            jdbcUrl = url,
            user = System.getenv("TEST_POSTGRES_USER").orEmpty().ifBlank { "postgres" },
            password = System.getenv("TEST_POSTGRES_PASSWORD").orEmpty(),
        )
        // Reaching here at all means Flyway migrated a real Postgres.
        pool.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use {
                it.execute(
                    "TRUNCATE TABLE jobs, schedules, sessions, identities, owner_aliases, " +
                        "email_codes, webhook_events, entitlements, credit_grants, " +
                        "credit_reservations, credit_ledger, workspaces, workspace_members, " +
                        "workspace_invites, api_keys, audit_events, email_suppressions, ai_usage_log",
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        if (::pool.isInitialized) (pool as? AutoCloseable)?.close()
    }

    @Test
    fun migrationsAndTheOperationsReportRunOnRealPostgres() {
        val metrics = OperationsReport.run(pool)
        listOf(
            "jobs_pending", "jobs_pending_older_than_1h", "jobs_running",
            "jobs_stale_lease", "jobs_retried", "jobs_terminal_failed",
            "jobs_terminal_succeeded", "schedules_enabled", "schedules_disabled",
            "schedules_due",
        ).forEach { stage ->
            assertEquals(0.0, metrics.getValue(stage), 0.0001, "$stage must be 0 on a fresh database")
        }
    }

    @Test
    fun concurrentClaimsYieldExactlyOneOwnerOnRealPostgres() = runBlocking<Unit> {
        val store = SqlJobStore()
        store.enqueue("pg-race-${UUID.randomUUID()}", "example/echo", "{}")

        val now = System.currentTimeMillis()
        val winners = listOf(
            async { store.claimPending(now, 30_000) },
            async { store.claimPending(now, 30_000) },
        ).awaitAll().filterNotNull()

        assertEquals(1, winners.size, "exactly one concurrent claim may win")
        assertEquals(1, winners.single().attempt)
    }

    @Test
    fun twoSchedulerInstancesEnqueueOnceOnRealPostgres() = runBlocking<Unit> {
        val schedules = SqlScheduleStore()
        val jobs = SqlJobStore()
        val nowMs = System.currentTimeMillis()
        schedules.upsert(
            ScheduleDefinition(
                name = "pg-sched-${UUID.randomUUID()}",
                spec = ScheduleSpec(Cadence.DAILY, hour = 3, minute = 0),
                zoneId = "UTC",
                jobType = "example/echo",
                payload = "{}",
                nextRunAtEpochMs = nowMs - 3_600_000L,
            ),
        )

        // Both instances read the same due row before either advances it.
        val snapshot = schedules.due(nowMs)
        val racyView = object : ScheduleStore by schedules {
            override suspend fun due(nowEpochMs: Long, limit: Int): List<ScheduleDefinition> = snapshot
        }
        val a = Scheduler(racyView, jobs) { nowMs }
        val b = Scheduler(racyView, jobs) { nowMs }

        val decisions = listOf(async { a.tick() }, async { b.tick() }).awaitAll().flatten()
        val enqueued = decisions.filterIsInstance<SchedulerDecision.Enqueued>()
        val lost = decisions.filterIsInstance<SchedulerDecision.LostRace>()

        assertEquals(1, enqueued.size, "exactly one instance may enqueue")
        assertEquals(1, lost.size, "the other must be told it lost the compare-and-set")
        assertEquals(
            enqueued.single().periodKey,
            jobs.job(enqueued.single().jobId!!)!!.key,
            "the dedupe key is the period key",
        )
    }

    @Test
    fun aHeartbeatRenewalSurvivesAReopenOnRealPostgres() = runBlocking<Unit> {
        val store = SqlJobStore()
        store.enqueue("pg-hb-${UUID.randomUUID()}", "example/echo", "{}")
        val held = store.claimPending(0, 1_000)!!

        store.renewLease(held.id, attempt = 1, nowEpochMs = 500, leaseMs = 1_000)

        // A fresh store over the same database sees the renewal.
        assertEquals(null, SqlJobStore().claimPending(1_001, 1_000), "the renewal is durable")
        assertIs<JobRecord>(SqlJobStore().job(held.id))
    }

    @Test
    fun concurrentSignInsForSameSubjectCreateExactlyOneOwnerOnRealPostgres() = runBlocking<Unit> {
        val store = SqlIdentityStore()
        val identity = VerifiedIdentity(
            issuer = "https://appleid.apple.com",
            subject = "pg-sub-${UUID.randomUUID()}",
            email = "relay@privaterelay.appleid.com",
            emailVerified = true,
        )

        val outcomes = (1..4).map { idx ->
            async {
                store.authenticateOrCreate(identity, { "owner-$idx-${UUID.randomUUID()}" }, now = 1_760_000_000_000L)
            }
        }.awaitAll()

        assertEquals(1, outcomes.count { it.created }, "exactly one concurrent first sign-in creates the binding")
        assertEquals(1, outcomes.map { it.ownerId }.toSet().size, "every caller resolves to the same canonical owner")
    }

    @Test
    fun concurrentCreditSpendsNeverOverdrawBalanceOnRealPostgres() = runBlocking<Unit> {
        val credits = SqlCreditBalanceStore()
        val owner = "pg-credit-${UUID.randomUUID()}"
        val now = 1_760_000_000_000L
        credits.grant(ownerId = owner, sourceRef = "pack-1", units = 100L, expiresAt = now + 86_400_000L, now = now)

        // Four concurrent callers each attempt to spend 60 units from a 100-unit balance.
        val results = (1..4).map {
            async { credits.trySpend(ownerId = owner, units = 60L, now = now + 10L) }
        }.awaitAll()

        assertEquals(1, results.count { it }, "only one 60-unit spend fits inside a 100-unit balance")
        assertEquals(40L, credits.available(owner, now + 10L))
    }

    @Test
    fun concurrentOtpSubmissionsVerifyExactlyOnceOnRealPostgres() = runBlocking<Unit> {
        val store = SqlEmailCodeStore()
        val service = EmailCodeService(store, clock = { 1_760_000_000_000L })
        val email = "pg-otp-${UUID.randomUUID()}@acme.test"
        val issued = service.issue(email)

        val checks = (1..4).map {
            async { service.verify(email, issued.code) }
        }.awaitAll()

        assertEquals(1, checks.count { it == EmailCodeCheck.Verified }, "a 6-digit OTP code may be consumed only once")
    }

    @Test
    fun concurrentCreditReservationsNeverOverbookBalanceOnRealPostgres() = runBlocking<Unit> {
        val credits = SqlCreditBalanceStore()
        val owner = "pg-hold-${UUID.randomUUID()}"
        val now = 1_760_000_000_000L
        credits.grant(ownerId = owner, sourceRef = "pack-hold-$owner", units = 100L, expiresAt = now + 86_400_000L, now = now)

        val holds = (1..4).map {
            async { credits.reserve(ownerId = owner, units = 60L, ttlMs = 60_000L, now = now + 10L) }
        }.awaitAll().filterNotNull()

        assertEquals(1, holds.size, "only one 60-unit hold fits inside a 100-unit balance")
        assertEquals(40L, credits.available(owner, now + 10L))
        assertEquals(
            CreditCommitOutcome.COMMITTED,
            credits.commitReservation(holds.single().id, actualUnits = 25L, now = now + 20L),
        )
        assertEquals(75L, credits.available(owner, now + 20L))
    }

    @Test
    fun concurrentPersonalWorkspaceCreationYieldsSingleWorkspaceOnRealPostgres() = runBlocking<Unit> {
        val workspaces = SqlWorkspaceStore()
        val owner = "pg-ws-${UUID.randomUUID()}"

        val created = (1..4).map {
            async { workspaces.ensurePersonalWorkspace(ownerId = owner, now = 1_760_000_000_000L) }
        }.awaitAll()

        assertEquals(1, created.map { it.id }.toSet().size, "every concurrent login resolves to one personal workspace")
        assertEquals(WorkspaceRole.OWNER, workspaces.memberRole(created.first().id, owner))
    }

    @Test
    fun refreshReuseOutsideGraceRevokesTokenFamilyOnRealPostgres() = runBlocking<Unit> {
        var now = 1_760_000_000_000L
        val sessions = SessionService(
            store = SqlSessionStore(),
            ttl = TokenTtl(reuseGraceMs = 5_000L),
            clock = { now },
        )
        val owner = "pg-sess-${UUID.randomUUID()}"
        val first = sessions.issue(ownerId = owner, deviceId = "laptop")
        val otherDevice = sessions.issue(ownerId = owner, deviceId = "phone")
        val rotated = sessions.refresh(first.refreshToken)!!

        now += 6_000L
        assertEquals(null, sessions.refresh(first.refreshToken))
        assertEquals(null, sessions.authenticate(rotated.accessToken), "stolen family is revoked")
        assertEquals(owner, sessions.authenticate(otherDevice.accessToken)?.ownerId, "other device survives")
    }

    @Test
    fun sqlAiUsageAggregateAndAuditPseudonymizationExecuteCleanlyOnRealPostgres() = runBlocking<Unit> {
        val aiUsage = SqlAiUsageStore()
        val auditLogs = SqlAuditLogStore()
        val owner = "pg-agg-${UUID.randomUUID()}"
        val other = "pg-other-${UUID.randomUUID()}"

        assertEquals(AiUsageSummary(0, 0L, 0L, 0L), aiUsage.summarizeForOwner(owner))
        aiUsage.record(AiUsageRecord(ownerId = owner, model = "gpt-4o-mini", inputTokens = 1_200, outputTokens = 300, costMicros = 400L, latencyMs = 180L, outcome = "ok", createdAt = 1_000L))
        aiUsage.record(AiUsageRecord(ownerId = owner, model = "gpt-4o-mini", inputTokens = 800, outputTokens = 200, costMicros = 250L, latencyMs = 120L, outcome = "ok", createdAt = 5_000L))
        assertEquals(AiUsageSummary(2, 2_000L, 500L, 650L), aiUsage.summarizeForOwner(owner))
        assertEquals(AiUsageSummary(1, 800L, 200L, 250L), aiUsage.summarizeForOwner(owner, sinceEpochMs = 2_500L))

        val wsId = "ws-pg-${UUID.randomUUID()}"
        auditLogs.append(AuditEventRow(workspaceId = wsId, ownerId = owner, actorId = owner, action = "key.created", ipAddress = "198.51.100.1", deviceId = "d1", createdAt = 1_000L))
        auditLogs.append(AuditEventRow(workspaceId = wsId, ownerId = owner, actorId = other, action = "member.invited", ipAddress = "198.51.100.2", deviceId = "d2", createdAt = 2_000L))
        auditLogs.append(AuditEventRow(workspaceId = wsId, ownerId = other, actorId = owner, action = "doc.shared", ipAddress = "198.51.100.3", deviceId = "d3", createdAt = 3_000L))

        assertEquals(3, auditLogs.pseudonymizeForOwner(owner))
        assertEquals(0, auditLogs.pseudonymizeForOwner(owner))
        val wsEvents = auditLogs.forWorkspace(wsId)
        assertEquals(3, wsEvents.size)
        wsEvents.forEach { row ->
            assertEquals(null, row.ipAddress)
            assertEquals(null, row.deviceId)
        }
    }
}
