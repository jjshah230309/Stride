package com.jashith.stride.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.geo.Smooth
import com.jashith.stride.core.io.Exporter
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.SavedRoute
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.util.fixed
import com.jashith.stride.files.FileOps
import com.jashith.stride.files.RouteSnapper
import com.jashith.stride.files.RouteTerrain
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.DateFmt
import com.jashith.stride.ui.components.CircleIconButton
import com.jashith.stride.ui.components.FormSection
import com.jashith.stride.ui.components.LineChart
import com.jashith.stride.ui.components.PickerRow
import com.jashith.stride.ui.components.SectionHeader
import com.jashith.stride.ui.components.StatTile
import com.jashith.stride.ui.components.StrideCard
import com.jashith.stride.ui.components.SubPage
import com.jashith.stride.ui.components.TextFieldRow
import com.jashith.stride.ui.components.ToggleRow
import com.jashith.stride.ui.components.sportAccent
import com.jashith.stride.ui.map.MapDot
import com.jashith.stride.ui.map.MapLine
import com.jashith.stride.ui.map.StrideMap
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideShapes
import com.jashith.stride.ui.theme.StrideType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun RouteDetailScreen(id: UUID) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberStoreState()
    val units = state.settings.units
    val route = state.routes.firstOrNull { it.id == id }
    if (route == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }

    // Uses your own average speed for this sport rather than a generic guess.
    val estimate = remember(route, state.activities) {
        val relevant = state.activities.filter { it.sport == route.sport && it.movingTime > 300 }
        val speed = if (relevant.isEmpty()) (if (route.sport.isRide) 6.5 else 2.8) else {
            val time = relevant.sumOf { it.movingTime }
            if (time > 0) relevant.sumOf { it.distance } / time else 2.8
        }
        route.estimatedTime(speed, route.sport)
    }

    SubPage(route.name, onBack = { nav.pop() }) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(StrideShapes.DetailMap))) {
            StrideMap(
                Modifier.fillMaxSize(),
                lines = listOf(MapLine(route.path, sportAccent(route.sport), 5f)),
                dots = listOf(MapDot(route.path.first(), StrideColors.Green), MapDot(route.path.last(), StrideColors.Negative)),
                fitKey = route.id,
            )
        }
        StrideCard {
            Row {
                StatTile(Fmt.distance(route.distance, units), "Distance", Modifier.weight(1f), unit = units.distanceUnit, size = 20.sp)
                StatTile(Fmt.elevation(route.elevationGain, units), "Elev gain", Modifier.weight(1f), unit = units.elevationUnit, size = 20.sp)
                StatTile(Fmt.durationCompact(estimate), "Est. time", Modifier.weight(1f), size = 20.sp)
            }
        }
        if (route.elevations.size > 2) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Elevation profile")
                    LineChart(route.elevations, Modifier.height(100.dp), StrideColors.HikeColor, fill = true)
                }
            }
        }
        FormSection {
            ToggleRow("Starred", route.isStarred, { graph.store.updateRoute(route.copy(isStarred = it)) })
            ActionRow("Export as GPX") {
                scope.launch {
                    val file = FileOps.writeExport(graph.store.files, route.name.replace("/", "-") + ".gpx", Exporter.gpx(route).toByteArray())
                    FileOps.share(context, file, "application/xml", "Export route")
                }
            }
            ActionRow("Delete route", destructive = true, divider = false) {
                graph.store.deleteRoute(route)
                nav.pop()
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, destructive: Boolean = false, divider: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
            style = StrideType.Body.copy(fontSize = 15.sp, color = if (destructive) StrideColors.Negative else StrideColors.PrimaryText),
        )
        if (divider) com.jashith.stride.ui.components.Hairline(Modifier.padding(start = 16.dp))
    }
}

