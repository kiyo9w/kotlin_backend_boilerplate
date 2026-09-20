package com.example.server.core

import com.example.server.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The session/token shell: issue, authenticate, refresh, revoke, and the
 * bearer guard that turns a live access token into an [AuthSubject] before a
 * protected handler runs. The injected clock makes every expiry boundary a
 * fact rather than a wait.
 */
class AuthTest {

    private var now = 1_700_000_000_000L

    private fun sessions(store: SessionStore = MemorySessionStore()) = SessionService(store) { now }

    @Test
    fun issueThenAuthenticateResolvesTheOwnerAndDevice() = runBlocking<Unit> {
        val service = sessions()

        val tokens = service.issue(ownerId = "owner-1", deviceId = "device-1")

        val subject = assertNotNull(service.authenticate(tokens.accessToken))
        assertEquals("owner-1", subject.ownerId)
        assertEquals("device-1", subject.deviceId)
        assertEquals(emptySet(), subject.roles, "the shell mints no roles; a product adds its own")
        assertEquals(now + TokenTtl().accessMs, tokens.accessExpiresAt)
        assertEquals(now + TokenTtl().refreshMs, tokens.refreshExpiresAt)
    }

    @Test
    fun onlyTokenHashesArePersisted() = runBlocking<Unit> {
        val spy = InsertSpy(MemorySessionStore())
        val service = SessionService(spy) { now }

        val tokens = service.issue("owner-1")

        val row = assertNotNull(spy.inserted)
        assertEquals(64, row.accessHash.length, "SHA-256 hex is 64 characters")
        assertEquals(64, row.refreshHash.length)
        assertNotEquals(tokens.accessToken, row.accessHash)
        assertNotEquals(tokens.refreshToken, row.refreshHash)
        assertFalse(row.accessHash.contains(tokens.accessToken), "the raw token never reaches the store")
    }

    @Test
    fun tokensAreThirtyTwoRandomBytesInUrlSafeUnpaddedBase64() = runBlocking<Unit> {
        val tokens = sessions().issue("owner-1")

        val urlSafeBase64Of32Bytes = Regex("[A-Za-z0-9_-]{43}")
        assertTrue(tokens.accessToken.matches(urlSafeBase64Of32Bytes), "43 chars: 32 bytes, no padding")
        assertTrue(tokens.refreshToken.matches(urlSafeBase64Of32Bytes))
    }

    @Test
    fun anExpiredAccessTokenResolvesNullButTheRefreshTokenIsStillAlive() = runBlocking<Unit> {
        val service = sessions()
        val tokens = service.issue("owner-1")

        now += TokenTtl().accessMs + 1

        assertNull(service.authenticate(tokens.accessToken), "expiry is checked against the injected clock")
        assertNotNull(service.refresh(tokens.refreshToken), "the refresh window outlives the access window")
    }

    @Test
    fun anExpiredRefreshTokenResolvesNull() = runBlocking<Unit> {
        val service = sessions()
        val tokens = service.issue("owner-1")

        now += TokenTtl().refreshMs + 1

        assertNull(service.refresh(tokens.refreshToken))
        assertNull(service.authenticate(tokens.accessToken))
    }

    @Test
    fun refreshRotatesAndRetiresThePresentedPair() = runBlocking<Unit> {
        val service = sessions()
        val first = service.issue(ownerId = "owner-1", deviceId = "device-1")

        val second = assertNotNull(service.refresh(first.refreshToken))

        assertNotNull(service.authenticate(second.accessToken), "the new access token is live")
        assertNull(
            service.refresh(first.refreshToken),
            "the presented refresh token is refused even though the new pair is unused",
        )
        assertNull(service.authenticate(first.accessToken), "the old access token dies with its row")
        assertNotNull(service.refresh(second.refreshToken), "the new refresh token works")
    }

