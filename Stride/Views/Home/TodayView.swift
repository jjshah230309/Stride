import SwiftUI
import UniformTypeIdentifiers

struct TodayView: View {
    @EnvironmentObject private var store: AppStore
    @Binding var tab: AppTab

    @State private var sport: SportChoice = .run
    @State private var showProfile = false
    @State private var showSuggestion = false
    @State private var showImport = false

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    heroCard
                    readiness
                    metricRow
                    recentSection
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
            }
            .background(Theme.pageBackground)
            .navigationBarHidden(true)
            .navigationDestination(for: UUID.self) { id in
                if let activity = store.activity(with: id) {
                    ActivityDetailView(activity: activity)
                }
            }
            .sheet(isPresented: $showProfile) {
                NavigationStack { ProfileView() }
            }
            .sheet(isPresented: $showSuggestion) {
                SuggestionSheet(readiness: readinessScore, form: currentForm)
                    .presentationDetents([.medium])
            }
            .fileImporter(isPresented: $showImport,
                          allowedContentTypes: ActivityFile.readableContentTypes,
                          allowsMultipleSelection: true) { result in
                handleImport(result)
            }
            .onAppear {
                sport = SportChoice.from(store.settings.display.defaultSport)
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Kicker(text: Fmt.headerDayFormatter.string(from: Date()))
                Text(greeting)
                    .font(.screenTitle)
                    .foregroundStyle(Theme.primaryText)
            }
            Spacer()
            AvatarBadge(name: store.settings.profile.name) { showProfile = true }
        }
        .padding(.top, 8)
    }

    private var greeting: String {
        if store.currentStreak >= 3 {
            return "\(store.currentStreak) days strong"
        }
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 4..<11: return "Ready to move?"
        case 11..<17: return "Time for a session?"
        case 17..<22: return "Evening effort?"
        default: return "Ready to move?"
        }
    }

    // MARK: - Hero

    private var sportsFilter: [SportType] {
        sport == .run ? SportType.runGroup : SportType.rideGroup
    }

    private var weekTotals: Totals {
        store.totals(for: store.currentWeek, sports: sportsFilter)
    }

    private var weekDays: (values: [Double], labels: [String], today: Int) {
        var calendar = Calendar.current
        calendar.firstWeekday = store.settings.display.weekStartsMonday ? 2 : 1
        let week = store.currentWeek
        var values: [Double] = []
        var labels: [String] = []
        var todayIndex = 0
        let letters = store.settings.display.weekStartsMonday
            ? ["M", "T", "W", "T", "F", "S", "S"]
            : ["S", "M", "T", "W", "T", "F", "S"]

        for offset in 0..<7 {
            guard let day = calendar.date(byAdding: .day, value: offset, to: week.start) else { continue }
            let total = store.activities
                .filter { calendar.isDate($0.startDate, inSameDayAs: day) && sportsFilter.contains($0.sport) }
                .reduce(0.0) { $0 + $1.distance }
            values.append(total / units.metersPerUnit)
            labels.append(letters[offset])
            if calendar.isDateInToday(day) { todayIndex = offset }
        }
        return (values, labels, todayIndex)
    }

    private var heroCard: some View {
        InkCard {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    SportToggle(selection: $sport, onDark: true)
                        .frame(maxWidth: 220)
                    Spacer()
                    Button {
                        showSuggestion = true
                    } label: {
                        Image(systemName: "sparkles")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Theme.lime)
                    }
                    .buttonStyle(.plain)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text("This week")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Theme.onInkSecondary)
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(Fmt.distance(weekTotals.distance, units, decimals: 1))
                            .font(.figure(52, .bold))
                            .monospacedDigit()
                            .foregroundStyle(.white)
                        Text(units.distanceUnit)
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(Theme.onInkSecondary)
                    }
                }

                let week = weekDays
                WeekBars(values: week.values, labels: week.labels, todayIndex: week.today)

                PrimaryButton(title: sport == .run ? "Start run" : "Start ride",
                              symbol: "play.fill") {
                    store.settings.display.defaultSport = sport.defaultSport
                    tab = .record
                }
            }
        }
    }

    // MARK: - Readiness

    private var currentForm: Double {
        store.fitnessCurve.last?.form ?? 0
    }

    /// Form maps onto a 0–100 dial: deeply fatigued at the bottom, rested at the top.
    private var readinessScore: Int {
        let scaled = (currentForm + 45) / 85 * 100
        return Int(Swift.max(0, Swift.min(100, scaled)).rounded())
    }

    private var readinessLabel: String {
        switch readinessScore {
        case 80...: return "Fully recovered"
        case 60..<80: return "Balanced load"
        case 40..<60: return "Building fitness"
        case 20..<40: return "Carrying fatigue"
        default: return "Time to back off"
        }
    }

    private var readiness: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Kicker(text: "Training readiness")
                    Text(readinessLabel)
                        .font(.system(size: 21, weight: .bold))
                        .foregroundStyle(Theme.primaryText)
                }
                Spacer()
                Text("\(readinessScore)")
                    .font(.figure(32, .bold))
                    .monospacedDigit()
                    .foregroundStyle(Theme.green)
            }
            ProgressBar(progress: Double(readinessScore) / 100, color: Theme.green, height: 9)
        }
        .padding(.top, 4)
    }

    // MARK: - Metrics

    private var metricRow: some View {
        let point = store.fitnessCurve.last
        let elevation = store.totals(for: store.currentWeek).elevationGain
        return HStack(spacing: 12) {
            MetricCard(symbol: "heart",
                       label: "Fitness",
                       value: Fmt.int(point?.fitness ?? 0),
                       unit: nil)
            MetricCard(symbol: "gauge.with.needle",
                       label: "Fatigue",
                       value: Fmt.int(point?.fatigue ?? 0),
                       unit: nil)
            MetricCard(symbol: "mountain.2",
                       label: "Elevation",
                       value: Fmt.elevation(elevation, units),
                       unit: units.elevationUnit)
        }
    }

    // MARK: - Recent

    private var recent: [Activity] {
        Array(store.activities.prefix(8))
    }

    private var recentSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Kicker(text: "Recent")
                Spacer()
                Menu {
                    Button {
                        showImport = true
                    } label: {
                        Label("Import activity file", systemImage: "square.and.arrow.down")
                    }
                    NavigationLink {
                        ManualActivityView()
                    } label: {
                        Label("Add manual activity", systemImage: "plus.circle")
                    }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.green)
                }
            }

            if recent.isEmpty {
                Card {
                    EmptyStateView(symbol: "figure.run",
                                   title: "Nothing recorded yet",
                                   message: "Tap Record to start your first run or ride, or import a GPX file from another app.")
                }
            } else {
                ForEach(recent) { activity in
                    NavigationLink(value: activity.id) {
                        ActivityRow(activity: activity)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.top, 6)
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        for url in urls {
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { continue }
            guard let imported = try? ActivityFile.load(data: data, filename: url.lastPathComponent),
                  !imported.isRoute else { continue }
            let activity = ActivityFile.makeActivity(from: imported,
                                                      profile: store.settings.profile)
            store.add(activity, points: imported.points)
        }
    }
}

