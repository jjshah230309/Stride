package com.jashith.stride.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.model.VoiceSettings
import com.jashith.stride.core.model.AnnouncementTrigger
import com.jashith.stride.core.recording.HeartRateSource
import com.jashith.stride.core.recording.LiveSegmentEngine
import com.jashith.stride.core.recording.RecordingState
import com.jashith.stride.ui.DateFmt
import com.jashith.stride.ui.Dest
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.CircleIconButton
import com.jashith.stride.ui.components.GpsIndicator
import com.jashith.stride.ui.components.InkCard
import com.jashith.stride.ui.components.Kicker
import com.jashith.stride.ui.components.MapBadge
import com.jashith.stride.ui.components.SportChoice
import com.jashith.stride.ui.components.SportToggle
import com.jashith.stride.ui.components.StrideCard
import com.jashith.stride.ui.map.MapDot
import com.jashith.stride.ui.map.MapLine
import com.jashith.stride.ui.map.StrideMap
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideShapes
import com.jashith.stride.ui.theme.StrideType
import java.time.Instant

fun HeartRateSource.label(): String = when (this) {
    HeartRateSource.None -> ""
    HeartRateSource.Strap -> "Strap"
    HeartRateSource.Watch -> "Watch"
    HeartRateSource.Health -> "Health Connect"
}

/** The runtime permissions a recording needs, minus the ones already granted. */
private fun missingPermissions(context: Context, foot: Boolean): List<String> {
    val wanted = ArrayList<String>()
    wanted += Manifest.permission.ACCESS_FINE_LOCATION
    wanted += Manifest.permission.ACCESS_COARSE_LOCATION
    if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
    if (Build.VERSION.SDK_INT >= 29 && foot) wanted += Manifest.permission.ACTIVITY_RECOGNITION
    return wanted.filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
}

