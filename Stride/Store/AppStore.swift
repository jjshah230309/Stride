import Foundation
import SwiftUI
import Combine

/// The single source of truth for everything on disk.
final class AppStore: ObservableObject {

    @Published var activities: [Activity] = []
    @Published var segments: [Segment] = []
    @Published var segmentEfforts: [SegmentEffort] = []
    @Published var routes: [SavedRoute] = []
    @Published var gear: [Gear] = []
    @Published var goals: [Goal] = []
    @Published var settings: AppSettings = AppSettings() {
        didSet { scheduleSave(.settings) }
    }

    private var pendingSaves: Set<SaveTarget> = []
    private var saveTimer: Timer?

    enum SaveTarget: Hashable {
        case activities, segments, efforts, routes, gear, goals, settings
    }

    // MARK: - Lifecycle

    init(loadFromDisk: Bool = true) {
        Store.prepare()
        guard loadFromDisk else { return }
        activities = Store.load([Activity].self, from: "activities.json") ?? []
        segments = Store.load([Segment].self, from: "segments.json") ?? []
        segmentEfforts = Store.load([SegmentEffort].self, from: "segment-efforts.json") ?? []
        routes = Store.load([SavedRoute].self, from: "routes.json") ?? []
        gear = Store.load([Gear].self, from: "gear.json") ?? []
        goals = Store.load([Goal].self, from: "goals.json") ?? []
        settings = Store.load(AppSettings.self, from: "settings.json") ?? AppSettings()
        activities.sort { $0.startDate > $1.startDate }
    }

    private func scheduleSave(_ target: SaveTarget) {
        pendingSaves.insert(target)
        saveTimer?.invalidate()
        saveTimer = Timer.scheduledTimer(withTimeInterval: 0.6, repeats: false) { [weak self] _ in
            self?.flush()
        }
    }

    func flush() {
        let targets = pendingSaves
        pendingSaves.removeAll()
        for target in targets {
            switch target {
            case .activities: Store.save(activities, to: "activities.json")
            case .segments: Store.save(segments, to: "segments.json")
            case .efforts: Store.save(segmentEfforts, to: "segment-efforts.json")
            case .routes: Store.save(routes, to: "routes.json")
            case .gear: Store.save(gear, to: "gear.json")
            case .goals: Store.save(goals, to: "goals.json")
            case .settings: Store.save(settings, to: "settings.json")
            }
        }
    }

    func saveEverything() {
        pendingSaves = [.activities, .segments, .efforts, .routes, .gear, .goals, .settings]
        flush()
    }

    // MARK: - Activities

    func add(_ activity: Activity, points: [TrackPoint]) {
        var a = activity
        if !points.isEmpty {
            Store.saveTrack(points, for: a.id)
        }
        let efforts = SegmentMatcher.findEfforts(activity: a, activityID: a.id,
                                                 points: points, segments: segments)
        a.segmentEffortIDs = efforts.map(\.id)
        segmentEfforts.append(contentsOf: efforts)
        reRankSegments(touchedBy: efforts.map(\.segmentID))

        activities.append(a)
        activities.sort { $0.startDate > $1.startDate }
        scheduleSave(.activities)
        scheduleSave(.efforts)
        recomputeGearMileage()
    }

    func update(_ activity: Activity) {
        guard let idx = activities.firstIndex(where: { $0.id == activity.id }) else { return }
        activities[idx] = activity
        activities.sort { $0.startDate > $1.startDate }
        scheduleSave(.activities)
        recomputeGearMileage()
    }

    func delete(_ activity: Activity) {
        activities.removeAll { $0.id == activity.id }
        segmentEfforts.removeAll { $0.activityID == activity.id }
        Store.deleteTrack(activity.id)
        for photo in activity.photos { Store.deletePhoto(photo.filename) }
        reRankSegments(touchedBy: segments.map(\.id))
        scheduleSave(.activities)
        scheduleSave(.efforts)
        recomputeGearMileage()
    }

    func track(for activity: Activity) -> [TrackPoint] {
        Store.loadTrack(activity.id)
    }

