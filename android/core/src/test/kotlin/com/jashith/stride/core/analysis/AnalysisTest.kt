package com.jashith.stride.core.analysis

import com.jashith.stride.core.SyntheticTracks
import com.jashith.stride.core.TestData
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.AthleteProfile
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.PowerCurvePoint
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Streams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import kotlin.math.abs

class AnalysisTest {

    private val profile = AthleteProfile(birthYear = Year.now().value - 30, weightKg = 70.0)
    private fun blank(sport: SportType = SportType.Run) = Activity(name = "t", sport = sport, startDate = TestData.started)

    // Physiology

    @Test fun minettiGradeAdjustment() {
        assertEquals(1.0, GradeAdjustedPace.factor(0.0), 1e-12)
        assertEquals(1.65783722, GradeAdjustedPace.factor(0.10), 1e-6)
        assertEquals(0.59769611, GradeAdjustedPace.factor(-0.10), 1e-6)
        // gradients beyond +-45% are clamped, and the factor is bounded to 0.5..3.0
        assertEquals(GradeAdjustedPace.factor(0.45), GradeAdjustedPace.factor(0.9), 0.0)
        assertTrue(GradeAdjustedPace.factor(-0.45) >= 0.5)
        assertEquals(300.0 / 1.65783722, GradeAdjustedPace.adjust(300.0, 0.10), 1e-4)
    }

    @Test fun gradeAdjustedPaceOverAWholeStream() {
        val flat = Streams(SyntheticTracks.line(600, speed = 3.0))
        assertEquals(1000.0 / 3.0, GradeAdjustedPace.adjustedPace(flat, 0, flat.count - 1)!!, 0.5)
        val hill = Streams(SyntheticTracks.line(600, speed = 3.0, grade = 0.05))
        // climbing at 3 m/s is worth a faster flat pace
        assertTrue(GradeAdjustedPace.adjustedPace(hill, 0, hill.count - 1)!! < 1000.0 / 3.0 - 20)
        assertNull(GradeAdjustedPace.adjustedPace(flat, 5, 5))
        assertNull(GradeAdjustedPace.adjustedPace(flat, 0, 99_999))
    }

    @Test fun cyclingPowerPhysics() {
        assertEquals(149.7077451426837, PowerModel.cyclingWatts(30 / 3.6, 0.0, 0.0, 80.0, 0.005, 0.32), 1e-9)
        assertEquals(286.861805050452, PowerModel.cyclingWatts(5.0, 0.05, 0.1, 80.0, 0.005, 0.32), 1e-9)
        assertEquals(0.0, PowerModel.cyclingWatts(0.5, 0.0, 0.0, 80.0, 0.005, 0.32), 0.0)
        assertEquals(0.0, PowerModel.cyclingWatts(10.0, -0.3, 0.0, 80.0, 0.005, 0.32), 0.0)      // steep descent: never negative
        assertEquals(2000.0, PowerModel.cyclingWatts(30.0, 0.2, 0.0, 120.0, 0.01, 0.5), 0.0)       // clamped
    }

    @Test fun runningPower() {
        assertEquals(205.8, PowerModel.runningWatts(3.0, 0.0, 70.0), 1e-9)
        assertEquals(617.6793, PowerModel.runningWatts(3.0, 0.05, 70.0), 1e-9)
        assertEquals(195.5030174999, PowerModel.runningWatts(3.0, -0.05, 70.0), 1e-9)
        assertEquals(0.0, PowerModel.runningWatts(0.4, 0.0, 70.0), 0.0)
    }

    @Test fun normalizedPowerAndTrainingStress() {
        assertNull(PowerModel.normalizedPower(DoubleArray(29) { 200.0 }))
        assertEquals(200.0, PowerModel.normalizedPower(DoubleArray(600) { 200.0 })!!, 1e-9)
        // variability pushes NP above the plain average
        val surges = DoubleArray(600) { if ((it / 30) % 2 == 0) 100.0 else 300.0 }
        assertTrue(PowerModel.normalizedPower(surges)!! > 200.0)
        assertEquals(100.0, PowerModel.trainingStress(250.0, 250.0, 3600.0), 1e-9)
        assertEquals(38.72, PowerModel.trainingStress(220.0, 250.0, 1800.0), 1e-9)
        assertEquals(0.0, PowerModel.trainingStress(220.0, 0.0, 1800.0), 0.0)
    }

