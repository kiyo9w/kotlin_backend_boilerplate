# Benchmarks

Measured on this machine in memory mode (blank `DATABASE_URL`),
launched from the installed distribution (`:server:installDist`), timing from
process start to the first `200` on `/health`, resident memory read 3 seconds
after readiness with `ps -o rss=`.

| Service | Startup to `/health` | Resident memory |
| --- | --- | --- |
| The reference product's server, memory mode | 1217 ms | 73 MB |
| This template's server, memory mode | 1785 ms | 33 MB |

Both are a JVM (OpenJDK 21) + Ktor Netty. The template's smaller footprint is
the smaller surface: no product domain loaded. The startup delta is JIT warmth
and first-run class loading, not a structural difference; a second run narrows
it.

**Method.** `DATABASE_URL= bin/server`, poll `/health` every 100 ms, then
`ps -o rss=`. One run each; this is a smoke benchmark, not a statistically
sound one. Re-measure before drawing conclusions.

**Comparison notes.** If startup time becomes a product constraint, measure
compile-time DI alternatives (such as Micronaut) on the same host and add the
row here.
