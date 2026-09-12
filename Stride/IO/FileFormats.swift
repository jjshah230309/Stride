import Foundation

/// GPX and TCX writers, so your data is never locked in this app.
enum Exporter {

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    static func gpx(activity: Activity, points: [TrackPoint]) -> String {
        var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Stride" xmlns="http://www.topografix.com/GPX/1/1" \
        xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
          <metadata>
            <name>\(escape(activity.name))</name>
            <time>\(isoFormatter.string(from: activity.startDate))</time>
          </metadata>
          <trk>
            <name>\(escape(activity.name))</name>
            <type>\(activity.sport.rawValue)</type>
            <trkseg>

        """
        for p in points where p.coord.isValid {
            let time = isoFormatter.string(from: activity.startDate.addingTimeInterval(p.t))
            xml += "      <trkpt lat=\"\(p.lat)\" lon=\"\(p.lon)\">\n"
            xml += "        <ele>\(String(format: "%.1f", p.alt))</ele>\n"
            xml += "        <time>\(time)</time>\n"
            if p.hr != nil || p.cad != nil || p.pw != nil {
                xml += "        <extensions>\n"
                if let pw = p.pw {
                    xml += "          <power>\(Int(pw))</power>\n"
                }
                if p.hr != nil || p.cad != nil {
                    xml += "          <gpxtpx:TrackPointExtension>\n"
                    if let hr = p.hr { xml += "            <gpxtpx:hr>\(Int(hr))</gpxtpx:hr>\n" }
                    if let cad = p.cad { xml += "            <gpxtpx:cad>\(Int(cad))</gpxtpx:cad>\n" }
                    xml += "          </gpxtpx:TrackPointExtension>\n"
                }
                xml += "        </extensions>\n"
            }
            xml += "      </trkpt>\n"
        }
        xml += """
            </trkseg>
          </trk>
        </gpx>
        """
        return xml
    }

    static func gpx(route: SavedRoute) -> String {
        var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Stride" xmlns="http://www.topografix.com/GPX/1/1">
          <rte>
            <name>\(escape(route.name))</name>

        """
        for (i, c) in route.path.enumerated() {
            let ele = i < route.elevations.count ? route.elevations[i] : 0
            xml += "    <rtept lat=\"\(c.lat)\" lon=\"\(c.lon)\"><ele>\(String(format: "%.1f", ele))</ele></rtept>\n"
        }
        xml += """
          </rte>
        </gpx>
        """
        return xml
    }

