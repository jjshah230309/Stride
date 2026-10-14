package com.jashith.stride.core.store

import com.jashith.stride.core.analysis.ActivityAnalyzer
import com.jashith.stride.core.analysis.BestEffortFinder
import com.jashith.stride.core.analysis.FitnessCurve
import com.jashith.stride.core.analysis.FitnessPoint
import com.jashith.stride.core.analysis.PowerCurveBuilder
import com.jashith.stride.core.analysis.RouteSignature
import com.jashith.stride.core.analysis.SegmentMatcher
import com.jashith.stride.core.io.AutoBackup
import com.jashith.stride.core.io.Backup
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.model.BestEffort
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.Goal
import com.jashith.stride.core.model.PowerCurvePoint
import com.jashith.stride.core.model.SavedRoute
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SegmentEffort
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Streams
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.model.Totals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Start and end of a stretch of time. Like Foundation's `DateInterval`, both ends are inside it. */
data class DateInterval(val start: Instant, val end: Instant) {
    fun contains(date: Instant): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** Everything the app knows, as one immutable value. */
data class StoreState(
    val loaded: Boolean = false,
    /** Newest first. */
    val activities: List<Activity> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val segmentEfforts: List<SegmentEffort> = emptyList(),
    val routes: List<SavedRoute> = emptyList(),
    val gear: List<Gear> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

data class PersonalRecord(val effort: BestEffort, val activity: Activity)

/**
 * The single source of truth for everything on disk: a port of iOS `AppStore`.
 *
 * State lives in one [StateFlow] of immutable lists, so the UI just collects it. Small edits are plain
 * functions. Edits that scan the whole history (matching a new segment, re-analysing after a profile
 * change) are `suspend` and do their work on [cpuDispatcher]; iOS does the same work on the main thread.
 * Saves are debounced by [debounceMillis] and written on [ioDispatcher]; [flush] writes immediately.
 */
class StrideStore(
    val files: StrideFiles,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 600,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Instant = { Instant.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> get() = _state

    val current: StoreState get() = _state.value

    enum class SaveTarget { Activities, Segments, Efforts, Routes, Gear, Goals, Settings }

    private val pending = AtomicReference<Set<SaveTarget>>(emptySet())
    private var saveJob: Job? = null
    private val writeLock = Any()

    // Lifecycle

    /** Reads every file. Missing files are empty; unreadable ones are set aside by [StrideFiles]. */
    suspend fun load() {
        val loaded = withContext(ioDispatcher) {
            files.prepare()
            StoreState(
                loaded = true,
                activities = (files.load(ListSerializer(Activity.serializer()), StrideFiles.ACTIVITIES) ?: emptyList())
                    .sortedByDescending { it.startDate },
                segments = files.load(ListSerializer(Segment.serializer()), StrideFiles.SEGMENTS) ?: emptyList(),
                segmentEfforts = files.load(ListSerializer(SegmentEffort.serializer()), StrideFiles.SEGMENT_EFFORTS) ?: emptyList(),
                routes = files.load(ListSerializer(SavedRoute.serializer()), StrideFiles.ROUTES) ?: emptyList(),
                gear = files.load(ListSerializer(Gear.serializer()), StrideFiles.GEAR) ?: emptyList(),
                goals = files.load(ListSerializer(Goal.serializer()), StrideFiles.GOALS) ?: emptyList(),
                settings = files.load(AppSettings.serializer(), StrideFiles.SETTINGS) ?: AppSettings(),
            )
        }
        _state.value = loaded
    }

    private fun scheduleSave(target: SaveTarget) {
        pending.updateAndGet { it + target }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMillis)
            withContext(ioDispatcher) { flush() }
        }
    }

    /** Writes whatever is waiting to be saved, now, on the calling thread. */
    fun flush() {
        val targets = pending.getAndSet(emptySet())
        if (targets.isEmpty()) return
        val s = _state.value
        synchronized(writeLock) {
            for (target in targets) {
                when (target) {
                    SaveTarget.Activities -> files.save(ListSerializer(Activity.serializer()), s.activities, StrideFiles.ACTIVITIES)
                    SaveTarget.Segments -> files.save(ListSerializer(Segment.serializer()), s.segments, StrideFiles.SEGMENTS)
                    SaveTarget.Efforts -> files.save(ListSerializer(SegmentEffort.serializer()), s.segmentEfforts, StrideFiles.SEGMENT_EFFORTS)
                    SaveTarget.Routes -> files.save(ListSerializer(SavedRoute.serializer()), s.routes, StrideFiles.ROUTES)
                    SaveTarget.Gear -> files.save(ListSerializer(Gear.serializer()), s.gear, StrideFiles.GEAR)
                    SaveTarget.Goals -> files.save(ListSerializer(Goal.serializer()), s.goals, StrideFiles.GOALS)
                    SaveTarget.Settings -> files.save(AppSettings.serializer(), s.settings, StrideFiles.SETTINGS)
                }
            }
        }
    }

    fun saveEverything() {
        pending.set(SaveTarget.entries.toSet())
        flush()
    }

    private fun edit(target: SaveTarget?, change: (StoreState) -> StoreState) {
        _state.update(change)
        if (target != null) scheduleSave(target)
    }

    // Settings

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        _state.update { it.copy(settings = change(it.settings)) }
        scheduleSave(SaveTarget.Settings)
    }

