package com.jashith.stride.core.io

import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.max

/**
 * Reads Garmin's Training Center XML. Unlike GPX, heart rate and cadence are first-class here,
 * so a TCX from a watch usually carries a complete trace.
 */
class TcxImporter(private val now: () -> Instant = { Instant.now() }) : DefaultHandler() {

    data class Result(
        val name: String,
        val sport: SportType?,
        val startDate: Instant,
        val points: List<TrackPoint>,
        val totalDistance: Double?,
        val totalTime: Double?,
        val calories: Double?,
    )

    private var name = ""
    private var sport: SportType? = null
    private var totalDistance: Double? = null
    private var totalTime: Double? = null
    private var calories: Double? = null
    private val points = ArrayList<TrackPoint>()
    private val text = StringBuilder()
    private val path = ArrayList<String>()

    private var pendingLat: Double? = null
    private var pendingLon: Double? = null
    private var pendingAltitude: Double? = null
    private var pendingDistance: Double? = null
    private var pendingTime: Instant? = null
    private var pendingHeartRate: Double? = null
    private var pendingCadence: Double? = null
    private var pendingRunCadence: Double? = null
    private var pendingPower: Double? = null
    private var pendingSpeed: Double? = null

    private var firstDate: Instant? = null
    private var lastCoord: Coord? = null
    private var lastElapsed = 0.0
    private var measured = 0.0

    /**
     * Mirrors iOS: malformed XML still returns whatever points were read before the break (with the
     * start date left at "now"); a file with no points returns null.
     */
    fun parse(data: ByteArray): Result? {
        name = ""; sport = null; totalDistance = null; totalTime = null; calories = null
        points.clear(); path.clear(); text.setLength(0)
        firstDate = null; lastCoord = null; lastElapsed = 0.0; measured = 0.0
        val created = now()
        var ok = true
        try {
            parseXml(data, this)
        } catch (_: Exception) {
            ok = false
        }
        if (points.isEmpty()) return null
        val start = if (ok) (firstDate ?: now()) else created
        return Result(name, sport, start, points.toList(), totalDistance, totalTime, calories)
    }

    /** Namespaced names arrive as "ns3:Watts"; only the local part matters. */
    private fun localName(n: String): String = n.substring(n.lastIndexOf(':') + 1)

    private fun date(s: String): Instant? = GpxImporter.parseTime(s)

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        val n = localName(qName)
        path.add(n)
        text.setLength(0)
        when (n) {
            "Activity" -> attributes.getValue("Sport")?.let {
                when (it.lowercase(Locale.ROOT)) {
                    "running" -> sport = SportType.Run
                    "biking" -> sport = SportType.Ride
                    "walking" -> sport = SportType.Walk
                    "hiking" -> sport = SportType.Hike
                }
            }
            "Trackpoint" -> {
                pendingLat = null; pendingLon = null; pendingAltitude = null
                pendingDistance = null; pendingTime = null
                pendingHeartRate = null; pendingCadence = null
                pendingRunCadence = null; pendingPower = null; pendingSpeed = null
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val n = localName(qName)
        val value = text.toString().trim()
        val insideTrackpoint = path.contains("Trackpoint")

        when (n) {
            "Id", "Notes" -> if (name.isEmpty() && value.isNotEmpty()) name = value
            "Time" -> if (insideTrackpoint) pendingTime = date(value)
            "LatitudeDegrees" -> pendingLat = parseDouble(value)
            "LongitudeDegrees" -> pendingLon = parseDouble(value)
            "AltitudeMeters" -> if (insideTrackpoint) pendingAltitude = parseDouble(value)
            "DistanceMeters" ->
                if (insideTrackpoint) {
                    pendingDistance = parseDouble(value)
                } else {
                    parseDouble(value)?.let { totalDistance = (totalDistance ?: 0.0) + it }
                }
            // Only the Value inside a Trackpoint's HeartRateBpm is a live reading.
            "Value" -> if (insideTrackpoint && path.contains("HeartRateBpm")) pendingHeartRate = parseDouble(value)
            "Cadence" -> if (insideTrackpoint) pendingCadence = parseDouble(value)
            // Garmin writes one foot; Stride stores both.
            "RunCadence" -> parseDouble(value)?.let { pendingRunCadence = it * 2 }
            "Watts" -> pendingPower = parseDouble(value)
            "Speed" -> if (insideTrackpoint) pendingSpeed = parseDouble(value)
            "Calories" -> parseDouble(value)?.let { calories = (calories ?: 0.0) + it }
            "TotalTimeSeconds" -> parseDouble(value)?.let { totalTime = (totalTime ?: 0.0) + it }
            "Trackpoint" -> appendPoint()
        }

        text.setLength(0)
        if (path.isNotEmpty() && path.last() == n) path.removeAt(path.size - 1)
    }

    private fun appendPoint() {
        // A trackpoint with neither a position nor a time carries nothing usable.
        var coordinate: Coord? = null
        val lat = pendingLat
        val lon = pendingLon
        if (lat != null && lon != null) {
            val candidate = Coord(lat, lon)
            if (candidate.isValid) coordinate = candidate
        }
        if (coordinate == null && pendingTime == null) return

        if (firstDate == null) firstDate = pendingTime ?: now()
        var elapsed = points.size.toDouble()
        val stamp = pendingTime
        val start = firstDate
        if (stamp != null && start != null) elapsed = max(0.0, Duration.between(start, stamp).toNanos() / 1e9)

        val previous = lastCoord
        if (coordinate != null && previous != null) measured += Geo.fastDistance(previous, coordinate)
        val distance = pendingDistance ?: measured

        var speed = pendingSpeed ?: 0.0
        if (speed == 0.0 && coordinate != null && previous != null && elapsed > lastElapsed) {
            speed = Geo.fastDistance(previous, coordinate) / (elapsed - lastElapsed)
        }

        points.add(
            TrackPoint(
                t = elapsed, lat = coordinate?.lat ?: 0.0, lon = coordinate?.lon ?: 0.0,
                alt = pendingAltitude ?: 0.0, d = distance, v = speed,
                hr = pendingHeartRate, cad = pendingRunCadence ?: pendingCadence, pw = pendingPower,
                moving = true, acc = 5.0,
            ),
        )
        if (coordinate != null) lastCoord = coordinate
        lastElapsed = elapsed
    }
}
