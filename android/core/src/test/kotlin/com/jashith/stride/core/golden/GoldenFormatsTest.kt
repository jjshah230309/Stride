package com.jashith.stride.core.golden

import com.jashith.stride.core.analysis.FitnessCurve
import com.jashith.stride.core.io.ActivityFile
import com.jashith.stride.core.io.Exporter
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.model.UnitSystem
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** Exports must be byte-for-byte what the iOS app writes; imports must read files the way it does. */
class GoldenFormatsTest {
    private val exports = Golden.readList("exports.json", ExportsGolden.serializer())
    private val documents = Golden.read("ios-documents.json", DocumentsGolden.serializer())

    @Test fun gpxTcxAndFitAreByteIdenticalToIos() {
        assertEquals(listOf("run", "ride", "trail"), exports.map { it.key })
        for (e in exports) {
            assertEquals("${e.key} gpx", e.gpx, Exporter.gpx(e.activity, e.track))
            assertEquals("${e.key} tcx", e.tcx, Exporter.tcx(e.activity, e.track))
            assertArrayEquals("${e.key} fit", Base64.getDecoder().decode(e.fitBase64), Exporter.fit(e.activity, e.track))
        }
    }

    @Test fun csvIsIdenticalInBothUnitSystems() {
        val first = exports.first()
        assertEquals(documents.activities.size + 1, first.csvMetric.split("\n").size)
        assertEquals(first.csvMetric, Exporter.csv(documents.activities, UnitSystem.Metric))
        assertEquals(first.csvImperial, Exporter.csv(documents.activities, UnitSystem.Imperial))
    }

    @Test fun routeGpxIsIdentical() {
        val g = Golden.read("route-export.json", RouteExportGolden.serializer())
        assertEquals(g.gpx, Exporter.gpx(g.route))
    }

    @Test fun theExportScenariosExerciseTheOptionalFields() {
        val run = exports.first { it.key == "run" }
        assertTrue(run.gpx.contains("<gpxtpx:hr>") && run.gpx.contains("<gpxtpx:cad>") && !run.gpx.contains("<power>"))
        val ride = exports.first { it.key == "ride" }
        assertTrue(ride.gpx.contains("<power>") && ride.tcx.contains("<Cadence>") && ride.tcx.contains("<ns3:Watts>"))
        val trail = exports.first { it.key == "trail" }
        assertTrue(trail.track.any { it.lat == 0.0 })     // GPS dropouts are skipped on export
        assertTrue(trail.gpx.split("<trkpt").size - 1 < trail.track.size)
    }

    private fun bytesFor(file: String): ByteArray {
        val key = file.substringBefore("-export")
        val ext = file.substringAfterLast('.')
        return when {
            file == "renamed.txt" -> Golden.fixture("strava.gpx")
            file == "no-extension" -> Base64.getDecoder().decode(exports[0].fitBase64)
            file.contains("-export.") -> {
                val e = exports.first { it.key == key }
                when (ext) {
                    "gpx" -> e.gpx.toByteArray()
                    "tcx" -> e.tcx.toByteArray()
                    else -> Base64.getDecoder().decode(e.fitBase64)
                }
            }
            else -> Golden.fixture(file)
        }
    }

    @Test fun importsReadEveryFileTheWayIosDoes() {
        val imports = Golden.readList("imports.json", ImportGolden.serializer())
        assertEquals(14, imports.size)
        for (g in imports) {
            val tag = g.file
            val actual = ActivityFile.load(bytesFor(g.file), g.file)
            assertEquals("$tag format", g.format, actual.format.name.lowercase())
            assertEquals("$tag name", g.name, actual.name)
            assertEquals("$tag sport", g.sport, actual.sport)
            assertEquals("$tag isRoute", g.isRoute, actual.isRoute)
            assertEquals("$tag warning", g.warning, actual.warning)
            if (g.startDate != null) assertEquals("$tag start", g.startDate, actual.startDate)
            JsonCompareLists.points(tag, g.points, actual.points)
        }
        assertTrue(imports.any { it.format == "fit" } && imports.any { it.format == "tcx" } && imports.any { it.format == "gpx" })
        assertTrue(imports.any { it.sport == SportType.Ride })
    }

    @Test fun fitnessAndFreshnessMatchIos() {
        val g = Golden.read("fitness.json", FitnessGolden.serializer())
        val activities = g.inputs.map { Activity(name = "x", sport = SportType.Run, startDate = it.startDate, trainingLoad = it.trainingLoad) }
        val curve = FitnessCurve.build(activities, end = g.end, zone = ZoneId.of("UTC"))
        assertEquals(g.points.size, curve.size)
        assertEquals(401, curve.size)
        for ((expected, actual) in g.points.zip(curve)) {
            assertEquals(expected.date, actual.date.toString())
            assertEquals(expected.date, expected.fitness, actual.fitness, 1e-9)
            assertEquals(expected.date, expected.fatigue, actual.fatigue, 1e-9)
            assertEquals(expected.date, expected.form, actual.form, 1e-9)
            assertEquals(expected.date, expected.load, actual.load, 1e-9)
        }
        assertTrue(curve.last().fitness > 5)
        for (d in g.descriptions) {
            val (title, detail) = FitnessCurve.formDescription(d.form)
            assertEquals("form ${d.form}", d.title, title)
            assertEquals("form ${d.form}", d.detail, detail)
        }
        assertNull(null)
    }
}

internal object JsonCompareLists {
    fun points(tag: String, expected: List<TrackPoint>, actual: List<TrackPoint>) {
        JsonCompare.assertSame(ListSerializer(TrackPoint.serializer()), expected, actual, tag)
    }
}
