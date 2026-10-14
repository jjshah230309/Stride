package com.jashith.stride.core.speech

import com.jashith.stride.core.model.SpokenMetric
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.model.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoachScriptTest {
    private val sample = CoachScript.sampleSnapshot

    private fun script(style: SpeechStyle, units: UnitSystem = UnitSystem.Metric, remarks: Boolean = false) =
        CoachScript(VoiceSettings(style = style, coachRemarks = remarks), units)

    private fun phrase(metric: SpokenMetric, style: SpeechStyle, snapshot: CoachSnapshot = sample, interval: Boolean = false, units: UnitSystem = UnitSystem.Metric) =
        script(style, units).phrase(metric, snapshot, interval)

    @Test fun theSettingsPreviewSentenceIsExactlyWhatTheCoachWouldSay() {
        val b = script(SpeechStyle.Natural).split(sample, isTimeInterval = false)
        assertEquals("Kilometre three, in four thirty-two, pace four thirty-two, averaging four thirty-seven.", b.plainText)
        assertEquals(
            "<speak>Kilometre three<break time=\"220ms\"/>in four thirty-two<break time=\"220ms\"/>pace four thirty-two" +
                "<break time=\"220ms\"/>averaging four thirty-seven</speak>",
            b.ssml,
        )
    }

    private data class Row(val metric: SpokenMetric, val brief: String, val natural: String, val detailed: String)

    @Test fun everyMetricInEveryStyleForTheSampleRun() {
        val rows = listOf(
            Row(SpokenMetric.SplitNumber, "three", "Kilometre three", "Kilometre three complete"),
            Row(SpokenMetric.SplitTime, "four thirty-two", "in four thirty-two", "split time four minutes thirty-two seconds"),
            Row(SpokenMetric.SplitPace, "four thirty-two", "pace four thirty-two", "split pace four minutes thirty-two seconds per kilometre"),
            Row(SpokenMetric.TotalDistance, "three kilometres", "three kilometres so far", "total distance three kilometres"),
            Row(SpokenMetric.TotalTime, "thirteen fifty-one", "running time thirteen fifty-one", "total moving time thirteen minutes fifty-one seconds"),
            Row(SpokenMetric.AveragePace, "average four thirty-seven", "averaging four thirty-seven", "average pace four minutes thirty-seven seconds per kilometre"),
            Row(SpokenMetric.CurrentPace, "four twenty-eight", "currently four twenty-eight", "current pace four minutes twenty-eight seconds per kilometre"),
            Row(SpokenMetric.GradeAdjustedPace, "adjusted four twenty-four", "flat equivalent four twenty-four", "grade adjusted pace four twenty-four per kilometre"),
            Row(SpokenMetric.AverageSpeed, "average twenty-five point nine kilometres an hour", "averaging twenty-five point nine kilometres an hour", "average speed twenty-five point nine kilometres per hour"),
            Row(SpokenMetric.CurrentSpeed, "twenty-six point six kilometres an hour", "holding twenty-six point six kilometres an hour", "current speed twenty-six point six kilometres per hour"),
            Row(SpokenMetric.HeartRate, "one fifty-four", "heart rate one fifty-four", "heart rate one hundred and fifty-four beats per minute"),
            Row(SpokenMetric.AverageHeartRate, "one fifty-one", "average heart rate one fifty-one", "average heart rate one hundred and fifty-one beats per minute"),
            Row(SpokenMetric.Cadence, "one seventy-six", "cadence one seventy-six", "cadence one seventy-six steps per minute"),
            Row(SpokenMetric.Power, "two hundred and forty-three watts", "power two hundred and forty-three", "power two hundred and forty-three watts"),
            Row(SpokenMetric.AveragePower, "average two hundred and thirty-one watts", "averaging two hundred and thirty-one watts", "average power two hundred and thirty-one watts"),
            Row(SpokenMetric.ElevationGain, "eighty-four up", "climbed eighty-four metres", "elevation gain eighty-four metres"),
            Row(SpokenMetric.Calories, "three hundred and twenty", "three hundred and twenty calories", "three hundred and twenty calories burned"),
        )
        assertEquals("all 17 metrics covered", SpokenMetric.entries.toSet(), rows.map { it.metric }.toSet())
        for (r in rows) {
            assertEquals("${r.metric} brief", r.brief, phrase(r.metric, SpeechStyle.Brief))
            assertEquals("${r.metric} natural", r.natural, phrase(r.metric, SpeechStyle.Natural))
            assertEquals("${r.metric} detailed", r.detailed, phrase(r.metric, SpeechStyle.Detailed))
        }
    }

    @Test fun timeIntervalsSayIntervalInsteadOfTheUnit() {
        assertEquals("three", phrase(SpokenMetric.SplitNumber, SpeechStyle.Brief, interval = true))
        assertEquals("Interval three", phrase(SpokenMetric.SplitNumber, SpeechStyle.Natural, interval = true))
        assertEquals("Interval three complete", phrase(SpokenMetric.SplitNumber, SpeechStyle.Detailed, interval = true))
    }

    @Test fun imperialUsesMilesAndFeet() {
        val i = UnitSystem.Imperial
        assertEquals("Mile three", phrase(SpokenMetric.SplitNumber, SpeechStyle.Natural, units = i))
        assertEquals("split pace four minutes thirty-two seconds per mile", phrase(SpokenMetric.SplitPace, SpeechStyle.Detailed, units = i))
        assertEquals("one point nine miles so far", phrase(SpokenMetric.TotalDistance, SpeechStyle.Natural, units = i))
        assertEquals("averaging sixteen point one miles an hour", phrase(SpokenMetric.AverageSpeed, SpeechStyle.Natural, units = i))
        assertEquals("climbed two hundred and seventy-six feet", phrase(SpokenMetric.ElevationGain, SpeechStyle.Natural, units = i))
    }

    @Test fun cadenceUnitFollowsTheSport() {
        val ride = sample.copy(sport = SportType.Ride)
        assertEquals("cadence one seventy-six revolutions per minute", phrase(SpokenMetric.Cadence, SpeechStyle.Detailed, ride))
    }

    @Test fun metricsWithNoUsableValueAreLeftOut() {
        val empty = CoachSnapshot()
        for (m in SpokenMetric.entries) {
            if (m == SpokenMetric.SplitNumber) continue // always speakable
            assertNull("$m", phrase(m, SpeechStyle.Natural, empty))
        }
        assertNull(phrase(SpokenMetric.HeartRate, SpeechStyle.Natural, sample.copy(heartRate = 30.0)))
        assertNull(phrase(SpokenMetric.Cadence, SpeechStyle.Natural, sample.copy(cadence = 5.0)))
        assertNull(phrase(SpokenMetric.ElevationGain, SpeechStyle.Natural, sample.copy(elevationGain = 1.0)))
        assertNull(phrase(SpokenMetric.AverageSpeed, SpeechStyle.Natural, sample.copy(averageSpeed = 0.2)))
    }

    @Test fun splitFollowsTheUsersMetricOrderAndSport() {
        val settings = VoiceSettings(
            style = SpeechStyle.Brief,
            runMetrics = listOf(SpokenMetric.HeartRate, SpokenMetric.SplitNumber, SpokenMetric.Calories),
        )
        val b = CoachScript(settings, UnitSystem.Metric).split(sample, isTimeInterval = false)
        assertEquals(listOf("one fifty-four", "three", "three hundred and twenty"), b.phrases)
        assertEquals(300, b.breakMilliseconds)

        val ride = CoachScript(VoiceSettings(style = SpeechStyle.Natural), UnitSystem.Metric)
            .split(sample.copy(sport = SportType.Ride), isTimeInterval = false)
        assertEquals(
            listOf("three kilometres so far", "running time thirteen fifty-one", "averaging twenty-five point nine kilometres an hour", "averaging two hundred and thirty-one watts"),
            ride.phrases,
        )
        assertEquals(220, ride.breakMilliseconds)
    }

    @Test fun remarksCompareThisSplitWithTheAverage() {
        fun remark(split: Double, avg: Double = 300.0, index: Int = 3, sport: SportType = SportType.Run) =
            script(SpeechStyle.Natural).remark(sample.copy(splitPaceSecondsPerUnit = split, averagePaceSecondsPerUnit = avg, splitIndex = index, sport = sport))
        assertEquals("that one was quick", remark(280.0))
        assertEquals("picking it up nicely", remark(290.0))
        assertEquals("right on pace", remark(298.0))
        assertEquals("right on pace", remark(304.0))
        assertEquals("easing off a touch", remark(310.0))
        assertEquals("settle in and find the rhythm", remark(330.0))
        assertNull(remark(280.0, index = 1))
        assertNull(remark(280.0, sport = SportType.Ride))
        assertNull(remark(0.0))
        val withRemark = script(SpeechStyle.Natural, remarks = true).split(sample.copy(splitPaceSecondsPerUnit = 270.0, averagePaceSecondsPerUnit = 300.0), false)
        assertEquals("that one was quick", withRemark.phrases.last())
    }

    @Test fun startAndFinish() {
        assertEquals(listOf("Started"), script(SpeechStyle.Brief).start(SportType.Run).phrases)
        assertEquals(listOf("Let's go"), script(SpeechStyle.Natural).start(SportType.Run).phrases)
        assertEquals(listOf("Starting your trail run"), script(SpeechStyle.Detailed).start(SportType.TrailRun).phrases)

        assertEquals(
            listOf("Nice work", "three kilometres", "in thirteen fifty-one", "averaging four thirty-seven"),
            script(SpeechStyle.Natural).finish(sample).phrases,
        )
        assertEquals(
            listOf("Done", "three kilometres", "in thirteen fifty-one", "four thirty-seven"),
            script(SpeechStyle.Brief).finish(sample).phrases,
        )
        assertEquals(
            listOf("Nice work", "three kilometres", "in thirteen minutes fifty-one seconds", "averaging four thirty-seven"),
            script(SpeechStyle.Detailed).finish(sample).phrases,
        )
        assertEquals(
            listOf("Nice work", "three kilometres", "in thirteen fifty-one", "averaging twenty-five point nine kilometres per hour"),
            script(SpeechStyle.Natural).finish(sample.copy(sport = SportType.Ride)).phrases,
        )
    }

    @Test fun lapAndHalfway() {
        assertEquals(listOf("Lap two", "four thirty-two", "four thirty-two"), script(SpeechStyle.Natural).lap(2, sample).phrases)
        assertEquals(listOf("Lap two", "four thirty-two"), script(SpeechStyle.Natural).lap(2, sample.copy(sport = SportType.Ride)).phrases)
        assertEquals(listOf("Halfway", "three kilometres", "in thirteen fifty-one"), script(SpeechStyle.Natural).halfway(sample).phrases)
    }

    @Test fun paceAlerts() {
        val s = script(SpeechStyle.Natural)
        assertEquals(listOf("twenty seconds behind target", "lift it a little"), s.paceAlert(290.0, 270.0)!!.phrases)
        assertEquals(listOf("twenty seconds ahead of target", "ease back if you want to hold it"), s.paceAlert(250.0, 270.0)!!.phrases)
        assertNull(s.paceAlert(280.0, 270.0))         // within the 15 s tolerance
        assertNull(s.paceAlert(0.0, 270.0))
        assertNull(s.paceAlert(280.0, 0.0))
        val strict = CoachScript(VoiceSettings(targetPaceToleranceSeconds = 0.0), UnitSystem.Metric)
        assertEquals(listOf("one second behind target", "lift it a little"), strict.paceAlert(271.0, 270.0)!!.phrases)
    }

    @Test fun zoneAlerts() {
        val s = script(SpeechStyle.Natural)
        assertEquals(listOf("Above zone three", "ease off"), s.zoneAlert(zone = 3, target = 3)!!.phrases)
        assertEquals(listOf("Below zone three", "pick it up"), s.zoneAlert(zone = 0, target = 3)!!.phrases)
        assertNull(s.zoneAlert(zone = 2, target = 3))
        assertNull(s.zoneAlert(zone = 2, target = 0))
    }

    @Test fun segmentAnnouncements() {
        val s = script(SpeechStyle.Natural)
        assertEquals(listOf("Segment Hill", "your best is two oh five", "go"), s.segmentStart("Hill", 125.0).phrases)
        assertEquals(listOf("Segment Hill"), s.segmentStart("Hill", null).phrases)
        assertEquals(listOf("five seconds ahead of your best", "eight hundred metres to go"), s.segmentProgress(-5.0, 800.0).phrases)
        assertEquals(listOf("one second behind your best", "one and a half kilometres to go"), s.segmentProgress(1.0, 1500.0).phrases)
        assertEquals(listOf("Level with your best", "eight hundred metres to go"), s.segmentProgress(0.2, 800.0).phrases)
        assertEquals(listOf("New record on Hill", "two oh five"), s.segmentFinish("Hill", 125.0, true, null).phrases)
        assertEquals(listOf("Hill done", "two oh five", "three off your best"), s.segmentFinish("Hill", 125.0, false, 3.4).phrases)
        assertEquals(listOf("Hill done", "two oh five"), s.segmentFinish("Hill", 125.0, false, -2.0).phrases)
    }
}
