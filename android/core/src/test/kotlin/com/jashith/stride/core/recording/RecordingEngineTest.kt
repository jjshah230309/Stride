package com.jashith.stride.core.recording

import com.jashith.stride.core.SyntheticTracks
import com.jashith.stride.core.analysis.PowerModel
import com.jashith.stride.core.model.AnnouncementTrigger
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.GearKind
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.model.VoiceSettings
import com.jashith.stride.core.speech.SpeechStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

class RecordingEngineTest {

    private fun startedHarness(
        settings: com.jashith.stride.core.model.AppSettings = quickSettings(),
        sport: SportType = SportType.Run,
        environment: RecordingEnvironment = RecordingEnvironment(),
    ): Harness = Harness(settings, sport, environment).also { it.engine.start() }

    // The basics

    @Test fun aSteadyRunBuildsTheTrackAndTheFirstKilometreSplit() {
        val h = startedHarness()
        assertEquals(RecordingState.Recording, h.engine.state)
        assertEquals(listOf("Let's go."), h.speaker.texts)

        h.seconds(400)

        // The first fix only anchors; every later fix adds 3 m: 399 x 3 = 1197 m.
        assertEquals(1197.0, h.stats.distance, 0.01)
        assertEquals(400.0, h.stats.elapsed, 1e-9)
        assertEquals(400.0, h.stats.movingTime, 1e-9)
        assertEquals(400, h.stats.pathSize)
        assertEquals(3.0, h.stats.currentSpeed, 1e-9)

        // 1000 m is first passed on fix 335 (d = 1002) when 334 ticks have run:
        //   split time 334 s = 5:34, split pace 334 / 1.002 = 333.3 s/km = 5:33, same for the average.
        assertEquals(1, h.stats.splits.size)
        val split = h.stats.splits[0]
        assertEquals(1, split.index)
        assertEquals(1002.0, split.distance, 0.01)
        assertEquals(334.0, split.elapsed, 1e-9)
        assertEquals(333.33, h.stats.lastSplitPace, 0.01)
        assertEquals(
            listOf("Let's go.", "Kilometre one, in five thirty-four, pace five thirty-three, averaging five thirty-three."),
            h.speaker.texts,
        )
        assertTrue(split.gapSeconds!! in 320.0..345.0)
    }

    @Test fun finishingGivesAnAnalysedActivityWithTheLapsAndGear() {
        val gear = Gear(id = UUID.fromString("00000000-0000-4000-8000-0000000000aa"), kind = GearKind.Shoes, name = "Pegasus")
        val h = startedHarness(environment = RecordingEnvironment(defaultGear = { sport -> if (sport.gearKind == GearKind.Shoes) gear else null }))
        h.seconds(400)
        h.engine.lap()
        val (activity, track) = h.engine.finish()

        assertEquals(RecordingState.Finished, h.engine.state)
        assertEquals(400, track.size)
        assertEquals(UUID.fromString("00000000-0000-4000-8000-000000000001"), activity.id)
        assertEquals(gear.id, activity.gearID)
        assertEquals(1, activity.laps.size)
        assertEquals(1197.0, activity.distance, 0.01)
        assertEquals(400.0, activity.elapsed, 1e-9)         // the last point's own time: the first fix lands at t = 1
        assertEquals(399.0, activity.movingTime, 1e-9)       // 399 intervals between 400 points
        assertEquals(SportType.Run, activity.sport)
        assertEquals(com.jashith.stride.core.model.Activity.defaultName(SportType.Run, Instant.parse("2026-03-14T07:30:00Z")), activity.name)
        assertEquals(2, activity.splitsKm.size)               // one full kilometre and a 195 m remainder
        assertTrue(activity.splitsKm[1].isPartial)
        assertTrue(activity.hasGPS)
        assertTrue(activity.previewPath.size >= 2)
        assertTrue(activity.calories > 0)
        assertEquals("Let's go.", h.speaker.texts.first())
        // 1197 m is "one point two"; 400 s of moving time is six forty; 400 / 1.197 = 334 s/km is 5:34
        assertEquals("Nice work, one point two kilometres, in six forty, averaging five thirty-four.", h.speaker.texts.last())
    }

    @Test fun theStateMachineIgnoresNonsenseRequests() {
        val h = Harness()
        h.engine.pause(); h.engine.resume(); h.engine.lap()
        assertEquals(RecordingState.Idle, h.engine.state)
        h.engine.start()
        h.engine.start()                                      // already recording
        assertEquals(RecordingState.Recording, h.engine.state)
        h.engine.resume()                                      // not paused
        assertEquals(RecordingState.Recording, h.engine.state)
        h.engine.togglePause()
        assertEquals(RecordingState.Paused, h.engine.state)
        h.engine.pause()                                       // only a recording can be paused
        assertEquals(RecordingState.Paused, h.engine.state)
        h.engine.togglePause()
        assertEquals(RecordingState.Recording, h.engine.state)
        h.engine.finish()
        h.engine.pause()
        assertEquals(RecordingState.Finished, h.engine.state)
        h.engine.start()                                       // a finished engine can start again
        assertEquals(RecordingState.Recording, h.engine.state)
    }

