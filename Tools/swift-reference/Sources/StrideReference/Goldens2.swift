import Foundation

// MARK: - Exports and imports

struct ExportsGolden: Codable {
    var key: String
    var activity: Activity
    var track: [TrackPoint]
    var gpx: String
    var tcx: String
    var csvMetric: String
    var csvImperial: String
    var fitBase64: String
}

struct RouteExportGolden: Codable {
    var route: SavedRoute
    var gpx: String
}

func exportsGolden(_ scenarios: [Scenario]) -> ([ExportsGolden], RouteExportGolden) {
    let all = scenarios.map(\.result)
    let exports = scenarios.filter { ["run", "ride", "trail"].contains($0.key) }.map { s in
        ExportsGolden(
            key: s.key, activity: s.result, track: s.points,
            gpx: Exporter.gpx(activity: s.result, points: s.points),
            tcx: Exporter.tcx(activity: s.result, points: s.points),
            csvMetric: Exporter.csv(activities: all, units: .metric),
            csvImperial: Exporter.csv(activities: all, units: .imperial),
            fitBase64: Exporter.fit(activity: s.result, points: s.points).base64EncodedString()
        )
    }
    let run = scenarios[0]
    let path = stride(from: 0, to: run.points.count, by: 90).map { run.points[$0].coord }
    let elevations = stride(from: 0, to: run.points.count, by: 90).map { run.points[$0].alt }
    var route = SavedRoute(name: "Reference <Loop> & Co", sport: .run, path: path, elevations: elevations,
                           distance: Geo.totalDistance(path), elevationGain: Smooth.elevationChange(elevations).gain)
    route.id = fixedUUID(4000)
    route.createdAt = startDate
    return (exports, RouteExportGolden(route: route, gpx: Exporter.gpx(route: route)))
}

struct ImportGolden: Codable {
    var file: String
    var format: String
    var name: String
    var sport: SportType?
    var startDate: Date?
    var isRoute: Bool
    var warning: String?
    var points: [TrackPoint]
}

func importsGolden(fixturesDir: URL, exports: [ExportsGolden]) throws -> [ImportGolden] {
    var out: [ImportGolden] = []
    func add(_ file: String, _ data: Data, deterministicDate: Bool = true) throws {
        let imported = try ActivityFile.load(data: data, filename: file)
        out.append(ImportGolden(file: file, format: imported.format.rawValue, name: imported.name, sport: imported.sport,
                                startDate: deterministicDate ? imported.startDate : nil, isRoute: imported.isRoute,
                                warning: imported.warning, points: imported.points))
    }
    for name in ["strava.gpx", "garmin.tcx", "fit-tool-ride.fit"] {
        try add(name, Data(contentsOf: fixturesDir.appendingPathComponent(name)))
    }
    for e in exports {
        try add("\(e.key)-export.gpx", Data(e.gpx.utf8))
        try add("\(e.key)-export.tcx", Data(e.tcx.utf8))
        try add("\(e.key)-export.fit", Data(base64Encoded: e.fitBase64)!)
    }
    // sniffing: a GPX with the wrong extension, and a FIT with no extension at all
    try add("renamed.txt", Data(contentsOf: fixturesDir.appendingPathComponent("strava.gpx")))
    try add("no-extension", Data(base64Encoded: exports[0].fitBase64)!)
    return out
}

// MARK: - Fitness

struct FitnessDay: Codable {
    var date: String
    var fitness: Double
    var fatigue: Double
    var form: Double
    var load: Double
}

struct FitnessInput: Codable { var startDate: Date; var trainingLoad: Double }

struct FitnessGolden: Codable {
    var end: Date
    var inputs: [FitnessInput]
    var points: [FitnessDay]
    var descriptions: [FitnessDescription]
}

struct FitnessDescription: Codable { var form: Double; var title: String; var detail: String }

