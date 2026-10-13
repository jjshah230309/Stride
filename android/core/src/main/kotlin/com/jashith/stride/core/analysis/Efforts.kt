package com.jashith.stride.core.analysis

import com.jashith.stride.core.model.BestEffort
import com.jashith.stride.core.model.HeartRateZones
import com.jashith.stride.core.model.PaceZones
import com.jashith.stride.core.model.PowerCurvePoint
import com.jashith.stride.core.model.PowerZones
import com.jashith.stride.core.model.Split
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Streams

object BestEffortFinder {

    val runDistances: List<Pair<String, Double>> = listOf(
        "400 m" to 400.0, "1/2 mile" to 804.672, "1 km" to 1000.0, "1 mile" to 1609.344,
        "2 mile" to 3218.688, "5 km" to 5000.0, "10 km" to 10000.0, "15 km" to 15000.0,
        "10 mile" to 16093.44, "20 km" to 20000.0, "Half Marathon" to 21097.5,
        "30 km" to 30000.0, "Marathon" to 42195.0,
    )

    val walkDistances: List<Pair<String, Double>> = listOf(
        "1 km" to 1000.0, "1 mile" to 1609.344, "5 km" to 5000.0, "10 km" to 10000.0,
    )

    private class Best(val time: Double, val start: Int, val end: Int)

    /**
     * Fastest window covering each standard distance. A two-pointer sweep over the cumulative
     * distance array, so this stays linear per distance.
     */
    fun find(streams: Streams, sport: SportType): List<BestEffort> {
        if (streams.count <= 2) return emptyList()
        val table = if (sport.isRun) runDistances else if (sport.isFoot) walkDistances else emptyList()
        if (table.isEmpty()) return emptyList()
        val total = streams.distance.lastOrNull() ?: 0.0
        val results = ArrayList<BestEffort>()

        for ((name, target) in table) {
            if (!(target <= total)) continue
            var best: Best? = null
            var start = 0
            for (end in 1 until streams.count) {
                while (start < end && streams.distance[end] - streams.distance[start] >= target) {
                    val elapsed = streams.time[end] - streams.time[start]
                    // Interpolate the overshoot so a sample landing past the mark is not penalised.
                    val covered = streams.distance[end] - streams.distance[start]
                    val overshoot = covered - target
                    var adjusted = elapsed
                    if (overshoot > 0 && end > 0) {
                        val segmentDistance = streams.distance[end] - streams.distance[end - 1]
                        val segmentTime = streams.time[end] - streams.time[end - 1]
                        if (segmentDistance > 0) {
                            adjusted = elapsed - (overshoot / segmentDistance) * segmentTime
                        }
                    }
                    val current = best
                    if (adjusted > 0 && (current == null || adjusted < current.time)) {
                        best = Best(adjusted, start, end)
                    }
                    start += 1
                }
            }
            val b = best
            if (b != null && b.time > 0) {
                results.add(
                    BestEffort(
                        name = name, distance = target, time = b.time,
                        startIndex = b.start, endIndex = b.end, startOffset = streams.time[b.start],
                    ),
                )
            }
        }
        return results
    }
}

object PowerCurveBuilder {

    val durations: List<Double> = listOf(
        1.0, 5.0, 10.0, 15.0, 20.0, 30.0, 45.0, 60.0, 120.0, 180.0, 300.0, 480.0, 600.0, 720.0, 900.0,
        1200.0, 1800.0, 2700.0, 3600.0, 5400.0, 7200.0, 10800.0, 14400.0,
    )

    /** Best rolling average power for each duration, from a 1 Hz watts series. */
    fun build(wattsPerSecond: DoubleArray): List<PowerCurvePoint> {
        if (wattsPerSecond.size <= 1) return emptyList()
        val prefix = DoubleArray(wattsPerSecond.size + 1)
        for (i in wattsPerSecond.indices) prefix[i + 1] = prefix[i] + wattsPerSecond[i]

        val out = ArrayList<PowerCurvePoint>()
        for (d in durations) {
            val window = d.toInt()
            if (window > wattsPerSecond.size) continue
            var best = 0.0
            var i = 0
            while (i + window <= wattsPerSecond.size) {
                val avg = (prefix[i + window] - prefix[i]) / window.toDouble()
                if (avg > best) best = avg
                i += 1
            }
            if (best > 0) out.add(PowerCurvePoint(d, best))
        }
        return out
    }

    /** The same idea applied to heart rate or speed, for the "best efforts" chart. */
    fun bestRollingAverage(series: DoubleArray, seconds: Int): Double? {
        if (!(seconds > 0 && series.size >= seconds)) return null
        var sum = 0.0
        for (k in 0 until seconds) sum += series[k]
        var best = sum
        var i = seconds
        while (i < series.size) {
            sum += series[i] - series[i - seconds]
            if (sum > best) best = sum
            i += 1
        }
        return best / seconds.toDouble()
    }

    /** Estimated functional threshold power: the best hour, else 95% of the best twenty minutes, else 90% of the best eight. */
    fun estimateFTP(curve: List<PowerCurvePoint>): Double? {
        curve.firstOrNull { it.duration == 3600.0 }?.let { return it.watts }
        curve.firstOrNull { it.duration == 1200.0 }?.let { return it.watts * 0.95 }
        curve.firstOrNull { it.duration == 480.0 }?.let { return it.watts * 0.90 }
        return null
    }