    /// Recompute every derived number, used after the athlete profile changes.
    func reanalyseAll(progress: ((Double) -> Void)? = nil) {
        let profile = settings.profile
        let total = Double(Swift.max(1, activities.count))
        var updated: [Activity] = []
        for (i, activity) in activities.enumerated() {
            let points = Store.loadTrack(activity.id)
            if points.count > 1 {
                updated.append(ActivityAnalyzer.analyse(activity: activity, points: points, profile: profile))
            } else {
                updated.append(activity)
            }
            progress?(Double(i + 1) / total)
        }
        activities = updated.sorted { $0.startDate > $1.startDate }
        rematchAllSegments()
        scheduleSave(.activities)
    }

    // MARK: - Queries

    func activities(in interval: DateInterval, sports: [SportType] = []) -> [Activity] {
        activities.filter {
            interval.contains($0.startDate) && (sports.isEmpty || sports.contains($0.sport))
        }
    }

    func totals(for interval: DateInterval, sports: [SportType] = []) -> Totals {
        Totals.of(activities(in: interval, sports: sports))
    }

    var currentWeek: DateInterval {
        var cal = Calendar.current
        cal.firstWeekday = settings.display.weekStartsMonday ? 2 : 1
        return cal.dateInterval(of: .weekOfYear, for: Date())
            ?? DateInterval(start: Date(), duration: 604800)
    }

    func weekInterval(containing date: Date) -> DateInterval {
        var cal = Calendar.current
        cal.firstWeekday = settings.display.weekStartsMonday ? 2 : 1
        return cal.dateInterval(of: .weekOfYear, for: date) ?? DateInterval(start: date, duration: 604800)
    }

    /// All-time personal records for the standard race distances.
    func personalRecords(sport: SportType) -> [(BestEffort, Activity)] {
        var best: [String: (BestEffort, Activity)] = [:]
        for activity in activities where activity.sport.isRun == sport.isRun && activity.sport.isFoot {
            for effort in activity.bestEfforts {
                if let existing = best[effort.name] {
                    if effort.time < existing.0.time { best[effort.name] = (effort, activity) }
                } else {
                    best[effort.name] = (effort, activity)
                }
            }
        }
        let order = BestEffortFinder.runDistances.map(\.0)
        return best.values.sorted { a, b in
            (order.firstIndex(of: a.0.name) ?? 99) < (order.firstIndex(of: b.0.name) ?? 99)
        }
    }

    var allTimePowerCurve: [PowerCurvePoint] {
        PowerCurveBuilder.combine(activities.filter { $0.sport.isRide }.map(\.powerCurve))
    }

    /// Every other activity that followed the same route.
    func matchedActivities(for activity: Activity) -> [Activity] {
        activities.filter { $0.id != activity.id && RouteSignature.similar($0, activity) }
            .sorted { $0.movingTime < $1.movingTime }
    }

    var fitnessCurve: [FitnessPoint] {
        FitnessCurve.build(activities: activities)
    }

    // MARK: - Segments

    func addSegment(_ segment: Segment) {
        segments.append(segment)
        scheduleSave(.segments)
        matchSegmentAgainstHistory(segment)
    }

    func updateSegment(_ segment: Segment) {
        guard let idx = segments.firstIndex(where: { $0.id == segment.id }) else { return }
        segments[idx] = segment
        scheduleSave(.segments)
    }

    func deleteSegment(_ segment: Segment) {
        segments.removeAll { $0.id == segment.id }
        segmentEfforts.removeAll { $0.segmentID == segment.id }
        scheduleSave(.segments)
        scheduleSave(.efforts)
    }

    func efforts(for segment: Segment) -> [SegmentEffort] {
        segmentEfforts.filter { $0.segmentID == segment.id }.sorted { $0.time < $1.time }
    }

    func personalBest(for segment: Segment) -> SegmentEffort? {
        efforts(for: segment).first
    }

    func activity(with id: UUID) -> Activity? {
        activities.first { $0.id == id }
    }