    // Activities

    private fun sorted(list: List<Activity>) = list.sortedByDescending { it.startDate }

    /** Saves the track, finds any segment efforts in it, and files the activity. Analysis happens before this. */
    suspend fun add(activity: Activity, points: List<TrackPoint>) {
        if (points.isNotEmpty()) withContext(ioDispatcher) { files.saveTrack(points, activity.id) }
        val segments = current.segments
        val efforts = withContext(cpuDispatcher) { SegmentMatcher.findEfforts(activity, activity.id, points, segments) }
        val filed = activity.copy(segmentEffortIDs = efforts.map { it.id })
        _state.update { s ->
            val withEfforts = s.segmentEfforts + efforts
            s.copy(
                segmentEfforts = reRanked(withEfforts, efforts.map { it.segmentID }),
                activities = sorted(s.activities + filed),
            )
        }
        scheduleSave(SaveTarget.Activities)
        scheduleSave(SaveTarget.Efforts)
    }

    fun update(activity: Activity) {
        if (current.activities.none { it.id == activity.id }) return
        edit(SaveTarget.Activities) { s ->
            s.copy(activities = sorted(s.activities.map { if (it.id == activity.id) activity else it }))
        }
    }

    fun delete(activity: Activity) {
        files.deleteTrack(activity.id)
        for (photo in activity.photos) files.deletePhoto(photo.filename)
        _state.update { s ->
            val remaining = s.segmentEfforts.filter { it.activityID != activity.id }
            s.copy(
                activities = s.activities.filter { it.id != activity.id },
                segmentEfforts = reRanked(remaining, s.segments.map { it.id }),
            )
        }
        scheduleSave(SaveTarget.Activities)
        scheduleSave(SaveTarget.Efforts)
    }

    fun track(activity: Activity): List<TrackPoint> = files.loadTrack(activity.id)

    fun activity(id: UUID): Activity? = current.activities.firstOrNull { it.id == id }

    /** Recompute every derived number, used after the athlete profile changes. */
    suspend fun reanalyseAll(progress: ((Double) -> Unit)? = null) {
        val profile = current.settings.profile
        val activities = current.activities
        val total = maxOf(1, activities.size).toDouble()
        val updated = withContext(cpuDispatcher) {
            activities.mapIndexed { i, activity ->
                val points = files.loadTrack(activity.id)
                val result = if (points.size > 1) ActivityAnalyzer.analyse(activity, points, profile) else activity
                progress?.invoke((i + 1) / total)
                result
            }
        }
        _state.update { it.copy(activities = sorted(updated)) }
        rematchAllSegments()
        scheduleSave(SaveTarget.Activities)
    }

    // Queries

    fun activities(interval: DateInterval, sports: List<SportType> = emptyList()): List<Activity> =
        current.activities.filter { interval.contains(it.startDate) && (sports.isEmpty() || sports.contains(it.sport)) }

