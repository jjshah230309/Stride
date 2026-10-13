@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.jashith.stride.core.golden

import com.jashith.stride.core.json.InstantSerializer
import com.jashith.stride.core.json.StrideJson
import com.jashith.stride.core.json.UuidSerializer
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.model.AthleteProfile
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.SavedRoute
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SegmentEffort
import com.jashith.stride.core.model.SpokenMetric
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.model.VoiceSettings
import com.jashith.stride.core.speech.CoachSnapshot
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.fail
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * The files under `src/test/resources/golden` are written by the *real iOS Swift code*, compiled on
 * Linux by Tools/swift-reference. These tests run the Kotlin port over the same inputs.
 */
object Golden {
    fun text(name: String): String = Golden::class.java.getResource("/golden/$name")!!.readText()
    fun bytes(name: String): ByteArray = Golden::class.java.getResource("/golden/$name")!!.readBytes()
    fun <T> read(name: String, serializer: KSerializer<T>): T = StrideJson.decodeFromString(serializer, text(name))
    fun <T> readList(name: String, serializer: KSerializer<T>): List<T> = read(name, ListSerializer(serializer))
    fun fixture(name: String): ByteArray = Golden::class.java.getResource("/fixtures/$name")!!.readBytes()
}

/** Compares two JSON trees, numbers within a tolerance (libm functions differ by an ulp between platforms). */
object JsonCompare {
    fun <T> assertSame(
        serializer: KSerializer<T>,
        expected: T,
        actual: T,
        label: String = "",
        ignoreKeys: Set<String> = emptySet(),
        tolerance: Double = 1e-9,
    ) {
        val e = StrideJson.encodeToJsonElement(serializer, expected)
        val a = StrideJson.encodeToJsonElement(serializer, actual)
        compare(label, e, a, ignoreKeys, tolerance)
    }

    fun compare(path: String, expected: JsonElement, actual: JsonElement, ignoreKeys: Set<String>, tolerance: Double) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                val keys = (expected.keys + actual.keys) - ignoreKeys
                for (k in keys.sorted()) {
                    val e = expected[k]
                    val a = actual[k]
                    if (e == null || e is JsonNull) {
                        if (a != null && a !is JsonNull) fail("$path.$k: expected it to be absent but was $a")
                        continue
                    }
                    if (a == null || a is JsonNull) {
                        fail("$path.$k: expected $e but it was absent")
                        continue
                    }
                    compare("$path.$k", e, a, ignoreKeys, tolerance)
                }
            }
            expected is JsonArray && actual is JsonArray -> {
                if (expected.size != actual.size) fail("$path: expected ${expected.size} items but was ${actual.size}")
                for (i in expected.indices) compare("$path[$i]", expected[i], actual[i], ignoreKeys, tolerance)
            }
            expected is JsonPrimitive && actual is JsonPrimitive -> {
                if (expected.isString || actual.isString) {
                    if (expected.content != actual.content) fail("$path: expected \"${expected.content}\" but was \"${actual.content}\"")
                } else {
                    val en = expected.content.toDoubleOrNull()
                    val an = actual.content.toDoubleOrNull()
                    if (en != null && an != null) {
                        val allowed = tolerance + tolerance * max(abs(en), abs(an))
                        if (abs(en - an) > allowed) fail("$path: expected $en but was $an (difference ${abs(en - an)})")
                    } else if (expected.content != actual.content) {
                        fail("$path: expected ${expected.content} but was ${actual.content}")
                    }
                }
            }
            else -> fail("$path: expected $expected but was $actual")
        }
    }
}

// Golden file shapes. They mirror the Codable structs in Tools/swift-reference/Sources/StrideReference.

@Serializable
data class AnalysisCase(
    val key: String,
    val ageYears: Int,
    val profile: AthleteProfile,
    val base: Activity,
    val track: List<TrackPoint>,
    val result: Activity,
    val movingTime: Double,
    val gradeAdjustedPace: Double? = null,
    val routeSignature: String,
)