func fitnessGolden(_ scenarios: [Scenario]) -> FitnessGolden {
    let end = ISO8601DateFormatter().date(from: "2026-03-14T12:00:00Z")!
    var activities: [Activity] = []
    for (i, s) in scenarios.enumerated() {
        for k in 0..<12 {
            var a = s.result
            a.id = fixedUUID(900 + i * 20 + k)
            a.startDate = end.addingTimeInterval(-Double(k * (i + 2) * 86_400 + 3600 * (k % 5)))
            a.trainingLoad = s.result.trainingLoad * (0.6 + Double((k * 7 + i) % 9) / 10)
            activities.append(a)
        }
    }
    let points = FitnessCurve.build(activities: activities, through: end, daysBack: 400)
    let day = DateFormatter()
    day.dateFormat = "yyyy-MM-dd"
    day.timeZone = TimeZone(identifier: "UTC")
    let days = points.map { FitnessDay(date: day.string(from: $0.date), fitness: $0.fitness, fatigue: $0.fatigue, form: $0.form, load: $0.load) }
    let forms: [Double] = [40, 25, 24.9, 5, 4.9, 0, -10, -10.1, -30, -30.1, -55]
    let descriptions = forms.map { f -> FitnessDescription in
        let d = FitnessCurve.formDescription(f)
        return FitnessDescription(form: f, title: d.0, detail: d.1)
    }
    return FitnessGolden(end: end, inputs: activities.map { FitnessInput(startDate: $0.startDate, trainingLoad: $0.trainingLoad) }, points: days, descriptions: descriptions)
}

// MARK: - Live segments

struct LiveEventDTO: Codable {
    var kind: String
    var name: String?
    var prTime: Double?
    var delta: Double?
    var remaining: Double?
    var time: Double?
    var isPR: Bool?
}

struct LiveActiveDTO: Codable {
    var segmentID: UUID
    var fraction: Double
    var distanceCovered: Double
    var elapsed: Double
    var deltaToPR: Double
    var lastAnnouncedFraction: Double
    var strayedSeconds: Double
}

struct LiveStep: Codable {
    var i: Int
    var events: [LiveEventDTO]
    var active: LiveActiveDTO?
    var nearby: [String]?
}

struct LiveGolden: Codable {
    var name: String
    var sport: SportType
    var segments: [Segment]
    var bests: [String: Double]
    /// Which analysis scenario's track is walked, and how it is altered first.
    var trackKey: String
    var transform: String
    var steps: [LiveStep]
}

func eventDTO(_ e: LiveSegmentEngine.Event) -> LiveEventDTO {
    switch e {
    case let .started(name, prTime): return LiveEventDTO(kind: "started", name: name, prTime: prTime)
    case let .progress(delta, remaining): return LiveEventDTO(kind: "progress", delta: delta, remaining: remaining)
    case let .finished(name, time, isPR, delta): return LiveEventDTO(kind: "finished", name: name, delta: delta, time: time, isPR: isPR)
    case let .abandoned(name): return LiveEventDTO(kind: "abandoned", name: name)
    }
}

func runLive(name: String, sport: SportType, segments: [Segment], bests: [UUID: Double], track: [TrackPoint], trackKey: String, transform: String) -> LiveGolden {
    let engine = LiveSegmentEngine()
    engine.load(segments: segments, bests: bests, sport: sport)
    var steps: [LiveStep] = []
    var wasActive = false
    for (i, p) in track.enumerated() {
        let events = engine.update(coord: p.coord, elapsed: p.t, totalDistance: p.d, dt: 1)
        var nearby: [String]? = nil
        if i % 10 == 0 {
            engine.updateNearby(coord: p.coord)
            nearby = engine.nearby.map(\.name)
        }
        let active = engine.active.map {
            LiveActiveDTO(segmentID: $0.segmentID, fraction: $0.fraction, distanceCovered: $0.distanceCovered, elapsed: $0.elapsed,
                          deltaToPR: $0.deltaToPR, lastAnnouncedFraction: $0.lastAnnouncedFraction, strayedSeconds: $0.strayedSeconds)
        }
        if active != nil || !events.isEmpty || wasActive || (nearby?.isEmpty == false) {
            steps.append(LiveStep(i: i, events: events.map(eventDTO), active: active, nearby: nearby))
        }
        wasActive = active != nil
    }
    return LiveGolden(name: name, sport: sport, segments: segments,
                      bests: Dictionary(uniqueKeysWithValues: bests.map { ($0.key.uuidString, $0.value) }),
                      trackKey: trackKey, transform: transform, steps: steps)
}

