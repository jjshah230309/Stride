package com.jashith.stride.core.analysis

import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.geo.Smooth
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.AthleteProfile
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.Streams
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.util.fixed
import com.jashith.stride.core.util.swiftRounded
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Turns a raw track into a fully analysed activity. Everything the detail screen shows is computed
 * once, here, and stored with the activity.
 */
object ActivityAnalyzer {

    fun analyse(input: Activity, points: List<TrackPoint>, profile: AthleteProfile): Activity {
        var a = input
        val streams = Streams(points)
        if (streams.count <= 1) return a.copy(hasGPS = false)

        // Totals
        val distance = streams.distance.lastOrNull() ?: 0.0
        val elapsed = streams.time.lastOrNull() ?: 0.0
        val movingTime = movingTime(streams)
        val smoothedAltitude = Smooth.movingAverage(streams.altitude, window = 9)
        val change = Smooth.elevationChange(smoothedAltitude, threshold = 1.0)
        val maxSpeed = streams.speed.filter { it < a.sport.maxPlausibleSpeed }.maxOrNull() ?: 0.0
        a = a.copy(
            distance = distance, elapsed = elapsed, movingTime = movingTime,
            elevationGain = change.gain, elevationLoss = change.loss,
            maxElevation = smoothedAltitude.maxOrNull() ?: 0.0,
            minElevation = smoothedAltitude.minOrNull() ?: 0.0,
            maxSpeed = maxSpeed,
        )

        // Heart rate
        val hrValues = streams.heartRate.filterNotNull().filter { it > 30 && it < 240 }
        if (hrValues.isNotEmpty()) {
            a = a.copy(avgHR = hrValues.sum() / hrValues.size.toDouble(), maxHR = hrValues.max())
        }

        // Cadence. Running cadence is conventionally reported for both legs.
        val cadValues = streams.cadence.filterNotNull().filter { it > 5 }
        if (cadValues.isNotEmpty()) {
            a = a.copy(avgCadence = cadValues.sum() / cadValues.size.toDouble(), maxCadence = cadValues.max())
        }

        // Power
        val pwValues = streams.power.filterNotNull().filter { it >= 0 }
        if (pwValues.isNotEmpty()) {
            a = a.copy(avgPower = pwValues.sum() / pwValues.size.toDouble(), maxPower = pwValues.max())
            val oneHz = Streams.toOneHertz(streams.power, streams.time, a.elapsed)
            a = a.copy(normalizedPower = PowerModel.normalizedPower(oneHz))
            if (a.sport.isRide) a = a.copy(powerCurve = PowerCurveBuilder.build(oneHz))
        }

        // Zones
        a = a.copy(
            hrZoneTimes = ZoneAnalysis.heartRateTimes(streams, profile.hrZones),
            powerZoneTimes = ZoneAnalysis.powerTimes(streams, profile.powerZones),
            paceZoneTimes = ZoneAnalysis.paceTimes(streams, profile.paceZones),
        )

        // Splits and best efforts
        a = a.copy(
            splitsKm = SplitBuilder.build(streams, unitMeters = 1000.0),
            splitsMi = SplitBuilder.build(streams, unitMeters = 1609.344),
            bestEfforts = BestEffortFinder.find(streams, a.sport),
        )

        // Calories and load
        a = a.copy(calories = EffortScore.calories(profile, a.sport, a.movingTime, a.avgHR, a.avgSpeed))
        a = a.copy(relativeEffort = relativeEffort(a, streams, profile))
        a = a.copy(trainingLoad = trainingLoad(a, profile))

        // Map preview and route grouping
        val valid = streams.coords.filter { it.isValid }
        a = a.copy(
            hasGPS = valid.size > 5,
            previewPath = Geo.downsample(Geo.simplify(valid, tolerance = 6.0), 300),
            startCoord = valid.firstOrNull(),
            routeSignature = RouteSignature.compute(valid),
        )
        return a
    }

    fun movingTime(streams: Streams): Double {
        if (streams.count <= 1) return 0.0
        var total = 0.0
        for (i in 1 until streams.count) {
            val dt = streams.time[i] - streams.time[i - 1]
            if (!(dt > 0 && dt < 30)) continue
            if (streams.moving[i]) total += dt
        }
        return total
    }

    fun relativeEffort(activity: Activity, streams: Streams, profile: AthleteProfile): Double {
        if (streams.hasHeartRate) return EffortScore.relativeEffort(activity.hrZoneTimes)
        val gap = GradeAdjustedPace.adjustedPace(streams, 0, streams.count - 1) ?: activity.avgPaceSecondsPerKm
        if (activity.sport.isFoot) {
            return EffortScore.estimatedEffort(activity.movingTime, gap, profile.paceZones.thresholdPace)
        }
        val np = activity.normalizedPower
        if (np != null && np > 0) {
            val tss = PowerModel.trainingStress(np, profile.powerZones.ftp, activity.movingTime)
            return tss * 1.1
        }
        return (activity.movingTime / 60) * 2.0
    }

