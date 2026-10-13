package com.jashith.stride.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun eightBitValue() = assertEquals(142, HeartRateMeasurement.parse(bytes(0x00, 142)))

    @Test fun eightBitValueAbove127IsNotNegative() = assertEquals(200, HeartRateMeasurement.parse(bytes(0x10, 200, 0x01)))

    @Test fun sixteenBitValueIsLittleEndian() = assertEquals(0x0123, HeartRateMeasurement.parse(bytes(0x01, 0x23, 0x01)))

    @Test fun shortPacketsAreRejected() {
        assertNull(HeartRateMeasurement.parse(bytes()))
        assertNull(HeartRateMeasurement.parse(bytes(0x00)))
        assertNull(HeartRateMeasurement.parse(bytes(0x01, 0x40)))
    }
}
