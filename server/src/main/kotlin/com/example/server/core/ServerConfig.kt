package com.example.server.core

/**
 * Every environment key this service binds. `ConfigExampleTest` asserts each
 * one appears in both `.env.example` and `.env.production.example`, which is
 * what stops the documented pair from rotting behind the code.
 */
object ConfigKey {
    const val DATABASE_URL = "DATABASE_URL"
    const val MODEL_API_KEY = "MODEL_API_KEY"
    const val MODEL_BASE_URL = "MODEL_BASE"
    const val MODEL_NAME = "MODEL_NAME"
    const val MODEL_CONTEXT_TOKENS = "MODEL_CONTEXT_TOKENS"
    const val MODEL_MAX_OUTPUT_TOKENS = "MODEL_MAX_OUTPUT_TOKENS"
    const val DAILY_SEED_CAP = "APP_DAILY_SEED_CAP"
    const val DAILY_TALK_CAP = "APP_DAILY_TALK_CAP"
    const val FACTORY_KILL = "APP_FACTORY_KILL"
    const val STORE_EVENTS_TRUST_UNVERIFIED = "APP_STORE_EVENTS_TRUST_UNVERIFIED"
    const val APPLE_ROOT_CA_PEM = "APPLE_ROOT_CA_PEM"
    const val APPLE_ROOT_CA_PATH = "APPLE_ROOT_CA_PATH"
    const val WEBHOOK_SECRET = "APP_WEBHOOK_SECRET"

    /** Every bound key, in the order the examples document them. */
    val all: List<String> = listOf(
        DATABASE_URL,
        MODEL_API_KEY,
        MODEL_BASE_URL,
        MODEL_NAME,
        MODEL_CONTEXT_TOKENS,
        MODEL_MAX_OUTPUT_TOKENS,
        DAILY_SEED_CAP,
        DAILY_TALK_CAP,
        FACTORY_KILL,
        STORE_EVENTS_TRUST_UNVERIFIED,
        APPLE_ROOT_CA_PEM,
        APPLE_ROOT_CA_PATH,
        WEBHOOK_SECRET,
    )
}

/** Defaults stated in code, never at a call site. */
object ConfigDefaults {
    const val SEEDS_PER_DAY = 24
    const val TALK_PER_DAY = 120
    const val MODEL_BASE_URL = "https://api.openai.com/v1"
    const val MODEL_NAME = "gpt-4o-mini"

    /**
     * The model's context window and the output space reserved inside it. The
     * window only bounds the prompt budget; the enforced input limit stays the
     * character bound in `SeedWork`, so an allowed input is never silently
     * trimmed. These are defaults for the locked model and can be overridden
     * per deployment, because a hardcoded window that does not match the model
     * makes the budget decorative.
     */
    const val CONTEXT_TOKENS = 128_000
    const val MAX_OUTPUT_TOKENS = 4_096
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
data class ModelConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    /** The model's context window, used to bound the prompt budget. */
    val contextTokens: Int = ConfigDefaults.CONTEXT_TOKENS,
    /** Output space reserved inside the window on every call. */
    val maxOutputTokens: Int = ConfigDefaults.MAX_OUTPUT_TOKENS,
) {
    val configured: Boolean get() = apiKey.isNotBlank()

    companion object {
        fun from(env: (String) -> String): ModelConfig {
            val contextTokens = env(ConfigKey.MODEL_CONTEXT_TOKENS)
                .positiveIntOrDefault(ConfigDefaults.CONTEXT_TOKENS)
            val maxOutputTokens = env(ConfigKey.MODEL_MAX_OUTPUT_TOKENS)
                .positiveIntOrDefault(ConfigDefaults.MAX_OUTPUT_TOKENS)
            // A window smaller than the reserved output is a misconfiguration;
            // fall back to the pair of defaults rather than building a budget
            // that can never fit a prompt.
            val sane = contextTokens > maxOutputTokens
            return ModelConfig(
                apiKey = env(ConfigKey.MODEL_API_KEY).trim(),
                baseUrl = env(ConfigKey.MODEL_BASE_URL).trim().ifBlank { ConfigDefaults.MODEL_BASE_URL },
                model = env(ConfigKey.MODEL_NAME).trim().ifBlank { ConfigDefaults.MODEL_NAME },
                contextTokens = if (sane) contextTokens else ConfigDefaults.CONTEXT_TOKENS,
                maxOutputTokens = if (sane) maxOutputTokens else ConfigDefaults.MAX_OUTPUT_TOKENS,
            )
        }
    }
}

private fun String.positiveIntOrDefault(default: Int): Int =
    trim().toIntOrNull()?.takeIf { it > 0 } ?: default

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

/** Webhook intake. A blank secret means every delivery is refused, not trusted. */
data class WebhookConfig(val secret: String) {
    companion object {
        fun from(env: (String) -> String): WebhookConfig =
            WebhookConfig(env(ConfigKey.WEBHOOK_SECRET).trim())
    }
}

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
    val webhooks: WebhookConfig,
    val factoryKilled: Boolean,
) {
    companion object {
        fun fromEnv(env: (String) -> String): ServerConfig = ServerConfig(
            database = DatabaseConfig.from(env),
            model = ModelConfig.from(env),
            caps = RateCaps.from(env),
            storeEvents = StoreEventsConfig.from(env),
            webhooks = WebhookConfig.from(env),
            factoryKilled = env(ConfigKey.FACTORY_KILL).trim().lowercase() in setOf("1", "true", "yes"),
        )
    }
}