    @Test fun relativeEffortAndCalories() {
        assertEquals(1.6, EffortScore.relativeEffort(listOf(60.0, 60.0, 0.0, 0.0, 0.0)), 1e-12)
        assertEquals(0.6 + 1.0 + 1.9 + 3.6 + 6.6, EffortScore.relativeEffort(List(5) { 60.0 }), 1e-12)
        assertEquals(128.04, EffortScore.estimatedEffort(3600.0, 300.0, 300.0), 1e-9)
        assertEquals(0.0, EffortScore.estimatedEffort(0.0, 300.0, 300.0), 0.0)

        assertEquals(853.3236137667305, EffortScore.calories(profile, SportType.Run, 3600.0, 150.0, 3.0), 1e-6)
        assertEquals(574.4282982791586, EffortScore.calories(profile.copy(isMale = false), SportType.Run, 3600.0, 150.0, 3.0), 1e-6)
        // no heart rate: MET model
        assertEquals(756.0, EffortScore.calories(profile, SportType.Run, 3600.0, null, 3.0), 1e-9)
        assertEquals(846.72, EffortScore.calories(profile, SportType.Ride, 3600.0, null, 8.0), 1e-9)
        assertEquals(3.8 * 70, EffortScore.calories(profile, SportType.Walk, 3600.0, null, 1.4), 1e-9)
        assertEquals(0.0, EffortScore.calories(profile, SportType.Run, 0.0, 150.0, 3.0), 0.0)
    }

    @Test fun racePredictions() {
        assertEquals(2501.9178260186914, RacePredictor.predict(5000.0, 1200.0, 10000.0), 1e-9)
        assertEquals(0.0, RacePredictor.predict(0.0, 1200.0, 10000.0), 0.0)
        assertEquals(8, RacePredictor.standardRaces.size)
        assertTrue(RacePredictor.performanceIndex(5000.0, 1200.0) in 40.0..60.0)
        assertEquals(0.0, RacePredictor.performanceIndex(300.0, 90.0), 0.0)
    }

    // Efforts

    @Test fun bestEffortsOnASteadyRun() {
        val s = Streams(SyntheticTracks.line(1000, speed = 3.0))   // 3000 m in 1000 s
        val efforts = BestEffortFinder.find(s, SportType.Run)
        assertEquals(listOf("400 m", "1/2 mile", "1 km", "1 mile"), efforts.map { it.name })   // 2 mile is 3219 m, beyond this 3000 m run
        val fourHundred = efforts[0]
        assertEquals(400.0 / 3.0, fourHundred.time, 1e-6)
        assertEquals(1000.0 / 3.0, efforts[2].time, 1e-6)
        assertEquals(1609.344 / 3.0, efforts[3].time, 1e-6)
        assertTrue(fourHundred.startIndex < fourHundred.endIndex)
        assertEquals(s.time[fourHundred.startIndex], fourHundred.startOffset, 0.0)
        assertTrue(BestEffortFinder.find(s, SportType.Ride).isEmpty())
        assertEquals(listOf("1 km", "1 mile"), BestEffortFinder.find(s, SportType.Walk).map { it.name })
    }

    @Test fun bestEffortFindsTheFastestWindowNotTheFirst() {
        // 1 km slow, then 1 km fast
        val slow = SyntheticTracks.line(334, speed = 3.0)
        val last = slow.last()
        val fast = (1..250).map { i -> last.copy(t = last.t + i, d = last.d + i * 4.0, lat = SyntheticTracks.latAt(last.d + i * 4.0), v = 4.0) }
        val s = Streams(slow + fast)
        val km = BestEffortFinder.find(s, SportType.Run).first { it.name == "1 km" }
        assertEquals(250.0, km.time, 1.0)
    }

