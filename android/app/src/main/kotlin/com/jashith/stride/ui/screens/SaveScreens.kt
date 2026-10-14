package com.jashith.stride.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.analysis.ActivityAnalyzer
import com.jashith.stride.core.analysis.EffortScore
import com.jashith.stride.core.analysis.HeartRateBackfill
import com.jashith.stride.core.analysis.SegmentMatcher
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.ActivityPhoto
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.ui.DateFmt
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.FormSection
import com.jashith.stride.ui.components.NotesField
import com.jashith.stride.ui.components.NumberFieldRow
import com.jashith.stride.ui.components.PickerRow
import com.jashith.stride.ui.components.Pill
import com.jashith.stride.ui.components.PrimaryButton
import com.jashith.stride.ui.components.SecondaryButton
import com.jashith.stride.ui.components.StatTile
import com.jashith.stride.ui.components.StrideCard
import com.jashith.stride.ui.components.SubPage
import com.jashith.stride.ui.components.TextFieldRow
import com.jashith.stride.ui.components.ToggleRow
import com.jashith.stride.ui.components.ValueRow
import com.jashith.stride.ui.map.StrideMap
import com.jashith.stride.ui.map.MapDot
import com.jashith.stride.ui.map.MapLine
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private val exertionLabels = listOf(
    0 to "Not set", 1 to "1 — Easy", 2 to "2", 3 to "3 — Moderate", 4 to "4", 5 to "5 — Hard", 6 to "6", 7 to "7 — Max effort",
)

