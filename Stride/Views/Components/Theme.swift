import SwiftUI

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }
}

enum Theme {

    // MARK: - Core palette

    /// The lime that carries every primary action.
    static let lime = Color(hex: 0xDDF85C)
    /// Near-black forest green used for hero cards and anything sitting on lime.
    static let ink = Color(hex: 0x1A2A1D)
    /// The readable green for figures and status text.
    static let green = Color(hex: 0x1A7A45)
    static let deepGreen = Color(hex: 0x14401F)

    static let pageBackground = Color(hex: 0xF7F7F4)
    static let surface = Color.white
    static let surfaceBorder = Color(hex: 0xECECE6)
    static let track = Color(hex: 0xE6E7E1)
    static let mapBase = Color(hex: 0xDDE3D6)
    static let mapLine = Color(hex: 0x1E4620)

    static let primaryText = Color(hex: 0x14181A)
    static let secondaryText = Color(hex: 0x767C73)
    static let tertiaryText = Color(hex: 0xA2A79E)
    /// Muted text and shapes sitting on the dark card.
    static let onInkSecondary = Color(hex: 0x9AA596)
    static let inkBar = Color(hex: 0x33422F)

    // MARK: - Named roles kept for the rest of the app

    static let accent = lime
    static let positive = green
    static let negative = Color(hex: 0xC2410C)
    static let warning = Color(hex: 0xB45309)

    static let runColor = green
    static let rideColor = Color(hex: 0x2F6F52)
    static let hikeColor = Color(hex: 0x4A7C2F)

    static let fitnessColor = green
    static let fatigueColor = Color(hex: 0xC2410C)
    static let formColor = Color(hex: 0x2F6F52)

    /// Five heart-rate zones, cool to hot, kept within the palette.
    static let zoneColors: [Color] = [
        Color(hex: 0xB9C4B2), Color(hex: 0x7FA98C), Color(hex: 0x1A7A45),
        Color(hex: 0xB9C22F), Color(hex: 0xC2410C)
    ]

    static let powerZoneColors: [Color] = [
        Color(hex: 0xB9C4B2), Color(hex: 0x8FB79A), Color(hex: 0x4E9468),
        Color(hex: 0x1A7A45), Color(hex: 0xA8BE33), Color(hex: 0xD98324),
        Color(hex: 0xC2410C)
    ]

    // MARK: - Legacy signatures

    /// The design is a fixed light palette, so the colour scheme is ignored.
    static func background(_ scheme: ColorScheme) -> Color { pageBackground }
    static func card(_ scheme: ColorScheme) -> Color { surface }
    static func separator(_ scheme: ColorScheme) -> Color { surfaceBorder }
}

// MARK: - Typography

extension Font {
    /// Large figures. Rounded reads friendlier at size and keeps digits even.
    static func figure(_ size: CGFloat, _ weight: Font.Weight = .bold) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }

    static var kicker: Font {
        .system(size: 11, weight: .semibold)
    }

    static var screenTitle: Font {
        .system(size: 30, weight: .bold)
    }

    static var cardTitle: Font {
        .system(size: 16, weight: .semibold)
    }

    static var rowTitle: Font {
        .system(size: 15, weight: .semibold)
    }

    static var caption: Font {
        .system(size: 12, weight: .medium)
    }

    // Kept so older call sites still build.
    static func statValue(_ size: CGFloat) -> Font { figure(size, .semibold) }
    static var statLabel: Font { kicker }
    static var sectionTitle: Font { .system(size: 13, weight: .semibold) }
}

// MARK: - Building blocks

/// The small uppercase line that sits above a title.
struct Kicker: View {
    var text: String
    var color: Color = Theme.secondaryText

    var body: some View {
        Text(text.uppercased())
            .font(.kicker)
            .kerning(1.1)
            .foregroundStyle(color)
    }
}

/// Kicker plus big title, with an optional control on the right.
struct ScreenHeader<Trailing: View>: View {
    var kicker: String
    var title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Kicker(text: kicker)
                Text(title)
                    .font(.screenTitle)
                    .foregroundStyle(Theme.primaryText)
            }
            Spacer()
            trailing
        }
    }
}

extension ScreenHeader where Trailing == EmptyView {
    init(kicker: String, title: String) {
        self.init(kicker: kicker, title: title) { EmptyView() }
    }
}

/// White card with a hairline border — the standard container.
struct Card<Content: View>: View {
    var padding: CGFloat = 16
    var radius: CGFloat = 20
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(Theme.surfaceBorder, lineWidth: 1)
            )
    }
}

