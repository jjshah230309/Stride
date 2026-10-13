package com.jashith.stride.core.model

import com.jashith.stride.core.geo.Smooth
import com.jashith.stride.core.util.swiftRoundedToInt
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** One recorded sample. Keys are short because a three-hour ride holds ~10,000 of these. */
@Serializable
data class TrackPoint(
    val t: Double,              // seconds since activity start
    val lat: Double,
    val lon: Double,
    val alt: Double,            // metres, smoothed
    val d: Double,              // cumulative distance in metres
    val v: Double,              // speed in m/s
    val hr: Double? = null,     // beats per minute
    val cad: Double? = null,    // steps or revolutions per minute
    val pw: Double? = null,     // watts (measured or estimated)
    val moving: Boolean = true,
    val acc: Double = 5.0,      // horizontal accuracy in metres
) {
    val coord: Coord get() = Coord(lat, lon)
}

/** Per-sample series pulled out of a track, so analysis code can work on flat arrays. */
class Streams(points: List<TrackPoint> = emptyList()) {
    val time: DoubleArray = DoubleArray(points.size) { points[it].t }
    val distance: DoubleArray = DoubleArray(points.size) { points[it].d }
    val coords: List<Coord> = points.map { it.coord }
    val altitude: DoubleArray = DoubleArray(points.size) { points[it].alt }
    val speed: DoubleArray = DoubleArray(points.size) { points[it].v }
    val heartRate: List<Double?> = points.map { it.hr }
    val cadence: List<Double?> = points.map { it.cad }
    val power: List<Double?> = points.map { it.pw }
    val moving: BooleanArray = BooleanArray(points.size) { points[it].moving }

    val count: Int get() = time.size
    val isEmpty: Boolean get() = time.isEmpty()

    val hasHeartRate: Boolean get() = heartRate.any { it != null }
    val hasPower: Boolean get() = power.any { it != null }
    val hasCadence: Boolean get() = cadence.any { it != null }

    /** Grade at each sample, smoothed over roughly [windowMeters] to keep GPS noise out. */
    fun grade(windowMeters: Double = 30.0): DoubleArray {
        if (count <= 2) return DoubleArray(count)
        val out = DoubleArray(count)
        var lo = 0
        for (i in 0 until count) {
            while (lo < i && distance[i] - distance[lo] > windowMeters) lo += 1
            var hi = i
            while (hi < count - 1 && distance[hi] - distance[i] < windowMeters) hi += 1
            val run = distance[hi] - distance[lo]
            if (run > 3) {
                out[i] = (altitude[hi] - altitude[lo]) / run
            }
            out[i] = max(-0.45, min(0.45, out[i]))
        }
        return Smooth.movingAverage(out, window = 5)
    }

    companion object {
        /** Resample a nullable series onto a fixed 1 Hz grid, filling gaps by holding the last value. */
        fun toOneHertz(values: List<Double?>, time: DoubleArray, duration: Double): DoubleArray {
            val n = max(1, duration.swiftRoundedToInt())
            val out = DoubleArray(n)
            if (time.isEmpty()) return out
            var idx = 0
            var last = 0.0
            for (second in 0 until n) {
                val target = second.toDouble()
                while (idx < time.size - 1 && time[idx + 1] <= target) idx += 1
                val v = values[min(idx, values.size - 1)]
                if (v != null) last = v
                out[second] = last
            }
            return out
        }
    }
}
