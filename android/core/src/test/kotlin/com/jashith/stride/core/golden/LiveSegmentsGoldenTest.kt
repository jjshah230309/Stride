package com.jashith.stride.core.golden

import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.recording.LiveSegmentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Steps the Kotlin live-segment engine through the same tracks as the Swift one and compares every event. */
class LiveSegmentsGoldenTest {
    private val analysis = Golden.readList("analysis.json", AnalysisCase.serializer()).associateBy { it.key }
    private val goldens = Golden.readList("live-segments.json", LiveGolden.serializer())

    private fun trackFor(g: LiveGolden): List<TrackPoint> {
        val base = analysis.getValue(g.trackKey).track
        return when (g.transform) {
            "none" -> base
            "reversed" -> base.reversed().mapIndexed { i, p -> p.copy(t = i.toDouble()) }
            "strayAfter640" -> base.mapIndexed { i, p -> if (i >= 640) p.copy(lon = p.lon + 0.0015) else p }
            else -> error("unknown transform ${g.transform}")
        }
    }

    private fun LiveSegmentEngine.Event.dto(): LiveEventDTO = when (this) {
        is LiveSegmentEngine.Event.Started -> LiveEventDTO(kind = "started", name = name, prTime = prTime)
        is LiveSegmentEngine.Event.Progress -> LiveEventDTO(kind = "progress", delta = delta, remaining = remaining)
        is LiveSegmentEngine.Event.Finished -> LiveEventDTO(kind = "finished", name = name, delta = delta, time = time, isPR = isPR)
        is LiveSegmentEngine.Event.Abandoned -> LiveEventDTO(kind = "abandoned", name = name)
    }

    private fun replay(g: LiveGolden): List<LiveStep> {
        val engine = LiveSegmentEngine()
        engine.load(g.segments, g.bests.mapKeys { UUID.fromString(it.key) }, g.sport)
        val steps = ArrayList<LiveStep>()
        var wasActive = false
        for ((i, p) in trackFor(g).withIndex()) {
            val events = engine.update(p.coord, p.t, p.d, 1.0)
            var nearby: List<String>? = null
            if (i % 10 == 0) {
                engine.updateNearby(p.coord)
                nearby = engine.nearby.map { it.name }
            }
            val active = engine.active?.let {
                LiveActiveDTO(it.segmentID, it.fraction, it.distanceCovered, it.elapsed, it.deltaToPR, it.lastAnnouncedFraction, it.strayedSeconds)
            }
            if (active != null || events.isNotEmpty() || wasActive || nearby?.isNotEmpty() == true) {
                steps.add(LiveStep(i, events.map { it.dto() }, active, nearby))
            }
            wasActive = active != null
        }
        return steps
    }

    @Test fun everyScenarioProducesTheSameStepsAsIos() {
        assertEquals(listOf("run-with-records", "run-new-record", "run-reversed", "run-strays", "ride", "ride-reversed"), goldens.map { it.name })
        for (g in goldens) {
            val actual = replay(g)
            assertEquals("${g.name}: steps recorded", g.steps.map { it.i }, actual.map { it.i })
            for ((expected, got) in g.steps.zip(actual)) {
                val tag = "${g.name} step ${expected.i}"
                assertEquals("$tag events", expected.events.map { it.kind to it.name }, got.events.map { it.kind to it.name })
                for ((e, a) in expected.events.zip(got.events)) {
                    assertEquals("$tag prTime", e.prTime, a.prTime)
                    assertEquals("$tag isPR", e.isPR, a.isPR)
                    near("$tag delta", e.delta, a.delta)
                    near("$tag remaining", e.remaining, a.remaining)
                    near("$tag time", e.time, a.time)
                }
                assertEquals("$tag active", expected.active != null, got.active != null)
                if (expected.active != null) {
                    val e = expected.active
                    val a = got.active!!
                    assertEquals("$tag segment", e.segmentID, a.segmentID)
                    near("$tag fraction", e.fraction, a.fraction)
                    near("$tag distance", e.distanceCovered, a.distanceCovered)
                    near("$tag elapsed", e.elapsed, a.elapsed)
                    near("$tag deltaToPR", e.deltaToPR, a.deltaToPR)
                    near("$tag announced", e.lastAnnouncedFraction, a.lastAnnouncedFraction)
                    near("$tag strayed", e.strayedSeconds, a.strayedSeconds)
                }
                assertEquals("$tag nearby", expected.nearby, got.nearby)
            }
        }
    }

    private fun near(tag: String, expected: Double?, actual: Double?) {
        if (expected == null) {
            assertEquals(tag, null, actual)
        } else {
            assertNotNull(tag, actual)
            assertEquals(tag, expected, actual!!, 1e-9 + 1e-9 * Math.abs(expected))
        }
    }

    @Test fun theScenariosActuallyExerciseTheEngine() {
        val kinds = goldens.associate { g -> g.name to g.steps.flatMap { it.events }.map { it.kind } }
        assertTrue(kinds.getValue("run-with-records").containsAll(listOf("started", "progress", "finished")))
        assertTrue("a faster-than-record run reports a PR", goldens.first { it.name == "run-new-record" }.steps.flatMap { it.events }.any { it.kind == "finished" && it.isPR == true })
        assertTrue("at cycling speed the reverse direction must not start anything", kinds.getValue("ride-reversed").none { it == "started" })
        // Known quirk of the iOS engine, kept for parity: the heading check only applies when a fix moved
        // more than 3 m, which at running pace and 1 Hz is not every fix, so a reversed run can still start one.
        assertTrue(kinds.getValue("run-reversed").count { it == "started" } <= kinds.getValue("run-with-records").count { it == "started" })
        assertTrue("wandering off the line abandons the segment", kinds.getValue("run-strays").contains("abandoned"))
        assertTrue(kinds.getValue("ride").contains("started"))
        assertTrue("some steps list segments coming up", goldens.any { g -> g.steps.any { it.nearby?.isNotEmpty() == true } })
    }
}
