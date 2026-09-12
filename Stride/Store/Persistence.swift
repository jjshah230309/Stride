import Foundation

/// Flat JSON files in the app's Documents folder. No database, no server, no account —
/// everything lives on the phone and rides along in an encrypted iPhone backup.
enum Store {

    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static var tracksDirectory: URL { documents.appendingPathComponent("Tracks", isDirectory: true) }
    static var photosDirectory: URL { documents.appendingPathComponent("Photos", isDirectory: true) }
    static var exportsDirectory: URL { documents.appendingPathComponent("Exports", isDirectory: true) }
    static var backupsDirectory: URL { documents.appendingPathComponent("Backups", isDirectory: true) }

    static func prepare() {
        for dir in [tracksDirectory, photosDirectory, exportsDirectory, backupsDirectory] {
            if !FileManager.default.fileExists(atPath: dir.path) {
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            }
        }
    }

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    static func url(_ name: String) -> URL {
        documents.appendingPathComponent(name)
    }

    static func save<T: Encodable>(_ value: T, to name: String) {
        do {
            let data = try encoder.encode(value)
            let target = url(name)
            // Write to a sibling first so a crash mid-write cannot destroy the file.
            let temp = target.appendingPathExtension("tmp")
            try data.write(to: temp, options: .atomic)
            if FileManager.default.fileExists(atPath: target.path) {
                _ = try FileManager.default.replaceItemAt(target, withItemAt: temp)
            } else {
                try FileManager.default.moveItem(at: temp, to: target)
            }
        } catch {
            NSLog("Stride: failed to save \(name): \(error.localizedDescription)")
        }
    }

    /// Files that existed but could not be read. The app must not save over these,
    /// or one bad decode would quietly erase a season of training.
    private(set) static var damagedFiles: [String] = []

    static func load<T: Decodable>(_ type: T.Type, from name: String) -> T? {
        let target = url(name)
        guard FileManager.default.fileExists(atPath: target.path) else { return nil }
        do {
            let data = try Data(contentsOf: target)
            return try decoder.decode(T.self, from: data)
        } catch {
            NSLog("Stride: failed to load \(name): \(error.localizedDescription)")
            quarantine(target, name: name)
            return nil
        }
    }

    /// Move an unreadable file aside under a dated name. Returning nil would leave
    /// the app looking empty, and the next save would overwrite the original bytes;
    /// this way they survive and can be recovered from the Files app.
    private static func quarantine(_ target: URL, name: String) {
        let stamp = Int(Date().timeIntervalSince1970)
        let backup = documents.appendingPathComponent("\(name).damaged-\(stamp)")
        do {
            try FileManager.default.moveItem(at: target, to: backup)
            damagedFiles.append(name)
            NSLog("Stride: kept the unreadable copy at \(backup.lastPathComponent)")
        } catch {
            NSLog("Stride: could not set aside \(name): \(error.localizedDescription)")
        }
    }

    // MARK: - Tracks

    static func trackURL(_ id: UUID) -> URL {
        tracksDirectory.appendingPathComponent("\(id.uuidString).json")
    }

    static func saveTrack(_ points: [TrackPoint], for id: UUID) {
        prepare()
        do {
            let data = try encoder.encode(points)
            try data.write(to: trackURL(id), options: .atomic)
        } catch {
            NSLog("Stride: failed to save track: \(error.localizedDescription)")
        }
    }

    static func loadTrack(_ id: UUID) -> [TrackPoint] {
        let target = trackURL(id)
        guard FileManager.default.fileExists(atPath: target.path) else { return [] }
        do {
            let data = try Data(contentsOf: target)
            return try decoder.decode([TrackPoint].self, from: data)
        } catch {
            return []
        }
    }

    static func deleteTrack(_ id: UUID) {
        try? FileManager.default.removeItem(at: trackURL(id))
    }

    // MARK: - Photos

    static func photoURL(_ filename: String) -> URL {
        photosDirectory.appendingPathComponent(filename)
    }

    static func savePhoto(_ data: Data) -> String? {
        prepare()
        let filename = UUID().uuidString + ".jpg"
        do {
            try data.write(to: photoURL(filename), options: .atomic)
            return filename
        } catch {
            return nil
        }
    }

    static func deletePhoto(_ filename: String) {
        try? FileManager.default.removeItem(at: photoURL(filename))
    }

    // MARK: - Crash recovery

    /// A recording in progress is flushed here every few seconds so a crash,
    /// a battery death or an iOS eviction does not lose the workout.
    static let recoveryFile = "recording-in-progress.json"

    struct RecoverySnapshot: Codable {
        var activityID: UUID
        var sport: SportType
        var startDate: Date
        var points: [TrackPoint]
        var elapsed: TimeInterval
        var pausedDuration: TimeInterval
        var laps: [Lap]
        var savedAt: Date
    }

    static func writeRecovery(_ snapshot: RecoverySnapshot) {
        save(snapshot, to: recoveryFile)
    }

    static func readRecovery() -> RecoverySnapshot? {
        load(RecoverySnapshot.self, from: recoveryFile)
    }

    static func clearRecovery() {
        try? FileManager.default.removeItem(at: url(recoveryFile))
    }
}
