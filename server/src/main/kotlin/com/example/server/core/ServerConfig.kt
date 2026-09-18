package com.example.server.core

/**
 * Every environment key this service binds. `ConfigExampleTest` asserts each
 * one appears in both `.env.example` and `.env.production.example`, which is
 * what stops the documented pair from rotting behind the code.
 */
object ConfigKey {
    const val DATABASE_URL = "DATABASE_URL"
    const val MODEL_API_KEY = "CMD_API_KEY"
    const val MODEL_API_KEY_ALIAS = "XAI_API_KEY"
    const val MODEL_BASE_URL = "CMD_BASE"
    const val MODEL_NAME = "CMD_MODEL"
    const val DAILY_SEED_CAP = "QOLOA_DAILY_SEED_CAP"
    const val DAILY_TALK_CAP = "QOLOA_DAILY_TALK_CAP"
    const val FACTORY_KILL = "QOLOA_FACTORY_KILL"
    const val STORE_EVENTS_TRUST_UNVERIFIED = "QOLOA_STORE_EVENTS_TRUST_UNVERIFIED"
    const val APPLE_ROOT_CA_PEM = "QOLOA_APPLE_ROOT_CA_PEM"
    const val APPLE_ROOT_CA_PATH = "QOLOA_APPLE_ROOT_CA_PATH"

    /** Every bound key, in the order the examples document them. */
    val all: List<String> = listOf(
        DATABASE_URL,
        MODEL_API_KEY,
        MODEL_API_KEY_ALIAS,
        MODEL_BASE_URL,
        MODEL_NAME,
        DAILY_SEED_CAP,
        DAILY_TALK_CAP,
        FACTORY_KILL,
        STORE_EVENTS_TRUST_UNVERIFIED,
        APPLE_ROOT_CA_PEM,
        APPLE_ROOT_CA_PATH,
    )
}

/** Defaults stated in code, never at a call site. */
object ConfigDefaults {
    const val SEEDS_PER_DAY = 24
    const val TALK_PER_DAY = 120
    const val MODEL_BASE_URL = "https://api.commandcode.ai/provider/v1"
    const val MODEL_NAME = "meta/muse-spark-1.2-contributor"
}

/** Durable store selection. A blank URL is the explicit local-only memory mode. */
data class DatabaseConfig(val url: String) {
    val durable: Boolean get() = url.isNotBlank()

    companion object {
        fun from(env: (String) -> String): DatabaseConfig =
            DatabaseConfig(env(ConfigKey.DATABASE_URL).trim())
    }
}

/** Model gateway credentials. Keys stay server-side; never in an example or a commit. */
data class ModelConfig(val apiKey: String, val baseUrl: String, val model: String) {
    val configured: Boolean get() = apiKey.isNotBlank()

    companion object {
        fun from(env: (String) -> String): ModelConfig = ModelConfig(
            apiKey = env(ConfigKey.MODEL_API_KEY).ifBlank { env(ConfigKey.MODEL_API_KEY_ALIAS) }.trim(),
            baseUrl = env(ConfigKey.MODEL_BASE_URL).trim().ifBlank { ConfigDefaults.MODEL_BASE_URL },
            model = env(ConfigKey.MODEL_NAME).trim().ifBlank { ConfigDefaults.MODEL_NAME },
        )
    }
}

/** Per-subject daily caps. A cap of zero or less means uncapped. */
data class RateCaps(val seedsPerDay: Int, val talkPerDay: Int) {
    companion object {
        fun from(env: (String) -> String): RateCaps = RateCaps(
            seedsPerDay = env(ConfigKey.DAILY_SEED_CAP).capOrDefault(ConfigDefaults.SEEDS_PER_DAY),
            talkPerDay = env(ConfigKey.DAILY_TALK_CAP).capOrDefault(ConfigDefaults.TALK_PER_DAY),
        )
    }
}

private fun String.capOrDefault(default: Int): Int = trim().toIntOrNull() ?: default

/** Store webhook trust. Trust is explicit, certificate-backed, and fail-closed. */
data class StoreEventsConfig(
    val trustUnverified: Boolean,
    val appleRootPem: String,
    val appleRootPath: String,
) {
    companion object {
        fun from(env: (String) -> String): StoreEventsConfig = StoreEventsConfig(
            trustUnverified = env(ConfigKey.STORE_EVENTS_TRUST_UNVERIFIED).trim()
                .equals("true", ignoreCase = true),
            appleRootPem = env(ConfigKey.APPLE_ROOT_CA_PEM),
            appleRootPath = env(ConfigKey.APPLE_ROOT_CA_PATH).trim(),
        )
    }
}

/**
 * The whole environment bound once, per concern, with defaults in code. One
 * reader per key: product wiring consumes these objects instead of calling
 * `System.getenv` (or [com.example.server.ServerEnv]) at the point of use.
 */
data class ServerConfig(
    val database: DatabaseConfig,
    val model: ModelConfig,
    val caps: RateCaps,
    val storeEvents: StoreEventsConfig,
    val factoryKilled: Boolean,
) {
    companion object {
        fun fromEnv(env: (String) -> String): ServerConfig = ServerConfig(
            database = DatabaseConfig.from(env),
            model = ModelConfig.from(env),
            caps = RateCaps.from(env),
            storeEvents = StoreEventsConfig.from(env),
            factoryKilled = env(ConfigKey.FACTORY_KILL).trim().lowercase() in setOf("1", "true", "yes"),
        )
    }
}
