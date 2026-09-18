package com.example.server

import com.example.server.core.ConfigDefaults
import com.example.server.core.ConfigKey
import com.example.server.core.ServerConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigTest {

    @Test
    fun blankEnvironmentBindsEveryDefault() {
        val config = ServerConfig.fromEnv { "" }
        assertFalse(config.database.durable, "blank DATABASE_URL is local-only memory mode")
        assertFalse(config.model.configured, "no key means the factory is off")
        assertEquals(ConfigDefaults.MODEL_BASE_URL, config.model.baseUrl)
        assertEquals(ConfigDefaults.MODEL_NAME, config.model.model)
        assertEquals(ConfigDefaults.CONTEXT_TOKENS, config.model.contextTokens)
        assertEquals(ConfigDefaults.MAX_OUTPUT_TOKENS, config.model.maxOutputTokens)
        assertEquals(ConfigDefaults.SEEDS_PER_DAY, config.caps.seedsPerDay)
        assertEquals(ConfigDefaults.TALK_PER_DAY, config.caps.talkPerDay)
        assertFalse(config.storeEvents.trustUnverified)
        assertFalse(config.factoryKilled)
    }

    @Test
    fun providedValuesBindPerConcern() {
        val env = mapOf(
            ConfigKey.DATABASE_URL to "postgresql://db",
            ConfigKey.MODEL_API_KEY to "k-1",
            ConfigKey.MODEL_BASE_URL to "https://model.example/v1",
            ConfigKey.MODEL_NAME to "some/model",
            ConfigKey.DAILY_SEED_CAP to "7",
            ConfigKey.DAILY_TALK_CAP to "9",
            ConfigKey.FACTORY_KILL to "yes",
            ConfigKey.STORE_EVENTS_TRUST_UNVERIFIED to "TRUE",
            ConfigKey.APPLE_ROOT_CA_PATH to "/etc/example/apple.pem",
        )
        val config = ServerConfig.fromEnv { env[it].orEmpty() }
        assertTrue(config.database.durable)
        assertTrue(config.model.configured)
        assertEquals("k-1", config.model.apiKey)
        assertEquals("https://model.example/v1", config.model.baseUrl)
        assertEquals("some/model", config.model.model)
        assertEquals(7, config.caps.seedsPerDay)
        assertEquals(9, config.caps.talkPerDay)
        assertTrue(config.factoryKilled)
        assertTrue(config.storeEvents.trustUnverified)
        assertEquals("/etc/example/apple.pem", config.storeEvents.appleRootPath)
    }

    @Test
    fun capsOfZeroOrLessAreAnHonestUncappedValue() {
        val zero = ServerConfig.fromEnv { if (it == ConfigKey.DAILY_SEED_CAP) "0" else "" }
        assertEquals(0, zero.caps.seedsPerDay, "zero stays zero; BudgetLimits treats <= 0 as uncapped")

        val negative = ServerConfig.fromEnv { if (it == ConfigKey.DAILY_SEED_CAP) "-5" else "" }
        assertEquals(-5, negative.caps.seedsPerDay)
    }

    @Test
    fun garbageCapFallsBackToTheDefaultRatherThanCrashing() {
        val config = ServerConfig.fromEnv { if (it == ConfigKey.DAILY_TALK_CAP) "lots" else "" }
        assertEquals(ConfigDefaults.TALK_PER_DAY, config.caps.talkPerDay)
    }

    @Test
    fun theModelContextWindowIsBoundAndFallsBackWhenInsane() {
        val bound = ServerConfig.fromEnv { key ->
            when (key) {
                ConfigKey.MODEL_CONTEXT_TOKENS -> "32000"
                ConfigKey.MODEL_MAX_OUTPUT_TOKENS -> "2048"
                else -> ""
            }
        }
        assertEquals(32_000, bound.model.contextTokens)
        assertEquals(2_048, bound.model.maxOutputTokens)

        // Reserving more output than the window is a misconfiguration: take the
        // sane pair of defaults rather than a budget that can never fit.
        val insane = ServerConfig.fromEnv { key ->
            when (key) {
                ConfigKey.MODEL_CONTEXT_TOKENS -> "1000"
                ConfigKey.MODEL_MAX_OUTPUT_TOKENS -> "2000"
                else -> ""
            }
        }
        assertEquals(ConfigDefaults.CONTEXT_TOKENS, insane.model.contextTokens)
        assertEquals(ConfigDefaults.MAX_OUTPUT_TOKENS, insane.model.maxOutputTokens)

        val garbage = ServerConfig.fromEnv { if (it == ConfigKey.MODEL_CONTEXT_TOKENS) "lots" else "" }
        assertEquals(ConfigDefaults.CONTEXT_TOKENS, garbage.model.contextTokens)
    }

    @Test
    fun storeTrustOnlyAcceptsTheExplicitTrue() {
        assertTrue(ServerConfig.fromEnv { if (it == ConfigKey.STORE_EVENTS_TRUST_UNVERIFIED) " true " else "" }
            .storeEvents.trustUnverified)
        assertFalse(ServerConfig.fromEnv { if (it == ConfigKey.STORE_EVENTS_TRUST_UNVERIFIED) "1" else "" }
            .storeEvents.trustUnverified, "the store flag is documented as true/false, not 1/0")
    }
}

/**
 * The `.env.example` / `.env.production.example` pair is documentation that
 * rots the moment a key is added in code and not in the file. This test is the
 * ratchet: it fails on a missing key and on a committed secret.
 */
class ConfigExampleTest {

    @Test
    fun bothExampleFilesDocumentEveryBoundKey() {
        val local = exampleKeys(readExample(".env.example"))
        val production = exampleKeys(readExample(".env.production.example"))
        ConfigKey.all.forEach { key ->
            assertTrue(key in local, ".env.example must document $key")
            assertTrue(key in production, ".env.production.example must document $key")
        }
    }

    @Test
    fun theProductionExampleNeverCarriesASecretValue() {
        val secrets = listOf(ConfigKey.MODEL_API_KEY)
        val lines = readExample(".env.production.example")
        secrets.forEach { key ->
            val value = lines.firstOrNull { it.trimStart().startsWith("$key=") }
                ?.substringAfter("=")
                ?.trim()
                .orEmpty()
            assertTrue(value.isEmpty(), "$key must be blank in the committed production example")
        }
    }

    private fun exampleKeys(lines: List<String>): Set<String> =
        lines.mapNotNull { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#")) return@mapNotNull null
            val name = trimmed.substringBefore('=', "").trim()
            name.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]*")) }
        }.toSet()

    private fun readExample(name: String): List<String> {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve(name)) }
            ?: error("could not find $name from ${Path.of("").toAbsolutePath()}")
        return Files.readAllLines(root.resolve(name))
    }
}
