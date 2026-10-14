package com.jashith.stride.core.speech

import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.SpokenMetric
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.model.VoiceSettings
import com.jashith.stride.core.util.swiftRounded
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

/**
 * Numbers as a person would say them out loud.
 *
 * A synthesiser reading "4:32" or "154" gives you "four colon thirty two" and "one hundred and fifty
 * four". Runners say "four thirty-two" and "one fifty-four", so the words are built here rather than
 * left to the voice.
 */
object SpokenNumber {

    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )

    private val tens = listOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    /** Conversational form: 154 becomes "one fifty-four", 205 "two oh five". */
    fun casual(n: Int): String {
        if (n < 0) return "minus " + casual(-n)
        if (n < 20) return ones[n]
        if (n < 100) {
            val remainder = n % 10
            return if (remainder == 0) tens[n / 10] else tens[n / 10] + "-" + ones[remainder]
        }
        if (n < 1000) {
            val hundreds = ones[n / 100]
            val remainder = n % 100
            if (remainder == 0) return "$hundreds hundred"
            if (remainder < 10) return hundreds + " oh " + ones[remainder]
            return hundreds + " " + casual(remainder)
        }
        return formal(n)
    }

    /** Full form: 486 becomes "four hundred and eighty-six". Right for counts. */
    fun formal(n: Int): String {
        if (n < 0) return "minus " + formal(-n)
        if (n < 100) return casual(n)
        if (n < 1000) {
            val remainder = n % 100
            val head = ones[n / 100] + " hundred"
            return if (remainder == 0) head else head + " and " + casual(remainder)
        }
        val thousands = n / 1000
        val remainder = n % 1000
        var out = formal(thousands) + " thousand"
        if (remainder > 0) {
            out += (if (remainder < 100) " and " else " ") + formal(remainder)
        }
        return out
    }

    /** Pace the way it is actually said: "four thirty-two", "four oh five", "five minutes flat". */
    fun pace(secondsPerUnit: Double): String? {
        if (!(secondsPerUnit.isFinite() && secondsPerUnit > 0 && secondsPerUnit < 60 * 60)) return null
        val total = secondsPerUnit.swiftRounded().toInt()
        val minutes = total / 60
        val seconds = total % 60
        if (seconds == 0) return casual(minutes) + " minutes flat"
        if (seconds < 10) return casual(minutes) + " oh " + ones[seconds]
        return casual(minutes) + " " + casual(seconds)
    }

    /** Durations read as a clock: "forty-two minutes", "one hour twelve". */
    fun duration(seconds: Double, verbose: Boolean = false): String {
        if (!(seconds.isFinite() && seconds >= 0)) return "unknown"
        val total = seconds.swiftRounded().toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60

        if (verbose) {
            val parts = ArrayList<String>()
            if (hours > 0) parts.add(casual(hours) + " hour" + (if (hours == 1) "" else "s"))
            if (minutes > 0) parts.add(casual(minutes) + " minute" + (if (minutes == 1) "" else "s"))
            if (secs > 0 || parts.isEmpty()) parts.add(casual(secs) + " second" + (if (secs == 1) "" else "s"))
            return parts.joinToString(" ")
        }

        if (hours > 0) {
            if (minutes == 0) return casual(hours) + (if (hours == 1) " hour" else " hours")
            return casual(hours) + " hour" + (if (hours == 1) "" else "s") + " " + casual(minutes)
        }
        if (minutes == 0) return casual(secs) + " second" + (if (secs == 1) "" else "s")
        if (secs == 0) return casual(minutes) + " minute" + (if (minutes == 1) "" else "s")
        if (secs < 10) return casual(minutes) + " oh " + ones[secs]
        return casual(minutes) + " " + casual(secs)
    }

    /** One decimal place, spoken: 28.4 becomes "twenty-eight point four". */
    fun decimal(value: Double): String {
        val rounded = (value * 10).swiftRounded() / 10
        val whole = rounded.toInt()
        val tenth = ((rounded - whole.toDouble()) * 10).swiftRounded().toInt()
        if (tenth <= 0) return formal(whole)
        return formal(whole) + " point " + casual(tenth)
    }

    /** Distances: "five kilometres", "seven point two miles", "eight hundred metres". */
    fun distance(meters: Double, unit: UnitSystem): String {
        val value = meters / unit.metersPerUnit
        if (value < 0.995) {
            if (unit == UnitSystem.Metric) return formal(meters.swiftRounded().toInt()) + " metres"
            return formal((meters / 0.3048).swiftRounded().toInt()) + " feet"
        }
        val rounded = (value * 10).swiftRounded() / 10
        val whole = rounded.toInt()
        val tenths = ((rounded - whole.toDouble()) * 10).swiftRounded().toInt()
        val noun = if (whole == 1 && tenths == 0) unit.spokenDistanceUnitSingular else unit.spokenDistanceUnit
        if (tenths == 0) return formal(whole) + " " + noun
        if (tenths == 5) return formal(whole) + " and a half " + noun
        return formal(whole) + " point " + ones[tenths] + " " + noun
    }
}

