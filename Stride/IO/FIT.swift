import Foundation

/// Reader and writer for Garmin's FIT format.
///
/// FIT is binary and self-describing: definition messages declare the shape of
/// the data messages that follow, so a file can carry any subset of fields. This
/// implementation covers what an activity actually needs — the `record` messages
/// that hold the track, plus the session and lap totals that make a written file
/// acceptable to Garmin Connect, Strava and the rest.
enum FIT {

    /// FIT counts seconds from the last day of 1989, not 1970.
    static let epochOffset: TimeInterval = 631_065_600

    static func date(fromFITSeconds seconds: UInt32) -> Date {
        Date(timeIntervalSince1970: TimeInterval(seconds) + epochOffset)
    }

    static func fitSeconds(from date: Date) -> UInt32 {
        let value = date.timeIntervalSince1970 - epochOffset
        guard value > 0, value < Double(UInt32.max) else { return 0 }
        return UInt32(value)
    }

    /// Positions are stored as semicircles: a full circle split into 2^32 parts.
    static let semicircleToDegrees: Double = 180.0 / 2147483648.0

    static func degrees(fromSemicircles value: Int32) -> Double {
        Double(value) * semicircleToDegrees
    }

    static func semicircles(fromDegrees value: Double) -> Int32 {
        let scaled = value / semicircleToDegrees
        guard scaled > Double(Int32.min), scaled < Double(Int32.max) else { return 0 }
        return Int32(scaled)
    }

    // MARK: - Message and field numbers

    enum GlobalMessage: UInt16 {
        case fileId = 0
        case session = 18
        case lap = 19
        case record = 20
        case event = 21
        case activity = 34
    }

    /// Sport values from the FIT profile.
    enum Sport: UInt8 {
        case generic = 0
        case running = 1
        case cycling = 2
        case walking = 11
        case hiking = 17

        var sportType: SportType {
            switch self {
            case .running: return .run
            case .cycling: return .ride
            case .walking: return .walk
            case .hiking: return .hike
            case .generic: return .run
            }
        }

        static func from(_ sport: SportType) -> Sport {
            if sport.isRide { return .cycling }
            if sport.isRun { return .running }
            if sport == .walk { return .walking }
            if sport == .hike { return .hiking }
            return .generic
        }
    }

    // MARK: - Base types

    /// Sizes and invalid markers, indexed by the base type number.
    static let baseSizes: [Int] = [1, 1, 1, 2, 2, 4, 4, 1, 4, 8, 1, 2, 4, 1, 8, 8, 8]
    static let baseInvalid: [UInt64] = [
        0xFF, 0x7F, 0xFF, 0x7FFF, 0xFFFF, 0x7FFF_FFFF, 0xFFFF_FFFF, 0x00,
        0xFFFF_FFFF, 0xFFFF_FFFF_FFFF_FFFF, 0x00, 0x0000, 0x0000_0000, 0xFF,
        0x7FFF_FFFF_FFFF_FFFF, 0xFFFF_FFFF_FFFF_FFFF, 0x0000_0000_0000_0000
    ]
    static let signedBaseTypes: Set<Int> = [1, 3, 5, 14]

    static func size(ofBaseType raw: UInt8) -> Int {
        let number = Int(raw & 0x1F)
        return number < baseSizes.count ? baseSizes[number] : 1
    }

    static func isInvalid(_ value: UInt64, baseType raw: UInt8) -> Bool {
        let number = Int(raw & 0x1F)
        guard number < baseInvalid.count else { return false }
        return value == baseInvalid[number]
    }

    // MARK: - Checksum

    private static let crcTable: [UInt16] = [
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    ]

    /// FIT's own CRC-16, computed a nibble at a time.
    static func crc16(_ bytes: [UInt8], from start: Int = 0, to end: Int? = nil) -> UInt16 {
        var crc: UInt16 = 0
        let last = end ?? bytes.count
        var index = start
        while index < last {
            let byte = bytes[index]
            var check = crcTable[Int(crc & 0x0F)]
            crc = (crc >> 4) & 0x0FFF
            crc = crc ^ check ^ crcTable[Int(byte & 0x0F)]

            check = crcTable[Int(crc & 0x0F)]
            crc = (crc >> 4) & 0x0FFF
            crc = crc ^ check ^ crcTable[Int((byte >> 4) & 0x0F)]
            index += 1
        }
        return crc
    }
}

// MARK: - Reading

