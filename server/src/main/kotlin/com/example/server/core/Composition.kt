package com.example.server.core

import javax.sql.DataSource

/**
 * Generic composition seam. One function, one data class, explicit degradation.
 *
 * This is the shape the brief calls "already the best thing in the server" —
 * kept exactly. A blank database URL degrades to the explicit local-only memory
 * mode; the names and comments say so everywhere.
 *
 * A product extends this with its own members (the reference product adds `ledger`, `webhooks`,
 * `receipts`, `identities`). The template ships this base; the product ships
 * its own composition function that calls [coreCompositionFromEnv] and adds
 * product-specific wiring.
 */
data class CoreComposition(
    val jobStore: JobStore,
    val spendGate: SpendGate,
    val worker: Worker?,
    /** Durable scheduler over the same queue. Enqueues; the worker executes. */
    val scheduler: Scheduler,
    /** SQL-mode pool so tests can close it across a simulated restart. Null in memory mode. */
    val dataSource: DataSource? = null,
    /** True when running in the explicit local-only memory mode. */
    val memoryMode: Boolean = false,
)

/**
 * Build the core composition from the environment. A blank [env]("DATABASE_URL")
 * selects the explicit local-only memory mode; any other value selects SQL.
 *
 * @param env environment reader (the reference product: `ServerEnv::get`)
 * @param registry job handler registry built by the product
 * @param spendGateFactory builds the spend gate for the selected mode
 */
fun coreCompositionFromEnv(
    env: (String) -> String,
    registry: JobHandlerRegistry,
    spendGateFactory: (Boolean) -> SpendGate,
): CoreComposition {
    val url = env("DATABASE_URL")
    if (url.isBlank()) {
        val store = MemoryJobStore()
        return CoreComposition(
            jobStore = store,
            spendGate = spendGateFactory(false),
            worker = Worker(store, registry),
            scheduler = Scheduler(MemoryScheduleStore(), store),
            memoryMode = true,
        )
    }
    val dataSource = openCoreDataSource(
        jdbcUrl = url,
        user = env("DATABASE_USER").ifBlank { "postgres" },
        password = env("DATABASE_PASSWORD"),
    )
    val store = SqlJobStore()
    return CoreComposition(
        jobStore = store,
        spendGate = spendGateFactory(true),
        worker = Worker(store, registry),
        scheduler = Scheduler(SqlScheduleStore(), store),
        dataSource = dataSource,
        memoryMode = false,
    )
}

/**
 * Hikari + Flyway + Exposed connect for the core store. Lifted from the reference product's
 * `openDataSource` — the shape is kept exactly.
 */
fun openCoreDataSource(
    jdbcUrl: String,
    user: String,
    password: String,
    maxPool: Int = 4,
): DataSource {
    val config = com.zaxxer.hikari.HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        username = user
        this.password = password
        maximumPoolSize = maxPool
        isAutoCommit = false
        driverClassName = when {
            jdbcUrl.startsWith("jdbc:h2") -> "org.h2.Driver"
            else -> "org.postgresql.Driver"
        }
    }
    val dataSource = com.zaxxer.hikari.HikariDataSource(config)
    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .load()
        .migrate()
    org.jetbrains.exposed.v1.jdbc.Database.connect(dataSource)
    return dataSource
}