    @Test fun discardingStopsTheCoachAndStartsAgainFromZero() {
        val h = startedHarness()
        h.seconds(30)
        h.engine.discard()
        assertEquals(RecordingState.Idle, h.engine.state)
        assertEquals(1, h.speaker.stops)
        assertEquals(0.0, h.stats.distance, 0.0)
        assertEquals(0, h.stats.pathSize)
        assertTrue(h.engine.trackSnapshot().isEmpty())
    }

    // Filtering

    @Test fun fixesWorseThanTheAccuracyFilterAreIgnored() {
        val h = startedHarness()
        h.seconds(5)
        val before = h.stats.pathSize
        h.second(accuracy = 31.0)                       // default filter is 30 m
        assertEquals(before, h.stats.pathSize)
        h.second(accuracy = 30.0)                       // exactly at the limit is fine
        assertEquals(before + 1, h.stats.pathSize)

        val loose = startedHarness(quickSettings(display = com.jashith.stride.core.model.DisplaySettings(gpsAccuracyFilter = 50.0)))
        loose.seconds(3)
        loose.second(accuracy = 49.0)
        assertEquals(4, loose.stats.pathSize)
    }

    @Test fun aGpsJumpIsDroppedAndDoesNotAddDistance() {
        val h = startedHarness()
        h.seconds(20)
        val before = h.stats.distance
        // 5 km away in one second: far beyond 9 m/s x 1.6
        h.clock.advance(1.0)
        h.engine.onFix(h.fix(jumpTo = 5000.0))
        h.engine.tick()
        assertEquals(before, h.stats.distance, 1e-9)
        assertEquals(20, h.stats.pathSize)
        // the next real fix is also "far" from the jump anchor and is dropped too, re-anchoring on itself
        h.d = 70.0
        h.clock.advance(1.0)
        h.engine.onFix(h.fix(speed = 3.0, moving = false))
        h.engine.tick()
        assertEquals(20, h.stats.pathSize)
        // then recording carries on normally with no 5 km spike
        h.seconds(10)
        assertEquals(30, h.stats.pathSize)
        assertTrue("distance ${h.stats.distance}", h.stats.distance in before..before + 40)
    }

    @Test fun theJumpLimitIsOnePointSixTimesTheSportsPlausibleSpeed() {
        // running: plausible 9 m/s, so more than 14.4 m between two fixes one second apart is a jump
        fun accepted(sport: SportType, metresInOneSecond: Double): Boolean {
            val h = startedHarness(sport = sport)
            h.seconds(5, speed = 3.0)
            val before = h.stats.pathSize
            h.second(speed = metresInOneSecond)
            return h.stats.pathSize == before + 1
        }
        assertTrue(accepted(SportType.Run, 14.0))
        assertFalse(accepted(SportType.Run, 15.0))
        // riding: plausible 30 m/s, so the limit is 48 m/s
        assertTrue(accepted(SportType.Ride, 47.0))
        assertFalse(accepted(SportType.Ride, 49.0))
    }

    @Test fun slowRealMovementStillAccumulatesDespiteTheNoiseFloor() {
        // 0.8 m per second is below the 1.5 m floor for a single fix, but the anchor is held so it adds up
        val h = startedHarness()
        h.seconds(30, speed = 0.8)
        assertTrue("got ${h.stats.distance}", h.stats.distance > 15)
        assertTrue(h.stats.distance <= 0.8 * 29 + 1.0)
    }

    @Test fun aMissingSpeedIsDerivedFromTheMovement() {
        val h = startedHarness()
        h.second(speed = 3.0, reportedSpeed = -1.0)       // first fix: nothing to derive from, so 0
        h.second(speed = 3.0, reportedSpeed = -1.0)
        assertEquals(3.0, h.stats.currentSpeed, 1e-6)
        assertEquals(3.0, h.stats.distance, 1e-6)
    }

    // Pausing

    @Test fun manualPauseFreezesDistanceAndMovingTime() {
        val h = startedHarness()
        h.seconds(10)
        h.engine.pause()
        assertEquals(RecordingState.Paused, h.engine.state)
        val distance = h.stats.distance
        h.seconds(5)                                            // still walking while paused
        assertEquals(distance, h.stats.distance, 1e-9)
        assertEquals(10.0, h.stats.movingTime, 1e-9)
        assertEquals(15.0, h.stats.elapsed, 1e-9)
        assertFalse(h.engine.trackSnapshot().last().moving)     // points are still recorded, flagged not moving
        h.engine.resume()
        h.seconds(5)
        assertTrue(h.stats.distance > distance)
        assertEquals(listOf("Let's go.", "Paused.", "Resumed."), h.speaker.texts)
    }

