package com.jashith.stride.core.io

import com.jashith.stride.core.TestData
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FitTest {

    private fun field(number: Int, size: Int, baseType: Int) = FitTestBuilder.Field(number, size, baseType)

    private val uint8 = 0x02
    private val uint16 = 0x84
    private val uint32 = 0x86
    private val sint32 = 0x85
    private val enum = 0x00

    private fun fitStamp(iso: String): Long = Fit.fitSeconds(Instant.parse(iso))

    @Test fun crcMatchesThePublishedCheckValue() {
        // CRC-16/ARC check value for the standard test input, which is what other tools verify against.
        assertEquals(0xBB3D, Fit.crc16("123456789".toByteArray()))
        assertEquals(0, Fit.crc16(ByteArray(0)))
    }

    @Test fun epochAndSemicircleConversions() {
        assertEquals(Instant.parse("1989-12-31T00:00:00Z"), Fit.instant(0))
        assertEquals(0, Fit.fitSeconds(Instant.parse("1980-01-01T00:00:00Z")))
        assertEquals(1_142_407_805L, fitStamp("2026-03-14T07:30:05Z"))
        assertEquals(0, Fit.semicircles(0.0))
        assertEquals(1_073_741_824, Fit.semicircles(90.0))
        assertEquals(90.0, Fit.degrees(1_073_741_824), 1e-12)
        assertEquals(0, Fit.semicircles(500.0)) // out of range is refused rather than wrapped
    }

    @Test fun anEncodedFileHasAValidHeaderAndChecksums() {
        val a = TestData.activity(1)
        val bytes = FitEncoder.encode(a, TestData.track(60))
        assertEquals(14, bytes[0].toInt())
        assertEquals(".FIT", String(bytes, 8, 4))
        val headerCrc = (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8)
        assertEquals(Fit.crc16(bytes, 0, 12), headerCrc)
        // the CRC of the whole file, including its own trailing CRC, is zero
        assertEquals(0, Fit.crc16(bytes))
        val declared = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8) or
            ((bytes[6].toInt() and 0xFF) shl 16) or ((bytes[7].toInt() and 0xFF) shl 24)
        assertEquals(bytes.size - 14 - 2, declared)
    }

    @Test fun roundTripIsLosslessWithinTheFormatsOwnPrecision() {
        val a = TestData.activity(1).copy(sport = SportType.Ride, avgHR = 148.0, maxHR = 171.0)
        val track = (0 until 90).map { i ->
            TrackPoint(
                t = i.toDouble(), lat = 51.5 + i * 0.00031, lon = -0.12 + i * 0.00017, alt = 23.4 + i * 0.37,
                d = i * 7.123, v = 7.123, hr = 120.0 + i % 40, cad = 80.0 + i % 9, pw = if (i % 4 == 0) 215.0 + i else null,
            )
        }
        val decoded = FitDecoder().decode(FitEncoder.encode(a, track))
        assertEquals(a.startDate, decoded.startDate)
        assertEquals(SportType.Ride, decoded.sport)
        assertEquals(track.size, decoded.points.size)
        assertEquals(148.0, decoded.averageHeartRate!!, 0.0)
        assertEquals(171.0, decoded.maxHeartRate!!, 0.0)
        for ((orig, back) in track.zip(decoded.points)) {
            assertEquals(orig.t, back.t, 0.0)
            assertEquals("lat", orig.lat, back.lat, 1.0e-7)   // 1 semicircle is 8.4e-8 degrees, about 9 mm
            assertEquals("lon", orig.lon, back.lon, 1.0e-7)
            assertEquals("alt", orig.alt, back.alt, 0.2)       // 0.2 m step
            assertEquals("distance", orig.d, back.d, 0.01)     // centimetres
            assertEquals("speed", orig.v, back.v, 0.001)       // mm/s
            assertEquals(orig.hr, back.hr)
            assertEquals(orig.cad, back.cad)
            assertEquals(orig.pw, back.pw)
        }
        assertFalse(FitDecoder().also { it.decode(FitEncoder.encode(a, track)) }.checksumMismatch)
    }

    @Test fun anActivityWithoutATrackStillWritesOneRecord() {
        val bytes = FitEncoder.encode(TestData.activity(1), emptyList())
        val decoded = FitDecoder().decode(bytes)
        assertEquals(1, decoded.points.size)
    }

    @Test fun sportMappingBothWays() {
        for ((sport, expected) in listOf(
            SportType.Run to SportType.Run, SportType.TrailRun to SportType.Run, SportType.Treadmill to SportType.Run,
            SportType.Ride to SportType.Ride, SportType.IndoorRide to SportType.Ride, SportType.MountainBike to SportType.Ride,
            SportType.Walk to SportType.Walk, SportType.Hike to SportType.Hike,
        )) {
            val decoded = FitDecoder().decode(FitEncoder.encode(TestData.activity(1).copy(sport = sport), TestData.track(5)))
            assertEquals(sport.toString(), expected, decoded.sport)
        }
        assertEquals(SportType.Run, Fit.Sport.fromRaw(0)!!.sportType) // generic
        assertNull(Fit.Sport.fromRaw(99))
    }

    @Test fun readsABigEndianFileWithFieldsInAnUnusualOrderAndDeveloperFields() {
        val big = true
        val b = FitTestBuilder()
        val t0 = fitStamp("2026-03-14T07:30:05Z")
        // fields deliberately out of order, power omitted, enhanced altitude/speed used, one developer field
        b.definition(
            3, Fit.GlobalMessage.RECORD,
            listOf(field(3, 1, uint8), field(253, 4, uint32), field(0, 4, sint32), field(5, 4, uint32),
                field(1, 4, sint32), field(78, 4, uint32), field(73, 4, uint32), field(4, 1, uint8)),
            big = big, developer = listOf(field(0, 3, uint8)),
        )
        for (i in 0 until 4) {
            b.data(
                3,
                b.num(140L + i, 1, big),
                b.num(t0 + i, 4, big),
                b.num(Fit.semicircles(51.5 + i * 0.001).toLong() and 0xFFFFFFFFL, 4, big),
                b.num(i * 300L, 4, big),                             // 3.00 m per step
                b.num(Fit.semicircles(-0.12).toLong() and 0xFFFFFFFFL, 4, big),
                b.num(((100.0 + 500) * 5).toLong() + i * 5, 4, big),  // 100 m, +1 m per step
                b.num(2500L, 4, big),                                  // 2.5 m/s
                b.num(90L, 1, big),
                byteArrayOf(9, 9, 9),                                  // developer data, must be skipped
            )
        }
        val decoded = FitDecoder().decode(b.build())
        assertEquals(4, decoded.points.size)
        assertEquals(Instant.parse("2026-03-14T07:30:05Z"), decoded.startDate)
        val p = decoded.points[2]
        assertEquals(2.0, p.t, 0.0)
        assertEquals(51.502, p.lat, 1e-6)
        assertEquals(-0.12, p.lon, 1e-6)
        assertEquals(102.0, p.alt, 0.01)
        assertEquals(6.0, p.d, 1e-9)
        assertEquals(2.5, p.v, 1e-9)
        assertEquals(142.0, p.hr!!, 0.0)
        assertEquals(90.0, p.cad!!, 0.0)
        assertNull(p.pw)
    }

    @Test fun unknownMessagesAreSkippedAndALocalTypeCanBeRedefinedMidFile() {
        val b = FitTestBuilder()
        val t0 = fitStamp("2026-03-14T07:30:05Z")
        // an unknown global message first
        b.definition(5, 999, listOf(field(0, 2, uint16), field(1, 1, uint8)))
        b.data(5, b.num(7, 2, false), b.num(1, 1, false))
        // records, local type 2
        b.definition(2, Fit.GlobalMessage.RECORD, listOf(field(253, 4, uint32), field(0, 4, sint32), field(1, 4, sint32), field(3, 1, uint8)))
        b.data(2, b.num(t0, 4, false), b.num(Fit.semicircles(10.0).toLong() and 0xFFFFFFFFL, 4, false), b.num(Fit.semicircles(20.0).toLong() and 0xFFFFFFFFL, 4, false), b.num(100, 1, false))
        // redefine the SAME local type as a different message (big endian, fewer fields)
        b.definition(2, Fit.GlobalMessage.RECORD, listOf(field(253, 4, uint32), field(0, 4, sint32), field(1, 4, sint32)), big = true)
        b.data(2, b.num(t0 + 1, 4, true), b.num(Fit.semicircles(10.001).toLong() and 0xFFFFFFFFL, 4, true), b.num(Fit.semicircles(20.0).toLong() and 0xFFFFFFFFL, 4, true))
        // a session with sport = cycling
        b.definition(4, Fit.GlobalMessage.SESSION, listOf(field(5, 1, enum)))
        b.data(4, b.num(2, 1, false))
        val decoded = FitDecoder().decode(b.build())
        assertEquals(2, decoded.points.size)
        assertEquals(100.0, decoded.points[0].hr!!, 0.0)
        assertNull(decoded.points[1].hr)
        assertEquals(10.001, decoded.points[1].lat, 1e-6)
        assertEquals(SportType.Ride, decoded.sport)
    }

    @Test fun invalidMarkersAreDroppedAndZeroHeartRateIsNoReading() {
        val b = FitTestBuilder()
        val t0 = fitStamp("2026-03-14T07:30:05Z")
        b.definition(0, Fit.GlobalMessage.RECORD, listOf(field(253, 4, uint32), field(0, 4, sint32), field(1, 4, sint32), field(2, 2, uint16), field(3, 1, uint8), field(7, 2, uint16)))
        // position invalid (0x7FFFFFFF), altitude invalid (0xFFFF), heart rate 0, power invalid
        b.data(0, b.num(t0, 4, false), b.num(0x7FFFFFFFL, 4, false), b.num(0x7FFFFFFFL, 4, false), b.num(0xFFFF, 2, false), b.num(0, 1, false), b.num(0xFFFF, 2, false))
        b.data(0, b.num(t0 + 1, 4, false), b.num(Fit.semicircles(51.0).toLong() and 0xFFFFFFFFL, 4, false), b.num(Fit.semicircles(0.5).toLong() and 0xFFFFFFFFL, 4, false), b.num((600.0 * 5).toLong(), 2, false), b.num(88, 1, false), b.num(0, 2, false))
        val p = FitDecoder().decode(b.build()).points
        assertEquals(0.0, p[0].lat, 0.0)
        assertEquals(0.0, p[0].alt, 0.0)
        assertNull(p[0].hr)
        assertNull(p[0].pw)
        assertEquals(51.0, p[1].lat, 1e-6)
        assertEquals(100.0, p[1].alt, 0.2)
        assertEquals(88.0, p[1].hr!!, 0.0)
        assertEquals(0.0, p[1].pw!!, 0.0)  // zero watts is a real reading, unlike zero heart rate
    }

    @Test fun distanceAndSpeedAreMeasuredWhenNotRecorded() {
        val b = FitTestBuilder()
        val t0 = fitStamp("2026-03-14T07:30:05Z")
        b.definition(0, Fit.GlobalMessage.RECORD, listOf(field(253, 4, uint32), field(0, 4, sint32), field(1, 4, sint32)))
        for (i in 0 until 3) {
            b.data(0, b.num(t0 + i * 10, 4, false), b.num(Fit.semicircles(51.0 + i * 0.0001).toLong() and 0xFFFFFFFFL, 4, false), b.num(Fit.semicircles(0.0 + 0.5).toLong() and 0xFFFFFFFFL, 4, false))
        }
        val p = FitDecoder().decode(b.build()).points
        assertEquals(0.0, p[0].d, 0.0)
        assertEquals(11.1, p[1].d, 0.2)
        assertEquals(22.2, p[2].d, 0.4)
        assertEquals(1.11, p[2].v, 0.03)
    }

    @Test fun compressedTimestampRecordsLackATimestampAndAreDroppedWithoutStoppingTheParse() {
        val b = FitTestBuilder()
        val t0 = fitStamp("2026-03-14T07:30:05Z")
        b.definition(0, Fit.GlobalMessage.RECORD, listOf(field(253, 4, uint32), field(3, 1, uint8)))
        b.data(0, b.num(t0, 4, false), b.num(100, 1, false))
        b.definition(1, Fit.GlobalMessage.RECORD, listOf(field(3, 1, uint8)))
        b.raw(0x80 or (1 shl 5) or 5, 120) // compressed-timestamp header for local type 1, then its one field
        b.data(0, b.num(t0 + 2, 4, false), b.num(110, 1, false))
        val p = FitDecoder().decode(b.build()).points
        assertEquals(2, p.size)
        assertEquals(2.0, p[1].t, 0.0)
    }

    @Test fun badChecksumIsReportedButTheDataIsStillReturned() {
        val bytes = FitEncoder.encode(TestData.activity(1), TestData.track(20)).copyOf()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x55).toByte()
        val d = FitDecoder()
        val result = d.decode(bytes)
        assertTrue(d.checksumMismatch)
        assertEquals(20, result.points.size)
    }

    @Test fun aZeroChecksumMeansNotComputedAndIsAccepted() {
        val b = FitTestBuilder()
        b.definition(0, Fit.GlobalMessage.RECORD, listOf(field(253, 4, uint32)))
        b.data(0, b.num(fitStamp("2026-03-14T07:30:05Z"), 4, false))
        val d = FitDecoder()
        d.decode(b.build(writeCrc = false))
        assertFalse(d.checksumMismatch)
    }

    @Test fun aTruncatedFileStillYieldsTheRecordsThatSurvived() {
        val bytes = FitEncoder.encode(TestData.activity(1), TestData.track(50))
        val cut = bytes.copyOf(bytes.size - 120)
        val d = FitDecoder()
        val result = d.decode(cut)
        assertTrue(d.checksumMismatch)
        assertTrue(result.points.size in 30..50)
    }

    @Test fun twelveByteHeadersAreAccepted() {
        val b = FitTestBuilder()
        b.definition(0, Fit.GlobalMessage.RECORD, listOf(field(253, 4, uint32), field(3, 1, uint8)))
        b.data(0, b.num(fitStamp("2026-03-14T07:30:05Z"), 4, false), b.num(99, 1, false))
        val result = FitDecoder().decode(b.build(headerSize = 12))
        assertEquals(99.0, result.points[0].hr!!, 0.0)
    }

    @Test fun nonFitAndEmptyInputsFailCleanly() {
        assertThrows(FitFailure.Truncated::class.java) { FitDecoder().decode(ByteArray(10)) }
        assertThrows(FitFailure.NotFit::class.java) { FitDecoder().decode(ByteArray(40) { 7 }) }
        val noRecords = FitTestBuilder().also {
            it.definition(0, Fit.GlobalMessage.FILE_ID, listOf(field(0, 1, enum)))
            it.data(0, byteArrayOf(4))
        }.build()
        assertThrows(FitFailure.NoRecords::class.java) { FitDecoder().decode(noRecords) }
    }

    @Test fun anInvalidPositionIsNotWrittenAsAZeroZeroFix() {
        val bytes = FitEncoder.encode(TestData.activity(1), listOf(TrackPoint(0.0, 0.0, 0.0, 10.0, 0.0, 0.0), TrackPoint(1.0, 51.5, -0.1, 10.0, 3.0, 3.0)))
        val p = FitDecoder().decode(bytes).points
        assertEquals(0.0, p[0].lat, 0.0)
        assertEquals(51.5, p[1].lat, 1e-6)
        assertNotNull(p[1].hr == null)
    }
}
