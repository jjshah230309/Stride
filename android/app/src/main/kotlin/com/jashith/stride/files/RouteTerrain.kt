package com.jashith.stride.files

import com.jashith.stride.core.geo.Geo
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.BoundingBox
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.TrackPoint

/**
 * Elevation borrowed from your own recorded tracks near each point of a route: the only terrain data
 * available without a network service. Port of the iOS route builder's lookup.
 */
object RouteTerrain {

    fun elevations(path: List<Coord>, activities: List<Activity>, loadTrack: (Activity) -> List<TrackPoint>): List<Double> {
        val routeBox = BoundingBox.of(path) ?: return emptyList()
        val searchBox = routeBox.expanded(120.0)

        val samples = ArrayList<Pair<Coord, Double>>()
        for (activity in activities.take(60)) {
            if (!activity.hasGPS) continue
            val box = activity.boundingBox ?: continue
            if (!box.expanded(200.0).intersects(routeBox)) continue
            val track = loadTrack(activity)
            // Every fourth sample is plenty for looking up terrain height.
            var i = 0
            while (i < track.size) {
                val point = track[i]
                if (point.coord.isValid && searchBox.contains(point.coord)) samples.add(point.coord to point.alt)
                i += 4
            }
            if (samples.size > 8000) break
        }
        if (samples.isEmpty()) return emptyList()

        return path.map { target ->
            var best = Double.MAX_VALUE
            var value = 0.0
            for ((coord, alt) in samples) {
                val d = Geo.fastDistance(coord, target)
                if (d < best) {
                    best = d
                    value = alt
                }
                if (best < 12) break
            }
            if (best < 60) value else 0.0
        }
    }
}