    @Test fun autoPauseKicksInAfterFourSlowSecondsAndResumesOnMovement() {
        val voice = VoiceSettings(announceAutoPause = true)
        val h = startedHarness(quickSettings(voice = voice))
        h.seconds(10)
        h.seconds(3, moving = false)
        assertEquals("three slow seconds is not enough", RecordingState.Recording, h.engine.state)
        h.second(moving = false)
        assertEquals(RecordingState.AutoPaused, h.engine.state)
        val distance = h.stats.distance
        h.seconds(5, moving = false)
        assertEquals(distance, h.stats.distance, 1e-9)
        h.second(speed = 3.0)                                    // a moving fix wakes it immediately
        assertEquals(RecordingState.Recording, h.engine.state)
        assertTrue(h.stats.distance > distance)
        assertEquals(listOf("Let's go.", "Auto paused.", "Rolling again."), h.speaker.texts)
    }

    @Test fun autoPauseIsSilentByDefaultAndCanBeSwitchedOffOrSkippedIndoors() {
        val quiet = startedHarness()
        quiet.seconds(10); quiet.seconds(6, moving = false)
        assertEquals(RecordingState.AutoPaused, quiet.engine.state)
        assertEquals(listOf("Let's go."), quiet.speaker.texts)

        val off = startedHarness(quickSettings(display = com.jashith.stride.core.model.DisplaySettings(autoPause = false)))
        off.seconds(10); off.seconds(20, moving = false)
        assertEquals(RecordingState.Recording, off.engine.state)

        val treadmill = startedHarness(sport = SportType.Treadmill)
        treadmill.seconds(10); treadmill.seconds(20, moving = false)
        assertEquals(RecordingState.Recording, treadmill.engine.state)

        // cycling uses a higher threshold: 1.0 m/s counts as stopped on a bike but not on foot
        val bike = startedHarness(sport = SportType.Ride)
        bike.seconds(10, speed = 5.0); bike.seconds(6, speed = 1.0)
        assertEquals(RecordingState.AutoPaused, bike.engine.state)
        val walk = startedHarness()
        walk.seconds(10); walk.seconds(6, speed = 1.0)
        assertEquals(RecordingState.Recording, walk.engine.state)
    }

    // Splits and laps

    @Test fun timeIntervalSplitsSayIntervalAndUseTheIntervalLength() {
        val voice = VoiceSettings(trigger = AnnouncementTrigger.Time, timeInterval = 60.0)
        val h = startedHarness(quickSettings(voice = voice))
        h.seconds(130)
        // tick 60: distance 3 x 59 = 177 m; pace 60 / 0.177 = 339 s/km = 5:39
        // tick 120: 180 m more; pace 60 / 0.180 = 333 = 5:33; running average 120 / 0.357 = 336 = 5:36
        assertEquals(
            listOf(
                "Let's go.",
                "Interval one, in one minute, pace five thirty-nine, averaging five thirty-nine.",
                "Interval two, in one minute, pace five thirty-three, averaging five thirty-six.",
            ),
            h.speaker.texts,
        )
        assertTrue("time splits do not add rows to the splits table", h.stats.splits.isEmpty())
    }

    @Test fun bothTriggersShareOneCounter() {
        val voice = VoiceSettings(trigger = AnnouncementTrigger.Both, timeInterval = 60.0)
        val h = startedHarness(quickSettings(voice = voice))
        h.seconds(340)
        val said = h.speaker.texts
        assertEquals(5, said.count { it.startsWith("Interval") })
        assertTrue(said.last().startsWith("Kilometre six,"))       // five intervals, then the first kilometre
    }

    @Test fun splitsCanBeTurnedOffAndTheIntervalCanBeBigger() {
        val off = startedHarness(quickSettings(voice = VoiceSettings(trigger = AnnouncementTrigger.Off)))
        off.seconds(400)
        assertTrue(off.stats.splits.isEmpty())
        assertEquals(listOf("Let's go."), off.speaker.texts)

        val half = startedHarness(quickSettings(voice = VoiceSettings(distanceInterval = 0.5)))
        half.seconds(400)
        assertEquals(2, half.stats.splits.size)                    // 500 m and 1000 m marks
        val miles = startedHarness(quickSettings(display = com.jashith.stride.core.model.DisplaySettings(units = UnitSystem.Imperial)))
        miles.seconds(600)                                          // 1797 m > 1609.344 m
        assertEquals(1, miles.stats.splits.size)
        assertTrue(miles.speaker.texts.last().startsWith("Mile one,"))
    }

    @Test fun veryShortIntervalsAreClampedToFiftyMetres() {
        val h = startedHarness(quickSettings(voice = VoiceSettings(distanceInterval = 0.001)))
        h.seconds(60)                                               // 177 m: three 50 m splits
        assertEquals(3, h.stats.splits.size)
    }

