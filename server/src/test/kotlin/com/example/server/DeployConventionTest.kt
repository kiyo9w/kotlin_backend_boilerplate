package com.example.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The deploy convention, enforced structurally.
 *
 * It also guards the defect this test was written for: `deploy/` was copied from
 * a product whose server lived under `mobile/`, and the paths silently pointed
 * at a directory that does not exist here. The "no stale paths" assertions are
 * what stop that copy-paste from surviving.
 */
class DeployConventionTest {

    private val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private val deploy: Path = root.resolve("deploy")

    private fun read(relative: String): String = Files.readString(deploy.resolve(relative))

    private val deployFiles: List<Path> = Files.walk(deploy).filter { Files.isRegularFile(it) }.toList()

    @Test
    fun noDeployFilePointsAtTheWrongLayout() {
        deployFiles.forEach { file ->
            val body = Files.readString(file)
            assertFalse(
                body.contains("mobile/"),
                "${root.relativize(file)} references mobile/, which does not exist in this layout",
            )
            assertFalse(
                body.contains("Qoloa"),
                "${root.relativize(file)} carries the donating product's capitalised name",
            )
        }
    }

    @Test
    fun containerBuildsTheDistributionAndDeclaresAHealthcheck() {
        val dockerfile = read("Dockerfile")
        assertTrue(":server:installDist" in dockerfile, "the image must build the runnable distribution")
        assertTrue("HEALTHCHECK" in dockerfile, "a container without a healthcheck cannot be rolled safely")
        assertTrue("EXPOSE 8080" in dockerfile, "the server port must be declared")
        assertTrue("USER " in dockerfile && "USER root" !in dockerfile, "the process must not run as root")
    }

    @Test
    fun composeBuildsFromTheRepositoryRootWithAHealthcheckAndANamedVolume() {
        val compose = read("docker-compose.yml")
        assertTrue("context: .." in compose, "the build context is the repository root")
        assertTrue("dockerfile: deploy/Dockerfile" in compose, "the Dockerfile path is relative to the root")
        assertTrue("healthcheck:" in compose, "compose must gate the server on a healthcheck")
        assertTrue("service_healthy" in compose, "the server must wait for the database to be healthy")
        assertTrue("POSTGRES_PASSWORD" in compose, "the database secret must come from the environment")
        assertTrue(Regex("^volumes:", RegexOption.MULTILINE).containsMatchIn(compose), "a named volume keeps the data")
    }

    @Test
    fun theProcessManagerRunsTheInstalledDistribution() {
        val ecosystem = read("ecosystem.config.js")
        assertTrue(
            "../server/build/install/server" in ecosystem,
            "PM2 must run the installed distribution under server/",
        )
    }

    @Test
    fun theDeployScriptVerifiesReadiness() {
        val script = deploy.resolve("deploy.sh")
        assertTrue(Files.isExecutable(script), "deploy.sh must be executable")
        val body = Files.readString(script)
        assertTrue("/ready" in body, "a deploy that does not check readiness is not verified")
        assertTrue("set -euo pipefail" in body, "the deploy script must fail fast")
    }

    @Test
    fun theProxyCarriesTheCorrelationId() {
        val nginx = read("nginx/app.conf")
        assertTrue("proxy_pass" in nginx, "the proxy must forward to the server")
        assertTrue("X-Request-Id" in nginx, "an inbound request id must survive the proxy")
    }

    @Test
    fun theDeployExampleDocumentsTheRequiredSecret() {
        assertTrue("POSTGRES_PASSWORD" in read(".env.example"))
    }

    @Test
    fun ciBuildsAndSmokeTestsTheImage() {
        val workflow = root.resolve(".github/workflows/server-image.yml")
        assertTrue(Files.isRegularFile(workflow), "the image build must be exercised in CI")
        val body = Files.readString(workflow)
        assertTrue("docker build" in body, "CI must build the image")
        assertTrue("/health" in body, "CI must wait for a healthy server, not just a built image")
    }
}
