package com.jashith.stride.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.analysis.FitnessCurve
import com.jashith.stride.core.analysis.FitnessPoint
import com.jashith.stride.core.analysis.PowerCurveBuilder
import com.jashith.stride.core.analysis.RacePredictor
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.BestEffort
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.Goal
import com.jashith.stride.core.model.GoalMetric
import com.jashith.stride.core.model.GoalPeriod
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Totals
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.store.DateInterval
import com.jashith.stride.ui.DateFmt
import com.jashith.stride.ui.Dest
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.BarChart
import com.jashith.stride.ui.components.ChartSeries
import com.jashith.stride.ui.components.DayVolume
import com.jashith.stride.ui.components.EmptyState
import com.jashith.stride.ui.components.GhostMetricCard
import com.jashith.stride.ui.components.InkCard
import com.jashith.stride.ui.components.Kicker
import com.jashith.stride.ui.components.MultiLineChart
import com.jashith.stride.ui.components.PowerCurveChart
import com.jashith.stride.ui.components.ScreenHeader
import com.jashith.stride.ui.components.SectionHeader
import com.jashith.stride.ui.components.SegmentedPicker
import com.jashith.stride.ui.components.StatTile
import com.jashith.stride.ui.components.StrideCard
import com.jashith.stride.ui.components.StrideChip
import com.jashith.stride.ui.components.StrideProgressBar
import com.jashith.stride.ui.components.SecondaryButton
import com.jashith.stride.ui.components.FormSection
import com.jashith.stride.ui.components.NumberFieldRow
import com.jashith.stride.ui.components.PickerRow
import com.jashith.stride.ui.components.TabPage
import com.jashith.stride.ui.components.TrainingLogGrid
import com.jashith.stride.ui.components.TrendBars
import com.jashith.stride.ui.components.sportAccent
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Section(val title: String) { Fitness("Fitness"), Log("Log"), Goals("Goals"), Records("Records") }

@Composable
fun ProgressScreen() {
    val graph = LocalGraph.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    var section by remember { mutableStateOf(Section.Fitness) }

    val curve = remember(state.activities, state.settings.profile) { store.fitnessCurve() }
    val today = curve.lastOrNull()
    val fitnessChange = if (curve.size > 30 && today != null) today.fitness - curve[curve.size - 31].fitness else 0.0
    val weekLoad = remember(state.activities, state.settings.display.weekStartsMonday) { store.totals(store.currentWeek()).relativeEffort }

    val weeklyLoads = remember(state.activities, state.settings.display.weekStartsMonday) {
        val out = ArrayList<Double>()
        var reference = Instant.now()
        repeat(8) {
            val interval = store.weekInterval(reference)
            out += store.totals(interval).relativeEffort
            reference = interval.start.minusSeconds(86400)
        }
        out.reversed()
    }

    val verdict = remember(weeklyLoads) {
        val previous = weeklyLoads.dropLast(1).takeLast(4).filter { it > 0 }
        if (previous.isEmpty()) "Getting started"
        else {
            val ratio = weekLoad / (previous.sum() / previous.size)
            when {
                ratio < 0.6 -> "Recovery week"
                ratio < 0.85 -> "Easing off"
                ratio < 1.25 -> "Productive"
                ratio < 1.6 -> "Ramping up"
                else -> "Ramping fast"
            }
        }
    }

    val records = remember(state.activities) { store.personalRecords(SportType.Run) }
    val fiveK = records.firstOrNull { it.effort.name == "5 km" } ?: records.lastOrNull()

    TabPage {
        ScreenHeader("Your training", "Progress")

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostMetricCard(Icons.Filled.FavoriteBorder, "Fitness", Fmt.int(today?.fitness ?: 0.0), "${Fmt.signed(fitnessChange)} this month", Modifier.weight(1f))
                GhostMetricCard(Icons.Filled.AutoAwesome, "Freshness", Fmt.int(today?.form ?: 0.0), FitnessCurve.formDescription(today?.form ?: 0.0).first, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostMetricCard(Icons.Filled.Speed, "Weekly load", Fmt.int(weekLoad), verdict, Modifier.weight(1f))
                GhostMetricCard(
                    Icons.Filled.EmojiEvents, "Best 5K",
                    fiveK?.let { Fmt.duration(it.effort.time) } ?: "--",
                    fiveK?.let { DateFmt.shortDay(it.activity.startDate) } ?: "No efforts yet", Modifier.weight(1f),
                )
            }
        }

        // Trend
        InkCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Kicker("8-week trend", color = StrideColors.OnInkSecondary)
                        Text(trendHeadline(weeklyLoads), style = StrideType.Body.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White))
                    }
                    Icon(Icons.Filled.Timer, null, tint = StrideColors.Lime, modifier = Modifier.size(20.dp))
                }
                TrendBars(weeklyLoads)
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Section.entries.forEach { StrideChip(it.title, section == it, { section = it }) }
        }

        when (section) {
            Section.Fitness -> FitnessSection(curve)
            Section.Log -> TrainingLogSection()
            Section.Goals -> GoalsSection()
            Section.Records -> RecordsSection()
        }
    }
}

