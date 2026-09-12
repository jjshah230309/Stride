import Foundation

/// Turns a raw track into a fully analysed activity. Everything the detail screen
/// shows is computed once, here, and stored with the activity.
enum ActivityAnalyzer {

    static func analyse(activity input: Activity,
                        points: [TrackPoint],
                        profile: AthleteProfile) -> Activity {
        var a = input
        let streams = Streams(points)
        guard streams.count > 1 else {
            a.hasGPS = false
            return a
        }

        // Totals
        a.distance = streams.distance.last ?? 0
        a.elapsed = streams.time.last ?? 0
        a.movingTime = movingTime(streams)
        let smoothedAltitude = Smooth.movingAverage(streams.altitude, window: 9)
        let change = Smooth.elevationChange(smoothedAltitude, threshold: 1.0)
        a.elevationGain = change.gain
        a.elevationLoss = change.loss
        a.maxElevation = smoothedAltitude.max() ?? 0
        a.minElevation = smoothedAltitude.min() ?? 0
        a.maxSpeed = streams.speed.filter { $0 < a.sport.maxPlausibleSpeed }.max() ?? 0

        // Heart rate
        let hrValues = streams.heartRate.compactMap { $0 }.filter { $0 > 30 && $0 < 240 }
        if !hrValues.isEmpty {
            a.avgHR = hrValues.reduce(0, +) / Double(hrValues.count)
            a.maxHR = hrValues.max()
        }

        // Cadence — running cadence is conventionally reported for both legs.
        let cadValues = streams.cadence.compactMap { $0 }.filter { $0 > 5 }
        if !cadValues.isEmpty {
            a.avgCadence = cadValues.reduce(0, +) / Double(cadValues.count)
            a.maxCadence = cadValues.max()
        }

        // Power
        let pwValues = streams.power.compactMap { $0 }.filter { $0 >= 0 }
        if !pwValues.isEmpty {
            a.avgPower = pwValues.reduce(0, +) / Double(pwValues.count)
            a.maxPower = pwValues.max()
            let oneHz = Streams.toOneHertz(streams.power, time: streams.time, duration: a.elapsed)
            a.normalizedPower = PowerModel.normalizedPower(oneHz)
            if a.sport.isRide {
                a.powerCurve = PowerCurveBuilder.build(wattsPerSecond: oneHz)
            }
        }

        // Zones
        a.hrZoneTimes = ZoneAnalysis.heartRateTimes(streams: streams, zones: profile.hrZones)
        a.powerZoneTimes = ZoneAnalysis.powerTimes(streams: streams, zones: profile.powerZones)
        a.paceZoneTimes = ZoneAnalysis.paceTimes(streams: streams, zones: profile.paceZones)

        // Splits
        a.splitsKm = SplitBuilder.build(streams: streams, unitMeters: 1000)
        a.splitsMi = SplitBuilder.build(streams: streams, unitMeters: 1609.344)

        // Best efforts
        a.bestEfforts = BestEffortFinder.find(streams: streams, sport: a.sport)

        // Calories and load
        a.calories = EffortScore.calories(profile: profile, sport: a.sport,
                                          movingTime: a.movingTime, avgHR: a.avgHR,
                                          avgSpeed: a.avgSpeed)
        a.relativeEffort = relativeEffort(activity: a, streams: streams, profile: profile)
        a.trainingLoad = trainingLoad(activity: a, profile: profile)

        // Map preview and route grouping
        let valid = streams.coords.filter { $0.isValid }
        a.hasGPS = valid.count > 5
        a.previewPath = Geo.downsample(Geo.simplify(valid, tolerance: 6), limit: 300)
        a.startCoord = valid.first
        a.routeSignature = RouteSignature.compute(valid)

        return a
    }

    static func movingTime(_ streams: Streams) -> TimeInterval {
        guard streams.count > 1 else { return 0 }
        var total = 0.0
        for i in 1..<streams.count {
            let dt = streams.time[i] - streams.time[i - 1]
            guard dt > 0, dt < 30 else { continue }
            if streams.moving[i] { total += dt }
        }
        return total
    }

    static func relativeEffort(activity: Activity, streams: Streams, profile: AthleteProfile) -> Double {
        if streams.hasHeartRate {
            return EffortScore.relativeEffort(zoneSeconds: activity.hrZoneTimes)
        }
        let gap = GradeAdjustedPace.adjustedPace(streams: streams, from: 0, to: streams.count - 1)
            ?? activity.avgPaceSecondsPerKm
        if activity.sport.isFoot {
            return EffortScore.estimatedEffort(movingTime: activity.movingTime,
                                               gradeAdjustedPace: gap,
                                               thresholdPace: profile.paceZones.thresholdPace)
        }
        if let np = activity.normalizedPower, np > 0 {
            let tss = PowerModel.trainingStress(normalizedPower: np, ftp: profile.powerZones.ftp,
                                                seconds: activity.movingTime)
            return tss * 1.1
        }
        return (activity.movingTime / 60) * 2.0
    }

