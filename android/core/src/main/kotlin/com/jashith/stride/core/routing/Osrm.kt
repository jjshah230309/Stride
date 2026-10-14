package com.jashith.stride.core.routing

import com.jashith.stride.core.model.Coord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * The request and response format of an OSRM routing server, with no networking in it. The app module
 * does the HTTP; keeping this part pure means it can be tested without a network.
 *
 * This replaces MapKit directions on iOS: snapping tapped waypoints onto real paths.
 */
object Osrm {

    const val FOOT_BASE = "https://routing.openstreetmap.de/routed-foot"
    const val BIKE_BASE = "https://routing.openstreetmap.de/routed-bike"

    fun baseUrl(walking: Boolean): String = if (walking) FOOT_BASE else BIKE_BASE

    /** `…/route/v1/driving/lon,lat;lon,lat?overview=full&geometries=geojson`. The profile word is fixed by these servers. */
    fun routeUrl(base: String, waypoints: List<Coord>): String {
        val coords = waypoints.joinToString(";") { String.format(Locale.ROOT, "%.6f,%.6f", it.lon, it.lat) }
        return "$base/route/v1/driving/$coords?overview=full&geometries=geojson&steps=false"
    }

    /** The snapped line, or null when the server found no route or the body is not what was expected. */
    fun parseRoute(body: String): List<Coord>? {
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return null
        }
        if (root["code"]?.jsonPrimitive?.content != "Ok") return null
        val route = (root["routes"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val coordinates = (route["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return null
        val out = ArrayList<Coord>(coordinates.size)
        for (pair in coordinates) {
            val arr = pair.jsonArray
            if (arr.size < 2) continue
            val lon = (arr[0] as? JsonPrimitive)?.doubleOrNull ?: continue
            val lat = (arr[1] as? JsonPrimitive)?.doubleOrNull ?: continue
            out.add(Coord(lat, lon))
        }
        return if (out.size >= 2) out else null
    }

    /** Splits a long waypoint list into overlapping chunks small enough for one request each. */
    fun chunks(waypoints: List<Coord>, size: Int = 25): List<List<Coord>> {
        if (waypoints.size <= size) return listOf(waypoints)
        val out = ArrayList<List<Coord>>()
        var start = 0
        while (start < waypoints.size - 1) {
            val end = minOf(waypoints.size, start + size)
            out.add(waypoints.subList(start, end))
            start = end - 1
        }
        return out
    }
}
