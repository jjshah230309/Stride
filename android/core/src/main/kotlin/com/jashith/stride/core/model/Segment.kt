@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.jashith.stride.core.model

import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.geo.Smooth
import com.jashith.stride.core.json.InstantSerializer
import com.jashith.stride.core.json.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * A stretch of road or trail you race yourself on. Strava's leaderboard is global;
 * this one is yours, built from every effort you have recorded on it.
 */
@Serializable
data class Segment(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val sport: SportType,
    val path: List<Coord>,
    val distance: Double,               // metres
    val elevationGain: Double,
    val averageGrade: Double,           // fraction, not percent
    val maxGrade: Double,
    val createdAt: Instant = Instant.now(),
    val isStarred: Boolean = true,      // starred segments get live feedback while recording
    val createdFromActivityID: UUID? = null,
    val notes: String = "",
) {
    val startCoord: Coord? get() = path.firstOrNull()
    val endCoord: Coord? get() = path.lastOrNull()
    val boundingBox: BoundingBox? get() = BoundingBox.of(path)

    /** Bearing you should be travelling when you enter, so the reverse direction does not match. */
    val startBearing: Double
        get() {
            if (path.size <= 1) return 0.0
            val ahead = path[min(path.size - 1, 3)]
            return Geo.bearing(path[0], ahead)
        }

    val category: String
        get() {
            val climbScore = elevationGain * averageGrade * 100
            return when {
                climbScore >= 8000 -> "HC"
                climbScore >= 6400 -> "Cat 1"
                climbScore >= 3200 -> "Cat 2"
                climbScore >= 1600 -> "Cat 3"
                climbScore >= 800 -> "Cat 4"
                else -> ""
            }
        }

    companion object {
        fun make(name: String, sport: SportType, path: List<Coord>, elevations: List<Double>): Segment {
            val distance = Geo.totalDistance(path)
            val change = Smooth.elevationChange(elevations, threshold = 0.8)
            var maxGrade = 0.0
            if (path.size > 1 && elevations.size == path.size) {
                for (i in 0 until path.size - 1) {
                    val run = Geo.fastDistance(path[i], path[i + 1])
                    if (run > 5) {
                        maxGrade = max(maxGrade, (elevations[i + 1] - elevations[i]) / run)
                    }
                }
            }
            return Segment(
                name = name, sport = sport, path = path,
                distance = distance,
                elevationGain = change.gain,
                averageGrade = if (distance > 0) change.gain / distance else 0.0,
                maxGrade = min(0.4, maxGrade),
            )
        }
    }
}

/** A saved route you plan to follow, either drawn on the map or lifted from an activity. */
@Serializable
data class SavedRoute(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val sport: SportType,
    val path: List<Coord>,
    val elevations: List<Double>,
    val distance: Double,
    val elevationGain: Double,
    val createdAt: Instant = Instant.now(),
    val notes: String = "",
    val isStarred: Boolean = false,
    val sourceActivityID: UUID? = null,
) {
    val boundingBox: BoundingBox? get() = BoundingBox.of(path)

    /** Estimated time to complete, given a speed in m/s, adjusted for the climbing. */
    fun estimatedTime(atSpeed: Double, sport: SportType): Double {
        if (!(atSpeed > 0 && distance > 0)) return 0.0
        val flat = distance / atSpeed
        // Naismith-style penalty: roughly ten minutes per 100 m of ascent on foot,
        // less on a bike where the descent gives some of it back.
        val climbPenalty = if (sport.isFoot) elevationGain * 6.0 else elevationGain * 3.5
        return flat + climbPenalty
    }
}

@Serializable
enum class GearKind {
    @SerialName("shoes") Shoes,
    @SerialName("bike") Bike;

    val displayName: String get() = if (this == Shoes) "Shoes" else "Bike"

    /** Distance at which we suggest retiring, in metres. */
    val defaultRetirementDistance: Double get() = if (this == Shoes) 800_000.0 else 20_000_000.0
}

@Serializable
data class Gear(
    val id: UUID = UUID.randomUUID(),
    val kind: GearKind,
    val name: String,
    val brand: String = "",
    val model: String = "",
    val notes: String = "",
    val addedDate: Instant = Instant.now(),
    val isRetired: Boolean = false,
    val isDefault: Boolean = false,
    val startingDistance: Double = 0.0,     // metres already on it before you started tracking
    val retirementDistance: Double = 0.0,   // 0 means no reminder
    val weightKg: Double = 0.0,             // bike weight, used by the power estimate
) {
    val displayName: String get() = if (brand.isNotEmpty() && model.isNotEmpty()) "$brand $model" else name
}

@Serializable
enum class GoalMetric {
    @SerialName("distance") Distance,
    @SerialName("time") Time,
    @SerialName("elevation") Elevation,
    @SerialName("activities") Activities;

    val displayName: String
        get() = when (this) {
            Distance -> "Distance"
            Time -> "Time"
            Elevation -> "Elevation"
            Activities -> "Activities"
        }
}

@Serializable
enum class GoalPeriod {
    @SerialName("weekly") Weekly,
    @SerialName("monthly") Monthly,
    @SerialName("yearly") Yearly;

    val displayName: String
        get() = when (this) {
            Weekly -> "Weekly"
            Monthly -> "Monthly"
            Yearly -> "Yearly"
        }
}

/** Goals mirror Strava's: a target over a repeating period, or a one-off target. */
@Serializable
data class Goal(
    val id: UUID = UUID.randomUUID(),
    val metric: GoalMetric,
    val period: GoalPeriod,
    /** Empty means every sport counts. */
    val sports: List<SportType> = emptyList(),
    /** Metres, seconds, metres of ascent, or a plain count. */
    val target: Double,
    val createdAt: Instant = Instant.now(),
    val isActive: Boolean = true,
) {
    fun matches(activity: Activity): Boolean = sports.isEmpty() || sports.contains(activity.sport)

    /**
     * Progress this period. Like iOS (`Calendar.current`), the week follows the device locale
     * rather than the "week starts Monday" preference.
     */
    fun progress(
        activities: List<Activity>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
    ): Double {
        val today = now.atZone(zone).toLocalDate()
        val start = when (period) {
            GoalPeriod.Weekly -> today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            GoalPeriod.Monthly -> today.withDayOfMonth(1)
            GoalPeriod.Yearly -> today.withDayOfYear(1)
        }
        val end = when (period) {
            GoalPeriod.Weekly -> start.plusWeeks(1)
            GoalPeriod.Monthly -> start.plusMonths(1)
            GoalPeriod.Yearly -> start.plusYears(1)
        }
        val from = start.atStartOfDay(zone).toInstant()
        val to = end.atStartOfDay(zone).toInstant()
        val relevant = activities.filter { !it.startDate.isBefore(from) && !it.startDate.isAfter(to) && matches(it) }
        return when (metric) {
            GoalMetric.Distance -> relevant.sumOf { it.distance }
            GoalMetric.Time -> relevant.sumOf { it.movingTime }
            GoalMetric.Elevation -> relevant.sumOf { it.elevationGain }
            GoalMetric.Activities -> relevant.size.toDouble()
        }
    }

    val title: String
        get() {
            val scope = if (sports.isEmpty()) "All sports" else sports.joinToString(", ") { it.shortName }
            return "${period.displayName} ${metric.displayName.lowercase(Locale.ROOT)} — $scope"
        }
}