/// Decodes a FIT activity into the same shape the GPX importer produces.
final class FITDecoder {

    struct FieldDefinition {
        var number: UInt8
        var size: Int
        var baseType: UInt8
    }

    struct MessageDefinition {
        var globalNumber: UInt16
        var littleEndian: Bool
        var fields: [FieldDefinition]
        var developerFields: [FieldDefinition]

        var dataSize: Int {
            fields.reduce(0) { $0 + $1.size } + developerFields.reduce(0) { $0 + $1.size }
        }
    }

    struct Result {
        var name: String = ""
        var sport: SportType?
        var startDate: Date = Date()
        var points: [TrackPoint] = []
        var totalDistance: Double?
        var totalTimerTime: TimeInterval?
        var totalCalories: Double?
        var totalAscent: Double?
        var averageHeartRate: Double?
        var maxHeartRate: Double?
    }

    enum Failure: LocalizedError {
        case notFIT
        case truncated
        case badChecksum
        case noRecords

        var errorDescription: String? {
            switch self {
            case .notFIT: return "That file is not in FIT format."
            case .truncated: return "The FIT file ends unexpectedly."
            case .badChecksum: return "The FIT file is damaged — its checksum does not match."
            case .noRecords: return "The FIT file has no track data in it."
            }
        }
    }

    private var bytes: [UInt8] = []
    private var cursor: Int = 0
    private var definitions: [UInt8: MessageDefinition] = [:]

    /// Set when the file's own checksum disagrees; the data is still returned,
    /// because a truncated export is usually better than nothing.
    private(set) var checksumMismatch = false

    // MARK: Entry point

    func decode(_ data: Data) throws -> Result {
        bytes = [UInt8](data)
        cursor = 0
        definitions.removeAll()
        checksumMismatch = false

        guard bytes.count > 14 else { throw Failure.truncated }

        let headerSize = Int(bytes[0])
        guard headerSize == 12 || headerSize == 14, bytes.count > headerSize else {
            throw Failure.notFIT
        }
        guard bytes[8] == 0x2E, bytes[9] == 0x46, bytes[10] == 0x49, bytes[11] == 0x54 else {
            throw Failure.notFIT   // ".FIT"
        }

        let declared = Int(readUInt32(at: 4))
        let available = bytes.count - headerSize - 2
        let dataSize = declared > 0 && declared <= available ? declared : available

        verifyChecksum(headerSize: headerSize, dataSize: dataSize)

        cursor = headerSize
        let end = headerSize + dataSize

        var result = Result()
        var records: [RawRecord] = []
        var sessions: [RawSession] = []

        while cursor < end {
            guard let header = next() else { break }
            if header.isDefinition {
                readDefinition(localType: header.localType, hasDeveloperData: header.hasDeveloperData)
            } else {
                guard let definition = definitions[header.localType] else {
                    // Without its definition the rest cannot be located, so stop.
                    break
                }
                let values = readValues(for: definition)
                switch definition.globalNumber {
                case FIT.GlobalMessage.record.rawValue:
                    if let record = RawRecord(values) { records.append(record) }
                case FIT.GlobalMessage.session.rawValue:
                    sessions.append(RawSession(values))
                default:
                    break
                }
            }
        }

        guard !records.isEmpty else { throw Failure.noRecords }

        result.points = buildTrack(from: records)
        result.startDate = records.first.map { FIT.date(fromFITSeconds: $0.timestamp) } ?? Date()

        if let session = sessions.first {
            result.sport = session.sport.flatMap { FIT.Sport(rawValue: $0)?.sportType }
            result.totalDistance = session.totalDistance
            result.totalTimerTime = session.totalTimerTime
            result.totalCalories = session.totalCalories
            result.totalAscent = session.totalAscent
            result.averageHeartRate = session.averageHeartRate
            result.maxHeartRate = session.maxHeartRate
        }
        return result
    }

    private func verifyChecksum(headerSize: Int, dataSize: Int) {
        let end = headerSize + dataSize
        guard bytes.count >= end + 2 else {
            checksumMismatch = true
            return
        }
        let stored = UInt16(bytes[end]) | (UInt16(bytes[end + 1]) << 8)
        let computed = FIT.crc16(bytes, from: 0, to: end)
        // A zero checksum is used by some writers to mean "not computed".
        checksumMismatch = stored != 0 && stored != computed
    }

    // MARK: Record headers

    private struct RecordHeader {
        var isDefinition: Bool
        var localType: UInt8
        var hasDeveloperData: Bool
        var compressedOffset: UInt8?
    }

