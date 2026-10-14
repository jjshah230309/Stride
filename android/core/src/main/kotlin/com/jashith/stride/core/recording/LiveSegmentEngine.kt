package com.jashith.stride.core.recording

import com.jashith.stride.core.analysis.SegmentMatcher
import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SportType
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time racing against your own best time on a segment, which Strava charges for. It watches for
 * you entering a starred segment, then tells you how you are doing against your record as you go.
 *
 * Not thread safe: drive it from one thread.
 */
class LiveSegmentEngine {

    data class LiveState(
        val segmentID: UUID,
        val name: String,
        val totalDistance: Double,
        val startedAtElapsed: Double,
        val startedAtDistance: Double,
        val fraction: Double = 0.0,
        val distanceCovered: Double = 0.0,
        val elapsed: Double = 0.0,
        val prTime: Double? = null,
        /** Negative means ahead of your best. */
        val deltaToPR: Double = 0.0,
        val lastAnnouncedFraction: Double = 0.0,
        val strayedSeconds: Double = 0.0,
    ) {
        val remaining: Double get() = max(0.0, totalDistance - distanceCovered)
    }

    sealed class Event {
        data class Started(val name: String, val prTime: Double?) : Event()
        data class Progress(val delta: Double, val remaining: Double) : Event()
        data class Finished(val name: String, val time: Double, val isPR: Boolean, val delta: Double?) : Event()
        data class Abandoned(val name: String) : Event()
    }

    var active: LiveState? = null
        private set
    var nearby: List<Segment> = emptyList()
        private set

    private class Candidate(val segment: Segment, val cumulative: DoubleArray)

    /** Segments eligible for live feedback, with their cumulative distance profiles. */
    private var candidates: List<Candidate> = emptyList()
    private var personalBests: MutableMap<UUID, Double> = HashMap()
    private val recentlyFinished: MutableSet<UUID> = HashSet()
    private var lastCoord: Coord? = null

    /** Announce progress at these points through the segment. */
    private val announcementPoints = doubleArrayOf(0.25, 0.5, 0.75, 0.9)

    fun load(segments: List<Segment>, bests: Map<UUID, Double>, sport: SportType) {
        candidates = segments
            .filter { it.isStarred && SegmentMatcher.sportsCompatible(it.sport, sport) && it.path.size > 2 }
            .map { Candidate(it, Geo.cumulativeDistance(it.path)) }
        personalBests = HashMap(bests)
        recentlyFinished.clear()
        active = null
    }

    fun reset() {
        active = null
        recentlyFinished.clear()
        lastCoord = null
    }

    /** Feed a position in. Returns whatever should be announced. */
    fun update(coord: Coord, elapsed: Double, totalDistance: Double, dt: Double): List<Event> {
        try {
            return step(coord, elapsed, totalDistance, dt)
        } finally {
            lastCoord = coord
        }
    }

    private fun step(coord: Coord, elapsed: Double, totalDistance: Double, dt: Double): List<Event> {
        val events = ArrayList<Event>()

        val current = active
        if (current != null) {
            var state: LiveState = current
            val entry = candidates.firstOrNull { it.segment.id == state.segmentID }
            if (entry == null) {
                active = null
                return events
            }
            val segment = entry.segment
            val cumulative = entry.cumulative

            // Where along the segment are we?
            val (index, offPathDistance) = nearestIndex(segment.path, coord)
            if (offPathDistance > 70) {
                state = state.copy(strayedSeconds = state.strayedSeconds + dt)
                if (state.strayedSeconds > 20) {
                    events.add(Event.Abandoned(segment.name))
                    active = null
                    return events
                }
            } else {
                state = state.copy(strayedSeconds = 0.0)
            }

            val along = cumulative[min(index, cumulative.size - 1)]
            state = state.copy(
                elapsed = elapsed - state.startedAtElapsed,
                distanceCovered = totalDistance - state.startedAtDistance,
                fraction = if (segment.distance > 0) min(1.0, along / segment.distance) else 0.0,
            )

            val pr = state.prTime
            if (pr != null && state.fraction > 0.01) {
                val expected = pr * state.fraction
                state = state.copy(deltaToPR = state.elapsed - expected)
            }

            // Finished?
            val end = segment.endCoord
            val nearEnd = end != null && Geo.fastDistance(coord, end) < SegmentMatcher.TERMINAL_RADIUS
            if (nearEnd && state.fraction > 0.8) {
                val time = state.elapsed
                val best = personalBests[segment.id]
                val isPR = best == null || time < best
                events.add(Event.Finished(segment.name, time, isPR, best?.let { time - it }))
                if (isPR) personalBests[segment.id] = time
                recentlyFinished.add(segment.id)
                active = null
                return events
            }

            // Timed out: you clearly are not racing it any more.
            if (pr != null && state.elapsed > pr * 3 + 180) {
                events.add(Event.Abandoned(segment.name))
                active = null
                return events
            }
            if (pr == null && state.distanceCovered > segment.distance * 1.6) {
                events.add(Event.Abandoned(segment.name))
                active = null
                return events
            }

            // Progress call-outs.
            for (point in announcementPoints) {
                if (state.fraction >= point && state.lastAnnouncedFraction < point) {
                    state = state.copy(lastAnnouncedFraction = point)
                    if (state.prTime != null) {
                        events.add(Event.Progress(state.deltaToPR, state.remaining))
                    }
                    break
                }
            }

            active = state
            return events
        }

        // Not on a segment: look for one starting here.
        val previous = lastCoord ?: return events
        val heading = Geo.bearing(previous, coord)
        val moved = Geo.fastDistance(previous, coord)

        for (entry in candidates) {
            val segment = entry.segment
            if (recentlyFinished.contains(segment.id)) continue
            val start = segment.startCoord ?: continue
            if (!(Geo.fastDistance(coord, start) < SegmentMatcher.TERMINAL_RADIUS)) continue
            // Heading check keeps the reverse direction from triggering, but only once we are actually moving.
            if (moved > 3 && Geo.bearingDelta(heading, segment.startBearing) > 60) continue

            val pr = personalBests[segment.id]
            active = LiveState(
                segmentID = segment.id, name = segment.name, totalDistance = segment.distance,
                startedAtElapsed = elapsed, startedAtDistance = totalDistance, prTime = pr,
            )
            events.add(Event.Started(segment.name, pr))
            break
        }
        return events
    }

    /** Index of the closest point on the path, plus how far off the path we are. */
    private fun nearestIndex(path: List<Coord>, coord: Coord): Pair<Int, Double> {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for ((i, p) in path.withIndex()) {
            val d = Geo.fastDistance(p, coord)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }
        return bestIndex to bestDistance
    }

    /** Segments whose start is within a few hundred metres, shown as "coming up". */
    fun updateNearby(coord: Coord) {
        nearby = candidates
            .mapNotNull { entry ->
                val start = entry.segment.startCoord ?: return@mapNotNull null
                val d = Geo.fastDistance(coord, start)
                if (d < 500) entry.segment to d else null
            }
            .sortedBy { it.second }
            .take(3)
            .map { it.first }
    }
}
