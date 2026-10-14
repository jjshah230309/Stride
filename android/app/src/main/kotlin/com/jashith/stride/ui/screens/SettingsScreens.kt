package com.jashith.stride.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.model.AnnouncementTrigger
import com.jashith.stride.core.model.DisplaySettings
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.HeartRateZones
import com.jashith.stride.core.model.SpokenMetric
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.VoiceSettings
import com.jashith.stride.core.speech.CoachScript
import com.jashith.stride.core.speech.SpeechStyle
import com.jashith.stride.health.rememberHealthPermissionRequest
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.ButtonRow
import com.jashith.stride.ui.components.FormSection
import com.jashith.stride.ui.components.NavRow
import com.jashith.stride.ui.components.PickerRow
import com.jashith.stride.ui.components.SegmentedPicker
import com.jashith.stride.ui.components.SubPage
import com.jashith.stride.ui.components.ToggleRow
import com.jashith.stride.ui.components.ValueRow
import com.jashith.stride.ui.Dest
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType

/** Everything about the spoken splits. */
@Composable
fun VoiceSettingsScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    val voice = state.settings.voice
    var editingRun by remember { mutableStateOf(true) }
    val voices by graph.voice.voices.collectAsState()
    val ttsAvailable by graph.voice.available.collectAsState()

    LaunchedEffectOnce { graph.voice.warmUp() }

    fun update(change: (VoiceSettings) -> VoiceSettings) = store.updateSettings { it.copy(voice = change(it.voice)) }

    val metrics = if (editingRun) voice.runMetrics else voice.rideMetrics
    fun setMetrics(list: List<SpokenMetric>) = update { if (editingRun) it.copy(runMetrics = list) else it.copy(rideMetrics = list) }
    val available = SpokenMetric.entries.filter { it !in metrics && (if (editingRun) it.suitsRunning else it.suitsCycling) }
    var addOpen by remember { mutableStateOf(false) }

    val preview = remember(voice, units, editingRun) {
        val snapshot = CoachScript.sampleSnapshot.copy(
            sport = if (editingRun) SportType.Run else SportType.Ride,
            totalDistance = units.metersPerUnit * 3, splitDistance = units.metersPerUnit,
            cadence = if (editingRun) 176.0 else 88.0,
        )
        val builder = CoachScript(voice, units).split(snapshot, isTimeInterval = false)
        if (builder.isEmpty) "Nothing selected — nothing will be spoken." else "“" + builder.plainText + "”"
    }

    SubPage("Voice Coach", onBack = { nav.pop() }) {
        FormSection(footer = "Splits are spoken over your music. Your music ducks for a moment rather than stopping, and it keeps working with the screen off.") {
            ToggleRow("Speak while recording", voice.enabled, { v -> update { it.copy(enabled = v) } }, divider = voice.enabled)
            if (voice.enabled) {
                ButtonRow("Hear a preview", { graph.recorder.engine.announcer.previewVoice() }, enabled = ttsAvailable, divider = false)
            }
        }
        if (!ttsAvailable) {
            Text("No text-to-speech engine was found on this phone. Install one (for example Speech Services by Google) in the system settings to hear the coach.", style = StrideType.Footnote.copy(color = StrideColors.Warning))
        }
        if (!voice.enabled) return@SubPage

        FormSection(header = "When to speak") {
            PickerRow("Trigger", AnnouncementTrigger.entries, voice.trigger, { it.displayName }, { v -> update { it.copy(trigger = v) } }, divider = voice.trigger != AnnouncementTrigger.Off)
            if (voice.trigger == AnnouncementTrigger.Distance || voice.trigger == AnnouncementTrigger.Both) {
                PickerRow("Every", listOf(0.25, 0.5, 1.0, 2.0, 5.0), voice.distanceInterval, { Fmt.trimmed(it) + " " + units.distanceUnit }, { v -> update { it.copy(distanceInterval = v) } },
                    divider = voice.trigger == AnnouncementTrigger.Both)
            }
            if (voice.trigger == AnnouncementTrigger.Time || voice.trigger == AnnouncementTrigger.Both) {
                PickerRow("Interval", listOf(60.0, 120.0, 300.0, 600.0, 900.0), voice.timeInterval, { "${(it / 60).toInt()} minute" + if (it > 60) "s" else "" }, { v -> update { it.copy(timeInterval = v) } }, divider = false)
            }
        }

        FormSection(header = "What to say", footer = preview) {
            Box(Modifier.padding(12.dp)) {
                SegmentedPicker(listOf(true, false), editingRun, { if (it) "Running" else "Cycling" }, { editingRun = it })
            }
            metrics.forEachIndexed { index, metric ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(metric.displayName, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 15.sp))
                    Icon(Icons.Filled.ArrowUpward, "Move up", tint = if (index > 0) StrideColors.SecondaryText else StrideColors.Track,
                        modifier = Modifier.size(34.dp).clickable(enabled = index > 0) { setMetrics(metrics.toMutableList().also { it.add(index - 1, it.removeAt(index)) }) }.padding(7.dp))
                    Icon(Icons.Filled.ArrowDownward, "Move down", tint = if (index < metrics.lastIndex) StrideColors.SecondaryText else StrideColors.Track,
                        modifier = Modifier.size(34.dp).clickable(enabled = index < metrics.lastIndex) { setMetrics(metrics.toMutableList().also { it.add(index + 1, it.removeAt(index)) }) }.padding(7.dp))
                    Icon(Icons.Filled.RemoveCircle, "Remove ${metric.displayName}", tint = StrideColors.Negative,
                        modifier = Modifier.size(34.dp).clickable { setMetrics(metrics - metric) }.padding(6.dp))
                }
            }
            Box {
                ButtonRow("Add a metric", { addOpen = true }, enabled = available.isNotEmpty(), divider = false)
                DropdownMenu(addOpen, { addOpen = false }, containerColor = StrideColors.Surface) {
                    available.forEach { m -> DropdownMenuItem(text = { Text(m.displayName) }, onClick = { addOpen = false; setMetrics(metrics + m) }) }
                }
            }
        }

        FormSection(
            header = "Delivery",
            footer = "Numbers are spoken the way a person says them — “four thirty-two” rather than “four minutes thirty-two seconds” — with real pauses between phrases instead of a run-on sentence.",
        ) {
            Box(Modifier.padding(12.dp)) { SegmentedPicker(SpeechStyle.entries, voice.style, { it.displayName }, { v -> update { it.copy(style = v) } }) }
            Text(voice.style.explanation, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = StrideType.Footnote)
            ToggleRow("Occasional encouragement", voice.coachRemarks, { v -> update { it.copy(coachRemarks = v) } }, divider = false)
        }

        FormSection(header = "Also announce") {
            ToggleRow("Countdown before starting", voice.countdownBeforeStart, { v -> update { it.copy(countdownBeforeStart = v) } })
            ToggleRow("Start", voice.announceStart, { v -> update { it.copy(announceStart = v) } })
            ToggleRow("Pause and resume", voice.announcePauseResume, { v -> update { it.copy(announcePauseResume = v) } })
            ToggleRow("Auto-pause", voice.announceAutoPause, { v -> update { it.copy(announceAutoPause = v) } })
            ToggleRow("Laps", voice.announceLaps, { v -> update { it.copy(announceLaps = v) } })
            ToggleRow("Finish summary", voice.announceFinish, { v -> update { it.copy(announceFinish = v) } })
            ToggleRow("Live segments", voice.announceSegments, { v -> update { it.copy(announceSegments = v) } })
            ToggleRow("Halfway to your goal", voice.announceHalfway, { v -> update { it.copy(announceHalfway = v) } })
            ToggleRow("Goal milestones", voice.announceGoalMilestones, { v -> update { it.copy(announceGoalMilestones = v) } }, divider = false)
        }

        FormSection(header = "Coaching", footer = "Alerts are spaced at least 45 seconds apart so they never nag.") {
            ToggleRow("Target pace alerts", voice.targetPaceAlertsEnabled, { v -> update { it.copy(targetPaceAlertsEnabled = v) } }, divider = voice.targetPaceAlertsEnabled)
            if (voice.targetPaceAlertsEnabled) {
                ValueRow("Target pace", Fmt.paceFromSeconds(voice.targetPaceSeconds) + " " + units.paceUnit, divider = false)
                LabelledSlider(voice.targetPaceSeconds.toFloat().coerceIn(150f, 600f), 150f..600f, 89) { v -> update { it.copy(targetPaceSeconds = (Math.round(v / 5f) * 5).toDouble()) } }
                PickerRow("Tell me when I am off by", listOf(5.0, 10.0, 15.0, 30.0), voice.targetPaceToleranceSeconds, { "${it.toInt()} seconds" }, { v -> update { it.copy(targetPaceToleranceSeconds = v) } })
            }
            ToggleRow("Heart rate zone alerts", voice.zoneAlertsEnabled, { v -> update { it.copy(zoneAlertsEnabled = v) } }, divider = voice.zoneAlertsEnabled)
            if (voice.zoneAlertsEnabled) {
                PickerRow("Stay in zone", (1..5).toList(), voice.targetHeartRateZone.coerceIn(1, 5), { "Zone $it — ${HeartRateZones.names[it - 1]}" }, { v -> update { it.copy(targetHeartRateZone = v) } }, divider = false)
            }
        }

        val better = voices.count { it.quality > 1 }
        FormSection(
            header = "Voice",
            footer = "Stride picks the best voice installed. Standard voices are the flat, robotic ones; Enhanced and Premium sound markedly more human, and choosing one makes far more difference than any other setting here." +
                if (better > 0) "\n\nYou have $better better-than-standard voice${if (better == 1) "" else "s"} installed — they are labelled in the list above."
                else "\n\nOnly Standard voices are installed. More voices can be downloaded in the text-to-speech engine's settings (look for speech or voice data in the system settings).",
        ) {
            PickerRow("Voice", listOf("") + voices.map { it.name }, voice.voiceIdentifier,
                { id -> if (id.isEmpty()) "Best available" else voices.firstOrNull { it.name == id }?.let { "${it.name} · ${it.qualityName}" } ?: id },
                { v -> update { it.copy(voiceIdentifier = v) } })
            Text("Speed", Modifier.padding(start = 16.dp, top = 8.dp), style = StrideType.Footnote)
            LabelledSlider(voice.rate.toFloat().coerceIn(0.3f, 0.7f), 0.3f..0.7f, 0) { v -> update { it.copy(rate = v.toDouble()) } }
            Text("Pitch", Modifier.padding(start = 16.dp), style = StrideType.Footnote)
            LabelledSlider(voice.pitch.toFloat().coerceIn(0.7f, 1.4f), 0.7f..1.4f, 0) { v -> update { it.copy(pitch = v.toDouble()) } }
            Text("Volume", Modifier.padding(start = 16.dp), style = StrideType.Footnote)
            LabelledSlider(voice.volume.toFloat().coerceIn(0.2f, 1f), 0.2f..1f, 0) { v -> update { it.copy(volume = v.toDouble()) } }
            ToggleRow("Duck music while speaking", voice.duckMusic, { v -> update { it.copy(duckMusic = v) } }, divider = false)
        }
    }
}