@Composable
fun RecordScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val recorder = graph.recorder
    val store = graph.store
    val context = LocalContext.current
    val state = rememberStoreState()
    val units = state.settings.units
    val stats by recorder.stats.collectAsState()
    val gps by recorder.location.status.collectAsState()
    val strap by recorder.strap.state.collectAsState()
    val recovery by recorder.recovery.collectAsState()

    var confirmFinish by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var deniedNote by remember { mutableStateOf<String?>(null) }

    val sport = stats.sport
    val choice = if (sport.isRide) SportChoice.Ride else SportChoice.Run

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        recorder.location.refreshPermission()
        if (recorder.location.hasPermission()) {
            if (pendingStart) recorder.start(stats.sport) else recorder.warmUp(stats.sport)
        } else if (pendingStart) {
            deniedNote = "Stride needs location access to record your route. Allow it in the app's permissions and try again."
        }
        pendingStart = false
    }

    // Warm the GPS up so the first fix is not thirty seconds late.
    DisposableEffect(Unit) {
        if (stats.state == RecordingState.Idle) {
            recorder.engine.sport = state.settings.display.defaultSport
            if (recorder.location.hasPermission()) recorder.warmUp(state.settings.display.defaultSport)
        }
        onDispose { recorder.stopWarmUp() }
    }

    // After finishing, move on to the Save screen.
    val finished by recorder.finished.collectAsState()
    LaunchedEffect(finished) {
        if (finished != null) nav.push(Dest.SaveActivity)
    }

    fun begin() {
        val missing = missingPermissions(context, stats.sport.isFoot)
        if (!recorder.location.hasPermission()) {
            pendingStart = true
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
            recorder.start(stats.sport)
        }
    }

    Box(Modifier.fillMaxSize().background(StrideColors.PageBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Header
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (stats.state) {
                        RecordingState.Paused, RecordingState.AutoPaused -> "Paused"
                        RecordingState.Recording, RecordingState.Countdown -> "Recording"
                        else -> "Record"
                    },
                    Modifier.weight(1f), style = StrideType.ScreenTitle,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (stats.state.isActive) {
                        CircleIconButton(Icons.Filled.GraphicEq, "Speak current stats", { recorder.speakCurrentStats() }, background = StrideColors.Lime, tint = StrideColors.DeepGreen)
                    }
                    CircleIconButton(Icons.Filled.Tune, "Recording settings", { nav.push(Dest.RecordingSettings) })
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (stats.state == RecordingState.Idle) {
                    SportToggle(choice, { picked ->
                        if (picked != choice) {
                            val next = if (picked == SportChoice.Ride) SportType.Ride else SportType.Run
                            recorder.engine.sport = next
                            store.updateSettings { it.copy(display = it.display.copy(defaultSport = next)) }
                            recorder.warmUp(next)
                        }
                    })
                    // Keep specific disciplines (trail run, gravel...) chosen elsewhere.
                }
                stats.liveSegment?.let { LiveSegmentBanner(it, units) }

                // Map
                Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(StrideShapes.Map))) {
                    val path = stats.path
                    StrideMap(
                        Modifier.fillMaxSize(),
                        lines = if (path.size > 1) listOf(MapLine(path, StrideColors.MapLine, 5f)) else emptyList(),
                        dots = if (path.size > 2) listOf(
                            MapDot(path.first(), StrideColors.Green),
                            MapDot(path.last(), StrideColors.Negative),
                        ) else emptyList(),
                        userLocation = gps.latest,
                        follow = if (path.size < 2) gps.latest else null,
                        fitKey = path.size / 15,
                        fitPaddingPx = 60,
                    )
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MapBadge(
                            Icons.Filled.Map,
                            when {
                                gps.signal > 0 -> "Live GPS"
                                gps.permitted -> "Finding GPS"
                                else -> "Location off"
                            },
                        )
                        if (gps.signal > 0) {
                            Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.94f)).padding(horizontal = 10.dp, vertical = 9.dp)) {
                                GpsIndicator(gps.signal)
                            }
                        }
                    }
                }

                // Distance
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Kicker("Distance")
                    Text(
                        Fmt.distance(stats.distance, units),
                        style = StrideType.figure(62, color = if (stats.state.isPaused) StrideColors.SecondaryText else StrideColors.PrimaryText),
                    )
                    Text(units.distanceUnit, style = StrideType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = StrideColors.SecondaryText))
                }

                // Stats
                val paceNow = if (stats.currentSpeed > 0.2) units.metersPerUnit / stats.currentSpeed else 0.0
                Row(Modifier.fillMaxWidth()) {
                    StatColumn("Time", Fmt.durationPadded(stats.movingTime), if (stats.state == RecordingState.AutoPaused) "auto-paused" else null, Modifier.weight(1f))
                    StatColumn(
                        if (sport.usesPace) "Pace" else "Speed",
                        if (sport.usesPace) Fmt.paceFromSeconds(paceNow) else Fmt.speed(stats.currentSpeed, units),
                        if (sport.usesPace) units.paceUnit else units.speedUnit,
                        Modifier.weight(1f),
                    )
                    StatColumn(
                        "Heart rate",
                        if (stats.heartRate > 30) stats.heartRate.toInt().toString() else "--",
                        if (stats.heartRate > 30) stats.heartRateSource.label() else "bpm",
                        Modifier.weight(1f),
                    )
                }

                // Spoken splits
                InkCard(padding = 16.dp, radius = 20.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(Dest.VoiceSettings) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.Headphones, null, tint = StrideColors.Lime, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Spoken splits", style = StrideType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                            Text(voiceSummary(state.settings.voice, units), style = StrideType.Body.copy(fontSize = 13.sp, color = StrideColors.OnInkSecondary))
                        }
                        Switch(
                            checked = state.settings.voice.enabled,
                            onCheckedChange = { on -> store.updateSettings { it.copy(voice = it.voice.copy(enabled = on)) } },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = StrideColors.DeepGreen, checkedTrackColor = StrideColors.Lime,
                                uncheckedThumbColor = Color.White, uncheckedTrackColor = StrideColors.InkBar, uncheckedBorderColor = StrideColors.InkBar,
                            ),
                        )
                    }
                }

                if (stats.splits.isNotEmpty()) {
                    StrideCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Kicker("Splits")
                            stats.splits.reversed().forEach { split ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${split.index}", Modifier.size(width = 22.dp, height = 18.dp), style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = StrideColors.Green))
                                    Text(Fmt.distanceWithUnit(split.distance, units), Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp, color = StrideColors.SecondaryText))
                                    Text(Fmt.duration(split.moving), style = StrideType.figure(15, FontWeight.SemiBold))
                                }
                            }
                        }
                    }
                }

                if (stats.state == RecordingState.Idle || stats.state == RecordingState.Finished) {
                    ReadinessPanel(recorder, state.settings.display.readFromAppleHealth, store.starredSegments.size, state.settings.display.showLiveSegments,
                        onFixLocation = { permissionLauncher.launch(missingPermissions(context, false).toTypedArray()) })
                }
                deniedNote?.let { Text(it, style = StrideType.Footnote.copy(color = StrideColors.Negative)) }
            }

            // Controls
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val active = stats.state.isActive
                CircleIconButton(
                    Icons.Filled.Replay, "Lap", { recorder.lap() }, size = 58.dp,
                    tint = if (active) StrideColors.PrimaryText else StrideColors.TertiaryText,
                )
                Box(
                    Modifier.size(86.dp).clip(CircleShape).background(StrideColors.Lime).clickable {
                        when (stats.state) {
                            RecordingState.Idle, RecordingState.Finished -> begin()
                            RecordingState.Recording -> recorder.pause()
                            RecordingState.Paused, RecordingState.AutoPaused -> recorder.resume()
                            RecordingState.Countdown -> {}
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (stats.state == RecordingState.Recording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (stats.state == RecordingState.Recording) "Pause" else "Start",
                        tint = StrideColors.DeepGreen, modifier = Modifier.size(36.dp),
                    )
                }
                CircleIconButton(
                    Icons.Filled.Flag, "Finish", { if (active) confirmFinish = true }, size = 58.dp,
                    background = if (active) StrideColors.Ink else StrideColors.Surface,
                    tint = if (active) Color.White else StrideColors.TertiaryText,
                )
            }
        }

        if (stats.state == RecordingState.Countdown) {
            Box(Modifier.fillMaxSize().background(StrideColors.Ink.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
                Text(if (stats.countdownValue > 0) "${stats.countdownValue}" else "GO", style = StrideType.figure(120, color = StrideColors.Lime))
            }
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish this ${sport.shortName.lowercase()}?") },
            text = { Text("You can save it, or discard it without keeping anything.") },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; recorder.finish() }) { Text("Finish and save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmFinish = false }) { Text("Keep going") }
                    TextButton(onClick = { confirmFinish = false; recorder.discard() }) { Text("Discard", color = StrideColors.Negative) }
                }
            },
        )
    }

    recovery?.let { snapshot ->
        if (stats.state == RecordingState.Idle) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Unfinished activity found") },
                text = {
                    Text(
                        "Stride was interrupted during a ${snapshot.sport.shortName.lowercase()} on ${DateFmt.shortDay(snapshot.startDate)}. " +
                            "${Fmt.durationCompact(snapshot.elapsed)} was recorded.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { recorder.restore(snapshot) }) { Text("Resume") }
                },
                dismissButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { recorder.saveRecovered(snapshot) }) { Text("Save what was recorded") }
                        TextButton(onClick = { recorder.dismissRecovery() }) { Text("Discard", color = StrideColors.Negative) }
                    }
                },
            )
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(), style = StrideType.Body.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = StrideColors.SecondaryText))
        Text(value, style = StrideType.figure(24), maxLines = 1)
        Text(unit ?: " ", style = StrideType.Body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = StrideColors.SecondaryText), textAlign = TextAlign.Center)
    }
}