private fun trendHeadline(loads: List<Double>): String {
    if (loads.size < 4) return "Just getting going"
    val recent = loads.takeLast(4).sum() / 4
    val earlier = loads.take(4).sum() / 4
    if (earlier < 1) return "Building from scratch"
    val ratio = recent / earlier
    return when {
        ratio < 0.75 -> "Winding down"
        ratio < 0.95 -> "Holding steady"
        ratio < 1.3 -> "Building consistently"
        else -> "Ramping hard"
    }
}

@Composable
private fun FitnessSection(full: List<FitnessPoint>) {
    val graph = LocalGraph.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    var range by remember { mutableStateOf(90) }
    val curve = full.takeLast(range)
    val today = curve.lastOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fitness & Freshness", Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    SegmentedPicker(listOf(42, 90, 180, 365), range, { when (it) { 42 -> "6w"; 90 -> "3m"; 180 -> "6m"; else -> "1y" } }, { range = it }, Modifier.width(190.dp))
                }
                if (curve.size > 2) {
                    MultiLineChart(
                        listOf(
                            ChartSeries(curve.map { it.fitness }, StrideColors.FitnessColor, fill = true, lineWidth = 2.5f),
                            ChartSeries(curve.map { it.fatigue }, StrideColors.FatigueColor, lineWidth = 1.5f),
                        ),
                        Modifier.height(150.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Legend(StrideColors.FitnessColor, "Fitness")
                        Legend(StrideColors.FatigueColor, "Fatigue")
                    }
                } else {
                    Text("Record a few more activities and this chart will fill in.", style = StrideType.BodySecondary.copy(fontSize = 13.sp))
                }
            }
        }

        if (today != null) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row {
                        StatTile(Fmt.int(today.fitness), "Fitness", Modifier.weight(1f), size = 24.sp, valueColor = StrideColors.FitnessColor)
                        StatTile(Fmt.int(today.fatigue), "Fatigue", Modifier.weight(1f), size = 24.sp, valueColor = StrideColors.FatigueColor)
                        StatTile(Fmt.signed(today.form), "Form", Modifier.weight(1f), size = 24.sp, valueColor = if (today.form >= 0) StrideColors.Positive else StrideColors.Warning)
                    }
                    com.jashith.stride.ui.components.Hairline()
                    val (headline, detail) = FitnessCurve.formDescription(today.form)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(headline, style = StrideType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                        Text(detail, style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.SecondaryText))
                    }
                }
            }
        }

        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Form over time")
                if (curve.size > 2) {
                    MultiLineChart(listOf(ChartSeries(curve.map { it.form }, StrideColors.FormColor, fill = true, lineWidth = 2f)), Modifier.height(100.dp), zeroLine = true)
                }
                Text(
                    "Fitness is a 42-day rolling average of training load, fatigue is a 7-day one. Form is the difference: positive means rested, negative means you are carrying a hard block.",
                    style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText),
                )
            }
        }

        val weeks = remember(state.activities, state.settings.display.weekStartsMonday) {
            val out = ArrayList<Pair<String, Double>>()
            var reference = Instant.now()
            repeat(8) {
                val interval = store.weekInterval(reference)
                out += DateFmt.shortDay(interval.start) to store.totals(interval).relativeEffort
                reference = interval.start.minusSeconds(86400)
            }
            out.reversed()
        }
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Weekly effort")
                BarChart(weeks.map { it.second }, Modifier.height(100.dp), labels = weeks.map { it.first })
                Text("Relative Effort per week. Ramping this up by more than about 10% a week is where injuries come from.", style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText))
            }
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText))
    }
}

// Training log

private enum class LogMetric(val title: String) { Distance("Distance"), Time("Time"), Effort("Effort") }