/**
 * Builds the phrases a synthesiser says, so it breathes between them instead of running everything
 * together behind commas. Android's TextToSpeech has no SSML, so the voice layer speaks [phrases]
 * one by one with a [breakMilliseconds] silence between; [ssml] and [plainText] exist for parity
 * with iOS and for the settings preview.
 */
class SpeechBuilder {
    private val _phrases = ArrayList<String>()
    var breakMilliseconds: Int = 220
    var ratePercentage: Int = 100

    val phrases: List<String> get() = _phrases.toList()

    fun add(phrase: String?) {
        if (phrase.isNullOrEmpty()) return
        _phrases.add(phrase)
    }

    val isEmpty: Boolean get() = _phrases.isEmpty()

    /** Plain text: what the settings preview shows. Commas keep it readable as one sentence. */
    val plainText: String
        get() {
            if (_phrases.isEmpty()) return ""
            var joined = _phrases.joinToString(", ")
            if (joined.isNotEmpty()) {
                val first = joined.codePointAt(0)
                val len = Character.charCount(first)
                joined = String(Character.toChars(first)).uppercase(Locale.ROOT) + joined.substring(len)
            }
            return "$joined."
        }

    val ssml: String
        get() {
            val body = _phrases.joinToString("<break time=\"${breakMilliseconds}ms\"/>") { escape(it) }
            if (ratePercentage == 100) return "<speak>$body</speak>"
            return "<speak><prosody rate=\"$ratePercentage%\">$body</prosody></speak>"
        }

    companion object {
        fun escape(text: String): String =
            text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
    }
}

/** The numbers the coach reads from. Filled in by the recording session. */
@Serializable
data class CoachSnapshot(
    val sport: SportType = SportType.Run,
    val splitIndex: Int = 0,
    val splitDistance: Double = 0.0,
    val splitTime: Double = 0.0,
    val splitPaceSecondsPerUnit: Double = 0.0,
    val totalDistance: Double = 0.0,
    val totalTime: Double = 0.0,
    val movingTime: Double = 0.0,
    val averagePaceSecondsPerUnit: Double = 0.0,
    val currentPaceSecondsPerUnit: Double = 0.0,
    val averageSpeed: Double = 0.0,
    val currentSpeed: Double = 0.0,
    val heartRate: Double = 0.0,
    val averageHeartRate: Double = 0.0,
    val cadence: Double = 0.0,
    val power: Double = 0.0,
    val averagePower: Double = 0.0,
    val elevationGain: Double = 0.0,
    val calories: Double = 0.0,
    val gradeAdjustedPaceSecondsPerUnit: Double = 0.0,
)

/**
 * Turns a snapshot into the words the coach will say. Pure text, no audio, no frameworks, so the
 * phrasing can be checked without a device.
 */
class CoachScript(val settings: VoiceSettings, val units: UnitSystem) {

    fun split(s: CoachSnapshot, isTimeInterval: Boolean): SpeechBuilder {
        val builder = SpeechBuilder()
        builder.breakMilliseconds = if (settings.style == SpeechStyle.Brief) 300 else 220
        for (metric in settings.metrics(s.sport)) {
            builder.add(phrase(metric, s, isTimeInterval))
        }
        if (settings.coachRemarks) {
            remark(s)?.let { builder.add(it) }
        }
        return builder
    }

    /** A light touch of encouragement, based on this split against the average. */
    fun remark(s: CoachSnapshot): String? {
        if (!(s.sport.usesPace && s.splitPaceSecondsPerUnit > 0 && s.averagePaceSecondsPerUnit > 0 && s.splitIndex > 1)) return null
        val delta = s.splitPaceSecondsPerUnit - s.averagePaceSecondsPerUnit
        return when {
            delta < -12 -> "that one was quick"
            delta < -4 -> "picking it up nicely"
            delta <= 4 -> "right on pace"
            delta <= 15 -> "easing off a touch"
            else -> "settle in and find the rhythm"
        }
    }

