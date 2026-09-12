import Foundation

enum UnitSystem: String, Codable, CaseIterable, Identifiable {
    case metric
    case imperial

    var id: String { rawValue }
    var title: String { self == .metric ? "Metric (km)" : "Imperial (mi)" }

    var distanceUnit: String { self == .metric ? "km" : "mi" }
    var elevationUnit: String { self == .metric ? "m" : "ft" }
    var speedUnit: String { self == .metric ? "km/h" : "mph" }
    var paceUnit: String { self == .metric ? "/km" : "/mi" }
    var weightUnit: String { self == .metric ? "kg" : "lb" }
    var shortUnit: String { self == .metric ? "m" : "ft" }

    /// Meters in one display distance unit.
    var metersPerUnit: Double { self == .metric ? 1000.0 : 1609.344 }
    /// Meters in one display elevation unit.
    var metersPerElevationUnit: Double { self == .metric ? 1.0 : 0.3048 }
    /// Kilograms in one display weight unit.
    var kilogramsPerWeightUnit: Double { self == .metric ? 1.0 : 0.45359237 }

    var spokenDistanceUnit: String { self == .metric ? "kilometres" : "miles" }
    var spokenDistanceUnitSingular: String { self == .metric ? "kilometre" : "mile" }
    var spokenElevationUnit: String { self == .metric ? "metres" : "feet" }
    var spokenSpeedUnit: String { self == .metric ? "kilometres per hour" : "miles per hour" }
    /// Shorter than "per hour" but still unambiguous — an initialism risks being
    /// read as a word rather than spelled out.
    var shortSpokenSpeedUnit: String { self == .metric ? "kilometres an hour" : "miles an hour" }
}

enum Fmt {

    // MARK: - Distance

    static func distanceValue(_ meters: Double, _ u: UnitSystem) -> Double {
        meters / u.metersPerUnit
    }

    static func distance(_ meters: Double, _ u: UnitSystem, decimals: Int = 2) -> String {
        let v = distanceValue(meters, u)
        return String(format: "%.\(decimals)f", v)
    }

    static func distanceWithUnit(_ meters: Double, _ u: UnitSystem, decimals: Int = 2) -> String {
        distance(meters, u, decimals: decimals) + " " + u.distanceUnit
    }

    static func elevationValue(_ meters: Double, _ u: UnitSystem) -> Double {
        meters / u.metersPerElevationUnit
    }

    static func elevation(_ meters: Double, _ u: UnitSystem) -> String {
        String(format: "%.0f", elevationValue(meters, u))
    }

    static func elevationWithUnit(_ meters: Double, _ u: UnitSystem) -> String {
        elevation(meters, u) + " " + u.elevationUnit
    }

    // MARK: - Time