    @Test fun splitsAreCutAtEveryKilometreWithAPartialAtTheEnd() {
        val s = Streams(SyntheticTracks.line(1000, speed = 3.0))
        val splits = SplitBuilder.build(s, 1000.0)
        assertEquals(3, splits.size)
        assertEquals(listOf(1, 2, 3), splits.map { it.index })
        assertTrue(splits.none { it.isPartial })
        for (sp in splits) {
            assertEquals(1000.0, sp.distance, 3.0)
            assertEquals(333.3, sp.moving, 2.0)
            assertEquals(333.3, sp.paceSecondsPerUnit, 2.5)
        }
        val miles = SplitBuilder.build(s, 1609.344)
        assertEquals(2, miles.size)       // 1 mile, then a 1390 m partial
        assertFalse(miles[0].isPartial)
        assertTrue(miles[1].isPartial)
        assertEquals(1390.656, miles[1].distance, 3.0)
    }

    @Test fun splitsCarryAveragesAndGradeAdjustedPaceAndTrimTheClimb() {
        val s = Streams(SyntheticTracks.line(700, speed = 3.0, grade = 0.05, hr = { 150.0 }, cad = { 170.0 }, pw = { 250.0 }))
        val sp = SplitBuilder.build(s, 1000.0)[0]
        assertEquals(150.0, sp.avgHR!!, 1e-9)
        assertEquals(250.0, sp.avgPower!!, 1e-9)
        assertEquals(170.0, sp.avgCadence!!, 1e-9)
        assertEquals(50.0 * 0.75, sp.elevGain, 0.5)        // 5% of 1000 m, trimmed by 25%
        assertEquals(0.0, sp.elevLoss, 1e-9)
        assertTrue("climbing makes the flat-equivalent pace faster", sp.gapSeconds!! < sp.paceSecondsPerUnit)
        val flat = SplitBuilder.build(Streams(SyntheticTracks.line(700)), 1000.0)[0]
        assertNull(flat.avgHR)
        assertEquals(flat.paceSecondsPerUnit, flat.gapSeconds!!, 0.5)
    }

    @Test fun pausedTimeIsNotMovingTime() {
        // 100 s standing still in the middle: only 300 s of movement, so 900 m and no full kilometre
        val s = Streams(SyntheticTracks.line(400, speed = 3.0, pausedFrom = 100, pausedTo = 200))
        val sp = SplitBuilder.build(s, 1000.0)
        assertEquals(1, sp.size)
        assertTrue(sp[0].isPartial)
        assertEquals(900.0, sp[0].distance, 1e-9)
        assertEquals(400.0, sp[0].elapsed, 1e-9)
        assertEquals(300.0, sp[0].moving, 1e-9)
        assertEquals(300.0 / 0.9, sp[0].paceSecondsPerUnit, 1e-9)
    }

    @Test fun zoneTimes() {
        val s = Streams(SyntheticTracks.line(300, speed = 3.0, hr = { i -> if (i < 100) 100.0 else if (i < 200) 150.0 else 185.0 }, pw = { i -> if (i < 150) 100.0 else 400.0 }))
        val hr = ZoneAnalysis.heartRateTimes(s, profile.hrZones)
        assertEquals(5, hr.size)
        assertEquals(300.0, hr.sum(), 1e-9)
        // zone ceilings at 190 bpm max: 114, 133, 152, 171, 228. Each sample counts the second before it.
        assertEquals(listOf(99.0, 0.0, 100.0, 0.0, 101.0), hr)          // 100 bpm zone 1, 150 bpm zone 3, 185 bpm zone 5
        val pw = ZoneAnalysis.powerTimes(s, profile.powerZones)
        assertEquals(7, pw.size)
        // zone ceilings at 200 W FTP: 110, 150, 180, 210, 240, 300, 800
        assertEquals(listOf(149.0, 0.0, 0.0, 0.0, 0.0, 0.0, 151.0), pw)  // 100 W zone 1, 400 W zone 7
    }

    @Test fun paceZonesIgnoreStoppedSamples() {
        val s = Streams(SyntheticTracks.line(300, speed = 3.0, pausedFrom = 100, pausedTo = 150))
        val z = ZoneAnalysis.paceTimes(s, profile.paceZones)
        assertEquals(5, z.size)
        // 333 s/km against a 300 s/km threshold is zone 3; the 50 stopped seconds are not counted
        assertEquals(listOf(0.0, 0.0, 250.0, 0.0, 0.0), z)
    }

