import Foundation

/// Deterministic pseudo-random numbers, so every run of the harness produces identical files.
struct LCG {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double((state >> 33) & 0xFFFFFF) / Double(0x1000000)
    }
    mutating func signed() -> Double { next() * 2 - 1 }
}

let metresPerDegree = 6_371_000.0 * Double.pi / 180
let startDate = ISO8601DateFormatter().date(from: "2026-03-14T07:30:05Z")!
let referenceNow = ISO8601DateFormatter().date(from: "2026-03-14T12:00:00Z")!

func fixedUUID(_ n: Int) -> UUID {
    UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", n))!
}

struct TrackSpec {
    var seconds: Int
    var baseSpeed: Double
    var speedVariation: Double
    var elevationAmplitude: Double
    var elevationPeriod: Double
    var loopRadius: Double = 380
    var originLat: Double = 51.5
    var originLon: Double = -0.12
    var pauses: [Range<Int>] = []
    var dropouts: Set<Int> = []
    var seed: UInt64 = 1
    var hr: ((Int, inout LCG) -> Double?)? = nil
    var cad: ((Int, inout LCG) -> Double?)? = nil
    var pw: ((Int, inout LCG) -> Double?)? = nil
}

/// A loop around a circle, with hills, pauses, GPS dropouts and sensors that sometimes miss a beat.
func makeTrack(_ spec: TrackSpec) -> [TrackPoint] {
    var rng = LCG(seed: spec.seed)
    var points: [TrackPoint] = []
    var distance = 0.0
    var previousSpeed = 0.0
    for i in 0...spec.seconds {
        let paused = spec.pauses.contains { $0.contains(i) }
        var speed = 0.0
        if i > 0 && !paused {
            speed = max(0.3, spec.baseSpeed + spec.speedVariation * sin(Double(i) / 37.0) + 0.25 * rng.signed())
            distance += speed
        }
        let theta = distance / spec.loopRadius
        let lat = spec.originLat + spec.loopRadius * sin(theta) / metresPerDegree
        let lon = spec.originLon + spec.loopRadius * (1 - cos(theta)) / (metresPerDegree * cos(spec.originLat * Double.pi / 180))
        let alt = 30 + spec.elevationAmplitude * sin(2 * Double.pi * distance / spec.elevationPeriod) + 0.3 * rng.signed()
        let dropout = spec.dropouts.contains(i)
        points.append(TrackPoint(
            t: Double(i),
            lat: dropout ? 0 : (lat * 1_000_000).rounded() / 1_000_000,
            lon: dropout ? 0 : (lon * 1_000_000).rounded() / 1_000_000,
            alt: (alt * 100).rounded() / 100,
            d: (distance * 1000).rounded() / 1000,
            v: (speed * 1000).rounded() / 1000,
            hr: spec.hr?(i, &rng),
            cad: spec.cad?(i, &rng),
            pw: spec.pw?(i, &rng),
            moving: !paused && speed > 0,
            acc: dropout ? 120 : (4 + 5 * rng.next() * 10).rounded() / 10
        ))
        previousSpeed = speed
    }
    _ = previousSpeed
    return points
}

func round1(_ x: Double) -> Double { (x * 10).rounded() / 10 }

struct Scenario {
    var key: String
    var sport: SportType
    var base: Activity
    var points: [TrackPoint]
    var profile: AthleteProfile
    var result: Activity
}

func ageYears() -> Int { 30 }

func makeProfile() -> AthleteProfile {
    var p = AthleteProfile()
    let year = Calendar.current.component(.year, from: Date())
    p.birthYear = year - ageYears()
    p.weightKg = 72.5
    p.isMale = true
    p.hrZones.maxHR = 186
    p.hrZones.restingHR = 52
    p.powerZones.ftp = 250
    p.paceZones.thresholdPace = 290
    p.name = "Reference"
    return p
}