@Composable
private fun TrainingLogSection() {
    val nav = LocalNavigator.current
    val state = rememberStoreState()
    val units = state.settings.units
    val zone = ZoneId.systemDefault()
    var metric by remember { mutableStateOf(LogMetric.Distance) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val monday = state.settings.display.weekStartsMonday
    val first = if (monday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val today = LocalDate.now(zone)

    val days = remember(state.activities, metric, monday) {
        val start = today.minusDays(181)
        val weekStart = start.with(TemporalAdjusters.previousOrSame(first))
        val byDay = HashMap<LocalDate, Triple<Double, Int, SportType>>()
        for (a in state.activities) {
            val day = a.startDate.atZone(zone).toLocalDate()
            if (day.isBefore(weekStart)) continue
            val value = when (metric) {
                LogMetric.Distance -> a.distance / units.metersPerUnit
                LogMetric.Time -> a.movingTime / 3600
                LogMetric.Effort -> a.relativeEffort
            }
            val existing = byDay[day]
            byDay[day] = Triple((existing?.first ?: 0.0) + value, (existing?.second ?: 0) + 1, a.sport)
        }
        generateSequence(weekStart) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
            val e = byDay[d]
            DayVolume(d, e?.first ?: 0.0, e?.third?.let { sportAccent(it) } ?: StrideColors.Accent, e?.second ?: 0)
        }.toList()
    }
    val weeks = days.chunked(7)
    val letters = if (monday) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("S", "M", "T", "W", "T", "F", "S")

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedPicker(LogMetric.entries, metric, { it.title }, { metric = it })
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    letters.forEach {
                        Text(it, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.TertiaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                    }
                }
                TrainingLogGrid(weeks, days.maxOfOrNull { it.value } ?: 1.0, today, onSelect = { selected = it })
                Text("Last 26 weeks. Tap a day to see what you did.", style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText))
            }
        }
        selected?.let { day ->
            val list = state.activities.filter { it.startDate.atZone(zone).toLocalDate() == day }
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(DateFmt.day(day))
                    if (list.isEmpty()) Text("Rest day.", style = StrideType.BodySecondary.copy(fontSize = 13.sp))
                    list.forEach { a ->
                        Row(Modifier.fillMaxWidth().clickable { nav.push(Dest.ActivityDetail(a.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(com.jashith.stride.ui.Icons.forSport(a.sport), null, tint = sportAccent(a.sport), modifier = Modifier.size(18.dp))
                            Text(a.name, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp), maxLines = 1)
                            Text(Fmt.distanceWithUnit(a.distance, units), style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }

        val months = remember(state.activities) {
            (0 until 6).map { offset ->
                val m = today.minusMonths(offset.toLong()).withDayOfMonth(1)
                val from = m.atStartOfDay(zone).toInstant()
                val to = m.plusMonths(1).atStartOfDay(zone).toInstant()
                DateFmt.month(m) to Totals.of(state.activities.filter { !it.startDate.isBefore(from) && it.startDate.isBefore(to) })
            }
        }
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Monthly totals")
                months.forEach { (name, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp))
                        Text("${t.count}", Modifier.width(26.dp), style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.SecondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.End))
                        Text(Fmt.durationCompact(t.movingTime), Modifier.width(60.dp), style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.SecondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.End))
                        Text(Fmt.distanceWithUnit(t.distance, units, 1), Modifier.width(84.dp), style = StrideType.figure(13, FontWeight.SemiBold).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
                    }
                }
            }
        }
    }
}