    @Test fun powerCurveAndFtp() {
        val watts = DoubleArray(1300) { 250.0 }
        val curve = PowerCurveBuilder.build(watts)
        assertEquals(PowerCurveBuilder.durations.filter { it <= 1300 }, curve.map { it.duration })
        assertTrue(curve.all { abs(it.watts - 250.0) < 1e-9 })
        assertEquals(237.5, PowerCurveBuilder.estimateFTP(curve)!!, 1e-9)
        assertEquals(250.0 * 0.90, PowerCurveBuilder.estimateFTP(curve.filter { it.duration <= 480 })!!, 1e-9)
        assertEquals(240.0, PowerCurveBuilder.estimateFTP(listOf(PowerCurvePoint(3600.0, 240.0), PowerCurvePoint(1200.0, 300.0)))!!, 0.0)
        assertNull(PowerCurveBuilder.estimateFTP(curve.filter { it.duration < 400 }))
        assertTrue(PowerCurveBuilder.build(DoubleArray(1)).isEmpty())

        val spiky = DoubleArray(100) { if (it in 40..49) 500.0 else 100.0 }
        val c2 = PowerCurveBuilder.build(spiky)
        assertEquals(500.0, c2.first { it.duration == 10.0 }.watts, 1e-9)
        assertEquals(100.0 + 400.0 * 10 / 60.0, c2.first { it.duration == 60.0 }.watts, 1e-9)

        val merged = PowerCurveBuilder.combine(listOf(curve, c2))
        assertEquals(500.0, merged.first { it.duration == 10.0 }.watts, 1e-9)
        assertEquals(250.0, merged.first { it.duration == 1200.0 }.watts, 1e-9)
        assertEquals(merged.map { it.duration }.sorted(), merged.map { it.duration })

        assertEquals(3.5, PowerCurveBuilder.bestRollingAverage(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 1.0), 2)!!, 1e-12)
        assertNull(PowerCurveBuilder.bestRollingAverage(doubleArrayOf(1.0), 2))
    }

    // Whole-activity analysis

    @Test fun analysingASteadyRunFillsEveryDerivedField() {
        val track = SyntheticTracks.line(1000, speed = 3.0, grade = 0.01, hr = { 150.0 }, cad = { 172.0 })
        val a = ActivityAnalyzer.analyse(blank(), track, profile)
        assertEquals(3000.0, a.distance, 1e-9)
        assertEquals(1000.0, a.elapsed, 1e-9)
        assertEquals(1000.0, a.movingTime, 1.0)
        assertEquals(30.0, a.elevationGain, 3.0)
        assertEquals(0.0, a.elevationLoss, 0.5)
        assertEquals(3.0, a.maxSpeed, 1e-9)
        assertEquals(150.0, a.avgHR!!, 1e-9)
        assertEquals(150.0, a.maxHR!!, 1e-9)
        assertEquals(172.0, a.avgCadence!!, 1e-9)
        assertNull(a.avgPower)
        assertNull(a.normalizedPower)
        assertTrue(a.powerCurve.isEmpty())
        assertEquals(3, a.splitsKm.size)
        assertEquals(2, a.splitsMi.size)
        assertEquals(4, a.bestEfforts.size)
        assertEquals(listOf(0.0, 0.0, 1000.0, 0.0, 0.0), a.hrZoneTimes)          // 150 bpm of 190 is zone 3
        assertEquals(14.222060229445509 * (1000.0 / 60), a.calories, 1e-6)        // Keytel, 30 year old male, 70 kg
        assertEquals(1000.0 / 60 * 1.9, a.relativeEffort, 1e-9)                   // a thousand seconds in zone 3, weight 1.9
        assertEquals(a.relativeEffort, a.trainingLoad, 0.0)                       // runs use relative effort as their load
        assertTrue(a.hasGPS)
        assertEquals(Coord(SyntheticTracks.START_LAT, SyntheticTracks.START_LON), a.startCoord)
        assertTrue(a.previewPath.size in 2..300)
        assertTrue(a.routeSignature.startsWith("6|"))               // 3000 m / 500 = 6
        assertEquals(24, a.routeSignature.removePrefix("6|").split(";").size)
        assertEquals(1000.0 / 3.0, a.avgPaceSecondsPerKm, 1.0)
    }

    @Test fun analysingARideWithPowerBuildsTheCurveAndTss() {
        val track = SyntheticTracks.line(1800, speed = 8.0, pw = { 220.0 })
        val a = ActivityAnalyzer.analyse(blank(SportType.Ride), track, profile.copy(powerZones = profile.powerZones.copy(ftp = 250.0)))
        assertEquals(220.0, a.avgPower!!, 1e-9)
        assertEquals(220.0, a.normalizedPower!!, 1e-6)
        assertTrue(a.powerCurve.isNotEmpty())
        assertEquals(220.0, a.powerCurve.first { it.duration == 1200.0 }.watts, 1e-6)
        assertTrue(a.splitsKm.isNotEmpty())
        assertTrue(a.bestEfforts.isEmpty())
        // 1800 s at NP 220, FTP 250: IF 0.88
        assertEquals((1800.0 * 220 * 0.88) / (250 * 3600) * 100, a.trainingLoad, 0.5)
        // no heart rate, so effort is TSS x 1.1
        assertEquals(a.trainingLoad * 1.1, a.relativeEffort, 0.5)
    }

    @Test fun effortWithoutHeartRateOnFootUsesPaceAgainstThreshold() {
        val track = SyntheticTracks.line(1200, speed = 1000.0 / 300.0)   // exactly threshold pace
        val a = ActivityAnalyzer.analyse(blank(), track, profile.copy(paceZones = profile.paceZones.copy(thresholdPace = 300.0)))
        assertEquals(20.0 * (1 + 5.6 * 0.45 * 0.45), a.relativeEffort, 0.5)
    }

    @Test fun aRideWithNeitherPowerNorHeartRateGetsTwoPerMinute() {
        val a = ActivityAnalyzer.analyse(blank(SportType.Ride), SyntheticTracks.line(600, speed = 6.0), profile)
        assertEquals(20.0, a.relativeEffort, 0.1)
    }

    @Test fun tooFewPointsMeansNoGpsAndNothingElseChanges() {
        val a = blank().copy(name = "manual", distance = 5000.0)
        val out = ActivityAnalyzer.analyse(a, emptyList(), profile)
        assertFalse(out.hasGPS)
        assertEquals(a.copy(hasGPS = false), out)
        assertFalse(ActivityAnalyzer.analyse(a, SyntheticTracks.line(0), profile).hasGPS)
    }

    @Test fun implausibleSpeedsDoNotSetMaxSpeed() {
        val track = SyntheticTracks.line(100, speed = 3.0).toMutableList()
        track[50] = track[50].copy(v = 25.0)       // GPS spike, above the 9 m/s running limit
        assertEquals(3.0, ActivityAnalyzer.analyse(blank(), track, profile).maxSpeed, 1e-9)
    }

    @Test fun heartRateOutsideThePlausibleBandIsIgnored() {
        val track = SyntheticTracks.line(100, hr = { i -> if (i % 2 == 0) 20.0 else 250.0 })
        val a = ActivityAnalyzer.analyse(blank(), track, profile)
        assertNull(a.avgHR)
        assertNull(a.maxHR)
    }

    // Route signature and grouping

    @Test fun routeSignatureShape() {
        assertEquals("", RouteSignature.compute(listOf(Coord(51.0, 0.0), Coord(51.1, 0.1))))
        val coords = (0..40).map { Coord(51.5 + it * 0.0001234, -0.12) }
        val sig = RouteSignature.compute(coords)
        val (bucket, rest) = sig.split("|")
        assertEquals((Math.round(coords.let { com.jashith.stride.core.geo.Geo.totalDistance(it) } / 500.0)).toString(), bucket)
        val parts = rest.split(";")
        assertEquals(24, parts.size)
        assertEquals("51.500,-0.120", parts.first())
        assertTrue(parts.all { Regex("-?\\d+\\.\\d{3},-?\\d+\\.\\d{3}").matches(it) })
    }

    @Test fun signatureUsesADotEvenWhenTheDeviceLocaleDoesNot() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-DE"))
            val sig = RouteSignature.compute((0..10).map { Coord(51.5 + it * 0.001, -0.12) })
            assertTrue(sig, sig.contains("51.500,-0.120"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun similarRoutesAreGroupedAndDifferentOnesAreNot() {
        val path = (0..80).map { Coord(51.5 + it * 0.0002, -0.12 + it * 0.0001) }
        val a = blank().copy(distance = 2000.0, previewPath = path, hasGPS = true)
        val shifted = path.map { Coord(it.lat + 0.0002, it.lon) }                        // ~22 m off everywhere
        assertTrue(RouteSignature.similar(a, a.copy(previewPath = shifted, distance = 2050.0)))
        val far = path.map { Coord(it.lat + 0.01, it.lon) }                               // 1.1 km away
        assertFalse(RouteSignature.similar(a, a.copy(previewPath = far)))
        assertFalse(RouteSignature.similar(a, a.copy(distance = 3000.0)))                // distance differs by > max(300, 8%)
        assertFalse(RouteSignature.similar(a, a.copy(hasGPS = false)))
        assertFalse(RouteSignature.similar(a, a.copy(previewPath = path.take(5))))
    }

    // Fitness and freshness

    @Test fun fitnessCurveFollowsTheExponentialFormulas() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-03-14T12:00:00Z")
        val a = blank().copy(startDate = Instant.parse("2026-03-14T06:00:00Z"), trainingLoad = 100.0)
        val curve = FitnessCurve.build(listOf(a), end = now, zone = zone)
        assertEquals(401, curve.size)
        assertEquals(java.time.LocalDate.of(2026, 3, 14), curve.last().date)
        assertEquals(java.time.LocalDate.of(2025, 2, 7), curve.first().date)
        val last = curve.last()
        assertEquals(2.3528313347756735, last.fitness, 1e-12)
        assertEquals(13.312210024981841, last.fatigue, 1e-12)
        assertEquals(-10.959378690206167, last.form, 1e-12)
        assertEquals(100.0, last.load, 0.0)
        assertEquals(0.0, curve[curve.size - 2].load, 0.0)
        // two days later the load is gone but the fitness lingers longer than the fatigue
        val later = FitnessCurve.build(listOf(a), end = Instant.parse("2026-03-16T12:00:00Z"), zone = zone).last()
        assertEquals(2.3528313347756735 * Math.pow(1 - 0.023528313347756735, 2.0), later.fitness, 1e-12)
        assertTrue(later.fatigue < last.fatigue && later.form > last.form)
    }

    @Test fun activitiesOutsideTheWindowAreIgnoredAndTimeZonesDecideTheDay() {
        val now = Instant.parse("2026-03-14T12:00:00Z")
        val old = blank().copy(startDate = Instant.parse("2024-01-01T06:00:00Z"), trainingLoad = 500.0)
        assertEquals(0.0, FitnessCurve.build(listOf(old), end = now, zone = ZoneId.of("UTC")).maxOf { it.load }, 0.0)
        // 23:30 UTC on the 13th is already the 14th in Auckland (UTC+13 in March)
        val late = blank().copy(startDate = Instant.parse("2026-03-13T23:30:00Z"), trainingLoad = 50.0)
        val the13th = java.time.LocalDate.of(2026, 3, 13)
        val the14th = java.time.LocalDate.of(2026, 3, 14)
        val auckland = FitnessCurve.build(listOf(late), end = now, zone = ZoneId.of("Pacific/Auckland"))
        assertEquals(50.0, auckland.first { it.date == the14th }.load, 0.0)
        assertEquals(0.0, auckland.first { it.date == the13th }.load, 0.0)
        val utc = FitnessCurve.build(listOf(late), end = now, zone = ZoneId.of("UTC"))
        assertEquals(50.0, utc.first { it.date == the13th }.load, 0.0)
        assertEquals(0.0, utc.first { it.date == the14th }.load, 0.0)
    }

    @Test fun formDescriptions() {
        assertEquals("Very fresh", FitnessCurve.formDescription(25.0).first)
        assertEquals("Fresh", FitnessCurve.formDescription(24.9).first)
        assertEquals("Fresh", FitnessCurve.formDescription(5.0).first)
        assertEquals("Neutral", FitnessCurve.formDescription(4.9).first)
        assertEquals("Neutral", FitnessCurve.formDescription(-10.0).first)
        assertEquals("Building", FitnessCurve.formDescription(-10.1).first)
        assertEquals("Building", FitnessCurve.formDescription(-30.0).first)
        assertEquals("Overreaching", FitnessCurve.formDescription(-30.1).first)
        assertNotNull(FitnessCurve.formDescription(0.0).second)
    }
}
