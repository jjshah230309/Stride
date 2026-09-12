import Foundation
import UniformTypeIdentifiers

/// One way in for every supported file. Sniffs the contents rather than trusting
/// the extension, because exports arrive with all sorts of names.
enum ActivityFile {

    enum Format: String {
        case gpx, tcx, fit

        var name: String { rawValue.uppercased() }
    }

    struct Imported {
        var format: Format
        var name: String
        var sport: SportType?
        var startDate: Date
        var points: [TrackPoint]
        var isRoute: Bool = false
        /// Set when the file itself says its data is damaged.
        var warning: String?
    }

    enum Failure: LocalizedError {
        case unrecognised
        case empty

        var errorDescription: String? {
            switch self {
            case .unrecognised: return "That is not a GPX, TCX or FIT file."
            case .empty: return "The file has no track data in it."
            }
        }
    }

    /// Types the file picker should offer.
    static var readableContentTypes: [UTType] {
        var types: [UTType] = [.xml, .data]
        if let gpx = UTType(filenameExtension: "gpx") { types.append(gpx) }
        if let tcx = UTType(filenameExtension: "tcx") { types.append(tcx) }
        if let fit = UTType(filenameExtension: "fit") { types.append(fit) }
        return types
    }

    static func detect(data: Data, filename: String = "") -> Format? {
        // FIT announces itself at a fixed offset in the header.
        if data.count > 12 {
            let signature = [UInt8](data[data.startIndex + 8 ..< data.startIndex + 12])
            if signature == Array(".FIT".utf8) { return .fit }
        }

        // For XML, look at the opening tags rather than the whole document.
        let prefixLength = Swift.min(data.count, 4096)
        let head = String(decoding: data.prefix(prefixLength), as: UTF8.self)
        if head.contains("<TrainingCenterDatabase") { return .tcx }
        if head.contains("<gpx") || head.contains("<trk") || head.contains("<rte") { return .gpx }

        switch (filename as NSString).pathExtension.lowercased() {
        case "fit": return .fit
        case "tcx": return .tcx
        case "gpx": return .gpx
        default: return nil
        }
    }

    static func load(data: Data, filename: String = "") throws -> Imported {
        guard let format = detect(data: data, filename: filename) else {
            throw Failure.unrecognised
        }

        switch format {
        case .fit:
            let decoder = FITDecoder()
            let result = try decoder.decode(data)
            guard !result.points.isEmpty else { throw Failure.empty }
            return Imported(format: .fit,
                            name: result.name.isEmpty ? defaultName(filename) : result.name,
                            sport: result.sport,
                            startDate: result.startDate,
                            points: result.points,
                            warning: decoder.checksumMismatch
                                ? "The file's checksum did not match, so it may be incomplete." : nil)

        case .tcx:
            guard let result = TCXImporter().parse(data: data), !result.points.isEmpty else {
                throw Failure.empty
            }
            // A TCX Id is a timestamp, which makes a poor activity name.
            let name = result.name.isEmpty || result.name.hasPrefix("20")
                ? defaultName(filename) : result.name
            return Imported(format: .tcx, name: name, sport: result.sport,
                            startDate: result.startDate, points: result.points)

        case .gpx:
            guard let result = GPXImporter().parse(data: data), !result.points.isEmpty else {
                throw Failure.empty
            }
            return Imported(format: .gpx,
                            name: result.name.isEmpty ? defaultName(filename) : result.name,
                            sport: sport(fromName: result.name),
                            startDate: result.startDate,
                            points: result.points,
                            isRoute: result.isRoute)
        }
    }

    /// Builds a finished activity, analysed and ready to store.
    static func makeActivity(from imported: Imported, profile: AthleteProfile) -> Activity {
        let sport = imported.sport ?? sport(fromName: imported.name) ?? .run
        var activity = Activity(name: imported.name, sport: sport, startDate: imported.startDate)
        activity = ActivityAnalyzer.analyse(activity: activity, points: imported.points,
                                            profile: profile)
        return activity
    }

    private static func defaultName(_ filename: String) -> String {
        let stem = (filename as NSString).deletingPathExtension
        return stem.isEmpty ? "Imported activity" : stem
    }

    /// Last resort when the file does not say which sport it was.
    static func sport(fromName name: String) -> SportType? {
        let lower = name.lowercased()
        if lower.contains("ride") || lower.contains("cycl") || lower.contains("bike") { return .ride }
        if lower.contains("walk") { return .walk }
        if lower.contains("hike") { return .hike }
        if lower.contains("run") { return .run }
        return nil
    }
}