    fun phrase(metric: SpokenMetric, s: CoachSnapshot, isTimeInterval: Boolean): String? {
        val style = settings.style
        val unitWord = if (units == UnitSystem.Metric) "kilometre" else "mile"

        when (metric) {
            SpokenMetric.SplitNumber -> {
                val number = SpokenNumber.casual(s.splitIndex)
                if (isTimeInterval) {
                    return when (style) {
                        SpeechStyle.Brief -> number
                        SpeechStyle.Natural -> "Interval $number"
                        SpeechStyle.Detailed -> "Interval $number complete"
                    }
                }
                return when (style) {
                    SpeechStyle.Brief -> number
                    SpeechStyle.Natural -> capitalizedFirst(unitWord) + " " + number
                    SpeechStyle.Detailed -> capitalizedFirst(unitWord) + " " + number + " complete"
                }
            }

            SpokenMetric.SplitTime -> {
                if (!(s.splitTime > 0)) return null
                val value = SpokenNumber.duration(s.splitTime, verbose = style == SpeechStyle.Detailed)
                return when (style) {
                    SpeechStyle.Brief -> value
                    SpeechStyle.Natural -> "in $value"
                    SpeechStyle.Detailed -> "split time $value"
                }
            }

            SpokenMetric.SplitPace -> {
                val pace = SpokenNumber.pace(s.splitPaceSecondsPerUnit) ?: return null
                return when (style) {
                    SpeechStyle.Brief -> pace
                    SpeechStyle.Natural -> "pace $pace"
                    SpeechStyle.Detailed ->
                        "split pace " + SpokenNumber.duration(s.splitPaceSecondsPerUnit, verbose = true) + " per " + unitWord
                }
            }

            SpokenMetric.TotalDistance -> {
                if (!(s.totalDistance > 0)) return null
                val value = SpokenNumber.distance(s.totalDistance, units)
                return when (style) {
                    SpeechStyle.Brief -> value
                    SpeechStyle.Natural -> "$value so far"
                    SpeechStyle.Detailed -> "total distance $value"
                }
            }

            SpokenMetric.TotalTime -> {
                if (!(s.movingTime > 0)) return null
                val value = SpokenNumber.duration(s.movingTime, verbose = style == SpeechStyle.Detailed)
                return when (style) {
                    SpeechStyle.Brief -> value
                    SpeechStyle.Natural -> "running time $value"
                    SpeechStyle.Detailed -> "total moving time $value"
                }
            }

            SpokenMetric.AveragePace -> {
                val pace = SpokenNumber.pace(s.averagePaceSecondsPerUnit) ?: return null
                return when (style) {
                    SpeechStyle.Brief -> "average $pace"
                    SpeechStyle.Natural -> "averaging $pace"
                    SpeechStyle.Detailed ->
                        "average pace " + SpokenNumber.duration(s.averagePaceSecondsPerUnit, verbose = true) + " per " + unitWord
                }
            }

            SpokenMetric.CurrentPace -> {
                val pace = SpokenNumber.pace(s.currentPaceSecondsPerUnit) ?: return null
                return when (style) {
                    SpeechStyle.Brief -> pace
                    SpeechStyle.Natural -> "currently $pace"
                    SpeechStyle.Detailed ->
                        "current pace " + SpokenNumber.duration(s.currentPaceSecondsPerUnit, verbose = true) + " per " + unitWord
                }
            }

            SpokenMetric.GradeAdjustedPace -> {
                val pace = SpokenNumber.pace(s.gradeAdjustedPaceSecondsPerUnit) ?: return null
                return when (style) {
                    SpeechStyle.Brief -> "adjusted $pace"
                    SpeechStyle.Natural -> "flat equivalent $pace"
                    SpeechStyle.Detailed -> "grade adjusted pace $pace per $unitWord"
                }
            }

            SpokenMetric.AverageSpeed -> {
                if (!(s.averageSpeed > 0.2)) return null
                val value = SpokenNumber.decimal(Fmt.speedValue(s.averageSpeed, units))
                return when (style) {
                    SpeechStyle.Brief -> "average $value " + units.shortSpokenSpeedUnit
                    SpeechStyle.Natural -> "averaging $value " + units.shortSpokenSpeedUnit
                    SpeechStyle.Detailed -> "average speed $value " + units.spokenSpeedUnit
                }
            }

            SpokenMetric.CurrentSpeed -> {
                if (!(s.currentSpeed > 0.2)) return null
                val value = SpokenNumber.decimal(Fmt.speedValue(s.currentSpeed, units))
                return when (style) {
                    SpeechStyle.Brief -> value + " " + units.shortSpokenSpeedUnit
                    SpeechStyle.Natural -> "holding $value " + units.shortSpokenSpeedUnit
                    SpeechStyle.Detailed -> "current speed $value " + units.spokenSpeedUnit
                }
            }

            SpokenMetric.HeartRate -> {
                if (!(s.heartRate > 30)) return null
                val value = SpokenNumber.casual(s.heartRate.swiftRounded().toInt())
                return when (style) {
                    SpeechStyle.Brief -> value
                    SpeechStyle.Natural -> "heart rate $value"
                    SpeechStyle.Detailed -> "heart rate " + SpokenNumber.formal(s.heartRate.swiftRounded().toInt()) + " beats per minute"
                }
            }

            SpokenMetric.AverageHeartRate -> {
                if (!(s.averageHeartRate > 30)) return null
                val value = SpokenNumber.casual(s.averageHeartRate.swiftRounded().toInt())
                return when (style) {
                    SpeechStyle.Brief -> value
                    SpeechStyle.Natural -> "average heart rate $value"
                    SpeechStyle.Detailed -> "average heart rate " + SpokenNumber.formal(s.averageHeartRate.swiftRounded().toInt()) + " beats per minute"
                }
            }

            SpokenMetric.Cadence -> {
                if (!(s.cadence > 5)) return null
                val value = SpokenNumber.casual(s.cadence.swiftRounded().toInt())
                val noun = if (s.sport.isRide) "revolutions per minute" else "steps per minute"
                return when (style) {
                    SpeechStyle.Brief -> value
                    SpeechStyle.Natural -> "cadence $value"
                    SpeechStyle.Detailed -> "cadence $value $noun"
                }
            }

            SpokenMetric.Power -> {
                if (!(s.power > 5)) return null
                val value = SpokenNumber.formal(s.power.swiftRounded().toInt())
                return when (style) {
                    SpeechStyle.Brief -> "$value watts"
                    SpeechStyle.Natural -> "power $value"
                    SpeechStyle.Detailed -> "power $value watts"
                }
            }

            SpokenMetric.AveragePower -> {
                if (!(s.averagePower > 5)) return null
                val value = SpokenNumber.formal(s.averagePower.swiftRounded().toInt())
                return when (style) {
                    SpeechStyle.Brief -> "average $value watts"
                    SpeechStyle.Natural -> "averaging $value watts"
                    SpeechStyle.Detailed -> "average power $value watts"
                }
            }

            SpokenMetric.ElevationGain -> {
                if (!(s.elevationGain > 1)) return null
                val value = SpokenNumber.formal(Fmt.elevationValue(s.elevationGain, units).swiftRounded().toInt())
                return when (style) {
                    SpeechStyle.Brief -> "$value up"
                    SpeechStyle.Natural -> "climbed $value " + units.spokenElevationUnit
                    SpeechStyle.Detailed -> "elevation gain $value " + units.spokenElevationUnit
                }
            }

            SpokenMetric.Calories -> {
                if (!(s.calories > 1)) return null
                val value = SpokenNumber.formal(s.calories.swiftRounded().toInt())
                return when (style) {
                    SpeechStyle.Brief -> value
                    SpeechStyle.Natural -> "$value calories"
                    SpeechStyle.Detailed -> "$value calories burned"
                }
            }
        }
    }

