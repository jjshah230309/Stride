import Foundation

/// A complete, portable copy of everything the app knows.
///
/// The format is one JSON object per line: a header, then one line per activity
/// carrying its full GPS track. Written and read a line at a time, so a library
/// with hundreds of long rides never has to fit in memory at once.
enum Backup {

    static let fileExtension = "stridebackup"
    static let formatVersion = 1

    struct Manifest: Codable {
        var version: Int
        var createdAt: Date
        var activityCount: Int
        var trackCount: Int
    }

    struct Header: Codable {
        var manifest: Manifest
        var settings: AppSettings
        var segments: [Segment]
        var segmentEfforts: [SegmentEffort]
        var routes: [SavedRoute]
        var gear: [Gear]
        var goals: [Goal]
    }

    struct Entry: Codable {
        var activity: Activity
        var track: [TrackPoint]
    }

    /// Everything needed to write a backup, snapshotted on the main thread so the
    /// file work can safely move to a background queue.
    struct Snapshot {
        var settings: AppSettings
        var activities: [Activity]
        var segments: [Segment]
        var segmentEfforts: [SegmentEffort]
        var routes: [SavedRoute]
        var gear: [Gear]
        var goals: [Goal]
    }

    struct Summary {
        var activities: Int
        var segments: Int
        var routes: Int
        var gear: Int
        var goals: Int
        var createdAt: Date
    }

    enum Failure: LocalizedError {
        case couldNotCreateFile
        case notABackup
        case unreadable

        var errorDescription: String? {
            switch self {
            case .couldNotCreateFile: return "Could not create the backup file."
            case .notABackup: return "That file is not a Stride backup."
            case .unreadable: return "The backup could not be opened."
            }
        }
    }

    enum RestoreMode {
        /// Keep what is already here and add anything the backup has that is new.
        case merge
        /// Throw away everything on the phone and use the backup instead.
        case replace
    }

    // MARK: - Writing

    static func suggestedFilename(date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd-HHmm"
        return "Stride-\(formatter.string(from: date)).\(fileExtension)"
    }

    @discardableResult
    static func write(_ snapshot: Snapshot,
                      to url: URL,
                      progress: ((Double) -> Void)? = nil) throws -> URL {
        let manager = FileManager.default
        if manager.fileExists(atPath: url.path) {
            try? manager.removeItem(at: url)
        }
        guard manager.createFile(atPath: url.path, contents: nil) else {
            throw Failure.couldNotCreateFile
        }
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }

        let newline = Data([0x0A])
        let encoder = Store.encoder

        // Count tracks by looking for the files rather than decoding them — each
        // one is re-read individually below.
        let tracked = snapshot.activities.filter {
            manager.fileExists(atPath: Store.trackURL($0.id).path)
        }.count

        let header = Header(manifest: Manifest(version: formatVersion,
                                               createdAt: Date(),
                                               activityCount: snapshot.activities.count,
                                               trackCount: tracked),
                            settings: snapshot.settings,
                            segments: snapshot.segments,
                            segmentEfforts: snapshot.segmentEfforts,
                            routes: snapshot.routes,
                            gear: snapshot.gear,
                            goals: snapshot.goals)

        try handle.write(contentsOf: try encoder.encode(header))
        try handle.write(contentsOf: newline)

        let total = Double(max(1, snapshot.activities.count))
        for (index, activity) in snapshot.activities.enumerated() {
            let entry = Entry(activity: activity, track: Store.loadTrack(activity.id))
            try handle.write(contentsOf: try encoder.encode(entry))
            try handle.write(contentsOf: newline)
            progress?(Double(index + 1) / total)
        }
        return url
    }

    // MARK: - Reading

    /// Peek at a backup without applying it, so the user can be told what is in it.
    static func inspect(_ url: URL) throws -> Summary {
        guard let reader = LineReader(url: url) else { throw Failure.unreadable }
        defer { reader.close() }
        guard let line = reader.nextLine(),
              let header = try? Store.decoder.decode(Header.self, from: line) else {
            throw Failure.notABackup
        }
        return Summary(activities: header.manifest.activityCount,
                       segments: header.segments.count,
                       routes: header.routes.count,
                       gear: header.gear.count,
                       goals: header.goals.count,
                       createdAt: header.manifest.createdAt)
    }

    /// The result of reading a backup, ready to hand to the store on the main thread.
    struct Restored {
        var settings: AppSettings
        var activities: [Activity]
        var segments: [Segment]
        var segmentEfforts: [SegmentEffort]
        var routes: [SavedRoute]
        var gear: [Gear]
        var goals: [Goal]
        var skipped: Int
    }

    /// Reads the file and writes each track straight to disk as it goes, so memory
    /// stays bounded by the single largest activity.
    static func read(_ url: URL,
                     existingActivityIDs: Set<UUID>,
                     mode: RestoreMode,
                     progress: ((Double) -> Void)? = nil) throws -> Restored {
        guard let reader = LineReader(url: url) else { throw Failure.unreadable }
        defer { reader.close() }
        guard let headerLine = reader.nextLine(),
              let header = try? Store.decoder.decode(Header.self, from: headerLine) else {
            throw Failure.notABackup
        }

        var activities: [Activity] = []
        var skipped = 0
        var seen = 0
        let expected = Double(max(1, header.manifest.activityCount))

        while let line = reader.nextLine() {
            guard !line.isEmpty else { continue }
            seen += 1
            progress?(Double(seen) / expected)
            guard let entry = try? Store.decoder.decode(Entry.self, from: line) else {
                skipped += 1
                continue
            }
            if mode == .merge && existingActivityIDs.contains(entry.activity.id) {
                continue
            }
            if !entry.track.isEmpty {
                Store.saveTrack(entry.track, for: entry.activity.id)
            }
            activities.append(entry.activity)
        }

        return Restored(settings: header.settings,
                        activities: activities,
                        segments: header.segments,
                        segmentEfforts: header.segmentEfforts,
                        routes: header.routes,
                        gear: header.gear,
                        goals: header.goals,
                        skipped: skipped)
    }
}

