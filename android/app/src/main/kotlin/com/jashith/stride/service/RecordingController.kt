package com.jashith.stride.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.recording.CoachAnnouncer
import com.jashith.stride.core.recording.GpsFix
import com.jashith.stride.core.recording.RecordingEngine
import com.jashith.stride.core.recording.RecordingEnvironment
import com.jashith.stride.core.recording.RecordingState
import com.jashith.stride.core.recording.RecordingStats
import com.jashith.stride.core.store.RecoverySnapshot
import com.jashith.stride.core.store.StrideStore
import com.jashith.stride.health.HealthConnectManager
import com.jashith.stride.sensors.HeartRateMonitor
import com.jashith.stride.sensors.LocationEngine
import com.jashith.stride.sensors.MotionEngine
import com.jashith.stride.voice.VoiceCoach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** A finished recording waiting on the Save screen. */
data class FinishedRecording(val activity: Activity, val track: List<TrackPoint>)

/**
 * The process-wide recorder. It owns the [RecordingEngine] and wires the phone's sensors, the voice and
 * Health Connect to it. The foreground [RecordingService] only keeps the process alive and shows the
 * notification; everything that matters happens here, on the main thread.
 */
class RecordingController(
    private val app: Context,
    private val store: StrideStore,
    private val scope: CoroutineScope,
    val location: LocationEngine,
    val motion: MotionEngine,
    val strap: HeartRateMonitor,
    val health: HealthConnectManager,
    val voice: VoiceCoach,
) {
    val engine = RecordingEngine(
        clock = { Instant.now() },
        announcer = CoachAnnouncer(store.current.settings.voice, store.current.settings.units, voice),
    )

    val stats: StateFlow<RecordingStats> get() = engine.stats

    private val _recovery = MutableStateFlow<RecoverySnapshot?>(null)
    /** An unfinished recording found on disk at launch. */
    val recovery: StateFlow<RecoverySnapshot?> get() = _recovery

    private val _finished = MutableStateFlow<FinishedRecording?>(null)
    val finished: StateFlow<FinishedRecording?> get() = _finished

    private var ticker: Job? = null
    private var healthPoll: Job? = null
    private var latestHealth: HealthConnectManager.LiveHeartRate? = null
    private var warm = false

    val isActive: Boolean get() = engine.state.isActive || engine.state == RecordingState.Countdown

    init {
        engine.settings = store.current.settings
        engine.recoverySink = { snapshot ->
            scope.launch(Dispatchers.IO) { store.files.writeRecovery(snapshot) }
        }
        location.onFix = { fix: GpsFix -> if (engine.state.isActive) engine.onFix(fix) }
        scope.launch {
            store.state.map { it.settings }.distinctUntilChanged().collect { engine.settings = it }
        }
    }

    /** Looks for a recording that was interrupted by a crash, a dead battery or the system. */
    fun checkForRecovery() {
        if (isActive) return
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { store.files.readRecovery() }
            if (snapshot != null && snapshot.points.isNotEmpty()) _recovery.value = snapshot
            else if (snapshot != null) withContext(Dispatchers.IO) { store.files.clearRecovery() }
        }
    }

    /** Turns an interrupted recording into a finished one, ready for the Save screen. */
    fun saveRecovered(snapshot: RecoverySnapshot) {
        val base = Activity(
            id = snapshot.activityID,
            name = Activity.defaultName(snapshot.sport, snapshot.startDate),
            sport = snapshot.sport,
            startDate = snapshot.startDate,
            laps = snapshot.laps,
        )
        val analysed = com.jashith.stride.core.analysis.ActivityAnalyzer.analyse(base, snapshot.points, store.current.settings.profile)
        _finished.value = FinishedRecording(analysed, snapshot.points)
        dismissRecovery()
    }

    fun dismissRecovery() {
        _recovery.value = null
        scope.launch(Dispatchers.IO) { store.files.clearRecovery() }
    }

    // Warm-up

    /** Start the GPS on the Record screen so the first fix is not thirty seconds late. */
    fun warmUp(sport: SportType) {
        if (isActive) return
        engine.sport = sport
        voice.warmUp()
        location.start()
        warm = true
    }

    fun stopWarmUp() {
        if (isActive || !warm) return
        location.stop()
        warm = false
    }

    // Control

    fun start(sport: SportType) {
        if (!(engine.state == RecordingState.Idle || engine.state == RecordingState.Finished)) return
        _finished.value = null
        engine.sport = sport
        engine.settings = store.current.settings
        engine.environment = environment(sport)
        engine.sensors.healthFresh = false
        engine.sensors.strapConnected = false
        beginSensors(sport)
        engine.start()
    }

    fun restore(snapshot: RecoverySnapshot) {
        engine.settings = store.current.settings
        engine.environment = environment(snapshot.sport)
        engine.restoreFromRecovery(snapshot)
        _recovery.value = null
        beginSensors(snapshot.sport)
    }

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun togglePause() = engine.togglePause()
    fun lap() = engine.lap()
    fun speakCurrentStats() = engine.speakCurrentStats()

    fun finish() {
        val (activity, track) = engine.finish()
        endSensors()
        _finished.value = FinishedRecording(activity, track)
    }

    /** Throw the recording away without saving it. */
    fun discard() {
        engine.discard()
        endSensors()
        _finished.value = null
        scope.launch(Dispatchers.IO) { store.files.clearRecovery() }
    }

    /** Called by the Save screen once the activity is stored or deliberately dropped. */
    fun completeFinished() {
        _finished.value = null
        engine.discard()
        scope.launch(Dispatchers.IO) { store.files.clearRecovery() }
    }

    private fun environment(sport: SportType): RecordingEnvironment {
        val starred = store.starredSegments
        val bests = HashMap<java.util.UUID, Double>()
        for (segment in starred) store.personalBest(segment)?.let { bests[segment.id] = it.time }
        val goal = store.current.goals.firstOrNull { it.isActive && it.metric == com.jashith.stride.core.model.GoalMetric.Distance }
        return RecordingEnvironment(
            starredSegments = starred,
            personalBests = bests,
            defaultGear = { store.defaultGear(it) },
            distanceGoalTarget = goal?.target,
        )
    }

    // Sensors and the 1 Hz tick

    private fun beginSensors(sport: SportType) {
        warm = false
        voice.warmUp()
        ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
        location.start()
        motion.reset()
        motion.start(engine.sensors, trackSteps = sport.isFoot)
        strap.reconnectLast()
        startHealthPolling()
        startTicker()
    }

    private fun endSensors() {
        ticker?.cancel(); ticker = null
        healthPoll?.cancel(); healthPoll = null
        location.stop()
        motion.stop()
        latestHealth = null
        engine.sensors.healthFresh = false
        engine.sensors.strapConnected = false
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            // Aligned to the monotonic clock so a slow frame cannot make the second drift.
            var next = SystemClock.elapsedRealtime() + 1000
            while (isActive) {
                delay(maxOf(1L, next - SystemClock.elapsedRealtime()))
                next += 1000
                tick()
            }
        }
    }

    private fun tick() {
        motion.tick()
        val strapState = strap.state.value
        engine.sensors.strapConnected = strapState.connected
        engine.sensors.strapHeartRate = strapState.heartRate

        val reading = latestHealth
        val fresh = reading != null && reading.bpm > 30 &&
            Instant.now().epochSecond - reading.time.epochSecond < FRESHNESS_SECONDS
        engine.sensors.healthFresh = fresh
        if (fresh && reading != null) {
            engine.sensors.healthHeartRate = reading.bpm
            engine.sensors.healthFromWatch = reading.fromWatch
        }
        engine.tick()
    }

    /** Health Connect has no live feed, so ask for the newest reading every few seconds. */
    private fun startHealthPolling() {
        healthPoll?.cancel()
        latestHealth = null
        val wanted = store.current.settings.display.readFromAppleHealth
        if (!wanted || !health.isAvailable) return
        healthPoll = scope.launch {
            health.refreshGranted()
            if (!health.canReadHeartRate) return@launch
            while (isActive) {
                health.latestHeartRate()?.let { fresh ->
                    val existing = latestHealth
                    if (existing == null || !existing.time.isAfter(fresh.time)) latestHealth = fresh
                }
                delay(POLL_MILLIS)
            }
        }
    }

    private companion object {
        const val FRESHNESS_SECONDS = 45L
        const val POLL_MILLIS = 10_000L
    }
}
