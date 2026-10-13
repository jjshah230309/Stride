package com.jashith.stride.core.analysis

import com.jashith.stride.core.geo.Smooth
import com.jashith.stride.core.model.AthleteProfile
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Streams
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

object GradeAdjustedPace {
    /**
     * Minetti's measured energy cost of running, in joules per kilogram per metre, as a function
     * of gradient. This is the curve behind grade adjusted pace.
     */
    fun energyCost(gradient: Double): Double {
        val g = max(-0.45, min(0.45, gradient))
        val g2 = g * g
        val g3 = g2 * g
        val g4 = g3 * g
        val g5 = g4 * g
        return 155.4 * g5 - 30.4 * g4 - 43.3 * g3 + 46.3 * g2 + 19.5 * g + 3.6
    }

    const val FLAT_COST: Double = 3.6

    /** How much harder than flat ground this gradient is. 1.0 = flat. */
    fun factor(gradient: Double): Double = max(0.5, min(3.0, energyCost(gradient) / FLAT_COST))

    /** Equivalent flat pace, in seconds per kilometre, for a stretch run at [paceSecondsPerKm] on [gradient]. */
    fun adjust(paceSecondsPerKm: Double, gradient: Double): Double = paceSecondsPerKm / factor(gradient)

    /** Grade adjusted seconds per kilometre across a stretch of a stream. */
    fun adjustedPace(streams: Streams, from: Int, to: Int): Double? {
        if (!(to > from && to < streams.count)) return null
        val grades = streams.grade()
        var equivalentDistance = 0.0
        var movingSeconds = 0.0
        var i = from + 1
        while (i <= to) {
            val d = streams.distance[i] - streams.distance[i - 1]
            val dt = streams.time[i] - streams.time[i - 1]
            if (d > 0 && dt > 0 && dt < 20 && streams.moving[i]) {
                equivalentDistance += d * factor(grades[i])
                movingSeconds += dt
            }
            i += 1
        }
        if (!(equivalentDistance > 1)) return null
        return movingSeconds / (equivalentDistance / 1000)
    }
}

object PowerModel {

    /**
     * Physics-based cycling power: gravity, rolling resistance, air drag and acceleration.
     * This is the same model Strava uses for its estimated power.
     */
    fun cyclingWatts(
        speed: Double,
        gradient: Double,
        acceleration: Double,
        totalMassKg: Double,
        crr: Double,
        cda: Double,
        airDensity: Double = 1.225,
    ): Double {
        if (!(speed > 0.5)) return 0.0
        val g = 9.80665
        val theta = atan(gradient)
        val gravity = totalMassKg * g * sin(theta)
        val rolling = totalMassKg * g * cos(theta) * crr
        val drag = 0.5 * airDensity * cda * speed * speed
        val inertia = totalMassKg * acceleration
        val force = gravity + rolling + drag + inertia
        val watts = force * speed / 0.976   // drivetrain efficiency
        return max(0.0, min(2000.0, watts))
    }

    /**
     * Running power. Horizontal cost is roughly one joule per kilogram per metre; climbing adds the
     * gravitational term at about a quarter efficiency.
     */
    fun runningWatts(speed: Double, gradient: Double, massKg: Double): Double {
        if (!(speed > 0.4)) return 0.0
        val horizontal = 0.98 * massKg * speed
        val verticalSpeed = speed * gradient
        val vertical = if (verticalSpeed > 0) {
            massKg * 9.80665 * verticalSpeed / 0.25
        } else {
            massKg * 9.80665 * verticalSpeed * 0.10   // descending returns a little
        }
        return max(0.0, min(1500.0, horizontal + vertical))
    }

    /**
     * Normalised power: the fourth-root-of-the-mean-of-the-fourth-power of a 30-second rolling
     * average. Weights surges the way your body feels them.
     */
    fun normalizedPower(wattsPerSecond: DoubleArray): Double? {
        if (wattsPerSecond.size < 30) return null
        val rolling = Smooth.movingAverage(wattsPerSecond, window = 30)
        var sum = 0.0
        for (w in rolling) {
            val x = max(0.0, w)
            sum += x * x * x * x
        }
        val mean = sum / rolling.size.toDouble()
        return mean.pow(0.25)
    }

