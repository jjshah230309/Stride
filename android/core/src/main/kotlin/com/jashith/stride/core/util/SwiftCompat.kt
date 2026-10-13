package com.jashith.stride.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/**
 * Helpers that reproduce Swift/C behaviour the iOS app depends on, so numbers and strings
 * come out identically on both platforms.
 */

/** Swift's `Double.rounded()`: half away from zero. Kotlin's `round()` is half-to-even. */
fun Double.swiftRounded(): Double {
    if (!isFinite()) return this
    val t = truncate(this)
    return if (abs(this - t) >= 0.5) t + sign(this) else t
}

/** `Int(x.rounded())` */
fun Double.swiftRoundedToInt(): Int = swiftRounded().toInt()

/**
 * `String(format: "%.<decimals>f", value)`.
 *
 * C rounds the exact binary value half-to-even, whereas `java.util.Formatter` rounds the
 * shortest decimal representation half-up (so `%.1f` of 0.15 differs). BigDecimal(double)
 * is exact, which gives C's answer.
 */
fun fixed(value: Double, decimals: Int): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    val text = BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
    val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    return if (negative && !text.startsWith("-")) "-$text" else text
}

/** Integer-only `String.format` with a locale that cannot change digits or separators. */
fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.ROOT, pattern, *args)
