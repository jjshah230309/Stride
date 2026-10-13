package com.jashith.stride.core.analysis

import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.BoundingBox
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SegmentEffort
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Streams
import com.jashith.stride.core.model.TrackPoint
import java.time.Instant
import java.util.UUID
import kotlin.math.min

/**
 * Finds every time an activity covered a saved segment. Strava does this on its servers against a
 * global segment set; here it runs on device against yours.
 */
object SegmentMatcher {

    /** How close you must pass to the segment's start or end for an effort to count. */
    const val TERMINAL_RADIUS: Double = 25.0

    /** How far the middle of your track may stray from the segment line. */
    const val CORRIDOR_RADIUS: Double = 35.0

    /** Fraction of the segment that must be covered within the corridor. */
    const val REQUIRED_COVERAGE: Double = 0.85

    fun findEfforts(activity: Activity, activityID: UUID, points: List<TrackPoint>, segments: List<Segment>): List<SegmentEffort> {
        if (points.size <= 4) return emptyList()
        val streams = Streams(points)
        val activityBox = BoundingBox.of(streams.coords) ?: return emptyList()

        val efforts = ArrayList<SegmentEffort>()
        for (segment in segments) {
            // Cheap rejections first: wrong sport family, no geographic overlap.
            if (!sportsCompatible(segment.sport, activity.sport)) continue
            val segBox = segment.boundingBox ?: continue
            if (!segBox.expanded(100.0).intersects(activityBox)) continue
            if (!(segment.distance > 50 && activity.distance >= segment.distance * 0.9)) continue

            bestEffort(segment, streams, activityID, activity.startDate)?.let { efforts.add(it) }
        }
        return efforts
    }

    fun sportsCompatible(a: SportType, b: SportType): Boolean = a.isRide == b.isRide

    /** The fastest valid pass over the segment inside this activity. */
    fun bestEffort(segment: Segment, streams: Streams, activityID: UUID, date: Instant): SegmentEffort? {
        val segStart = segment.startCoord ?: return null
        val segEnd = segment.endCoord ?: return null

        // Candidate entry and exit samples.
        val entries = ArrayList<Int>()
        val exits = ArrayList<Int>()
        for (i in 0 until streams.count) {
            val c = streams.coords[i]
            if (!c.isValid) continue
            if (Geo.fastDistance(c, segStart) <= TERMINAL_RADIUS) entries.add(i)
            if (Geo.fastDistance(c, segEnd) <= TERMINAL_RADIUS) exits.add(i)
        }
        if (entries.isEmpty() || exits.isEmpty()) return null

        // Collapse runs of consecutive samples down to one representative each,
        // otherwise a slow pass produces hundreds of near-identical candidates.
        val entryGroups = collapse(entries)
        val exitGroups = collapse(exits)

        var best: SegmentEffort? = null
        for (start in entryGroups) {
            for (end in exitGroups) {
                if (end <= start) continue
                val covered = streams.distance[end] - streams.distance[start]
                // Reject wandering: you must not cover far more ground than the segment.
                if (!(covered >= segment.distance * 0.85 && covered <= segment.distance * 1.25)) continue
                if (!verifyCorridor(segment, streams, start, end)) continue

                val time = movingTime(streams, start, end)
                if (!(time > 1)) continue
                val current = best
                if (current == null || time < current.time) {
                    best = SegmentEffort(
                        segmentID = segment.id,
                        activityID = activityID,
                        date = date.plusNanos((streams.time[start] * 1e9).toLong()),
                        time = time,
                        startIndex = start,
                        endIndex = end,
                        avgHR = average(streams.heartRate, start, end),
                        avgPower = average(streams.power, start, end),
                        avgSpeed = segment.distance / time,
                        isPR = false,
                    )
                }
            }
        }
        return best
    }

    private fun collapse(indices: List<Int>): List<Int> {
        if (indices.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var runStart = indices[0]
        var previous = indices[0]
        for (k in 1 until indices.size) {
            val i = indices[k]
            if (i - previous > 3) {
                out.add((runStart + previous) / 2)
                runStart = i
            }
            previous = i
        }
        out.add((runStart + previous) / 2)
        return out
    }

    /** Every point of the segment must have some part of your track near it. */
    fun verifyCorridor(segment: Segment, streams: Streams, from: Int, to: Int): Boolean {
        val checkPoints = Geo.downsample(segment.path, 40)
        if (checkPoints.size <= 2) return false
        var hits = 0
        for (p in checkPoints) {
            var nearest = Double.MAX_VALUE
            var i = from
            while (i < to) {
                val d = Geo.perpendicularDistance(p, streams.coords[i], streams.coords[i + 1])
                if (d < nearest) nearest = d
                if (nearest < CORRIDOR_RADIUS) break
                i += 1
            }
            if (nearest <= CORRIDOR_RADIUS) hits += 1
        }
        return hits.toDouble() / checkPoints.size.toDouble() >= REQUIRED_COVERAGE
    }

    fun movingTime(streams: Streams, from: Int, to: Int): Double {
        var total = 0.0
        var i = from + 1
        while (i <= to && i < streams.count) {
            val dt = streams.time[i] - streams.time[i - 1]
            if (dt > 0 && dt < 30 && streams.moving[i]) total += dt
            i += 1
        }
        return if (total > 0) total else streams.time[min(to, streams.count - 1)] - streams.time[from]
    }

    fun average(values: List<Double?>, from: Int, to: Int): Double? {
        var sum = 0.0
        var count = 0.0
        var i = from
        while (i <= to && i < values.size) {
            values[i]?.let { sum += it; count += 1 }
            i += 1
        }
        return if (count > 0) sum / count else null
    }

    /** Rank a segment's efforts fastest-first and flag the personal record. */
    fun rank(efforts: List<SegmentEffort>): List<SegmentEffort> =
        efforts.sortedBy { it.time }.mapIndexed { i, e -> e.copy(rank = i + 1, isPR = i == 0) }
}
