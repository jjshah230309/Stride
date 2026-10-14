package com.jashith.stride.core.recording

import com.jashith.stride.core.analysis.ActivityAnalyzer
import com.jashith.stride.core.analysis.EffortScore
import com.jashith.stride.core.analysis.GradeAdjustedPace
import com.jashith.stride.core.analysis.PowerModel
import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.geo.Smooth
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.Lap
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.Split
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Streams
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.model.AnnouncementTrigger
import com.jashith.stride.core.speech.CoachSnapshot
import com.jashith.stride.core.store.RecoverySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class RecordingState {
    Idle, Countdown, Recording, Paused, AutoPaused, Finished;

    val isActive: Boolean get() = this == Recording || this == Paused || this == AutoPaused
    val isPaused: Boolean get() = this == Paused || this == AutoPaused
}

enum class HeartRateSource { None, Strap, Watch, Health }

/** One position fix, as reported by the platform's location provider. */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val horizontalAccuracy: Double,
    val verticalAccuracy: Double,
    /** Negative when the provider has no speed for this fix. */
    val speed: Double,
    val timestamp: Instant,
) {
    val coord: Coord get() = Coord(lat, lon)
    fun distanceTo(other: GpsFix): Double = Geo.distance(coord, other.coord)
}

/** Live readings from the phone's other sensors. The platform layer writes them; the engine reads them. */
class Sensors {
    var hasBarometer: Boolean = false
    /** Metres since the barometer started. */
    var relativeAltitude: Double = 0.0
    /** Steps per minute from the pedometer, 0 when unknown. */
    var cadence: Double = 0.0
    var strapConnected: Boolean = false
    var strapHeartRate: Double = 0.0
    /** A health-platform reading less than 45 seconds old. */
    var healthFresh: Boolean = false
    var healthHeartRate: Double = 0.0
    var healthFromWatch: Boolean = false
}

/** What the engine needs to know about the rest of the app when a recording starts. */
class RecordingEnvironment(
    val starredSegments: List<Segment> = emptyList(),
    /** Fastest time on each starred segment. */
    val personalBests: Map<UUID, Double> = emptyMap(),
    val defaultGear: (SportType) -> Gear? = { null },
    /** Target of the first active distance goal, if any; "halfway" only means something against one. */
    val distanceGoalTarget: Double? = null,
)

/** Everything the record screen shows, as one immutable value. */
data class RecordingStats(
    val state: RecordingState = RecordingState.Idle,
    val sport: SportType = SportType.Run,
    val elapsed: Double = 0.0,
    val movingTime: Double = 0.0,
    val distance: Double = 0.0,
    val currentSpeed: Double = 0.0,
    val elevationGain: Double = 0.0,
    val elevationLoss: Double = 0.0,
    val currentGrade: Double = 0.0,
    val altitude: Double = 0.0,
    val heartRate: Double = 0.0,
    val cadence: Double = 0.0,
    val power: Double = 0.0,
    val calories: Double = 0.0,
    val countdownValue: Int = 0,
    val heartRateSource: HeartRateSource = HeartRateSource.None,
    val splits: List<Split> = emptyList(),
    val laps: List<Lap> = emptyList(),
    val currentSplitDistance: Double = 0.0,
    val currentSplitTime: Double = 0.0,
    val lastSplitPace: Double = 0.0,
    val currentCoord: Coord? = null,
    /** The recorded route so far. The list is shared, so compare [pathSize] to notice growth. */
    val path: List<Coord> = emptyList(),
    val pathSize: Int = 0,
    val liveSegment: LiveSegmentEngine.LiveState? = null,
    val nearbySegments: List<Segment> = emptyList(),
)

/**
 * The recorder, with every Apple or Android type taken out: fixes, sensor readings and a 1 Hz tick go in;
 * a track, splits, laps, announcements and an analysed activity come out. It is a port of iOS
 * `RecordingSession` and keeps its filtering thresholds exactly, so a run records the same on both.
 *
 * Not thread safe. The foreground service drives it from a single thread.
 */