    /** Intensity factor and training stress, the standard cycling load metric. */
    fun trainingStress(normalizedPower: Double, ftp: Double, seconds: Double): Double {
        if (!(ftp > 0 && seconds > 0)) return 0.0
        val intensity = normalizedPower / ftp
        return (seconds * normalizedPower * intensity) / (ftp * 3600) * 100
    }
}

object EffortScore {

    /** Weights per heart-rate zone, rising steeply, matching how Strava's Relative Effort treats time near threshold. */
    val zoneWeights: List<Double> = listOf(0.6, 1.0, 1.9, 3.6, 6.6)

    /** Relative Effort from time spent in each heart rate zone, in seconds. */
    fun relativeEffort(zoneSeconds: List<Double>): Double {
        var score = 0.0
        for ((i, seconds) in zoneSeconds.withIndex()) {
            if (i < zoneWeights.size) score += (seconds / 60.0) * zoneWeights[i]
        }
        return score
    }

    /** Fallback when there is no heart rate: scale by how hard the pace was relative to threshold pace. */
    fun estimatedEffort(movingTime: Double, gradeAdjustedPace: Double, thresholdPace: Double): Double {
        if (!(movingTime > 0 && gradeAdjustedPace > 0 && thresholdPace > 0)) return 0.0
        val intensity = max(0.4, min(1.4, thresholdPace / gradeAdjustedPace))
        val weight = 1.0 + 5.6 * max(0.0, intensity - 0.55).pow(2.0)
        return (movingTime / 60.0) * weight
    }

    /** Calories from heart rate (Keytel), falling back to a MET estimate. */
    fun calories(profile: AthleteProfile, sport: SportType, movingTime: Double, avgHR: Double?, avgSpeed: Double): Double {
        val minutes = movingTime / 60
        if (!(minutes > 0)) return 0.0
        if (avgHR != null && avgHR > 50) {
            val age = profile.age.toDouble()
            val w = profile.weightKg
            val perMinute = if (profile.isMale) {
                (-55.0969 + 0.6309 * avgHR + 0.1988 * w + 0.2017 * age) / 4.184
            } else {
                (-20.4022 + 0.4472 * avgHR - 0.1263 * w + 0.074 * age) / 4.184
            }
            return max(0.0, perMinute * minutes)
        }
        // MET model, scaled by how fast you actually moved.
        var met = sport.baseMET
        if (sport.isRun && avgSpeed > 0) {
            met = max(6.0, min(20.0, avgSpeed * 3.6 * 1.0))
        } else if (sport.isRide && avgSpeed > 0) {
            met = max(4.0, min(16.0, avgSpeed * 3.6 * 0.42))
        }
        return met * profile.weightKg * (minutes / 60)
    }
}

object RacePredictor {

    /** Riegel's endurance exponent. 1.06 is the classic value for trained runners. */
    const val EXPONENT: Double = 1.06

    fun predict(fromDistance: Double, time: Double, toDistance: Double): Double {
        if (!(fromDistance > 0 && time > 0 && toDistance > 0)) return 0.0
        return time * (toDistance / fromDistance).pow(EXPONENT)
    }

    val standardRaces: List<Pair<String, Double>> = listOf(
        "1 km" to 1000.0, "1 mile" to 1609.344, "5K" to 5000.0, "10K" to 10000.0,
        "15K" to 15000.0, "10 mile" to 16093.44, "Half Marathon" to 21097.5,
        "Marathon" to 42195.0,
    )

    /** VDOT-style fitness score from a recent effort, useful for ranking performances. */
    fun performanceIndex(distance: Double, time: Double): Double {
        if (!(distance > 400 && time > 60)) return 0.0
        val velocity = distance / (time / 60)                      // metres per minute
        val percentMax = 0.8 + 0.1894393 * exp(-0.012778 * (time / 60)) + 0.2989558 * exp(-0.1932605 * (time / 60))
        val vo2 = -4.60 + 0.182258 * velocity + 0.000104 * velocity * velocity
        return vo2 / percentMax
    }
}
