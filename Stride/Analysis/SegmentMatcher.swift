import Foundation

/// Finds every time an activity covered a saved segment. Strava does this on its
/// servers against a global segment set; here it runs on device against yours.
enum SegmentMatcher {

    /// How close you must pass to the segment's start or end for an effort to count.
    static let terminalRadius: Double = 25.0
    /// How far the middle of your track may stray from the segment line.
    static let corridorRadius: Double = 35.0
    /// Fraction of the segment that must be covered within the corridor.
    static let requiredCoverage: Double = 0.85

    static func findEfforts(activity: Activity,
                            activityID: UUID,
                            points: [TrackPoint],
                            segments: [Segment]) -> [SegmentEffort] {
        guard points.count > 4 else { return [] }
        let streams = Streams(points)
        guard let activityBox = BoundingBox(streams.coords) else { return [] }

        var efforts: [SegmentEffort] = []
        for segment in segments {
            // Cheap rejections first: wrong sport family, no geographic overlap.
            guard sportsCompatible(segment.sport, activity.sport) else { continue }
            guard let segBox = segment.boundingBox,
                  segBox.expanded(byMeters: 100).intersects(activityBox) else { continue }
            guard segment.distance > 50, activity.distance >= segment.distance * 0.9 else { continue }

            if let effort = bestEffort(segment: segment, streams: streams,
                                       activityID: activityID, date: activity.startDate) {
                efforts.append(effort)
            }
        }
        return efforts
    }

    static func sportsCompatible(_ a: SportType, _ b: SportType) -> Bool {
        if a.isRide != b.isRide { return false }
        return true
    }

    /// The fastest valid pass over the segment inside this activity.
    static func bestEffort(segment: Segment,
                           streams: Streams,
                           activityID: UUID,
                           date: Date) -> SegmentEffort? {
        guard let segStart = segment.startCoord, let segEnd = segment.endCoord else { return nil }

        // Candidate entry and exit samples.
        var entries: [Int] = []
        var exits: [Int] = []
        for i in 0..<streams.count {
            let c = streams.coords[i]
            guard c.isValid else { continue }
            if Geo.fastDistance(c, segStart) <= terminalRadius { entries.append(i) }
            if Geo.fastDistance(c, segEnd) <= terminalRadius { exits.append(i) }
        }
        guard !entries.isEmpty, !exits.isEmpty else { return nil }

        // Collapse runs of consecutive samples down to one representative each,
        // otherwise a slow pass produces hundreds of near-identical candidates.
        let entryGroups = collapse(entries)
        let exitGroups = collapse(exits)

        var best: SegmentEffort?
        for start in entryGroups {
            for end in exitGroups where end > start {
                let covered = streams.distance[end] - streams.distance[start]
                // Reject wandering: you must not cover far more ground than the segment.
                guard covered >= segment.distance * 0.85,
                      covered <= segment.distance * 1.25 else { continue }
                guard verifyCorridor(segment: segment, streams: streams, from: start, to: end) else { continue }

                let time = movingTime(streams, from: start, to: end)
                guard time > 1 else { continue }
                if best == nil || time < best!.time {
                    var effort = SegmentEffort(segmentID: segment.id,
                                               activityID: activityID,
                                               date: date.addingTimeInterval(streams.time[start]),
                                               time: time,
                                               startIndex: start,
                                               endIndex: end,
                                               avgHR: average(streams.heartRate, start, end),
                                               avgPower: average(streams.power, start, end),
                                               avgSpeed: segment.distance / time)
                    effort.isPR = false
                    best = effort
                }
            }
        }
        return best
    }

    private static func collapse(_ indices: [Int]) -> [Int] {
        guard !indices.isEmpty else { return [] }
        var out: [Int] = []
        var runStart = indices[0]
        var previous = indices[0]
        for i in indices.dropFirst() {
            if i - previous > 3 {
                out.append((runStart + previous) / 2)
                runStart = i
            }
            previous = i
        }
        out.append((runStart + previous) / 2)
        return out
    }

    /// Every point of the segment must have some part of your track near it.
    static func verifyCorridor(segment: Segment, streams: Streams, from: Int, to: Int) -> Bool {
        let checkPoints = Geo.downsample(segment.path, limit: 40)
        guard checkPoints.count > 2 else { return false }
        var hits = 0
        for p in checkPoints {
            var nearest = Double.greatestFiniteMagnitude
            var i = from
            while i < to {
                let d = Geo.perpendicularDistance(p, streams.coords[i], streams.coords[i + 1])
                if d < nearest { nearest = d }
                if nearest < corridorRadius { break }
                i += 1
            }
            if nearest <= corridorRadius { hits += 1 }
        }
        return Double(hits) / Double(checkPoints.count) >= requiredCoverage
    }

    static func movingTime(_ streams: Streams, from: Int, to: Int) -> TimeInterval {
        var total = 0.0
        var i = from + 1
        while i <= to && i < streams.count {
            let dt = streams.time[i] - streams.time[i - 1]
            if dt > 0 && dt < 30 && streams.moving[i] { total += dt }
            i += 1
        }
        return total > 0 ? total : streams.time[Swift.min(to, streams.count - 1)] - streams.time[from]
    }

    static func average(_ values: [Double?], _ from: Int, _ to: Int) -> Double? {
        var sum = 0.0
        var count = 0.0
        var i = from
        while i <= to && i < values.count {
            if let v = values[i] { sum += v; count += 1 }
            i += 1
        }
        return count > 0 ? sum / count : nil
    }

    /// Rank a segment's efforts fastest-first and flag the personal record.
    static func rank(_ efforts: [SegmentEffort]) -> [SegmentEffort] {
        let sorted = efforts.sorted { $0.time < $1.time }
        var out: [SegmentEffort] = []
        for (i, effort) in sorted.enumerated() {
            var e = effort
            e.rank = i + 1
            e.isPR = i == 0
            out.append(e)
        }
        return out
    }
}
