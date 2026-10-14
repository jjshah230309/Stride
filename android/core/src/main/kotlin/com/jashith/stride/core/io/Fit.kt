package com.jashith.stride.core.io

import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * Reader and writer for Garmin's FIT format.
 *
 * FIT is binary and self-describing: definition messages declare the shape of the data messages
 * that follow, so a file can carry any subset of fields. This covers what an activity needs: the
 * `record` messages that hold the track, plus the session and lap totals that make a written file
 * acceptable to Garmin Connect, Strava and the rest.
 */
object Fit {

    /** FIT counts seconds from the last day of 1989, not 1970. */
    const val EPOCH_OFFSET: Long = 631_065_600L

    fun instant(fromFitSeconds: Long): Instant = Instant.ofEpochSecond(fromFitSeconds + EPOCH_OFFSET)

    /** 0 when the date is outside what a uint32 can hold. */
    fun fitSeconds(from: Instant): Long {
        val value = from.epochSecond + from.nano / 1e9 - EPOCH_OFFSET
        if (!(value > 0 && value < 4_294_967_295.0)) return 0
        return value.toLong()
    }

    /** Positions are stored as semicircles: a full circle split into 2^32 parts. */
    const val SEMICIRCLE_TO_DEGREES: Double = 180.0 / 2147483648.0

    fun degrees(semicircles: Int): Double = semicircles.toDouble() * SEMICIRCLE_TO_DEGREES

    fun semicircles(degrees: Double): Int {
        val scaled = degrees / SEMICIRCLE_TO_DEGREES
        if (!(scaled > Int.MIN_VALUE.toDouble() && scaled < Int.MAX_VALUE.toDouble())) return 0
        return scaled.toInt()
    }

    object GlobalMessage {
        const val FILE_ID = 0
        const val SESSION = 18
        const val LAP = 19
        const val RECORD = 20
        const val EVENT = 21
        const val ACTIVITY = 34
    }

    /** Sport values from the FIT profile. */
    enum class Sport(val raw: Int) {
        Generic(0), Running(1), Cycling(2), Walking(11), Hiking(17);

        val sportType: SportType
            get() = when (this) {
                Running -> SportType.Run
                Cycling -> SportType.Ride
                Walking -> SportType.Walk
                Hiking -> SportType.Hike
                Generic -> SportType.Run
            }

        companion object {
            fun fromRaw(raw: Int): Sport? = entries.firstOrNull { it.raw == raw }

            fun from(sport: SportType): Sport = when {
                sport.isRide -> Cycling
                sport.isRun -> Running
                sport == SportType.Walk -> Walking
                sport == SportType.Hike -> Hiking
                else -> Generic
            }
        }
    }

    /** Sizes and invalid markers, indexed by the base type number. */
    private val baseSizes = intArrayOf(1, 1, 1, 2, 2, 4, 4, 1, 4, 8, 1, 2, 4, 1, 8, 8, 8)

    @OptIn(ExperimentalUnsignedTypes::class)
    private val baseInvalid = ulongArrayOf(
        0xFFuL, 0x7FuL, 0xFFuL, 0x7FFFuL, 0xFFFFuL, 0x7FFF_FFFFuL, 0xFFFF_FFFFuL, 0x00uL,
        0xFFFF_FFFFuL, 0xFFFF_FFFF_FFFF_FFFFuL, 0x00uL, 0x0000uL, 0x0000_0000uL, 0xFFuL,
        0x7FFF_FFFF_FFFF_FFFFuL, 0xFFFF_FFFF_FFFF_FFFFuL, 0x0000_0000_0000_0000uL,
    )

