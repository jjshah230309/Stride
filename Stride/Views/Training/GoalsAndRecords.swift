import SwiftUI

// MARK: - Goals

struct GoalsSection: View {
    @EnvironmentObject private var store: AppStore
    @State private var showEditor = false

    var body: some View {
        VStack(spacing: 14) {
            if store.goals.isEmpty {
                EmptyStateView(symbol: "target",
                               title: "No goals set",
                               message: "Set a weekly, monthly or yearly target for distance, time, elevation or number of activities.",
                               actionTitle: "Add a goal") { showEditor = true }
            } else {
                ForEach(store.goals) { goal in
                    Card {
                        GoalProgressRow(goal: goal, compact: false)
                    }
                    .contextMenu {
                        Button(role: .destructive) { store.deleteGoal(goal) } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
                Button {
                    showEditor = true
                } label: {
                    Label("Add a goal", systemImage: "plus.circle")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.bordered)
            }
            StreakCard()
        }
        .sheet(isPresented: $showEditor) {
            NavigationStack { GoalEditorView() }
        }
    }
}

struct GoalProgressRow: View {
    @EnvironmentObject private var store: AppStore
    var goal: Goal
    var compact: Bool

    private var units: UnitSystem { store.settings.units }

    private var progress: Double {
        goal.progress(from: store.activities)
    }

    private var fraction: Double {
        goal.target > 0 ? progress / goal.target : 0
    }

    /// Where you should be by now if you finish exactly on target.
    private var expectedFraction: Double {
        let cal = Calendar.current
        let interval: DateInterval?
        switch goal.period {
        case .weekly: interval = cal.dateInterval(of: .weekOfYear, for: Date())
        case .monthly: interval = cal.dateInterval(of: .month, for: Date())
        case .yearly: interval = cal.dateInterval(of: .year, for: Date())
        }
        guard let range = interval, range.duration > 0 else { return 0 }
        return Swift.max(0, Swift.min(1, Date().timeIntervalSince(range.start) / range.duration))
    }

    private var isAhead: Bool { fraction >= expectedFraction }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(goal.title)
                    .font(.system(size: compact ? 12 : 14, weight: .semibold))
                    .lineLimit(1)
                Spacer()
                Text(Fmt.percent(Swift.min(1, fraction)))
                    .font(.system(size: compact ? 12 : 14, weight: .bold))
                    .foregroundStyle(fraction >= 1 ? Theme.positive : (isAhead ? Theme.accent : .secondary))
            }
            ProgressBar(progress: fraction,
                        color: fraction >= 1 ? Theme.positive : Theme.accent,
                        height: compact ? 6 : 9)
            HStack {
                Text(valueText(progress) + " of " + valueText(goal.target))
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                Spacer()
                if !compact {
                    Text(isAhead ? "On track" : "Behind pace")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(isAhead ? Theme.positive : Theme.warning)
                }
            }
        }
    }

    private func valueText(_ value: Double) -> String {
        switch goal.metric {
        case .distance: return Fmt.distanceWithUnit(value, units, decimals: 1)
        case .time: return Fmt.durationCompact(value)
        case .elevation: return Fmt.elevationWithUnit(value, units)
        case .activities: return "\(Int(value))"
        }
    }
}

struct GoalEditorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    @State private var metric: GoalMetric = .distance
    @State private var period: GoalPeriod = .weekly
    @State private var sports: Set<SportType> = []
    @State private var targetValue: Double = 30

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        Form {
            Section("Goal") {
                Picker("Metric", selection: $metric) {
                    ForEach(GoalMetric.allCases) { Text($0.name).tag($0) }
                }
                Picker("Period", selection: $period) {
                    ForEach(GoalPeriod.allCases) { Text($0.name).tag($0) }
                }
            }
            Section("Target") {
                HStack {
                    TextField("Target", value: $targetValue, format: .number)
                        .keyboardType(.decimalPad)
                    Text(unitLabel).foregroundStyle(.secondary)
                }
            }
            Section {
                ForEach(SportType.allCases) { sport in
                    Button {
                        if sports.contains(sport) { sports.remove(sport) } else { sports.insert(sport) }
                    } label: {
                        HStack {
                            Label(sport.name, systemImage: sport.symbol)
                            Spacer()
                            if sports.contains(sport) {
                                Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Sports")
            } footer: {
                Text(sports.isEmpty ? "Nothing selected means every sport counts." : "")
            }
        }
        .navigationTitle("New Goal")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Add") { save() }.fontWeight(.semibold).disabled(targetValue <= 0)
            }
        }
    }

    private var unitLabel: String {
        switch metric {
        case .distance: return units.distanceUnit
        case .time: return "hours"
        case .elevation: return units.elevationUnit
        case .activities: return "activities"
        }
    }

    private func save() {
        let target: Double
        switch metric {
        case .distance: target = targetValue * units.metersPerUnit
        case .time: target = targetValue * 3600
        case .elevation: target = targetValue * units.metersPerElevationUnit
        case .activities: target = targetValue
        }
        store.addGoal(Goal(metric: metric, period: period,
                           sports: Array(sports), target: target))
        dismiss()
    }
}

