package com.jashith.stride.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Totals
import com.jashith.stride.core.store.DateInterval
import com.jashith.stride.files.FileOps
import com.jashith.stride.ui.DateFmt
import com.jashith.stride.ui.Dest
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.ActivityRow
import com.jashith.stride.ui.components.AppTab
import com.jashith.stride.ui.components.AvatarBadge
import com.jashith.stride.ui.components.EmptyState
import com.jashith.stride.ui.components.InkCard
import com.jashith.stride.ui.components.Kicker
import com.jashith.stride.ui.components.MetricCard
import com.jashith.stride.ui.components.Pill
import com.jashith.stride.ui.components.PrimaryButton
import com.jashith.stride.ui.components.SportChoice
import com.jashith.stride.ui.components.SportToggle
import com.jashith.stride.ui.components.StrideCard
import com.jashith.stride.ui.components.StrideProgressBar
import com.jashith.stride.ui.components.TabPage
import com.jashith.stride.ui.components.WeekBars
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(onMessage: (String) -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val state = rememberStoreState()
    val store = graph.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val units = state.settings.units

    var sport by remember { mutableStateOf(if (state.settings.display.defaultSport.isRide) SportChoice.Ride else SportChoice.Run) }
    LaunchedEffect(state.loaded) {
        if (state.loaded) sport = if (state.settings.display.defaultSport.isRide) SportChoice.Ride else SportChoice.Run
    }
    var showSuggestion by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { onMessage(FileOps.importSummary(FileOps.importActivities(context, store, uris))) }
    }

    val zone = ZoneId.systemDefault()
    val streak = remember(state.activities) { store.currentStreak() }
    val fitness = remember(state.activities, state.settings.profile) { store.fitnessCurve() }
    val point = fitness.lastOrNull()
    val form = point?.form ?: 0.0
    val readiness = max(0.0, min(100.0, (form + 45) / 85 * 100)).roundToInt()

    val week = remember(state.activities, state.settings.display.weekStartsMonday) { store.currentWeek() }
    val sports = if (sport == SportChoice.Run) SportType.runGroup else SportType.rideGroup
    val weekTotals = remember(state.activities, sport) { store.totals(week, sports) }
    val weekElevation = remember(state.activities) { store.totals(week).elevationGain }
    val prCounts = remember(state.segmentEfforts) { state.segmentEfforts.filter { it.isPR }.groupingBy { it.activityID }.eachCount() }

    TabPage {
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Kicker(DateFmt.day(Instant.now()))
                Text(greeting(streak), style = StrideType.ScreenTitle)
            }
            AvatarBadge(state.settings.profile.name, onClick = { nav.push(Dest.Profile) })
        }

        // Hero
        InkCard {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SportToggle(sport, { sport = it }, Modifier.weight(1f, fill = false).fillMaxWidth(0.7f), onDark = true)
                    Box(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.AutoAwesome, "Suggestion", tint = StrideColors.Lime,
                        modifier = Modifier.clickable { showSuggestion = true }.padding(6.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This week", style = StrideType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = StrideColors.OnInkSecondary))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(Fmt.distance(weekTotals.distance, units, 1), style = StrideType.figure(52, color = androidx.compose.ui.graphics.Color.White))
                        Text(
                            units.distanceUnit,
                            Modifier.padding(bottom = 8.dp),
                            style = StrideType.Body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = StrideColors.OnInkSecondary),
                        )
                    }
                }
                val days = weekDays(week, state.activities, sports, units.metersPerUnit, state.settings.display.weekStartsMonday, zone)
                WeekBars(days.values, days.todayIndex, days.letters)
                PrimaryButton(if (sport == SportChoice.Run) "Start run" else "Start ride", onClick = {
                    store.updateSettings { s ->
                        s.copy(display = s.display.copy(defaultSport = if (sport == SportChoice.Run) SportType.Run else SportType.Ride))
                    }
                    nav.tab = AppTab.Record
                }, icon = Icons.Filled.PlayArrow)
            }
        }

        // Readiness
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Kicker("Training readiness")
                    Text(readinessLabel(readiness), style = StrideType.Body.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold))
                }
                Text("$readiness", style = StrideType.figure(32, color = StrideColors.Green))
            }
            StrideProgressBar(readiness / 100f, height = 9.dp)
        }

        // Metrics
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(Icons.Filled.FavoriteBorder, "Fitness", Fmt.int(point?.fitness ?: 0.0), Modifier.weight(1f))
            MetricCard(Icons.Filled.Speed, "Fatigue", Fmt.int(point?.fatigue ?: 0.0), Modifier.weight(1f))
            MetricCard(Icons.Filled.Landscape, "Elevation", Fmt.elevation(weekElevation, units), Modifier.weight(1f), unit = units.elevationUnit)
        }

        // Recent
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Recent", Modifier.weight(1f))
                Box {
                    Icon(
                        Icons.Filled.Add, "Add or import", tint = StrideColors.Green,
                        modifier = Modifier.clickable { menuOpen = true }.padding(6.dp),
                    )
                    DropdownMenu(menuOpen, { menuOpen = false }, containerColor = StrideColors.Surface) {
                        DropdownMenuItem(
                            text = { Text("Import activity file") },
                            leadingIcon = { Icon(Icons.Filled.Download, null) },
                            onClick = { menuOpen = false; importLauncher.launch(com.jashith.stride.core.io.ActivityFile.readableMimeTypes) },
                        )
                        DropdownMenuItem(
                            text = { Text("Add manual activity") },
                            leadingIcon = { Icon(Icons.Filled.AddCircleOutline, null) },
                            onClick = { menuOpen = false; nav.push(Dest.ManualActivity) },
                        )
                    }
                }
            }
            val recent = state.activities.take(8)
            if (recent.isEmpty()) {
                StrideCard {
                    EmptyState(
                        com.jashith.stride.ui.Icons.Run, "Nothing recorded yet",
                        "Tap Record to start your first run or ride, or import a GPX file from another app.",
                    )
                }
            } else {
                recent.forEach { activity ->
                    ActivityRow(activity, units, prCounts[activity.id] ?: 0, onClick = { nav.push(Dest.ActivityDetail(activity.id)) })
                }
            }
        }
    }

    if (showSuggestion) {
        ModalBottomSheet(onDismissRequest = { showSuggestion = false }, containerColor = StrideColors.PageBackground) {
            SuggestionContent(readiness, form) { showSuggestion = false }
        }
    }
}

