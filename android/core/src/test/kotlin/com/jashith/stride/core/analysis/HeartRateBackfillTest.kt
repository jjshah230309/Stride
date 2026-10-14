package com.jashith.stride.core.analysis

import com.jashith.stride.core.model.TrackPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateBackfillTest {
    private val start = Instant.parse("2026-03-01T08:00:00Z")

    private fun point(t: Double, hr: Double? = null) =
        TrackPoint(t = t, lat = 51.0, lon = 0.0, alt = 10.0, d = t * 3, v = 3.0, hr = hr)

    private fun sample(secondsIn: Long, bpm: Double, watch: Boolean = false) =
        HeartRateSample(start.plusSeconds(secondsIn), bpm, watch)

    @Test
    fun fillsPointsWithinTolerance() {
        val points = listOf(point(0.0), point(10.0), point(20.0))
        val result = HeartRateBackfill.apply(listOf(sample(2, 140.0), sample(19, 150.0, watch = true)), points, start)
        assertEquals(listOf(140.0, 140.0, 150.0), result.points.map { it.hr })
        assertEquals(3, result.filled)
        assertTrue(result.fromWatch)
        assertEquals(1.0, result.coverage, 1e-9)
    }

    @Test
    fun leavesFarPointsAndExistingReadingsAlone() {
        val points = listOf(point(0.0, hr = 120.0), point(100.0), point(300.0))
        val result = HeartRateBackfill.apply(listOf(sample(95, 160.0)), points, start)
        assertEquals(120.0, result.points[0].hr!!, 0.0)
        assertEquals(160.0, result.points[1].hr!!, 0.0)
        assertNull(result.points[2].hr)
        assertEquals(1, result.filled)
        assertTrue(!result.fromWatch)
    }

    @Test
    fun toleranceIsInclusiveAtTwentySeconds() {
        val result = HeartRateBackfill.apply(listOf(sample(20, 131.0)), listOf(point(0.0), point(41.0)), start)
        assertEquals(131.0, result.points[0].hr!!, 0.0)
        assertNull(result.points[1].hr)
    }

    @Test
    fun emptyInputsChangeNothing() {
        assertEquals(0, HeartRateBackfill.apply(emptyList(), listOf(point(0.0)), start).filled)
        assertEquals(0, HeartRateBackfill.apply(listOf(sample(0, 100.0)), emptyList(), start).filled)
    }

    @Test
    fun plausibleRange() {
        assertTrue(HeartRateBackfill.isPlausible(31.0))
        assertTrue(!HeartRateBackfill.isPlausible(30.0))
        assertTrue(!HeartRateBackfill.isPlausible(240.0))
    }
}