    @Test fun lapsNeedFiveMetresOrFiveSecondsAndAnnounceThemselves() {
        val h = startedHarness()
        h.seconds(2)
        h.engine.lap()
        assertTrue("only 3 m in 2 s", h.stats.laps.isEmpty())
        h.seconds(18)
        h.engine.lap()
        assertEquals(1, h.stats.laps.size)
        val lap = h.stats.laps[0]
        assertEquals(1, lap.index)
        assertEquals(20.0, lap.moving, 1e-9)
        assertEquals(57.0, lap.distance, 0.01)
        // pace 20 / 0.057 = 350.9 s/km = 5:51
        assertEquals("Lap one, twenty seconds, five fifty-one.", h.speaker.texts.last())
        h.seconds(10)
        h.engine.lap()
        assertEquals(30.0, h.stats.laps[1].distance, 0.01)
        assertEquals(20.0, h.stats.laps[1].startTime, 1e-9)
        assertEquals(2, h.stats.laps[1].index)
    }

    @Test fun halfwayIsOnlyAnnouncedAgainstADistanceGoalAndOnlyOnce() {
        val voice = VoiceSettings(announceHalfway = true)
        val withGoal = startedHarness(quickSettings(voice = voice), environment = RecordingEnvironment(distanceGoalTarget = 2000.0))
        withGoal.seconds(400)
        // at fix 335 the split and then halfway fire: 1002 m in 334 s
        assertEquals(1, withGoal.speaker.texts.count { it.startsWith("Halfway") })
        assertEquals("Halfway, one kilometre, in five thirty-four.", withGoal.speaker.texts.first { it.startsWith("Halfway") })
        val splitIndex = withGoal.speaker.texts.indexOfFirst { it.startsWith("Kilometre one") }
        assertEquals(splitIndex + 1, withGoal.speaker.texts.indexOfFirst { it.startsWith("Halfway") })

        val noGoal = startedHarness(quickSettings(voice = voice))
        noGoal.seconds(400)
        assertEquals(0, noGoal.speaker.texts.count { it.startsWith("Halfway") })
        val off = startedHarness(quickSettings(), environment = RecordingEnvironment(distanceGoalTarget = 2000.0))
        off.seconds(400)
        assertEquals(0, off.speaker.texts.count { it.startsWith("Halfway") })
    }

    @Test fun paceAlertsFireImmediatelyAndNoMoreOftenThanEveryFortyFiveSeconds() {
        val voice = VoiceSettings(targetPaceAlertsEnabled = true, targetPaceSeconds = 270.0)
        val h = startedHarness(quickSettings(voice = voice))
        h.seconds(100)
        val alerts = h.speaker.said.filter { it.text.contains("behind target") }
        // 3 m/s is 333 s/km: 63 s behind a 270 target. First on tick 1, then every 46 s.
        assertEquals(3, alerts.size)
        assertTrue(alerts.all { it.immediate })
        assertEquals("Sixty-three seconds behind target, lift it a little.", alerts[0].text)
        assertEquals(listOf("sixty-three seconds behind target", "lift it a little"), alerts[0].phrases)

        val onPace = startedHarness(quickSettings(voice = VoiceSettings(targetPaceAlertsEnabled = true, targetPaceSeconds = 330.0)))
        onPace.seconds(100)
        assertEquals("within 15 s of target", 0, onPace.speaker.said.count { it.text.contains("target") })
        val disabled = startedHarness(quickSettings(voice = VoiceSettings(targetPaceAlertsEnabled = false, targetPaceSeconds = 270.0)))
        disabled.seconds(100)
        assertEquals(0, disabled.speaker.said.count { it.text.contains("target") })
    }

    @Test fun speakingTheCurrentStatsOnDemandUsesTheNextSplitNumber() {
        val h = startedHarness()
        h.seconds(60)
        h.engine.speakCurrentStats()
        // no split yet, so only the number and the running average (60 s over 177 m is 5:39 per km) are known
        assertEquals("Kilometre one, averaging five thirty-nine.", h.speaker.texts.last())
        assertFalse(h.speaker.said.last().immediate)
    }

    // Countdown

    @Test fun theCountdownSpeaksEachNumberThenGoThenStarts() {
        val settings = quickSettings().copy(
            display = quickSettings().display.copy(countdownSeconds = 3),
            voice = quickSettings().voice.copy(countdownBeforeStart = true),
        )
        val h = Harness(settings)
        h.engine.start()
        assertEquals(RecordingState.Countdown, h.engine.state)
        assertEquals(3, h.stats.countdownValue)
        h.clock.advance(1.0); h.engine.tick()
        h.clock.advance(1.0); h.engine.tick()
        assertEquals(RecordingState.Countdown, h.engine.state)
        h.clock.advance(1.0); h.engine.tick()
        assertEquals(RecordingState.Recording, h.engine.state)
        assertEquals(listOf("Three.", "Two.", "One.", "Go.", "Let's go."), h.speaker.texts)
        // recording started when the countdown ended
        h.seconds(10)
        assertEquals(10.0, h.stats.movingTime, 1e-9)
        assertEquals(10.0, h.engine.trackSnapshot().last().t, 1e-9)
    }

