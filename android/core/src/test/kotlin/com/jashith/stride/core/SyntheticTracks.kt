package com.jashith.stride.core

import com.jashith.stride.core.model.TrackPoint

/** Straight tracks heading due north from a fixed start, with exactly known geometry. */
object SyntheticTracks {
    /** Metres per degree of latitude in Geo.fastDistance (earth radius * pi / 180). */
    const val METRES_PER_DEGREE = 6_371_000.0 * Math.PI / 180

    const val START_LAT = 51.5
    const val START_LON = -0.12

    fun latAt(distance: Double): Double = START_LAT + distance / METRES_PER_DEGREE

    /**
     * 1 Hz samples at a constant [speed]. [grade] is a fraction (0.05 = 5% climb). Anything in
     * [pausedFrom, pausedTo) seconds is stationary and flagged not moving.
     */
    fun line(
        seconds: Int,
        speed: Double = 3.0,
        grade: Double = 0.0,
        startAlt: Double = 20.0,
        hr: ((Int) -> Double?)? = null,
        cad: ((Int) -> Double?)? = null,
        pw: ((Int) -> Double?)? = null,
        pausedFrom: Int = -1,
        pausedTo: Int = -1,
    ): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        var d = 0.0
        for (i in 0..seconds) {
            val paused = i in pausedFrom until pausedTo
            if (i > 0 && !paused) d += speed
            out.add(
                TrackPoint(
                    t = i.toDouble(), lat = latAt(d), lon = START_LON, alt = startAlt + d * grade, d = d,
                    v = if (paused || i == 0) 0.0 else speed,
                    hr = hr?.invoke(i), cad = cad?.invoke(i), pw = pw?.invoke(i),
                    moving = !paused, acc = 4.0,
                ),
            )
        }
        return out
    }
}
