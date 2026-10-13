package com.jashith.stride.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jashith.stride.core.model.VoiceSettings
import com.jashith.stride.core.recording.Speaker
import com.jashith.stride.core.speech.SpeechBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Speaks the coach's phrases over the user's music, ducking it briefly rather than stopping it.
 *
 * Android's [TextToSpeech] has no SSML pauses between phrases, so each phrase is queued as its own
 * utterance with a silent utterance between them, which gives the same rhythm. Must be used from the
 * main thread.
 */
class VoiceCoach(private val context: Context) : Speaker {

    data class VoiceOption(
        val name: String,
        val locale: String,
        /** 1 standard, 2 enhanced, 3 premium. */
        val quality: Int,
        val qualityName: String,
        val needsNetwork: Boolean,
    )

    var settings: VoiceSettings = VoiceSettings()
        set(value) {
            field = value
            applyVoiceSettings()
        }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> get() = _speaking
    private val _lastSpoken = MutableStateFlow("")
    val lastSpoken: StateFlow<String> get() = _lastSpoken
    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> get() = _voices
    private val _available = MutableStateFlow(true)
    /** False when the phone has no text-to-speech engine installed. */
    val available: StateFlow<Boolean> get() = _available

    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false
    private val backlog = ArrayList<() -> Unit>()
    private var outstanding = 0
    private var utteranceCounter = 0L
    private var focus: AudioFocusRequest? = null

    /** Starts the engine early so the first announcement is not late. Safe to call repeatedly. */
    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(progress)
                    tts?.setAudioAttributes(attributes)
                    applyVoiceSettings()
                    refreshVoices()
                    backlog.toList().also { backlog.clear() }.forEach { it() }
                } else {
                    _available.value = false
                    backlog.clear()
                }
            }
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Voices

    private fun refreshVoices() {
        val engine = tts ?: return
        val language = Locale.getDefault().language
        val all = try {
            engine.voices.orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
        _voices.value = all
            .filter { it.locale.language == language || it.locale.language == "en" }
            .map { it.toOption() }
            .sortedWith(compareByDescending<VoiceOption> { it.quality }.thenBy { it.name })
    }

    private fun Voice.toOption(): VoiceOption {
        val q = when {
            quality >= Voice.QUALITY_VERY_HIGH -> 3
            quality >= Voice.QUALITY_HIGH -> 2
            else -> 1
        }
        return VoiceOption(
            name = name,
            locale = locale.getDisplayName(Locale.getDefault()),
            quality = q,
            qualityName = when (q) { 3 -> "Premium"; 2 -> "Enhanced"; else -> "Standard" },
            needsNetwork = isNetworkConnectionRequired,
        )
    }

    private fun applyVoiceSettings() {
        val engine = tts ?: return
        if (!ready) return
        val s = settings
        engine.setPitch(s.pitch.coerceIn(0.5, 2.0).toFloat())
        // iOS speaks on a 0 to 1 scale where 0.5 is normal speed; Android's scale is 1.0 for normal.
        engine.setSpeechRate((s.rate.coerceIn(0.2, 0.8) / 0.5).toFloat())
        val wanted = s.voiceIdentifier
        val chosen = if (wanted.isNotEmpty()) engine.voices?.firstOrNull { it.name == wanted } else null
        if (chosen != null) {
            engine.voice = chosen
        } else {
            val best = _voices.value.firstOrNull()?.name
            engine.voices?.firstOrNull { it.name == best }?.let { engine.voice = it }
        }
    }

    // Speaking

    override fun speak(builder: SpeechBuilder) = speak(builder, interrupt = false)

    override fun speakImmediately(builder: SpeechBuilder) = speak(builder, interrupt = true)

    fun speak(text: String) {
        val builder = SpeechBuilder()
        builder.add(if (text.endsWith(".")) text.dropLast(1) else text)
        speak(builder, interrupt = false)
    }

    private fun speak(builder: SpeechBuilder, interrupt: Boolean) {
        if (builder.isEmpty) return
        handler.post {
            warmUp()
            val job = { say(builder, interrupt) }
            if (ready) job() else backlog.add(job)
        }
    }

    private fun say(builder: SpeechBuilder, interrupt: Boolean) {
        val engine = tts ?: return
        if (interrupt) {
            engine.stop()
            outstanding = 0
        }
        requestFocus()
        // Some builders ask for a different pace (for example numbers read slowly).
        engine.setSpeechRate((settings.rate.coerceIn(0.2, 0.8) / 0.5 * builder.ratePercentage / 100.0).toFloat())

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume.coerceIn(0.1, 1.0).toFloat())
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val phrases = builder.phrases
        phrases.forEachIndexed { index, phrase ->
            if (index > 0) {
                outstanding += 1
                engine.playSilentUtterance(builder.breakMilliseconds.toLong(), TextToSpeech.QUEUE_ADD, nextId())
            }
            outstanding += 1
            engine.speak(phrase, TextToSpeech.QUEUE_ADD, params, nextId())
        }
        // iOS leaves a short tail after each announcement; this is the equivalent of postUtteranceDelay.
        outstanding += 1
        engine.playSilentUtterance(150, TextToSpeech.QUEUE_ADD, nextId())

        _lastSpoken.value = builder.plainText
        _speaking.value = true
    }

    private fun nextId(): String = "stride-${utteranceCounter++}"

    override fun stop() {
        handler.post {
            backlog.clear()
            tts?.stop()
            outstanding = 0
            _speaking.value = false
            abandonFocusSoon()
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finished()

        @Deprecated("Replaced by onError(String, Int)")
        override fun onError(utteranceId: String?) = finished()
        override fun onError(utteranceId: String?, errorCode: Int) = finished()
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
    }

    private fun finished() {
        handler.post {
            if (outstanding > 0) outstanding -= 1
            if (outstanding == 0) {
                _speaking.value = false
                abandonFocusSoon()
            }
        }
    }

    // Audio focus

    private fun requestFocus() {
        handler.removeCallbacks(abandonRunnable)
        if (!settings.duckMusic || focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    private val abandonRunnable = Runnable {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Let the tail of the utterance play out before handing audio back. */
    private fun abandonFocusSoon() {
        handler.removeCallbacks(abandonRunnable)
        handler.postDelayed(abandonRunnable, 350)
    }

    /** Reads a sample split in the chosen voice. Lives in the controller, which owns the script. */
    fun speakForced(builder: SpeechBuilder) {
        if (builder.isEmpty) return
        speak(builder, interrupt = false)
    }
}