    @Test fun noCountdownWhenItIsSwitchedOffEitherWay() {
        val a = Harness(quickSettings().copy(display = quickSettings().display.copy(countdownSeconds = 0), voice = quickSettings().voice.copy(countdownBeforeStart = true)))
        a.engine.start()
        assertEquals(RecordingState.Recording, a.engine.state)
        val b = Harness(quickSettings().copy(display = quickSettings().display.copy(countdownSeconds = 5), voice = quickSettings().voice.copy(countdownBeforeStart = false)))
        b.engine.start()
        assertEquals(RecordingState.Recording, b.engine.state)
    }

    @Test fun discardingDuringTheCountdownCancelsIt() {
        val h = Harness(quickSettings().copy(display = quickSettings().display.copy(countdownSeconds = 3), voice = quickSettings().voice.copy(countdownBeforeStart = true)))
        h.engine.start()
        h.engine.discard()
        h.clock.advance(1.0); h.engine.tick(); h.clock.advance(1.0); h.engine.tick()
        assertEquals(RecordingState.Idle, h.engine.state)
    }

    // The coach's switches

    @Test fun aSilencedCoachSaysNothingButStillRecords() {
        val h = startedHarness(quickSettings(voice = VoiceSettings(enabled = false)))
        h.seconds(400)
        assertTrue(h.speaker.said.isEmpty())
        assertEquals(1, h.stats.splits.size)
        h.engine.finish()
        assertTrue(h.speaker.said.isEmpty())
    }

    @Test fun styleAndMetricChoicesReachTheSpeech() {
        val voice = VoiceSettings(style = SpeechStyle.Brief, runMetrics = listOf(com.jashith.stride.core.model.SpokenMetric.SplitNumber, com.jashith.stride.core.model.SpokenMetric.TotalDistance))
        val h = startedHarness(quickSettings(voice = voice))
        h.seconds(340)
        val split = h.speaker.said.last()
        assertEquals(listOf("one", "one kilometre"), split.phrases)
        assertEquals(300, split.breakMs)
        h.engine.settings = quickSettings(voice = voice.copy(style = SpeechStyle.Detailed))   // changed mid-run
        h.seconds(10)
        h.engine.speakCurrentStats()
        // 349 x 3 = 1047 m is still "one kilometre" to one decimal place
        assertEquals(listOf("Kilometre two complete", "total distance one kilometre"), h.speaker.said.last().phrases)
    }

    // Sensors

    @Test fun heartRatePrefersAStrapThenFreshHealthDataThenNothing() {
        val h = startedHarness()
        h.seconds(3)
        assertEquals(HeartRateSource.None, h.stats.heartRateSource)
        assertNull(h.engine.trackSnapshot().last().hr)

        h.engine.sensors.healthFresh = true; h.engine.sensors.healthHeartRate = 140.0; h.engine.sensors.healthFromWatch = true
        h.seconds(2)
        assertEquals(HeartRateSource.Watch, h.stats.heartRateSource)
        assertEquals(140.0, h.stats.heartRate, 0.0)

        h.engine.sensors.healthFromWatch = false
        h.second()
        assertEquals(HeartRateSource.Health, h.stats.heartRateSource)

        h.engine.sensors.strapConnected = true; h.engine.sensors.strapHeartRate = 155.0
        h.seconds(2)
        assertEquals(HeartRateSource.Strap, h.stats.heartRateSource)
        assertEquals(155.0, h.stats.heartRate, 0.0)

        h.engine.sensors.strapHeartRate = 20.0          // a bad strap reading falls through to health
        h.second()
        assertEquals(HeartRateSource.Health, h.stats.heartRateSource)

        h.engine.sensors.strapConnected = false; h.engine.sensors.healthFresh = false     // everything went stale
        h.seconds(2)
        assertEquals(HeartRateSource.None, h.stats.heartRateSource)
        assertEquals(0.0, h.stats.heartRate, 0.0)
        assertNull(h.engine.trackSnapshot().last().hr)

        val (activity, track) = h.engine.finish()
        val values = track.mapNotNull { it.hr }
        assertTrue(values.isNotEmpty() && values.all { it > 30 })
        assertEquals(values.sum() / values.size, activity.avgHR!!, 1e-9)
    }

    @Test fun cadenceComesFromTheStepSensorOnFootButNotOnABike() {
        val foot = startedHarness()
        foot.engine.sensors.cadence = 172.0
        foot.seconds(3)
        assertEquals(172.0, foot.stats.cadence, 0.0)
        assertEquals(172.0, foot.engine.trackSnapshot().last().cad!!, 0.0)
        val bike = startedHarness(sport = SportType.Ride)
        bike.engine.sensors.cadence = 172.0
        bike.seconds(3, speed = 6.0)
        assertEquals(0.0, bike.stats.cadence, 0.0)
        assertNull(bike.engine.trackSnapshot().last().cad)
    }

