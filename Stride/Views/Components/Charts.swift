import SwiftUI

/// A plain line chart drawn with Path. Everything here is hand-drawn so the charts
/// look the same on every iOS version and stay fast with ten thousand samples.
struct LineChart: View {
    var values: [Double]
    var color: Color = Theme.accent
    var fill: Bool = false
    var lineWidth: CGFloat = 2
    var yMin: Double?
    var yMax: Double?
    /// Highlight a range of indices, used to show a best effort inside a stream.
    var highlight: ClosedRange<Int>?

    var body: some View {
        GeometryReader { geo in
            let bounds = resolvedBounds()
            let points = normalized(in: geo.size, bounds: bounds)
            ZStack {
                if let highlight, points.count > 1 {
                    highlightShape(points: points, range: highlight, size: geo.size)
                        .fill(color.opacity(0.16))
                }
                if fill && points.count > 1 {
                    areaPath(points: points, size: geo.size)
                        .fill(
                            LinearGradient(colors: [color.opacity(0.30), color.opacity(0.02)],
                                           startPoint: .top, endPoint: .bottom)
                        )
                }
                linePath(points: points)
                    .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
            }
        }
    }

    private func resolvedBounds() -> (Double, Double) {
        let lo = yMin ?? (values.min() ?? 0)
        let hi = yMax ?? (values.max() ?? 1)
        if hi - lo < 0.0001 { return (lo - 1, hi + 1) }
        return (lo, hi)
    }

    private func normalized(in size: CGSize, bounds: (Double, Double)) -> [CGPoint] {
        guard values.count > 1 else { return [] }
        let (lo, hi) = bounds
        let span = hi - lo
        let stepX = size.width / CGFloat(values.count - 1)
        var out: [CGPoint] = []
        out.reserveCapacity(values.count)
        for (i, v) in values.enumerated() {
            let clamped = Swift.max(lo, Swift.min(hi, v))
            let y = size.height - CGFloat((clamped - lo) / span) * size.height
            out.append(CGPoint(x: CGFloat(i) * stepX, y: y))
        }
        return out
    }

    private func linePath(points: [CGPoint]) -> Path {
        var path = Path()
        guard let first = points.first else { return path }
        path.move(to: first)
        for p in points.dropFirst() { path.addLine(to: p) }
        return path
    }

    private func areaPath(points: [CGPoint], size: CGSize) -> Path {
        var path = linePath(points: points)
        if let last = points.last, let first = points.first {
            path.addLine(to: CGPoint(x: last.x, y: size.height))
            path.addLine(to: CGPoint(x: first.x, y: size.height))
            path.closeSubpath()
        }
        return path
    }

    private func highlightShape(points: [CGPoint], range: ClosedRange<Int>, size: CGSize) -> Path {
        var path = Path()
        let lower = Swift.max(0, Swift.min(points.count - 1, range.lowerBound))
        let upper = Swift.max(0, Swift.min(points.count - 1, range.upperBound))
        guard upper > lower else { return path }
        path.addRect(CGRect(x: points[lower].x, y: 0,
                            width: points[upper].x - points[lower].x, height: size.height))
        return path
    }
}

/// Two or more lines on shared axes — used by Fitness & Freshness.
struct MultiLineChart: View {
    struct Series: Identifiable {
        var id = UUID()
        var values: [Double]
        var color: Color
        var fill: Bool = false
        var lineWidth: CGFloat = 2
    }

    var series: [Series]
    var zeroLine: Bool = false

    private var bounds: (Double, Double) {
        var lo = Double.greatestFiniteMagnitude
        var hi = -Double.greatestFiniteMagnitude
        for s in series {
            for v in s.values {
                lo = Swift.min(lo, v)
                hi = Swift.max(hi, v)
            }
        }
        if lo > hi { return (0, 1) }
        if hi - lo < 0.001 { return (lo - 1, hi + 1) }
        let pad = (hi - lo) * 0.08
        return (lo - pad, hi + pad)
    }

    var body: some View {
        let (lo, hi) = bounds
        GeometryReader { geo in
            ZStack {
                if zeroLine && lo < 0 && hi > 0 {
                    let y = geo.size.height - CGFloat((0 - lo) / (hi - lo)) * geo.size.height
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: y))
                        p.addLine(to: CGPoint(x: geo.size.width, y: y))
                    }
                    .stroke(Color.secondary.opacity(0.3), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
                }
                ForEach(series) { s in
                    LineChart(values: s.values, color: s.color, fill: s.fill,
                              lineWidth: s.lineWidth, yMin: lo, yMax: hi)
                }
            }
        }
    }
}

/// Weekly or daily volume bars.
struct BarChart: View {
    var values: [Double]
    var labels: [String] = []
    var color: Color = Theme.accent
    var highlightLast: Bool = true
    var barSpacing: CGFloat = 4