@Composable
fun SaveActivityScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val recorder = graph.recorder
    val store = graph.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberStoreState()
    val units = state.settings.units
    val finished by recorder.finished.collectAsState()

    val initial = finished
    if (initial == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }

    var activity by remember(initial) { mutableStateOf(initial.activity) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        scope.launch {
            val added = ArrayList<ActivityPhoto>()
            for (uri in uris) {
                val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
                val name = bytes?.let { withContext(Dispatchers.IO) { store.files.savePhoto(it) } }
                if (name != null) added += ActivityPhoto(filename = name)
            }
            activity = activity.copy(photos = activity.photos + added)
        }
    }

    val relevantGear = state.gear.filter { it.kind == activity.sport.gearKind && !it.isRetired }
    val matched = remember(activity.id, activity.sport, state.segments) {
        SegmentMatcher.findEfforts(activity, activity.id, initial.track, state.segments).mapNotNull { effort ->
            val segment = state.segments.firstOrNull { it.id == effort.segmentID } ?: return@mapNotNull null
            val existing = store.efforts(segment)
            segment to effort.copy(isPR = existing.isEmpty() || effort.time < existing.first().time)
        }
    }

    fun finishSaving(points: List<TrackPoint>) {
        scope.launch {
            // Heart rate may have changed, so every derived figure is recomputed.
            var analysed = withContext(Dispatchers.Default) { ActivityAnalyzer.analyse(activity, points, state.settings.profile) }
            analysed = analysed.copy(
                name = activity.name, notes = activity.notes, gearID = activity.gearID, photos = activity.photos,
                isCommute = activity.isCommute, isPrivate = activity.isPrivate, perceivedExertion = activity.perceivedExertion,
            )
            store.add(analysed, points)
            if (state.settings.display.writeToAppleHealth) {
                graph.health.refreshGranted()
                graph.health.save(analysed, points)
            }
            recorder.completeFinished()
            saving = false
            nav.pop()
        }
    }

    fun save() {
        saving = true
        val display = state.settings.display
        if (!(display.readFromAppleHealth && display.backfillHeartRateFromHealth && graph.health.isAvailable && initial.track.size > 1)) {
            finishSaving(initial.track)
            return
        }
        note = "Reading heart rate from Health Connect…"
        scope.launch {
            graph.health.refreshGranted()
            val samples = graph.health.heartRateSamples(activity.startDate.minusSeconds(30), activity.endDate.plusSeconds(30))
            if (samples.isEmpty()) finishSaving(initial.track)
            else finishSaving(HeartRateBackfill.apply(samples, initial.track, activity.startDate).points)
        }
    }

    SubPage(
        "Save Activity", onBack = { confirmDiscard = true },
        trailing = {
            Text(
                if (saving) "Saving…" else "Save",
                Modifier.clickable(enabled = !saving) { save() }.padding(8.dp),
                style = StrideType.Body.copy(fontSize = 16.sp, color = StrideColors.Green, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            )
        },
    ) {
        if (activity.hasGPS && activity.previewPath.size > 1) {
            Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(18.dp))) {
                StrideMap(
                    Modifier.fillMaxSize(),
                    lines = listOf(MapLine(activity.previewPath, StrideColors.MapLine, 5f)),
                    dots = listOf(MapDot(activity.previewPath.first(), StrideColors.Green), MapDot(activity.previewPath.last(), StrideColors.Negative)),
                    interactive = false,
                )
            }
        }
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row { 
                    StatTile(Fmt.distance(activity.distance, units), "Distance", Modifier.weight(1f), unit = units.distanceUnit, size = 20.sp)
                    StatTile(Fmt.duration(activity.movingTime), "Moving", Modifier.weight(1f), size = 20.sp)
                    StatTile(
                        if (activity.sport.usesPace) Fmt.pace(activity.distance, activity.movingTime, units) else Fmt.avgSpeed(activity.distance, activity.movingTime, units),
                        if (activity.sport.usesPace) "Pace" else "Speed", Modifier.weight(1f), size = 20.sp,
                    )
                }
                Row {
                    StatTile(Fmt.elevation(activity.elevationGain, units), "Elev gain", Modifier.weight(1f), unit = units.elevationUnit, size = 20.sp)
                    StatTile(Fmt.int(activity.calories), "Calories", Modifier.weight(1f), size = 20.sp)
                    StatTile(activity.avgHR?.let { "${it.toInt()}" } ?: "--", "Avg HR", Modifier.weight(1f), size = 20.sp)
                }
            }
        }
        if (saving && note.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = StrideColors.Green)
                Text(note, style = StrideType.Footnote)
            }
        }

        FormSection(header = "Details") {
            TextFieldRow("Activity name", activity.name, { activity = activity.copy(name = it) })
            PickerRow("Sport", SportType.entries, activity.sport, { it.displayName }, { activity = activity.copy(sport = it, gearID = null) })
        }
        NotesField(activity.notes, { activity = activity.copy(notes = it) }, "How did it go?")

        FormSection(header = "Effort") {
            PickerRow("Perceived exertion", exertionLabels.map { it.first }, activity.perceivedExertion ?: 0,
                { v -> exertionLabels.first { it.first == v }.second },
                { activity = activity.copy(perceivedExertion = if (it == 0) null else it) })
            ValueRow("Relative Effort", "${activity.relativeEffort.toInt()}")
            ValueRow("Training load", "${activity.trainingLoad.toInt()}", divider = false)
        }

        if (relevantGear.isNotEmpty()) {
            FormSection(header = "Gear") {
                PickerRow("Gear", listOf<UUID?>(null) + relevantGear.map { it.id }, activity.gearID,
                    { id -> relevantGear.firstOrNull { it.id == id }?.displayName ?: "None" },
                    { activity = activity.copy(gearID = it) }, divider = false)
            }
        }

        FormSection(header = "Photos") {
            ValueRow(
                "Add photos",
                if (activity.photos.isEmpty()) "" else "${activity.photos.size} attached",
                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                divider = false,
            )
        }

        FormSection {
            ToggleRow("Commute", activity.isCommute, { activity = activity.copy(isCommute = it) })
            ToggleRow("Keep private", activity.isPrivate, { activity = activity.copy(isPrivate = it) }, divider = false)
        }

        if (matched.isNotEmpty()) {
            FormSection(header = "Segments on this activity") {
                matched.forEachIndexed { i, (segment, effort) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(segment.name, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 15.sp), maxLines = 1)
                        if (effort.isPR) Pill("PR", color = StrideColors.Warning)
                        Text(Fmt.duration(effort.time), style = StrideType.figure(14, androidx.compose.ui.text.font.FontWeight.SemiBold))
                    }
                }
            }
        }
        SecondaryButton("Discard", { confirmDiscard = true }, destructive = true)
    }

    if (confirmDiscard) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this activity?") },
            text = { Text("Nothing from this recording will be kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    for (p in activity.photos) store.files.deletePhoto(p.filename)
                    recorder.completeFinished()
                    nav.pop()
                }) { Text("Discard", color = StrideColors.Negative) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** A date and time chosen with the Material pickers, shown as one row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeRow(title: String, value: Instant, onChange: (Instant) -> Unit, divider: Boolean = true) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val local = value.atZone(zone)
    var pickedDate by remember { mutableStateOf(local.toLocalDate()) }
    ValueRow(title, DateFmt.medium(value) + ", " + DateFmt.time(value), onClick = { showDate = true }, divider = divider)
    if (showDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { pickedDate = Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                    showTime = true
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(dateState) }
    }
    if (showTime) {
        val timeState = rememberTimePickerState(local.hour, local.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    showTime = false
                    onChange(pickedDate.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant())
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(timeState) },
        )
    }
}

