import Foundation

/// Reads Garmin's Training Center XML. Unlike GPX, heart rate and cadence are
/// first-class here, so a TCX from a watch usually carries a complete trace.
final class TCXImporter: NSObject, XMLParserDelegate {

    struct Result {
        var name: String = ""
        var sport: SportType?
        var startDate: Date = Date()
        var points: [TrackPoint] = []
        var totalDistance: Double?
        var totalTime: TimeInterval?
        var calories: Double?
    }

    private var result = Result()
    private var text = ""
    private var path: [String] = []

    private var pendingLat: Double?
    private var pendingLon: Double?
    private var pendingAltitude: Double?
    private var pendingDistance: Double?
    private var pendingTime: Date?
    private var pendingHeartRate: Double?
    private var pendingCadence: Double?
    private var pendingRunCadence: Double?
    private var pendingPower: Double?
    private var pendingSpeed: Double?

    private var firstDate: Date?
    private var lastCoord: Coord?
    private var lastElapsed: TimeInterval = 0
    private var measured: Double = 0

    private let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    func parse(data: Data) -> Result? {
        result = Result()
        path.removeAll()
        firstDate = nil
        lastCoord = nil
        measured = 0

        let parser = XMLParser(data: data)
        parser.delegate = self
        parser.shouldProcessNamespaces = false
        guard parser.parse() else { return result.points.isEmpty ? nil : result }
        guard !result.points.isEmpty else { return nil }
        result.startDate = firstDate ?? Date()
        return result
    }

    /// Namespaced names arrive as "ns3:Watts"; only the local part matters.
    private func localName(_ name: String) -> String {
        guard let colon = name.lastIndex(of: ":") else { return name }
        return String(name[name.index(after: colon)...])
    }

    private func date(from string: String) -> Date? {
        fractional.date(from: string) ?? plain.date(from: string)
    }

    // MARK: - Parser delegate

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        let name = localName(elementName)
        path.append(name)
        text = ""

        switch name {
        case "Activity":
            if let sport = attributeDict["Sport"] {
                switch sport.lowercased() {
                case "running": result.sport = .run
                case "biking": result.sport = .ride
                case "walking": result.sport = .walk
                case "hiking": result.sport = .hike
                default: break
                }
            }
        case "Trackpoint":
            pendingLat = nil; pendingLon = nil; pendingAltitude = nil
            pendingDistance = nil; pendingTime = nil
            pendingHeartRate = nil; pendingCadence = nil
            pendingRunCadence = nil; pendingPower = nil; pendingSpeed = nil
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        text += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?) {
        let name = localName(elementName)
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let insideTrackpoint = path.contains("Trackpoint")

        switch name {
        case "Id":
            if result.name.isEmpty, !value.isEmpty { result.name = value }
        case "Notes":
            if result.name.isEmpty, !value.isEmpty { result.name = value }
        case "Time":
            if insideTrackpoint { pendingTime = date(from: value) }
        case "LatitudeDegrees":
            pendingLat = Double(value)
        case "LongitudeDegrees":
            pendingLon = Double(value)
        case "AltitudeMeters":
            if insideTrackpoint { pendingAltitude = Double(value) }
        case "DistanceMeters":
            if insideTrackpoint {
                pendingDistance = Double(value)
            } else if let total = Double(value) {
                result.totalDistance = (result.totalDistance ?? 0) + total
            }
        case "Value":
            // Only the Value inside a Trackpoint's HeartRateBpm is a live reading.
            if insideTrackpoint, path.contains("HeartRateBpm") {
                pendingHeartRate = Double(value)
            }
        case "Cadence":
            if insideTrackpoint { pendingCadence = Double(value) }
        case "RunCadence":
            // Garmin writes one foot; Stride stores both.
            if let raw = Double(value) { pendingRunCadence = raw * 2 }
        case "Watts":
            pendingPower = Double(value)
        case "Speed":
            if insideTrackpoint { pendingSpeed = Double(value) }
        case "Calories":
            if let raw = Double(value) { result.calories = (result.calories ?? 0) + raw }
        case "TotalTimeSeconds":
            if let raw = Double(value) { result.totalTime = (result.totalTime ?? 0) + raw }
        case "Trackpoint":
            appendPoint()
        default:
            break
        }

        text = ""
        if let last = path.last, last == name { path.removeLast() }
    }

    private func appendPoint() {
        // A trackpoint with neither a position nor a time carries nothing usable.
        var coordinate: Coord?
        if let lat = pendingLat, let lon = pendingLon {
            let candidate = Coord(lat: lat, lon: lon)
            if candidate.isValid { coordinate = candidate }
        }
        guard coordinate != nil || pendingTime != nil else { return }

        if firstDate == nil { firstDate = pendingTime ?? Date() }
        var elapsed = Double(result.points.count)
        if let stamp = pendingTime, let start = firstDate {
            elapsed = max(0, stamp.timeIntervalSince(start))
        }

        if let coordinate, let previous = lastCoord {
            measured += Geo.fastDistance(previous, coordinate)
        }
        let distance = pendingDistance ?? measured

        var speed = pendingSpeed ?? 0
        if speed == 0, let coordinate, let previous = lastCoord, elapsed > lastElapsed {
            speed = Geo.fastDistance(previous, coordinate) / (elapsed - lastElapsed)
        }

        result.points.append(TrackPoint(t: elapsed,
                                        lat: coordinate?.lat ?? 0,
                                        lon: coordinate?.lon ?? 0,
                                        alt: pendingAltitude ?? 0,
                                        d: distance,
                                        v: speed,
                                        hr: pendingHeartRate,
                                        cad: pendingRunCadence ?? pendingCadence,
                                        pw: pendingPower,
                                        moving: true,
                                        acc: 5))
        if let coordinate { lastCoord = coordinate }
        lastElapsed = elapsed
    }
}