/** Tap the map to lay down a route. Points can be joined in a straight line or snapped onto real paths. */
@Composable
fun RouteBuilderScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    val scope = rememberCoroutineScope()
    val gps by graph.location.status.collectAsState()

    var waypoints by remember { mutableStateOf(listOf<Coord>()) }
    var rendered by remember { mutableStateOf(listOf<Coord>()) }
    var name by remember { mutableStateOf("") }
    var sport by remember { mutableStateOf(SportType.Run) }
    var snap by remember { mutableStateOf(true) }
    var snapping by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (graph.location.hasPermission() && !graph.recorder.isActive) graph.location.start() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (!graph.recorder.isActive) graph.location.stop() } }

    val center = gps.latest ?: state.activities.firstOrNull()?.startCoord

    // Re-snap shortly after the last tap so a quick run of taps makes one request, not five.
    LaunchedEffect(waypoints, snap, sport) {
        if (waypoints.size < 2 || !snap) {
            rendered = waypoints
            snapping = false
            return@LaunchedEffect
        }
        snapping = true
        delay(350)
        rendered = RouteSnapper.snap(waypoints, walking = !sport.isRide)
        snapping = false
    }

    val path = if (rendered.size > 1) rendered else waypoints
    val distance = Geo.totalDistance(path)
    val estimate = if (distance > 0) distance / (if (sport.isRide) 6.5 else 2.8) else 0.0

    fun save() {
        saving = true
        scope.launch {
            val profile = withContext(Dispatchers.Default) { RouteTerrain.elevations(path, state.activities) { store.track(it) } }
            val gain = if (profile.isEmpty()) 0.0 else Smooth.elevationChange(profile, 2.0).gain
            store.addRoute(
                SavedRoute(
                    name = name.ifEmpty { "Route ${state.routes.size + 1}" }, sport = sport, path = path, elevations = profile,
                    distance = Geo.totalDistance(path), elevationGain = gain,
                ),
            )
            saving = false
            nav.pop()
        }
    }

    SubPage(
        "Build Route", onBack = { nav.pop() }, scroll = false, gutters = false,
        trailing = {
            Text(
                if (saving) "Saving…" else "Save",
                Modifier.clickable(enabled = waypoints.size >= 2 && !saving) { save() }.padding(8.dp),
                style = StrideType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (waypoints.size >= 2) StrideColors.Green else StrideColors.TertiaryText),
            )
        },
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            StrideMap(
                Modifier.fillMaxSize(),
                lines = if (path.size > 1) listOf(MapLine(path, StrideColors.Green, 5f)) else emptyList(),
                dots = waypoints.mapIndexed { i, c ->
                    when (i) {
                        0 -> MapDot(c, StrideColors.Green, 8f)
                        waypoints.lastIndex -> MapDot(c, StrideColors.Negative, 8f)
                        else -> MapDot(c, StrideColors.Lime, 5.5f)
                    }
                },
                userLocation = gps.latest,
                initialCenter = center,
                fitKey = Unit,
                onTap = { waypoints = waypoints + it },
            )
            Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleIconButton(Icons.Filled.Undo, "Undo", { if (waypoints.isNotEmpty()) waypoints = waypoints.dropLast(1) }, size = 40.dp)
                CircleIconButton(Icons.Filled.Delete, "Clear", { waypoints = emptyList(); rendered = emptyList() }, size = 40.dp)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                StatTile(Fmt.distance(distance, units), "Distance", Modifier.weight(1f), unit = units.distanceUnit, size = 20.sp)
                StatTile("${waypoints.size}", "Points", Modifier.weight(1f), size = 20.sp)
                StatTile(Fmt.durationCompact(estimate), "Est. time", Modifier.weight(1f), size = 20.sp)
            }
            FormSection {
                TextFieldRow("Route name", name, { name = it }, placeholder = "Optional")
                PickerRow("Sport", SportType.entries.filter { !it.isIndoor }, sport, { it.shortName }, { sport = it })
                ToggleRow("Snap to paths and roads", snap, { snap = it }, divider = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapping) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = StrideColors.Green)
                Text(
                    if (waypoints.isEmpty()) "Tap the map to drop your first point."
                    else "Tap to add more points. Snapping uses OpenStreetMap routing and needs a connection; offline it joins the points with straight lines.",
                    style = StrideType.Footnote.copy(fontSize = 11.sp),
                )
            }
        }
    }
}

