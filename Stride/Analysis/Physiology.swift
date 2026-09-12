import Foundation

enum GradeAdjustedPace {
    /// Minetti's measured energy cost of running, in joules per kilogram per metre,
    /// as a function of gradient. This is the curve behind grade adjusted pace.
    static func energyCost(gradient i: Double) -> Double {
        let g = Swift.max(-0.45, Swift.min(0.45, i))
        let g2 = g * g
        let g3 = g2 * g
        let g4 = g3 * g
        let g5 = g4 * g
        return 155.4 * g5 - 30.4 * g4 - 43.3 * g3 + 46.3 * g2 + 19.5 * g + 3.6
    }

    static let flatCost: Double = 3.6

    /// How much harder than flat ground this gradient is. 1.0 = flat.
    static func factor(gradient: Double) -> Double {
        Swift.max(0.5, Swift.min(3.0, energyCost(gradient: gradient) / flatCost))
    }

    /// Equivalent flat pace, in seconds per kilometre, for a stretch run at `pace` on `gradient`.
    static func adjust(paceSecondsPerKm pace: Double, gradient: Double) -> Double {
        pace / factor(gradient: gradient)
    }

    /// Grade adjusted seconds per kilometre across a whole stream.
    static func adjustedPace(streams: Streams, from: Int, to: Int) -> Double? {
        guard to > from, to < streams.count else { return nil }
        let grades = streams.grade()
        var equivalentDistance = 0.0
        var movingSeconds = 0.0
        var i = from + 1
        while i <= to {
            let d = streams.distance[i] - streams.distance[i - 1]
            let dt = streams.time[i] - streams.time[i - 1]
            if d > 0 && dt > 0 && dt < 20 && streams.moving[i] {
                equivalentDistance += d * factor(gradient: grades[i])
                movingSeconds += dt
            }
            i += 1
        }
        guard equivalentDistance > 1 else { return nil }
        return movingSeconds / (equivalentDistance / 1000)
    }
}

enum PowerModel {

    /// Physics-based cycling power: gravity, rolling resistance, air drag and acceleration.
    /// This is the same model Strava uses for its estimated power.
    static func cyclingWatts(speed v: Double,
                             gradient: Double,
                             acceleration a: Double,
                             totalMassKg m: Double,
                             crr: Double,
                             cda: Double,
                             airDensity rho: Double = 1.225) -> Double {
        guard v > 0.5 else { return 0 }
        let g = 9.80665
        let theta = atan(gradient)
        let gravity = m * g * sin(theta)
        let rolling = m * g * cos(theta) * crr
        let drag = 0.5 * rho * cda * v * v
        let inertia = m * a
        let force = gravity + rolling + drag + inertia
        let watts = force * v / 0.976   // drivetrain efficiency
        return Swift.max(0, Swift.min(2000, watts))
    }

    /// Running power. Horizontal cost is roughly one joule per kilogram per metre;
    /// climbing adds the gravitational term at about a quarter efficiency.
    static func runningWatts(speed v: Double,
                             gradient: Double,
                             massKg m: Double) -> Double {
        guard v > 0.4 else { return 0 }
        let horizontal = 0.98 * m * v
        let verticalSpeed = v * gradient
        let vertical = verticalSpeed > 0
            ? m * 9.80665 * verticalSpeed / 0.25
            : m * 9.80665 * verticalSpeed * 0.10   // descending returns a little
        return Swift.max(0, Swift.min(1500, horizontal + vertical))
    }

    /// Normalised power: the fourth-root-of-the-mean-of-the-fourth-power of a
    /// 30-second rolling average. Weights surges the way your body feels them.
    static func normalizedPower(_ wattsPerSecond: [Double]) -> Double? {
        guard wattsPerSecond.count >= 30 else { return nil }
        let rolling = Smooth.movingAverage(wattsPerSecond, window: 30)
        var sum = 0.0
        for w in rolling {
            let x = Swift.max(0, w)
            sum += x * x * x * x
        }
        let mean = sum / Double(rolling.count)
        return pow(mean, 0.25)
    }

