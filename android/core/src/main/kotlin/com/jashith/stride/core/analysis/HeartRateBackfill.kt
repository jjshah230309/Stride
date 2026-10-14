package com.jashith.stride.core.analysis

import com.jashith.stride.core.model.TrackPoint
import java.time.Instant
import kotlin.math.abs

/** One heart rate reading from a health platform. */
data class HeartRateSample(val time: Instant, val bpm: Double, val fromWatch: Boolean)

/**
 * Fills the gaps in a recorded track from heart rate readings the health platform collected meanwhile
 * (a watch that synced late, say). A port of iOS `HealthKitManager.applyHeartRate`.
 */
object HeartRateBackfill {

    data class Result(val points: List<TrackPoint>, val filled: Int, val fromWatch: Boolean) {
        /** Share of the track that now has a heart rate from this pass, 0 to 1. */
        val coverage: Double get() = if (points.isEmpty()) 0.0 else filled.toDouble() / points.size
    }

    /** Readings below or at 30, or at 240 and above, are sensor noise. */
    fun isPlausible(bpm: Double): Boolean = bpm > 30 && bpm < 240

    /**
     * Only points without a heart rate are touched. [samples] must be ordered by time. A point takes the
     * nearest sample, provided it is within [toleranceSeconds].
     */
    fun apply(
        samples: List<HeartRateSample>,
        points: List<TrackPoint>,
        activityStart: Instant,
        toleranceSeconds: Double = 20.0,
    ): Result {
        if (samples.isEmpty() || points.isEmpty()) return Result(points, 0, false)

        val updated = points.toMutableList()
        var filled = 0
        var watchSeen = false
        var index = 0

        fun offset(sample: HeartRateSample, target: Instant): Double =
            abs((sample.time.epochSecond - target.epochSecond).toDouble() + (sample.time.nano - target.nano) / 1e9)

        for (i in updated.indices) {
            if (updated[i].hr != null) continue
            val whole = Math.floor(updated[i].t).toLong()
            val target = activityStart.plusSeconds(whole)
                .plusNanos(((updated[i].t - whole) * 1e9).toLong())
            while (index + 1 < samples.size && offset(samples[index + 1], target) <= offset(samples[index], target)) {
                index += 1
            }
            val candidate = samples[index]
            if (offset(candidate, target) <= toleranceSeconds) {
                updated[i] = updated[i].copy(hr = candidate.bpm)
                filled += 1
                if (candidate.fromWatch) watchSeen = true
            }
        }
        return Result(updated, filled, watchSeen)
    }
}
