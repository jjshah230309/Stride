import SwiftUI

struct ProgressTabView: View {
    @EnvironmentObject private var store: AppStore
    @State private var section: Section = .fitness

    enum Section: String, CaseIterable, Identifiable {
        case fitness, log, goals, records
        var id: String { rawValue }
        var name: String {
            switch self {
            case .fitness: return "Fitness"
            case .log: return "Log"
            case .goals: return "Goals"
            case .records: return "Records"
            }
        }
    }

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    ScreenHeader(kicker: "Your training", title: "Progress")
                        .padding(.top, 8)
                    headlineGrid
                    trendCard
                    picker
                    detail
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
        }
    }

    // MARK: - Headline cards

    private var curve: [FitnessPoint] { store.fitnessCurve }
    private var today: FitnessPoint? { curve.last }

    private var fitnessChange: Double {
        guard curve.count > 30, let now = curve.last else { return 0 }
        return now.fitness - curve[curve.count - 31].fitness
    }

    private var weeklyLoad: Double {
        store.totals(for: store.currentWeek).relativeEffort
    }

    /// This week measured against the previous four, which is how you tell
    /// productive from reckless.
    private var loadVerdict: String {
        var previous: [Double] = []
        var reference = store.currentWeek.start.addingTimeInterval(-86400)
        for _ in 0..<4 {
            let interval = store.weekInterval(containing: reference)
            previous.append(store.totals(for: interval).relativeEffort)
            reference = interval.start.addingTimeInterval(-86400)
        }
        let average = previous.filter { $0 > 0 }
        guard !average.isEmpty else { return "Getting started" }
        let mean = average.reduce(0, +) / Double(average.count)
        guard mean > 0 else { return "Getting started" }
        let ratio = weeklyLoad / mean
        switch ratio {
        case ..<0.6: return "Recovery week"
        case 0.6..<0.85: return "Easing off"
        case 0.85..<1.25: return "Productive"
        case 1.25..<1.6: return "Ramping up"
        default: return "Ramping fast"
        }
    }

    private var bestFiveK: (String, String) {
        let records = store.personalRecords(sport: .run)
        guard let record = records.first(where: { $0.0.name == "5 km" }) ?? records.last else {
            return ("--", "No efforts yet")
        }
        return (Fmt.duration(record.0.time), Fmt.shortDayFormatter.string(from: record.1.startDate))
    }

    private var headlineGrid: some View {
        let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
        let best = bestFiveK
        return LazyVGrid(columns: columns, spacing: 12) {
            GhostMetricCard(symbol: "heart",
                            label: "Fitness",
                            value: Fmt.int(today?.fitness ?? 0),
                            note: fitnessChange >= 0
                                ? "\(Fmt.signed(fitnessChange)) this month"
                                : "\(Fmt.signed(fitnessChange)) this month")
            GhostMetricCard(symbol: "sparkles",
                            label: "Freshness",
                            value: Fmt.int(today?.form ?? 0),
                            note: FitnessCurve.formDescription(today?.form ?? 0).0)
            GhostMetricCard(symbol: "gauge.with.needle",
                            label: "Weekly load",
                            value: Fmt.int(weeklyLoad),
                            note: loadVerdict)
            GhostMetricCard(symbol: "trophy",
                            label: "Best 5K",
                            value: best.0,
                            note: best.1)
        }
    }

    // MARK: - Trend

    private var weeklyLoads: [Double] {
        var out: [Double] = []
        var reference = Date()
        for _ in 0..<8 {
            let interval = store.weekInterval(containing: reference)
            out.append(store.totals(for: interval).relativeEffort)
            reference = interval.start.addingTimeInterval(-86400)
        }
        return out.reversed()
    }

    private var trendHeadline: String {
        let loads = weeklyLoads
        guard loads.count >= 4 else { return "Just getting going" }
        let recent = loads.suffix(4).reduce(0, +) / 4
        let earlier = loads.prefix(4).reduce(0, +) / 4
        if earlier < 1 { return "Building from scratch" }
        let ratio = recent / earlier
        switch ratio {
        case ..<0.75: return "Winding down"
        case 0.75..<0.95: return "Holding steady"
        case 0.95..<1.3: return "Building consistently"
        default: return "Ramping hard"
        }
    }

    private var trendCard: some View {
        InkCard {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Kicker(text: "8-week trend", color: Theme.onInkSecondary)
                        Text(trendHeadline)
                            .font(.system(size: 21, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    Spacer()
                    Image(systemName: "stopwatch")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(Theme.lime)
                }
                TrendBars(values: weeklyLoads)
            }
        }
    }

    // MARK: - Detail sections

    private var picker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Section.allCases) { option in
                    FilterChip(title: option.name, isActive: section == option) {
                        section = option
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var detail: some View {
        switch section {
        case .fitness: FitnessSection()
        case .log: TrainingLogSection()
        case .goals: GoalsSection()
        case .records: RecordsSection()
        }
    }
}

// MARK: - Fitness & Freshness

struct FitnessSection: View {
    @EnvironmentObject private var store: AppStore
    @State private var range: Int = 90

    private var curve: [FitnessPoint] {
        let full = store.fitnessCurve
        return Array(full.suffix(range))
    }

    private var today: FitnessPoint? { curve.last }

    var body: some View {
        VStack(spacing: 14) {
            Card {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text("Fitness & Freshness")
                            .font(.system(size: 15, weight: .semibold))
                        Spacer()
                        Picker("Range", selection: $range) {
                            Text("6w").tag(42)
                            Text("3m").tag(90)
                            Text("6m").tag(180)
                            Text("1y").tag(365)
                        }
                        .pickerStyle(.segmented)
                        .frame(width: 170)
                    }

                    if curve.count > 2 {
                        MultiLineChart(series: [
                            MultiLineChart.Series(values: curve.map(\.fitness),
                                                  color: Theme.fitnessColor, fill: true, lineWidth: 2.5),
                            MultiLineChart.Series(values: curve.map(\.fatigue),
                                                  color: Theme.fatigueColor, lineWidth: 1.5)
                        ])
                        .frame(height: 150)

                        HStack(spacing: 16) {
                            legend(color: Theme.fitnessColor, label: "Fitness")
                            legend(color: Theme.fatigueColor, label: "Fatigue")
                            Spacer()
                        }
                    } else {
                        Text("Record a few more activities and this chart will fill in.")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if let today {
                Card {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 0) {
                            StatTile(label: "Fitness", value: Fmt.int(today.fitness),
                                     size: 24, color: Theme.fitnessColor, alignment: .center)
                            StatTile(label: "Fatigue", value: Fmt.int(today.fatigue),
                                     size: 24, color: Theme.fatigueColor, alignment: .center)
                            StatTile(label: "Form", value: Fmt.signed(today.form),
                                     size: 24, color: today.form >= 0 ? Theme.positive : Theme.warning,
                                     alignment: .center)
                        }
                        Divider()
                        let description = FitnessCurve.formDescription(today.form)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(description.0).font(.system(size: 14, weight: .semibold))
                            Text(description.1).font(.system(size: 12)).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Card {
                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(title: "Form over time")
                    if curve.count > 2 {
                        MultiLineChart(series: [
                            MultiLineChart.Series(values: curve.map(\.form),
                                                  color: Theme.formColor, fill: true, lineWidth: 2)
                        ], zeroLine: true)
                        .frame(height: 100)
                    }
                    Text("Fitness is a 42-day rolling average of training load, fatigue is a 7-day one. Form is the difference: positive means rested, negative means you are carrying a hard block.")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
            }

            WeeklyLoadCard()
        }
    }

    private func legend(color: Color, label: String) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(label).font(.system(size: 11)).foregroundStyle(.secondary)
        }
    }
}

struct WeeklyLoadCard: View {
    @EnvironmentObject private var store: AppStore

    private var weeks: [(String, Double, Double)] {
        var out: [(String, Double, Double)] = []
        var reference = Date()
        for _ in 0..<8 {
            let interval = store.weekInterval(containing: reference)
            let totals = store.totals(for: interval)
            out.append((Fmt.shortDayFormatter.string(from: interval.start),
                        totals.relativeEffort,
                        totals.distance / store.settings.units.metersPerUnit))
            reference = interval.start.addingTimeInterval(-86400)
        }
        return out.reversed()
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Weekly effort")
                BarChart(values: weeks.map(\.1), labels: weeks.map(\.0), color: Theme.accent)
                    .frame(height: 100)
                Text("Relative Effort per week. Ramping this up by more than about 10% a week is where injuries come from.")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Training log

struct TrainingLogSection: View {
    @EnvironmentObject private var store: AppStore
    @State private var metric: LogMetric = .distance
    @State private var selectedDay: Date?

    enum LogMetric: String, CaseIterable, Identifiable {
        case distance, time, effort
        var id: String { rawValue }
        var name: String { rawValue.capitalized }
    }

    private var units: UnitSystem { store.settings.units }

    private var days: [TrainingLogGrid.DayVolume] {
        var cal = Calendar.current
        cal.firstWeekday = store.settings.display.weekStartsMonday ? 2 : 1
        let today = cal.startOfDay(for: Date())
        guard let start = cal.date(byAdding: .day, value: -181, to: today) else { return [] }
        // Pad to the beginning of that week so the grid lines up by weekday.
        let weekStart = cal.dateInterval(of: .weekOfYear, for: start)?.start ?? start

        var byDay: [Date: (Double, Int, SportType)] = [:]
        for activity in store.activities {
            let day = cal.startOfDay(for: activity.startDate)
            guard day >= weekStart else { continue }
            let value: Double
            switch metric {
            case .distance: value = activity.distance / units.metersPerUnit
            case .time: value = activity.movingTime / 3600
            case .effort: value = activity.relativeEffort
            }
            let existing = byDay[day]
            byDay[day] = ((existing?.0 ?? 0) + value, (existing?.1 ?? 0) + 1, activity.sport)
        }

        var out: [TrainingLogGrid.DayVolume] = []
        var day = weekStart
        while day <= today {
            let entry = byDay[day]
            out.append(TrainingLogGrid.DayVolume(date: day,
                                                 value: entry?.0 ?? 0,
                                                 color: entry?.2.accentColor ?? Theme.accent,
                                                 count: entry?.1 ?? 0))
            guard let next = cal.date(byAdding: .day, value: 1, to: day) else { break }
            day = next
        }
        return out
    }

    private var weeks: [[TrainingLogGrid.DayVolume]] {
        stride(from: 0, to: days.count, by: 7).map { index in
            Array(days[index..<Swift.min(index + 7, days.count)])
        }
    }

    private var selectedActivities: [Activity] {
        guard let selectedDay else { return [] }
        let cal = Calendar.current
        return store.activities.filter { cal.isDate($0.startDate, inSameDayAs: selectedDay) }
    }

    var body: some View {
        VStack(spacing: 14) {
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    Picker("Metric", selection: $metric) {
                        ForEach(LogMetric.allCases) { Text($0.name).tag($0) }
                    }
                    .pickerStyle(.segmented)

                    HStack(spacing: 5) {
                        ForEach(weekdayLabels, id: \.self) { label in
                            Text(label)
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundStyle(.tertiary)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    TrainingLogGrid(weeks: weeks,
                                    maxValue: days.map(\.value).max() ?? 1) { day in
                        selectedDay = day
                    }
                    Text("Last 26 weeks. Tap a day to see what you did.")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
            }

            if let selectedDay {
                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: Fmt.dayFormatter.string(from: selectedDay))
                        if selectedActivities.isEmpty {
                            Text("Rest day.").font(.system(size: 13)).foregroundStyle(.secondary)
                        }
                        ForEach(selectedActivities) { activity in
                            NavigationLink(value: activity.id) {
                                HStack {
                                    Image(systemName: activity.sport.symbol)
                                        .foregroundStyle(activity.sport.accentColor)
                                    Text(activity.name).font(.system(size: 13)).lineLimit(1)
                                    Spacer()
                                    Text(Fmt.distanceWithUnit(activity.distance, units))
                                        .font(.system(size: 13, weight: .semibold))
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

            MonthlyTotalsCard()
        }
    }

    private var weekdayLabels: [String] {
        let base = ["M", "T", "W", "T", "F", "S", "S"]
        return store.settings.display.weekStartsMonday ? base : ["S", "M", "T", "W", "T", "F", "S"]
    }
}

struct MonthlyTotalsCard: View {
    @EnvironmentObject private var store: AppStore

    private var units: UnitSystem { store.settings.units }

    private var months: [(String, Totals)] {
        let cal = Calendar.current
        var out: [(String, Totals)] = []
        for offset in 0..<6 {
            guard let date = cal.date(byAdding: .month, value: -offset, to: Date()),
                  let interval = cal.dateInterval(of: .month, for: date) else { continue }
            out.append((Fmt.monthFormatter.string(from: interval.start), store.totals(for: interval)))
        }
        return out
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Monthly totals")
                ForEach(months, id: \.0) { name, totals in
                    HStack {
                        Text(name).font(.system(size: 13))
                        Spacer()
                        Text("\(totals.count)").font(.system(size: 12)).foregroundStyle(.secondary)
                            .frame(width: 26, alignment: .trailing)
                        Text(Fmt.durationCompact(totals.movingTime))
                            .font(.system(size: 12)).foregroundStyle(.secondary)
                            .frame(width: 60, alignment: .trailing)
                        Text(Fmt.distanceWithUnit(totals.distance, units, decimals: 1))
                            .font(.system(size: 13, weight: .semibold))
                            .monospacedDigit()
                            .frame(width: 84, alignment: .trailing)
                    }
                }
            }
        }
    }
}
