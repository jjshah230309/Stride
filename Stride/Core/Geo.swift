import Foundation
import CoreLocation

/// A Codable latitude/longitude pair. CLLocationCoordinate2D is not Codable.
struct Coord: Codable, Hashable {
    var lat: Double
    var lon: Double

    init(lat: Double, lon: Double) {
        self.lat = lat
        self.lon = lon
    }

    init(_ c: CLLocationCoordinate2D) {
        self.lat = c.latitude
        self.lon = c.longitude
    }

    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    var isValid: Bool {
        lat.isFinite && lon.isFinite && abs(lat) <= 90 && abs(lon) <= 180 && !(lat == 0 && lon == 0)
    }

    // Compact array encoding keeps track files small.
    init(from decoder: Decoder) throws {
        var c = try decoder.unkeyedContainer()
        lat = try c.decode(Double.self)
        lon = try c.decode(Double.self)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.unkeyedContainer()
        try c.encode(lat)
        try c.encode(lon)
    }
}

struct BoundingBox {
    var minLat: Double
    var maxLat: Double
    var minLon: Double
    var maxLon: Double

    init?(_ coords: [Coord]) {
        let valid = coords.filter { $0.isValid }
        guard let first = valid.first else { return nil }
        minLat = first.lat; maxLat = first.lat
        minLon = first.lon; maxLon = first.lon
        for c in valid {
            minLat = Swift.min(minLat, c.lat); maxLat = Swift.max(maxLat, c.lat)
            minLon = Swift.min(minLon, c.lon); maxLon = Swift.max(maxLon, c.lon)
        }
    }

    var center: Coord { Coord(lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2) }

    func expanded(byMeters m: Double) -> BoundingBox {
        let dLat = m / 111_320.0
        let dLon = m / (111_320.0 * Swift.max(0.01, cos(center.lat * .pi / 180)))
        var b = self
        b.minLat -= dLat; b.maxLat += dLat
        b.minLon -= dLon; b.maxLon += dLon
        return b
    }

    func intersects(_ other: BoundingBox) -> Bool {
        !(other.minLat > maxLat || other.maxLat < minLat || other.minLon > maxLon || other.maxLon < minLon)
    }

    func contains(_ c: Coord) -> Bool {
        c.lat >= minLat && c.lat <= maxLat && c.lon >= minLon && c.lon <= maxLon
    }
}

enum Geo {
    static let earthRadius: Double = 6_371_000.0

    /// Great-circle distance in metres.
    static func distance(_ a: Coord, _ b: Coord) -> Double {
        let lat1 = a.lat * .pi / 180
        let lat2 = b.lat * .pi / 180
        let dLat = (b.lat - a.lat) * .pi / 180
        let dLon = (b.lon - a.lon) * .pi / 180
        let sinDLat = sin(dLat / 2)
        let sinDLon = sin(dLon / 2)
        let h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2 * earthRadius * asin(Swift.min(1, sqrt(Swift.max(0, h))))
    }

    /// Fast planar approximation. Accurate to well under a metre at the scales we care about.
    static func fastDistance(_ a: Coord, _ b: Coord) -> Double {
        let meanLat = (a.lat + b.lat) / 2 * .pi / 180
        let dx = (b.lon - a.lon) * .pi / 180 * cos(meanLat) * earthRadius
        let dy = (b.lat - a.lat) * .pi / 180 * earthRadius
        return sqrt(dx * dx + dy * dy)
    }

    /// Initial bearing in degrees, 0 = north.
    static func bearing(_ a: Coord, _ b: Coord) -> Double {
        let lat1 = a.lat * .pi / 180
        let lat2 = b.lat * .pi / 180
        let dLon = (b.lon - a.lon) * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let deg = atan2(y, x) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }

    /// Smallest absolute difference between two bearings, in degrees.
    static func bearingDelta(_ a: Double, _ b: Double) -> Double {
        var d = abs(a - b).truncatingRemainder(dividingBy: 360)
        if d > 180 { d = 360 - d }
        return d
    }