@Composable
private fun LaunchedEffectOnce(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

@Composable
private fun LabelledSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp)) {
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = StrideColors.Green, activeTrackColor = StrideColors.Green, inactiveTrackColor = StrideColors.Track),
        )
    }
}

/** How recording behaves, plus a way through to the voice coach. */
@Composable
fun RecordingSettingsScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val display = state.settings.display
    val requestHealth = rememberHealthPermissionRequest(graph.health)

    fun update(change: (DisplaySettings) -> DisplaySettings) = store.updateSettings { it.copy(display = change(it.display)) }

    SubPage("Recording", onBack = { nav.pop() }) {
        FormSection { NavRow(com.jashith.stride.ui.Icons.Run, "Voice coach", { nav.push(Dest.VoiceSettings) }, divider = false) }

        FormSection(header = "Recording screen") {
            ToggleRow("Auto-pause when you stop", display.autoPause, { v -> update { it.copy(autoPause = v) } })
            ToggleRow("Keep the screen awake", display.keepScreenOn, { v -> update { it.copy(keepScreenOn = v) } })
            PickerRow("Countdown", listOf(0, 3, 5, 10), display.countdownSeconds, { if (it == 0) "Off" else "$it seconds" }, { v -> update { it.copy(countdownSeconds = v) } }, divider = false)
        }

        FormSection(
            header = "Smartwatch",
            footer = "Start a workout on your watch when you start here. Most watches share heart rate with Health Connect in bursts, so the live number can lag; filling in after saving re-reads the whole activity, so your splits, zones and Relative Effort use everything the watch recorded. A Bluetooth strap paired under Heart rate monitor is live.",
        ) {
            ToggleRow("Read heart rate from Health Connect", display.readFromAppleHealth, { v -> update { it.copy(readFromAppleHealth = v) }; if (v) requestHealth() })
            ToggleRow("Fill in heart rate after saving", display.backfillHeartRateFromHealth, { v -> update { it.copy(backfillHeartRateFromHealth = v) } }, divider = false)
        }

        FormSection(header = "Segments", footer = "Star a segment to race it live. Stride watches for you entering it and calls out how you compare with your own best time.") {
            ToggleRow("Live segments", display.showLiveSegments, { v -> update { it.copy(showLiveSegments = v) } })
            ToggleRow("Speak segment progress", display.liveSegmentVoice, { v -> update { it.copy(liveSegmentVoice = v) } }, divider = false)
        }

        FormSection(header = "GPS", footer = "Stricter filtering gives cleaner distance under trees and between buildings, but can drop samples when the signal is poor.") {
            PickerRow("Discard fixes worse than", listOf(10.0, 20.0, 30.0, 50.0), display.gpsAccuracyFilter,
                { when (it) { 10.0 -> "10 m (strict)"; 30.0 -> "30 m (default)"; 50.0 -> "50 m (lenient)"; else -> "${it.toInt()} m" } },
                { v -> update { it.copy(gpsAccuracyFilter = v) } }, divider = false)
        }
    }
}