    var body: some View {
        let maxValue = Swift.max(values.max() ?? 1, 0.0001)
        VStack(spacing: 6) {
            GeometryReader { geo in
                HStack(alignment: .bottom, spacing: barSpacing) {
                    ForEach(values.indices, id: \.self) { i in
                        let fraction = values[i] / maxValue
                        let isLast = highlightLast && i == values.count - 1
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(isLast ? color : color.opacity(0.35))
                            .frame(height: Swift.max(2, CGFloat(fraction) * geo.size.height))
                            .frame(maxWidth: .infinity)
                    }
                }
                .frame(maxHeight: .infinity, alignment: .bottom)
            }
            if !labels.isEmpty {
                HStack(spacing: barSpacing) {
                    ForEach(labels.indices, id: \.self) { i in
                        Text(labels[i])
                            .font(.system(size: 9))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
    }
}

/// Horizontal time-in-zone bars.
struct ZoneBars: View {
    var times: [TimeInterval]
    var names: [String]
    var shortNames: [String]
    var colors: [Color]
    var ranges: [(Double, Double)] = []
    var unit: String = "bpm"

    private var total: TimeInterval {
        Swift.max(1, times.reduce(0, +))
    }

    var body: some View {
        VStack(spacing: 10) {
            ForEach(times.indices, id: \.self) { i in
                row(i)
            }
        }
    }

    private func row(_ i: Int) -> some View {
        let fraction = times[i] / total
        return VStack(spacing: 4) {
            HStack(spacing: 6) {
                Text(i < shortNames.count ? shortNames[i] : "Z\(i + 1)")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(colors[Swift.min(i, colors.count - 1)])
                    .frame(width: 22, alignment: .leading)
                Text(i < names.count ? names[i] : "")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                if i < ranges.count {
                    Text(rangeText(i))
                        .font(.system(size: 11))
                        .foregroundStyle(.tertiary)
                }
                Spacer()
                Text(Fmt.durationCompact(times[i]))
                    .font(.system(size: 12, weight: .semibold))
                    .monospacedDigit()
                Text(Fmt.percent(fraction))
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .frame(width: 38, alignment: .trailing)
            }
            ProgressBar(progress: fraction, color: colors[Swift.min(i, colors.count - 1)], height: 6)
        }
    }

    private func rangeText(_ i: Int) -> String {
        let (lo, hi) = ranges[i]
        if i == ranges.count - 1 { return "\(Int(lo))+ \(unit)" }
        return "\(Int(lo))–\(Int(hi)) \(unit)"
    }
}

/// Log-scaled power (or pace) curve.
struct PowerCurveChart: View {
    var points: [PowerCurvePoint]
    var comparison: [PowerCurvePoint] = []
    var color: Color = Theme.rideColor

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if !comparison.isEmpty {
                    curvePath(comparison, size: geo.size)
                        .stroke(Color.secondary.opacity(0.4),
                                style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                }
                curvePath(points, size: geo.size)
                    .stroke(color, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            }
        }
    }

    private var maxWatts: Double {
        Swift.max(1, Swift.max(points.map(\.watts).max() ?? 1, comparison.map(\.watts).max() ?? 1))
    }

    private var durationBounds: (Double, Double) {
        let all = points + comparison
        let lo = all.map(\.duration).min() ?? 1
        let hi = all.map(\.duration).max() ?? 3600
        return (Swift.max(1, lo), Swift.max(lo + 1, hi))
    }

    private func curvePath(_ data: [PowerCurvePoint], size: CGSize) -> Path {
        var path = Path()
        guard data.count > 1 else { return path }
        let (lo, hi) = durationBounds
        let logLo = log(lo), logHi = log(hi)
        let span = Swift.max(0.001, logHi - logLo)
        let sorted = data.sorted { $0.duration < $1.duration }
        for (i, p) in sorted.enumerated() {
            let x = CGFloat((log(Swift.max(1, p.duration)) - logLo) / span) * size.width
            let y = size.height - CGFloat(p.watts / maxWatts) * size.height
            if i == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
        }
        return path
    }
}

/// The training-log calendar: one square per day, sized by volume.
struct TrainingLogGrid: View {
    var weeks: [[DayVolume]]
    var maxValue: Double
    var onSelect: ((Date) -> Void)?

    struct DayVolume: Identifiable, Hashable {
        var id: Date { date }
        var date: Date
        var value: Double
        var color: Color
        var count: Int
    }

    var body: some View {
        VStack(spacing: 5) {
            ForEach(weeks.indices, id: \.self) { w in
                HStack(spacing: 5) {
                    ForEach(weeks[w]) { day in
                        dayCell(day)
                    }
                }
            }
        }
    }

    private func dayCell(_ day: DayVolume) -> some View {
        let fraction = maxValue > 0 ? Swift.min(1, day.value / maxValue) : 0
        return RoundedRectangle(cornerRadius: 5, style: .continuous)
            .fill(day.count > 0 ? day.color.opacity(0.25 + 0.75 * fraction) : Color.secondary.opacity(0.10))
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .overlay {
                if Calendar.current.isDateInToday(day.date) {
                    RoundedRectangle(cornerRadius: 5, style: .continuous)
                        .strokeBorder(Theme.accent, lineWidth: 1.5)
                }
            }
            .onTapGesture { onSelect?(day.date) }
    }
}

/// A tiny inline chart for list rows.
struct Sparkline: View {
    var values: [Double]
    var color: Color = Theme.accent

    var body: some View {
        LineChart(values: values, color: color, fill: true, lineWidth: 1.5)
            .frame(height: 26)
    }
}

/// Axis labels drawn under a chart.
struct ChartAxis: View {
    var labels: [String]

    var body: some View {
        HStack {
            ForEach(labels.indices, id: \.self) { i in
                Text(labels[i])
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                if i < labels.count - 1 { Spacer() }
            }
        }
    }
}