class RecordingEngine(
    private val clock: () -> Instant,
    val announcer: CoachAnnouncer,
    private val newId: () -> UUID = { UUID.randomUUID() },
) {
    val sensors = Sensors()
    val liveSegments = LiveSegmentEngine()

    /** Called at most every 10 seconds while recording, with a copy of the track. Do the file write off-thread. */
    var recoverySink: ((RecoverySnapshot) -> Unit)? = null

    var environment: RecordingEnvironment = RecordingEnvironment()

    var settings: AppSettings = AppSettings()
        set(value) {
            field = value
            announcer.settings = value.voice
            announcer.units = value.units
        }

    var sport: SportType = SportType.Run

    private val _stats = MutableStateFlow(RecordingStats())
    val stats: StateFlow<RecordingStats> get() = _stats

    var state: RecordingState = RecordingState.Idle
        private set

    // Published values
    private var elapsed = 0.0
    private var movingTime = 0.0
    private var distance = 0.0
    private var currentSpeed = 0.0
    private var elevationGain = 0.0
    private var elevationLoss = 0.0
    private var currentGrade = 0.0
    private var altitude = 0.0
    private var heartRate = 0.0
    private var cadence = 0.0
    private var power = 0.0
    private var calories = 0.0
    private var countdownValue = 0
    private var heartRateSource = HeartRateSource.None
    private val splits = ArrayList<Split>()
    private val laps = ArrayList<Lap>()
    private var currentSplitDistance = 0.0
    private var currentSplitTime = 0.0
    private var lastSplitPace = 0.0
    private var currentCoord: Coord? = null
    private val path = ArrayList<Coord>()
    private val pathView: List<Coord> = Collections.unmodifiableList(path)

    // Internals
    private var activityID: UUID = newId()
    private var startDate: Instant = Instant.EPOCH
    private val points = ArrayList<TrackPoint>()
    private var lastTick: Instant? = null
    private var lastAcceptedFix: GpsFix? = null
    private var pendingDistanceAnchor: GpsFix? = null
    private var altitudeFilter = Smooth.Kalman1D(initial = 0.0)
    private var barometerAnchor: Double? = null
    private var lastAltitude: Double? = null
    private var elevationReference: Double? = null
    private var slowSeconds = 0.0
    private var fastSeconds = 0.0
    private val hrSamples = ArrayList<Double>()
    private val powerSamples = ArrayList<Double>()
    private var lastPointTime = -1.0

    private var splitAnchorDistance = 0.0
    private var splitAnchorTime = 0.0
    private var splitAnchorIndex = 0
    private var nextDistanceMark = 0.0
    private var nextTimeMark = 0.0
    private var splitCounter = 0
    private var lapAnchorTime = 0.0
    private var lapAnchorDistance = 0.0
    private var lapAnchorElevation = 0.0
    private var halfwayAnnounced = false
    private var lastPaceAlert: Instant = Instant.MIN
    private var lastRecoveryWrite: Instant = Instant.MIN

    /** Seconds between two instants. Done by hand: Duration.toNanos() overflows when one side is Instant.MIN. */
    private fun seconds(from: Instant, to: Instant): Double =
        (to.epochSecond - from.epochSecond).toDouble() + (to.nano - from.nano) / 1e9

    // Control

    fun start() {
        if (!(state == RecordingState.Idle || state == RecordingState.Finished)) return
        reset()
        if (settings.voice.countdownBeforeStart && settings.display.countdownSeconds > 0) {
            state = RecordingState.Countdown
            countdownValue = settings.display.countdownSeconds
            announcer.announceCountdown(countdownValue)
            publish()
        } else {
            beginRecording()
        }
    }

    /** iOS counts down with a chain of one-second delays; here the 1 Hz [tick] does it. */
    private fun advanceCountdown() {
        countdownValue -= 1
        if (countdownValue > 0) {
            announcer.announceCountdown(countdownValue)
            publish()
        } else {
            announcer.announceCountdown(0)
            beginRecording()
        }
    }

    private fun beginRecording() {
        activityID = newId()
        startDate = clock()
        state = RecordingState.Recording
        lastTick = startDate

        liveSegments.load(environment.starredSegments, environment.personalBests, sport)

        nextDistanceMark = splitDistanceMeters
        nextTimeMark = settings.voice.timeInterval

        announcer.announceStart(sport)
        publish()
    }

    fun pause(automatic: Boolean = false) {
        if (state != RecordingState.Recording) return
        state = if (automatic) RecordingState.AutoPaused else RecordingState.Paused
        announcer.announcePause(automatic)
        publish()
    }

    fun resume(automatic: Boolean = false) {
        if (!state.isPaused) return
        state = RecordingState.Recording
        lastTick = clock()
        announcer.announceResume(automatic)
        publish()
    }

    fun togglePause() {
        if (state.isPaused) resume() else pause()
    }

    fun lap() {
        if (!state.isActive) return
        val lapIndex = laps.size + 1
        val lapTime = movingTime - lapAnchorTime
        val lapDistance = distance - lapAnchorDistance
        if (!(lapDistance > 5 || lapTime > 5)) return
        laps.add(
            Lap(
                index = lapIndex,
                startTime = lapAnchorTime,
                endTime = movingTime,
                distance = lapDistance,
                moving = lapTime,
                elevGain = elevationGain - lapAnchorElevation,
                avgHR = if (hrSamples.isEmpty()) null else hrSamples.sum() / hrSamples.size.toDouble(),
                avgPower = if (powerSamples.isEmpty()) null else powerSamples.sum() / powerSamples.size.toDouble(),
            ),
        )
        lapAnchorTime = movingTime
        lapAnchorDistance = distance
        lapAnchorElevation = elevationGain

        val snapshot = makeSnapshot().copy(
            splitIndex = lapIndex,
            splitTime = lapTime,
            splitDistance = lapDistance,
            splitPaceSecondsPerUnit = if (lapDistance > 1) lapTime / (lapDistance / settings.units.metersPerUnit) else 0.0,
        )
        announcer.announceLap(lapIndex, snapshot)
        publish()
    }

    /** Finish and hand back the analysed activity plus its track, ready to save. */
    fun finish(): Pair<Activity, List<TrackPoint>> {
        state = RecordingState.Finished

        announcer.announceFinish(makeSnapshot())

        var activity = Activity(
            id = activityID,
            name = Activity.defaultName(sport, startDate),
            sport = sport,
            startDate = startDate,
            laps = laps.toList(),
            gearID = environment.defaultGear(sport)?.id,
        )
        val track = points.toList()
        activity = ActivityAnalyzer.analyse(activity, track, settings.profile)
        publish()
        return activity to track
    }

    fun discard() {
        announcer.stop()
        reset()
        state = RecordingState.Idle
        publish()
    }

    private fun reset() {
        points.clear()
        path.clear()
        splits.clear()
        laps.clear()
        hrSamples.clear()
        powerSamples.clear()
        elapsed = 0.0; movingTime = 0.0; distance = 0.0; currentSpeed = 0.0
        elevationGain = 0.0; elevationLoss = 0.0; currentGrade = 0.0
        calories = 0.0; power = 0.0; cadence = 0.0
        currentSplitDistance = 0.0; currentSplitTime = 0.0; lastSplitPace = 0.0
        splitAnchorDistance = 0.0; splitAnchorTime = 0.0; splitAnchorIndex = 0
        splitCounter = 0; lapAnchorTime = 0.0; lapAnchorDistance = 0.0; lapAnchorElevation = 0.0
        halfwayAnnounced = false
        slowSeconds = 0.0; fastSeconds = 0.0
        lastAcceptedFix = null
        pendingDistanceAnchor = null
        barometerAnchor = null
        lastAltitude = null
        elevationReference = null
        lastPointTime = -1.0
        liveSegments.reset()
    }

    // Recovery

    fun restoreFromRecovery(snapshot: RecoverySnapshot) {
        reset()
        activityID = snapshot.activityID
        sport = snapshot.sport
        startDate = snapshot.startDate
        points.addAll(snapshot.points)
        laps.addAll(snapshot.laps)
        path.addAll(points.map { it.coord }.filter { it.isValid })
        points.lastOrNull()?.let {
            distance = it.d
            elapsed = it.t
            altitude = it.alt
        }
        movingTime = ActivityAnalyzer.movingTime(Streams(points))
        val smoothed = Smooth.movingAverage(DoubleArray(points.size) { points[it].alt }, window = 9)
        val change = Smooth.elevationChange(smoothed, threshold = 1.0)
        elevationGain = change.gain
        elevationLoss = change.loss
        splitCounter = (distance / splitDistanceMeters).toInt()
        nextDistanceMark = (splitCounter + 1).toDouble() * splitDistanceMeters
        nextTimeMark = settings.voice.timeInterval *
            ((movingTime / max(1.0, settings.voice.timeInterval)).toInt() + 1).toDouble()
        splitAnchorDistance = distance
        splitAnchorTime = movingTime
        splitAnchorIndex = max(0, points.size - 1)
        lapAnchorDistance = distance
        lapAnchorTime = movingTime
        state = RecordingState.Paused
        lastTick = clock()
        liveSegments.load(environment.starredSegments, environment.personalBests, sport)
        publish()
    }

    // The 1 Hz tick

    fun tick() {
        if (state == RecordingState.Countdown) {
            advanceCountdown()
            return
        }
        if (!state.isActive) return
        val now = clock()
        val dt = lastTick?.let { seconds(it, now) } ?: 1.0
        lastTick = now
        if (!(dt > 0 && dt < 60)) return

        elapsed += dt
        if (state == RecordingState.Recording) {
            movingTime += dt
            currentSplitTime = movingTime - splitAnchorTime
            currentSplitDistance = distance - splitAnchorDistance
        }

        // Cadence from the pedometer while on foot.
        if (sport.isFoot && sensors.cadence > 0) cadence = sensors.cadence
        resolveHeartRate()
        if (heartRate > 30) hrSamples.add(heartRate)

        updateCalories()
        checkAutoPause(dt)
        checkTimeSplit()
        checkPaceAlert(now)
        writeRecoveryIfNeeded(now)
        publish()
    }

    /**
     * A worn chest strap wins outright. Otherwise take what the health platform has, which on a wrist means
     * whatever the watch has synced across. Anything stale is dropped rather than left frozen looking live.
     */
    private fun resolveHeartRate() {
        if (sensors.strapConnected && sensors.strapHeartRate > 30) {
            heartRate = sensors.strapHeartRate
            heartRateSource = HeartRateSource.Strap
            return
        }
        if (sensors.healthFresh) {
            heartRate = sensors.healthHeartRate
            heartRateSource = if (sensors.healthFromWatch) HeartRateSource.Watch else HeartRateSource.Health
            return
        }
        heartRate = 0.0
        heartRateSource = HeartRateSource.None
    }

    private fun updateCalories() {
        val avgHR = if (hrSamples.isEmpty()) null else hrSamples.sum() / hrSamples.size.toDouble()
        calories = EffortScore.calories(
            settings.profile, sport, movingTime, avgHR,
            if (movingTime > 0) distance / movingTime else 0.0,
        )
    }

    private fun checkAutoPause(dt: Double) {
        if (!settings.display.autoPause || sport.isIndoor) return
        val threshold = sport.autoPauseThreshold
        if (state == RecordingState.Recording) {
            if (currentSpeed < threshold) {
                slowSeconds += dt
                if (slowSeconds >= 4) {
                    pause(automatic = true)
                    slowSeconds = 0.0
                }
            } else {
                slowSeconds = 0.0
            }
        } else if (state == RecordingState.AutoPaused) {
            if (currentSpeed > threshold * 1.4) {
                fastSeconds += dt
                if (fastSeconds >= 2) {
                    resume(automatic = true)
                    fastSeconds = 0.0
                }
            } else {
                fastSeconds = 0.0
            }
        }
    }

    // Location handling

    fun onFix(fix: GpsFix) {
        val coord = fix.coord
        currentCoord = coord
        if (!state.isActive) {
            liveSegments.updateNearby(coord)
            publish()
            return
        }
        if (!(fix.horizontalAccuracy <= settings.display.gpsAccuracyFilter)) return

        val now = seconds(startDate, clock())
        if (!(now > lastPointTime)) return
        val dt = if (lastPointTime < 0) 1.0 else now - lastPointTime

        // Altitude: barometer if the phone has one, GPS otherwise.
        val newAltitude: Double
        if (sensors.hasBarometer) {
            if (barometerAnchor == null) barometerAnchor = fix.altitude - sensors.relativeAltitude
            newAltitude = (barometerAnchor ?: fix.altitude) + sensors.relativeAltitude
        } else {
            if (lastAltitude == null) altitudeFilter = Smooth.Kalman1D(initial = fix.altitude)
            newAltitude = altitudeFilter.update(fix.altitude, measurementNoise = max(2.0, fix.verticalAccuracy))
        }

        // Distance: only count movement that clears the noise floor for this fix.
        var increment = 0.0
        var instantaneousSpeed = if (fix.speed >= 0) fix.speed else 0.0
        val anchor = pendingDistanceAnchor ?: lastAcceptedFix
        if (anchor != null) {
            val raw = fix.distanceTo(anchor)
            val floor = max(1.5, fix.horizontalAccuracy * 0.35)
            val dtAnchor = max(0.5, seconds(anchor.timestamp, fix.timestamp))
            val impliedSpeed = raw / dtAnchor
            if (impliedSpeed > sport.maxPlausibleSpeed * 1.6) {
                // A GPS jump. Drop it and re-anchor.
                pendingDistanceAnchor = fix
                lastPointTime = now
                return
            }
            if (raw >= floor) {
                increment = raw
                pendingDistanceAnchor = null
                lastAcceptedFix = fix
                if (fix.speed < 0) instantaneousSpeed = raw / dtAnchor
            } else {
                // Hold the anchor so slow, real movement still accumulates.
                pendingDistanceAnchor = anchor
            }
        } else {
            lastAcceptedFix = fix
        }

        if (state == RecordingState.AutoPaused && increment > 0 && instantaneousSpeed > sport.autoPauseThreshold * 1.4) {
            resume(automatic = true)
        }

        val moving = state == RecordingState.Recording && instantaneousSpeed >= sport.autoPauseThreshold * 0.6

        if (moving) distance += increment

        // Elevation, with a dead band so a flat run does not "climb".
        if (elevationReference == null) elevationReference = newAltitude
        elevationReference?.let { reference ->
            val delta = newAltitude - reference
            if (delta > 1.0) {
                elevationGain += delta
                elevationReference = newAltitude
            } else if (delta < -1.0) {
                elevationLoss += -delta
                elevationReference = newAltitude
            }
        }

        // Grade over the last stretch, for power and grade adjusted pace.
        val previousAltitude = lastAltitude
        if (previousAltitude != null && increment > 1) {
            val raw = (newAltitude - previousAltitude) / increment
            currentGrade = currentGrade * 0.7 + max(-0.45, min(0.45, raw)) * 0.3
        }
        lastAltitude = newAltitude

        // Power estimate.
        val acceleration = if (dt > 0) (instantaneousSpeed - currentSpeed) / dt else 0.0
        val watts: Double
        if (sport.isRide) {
            val gearWeight = environment.defaultGear(sport)?.weightKg
            val bikeMass = if (gearWeight != null && gearWeight > 0) gearWeight else settings.profile.bikeWeightKg
            watts = PowerModel.cyclingWatts(
                speed = instantaneousSpeed,
                gradient = currentGrade,
                acceleration = acceleration,
                totalMassKg = settings.profile.weightKg + bikeMass,
                crr = settings.profile.rollingResistance,
                cda = settings.profile.dragArea,
            )
        } else {
            watts = PowerModel.runningWatts(instantaneousSpeed, currentGrade, settings.profile.weightKg)
        }
        if (watts > 0) powerSamples.add(watts)

        currentSpeed = instantaneousSpeed
        altitude = newAltitude
        power = watts

        points.add(
            TrackPoint(
                t = now,
                lat = coord.lat,
                lon = coord.lon,
                alt = newAltitude,
                d = distance,
                v = instantaneousSpeed,
                hr = if (heartRate > 30) heartRate else null,
                cad = if (cadence > 5) cadence else null,
                pw = if (watts > 0) watts else null,
                moving = moving,
                acc = fix.horizontalAccuracy,
            ),
        )
        path.add(coord)
        lastPointTime = now

        checkDistanceSplit()
        checkHalfway()
        updateLiveSegments(coord, dt)
        publish()
    }

    // Splits

    private val splitDistanceMeters: Double
        get() = max(50.0, settings.voice.distanceInterval * settings.units.metersPerUnit)

    private fun checkDistanceSplit() {
        val trigger = settings.voice.trigger
        if (!(trigger == AnnouncementTrigger.Distance || trigger == AnnouncementTrigger.Both)) return
        while (distance >= nextDistanceMark) {
            splitCounter += 1
            val splitTime = movingTime - splitAnchorTime
            val splitDistance = distance - splitAnchorDistance
            recordSplit(splitCounter, splitDistance, splitTime)
            val pace = if (splitDistance > 1) splitTime / (splitDistance / settings.units.metersPerUnit) else 0.0
            val snapshot = makeSnapshot().copy(
                splitIndex = splitCounter, splitTime = splitTime, splitDistance = splitDistance,
                splitPaceSecondsPerUnit = pace,
            )
            lastSplitPace = pace
            announcer.announceSplit(snapshot, isTimeInterval = false)

            splitAnchorDistance = distance
            splitAnchorTime = movingTime
            splitAnchorIndex = points.size - 1
            nextDistanceMark += splitDistanceMeters
        }
    }

    private fun checkTimeSplit() {
        val trigger = settings.voice.trigger
        if (!(trigger == AnnouncementTrigger.Time || trigger == AnnouncementTrigger.Both)) return
        if (!(settings.voice.timeInterval > 0)) return
        while (movingTime >= nextTimeMark) {
            splitCounter += 1
            val splitDistance = distance - splitAnchorDistance
            val pace = if (splitDistance > 1) settings.voice.timeInterval / (splitDistance / settings.units.metersPerUnit) else 0.0
            val snapshot = makeSnapshot().copy(
                splitIndex = splitCounter, splitTime = settings.voice.timeInterval, splitDistance = splitDistance,
                splitPaceSecondsPerUnit = pace,
            )
            announcer.announceSplit(snapshot, isTimeInterval = true)
            splitAnchorDistance = distance
            splitAnchorTime = movingTime
            nextTimeMark += settings.voice.timeInterval
        }
    }

    private fun recordSplit(index: Int, splitDistance: Double, splitTime: Double) {
        val slice = if (splitAnchorIndex < points.size) points.subList(splitAnchorIndex, points.size).toList() else emptyList()
        val change = Smooth.elevationChange(DoubleArray(slice.size) { slice[it].alt }, threshold = 1.0)
        val hrValues = slice.mapNotNull { it.hr }
        val pwValues = slice.mapNotNull { it.pw }
        val cadValues = slice.mapNotNull { it.cad }
        var gap: Double? = null
        if (slice.size > 2) {
            val streams = Streams(slice)
            gap = GradeAdjustedPace.adjustedPace(streams, 0, streams.count - 1)
                ?.let { it * settings.units.metersPerUnit / 1000 }
        }
        splits.add(
            Split(
                index = index,
                distance = splitDistance,
                elapsed = splitTime,
                moving = splitTime,
                elevGain = change.gain,
                elevLoss = change.loss,
                avgHR = if (hrValues.isEmpty()) null else hrValues.sum() / hrValues.size.toDouble(),
                avgPower = if (pwValues.isEmpty()) null else pwValues.sum() / pwValues.size.toDouble(),
                avgCadence = if (cadValues.isEmpty()) null else cadValues.sum() / cadValues.size.toDouble(),
                gapSeconds = gap,
            ),
        )
    }

    private fun checkHalfway() {
        if (!settings.voice.announceHalfway || halfwayAnnounced) return
        // Halfway only means something against a goal distance.
        val target = environment.distanceGoalTarget ?: return
        if (!(target > 0 && distance >= target / 2)) return
        halfwayAnnounced = true
        announcer.announceHalfway(makeSnapshot())
    }

    private fun checkPaceAlert(now: Instant) {
        if (!(settings.voice.targetPaceAlertsEnabled && settings.voice.targetPaceSeconds > 0)) return
        if (!(seconds(lastPaceAlert, now) > 45)) return
        if (!(currentSpeed > 0.4)) return
        val current = settings.units.metersPerUnit / currentSpeed
        val target = settings.voice.targetPaceSeconds
        if (abs(current - target) > settings.voice.targetPaceToleranceSeconds) {
            lastPaceAlert = now
            announcer.announcePaceAlert(current, target)
        }
    }

    // Live segments

    private fun updateLiveSegments(coord: Coord, dt: Double) {
        if (!settings.display.showLiveSegments) return
        val events = liveSegments.update(coord, movingTime, distance, dt)
        if (!settings.display.liveSegmentVoice) return
        for (event in events) {
            when (event) {
                is LiveSegmentEngine.Event.Started -> announcer.announceSegmentStart(event.name, event.prTime)
                is LiveSegmentEngine.Event.Progress -> announcer.announceSegmentProgress(event.delta, event.remaining)
                is LiveSegmentEngine.Event.Finished -> announcer.announceSegmentFinish(event.name, event.time, event.isPR, event.delta)
                is LiveSegmentEngine.Event.Abandoned -> Unit
            }
        }
    }

    // Snapshot

    fun makeSnapshot(): CoachSnapshot {
        val unit = settings.units.metersPerUnit
        var averagePace = 0.0
        if (distance > 1 && movingTime > 0) averagePace = movingTime / (distance / unit)
        var currentPace = 0.0
        var gradeAdjusted = 0.0
        if (currentSpeed > 0.2) {
            currentPace = unit / currentSpeed
            gradeAdjusted = currentPace / GradeAdjustedPace.factor(currentGrade)
        }
        return CoachSnapshot(
            sport = sport,
            totalDistance = distance,
            totalTime = elapsed,
            movingTime = movingTime,
            currentSpeed = currentSpeed,
            averageSpeed = if (movingTime > 0) distance / movingTime else 0.0,
            averagePaceSecondsPerUnit = averagePace,
            currentPaceSecondsPerUnit = currentPace,
            gradeAdjustedPaceSecondsPerUnit = gradeAdjusted,
            heartRate = heartRate,
            averageHeartRate = if (hrSamples.isEmpty()) 0.0 else hrSamples.sum() / hrSamples.size.toDouble(),
            cadence = cadence,
            power = power,
            averagePower = if (powerSamples.isEmpty()) 0.0 else powerSamples.sum() / powerSamples.size.toDouble(),
            elevationGain = elevationGain,
            calories = calories,
        )
    }

    /** Read the current numbers out on demand, from the record screen's speaker button. */
    fun speakCurrentStats() {
        announcer.announceSplit(makeSnapshot().copy(splitIndex = splitCounter + 1), isTimeInterval = false)
    }

    // Crash recovery

    private fun writeRecoveryIfNeeded(now: Instant) {
        val sink = recoverySink ?: return
        if (!(seconds(lastRecoveryWrite, now) > 10)) return
        lastRecoveryWrite = now
        sink(
            RecoverySnapshot(
                activityID = activityID,
                sport = sport,
                startDate = startDate,
                points = points.toList(),       // a copy: the sink may write on another thread
                elapsed = elapsed,
                pausedDuration = elapsed - movingTime,
                laps = laps.toList(),
                savedAt = now,
            ),
        )
    }

    // Derived display values

    val averagePaceSecondsPerUnit: Double
        get() = if (distance > 1 && movingTime > 0) movingTime / (distance / settings.units.metersPerUnit) else 0.0

    val currentPaceSecondsPerUnit: Double
        get() = if (currentSpeed > 0.2) settings.units.metersPerUnit / currentSpeed else 0.0

    val averageSpeedValue: Double get() = if (movingTime > 0) distance / movingTime else 0.0

    /** A copy of the track so far, for saving or analysis. */
    fun trackSnapshot(): List<TrackPoint> = points.toList()

    private fun publish() {
        _stats.value = RecordingStats(
            state = state, sport = sport, elapsed = elapsed, movingTime = movingTime, distance = distance,
            currentSpeed = currentSpeed, elevationGain = elevationGain, elevationLoss = elevationLoss,
            currentGrade = currentGrade, altitude = altitude, heartRate = heartRate, cadence = cadence,
            power = power, calories = calories, countdownValue = countdownValue, heartRateSource = heartRateSource,
            splits = splits.toList(), laps = laps.toList(), currentSplitDistance = currentSplitDistance,
            currentSplitTime = currentSplitTime, lastSplitPace = lastSplitPace, currentCoord = currentCoord,
            path = pathView, pathSize = path.size, liveSegment = liveSegments.active,
            nearbySegments = liveSegments.nearby,
        )
    }
}
