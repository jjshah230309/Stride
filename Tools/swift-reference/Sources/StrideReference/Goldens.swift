import Foundation

// MARK: - Output plumbing

let encoder: JSONEncoder = Store.encoder

func write<T: Encodable>(_ value: T, to url: URL) throws {
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try encoder.encode(value).write(to: url)
    print("wrote \(url.lastPathComponent)")
}

// MARK: - Analysis

struct AnalysisCase: Codable {
    var key: String
    var ageYears: Int
    var profile: AthleteProfile
    var base: Activity
    var track: [TrackPoint]
    var result: Activity
    var movingTime: Double
    var gradeAdjustedPace: Double?
    var routeSignature: String
}

func analysisGolden(_ scenarios: [Scenario]) -> [AnalysisCase] {
    scenarios.map { s in
        let streams = Streams(s.points)
        return AnalysisCase(
            key: s.key, ageYears: ageYears(), profile: s.profile, base: s.base, track: s.points, result: s.result,
            movingTime: ActivityAnalyzer.movingTime(streams),
            gradeAdjustedPace: GradeAdjustedPace.adjustedPace(streams: streams, from: 0, to: streams.count - 1),
            routeSignature: RouteSignature.compute(streams.coords.filter { $0.isValid })
        )
    }
}

// MARK: - Segments

struct SegmentsGolden: Codable {
    var scenarioKey: String
    var activity: Activity
    var track: [TrackPoint]
    var segments: [Segment]
    var efforts: [SegmentEffort]
    var ranked: [SegmentEffort]
}

func segmentFrom(_ points: [TrackPoint], _ range: Range<Int>, name: String, sport: SportType, id: Int, reversed: Bool = false) -> Segment {
    var slice = Array(points[range])
    if reversed { slice.reverse() }
    var s = Segment.make(name: name, sport: sport, path: slice.map(\.coord), elevations: slice.map(\.alt))
    s.id = fixedUUID(2000 + id)
    s.createdAt = startDate
    return s
}

func segmentsGolden(_ scenarios: [Scenario]) -> [SegmentsGolden] {
    var out: [SegmentsGolden] = []
    let run = scenarios.first { $0.key == "run" }!
    let ride = scenarios.first { $0.key == "ride" }!
    let hike = scenarios.first { $0.key == "hike" }!

    let runSegments = [
        segmentFrom(run.points, 60..<230, name: "Opening Hill", sport: .run, id: 1),
        segmentFrom(run.points, 500..<760, name: "Back Straight", sport: .run, id: 2),
        segmentFrom(run.points, 500..<760, name: "Back Straight Reversed", sport: .run, id: 3, reversed: true),
        segmentFrom(run.points, 60..<230, name: "Wrong Sport", sport: .ride, id: 4),
        segmentFrom(run.points, 700..<712, name: "Too Short", sport: .run, id: 5),
        segmentFrom(run.points, 1150..<1450, name: "Finishing Loop", sport: .run, id: 6),
    ]
    let runEfforts = stabilised(efforts: SegmentMatcher.findEfforts(activity: run.result, activityID: run.result.id, points: run.points, segments: runSegments), salt: 1)
    out.append(SegmentsGolden(scenarioKey: "run", activity: run.result, track: [], segments: runSegments, efforts: runEfforts, ranked: SegmentMatcher.rank(runEfforts)))

    let rideSegments = [
        segmentFrom(ride.points, 100..<500, name: "Long Drag", sport: .ride, id: 11),
        segmentFrom(ride.points, 1600..<2100, name: "Fast Finish", sport: .gravelRide, id: 12),
    ]
    let rideEfforts = stabilised(efforts: SegmentMatcher.findEfforts(activity: ride.result, activityID: ride.result.id, points: ride.points, segments: rideSegments), salt: 2)
    out.append(SegmentsGolden(scenarioKey: "ride", activity: ride.result, track: [], segments: rideSegments, efforts: rideEfforts, ranked: SegmentMatcher.rank(rideEfforts)))

    let hikeSegments = [segmentFrom(hike.points, 200..<900, name: "Ridge", sport: .hike, id: 21)]
    let hikeEfforts = stabilised(efforts: SegmentMatcher.findEfforts(activity: hike.result, activityID: hike.result.id, points: hike.points, segments: hikeSegments), salt: 3)
    out.append(SegmentsGolden(scenarioKey: "hike", activity: hike.result, track: [], segments: hikeSegments, efforts: hikeEfforts, ranked: SegmentMatcher.rank(hikeEfforts)))
    return out
}

// MARK: - Speech