    internal fun size(baseType: Int): Int {
        val number = baseType and 0x1F
        return if (number < baseSizes.size) baseSizes[number] else 1
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    internal fun isInvalid(value: ULong, baseType: Int): Boolean {
        val number = baseType and 0x1F
        if (number >= baseInvalid.size) return false
        return value == baseInvalid[number]
    }

    private val crcTable = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    /** FIT's own CRC-16 (CRC-16/ARC), computed a nibble at a time. */
    fun crc16(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0
        for (index in from until to) {
            val byte = bytes[index].toInt() and 0xFF
            var check = crcTable[crc and 0x0F]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor check xor crcTable[byte and 0x0F]

            check = crcTable[crc and 0x0F]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor check xor crcTable[(byte shr 4) and 0x0F]
        }
        return crc and 0xFFFF
    }
}

sealed class FitFailure(message: String) : Exception(message) {
    class NotFit : FitFailure("That file is not in FIT format.")
    class Truncated : FitFailure("The FIT file ends unexpectedly.")
    class BadChecksum : FitFailure("The FIT file is damaged — its checksum does not match.")
    class NoRecords : FitFailure("The FIT file has no track data in it.")
}

/** Decodes a FIT activity into the same shape the GPX importer produces. */
class FitDecoder(private val now: () -> Instant = { Instant.now() }) {

    private class FieldDefinition(val number: Int, val size: Int, val baseType: Int)

    private class MessageDefinition(
        val globalNumber: Int,
        val littleEndian: Boolean,
        val fields: List<FieldDefinition>,
        val developerFields: List<FieldDefinition>,
    )

    data class Result(
        val name: String = "",
        val sport: SportType? = null,
        val startDate: Instant,
        val points: List<TrackPoint>,
        val totalDistance: Double? = null,
        val totalTimerTime: Double? = null,
        val totalCalories: Double? = null,
        val totalAscent: Double? = null,
        val averageHeartRate: Double? = null,
        val maxHeartRate: Double? = null,
    )

    private var bytes = ByteArray(0)
    private var cursor = 0
    private val definitions = HashMap<Int, MessageDefinition>()

    /** Set when the file's own checksum disagrees; the data is still returned. */
    var checksumMismatch = false
        private set

    @Throws(FitFailure::class)
    fun decode(data: ByteArray): Result {
        bytes = data
        cursor = 0
        definitions.clear()
        checksumMismatch = false

        if (bytes.size <= 14) throw FitFailure.Truncated()

        val headerSize = u8(0)
        if (!(headerSize == 12 || headerSize == 14) || bytes.size <= headerSize) throw FitFailure.NotFit()
        if (!(u8(8) == 0x2E && u8(9) == 0x46 && u8(10) == 0x49 && u8(11) == 0x54)) throw FitFailure.NotFit() // ".FIT"

        val declared = readUInt32(4)
        val available = (bytes.size - headerSize - 2).toLong()
        val dataSize = (if (declared > 0 && declared <= available) declared else available).toInt()

        verifyChecksum(headerSize, dataSize)

        cursor = headerSize
        val end = headerSize + dataSize

        val records = ArrayList<RawRecord>()
        val sessions = ArrayList<RawSession>()

        while (cursor < end) {
            val header = next() ?: break
            if (header.isDefinition) {
                readDefinition(header.localType, header.hasDeveloperData)
            } else {
                // Without its definition the rest cannot be located, so stop.
                val definition = definitions[header.localType] ?: break
                val values = readValues(definition)
                when (definition.globalNumber) {
                    Fit.GlobalMessage.RECORD -> RawRecord.from(values)?.let { records.add(it) }
                    Fit.GlobalMessage.SESSION -> sessions.add(RawSession(values))
                }
            }
        }

        if (records.isEmpty()) throw FitFailure.NoRecords()

        val points = buildTrack(records)
        val startDate = Fit.instant(records.first().timestamp)
        val session = sessions.firstOrNull()
        return Result(
            startDate = startDate,
            points = points,
            sport = session?.sport?.let { Fit.Sport.fromRaw(it)?.sportType },
            totalDistance = session?.totalDistance,
            totalTimerTime = session?.totalTimerTime,
            totalCalories = session?.totalCalories,
            totalAscent = session?.totalAscent,
            averageHeartRate = session?.averageHeartRate,
            maxHeartRate = session?.maxHeartRate,
        )
    }

    private fun verifyChecksum(headerSize: Int, dataSize: Int) {
        val end = headerSize + dataSize
        if (bytes.size < end + 2) {
            checksumMismatch = true
            return
        }
        val stored = u8(end) or (u8(end + 1) shl 8)
        val computed = Fit.crc16(bytes, 0, end)
        // A zero checksum is used by some writers to mean "not computed".
        checksumMismatch = stored != 0 && stored != computed
    }

    private fun u8(index: Int): Int = bytes[index].toInt() and 0xFF