    // The other announcements

    fun start(sport: SportType): SpeechBuilder {
        val builder = SpeechBuilder()
        when (settings.style) {
            SpeechStyle.Brief -> builder.add("Started")
            SpeechStyle.Natural -> builder.add("Let's go")
            SpeechStyle.Detailed -> builder.add("Starting your " + sport.displayName.lowercase(Locale.ROOT))
        }
        return builder
    }

    fun finish(s: CoachSnapshot): SpeechBuilder {
        val builder = SpeechBuilder()
        builder.add(if (settings.style == SpeechStyle.Brief) "Done" else "Nice work")
        builder.add(SpokenNumber.distance(s.totalDistance, units))
        builder.add("in " + SpokenNumber.duration(s.movingTime, verbose = settings.style == SpeechStyle.Detailed))
        val pace = if (s.sport.usesPace) SpokenNumber.pace(s.averagePaceSecondsPerUnit) else null
        if (pace != null) {
            builder.add(if (settings.style == SpeechStyle.Brief) pace else "averaging $pace")
        } else if (s.averageSpeed > 0) {
            builder.add(
                "averaging " + SpokenNumber.decimal(Fmt.speedValue(s.averageSpeed, units)) + " " + units.spokenSpeedUnit,
            )
        }
        return builder
    }

