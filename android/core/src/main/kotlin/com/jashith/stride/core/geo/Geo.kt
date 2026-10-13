package com.jashith.stride.core.geo

import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.util.swiftRounded
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_RADIUS: Double = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distance(a: Coord, b: Coord): Double {
        val lat1 = a.lat * Math.PI / 180
        val lat2 = b.lat * Math.PI / 180
        val dLat = (b.lat - a.lat) * Math.PI / 180
        val dLon = (b.lon - a.lon) * Math.PI / 180
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(max(0.0, h))))
    }

    /** Fast planar approximation. Accurate to well under a metre at the scales we care about. */
    fun fastDistance(a: Coord, b: Coord): Double {
        val meanLat = (a.lat + b.lat) / 2 * Math.PI / 180
        val dx = (b.lon - a.lon) * Math.PI / 180 * cos(meanLat) * EARTH_RADIUS
        val dy = (b.lat - a.lat) * Math.PI / 180 * EARTH_RADIUS
        return sqrt(dx * dx + dy * dy)
    }

    /** Initial bearing in degrees, 0 = north. */
    fun bearing(a: Coord, b: Coord): Double {
        val lat1 = a.lat * Math.PI / 180
        val lat2 = b.lat * Math.PI / 180
        val dLon = (b.lon - a.lon) * Math.PI / 180
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = atan2(y, x) * 180 / Math.PI
        return if (deg < 0) deg + 360 else deg
    }

    /** Smallest absolute difference between two bearings, in degrees. */
    fun bearingDelta(a: Double, b: Double): Double {
        // Swift's truncatingRemainder has the sign of the dividend, like Kotlin's %.
        var d = abs(a - b) % 360
        if (d > 180) d = 360 - d
        return d
    }

    fun totalDistance(coords: List<Coord>): Double {
        if (coords.size <= 1) return 0.0
        var total = 0.0
        for (i in 1 until coords.size) total += fastDistance(coords[i - 1], coords[i])
        return total
    }

    /** Cumulative distance at each index. */
    fun cumulativeDistance(coords: List<Coord>): DoubleArray {
        val out = DoubleArray(coords.size)
        if (coords.size <= 1) return out
        for (i in 1 until coords.size) out[i] = out[i - 1] + fastDistance(coords[i - 1], coords[i])
        return out
    }

    /** Ramer–Douglas–Peucker simplification, tolerance in metres. */
    fun simplify(coords: List<Coord>, tolerance: Double): List<Coord> {
        if (coords.size <= 2) return coords
        val keep = BooleanArray(coords.size)
        keep[0] = true
        keep[coords.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to coords.size - 1)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end <= start + 1) continue
            var maxDist = 0.0
            var maxIndex = start
            for (i in (start + 1) until end) {
                val d = perpendicularDistance(coords[i], coords[start], coords[end])
                if (d > maxDist) {
                    maxDist = d
                    maxIndex = i
                }
            }
            if (maxDist > tolerance) {
                keep[maxIndex] = true
                stack.addLast(start to maxIndex)
                stack.addLast(maxIndex to end)
            }
        }
        val out = ArrayList<Coord>()
        for (i in coords.indices) if (keep[i]) out.add(coords[i])
        return out
    }

    /** Distance from point to the segment a→b, in metres. */
    fun perpendicularDistance(p: Coord, a: Coord, b: Coord): Double {
        val meanLat = a.lat * Math.PI / 180
        val mx = Math.PI / 180 * cos(meanLat) * EARTH_RADIUS
        val my = Math.PI / 180 * EARTH_RADIUS
        val px = p.lon * mx
        val py = p.lat * my
        val ax = a.lon * mx
        val ay = a.lat * my
        val bx = b.lon * mx
        val by = b.lat * my
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = max(0.0, min(1.0, t))
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /**
     * Evenly reduce a series to at most [limit] values, always keeping the ends.
     * Generic so it works on coordinates and on the elevation arrays beside them.
     */
    fun <T> downsample(values: List<T>, limit: Int): List<T> {
        if (values.size <= limit || limit <= 2) return values
        val out = ArrayList<T>(limit)
        val step = (values.size - 1).toDouble() / (limit - 1).toDouble()
        for (i in 0 until limit) {
            out.add(values[(i.toDouble() * step).swiftRounded().toInt()])
        }
        return out
    }

    /** Interpolate a point at [fraction] of the way from a to b. */
    fun interpolate(a: Coord, b: Coord, fraction: Double): Coord =
        Coord(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)

    /** Resample a path so points sit at fixed distance intervals. Used for shape comparison. */
    fun resampleByDistance(coords: List<Coord>, interval: Double): List<Coord> {
        if (coords.size <= 1 || interval <= 0) return coords
        val out = ArrayList<Coord>()
        out.add(coords[0])
        var carry = 0.0
        for (i in 1 until coords.size) {
            val a = coords[i - 1]
            val b = coords[i]
            val segment = fastDistance(a, b)
            if (segment <= 0) continue
            var consumed = 0.0
            while (carry + (segment - consumed) >= interval) {
                val need = interval - carry
                consumed += need
                out.add(interpolate(a, b, consumed / segment))
                carry = 0.0
            }
            carry += segment - consumed
        }
        val last = coords.last()
        val end = out.last()
        if (fastDistance(end, last) > interval / 2) out.add(last)
        return out
    }
}