    /// Scan the whole history for a segment that was just created.
    func matchSegmentAgainstHistory(_ segment: Segment) {
        var found: [SegmentEffort] = []
        for activity in activities where activity.hasGPS {
            guard SegmentMatcher.sportsCompatible(segment.sport, activity.sport) else { continue }
            guard let segBox = segment.boundingBox,
                  let actBox = activity.boundingBox,
                  segBox.expanded(byMeters: 150).intersects(actBox) else { continue }
            let points = Store.loadTrack(activity.id)
            guard points.count > 4 else { continue }
            let streams = Streams(points)
            if let effort = SegmentMatcher.bestEffort(segment: segment, streams: streams,
                                                      activityID: activity.id, date: activity.startDate) {
                found.append(effort)
            }
        }
        segmentEfforts.removeAll { $0.segmentID == segment.id }
        segmentEfforts.append(contentsOf: SegmentMatcher.rank(found))
        scheduleSave(.efforts)
    }

    func rematchAllSegments() {
        segmentEfforts.removeAll()
        for segment in segments {
            matchSegmentAgainstHistory(segment)
        }
    }

    private func reRankSegments(touchedBy segmentIDs: [UUID]) {
        for id in Set(segmentIDs) {
            let existing = segmentEfforts.filter { $0.segmentID == id }
            segmentEfforts.removeAll { $0.segmentID == id }
            segmentEfforts.append(contentsOf: SegmentMatcher.rank(existing))
        }
    }

    var starredSegments: [Segment] {
        segments.filter(\.isStarred)
    }

    // MARK: - Routes

    func addRoute(_ route: SavedRoute) {
        routes.append(route)
        scheduleSave(.routes)
    }

    func deleteRoute(_ route: SavedRoute) {
        routes.removeAll { $0.id == route.id }
        scheduleSave(.routes)
    }

    func updateRoute(_ route: SavedRoute) {
        guard let idx = routes.firstIndex(where: { $0.id == route.id }) else { return }
        routes[idx] = route
        scheduleSave(.routes)
    }

    // MARK: - Gear

    func addGear(_ item: Gear) {
        gear.append(item)
        scheduleSave(.gear)
    }

    func updateGear(_ item: Gear) {
        guard let idx = gear.firstIndex(where: { $0.id == item.id }) else { return }
        gear[idx] = item
        scheduleSave(.gear)
    }

    func deleteGear(_ item: Gear) {
        gear.removeAll { $0.id == item.id }
        for i in activities.indices where activities[i].gearID == item.id {
            activities[i].gearID = nil
        }
        scheduleSave(.gear)
        scheduleSave(.activities)
    }

    private var gearMileageCache: [UUID: Double] = [:]

    func mileage(for item: Gear) -> Double {
        item.startingDistance + (gearMileageCache[item.id] ?? 0)
    }

    func activityCount(for item: Gear) -> Int {
        activities.filter { $0.gearID == item.id }.count
    }

    func recomputeGearMileage() {
        var cache: [UUID: Double] = [:]
        for activity in activities {
            guard let id = activity.gearID else { continue }
            cache[id, default: 0] += activity.distance
        }
        gearMileageCache = cache
    }

    func defaultGear(for sport: SportType) -> Gear? {
        gear.first { $0.kind == sport.gearKind && $0.isDefault && !$0.isRetired }
            ?? gear.first { $0.kind == sport.gearKind && !$0.isRetired }
    }

    // MARK: - Goals

    func addGoal(_ goal: Goal) {
        goals.append(goal)
        scheduleSave(.goals)
    }

    func deleteGoal(_ goal: Goal) {
        goals.removeAll { $0.id == goal.id }
        scheduleSave(.goals)
    }

    func updateGoal(_ goal: Goal) {
        guard let idx = goals.firstIndex(where: { $0.id == goal.id }) else { return }
        goals[idx] = goal
        scheduleSave(.goals)
    }

    // MARK: - Backup

