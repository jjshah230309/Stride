package com.jashith.stride.core.model

import com.jashith.stride.core.json.CoordSerializer
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** A latitude/longitude pair. Serialized as the array `[lat, lon]` to keep files small. */
@Serializable(with = CoordSerializer::class)
data class Coord(val lat: Double, val lon: Double) {
    val isValid: Boolean
        get() = lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180 && !(lat == 0.0 && lon == 0.0)
}

data class BoundingBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {

    val center: Coord get() = Coord((minLat + maxLat) / 2, (minLon + maxLon) / 2)

    fun expanded(byMeters: Double): BoundingBox {
        val dLat = byMeters / 111_320.0
        val dLon = byMeters / (111_320.0 * max(0.01, cos(center.lat * Math.PI / 180)))
        return BoundingBox(minLat - dLat, maxLat + dLat, minLon - dLon, maxLon + dLon)
    }

    fun intersects(other: BoundingBox): Boolean =
        !(other.minLat > maxLat || other.maxLat < minLat || other.minLon > maxLon || other.maxLon < minLon)

    fun contains(c: Coord): Boolean =
        c.lat >= minLat && c.lat <= maxLat && c.lon >= minLon && c.lon <= maxLon

    companion object {
        /** Null when there is no valid coordinate, like the failable Swift initializer. */
        fun of(coords: List<Coord>): BoundingBox? {
            val valid = coords.filter { it.isValid }
            val first = valid.firstOrNull() ?: return null
            var minLat = first.lat
            var maxLat = first.lat
            var minLon = first.lon
            var maxLon = first.lon
            for (c in valid) {
                minLat = minOf(minLat, c.lat); maxLat = maxOf(maxLat, c.lat)
                minLon = minOf(minLon, c.lon); maxLon = maxOf(maxLon, c.lon)
            }
            return BoundingBox(minLat, maxLat, minLon, maxLon)
        }
    }
}
