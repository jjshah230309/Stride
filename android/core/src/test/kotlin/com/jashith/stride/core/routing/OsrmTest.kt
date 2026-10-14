package com.jashith.stride.core.routing

import com.jashith.stride.core.model.Coord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OsrmTest {
    @Test
    fun urlPutsLongitudeFirst() {
        val url = Osrm.routeUrl(Osrm.FOOT_BASE, listOf(Coord(51.5, -0.12), Coord(51.51, -0.1)))
        assertEquals(
            "https://routing.openstreetmap.de/routed-foot/route/v1/driving/-0.120000,51.500000;-0.100000,51.510000?overview=full&geometries=geojson&steps=false",
            url,
        )
    }

    @Test
    fun parsesGeoJsonGeometry() {
        val body = """{"code":"Ok","routes":[{"geometry":{"type":"LineString","coordinates":[[-0.12,51.5],[-0.11,51.505],[-0.1,51.51]]},"distance":1234.5}],"waypoints":[]}"""
        val path = Osrm.parseRoute(body)!!
        assertEquals(3, path.size)
        assertEquals(Coord(51.5, -0.12), path[0])
        assertEquals(Coord(51.51, -0.1), path[2])
    }

    @Test
    fun noRouteAndGarbageGiveNull() {
        assertNull(Osrm.parseRoute("""{"code":"NoRoute","routes":[]}"""))
        assertNull(Osrm.parseRoute("not json"))
        assertNull(Osrm.parseRoute("""{"code":"Ok","routes":[{"geometry":{"coordinates":[[1,2]]}}]}"""))
    }

    @Test
    fun chunksOverlapByOnePoint() {
        val points = (0 until 60).map { Coord(it.toDouble() / 100, 0.0) }
        val chunks = Osrm.chunks(points, 25)
        assertEquals(listOf(25, 25, 12), chunks.map { it.size })
        assertEquals(chunks[0].last(), chunks[1].first())
        assertEquals(points.last(), chunks.last().last())
        assertEquals(1, Osrm.chunks(points.take(10), 25).size)
    }
}