    /// A single load number per activity, so runs and rides land on the same
    /// Fitness & Freshness chart.
    static func trainingLoad(activity: Activity, profile: AthleteProfile) -> Double {
        if activity.sport.isRide, let np = activity.normalizedPower, np > 0, profile.powerZones.ftp > 0 {
            return PowerModel.trainingStress(normalizedPower: np, ftp: profile.powerZones.ftp,
                                             seconds: activity.movingTime)
        }
        // Relative Effort and TSS run on similar scales for an hour at threshold,
        // so the effort score doubles as a load for everything else.
        return activity.relativeEffort
    }
}

/// A coarse fingerprint of a route, used to group repeats of the same loop the way
/// Strava's "matched runs" does.
enum RouteSignature {

    static func compute(_ coords: [Coord]) -> String {
        guard coords.count > 4 else { return "" }
        let simplified = Geo.downsample(coords, limit: 24)
        // Round to roughly 100 m so small GPS differences still match.
        var parts: [String] = []
        for c in simplified {
            let lat = (c.lat * 1000).rounded() / 1000
            let lon = (c.lon * 1000).rounded() / 1000
            parts.append(String(format: "%.3f,%.3f", lat, lon))
        }
        let distanceBucket = Int((Geo.totalDistance(coords) / 500).rounded())
        return "\(distanceBucket)|" + parts.joined(separator: ";")
    }

    /// True when two activities cover essentially the same ground.
    static func similar(_ a: Activity, _ b: Activity, tolerance: Double = 60) -> Bool {
        guard a.hasGPS, b.hasGPS else { return false }
        guard abs(a.distance - b.distance) < Swift.max(300, a.distance * 0.08) else { return false }
        let pa = Geo.downsample(a.previewPath, limit: 40)
        let pb = Geo.downsample(b.previewPath, limit: 40)
        guard pa.count > 8, pb.count == pa.count else { return false }
        var matched = 0
        for (x, y) in zip(pa, pb) where Geo.fastDistance(x, y) < tolerance {
            matched += 1
        }
        return Double(matched) / Double(pa.count) > 0.75
    }
}

/// Fitness, Fatigue and Form — the paid Strava chart, computed from daily load.
struct FitnessPoint: Identifiable, Hashable {
    var id: Date { date }
    var date: Date
    var fitness: Double     // 42-day exponentially weighted load
    var fatigue: Double     // 7-day
    var form: Double        // fitness minus fatigue
    var load: Double        // that day's load
}

enum FitnessCurve {

    static let fitnessTimeConstant = 42.0
    static let fatigueTimeConstant = 7.0

    static func build(activities: [Activity], through end: Date = Date(), daysBack: Int = 400) -> [FitnessPoint] {
        let cal = Calendar.current
        let endDay = cal.startOfDay(for: end)
        guard let start = cal.date(byAdding: .day, value: -daysBack, to: endDay) else { return [] }

        var dailyLoad: [Date: Double] = [:]
        for a in activities {
            let day = cal.startOfDay(for: a.startDate)
            guard day >= start, day <= endDay else { continue }
            dailyLoad[day, default: 0] += a.trainingLoad
        }

        var out: [FitnessPoint] = []
        var fitness = 0.0
        var fatigue = 0.0
        let fitnessAlpha = 1 - exp(-1 / fitnessTimeConstant)
        let fatigueAlpha = 1 - exp(-1 / fatigueTimeConstant)

        var day = start
        while day <= endDay {
            let load = dailyLoad[day] ?? 0
            fitness += fitnessAlpha * (load - fitness)
            fatigue += fatigueAlpha * (load - fatigue)
            out.append(FitnessPoint(date: day, fitness: fitness, fatigue: fatigue,
                                    form: fitness - fatigue, load: load))
            guard let next = cal.date(byAdding: .day, value: 1, to: day) else { break }
            day = next
        }
        return out
    }

    /// Plain-language reading of today's form, the way the paid feature phrases it.
    static func formDescription(_ form: Double) -> (String, String) {
        switch form {
        case 25...: return ("Very fresh", "Well rested. A good day to race or go hard.")
        case 5..<25: return ("Fresh", "Recovered and ready for quality work.")
        case (-10)..<5: return ("Neutral", "Balanced. Steady training is fine.")
        case (-30)..<(-10): return ("Building", "Carrying fatigue — this is where fitness is built.")
        default: return ("Overreaching", "Heavy fatigue. Consider an easier few days.")
        }
    }
}
