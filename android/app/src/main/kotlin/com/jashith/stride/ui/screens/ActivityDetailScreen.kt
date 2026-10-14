package com.jashith.stride.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.analysis.RouteSignature
import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.geo.Smooth
import com.jashith.stride.core.io.Exporter
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.BestEffort
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.HeartRateZones
import com.jashith.stride.core.model.PowerZones
import com.jashith.stride.core.model.SavedRoute
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Streams
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.util.fixed
import com.jashith.stride.files.FileOps
import com.jashith.stride.ui.DateFmt
import com.jashith.stride.ui.Dest
import com.jashith.stride.ui.Icons as AppIcons
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalMessage
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.ChartAxis
import com.jashith.stride.ui.components.CircleIconButton
import com.jashith.stride.ui.components.FormSection
import com.jashith.stride.ui.components.LineChart
import com.jashith.stride.ui.components.NotesField
import com.jashith.stride.ui.components.PickerRow
import com.jashith.stride.ui.components.Pill
import com.jashith.stride.ui.components.SectionHeader
import com.jashith.stride.ui.components.SplitsTable
import com.jashith.stride.ui.components.StatTile
import com.jashith.stride.ui.components.StrideCard
import com.jashith.stride.ui.components.SubPage
import com.jashith.stride.ui.components.TextFieldRow
import com.jashith.stride.ui.components.ToggleRow
import com.jashith.stride.ui.components.ZoneBars
import com.jashith.stride.ui.components.sportAccent
import com.jashith.stride.ui.map.MapDot
import com.jashith.stride.ui.map.MapLine
import com.jashith.stride.ui.map.StrideMap
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(id: UUID) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val message = LocalMessage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberStoreState()
    val store = graph.store
    val units = state.settings.units

    val activity = state.activities.firstOrNull { it.id == id }
    if (activity == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }

    val points by produceState(emptyList<TrackPoint>(), activity.id) {
        value = withContext(Dispatchers.IO) { store.track(activity) }
    }
    val streams = remember(points) { Streams(points) }
    var selectedEffort by remember { mutableStateOf<BestEffort?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun export(format: String) {
        scope.launch {
            val safe = activity.name.replace("/", "-")
            val filename = "$safe-${activity.startDate.epochSecond}.$format"
            val bytes = withContext(Dispatchers.Default) {
                when (format) {
                    "gpx" -> Exporter.gpx(activity, points).toByteArray()
                    "tcx" -> Exporter.tcx(activity, points).toByteArray()
                    else -> Exporter.fit(activity, points)
                }
            }
            val file = FileOps.writeExport(store.files, filename, bytes)
            FileOps.share(context, file, if (format == "fit") "application/octet-stream" else "application/xml", "Export ${format.uppercase()}")
        }
    }

    fun saveAsRoute() {
        val path = activity.previewPath
        if (path.size < 2) return
        val elevations = Geo.downsample(streams.altitude.toList(), path.size)
        store.addRoute(
            SavedRoute(
                name = activity.name, sport = activity.sport, path = path, elevations = elevations,
                distance = activity.distance, elevationGain = activity.elevationGain, sourceActivityID = activity.id,
            ),
        )
        message("Saved to Routes")
    }

    SubPage(
        "", onBack = { nav.pop() },
        trailing = {
            Box {
                CircleIconButton(Icons.Filled.MoreVert, "More", { menuOpen = true }, size = 40.dp)
                DropdownMenu(menuOpen, { menuOpen = false }, containerColor = StrideColors.Surface) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; editing = true })
                    DropdownMenuItem(text = { Text("Export GPX") }, onClick = { menuOpen = false; export("gpx") })
                    DropdownMenuItem(text = { Text("Export TCX") }, onClick = { menuOpen = false; export("tcx") })
                    DropdownMenuItem(text = { Text("Export FIT") }, onClick = { menuOpen = false; export("fit") })
                    if (activity.hasGPS) {
                        DropdownMenuItem(text = { Text("Create segment") }, onClick = { menuOpen = false; nav.push(Dest.SegmentCreator(activity.id)) })
                        DropdownMenuItem(text = { Text("Save as route") }, onClick = { menuOpen = false; saveAsRoute() })
                    }
                    DropdownMenuItem(text = { Text("Delete", color = StrideColors.Negative) }, onClick = { menuOpen = false; confirmDelete = true })
                }
            }
        },
    ) {
        // Header
        Column(Modifier.fillMaxWidth().padding(top = 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Icon(AppIcons.forSport(activity.sport), null, tint = sportAccent(activity.sport), modifier = Modifier.size(18.dp))
                Text(activity.sport.displayName, style = StrideType.Body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.SecondaryText))
                if (activity.isCommute) Pill("Commute", color = StrideColors.SecondaryText)
                if (activity.isManual) Pill("Manual", color = StrideColors.SecondaryText)
            }
            Text(activity.name, style = StrideType.Body.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold))
            Text(DateFmt.day(activity.startDate) + " · " + DateFmt.time(activity.startDate), style = StrideType.Body.copy(fontSize = 13.sp, color = StrideColors.SecondaryText))
        }

        // Map
        if (activity.hasGPS && activity.previewPath.size > 1) {
            val highlight = selectedEffort?.let { e ->
                if (streams.isEmpty) emptyList() else {
                    val lower = e.startIndex.coerceIn(0, streams.count - 1)
                    val upper = e.endIndex.coerceIn(lower, streams.count - 1)
                    streams.coords.subList(lower, upper + 1)
                }
            } ?: emptyList()
            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp))) {
                StrideMap(
                    Modifier.fillMaxSize(),
                    lines = buildList {
                        add(MapLine(activity.previewPath, sportAccent(activity.sport), 5f))
                        if (highlight.size > 1) add(MapLine(highlight, StrideColors.Warning, 7f))
                    },
                    dots = listOf(MapDot(activity.previewPath.first(), StrideColors.Green), MapDot(activity.previewPath.last(), StrideColors.Negative)),
                    fitKey = activity.id,
                )
            }
        }

        // Stats
        StrideCard {
            val tiles = buildList<Triple<String, String, String?>> {
                add(Triple(Fmt.distance(activity.distance, units), "Distance", units.distanceUnit))
                add(Triple(
                    if (activity.sport.usesPace) Fmt.pace(activity.distance, activity.movingTime, units) else Fmt.avgSpeed(activity.distance, activity.movingTime, units),
                    if (activity.sport.usesPace) "Avg pace" else "Avg speed", null,
                ))
                add(Triple(Fmt.duration(activity.movingTime), "Moving time", null))
                add(Triple(Fmt.elevation(activity.elevationGain, units), "Elev gain", units.elevationUnit))
                add(Triple(Fmt.int(activity.calories), "Calories", null))
                add(Triple(Fmt.int(activity.relativeEffort), "Relative effort", null))
                activity.avgHR?.let { add(Triple("${it.toInt()}", "Avg HR", "bpm")) }
                activity.maxHR?.let { add(Triple("${it.toInt()}", "Max HR", "bpm")) }
                activity.avgPower?.let { add(Triple("${it.toInt()}", "Avg power", "W")) }
                activity.normalizedPower?.let { add(Triple("${it.toInt()}", "Normalized", "W")) }
                activity.avgCadence?.let { add(Triple("${it.toInt()}", "Avg cadence", if (activity.sport.isRide) "rpm" else "spm")) }
                if (activity.maxSpeed > 0) add(Triple(Fmt.speed(activity.maxSpeed, units), "Max speed", units.speedUnit))
                add(Triple(Fmt.duration(activity.elapsed), "Elapsed", null))
                if (activity.elevationLoss > 5) add(Triple(Fmt.elevation(activity.elevationLoss, units), "Elev loss", units.elevationUnit))
                state.gear.firstOrNull { it.id == activity.gearID }?.let { add(Triple(it.displayName, "Gear", null)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (value, label, unit) ->
                            StatTile(value, label, Modifier.weight(1f), unit = unit, size = if (label == "Gear") 15.sp else 21.sp)
                        }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }

        if (activity.notes.isNotEmpty()) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader("Notes")
                    Text(activity.notes, style = StrideType.Body)
                }
            }
        }

        val highlightRange = selectedEffort?.let { it.startIndex..it.endIndex }

        if (!streams.isEmpty) {
            val elevation = remember(streams) { Smooth.movingAverage(streams.altitude, 15).toList() }
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Elevation")
                    LineChart(elevation, Modifier.height(110.dp), StrideColors.HikeColor, fill = true, highlight = highlightRange)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val small = StrideType.Body.copy(fontSize = 10.sp, color = StrideColors.SecondaryText)
                        Text("${Fmt.elevation(activity.minElevation, units)} ${units.elevationUnit}", style = small)
                        Text("+${Fmt.elevation(activity.elevationGain, units)} / -${Fmt.elevation(activity.elevationLoss, units)} ${units.elevationUnit}", style = small)
                        Text("${Fmt.elevation(activity.maxElevation, units)} ${units.elevationUnit}", style = small)
                    }
                }
            }
        }

        val splits = if (units == com.jashith.stride.core.model.UnitSystem.Metric) activity.splitsKm else activity.splitsMi
        if (splits.isNotEmpty()) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Splits (per ${units.distanceUnit})")
                    SplitsTable(splits, activity.sport, units)
                }
            }
        }

        if (!streams.isEmpty) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader("Analysis")
                    if (streams.hasHeartRate) {
                        StreamRow("Heart rate", streams.heartRate.map { it ?: 0.0 }, StrideColors.Negative,
                            "${Fmt.int(activity.avgHR)} avg · ${Fmt.int(activity.maxHR)} max bpm", highlightRange)
                    }
                    val speed = remember(streams) { Smooth.movingAverage(DoubleArray(streams.count) { minOf(streams.speed[it], activity.sport.maxPlausibleSpeed) }, 9).toList() }
                    StreamRow(if (activity.sport.usesPace) "Pace" else "Speed", speed, sportAccent(activity.sport), activity.paceOrSpeedString(units) + " average", highlightRange)
                    if (streams.hasPower) {
                        StreamRow("Power", streams.power.map { it ?: 0.0 }, StrideColors.Warning,
                            "${Fmt.int(activity.avgPower)} W avg · ${Fmt.int(activity.normalizedPower)} W normalized", highlightRange)
                    }
                    if (streams.hasCadence) {
                        StreamRow("Cadence", streams.cadence.map { it ?: 0.0 }, StrideColors.RideColor, "${Fmt.int(activity.avgCadence)} average", highlightRange)
                    }
                    val grades = remember(streams) { streams.grade().map { it * 100 } }
                    StreamRow(
                        "Gradient", grades, StrideColors.HikeColor,
                        fixed(if (activity.distance > 0) activity.elevationGain / activity.distance * 100 else 0.0, 1) + "% average climb", null, lineWidth = 1.2f, height = 50,
                    )
                }
            }
        }

        val hr = activity.hrZoneTimes
        val pw = activity.powerZoneTimes
        if (hr.sum() > 0 || pw.sum() > 0) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val profile = state.settings.profile
                    if (hr.sum() > 0) {
                        SectionHeader("Heart rate zones")
                        ZoneBars(hr, HeartRateZones.names, HeartRateZones.shortNames, StrideColors.HeartRateZones, ranges = (0 until 5).map { profile.hrZones.range(it) }, unit = "bpm")
                    }
                    if (pw.sum() > 0) {
                        SectionHeader("Power zones")
                        ZoneBars(pw, PowerZones.names, PowerZones.shortNames, StrideColors.PowerZones, ranges = (0 until profile.powerZones.bounds.size).map { profile.powerZones.range(it) }, unit = "W")
                    }
                }
            }
        }

        if (activity.bestEfforts.isNotEmpty()) {
            val records = remember(state.activities) { store.personalRecords(activity.sport) }
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Best efforts")
                    activity.bestEfforts.forEach { effort ->
                        val selected = selectedEffort?.id == effort.id
                        val isPr = records.firstOrNull { it.effort.name == effort.name }?.activity?.id == activity.id
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedEffort = if (selected) null else effort }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(effort.name, style = StrideType.Body.copy(fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal))
                            if (isPr) Pill("PR", color = StrideColors.Warning)
                            Box(Modifier.weight(1f))
                            Text(
                                Fmt.paceFromSeconds(effort.paceSecondsPerKm * units.metersPerUnit / 1000) + " " + units.paceUnit,
                                style = StrideType.figure(12, FontWeight.Normal, StrideColors.SecondaryText),
                            )
                            Text(Fmt.duration(effort.time), Modifier.width(66.dp), style = StrideType.figure(14, FontWeight.SemiBold), maxLines = 1)
                        }
                    }
                    if (selectedEffort != null) Text("Tap again to clear the highlight.", style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.TertiaryText))
                }
            }
        }

        val efforts = state.segmentEfforts.filter { it.activityID == activity.id }.mapNotNull { e -> state.segments.firstOrNull { it.id == e.segmentID }?.let { it to e } }
        if (efforts.isNotEmpty()) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Segments")
                    efforts.forEach { (segment, effort) ->
                        Row(Modifier.fillMaxWidth().clickable { nav.push(Dest.SegmentDetail(segment.id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(segment.name, style = StrideType.Body.copy(fontSize = 14.sp), maxLines = 1)
                                Text(
                                    Fmt.distanceWithUnit(segment.distance, units, 2) + " · " + fixed(segment.averageGrade * 100, 1) + "%",
                                    style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText),
                                )
                            }
                            if (effort.isPR) Pill("PR", color = StrideColors.Warning) else if (effort.rank > 0) Pill("#${effort.rank}", color = StrideColors.SecondaryText)
                            Text(Fmt.duration(effort.time), style = StrideType.figure(14, FontWeight.SemiBold))
                        }
                    }
                }
            }
        }

        if (activity.laps.isNotEmpty()) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Laps")
                    activity.laps.forEach { lap ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${lap.index}", Modifier.width(24.dp), style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                            Text(Fmt.distanceWithUnit(lap.distance, units), Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp, color = StrideColors.SecondaryText))
                            lap.avgHR?.let { Text("${it.toInt()} bpm", style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.SecondaryText)) }
                            Text(Fmt.duration(lap.moving), style = StrideType.figure(14, FontWeight.SemiBold))
                        }
                    }
                }
            }
        }

        val matched = remember(state.activities, activity) { store.matchedActivities(activity) }
        if (matched.isNotEmpty()) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Same route")
                    Text("You have covered this route ${matched.size + 1} times.", style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.SecondaryText))
                    matched.take(6).forEach { other ->
                        val delta = other.movingTime - activity.movingTime
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(DateFmt.shortDay(other.startDate), Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp))
                            Text(
                                Fmt.signed(delta, 0) + "s",
                                style = StrideType.Body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (delta < 0) StrideColors.Positive else StrideColors.Negative),
                            )
                            Text(Fmt.duration(other.movingTime), Modifier.width(70.dp), style = StrideType.figure(14, FontWeight.SemiBold))
                        }
                    }
                }
            }
        }

        if (activity.photos.isNotEmpty()) {
            StrideCard(padding = 12.dp) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activity.photos.forEach { photo -> PhotoThumb(store.files.photoFile(photo.filename)) }
                }
            }
        }
    }

    if (editing) {
        EditActivitySheet(activity, onDismiss = { editing = false }, onSave = { store.update(it); editing = false })
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this activity?") },
            text = { Text("The activity, its track and its photos are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; store.delete(activity); nav.pop() }) { Text("Delete", color = StrideColors.Negative) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StreamRow(title: String, values: List<Double>, color: androidx.compose.ui.graphics.Color, caption: String, highlight: IntRange?, lineWidth: Float = 1.5f, height: Int = 66) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            Text(caption, style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText))
        }
        LineChart(values, Modifier.height(height.dp), color, fill = true, lineWidth = lineWidth, highlight = highlight)
    }
}