    private func next() -> RecordHeader? {
        guard cursor < bytes.count else { return nil }
        let byte = bytes[cursor]
        cursor += 1
        if byte & 0x80 != 0 {
            // Compressed timestamp header: always a data message.
            return RecordHeader(isDefinition: false,
                                localType: (byte >> 5) & 0x03,
                                hasDeveloperData: false,
                                compressedOffset: byte & 0x1F)
        }
        return RecordHeader(isDefinition: byte & 0x40 != 0,
                            localType: byte & 0x0F,
                            hasDeveloperData: byte & 0x20 != 0,
                            compressedOffset: nil)
    }

    private func readDefinition(localType: UInt8, hasDeveloperData: Bool) {
        guard cursor + 5 <= bytes.count else { cursor = bytes.count; return }
        cursor += 1                                    // reserved
        let littleEndian = bytes[cursor] == 0
        cursor += 1
        let globalNumber = littleEndian
            ? UInt16(bytes[cursor]) | (UInt16(bytes[cursor + 1]) << 8)
            : (UInt16(bytes[cursor]) << 8) | UInt16(bytes[cursor + 1])
        cursor += 2
        let fieldCount = Int(bytes[cursor])
        cursor += 1

        var fields: [FieldDefinition] = []
        for _ in 0..<fieldCount {
            guard cursor + 3 <= bytes.count else { cursor = bytes.count; return }
            fields.append(FieldDefinition(number: bytes[cursor],
                                          size: Int(bytes[cursor + 1]),
                                          baseType: bytes[cursor + 2]))
            cursor += 3
        }

        var developer: [FieldDefinition] = []
        if hasDeveloperData {
            guard cursor < bytes.count else { return }
            let count = Int(bytes[cursor])
            cursor += 1
            for _ in 0..<count {
                guard cursor + 3 <= bytes.count else { cursor = bytes.count; return }
                developer.append(FieldDefinition(number: bytes[cursor],
                                                 size: Int(bytes[cursor + 1]),
                                                 baseType: bytes[cursor + 2]))
                cursor += 3
            }
        }

        definitions[localType] = MessageDefinition(globalNumber: globalNumber,
                                                   littleEndian: littleEndian,
                                                   fields: fields,
                                                   developerFields: developer)
    }

    /// Field number to raw value, with invalid markers dropped.
    private func readValues(for definition: MessageDefinition) -> [UInt8: UInt64] {
        var values: [UInt8: UInt64] = [:]
        for field in definition.fields {
            guard cursor + field.size <= bytes.count else {
                cursor = bytes.count
                return values
            }
            let elementSize = FIT.size(ofBaseType: field.baseType)
            // Arrays are declared by a size larger than one element; take the first.
            if elementSize > 0 && field.size >= elementSize {
                let raw = readInteger(at: cursor, size: elementSize, littleEndian: definition.littleEndian)
                if !FIT.isInvalid(raw, baseType: field.baseType) {
                    values[field.number] = raw
                }
            }
            cursor += field.size
        }
        for field in definition.developerFields {
            cursor += field.size
        }
        if cursor > bytes.count { cursor = bytes.count }
        return values
    }

    private func readInteger(at index: Int, size: Int, littleEndian: Bool) -> UInt64 {
        var value: UInt64 = 0
        if littleEndian {
            for offset in stride(from: size - 1, through: 0, by: -1) {
                value = (value << 8) | UInt64(bytes[index + offset])
            }
        } else {
            for offset in 0..<size {
                value = (value << 8) | UInt64(bytes[index + offset])
            }
        }
        return value
    }

    private func readUInt32(at index: Int) -> UInt32 {
        UInt32(bytes[index]) | (UInt32(bytes[index + 1]) << 8)
            | (UInt32(bytes[index + 2]) << 16) | (UInt32(bytes[index + 3]) << 24)
    }

    // MARK: Raw messages

    private struct RawRecord {
        var timestamp: UInt32
        var latitude: Double?
        var longitude: Double?
        var altitude: Double?
        var distance: Double?
        var speed: Double?
        var heartRate: Double?
        var cadence: Double?
        var power: Double?

