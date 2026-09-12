import Foundation

enum BestEffortFinder {

    static let runDistances: [(String, Double)] = [
        ("400 m", 400), ("1/2 mile", 804.672), ("1 km", 1000), ("1 mile", 1609.344),
        ("2 mile", 3218.688), ("5 km", 5000), ("10 km", 10000), ("15 km", 15000),
        ("10 mile", 16093.44), ("20 km", 20000), ("Half Marathon", 21097.5),
        ("30 km", 30000), ("Marathon", 42195)
    ]

    static let walkDistances: [(String, Double)] = [
        ("1 km", 1000), ("1 mile", 1609.344), ("5 km", 5000), ("10 km", 10000)
    ]

    /// Fastest window covering each standard distance. A two-pointer sweep over the
    /// cumulative distance array, so this stays linear per distance.
    static func find(streams: Streams, sport: SportType) -> [BestEffort] {
        guard streams.count > 2 else { return [] }
        let table = sport.isRun ? runDistances : (sport.isFoot ? walkDistances : [])
        guard !table.isEmpty else { return [] }
        let total = streams.distance.last ?? 0
        var results: [BestEffort] = []

        for (name, target) in table where target <= total {
            var best: (time: TimeInterval, start: Int, end: Int)?
            var start = 0
            for end in 1..<streams.count {
                while start < end && streams.distance[end] - streams.distance[start] >= target {
                    let elapsed = streams.time[end] - streams.time[start]
                    // Interpolate the overshoot so a sample landing past the mark is not penalised.
                    let covered = streams.distance[end] - streams.distance[start]
                    let overshoot = covered - target
                    var adjusted = elapsed
                    if overshoot > 0, end > 0 {
                        let segmentDistance = streams.distance[end] - streams.distance[end - 1]
                        let segmentTime = streams.time[end] - streams.time[end - 1]
                        if segmentDistance > 0 {
                            adjusted = elapsed - (overshoot / segmentDistance) * segmentTime
                        }
                    }
                    if adjusted > 0 && (best == nil || adjusted < best!.time) {
                        best = (adjusted, start, end)
                    }
                    start += 1
                }
            }
            if let b = best, b.time > 0 {
                results.append(BestEffort(name: name, distance: target, time: b.time,
                                          startIndex: b.start, endIndex: b.end,
                                          startOffset: streams.time[b.start]))
            }
        }
        return results
    }
}

enum PowerCurveBuilder {

    static let durations: [TimeInterval] = [
        1, 5, 10, 15, 20, 30, 45, 60, 120, 180, 300, 480, 600, 720, 900,
        1200, 1800, 2700, 3600, 5400, 7200, 10800, 14400
    ]

    /// Best rolling average power for each duration, from a 1 Hz watts series.
    static func build(wattsPerSecond watts: [Double]) -> [PowerCurvePoint] {
        guard watts.count > 1 else { return [] }
        var prefix = [Double](repeating: 0, count: watts.count + 1)
        for i in 0..<watts.count { prefix[i + 1] = prefix[i] + watts[i] }

        var out: [PowerCurvePoint] = []
        for d in durations {
            let window = Int(d)
            guard window <= watts.count else { continue }
            var best = 0.0
            var i = 0
            while i + window <= watts.count {
                let avg = (prefix[i + window] - prefix[i]) / Double(window)
                if avg > best { best = avg }
                i += 1
            }
            if best > 0 { out.append(PowerCurvePoint(duration: d, watts: best)) }
        }
        return out
    }

    /// The same idea applied to heart rate or speed, for the "best efforts" chart.
    static func bestRollingAverage(_ series: [Double], seconds: Int) -> Double? {
        guard seconds > 0, series.count >= seconds else { return nil }
        var sum = series[0..<seconds].reduce(0, +)
        var best = sum
        var i = seconds
        while i < series.count {
            sum += series[i] - series[i - seconds]
            if sum > best { best = sum }
            i += 1
        }
        return best / Double(seconds)
    }

    /// Estimated functional threshold power: 95% of the best twenty minutes,
    /// or the best hour outright if one exists.
    static func estimateFTP(from curve: [PowerCurvePoint]) -> Double? {
        if let hour = curve.first(where: { $0.duration == 3600 }) { return hour.watts }
        if let twenty = curve.first(where: { $0.duration == 1200 }) { return twenty.watts * 0.95 }
        if let eight = curve.first(where: { $0.duration == 480 }) { return eight.watts * 0.90 }
        return nil
    }

    /// Merge many curves into an all-time best.
    static func combine(_ curves: [[PowerCurvePoint]]) -> [PowerCurvePoint] {
        var best: [TimeInterval: Double] = [:]
        for curve in curves {
            for point in curve {
                best[point.duration] = Swift.max(best[point.duration] ?? 0, point.watts)
            }
        }
        return best.map { PowerCurvePoint(duration: $0.key, watts: $0.value) }
            .sorted { $0.duration < $1.duration }
    }
}

