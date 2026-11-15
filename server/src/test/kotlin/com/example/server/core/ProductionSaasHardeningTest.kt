package com.example.server.core

import com.example.server.ApiException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ProductionSaasHardeningTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:hardening_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool(): Unit {
        (pool as? AutoCloseable)?.close()
    }

    @Test
    fun memorySessionsEnforceRfc9700FamilyRevocationSudoAndDeviceRevoke(): Unit = runBlocking {
        verifySessionHardeningContract(MemorySessionStore())
    }

    @Test
    fun sqlSessionsEnforceRfc9700FamilyRevocationSudoAndDeviceRevoke(): Unit = runBlocking {
        verifySessionHardeningContract(SqlSessionStore())
    }

    private suspend fun verifySessionHardeningContract(store: SessionStore) {
        var now = 1_760_000_000_000L
        val service = SessionService(
            store = store,
            ttl = TokenTtl(reuseGraceMs = 10_000L, sudoMs = 60_000L),
            clock = { now },
        )

        val laptopFirst = service.issue(
            ownerId = "owner-1",
            deviceId = "macbook",
            ipAddress = "203.0.113.10",
            userAgent = "Safari/18",
        )
        val phoneSession = service.issue(
            ownerId = "owner-1",
            deviceId = "iphone",
            ipAddress = "203.0.113.11",
            userAgent = "MobileSafari/18",
        )

        val laptopRotated = service.refresh(laptopFirst.refreshToken)
        assertNotNull(laptopRotated)

        // Benign multi-tab retry within reuseGraceMs returns null without killing the child token.
        now += 3_000L
        assertNull(service.refresh(laptopFirst.refreshToken))
        assertNotNull(service.authenticate(laptopRotated.accessToken))

        // Replay outside reuseGraceMs triggers RFC 9700 family revocation on the laptop family only.
        now += 8_000L
        assertNull(service.refresh(laptopFirst.refreshToken))
        assertNull(service.authenticate(laptopRotated.accessToken))
        assertNull(service.refresh(laptopRotated.refreshToken))
        assertNotNull(service.authenticate(phoneSession.accessToken), "other device family stays live")

        // Immediate replay when successor already rotated (s1 -> s2 -> s3, replay s1 at t+1ms) also revokes family.
        val chain1 = service.issue(ownerId = "owner-2", deviceId = "ipad")
        now += 10L
        val chain2 = service.refresh(chain1.refreshToken)!!
        now += 10L
        val chain3 = service.refresh(chain2.refreshToken)!!
        now += 10L
        assertNull(service.refresh(chain1.refreshToken))
        assertNull(service.authenticate(chain3.accessToken))

        // Step-up sudo window is bound to sessionId, not ownerId globally.
        val phoneSubjectBefore = service.authenticate(phoneSession.accessToken)!!
        val phoneSessionId = assertNotNull(phoneSubjectBefore.sessionId)
        assertFalse(phoneSubjectBefore.hasSudoAt(now))
        assertFalse(service.elevateSudo("wrong-owner", phoneSessionId))
        assertTrue(service.elevateSudo("owner-1", phoneSessionId))
        val phoneSubjectElevated = service.authenticate(phoneSession.accessToken)!!
        assertTrue(phoneSubjectElevated.hasSudoAt(now))
        assertFalse(phoneSubjectElevated.hasSudoAt(now + 60_000L))

        // Owner-scoped device revocation refuses cross-owner session IDs.
        assertEquals(1, service.activeSessions("owner-1").size)
        assertFalse(service.revokeSession("intruder", phoneSessionId))
        assertTrue(service.revokeSession("owner-1", phoneSessionId))
        assertEquals(0, service.activeSessions("owner-1").size)
    }

    @Test
    fun requireSudoGuardRejectsNonElevatedSessionAndAcceptsElevatedSession(): Unit = testApplication {
        val sessions = SessionService(MemorySessionStore())
        val normal = sessions.issue("owner-sudo")
        val elevated = sessions.issue("owner-sudo", sudoOnIssue = true)

        application {
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respondProblem(call.problemDetail(cause.status, cause.code, cause.message))
                }
            }
            routing {
                installRouteGuard(BearerRouteGuard(sessions))
                get("/v1/billing-key", RoutePolicy.AUTHENTICATED) {
                    val subject = call.requireSudo()
                    call.respondText("ok:${subject.ownerId}")
                }
            }
        }

        val forbidden = client.get("/v1/billing-key") {
            header("Authorization", "Bearer ${normal.accessToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val allowed = client.get("/v1/billing-key") {
            header("Authorization", "Bearer ${elevated.accessToken}")
        }
        assertEquals(HttpStatusCode.OK, allowed.status)
    }

    @Test
    fun memoryAndSqlEntitlementsFenceOutOfOrderWebhooksAndArchiveRawPayload(): Unit = runBlocking {
        verifyOutOfOrderWebhookContract(MemoryEntitlementStore())
        verifyOutOfOrderWebhookContract(SqlEntitlementStore())
    }

    private suspend fun verifyOutOfOrderWebhookContract(store: EntitlementStore) {
        val owner = "owner-${UUID.randomUUID()}"
        val now = 1_760_000_000_000L

        // Newer event (t = 2_000) lands first (for example via convergent /billing/return pull).
        val first = store.applyWebhookEvent(
            provider = "polar",
            eventId = "evt-newer-$owner",
            now = now,
            rawPayload = """{"type":"subscription.updated","cancel_at_period_end":true}""",
            upsert = EntitlementUpsert(
                externalSubId = "sub-$owner",
                ownerId = owner,
                planCode = "pro",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEnd = now + 86_400_000L,
                cancelAtPeriodEnd = true,
                sourceTimestamp = 2_000L,
            ),
        )
        assertEquals(WebhookApplyOutcome.APPLIED, first)

        // Delayed older webhook (t = 1_000) arrives later and is fenced without overwriting state.
        val stale = store.applyWebhookEvent(
            provider = "polar",
            eventId = "evt-older-$owner",
            now = now + 500L,
            rawPayload = """{"type":"subscription.created","plan":"starter"}""",
            upsert = EntitlementUpsert(
                externalSubId = "sub-$owner",
                ownerId = owner,
                planCode = "starter",
                status = SubscriptionStatus.TRIALING,
                currentPeriodEnd = now + 10_000L,
                cancelAtPeriodEnd = false,
                sourceTimestamp = 1_000L,
            ),
        )
        assertEquals(WebhookApplyOutcome.STALE_IGNORED, stale)

        val active = store.activeForOwner(owner, now).single()
        assertEquals("pro", active.planCode)
        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertTrue(active.cancelAtPeriodEnd)
        assertEquals(2_000L, active.sourceTimestamp)

        val archived = store.webhookEvent("polar", "evt-older-$owner")
        assertNotNull(archived)
        assertEquals(WebhookApplyOutcome.STALE_IGNORED, archived.outcome)
        assertTrue(archived.rawPayload.orEmpty().contains("subscription.created"))
    }

    @Test
    fun memoryAndSqlCreditReservationsSettlePartialHoldsReleaseFailuresAndExpireLeases(): Unit = runBlocking {
        verifyTwoPhaseCreditContract(MemoryCreditBalanceStore())
        verifyTwoPhaseCreditContract(SqlCreditBalanceStore())
    }

    private suspend fun verifyTwoPhaseCreditContract(store: CreditBalanceStore) {
        val owner = "owner-credits-${UUID.randomUUID()}"
        val now = 1_760_000_000_000L

        store.grant(owner, "promo-$owner", units = 50L, expiresAt = null, now = now)
        store.grant(owner, "topup-$owner", units = 50L, expiresAt = now + 86_400_000L, now = now + 1L)
        assertEquals(100L, store.available(owner, now + 2L))

        // Reserve 70 units for an AI stream; available drops to 30 immediately.
        val hold1 = store.reserve(owner, units = 70L, ttlMs = 60_000L, now = now + 10L)
        assertNotNull(hold1)
        assertEquals(30L, store.available(owner, now + 10L))
        assertFalse(store.trySpend(owner, units = 40L, now + 10L))
        assertTrue(store.trySpend(owner, units = 20L, now + 10L))
        assertEquals(10L, store.available(owner, now + 10L))

        // Stream finishes using 40 of the 70 held units -> unused 30 return immediately.
        assertEquals(
            CreditCommitOutcome.COMMITTED,
            store.commitReservation(hold1.id, actualUnits = 40L, now = now + 20L),
        )
        assertEquals(
            CreditCommitOutcome.ALREADY_COMMITTED,
            store.commitReservation(hold1.id, actualUnits = 40L, now = now + 21L),
        )
        assertEquals(40L, store.available(owner, now + 21L))

        // Upstream provider fails -> releaseReservation burns zero credits.
        val hold2 = store.reserve(owner, units = 35L, ttlMs = 60_000L, now = now + 30L)!!
        assertEquals(5L, store.available(owner, now + 30L))
        assertTrue(store.releaseReservation(hold2.id, now = now + 35L))
        assertFalse(store.releaseReservation(hold2.id, now = now + 36L))
        assertEquals(40L, store.available(owner, now + 36L))

        // Crashed worker hold expires after ttlMs -> balance recovers automatically without a sweeper.
        val crashedHold = store.reserve(owner, units = 40L, ttlMs = 5_000L, now = now + 40L)!!
        assertEquals(0L, store.available(owner, now + 41L))
        assertEquals(40L, store.available(owner, now + 45_001L))
        assertTrue(store.trySpend(owner, units = 30L, now = now + 45_002L))
        assertEquals(
            CreditCommitOutcome.EXPIRED_INSUFFICIENT,
            store.commitReservation(crashedHold.id, actualUnits = 25L, now = now + 45_003L),
        )

        val journal = store.ledgerForOwner(owner)
        assertEquals(10L, journal.sumOf { it.deltaUnits })
        assertEquals(10L, store.available(owner, now + 45_004L))
    }

    @Test
    fun transactionalOutboxCommitsAndRollsBackAtomicallyWithDomainWrites(): Unit = runBlocking {
        val jobs = SqlJobStore()
        val docs = SqlDocumentStore()
        val owner = "outbox-owner-${UUID.randomUUID()}"

        val rolledBackKey = "outbox-rollback-${UUID.randomUUID()}"
        assertFailsWith<IllegalStateException> {
            runInCoreTransaction {
                jobs.enqueueInTransaction(this, rolledBackKey, "email/welcome", """{"owner":"$owner"}""")
                error("simulated domain failure after enqueue")
            }
        }
        assertNull(jobs.claimPending(System.currentTimeMillis(), 10_000L), "rolled-back outbox job must not persist")

        val committedKey = "outbox-commit-${UUID.randomUUID()}"
        val enqueued = runInCoreTransaction {
            jobs.enqueueInTransaction(this, committedKey, "webhook/deliver", """{"event":"created"}""")
        }
        docs.put(
            ownerId = owner,
            documentId = "profile",
            schemaVersion = 1,
            payload = """{"ready":true}""",
            baseVersion = 0L,
            now = 1_760_000_000_000L,
        )
        assertNotNull(jobs.job(enqueued.id))
    }

    @Test
    fun memoryAndSqlWorkspacesApiKeysAndAccountDeletionEnforceOwnershipAndRoleClamping(): Unit = runBlocking {
        verifyWorkspaceAndApiKeyContract(
            workspaces = MemoryWorkspaceStore(),
            apiKeys = MemoryApiKeyStore(),
            sessions = MemorySessionStore(),
            identities = MemoryIdentityStore(),
            auditLogs = MemoryAuditLogStore(),
        )
        verifyWorkspaceAndApiKeyContract(
            workspaces = SqlWorkspaceStore(),
            apiKeys = SqlApiKeyStore(),
            sessions = SqlSessionStore(),
            identities = SqlIdentityStore(),
            auditLogs = SqlAuditLogStore(),
        )
    }

    private suspend fun verifyWorkspaceAndApiKeyContract(
        workspaces: WorkspaceStore,
        apiKeys: ApiKeyStore,
        sessions: SessionStore,
        identities: IdentityStore,
        auditLogs: AuditLogStore,
    ) {
        var now = 1_760_000_000_000L
        val founder = "founder-${UUID.randomUUID()}"
        val teammate = "teammate-${UUID.randomUUID()}"

        // 1. Auto-provisioned 1:1 personal workspace is idempotent.
        val personal1 = workspaces.ensurePersonalWorkspace(founder, now = now)
        val personal2 = workspaces.ensurePersonalWorkspace(founder, now = now + 100L)
        assertEquals(personal1.id, personal2.id)
        assertEquals(WorkspaceRole.OWNER, workspaces.memberRole(personal1.id, founder))

        // 2. Shared workspace + eager billingCustomerId binding + sole-owner orphan guard.
        val slug = "team-${UUID.randomUUID().toString().take(8)}"
        val shared = workspaces.createWorkspace(founder, slug = slug, name = "Studio", planCode = "pro", now = now)
        val customerId = "cus_${UUID.randomUUID()}"
        workspaces.bindBillingCustomer(shared.id, customerId, now = now + 10L)
        assertEquals(shared.id, workspaces.byBillingCustomer(customerId)?.id)

        assertFailsWith<LastWorkspaceOwnerException> {
            workspaces.upsertMember(shared.id, founder, WorkspaceRole.ADMIN, now = now + 20L)
        }
        assertFailsWith<LastWorkspaceOwnerException> {
            workspaces.removeMember(shared.id, founder)
        }

        // 3. Email-bound hashed invite refuses mismatched email without burning the token.
        val issuedInvite = workspaces.issueInvite(
            workspaceId = shared.id,
            invitedEmail = "teammate@acme.test",
            role = WorkspaceRole.MEMBER,
            invitedBy = founder,
            now = now + 30L,
        )
        val wrongEmail = workspaces.acceptInvite(
            rawToken = issuedInvite.rawToken,
            acceptingOwnerId = teammate,
            verifiedEmail = "personal@acme.test",
            now = now + 40L,
        )
        assertEquals(InviteAcceptOutcome.EMAIL_MISMATCH, wrongEmail.outcome)

        val accepted = workspaces.acceptInvite(
            rawToken = issuedInvite.rawToken,
            acceptingOwnerId = teammate,
            verifiedEmail = "Teammate@acme.test",
            now = now + 50L,
        )
        assertEquals(InviteAcceptOutcome.ACCEPTED, accepted.outcome)
        assertEquals(
            InviteAcceptOutcome.ALREADY_USED,
            workspaces.acceptInvite(issuedInvite.rawToken, teammate, "teammate@acme.test", now + 60L).outcome,
        )

        // 4. API key format check, live role clamping, rotation grace, and 1/min lastUsedAt throttle.
        val keyService = ApiKeyService(
            store = apiKeys,
            workspaces = workspaces,
            productPrefix = "sk",
            touchMinIntervalMs = 60_000L,
            clock = { now },
        )
        assertNull(keyService.authenticate("not-a-valid-key"))

        val teammateKey = keyService.issue(
            workspaceId = shared.id,
            createdByOwnerId = teammate,
            name = "CI runner",
            scopes = setOf("read", "write"),
        )
        val auth1 = keyService.authenticate(teammateKey.rawKey, requiredScope = "write")
        assertNotNull(auth1)
        val firstTouch = keyService.listForWorkspace(shared.id).single { it.id == teammateKey.record.id }.lastUsedAt
        assertEquals(now, firstTouch)

        now += 15_000L
        assertNotNull(keyService.authenticate(teammateKey.rawKey, requiredScope = "read"))
        val throttledTouch = keyService.listForWorkspace(shared.id).single { it.id == teammateKey.record.id }.lastUsedAt
        assertEquals(firstTouch, throttledTouch, "lastUsedAt must be throttled inside touchMinIntervalMs")

        // Demoting teammate to VIEWER immediately clamps existing key so "write" fails.
        workspaces.upsertMember(shared.id, teammate, WorkspaceRole.VIEWER, now = now)
        assertNull(keyService.authenticate(teammateKey.rawKey, requiredScope = "write"))
        assertNotNull(keyService.authenticate(teammateKey.rawKey, requiredScope = "read"))

        // Key rotation keeps outgoing key valid during graceMs, then retires it automatically.
        val rotated = keyService.rotate(shared.id, teammateKey.record.id, graceMs = 10_000L)!!
        now += 5_000L
        assertNotNull(keyService.authenticate(teammateKey.rawKey, requiredScope = "read"))
        assertNotNull(keyService.authenticate(rotated.rawKey, requiredScope = "read"))
        now += 6_000L
        assertNull(keyService.authenticate(teammateKey.rawKey, requiredScope = "read"))
        assertNotNull(keyService.authenticate(rotated.rawKey, requiredScope = "read"))

        // 5. Account deletion refuses sole owner of shared workspace before revoking external credentials.
        var appleRevokeCalls = 0
        val deletion = AccountDeletionService(
            sessions = sessions,
            identities = identities,
            workspaces = workspaces,
            apiKeys = apiKeys,
            auditLogs = auditLogs,
            appleRevoker = AppleTokenRevoker { _, _ ->
                appleRevokeCalls++
                true
            },
            clock = { now },
        )
        assertFailsWith<LastWorkspaceOwnerException> {
            deletion.deleteAccount(ownerId = founder, appleToken = "apple-refresh")
        }
        assertEquals(0, appleRevokeCalls, "sole-owner preflight must abort before revoking external Apple token")

        // Promoting teammate to co-owner allows founder to delete account cleanly while shared workspace survives.
        workspaces.upsertMember(shared.id, teammate, WorkspaceRole.OWNER, now = now)
        auditLogs.append(
            AuditEventRow(
                workspaceId = shared.id,
                ownerId = founder,
                actorId = founder,
                action = "workspace.created",
                ipAddress = "198.51.100.24",
                deviceId = "dev-founder",
                userAgent = "Chrome",
                createdAt = now,
            ),
        )
        deletion.deleteAccount(ownerId = founder, appleToken = "apple-refresh")
        assertEquals(1, appleRevokeCalls)
        assertNull(workspaces.byId(personal1.id), "founder personal workspace is deleted")
        assertNotNull(workspaces.byId(shared.id), "shared workspace with co-owner remains")
        assertEquals(emptyList(), auditLogs.forOwner(founder))
        val retainedWsAudit = auditLogs.forWorkspace(shared.id).single()
        assertTrue(retainedWsAudit.actorId.startsWith("deleted:"))
        assertNull(retainedWsAudit.ipAddress)
        assertNull(retainedWsAudit.deviceId)
    }

    @Test
    fun weightedRateLimiterPreventsOverdraftAndEmitsStandardHeaders(): Unit = testApplication {
        val limiter = TokenBucketRateLimiter(
            capacity = 10L,
            refillTokensPerInterval = 10L,
            refillIntervalMs = 10_000L,
        )
        var now = 1_760_000_000_000L

        application {
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respondProblem(call.problemDetail(cause.status, cause.code, cause.message))
                }
            }
            routing {
                routeGet("/v1/light") {
                    call.enforceRateLimit(limiter, key = "workspace-1", cost = 4L, now = now)
                    call.respondText("ok")
                }
                routeGet("/v1/heavy") {
                    call.enforceRateLimit(limiter, key = "workspace-1", cost = 8L, now = now)
                    call.respondText("heavy-ok")
                }
            }
        }

        val first = client.get("/v1/light")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("10", first.headers[RATE_LIMIT_LIMIT_HEADER])
        assertEquals("6", first.headers[RATE_LIMIT_REMAINING_HEADER])

        // 6 tokens remain, so an 8-token weighted request is rejected without overdrafting the 6 tokens!
        val heavyDenied = client.get("/v1/heavy")
        assertEquals(HttpStatusCode.TooManyRequests, heavyDenied.status)
        assertEquals("6", heavyDenied.headers[RATE_LIMIT_REMAINING_HEADER])
        assertEquals("2", heavyDenied.headers[RETRY_AFTER_HEADER])

        // Advancing 2 seconds refills 2 tokens (6 + 2 = 8), allowing the heavy call.
        now += 2_000L
        val heavyAllowed = client.get("/v1/heavy")
        assertEquals(HttpStatusCode.OK, heavyAllowed.status)
        assertEquals("0", heavyAllowed.headers[RATE_LIMIT_REMAINING_HEADER])
    }

    @Test
    fun memoryAndSqlAuditLogsSupportImpersonationAndVisaCompellingEvidenceWindow(): Unit = runBlocking {
        verifyAuditEvidenceContract(MemoryAuditLogStore())
        verifyAuditEvidenceContract(SqlAuditLogStore())
    }

    private suspend fun verifyAuditEvidenceContract(store: AuditLogStore) {
        val dayMs = 86_400_000L
        val disputedAt = 1_760_000_000_000L
        val owner = "buyer-${UUID.randomUUID()}"

        // Undisputed logins 200 days and 150 days before dispute with matching IP/device -> qualify for Visa CE 3.0.
        store.append(
            AuditEventRow(
                ownerId = owner,
                actorId = owner,
                action = "billing.renewal.used",
                ipAddress = "198.51.100.77",
                deviceId = "mac-1",
                createdAt = disputedAt - 200 * dayMs,
            ),
        )
        store.append(
            AuditEventRow(
                ownerId = owner,
                actorId = owner,
                action = "document.exported",
                ipAddress = "192.0.2.9",
                deviceId = "mac-1",
                createdAt = disputedAt - 150 * dayMs,
            ),
        )
        // Too recent (30 days prior) and support impersonation action (even with matching IP/device!) -> excluded.
        store.append(
            AuditEventRow(
                ownerId = owner,
                actorId = owner,
                action = "document.exported",
                ipAddress = "198.51.100.77",
                deviceId = "mac-1",
                createdAt = disputedAt - 30 * dayMs,
            ),
        )
        store.append(
            AuditEventRow(
                ownerId = owner,
                actorId = owner,
                impersonatorId = "support-eng-1",
                action = "support.view",
                ipAddress = "198.51.100.77",
                deviceId = "mac-1",
                createdAt = disputedAt - 180 * dayMs,
            ),
        )

        val evidence = store.findVisaCompellingEvidence(
            ownerId = owner,
            disputedAt = disputedAt,
            ipAddress = "198.51.100.77",
            deviceId = "mac-1",
        )
        assertEquals(2, evidence.size, "Visa CE 3.0 matches direct customer events 120..365 days before dispute")
        assertEquals(1, store.forOwner(owner).count { it.impersonatorId == "support-eng-1" })
    }
}
