package com.jashith.stride.core.analysis

import com.jashith.stride.core.SyntheticTracks
import com.jashith.stride.core.TestData
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Streams
import com.jashith.stride.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentMatcherTest {

    private val runId = TestData.id(1)

    private fun activityFor(track: List<TrackPoint>, sport: SportType = SportType.Run) =
        Activity(id = runId, name = "t", sport = sport, startDate = TestData.started, distance = track.last().d)

    /** A segment along the same line, from [fromMetres] to [toMetres] of the track. */
    private fun segment(fromMetres: Double, toMetres: Double, sport: SportType = SportType.Run, id: Int = 1): Segment {
        val path = (0..20).map { i ->
            val d = fromMetres + (toMetres - fromMetres) * i / 20
            Coord(SyntheticTracks.latAt(d), SyntheticTracks.START_LON)
        }
        return Segment(
            id = TestData.id(2000 + id), name = "Seg $id", sport = sport, path = path,
            distance = toMetres - fromMetres, elevationGain = 0.0, averageGrade = 0.0, maxGrade = 0.0,
        )
    }

    @Test fun aPassOverTheSegmentIsFoundAndTimed() {
        val track = SyntheticTracks.line(300, speed = 3.0)          // 900 m
        val seg = segment(300.0, 600.0)
        val efforts = SegmentMatcher.findEfforts(activityFor(track), runId, track, listOf(seg))
        assertEquals(1, efforts.size)
        val e = efforts[0]
        assertEquals(seg.id, e.segmentID)
        assertEquals(runId, e.activityID)
        assertEquals(100.0, e.time, 2.5)
        assertEquals(3.0, e.avgSpeed, 0.1)
        assertTrue(e.endIndex > e.startIndex)
        assertEquals(TestData.started.plusSeconds(e.startIndex.toLong()), e.date)
        assertTrue(!e.isPR)
        assertNull(e.avgHR)
    }

    @Test fun averagesHeartRateAndPowerOverTheEffort() {
        val track = SyntheticTracks.line(300, speed = 3.0, hr = { 160.0 }, pw = { 210.0 })
        val e = SegmentMatcher.findEfforts(activityFor(track), runId, track, listOf(segment(300.0, 600.0))).single()
        assertEquals(160.0, e.avgHR!!, 1e-9)
        assertEquals(210.0, e.avgPower!!, 1e-9)
    }

    @Test fun theWrongDirectionDoesNotCount() {
        val reversed = SyntheticTracks.line(300, speed = 3.0).let { t ->
            val n = t.size
            t.mapIndexed { i, p -> p.copy(t = i.toDouble(), lat = t[n - 1 - i].lat, d = t[i].d) }
        }
        assertTrue(SegmentMatcher.findEfforts(activityFor(reversed), runId, reversed, listOf(segment(300.0, 600.0))).isEmpty())
    }

    @Test fun aSegmentBesideTheTrackIsNotMatched() {
        val track = SyntheticTracks.line(300, speed = 3.0)
        val beside = segment(300.0, 600.0).let { s -> s.copy(path = s.path.map { Coord(it.lat, it.lon + 0.001) }) }   // ~70 m east
        assertTrue(SegmentMatcher.findEfforts(activityFor(track), runId, track, listOf(beside)).isEmpty())
    }

    @Test fun aRunDoesNotMatchARideSegmentAndViceVersa() {
        val track = SyntheticTracks.line(300, speed = 3.0)
        assertTrue(SegmentMatcher.findEfforts(activityFor(track), runId, track, listOf(segment(300.0, 600.0, SportType.Ride))).isEmpty())
        assertTrue(SegmentMatcher.sportsCompatible(SportType.Walk, SportType.TrailRun))
        assertTrue(SegmentMatcher.sportsCompatible(SportType.Ride, SportType.EBike))
        assertTrue(!SegmentMatcher.sportsCompatible(SportType.Hike, SportType.GravelRide))
    }

    @Test fun shortSegmentsAndShortActivitiesAreRejected() {
        val track = SyntheticTracks.line(300, speed = 3.0)
        assertTrue(SegmentMatcher.findEfforts(activityFor(track), runId, track, listOf(segment(300.0, 340.0))).isEmpty())   // <= 50 m
        val tiny = activityFor(track).copy(distance = 100.0)
        assertTrue(SegmentMatcher.findEfforts(tiny, runId, track, listOf(segment(300.0, 600.0))).isEmpty())                // activity shorter than the segment
        assertTrue(SegmentMatcher.findEfforts(activityFor(track), runId, track.take(4), listOf(segment(0.0, 100.0))).isEmpty())
    }

    @Test fun theFastestOfTwoPassesWins() {
        // out and back over the same 300 m: first pass slow (3 m/s), second pass would be reverse so use two laps
        val first = SyntheticTracks.line(150, speed = 2.0)                       // 0..300 m at 2 m/s
        val jump = first.last()
        // teleport back to the start (a second lap) and run it at 4 m/s
        val second = (1..75).map { i ->
            jump.copy(t = jump.t + i, d = jump.d + i * 4.0, lat = SyntheticTracks.latAt(i * 4.0), v = 4.0)
        }
        val track = first + second
        val seg = segment(20.0, 280.0)
        val e = SegmentMatcher.findEfforts(activityFor(track).copy(distance = track.last().d), runId, track, listOf(seg)).single()
        assertTrue("fast pass should win, got ${e.time}", e.time < 80.0)
    }

    @Test fun movingTimeSkipsPausesAndFallsBackToElapsed() {
        val paused = Streams(SyntheticTracks.line(100, speed = 3.0, pausedFrom = 40, pausedTo = 60))
        assertEquals(80.0, SegmentMatcher.movingTime(paused, 0, 100), 1e-9)
        val stopped = Streams(SyntheticTracks.line(100, speed = 3.0, pausedFrom = 0, pausedTo = 101))
        assertEquals(50.0, SegmentMatcher.movingTime(stopped, 10, 60), 1e-9)
        assertNull(SegmentMatcher.average(listOf(null, null), 0, 1))
        assertEquals(2.0, SegmentMatcher.average(listOf(1.0, null, 3.0), 0, 2)!!, 1e-12)
    }

    @Test fun rankingSortsFastestFirstAndMarksTheRecord() {
        val base = TestData.effort()
        val ranked = SegmentMatcher.rank(listOf(base.copy(time = 90.0), base.copy(time = 70.0), base.copy(time = 80.0)))
        assertEquals(listOf(70.0, 80.0, 90.0), ranked.map { it.time })
        assertEquals(listOf(1, 2, 3), ranked.map { it.rank })
        assertEquals(listOf(true, false, false), ranked.map { it.isPR })
        assertNotNull(ranked.firstOrNull())
    }
}
