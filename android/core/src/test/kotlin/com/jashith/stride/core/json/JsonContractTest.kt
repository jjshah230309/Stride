package com.jashith.stride.core.json

import com.jashith.stride.core.TestData
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.GearKind
import com.jashith.stride.core.model.SpokenMetric
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.speech.SpeechStyle
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * These tests pin the wire format. The iOS app decodes with Swift's synthesized Codable, which is
 * strict about keys, dates and types, so anything written here must satisfy it.
 */
class JsonContractTest {

    private fun resource(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun encodeActivity(a: Activity): JsonObject =
        Json.parseToJsonElement(StrideJson.encodeToString(Activity.serializer(), a)).jsonObject

    @Test fun uuidsAreWrittenUpperCaseAndReadInAnyCase() {
        val id = UUID.fromString("c4f2a9d0-5e6b-4b7a-8d2e-1a2b3c4d5e6f")
        val a = TestData.activity(1).copy(id = id)
        assertEquals("C4F2A9D0-5E6B-4B7A-8D2E-1A2B3C4D5E6F", encodeActivity(a)["id"]!!.jsonPrimitive.content)
        val lower = StrideJson.encodeToString(Activity.serializer(), a)
            .replace("C4F2A9D0-5E6B-4B7A-8D2E-1A2B3C4D5E6F", "c4f2a9d0-5e6b-4b7a-8d2e-1a2b3c4d5e6f")
        assertEquals(id, StrideJson.decodeFromString(Activity.serializer(), lower).id)
    }

    @Test fun datesAreWholeSecondUtcWithZ() {
        val withFraction = Instant.parse("2026-03-14T07:30:05.987654Z")
        val json = encodeActivity(TestData.activity(1).copy(startDate = withFraction))
        assertEquals("2026-03-14T07:30:05Z", json["startDate"]!!.jsonPrimitive.content)
        // exactly on a minute still prints seconds, which Swift requires
        val onMinute = encodeActivity(TestData.activity(1).copy(startDate = Instant.parse("2026-03-14T07:30:00Z")))
        assertEquals("2026-03-14T07:30:00Z", onMinute["startDate"]!!.jsonPrimitive.content)
    }

    @Test fun datesAcceptFractionsAndOffsetsOnTheWayIn() {
        for (text in listOf("2026-03-14T07:30:05.123Z", "2026-03-14T08:30:05+01:00", "2026-03-14T07:30:05Z")) {
            val json = """{"x":"$text"}"""
            val parsed = InstantSerializer.parseIso(Json.parseToJsonElement(json).jsonObject["x"]!!.jsonPrimitive.content)
            assertEquals(1773473405L, parsed.epochSecond)
        }
    }

    @Test fun coordinatesAreArrays() {
        val json = encodeActivity(TestData.activity(1))
        val first = json["previewPath"]!!.jsonArray[0]
        assertTrue(first is JsonArray)
        assertEquals(2, (first as JsonArray).size)
        assertEquals("51.5", first[0].jsonPrimitive.content)
        assertTrue(json["startCoord"] is JsonArray)
        assertEquals(Coord(1.5, 2.5), StrideJson.decodeFromString(Coord.serializer(), "[1.5,2.5]"))
    }

    @Test fun trackPointsUseShortKeysAndOmitNullOptionals() {
        val p = TrackPoint(t = 1.0, lat = 51.5, lon = -0.1, alt = 20.0, d = 3.0, v = 3.0)
        val obj = Json.parseToJsonElement(StrideJson.encodeToString(TrackPoint.serializer(), p)).jsonObject
        assertEquals(setOf("t", "lat", "lon", "alt", "d", "v", "moving", "acc"), obj.keys)
        assertTrue(obj["moving"]!!.jsonPrimitive.boolean)
        assertEquals("5.0", obj["acc"]!!.jsonPrimitive.content)
        val withHr = Json.parseToJsonElement(StrideJson.encodeToString(TrackPoint.serializer(), p.copy(hr = 150.0, cad = 80.0, pw = 200.0))).jsonObject
        assertEquals(setOf("t", "lat", "lon", "alt", "d", "v", "hr", "cad", "pw", "moving", "acc"), withHr.keys)
    }

    @Test fun everyKeySwiftRequiresIsWrittenEvenWhenItHasADefault() {
        val required = setOf(
            "id", "name", "sport", "startDate", "elapsed", "movingTime", "distance", "elevationGain", "elevationLoss",
            "maxElevation", "minElevation", "calories", "relativeEffort", "trainingLoad", "maxSpeed",
            "hrZoneTimes", "powerZoneTimes", "paceZoneTimes", "splitsKm", "splitsMi", "laps", "bestEfforts",
            "powerCurve", "segmentEffortIDs", "notes", "isCommute", "isPrivate", "isManual", "photos",
            "routeSignature", "hasGPS", "previewPath", "locationName",
        )
        val keys = encodeActivity(Activity(name = "x", sport = SportType.Run, startDate = TestData.started)).keys
        assertTrue("missing: ${required - keys}", keys.containsAll(required))
        // computed properties must never leak into the file
        for (computed in listOf("endDate", "avgSpeed", "avgPaceSecondsPerKm", "boundingBox")) {
            assertFalse("$computed leaked", keys.contains(computed))
        }
        // nil optionals are omitted, like Swift
        for (optional in listOf("avgHR", "maxHR", "avgCadence", "normalizedPower", "perceivedExertion", "gearID", "startCoord")) {
            assertFalse("$optional should be omitted when nil", keys.contains(optional))
        }
    }

    @Test fun swiftIntFieldsAreWrittenWithoutADecimalPoint() {
        val json = encodeActivity(TestData.activity(1).copy(perceivedExertion = 6))
        assertEquals("6", json["perceivedExertion"]!!.jsonPrimitive.content)
        val split = json["splitsKm"]!!.jsonArray[0].jsonObject
        assertEquals("1", split["index"]!!.jsonPrimitive.content)
        val effort = Json.parseToJsonElement(StrideJson.encodeToString(com.jashith.stride.core.model.SegmentEffort.serializer(), TestData.effort())).jsonObject
        for (key in listOf("startIndex", "endIndex", "rank")) {
            assertFalse("$key must be an integer literal", effort[key]!!.jsonPrimitive.content.contains('.'))
        }
        val settings = Json.parseToJsonElement(StrideJson.encodeToString(AppSettings.serializer(), AppSettings())).jsonObject
        val display = settings["display"]!!.jsonObject
        assertEquals("3", display["countdownSeconds"]!!.jsonPrimitive.content)
        assertEquals("1995", settings["profile"]!!.jsonObject["birthYear"]!!.jsonPrimitive.content)
        assertEquals("7", settings["backup"]!!.jsonObject["intervalDays"]!!.jsonPrimitive.content)
        assertEquals("0", settings["voice"]!!.jsonObject["targetHeartRateZone"]!!.jsonPrimitive.content)
    }

    @Test fun enumsUseTheSwiftRawValues() {
        val sports = SportType.entries.map { StrideJson.encodeToString(SportType.serializer(), it).trim('"') }
        assertEquals(
            listOf("run", "trailRun", "treadmill", "walk", "hike", "ride", "gravelRide", "mountainBike", "eBike", "indoorRide"),
            sports,
        )
        val metrics = SpokenMetric.entries.map { StrideJson.encodeToString(SpokenMetric.serializer(), it).trim('"') }
        assertEquals(
            listOf(
                "splitNumber", "splitPace", "splitTime", "totalDistance", "totalTime", "averagePace", "currentPace",
                "averageSpeed", "currentSpeed", "heartRate", "averageHeartRate", "cadence", "power", "averagePower",
                "elevationGain", "calories", "gradeAdjustedPace",
            ),
            metrics,
        )
        assertEquals("\"shoes\"", StrideJson.encodeToString(GearKind.serializer(), GearKind.Shoes))
        assertEquals("\"imperial\"", StrideJson.encodeToString(UnitSystem.serializer(), UnitSystem.Imperial))
        assertEquals("\"detailed\"", StrideJson.encodeToString(SpeechStyle.serializer(), SpeechStyle.Detailed))
    }

    @Test fun anIosWrittenActivityDecodes() {
        val a = StrideJson.decodeFromString(Activity.serializer(), resource("ios-activity.json"))
        assertEquals(UUID.fromString("c4f2a9d0-5e6b-4b7a-8d2e-1a2b3c4d5e6f"), a.id)
        assertEquals(SportType.TrailRun, a.sport)
        assertEquals(Instant.parse("2026-03-14T07:30:05Z"), a.startDate)
        assertEquals(6, a.perceivedExertion)
        assertEquals(Coord(51.5, -0.12), a.startCoord)
        assertEquals(2, a.previewPath.size)
        assertEquals(1, a.splitsKm.size)
        assertEquals(297.5, a.splitsKm[0].gapSeconds!!, 0.0)
        assertNull(a.splitsKm[0].avgPower)
        assertEquals(380.5, a.powerCurve[1].watts, 0.0)
        assertEquals(UUID.fromString("d0c0b0a0-9080-4070-a060-504030201000"), a.gearID)
        assertEquals("felt good", a.notes)
        assertEquals(1, a.segmentEffortIDs.size)
    }

    @Test fun anActivityRoundTripsExactly() {
        val a = TestData.activity(3).copy(perceivedExertion = 7, avgPower = 210.0, notes = "tempo \"quoted\" é")
        val text = StrideJson.encodeToString(Activity.serializer(), a)
        assertEquals(a, StrideJson.decodeFromString(Activity.serializer(), text))
    }

    @Test fun settingsRoundTripAndDefaultsMatchIos() {
        val s = AppSettings()
        assertEquals(s, StrideJson.decodeFromString(AppSettings.serializer(), StrideJson.encodeToString(AppSettings.serializer(), s)))
        assertEquals(1.0, s.voice.distanceInterval, 0.0)
        assertEquals(300.0, s.voice.timeInterval, 0.0)
        assertEquals(0.5, s.voice.rate, 0.0)
        assertEquals(SpeechStyle.Natural, s.voice.style)
        assertEquals(listOf(SpokenMetric.SplitNumber, SpokenMetric.SplitTime, SpokenMetric.SplitPace, SpokenMetric.AveragePace), s.voice.runMetrics)
        assertEquals(30.0, s.display.gpsAccuracyFilter, 0.0)
        assertEquals("standard", s.display.mapStyle)
        assertEquals(listOf(0.60, 0.70, 0.80, 0.90, 1.20), s.profile.hrZones.bounds)
        assertEquals(200.0, s.profile.powerZones.ftp, 0.0)
        assertEquals(300.0, s.profile.paceZones.thresholdPace, 0.0)
        assertEquals(5, s.backup.keepCount)
    }

    @Test fun aDamagedSettingsSectionResetsOnlyThatSection() {
        val s = StrideJson.decodeFromString(AppSettings.serializer(), resource("ios-settings-damaged-voice.json"))
        assertEquals("Jash", s.profile.name)
        assertEquals(240.0, s.profile.powerZones.ftp, 0.0)
        assertEquals(UnitSystem.Imperial, s.display.units)
        assertEquals("hybrid", s.display.mapStyle)
        assertEquals(SportType.Ride, s.display.defaultSport)
        assertEquals(true, s.display.hasCompletedOnboarding)
        assertEquals(14, s.backup.intervalDays)
        assertEquals(Instant.parse("2026-03-01T09:00:00Z"), s.backup.lastRun)
        // the voice section was garbage, so it falls back to defaults
        assertEquals(com.jashith.stride.core.model.VoiceSettings(), s.voice)
    }

    @Test fun missingSectionsAndEmptyObjectsFallBackToDefaults() {
        assertEquals(AppSettings(), StrideJson.decodeFromString(AppSettings.serializer(), "{}"))
        val onlyProfile = StrideJson.decodeFromString(AppSettings.serializer(), """{"profile":{"name":"A"}}""")
        assertEquals("A", onlyProfile.profile.name)
        assertEquals(AppSettings().display, onlyProfile.display)
    }

    @Test fun unknownKeysAreIgnored() {
        val text = """{"t":1,"lat":2,"lon":3,"alt":4,"d":5,"v":6,"moving":true,"acc":1,"futureField":"x"}"""
        assertEquals(1.0, StrideJson.decodeFromString(TrackPoint.serializer(), text).t, 0.0)
    }

    @Test fun anUnknownSportFailsLikeSwiftDoes() {
        try {
            StrideJson.decodeFromString(ListSerializer(SportType.serializer()), """["run","surfing"]""")
            fail("expected a failure")
        } catch (_: Exception) {
            // expected: iOS would refuse the whole file too, so Android must never write unknown values
        }
    }

    @Test fun nonFiniteNumbersRefuseToEncodeJustLikeSwift() {
        try {
            StrideJson.encodeToString(TrackPoint.serializer(), TrackPoint(0.0, 1.0, 1.0, Double.NaN, 0.0, 0.0))
            fail("expected a failure")
        } catch (_: Exception) {
            // expected
        }
    }
}
