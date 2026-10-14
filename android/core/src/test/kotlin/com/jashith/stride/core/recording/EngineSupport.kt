package com.jashith.stride.core.recording

import com.jashith.stride.core.SyntheticTracks
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.model.DisplaySettings
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.VoiceSettings
import com.jashith.stride.core.speech.SpeechBuilder
import java.time.Instant

class FakeClock(var now: Instant = Instant.parse("2026-03-14T07:30:00Z")) {
    fun advance(seconds: Double) {
        now = now.plusNanos((seconds * 1e9).toLong())
    }
}

class RecordingSpeaker : Speaker {
    data class Said(val text: String, val immediate: Boolean, val phrases: List<String>, val breakMs: Int)

    val said = ArrayList<Said>()
    var stops = 0

    override fun speak(builder: SpeechBuilder) {
        said.add(Said(builder.plainText, false, builder.phrases, builder.breakMilliseconds))
    }

    override fun speakImmediately(builder: SpeechBuilder) {
        said.add(Said(builder.plainText, true, builder.phrases, builder.breakMilliseconds))
    }

    override fun stop() {
        stops += 1
    }

    val texts: List<String> get() = said.map { it.text }
}

/** Settings with no countdown, so a test can start recording immediately. */
fun quickSettings(
    voice: VoiceSettings = VoiceSettings(),
    display: DisplaySettings = DisplaySettings(),
): AppSettings = AppSettings(
    display = display.copy(countdownSeconds = 0),
    voice = voice.copy(countdownBeforeStart = false),
)

/** Drives an engine the way the foreground service does: a fix, then the one-second tick. */
class Harness(
    settings: AppSettings = quickSettings(),
    val sport: SportType = SportType.Run,
    environment: RecordingEnvironment = RecordingEnvironment(),
) {
    val clock = FakeClock()
    val speaker = RecordingSpeaker()
    val recoveries = ArrayList<com.jashith.stride.core.store.RecoverySnapshot>()
    val engine: RecordingEngine

    /** Metres travelled along the due-north test line. */
    var d = 0.0

    init {
        val announcer = CoachAnnouncer(settings.voice, settings.units, speaker)
        engine = RecordingEngine(
            clock = { clock.now }, announcer = announcer,
            newId = { java.util.UUID.fromString("00000000-0000-4000-8000-000000000001") },
        )
        engine.settings = settings
        engine.sport = sport
        engine.environment = environment
        engine.recoverySink = { recoveries.add(it) }
    }

    fun fix(
        speed: Double = 3.0,
        moving: Boolean = true,
        accuracy: Double = 5.0,
        altitude: Double = 20.0,
        verticalAccuracy: Double = 3.0,
        reportedSpeed: Double? = null,
        jumpTo: Double? = null,
    ): GpsFix {
        if (moving && jumpTo == null) d += speed
        val along = jumpTo ?: d
        return GpsFix(
            lat = SyntheticTracks.latAt(along), lon = SyntheticTracks.START_LON, altitude = altitude,
            horizontalAccuracy = accuracy, verticalAccuracy = verticalAccuracy,
            speed = reportedSpeed ?: (if (moving) speed else 0.0), timestamp = clock.now,
        )
    }

    /** One second of life: time passes, a fix arrives, the tick fires. */
    fun second(
        speed: Double = 3.0,
        moving: Boolean = true,
        accuracy: Double = 5.0,
        altitude: Double = 20.0,
        reportedSpeed: Double? = null,
        tick: Boolean = true,
    ) {
        clock.advance(1.0)
        engine.onFix(fix(speed, moving, accuracy, altitude, reportedSpeed = reportedSpeed))
        if (tick) engine.tick()
    }

    fun seconds(n: Int, speed: Double = 3.0, moving: Boolean = true, altitude: (Int) -> Double = { 20.0 }) {
        repeat(n) { second(speed, moving, altitude = altitude(it)) }
    }

    val stats get() = engine.stats.value
}