    fun lap(number: Int, s: CoachSnapshot): SpeechBuilder {
        val builder = SpeechBuilder()
        builder.add("Lap " + SpokenNumber.casual(number))
        builder.add(SpokenNumber.duration(s.splitTime, verbose = settings.style == SpeechStyle.Detailed))
        if (s.sport.usesPace) {
            SpokenNumber.pace(s.splitPaceSecondsPerUnit)?.let { builder.add(it) }
        }
        return builder
    }

    fun halfway(s: CoachSnapshot): SpeechBuilder {
        val builder = SpeechBuilder()
        builder.add("Halfway")
        builder.add(SpokenNumber.distance(s.totalDistance, units))
        builder.add("in " + SpokenNumber.duration(s.movingTime))
        return builder
    }

    fun paceAlert(current: Double, target: Double): SpeechBuilder? {
        if (!(target > 0 && current > 0)) return null
        val delta = current - target
        if (!(abs(delta) > settings.targetPaceToleranceSeconds)) return null
        val seconds = abs(delta).swiftRounded().toInt()
        val plural = if (seconds == 1) "" else "s"
        val builder = SpeechBuilder()
        if (delta > 0) {
            builder.add(SpokenNumber.casual(seconds) + " second" + plural + " behind target")
            builder.add("lift it a little")
        } else {
            builder.add(SpokenNumber.casual(seconds) + " second" + plural + " ahead of target")
            builder.add("ease back if you want to hold it")
        }
        return builder
    }

    fun zoneAlert(zone: Int, target: Int): SpeechBuilder? {
        if (target <= 0) return null
        val builder = SpeechBuilder()
        if (zone + 1 > target) {
            builder.add("Above zone " + SpokenNumber.casual(target))
            builder.add("ease off")
        } else if (zone + 1 < target) {
            builder.add("Below zone " + SpokenNumber.casual(target))
            builder.add("pick it up")
        } else {
            return null
        }
        return builder
    }

    fun segmentStart(name: String, prTime: Double?): SpeechBuilder {
        val builder = SpeechBuilder()
        builder.add("Segment $name")
        if (prTime != null && prTime > 0) {
            builder.add("your best is " + SpokenNumber.duration(prTime))
            builder.add("go")
        }
        return builder
    }

    fun segmentProgress(deltaSeconds: Double, remaining: Double): SpeechBuilder {
        val builder = SpeechBuilder()
        val seconds = abs(deltaSeconds).swiftRounded().toInt()
        if (seconds == 0) {
            builder.add("Level with your best")
        } else {
            val plural = if (seconds == 1) "" else "s"
            builder.add(
                SpokenNumber.casual(seconds) + " second" + plural +
                    (if (deltaSeconds < 0) " ahead of your best" else " behind your best"),
            )
        }
        builder.add(SpokenNumber.distance(remaining, units) + " to go")
        return builder
    }

    fun segmentFinish(name: String, time: Double, isPR: Boolean, delta: Double?): SpeechBuilder {
        val builder = SpeechBuilder()
        if (isPR) {
            builder.add("New record on $name")
            builder.add(SpokenNumber.duration(time))
        } else {
            builder.add("$name done")
            builder.add(SpokenNumber.duration(time))
            if (delta != null && delta > 0) {
                builder.add(SpokenNumber.casual(delta.swiftRounded().toInt()) + " off your best")
            }
        }
        return builder
    }

    private fun capitalizedFirst(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase(Locale.ROOT) + s.substring(1)

    companion object {
        /** A worked example, used by the settings preview. */
        val sampleSnapshot: CoachSnapshot = CoachSnapshot(
            sport = SportType.Run,
            splitIndex = 3,
            splitTime = 272.0,
            splitDistance = 1000.0,
            splitPaceSecondsPerUnit = 272.0,
            totalDistance = 3000.0,
            movingTime = 831.0,
            totalTime = 845.0,
            averagePaceSecondsPerUnit = 277.0,
            currentPaceSecondsPerUnit = 268.0,
            gradeAdjustedPaceSecondsPerUnit = 264.0,
            averageSpeed = 7.2,
            currentSpeed = 7.4,
            heartRate = 154.0,
            averageHeartRate = 151.0,
            cadence = 176.0,
            power = 243.0,
            averagePower = 231.0,
            elevationGain = 84.0,
            calories = 320.0,
        )
    }
}
