@file:UseSerializers(InstantSerializer::class)

package com.jashith.stride.core.model

import com.jashith.stride.core.json.InstantSerializer
import com.jashith.stride.core.speech.SpeechStyle
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.Year
import kotlin.math.max
import kotlin.math.min

// Voice coach

@Serializable
enum class AnnouncementTrigger {
    @SerialName("distance") Distance,
    @SerialName("time") Time,
    @SerialName("both") Both,
    @SerialName("off") Off;

    val displayName: String
        get() = when (this) {
            Distance -> "Every split"
            Time -> "Every interval"
            Both -> "Splits and intervals"
            Off -> "Off"
        }
}

/** Everything the coach can read out. Order here is the order it speaks. */
@Serializable
enum class SpokenMetric {
    @SerialName("splitNumber") SplitNumber,
    @SerialName("splitPace") SplitPace,
    @SerialName("splitTime") SplitTime,
    @SerialName("totalDistance") TotalDistance,
    @SerialName("totalTime") TotalTime,
    @SerialName("averagePace") AveragePace,
    @SerialName("currentPace") CurrentPace,
    @SerialName("averageSpeed") AverageSpeed,
    @SerialName("currentSpeed") CurrentSpeed,
    @SerialName("heartRate") HeartRate,
    @SerialName("averageHeartRate") AverageHeartRate,
    @SerialName("cadence") Cadence,
    @SerialName("power") Power,
    @SerialName("averagePower") AveragePower,
    @SerialName("elevationGain") ElevationGain,
    @SerialName("calories") Calories,
    @SerialName("gradeAdjustedPace") GradeAdjustedPace;

    val displayName: String
        get() = when (this) {
            SplitNumber -> "Split number"
            SplitPace -> "Split pace"
            SplitTime -> "Split time"
            TotalDistance -> "Total distance"
            TotalTime -> "Total time"
            AveragePace -> "Average pace"
            CurrentPace -> "Current pace"
            AverageSpeed -> "Average speed"
            CurrentSpeed -> "Current speed"
            HeartRate -> "Heart rate"
            AverageHeartRate -> "Average heart rate"
            Cadence -> "Cadence"
            Power -> "Power"
            AveragePower -> "Average power"
            ElevationGain -> "Elevation gain"
            Calories -> "Calories"
            GradeAdjustedPace -> "Grade adjusted pace"
        }

    val suitsRunning: Boolean get() = this != AverageSpeed && this != CurrentSpeed

    val suitsCycling: Boolean
        get() = when (this) {
            SplitPace, AveragePace, CurrentPace, GradeAdjustedPace -> false
            else -> true
        }

    companion object {
        val runDefaults: List<SpokenMetric> = listOf(SplitNumber, SplitTime, SplitPace, AveragePace)
        val rideDefaults: List<SpokenMetric> = listOf(TotalDistance, TotalTime, AverageSpeed, AveragePower)
    }
}

@Serializable
data class VoiceSettings(
    val enabled: Boolean = true,
    val trigger: AnnouncementTrigger = AnnouncementTrigger.Distance,
    /** In display distance units: 1.0 means every kilometre or every mile. */
    val distanceInterval: Double = 1.0,
    /** In seconds. */
    val timeInterval: Double = 300.0,

    val runMetrics: List<SpokenMetric> = SpokenMetric.runDefaults,
    val rideMetrics: List<SpokenMetric> = SpokenMetric.rideDefaults,

    val announceStart: Boolean = true,
    val announcePauseResume: Boolean = true,
    val announceFinish: Boolean = true,
    val announceAutoPause: Boolean = false,
    val announceLaps: Boolean = true,
    val announceSegments: Boolean = true,
    val announceGoalMilestones: Boolean = true,
    val announceHalfway: Boolean = false,
    val countdownBeforeStart: Boolean = true,

    /** Target pace nagging, in seconds per display unit. Zero disables it. */
    val targetPaceSeconds: Double = 0.0,
    val targetPaceToleranceSeconds: Double = 15.0,
    val targetPaceAlertsEnabled: Boolean = false,
    val targetHeartRateZone: Int = 0,       // 0 = off, otherwise 1…5
    val zoneAlertsEnabled: Boolean = false,

    /** How much scaffolding goes around the numbers. */
    val style: SpeechStyle = SpeechStyle.Natural,
    /** The occasional "nicely on pace" after a split. */
    val coachRemarks: Boolean = false,

    /** iOS stores an AVSpeechUtterance rate here (0 to 1, 0.5 is normal); Android maps it to TTS speech rate. */
    val rate: Double = 0.5,
    val pitch: Double = 1.0,
    val volume: Double = 1.0,
    /** Identifies a system voice. Empty means the system default. Platform specific. */
    val voiceIdentifier: String = "",
    val duckMusic: Boolean = true,
    val useVoiceWhileLocked: Boolean = true,
) {
    fun metrics(sport: SportType): List<SpokenMetric> = if (sport.usesPace) runMetrics else rideMetrics
}