    /** A single load number per activity, so runs and rides land on the same Fitness & Freshness chart. */
    fun trainingLoad(activity: Activity, profile: AthleteProfile): Double {
        val np = activity.normalizedPower
        if (activity.sport.isRide && np != null && np > 0 && profile.powerZones.ftp > 0) {
            return PowerModel.trainingStress(np, profile.powerZones.ftp, activity.movingTime)
        }
        // Relative Effort and TSS run on similar scales for an hour at threshold,
        // so the effort score doubles as a load for everything else.
        return activity.relativeEffort
    }
}

/**
 * A coarse fingerprint of a route, used to group repeats of the same loop the way Strava's
 * "matched runs" does.
 */
object RouteSignature {

    fun compute(coords: List<Coord>): String {
        if (coords.size <= 4) return ""
        val simplified = Geo.downsample(coords, 24)
        // Round to roughly 100 m so small GPS differences still match.
        val parts = simplified.map { c ->
            val lat = (c.lat * 1000).swiftRounded() / 1000
            val lon = (c.lon * 1000).swiftRounded() / 1000
            fixed(lat, 3) + "," + fixed(lon, 3)
        }
        val distanceBucket = (Geo.totalDistance(coords) / 500).swiftRounded().toInt()
        return "$distanceBucket|" + parts.joinToString(";")
    }

    /** True when two activities cover essentially the same ground. */
    fun similar(a: Activity, b: Activity, tolerance: Double = 60.0): Boolean {
        if (!(a.hasGPS && b.hasGPS)) return false
        if (!(abs(a.distance - b.distance) < max(300.0, a.distance * 0.08))) return false
        val pa = Geo.downsample(a.previewPath, 40)
        val pb = Geo.downsample(b.previewPath, 40)
        if (!(pa.size > 8 && pb.size == pa.size)) return false
        var matched = 0
        for (i in pa.indices) {
            if (Geo.fastDistance(pa[i], pb[i]) < tolerance) matched += 1
        }
        return matched.toDouble() / pa.size.toDouble() > 0.75
    }
}

/** Fitness, Fatigue and Form: the paid Strava chart, computed from daily load. */
data class FitnessPoint(
    val date: LocalDate,
    val fitness: Double,    // 42-day exponentially weighted load
    val fatigue: Double,    // 7-day
    val form: Double,       // fitness minus fatigue
    val load: Double,       // that day's load
)

object FitnessCurve {

    const val FITNESS_TIME_CONSTANT: Double = 42.0
    const val FATIGUE_TIME_CONSTANT: Double = 7.0

    fun build(
        activities: List<Activity>,
        end: Instant = Instant.now(),
        daysBack: Int = 400,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<FitnessPoint> {
        val endDay = end.atZone(zone).toLocalDate()
        val start = endDay.minusDays(daysBack.toLong())

        val dailyLoad = HashMap<LocalDate, Double>()
        for (a in activities) {
            val day = a.startDate.atZone(zone).toLocalDate()
            if (day.isBefore(start) || day.isAfter(endDay)) continue
            dailyLoad[day] = (dailyLoad[day] ?: 0.0) + a.trainingLoad
        }

        val out = ArrayList<FitnessPoint>()
        var fitness = 0.0
        var fatigue = 0.0
        val fitnessAlpha = 1 - exp(-1 / FITNESS_TIME_CONSTANT)
        val fatigueAlpha = 1 - exp(-1 / FATIGUE_TIME_CONSTANT)

        var day = start
        while (!day.isAfter(endDay)) {
            val load = dailyLoad[day] ?: 0.0
            fitness += fitnessAlpha * (load - fitness)
            fatigue += fatigueAlpha * (load - fatigue)
            out.add(FitnessPoint(day, fitness, fatigue, fitness - fatigue, load))
            day = day.plusDays(1)
        }
        return out
    }

    /** Plain-language reading of today's form, the way the paid feature phrases it. */
    fun formDescription(form: Double): Pair<String, String> = when {
        form >= 25 -> "Very fresh" to "Well rested. A good day to race or go hard."
        form >= 5 -> "Fresh" to "Recovered and ready for quality work."
        form >= -10 -> "Neutral" to "Balanced. Steady training is fine."
        form >= -30 -> "Building" to "Carrying fatigue — this is where fitness is built."
        else -> "Overreaching" to "Heavy fatigue. Consider an easier few days."
    }
}