    /** Merge many curves into an all-time best. */
    fun combine(curves: List<List<PowerCurvePoint>>): List<PowerCurvePoint> {
        val best = HashMap<Double, Double>()
        for (curve in curves) {
            for (point in curve) {
                best[point.duration] = maxOf(best[point.duration] ?: 0.0, point.watts)
            }
        }
        return best.map { PowerCurvePoint(it.key, it.value) }.sortedBy { it.duration }
    }
}

object SplitBuilder {

    /** Auto splits at every [unitMeters] of distance, plus a final partial split. */
    fun build(streams: Streams, unitMeters: Double): List<Split> {
        if (!(streams.count > 1 && unitMeters > 0)) return emptyList()
        val grades = streams.grade()
        val splits = ArrayList<Split>()
        var index = 1
        var startIdx = 0
        var nextMark = unitMeters

        fun finish(endIdx: Int, partial: Boolean) {
            if (endIdx <= startIdx) return
            var moving = 0.0
            var gain = 0.0
            var loss = 0.0
            var hrSum = 0.0; var hrCount = 0.0
            var pwSum = 0.0; var pwCount = 0.0
            var cadSum = 0.0; var cadCount = 0.0
            var equivalentDistance = 0.0
            var i = startIdx + 1
            while (i <= endIdx) {
                val dt = streams.time[i] - streams.time[i - 1]
                val dd = streams.distance[i] - streams.distance[i - 1]
                if (streams.moving[i] && dt > 0 && dt < 30) moving += dt
                val dz = streams.altitude[i] - streams.altitude[i - 1]
                if (dz > 0) gain += dz else loss += -dz
                streams.heartRate[i]?.let { hrSum += it; hrCount += 1 }
                streams.power[i]?.let { pwSum += it; pwCount += 1 }
                streams.cadence[i]?.let { cadSum += it; cadCount += 1 }
                if (dd > 0) equivalentDistance += dd * GradeAdjustedPace.factor(grades[i])
                i += 1
            }
            val distance = streams.distance[endIdx] - streams.distance[startIdx]
            val elapsed = streams.time[endIdx] - streams.time[startIdx]
            var gap: Double? = null
            if (equivalentDistance > 1 && moving > 0) gap = moving / (equivalentDistance / unitMeters)
            splits.add(
                Split(
                    index = index,
                    distance = distance,
                    elapsed = elapsed,
                    moving = if (moving > 0) moving else elapsed,
                    elevGain = gain,
                    elevLoss = loss,
                    avgHR = if (hrCount > 0) hrSum / hrCount else null,
                    avgPower = if (pwCount > 0) pwSum / pwCount else null,
                    avgCadence = if (cadCount > 0) cadSum / cadCount else null,
                    gapSeconds = gap,
                    isPartial = partial,
                ),
            )
            index += 1
        }

        for (i in 1 until streams.count) {
            while (streams.distance[i] >= nextMark) {
                finish(i, partial = false)
                startIdx = i
                nextMark += unitMeters
            }
        }
        val last = streams.distance.lastOrNull()
        if (last != null && last - streams.distance[startIdx] > unitMeters * 0.02) {
            finish(streams.count - 1, partial = true)
        }
        // Smooth the elevation figures: raw per-sample deltas overstate the climbing.
        return splits.map { it.copy(elevGain = it.elevGain * 0.75, elevLoss = it.elevLoss * 0.75) }
    }
}

object ZoneAnalysis {

    /** Seconds spent in each heart-rate zone. */
    fun heartRateTimes(streams: Streams, zones: HeartRateZones): List<Double> {
        val out = DoubleArray(5)
        if (streams.count <= 1) return out.toList()
        for (i in 1 until streams.count) {
            val hr = streams.heartRate[i] ?: continue
            val dt = streams.time[i] - streams.time[i - 1]
            if (!(dt > 0 && dt < 30)) continue
            out[zones.zone(hr)] += dt
        }
        return out.toList()
    }

    fun powerTimes(streams: Streams, zones: PowerZones): List<Double> {
        val out = DoubleArray(zones.bounds.size)
        if (streams.count <= 1) return out.toList()
        for (i in 1 until streams.count) {
            val pw = streams.power[i] ?: continue
            val dt = streams.time[i] - streams.time[i - 1]
            if (!(dt > 0 && dt < 30)) continue
            out[zones.zone(pw)] += dt
        }
        return out.toList()
    }

    fun paceTimes(streams: Streams, zones: PaceZones): List<Double> {
        val out = DoubleArray(PaceZones.names.size)
        if (streams.count <= 1) return out.toList()
        for (i in 1 until streams.count) {
            val dt = streams.time[i] - streams.time[i - 1]
            if (!(dt > 0 && dt < 30 && streams.moving[i] && streams.speed[i] > 0.4)) continue
            val secondsPerKm = 1000.0 / streams.speed[i]
            out[zones.zone(secondsPerKm)] += dt
        }
        return out.toList()
    }
}