/** Carve a segment out of an activity by dragging the start and end handles. */
@Composable
fun SegmentCreatorScreen(activityId: UUID) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    val scope = rememberCoroutineScope()
    val activity = state.activities.firstOrNull { it.id == activityId }
    if (activity == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val points by produceState(emptyList<TrackPoint>(), activityId) { value = withContext(Dispatchers.IO) { store.track(activity) } }
    val valid = remember(points) { points.filter { it.coord.isValid } }

    var startFraction by remember { mutableStateOf(0.1f) }
    var endFraction by remember { mutableStateOf(0.4f) }
    var name by remember { mutableStateOf("") }

    val denominator = maxOf(1, valid.size - 1)
    val startIndex = (startFraction * denominator).toInt().coerceIn(0, maxOf(0, valid.size - 1))
    val endIndex = maxOf(startIndex + 1, minOf(valid.size - 1, (endFraction * denominator).toInt()))
    val selected = if (valid.size > 1 && endIndex > startIndex) valid.subList(startIndex, endIndex + 1) else emptyList()
    val selectedPath = selected.map { it.coord }
    val selectedDistance = if (selected.size > 1) selected.last().d - selected.first().d else 0.0
    val change = Smooth.elevationChange(Smooth.movingAverage(selected.map { it.alt }.toDoubleArray(), 7), 0.8)
    val selectedTime = if (selected.size > 1) selected.last().t - selected.first().t else 0.0

    fun create() {
        val path = Geo.simplify(selectedPath, 3.0)
        val elevations = Geo.downsample(selected.map { it.alt }, path.size)
        val segment = Segment.make(name, activity.sport, path, elevations.ifEmpty { selected.map { it.alt } })
            .copy(createdFromActivityID = activity.id)
        scope.launch {
            store.addSegment(segment)
            nav.pop()
        }
    }

    SubPage(
        "New Segment", onBack = { nav.pop() },
        trailing = {
            val ok = selectedDistance >= 100 && name.isNotEmpty()
            Text(
                "Create", Modifier.clickable(enabled = ok) { create() }.padding(8.dp),
                style = StrideType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (ok) StrideColors.Green else StrideColors.TertiaryText),
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(StrideShapes.DetailMap))) {
            StrideMap(
                Modifier.fillMaxSize(),
                lines = buildList {
                    add(MapLine(activity.previewPath, StrideColors.SecondaryText, 4f))
                    if (selectedPath.size > 1) add(MapLine(Geo.downsample(selectedPath, 300), StrideColors.Warning, 7f))
                },
                fitKey = activity.id,
            )
        }
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row {
                    StatTile(Fmt.distance(selectedDistance, units), "Length", Modifier.weight(1f), unit = units.distanceUnit, size = 19.sp)
                    StatTile(Fmt.elevation(change.gain, units), "Climb", Modifier.weight(1f), unit = units.elevationUnit, size = 19.sp)
                }
                Row {
                    StatTile(fixed(if (selectedDistance > 0) change.gain / selectedDistance * 100 else 0.0, 1), "Grade", Modifier.weight(1f), unit = "%", size = 19.sp)
                    StatTile(Fmt.duration(selectedTime), "Your time", Modifier.weight(1f), size = 19.sp)
                }
            }
        }
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionHeader("Trim")
                Column {
                    Text("Start", style = StrideType.Footnote)
                    Slider(
                        value = startFraction, valueRange = 0f..0.95f,
                        onValueChange = { startFraction = it; if (endFraction <= it + 0.02f) endFraction = minOf(1f, it + 0.02f) },
                        colors = SliderDefaults.colors(thumbColor = StrideColors.Green, activeTrackColor = StrideColors.Green, inactiveTrackColor = StrideColors.Track),
                    )
                }
                Column {
                    Text("Finish", style = StrideType.Footnote)
                    Slider(
                        value = endFraction, valueRange = 0.05f..1f,
                        onValueChange = { endFraction = it; if (startFraction >= it - 0.02f) startFraction = maxOf(0f, it - 0.02f) },
                        colors = SliderDefaults.colors(thumbColor = StrideColors.Green, activeTrackColor = StrideColors.Green, inactiveTrackColor = StrideColors.Track),
                    )
                }
            }
        }
        FormSection(
            header = "Name",
            footer = "Stride will search every activity you have recorded for previous attempts, then keep score every time you cover it again.",
        ) { TextFieldRow("Name", name, { name = it }, placeholder = "Riverside climb", divider = false) }
    }
}

