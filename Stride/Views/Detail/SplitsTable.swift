import SwiftUI

/// The split table, with a bar behind each row scaled to pace so the fast and slow
/// kilometres are obvious at a glance.
struct SplitsTable: View {
    var splits: [Split]
    var sport: SportType
    var units: UnitSystem

    private var paceValues: [Double] {
        splits.map { split in
            guard split.distance > 1, split.moving > 0 else { return 0 }
            return split.moving / (split.distance / units.metersPerUnit)
        }
    }

    private var fastest: Double {
        paceValues.filter { $0 > 0 }.min() ?? 1
    }

    private var slowest: Double {
        paceValues.filter { $0 > 0 }.max() ?? 1
    }

    var body: some View {
        VStack(spacing: 6) {
            headerRow
            ForEach(Array(splits.enumerated()), id: \.element.id) { index, split in
                row(split, pace: paceValues[index])
            }
        }
    }

    private var headerRow: some View {
        HStack {
            Text(units.distanceUnit.uppercased())
                .frame(width: 26, alignment: .leading)
            Text(sport.usesPace ? "PACE" : "SPEED")
                .frame(width: 62, alignment: .leading)
            Spacer()
            Text("ELEV").frame(width: 46, alignment: .trailing)
            if splits.contains(where: { $0.avgHR != nil }) {
                Text("HR").frame(width: 34, alignment: .trailing)
            }
        }
        .font(.system(size: 9, weight: .semibold))
        .foregroundStyle(.tertiary)
    }

    private func row(_ split: Split, pace: Double) -> some View {
        let span = Swift.max(0.001, slowest - fastest)
        // Fastest split gets the full bar, slowest the shortest.
        let fraction = pace > 0 ? 1.0 - (pace - fastest) / span * 0.72 : 0.1
        return ZStack(alignment: .leading) {
            GeometryReader { geo in
                RoundedRectangle(cornerRadius: 4)
                    .fill(sport.accentColor.opacity(0.18))
                    .frame(width: geo.size.width * Swift.max(0.08, fraction))
            }
            HStack {
                Text(split.isPartial ? String(format: "%.2f", split.distance / units.metersPerUnit) : "\(split.index)")
                    .font(.system(size: 12, weight: .semibold))
                    .frame(width: 26, alignment: .leading)
                Text(sport.usesPace
                     ? Fmt.paceFromSeconds(pace)
                     : Fmt.avgSpeed(distance: split.distance, time: split.moving, units))
                    .font(.system(size: 13, weight: .semibold))
                    .monospacedDigit()
                    .frame(width: 62, alignment: .leading)
                if let gap = split.gapSeconds, sport.usesPace, abs(gap - pace) > 3 {
                    Text("GAP " + Fmt.paceFromSeconds(gap))
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(elevationText(split))
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .frame(width: 46, alignment: .trailing)
                if let hr = split.avgHR {
                    Text("\(Int(hr))")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .frame(width: 34, alignment: .trailing)
                }
            }
            .padding(.horizontal, 6)
        }
        .frame(height: 26)
    }

    private func elevationText(_ split: Split) -> String {
        let net = split.elevGain - split.elevLoss
        guard abs(net) > 0.5 else { return "—" }
        return Fmt.signed(Fmt.elevationValue(net, units), decimals: 0)
    }
}
