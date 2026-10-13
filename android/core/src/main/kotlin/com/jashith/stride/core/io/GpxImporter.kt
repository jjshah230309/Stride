package com.jashith.stride.core.io

import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.json.InstantSerializer
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/** A small GPX reader, enough to pull in tracks exported from Strava, Garmin or Wahoo. */
class GpxImporter(private val now: () -> Instant = { Instant.now() }) : DefaultHandler() {

    data class Result(
        val name: String,
        val points: List<TrackPoint>,
        val startDate: Instant,
        val isRoute: Boolean,
    )

    private var name = ""
    private var isRoute = false
    private val points = ArrayList<TrackPoint>()
    private val text = StringBuilder()
    private var pendingLat: Double? = null
    private var pendingLon: Double? = null
    private var pendingEle: Double? = null
    private var pendingTime: Instant? = null
    private var pendingHR: Double? = null
    private var pendingCad: Double? = null
    private var pendingPower: Double? = null
    private var firstDate: Instant? = null
    private var lastCoord: Coord? = null
    private var cumulative = 0.0
    private var inMetadata = false

    /** Null when the XML is malformed or holds no usable points. */
    fun parse(data: ByteArray): Result? {
        name = ""; isRoute = false; points.clear(); text.setLength(0)
        cumulative = 0.0; lastCoord = null; firstDate = null; inMetadata = false
        try {
            parseXml(data, this)
        } catch (_: Exception) {
            return null
        }
        if (points.isEmpty()) return null
        return Result(name, points.toList(), firstDate ?: now(), isRoute)
    }

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        text.setLength(0)
        if (qName == "metadata") inMetadata = true
        if (qName == "trkpt" || qName == "rtept" || qName == "wpt") {
            pendingLat = parseDouble(attributes.getValue("lat") ?: "")
            pendingLon = parseDouble(attributes.getValue("lon") ?: "")
            pendingEle = null; pendingTime = null; pendingHR = null; pendingCad = null; pendingPower = null
            if (qName == "rtept") isRoute = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val value = text.toString().trim()
        when (qName) {
            "metadata" -> inMetadata = false
            "name" -> if (name.isEmpty() && value.isNotEmpty()) name = value
            "ele" -> pendingEle = parseDouble(value)
            "time" -> if (!inMetadata) pendingTime = parseTime(value)
            "hr", "gpxtpx:hr", "ns3:HeartRateBpm" -> pendingHR = parseDouble(value)
            "cad", "gpxtpx:cad" -> pendingCad = parseDouble(value)
            "power", "ns3:Watts" -> pendingPower = parseDouble(value)
            "trkpt", "rtept", "wpt" -> appendPoint()
        }
        text.setLength(0)
    }

    private fun appendPoint() {
        val lat = pendingLat ?: return
        val lon = pendingLon ?: return
        val coord = Coord(lat, lon)
        if (!coord.isValid) return
        if (firstDate == null) firstDate = pendingTime ?: now()
        var elapsed = points.size.toDouble()
        val stamp = pendingTime
        val start = firstDate
        if (stamp != null && start != null) elapsed = Duration.between(start, stamp).toNanos() / 1e9
        var speed = 0.0
        val previous = lastCoord
        if (previous != null) {
            val step = Geo.fastDistance(previous, coord)
            cumulative += step
            val last = points.lastOrNull()
            if (last != null && elapsed > last.t) speed = step / (elapsed - last.t)
        }
        lastCoord = coord
        points.add(
            TrackPoint(
                t = max(0.0, elapsed), lat = lat, lon = lon, alt = pendingEle ?: 0.0, d = cumulative, v = speed,
                hr = pendingHR, cad = pendingCad, pw = pendingPower, moving = true, acc = 5.0,
            ),
        )
    }

    companion object {
        internal fun parseTime(text: String): Instant? =
            try {
                InstantSerializer.parseIso(text)
            } catch (_: Exception) {
                null
            }
    }
}
