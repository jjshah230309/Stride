package com.jashith.stride.core.model

import com.jashith.stride.core.util.fixed
import com.jashith.stride.core.util.fmt
import com.jashith.stride.core.util.swiftRounded
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class UnitSystem {
    @SerialName("metric") Metric,
    @SerialName("imperial") Imperial;

    val title: String get() = if (this == Metric) "Metric (km)" else "Imperial (mi)"

    val distanceUnit: String get() = if (this == Metric) "km" else "mi"
    val elevationUnit: String get() = if (this == Metric) "m" else "ft"
    val speedUnit: String get() = if (this == Metric) "km/h" else "mph"
    val paceUnit: String get() = if (this == Metric) "/km" else "/mi"
    val weightUnit: String get() = if (this == Metric) "kg" else "lb"
    val shortUnit: String get() = if (this == Metric) "m" else "ft"

    /** Meters in one display distance unit. */
    val metersPerUnit: Double get() = if (this == Metric) 1000.0 else 1609.344

    /** Meters in one display elevation unit. */
    val metersPerElevationUnit: Double get() = if (this == Metric) 1.0 else 0.3048

    /** Kilograms in one display weight unit. */
    val kilogramsPerWeightUnit: Double get() = if (this == Metric) 1.0 else 0.45359237

    val spokenDistanceUnit: String get() = if (this == Metric) "kilometres" else "miles"
    val spokenDistanceUnitSingular: String get() = if (this == Metric) "kilometre" else "mile"
    val spokenElevationUnit: String get() = if (this == Metric) "metres" else "feet"
    val spokenSpeedUnit: String get() = if (this == Metric) "kilometres per hour" else "miles per hour"

    /** Shorter than "per hour" but still unambiguous. */
    val shortSpokenSpeedUnit: String get() = if (this == Metric) "kilometres an hour" else "miles an hour"
}

/** Number formatting, matching the iOS `Fmt` byte for byte. Date formatting lives in the app module. */
object Fmt {

    // Distance

    fun distanceValue(meters: Double, u: UnitSystem): Double = meters / u.metersPerUnit

    fun distance(meters: Double, u: UnitSystem, decimals: Int = 2): String =
        fixed(distanceValue(meters, u), decimals)

    fun distanceWithUnit(meters: Double, u: UnitSystem, decimals: Int = 2): String =
        distance(meters, u, decimals) + " " + u.distanceUnit

    fun elevationValue(meters: Double, u: UnitSystem): Double = meters / u.metersPerElevationUnit

    fun elevation(meters: Double, u: UnitSystem): String = fixed(elevationValue(meters, u), 0)

    fun elevationWithUnit(meters: Double, u: UnitSystem): String = elevation(meters, u) + " " + u.elevationUnit

    // Time

    /** 1:02:03 for hours, 5:03 below an hour. */
    fun duration(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "--:--"
        val total = seconds.swiftRounded().toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) fmt("%d:%02d:%02d", h, m, s) else fmt("%d:%02d", m, s)
    }

    /** Always h:mm:ss, padded. Used where column alignment matters. */
    fun durationPadded(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "--:--:--"
        val total = seconds.swiftRounded().toInt()
        return fmt("%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    /** "1h 24m" style, for summaries. */
    fun durationCompact(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "0m"
        val total = seconds.swiftRounded().toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        if (h > 0) return "${h}h ${m}m"
        if (m > 0) return "${m}m"
        return "${total}s"
    }

    // Pace and speed

    /** Seconds needed to cover one display unit of distance. */
    fun paceSeconds(distanceMeters: Double, timeSeconds: Double, u: UnitSystem): Double? {
        if (!(distanceMeters > 1 && timeSeconds > 0)) return null
        return timeSeconds / (distanceMeters / u.metersPerUnit)
    }

    fun pace(distanceMeters: Double, timeSeconds: Double, u: UnitSystem): String {
        val p = paceSeconds(distanceMeters, timeSeconds, u)
        if (p == null || !p.isFinite() || p >= 60 * 99) return "--:--"
        return paceFromSeconds(p)
    }

    fun paceFromSeconds(secondsPerUnit: Double): String {
        if (!secondsPerUnit.isFinite() || secondsPerUnit <= 0 || secondsPerUnit >= 60 * 99) return "--:--"
        val total = secondsPerUnit.swiftRounded().toInt()
        return fmt("%d:%02d", total / 60, total % 60)
    }

    /** Instantaneous pace from a speed in m/s. */
    fun paceFromSpeed(mps: Double, u: UnitSystem): String {
        if (!(mps > 0.15)) return "--:--"
        return paceFromSeconds(u.metersPerUnit / mps)
    }

    fun speedValue(mps: Double, u: UnitSystem): Double = mps * 3600.0 / u.metersPerUnit

    fun speed(mps: Double, u: UnitSystem, decimals: Int = 1): String {
        if (!mps.isFinite() || mps < 0) return "0.0"
        return fixed(speedValue(mps, u), decimals)
    }

    fun avgSpeed(distanceMeters: Double, timeSeconds: Double, u: UnitSystem): String {
        if (!(timeSeconds > 0)) return "0.0"
        return speed(distanceMeters / timeSeconds, u)
    }

    // Scalars

    fun int(v: Double?): String = if (v == null || !v.isFinite()) "--" else fixed(v, 0)

    fun one(v: Double?): String = if (v == null || !v.isFinite()) "--" else fixed(v, 1)

    /** 1.0 reads as "1", 0.5 as "0.5": no trailing zeros in interval labels. */
    fun trimmed(v: Double): String {
        if (abs(v - v.swiftRounded()) < 0.001) return v.swiftRounded().toInt().toString()
        var text = fixed(v, 2)
        while (text.endsWith("0")) text = text.dropLast(1)
        if (text.endsWith(".")) text = text.dropLast(1)
        return text
    }

    fun percent(v: Double): String = fixed(v * 100, 0) + "%"

    fun signed(v: Double, decimals: Int = 0): String {
        val s = fixed(abs(v), decimals)
        if (v > 0) return "+$s"
        if (v < 0) return "-$s"
        return s
    }
}