    @Test fun barometerAltitudeIsAnchoredToTheFirstGpsAltitude() {
        val h = startedHarness()
        h.engine.sensors.hasBarometer = true
        h.engine.sensors.relativeAltitude = 2.0
        h.second(altitude = 100.0)                                  // anchor = 100 - 2 = 98
        h.engine.sensors.relativeAltitude = 7.5
        h.second(altitude = 130.0)                                  // GPS wanders; the barometer does not
        h.engine.sensors.relativeAltitude = 4.0
        h.second(altitude = 90.0)
        val alts = h.engine.trackSnapshot().map { it.alt }
        assertEquals(listOf(100.0, 105.5, 102.0), alts)
        assertEquals(102.0, h.stats.altitude, 1e-9)
    }

    @Test fun withoutABarometerGpsAltitudeIsSmoothedByAKalmanFilter() {
        val h = startedHarness()
        h.second(altitude = 100.0)
        h.second(altitude = 120.0)                                  // one wild reading
        val second = h.engine.trackSnapshot()[1].alt
        assertTrue("smoothed, got $second", second > 100.0 && second < 110.0)
        repeat(60) { h.second(altitude = 100.0) }
        assertEquals(100.0, h.engine.trackSnapshot().last().alt, 0.5)
    }

    @Test fun elevationGainIgnoresNoiseBelowOneMetre() {
        val flat = startedHarness()
        flat.engine.sensors.hasBarometer = true
        flat.second(altitude = 50.0)
        for (i in 0 until 40) {
            flat.engine.sensors.relativeAltitude = if (i % 2 == 0) 0.7 else -0.7
            flat.second()
        }
        assertEquals(0.0, flat.stats.elevationGain, 0.0)
        assertEquals(0.0, flat.stats.elevationLoss, 0.0)

        val hill = startedHarness()
        hill.engine.sensors.hasBarometer = true
        hill.second(altitude = 50.0)
        for (i in 1..20) {
            hill.engine.sensors.relativeAltitude = i * 0.5           // 10 m climb in 20 s
            hill.second()
        }
        for (i in 1..10) {
            hill.engine.sensors.relativeAltitude = 10.0 - i * 0.5     // 5 m back down
            hill.second()
        }
        // The dead band only books a change once it exceeds 1 m from the last reference, so half-metre steps
        // are counted in 1.5 m chunks: climbing 50 -> 60 books 6 x 1.5 = 9 (references 51.5 ... 59),
        // and descending 60 -> 55 books 2 x 1.5 = 3 (references 57.5 and 56).
        assertEquals(9.0, hill.stats.elevationGain, 1e-9)
        assertEquals(3.0, hill.stats.elevationLoss, 1e-9)
    }

    // Power

    @Test fun cyclingPowerUsesTheProfileAndTheDefaultBikesWeight() {
        val h = startedHarness(sport = SportType.Ride)
        h.seconds(10, speed = 8.0)
        // steady 8 m/s on the flat: profile 70 kg + 9 kg bike
        val expected = PowerModel.cyclingWatts(8.0, 0.0, 0.0, 79.0, 0.005, 0.32)
        assertEquals(expected, h.engine.trackSnapshot().last().pw!!, 1e-9)
        assertEquals(expected, h.stats.power, 1e-9)

        val gear = Gear(kind = GearKind.Bike, name = "Gravel", weightKg = 12.0)
        val heavy = startedHarness(sport = SportType.Ride, environment = RecordingEnvironment(defaultGear = { gear }))
        heavy.seconds(10, speed = 8.0)
        assertEquals(PowerModel.cyclingWatts(8.0, 0.0, 0.0, 82.0, 0.005, 0.32), heavy.stats.power, 1e-9)

        val unweighed = startedHarness(sport = SportType.Ride, environment = RecordingEnvironment(defaultGear = { Gear(kind = GearKind.Bike, name = "?") }))
        unweighed.seconds(10, speed = 8.0)
        assertEquals(expected, unweighed.stats.power, 1e-9)         // weight 0 means unknown: the profile's 9 kg
    }

    @Test fun runningPowerIsFromTheRunningModel() {
        val h = startedHarness()
        h.seconds(10)
        assertEquals(PowerModel.runningWatts(3.0, 0.0, 70.0), h.stats.power, 1e-9)
        assertEquals(205.8, h.stats.power, 1e-9)
    }

    // Idle fixes

    @Test fun fixesBeforeStartingOnlyUpdateTheMapAndNearbySegments() {
        val h = Harness()
        h.engine.onFix(h.fix())
        assertNotNull(h.stats.currentCoord)
        assertEquals(0, h.stats.pathSize)
        assertTrue(h.engine.trackSnapshot().isEmpty())
    }