    @Test
    fun revokeKillsThePairAndRevokeAllIsOwnerScoped() = runBlocking<Unit> {
        val service = sessions()
        val ownerAFirst = service.issue("owner-a", "device-1")
        val ownerASecond = service.issue("owner-a", "device-2")
        val ownerB = service.issue("owner-b", "device-3")

        assertTrue(service.revoke(ownerAFirst.accessToken), "the presented pair is revoked")
        assertNull(service.authenticate(ownerAFirst.accessToken))
        assertNull(service.refresh(ownerAFirst.refreshToken))
        assertFalse(service.revoke(ownerAFirst.accessToken), "there is nothing live left to revoke")

        service.revokeAll("owner-a")

        assertNull(service.authenticate(ownerASecond.accessToken), "every live session for the owner is dead")
        assertNull(service.refresh(ownerASecond.refreshToken))
        assertNotNull(service.authenticate(ownerB.accessToken), "another owner's session survives")
    }

    @Test
    fun unknownAndGarbageTokensResolveNull() = runBlocking<Unit> {
        val service = sessions()

        listOf("", "not-a-token", "!!!", "A".repeat(43)).forEach { garbage ->
            assertNull(service.authenticate(garbage), "authenticate($garbage)")
            assertNull(service.refresh(garbage), "refresh($garbage)")
            assertFalse(service.revoke(garbage), "revoke($garbage)")
        }
    }

    // ---- BearerRouteGuard, through a real testApplication and the real route policy plugin ----

    @Test
    fun aMissingOrMalformedBearerHeaderIsRefusedWithTheProblemEnvelope() = withAuthApp { client, _ ->
        val missing = client.get("/v1/me")

        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals(
            "application/problem+json",
            missing.contentType()?.withoutParameters()?.toString(),
        )
        val problem = missing.body<ProblemDetail>()
        assertEquals(401, problem.status)
        assertEquals("UNAUTHORIZED", problem.code)

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/me") { header("Authorization", "Bearer not-a-token") }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/me") { header("Authorization", "Basic abc") }.status,
            "another scheme is not a bearer token",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/me") { header("Authorization", "Bearer") }.status,
        )
    }

    @Test
    fun aValidBearerTokenAttachesTheSubjectForTheHandler() = withAuthApp { client, sessions ->
        val tokens = sessions.issue(ownerId = "owner-42", deviceId = "device-7")

        val response = client.get("/v1/me") { header("Authorization", "Bearer ${tokens.accessToken}") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "owner-42|device-7",
            response.bodyAsText(),
            "the handler reads the subject from AuthSubjectKey",
        )
    }

    @Test
    fun aPublicRouteNeedsNoBearerToken() = withAuthApp { client, _ ->
        assertEquals(HttpStatusCode.OK, client.get("/v1/public").status)
    }

    @Test
    fun anAdminRouteRefusesABearerSessionWithNoAdminRole() = withAuthApp { client, sessions ->
        val tokens = sessions.issue("owner-1")

        val response = client.get("/v1/admin") { header("Authorization", "Bearer ${tokens.accessToken}") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("FORBIDDEN", response.body<ProblemDetail>().code)
    }

    private fun withAuthApp(test: suspend (HttpClient, SessionService) -> Unit): Unit = testApplication {
        val sessions = SessionService(MemorySessionStore())
        val guard = BearerRouteGuard(sessions)
        application {
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respondProblem(call.problemDetail(cause.status, cause.code, cause.message))
                }
            }
            routing {
                installRouteGuard(guard)
                get("/v1/me", RoutePolicy.AUTHENTICATED) {
                    val subject = call.attributes[AuthSubjectKey]
                    call.respondText("${subject.ownerId}|${subject.deviceId.orEmpty()}")
                }
                get("/v1/public", RoutePolicy.PUBLIC) {
                    // The policy plugin short-circuits PUBLIC before the guard
                    // is consulted; call it directly to prove the guard's own
                    // PUBLIC branch is a no-op.
                    guard.authorize(call, RoutePolicy.PUBLIC)
                    call.respondText("public")
                }
                get("/v1/admin", RoutePolicy.ADMIN) {
                    call.respondText("admin")
                }
            }
        }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        test(client, sessions)
    }

    private class InsertSpy(private val delegate: SessionStore) : SessionStore by delegate {
        var inserted: SessionRow? = null
            private set

        override suspend fun insert(row: SessionRow) {
            inserted = row
            delegate.insert(row)
        }
    }
}

/**
 * The SQL store against a real database: the rotation compare-and-set and the
 * revocation sweep are the durable production path, so they are proven on H2
 * (Postgres mode) rather than only in memory, mirroring SqlSchedulerTest.
 */
