package com.jashith.stride.core.io

import com.jashith.stride.core.TestData
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FormatsTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readBytes()
    private val fixedNow = { Instant.parse("2030-01-01T00:00:00Z") }

    // GPX

    @Test fun gpxExportThenImportRoundTrips() {
        val a = TestData.activity(1).copy(name = "Tom & Jerry's \"fast\" <run>", sport = SportType.TrailRun)
        val track = (0 until 20).map { i ->
            TrackPoint(i * 5.0, 51.5 + i * 0.0002, -0.12 + i * 0.0001, 20.0 + i * 0.5, i * 15.0, 3.0,
                hr = if (i % 2 == 0) 140.0 + i else null, cad = if (i % 3 == 0) 170.0 else null, pw = if (i % 5 == 0) 250.0 else null)
        }
        val xml = Exporter.gpx(a, track)
        assertTrue(xml.contains("<type>trailRun</type>"))
        assertTrue(xml.contains("Tom &amp; Jerry's &quot;fast&quot; &lt;run&gt;"))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Stride\""))

        val r = GpxImporter(fixedNow).parse(xml.toByteArray())!!
        assertEquals("Tom & Jerry's \"fast\" <run>", r.name)
        assertEquals(a.startDate, r.startDate)
        assertFalse(r.isRoute)
        assertEquals(20, r.points.size)
        for ((o, b) in track.zip(r.points)) {
            assertEquals(o.t, b.t, 0.0)
            assertEquals(o.lat, b.lat, 1e-9)
            assertEquals(o.lon, b.lon, 1e-9)
            assertEquals(o.alt, b.alt, 0.05)
            assertEquals(o.hr, b.hr)
            assertEquals(o.cad, b.cad)
            assertEquals(o.pw, b.pw)
        }
        assertEquals(Coord(51.5, -0.12), Coord(r.points[0].lat, r.points[0].lon))
    }

    @Test fun gpxCoordinatesAreNeverScientificNotation() {
        val a = TestData.activity(1)
        val xml = Exporter.gpx(a, listOf(TrackPoint(0.0, 0.00001, 0.00002, 5.0, 0.0, 0.0)))
        assertTrue(xml, xml.contains("lat=\"0.00001\" lon=\"0.00002\""))
    }

    @Test fun gpxFromAnotherAppImportsWithItsQuirks() {
        val r = GpxImporter(fixedNow).parse(fixture("strava.gpx"))!!
        // Like iOS, the first non-empty <name> anywhere in the file wins, even one inside <metadata>.
        assertEquals("ignored when the track has its own", r.name)
        assertEquals(Instant.parse("2026-03-14T07:30:05Z"), r.startDate)  // the <metadata> time is ignored
        assertEquals(3, r.points.size)                                // the 0,0 fix is dropped
        assertEquals(131.0, r.points[0].hr!!, 0.0)
        assertEquals(82.0, r.points[0].cad!!, 0.0)
        assertEquals(180.0, r.points[0].pw!!, 0.0)
        assertEquals(10.25, r.points[1].t, 1e-9)                      // fractional seconds are understood
        assertNull(r.points[1].hr)
        assertEquals(100.0, r.points[1].d, 1.0)
        assertEquals(100.0 / 10.25, r.points[1].v, 0.1)
        assertEquals(20.0, r.points[2].t, 1e-9)
    }

    @Test fun gpxRoutesAreFlaggedAndWaypointsCountAsPoints() {
        val route = TestData.route()
        val xml = Exporter.gpx(route)
        assertTrue(xml.contains("<rtept lat=\"51.5\" lon=\"-0.12\"><ele>10.0</ele></rtept>"))
        val r = GpxImporter(fixedNow).parse(xml.toByteArray())!!
        assertTrue(r.isRoute)
        assertEquals("Loop", r.name)
        assertEquals(2, r.points.size)
        assertEquals(0.0, r.points[0].t, 0.0)
        assertEquals(1.0, r.points[1].t, 0.0)   // no timestamps, so the point index stands in
        assertEquals(fixedNow(), r.startDate)

        val wpt = """<gpx><wpt lat="51.5" lon="-0.1"><ele>3</ele></wpt><wpt lat="51.6" lon="-0.1"/></gpx>"""
        assertEquals(2, GpxImporter(fixedNow).parse(wpt.toByteArray())!!.points.size)
    }

    @Test fun gpxMalformedOrEmptyInputReturnsNull() {
        assertNull(GpxImporter(fixedNow).parse("<gpx><trk><trkseg><trkpt lat=\"51\" lon=\"1\"></trkpt></trkseg></trk>".toByteArray())) // unclosed
        assertNull(GpxImporter(fixedNow).parse("<gpx></gpx>".toByteArray()))
        assertNull(GpxImporter(fixedNow).parse("not xml at all".toByteArray()))
        assertNull(GpxImporter(fixedNow).parse(ByteArray(0)))
    }

    @Test fun xmlWithADoctypeIsRefusedRatherThanExpanded() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x "boom">]><gpx><trk><trkseg><trkpt lat="51" lon="1"><ele>1</ele></trkpt></trkseg></trk></gpx>"""
        assertNull(GpxImporter(fixedNow).parse(bomb.toByteArray()))
    }

    // TCX

    @Test fun tcxFromAGarminWatchImports() {
        val r = TcxImporter(fixedNow).parse(fixture("garmin.tcx"))!!
        assertEquals(SportType.Run, r.sport)
        assertEquals(Instant.parse("2026-03-14T07:30:05Z"), r.startDate)
        assertEquals(3, r.points.size)
        val p0 = r.points[0]
        assertEquals(120.0, p0.hr!!, 0.0)
        assertEquals(170.0, p0.cad!!, 0.0)       // RunCadence is one foot; Stride stores both
        assertEquals(230.0, p0.pw!!, 0.0)
        assertEquals(3.0, p0.v, 0.0)
        assertEquals(20.5, p0.alt, 0.0)
        assertEquals(30.0, r.points[1].t, 0.0)
        assertEquals(100.0, r.points[1].d, 0.0)
        assertEquals(172.0, r.points[1].cad!!, 0.0)
        // third trackpoint has a time but no position
        assertEquals(60.0, r.points[2].t, 0.0)
        assertEquals(0.0, r.points[2].lat, 0.0)
        assertEquals(90.0, r.points[2].cad!!, 0.0)   // plain Cadence is used as is
        // lap totals are summed across laps (and the Id, a timestamp, is the name)
        assertEquals(270.0, r.totalDistance!!, 0.0)
        assertEquals(90.0, r.totalTime!!, 0.0)
        assertEquals(18.0, r.calories!!, 0.0)
        assertEquals("2026-03-14T07:30:05.000Z", r.name)
    }

    @Test fun tcxExportThenImportRoundTripsForARun() {
        val a = TestData.activity(1)
        val track = (0 until 10).map { i ->
            TrackPoint(i * 5.0, 51.5 + i * 0.0002, -0.12, 20.0 + i, i * 10.0, 2.0, hr = 130.0 + i, cad = 170.0, pw = 200.0)
        }
        val xml = Exporter.tcx(a, track)
        assertTrue(xml.contains("Sport=\"Running\""))
        assertTrue(xml.contains("<ns3:RunCadence>85</ns3:RunCadence>"))
        val r = TcxImporter(fixedNow).parse(xml.toByteArray())!!
        assertEquals(SportType.Run, r.sport)
        assertEquals(10, r.points.size)
        assertEquals(a.startDate, r.startDate)
        assertEquals(170.0, r.points[3].cad!!, 0.0)
        assertEquals(133.0, r.points[3].hr!!, 0.0)
        assertEquals(200.0, r.points[3].pw!!, 0.0)
        assertEquals(30.0, r.points[3].d, 0.05)
    }

    @Test fun tcxRidesWriteCadenceAsAPlainElement() {
        val a = TestData.activity(1).copy(sport = SportType.Ride)
        val xml = Exporter.tcx(a, listOf(TrackPoint(0.0, 51.5, -0.1, 5.0, 0.0, 0.0, cad = 88.0)))
        assertTrue(xml.contains("Sport=\"Biking\""))
        assertTrue(xml.contains("<Cadence>88</Cadence>"))
        assertFalse(xml.contains("RunCadence"))
        assertEquals(88.0, TcxImporter(fixedNow).parse(xml.toByteArray())!!.points[0].cad!!, 0.0)
        assertTrue(Exporter.tcx(a.copy(sport = SportType.Hike), emptyList()).contains("Sport=\"Other\""))
    }

    @Test fun tcxMalformedXmlKeepsThePointsReadSoFar() {
        val full = String(fixture("garmin.tcx"))
        val cut = full.substring(0, full.indexOf("<Time>2026-03-14T07:31:05Z"))
        val r = TcxImporter(fixedNow).parse(cut.toByteArray())!!
        assertEquals(2, r.points.size)
        assertEquals(fixedNow(), r.startDate)   // as on iOS, the start date is not recovered on this path
        assertNull(TcxImporter(fixedNow).parse("<TrainingCenterDatabase>".toByteArray()))
    }

    // Sniffing and ActivityFile

    @Test fun formatIsWorkedOutFromTheContentsNotTheFilename() {
        val fit = FitEncoder.encode(TestData.activity(1), TestData.track(5))
        assertEquals(ActivityFile.Format.Fit, ActivityFile.detect(fit, "ride.txt"))
        assertEquals(ActivityFile.Format.Gpx, ActivityFile.detect(fixture("strava.gpx"), "export"))
        assertEquals(ActivityFile.Format.Tcx, ActivityFile.detect(fixture("garmin.tcx"), "export.xml"))
        assertEquals(ActivityFile.Format.Gpx, ActivityFile.detect("<rte></rte>".toByteArray(), ""))
        // extension is only the last resort
        assertEquals(ActivityFile.Format.Fit, ActivityFile.detect(ByteArray(3), "Run.FIT"))
        assertNull(ActivityFile.detect("hello".toByteArray(), "notes.txt"))
    }

    @Test fun loadReturnsNamesSportsAndWarnings() {
        val gpx = ActivityFile.load(fixture("strava.gpx"), "Lunch.gpx", fixedNow)
        assertEquals("ignored when the track has its own", gpx.name)
        assertEquals(ActivityFile.Format.Gpx, gpx.format)
        // no <metadata> name: the track name is used and XML entities are decoded
        val plain = ActivityFile.load(String(fixture("strava.gpx")).replace("<name>ignored when the track has its own</name>", "").toByteArray(), "Lunch.gpx", fixedNow)
        assertEquals("Lunch Ride & Coffee", plain.name)

        val tcx = ActivityFile.load(fixture("garmin.tcx"), "Morning Run.tcx", fixedNow)
        assertEquals("Morning Run", tcx.name)          // a TCX Id is a timestamp and makes a poor name
        assertEquals(SportType.Run, tcx.sport)

        val bytes = FitEncoder.encode(TestData.activity(1), TestData.track(10)).copyOf()
        val fit = ActivityFile.load(bytes, "2026-03-14-07-30-05.fit", fixedNow)
        assertEquals("2026-03-14-07-30-05", fit.name)
        assertNull(fit.warning)
        bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 0x1).toByte()
        assertNotNull(ActivityFile.load(bytes, "x.fit", fixedNow).warning)
    }

    @Test fun nothingUnrecognisedIsHalfImported() {
        assertThrows(ActivityFile.Failure.Unrecognised::class.java) { ActivityFile.load("hello world".toByteArray(), "x.txt", fixedNow) }
        assertThrows(ActivityFile.Failure.Empty::class.java) { ActivityFile.load("<gpx></gpx>".toByteArray(), "x.gpx", fixedNow) }
        assertThrows(FitFailure::class.java) { ActivityFile.load(ByteArray(40) { 1 }, "x.fit", fixedNow) }
        assertEquals("Imported activity", ActivityFile.defaultName(""))
        assertEquals("a.b", ActivityFile.defaultName("a.b.gpx"))
    }

    @Test fun sportCanBeGuessedFromTheName() {
        assertEquals(SportType.Ride, ActivityFile.sport("Evening Cycling"))
        assertEquals(SportType.Ride, ActivityFile.sport("my bike"))
        assertEquals(SportType.Walk, ActivityFile.sport("dog walk"))
        assertEquals(SportType.Hike, ActivityFile.sport("Hike up the hill"))
        assertEquals(SportType.Run, ActivityFile.sport("Lunch Run"))
        assertNull(ActivityFile.sport("Swim"))
    }

    // CSV

    @Test fun csvMatchesTheIosLayout() {
        val a = TestData.activity(1).copy(
            name = "Easy, steady", distance = 8046.72, movingTime = 2400.4, elapsed = 2500.0, elevationGain = 30.48,
            avgHR = 150.4, maxHR = null, avgPower = null, calories = 512.5, relativeEffort = 37.5,
        )
        val metric = Exporter.csv(listOf(a), UnitSystem.Metric).split("\n")
        assertEquals("Date,Name,Sport,Distance (km),Moving Time (s),Elapsed (s),Elevation (m),Avg HR,Max HR,Avg Power,Calories,Relative Effort", metric[0])
        // calories 512.5 and RE 37.5 are exact ties, which C prints half-to-even: 512 and 38
        assertEquals("2026-03-15T07:30:05Z,Easy  steady,Run,8.047,2400,2500,30,150,--,--,512,38", metric[1])
        val imperial = Exporter.csv(listOf(a), UnitSystem.Imperial).split("\n")
        assertTrue(imperial[0].contains("Distance (mi)"))
        assertTrue(imperial[0].contains("Elevation (ft)"))
        assertTrue(imperial[1].contains(",5.000,"))
        assertTrue(imperial[1].contains(",100,"))
        assertFalse(Exporter.csv(emptyList(), UnitSystem.Metric).contains("\n"))
    }
}