private fun greeting(streak: Int): String {
    if (streak >= 3) return "$streak days strong"
    return when (java.time.LocalTime.now().hour) {
        in 4..10 -> "Ready to move?"
        in 11..16 -> "Time for a session?"
        in 17..21 -> "Evening effort?"
        else -> "Ready to move?"
    }
}

private fun readinessLabel(score: Int) = when {
    score >= 80 -> "Fully recovered"
    score >= 60 -> "Balanced load"
    score >= 40 -> "Building fitness"
    score >= 20 -> "Carrying fatigue"
    else -> "Time to back off"
}

private class WeekDays(val values: List<Double>, val letters: List<String>, val todayIndex: Int)

private fun weekDays(
    week: DateInterval,
    activities: List<com.jashith.stride.core.model.Activity>,
    sports: List<SportType>,
    metersPerUnit: Double,
    mondayStart: Boolean,
    zone: ZoneId,
): WeekDays {
    val start = week.start.atZone(zone).toLocalDate()
    val letters = if (mondayStart) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("S", "M", "T", "W", "T", "F", "S")
    val today = LocalDate.now(zone)
    var todayIndex = 0
    val values = (0 until 7).map { offset ->
        val day = start.plusDays(offset.toLong())
        if (day == today) todayIndex = offset
        activities.filter { it.sport in sports && it.startDate.atZone(zone).toLocalDate() == day }.sumOf { it.distance } / metersPerUnit
    }
    return WeekDays(values, letters, todayIndex)
}

@Composable
private fun SuggestionContent(readiness: Int, form: Double, onClose: () -> Unit) {
    val (title, kind, body) = suggestion(readiness)
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker("Suggested today", Modifier.weight(1f))
            Icon(Icons.Filled.Close, "Close", tint = StrideColors.SecondaryText, modifier = Modifier.clickable(onClick = onClose).padding(4.dp))
        }
        Text(title, style = StrideType.Body.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold))
        Pill(kind)
        Text(body, style = StrideType.Body.copy(fontSize = 15.sp, color = StrideColors.SecondaryText))
        Text(
            "Based on your form of ${Fmt.signed(form)} — fitness minus fatigue. It is a guide, not a rule; how your legs actually feel wins.",
            style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.TertiaryText),
        )
    }
}

private fun suggestion(readiness: Int): Triple<String, String, String> = when {
    readiness >= 80 -> Triple(
        "Go hard", "Intervals or a time trial",
        "You are rested and the fatigue has cleared. This is the day to chase a personal record or run a hard session — 6 × 3 minutes at threshold, or a segment you have been circling.",
    )
    readiness >= 60 -> Triple(
        "Steady quality", "Tempo or a progression",
        "Balanced load. A controlled tempo effort fits well — 20 to 30 minutes at a pace you could hold for an hour, then an easy few minutes to finish.",
    )
    readiness >= 40 -> Triple(
        "Keep building", "Easy aerobic volume",
        "You are absorbing a solid block. Stay conversational, keep the heart rate in zone 2, and let the fitness catch up with the work.",
    )
    readiness >= 20 -> Triple(
        "Ease off", "Short and easy",
        "Fatigue is running ahead of fitness. Thirty to forty minutes easy, or a walk. The gains from last week land while you recover, not while you push.",
    )
    else -> Triple(
        "Rest", "Take the day",
        "You are deep in the hole. A rest day now protects the block you have just done — training through this is how niggles turn into injuries.",
    )
}