    private class RecordHeader(val isDefinition: Boolean, val localType: Int, val hasDeveloperData: Boolean)

    private fun next(): RecordHeader? {
        if (cursor >= bytes.size) return null
        val byte = u8(cursor)
        cursor += 1
        if (byte and 0x80 != 0) {
            // Compressed timestamp header: always a data message.
            return RecordHeader(false, (byte shr 5) and 0x03, false)
        }
        return RecordHeader(byte and 0x40 != 0, byte and 0x0F, byte and 0x20 != 0)
    }

    private fun readDefinition(localType: Int, hasDeveloperData: Boolean) {
        if (cursor + 5 > bytes.size) {
            cursor = bytes.size
            return
        }
        cursor += 1                                     // reserved
        val littleEndian = u8(cursor) == 0
        cursor += 1
        val globalNumber = if (littleEndian) u8(cursor) or (u8(cursor + 1) shl 8) else (u8(cursor) shl 8) or u8(cursor + 1)
        cursor += 2
        val fieldCount = u8(cursor)
        cursor += 1

        val fields = ArrayList<FieldDefinition>()
        repeat(fieldCount) {
            if (cursor + 3 > bytes.size) {
                cursor = bytes.size
                return
            }
            fields.add(FieldDefinition(u8(cursor), u8(cursor + 1), u8(cursor + 2)))
            cursor += 3
        }

        val developer = ArrayList<FieldDefinition>()
        if (hasDeveloperData) {
            if (cursor >= bytes.size) return
            val count = u8(cursor)
            cursor += 1
            repeat(count) {
                if (cursor + 3 > bytes.size) {
                    cursor = bytes.size
                    return
                }
                developer.add(FieldDefinition(u8(cursor), u8(cursor + 1), u8(cursor + 2)))
                cursor += 3
            }
        }

        definitions[localType] = MessageDefinition(globalNumber, littleEndian, fields, developer)
    }

    /** Field number to raw value, with invalid markers dropped. */
    private fun readValues(definition: MessageDefinition): HashMap<Int, ULong> {
        val values = HashMap<Int, ULong>()
        for (field in definition.fields) {
            if (cursor + field.size > bytes.size) {
                cursor = bytes.size
                return values
            }
            val elementSize = Fit.size(field.baseType)
            // Arrays are declared by a size larger than one element; take the first.
            if (elementSize > 0 && field.size >= elementSize) {
                val raw = readInteger(cursor, elementSize, definition.littleEndian)
                if (!Fit.isInvalid(raw, field.baseType)) values[field.number] = raw
            }
            cursor += field.size
        }
        for (field in definition.developerFields) cursor += field.size
        if (cursor > bytes.size) cursor = bytes.size
        return values
    }

    private fun readInteger(index: Int, size: Int, littleEndian: Boolean): ULong {
        var value = 0uL
        if (littleEndian) {
            for (offset in size - 1 downTo 0) value = (value shl 8) or u8(index + offset).toULong()
        } else {
            for (offset in 0 until size) value = (value shl 8) or u8(index + offset).toULong()
        }
        return value
    }

    private fun readUInt32(index: Int): Long =
        (u8(index).toLong()) or (u8(index + 1).toLong() shl 8) or (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)

    private class RawRecord(
        val timestamp: Long,
        val latitude: Double?,
        val longitude: Double?,
        val altitude: Double?,
        val distance: Double?,
        val speed: Double?,
        val heartRate: Double?,
        val cadence: Double?,
        val power: Double?,
    ) {
        companion object {
            fun from(values: Map<Int, ULong>): RawRecord? {
                val stamp = values[253] ?: return null
                // enhanced_altitude wins when present; both are scaled by 5, offset 500.
                val alt = values[78] ?: values[2]
                val spd = values[73] ?: values[6]
                return RawRecord(
                    timestamp = stamp.toUInt().toLong(),
                    latitude = values[0]?.let { Fit.degrees(it.toUInt().toInt()) },
                    longitude = values[1]?.let { Fit.degrees(it.toUInt().toInt()) },
                    altitude = alt?.let { it.toDouble() / 5.0 - 500.0 },
                    distance = values[5]?.let { it.toDouble() / 100.0 },
                    speed = spd?.let { it.toDouble() / 1000.0 },
                    heartRate = values[3]?.takeIf { it > 0uL }?.toDouble(),
                    cadence = values[4]?.takeIf { it > 0uL }?.toDouble(),
                    power = values[7]?.toDouble(),
                )
            }
        }
    }