    static func tcx(activity: Activity, points: [TrackPoint]) -> String {
        let sport = activity.sport.isRide ? "Biking" : (activity.sport.isRun ? "Running" : "Other")
        var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" \
        xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">
          <Activities>
            <Activity Sport="\(sport)">
              <Id>\(isoFormatter.string(from: activity.startDate))</Id>
              <Lap StartTime="\(isoFormatter.string(from: activity.startDate))">
                <TotalTimeSeconds>\(String(format: "%.1f", activity.movingTime))</TotalTimeSeconds>
                <DistanceMeters>\(String(format: "%.1f", activity.distance))</DistanceMeters>
                <Calories>\(Int(activity.calories))</Calories>
                <Intensity>Active</Intensity>
                <TriggerMethod>Manual</TriggerMethod>
                <Track>

        """
        for p in points where p.coord.isValid {
            let time = isoFormatter.string(from: activity.startDate.addingTimeInterval(p.t))
            xml += "          <Trackpoint>\n"
            xml += "            <Time>\(time)</Time>\n"
            xml += "            <Position><LatitudeDegrees>\(p.lat)</LatitudeDegrees><LongitudeDegrees>\(p.lon)</LongitudeDegrees></Position>\n"
            xml += "            <AltitudeMeters>\(String(format: "%.1f", p.alt))</AltitudeMeters>\n"
            xml += "            <DistanceMeters>\(String(format: "%.1f", p.d))</DistanceMeters>\n"
            if let hr = p.hr {
                xml += "            <HeartRateBpm><Value>\(Int(hr))</Value></HeartRateBpm>\n"
            }
            if let cad = p.cad, activity.sport.isRide {
                xml += "            <Cadence>\(Int(cad))</Cadence>\n"
            }
            if p.pw != nil || (p.cad != nil && !activity.sport.isRide) {
                xml += "            <Extensions><ns3:TPX>\n"
                if let pw = p.pw { xml += "              <ns3:Watts>\(Int(pw))</ns3:Watts>\n" }
                if let cad = p.cad, !activity.sport.isRide {
                    xml += "              <ns3:RunCadence>\(Int(cad / 2))</ns3:RunCadence>\n"
                }
                xml += "            </ns3:TPX></Extensions>\n"
            }
            xml += "          </Trackpoint>\n"
        }
        xml += """
                </Track>
              </Lap>
            </Activity>
          </Activities>
        </TrainingCenterDatabase>
        """
        return xml
    }

    static func csv(activities: [Activity], units: UnitSystem) -> String {
        var rows = ["Date,Name,Sport,Distance (\(units.distanceUnit)),Moving Time (s),Elapsed (s),Elevation (\(units.elevationUnit)),Avg HR,Max HR,Avg Power,Calories,Relative Effort"]
        let df = ISO8601DateFormatter()
        for a in activities {
            let name = a.name.replacingOccurrences(of: ",", with: " ")
            rows.append([
                df.string(from: a.startDate),
                name,
                a.sport.name,
                String(format: "%.3f", a.distance / units.metersPerUnit),
                String(format: "%.0f", a.movingTime),
                String(format: "%.0f", a.elapsed),
                String(format: "%.0f", a.elevationGain / units.metersPerElevationUnit),
                Fmt.int(a.avgHR),
                Fmt.int(a.maxHR),
                Fmt.int(a.avgPower),
                String(format: "%.0f", a.calories),
                String(format: "%.0f", a.relativeEffort)
            ].joined(separator: ","))
        }
        return rows.joined(separator: "\n")
    }

    static func fit(activity: Activity, points: [TrackPoint]) -> Data {
        FITEncoder.encode(activity: activity, points: points)
    }

    static func write(_ data: Data, filename: String) -> URL? {
        Store.prepare()
        let url = Store.exportsDirectory.appendingPathComponent(filename)
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }

    static func write(_ contents: String, filename: String) -> URL? {
        Store.prepare()
        let url = Store.exportsDirectory.appendingPathComponent(filename)
        do {
            try contents.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }

    private static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }
}

/// A small GPX reader, enough to pull in tracks exported from Strava, Garmin or Wahoo.
final class GPXImporter: NSObject, XMLParserDelegate {

    struct Result {
        var name: String = ""
        var points: [TrackPoint] = []
        var startDate: Date = Date()
        var isRoute: Bool = false
    }

    private var result = Result()
    private var currentElement = ""
    private var currentText = ""
    private var pendingLat: Double?
    private var pendingLon: Double?
    private var pendingEle: Double?
    private var pendingTime: Date?
    private var pendingHR: Double?
    private var pendingCad: Double?
    private var pendingPower: Double?
    private var firstDate: Date?
    private var lastCoord: Coord?
    private var cumulative: Double = 0
    private var inMetadata = false

    private let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private let plainFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    func parse(data: Data) -> Result? {
        result = Result()
        cumulative = 0
        lastCoord = nil
        firstDate = nil
        let parser = XMLParser(data: data)
        parser.delegate = self
        guard parser.parse() else { return nil }
        result.startDate = firstDate ?? Date()
        return result.points.isEmpty ? nil : result
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        currentElement = elementName
        currentText = ""
        if elementName == "metadata" { inMetadata = true }
        if elementName == "trkpt" || elementName == "rtept" || elementName == "wpt" {
            pendingLat = Double(attributeDict["lat"] ?? "")
            pendingLon = Double(attributeDict["lon"] ?? "")
            pendingEle = nil
            pendingTime = nil
            pendingHR = nil
            pendingCad = nil
            pendingPower = nil
            if elementName == "rtept" { result.isRoute = true }
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?) {
        let text = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
        switch elementName {
        case "metadata":
            inMetadata = false
        case "name":
            if result.name.isEmpty && !text.isEmpty { result.name = text }
        case "ele":
            pendingEle = Double(text)
        case "time":
            if !inMetadata {
                pendingTime = formatter.date(from: text) ?? plainFormatter.date(from: text)
            }
        case "hr", "gpxtpx:hr", "ns3:HeartRateBpm":
            pendingHR = Double(text)
        case "cad", "gpxtpx:cad":
            pendingCad = Double(text)
        case "power", "ns3:Watts":
            pendingPower = Double(text)
        case "trkpt", "rtept", "wpt":
            guard let lat = pendingLat, let lon = pendingLon else { break }
            let coord = Coord(lat: lat, lon: lon)
            guard coord.isValid else { break }
            if firstDate == nil { firstDate = pendingTime ?? Date() }
            var elapsed = Double(result.points.count)
            if let stamp = pendingTime, let start = firstDate {
                elapsed = stamp.timeIntervalSince(start)
            }
            var speed = 0.0
            if let previous = lastCoord {
                let step = Geo.fastDistance(previous, coord)
                cumulative += step
                if let last = result.points.last, elapsed > last.t {
                    speed = step / (elapsed - last.t)
                }
            }
            lastCoord = coord
            result.points.append(TrackPoint(t: Swift.max(0, elapsed),
                                            lat: lat, lon: lon,
                                            alt: pendingEle ?? 0,
                                            d: cumulative,
                                            v: speed,
                                            hr: pendingHR,
                                            cad: pendingCad,
                                            pw: pendingPower,
                                            moving: true,
                                            acc: 5))
        default:
            break
        }
        currentText = ""
    }
}
