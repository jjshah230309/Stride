package com.jashith.stride.core.golden

import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.speech.CoachScript
import com.jashith.stride.core.speech.SpeechBuilder
import com.jashith.stride.core.speech.SpokenNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every sentence the coach can say, compared with what the iOS Swift code says. */
class GoldenSpeechTest {
    private val golden = Golden.read("speech.json", SpeechGolden.serializer())

    private fun BuilderOut?.matches(label: String, actual: SpeechBuilder?) {
        if (this == null) {
            assertEquals("$label should be silent", null, actual)
            return
        }
        assertEquals("$label ssml", ssml, actual!!.ssml)
        assertEquals("$label plain", plain, actual.plainText)
    }

    @Test fun numbersAsWords() {
        assertTrue(golden.ints.size > 300)
        for (c in golden.ints) {
            assertEquals("casual(${c.n})", c.casual, SpokenNumber.casual(c.n))
            assertEquals("formal(${c.n})", c.formal, SpokenNumber.formal(c.n))
        }
    }

    @Test fun pacesAndDurations() {
        assertTrue(golden.doubles.size > 200)
        for (c in golden.doubles) {
            assertEquals("pace(${c.x})", c.pace, SpokenNumber.pace(c.x))
            assertEquals("duration(${c.x})", c.duration, SpokenNumber.duration(c.x))
            assertEquals("verbose(${c.x})", c.durationVerbose, SpokenNumber.duration(c.x, verbose = true))
        }
        for (c in golden.specials) {
            val x = when (c.label) {
                "nan" -> Double.NaN
                "inf" -> Double.POSITIVE_INFINITY
                else -> Double.NEGATIVE_INFINITY
            }
            assertEquals(c.label, c.pace, SpokenNumber.pace(x))
            assertEquals(c.label, c.duration, SpokenNumber.duration(x))
            assertEquals(c.label, c.durationVerbose, SpokenNumber.duration(x, verbose = true))
        }
    }

    @Test fun decimalsAndDistances() {
        assertTrue(golden.decimals.size > 150)
        for (c in golden.decimals) {
            assertEquals("decimal(${c.x})", c.decimal, SpokenNumber.decimal(c.x))
            assertEquals("distance metric(${c.x})", c.distanceMetric, SpokenNumber.distance(c.x, UnitSystem.Metric))
            assertEquals("distance imperial(${c.x})", c.distanceImperial, SpokenNumber.distance(c.x, UnitSystem.Imperial))
        }
    }

    @Test fun theCoachSaysExactlyWhatTheIosCoachSays() {
        assertTrue(golden.coach.size >= 84)
        for ((index, c) in golden.coach.withIndex()) {
            val tag = "case $index (${c.snapshot.sport}, ${c.units}, ${c.settings.style}, interval=${c.isTimeInterval})"
            val script = CoachScript(c.settings, c.units)
            assertEquals(17, c.metrics.size)
            for (m in c.metrics) {
                assertEquals("$tag ${m.metric}", m.text, script.phrase(m.metric, c.snapshot, c.isTimeInterval))
            }
            c.split.matches("$tag split", script.split(c.snapshot, c.isTimeInterval))
            assertEquals("$tag remark", c.remark, script.remark(c.snapshot))
            c.start.matches("$tag start", script.start(c.snapshot.sport))
            c.finish.matches("$tag finish", script.finish(c.snapshot))
            c.lap.matches("$tag lap", script.lap(c.lapNumber, c.snapshot))
            c.halfway.matches("$tag halfway", script.halfway(c.snapshot))
            c.paceAlert.matches("$tag pace alert", script.paceAlert(c.paceAlertCurrent, c.paceAlertTarget))
            c.zoneAlert.matches("$tag zone alert", script.zoneAlert(c.zoneAlertZone, c.zoneAlertTarget))
            c.segmentStart.matches("$tag segment start", script.segmentStart("Hill & <Co>", c.segmentStartPR))
            c.segmentProgress.matches("$tag segment progress", script.segmentProgress(c.segmentProgressDelta, c.segmentProgressRemaining))
            c.segmentFinish.matches("$tag segment finish", script.segmentFinish("Hill", c.segmentFinishTime, c.segmentFinishIsPR, c.segmentFinishDelta))
        }
    }

    @Test fun theGoldenCoversTheInterestingBranches() {
        // guard against the cases quietly degenerating into empty strings
        val spoken = golden.coach.flatMap { it.metrics }.count { it.text != null }
        assertTrue("only $spoken phrases", spoken > 600)
        assertTrue(golden.coach.any { it.paceAlert != null } && golden.coach.any { it.paceAlert == null })
        assertTrue(golden.coach.any { it.zoneAlert != null } && golden.coach.any { it.zoneAlert == null })
        assertTrue(golden.coach.any { it.remark != null })
        assertTrue(golden.coach.map { it.settings.style }.toSet().size == 3)
    }

    @Test fun numberFormattingMatchesCPrintfAsUsedByTheIosApp() {
        val cases = Golden.readList("fmt.json", FmtCase.serializer())
        assertTrue(cases.size > 600)
        for (c in cases) {
            val u = c.units
            val x = c.x
            val tag = "x=$x $u"
            assertEquals("$tag distance", c.distance, Fmt.distance(x, u))
            assertEquals("$tag distance0", c.distance0, Fmt.distance(x, u, decimals = 0))
            assertEquals("$tag distance3", c.distance3, Fmt.distance(x, u, decimals = 3))
            assertEquals("$tag elevation", c.elevation, Fmt.elevation(x, u))
            assertEquals("$tag duration", c.duration, Fmt.duration(x))
            assertEquals("$tag durationPadded", c.durationPadded, Fmt.durationPadded(x))
            assertEquals("$tag durationCompact", c.durationCompact, Fmt.durationCompact(x))
            assertEquals("$tag paceFromSeconds", c.paceFromSeconds, Fmt.paceFromSeconds(x))
            assertEquals("$tag paceFromSpeed", c.paceFromSpeed, Fmt.paceFromSpeed(x, u))
            assertEquals("$tag speed", c.speed, Fmt.speed(x, u))
            assertEquals("$tag int", c.int, Fmt.int(x))
            assertEquals("$tag one", c.one, Fmt.one(x))
            assertEquals("$tag trimmed", c.trimmed, Fmt.trimmed(x))
            assertEquals("$tag percent", c.percent, Fmt.percent(x))
            assertEquals("$tag signed0", c.signed0, Fmt.signed(x))
            assertEquals("$tag signed2", c.signed2, Fmt.signed(x, decimals = 2))
        }
    }
}