    private class RawSession(values: Map<Int, ULong>) {
        val sport: Int? = values[5]?.let { (it and 0xFFuL).toInt() }
        val totalDistance: Double? = values[9]?.let { it.toDouble() / 100.0 }
        val totalTimerTime: Double? = values[8]?.let { it.toDouble() / 1000.0 }
        val totalCalories: Double? = values[11]?.toDouble()
        val totalAscent: Double? = values[22]?.toDouble()
        val averageHeartRate: Double? = values[16]?.toDouble()
        val maxHeartRate: Double? = values[17]?.toDouble()
    }

    private fun buildTrack(records: List<RawRecord>): List<TrackPoint> {
        val first = records.firstOrNull() ?: return emptyList()
        val points = ArrayList<TrackPoint>(records.size)

        var cumulative = 0.0
        var previous: Coord? = null
        var previousTime = 0.0

        for (record in records) {
            val elapsed = (record.timestamp - first.timestamp).toDouble()
            if (elapsed < 0) continue

            var coordinate: Coord? = null
            if (record.latitude != null && record.longitude != null) {
                val candidate = Coord(record.latitude, record.longitude)
                if (candidate.isValid) coordinate = candidate
            }

            // Prefer the recorded distance; fall back to measuring the track.
            if (record.distance != null) {
                cumulative = record.distance
            } else if (coordinate != null && previous != null) {
                cumulative += Geo.fastDistance(previous, coordinate)
            }

            var speed = record.speed ?: 0.0
            if (speed == 0.0 && coordinate != null && previous != null && elapsed > previousTime) {
                speed = Geo.fastDistance(previous, coordinate) / (elapsed - previousTime)
            }

            points.add(
                TrackPoint(
                    t = elapsed, lat = coordinate?.lat ?: 0.0, lon = coordinate?.lon ?: 0.0,
                    alt = record.altitude ?: 0.0, d = cumulative, v = speed,
                    hr = record.heartRate, cad = record.cadence, pw = record.power, moving = true, acc = 5.0,
                ),
            )
            if (coordinate != null) previous = coordinate
            previousTime = elapsed
        }
        return points
    }
}

/**
 * Writes an activity as a FIT file.
 *
 * The layout follows what every reader expects: a `file_id` first, then the `record` messages
 * carrying the track, then the `lap`, `session` and `activity` summaries at the end.
 */
object FitEncoder {

    // Local message types, assigned once and reused by their data messages.
    private const val FILE_ID_LOCAL = 0
    private const val RECORD_LOCAL = 1
    private const val LAP_LOCAL = 2
    private const val SESSION_LOCAL = 3
    private const val ACTIVITY_LOCAL = 4

    // Base type identifiers.
    private const val T_UINT8 = 0x02
    private const val T_UINT16 = 0x84
    private const val T_UINT32 = 0x86
    private const val T_SINT32 = 0x85
    private const val T_ENUM = 0x00

    private const val UINT32_MAX = 4_294_967_295L
    private const val UINT16_MAX = 65_535L

    private class Body {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        fun u32(v: Long) {
            for (shift in 0 until 32 step 8) out.write(((v shr shift) and 0xFF).toInt())
        }
        fun s32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    }

    /** `UInt32(clamping: Int(x))`: truncate toward zero, then clamp. */
    private fun clampU32(x: Double): Long = x.toLong().coerceIn(0L, UINT32_MAX)

    private fun clampU16(x: Double): Int = x.toLong().coerceIn(0L, UINT16_MAX).toInt()

    fun encode(activity: Activity, points: List<TrackPoint>): ByteArray {
        val body = Body()
        val start = activity.startDate
        val startStamp = Fit.fitSeconds(start)
        val endStamp = Fit.fitSeconds(start.plusNanos((activity.elapsed * 1e9).toLong()))

        writeFileId(body, startStamp)
        writeRecordDefinition(body)

        var wroteAny = false
        for (point in points) {
            writeRecord(body, point, start)
            wroteAny = true
        }
        // A file with no track still needs at least one record to be an activity.
        if (!wroteAny) writeRecord(body, TrackPoint(t = 0.0, lat = 0.0, lon = 0.0, alt = 0.0, d = 0.0, v = 0.0), start)

        writeLap(body, activity, startStamp, endStamp)
        writeSession(body, activity, startStamp, endStamp)
        writeActivity(body, activity, endStamp)
        return finish(body.out.toByteArray())
    }

