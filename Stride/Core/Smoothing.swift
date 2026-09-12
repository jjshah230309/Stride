import Foundation

enum Smooth {

    /// Centred moving average. `window` is the total width in samples.
    static func movingAverage(_ values: [Double], window: Int) -> [Double] {
        guard window > 1, values.count > 1 else { return values }
        let half = window / 2
        var out = [Double](repeating: 0, count: values.count)
        var sum = 0.0
        var prefix = [Double](repeating: 0, count: values.count + 1)
        for i in 0..<values.count {
            sum += values[i]
            prefix[i + 1] = sum
        }
        for i in 0..<values.count {
            let lo = Swift.max(0, i - half)
            let hi = Swift.min(values.count - 1, i + half)
            out[i] = (prefix[hi + 1] - prefix[lo]) / Double(hi - lo + 1)
        }
        return out
    }

    /// Median filter — better than a mean for knocking out single-sample GPS spikes.
    static func median(_ values: [Double], window: Int) -> [Double] {
        guard window > 1, values.count > window else { return values }
        let half = window / 2
        var out = [Double](repeating: 0, count: values.count)
        for i in 0..<values.count {
            let lo = Swift.max(0, i - half)
            let hi = Swift.min(values.count - 1, i + half)
            var slice = Array(values[lo...hi])
            slice.sort()
            out[i] = slice[slice.count / 2]
        }
        return out
    }

    /// Exponential moving average with the given smoothing factor (0…1, higher = more responsive).
    static func exponential(_ values: [Double], alpha: Double) -> [Double] {
        guard let first = values.first else { return values }
        var out = [Double]()
        out.reserveCapacity(values.count)
        var acc = first
        for v in values {
            acc = alpha * v + (1 - alpha) * acc
            out.append(acc)
        }
        return out
    }

    /// Total ascent and descent from an elevation series, ignoring noise below `threshold` metres.
    /// Barometric and GPS altitude both wander; without a threshold a flat run "climbs" 200 m.
    static func elevationChange(_ elevations: [Double], threshold: Double = 1.0) -> (gain: Double, loss: Double) {
        guard elevations.count > 1 else { return (0, 0) }
        var gain = 0.0
        var loss = 0.0
        var reference = elevations[0]
        for e in elevations.dropFirst() {
            let delta = e - reference
            if delta > threshold {
                gain += delta
                reference = e
            } else if delta < -threshold {
                loss += -delta
                reference = e
            }
        }
        return (gain, loss)
    }

    /// A running one-dimensional Kalman filter, used to steady GPS altitude in real time.
    struct Kalman1D {
        var estimate: Double
        var errorCovariance: Double
        var processNoise: Double
        var measurementNoise: Double

        init(initial: Double, processNoise: Double = 0.02, measurementNoise: Double = 4.0) {
            self.estimate = initial
            self.errorCovariance = 1.0
            self.processNoise = processNoise
            self.measurementNoise = measurementNoise
        }

        mutating func update(_ measurement: Double, measurementNoise: Double? = nil) -> Double {
            let r = measurementNoise ?? self.measurementNoise
            errorCovariance += processNoise
            let gain = errorCovariance / (errorCovariance + r)
            estimate += gain * (measurement - estimate)
            errorCovariance *= (1 - gain)
            return estimate
        }
    }
}
