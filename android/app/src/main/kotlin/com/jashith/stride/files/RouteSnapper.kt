package com.jashith.stride.files

import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.routing.Osrm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Snaps tapped waypoints onto real paths with the public OpenStreetMap routing servers. When a request
 * fails (offline, server busy) that stretch is joined with a straight line instead, so building a route
 * never depends on the network.
 */
object RouteSnapper {

    suspend fun snap(waypoints: List<Coord>, walking: Boolean): List<Coord> = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext waypoints
        val result = ArrayList<Coord>()
        for (chunk in Osrm.chunks(waypoints)) {
            val snapped = fetch(Osrm.routeUrl(Osrm.baseUrl(walking), chunk)) ?: chunk
            if (result.isEmpty()) result.addAll(snapped) else result.addAll(snapped.drop(1))
        }
        result
    }

    private fun fetch(url: String): List<Coord>? =
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            // The OpenStreetMap servers ask clients to identify themselves.
            connection.setRequestProperty("User-Agent", "Stride-Android/1.0 (route builder)")
            try {
                if (connection.responseCode != 200) null
                else Osrm.parseRoute(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
}
