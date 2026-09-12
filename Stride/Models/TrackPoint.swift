import Foundation

/// One recorded sample. Keys are short because a three-hour ride holds ~10,000 of these.
struct TrackPoint: Codable {
    var t: TimeInterval      // seconds since activity start
    var lat: Double
    var lon: Double
    var alt: Double          // metres, smoothed
    var d: Double            // cumulative distance in metres
    var v: Double            // speed in m/s
    var hr: Double?          // beats per minute
    var cad: Double?         // steps or revolutions per minute
    var pw: Double?          // watts (measured or estimated)
    var moving: Bool
    var acc: Double          // horizontal accuracy in metres

    var coord: Coord { Coord(lat: lat, lon: lon) }

    enum CodingKeys: String, CodingKey {
        case t, lat, lon, alt, d, v, hr, cad, pw, moving, acc
    }

    init(t: TimeInterval, lat: Double, lon: Double, alt: Double, d: Double, v: Double,
         hr: Double? = nil, cad: Double? = nil, pw: Double? = nil, moving: Bool = true, acc: Double = 5) {
        self.t = t; self.lat = lat; self.lon = lon; self.alt = alt
        self.d = d; self.v = v; self.hr = hr; self.cad = cad; self.pw = pw
        self.moving = moving; self.acc = acc
    }
}

/// Per-sample series pulled out of a track, so analysis code can work on flat arrays.
struct Streams {
    var time: [Double] = []
    var distance: [Double] = []
    var coords: [Coord] = []
    var altitude: [Double] = []
    var speed: [Double] = []
    var heartRate: [Double?] = []
    var cadence: [Double?] = []
    var power: [Double?] = []
    var moving: [Bool] = []

    var count: Int { time.count }
    var isEmpty: Bool { time.isEmpty }

    init() {}

    init(_ points: [TrackPoint]) {
        time = points.map(\.t)
        distance = points.map(\.d)
        coords = points.map(\.coord)
        altitude = points.map(\.alt)
        speed = points.map(\.v)
        heartRate = points.map(\.hr)
        cadence = points.map(\.cad)
        power = points.map(\.pw)
        moving = points.map(\.moving)
    }

    var hasHeartRate: Bool { heartRate.contains { $0 != nil } }
    var hasPower: Bool { power.contains { $0 != nil } }
    var hasCadence: Bool { cadence.contains { $0 != nil } }

    /// Grade at each sample, smoothed over roughly `windowMeters` to keep GPS noise out.
    func grade(windowMeters: Double = 30) -> [Double] {
        guard count > 2 else { return [Double](repeating: 0, count: count) }
        var out = [Double](repeating: 0, count: count)
        var lo = 0
        var hi = 0
        for i in 0..<count {
            while lo < i && distance[i] - distance[lo] > windowMeters { lo += 1 }
            hi = i
            while hi < count - 1 && distance[hi] - distance[i] < windowMeters { hi += 1 }
            let run = distance[hi] - distance[lo]
            if run > 3 {
                out[i] = (altitude[hi] - altitude[lo]) / run
            }
            out[i] = Swift.max(-0.45, Swift.min(0.45, out[i]))
        }
        return Smooth.movingAverage(out, window: 5)
    }

    /// Resample a nullable series onto a fixed 1 Hz grid, filling gaps by holding the last value.
    static func toOneHertz(_ values: [Double?], time: [Double], duration: Double) -> [Double] {
        let n = Swift.max(1, Int(duration.rounded()))
        var out = [Double](repeating: 0, count: n)
        guard !time.isEmpty else { return out }
        var idx = 0
        var last = 0.0
        for second in 0..<n {
            let target = Double(second)
            while idx < time.count - 1 && time[idx + 1] <= target { idx += 1 }
            if let v = values[Swift.min(idx, values.count - 1)] { last = v }
            out[second] = last
        }
        return out
    }
}