/** The personal leaderboard: every attempt you have made, ranked. */
@Composable
fun SegmentDetailScreen(id: UUID) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    val segment = state.segments.firstOrNull { it.id == id }
    var confirm by remember { mutableStateOf(false) }
    if (segment == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val efforts = remember(state.segmentEfforts, segment) { store.efforts(segment) }

    SubPage(segment.name, onBack = { nav.pop() }) {
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(StrideShapes.DetailMap))) {
            StrideMap(
                Modifier.fillMaxSize(),
                lines = listOf(MapLine(segment.path, StrideColors.Green, 5f)),
                dots = listOf(MapDot(segment.path.first(), StrideColors.Green), MapDot(segment.path.last(), StrideColors.Negative)),
                fitKey = segment.id,
            )
        }
        StrideCard {
            Row {
                StatTile(Fmt.distance(segment.distance, units), "Length", Modifier.weight(1f), unit = units.distanceUnit, size = 20.sp)
                StatTile(fixed(segment.averageGrade * 100, 1), "Avg grade", Modifier.weight(1f), unit = "%", size = 20.sp)
                StatTile(Fmt.elevation(segment.elevationGain, units), "Climb", Modifier.weight(1f), unit = units.elevationUnit, size = 20.sp)
            }
        }
        if (efforts.size > 1) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Progress")
                    LineChart(efforts.sortedBy { it.date }.map { it.time }, Modifier.height(90.dp), StrideColors.Green, fill = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Oldest", style = StrideType.Body.copy(fontSize = 10.sp, color = StrideColors.SecondaryText))
                        Text("Latest", style = StrideType.Body.copy(fontSize = 10.sp, color = StrideColors.SecondaryText))
                    }
                }
            }
        }
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Your leaderboard")
                if (efforts.isEmpty()) Text("No efforts recorded yet. Go and ride or run it.", style = StrideType.BodySecondary.copy(fontSize = 13.sp))
                efforts.forEachIndexed { index, effort ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(com.jashith.stride.ui.Dest.ActivityDetail(effort.activityID)) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("${index + 1}", Modifier.width(20.dp), style = StrideType.Body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (index == 0) StrideColors.Warning else StrideColors.SecondaryText))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(DateFmt.shortDay(effort.date), style = StrideType.Body.copy(fontSize = 13.sp))
                            Text(
                                if (segment.sport.usesPace) Fmt.pace(segment.distance, effort.time, units) + " " + units.paceUnit
                                else Fmt.avgSpeed(segment.distance, effort.time, units) + " " + units.speedUnit,
                                style = StrideType.Body.copy(fontSize = 10.sp, color = StrideColors.SecondaryText),
                            )
                        }
                        effort.avgHR?.let { Text("${it.toInt()} bpm", style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText)) }
                        Text(Fmt.duration(effort.time), style = StrideType.figure(15, FontWeight.SemiBold))
                    }
                }
            }
        }
        FormSection(footer = "Starred segments are watched while you record. Stride announces when you enter one and calls out how you compare with your best.") {
            ToggleRow("Race this live", segment.isStarred, { store.updateSegment(segment.copy(isStarred = it)) })
            ActionRow("Delete segment", destructive = true, divider = false) { confirm = true }
        }
    }

    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this segment and all its efforts?") },
            confirmButton = { TextButton(onClick = { confirm = false; store.deleteSegment(segment); nav.pop() }) { Text("Delete", color = StrideColors.Negative) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