        init?(_ values: [UInt8: UInt64]) {
            guard let stamp = values[253] else { return nil }
            timestamp = UInt32(truncatingIfNeeded: stamp)

            if let raw = values[0] { latitude = FIT.degrees(fromSemicircles: Int32(bitPattern: UInt32(truncatingIfNeeded: raw))) }
            if let raw = values[1] { longitude = FIT.degrees(fromSemicircles: Int32(bitPattern: UInt32(truncatingIfNeeded: raw))) }

            // enhanced_altitude wins when present; both are scaled by 5, offset 500.
            if let raw = values[78] ?? values[2] { altitude = Double(raw) / 5.0 - 500.0 }
            if let raw = values[5] { distance = Double(raw) / 100.0 }
            if let raw = values[73] ?? values[6] { speed = Double(raw) / 1000.0 }
            if let raw = values[3], raw > 0 { heartRate = Double(raw) }
            if let raw = values[4], raw > 0 { cadence = Double(raw) }
            if let raw = values[7] { power = Double(raw) }
        }
    }

    private struct RawSession {
        var sport: UInt8?
        var totalDistance: Double?
        var totalTimerTime: TimeInterval?
        var totalCalories: Double?
        var totalAscent: Double?
        var averageHeartRate: Double?
        var maxHeartRate: Double?

        init(_ values: [UInt8: UInt64]) {
            if let raw = values[5] { sport = UInt8(truncatingIfNeeded: raw) }
            if let raw = values[9] { totalDistance = Double(raw) / 100.0 }
            if let raw = values[8] { totalTimerTime = Double(raw) / 1000.0 }
            if let raw = values[11] { totalCalories = Double(raw) }
            if let raw = values[22] { totalAscent = Double(raw) }
            if let raw = values[16] { averageHeartRate = Double(raw) }
            if let raw = values[17] { maxHeartRate = Double(raw) }
        }
    }

    private func buildTrack(from records: [RawRecord]) -> [TrackPoint] {
        guard let first = records.first else { return [] }
        let start = FIT.date(fromFITSeconds: first.timestamp)
        var points: [TrackPoint] = []
        points.reserveCapacity(records.count)

        var cumulative = 0.0
        var previous: Coord?
        var previousTime: TimeInterval = 0

        for record in records {
            let elapsed = FIT.date(fromFITSeconds: record.timestamp).timeIntervalSince(start)
            guard elapsed >= 0 else { continue }

            var coordinate: Coord?
            if let lat = record.latitude, let lon = record.longitude {
                let candidate = Coord(lat: lat, lon: lon)
                if candidate.isValid { coordinate = candidate }
            }

            // Prefer the recorded distance; fall back to measuring the track.
            if let distance = record.distance {
                cumulative = distance
            } else if let coordinate, let previous {
                cumulative += Geo.fastDistance(previous, coordinate)
            }

            var speed = record.speed ?? 0
            if speed == 0, let coordinate, let previous, elapsed > previousTime {
                speed = Geo.fastDistance(previous, coordinate) / (elapsed - previousTime)
            }

            points.append(TrackPoint(t: elapsed,
                                     lat: coordinate?.lat ?? 0,
                                     lon: coordinate?.lon ?? 0,
                                     alt: record.altitude ?? 0,
                                     d: cumulative,
                                     v: speed,
                                     hr: record.heartRate,
                                     cad: record.cadence,
                                     pw: record.power,
                                     moving: true,
                                     acc: 5))
            if let coordinate { previous = coordinate }
            previousTime = elapsed
        }
        return points
    }
}

// MARK: - Writing

/// Writes an activity as a FIT file.
///
/// The layout follows what every reader expects: a `file_id` first, then the
/// `record` messages carrying the track, then the `lap`, `session` and
/// `activity` summaries at the end.
struct FITEncoder {

    private var body: [UInt8] = []

    // Local message types, assigned once and reused by their data messages.
    private let fileIdLocal: UInt8 = 0
    private let recordLocal: UInt8 = 1
    private let lapLocal: UInt8 = 2
    private let sessionLocal: UInt8 = 3
    private let activityLocal: UInt8 = 4

    // Base type identifiers.
    private let tUint8: UInt8 = 0x02
    private let tUint16: UInt8 = 0x84
    private let tUint32: UInt8 = 0x86
    private let tSint32: UInt8 = 0x85
    private let tEnum: UInt8 = 0x00

    static func encode(activity: Activity, points: [TrackPoint]) -> Data {
        var encoder = FITEncoder()
        encoder.build(activity: activity, points: points)
        return encoder.finish()
    }