struct StreakCard: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        Card {
            HStack(spacing: 0) {
                StatTile(label: "Day streak", value: "\(store.currentStreak)", size: 24, alignment: .center)
                StatTile(label: "Week streak", value: "\(store.weekStreak)", size: 24, alignment: .center)
                StatTile(label: "All activities", value: "\(store.activities.count)", size: 24, alignment: .center)
            }
        }
    }
}

// MARK: - Records

struct RecordsSection: View {
    @EnvironmentObject private var store: AppStore
    @State private var showRunRecords = true

    private var units: UnitSystem { store.settings.units }

    private var runRecords: [(BestEffort, Activity)] {
        store.personalRecords(sport: .run)
    }

    private var powerCurve: [PowerCurvePoint] {
        store.allTimePowerCurve
    }

    var body: some View {
        VStack(spacing: 14) {
            AllTimeTotalsCard()

            if !runRecords.isEmpty {
                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: "Personal records")
                        ForEach(runRecords, id: \.0.id) { effort, activity in
                            NavigationLink(value: activity.id) {
                                HStack {
                                    Text(effort.name).font(.system(size: 14, weight: .semibold))
                                    Spacer()
                                    Text(Fmt.shortDayFormatter.string(from: activity.startDate))
                                        .font(.system(size: 11))
                                        .foregroundStyle(.secondary)
                                    Text(Fmt.duration(effort.time))
                                        .font(.system(size: 15, weight: .bold))
                                        .monospacedDigit()
                                        .frame(width: 76, alignment: .trailing)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                RacePredictionCard(records: runRecords)
            }

            if !powerCurve.isEmpty {
                Card {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionHeader(title: "Power curve")
                        PowerCurveChart(points: powerCurve)
                            .frame(height: 130)
                        HStack {
                            Text("5s").font(.system(size: 10)).foregroundStyle(.secondary)
                            Spacer()
                            Text("1m").font(.system(size: 10)).foregroundStyle(.secondary)
                            Spacer()
                            Text("20m").font(.system(size: 10)).foregroundStyle(.secondary)
                            Spacer()
                            Text("1h+").font(.system(size: 10)).foregroundStyle(.secondary)
                        }
                        if let ftp = PowerCurveBuilder.estimateFTP(from: powerCurve) {
                            Divider()
                            HStack {
                                Text("Estimated FTP").font(.system(size: 13))
                                Spacer()
                                Text("\(Int(ftp)) W")
                                    .font(.system(size: 15, weight: .bold))
                                Button("Use") {
                                    store.settings.profile.powerZones.ftp = ftp
                                }
                                .font(.system(size: 12, weight: .semibold))
                            }
                            Text("Estimated from your best twenty-minute effort. Power on rides without a meter is modelled from speed, gradient and your weight.")
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }
}

struct AllTimeTotalsCard: View {
    @EnvironmentObject private var store: AppStore

    private var units: UnitSystem { store.settings.units }
    private var totals: Totals { Totals.of(store.activities) }

    private var yearTotals: Totals {
        guard let interval = Calendar.current.dateInterval(of: .year, for: Date()) else { return Totals() }
        return store.totals(for: interval)
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader(title: "This year")
                HStack(spacing: 0) {
                    StatTile(label: "Distance", value: Fmt.distance(yearTotals.distance, units, decimals: 0),
                             unit: units.distanceUnit, size: 22, alignment: .center)
                    StatTile(label: "Time", value: Fmt.durationCompact(yearTotals.movingTime),
                             size: 22, alignment: .center)
                    StatTile(label: "Elevation", value: Fmt.elevation(yearTotals.elevationGain, units),
                             unit: units.elevationUnit, size: 22, alignment: .center)
                }
                Divider()
                SectionHeader(title: "All time")
                HStack(spacing: 0) {
                    StatTile(label: "Distance", value: Fmt.distance(totals.distance, units, decimals: 0),
                             unit: units.distanceUnit, size: 22, alignment: .center)
                    StatTile(label: "Time", value: Fmt.durationCompact(totals.movingTime),
                             size: 22, alignment: .center)
                    StatTile(label: "Activities", value: "\(totals.count)", size: 22, alignment: .center)
                }
            }
        }
    }
}

/// Riegel predictions from your single best recent effort.
struct RacePredictionCard: View {
    var records: [(BestEffort, Activity)]

    private var anchor: (BestEffort, Activity)? {
        // Prefer a long, recent effort: those predict better than a fast 400.
        records
            .filter { $0.0.distance >= 3000 }
            .max { a, b in a.0.distance < b.0.distance }
            ?? records.first
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Race predictions")
                if let anchor {
                    Text("Based on your \(anchor.0.name) in \(Fmt.duration(anchor.0.time))")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                    ForEach(RacePredictor.standardRaces, id: \.0) { name, distance in
                        HStack {
                            Text(name).font(.system(size: 13))
                            Spacer()
                            Text(Fmt.duration(RacePredictor.predict(fromDistance: anchor.0.distance,
                                                                    time: anchor.0.time,
                                                                    toDistance: distance)))
                                .font(.system(size: 14, weight: .semibold))
                                .monospacedDigit()
                        }
                    }
                    Text("Riegel's formula, which assumes you have trained for the distance. Treat the marathon number with suspicion unless you have done the long runs.")
                        .font(.system(size: 10))
                        .foregroundStyle(.tertiary)
                } else {
                    Text("Record a timed effort of a kilometre or more to see predictions.")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}