/// One activity in the Today list: a small map strip, the name, and the numbers.
struct ActivityRow: View {
    @EnvironmentObject private var store: AppStore
    var activity: Activity

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                GlyphTile(symbol: activity.sport.symbol, size: 42)
                VStack(alignment: .leading, spacing: 3) {
                    Text(activity.name)
                        .font(.rowTitle)
                        .foregroundStyle(Theme.primaryText)
                        .lineLimit(1)
                    Text(Fmt.relativeDay(activity.startDate))
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.secondaryText)
                }
                Spacer(minLength: 6)
                if prCount > 0 {
                    Pill(text: "\(prCount) PR", color: Theme.green)
                }
            }
            .padding(14)

            if activity.hasGPS && activity.previewPath.count > 1 {
                RouteMap(path: activity.previewPath,
                         showsStartEnd: false,
                         interactive: false,
                         color: Theme.mapLine,
                         padding: 12)
                    .frame(height: 108)
                    .allowsHitTesting(false)
            }

            HStack(spacing: 0) {
                StatTile(label: "Distance",
                         value: Fmt.distance(activity.distance, units),
                         unit: units.distanceUnit, size: 17)
                StatTile(label: activity.sport.usesPace ? "Pace" : "Speed",
                         value: activity.sport.usesPace
                            ? Fmt.pace(distance: activity.distance, time: activity.movingTime, units)
                            : Fmt.avgSpeed(distance: activity.distance, time: activity.movingTime, units),
                         size: 17)
                StatTile(label: "Time", value: Fmt.duration(activity.movingTime), size: 17)
                if activity.elevationGain > 5 {
                    StatTile(label: "Climb",
                             value: Fmt.elevation(activity.elevationGain, units),
                             unit: units.elevationUnit, size: 17)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(Theme.surfaceBorder, lineWidth: 1)
        )
    }

    private var prCount: Int {
        store.segmentEfforts.filter { $0.activityID == activity.id && $0.isPR }.count
    }
}

/// What the sparkle offers: a session that suits how recovered you are.
struct SuggestionSheet: View {
    @Environment(\.dismiss) private var dismiss
    var readiness: Int
    var form: Double

    private var suggestion: (String, String, String) {
        switch readiness {
        case 80...:
            return ("Go hard",
                    "Intervals or a time trial",
                    "You are rested and the fatigue has cleared. This is the day to chase a personal record or run a hard session — 6 × 3 minutes at threshold, or a segment you have been circling.")
        case 60..<80:
            return ("Steady quality",
                    "Tempo or a progression",
                    "Balanced load. A controlled tempo effort fits well — 20 to 30 minutes at a pace you could hold for an hour, then an easy few minutes to finish.")
        case 40..<60:
            return ("Keep building",
                    "Easy aerobic volume",
                    "You are absorbing a solid block. Stay conversational, keep the heart rate in zone 2, and let the fitness catch up with the work.")
        case 20..<40:
            return ("Ease off",
                    "Short and easy",
                    "Fatigue is running ahead of fitness. Thirty to forty minutes easy, or a walk. The gains from last week land while you recover, not while you push.")
        default:
            return ("Rest",
                    "Take the day",
                    "You are deep in the hole. A rest day now protects the block you have just done — training through this is how niggles turn into injuries.")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Kicker(text: "Suggested today")
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Theme.secondaryText)
                }
                .buttonStyle(.plain)
            }
            Text(suggestion.0)
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(Theme.primaryText)
            Pill(text: suggestion.1, color: Theme.green)
            Text(suggestion.2)
                .font(.system(size: 15))
                .foregroundStyle(Theme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()
            Text("Based on your form of \(Fmt.signed(form)) — fitness minus fatigue. It is a guide, not a rule; how your legs actually feel wins.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.tertiaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.pageBackground)
    }
}