// Goals

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalsSection() {
    val graph = LocalGraph.current
    val store = graph.store
    val state = rememberStoreState()
    var editor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Goal?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.goals.isEmpty()) {
            StrideCard {
                EmptyState(Icons.Filled.TrackChanges, "No goals set", "Set a weekly, monthly or yearly target for distance, time, elevation or number of activities.", actionTitle = "Add a goal", onAction = { editor = true })
            }
        } else {
            state.goals.forEach { goal ->
                StrideCard(onClick = { deleting = goal }) { GoalProgressRow(goal, state.activities, state.settings.units, compact = false) }
            }
            SecondaryButton("Add a goal", { editor = true })
            Text("Tap a goal to delete it.", style = StrideType.Footnote)
        }
        StrideCard {
            Row {
                StatTile("${store.currentStreak()}", "Day streak", Modifier.weight(1f), size = 24.sp)
                StatTile("${store.weekStreak()}", "Week streak", Modifier.weight(1f), size = 24.sp)
                StatTile("${state.activities.size}", "All activities", Modifier.weight(1f), size = 24.sp)
            }
        }
    }

    if (editor) {
        ModalBottomSheet(onDismissRequest = { editor = false }, containerColor = StrideColors.PageBackground) {
            GoalEditor(state.settings.units, onAdd = { store.addGoal(it); editor = false })
        }
    }
    deleting?.let { goal ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this goal?") },
            text = { Text(goal.title) },
            confirmButton = { TextButton(onClick = { store.deleteGoal(goal); deleting = null }) { Text("Delete", color = StrideColors.Negative) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun GoalProgressRow(goal: Goal, activities: List<Activity>, units: UnitSystem, compact: Boolean) {
    val progress = goal.progress(activities)
    val fraction = if (goal.target > 0) progress / goal.target else 0.0
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val today = now.atZone(zone).toLocalDate()
    val first = remember { deviceFirstDayOfWeek() }
    val start = when (goal.period) {
        GoalPeriod.Weekly -> today.with(TemporalAdjusters.previousOrSame(first))
        GoalPeriod.Monthly -> today.withDayOfMonth(1)
        GoalPeriod.Yearly -> today.withDayOfYear(1)
    }
    val end = when (goal.period) {
        GoalPeriod.Weekly -> start.plusWeeks(1)
        GoalPeriod.Monthly -> start.plusMonths(1)
        GoalPeriod.Yearly -> start.plusYears(1)
    }
    val from = start.atStartOfDay(zone).toInstant()
    val total = end.atStartOfDay(zone).toInstant().epochSecond - from.epochSecond
    val expected = if (total > 0) ((now.epochSecond - from.epochSecond).toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    val ahead = fraction >= expected

    fun text(v: Double) = when (goal.metric) {
        GoalMetric.Distance -> Fmt.distanceWithUnit(v, units, 1)
        GoalMetric.Time -> Fmt.durationCompact(v)
        GoalMetric.Elevation -> Fmt.elevationWithUnit(v, units)
        GoalMetric.Activities -> "${v.toInt()}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(goal.title, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text(
                Fmt.percent(min(1.0, fraction)),
                style = StrideType.Body.copy(
                    fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold,
                    color = if (fraction >= 1) StrideColors.Positive else if (ahead) StrideColors.Green else StrideColors.SecondaryText,
                ),
            )
        }
        StrideProgressBar(fraction.toFloat(), color = if (fraction >= 1) StrideColors.Positive else StrideColors.Green, height = if (compact) 6.dp else 9.dp)
        Row {
            Text(text(progress) + " of " + text(goal.target), Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText))
            if (!compact) Text(if (ahead) "On track" else "Behind pace", style = StrideType.Body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (ahead) StrideColors.Positive else StrideColors.Warning))
        }
    }
}

@Composable
private fun GoalEditor(units: UnitSystem, onAdd: (Goal) -> Unit) {
    var metric by remember { mutableStateOf(GoalMetric.Distance) }
    var period by remember { mutableStateOf(GoalPeriod.Weekly) }
    var sports by remember { mutableStateOf(setOf<SportType>()) }
    var target by remember { mutableStateOf(30.0) }
    val label = when (metric) {
        GoalMetric.Distance -> units.distanceUnit
        GoalMetric.Time -> "hours"
        GoalMetric.Elevation -> units.elevationUnit
        GoalMetric.Activities -> "activities"
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New goal", Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
            TextButton(enabled = target > 0, onClick = {
                val value = when (metric) {
                    GoalMetric.Distance -> target * units.metersPerUnit
                    GoalMetric.Time -> target * 3600
                    GoalMetric.Elevation -> target * units.metersPerElevationUnit
                    GoalMetric.Activities -> target
                }
                onAdd(Goal(metric = metric, period = period, sports = sports.toList(), target = value))
            }) { Text("Add", color = StrideColors.Green, fontWeight = FontWeight.SemiBold) }
        }
        FormSection(header = "Goal") {
            PickerRow("Metric", GoalMetric.entries, metric, { it.displayName }, { metric = it })
            PickerRow("Period", GoalPeriod.entries, period, { it.displayName }, { period = it }, divider = false)
        }
        FormSection(header = "Target") { NumberFieldRow("Target", target, { target = it }, decimals = 1, suffix = label, divider = false) }
        FormSection(header = "Sports", footer = if (sports.isEmpty()) "Nothing selected means every sport counts." else null) {
            SportType.entries.forEachIndexed { i, sport ->
                Row(
                    Modifier.fillMaxWidth().clickable { sports = if (sport in sports) sports - sport else sports + sport }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(com.jashith.stride.ui.Icons.forSport(sport), null, tint = StrideColors.Green, modifier = Modifier.size(20.dp))
                    Text(sport.displayName, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 15.sp))
                    if (sport in sports) Icon(Icons.Filled.Check, null, tint = StrideColors.Green)
                }
            }
        }
    }
}