    private fun definition(body: Body, local: Int, global: Int, fields: List<Triple<Int, Int, Int>>) {
        body.u8(0x40 or (local and 0x0F))   // definition message header
        body.u8(0)                           // reserved
        body.u8(0)                           // little endian
        body.u16(global)
        body.u8(fields.size)
        for ((number, size, baseType) in fields) {
            body.u8(number)
            body.u8(size)
            body.u8(baseType)
        }
    }

    private fun byteOrInvalid(value: Double?): Int {
        if (value == null || !(value > 0 && value < 255)) return 0xFF
        return value.toInt()
    }

    private fun writeFileId(body: Body, created: Long) {
        definition(
            body, FILE_ID_LOCAL, Fit.GlobalMessage.FILE_ID,
            listOf(
                Triple(0, 1, T_ENUM),      // type
                Triple(1, 2, T_UINT16),    // manufacturer
                Triple(2, 2, T_UINT16),    // product
                Triple(3, 4, T_UINT32),    // serial_number
                Triple(4, 4, T_UINT32),    // time_created
            ),
        )
        body.u8(FILE_ID_LOCAL)
        body.u8(4)                 // 4 = activity file
        body.u16(255)              // 255 = development manufacturer
        body.u16(0)
        body.u32(0x53545244L)      // "STRD"
        body.u32(created)
    }

    private fun writeRecordDefinition(body: Body) {
        definition(
            body, RECORD_LOCAL, Fit.GlobalMessage.RECORD,
            listOf(
                Triple(253, 4, T_UINT32),  // timestamp
                Triple(0, 4, T_SINT32),    // position_lat
                Triple(1, 4, T_SINT32),    // position_long
                Triple(5, 4, T_UINT32),    // distance
                Triple(2, 2, T_UINT16),    // altitude
                Triple(6, 2, T_UINT16),    // speed
                Triple(7, 2, T_UINT16),    // power
                Triple(3, 1, T_UINT8),     // heart_rate
                Triple(4, 1, T_UINT8),     // cadence
            ),
        )
    }

    private fun writeRecord(body: Body, point: TrackPoint, start: Instant) {
        body.u8(RECORD_LOCAL)
        body.u32(Fit.fitSeconds(start.plusNanos((point.t * 1e9).toLong())))

        if (point.coord.isValid) {
            body.s32(Fit.semicircles(point.lat))
            body.s32(Fit.semicircles(point.lon))
        } else {
            body.s32(0x7FFF_FFFF)    // invalid
            body.s32(0x7FFF_FFFF)
        }

        body.u32(clampU32(maxOf(0.0, point.d * 100)))
        // Altitude is stored scaled by 5 with a 500 m offset.
        val altitude = (point.alt + 500) * 5
        body.u16(if (altitude > 0 && altitude < 65534) altitude.toInt() else 0xFFFF)
        val speed = point.v * 1000
        body.u16(if (speed >= 0 && speed < 65534) speed.toInt() else 0xFFFF)
        val power = point.pw
        body.u16(if (power != null && power >= 0 && power < 65534) power.toInt() else 0xFFFF)
        body.u8(byteOrInvalid(point.hr))
        body.u8(byteOrInvalid(point.cad))
    }

    private fun writeLap(body: Body, activity: Activity, startStamp: Long, endStamp: Long) {
        definition(
            body, LAP_LOCAL, Fit.GlobalMessage.LAP,
            listOf(
                Triple(254, 2, T_UINT16),  // message_index
                Triple(253, 4, T_UINT32),  // timestamp
                Triple(2, 4, T_UINT32),    // start_time
                Triple(7, 4, T_UINT32),    // total_elapsed_time
                Triple(8, 4, T_UINT32),    // total_timer_time
                Triple(9, 4, T_UINT32),    // total_distance
                Triple(0, 1, T_ENUM),      // event
                Triple(1, 1, T_ENUM),      // event_type
            ),
        )
        body.u8(LAP_LOCAL)
        body.u16(0)
        body.u32(endStamp)
        body.u32(startStamp)
        body.u32(clampU32(activity.elapsed * 1000))
        body.u32(clampU32(activity.movingTime * 1000))
        body.u32(clampU32(activity.distance * 100))
        body.u8(9)     // lap
        body.u8(1)     // stop
    }