func liveGolden(_ scenarios: [Scenario]) -> [LiveGolden] {
    let run = scenarios.first { $0.key == "run" }!
    let ride = scenarios.first { $0.key == "ride" }!
    let hill = segmentFrom(run.points, 60..<230, name: "Opening Hill", sport: .run, id: 1)
    let straight = segmentFrom(run.points, 500..<760, name: "Back Straight", sport: .run, id: 2)
    let finishing = segmentFrom(run.points, 1150..<1450, name: "Finishing Loop", sport: .run, id: 6)
    var unstarred = segmentFrom(run.points, 300..<420, name: "Unstarred", sport: .run, id: 7)
    unstarred.isStarred = false
    let rideSeg = segmentFrom(ride.points, 100..<500, name: "Long Drag", sport: .ride, id: 11)

    var out: [LiveGolden] = []
    let bests: [UUID: Double] = [straight.id: 95, finishing.id: 480]
    out.append(runLive(name: "run-with-records", sport: .run, segments: [hill, straight, finishing, unstarred, rideSeg], bests: bests, track: run.points, trackKey: "run", transform: "none"))

    // a very slow best that this run beats, and one it never will
    out.append(runLive(name: "run-new-record", sport: .run, segments: [hill, straight, finishing], bests: [hill.id: 1000, straight.id: 10], track: run.points, trackKey: "run", transform: "none"))

    // the same ground in the opposite direction must not trigger anything
    let reversed: [TrackPoint] = run.points.reversed().enumerated().map { i, p in
        var q = p; q.t = Double(i); return q
    }
    out.append(runLive(name: "run-reversed", sport: .run, segments: [hill, straight, finishing], bests: bests, track: reversed, trackKey: "run", transform: "reversed"))

    // wander off the line half way through the back straight: abandoned after 20 s of straying
    var strayed = run.points
    for i in 640..<strayed.count {
        strayed[i].lon += 0.0015
    }
    out.append(runLive(name: "run-strays", sport: .run, segments: [straight], bests: bests, track: strayed, trackKey: "run", transform: "strayAfter640"))

    out.append(runLive(name: "ride", sport: .ride, segments: [hill, rideSeg], bests: [rideSeg.id: 60], track: ride.points, trackKey: "ride", transform: "none"))
    return out
}

// MARK: - The iOS Documents folder and backup

struct DocumentsGolden: Codable {
    var settings: AppSettings
    var activities: [Activity]
    var activityIDWithTrack: UUID
}

func customSettings() -> AppSettings {
    var s = AppSettings()
    s.profile = makeProfile()
    s.display.units = .imperial
    s.display.mapStyle = "hybrid"
    s.display.countdownSeconds = 5
    s.display.gpsAccuracyFilter = 20
    s.display.defaultSport = .ride
    s.display.weekStartsMonday = false
    s.display.hasCompletedOnboarding = true
    s.voice.style = .detailed
    s.voice.trigger = .both
    s.voice.distanceInterval = 0.5
    s.voice.timeInterval = 120
    s.voice.runMetrics = [.heartRate, .splitNumber, .gradeAdjustedPace, .calories]
    s.voice.rideMetrics = [.averagePower, .currentSpeed]
    s.voice.rate = 0.42
    s.voice.voiceIdentifier = "com.apple.voice.premium.en-GB.Malcolm"
    s.voice.targetPaceSeconds = 285
    s.voice.targetHeartRateZone = 3
    s.backup.automatic = true
    s.backup.intervalDays = 14
    s.backup.keepCount = 10
    s.backup.lastRun = ISO8601DateFormatter().date(from: "2026-03-01T09:00:00Z")
    s.backup.lastFilename = "Stride-2026-03-01-0900.stridebackup"
    s.backup.lastActivityCount = 4
    return s
}