class SqlSessionStoreTest {

    private val pool = openCoreDataSource(
        jdbcUrl = "jdbc:h2:mem:sessions_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
    )

    @AfterTest
    fun closePool() {
        (pool as? AutoCloseable)?.close()
    }

    private fun row(
        id: String,
        accessHash: String,
        refreshHash: String,
        now: Long,
        ownerId: String = "owner-1",
        deviceId: String? = null,
        rotatedFrom: String? = null,
    ) = SessionRow(
        id = id,
        ownerId = ownerId,
        deviceId = deviceId,
        accessHash = accessHash,
        refreshHash = refreshHash,
        accessExpiresAt = now + 900_000,
        refreshExpiresAt = now + 2_592_000_000,
        rotatedFrom = rotatedFrom,
        createdAt = now,
    )

    @Test
    fun insertLookupAndTouchRoundTrip() = runBlocking<Unit> {
        val store = SqlSessionStore()
        val now = 1_700_000_000_000L
        val inserted = row(id = "s1", accessHash = "hash-a1", refreshHash = "hash-r1", now = now)

        store.insert(inserted)

        assertEquals(inserted, SqlSessionStore().byAccessHash("hash-a1"))
        assertEquals(inserted, SqlSessionStore().byRefreshHash("hash-r1"))
        assertNull(SqlSessionStore().byAccessHash("hash-unknown"))

        store.touch("s1", now + 5)
        assertEquals(now + 5, SqlSessionStore().byAccessHash("hash-a1")!!.lastUsedAt)
    }

    @Test
    fun aStaleRotationIsRefusedAndTheSuccessorIsNeverInserted() = runBlocking<Unit> {
        val store = SqlSessionStore()
        val now = 1_700_000_000_000L
        val first = row(id = "s1", accessHash = "hash-a1", refreshHash = "hash-r1", now = now)
        store.insert(first)
        val second = row(
            id = "s2",
            accessHash = "hash-a2",
            refreshHash = "hash-r2",
            now = now,
            rotatedFrom = "s1",
        )

        store.rotate("s1", second, now + 1)

        assertEquals(now + 1, SqlSessionStore().byRefreshHash("hash-r1")!!.revokedAt)
        assertEquals("s1", SqlSessionStore().byAccessHash("hash-a2")!!.rotatedFrom)

        assertFailsWith<StaleClaimException> {
            store.rotate("s1", row(id = "s3", accessHash = "hash-a3", refreshHash = "hash-r3", now = now), now + 2)
        }
        assertNull(SqlSessionStore().byAccessHash("hash-a3"), "the refused successor was never inserted")
    }

    @Test
    fun revokeAndRevokeAllAreDurableAndOwnerScoped() = runBlocking<Unit> {
        val store = SqlSessionStore()
        val now = 1_700_000_000_000L
        store.insert(row(id = "a1", accessHash = "hash-aa1", refreshHash = "hash-ar1", ownerId = "owner-a", now = now))
        store.insert(row(id = "a2", accessHash = "hash-aa2", refreshHash = "hash-ar2", ownerId = "owner-a", now = now))
        store.insert(row(id = "b1", accessHash = "hash-ab1", refreshHash = "hash-br1", ownerId = "owner-b", now = now))

        store.revoke("a1", now + 1)
        assertEquals(now + 1, SqlSessionStore().byAccessHash("hash-aa1")!!.revokedAt)

        store.revokeAll("owner-a", now + 2)
        assertEquals(now + 2, SqlSessionStore().byAccessHash("hash-aa2")!!.revokedAt)
        assertNull(
            SqlSessionStore().byAccessHash("hash-ab1")!!.revokedAt,
            "another owner's row is untouched",
        )
    }

    @Test
    fun theServiceRotatesOverSqlAndRefusesAReplayedRefresh() = runBlocking<Unit> {
        val service = SessionService(SqlSessionStore())
        val first = service.issue(ownerId = "owner-1", deviceId = "device-1")

        val second = assertNotNull(service.refresh(first.refreshToken))

        assertNotNull(service.authenticate(second.accessToken))
        assertNull(service.authenticate(first.accessToken), "the old access token is refused")
        assertNull(service.refresh(first.refreshToken), "the replayed refresh token is refused")
    }
}
