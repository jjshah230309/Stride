package com.jashith.stride.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.util.fixed
import com.jashith.stride.ui.Dest
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.CircleIconButton
import com.jashith.stride.ui.components.DetailRow
import com.jashith.stride.ui.components.EmptyState
import com.jashith.stride.ui.components.MapBadge
import com.jashith.stride.ui.components.ScreenHeader
import com.jashith.stride.ui.components.StrideCard
import com.jashith.stride.ui.components.StrideChip
import com.jashith.stride.ui.components.TabPage
import com.jashith.stride.ui.map.MapDot
import com.jashith.stride.ui.map.MapLine
import com.jashith.stride.ui.map.StrideMap
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideShapes
import com.jashith.stride.ui.theme.StrideType

private enum class RouteFilter(val title: String) { Routes("Routes"), Segments("Segments"), Starred("Starred"), Heatmap("Heatmap") }

@Composable
fun RoutesScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    var filter by remember { mutableStateOf(RouteFilter.Routes) }

    val starredSegments = state.segments.filter { it.isStarred }
    val pool: List<Segment> = if (filter == RouteFilter.Starred) starredSegments else state.segments

    fun segmentSubtitle(s: Segment): String {
        val parts = arrayListOf(Fmt.distanceWithUnit(s.distance, units, 2), fixed(s.averageGrade * 100, 1) + "%")
        store.personalBest(s)?.let { parts += "PR " + Fmt.duration(it.time) }
        return parts.joinToString(" · ")
    }

    TabPage {
        ScreenHeader("Explore", "Routes", trailing = {
            CircleIconButton(Icons.Filled.Add, "Build a route", { nav.push(Dest.RouteBuilder()) })
        })

        // Feature card
        if (filter == RouteFilter.Heatmap) {
            val paths = remember(state.activities) { state.activities.filter { it.hasGPS && it.previewPath.size > 1 }.map { it.previewPath } }
            if (paths.isEmpty()) {
                StrideCard {
                    EmptyState(Icons.Filled.LocalFireDepartment, "Your heatmap is empty", "Record a few activities and the roads and trails you use most will start to glow here.")
                }
            } else {
                Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(StrideShapes.Map))) {
                    StrideMap(
                        Modifier.fillMaxSize(),
                        lines = paths.map { MapLine(it, StrideColors.Heat, 4f) },
                        zoomScaledLines = true,
                        fitKey = paths.size,
                        fitPaddingPx = 70,
                    )
                    Box(Modifier.padding(14.dp).align(Alignment.BottomStart)) {
                        MapBadge(
                            Icons.Filled.LocalFireDepartment,
                            "${paths.size} activities · " + Fmt.distanceWithUnit(state.activities.sumOf { it.distance }, units, 0),
                        )
                    }
                }
            }
        } else {
            val featured: Pair<List<Coord>, String>? = when (filter) {
                RouteFilter.Segments, RouteFilter.Starred -> pool.firstOrNull()?.let {
                    it.path to (Fmt.distanceWithUnit(it.distance, units, 2) + " · " + Fmt.elevationWithUnit(it.elevationGain, units))
                }
                else -> {
                    val route = state.routes.firstOrNull { it.isStarred } ?: state.routes.firstOrNull()
                    if (route != null) {
                        route.path to (Fmt.distanceWithUnit(route.distance, units) + " · " + Fmt.elevationWithUnit(route.elevationGain, units))
                    } else {
                        state.activities.firstOrNull { it.hasGPS }?.let {
                            it.previewPath to (Fmt.distanceWithUnit(it.distance, units) + " · " + Fmt.elevationWithUnit(it.elevationGain, units))
                        }
                    }
                }
            }
            if (featured != null && featured.first.size > 1) {
                Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(StrideShapes.Map))) {
                    StrideMap(
                        Modifier.fillMaxSize(),
                        lines = listOf(MapLine(featured.first, StrideColors.MapLine, 5f)),
                        dots = listOf(MapDot(featured.first.first(), StrideColors.Green), MapDot(featured.first.last(), StrideColors.Negative)),
                        interactive = false,
                        fitKey = featured.first.size to filter,
                        fitPaddingPx = 70,
                    )
                    Box(Modifier.padding(14.dp).align(Alignment.BottomStart)) { MapBadge(Icons.Filled.Map, featured.second) }
                }
            }
        }

        Column(Modifier.horizontalScroll(rememberScrollState())) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteFilter.entries.forEach { StrideChip(it.title, filter == it, { filter = it }) }
            }
        }

        when (filter) {
            RouteFilter.Heatmap -> Text(
                "Every route you have recorded, drawn on top of itself. The brighter a road, the more often you have been down it.",
                style = StrideType.BodySecondary.copy(fontSize = 13.sp),
            )
            RouteFilter.Routes -> if (state.routes.isEmpty()) {
                StrideCard {
                    EmptyState(
                        Icons.Filled.Map, "No routes yet",
                        "Build one by tapping points on the map, or save one from any activity you have already recorded.",
                        actionTitle = "Build a route", onAction = { nav.push(Dest.RouteBuilder()) },
                    )
                }
            } else {
                state.routes.forEach { route ->
                    DetailRow(
                        Icons.Filled.Route, route.name,
                        Fmt.distanceWithUnit(route.distance, units) + " · " + Fmt.elevationWithUnit(route.elevationGain, units) + " climb",
                        onClick = { nav.push(Dest.RouteDetail(route.id)) },
                    )
                }
            }
            RouteFilter.Segments, RouteFilter.Starred -> if (pool.isEmpty()) {
                StrideCard {
                    EmptyState(
                        Icons.Filled.Flag,
                        if (filter == RouteFilter.Starred) "Nothing starred" else "No segments yet",
                        if (filter == RouteFilter.Starred) "Star a segment and Stride will race you against your own best time as you pass through it."
                        else "Open any activity, choose \"Create segment\", and drag the handles over the stretch you want to race.",
                    )
                }
            } else {
                pool.forEach { s -> DetailRow(Icons.Filled.Flag, s.name, segmentSubtitle(s), onClick = { nav.push(Dest.SegmentDetail(s.id)) }) }
            }
        }
    }
}