    /// Snapshot taken on the main thread so the file writing can move off it.
    func backupSnapshot() -> Backup.Snapshot {
        Backup.Snapshot(settings: settings,
                        activities: activities,
                        segments: segments,
                        segmentEfforts: segmentEfforts,
                        routes: routes,
                        gear: gear,
                        goals: goals)
    }

    var activityIDs: Set<UUID> {
        Set(activities.map(\.id))
    }

    /// Files that were on disk but could not be read. Their bytes were set aside
    /// rather than overwritten, so nothing is lost yet.
    var damagedFiles: [String] {
        Store.damagedFiles
    }

    private func merged<T: Identifiable>(_ existing: [T], _ incoming: [T]) -> [T] {
        var seen = Set(existing.map(\.id))
        var out = existing
        for item in incoming where !seen.contains(item.id) {
            seen.insert(item.id)
            out.append(item)
        }
        return out
    }

    func apply(_ restored: Backup.Restored,
               mode: Backup.RestoreMode,
               restoreSettings: Bool) {
        switch mode {
        case .replace:
            // Drop the tracks and photos belonging to activities being discarded.
            let keeping = Set(restored.activities.map(\.id))
            for activity in activities where !keeping.contains(activity.id) {
                Store.deleteTrack(activity.id)
                for photo in activity.photos { Store.deletePhoto(photo.filename) }
            }
            activities = restored.activities
            segments = restored.segments
            segmentEfforts = restored.segmentEfforts
            routes = restored.routes
            gear = restored.gear
            goals = restored.goals

        case .merge:
            activities = merged(activities, restored.activities)
            segments = merged(segments, restored.segments)
            segmentEfforts = merged(segmentEfforts, restored.segmentEfforts)
            routes = merged(routes, restored.routes)
            gear = merged(gear, restored.gear)
            goals = merged(goals, restored.goals)
        }

        if restoreSettings {
            settings = restored.settings
        }
        activities.sort { $0.startDate > $1.startDate }
        recomputeGearMileage()
        saveEverything()
    }

    /// Runs a scheduled backup if one is due. Safe to call on every launch and
    /// every return to the foreground.
    func runAutomaticBackupIfDue() {
        guard settings.backup.isDue() else { return }
        guard !activities.isEmpty else { return }
        let snapshot = backupSnapshot()
        let keep = settings.backup.keepCount
        let count = activities.count
        AutoBackup.run(snapshot: snapshot, keep: keep) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let entry):
                self.settings.backup.lastRun = entry.created
                self.settings.backup.lastFilename = entry.filename
                self.settings.backup.lastActivityCount = count
                self.flush()
            case .failure(let error):
                NSLog("Stride: automatic backup failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Streaks and milestones

    /// Consecutive days with at least one activity, counting back from today.
    var currentStreak: Int {
        let cal = Calendar.current
        let days = Set(activities.map { cal.startOfDay(for: $0.startDate) })
        guard !days.isEmpty else { return 0 }
        var streak = 0
        var day = cal.startOfDay(for: Date())
        if !days.contains(day) {
            guard let yesterday = cal.date(byAdding: .day, value: -1, to: day) else { return 0 }
            day = yesterday
            if !days.contains(day) { return 0 }
        }
        while days.contains(day) {
            streak += 1
            guard let previous = cal.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        return streak
    }

    /// Weeks in a row with at least one activity.
    var weekStreak: Int {
        let cal = Calendar.current
        var streak = 0
        var reference = Date()
        while true {
            guard let interval = cal.dateInterval(of: .weekOfYear, for: reference) else { break }
            let count = activities.filter { interval.contains($0.startDate) }.count
            if count == 0 {
                if streak == 0 && cal.isDate(reference, equalTo: Date(), toGranularity: .weekOfYear) {
                    // The current week not having started yet should not break the streak.
                    guard let back = cal.date(byAdding: .weekOfYear, value: -1, to: reference) else { break }
                    reference = back
                    continue
                }
                break
            }
            streak += 1
            guard let back = cal.date(byAdding: .weekOfYear, value: -1, to: reference) else { break }
            reference = back
            if streak > 520 { break }
        }
        return streak
    }
}
