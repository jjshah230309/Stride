import Foundation

/// Decodes a folder written by the Android app using the iOS app's real Codable models and
/// readers. If this passes, an iPhone could open the same folder.
func verifyDirectory(_ dir: URL) {
    var failures = 0
    func check(_ what: String, _ body: () throws -> String) {
        do {
            print("ok   \(what): \(try body())")
        } catch {
            failures += 1
            print("FAIL \(what): \(error)")
        }
    }
    func decode<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
        let data = try Data(contentsOf: dir.appendingPathComponent(name))
        return try Store.decoder.decode(T.self, from: data)
    }
    let fm = FileManager.default
    func exists(_ name: String) -> Bool { fm.fileExists(atPath: dir.appendingPathComponent(name).path) }

    if exists("activities.json") { check("activities.json") { "\(try decode([Activity].self, "activities.json").count) activities" } }
    if exists("segments.json") { check("segments.json") { "\(try decode([Segment].self, "segments.json").count) segments" } }
    if exists("segment-efforts.json") { check("segment-efforts.json") { "\(try decode([SegmentEffort].self, "segment-efforts.json").count) efforts" } }
    if exists("routes.json") { check("routes.json") { "\(try decode([SavedRoute].self, "routes.json").count) routes" } }
    if exists("gear.json") { check("gear.json") { "\(try decode([Gear].self, "gear.json").count) gear" } }
    if exists("goals.json") { check("goals.json") { "\(try decode([Goal].self, "goals.json").count) goals" } }
    if exists("settings.json") {
        check("settings.json") {
            // AppSettings decodes leniently, so also decode every section strictly: a damaged
            // section would silently reset on an iPhone.
            let data = try Data(contentsOf: dir.appendingPathComponent("settings.json"))
            guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw NSError(domain: "verify", code: 1) }
            func strict<T: Decodable>(_ type: T.Type, _ key: String) throws {
                guard let section = obj[key] else { throw NSError(domain: "verify", code: 2, userInfo: [NSLocalizedDescriptionKey: "missing \(key)"]) }
                _ = try Store.decoder.decode(T.self, from: JSONSerialization.data(withJSONObject: section))
            }
            try strict(AthleteProfile.self, "profile")
            try strict(DisplaySettings.self, "display")
            try strict(VoiceSettings.self, "voice")
            try strict(BackupSettings.self, "backup")
            let s = try decode(AppSettings.self, "settings.json")
            return "strict sections ok, units \(s.units.rawValue)"
        }
    }
    if exists("recording-in-progress.json") {
        check("recording-in-progress.json") { "\(try decode(Store.RecoverySnapshot.self, "recording-in-progress.json").points.count) points" }
    }
    let tracks = dir.appendingPathComponent("Tracks")
    if let names = try? fm.contentsOfDirectory(atPath: tracks.path) {
        for name in names.sorted() where name.hasSuffix(".json") {
            check("Tracks/\(name)") {
                let points = try Store.decoder.decode([TrackPoint].self, from: Data(contentsOf: tracks.appendingPathComponent(name)))
                guard UUID(uuidString: String(name.dropLast(5))) != nil else { throw NSError(domain: "verify", code: 3, userInfo: [NSLocalizedDescriptionKey: "file name is not a UUID"]) }
                guard name == name.uppercased().replacingOccurrences(of: ".JSON", with: ".json") else { throw NSError(domain: "verify", code: 4, userInfo: [NSLocalizedDescriptionKey: "track file name should be an upper case UUID"]) }
                return "\(points.count) points"
            }
        }
    }
    for url in ((try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []).sorted(by: { $0.path < $1.path }) where url.pathExtension == Backup.fileExtension {
        check(url.lastPathComponent) {
            let summary = try Backup.inspect(url)
            let restored = try Backup.read(url, existingActivityIDs: [], mode: .replace)
            guard restored.skipped == 0 else { throw NSError(domain: "verify", code: 5, userInfo: [NSLocalizedDescriptionKey: "\(restored.skipped) entries skipped"]) }
            guard restored.activities.count == summary.activities else { throw NSError(domain: "verify", code: 6, userInfo: [NSLocalizedDescriptionKey: "header says \(summary.activities) activities, read \(restored.activities.count)"]) }
            return "\(summary.activities) activities, \(summary.segments) segments, \(summary.routes) routes, \(summary.gear) gear, \(summary.goals) goals"
        }
    }
    if failures > 0 {
        print("\(failures) failure(s)")
        exit(1)
    }
    print("everything decodes with the iOS models")
}
