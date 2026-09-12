import Foundation

/// A stretch of road or trail you race yourself on. Strava's leaderboard is global;
/// this one is yours, built from every effort you have recorded on it.
struct Segment: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String
    var sport: SportType
    var path: [Coord]
    var distance: Double            // metres
    var elevationGain: Double
    var averageGrade: Double        // fraction, not percent
    var maxGrade: Double
    var createdAt: Date = Date()
    var isStarred: Bool = true      // starred segments get live feedback while recording
    var createdFromActivityID: UUID?
    var notes: String = ""

    var startCoord: Coord? { path.first }
    var endCoord: Coord? { path.last }

    var boundingBox: BoundingBox? { BoundingBox(path) }

    /// Bearing you should be travelling when you enter, so the reverse direction does not match.
    var startBearing: Double {
        guard path.count > 1 else { return 0 }
        let ahead = path[Swift.min(path.count - 1, 3)]
        return Geo.bearing(path[0], ahead)
    }

    var category: String {
        let climbScore = elevationGain * averageGrade * 100
        switch climbScore {
        case 8000...: return "HC"
        case 6400..<8000: return "Cat 1"
        case 3200..<6400: return "Cat 2"
        case 1600..<3200: return "Cat 3"
        case 800..<1600: return "Cat 4"
        default: return ""
        }
    }

    static func make(name: String, sport: SportType, path: [Coord], elevations: [Double]) -> Segment {
        let distance = Geo.totalDistance(path)
        let change = Smooth.elevationChange(elevations, threshold: 0.8)
        var maxGrade = 0.0
        if path.count > 1 && elevations.count == path.count {
            var i = 0
            while i < path.count - 1 {
                let run = Geo.fastDistance(path[i], path[i + 1])
                if run > 5 {
                    maxGrade = Swift.max(maxGrade, (elevations[i + 1] - elevations[i]) / run)
                }
                i += 1
            }
        }
        var s = Segment(name: name, sport: sport, path: path,
                        distance: distance,
                        elevationGain: change.gain,
                        averageGrade: distance > 0 ? change.gain / distance : 0,
                        maxGrade: Swift.min(0.4, maxGrade))
        s.notes = ""
        return s
    }
}

/// A saved route you plan to follow, either drawn on the map or lifted from an activity.
struct SavedRoute: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String
    var sport: SportType
    var path: [Coord]
    var elevations: [Double]
    var distance: Double
    var elevationGain: Double
    var createdAt: Date = Date()
    var notes: String = ""
    var isStarred: Bool = false
    var sourceActivityID: UUID?

    var boundingBox: BoundingBox? { BoundingBox(path) }

    /// Estimated time to complete, given a speed in m/s, adjusted for the climbing.
    func estimatedTime(atSpeed mps: Double, sport: SportType) -> TimeInterval {
        guard mps > 0, distance > 0 else { return 0 }
        let flat = distance / mps
        // Naismith-style penalty: roughly ten minutes per 100 m of ascent on foot,
        // less on a bike where the descent gives some of it back.
        let climbPenalty = sport.isFoot ? elevationGain * 6.0 : elevationGain * 3.5
        return flat + climbPenalty
    }
}

enum GearKind: String, Codable, CaseIterable, Identifiable {
    case shoes
    case bike

    var id: String { rawValue }
    var name: String { self == .shoes ? "Shoes" : "Bike" }
    var symbol: String { self == .shoes ? "shoe.2" : "bicycle" }
    /// Distance at which we suggest retiring, in metres.
    var defaultRetirementDistance: Double { self == .shoes ? 800_000 : 20_000_000 }
}

struct Gear: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var kind: GearKind
    var name: String
    var brand: String = ""
    var model: String = ""
    var notes: String = ""
    var addedDate: Date = Date()
    var isRetired: Bool = false
    var isDefault: Bool = false
    var startingDistance: Double = 0        // metres already on it before you started tracking
    var retirementDistance: Double = 0      // 0 means no reminder
    var weightKg: Double = 0                // bike weight, used by the power estimate

    var displayName: String {
        if !brand.isEmpty && !model.isEmpty { return "\(brand) \(model)" }
        return name
    }
}

/// Goals mirror Strava's: a target over a repeating period, or a one-off target.
enum GoalMetric: String, Codable, CaseIterable, Identifiable {
    case distance
    case time
    case elevation
    case activities

    var id: String { rawValue }
    var name: String {
        switch self {
        case .distance: return "Distance"
        case .time: return "Time"
        case .elevation: return "Elevation"
        case .activities: return "Activities"
        }
    }
}

enum GoalPeriod: String, Codable, CaseIterable, Identifiable {
    case weekly
    case monthly
    case yearly

    var id: String { rawValue }
    var name: String {
        switch self {
        case .weekly: return "Weekly"
        case .monthly: return "Monthly"
        case .yearly: return "Yearly"
        }
    }
}

struct Goal: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var metric: GoalMetric
    var period: GoalPeriod
    /// Empty means every sport counts.
    var sports: [SportType] = []
    /// Metres, seconds, metres of ascent, or a plain count.
    var target: Double
    var createdAt: Date = Date()
    var isActive: Bool = true

    func matches(_ activity: Activity) -> Bool {
        sports.isEmpty || sports.contains(activity.sport)
    }

    func progress(from activities: [Activity], now: Date = Date()) -> Double {
        let cal = Calendar.current
        let interval: DateInterval?
        switch period {
        case .weekly: interval = cal.dateInterval(of: .weekOfYear, for: now)
        case .monthly: interval = cal.dateInterval(of: .month, for: now)
        case .yearly: interval = cal.dateInterval(of: .year, for: now)
        }
        guard let range = interval else { return 0 }
        let relevant = activities.filter { range.contains($0.startDate) && matches($0) }
        switch metric {
        case .distance: return relevant.reduce(0) { $0 + $1.distance }
        case .time: return relevant.reduce(0) { $0 + $1.movingTime }
        case .elevation: return relevant.reduce(0) { $0 + $1.elevationGain }
        case .activities: return Double(relevant.count)
        }
    }

    var title: String {
        let scope = sports.isEmpty ? "All sports" : sports.map(\.shortName).joined(separator: ", ")
        return "\(period.name) \(metric.name.lowercased()) — \(scope)"
    }
}