    private mutating func build(activity: Activity, points: [TrackPoint]) {
        let start = activity.startDate
        let startStamp = FIT.fitSeconds(from: start)
        let endStamp = FIT.fitSeconds(from: start.addingTimeInterval(activity.elapsed))

        writeFileId(created: startStamp)
        writeRecordDefinition()

        var wroteAny = false
        for point in points {
            writeRecord(point: point, start: start)
            wroteAny = true
        }
        // A file with no track still needs at least one record to be an activity.
        if !wroteAny {
            writeRecord(point: TrackPoint(t: 0, lat: 0, lon: 0, alt: 0, d: 0, v: 0), start: start)
        }

        writeLap(activity: activity, startStamp: startStamp, endStamp: endStamp)
        writeSession(activity: activity, startStamp: startStamp, endStamp: endStamp)
        writeActivity(activity: activity, endStamp: endStamp)
    }

    // MARK: Message writers

    private mutating func writeFileId(created: UInt32) {
        definition(local: fileIdLocal, global: FIT.GlobalMessage.fileId.rawValue, fields: [
            (0, 1, tEnum),      // type
            (1, 2, tUint16),    // manufacturer
            (2, 2, tUint16),    // product
            (3, 4, tUint32),    // serial_number
            (4, 4, tUint32)     // time_created
        ])
        body.append(fileIdLocal)
        append(UInt8(4))            // 4 = activity file
        append(UInt16(255))         // 255 = development manufacturer
        append(UInt16(0))
        append(UInt32(0x53545244))  // "STRD"
        append(created)
    }

    private mutating func writeRecordDefinition() {
        definition(local: recordLocal, global: FIT.GlobalMessage.record.rawValue, fields: [
            (253, 4, tUint32),  // timestamp
            (0, 4, tSint32),    // position_lat
            (1, 4, tSint32),    // position_long
            (5, 4, tUint32),    // distance
            (2, 2, tUint16),    // altitude
            (6, 2, tUint16),    // speed
            (7, 2, tUint16),    // power
            (3, 1, tUint8),     // heart_rate
            (4, 1, tUint8)      // cadence
        ])
    }

    private mutating func writeRecord(point: TrackPoint, start: Date) {
        body.append(recordLocal)
        append(FIT.fitSeconds(from: start.addingTimeInterval(point.t)))

        let coordinate = point.coord
        if coordinate.isValid {
            append(FIT.semicircles(fromDegrees: point.lat))
            append(FIT.semicircles(fromDegrees: point.lon))
        } else {
            append(Int32(bitPattern: 0x7FFF_FFFF))   // invalid
            append(Int32(bitPattern: 0x7FFF_FFFF))
        }

        append(UInt32(clamping: Int(max(0, point.d * 100))))
        // Altitude is stored scaled by 5 with a 500 m offset.
        let altitude = (point.alt + 500) * 5
        append(altitude > 0 && altitude < 65534 ? UInt16(altitude) : UInt16(0xFFFF))
        let speed = point.v * 1000
        append(speed >= 0 && speed < 65534 ? UInt16(speed) : UInt16(0xFFFF))
        if let power = point.pw, power >= 0, power < 65534 {
            append(UInt16(power))
        } else {
            append(UInt16(0xFFFF))
        }
        append(byteOrInvalid(point.hr))
        append(byteOrInvalid(point.cad))
    }

    private mutating func writeLap(activity: Activity, startStamp: UInt32, endStamp: UInt32) {
        definition(local: lapLocal, global: FIT.GlobalMessage.lap.rawValue, fields: [
            (254, 2, tUint16),  // message_index
            (253, 4, tUint32),  // timestamp
            (2, 4, tUint32),    // start_time
            (7, 4, tUint32),    // total_elapsed_time
            (8, 4, tUint32),    // total_timer_time
            (9, 4, tUint32),    // total_distance
            (0, 1, tEnum),      // event
            (1, 1, tEnum)       // event_type
        ])
        body.append(lapLocal)
        append(UInt16(0))
        append(endStamp)
        append(startStamp)
        append(UInt32(clamping: Int(activity.elapsed * 1000)))
        append(UInt32(clamping: Int(activity.movingTime * 1000)))
        append(UInt32(clamping: Int(activity.distance * 100)))
        append(UInt8(9))     // lap
        append(UInt8(1))     // stop
    }

