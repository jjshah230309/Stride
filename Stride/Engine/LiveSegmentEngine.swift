import Foundation
import Combine

/// Real-time racing against your own best time on a segment — Strava charges for
/// this one. It watches for you entering a starred segment, then tells you how you
/// are doing against your record as you go.
final class LiveSegmentEngine: ObservableObject {

    struct LiveState: Identifiable, Equatable {
        var id: UUID { segmentID }
        var segmentID: UUID
        var name: String
        var totalDistance: Double
        var startedAtElapsed: TimeInterval
        var startedAtDistance: Double
        var fraction: Double = 0
        var distanceCovered: Double = 0
        var elapsed: TimeInterval = 0
        var prTime: TimeInterval?
        /// Negative means ahead of your best.
        var deltaToPR: Double = 0
        var lastAnnouncedFraction: Double = 0
        var strayedSeconds: Double = 0

        var remaining: Double { Swift.max(0, totalDistance - distanceCovered) }

        static func == (a: LiveState, b: LiveState) -> Bool {
            a.segmentID == b.segmentID && a.fraction == b.fraction && a.elapsed == b.elapsed
        }
    }

    enum Event {
        case started(name: String, prTime: TimeInterval?)
        case progress(delta: Double, remaining: Double)
        case finished(name: String, time: TimeInterval, isPR: Bool, delta: Double?)
        case abandoned(name: String)
    }

    @Published var active: LiveState?
    @Published var nearby: [Segment] = []

    /// Segments eligible for live feedback, with their cumulative distance profiles.
    private var candidates: [(segment: Segment, cumulative: [Double])] = []
    private var personalBests: [UUID: TimeInterval] = [:]
    private var recentlyFinished: Set<UUID> = []
    private var lastCoord: Coord?

    /// Announce progress at these points through the segment.
    private let announcementPoints: [Double] = [0.25, 0.5, 0.75, 0.9]

    func load(segments: [Segment], bests: [UUID: TimeInterval], sport: SportType) {
        candidates = segments
            .filter { $0.isStarred && SegmentMatcher.sportsCompatible($0.sport, sport) && $0.path.count > 2 }
            .map { ($0, Geo.cumulativeDistance($0.path)) }
        personalBests = bests
        recentlyFinished.removeAll()
        active = nil
    }

    func reset() {
        active = nil
        recentlyFinished.removeAll()
        lastCoord = nil
    }

    /// Feed a position in. Returns whatever should be announced.
    func update(coord: Coord, elapsed: TimeInterval, totalDistance: Double, dt: Double) -> [Event] {
        var events: [Event] = []
        defer { lastCoord = coord }

        if var state = active {
            guard let entry = candidates.first(where: { $0.segment.id == state.segmentID }) else {
                active = nil
                return events
            }
            let segment = entry.segment
            let cumulative = entry.cumulative

            // Where along the segment are we?
            let (index, offPathDistance) = nearestIndex(on: segment.path, to: coord)
            if offPathDistance > 70 {
                state.strayedSeconds += dt
                if state.strayedSeconds > 20 {
                    events.append(.abandoned(name: segment.name))
                    active = nil
                    return events
                }
            } else {
                state.strayedSeconds = 0
            }

            state.elapsed = elapsed - state.startedAtElapsed
            state.distanceCovered = totalDistance - state.startedAtDistance
            let along = cumulative[Swift.min(index, cumulative.count - 1)]
            state.fraction = segment.distance > 0 ? Swift.min(1, along / segment.distance) : 0

            if let pr = state.prTime, state.fraction > 0.01 {
                let expected = pr * state.fraction
                state.deltaToPR = state.elapsed - expected
            }

            // Finished?
            let nearEnd = segment.endCoord.map { Geo.fastDistance(coord, $0) < SegmentMatcher.terminalRadius } ?? false
            if nearEnd && state.fraction > 0.8 {
                let time = state.elapsed
                let pr = personalBests[segment.id]
                let isPR = pr == nil || time < pr!
                events.append(.finished(name: segment.name, time: time, isPR: isPR,
                                        delta: pr.map { time - $0 }))
                if isPR { personalBests[segment.id] = time }
                recentlyFinished.insert(segment.id)
                active = nil
                return events
            }

            // Timed out — you clearly are not racing it any more.
            if let pr = state.prTime, state.elapsed > pr * 3 + 180 {
                events.append(.abandoned(name: segment.name))
                active = nil
                return events
            }
            if state.prTime == nil && state.distanceCovered > segment.distance * 1.6 {
                events.append(.abandoned(name: segment.name))
                active = nil
                return events
            }

            // Progress call-outs.
            for point in announcementPoints where state.fraction >= point && state.lastAnnouncedFraction < point {
                state.lastAnnouncedFraction = point
                if state.prTime != nil {
                    events.append(.progress(delta: state.deltaToPR, remaining: state.remaining))
                }
                break
            }

            active = state
            return events
        }

        // Not on a segment: look for one starting here.
        guard let previous = lastCoord else { return events }
        let heading = Geo.bearing(previous, coord)
        let moved = Geo.fastDistance(previous, coord)

        for entry in candidates {
            let segment = entry.segment
            guard !recentlyFinished.contains(segment.id) else { continue }
            guard let start = segment.startCoord else { continue }
            guard Geo.fastDistance(coord, start) < SegmentMatcher.terminalRadius else { continue }
            // Heading check keeps the reverse direction from triggering, but only
            // once we are actually moving.
            if moved > 3 && Geo.bearingDelta(heading, segment.startBearing) > 60 { continue }

            let pr = personalBests[segment.id]
            active = LiveState(segmentID: segment.id,
                               name: segment.name,
                               totalDistance: segment.distance,
                               startedAtElapsed: elapsed,
                               startedAtDistance: totalDistance,
                               prTime: pr)
            events.append(.started(name: segment.name, prTime: pr))
            break
        }
        return events
    }

    /// Index of the closest point on the path, plus how far off the path we are.
    private func nearestIndex(on path: [Coord], to coord: Coord) -> (Int, Double) {
        var bestIndex = 0
        var bestDistance = Double.greatestFiniteMagnitude
        for (i, p) in path.enumerated() {
            let d = Geo.fastDistance(p, coord)
            if d < bestDistance {
                bestDistance = d
                bestIndex = i
            }
        }
        return (bestIndex, bestDistance)
    }

    /// Segments whose start is within a few hundred metres — shown as "coming up".
    func updateNearby(coord: Coord) {
        nearby = candidates.compactMap { entry -> (Segment, Double)? in
            guard let start = entry.segment.startCoord else { return nil }
            let d = Geo.fastDistance(coord, start)
            return d < 500 ? (entry.segment, d) : nil
        }
        .sorted { $0.1 < $1.1 }
        .prefix(3)
        .map(\.0)
    }
}