@Serializable
data class SegmentsGolden(
    val scenarioKey: String,
    val activity: Activity,
    val segments: List<Segment>,
    val efforts: List<SegmentEffort>,
    val ranked: List<SegmentEffort>,
)

@Serializable data class IntCase(val n: Int, val casual: String, val formal: String)
@Serializable data class DoubleCase(val x: Double, val pace: String? = null, val duration: String, val durationVerbose: String)
@Serializable data class SpecialCase(val label: String, val pace: String? = null, val duration: String, val durationVerbose: String)
@Serializable data class DecimalCase(val x: Double, val decimal: String, val distanceMetric: String, val distanceImperial: String)
@Serializable data class MetricPhrase(val metric: SpokenMetric, val text: String? = null)
@Serializable data class BuilderOut(val ssml: String, val plain: String)

@Serializable
data class CoachCase(
    val snapshot: CoachSnapshot,
    val settings: VoiceSettings,
    val units: UnitSystem,
    val isTimeInterval: Boolean,
    val metrics: List<MetricPhrase>,
    val split: BuilderOut,
    val remark: String? = null,
    val start: BuilderOut,
    val finish: BuilderOut,
    val lapNumber: Int,
    val lap: BuilderOut,
    val halfway: BuilderOut,
    val paceAlert: BuilderOut? = null,
    val paceAlertCurrent: Double,
    val paceAlertTarget: Double,
    val zoneAlert: BuilderOut? = null,
    val zoneAlertZone: Int,
    val zoneAlertTarget: Int,
    val segmentStart: BuilderOut,
    val segmentStartPR: Double? = null,
    val segmentProgress: BuilderOut,
    val segmentProgressDelta: Double,
    val segmentProgressRemaining: Double,
    val segmentFinish: BuilderOut,
    val segmentFinishTime: Double,
    val segmentFinishIsPR: Boolean,
    val segmentFinishDelta: Double? = null,
)

@Serializable
data class SpeechGolden(
    val ints: List<IntCase>,
    val doubles: List<DoubleCase>,
    val specials: List<SpecialCase>,
    val decimals: List<DecimalCase>,
    val coach: List<CoachCase>,
)

@Serializable
data class FmtCase(
    val x: Double,
    val units: UnitSystem,
    val distance: String,
    val distance0: String,
    val distance3: String,
    val elevation: String,
    val duration: String,
    val durationPadded: String,
    val durationCompact: String,
    val paceFromSeconds: String,
    val paceFromSpeed: String,
    val speed: String,
    val int: String,
    val one: String,
    val trimmed: String,
    val percent: String,
    val signed0: String,
    val signed2: String,
)

@Serializable
data class ExportsGolden(
    val key: String,
    val activity: Activity,
    val track: List<TrackPoint>,
    val gpx: String,
    val tcx: String,
    val csvMetric: String,
    val csvImperial: String,
    val fitBase64: String,
)

@Serializable data class RouteExportGolden(val route: SavedRoute, val gpx: String)

@Serializable
data class ImportGolden(
    val file: String,
    val format: String,
    val name: String,
    val sport: SportType? = null,
    val startDate: Instant? = null,
    val isRoute: Boolean,
    val warning: String? = null,
    val points: List<TrackPoint>,
)

@Serializable data class FitnessInput(val startDate: Instant, val trainingLoad: Double)
@Serializable data class FitnessDay(val date: String, val fitness: Double, val fatigue: Double, val form: Double, val load: Double)
@Serializable data class FitnessDescription(val form: Double, val title: String, val detail: String)

@Serializable
data class FitnessGolden(
    val end: Instant,
    val inputs: List<FitnessInput>,
    val points: List<FitnessDay>,
    val descriptions: List<FitnessDescription>,
)

@Serializable
data class DocumentsGolden(val settings: AppSettings, val activities: List<Activity>, val activityIDWithTrack: UUID)

/** Splits, laps and best efforts get random ids from the analysis; they carry no meaning. */
val IGNORED_IDS = setOf("id")
