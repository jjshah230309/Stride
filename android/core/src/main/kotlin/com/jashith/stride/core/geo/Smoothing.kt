package com.jashith.stride.core.geo

import kotlin.math.max
import kotlin.math.min

object Smooth {

    /** Centred moving average. [window] is the total width in samples. */
    fun movingAverage(values: DoubleArray, window: Int): DoubleArray {
        if (window <= 1 || values.size <= 1) return values
        val half = window / 2
        val out = DoubleArray(values.size)
        val prefix = DoubleArray(values.size + 1)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            prefix[i + 1] = sum
        }
        for (i in values.indices) {
            val lo = max(0, i - half)
            val hi = min(values.size - 1, i + half)
            out[i] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1).toDouble()
        }
        return out
    }

    /** Median filter, better than a mean for knocking out single-sample GPS spikes. */
    fun median(values: DoubleArray, window: Int): DoubleArray {
        if (window <= 1 || values.size <= window) return values
        val half = window / 2
        val out = DoubleArray(values.size)
        for (i in values.indices) {
            val lo = max(0, i - half)
            val hi = min(values.size - 1, i + half)
            val slice = values.copyOfRange(lo, hi + 1)
            slice.sort()
            out[i] = slice[slice.size / 2]
        }
        return out
    }

    /** Exponential moving average; [alpha] in 0…1, higher is more responsive. */
    fun exponential(values: DoubleArray, alpha: Double): DoubleArray {
        if (values.isEmpty()) return values
        val out = DoubleArray(values.size)
        var acc = values[0]
        for (i in values.indices) {
            acc = alpha * values[i] + (1 - alpha) * acc
            out[i] = acc
        }
        return out
    }

    data class ElevationChange(val gain: Double, val loss: Double)

    /**
     * Total ascent and descent from an elevation series, ignoring noise below [threshold] metres.
     * Barometric and GPS altitude both wander; without a threshold a flat run "climbs" 200 m.
     */
    fun elevationChange(elevations: DoubleArray, threshold: Double = 1.0): ElevationChange {
        if (elevations.size <= 1) return ElevationChange(0.0, 0.0)
        var gain = 0.0
        var loss = 0.0
        var reference = elevations[0]
        for (i in 1 until elevations.size) {
            val e = elevations[i]
            val delta = e - reference
            if (delta > threshold) {
                gain += delta
                reference = e
            } else if (delta < -threshold) {
                loss += -delta
                reference = e
            }
        }
        return ElevationChange(gain, loss)
    }

    fun elevationChange(elevations: List<Double>, threshold: Double = 1.0): ElevationChange =
        elevationChange(elevations.toDoubleArray(), threshold)

    /** A running one-dimensional Kalman filter, used to steady GPS altitude in real time. */
    class Kalman1D(
        initial: Double,
        private val processNoise: Double = 0.02,
        private val measurementNoise: Double = 4.0,
    ) {
        var estimate: Double = initial
            private set
        private var errorCovariance: Double = 1.0

        fun update(measurement: Double, measurementNoise: Double? = null): Double {
            val r = measurementNoise ?: this.measurementNoise
            errorCovariance += processNoise
            val gain = errorCovariance / (errorCovariance + r)
            estimate += gain * (measurement - estimate)
            errorCovariance *= (1 - gain)
            return estimate
        }
    }
}
