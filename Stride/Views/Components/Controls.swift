import SwiftUI

// MARK: - Tabs

enum AppTab: String, CaseIterable, Identifiable {
    case today, record, progress, routes

    var id: String { rawValue }

    var title: String {
        switch self {
        case .today: return "Today"
        case .record: return "Record"
        case .progress: return "Progress"
        case .routes: return "Routes"
        }
    }

    var symbol: String {
        switch self {
        case .today: return "waveform.path.ecg"
        case .record: return "record.circle"
        case .progress: return "trophy"
        case .routes: return "map"
        }
    }
}

struct StrideTabBar: View {
    @Binding var selection: AppTab

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.allCases) { tab in
                item(tab)
            }
        }
        .padding(.top, 10)
        .padding(.horizontal, 8)
        .background(
            Theme.pageBackground
                .overlay(Rectangle().fill(Theme.surfaceBorder).frame(height: 1), alignment: .top)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private func item(_ tab: AppTab) -> some View {
        let isActive = selection == tab
        return Button {
            selection = tab
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    if isActive {
                        Circle()
                            .fill(Theme.lime)
                            .frame(width: 40, height: 40)
                    }
                    Image(systemName: tab.symbol)
                        .font(.system(size: 18, weight: isActive ? .semibold : .regular))
                        .foregroundStyle(isActive ? Theme.deepGreen : Theme.secondaryText)
                }
                .frame(height: 40)
                Text(tab.title)
                    .font(.system(size: 11, weight: isActive ? .semibold : .medium))
                    .foregroundStyle(isActive ? Theme.green : Theme.secondaryText)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Sport toggle

enum SportChoice: String, CaseIterable, Identifiable {
    case run, ride

    var id: String { rawValue }
    var title: String { self == .run ? "Run" : "Ride" }
    var symbol: String { self == .run ? "shoeprints.fill" : "bicycle" }

    var defaultSport: SportType { self == .run ? .run : .ride }

    static func from(_ sport: SportType) -> SportChoice {
        sport.isRide ? .ride : .run
    }
}

/// The Run / Ride switch. Two looks: one for the dark hero card, one for a page.
struct SportToggle: View {
    @Binding var selection: SportChoice
    var onDark: Bool = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(SportChoice.allCases) { choice in
                segment(choice)
            }
        }
        .padding(4)
        .background(onDark ? Theme.inkBar.opacity(0.85) : Theme.track)
        .clipShape(Capsule())
    }

    private func segment(_ choice: SportChoice) -> some View {
        let isActive = selection == choice
        let foreground: Color = isActive
            ? Theme.deepGreen
            : (onDark ? Color.white.opacity(0.85) : Theme.secondaryText)
        let background: Color = isActive ? (onDark ? Theme.lime : Color.white) : Color.clear
        return Button {
            selection = choice
        } label: {
            HStack(spacing: 6) {
                Image(systemName: choice.symbol)
                    .font(.system(size: 12, weight: .semibold))
                Text(choice.title)
                    .font(.system(size: 14, weight: .semibold))
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 9)
            .frame(maxWidth: .infinity)
            .background(background)
            .foregroundStyle(foreground)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Bars

/// Seven capsules with weekday letters, sitting on the dark card.
struct WeekBars: View {
    var values: [Double]
    var labels: [String]
    var todayIndex: Int
    var barColor: Color = Theme.inkBar
    var highlight: Color = Theme.lime
    var labelColor: Color = Theme.onInkSecondary
    var height: CGFloat = 78

    private var peak: Double {
        Swift.max(values.max() ?? 1, 0.0001)
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack(alignment: .bottom, spacing: 10) {
                ForEach(values.indices, id: \.self) { i in
                    Capsule()
                        .fill(i == todayIndex ? highlight : barColor)
                        .frame(width: 14, height: barHeight(i))
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(height: height, alignment: .bottom)

            HStack(spacing: 10) {
                ForEach(labels.indices, id: \.self) { i in
                    Text(labels[i])
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(labelColor)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func barHeight(_ i: Int) -> CGFloat {
        let fraction = values[i] / peak
        // A rest day still shows a small marker rather than vanishing.
        return Swift.max(4, CGFloat(fraction) * height)
    }
}

/// The trend bars inside a dark card — last column highlighted.
struct TrendBars: View {
    var values: [Double]
    var height: CGFloat = 96

    private var peak: Double { Swift.max(values.max() ?? 1, 0.0001) }

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            ForEach(values.indices, id: \.self) { i in
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(i == values.count - 1 ? Theme.lime : Theme.inkBar)
                    .frame(height: Swift.max(6, CGFloat(values[i] / peak) * height))
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(height: height, alignment: .bottom)
    }
}

// MARK: - Chips and small controls

struct FilterChip: View {
    var title: String
    var isActive: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(isActive ? Theme.ink : Theme.surface)
                .foregroundStyle(isActive ? Color.white : Theme.primaryText)
                .clipShape(Capsule())
                .overlay(
                    Capsule().strokeBorder(isActive ? Color.clear : Theme.surfaceBorder, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

struct CircleIconButton: View {
    var symbol: String
    var background: Color = Theme.surface
    var foreground: Color = Theme.primaryText
    var size: CGFloat = 44
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * 0.36, weight: .semibold))
                .foregroundStyle(foreground)
                .frame(width: size, height: size)
                .background(background)
                .clipShape(Circle())
                .overlay(
                    Circle().strokeBorder(background == Theme.surface ? Theme.surfaceBorder : Color.clear,
                                          lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

/// The initials badge in the Today header.
struct AvatarBadge: View {
    var name: String
    var size: CGFloat = 46
    var action: () -> Void

    private var initials: String {
        let parts = name.split(separator: " ").prefix(2)
        let letters = parts.compactMap { $0.first }.map(String.init)
        return letters.isEmpty ? "ME" : letters.joined().uppercased()
    }

    var body: some View {
        Button(action: action) {
            Text(initials)
                .font(.system(size: size * 0.34, weight: .bold))
                .foregroundStyle(Theme.deepGreen)
                .frame(width: size, height: size)
                .background(Theme.lime)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

/// A rounded lime tile holding a small glyph, used at the head of list rows.
struct GlyphTile: View {
    var symbol: String
    var size: CGFloat = 44

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.4, weight: .semibold))
            .foregroundStyle(Theme.deepGreen)
            .frame(width: size, height: size)
            .background(Theme.lime)
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

/// A tappable row: glyph, title, subtitle, chevron.
struct DetailRow: View {
    var symbol: String
    var title: String
    var subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            GlyphTile(symbol: symbol)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.rowTitle)
                    .foregroundStyle(Theme.primaryText)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.secondaryText)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.tertiaryText)
        }
        .padding(14)
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Theme.surfaceBorder, lineWidth: 1)
        )
    }
}

/// A floating label over a map, as in the Live GPS and distance badges.
struct MapBadge: View {
    var text: String
    var symbol: String?

    var body: some View {
        HStack(spacing: 6) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 12, weight: .semibold))
            }
            Text(text)
                .font(.system(size: 13, weight: .semibold))
        }
        .foregroundStyle(Theme.primaryText)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.white.opacity(0.94))
        .clipShape(Capsule())
    }
}
