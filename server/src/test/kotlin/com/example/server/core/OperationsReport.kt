package com.example.server.core

import javax.sql.DataSource
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs an analytics SQL report against a database and returns its
 * stage-to-metric rows. Shared by the report test and the recovery drill, so the
 * execution rule — strip comment lines, split on `;`, read `stage`/`metric` —
 * lives in one place instead of drifting between copies.
 */
internal object OperationsReport {
    fun run(ds: DataSource, resource: String = "analytics/operations.sql"): Map<String, Double> {
        val script = OperationsReport::class.java.classLoader.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        assertNotNull(script, "$resource is missing from the server classpath")
        // Strip comment lines before splitting on ";", so a stray semicolon in
        // prose cannot silently cut a statement in half.
        val anonymous = script.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
        val statements = anonymous.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(statements.isNotEmpty(), "$resource must contain at least one statement")

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
