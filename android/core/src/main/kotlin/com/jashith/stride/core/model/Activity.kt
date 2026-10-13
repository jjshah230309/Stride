@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.jashith.stride.core.model

import com.jashith.stride.core.json.InstantSerializer
import com.jashith.stride.core.json.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable
data class Split(
    val id: UUID = UUID.randomUUID(),
    val index: Int,
    val distance: Double,       // metres covered in this split
    val elapsed: Double,
    val moving: Double,
    val elevGain: Double,
    val elevLoss: Double,
    val avgHR: Double? = null,
    val avgPower: Double? = null,
    val avgCadence: Double? = null,
    val gapSeconds: Double? = null,     // grade adjusted seconds per display unit
    val isPartial: Boolean = false,
) {
    /** Seconds per kilometre; converted at display time. */
    val paceSecondsPerUnit: Double get() = if (distance > 1) moving / (distance / 1000.0) else 0.0
}

@Serializable
data class Lap(
    val id: UUID = UUID.randomUUID(),
    val index: Int,
    val startTime: Double,
    val endTime: Double,
    val distance: Double,
    val moving: Double,
    val elevGain: Double,
    val avgHR: Double? = null,
    val avgPower: Double? = null,
)

@Serializable
data class BestEffort(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val distance: Double,       // metres
    val time: Double,
    val startIndex: Int,
    val endIndex: Int,
    val startOffset: Double,
) {
    val paceSecondsPerKm: Double get() = if (distance > 0) time / (distance / 1000) else 0.0
}

@Serializable
data class PowerCurvePoint(val duration: Double, val watts: Double)

@Serializable
data class SegmentEffort(
    val id: UUID = UUID.randomUUID(),
    val segmentID: UUID,
    val activityID: UUID,
    val date: Instant,
    val time: Double,               // moving time over the segment
    val startIndex: Int,
    val endIndex: Int,
    val avgHR: Double? = null,
    val avgPower: Double? = null,
    val avgSpeed: Double,
    val isPR: Boolean = false,
    val rank: Int = 0,              // 1 = fastest ever on this segment
)

@Serializable
data class ActivityPhoto(
    val id: UUID = UUID.randomUUID(),
    val filename: String,
    val caption: String = "",
    val coord: Coord? = null,
)

@Serializable
data class Activity(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val sport: SportType,
    val startDate: Instant,

    // Totals
    val elapsed: Double = 0.0,
    val movingTime: Double = 0.0,
    val distance: Double = 0.0,             // metres
    val elevationGain: Double = 0.0,
    val elevationLoss: Double = 0.0,
    val maxElevation: Double = 0.0,
    val minElevation: Double = 0.0,

    // Physiology
    val avgHR: Double? = null,
    val maxHR: Double? = null,
    val avgCadence: Double? = null,
    val maxCadence: Double? = null,
    val avgPower: Double? = null,
    val maxPower: Double? = null,
    val normalizedPower: Double? = null,
    val calories: Double = 0.0,
    val relativeEffort: Double = 0.0,
    val trainingLoad: Double = 0.0,         // TSS-equivalent, drives Fitness & Freshness
    val perceivedExertion: Int? = null,
    val maxSpeed: Double = 0.0,

    // Time in zone, seconds
    val hrZoneTimes: List<Double> = emptyList(),
    val powerZoneTimes: List<Double> = emptyList(),
    val paceZoneTimes: List<Double> = emptyList(),

    // Derived detail
    val splitsKm: List<Split> = emptyList(),
    val splitsMi: List<Split> = emptyList(),
    val laps: List<Lap> = emptyList(),
    val bestEfforts: List<BestEffort> = emptyList(),
    val powerCurve: List<PowerCurvePoint> = emptyList(),
    val segmentEffortIDs: List<UUID> = emptyList(),

    // Metadata
    val notes: String = "",
    val isCommute: Boolean = false,
    val isPrivate: Boolean = false,
    val isManual: Boolean = false,
    val gearID: UUID? = null,
    val photos: List<ActivityPhoto> = emptyList(),
    val routeSignature: String = "",        // groups activities that follow the same route
    val hasGPS: Boolean = true,

    /** Simplified path for map previews, so the full track stays on disk until needed. */
    val previewPath: List<Coord> = emptyList(),
    val startCoord: Coord? = null,
    val locationName: String = "",
) {
    val endDate: Instant get() = startDate.plusMillis((elapsed * 1000).toLong())

    val avgSpeed: Double get() = if (movingTime > 0) distance / movingTime else 0.0

    val avgPaceSecondsPerKm: Double get() = if (distance > 1) movingTime / (distance / 1000) else 0.0

    val boundingBox: BoundingBox? get() = BoundingBox.of(previewPath)

    fun paceOrSpeedString(u: UnitSystem): String =
        if (sport.usesPace) {
            Fmt.pace(distance, movingTime, u) + " " + u.paceUnit
        } else {
            Fmt.avgSpeed(distance, movingTime, u) + " " + u.speedUnit
        }

    companion object {
        fun defaultName(sport: SportType, at: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
            val hour = at.atZone(zone).hour
            val part = when (hour) {
                in 4..11 -> "Morning"
                in 12..16 -> "Afternoon"
                in 17..20 -> "Evening"
                else -> "Night"
            }
            return "$part ${sport.shortName}"
        }
    }
}

/** A rolled-up total, reused by the weekly card, the training log and goals. */
data class Totals(
    val count: Int = 0,
    val distance: Double = 0.0,
    val movingTime: Double = 0.0,
    val elevationGain: Double = 0.0,
    val relativeEffort: Double = 0.0,
    val calories: Double = 0.0,
) {
    operator fun plus(other: Totals) = Totals(
        count + other.count,
        distance + other.distance,
        movingTime + other.movingTime,
        elevationGain + other.elevationGain,
        relativeEffort + other.relativeEffort,
        calories + other.calories,
    )

    companion object {
        fun of(activities: List<Activity>): Totals {
            var t = Totals()
            for (a in activities) {
                t = Totals(
                    t.count + 1,
                    t.distance + a.distance,
                    t.movingTime + a.movingTime,
                    t.elevationGain + a.elevationGain,
                    t.relativeEffort + a.relativeEffort,
                    t.calories + a.calories,
                )
            }
            return t
        }
    }
}