// Records

@Composable
private fun RecordsSection() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    val zone = ZoneId.systemDefault()
    val records = remember(state.activities) { store.personalRecords(SportType.Run) }
    val curve = remember(state.activities) { store.allTimePowerCurve }

    val today = LocalDate.now(zone)
    val yearStart = today.withDayOfYear(1).atStartOfDay(zone).toInstant()
    val year = Totals.of(state.activities.filter { !it.startDate.isBefore(yearStart) })
    val all = Totals.of(state.activities)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionHeader("This year")
                Row {
                    StatTile(Fmt.distance(year.distance, units, 0), "Distance", Modifier.weight(1f), unit = units.distanceUnit, size = 22.sp)
                    StatTile(Fmt.durationCompact(year.movingTime), "Time", Modifier.weight(1f), size = 22.sp)
                    StatTile(Fmt.elevation(year.elevationGain, units), "Elevation", Modifier.weight(1f), unit = units.elevationUnit, size = 22.sp)
                }
                com.jashith.stride.ui.components.Hairline()
                SectionHeader("All time")
                Row {
                    StatTile(Fmt.distance(all.distance, units, 0), "Distance", Modifier.weight(1f), unit = units.distanceUnit, size = 22.sp)
                    StatTile(Fmt.durationCompact(all.movingTime), "Time", Modifier.weight(1f), size = 22.sp)
                    StatTile("${all.count}", "Activities", Modifier.weight(1f), size = 22.sp)
                }
            }
        }

        if (records.isNotEmpty()) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Personal records")
                    records.forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable { nav.push(Dest.ActivityDetail(r.activity.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.effort.name, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                            Text(DateFmt.shortDay(r.activity.startDate), style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText))
                            Text(Fmt.duration(r.effort.time), Modifier.width(76.dp), style = StrideType.figure(15, FontWeight.Bold).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
                        }
                    }
                }
            }
            RacePredictionCard(records.map { it.effort })
        }

        if (curve.isNotEmpty()) {
            StrideCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Power curve")
                    PowerCurveChart(curve, Modifier.height(130.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("5s", "1m", "20m", "1h+").forEach { Text(it, style = StrideType.Body.copy(fontSize = 10.sp, color = StrideColors.SecondaryText)) }
                    }
                    val ftp = PowerCurveBuilder.estimateFTP(curve)
                    if (ftp != null) {
                        com.jashith.stride.ui.components.Hairline()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Estimated FTP", Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp))
                            Text("${ftp.toInt()} W", style = StrideType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
                            Text(
                                "Use",
                                Modifier.clickable { store.updateSettings { s -> s.copy(profile = s.profile.copy(powerZones = s.profile.powerZones.copy(ftp = ftp))) } }.padding(6.dp),
                                style = StrideType.Body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.Green),
                            )
                        }
                        Text(
                            "Estimated from your best twenty-minute effort. Power on rides without a meter is modelled from speed, gradient and your weight.",
                            style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText),
                        )
                    }
                }
            }
        }
    }
}

/** Riegel predictions from your single best long effort. */
@Composable
private fun RacePredictionCard(efforts: List<BestEffort>) {
    // Prefer a long effort: those predict better than a fast 400.
    val anchor = efforts.filter { it.distance >= 3000 }.maxByOrNull { it.distance } ?: efforts.firstOrNull()
    StrideCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Race predictions")
            if (anchor != null) {
                Text("Based on your ${anchor.name} in ${Fmt.duration(anchor.time)}", style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText))
                RacePredictor.standardRaces.forEach { (name, distance) ->
                    Row {
                        Text(name, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp))
                        Text(Fmt.duration(RacePredictor.predict(anchor.distance, anchor.time, distance)), style = StrideType.figure(14, FontWeight.SemiBold))
                    }
                }
                Text(
                    "Riegel's formula, which assumes you have trained for the distance. Treat the marathon number with suspicion unless you have done the long runs.",
                    style = StrideType.Body.copy(fontSize = 10.sp, color = StrideColors.TertiaryText),
                )
            } else {
                Text("Record a timed effort of a kilometre or more to see predictions.", style = StrideType.BodySecondary.copy(fontSize = 13.sp))
            }
        }
    }
}

/** Read outside composition: it only changes if the user changes the device locale, which restarts the activity anyway. */
private fun deviceFirstDayOfWeek(): DayOfWeek = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
