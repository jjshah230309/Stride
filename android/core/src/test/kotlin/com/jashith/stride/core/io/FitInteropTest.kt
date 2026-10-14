package com.jashith.stride.core.io

import com.jashith.stride.core.model.SportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * `fit-tool-ride.fit` was written by `fit-tool` (see Tools/make-fit-fixture.py), a FIT writer that
 * shares no code with Stride's. It also carries `device_info` and `event` messages the reader must skip.
 */
class FitInteropTest {
    private val bytes = javaClass.getResource("/fixtures/fit-tool-ride.fit")!!.readBytes()

    @Test fun decodesAFileWrittenByAnIndependentEncoder() {
        val decoder = FitDecoder()
        val r = decoder.decode(bytes)
        assertFalse("checksum", decoder.checksumMismatch)
        assertEquals(SportType.Ride, r.sport)
        assertEquals(Instant.parse("2026-03-14T07:30:05Z"), r.startDate)
        assertEquals(120, r.points.size)
        assertEquals(780.0, r.totalDistance!!, 0.0)
        assertEquals(120.0, r.totalTimerTime!!, 0.0)
        assertEquals(64.0, r.totalCalories!!, 0.0)
        assertEquals(12.0, r.totalAscent!!, 0.0)
        assertEquals(144.0, r.averageHeartRate!!, 0.0)
        assertEquals(159.0, r.maxHeartRate!!, 0.0)

        val p = r.points[3]
        assertEquals(3.0, p.t, 0.0)
        assertEquals(51.5 + 3 * 0.00018, p.lat, 1e-6)
        assertEquals(-0.12 + 3 * 0.00009, p.lon, 1e-6)
        assertEquals(30.3, p.alt, 0.2)
        assertEquals(19.5, p.d, 0.01)
        assertEquals(6.5, p.v, 0.001)
        assertEquals(133.0, p.hr!!, 0.0)
        assertEquals(88.0, p.cad!!, 0.0)
        assertEquals(203.0, p.pw!!, 0.0)
        assertNull(r.points[4].pw)             // power only on every third sample
        assertEquals(119.0 * 6.5, r.points.last().d, 0.01)
    }

    @Test fun loadsThroughTheFormatSniffer() {
        val i = ActivityFile.load(bytes, "ride.bin")
        assertEquals(ActivityFile.Format.Fit, i.format)
        assertEquals("ride", i.name)
        assertEquals(SportType.Ride, i.sport)
        assertNull(i.warning)
    }
}