// Zones

@Serializable
data class HeartRateZones(
    /** Upper bound of each of the five zones, as a fraction of max HR. */
    val bounds: List<Double> = listOf(0.60, 0.70, 0.80, 0.90, 1.20),
    val maxHR: Double = 190.0,
    val restingHR: Double = 60.0,
    val lactateThresholdHR: Double = 170.0,
    val useThresholdBased: Boolean = false,
) {
    /** Lower and upper bpm for a zone index 0…4. */
    fun range(index: Int): Pair<Double, Double> {
        val reference = if (useThresholdBased) lactateThresholdHR else maxHR
        val b = if (useThresholdBased) thresholdBounds else bounds
        val lower = if (index == 0) 0.0 else b[index - 1] * reference
        val upper = b[min(index, b.size - 1)] * reference
        return lower to upper
    }

    fun zone(hr: Double): Int {
        for (i in 0 until 5) {
            val (_, upper) = range(i)
            if (hr < upper) return i
        }
        return 4
    }

    companion object {
        val names = listOf("Endurance", "Moderate", "Tempo", "Threshold", "Anaerobic")
        val shortNames = listOf("Z1", "Z2", "Z3", "Z4", "Z5")
        private val thresholdBounds = listOf(0.81, 0.89, 0.94, 1.00, 1.30)
    }
}

@Serializable
data class PowerZones(
    val ftp: Double = 200.0,
    /** Coggan's seven zones as fractions of FTP. */
    val bounds: List<Double> = listOf(0.55, 0.75, 0.90, 1.05, 1.20, 1.50, 4.00),
) {
    fun range(index: Int): Pair<Double, Double> {
        val lower = if (index == 0) 0.0 else bounds[index - 1] * ftp
        val upper = bounds[min(index, bounds.size - 1)] * ftp
        return lower to upper
    }

    fun zone(watts: Double): Int {
        for (i in bounds.indices) {
            if (watts < bounds[i] * ftp) return i
        }
        return bounds.size - 1
    }

    companion object {
        val names = listOf("Active Recovery", "Endurance", "Tempo", "Threshold", "VO2 Max", "Anaerobic", "Neuromuscular")
        val shortNames = listOf("Z1", "Z2", "Z3", "Z4", "Z5", "Z6", "Z7")
    }
}

@Serializable
data class PaceZones(
    /** Threshold pace in seconds per kilometre. */
    val thresholdPace: Double = 300.0,
    /** Upper bound of each zone as a multiple of threshold pace (slower = larger). */
    val bounds: List<Double> = listOf(1.29, 1.14, 1.06, 1.00, 0.90),
) {
    fun zone(secondsPerKm: Double): Int {
        if (!(secondsPerKm > 0)) return 0
        for (i in bounds.indices) {
            if (secondsPerKm > bounds[i] * thresholdPace) return i
        }
        return bounds.size - 1
    }

    companion object {
        val names = listOf("Easy", "Moderate", "Tempo", "Threshold", "Interval")
        val shortNames = listOf("Z1", "Z2", "Z3", "Z4", "Z5")
    }
}

// Athlete profile and app settings

@Serializable
data class AthleteProfile(
    val name: String = "",
    val weightKg: Double = 70.0,
    val heightCm: Double = 175.0,
    val birthYear: Int = 1995,
    val isMale: Boolean = true,

    val hrZones: HeartRateZones = HeartRateZones(),
    val powerZones: PowerZones = PowerZones(),
    val paceZones: PaceZones = PaceZones(),

    /** Bike plus kit mass added to the rider for the cycling power estimate. */
    val bikeWeightKg: Double = 9.0,
    val rollingResistance: Double = 0.005,
    val dragArea: Double = 0.32,            // CdA in m²
) {
    val age: Int get() = max(10, Year.now().value - birthYear)

    /** Tanaka formula, a better predictor than 220 minus age. */
    val predictedMaxHR: Double get() = 208 - 0.7 * age.toDouble()
}

