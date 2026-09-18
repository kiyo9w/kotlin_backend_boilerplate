package com.example.server.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.WeekFields

/**
 * The cadences a durable schedule supports. Full cron is deliberately not built:
 * a product needs daily / weekly / monthly / yearly batched content jobs, the
 * brief names those four, and [java.time] computes all four exactly with no new
 * dependency (`the reference ranking` ranks no cron library as a depend). Adding a
 * fifth cadence is a small, tested addition here rather than a parser.
 */
enum class Cadence {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

/**
 * When a schedule fires, as a civil time in a named zone.
 *
 * A "day" is a civil day in [ZoneId], never a UTC day: 09:00 in
 * `Asia/Tokyo` fires at 00:00Z, and its period key is the Tokyo date. A time
 * that does not exist on a DST transition day (a spring-forward gap) resolves
 * forward to the next valid instant, which is [ZonedDateTime]'s documented
 * behaviour; a time that occurs twice resolves to the first occurrence.
 */
data class ScheduleSpec(
    val cadence: Cadence,
    val hour: Int,
    val minute: Int,
    /** Required for [Cadence.WEEKLY]. */
    val dayOfWeek: DayOfWeek? = null,
    /** Required for [Cadence.MONTHLY] and [Cadence.YEARLY]. Clamped to the month length. */
    val dayOfMonth: Int? = null,
    /** Required for [Cadence.YEARLY]. */
    val month: Month? = null,
) {
    init {
        require(hour in 0..23) { "hour must be 0..23, got $hour" }
        require(minute in 0..59) { "minute must be 0..59, got $minute" }
        require(dayOfMonth == null || dayOfMonth in 1..31) { "dayOfMonth must be 1..31" }
        when (cadence) {
            Cadence.DAILY -> require(dayOfWeek == null && dayOfMonth == null && month == null) {
                "DAILY takes no day or month fields"
            }
            Cadence.WEEKLY -> require(dayOfWeek != null && dayOfMonth == null && month == null) {
                "WEEKLY needs dayOfWeek and nothing else"
            }
            Cadence.MONTHLY -> require(dayOfMonth != null && dayOfWeek == null && month == null) {
                "MONTHLY needs dayOfMonth and nothing else"
            }
            Cadence.YEARLY -> require(dayOfMonth != null && month != null && dayOfWeek == null) {
                "YEARLY needs month and dayOfMonth"
            }
        }
    }

    /** The first firing strictly after [afterEpochMs]. */
    fun firstOccurrenceAfter(afterEpochMs: Long, zone: ZoneId): Long {
        var anchor = LocalDate.ofInstant(Instant.ofEpochMilli(afterEpochMs), zone)
        repeat(MAX_PERIOD_STEPS) {
            val candidate = candidateInstant(anchor, zone)
            if (candidate > afterEpochMs) return candidate
            anchor = nextAnchor(anchor)
        }
        error("no occurrence within $MAX_PERIOD_STEPS periods")
    }

    /** The most recent firing at or before [nowEpochMs]. */
    fun lastOccurrenceAtOrBefore(nowEpochMs: Long, zone: ZoneId): Long {
        var anchor = LocalDate.ofInstant(Instant.ofEpochMilli(nowEpochMs), zone)
        repeat(MAX_PERIOD_STEPS) {
            val candidate = candidateInstant(anchor, zone)
            if (candidate <= nowEpochMs) return candidate
            anchor = previousAnchor(anchor)
        }
        error("no occurrence within $MAX_PERIOD_STEPS periods")
    }

    /**
     * The deterministic identity of one period, used in the job dedupe key.
     * Two ticks that decide to fire the same period produce the same key, so
     * the queue collapses them to one job.
     */
    fun periodKey(occurrenceEpochMs: Long, zone: ZoneId): String {
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(occurrenceEpochMs), zone)
        return when (cadence) {
            Cadence.DAILY -> at.toLocalDate().toString()
            Cadence.WEEKLY -> "%04d-W%02d".format(
                at.get(WeekFields.ISO.weekBasedYear()),
                at.get(WeekFields.ISO.weekOfWeekBasedYear()),
            )
            Cadence.MONTHLY -> "%04d-%02d".format(at.year, at.monthValue)
            Cadence.YEARLY -> "%04d".format(at.year)
        }
    }

    private fun candidateInstant(anchor: LocalDate, zone: ZoneId): Long =
        occurrenceDate(anchor).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** The firing date inside the period that contains [anchor]. */
    private fun occurrenceDate(anchor: LocalDate): LocalDate = when (cadence) {
        Cadence.DAILY -> anchor
        Cadence.WEEKLY -> {
            val weekStart = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
            weekStart.plusDays((dayOfWeek!!.value - 1).toLong())
        }
        Cadence.MONTHLY -> anchor.withDayOfMonth(minOf(dayOfMonth!!, anchor.lengthOfMonth()))
        Cadence.YEARLY -> {
            val length = YearMonth.of(anchor.year, month!!).lengthOfMonth()
            LocalDate.of(anchor.year, month, minOf(dayOfMonth!!, length))
        }
    }

    private fun nextAnchor(anchor: LocalDate): LocalDate = when (cadence) {
        Cadence.DAILY -> anchor.plusDays(1)
        Cadence.WEEKLY -> anchor.plusWeeks(1)
        Cadence.MONTHLY -> anchor.plusMonths(1).withDayOfMonth(1)
        Cadence.YEARLY -> anchor.plusYears(1).withDayOfYear(1)
    }

    private fun previousAnchor(anchor: LocalDate): LocalDate = when (cadence) {
        Cadence.DAILY -> anchor.minusDays(1)
        Cadence.WEEKLY -> anchor.minusWeeks(1)
        Cadence.MONTHLY -> anchor.minusMonths(1).withDayOfMonth(1)
        Cadence.YEARLY -> anchor.minusYears(1).withDayOfYear(1)
    }

    private companion object {
        const val MAX_PERIOD_STEPS = 400
    }
}