/// The dark hero card used for the week summary and the trend panel.
struct InkCard<Content: View>: View {
    var padding: CGFloat = 20
    var radius: CGFloat = 24
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.ink)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

struct SectionHeader: View {
    var title: String
    var action: String?
    var onTap: (() -> Void)?

    var body: some View {
        HStack {
            Kicker(text: title)
            Spacer()
            if let action, let onTap {
                Button(action, action: onTap)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.green)
            }
        }
    }
}

/// One number with its label.
struct StatTile: View {
    var label: String
    var value: String
    var unit: String? = nil
    var size: CGFloat = 22
    var color: Color? = nil
    var alignment: HorizontalAlignment = .leading

    var body: some View {
        VStack(alignment: alignment, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.figure(size, .semibold))
                    .monospacedDigit()
                    .foregroundStyle(color ?? Theme.primaryText)
                if let unit {
                    Text(unit)
                        .font(.system(size: Swift.max(10, size * 0.44), weight: .medium))
                        .foregroundStyle(Theme.secondaryText)
                }
            }
            Text(label.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .kerning(0.7)
                .foregroundStyle(Theme.secondaryText)
        }
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .center)
    }
}

/// Small white card: outlined icon, label, figure. Used in the Today row.
struct MetricCard: View {
    var symbol: String
    var label: String
    var value: String
    var unit: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .regular))
                .foregroundStyle(Theme.green)
            VStack(alignment: .leading, spacing: 1) {
                Text(label)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.secondaryText)
                HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(value)
                        .font(.figure(19, .bold))
                        .monospacedDigit()
                        .foregroundStyle(Theme.primaryText)
                    if let unit {
                        Text(unit)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(Theme.secondaryText)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Theme.surfaceBorder, lineWidth: 1)
        )
    }
}

/// The larger Progress tile: a big soft figure with a green note beneath.
struct GhostMetricCard: View {
    var symbol: String
    var label: String
    var value: String
    var note: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .regular))
                .foregroundStyle(Theme.green)
            Text(label)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Theme.secondaryText)
            Text(value)
                .font(.figure(38, .bold))
                .monospacedDigit()
                .foregroundStyle(Theme.primaryText.opacity(0.22))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(note)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.green)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(Theme.surfaceBorder, lineWidth: 1)
        )
    }
}

struct Pill: View {
    var text: String
    var color: Color = Theme.green
    var filled: Bool = false

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(filled ? color : color.opacity(0.12))
            .foregroundStyle(filled ? Color.white : color)
            .clipShape(Capsule())
    }
}

/// Full-width lime action button.
struct PrimaryButton: View {
    var title: String
    var symbol: String?
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let symbol {
                    Image(systemName: symbol)
                        .font(.system(size: 15, weight: .bold))
                }
                Text(title)
                    .font(.system(size: 17, weight: .bold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 17)
            .background(Theme.lime)
            .foregroundStyle(Theme.ink)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct ProgressBar: View {
    var progress: Double
    var color: Color = Theme.green
    var height: CGFloat = 8

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.track)
                Capsule()
                    .fill(color)
                    .frame(width: Swift.max(0, Swift.min(1, progress)) * geo.size.width)
            }
        }
        .frame(height: height)
    }
}

struct EmptyStateView: View {
    var symbol: String
    var title: String
    var message: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(Theme.tertiaryText)
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.primaryText)
            Text(message)
                .font(.system(size: 14))
                .foregroundStyle(Theme.secondaryText)
                .multilineTextAlignment(.center)
            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle)
                        .font(.system(size: 14, weight: .semibold))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 11)
                        .background(Theme.lime)
                        .foregroundStyle(Theme.ink)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .padding(.horizontal, 24)
    }
}

struct GPSIndicator: View {
    var strength: Int

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<3, id: \.self) { i in
                Capsule()
                    .fill(i < strength ? color : Theme.track)
                    .frame(width: 3, height: CGFloat(6 + i * 4))
            }
        }
    }

    private var color: Color {
        switch strength {
        case 3: return Theme.green
        case 2: return Theme.warning
        case 1: return Theme.negative
        default: return Theme.tertiaryText
        }
    }
}

extension View {
    func cardStyle(_ scheme: ColorScheme, radius: CGFloat = 20) -> some View {
        self.background(Theme.surface)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(Theme.surfaceBorder, lineWidth: 1)
            )
    }
}