/// Reads a file one newline-delimited record at a time without loading it whole.
final class LineReader {
    private let handle: FileHandle
    private var buffer = Data()
    private var finished = false
    private let chunkSize = 1 << 20

    init?(url: URL) {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        self.handle = handle
    }

    func close() {
        try? handle.close()
    }

    func nextLine() -> Data? {
        while true {
            if let index = buffer.firstIndex(of: 0x0A) {
                let line = Data(buffer[buffer.startIndex..<index])
                buffer = Data(buffer[buffer.index(after: index)...])
                return line
            }
            if finished {
                guard !buffer.isEmpty else { return nil }
                let line = buffer
                buffer = Data()
                return line
            }
            let chunk = (try? handle.read(upToCount: chunkSize)) ?? nil
            if let chunk, !chunk.isEmpty {
                buffer.append(chunk)
            } else {
                finished = true
            }
        }
    }
}

// MARK: - Scheduled backups

/// Writes a copy of everything on a schedule, into a folder you can reach from
/// the Files app, and keeps only the most recent few.
enum AutoBackup {

    struct Entry: Identifiable, Hashable {
        var id: String { filename }
        var filename: String
        var url: URL
        var created: Date
        var byteCount: Int

        var sizeText: String {
            ByteCountFormatter.string(fromByteCount: Int64(byteCount), countStyle: .file)
        }
    }

    static func filename(date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd-HHmm"
        return "Stride-\(formatter.string(from: date)).\(Backup.fileExtension)"
    }

    /// Everything already saved, newest first.
    static func existing() -> [Entry] {
        Store.prepare()
        let keys: [URLResourceKey] = [.creationDateKey, .fileSizeKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: Store.backupsDirectory,
            includingPropertiesForKeys: keys) else { return [] }

        return urls
            .filter { $0.pathExtension == Backup.fileExtension }
            .compactMap { url -> Entry? in
                let values = try? url.resourceValues(forKeys: Set(keys))
                return Entry(filename: url.lastPathComponent,
                             url: url,
                             created: values?.creationDate ?? Date.distantPast,
                             byteCount: values?.fileSize ?? 0)
            }
            .sorted { $0.created > $1.created }
    }

    /// Delete the oldest until only `keep` remain.
    static func prune(keep: Int) {
        guard keep > 0 else { return }
        let all = existing()
        guard all.count > keep else { return }
        for entry in all.dropFirst(keep) {
            try? FileManager.default.removeItem(at: entry.url)
        }
    }

    static func delete(_ entry: Entry) {
        try? FileManager.default.removeItem(at: entry.url)
    }

    /// Writes a backup off the main thread and reports the file back on it.
    /// The snapshot must be taken on the main thread by the caller.
    static func run(snapshot: Backup.Snapshot,
                    keep: Int,
                    completion: @escaping (Result<Entry, Error>) -> Void) {
        Store.prepare()
        let destination = Store.backupsDirectory.appendingPathComponent(filename())

        DispatchQueue.global(qos: .utility).async {
            do {
                try Backup.write(snapshot, to: destination)
                prune(keep: keep)
                let attributes = try? FileManager.default.attributesOfItem(atPath: destination.path)
                let size = (attributes?[.size] as? Int) ?? 0
                let entry = Entry(filename: destination.lastPathComponent,
                                  url: destination,
                                  created: Date(),
                                  byteCount: size)
                DispatchQueue.main.async { completion(.success(entry)) }
            } catch {
                DispatchQueue.main.async { completion(.failure(error)) }
            }
        }
    }
}
