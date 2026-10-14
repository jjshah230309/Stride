package com.jashith.stride.core.speech

import com.jashith.stride.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenNumberTest {

    @Test fun casualIsHowRunnersSayNumbers() {
        assertEquals("zero", SpokenNumber.casual(0))
        assertEquals("seven", SpokenNumber.casual(7))
        assertEquals("nineteen", SpokenNumber.casual(19))
        assertEquals("twenty", SpokenNumber.casual(20))
        assertEquals("twenty-one", SpokenNumber.casual(21))
        assertEquals("ninety-nine", SpokenNumber.casual(99))
        assertEquals("one hundred", SpokenNumber.casual(100))
        assertEquals("one oh five", SpokenNumber.casual(105))
        assertEquals("one fifty-four", SpokenNumber.casual(154))
        assertEquals("two oh five", SpokenNumber.casual(205))
        assertEquals("nine ninety-nine", SpokenNumber.casual(999))
        assertEquals("minus five", SpokenNumber.casual(-5))
        assertEquals("one thousand", SpokenNumber.casual(1000)) // beyond 999 it defers to the formal form
    }

    @Test fun formalIsFullForm() {
        assertEquals("one hundred", SpokenNumber.formal(100))
        assertEquals("one hundred and one", SpokenNumber.formal(101))
        assertEquals("four hundred and eighty-six", SpokenNumber.formal(486))
        assertEquals("one thousand", SpokenNumber.formal(1000))
        assertEquals("one thousand and one", SpokenNumber.formal(1001))
        assertEquals("one thousand and fifty", SpokenNumber.formal(1050))
        assertEquals("two thousand five hundred", SpokenNumber.formal(2500))
        assertEquals("twelve thousand three hundred and forty-five", SpokenNumber.formal(12345))
        assertEquals("minus one hundred and twenty", SpokenNumber.formal(-120))
        assertEquals("forty-two", SpokenNumber.formal(42))
    }

    @Test fun paceReadsLikeAClock() {
        assertEquals("four thirty-two", SpokenNumber.pace(272.0))
        assertEquals("four oh five", SpokenNumber.pace(245.0))
        assertEquals("five minutes flat", SpokenNumber.pace(300.0))
        assertEquals("ten oh nine", SpokenNumber.pace(609.0))
        assertEquals("fifty-nine twenty", SpokenNumber.pace(3560.0))
        // rounds half away from zero, and always says "minutes", exactly as the iOS app does
        assertEquals("one minutes flat", SpokenNumber.pace(59.5))
        assertEquals("four thirty-three", SpokenNumber.pace(272.5))
        assertNull(SpokenNumber.pace(0.0))
        assertNull(SpokenNumber.pace(-3.0))
        assertNull(SpokenNumber.pace(3600.0))
        assertNull(SpokenNumber.pace(Double.NaN))
        assertNull(SpokenNumber.pace(Double.POSITIVE_INFINITY))
    }

    @Test fun durationNonVerbose() {
        assertEquals("one hour two", SpokenNumber.duration(3720.0))
        assertEquals("one hour", SpokenNumber.duration(3600.0))
        assertEquals("two hours", SpokenNumber.duration(7200.0))
        assertEquals("two hours thirty", SpokenNumber.duration(9030.0))   // seconds are dropped past an hour
        assertEquals("forty-five seconds", SpokenNumber.duration(45.0))
        assertEquals("one second", SpokenNumber.duration(1.0))
        assertEquals("zero seconds", SpokenNumber.duration(0.0))
        assertEquals("one minute", SpokenNumber.duration(60.0))
        assertEquals("two minutes", SpokenNumber.duration(120.0))
        assertEquals("two oh five", SpokenNumber.duration(125.0))
        assertEquals("four thirty-two", SpokenNumber.duration(272.0))
        assertEquals("unknown", SpokenNumber.duration(-1.0))
        assertEquals("unknown", SpokenNumber.duration(Double.NaN))
    }

    @Test fun durationVerbose() {
        assertEquals("one hour two minutes five seconds", SpokenNumber.duration(3725.0, verbose = true))
        assertEquals("four minutes thirty-two seconds", SpokenNumber.duration(272.0, verbose = true))
        assertEquals("two hours", SpokenNumber.duration(7200.0, verbose = true))
        assertEquals("one minute", SpokenNumber.duration(60.0, verbose = true))
        assertEquals("zero seconds", SpokenNumber.duration(0.0, verbose = true))
        assertEquals("one hour one second", SpokenNumber.duration(3601.0, verbose = true))
    }

    @Test fun decimalSpeaksOnePlace() {
        assertEquals("twenty-eight point four", SpokenNumber.decimal(28.4))
        assertEquals("twenty-eight", SpokenNumber.decimal(28.0))
        assertEquals("twenty-nine", SpokenNumber.decimal(28.96))
        assertEquals("seven point three", SpokenNumber.decimal(7.25))
        assertEquals("zero point five", SpokenNumber.decimal(0.5))
        assertEquals("twenty-five point nine", SpokenNumber.decimal(25.92))
    }

    @Test fun distanceMetric() {
        assertEquals("one and a half kilometres", SpokenNumber.distance(1500.0, UnitSystem.Metric))
        assertEquals("eight hundred metres", SpokenNumber.distance(800.0, UnitSystem.Metric))
        assertEquals("nine hundred and ninety-four metres", SpokenNumber.distance(994.0, UnitSystem.Metric))
        assertEquals("one kilometre", SpokenNumber.distance(995.0, UnitSystem.Metric))
        assertEquals("one kilometre", SpokenNumber.distance(1000.0, UnitSystem.Metric))
        assertEquals("five kilometres", SpokenNumber.distance(5000.0, UnitSystem.Metric))
        assertEquals("seven point two kilometres", SpokenNumber.distance(7234.0, UnitSystem.Metric))
        assertEquals("two and a half kilometres", SpokenNumber.distance(2500.0, UnitSystem.Metric))
        assertEquals("forty-two point two kilometres", SpokenNumber.distance(42195.0, UnitSystem.Metric))
    }

    @Test fun distanceImperial() {
        assertEquals("one mile", SpokenNumber.distance(1609.344, UnitSystem.Imperial))
        assertEquals("five miles", SpokenNumber.distance(8046.72, UnitSystem.Imperial))
        assertEquals("three hundred and twenty-eight feet", SpokenNumber.distance(100.0, UnitSystem.Imperial))
        assertEquals("one point nine miles", SpokenNumber.distance(3000.0, UnitSystem.Imperial))
        assertEquals("twenty-six point two miles", SpokenNumber.distance(42195.0, UnitSystem.Imperial))
    }

    @Test fun speechBuilderJoinsAndEscapes() {
        val b = SpeechBuilder()
        assertEquals(true, b.isEmpty)
        assertEquals("", b.plainText)
        b.add(null)
        b.add("")
        assertEquals(true, b.isEmpty)
        b.add("kilometre three")
        b.add("in four thirty-two")
        assertEquals("Kilometre three, in four thirty-two.", b.plainText)
        assertEquals("<speak>kilometre three<break time=\"220ms\"/>in four thirty-two</speak>", b.ssml)
        b.breakMilliseconds = 300
        b.ratePercentage = 90
        assertEquals("<speak><prosody rate=\"90%\">kilometre three<break time=\"300ms\"/>in four thirty-two</prosody></speak>", b.ssml)
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", SpeechBuilder.escape("a & b <c> \"d\" 'e'"))
    }
}
