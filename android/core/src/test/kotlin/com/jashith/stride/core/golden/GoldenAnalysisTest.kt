package com.jashith.stride.core.golden

import com.jashith.stride.core.analysis.ActivityAnalyzer
import com.jashith.stride.core.analysis.GradeAdjustedPace
import com.jashith.stride.core.analysis.RouteSignature
import com.jashith.stride.core.analysis.SegmentMatcher
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.SegmentEffort
import com.jashith.stride.core.model.Streams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Year

/** The Kotlin analysis must reproduce, number for number, what the iOS Swift code computed. */
class GoldenAnalysisTest {
    private val cases = Golden.readList("analysis.json", AnalysisCase.serializer())

    private fun case(key: String) = cases.first { it.key == key }

    @Test fun theScenariosAreMeaningful() {
        assertEquals(listOf("run", "ride", "hike", "trail"), cases.map { it.key })
        val run = case("run").result
        assertTrue(run.movingTime < run.elapsed - 60)              // it has real pauses
        assertTrue(run.splitsKm.size >= 4 && run.splitsMi.size >= 2)
        assertTrue(run.bestEfforts.isNotEmpty() && run.avgHR != null && run.avgCadence != null && run.elevationGain > 20)
        val ride = case("ride").result
        assertTrue(ride.powerCurve.size > 10 && ride.normalizedPower != null && ride.avgPower != null)
        assertTrue(ride.bestEfforts.isEmpty())
        val hike = case("hike").result
        assertTrue(hike.avgHR == null && hike.avgPower == null && hike.relativeEffort > 0)
        val trail = case("trail")
        assertTrue(trail.track.any { it.lat == 0.0 })              // GPS dropouts
        assertTrue(trail.result.hasGPS)
        assertTrue(cases.all { it.result.previewPath.size > 5 && it.routeSignature.isNotEmpty() })
    }

    @Test fun everyActivityAnalysesLikeTheIosCode() {
        for (c in cases) {
            // calories depend on age, which iOS derives from the current year; the golden was made with a 30 year old
            val profile = c.profile.copy(birthYear = Year.now().value - c.ageYears)
            val actual = ActivityAnalyzer.analyse(c.base, c.track, profile)
            assertEquals(c.key, c.result.id, actual.id)
            JsonCompare.assertSame(Activity.serializer(), c.result, actual, label = c.key, ignoreKeys = IGNORED_IDS)
        }
    }

    @Test fun movingTimeGradeAdjustedPaceAndRouteSignature() {
        for (c in cases) {
            val streams = Streams(c.track)
            assertEquals(c.key, c.movingTime, ActivityAnalyzer.movingTime(streams), 1e-9)
            val gap = GradeAdjustedPace.adjustedPace(streams, 0, streams.count - 1)
            if (c.gradeAdjustedPace == null) assertEquals(null, gap) else assertEquals(c.key, c.gradeAdjustedPace, gap!!, 1e-9)
            assertEquals(c.key, c.routeSignature, RouteSignature.compute(streams.coords.filter { it.isValid }))
        }
    }

    @Test fun routeSimilarityAgreesWithIosOnTheScenarios() {
        // identical paths are the same route; different places are not
        val run = case("run").result
        assertTrue(RouteSignature.similar(run, run))
        for (other in listOf("ride", "hike", "trail")) assertTrue(other, !RouteSignature.similar(run, case(other).result))
    }

    @Test fun segmentEffortsMatchIosAndRankingToo() {
        val goldens = Golden.readList("segments.json", SegmentsGolden.serializer())
        assertEquals(listOf("run", "ride", "hike"), goldens.map { it.scenarioKey })
        var matched = 0
        for (g in goldens) {
            val track = case(g.scenarioKey).track
            val efforts = SegmentMatcher.findEfforts(g.activity, g.activity.id, track, g.segments)
            assertEquals(g.scenarioKey, g.efforts.map { it.segmentID }, efforts.map { it.segmentID })
            for ((expected, actual) in g.efforts.zip(efforts)) {
                JsonCompare.assertSame(SegmentEffort.serializer(), expected, actual, g.scenarioKey, IGNORED_IDS)
            }
            val ranked = SegmentMatcher.rank(efforts)
            for ((expected, actual) in g.ranked.zip(ranked)) {
                JsonCompare.assertSame(SegmentEffort.serializer(), expected, actual, g.scenarioKey + " ranked", IGNORED_IDS)
            }
            matched += efforts.size
        }
        assertTrue("the scenarios should produce real efforts, got $matched", matched >= 3)
        // the run scenario deliberately includes a reversed, a wrong-sport and a too-short segment
        val run = goldens.first { it.scenarioKey == "run" }
        assertEquals(6, run.segments.size)
        assertTrue(run.efforts.size < run.segments.size)
        assertNotNull(run.efforts.firstOrNull())
    }
}