struct IntCase: Codable { var n: Int; var casual: String; var formal: String }
struct DoubleCase: Codable {
    var x: Double
    var pace: String?
    var duration: String
    var durationVerbose: String
}
/// NaN and infinity cannot be written as JSON numbers, so they are identified by name.
struct SpecialCase: Codable {
    var label: String
    var pace: String?
    var duration: String
    var durationVerbose: String
}
struct DecimalCase: Codable {
    var x: Double
    var decimal: String
    var distanceMetric: String
    var distanceImperial: String
}

struct SnapshotDTO: Codable {
    var sport: SportType
    var splitIndex: Int
    var splitDistance: Double
    var splitTime: Double
    var splitPaceSecondsPerUnit: Double
    var totalDistance: Double
    var totalTime: Double
    var movingTime: Double
    var averagePaceSecondsPerUnit: Double
    var currentPaceSecondsPerUnit: Double
    var averageSpeed: Double
    var currentSpeed: Double
    var heartRate: Double
    var averageHeartRate: Double
    var cadence: Double
    var power: Double
    var averagePower: Double
    var elevationGain: Double
    var calories: Double
    var gradeAdjustedPaceSecondsPerUnit: Double

    var snapshot: CoachSnapshot {
        var s = CoachSnapshot()
        s.sport = sport; s.splitIndex = splitIndex; s.splitDistance = splitDistance; s.splitTime = splitTime
        s.splitPaceSecondsPerUnit = splitPaceSecondsPerUnit; s.totalDistance = totalDistance
        s.totalTime = totalTime; s.movingTime = movingTime
        s.averagePaceSecondsPerUnit = averagePaceSecondsPerUnit; s.currentPaceSecondsPerUnit = currentPaceSecondsPerUnit
        s.averageSpeed = averageSpeed; s.currentSpeed = currentSpeed
        s.heartRate = heartRate; s.averageHeartRate = averageHeartRate; s.cadence = cadence
        s.power = power; s.averagePower = averagePower; s.elevationGain = elevationGain
        s.calories = calories; s.gradeAdjustedPaceSecondsPerUnit = gradeAdjustedPaceSecondsPerUnit
        return s
    }

    init(_ s: CoachSnapshot) {
        sport = s.sport; splitIndex = s.splitIndex; splitDistance = s.splitDistance; splitTime = s.splitTime
        splitPaceSecondsPerUnit = s.splitPaceSecondsPerUnit; totalDistance = s.totalDistance
        totalTime = s.totalTime; movingTime = s.movingTime
        averagePaceSecondsPerUnit = s.averagePaceSecondsPerUnit; currentPaceSecondsPerUnit = s.currentPaceSecondsPerUnit
        averageSpeed = s.averageSpeed; currentSpeed = s.currentSpeed
        heartRate = s.heartRate; averageHeartRate = s.averageHeartRate; cadence = s.cadence
        power = s.power; averagePower = s.averagePower; elevationGain = s.elevationGain
        calories = s.calories; gradeAdjustedPaceSecondsPerUnit = s.gradeAdjustedPaceSecondsPerUnit
    }
}

struct MetricPhrase: Codable { var metric: SpokenMetric; var text: String? }
struct BuilderOut: Codable { var ssml: String; var plain: String }

struct CoachCase: Codable {
    var snapshot: SnapshotDTO
    var settings: VoiceSettings
    var units: UnitSystem
    var isTimeInterval: Bool
    var metrics: [MetricPhrase]
    var split: BuilderOut
    var remark: String?
    var start: BuilderOut
    var finish: BuilderOut
    var lapNumber: Int
    var lap: BuilderOut
    var halfway: BuilderOut
    var paceAlert: BuilderOut?
    var paceAlertCurrent: Double
    var paceAlertTarget: Double
    var zoneAlert: BuilderOut?
    var zoneAlertZone: Int
    var zoneAlertTarget: Int
    var segmentStart: BuilderOut
    var segmentStartPR: Double?
    var segmentProgress: BuilderOut
    var segmentProgressDelta: Double
    var segmentProgressRemaining: Double
    var segmentFinish: BuilderOut
    var segmentFinishTime: Double
    var segmentFinishIsPR: Bool
    var segmentFinishDelta: Double?
}

struct SpeechGolden: Codable {
    var ints: [IntCase]
    var doubles: [DoubleCase]
    var specials: [SpecialCase]
    var decimals: [DecimalCase]
    var coach: [CoachCase]
}

func out(_ b: SpeechBuilder?) -> BuilderOut? {
    guard let b else { return nil }
    return BuilderOut(ssml: b.ssml, plain: b.plainText)
}