    /// 1:02:03 for hours, 5:03 below an hour.
    static func duration(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "--:--" }
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%d:%02d", m, s)
    }

    /// Always h:mm:ss, padded. Used where column alignment matters.
    static func durationPadded(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "--:--:--" }
        let total = Int(seconds.rounded())
        return String(format: "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    /// "1h 24m" style, for summaries.
    static func durationCompact(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0m" }
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        if h > 0 { return "\(h)h \(m)m" }
        if m > 0 { return "\(m)m" }
        return "\(total)s"
    }

    // MARK: - Pace & speed

    /// Seconds needed to cover one display unit of distance.
    static func paceSeconds(distance meters: Double, time seconds: TimeInterval, _ u: UnitSystem) -> Double? {
        guard meters > 1, seconds > 0 else { return nil }
        return seconds / (meters / u.metersPerUnit)
    }

    static func pace(distance meters: Double, time seconds: TimeInterval, _ u: UnitSystem) -> String {
        guard let p = paceSeconds(distance: meters, time: seconds, u), p.isFinite, p < 60 * 99 else { return "--:--" }
        return paceFromSeconds(p)
    }

    static func paceFromSeconds(_ secondsPerUnit: Double) -> String {
        guard secondsPerUnit.isFinite, secondsPerUnit > 0, secondsPerUnit < 60 * 99 else { return "--:--" }
        let total = Int(secondsPerUnit.rounded())
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// Instantaneous pace from a speed in m/s.
    static func paceFromSpeed(_ mps: Double, _ u: UnitSystem) -> String {
        guard mps > 0.15 else { return "--:--" }
        return paceFromSeconds(u.metersPerUnit / mps)
    }

    static func speedValue(_ mps: Double, _ u: UnitSystem) -> Double {
        mps * 3600.0 / u.metersPerUnit
    }

    static func speed(_ mps: Double, _ u: UnitSystem, decimals: Int = 1) -> String {
        guard mps.isFinite, mps >= 0 else { return "0.0" }
        return String(format: "%.\(decimals)f", speedValue(mps, u))
    }

    static func avgSpeed(distance meters: Double, time seconds: TimeInterval, _ u: UnitSystem) -> String {
        guard seconds > 0 else { return "0.0" }
        return speed(meters / seconds, u)
    }

    // MARK: - Scalars

    static func int(_ v: Double?) -> String {
        guard let v, v.isFinite else { return "--" }
        return String(format: "%.0f", v)
    }

    static func one(_ v: Double?) -> String {
        guard let v, v.isFinite else { return "--" }
        return String(format: "%.1f", v)
    }

    /// 1.0 reads as "1", 0.5 as "0.5" — no trailing zeros in interval labels.
    static func trimmed(_ v: Double) -> String {
        if abs(v - v.rounded()) < 0.001 { return String(Int(v.rounded())) }
        var text = String(format: "%.2f", v)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return text
    }

    static func percent(_ v: Double) -> String {
        String(format: "%.0f%%", v * 100)
    }

    static func signed(_ v: Double, decimals: Int = 0) -> String {
        let s = String(format: "%.\(decimals)f", abs(v))
        if v > 0 { return "+" + s }
        if v < 0 { return "-" + s }
        return s
    }

    // MARK: - Dates

    static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "EEEE, d MMMM"
        return f
    }()

    static let shortDayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "d MMM"
        return f
    }()

    static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.timeStyle = .short
        f.dateStyle = .none
        return f
    }()

    /// "MONDAY, 31 AUGUST" for the Today header.
    static let headerDayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "EEEE, d MMMM"
        return f
    }()

    static let monthFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMMM yyyy"
        return f
    }()

    static func relativeDay(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "Today at " + timeFormatter.string(from: date) }
        if cal.isDateInYesterday(date) { return "Yesterday at " + timeFormatter.string(from: date) }
        let days = cal.dateComponents([.day], from: date, to: Date()).day ?? 0
        if days < 7 {
            let f = DateFormatter()
            f.dateFormat = "EEEE"
            return f.string(from: date) + " at " + timeFormatter.string(from: date)
        }
        return shortDayFormatter.string(from: date) + " at " + timeFormatter.string(from: date)
    }

    // MARK: - Spoken forms

    /// "4 minutes 32 seconds" — clearer than "4:32" through a headphone.
    static func spokenDuration(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "unknown" }
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        var parts: [String] = []
        if h > 0 { parts.append("\(h) hour" + (h == 1 ? "" : "s")) }
        if m > 0 { parts.append("\(m) minute" + (m == 1 ? "" : "s")) }
        if s > 0 || parts.isEmpty { parts.append("\(s) second" + (s == 1 ? "" : "s")) }
        return parts.joined(separator: " ")
    }

    /// "4 30 per kilometre" reads badly; "4 minutes 30 seconds per kilometre" reads well.
    static func spokenPace(_ secondsPerUnit: Double, _ u: UnitSystem) -> String {
        guard secondsPerUnit.isFinite, secondsPerUnit > 0, secondsPerUnit < 60 * 99 else { return "no pace yet" }
        return spokenDuration(secondsPerUnit) + " per " + u.spokenDistanceUnitSingular
    }

    static func spokenDistance(_ meters: Double, _ u: UnitSystem) -> String {
        let v = meters / u.metersPerUnit
        if v < 0.995 {
            // Below one unit, speak the raw sub-unit for precision.
            if u == .metric { return "\(Int(meters.rounded())) metres" }
            return "\(Int((meters / 0.3048).rounded())) feet"
        }
        let rounded = (v * 100).rounded() / 100
        let whole = Int(rounded)
        let hundredths = Int(((rounded - Double(whole)) * 100).rounded())
        let unit = (whole == 1 && hundredths == 0) ? u.spokenDistanceUnitSingular : u.spokenDistanceUnit
        if hundredths == 0 { return "\(whole) \(unit)" }
        return "\(whole) point \(hundredths < 10 ? "0" : "")\(hundredths) \(unit)"
    }

    static func spokenNumber(_ v: Double) -> String {
        String(Int(v.rounded()))
    }
}