    /// Intensity factor and training stress, the standard cycling load metric.
    static func trainingStress(normalizedPower np: Double, ftp: Double, seconds: TimeInterval) -> Double {
        guard ftp > 0, seconds > 0 else { return 0 }
        let intensity = np / ftp
        return (seconds * np * intensity) / (ftp * 3600) * 100
    }
}

enum EffortScore {

    /// Weights per heart-rate zone, rising steeply — matching how Strava's
    /// Relative Effort punishes time spent near threshold.
    static let zoneWeights: [Double] = [0.6, 1.0, 1.9, 3.6, 6.6]

    /// Relative Effort from time spent in each heart rate zone, in seconds.
    static func relativeEffort(zoneSeconds: [TimeInterval]) -> Double {
        var score = 0.0
        for (i, seconds) in zoneSeconds.enumerated() where i < zoneWeights.count {
            score += (seconds / 60.0) * zoneWeights[i]
        }
        return score
    }

    /// Fallback when there is no heart rate: scale by how hard the pace was
    /// relative to threshold pace.
    static func estimatedEffort(movingTime: TimeInterval,
                                gradeAdjustedPace pace: Double,
                                thresholdPace: Double) -> Double {
        guard movingTime > 0, pace > 0, thresholdPace > 0 else { return 0 }
        let intensity = Swift.max(0.4, Swift.min(1.4, thresholdPace / pace))
        let weight = 1.0 + 5.6 * pow(Swift.max(0, intensity - 0.55), 2.0)
        return (movingTime / 60.0) * weight
    }

    /// Calories from heart rate (Keytel), falling back to a MET estimate.
    static func calories(profile: AthleteProfile,
                         sport: SportType,
                         movingTime: TimeInterval,
                         avgHR: Double?,
                         avgSpeed: Double) -> Double {
        let minutes = movingTime / 60
        guard minutes > 0 else { return 0 }
        if let hr = avgHR, hr > 50 {
            let age = Double(profile.age)
            let w = profile.weightKg
            let perMinute: Double
            if profile.isMale {
                perMinute = (-55.0969 + 0.6309 * hr + 0.1988 * w + 0.2017 * age) / 4.184
            } else {
                perMinute = (-20.4022 + 0.4472 * hr - 0.1263 * w + 0.074 * age) / 4.184
            }
            return Swift.max(0, perMinute * minutes)
        }
        // MET model, scaled by how fast you actually moved.
        var met = sport.baseMET
        if sport.isRun && avgSpeed > 0 {
            met = Swift.max(6.0, Swift.min(20.0, avgSpeed * 3.6 * 1.0))
        } else if sport.isRide && avgSpeed > 0 {
            met = Swift.max(4.0, Swift.min(16.0, avgSpeed * 3.6 * 0.42))
        }
        return met * profile.weightKg * (minutes / 60)
    }
}

enum RacePredictor {

    /// Riegel's endurance exponent. 1.06 is the classic value for trained runners.
    static let exponent = 1.06

    static func predict(fromDistance d1: Double, time t1: TimeInterval, toDistance d2: Double) -> TimeInterval {
        guard d1 > 0, t1 > 0, d2 > 0 else { return 0 }
        return t1 * pow(d2 / d1, exponent)
    }

    static let standardRaces: [(String, Double)] = [
        ("1 km", 1000), ("1 mile", 1609.344), ("5K", 5000), ("10K", 10000),
        ("15K", 15000), ("10 mile", 16093.44), ("Half Marathon", 21097.5),
        ("Marathon", 42195)
    ]

    /// VDOT-style fitness score from a recent effort, useful for ranking performances.
    static func performanceIndex(distance: Double, time: TimeInterval) -> Double {
        guard distance > 400, time > 60 else { return 0 }
        let velocity = distance / (time / 60)                      // metres per minute
        let percentMax = 0.8 + 0.1894393 * exp(-0.012778 * (time / 60)) + 0.2989558 * exp(-0.1932605 * (time / 60))
        let vo2 = -4.60 + 0.182258 * velocity + 0.000104 * velocity * velocity
        return vo2 / percentMax
    }
}
