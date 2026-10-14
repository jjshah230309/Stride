package com.jashith.stride.core.recording

import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.model.VoiceSettings
import com.jashith.stride.core.speech.CoachScript
import com.jashith.stride.core.speech.CoachSnapshot
import com.jashith.stride.core.speech.SpeechBuilder
import com.jashith.stride.core.speech.SpokenNumber

/** Whatever actually makes sound: Android's TextToSpeech in the app, a recorder in tests. */
interface Speaker {
    /** Queue after anything already being said. */
    fun speak(builder: SpeechBuilder)

    /** Cut in ahead of anything queued; used for alerts that go stale fast. */
    fun speakImmediately(builder: SpeechBuilder)

    fun stop()
}

/**
 * Decides *whether* to say something (the user's switches) and hands the words from [CoachScript] to a
 * [Speaker]. This is the part of iOS `VoiceCoach` that has no audio in it, ported one for one.
 */
class CoachAnnouncer(
    var settings: VoiceSettings,
    var units: UnitSystem,
    private val speaker: Speaker,
) {
    private val script: CoachScript get() = CoachScript(settings, units)

    private fun speak(builder: SpeechBuilder, force: Boolean = false) {
        if (builder.isEmpty) return
        if (!(settings.enabled || force)) return
        speaker.speak(builder)
    }

    private fun speak(text: String, force: Boolean = false) {
        val builder = SpeechBuilder()
        builder.add(if (text.endsWith(".")) text.dropLast(1) else text)
        speak(builder, force)
    }

    private fun speakImmediately(builder: SpeechBuilder) {
        if (builder.isEmpty) return
        if (!settings.enabled) return
        speaker.speakImmediately(builder)
    }

    fun stop() = speaker.stop()

    fun announceCountdown(n: Int) = speak(if (n > 0) SpokenNumber.casual(n) else "Go")

    fun announceStart(sport: SportType) {
        if (!settings.announceStart) return
        speak(script.start(sport))
    }

    fun announcePause(automatic: Boolean) {
        if (!(if (automatic) settings.announceAutoPause else settings.announcePauseResume)) return
        speak(if (automatic) "Auto paused" else "Paused")
    }

    fun announceResume(automatic: Boolean) {
        if (!(if (automatic) settings.announceAutoPause else settings.announcePauseResume)) return
        speak(if (automatic) "Rolling again" else "Resumed")
    }

    fun announceFinish(s: CoachSnapshot) {
        if (!settings.announceFinish) return
        speak(script.finish(s))
    }

    fun announceLap(number: Int, s: CoachSnapshot) {
        if (!settings.announceLaps) return
        speak(script.lap(number, s))
    }

    fun announceHalfway(s: CoachSnapshot) {
        if (!settings.announceHalfway) return
        speak(script.halfway(s))
    }

    /** The split announcement, the one that matters most. */
    fun announceSplit(s: CoachSnapshot, isTimeInterval: Boolean = false) = speak(script.split(s, isTimeInterval))

    fun buildSplit(s: CoachSnapshot, isTimeInterval: Boolean): SpeechBuilder = script.split(s, isTimeInterval)

    fun announcePaceAlert(current: Double, target: Double) {
        if (!settings.targetPaceAlertsEnabled) return
        val builder = script.paceAlert(current, target) ?: return
        speakImmediately(builder)
    }

    fun announceZoneAlert(zone: Int, target: Int) {
        if (!settings.zoneAlertsEnabled) return
        val builder = script.zoneAlert(zone, target) ?: return
        speakImmediately(builder)
    }

    fun announceGoalMilestone(text: String) {
        if (!settings.announceGoalMilestones) return
        speak(text)
    }

    fun announceSegmentStart(name: String, prTime: Double?) {
        if (!settings.announceSegments) return
        speakImmediately(script.segmentStart(name, prTime))
    }

    fun announceSegmentProgress(deltaSeconds: Double, remaining: Double) {
        if (!settings.announceSegments) return
        speak(script.segmentProgress(deltaSeconds, remaining))
    }

    fun announceSegmentFinish(name: String, time: Double, isPR: Boolean, delta: Double?) {
        if (!settings.announceSegments) return
        speakImmediately(script.segmentFinish(name, time, isPR, delta))
    }

    /** What the settings screen plays: the sample sentence, spoken even if the coach is switched off. */
    fun previewVoice() {
        val sample = CoachScript.sampleSnapshot.copy(
            totalDistance = units.metersPerUnit * 3,
            splitDistance = units.metersPerUnit,
        )
        val builder = script.split(sample, isTimeInterval = false)
        if (builder.isEmpty) builder.add("Nothing is selected, so nothing will be spoken")
        speak(builder, force = true)
    }
}