@Composable
private fun PhotoThumb(file: File) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 600) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(it, null, Modifier.size(130.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditActivitySheet(activity: Activity, onDismiss: () -> Unit, onSave: (Activity) -> Unit) {
    val state = rememberStoreState()
    var draft by remember { mutableStateOf(activity) }
    val gear = state.gear.filter { it.kind == draft.sport.gearKind }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = StrideColors.PageBackground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Edit", Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                TextButton(onClick = { onSave(draft) }) { Text("Done", color = StrideColors.Green, fontWeight = FontWeight.SemiBold) }
            }
            FormSection(header = "Details") {
                TextFieldRow("Name", draft.name, { draft = draft.copy(name = it) })
                PickerRow("Sport", SportType.entries, draft.sport, { it.displayName }, { draft = draft.copy(sport = it, gearID = null) }, divider = false)
            }
            NotesField(draft.notes, { draft = draft.copy(notes = it) }, "Notes", minLines = 3)
            FormSection(header = "Gear") {
                PickerRow("Gear", listOf<UUID?>(null) + gear.map { it.id }, draft.gearID, { id -> gear.firstOrNull { it.id == id }?.displayName ?: "None" }, { draft = draft.copy(gearID = it) }, divider = false)
            }
            FormSection {
                ToggleRow("Commute", draft.isCommute, { draft = draft.copy(isCommute = it) })
                ToggleRow("Private", draft.isPrivate, { draft = draft.copy(isPrivate = it) }, divider = false)
            }
        }
    }
}