func writeDocuments(scenarios: [Scenario], fullScenarios: [Scenario], into outDir: URL) throws {
    Store.prepare()
    let activities = scenarios.map(\.result)
    for s in scenarios { Store.saveTrack(s.points, for: s.result.id) }

    let segmentsRun = segmentsGolden(fullScenarios)
    let segments = segmentsRun.flatMap(\.segments)
    let efforts = segmentsRun.flatMap(\.ranked)
    var route = exportsGolden(fullScenarios).1.route
    route.isStarred = true
    var gear = Gear(kind: .shoes, name: "Pegasus", brand: "Nike", model: "Pegasus 41")
    gear.id = fixedUUID(500); gear.addedDate = startDate; gear.retirementDistance = 800_000
    var goal = Goal(metric: .distance, period: .weekly, sports: [.run, .trailRun], target: 30_000)
    goal.id = fixedUUID(6000); goal.createdAt = startDate
    let settings = customSettings()

    Store.save(activities, to: "activities.json")
    Store.save(segments, to: "segments.json")
    Store.save(efforts, to: "segment-efforts.json")
    Store.save([route], to: "routes.json")
    Store.save([gear], to: "gear.json")
    Store.save([goal], to: "goals.json")
    Store.save(settings, to: "settings.json")
    var lap = Lap(index: 1, startTime: 0, endTime: 30, distance: 90, moving: 30, elevGain: 1)
    lap.id = fixedUUID(778)
    Store.writeRecovery(Store.RecoverySnapshot(
        activityID: fixedUUID(777), sport: .run, startDate: startDate, points: Array(scenarios[0].points.prefix(40)),
        elapsed: 40, pausedDuration: 3, laps: [lap],
        savedAt: startDate.addingTimeInterval(41)))

    let snapshot = Backup.Snapshot(settings: settings, activities: activities, segments: segments, segmentEfforts: efforts,
                                   routes: [route], gear: [gear], goals: [goal])
    let backupURL = Store.exportsDirectory.appendingPathComponent("ios.stridebackup")
    _ = try Backup.write(snapshot, to: backupURL)

    let dest = outDir.appendingPathComponent("ios-documents")
    try? FileManager.default.removeItem(at: dest)
    try FileManager.default.createDirectory(at: dest.appendingPathComponent("Tracks"), withIntermediateDirectories: true)
    for name in ["activities.json", "segments.json", "segment-efforts.json", "routes.json", "gear.json", "goals.json", "settings.json", "recording-in-progress.json"] {
        try FileManager.default.copyItem(at: Store.url(name), to: dest.appendingPathComponent(name))
    }
    // one track is enough to prove the on-disk track format; the backup carries all of them
    let smallest = scenarios.min { $0.points.count < $1.points.count }!
    try FileManager.default.copyItem(at: Store.trackURL(smallest.result.id), to: dest.appendingPathComponent("Tracks/\(smallest.result.id.uuidString).json"))
    let backupDest = outDir.appendingPathComponent("ios.stridebackup")
    try? FileManager.default.removeItem(at: backupDest)
    try FileManager.default.copyItem(at: backupURL, to: backupDest)
    try write(DocumentsGolden(settings: settings, activities: activities, activityIDWithTrack: smallest.result.id), to: outDir.appendingPathComponent("ios-documents.json"))
    print("wrote ios-documents/ and ios.stridebackup")
}