    private fun writeSession(body: Body, activity: Activity, startStamp: Long, endStamp: Long) {
        definition(
            body, SESSION_LOCAL, Fit.GlobalMessage.SESSION,
            listOf(
                Triple(254, 2, T_UINT16),  // message_index
                Triple(253, 4, T_UINT32),  // timestamp
                Triple(2, 4, T_UINT32),    // start_time
                Triple(7, 4, T_UINT32),    // total_elapsed_time
                Triple(8, 4, T_UINT32),    // total_timer_time
                Triple(9, 4, T_UINT32),    // total_distance
                Triple(11, 2, T_UINT16),   // total_calories
                Triple(22, 2, T_UINT16),   // total_ascent
                Triple(25, 2, T_UINT16),   // first_lap_index
                Triple(26, 2, T_UINT16),   // num_laps
                Triple(5, 1, T_ENUM),      // sport
                Triple(6, 1, T_ENUM),      // sub_sport
                Triple(16, 1, T_UINT8),    // avg_heart_rate
                Triple(17, 1, T_UINT8),    // max_heart_rate
                Triple(0, 1, T_ENUM),      // event
                Triple(1, 1, T_ENUM),      // event_type
            ),
        )
        body.u8(SESSION_LOCAL)
        body.u16(0)
        body.u32(endStamp)
        body.u32(startStamp)
        body.u32(clampU32(activity.elapsed * 1000))
        body.u32(clampU32(activity.movingTime * 1000))
        body.u32(clampU32(activity.distance * 100))
        body.u16(clampU16(activity.calories))
        body.u16(clampU16(activity.elevationGain))
        body.u16(0)    // first lap
        body.u16(1)    // one lap
        body.u8(Fit.Sport.from(activity.sport).raw)
        body.u8(0)     // generic sub-sport
        body.u8(byteOrInvalid(activity.avgHR))
        body.u8(byteOrInvalid(activity.maxHR))
        body.u8(8)     // session
        body.u8(1)     // stop
    }

    private fun writeActivity(body: Body, activity: Activity, endStamp: Long) {
        definition(
            body, ACTIVITY_LOCAL, Fit.GlobalMessage.ACTIVITY,
            listOf(
                Triple(253, 4, T_UINT32),  // timestamp
                Triple(0, 4, T_UINT32),    // total_timer_time
                Triple(1, 2, T_UINT16),    // num_sessions
                Triple(2, 1, T_ENUM),      // type
                Triple(3, 1, T_ENUM),      // event
                Triple(4, 1, T_ENUM),      // event_type
            ),
        )
        body.u8(ACTIVITY_LOCAL)
        body.u32(endStamp)
        body.u32(clampU32(activity.movingTime * 1000))
        body.u16(1)
        body.u8(0)     // manual
        body.u8(26)    // activity
        body.u8(1)     // stop
    }

    /** Prepends the 14-byte header and appends the file checksum. */
    private fun finish(body: ByteArray): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(14)                          // header size
        header.write(0x20)                        // protocol version 2.0
        header.write(0x00); header.write(0x08)    // profile version, little endian
        val size = body.size.toLong()
        for (shift in 0 until 32 step 8) header.write(((size shr shift) and 0xFF).toInt())
        header.write(".FIT".toByteArray(Charsets.US_ASCII))
        val headerBytes = header.toByteArray()
        val headerCrc = Fit.crc16(headerBytes)

        val file = ByteArrayOutputStream()
        file.write(headerBytes)
        file.write(headerCrc and 0xFF)
        file.write((headerCrc shr 8) and 0xFF)
        file.write(body)
        val withoutCrc = file.toByteArray()
        val fileCrc = Fit.crc16(withoutCrc)
        file.write(fileCrc and 0xFF)
        file.write((fileCrc shr 8) and 0xFF)
        return file.toByteArray()
    }
}