@Composable
fun ManualActivityScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var sport by remember { mutableStateOf(SportType.Run) }
    var date by remember { mutableStateOf(Instant.now()) }
    var distance by remember { mutableStateOf(5.0) }
    var hours by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(30) }
    var seconds by remember { mutableStateOf(0) }
    var elevation by remember { mutableStateOf(0.0) }
    var avgHR by remember { mutableStateOf(0.0) }
    var notes by remember { mutableStateOf("") }

    val duration = (hours * 3600 + minutes * 60 + seconds).toDouble()

    fun save() {
        val profile = state.settings.profile
        var activity = Activity(
            name = name.ifEmpty { Activity.defaultName(sport, date) },
            sport = sport, startDate = date, isManual = true, hasGPS = false,
            distance = distance * units.metersPerUnit, movingTime = duration, elapsed = duration,
            elevationGain = elevation * units.metersPerElevationUnit, notes = notes,
        )
        if (avgHR > 30) {
            val zone = profile.hrZones.zone(avgHR)
            val times = MutableList(5) { 0.0 }
            times[zone] = duration
            activity = activity.copy(avgHR = avgHR, hrZoneTimes = times)
        }
        val speed = if (duration > 0) activity.distance / duration else 0.0
        val calories = EffortScore.calories(profile, sport, duration, if (avgHR > 30) avgHR else null, speed)
        val effort = if (activity.hrZoneTimes.isEmpty()) {
            EffortScore.estimatedEffort(duration, activity.avgPaceSecondsPerKm, profile.paceZones.thresholdPace)
        } else {
            EffortScore.relativeEffort(activity.hrZoneTimes)
        }
        activity = activity.copy(calories = calories, relativeEffort = effort, trainingLoad = effort)
        scope.launch {
            store.add(activity, emptyList())
            nav.pop()
        }
    }

    SubPage(
        "Manual Activity", onBack = { nav.pop() },
        trailing = {
            Text(
                "Save",
                Modifier.clickable(enabled = duration > 0) { save() }.padding(8.dp),
                style = StrideType.Body.copy(fontSize = 16.sp, color = if (duration > 0) StrideColors.Green else StrideColors.TertiaryText, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            )
        },
    ) {
        FormSection(header = "Activity") {
            TextFieldRow("Name", name, { name = it }, placeholder = "Optional")
            PickerRow("Sport", SportType.entries, sport, { it.displayName }, { sport = it })
            DateTimeRow("Date", date, { date = it }, divider = false)
        }
        FormSection(header = "Numbers") {
            NumberFieldRow("Distance", distance, { distance = it }, decimals = 2, suffix = units.distanceUnit)
            PickerRow("Hours", (0..23).toList(), hours, { "${it}h" }, { hours = it })
            PickerRow("Minutes", (0..59).toList(), minutes, { "${it}m" }, { minutes = it })
            PickerRow("Seconds", (0..59).toList(), seconds, { "${it}s" }, { seconds = it })
            NumberFieldRow("Elevation gain", elevation, { elevation = it }, decimals = 0, suffix = units.elevationUnit)
            NumberFieldRow("Average heart rate", avgHR, { avgHR = it }, decimals = 0, suffix = "bpm", divider = false)
        }
        NotesField(notes, { notes = it }, "Notes")
    }
}