    // Crash recovery

    @Test fun aRecoverySnapshotIsHandedOutEveryTenSecondsWithACopyOfTheTrack() {
        val h = startedHarness()
        h.seconds(35)
        // ticks 1, 12, 23, 34: the first writes immediately, then once more than 10 s has passed
        assertEquals(4, h.recoveries.size)
        val first = h.recoveries[0]
        assertEquals(1, first.points.size)
        val last = h.recoveries.last()
        assertEquals(34, last.points.size)
        assertEquals(34.0, last.elapsed, 1e-9)
        assertEquals(0.0, last.pausedDuration, 1e-9)
        assertEquals(SportType.Run, last.sport)
        assertEquals(h.engine.trackSnapshot().take(34), last.points)
        h.seconds(5)
        assertEquals("an earlier snapshot is not mutated by later recording", 34, last.points.size)
        assertEquals(4, h.recoveries.size)
    }

    @Test fun noRecoverySnapshotsWithoutASink() {
        val h = startedHarness()
        h.engine.recoverySink = null
        h.seconds(30)
        assertTrue(h.recoveries.isEmpty())
    }

    @Test fun aRecordingCanBeRestoredAfterTheAppDiesAndContinued() {
        val first = startedHarness()
        first.seconds(50)
        first.engine.lap()
        first.seconds(70)
        val snapshot = first.recoveries.last()                 // written on tick 111, so it holds 111 points and the lap
        assertEquals(111, snapshot.points.size)
        assertEquals(1, snapshot.laps.size)

        // a fresh process: new engine, the clock carries on from where it was
        val second = Harness()
        second.clock.now = first.clock.now.plusSeconds(30)
        second.engine.restoreFromRecovery(snapshot)

        assertEquals(RecordingState.Paused, second.engine.state)
        assertEquals(snapshot.points.size, second.stats.pathSize)
        assertEquals(snapshot.points.last().d, second.stats.distance, 1e-9)
        assertEquals(snapshot.points.last().t, second.stats.elapsed, 1e-9)
        assertEquals(snapshot.points.size - 1.0, second.stats.movingTime, 1e-9)     // every interval was moving
        assertEquals(snapshot.laps, second.stats.laps)

        second.engine.resume()
        assertEquals(RecordingState.Recording, second.engine.state)
        // 30 s passed while the app was dead, so the next fix is stamped well after the last point
        second.d = snapshot.points.last().d
        second.second(speed = 3.0)
        val points = second.engine.trackSnapshot()
        assertEquals(snapshot.points.size + 1, points.size)
        assertTrue(points.last().t > snapshot.points.last().t)
        // 330 m were recorded, so the next mark is still the first kilometre
        assertEquals(0, second.stats.splits.size)
        second.seconds(300)
        assertEquals(1, second.stats.splits.size)
        assertEquals(1, second.stats.splits[0].index)
    }

    @Test fun restoringBeyondAKilometreContinuesNumberingSplits() {
        val first = startedHarness()
        first.seconds(400)
        val snapshot = first.recoveries.last().let { it.copy(points = first.engine.trackSnapshot()) }
        val second = Harness()
        second.clock.now = first.clock.now
        second.engine.restoreFromRecovery(snapshot)
        second.engine.resume()
        second.d = snapshot.points.last().d
        second.seconds(300)
        assertEquals("the second kilometre is split 2", listOf(2), second.stats.splits.map { it.index })
    }

    // Live segments

    private fun lineSegment(from: Double, to: Double, name: String = "Hill"): Segment {
        val path = (0..20).map { SyntheticTracks.latAt(from + (to - from) * it / 20) }.map { com.jashith.stride.core.model.Coord(it, SyntheticTracks.START_LON) }
        return Segment(
            id = UUID.fromString("00000000-0000-4000-8000-0000000000bb"), name = name, sport = SportType.Run, path = path,
            distance = to - from, elevationGain = 0.0, averageGrade = 0.0, maxGrade = 0.0,
        )
    }

    @Test fun racingAStarredSegmentAnnouncesTheStartProgressAndFinish() {
        val seg = lineSegment(300.0, 600.0)
        val env = RecordingEnvironment(starredSegments = listOf(seg), personalBests = mapOf(seg.id to 90.0))
        val h = startedHarness(environment = env)
        h.seconds(240)

        val said = h.speaker.said
        val start = said.first { it.text.startsWith("Segment Hill") }
        assertTrue(start.immediate)
        assertEquals("Segment Hill, your best is one thirty, go.", start.text)
        val progress = said.filter { it.text.endsWith("to go.") }
        assertEquals(4, progress.size)                                  // 25, 50, 75 and 90 percent
        assertTrue(progress.all { !it.immediate && it.text.contains("behind your best") })
        val finish = said.last { it.text.startsWith("Hill done") }
        assertTrue(finish.immediate)
        assertTrue(finish.text.endsWith("off your best."))
        assertNull(h.stats.liveSegment)
    }

