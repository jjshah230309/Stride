package com.jashith.stride.core.geo

import com.jashith.stride.core.model.BoundingBox
import com.jashith.stride.core.model.Coord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun distances() {
        val a = Coord(51.0, 0.0)
        val b = Coord(52.0, 0.0)
        assertEquals(111_194.9, Geo.distance(a, b), 1.0)
        assertEquals(111_194.9, Geo.fastDistance(a, b), 1.0)
        assertEquals(0.0, Geo.distance(a, a), 0.0)
    }

    @Test fun bearings() {
        val o = Coord(10.0, 10.0)
        assertEquals(0.0, Geo.bearing(o, Coord(11.0, 10.0)), 1e-9)
        assertEquals(90.0, Geo.bearing(Coord(0.0, 10.0), Coord(0.0, 11.0)), 1e-6)
        assertEquals(180.0, Geo.bearing(o, Coord(9.0, 10.0)), 1e-9)
        assertEquals(270.0, Geo.bearing(Coord(0.0, 10.0), Coord(0.0, 9.0)), 1e-6)
        assertEquals(20.0, Geo.bearingDelta(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.bearingDelta(0.0, 180.0), 1e-9)
    }

    @Test fun totalAndCumulativeDistance() {
        val path = listOf(Coord(51.0, 0.0), Coord(51.001, 0.0), Coord(51.002, 0.0))
        val cum = Geo.cumulativeDistance(path)
        assertEquals(0.0, cum[0], 0.0)
        assertEquals(Geo.totalDistance(path), cum[2], 1e-9)
        assertEquals(222.4, cum[2], 0.5)
        assertEquals(0.0, Geo.totalDistance(listOf(Coord(1.0, 1.0))), 0.0)
    }

    @Test fun simplifyDropsCollinearPointsAndKeepsCorners() {
        val line = (0..10).map { Coord(51.0 + it * 0.0001, 0.0) }
        assertEquals(2, Geo.simplify(line, tolerance = 1.0).size)
        val corner = listOf(Coord(51.0, 0.0), Coord(51.001, 0.0), Coord(51.001, 0.001))
        assertEquals(3, Geo.simplify(corner, tolerance = 1.0).size)
    }

    @Test fun downsampleKeepsEnds() {
        val values = (0..99).toList()
        val out = Geo.downsample(values, limit = 10)
        assertEquals(10, out.size)
        assertEquals(0, out.first())
        assertEquals(99, out.last())
        assertEquals(values, Geo.downsample(values, limit = 200))
    }

    @Test fun boundingBox() {
        assertNull(BoundingBox.of(listOf(Coord(0.0, 0.0))))
        val box = BoundingBox.of(listOf(Coord(51.0, -1.0), Coord(52.0, 1.0)))!!
        assertTrue(box.contains(Coord(51.5, 0.0)))
        assertTrue(box.intersects(BoundingBox(51.5, 53.0, 0.0, 2.0)))
        assertTrue(!box.intersects(BoundingBox(53.0, 54.0, 0.0, 2.0)))
        assertTrue(box.expanded(1000.0).minLat < box.minLat)
    }

    @Test fun movingAverageIsCentredAndClampedAtTheEdges() {
        val out = Smooth.movingAverage(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), window = 3)
        assertEquals(1.5, out[0], 1e-12)
        assertEquals(2.0, out[1], 1e-12)
        assertEquals(4.5, out[4], 1e-12)
    }

    @Test fun elevationChangeIgnoresNoiseBelowThreshold() {
        val noisy = doubleArrayOf(100.0, 100.4, 99.8, 100.5, 100.1)
        val flat = Smooth.elevationChange(noisy, threshold = 1.0)
        assertEquals(0.0, flat.gain, 0.0)
        assertEquals(0.0, flat.loss, 0.0)
        val hill = Smooth.elevationChange(doubleArrayOf(100.0, 105.0, 110.0, 104.0), threshold = 1.0)
        assertEquals(10.0, hill.gain, 1e-9)
        assertEquals(6.0, hill.loss, 1e-9)
    }

    @Test fun kalmanConvergesTowardsAConstantMeasurement() {
        val k = Smooth.Kalman1D(initial = 0.0)
        var last = 0.0
        repeat(200) { last = k.update(50.0) }
        assertEquals(50.0, last, 0.5)
    }
}