enum SplitBuilder {

    /// Auto splits at every `unitMeters` of distance, plus a final partial split.
    static func build(streams: Streams, unitMeters: Double) -> [Split] {
        guard streams.count > 1, unitMeters > 0 else { return [] }
        let grades = streams.grade()
        var splits: [Split] = []
        var index = 1
        var startIdx = 0
        var nextMark = unitMeters

        func finish(_ endIdx: Int, partial: Bool) {
            guard endIdx > startIdx else { return }
            var moving = 0.0
            var gain = 0.0
            var loss = 0.0
            var hrSum = 0.0, hrCount = 0.0
            var pwSum = 0.0, pwCount = 0.0
            var cadSum = 0.0, cadCount = 0.0
            var equivalentDistance = 0.0
            var i = startIdx + 1
            while i <= endIdx {
                let dt = streams.time[i] - streams.time[i - 1]
                let dd = streams.distance[i] - streams.distance[i - 1]
                if streams.moving[i] && dt > 0 && dt < 30 { moving += dt }
                let dz = streams.altitude[i] - streams.altitude[i - 1]
                if dz > 0 { gain += dz } else { loss += -dz }
                if let hr = streams.heartRate[i] { hrSum += hr; hrCount += 1 }
                if let pw = streams.power[i] { pwSum += pw; pwCount += 1 }
                if let cad = streams.cadence[i] { cadSum += cad; cadCount += 1 }
                if dd > 0 { equivalentDistance += dd * GradeAdjustedPace.factor(gradient: grades[i]) }
                i += 1
            }
            let distance = streams.distance[endIdx] - streams.distance[startIdx]
            let elapsed = streams.time[endIdx] - streams.time[startIdx]
            var split = Split(index: index,
                              distance: distance,
                              elapsed: elapsed,
                              moving: moving > 0 ? moving : elapsed,
                              elevGain: gain,
                              elevLoss: loss,
                              avgHR: hrCount > 0 ? hrSum / hrCount : nil,
                              avgPower: pwCount > 0 ? pwSum / pwCount : nil,
                              avgCadence: cadCount > 0 ? cadSum / cadCount : nil,
                              gapSeconds: nil,
                              isPartial: partial)
            if equivalentDistance > 1 && moving > 0 {
                split.gapSeconds = moving / (equivalentDistance / unitMeters)
            }
            splits.append(split)
            index += 1
        }

        for i in 1..<streams.count {
            while streams.distance[i] >= nextMark {
                finish(i, partial: false)
                startIdx = i
                nextMark += unitMeters
            }
        }
        if let last = streams.distance.last, last - streams.distance[startIdx] > unitMeters * 0.02 {
            finish(streams.count - 1, partial: true)
        }
        // Smooth the elevation figures — raw per-sample deltas overstate the climbing.
        for i in splits.indices {
            splits[i].elevGain = splits[i].elevGain * 0.75
            splits[i].elevLoss = splits[i].elevLoss * 0.75
        }
        return splits
    }
}

enum ZoneAnalysis {

    /// Seconds spent in each heart-rate zone.
    static func heartRateTimes(streams: Streams, zones: HeartRateZones) -> [TimeInterval] {
        var out = [TimeInterval](repeating: 0, count: 5)
        guard streams.count > 1 else { return out }
        for i in 1..<streams.count {
            guard let hr = streams.heartRate[i] else { continue }
            let dt = streams.time[i] - streams.time[i - 1]
            guard dt > 0, dt < 30 else { continue }
            out[zones.zone(for: hr)] += dt
        }
        return out
    }

    static func powerTimes(streams: Streams, zones: PowerZones) -> [TimeInterval] {
        var out = [TimeInterval](repeating: 0, count: zones.bounds.count)
        guard streams.count > 1 else { return out }
        for i in 1..<streams.count {
            guard let pw = streams.power[i] else { continue }
            let dt = streams.time[i] - streams.time[i - 1]
            guard dt > 0, dt < 30 else { continue }
            out[zones.zone(for: pw)] += dt
        }
        return out
    }

    static func paceTimes(streams: Streams, zones: PaceZones) -> [TimeInterval] {
        var out = [TimeInterval](repeating: 0, count: PaceZones.names.count)
        guard streams.count > 1 else { return out }
        for i in 1..<streams.count {
            let dt = streams.time[i] - streams.time[i - 1]
            guard dt > 0, dt < 30, streams.moving[i], streams.speed[i] > 0.4 else { continue }
            let secondsPerKm = 1000.0 / streams.speed[i]
            out[zones.zone(for: secondsPerKm)] += dt
        }
        return out
    }
}
