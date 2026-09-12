import Foundation

struct Split: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var index: Int
    var distance: Double        // metres covered in this split
    var elapsed: TimeInterval
    var moving: TimeInterval
    var elevGain: Double
    var elevLoss: Double
    var avgHR: Double?
    var avgPower: Double?
    var avgCadence: Double?
    var gapSeconds: Double?     // grade adjusted seconds per display unit
    var isPartial: Bool = false

    var paceSecondsPerUnit: Double {
        guard distance > 1 else { return 0 }
        return moving / (distance / 1000.0)  // per km; converted at display time
    }
}

struct Lap: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var index: Int
    var startTime: TimeInterval
    var endTime: TimeInterval
    var distance: Double
    var moving: TimeInterval
    var elevGain: Double
    var avgHR: Double?
    var avgPower: Double?
}

struct BestEffort: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String
    var distance: Double        // metres
    var time: TimeInterval
    var startIndex: Int
    var endIndex: Int
    var startOffset: TimeInterval

    var paceSecondsPerKm: Double { distance > 0 ? time / (distance / 1000) : 0 }
}

struct PowerCurvePoint: Codable, Hashable {
    var duration: TimeInterval
    var watts: Double
}

struct SegmentEffort: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var segmentID: UUID
    var activityID: UUID
    var date: Date
    var time: TimeInterval          // moving time over the segment
    var startIndex: Int
    var endIndex: Int
    var avgHR: Double?
    var avgPower: Double?
    var avgSpeed: Double
    var isPR: Bool = false
    var rank: Int = 0               // 1 = fastest ever on this segment
}

struct ActivityPhoto: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var filename: String
    var caption: String = ""
    var coord: Coord?
}

struct Activity: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String
    var sport: SportType
    var startDate: Date

    // Totals
    var elapsed: TimeInterval = 0
    var movingTime: TimeInterval = 0
    var distance: Double = 0            // metres
    var elevationGain: Double = 0
    var elevationLoss: Double = 0
    var maxElevation: Double = 0
    var minElevation: Double = 0

    // Physiology
    var avgHR: Double?
    var maxHR: Double?
    var avgCadence: Double?
    var maxCadence: Double?
    var avgPower: Double?
    var maxPower: Double?
    var normalizedPower: Double?
    var calories: Double = 0
    var relativeEffort: Double = 0
    var trainingLoad: Double = 0        // TSS-equivalent, drives Fitness & Freshness
    var perceivedExertion: Int?
    var maxSpeed: Double = 0

    // Time in zone, seconds
    var hrZoneTimes: [TimeInterval] = []
    var powerZoneTimes: [TimeInterval] = []
    var paceZoneTimes: [TimeInterval] = []

    // Derived detail
    var splitsKm: [Split] = []
    var splitsMi: [Split] = []
    var laps: [Lap] = []
    var bestEfforts: [BestEffort] = []
    var powerCurve: [PowerCurvePoint] = []
    var segmentEffortIDs: [UUID] = []

    // Metadata
    var notes: String = ""
    var isCommute: Bool = false
    var isPrivate: Bool = false
    var isManual: Bool = false
    var gearID: UUID?
    var photos: [ActivityPhoto] = []
    var routeSignature: String = ""     // groups activities that follow the same route
    var hasGPS: Bool = true

    /// Simplified path for map previews, so the full track stays on disk until needed.
    var previewPath: [Coord] = []
    var startCoord: Coord?
    var locationName: String = ""

    init(name: String, sport: SportType, startDate: Date) {
        self.name = name
        self.sport = sport
        self.startDate = startDate
    }

    var endDate: Date { startDate.addingTimeInterval(elapsed) }

    var avgSpeed: Double { movingTime > 0 ? distance / movingTime : 0 }

    var avgPaceSecondsPerKm: Double { distance > 1 ? movingTime / (distance / 1000) : 0 }

    var boundingBox: BoundingBox? { BoundingBox(previewPath) }

    func paceOrSpeedString(_ u: UnitSystem) -> String {
        if sport.usesPace {
            return Fmt.pace(distance: distance, time: movingTime, u) + " " + u.paceUnit
        }
        return Fmt.avgSpeed(distance: distance, time: movingTime, u) + " " + u.speedUnit
    }

    static func defaultName(for sport: SportType, at date: Date) -> String {
        let hour = Calendar.current.component(.hour, from: date)
        let part: String
        switch hour {
        case 4..<12: part = "Morning"
        case 12..<17: part = "Afternoon"
        case 17..<21: part = "Evening"
        default: part = "Night"
        }
        return "\(part) \(sport.shortName)"
    }
}

/// A rolled-up total, reused by the weekly card, the training log and goals.
struct Totals {
    var count: Int = 0
    var distance: Double = 0
    var movingTime: TimeInterval = 0
    var elevationGain: Double = 0
    var relativeEffort: Double = 0
    var calories: Double = 0

    static func of(_ activities: [Activity]) -> Totals {
        var t = Totals()
        for a in activities {
            t.count += 1
            t.distance += a.distance
            t.movingTime += a.movingTime
            t.elevationGain += a.elevationGain
            t.relativeEffort += a.relativeEffort
            t.calories += a.calories
        }
        return t
    }

    static func + (lhs: Totals, rhs: Totals) -> Totals {
        Totals(count: lhs.count + rhs.count,
               distance: lhs.distance + rhs.distance,
               movingTime: lhs.movingTime + rhs.movingTime,
               elevationGain: lhs.elevationGain + rhs.elevationGain,
               relativeEffort: lhs.relativeEffort + rhs.relativeEffort,
               calories: lhs.calories + rhs.calories)
    }
}