    @Test fun aFasterThanRecordRunIsCelebrated() {
        val seg = lineSegment(300.0, 600.0)
        val env = RecordingEnvironment(starredSegments = listOf(seg), personalBests = mapOf(seg.id to 400.0))
        val h = startedHarness(environment = env)
        h.seconds(240)
        assertTrue(h.speaker.texts.any { it.startsWith("New record on Hill") })
    }

    @Test fun liveSegmentCallsCanBeSwitchedOffTogetherOrJustTheVoice() {
        val seg = lineSegment(300.0, 600.0)
        val env = RecordingEnvironment(starredSegments = listOf(seg), personalBests = mapOf(seg.id to 90.0))

        val noSegmentVoice = startedHarness(quickSettings(voice = VoiceSettings(announceSegments = false)), environment = env)
        noSegmentVoice.seconds(240)
        assertEquals(listOf("Let's go."), noSegmentVoice.speaker.texts)

        val noLive = startedHarness(quickSettings(display = com.jashith.stride.core.model.DisplaySettings(showLiveSegments = false)), environment = env)
        noLive.seconds(240)
        assertNull(noLive.stats.liveSegment)
        assertEquals(listOf("Let's go."), noLive.speaker.texts)

        val silentButTracked = startedHarness(quickSettings(display = com.jashith.stride.core.model.DisplaySettings(liveSegmentVoice = false)), environment = env)
        silentButTracked.seconds(240)
        assertEquals(listOf("Let's go."), silentButTracked.speaker.texts)
    }

    @Test fun theActiveSegmentIsExposedWhileRacingAndNearbyBeforehand() {
        val seg = lineSegment(300.0, 600.0)
        val env = RecordingEnvironment(starredSegments = listOf(seg), personalBests = mapOf(seg.id to 90.0))
        val h = startedHarness(environment = env)
        h.seconds(100)                                               // d = 297 m: inside the start radius
        val live = h.stats.liveSegment
        assertNotNull(live)
        assertEquals("Hill", live!!.name)
        h.seconds(30)
        assertTrue(h.stats.liveSegment!!.fraction > 0.2)
    }

    // Misc

    @Test fun settingsChangesReachTheAnnouncer() {
        val h = Harness()
        h.engine.settings = quickSettings(
            voice = VoiceSettings(style = SpeechStyle.Detailed),
            display = com.jashith.stride.core.model.DisplaySettings(units = UnitSystem.Imperial),
        )
        assertEquals(SpeechStyle.Detailed, h.engine.announcer.settings.style)
        assertEquals(UnitSystem.Imperial, h.engine.announcer.units)
    }

    @Test fun theSnapshotTheCoachReadsMatchesTheLiveNumbers() {
        val h = startedHarness()
        h.engine.sensors.healthFresh = true; h.engine.sensors.healthHeartRate = 150.0
        h.seconds(60)
        val s = h.engine.makeSnapshot()
        assertEquals(SportType.Run, s.sport)
        assertEquals(h.stats.distance, s.totalDistance, 1e-9)
        assertEquals(60.0, s.totalTime, 1e-9)
        assertEquals(60.0, s.movingTime, 1e-9)
        assertEquals(3.0, s.currentSpeed, 1e-9)
        assertEquals(3.0, s.averageSpeed, 0.1)
        assertEquals(1000.0 / 3.0, s.currentPaceSecondsPerUnit, 1e-6)
        assertEquals(1000.0 / 3.0, s.gradeAdjustedPaceSecondsPerUnit, 1.0)   // flat: no adjustment
        assertEquals(150.0, s.heartRate, 0.0)
        assertEquals(150.0, s.averageHeartRate, 0.0)
        assertEquals(h.stats.calories, s.calories, 1e-9)
        assertTrue(s.averagePaceSecondsPerUnit in 330.0..345.0)
        assertEquals(h.engine.averagePaceSecondsPerUnit, s.averagePaceSecondsPerUnit, 1e-9)
        assertEquals(h.engine.currentPaceSecondsPerUnit, s.currentPaceSecondsPerUnit, 1e-9)
        assertTrue(abs(h.engine.averageSpeedValue - s.averageSpeed) < 1e-9)
    }

    @Test fun theStatsFlowAlwaysHoldsTheLatestValues() {
        val h = startedHarness()
        assertEquals(RecordingState.Recording, h.engine.stats.value.state)
        h.seconds(5)
        assertEquals(5, h.engine.stats.value.pathSize)
        assertEquals(5, h.engine.stats.value.path.size)
        h.engine.finish()
        assertEquals(RecordingState.Finished, h.engine.stats.value.state)
        assertEquals(Instant.parse("2026-03-14T07:30:00Z").plusSeconds(5), h.clock.now)
    }
}
