package com.example.server

import java.nio.file.Files
import java.nio.file.Path

/** Reads gitignored .env files, then process env. */
object ServerEnv {
    private val file: Map<String, String> = load()

    fun get(name: String): String =
        file[name]?.takeIf { it.isNotBlank() } ?: System.getenv(name).orEmpty()

    private fun load(): Map<String, String> {
        val cwd = Path.of("").toAbsolutePath()
        val candidates = listOf(
            cwd.resolve(".env"),
            cwd.resolve("mobile/.env"),
            cwd.parent?.resolve(".env"),
            cwd.parent?.resolve("mobile/.env"),
        ).filterNotNull()
        val out = linkedMapOf<String, String>()
        candidates.filter { Files.isRegularFile(it) }.forEach { path ->
            Files.readAllLines(path).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val split = line.indexOf('=')
                if (split <= 0) return@forEach
                val key = line.take(split).trim()
                var value = line.substring(split + 1).trim()
                if (value.length >= 2 && value.first() == value.last() && (value.first() == '"' || value.first() == '\'')) {
                    value = value.substring(1, value.lastIndex)
                }
                out.putIfAbsent(key, value)
            }
        }
        return out
    }
}
