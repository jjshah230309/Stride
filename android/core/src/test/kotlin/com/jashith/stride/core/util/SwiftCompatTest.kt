package com.jashith.stride.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SwiftCompatTest {
    @Test fun roundsHalfAwayFromZeroLikeSwift() {
        assertEquals(3.0, 2.5.swiftRounded(), 0.0)
        assertEquals(-3.0, (-2.5).swiftRounded(), 0.0)
        assertEquals(1.0, 0.5.swiftRounded(), 0.0)
        assertEquals(2.0, 1.5.swiftRounded(), 0.0)
        assertEquals(0.0, 0.49999999999999994.swiftRounded(), 0.0)
        assertEquals(5, 4.6.swiftRoundedToInt())
        assertEquals(-5, (-4.6).swiftRoundedToInt())
    }

    @Test fun fixedMatchesCPrintfRoundingOnTheExactBinaryValue() {
        // 0.15 is really 0.1499999999999999944..., so C prints 0.1 where java.util.Formatter prints 0.2.
        assertEquals("0.1", fixed(0.15, 1))
        // Exact ties are rounded half-to-even by glibc.
        assertEquals("2", fixed(2.5, 0))
        assertEquals("4", fixed(3.5, 0))
        assertEquals("0.12", fixed(0.125, 2))
        assertEquals("1.50", fixed(1.5, 2))
        assertEquals("-0", fixed(-0.4, 0))
        assertEquals("-0.0", fixed(-0.04, 1))
        assertEquals("12345.679", fixed(12345.6789, 3))
        assertEquals("nan", fixed(Double.NaN, 1))
    }

    @Test fun fmtIgnoresDeviceLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("4:05", fmt("%d:%02d", 4, 5))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