func speechGolden() -> SpeechGolden {
    var ints: [IntCase] = []
    var values = Array(-12...130) + Array(195...215) + Array(498...502) + [990, 995, 999, 1000, 1001, 1010, 1050, 1099, 1100, 1999, 2000, 2025, 2500, 9999, 10000, 12345, 100000, 123456, 999999]
    var rng = LCG(seed: 5)
    for _ in 0..<150 { values.append(Int(rng.next() * 3000)) }
    for n in values { ints.append(IntCase(n: n, casual: SpokenNumber.casual(n), formal: SpokenNumber.formal(n))) }

    var doubles: [DoubleCase] = []
    var times: [Double] = [-5, 0, 0.4, 0.5, 1, 1.5, 9, 9.5, 10, 59, 59.4, 59.5, 59.6, 60, 61, 119.5, 120, 125, 245, 272, 272.5, 299.5, 300, 301, 3599, 3599.4, 3599.5, 3600, 3601, 3720, 3725, 7199, 7200, 9030, 86399, 100000]
    for _ in 0..<200 { times.append((rng.next() * 4000 * 10).rounded() / 10) }
    for x in times {
        doubles.append(DoubleCase(x: x, pace: SpokenNumber.pace(x), duration: SpokenNumber.duration(x), durationVerbose: SpokenNumber.duration(x, verbose: true)))
    }

    let specials = [("nan", Double.nan), ("inf", Double.infinity), ("-inf", -Double.infinity)].map {
        SpecialCase(label: $0.0, pace: SpokenNumber.pace($0.1), duration: SpokenNumber.duration($0.1), durationVerbose: SpokenNumber.duration($0.1, verbose: true))
    }

    var decimals: [DecimalCase] = []
    var reals: [Double] = [0, 0.05, 0.25, 0.5, 0.94, 0.95, 0.99, 0.995, 1, 1.04, 1.05, 1.45, 1.5, 1.95, 2.05, 7.25, 7.249, 9.95, 9.99, 28.4, 28.96, 99.95, 100, 421.55]
    for _ in 0..<150 { reals.append((rng.next() * 50000).rounded() / 10) }
    for x in reals {
        decimals.append(DecimalCase(x: x, decimal: SpokenNumber.decimal(x),
                                    distanceMetric: SpokenNumber.distance(x, .metric),
                                    distanceImperial: SpokenNumber.distance(x, .imperial)))
    }

    var sample = CoachScript.sampleSnapshot
    sample.sport = .run
    var ride = sample; ride.sport = .ride
    var empty = CoachSnapshot()
    empty.splitIndex = 1
    var big = CoachSnapshot()
    big.sport = .run; big.splitIndex = 12; big.splitTime = 3725; big.splitPaceSecondsPerUnit = 3599.6
    big.totalDistance = 42195; big.totalTime = 14000; big.movingTime = 13500
    big.averagePaceSecondsPerUnit = 59.5; big.currentPaceSecondsPerUnit = 600; big.averageSpeed = 0.21; big.currentSpeed = 12.4
    big.heartRate = 205; big.averageHeartRate = 30.5; big.cadence = 5.4; big.power = 1234; big.averagePower = 5.5
    big.elevationGain = 1234.5; big.calories = 12345.5; big.gradeAdjustedPaceSecondsPerUnit = 3599
    var small = CoachSnapshot()
    small.sport = .walk; small.splitIndex = 1; small.splitTime = 1.4; small.splitPaceSecondsPerUnit = 0.6
    small.totalDistance = 950; small.movingTime = 0.5; small.averagePaceSecondsPerUnit = 1; small.currentPaceSecondsPerUnit = 3599.4
    small.averageSpeed = 0.2; small.currentSpeed = 0.21; small.heartRate = 30.4; small.cadence = 5.1; small.power = 5.2
    small.elevationGain = 1.01; small.calories = 1.4
    var hike = sample; hike.sport = .hike; hike.splitIndex = 2
    var mtb = ride; mtb.sport = .mountainBike; mtb.splitIndex = 101; mtb.totalDistance = 995; mtb.averageSpeed = 4.3

    let snapshots: [CoachSnapshot] = [sample, ride, empty, big, small, hike, mtb]
    var cases: [CoachCase] = []
    var caseIndex = 0
    for snapshot in snapshots {
        for units in UnitSystem.allCases {
            for style in SpeechStyle.allCases {
                for interval in [false, true] {
                    var settings = VoiceSettings()
                    settings.style = style
                    settings.runMetrics = SpokenMetric.allCases
                    settings.rideMetrics = SpokenMetric.allCases.reversed()
                    settings.coachRemarks = caseIndex % 2 == 0
                    settings.targetPaceToleranceSeconds = [15, 5, 0][caseIndex % 3]
                    let script = CoachScript(settings: settings, units: units)
                    let current = [290.0, 250.0, 271.0, 269.5][caseIndex % 4]
                    let target = [270.0, 270.0, 270.0, 270.0][caseIndex % 4]
                    let zone = caseIndex % 5
                    let zoneTarget = [3, 3, 1, 0, 5][caseIndex % 5]
                    let segDelta = [-5.0, 0.2, 1.0, 12.6, -0.4][caseIndex % 5]
                    let finishDeltas: [Double?] = [nil, 3.4, -2, 0.4]
                    let finishDelta: Double? = finishDeltas[caseIndex % 4]
                    let segPRs: [Double?] = [125, nil, 0, 3725]
                    let segPR: Double? = segPRs[caseIndex % 4]
                    cases.append(CoachCase(
                        snapshot: SnapshotDTO(snapshot), settings: settings, units: units, isTimeInterval: interval,
                        metrics: SpokenMetric.allCases.map { MetricPhrase(metric: $0, text: script.phrase(for: $0, snapshot: snapshot, isTimeInterval: interval)) },
                        split: out(script.split(snapshot, isTimeInterval: interval))!,
                        remark: script.remark(for: snapshot),
                        start: out(script.start(sport: snapshot.sport))!,
                        finish: out(script.finish(snapshot))!,
                        lapNumber: caseIndex % 7 + 1,
                        lap: out(script.lap(number: caseIndex % 7 + 1, snapshot: snapshot))!,
                        halfway: out(script.halfway(snapshot))!,
                        paceAlert: out(script.paceAlert(current: current, target: target)),
                        paceAlertCurrent: current, paceAlertTarget: target,
                        zoneAlert: out(script.zoneAlert(zone: zone, target: zoneTarget)),
                        zoneAlertZone: zone, zoneAlertTarget: zoneTarget,
                        segmentStart: out(script.segmentStart(name: "Hill & <Co>", prTime: segPR))!,
                        segmentStartPR: segPR,
                        segmentProgress: out(script.segmentProgress(deltaSeconds: segDelta, remaining: [800, 1500, 95, 12000][caseIndex % 4]))!,
                        segmentProgressDelta: segDelta,
                        segmentProgressRemaining: [800, 1500, 95, 12000][caseIndex % 4],
                        segmentFinish: out(script.segmentFinish(name: "Hill", time: [125, 3725, 59.6, 0][caseIndex % 4], isPR: caseIndex % 3 == 0, delta: finishDelta))!,
                        segmentFinishTime: [125, 3725, 59.6, 0][caseIndex % 4],
                        segmentFinishIsPR: caseIndex % 3 == 0,
                        segmentFinishDelta: finishDelta
                    ))
                    caseIndex += 1
                }
            }
        }
    }
    return SpeechGolden(ints: ints, doubles: doubles, specials: specials, decimals: decimals, coach: cases)
}