    static func totalDistance(_ coords: [Coord]) -> Double {
        guard coords.count > 1 else { return 0 }
        var total = 0.0
        for i in 1..<coords.count {
            total += fastDistance(coords[i - 1], coords[i])
        }
        return total
    }

    /// Cumulative distance at each index.
    static func cumulativeDistance(_ coords: [Coord]) -> [Double] {
        var out = [Double](repeating: 0, count: coords.count)
        guard coords.count > 1 else { return out }
        for i in 1..<coords.count {
            out[i] = out[i - 1] + fastDistance(coords[i - 1], coords[i])
        }
        return out
    }

    /// Ramer–Douglas–Peucker simplification, tolerance in metres.
    static func simplify(_ coords: [Coord], tolerance: Double) -> [Coord] {
        guard coords.count > 2 else { return coords }
        var keep = [Bool](repeating: false, count: coords.count)
        keep[0] = true
        keep[coords.count - 1] = true
        var stack: [(Int, Int)] = [(0, coords.count - 1)]
        while let (start, end) = stack.popLast() {
            guard end > start + 1 else { continue }
            var maxDist = 0.0
            var maxIndex = start
            for i in (start + 1)..<end {
                let d = perpendicularDistance(coords[i], coords[start], coords[end])
                if d > maxDist { maxDist = d; maxIndex = i }
            }
            if maxDist > tolerance {
                keep[maxIndex] = true
                stack.append((start, maxIndex))
                stack.append((maxIndex, end))
            }
        }
        var out: [Coord] = []
        for (i, c) in coords.enumerated() where keep[i] { out.append(c) }
        return out
    }

    /// Distance from point to the segment a→b, in metres.
    static func perpendicularDistance(_ p: Coord, _ a: Coord, _ b: Coord) -> Double {
        let meanLat = a.lat * .pi / 180
        let mx = .pi / 180 * cos(meanLat) * earthRadius
        let my = Double.pi / 180 * earthRadius
        let px = p.lon * mx, py = p.lat * my
        let ax = a.lon * mx, ay = a.lat * my
        let bx = b.lon * mx, by = b.lat * my
        let dx = bx - ax, dy = by - ay
        let lenSq = dx * dx + dy * dy
        if lenSq < 1e-9 { return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay)) }
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = Swift.max(0, Swift.min(1, t))
        let cx = ax + t * dx, cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /// Evenly reduce a series to at most `limit` values, always keeping the ends.
    /// Generic so it works on coordinates and on the elevation arrays beside them.
    static func downsample<T>(_ values: [T], limit: Int) -> [T] {
        guard values.count > limit, limit > 2 else { return values }
        var out: [T] = []
        out.reserveCapacity(limit)
        let step = Double(values.count - 1) / Double(limit - 1)
        for i in 0..<limit {
            out.append(values[Int((Double(i) * step).rounded())])
        }
        return out
    }

    /// Interpolate a point at `fraction` of the way from a to b.
    static func interpolate(_ a: Coord, _ b: Coord, _ fraction: Double) -> Coord {
        Coord(lat: a.lat + (b.lat - a.lat) * fraction, lon: a.lon + (b.lon - a.lon) * fraction)
    }

    /// Resample a path so points sit at fixed distance intervals. Used for shape comparison.
    static func resampleByDistance(_ coords: [Coord], interval: Double) -> [Coord] {
        guard coords.count > 1, interval > 0 else { return coords }
        var out: [Coord] = [coords[0]]
        var carry = 0.0
        for i in 1..<coords.count {
            let a = coords[i - 1], b = coords[i]
            var segment = fastDistance(a, b)
            guard segment > 0 else { continue }
            var consumed = 0.0
            while carry + (segment - consumed) >= interval {
                let need = interval - carry
                consumed += need
                out.append(interpolate(a, b, consumed / segment))
                carry = 0
            }
            carry += segment - consumed
            segment = 0
        }
        if let last = coords.last, let end = out.last, fastDistance(end, last) > interval / 2 {
            out.append(last)
        }
        return out
    }
}
