package com.jashith.stride.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Date formatting for screens. The number formatting lives in core's `Fmt`; this is the half that needs a locale. */
object DateFmt {
    private fun f(pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())

    private val day get() = f("EEEE, d MMMM")
    private val shortDayF get() = f("d MMM")
    private val month get() = f("MMMM yyyy")
    private val weekday get() = f("EEEE")
    private val time get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    private val longDate get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

    private fun zone(): ZoneId = ZoneId.systemDefault()

    fun day(date: Instant): String = day.format(date.atZone(zone()))
    fun day(date: LocalDate): String = day.format(date)
    fun shortDay(date: Instant): String = shortDayF.format(date.atZone(zone()))
    fun shortDay(date: LocalDate): String = shortDayF.format(date)
    fun month(date: Instant): String = month.format(date.atZone(zone()))
    fun month(date: LocalDate): String = month.format(date)
    fun time(date: Instant): String = time.format(date.atZone(zone()))
    fun medium(date: Instant): String = longDate.format(date.atZone(zone()))
    fun medium(date: LocalDate): String = longDate.format(date)
    fun weekday(date: Instant): String = weekday.format(date.atZone(zone()))

    /** "Today at 7:42 AM", "Yesterday at ...", a weekday within the last week, otherwise a short date. */
    fun relativeDay(date: Instant, now: Instant = Instant.now()): String {
        val z = zone()
        val d = date.atZone(z).toLocalDate()
        val today = now.atZone(z).toLocalDate()
        val t = time(date)
        if (d == today) return "Today at $t"
        if (d == today.minusDays(1)) return "Yesterday at $t"
        val days = ChronoUnit.DAYS.between(d, today)
        if (days in 0..6) return weekday(date) + " at " + t
        return shortDay(date) + " at " + t
    }
}
