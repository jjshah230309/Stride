package com.jashith.stride.core.io

import com.jashith.stride.core.json.InstantSerializer
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.SavedRoute
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.util.fixed
import java.time.Instant

/** GPX, TCX, FIT and CSV writers, so your data is never locked in this app. */
object Exporter {

    private fun iso(instant: Instant): String = InstantSerializer.formatIso(instant)

    private fun plus(start: Instant, seconds: Double): Instant = start.plusNanos((seconds * 1e9).toLong())

    fun gpx(activity: Activity, points: List<TrackPoint>): String {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<gpx version=\"1.1\" creator=\"Stride\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        xml.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        xml.append("  <metadata>\n")
        xml.append("    <name>${escapeXml(activity.name)}</name>\n")
        xml.append("    <time>${iso(activity.startDate)}</time>\n")
        xml.append("  </metadata>\n")
        xml.append("  <trk>\n")
        xml.append("    <name>${escapeXml(activity.name)}</name>\n")
        xml.append("    <type>${activity.sport.raw}</type>\n")
        xml.append("    <trkseg>\n")
        for (p in points) {
            if (!p.coord.isValid) continue
            val time = iso(plus(activity.startDate, p.t))
            xml.append("      <trkpt lat=\"${plainDecimal(p.lat)}\" lon=\"${plainDecimal(p.lon)}\">\n")
            xml.append("        <ele>${fixed(p.alt, 1)}</ele>\n")
            xml.append("        <time>$time</time>\n")
            if (p.hr != null || p.cad != null || p.pw != null) {
                xml.append("        <extensions>\n")
                p.pw?.let { xml.append("          <power>${it.toLong()}</power>\n") }
                if (p.hr != null || p.cad != null) {
                    xml.append("          <gpxtpx:TrackPointExtension>\n")
                    p.hr?.let { xml.append("            <gpxtpx:hr>${it.toLong()}</gpxtpx:hr>\n") }
                    p.cad?.let { xml.append("            <gpxtpx:cad>${it.toLong()}</gpxtpx:cad>\n") }
                    xml.append("          </gpxtpx:TrackPointExtension>\n")
                }
                xml.append("        </extensions>\n")
            }
            xml.append("      </trkpt>\n")
        }
        xml.append("    </trkseg>\n")
        xml.append("  </trk>\n")
        xml.append("</gpx>")
        return xml.toString()
    }

    fun gpx(route: SavedRoute): String {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<gpx version=\"1.1\" creator=\"Stride\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        xml.append("  <rte>\n")
        xml.append("    <name>${escapeXml(route.name)}</name>\n")
        route.path.forEachIndexed { i, c ->
            val ele = if (i < route.elevations.size) route.elevations[i] else 0.0
            xml.append("    <rtept lat=\"${plainDecimal(c.lat)}\" lon=\"${plainDecimal(c.lon)}\"><ele>${fixed(ele, 1)}</ele></rtept>\n")
        }
        xml.append("  </rte>\n")
        xml.append("</gpx>")
        return xml.toString()
    }

    fun tcx(activity: Activity, points: List<TrackPoint>): String {
        val sport = if (activity.sport.isRide) "Biking" else if (activity.sport.isRun) "Running" else "Other"
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" ")
        xml.append("xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">\n")
        xml.append("  <Activities>\n")
        xml.append("    <Activity Sport=\"$sport\">\n")
        xml.append("      <Id>${iso(activity.startDate)}</Id>\n")
        xml.append("      <Lap StartTime=\"${iso(activity.startDate)}\">\n")
        xml.append("        <TotalTimeSeconds>${fixed(activity.movingTime, 1)}</TotalTimeSeconds>\n")
        xml.append("        <DistanceMeters>${fixed(activity.distance, 1)}</DistanceMeters>\n")
        xml.append("        <Calories>${activity.calories.toLong()}</Calories>\n")
        xml.append("        <Intensity>Active</Intensity>\n")
        xml.append("        <TriggerMethod>Manual</TriggerMethod>\n")
        xml.append("        <Track>\n")
        for (p in points) {
            if (!p.coord.isValid) continue
            val time = iso(plus(activity.startDate, p.t))
            xml.append("          <Trackpoint>\n")
            xml.append("            <Time>$time</Time>\n")
            xml.append("            <Position><LatitudeDegrees>${plainDecimal(p.lat)}</LatitudeDegrees><LongitudeDegrees>${plainDecimal(p.lon)}</LongitudeDegrees></Position>\n")
            xml.append("            <AltitudeMeters>${fixed(p.alt, 1)}</AltitudeMeters>\n")
            xml.append("            <DistanceMeters>${fixed(p.d, 1)}</DistanceMeters>\n")
            p.hr?.let { xml.append("            <HeartRateBpm><Value>${it.toLong()}</Value></HeartRateBpm>\n") }
            if (p.cad != null && activity.sport.isRide) {
                xml.append("            <Cadence>${p.cad.toLong()}</Cadence>\n")
            }
            if (p.pw != null || (p.cad != null && !activity.sport.isRide)) {
                xml.append("            <Extensions><ns3:TPX>\n")
                p.pw?.let { xml.append("              <ns3:Watts>${it.toLong()}</ns3:Watts>\n") }
                if (p.cad != null && !activity.sport.isRide) {
                    xml.append("              <ns3:RunCadence>${(p.cad / 2).toLong()}</ns3:RunCadence>\n")
                }
                xml.append("            </ns3:TPX></Extensions>\n")
            }
            xml.append("          </Trackpoint>\n")
        }
        xml.append("        </Track>\n")
        xml.append("      </Lap>\n")
        xml.append("    </Activity>\n")
        xml.append("  </Activities>\n")
        xml.append("</TrainingCenterDatabase>")
        return xml.toString()
    }

    fun csv(activities: List<Activity>, units: UnitSystem): String {
        val rows = ArrayList<String>()
        rows.add(
            "Date,Name,Sport,Distance (${units.distanceUnit}),Moving Time (s),Elapsed (s)," +
                "Elevation (${units.elevationUnit}),Avg HR,Max HR,Avg Power,Calories,Relative Effort",
        )
        for (a in activities) {
            val name = a.name.replace(",", " ")
            rows.add(
                listOf(
                    iso(a.startDate),
                    name,
                    a.sport.displayName,
                    fixed(a.distance / units.metersPerUnit, 3),
                    fixed(a.movingTime, 0),
                    fixed(a.elapsed, 0),
                    fixed(a.elevationGain / units.metersPerElevationUnit, 0),
                    Fmt.int(a.avgHR),
                    Fmt.int(a.maxHR),
                    Fmt.int(a.avgPower),
                    fixed(a.calories, 0),
                    fixed(a.relativeEffort, 0),
                ).joinToString(","),
            )
        }
        return rows.joinToString("\n")
    }

    fun fit(activity: Activity, points: List<TrackPoint>): ByteArray = FitEncoder.encode(activity, points)
}