private fun voiceSummary(v: VoiceSettings, units: UnitSystem): String {
    if (!v.enabled) return "Off"
    return when (v.trigger) {
        AnnouncementTrigger.Off -> "Off"
        AnnouncementTrigger.Distance -> "Every ${Fmt.trimmed(v.distanceInterval)} ${units.distanceUnit}"
        AnnouncementTrigger.Time -> "Every ${Fmt.durationCompact(v.timeInterval)}"
        AnnouncementTrigger.Both -> "Every ${Fmt.trimmed(v.distanceInterval)} ${units.distanceUnit} and ${Fmt.durationCompact(v.timeInterval)}"
    }
}

@Composable
private fun ReadinessPanel(
    recorder: com.jashith.stride.service.RecordingController,
    readHealth: Boolean,
    segmentsArmed: Int,
    liveSegments: Boolean,
    onFixLocation: () -> Unit,
) {
    val context = LocalContext.current
    val gps by recorder.location.status.collectAsState()
    val strap by recorder.strap.state.collectAsState()
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
    StrideCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Kicker("Before you start")
            ReadinessRow(gps.permitted, if (gps.permitted) "Location allowed" else "Location permission needed", if (gps.permitted) null else onFixLocation)
            ReadinessRow(gps.signal >= 2, if (gps.signal >= 2) "GPS locked on" else "Waiting for a good GPS fix", null)
            if (segmentsArmed > 0 && liveSegments) {
                ReadinessRow(true, "$segmentsArmed live segment${if (segmentsArmed == 1) "" else "s"} armed", null)
            }
            if (strap.connected) ReadinessRow(true, "${strap.deviceName} connected", null)
            if (readHealth && !strap.connected && recorder.health.isAvailable) {
                ReadinessRow(false, "Heart rate comes from Health Connect when your watch syncs it", null)
            }
            ReadinessRow(
                unrestricted,
                if (unrestricted) "Background recording allowed" else "Battery saver may stop long recordings",
                if (unrestricted) null else ({
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }),
            )
        }
    }
}

@Composable
private fun ReadinessRow(ok: Boolean, text: String, fix: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (ok) StrideColors.Green else StrideColors.Warning, modifier = Modifier.size(17.dp))
        Text(text, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp))
        if (fix != null) {
            Text("Fix", Modifier.clickable(onClick = fix).padding(4.dp), style = StrideType.Body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.Green))
        }
    }
}

/** The live segment heads-up display: how far in, and how you compare. */
@Composable
fun LiveSegmentBanner(state: LiveSegmentEngine.LiveState, units: UnitSystem) {
    val ahead = state.deltaToPR <= 0
    val deltaColor = if (ahead) StrideColors.Lime else StrideColors.BehindPR
    InkCard(padding = 16.dp, radius = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.name, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1)
                if (state.prTime != null) {
                    Text(Fmt.signed(state.deltaToPR) + "s", style = StrideType.figure(20, color = deltaColor))
                }
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(StrideColors.InkBar)) {
                Box(Modifier.fillMaxWidth(state.fraction.coerceIn(0.0, 1.0).toFloat()).height(6.dp).clip(CircleShape).background(deltaColor))
            }
            Row {
                Text(Fmt.duration(state.elapsed), Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                Text(Fmt.distanceWithUnit(state.remaining, units) + " to go", style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.OnInkSecondary))
            }
        }
    }
}