    private mutating func writeSession(activity: Activity, startStamp: UInt32, endStamp: UInt32) {
        definition(local: sessionLocal, global: FIT.GlobalMessage.session.rawValue, fields: [
            (254, 2, tUint16),  // message_index
            (253, 4, tUint32),  // timestamp
            (2, 4, tUint32),    // start_time
            (7, 4, tUint32),    // total_elapsed_time
            (8, 4, tUint32),    // total_timer_time
            (9, 4, tUint32),    // total_distance
            (11, 2, tUint16),   // total_calories
            (22, 2, tUint16),   // total_ascent
            (25, 2, tUint16),   // first_lap_index
            (26, 2, tUint16),   // num_laps
            (5, 1, tEnum),      // sport
            (6, 1, tEnum),      // sub_sport
            (16, 1, tUint8),    // avg_heart_rate
            (17, 1, tUint8),    // max_heart_rate
            (0, 1, tEnum),      // event
            (1, 1, tEnum)       // event_type
        ])
        body.append(sessionLocal)
        append(UInt16(0))
        append(endStamp)
        append(startStamp)
        append(UInt32(clamping: Int(activity.elapsed * 1000)))
        append(UInt32(clamping: Int(activity.movingTime * 1000)))
        append(UInt32(clamping: Int(activity.distance * 100)))
        append(UInt16(clamping: Int(activity.calories)))
        append(UInt16(clamping: Int(activity.elevationGain)))
        append(UInt16(0))    // first lap
        append(UInt16(1))    // one lap
        append(FIT.Sport.from(activity.sport).rawValue)
        append(UInt8(0))     // generic sub-sport
        append(byteOrInvalid(activity.avgHR))
        append(byteOrInvalid(activity.maxHR))
        append(UInt8(8))     // session
        append(UInt8(1))     // stop
    }

    private mutating func writeActivity(activity: Activity, endStamp: UInt32) {
        definition(local: activityLocal, global: FIT.GlobalMessage.activity.rawValue, fields: [
            (253, 4, tUint32),  // timestamp
            (0, 4, tUint32),    // total_timer_time
            (1, 2, tUint16),    // num_sessions
            (2, 1, tEnum),      // type
            (3, 1, tEnum),      // event
            (4, 1, tEnum)       // event_type
        ])
        body.append(activityLocal)
        append(endStamp)
        append(UInt32(clamping: Int(activity.movingTime * 1000)))
        append(UInt16(1))
        append(UInt8(0))     // manual
        append(UInt8(26))    // activity
        append(UInt8(1))     // stop
    }

    // MARK: Primitives

    private mutating func definition(local: UInt8, global: UInt16,
                                     fields: [(UInt8, UInt8, UInt8)]) {
        body.append(0x40 | (local & 0x0F))   // definition message header
        body.append(0)                        // reserved
        body.append(0)                        // little endian
        append(global)
        body.append(UInt8(fields.count))
        for (number, size, baseType) in fields {
            body.append(number)
            body.append(size)
            body.append(baseType)
        }
    }

    private func byteOrInvalid(_ value: Double?) -> UInt8 {
        guard let value, value > 0, value < 255 else { return 0xFF }
        return UInt8(value)
    }

    private mutating func append(_ value: UInt8) {
        body.append(value)
    }

    private mutating func append(_ value: UInt16) {
        body.append(UInt8(value & 0xFF))
        body.append(UInt8((value >> 8) & 0xFF))
    }

    private mutating func append(_ value: UInt32) {
        for shift in stride(from: 0, to: 32, by: 8) {
            body.append(UInt8((value >> UInt32(shift)) & 0xFF))
        }
    }

    private mutating func append(_ value: Int32) {
        append(UInt32(bitPattern: value))
    }

    /// Prepends the 14-byte header and appends the file checksum.
    private func finish() -> Data {
        var header: [UInt8] = []
        header.append(14)                    // header size
        header.append(0x20)                  // protocol version 2.0
        header.append(contentsOf: [0x00, 0x08])  // profile version, little endian
        let size = UInt32(body.count)
        for shift in stride(from: 0, to: 32, by: 8) {
            header.append(UInt8((size >> UInt32(shift)) & 0xFF))
        }
        header.append(contentsOf: Array(".FIT".utf8))
        let headerCRC = FIT.crc16(header)
        header.append(UInt8(headerCRC & 0xFF))
        header.append(UInt8((headerCRC >> 8) & 0xFF))

        var file = header + body
        let fileCRC = FIT.crc16(file)
        file.append(UInt8(fileCRC & 0xFF))
        file.append(UInt8((fileCRC >> 8) & 0xFF))
        return Data(file)
    }
}