func makeScenarios() -> [Scenario] {
    let profile = makeProfile()

    let run = makeTrack(TrackSpec(
        seconds: 1500, baseSpeed: 3.2, speedVariation: 0.7, elevationAmplitude: 14, elevationPeriod: 900,
        pauses: [400..<460, 1100..<1112], seed: 11,
        hr: { i, r in i % 17 == 0 ? nil : round1(118 + 55 * min(1, Double(i) / 700) + 6 * sin(Double(i) / 20) + 2 * r.signed()) },
        cad: { i, r in i % 11 == 0 ? nil : round1(168 + 8 * r.signed()) }
    ))
    let ride = makeTrack(TrackSpec(
        seconds: 2400, baseSpeed: 8.6, speedVariation: 2.4, elevationAmplitude: 22, elevationPeriod: 2600,
        loopRadius: 900, originLat: 51.52, originLon: -0.15, pauses: [1500..<1530], seed: 23,
        hr: { i, r in i % 29 == 0 ? nil : round1(110 + 50 * min(1, Double(i) / 900) + 5 * r.signed()) },
        cad: { i, r in i % 13 == 0 ? nil : round1(84 + 8 * r.signed()) },
        pw: { i, r in i % 23 == 0 ? nil : max(0, round1(190 + 110 * max(0, sin(Double(i) / 60)) + 25 * r.signed())) }
    ))
    let hike = makeTrack(TrackSpec(
        seconds: 1800, baseSpeed: 1.25, speedVariation: 0.3, elevationAmplitude: 40, elevationPeriod: 700,
        loopRadius: 200, originLat: 51.48, originLon: -0.1, seed: 37
    ))
    let trail = makeTrack(TrackSpec(
        seconds: 900, baseSpeed: 2.9, speedVariation: 0.6, elevationAmplitude: 30, elevationPeriod: 500,
        originLat: 51.55, originLon: -0.2, dropouts: [120, 121, 122, 400], seed: 53,
        cad: { i, r in i % 5 == 0 ? nil : round1(160 + 10 * r.signed()) }
    ))

    let defs: [(String, SportType, String, [TrackPoint])] = [
        ("run", .run, "Morning Run", run),
        ("ride", .ride, "Lunch Ride", ride),
        ("hike", .hike, "Hill Hike", hike),
        ("trail", .trailRun, "Trail Loop", trail),
    ]
    var out: [Scenario] = []
    for (index, def) in defs.enumerated() {
        var base = Activity(name: def.2, sport: def.1, startDate: startDate)
        base.id = fixedUUID(100 + index)
        let result = stabilised(ActivityAnalyzer.analyse(activity: base, points: def.3, profile: profile), salt: index)
        out.append(Scenario(key: def.0, sport: def.1, base: base, points: def.3, profile: profile, result: result))
    }
    return out
}

func iso(_ date: Date) -> String {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f.string(from: date)
}

/// The same four activities cut to their first few minutes, for the goldens that carry whole tracks
/// (exports, imports, backups), so they stay small enough to keep in the repository.
func makeSmallScenarios(from full: [Scenario], points count: Int = 240) -> [Scenario] {
    full.map { s in
        let track = Array(s.points.prefix(count))
        let result = stabilised(ActivityAnalyzer.analyse(activity: s.base, points: track, profile: s.profile), salt: 50)
        return Scenario(key: s.key, sport: s.sport, base: s.base, points: track, profile: s.profile, result: result)
    }
}

/// The analysis gives every split, lap and best effort a random id. Give them deterministic ones so
/// regenerating the goldens does not rewrite files for no reason. (The Kotlin tests ignore these ids.)
func stabilised(_ activity: Activity, salt: Int) -> Activity {
    var a = activity
    var counter = 0
    func next() -> UUID { counter += 1; return fixedUUID(10_000 + salt * 1_000 + counter) }
    for i in a.splitsKm.indices { a.splitsKm[i].id = next() }
    for i in a.splitsMi.indices { a.splitsMi[i].id = next() }
    for i in a.laps.indices { a.laps[i].id = next() }
    for i in a.bestEfforts.indices { a.bestEfforts[i].id = next() }
    return a
}

func stabilised(efforts: [SegmentEffort], salt: Int) -> [SegmentEffort] {
    efforts.enumerated().map { i, e in
        var e = e
        e.id = fixedUUID(30_000 + salt * 100 + i)
        return e
    }
}