// MARK: - Number formatting

struct FmtCase: Codable {
    var x: Double
    var units: UnitSystem
    var distance: String
    var distance0: String
    var distance3: String
    var elevation: String
    var duration: String
    var durationPadded: String
    var durationCompact: String
    var paceFromSeconds: String
    var paceFromSpeed: String
    var speed: String
    var int: String
    var one: String
    var trimmed: String
    var percent: String
    var signed0: String
    var signed2: String
}

func fmtGolden() -> [FmtCase] {
    var values: [Double] = [
        0, 0.5, 1, 1.5, 2.5, 3.5, 0.125, 0.15, 0.25, 0.35, 0.45, 0.005, 0.015, 0.025, 0.045, 10.05, 1.005, 2.675,
        -0.4, -0.04, -0.5, -1.5, -2.5, -0.0, 5.55, 5.45, 1234.5678, 999.9995, 1e6 + 0.5, 59.5, 59.4, 119.5, 3599.5,
        3600, 5940, 6000, 0.15 * 10, 272, 272.5, 1609.344, 1000, 4.2, 7.25, 8.5, 0.3, 0.7, 12.345, 100.0
    ]
    var rng = LCG(seed: 91)
    for _ in 0..<300 { values.append((rng.next() * 20000 - 2000).rounded() / 100) }
    var out: [FmtCase] = []
    for u in UnitSystem.allCases {
        for x in values {
            out.append(FmtCase(
                x: x, units: u,
                distance: Fmt.distance(x, u), distance0: Fmt.distance(x, u, decimals: 0), distance3: Fmt.distance(x, u, decimals: 3),
                elevation: Fmt.elevation(x, u),
                duration: Fmt.duration(x), durationPadded: Fmt.durationPadded(x), durationCompact: Fmt.durationCompact(x),
                paceFromSeconds: Fmt.paceFromSeconds(x), paceFromSpeed: Fmt.paceFromSpeed(x, u),
                speed: Fmt.speed(x, u), int: Fmt.int(x), one: Fmt.one(x), trimmed: Fmt.trimmed(x), percent: Fmt.percent(x),
                signed0: Fmt.signed(x), signed2: Fmt.signed(x, decimals: 2)
            ))
        }
    }
    return out
}