/**
 * The `writeToAppleHealth` / `readFromAppleHealth` / `backfillHeartRateFromHealth` keys keep their
 * iOS names so settings survive a backup round trip; on Android they control Health Connect.
 */
@Serializable
data class DisplaySettings(
    val units: UnitSystem = UnitSystem.Metric,
    val autoPause: Boolean = true,
    val keepScreenOn: Boolean = true,
    val countdownSeconds: Int = 3,
    val mapStyle: String = "standard",          // standard, hybrid, satellite
    val showLiveSegments: Boolean = true,
    val liveSegmentVoice: Boolean = true,
    val recordingMetrics: List<String> = listOf("duration", "distance", "pace", "heartRate", "elevation", "calories"),
    val gpsAccuracyFilter: Double = 30.0,       // discard fixes worse than this, in metres
    val writeToAppleHealth: Boolean = true,
    val readFromAppleHealth: Boolean = true,
    /** After saving, re-read heart rate for the whole activity, picking up everything a watch recorded late. */
    val backfillHeartRateFromHealth: Boolean = true,
    val defaultSport: SportType = SportType.Run,
    val weekStartsMonday: Boolean = true,
    val hasCompletedOnboarding: Boolean = false,
)

/** How often a copy of everything is written, and how many are kept. */
@Serializable
data class BackupSettings(
    val automatic: Boolean = false,
    /** Days between backups. Months are expressed as 30, 90. */
    val intervalDays: Int = 7,
    val keepCount: Int = 5,
    val lastRun: Instant? = null,
    val lastFilename: String = "",
    val lastActivityCount: Int = 0,
) {
    val intervalName: String
        get() = intervalChoices.firstOrNull { it.first == intervalDays }?.second ?: "Every $intervalDays days"

    fun isDue(now: Instant = Instant.now()): Boolean {
        if (!automatic) return false
        val last = lastRun ?: return true
        return (now.toEpochMilli() - last.toEpochMilli()) / 1000.0 >= intervalDays.toDouble() * 86400
    }

    fun nextDue(now: Instant = Instant.now()): Instant? {
        if (!automatic) return null
        val last = lastRun ?: return now
        return last.plusSeconds(intervalDays.toLong() * 86400)
    }

    companion object {
        val intervalChoices: List<Pair<Int, String>> = listOf(
            1 to "Every day",
            3 to "Every 3 days",
            7 to "Every week",
            14 to "Every 2 weeks",
            30 to "Every month",
            90 to "Every 3 months",
        )
    }
}

/**
 * Every section is optional on the way in, so adding one in a later version cannot make an
 * existing settings file unreadable (and a damaged section resets only itself).
 */
@Serializable(with = AppSettingsSerializer::class)
data class AppSettings(
    val profile: AthleteProfile = AthleteProfile(),
    val display: DisplaySettings = DisplaySettings(),
    val voice: VoiceSettings = VoiceSettings(),
    val backup: BackupSettings = BackupSettings(),
) {
    val units: UnitSystem get() = display.units
}

object AppSettingsSerializer : KSerializer<AppSettings> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AppSettings")

    override fun serialize(encoder: Encoder, value: AppSettings) {
        val output = encoder as? JsonEncoder ?: error("AppSettings can only be written as JSON")
        val json = output.json
        output.encodeJsonElement(
            buildJsonObject {
                put("profile", json.encodeToJsonElement(AthleteProfile.serializer(), value.profile))
                put("display", json.encodeToJsonElement(DisplaySettings.serializer(), value.display))
                put("voice", json.encodeToJsonElement(VoiceSettings.serializer(), value.voice))
                put("backup", json.encodeToJsonElement(BackupSettings.serializer(), value.backup))
            },
        )
    }

    override fun deserialize(decoder: Decoder): AppSettings {
        val input = decoder as? JsonDecoder ?: error("AppSettings can only be read from JSON")
        val json = input.json
        val obj: JsonObject = input.decodeJsonElement().jsonObject
        fun <T> section(key: String, serializer: KSerializer<T>, fallback: T): T {
            val element = obj[key] ?: return fallback
            return try {
                json.decodeFromJsonElement(serializer, element)
            } catch (_: Exception) {
                fallback
            }
        }
        return AppSettings(
            profile = section("profile", AthleteProfile.serializer(), AthleteProfile()),
            display = section("display", DisplaySettings.serializer(), DisplaySettings()),
            voice = section("voice", VoiceSettings.serializer(), VoiceSettings()),
            backup = section("backup", BackupSettings.serializer(), BackupSettings()),
        )
    }
}