    fun totals(interval: DateInterval, sports: List<SportType> = emptyList()): Totals =
        Totals.of(activities(interval, sports))

    private val firstDayOfWeek: DayOfWeek
        get() = if (current.settings.display.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY

    fun currentWeek(): DateInterval = weekInterval(clock())

    /** The week containing [date], starting on Monday or Sunday per the preference. */
    fun weekInterval(date: Instant): DateInterval = weekOf(date, firstDayOfWeek)

    private fun weekOf(date: Instant, first: DayOfWeek): DateInterval {
        val z = zone()
        val day = date.atZone(z).toLocalDate()
        val start = day.with(TemporalAdjusters.previousOrSame(first))
        return DateInterval(start.atStartOfDay(z).toInstant(), start.plusWeeks(1).atStartOfDay(z).toInstant())
    }

    /** All-time personal records for the standard race distances. */
    fun personalRecords(sport: SportType): List<PersonalRecord> {
        val best = LinkedHashMap<String, PersonalRecord>()
        for (activity in current.activities) {
            if (!(activity.sport.isRun == sport.isRun && activity.sport.isFoot)) continue
            for (effort in activity.bestEfforts) {
                val existing = best[effort.name]
                if (existing == null || effort.time < existing.effort.time) best[effort.name] = PersonalRecord(effort, activity)
            }
        }
        val order = BestEffortFinder.runDistances.map { it.first }
        return best.values.sortedBy { order.indexOf(it.effort.name).let { i -> if (i < 0) 99 else i } }
    }

    val allTimePowerCurve: List<PowerCurvePoint>
        get() = PowerCurveBuilder.combine(current.activities.filter { it.sport.isRide }.map { it.powerCurve })

    /** Every other activity that followed the same route, quickest first. */
    fun matchedActivities(activity: Activity): List<Activity> =
        current.activities.filter { it.id != activity.id && RouteSignature.similar(it, activity) }.sortedBy { it.movingTime }

    fun fitnessCurve(): List<FitnessPoint> = FitnessCurve.build(current.activities, clock(), zone = zone())

    // Segments

    /** Adds the segment and scans the whole history for efforts on it. */
    suspend fun addSegment(segment: Segment) {
        _state.update { it.copy(segments = it.segments + segment) }
        scheduleSave(SaveTarget.Segments)
        matchSegmentAgainstHistory(segment)
    }

    fun updateSegment(segment: Segment) {
        if (current.segments.none { it.id == segment.id }) return
        edit(SaveTarget.Segments) { s -> s.copy(segments = s.segments.map { if (it.id == segment.id) segment else it }) }
    }

    fun deleteSegment(segment: Segment) {
        _state.update { s ->
            s.copy(
                segments = s.segments.filter { it.id != segment.id },
                segmentEfforts = s.segmentEfforts.filter { it.segmentID != segment.id },
            )
        }
        scheduleSave(SaveTarget.Segments)
        scheduleSave(SaveTarget.Efforts)
    }

    fun efforts(segment: Segment): List<SegmentEffort> =
        current.segmentEfforts.filter { it.segmentID == segment.id }.sortedBy { it.time }

    fun personalBest(segment: Segment): SegmentEffort? = efforts(segment).firstOrNull()

    val starredSegments: List<Segment> get() = current.segments.filter { it.isStarred }

    /** Scan the whole history for a segment that was just created. */
    suspend fun matchSegmentAgainstHistory(segment: Segment) {
        val activities = current.activities
        val found = withContext(cpuDispatcher) { scanHistory(segment, activities) }
        _state.update { s ->
            s.copy(segmentEfforts = s.segmentEfforts.filter { it.segmentID != segment.id } + SegmentMatcher.rank(found))
        }
        scheduleSave(SaveTarget.Efforts)
    }

    private fun scanHistory(segment: Segment, activities: List<Activity>): List<SegmentEffort> {
        val found = ArrayList<SegmentEffort>()
        for (activity in activities) {
            if (!activity.hasGPS) continue
            if (!SegmentMatcher.sportsCompatible(segment.sport, activity.sport)) continue
            val segBox = segment.boundingBox ?: continue
            val actBox = activity.boundingBox ?: continue
            if (!segBox.expanded(150.0).intersects(actBox)) continue
            val points = files.loadTrack(activity.id)
            if (points.size <= 4) continue
            val effort = SegmentMatcher.bestEffort(segment, Streams(points), activity.id, activity.startDate)
            if (effort != null) found.add(effort)
        }
        return found
    }

    suspend fun rematchAllSegments() {
        val segments = current.segments
        val activities = current.activities
        val all = withContext(cpuDispatcher) {
            segments.flatMap { SegmentMatcher.rank(scanHistory(it, activities)) }
        }
        _state.update { it.copy(segmentEfforts = all) }
        scheduleSave(SaveTarget.Efforts)
    }

    private fun reRanked(efforts: List<SegmentEffort>, touchedBy: List<UUID>): List<SegmentEffort> {
        var out = efforts
        for (id in touchedBy.toSet()) {
            val existing = out.filter { it.segmentID == id }
            out = out.filter { it.segmentID != id } + SegmentMatcher.rank(existing)
        }
        return out
    }

    // Routes

    fun addRoute(route: SavedRoute) = edit(SaveTarget.Routes) { it.copy(routes = it.routes + route) }

    fun deleteRoute(route: SavedRoute) = edit(SaveTarget.Routes) { s -> s.copy(routes = s.routes.filter { it.id != route.id }) }

    fun updateRoute(route: SavedRoute) {
        if (current.routes.none { it.id == route.id }) return
        edit(SaveTarget.Routes) { s -> s.copy(routes = s.routes.map { if (it.id == route.id) route else it }) }
    }

    // Gear

    fun addGear(item: Gear) = edit(SaveTarget.Gear) { it.copy(gear = it.gear + item) }

    fun updateGear(item: Gear) {
        if (current.gear.none { it.id == item.id }) return
        edit(SaveTarget.Gear) { s -> s.copy(gear = s.gear.map { if (it.id == item.id) item else it }) }
    }

    fun deleteGear(item: Gear) {
        _state.update { s ->
            s.copy(
                gear = s.gear.filter { it.id != item.id },
                activities = s.activities.map { if (it.gearID == item.id) it.copy(gearID = null) else it },
            )
        }
        scheduleSave(SaveTarget.Gear)
        scheduleSave(SaveTarget.Activities)
    }

    /** Distance already on the gear when tracking started, plus every activity logged against it. */
    fun mileage(item: Gear): Double =
        item.startingDistance + current.activities.filter { it.gearID == item.id }.sumOf { it.distance }

    fun activityCount(item: Gear): Int = current.activities.count { it.gearID == item.id }

    fun defaultGear(sport: SportType): Gear? {
        val kind = sport.gearKind
        val gear = current.gear
        return gear.firstOrNull { it.kind == kind && it.isDefault && !it.isRetired }
            ?: gear.firstOrNull { it.kind == kind && !it.isRetired }
    }

    // Goals

    fun addGoal(goal: Goal) = edit(SaveTarget.Goals) { it.copy(goals = it.goals + goal) }

    fun deleteGoal(goal: Goal) = edit(SaveTarget.Goals) { s -> s.copy(goals = s.goals.filter { it.id != goal.id }) }

    fun updateGoal(goal: Goal) {
        if (current.goals.none { it.id == goal.id }) return
        edit(SaveTarget.Goals) { s -> s.copy(goals = s.goals.map { if (it.id == goal.id) goal else it }) }
    }

    // Backup

    fun backupSnapshot(): Backup.Snapshot = current.let {
        Backup.Snapshot(it.settings, it.activities, it.segments, it.segmentEfforts, it.routes, it.gear, it.goals)
    }

    val activityIDs: Set<UUID> get() = current.activities.map { it.id }.toSet()

    /** Files that were on disk but could not be read. Their bytes were set aside, so nothing is lost yet. */
    val damagedFiles: List<String> get() = files.damagedFiles

    private fun <T> merged(existing: List<T>, incoming: List<T>, id: (T) -> UUID): List<T> {
        val seen = existing.map(id).toMutableSet()
        val out = ArrayList(existing)
        for (item in incoming) if (seen.add(id(item))) out.add(item)
        return out
    }

    fun apply(restored: Backup.Restored, mode: Backup.RestoreMode, restoreSettings: Boolean) {
        val before = current
        when (mode) {
            Backup.RestoreMode.Replace -> {
                // Drop the tracks and photos belonging to activities being discarded.
                val keeping = restored.activities.map { it.id }.toSet()
                for (activity in before.activities) {
                    if (activity.id in keeping) continue
                    files.deleteTrack(activity.id)
                    for (photo in activity.photos) files.deletePhoto(photo.filename)
                }
                _state.update {
                    it.copy(
                        activities = restored.activities, segments = restored.segments,
                        segmentEfforts = restored.segmentEfforts, routes = restored.routes,
                        gear = restored.gear, goals = restored.goals,
                    )
                }
            }
            Backup.RestoreMode.Merge -> _state.update {
                it.copy(
                    activities = merged(it.activities, restored.activities) { a -> a.id },
                    segments = merged(it.segments, restored.segments) { s -> s.id },
                    segmentEfforts = merged(it.segmentEfforts, restored.segmentEfforts) { e -> e.id },
                    routes = merged(it.routes, restored.routes) { r -> r.id },
                    gear = merged(it.gear, restored.gear) { g -> g.id },
                    goals = merged(it.goals, restored.goals) { g -> g.id },
                )
            }
        }
        _state.update { s ->
            s.copy(
                settings = if (restoreSettings) restored.settings else s.settings,
                activities = sorted(s.activities),
            )
        }
        saveEverything()
    }

    /**
     * Runs a scheduled backup if one is due. Safe to call on every launch and every return to the foreground.
     * Returns the new backup, or null if nothing was due or it failed (the failure is passed to [onError]).
     */
    suspend fun runAutomaticBackupIfDue(onError: (Throwable) -> Unit = {}): AutoBackup.Entry? {
        val settings = current.settings
        if (!settings.backup.isDue(clock())) return null
        if (current.activities.isEmpty()) return null
        val snapshot = backupSnapshot()
        val count = current.activities.size
        return try {
            val entry = withContext(ioDispatcher) { AutoBackup.run(snapshot, files, settings.backup.keepCount, clock(), zone()) }
            updateSettings {
                it.copy(backup = it.backup.copy(lastRun = entry.created, lastFilename = entry.filename, lastActivityCount = count))
            }
            flush()
            entry
        } catch (e: Exception) {
            onError(e)
            null
        }
    }

    // Streaks

    /** Consecutive days with at least one activity, counting back from today (or yesterday, if today is empty). */
    fun currentStreak(): Int {
        val z = zone()
        val days = current.activities.map { it.startDate.atZone(z).toLocalDate() }.toSet()
        if (days.isEmpty()) return 0
        var streak = 0
        var day = clock().atZone(z).toLocalDate()
        if (day !in days) {
            day = day.minusDays(1)
            if (day !in days) return 0
        }
        while (day in days) {
            streak += 1
            day = day.minusDays(1)
        }
        return streak
    }

    /**
     * Weeks in a row with at least one activity. Like iOS this follows the device's calendar for where a
     * week starts, not the Monday/Sunday preference.
     */
    fun weekStreak(firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek): Int {
        val z = zone()
        val now = clock()
        var streak = 0
        var reference = now
        while (true) {
            val interval = weekOf(reference, firstDay)
            val count = current.activities.count { interval.contains(it.startDate) }
            if (count == 0) {
                if (streak == 0 && sameWeek(reference, now, firstDay, z)) {
                    // The current week not having started yet should not break the streak.
                    reference = reference.atZone(z).minusWeeks(1).toInstant()
                    continue
                }
                break
            }
            streak += 1
            reference = reference.atZone(z).minusWeeks(1).toInstant()
            if (streak > 520) break
        }
        return streak
    }

    private fun sameWeek(a: Instant, b: Instant, first: DayOfWeek, z: ZoneId): Boolean =
        weekOf(a, first).start == weekOf(b, first).start
}
