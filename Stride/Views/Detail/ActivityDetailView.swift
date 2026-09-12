import SwiftUI
import UIKit

struct ActivityDetailView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State var activity: Activity
    @State private var points: [TrackPoint] = []
    @State private var streams = Streams()
    @State private var splitUnit: SplitUnit = .primary
    @State private var showEdit = false
    @State private var showSegmentCreator = false
    @State private var confirmDelete = false
    @State private var exportURL: URL?
    @State private var showShare = false
    @State private var selectedEffort: BestEffort?

    enum SplitUnit: String, CaseIterable, Identifiable {
        case primary, laps
        var id: String { rawValue }
    }

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                header
                if activity.hasGPS && activity.previewPath.count > 1 {
                    mapCard
                }
                statsCard
                if !activity.notes.isEmpty { notesCard }
                if !elevationSeries.isEmpty { elevationCard }
                if !currentSplits.isEmpty { splitsCard }
                if !streams.isEmpty { analysisCard }
                if hasZones { zonesCard }
                if !activity.bestEfforts.isEmpty { bestEffortsCard }
                if !segmentEfforts.isEmpty { segmentsCard }
                if !activity.laps.isEmpty { lapsCard }
                if !matched.isEmpty { matchedCard }
                if !activity.photos.isEmpty { photosCard }
                actionsCard
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 30)
        }
        .background(Theme.background(scheme))
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { showEdit = true } label: { Label("Edit", systemImage: "pencil") }
                    Menu {
                        Button("GPX") { export(as: .gpx) }
                        Button("TCX") { export(as: .tcx) }
                        Button("FIT") { export(as: .fit) }
                    } label: {
                        Label("Export", systemImage: "square.and.arrow.up")
                    }
                    if activity.hasGPS {
                        Button { showSegmentCreator = true } label: {
                            Label("Create segment", systemImage: "flag")
                        }
                        Button { saveAsRoute() } label: {
                            Label("Save as route", systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                        }
                    }
                    Divider()
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Delete", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showEdit) {
            NavigationStack { EditActivityView(activity: $activity) }
        }
        .sheet(isPresented: $showSegmentCreator) {
            NavigationStack {
                SegmentCreatorView(activity: activity, points: points)
            }
        }
        .sheet(isPresented: $showShare) {
            if let exportURL {
                ShareSheet(items: [exportURL])
            }
        }
        .confirmationDialog("Delete this activity?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                store.delete(activity)
                dismiss()
            }
        }
        .onAppear(perform: loadTrack)
        .onChange(of: activity) { store.update(activity) }
    }

    // MARK: - Loading

    private func loadTrack() {
        guard points.isEmpty else { return }
        let loaded = store.track(for: activity)
        points = loaded
        streams = Streams(loaded)
    }

    // MARK: - Cards

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: activity.sport.symbol)
                    .foregroundStyle(activity.sport.accentColor)
                Text(activity.sport.name)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary)
                if activity.isCommute { Pill(text: "Commute", color: .secondary) }
                if activity.isManual { Pill(text: "Manual", color: .secondary) }
            }
            Text(activity.name)
                .font(.system(size: 24, weight: .bold))
            Text(Fmt.dayFormatter.string(from: activity.startDate) + " · " + Fmt.timeFormatter.string(from: activity.startDate))
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 4)
    }

    private var mapCard: some View {
        RouteMap(path: activity.previewPath,
                 highlight: highlightPath,
                 color: activity.sport.accentColor,
                 style: MapStyleOption(rawValue: store.settings.display.mapStyle) ?? .standard)
            .frame(height: 260)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var highlightPath: [Coord] {
        guard let effort = selectedEffort, !streams.isEmpty else { return [] }
        let lower = Swift.max(0, Swift.min(streams.count - 1, effort.startIndex))
        let upper = Swift.max(lower, Swift.min(streams.count - 1, effort.endIndex))
        return Array(streams.coords[lower...upper])
    }

    private var statsCard: some View {
        Card {
            let columns = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]
            LazyVGrid(columns: columns, spacing: 18) {
                StatTile(label: "Distance", value: Fmt.distance(activity.distance, units),
                         unit: units.distanceUnit, size: 21, alignment: .center)
                StatTile(label: activity.sport.usesPace ? "Avg pace" : "Avg speed",
                         value: activity.sport.usesPace
                            ? Fmt.pace(distance: activity.distance, time: activity.movingTime, units)
                            : Fmt.avgSpeed(distance: activity.distance, time: activity.movingTime, units),
                         size: 21, alignment: .center)
                StatTile(label: "Moving time", value: Fmt.duration(activity.movingTime),
                         size: 21, alignment: .center)
                StatTile(label: "Elev gain", value: Fmt.elevation(activity.elevationGain, units),
                         unit: units.elevationUnit, size: 21, alignment: .center)
                StatTile(label: "Calories", value: Fmt.int(activity.calories), size: 21, alignment: .center)
                StatTile(label: "Relative effort", value: Fmt.int(activity.relativeEffort),
                         size: 21, color: Theme.accent, alignment: .center)
                if let hr = activity.avgHR {
                    StatTile(label: "Avg HR", value: "\(Int(hr))", unit: "bpm", size: 21, alignment: .center)
                }
                if let maxHR = activity.maxHR {
                    StatTile(label: "Max HR", value: "\(Int(maxHR))", unit: "bpm", size: 21, alignment: .center)
                }
                if let power = activity.avgPower {
                    StatTile(label: "Avg power", value: "\(Int(power))", unit: "W", size: 21, alignment: .center)
                }
                if let np = activity.normalizedPower {
                    StatTile(label: "Normalized", value: "\(Int(np))", unit: "W", size: 21, alignment: .center)
                }
                if let cadence = activity.avgCadence {
                    StatTile(label: "Avg cadence", value: "\(Int(cadence))",
                             unit: activity.sport.isRide ? "rpm" : "spm", size: 21, alignment: .center)
                }
                if activity.maxSpeed > 0 {
                    StatTile(label: "Max speed", value: Fmt.speed(activity.maxSpeed, units),
                             unit: units.speedUnit, size: 21, alignment: .center)
                }
                StatTile(label: "Elapsed", value: Fmt.duration(activity.elapsed), size: 21, alignment: .center)
                if activity.elevationLoss > 5 {
                    StatTile(label: "Elev loss", value: Fmt.elevation(activity.elevationLoss, units),
                             unit: units.elevationUnit, size: 21, alignment: .center)
                }
                if let gear = store.gear.first(where: { $0.id == activity.gearID }) {
                    StatTile(label: "Gear", value: gear.displayName, size: 15, alignment: .center)
                }
            }
        }
    }

    private var notesCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 6) {
                SectionHeader(title: "Notes")
                Text(activity.notes).font(.system(size: 14))
            }
        }
    }

    private var elevationSeries: [Double] {
        streams.isEmpty ? [] : Smooth.movingAverage(streams.altitude, window: 15)
    }

    private var elevationCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Elevation")
                LineChart(values: elevationSeries, color: Theme.hikeColor, fill: true,
                          highlight: selectedEffort.map { $0.startIndex...$0.endIndex })
                    .frame(height: 110)
                HStack {
                    Text("\(Fmt.elevation(activity.minElevation, units)) \(units.elevationUnit)")
                    Spacer()
                    Text("+\(Fmt.elevation(activity.elevationGain, units)) / -\(Fmt.elevation(activity.elevationLoss, units)) \(units.elevationUnit)")
                    Spacer()
                    Text("\(Fmt.elevation(activity.maxElevation, units)) \(units.elevationUnit)")
                }
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
            }
        }
    }

    private var currentSplits: [Split] {
        units == .metric ? activity.splitsKm : activity.splitsMi
    }

    private var splitsCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Splits (per \(units.distanceUnit))")
                SplitsTable(splits: currentSplits, sport: activity.sport, units: units)
            }
        }
    }

    private var analysisCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader(title: "Analysis")
                if streams.hasHeartRate {
                    streamRow(title: "Heart rate",
                              values: streams.heartRate.map { $0 ?? 0 },
                              color: Theme.negative,
                              caption: "\(Fmt.int(activity.avgHR)) avg · \(Fmt.int(activity.maxHR)) max bpm")
                }
                streamRow(title: activity.sport.usesPace ? "Pace" : "Speed",
                          values: paceSeries,
                          color: activity.sport.accentColor,
                          caption: activity.paceOrSpeedString(units) + " average")
                if streams.hasPower {
                    streamRow(title: "Power",
                              values: streams.power.map { $0 ?? 0 },
                              color: Theme.warning,
                              caption: "\(Fmt.int(activity.avgPower)) W avg · \(Fmt.int(activity.normalizedPower)) W normalized")
                }
                if streams.hasCadence {
                    streamRow(title: "Cadence",
                              values: streams.cadence.map { $0 ?? 0 },
                              color: Theme.rideColor,
                              caption: "\(Fmt.int(activity.avgCadence)) average")
                }
                gradeRow
            }
        }
    }

    /// Speed reads better than raw pace on a chart, because a stop sends pace to infinity.
    private var paceSeries: [Double] {
        Smooth.movingAverage(streams.speed.map { Swift.min($0, activity.sport.maxPlausibleSpeed) }, window: 9)
    }

    private func streamRow(title: String, values: [Double], color: Color, caption: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(.system(size: 13, weight: .semibold))
                Spacer()
                Text(caption).font(.system(size: 11)).foregroundStyle(.secondary)
            }
            LineChart(values: values, color: color, fill: true, lineWidth: 1.5,
                      highlight: selectedEffort.map { $0.startIndex...$0.endIndex })
                .frame(height: 66)
        }
    }

    private var gradeRow: some View {
        let grades = streams.grade().map { $0 * 100 }
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Gradient").font(.system(size: 13, weight: .semibold))
                Spacer()
                Text(String(format: "%.1f%% average climb", activity.distance > 0 ? activity.elevationGain / activity.distance * 100 : 0))
                    .font(.system(size: 11)).foregroundStyle(.secondary)
            }
            LineChart(values: grades, color: Theme.hikeColor, lineWidth: 1.2)
                .frame(height: 50)
        }
    }

    private var hasZones: Bool {
        activity.hrZoneTimes.reduce(0, +) > 0 || activity.powerZoneTimes.reduce(0, +) > 0
    }

    private var zonesCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                if activity.hrZoneTimes.reduce(0, +) > 0 {
                    SectionHeader(title: "Heart rate zones")
                    ZoneBars(times: activity.hrZoneTimes,
                             names: HeartRateZones.names,
                             shortNames: HeartRateZones.shortNames,
                             colors: Theme.zoneColors,
                             ranges: (0..<5).map { store.settings.profile.hrZones.range($0) },
                             unit: "bpm")
                }
                if activity.powerZoneTimes.reduce(0, +) > 0 {
                    SectionHeader(title: "Power zones")
                    ZoneBars(times: activity.powerZoneTimes,
                             names: PowerZones.names,
                             shortNames: PowerZones.shortNames,
                             colors: Theme.powerZoneColors,
                             ranges: (0..<store.settings.profile.powerZones.bounds.count)
                                .map { store.settings.profile.powerZones.range($0) },
                             unit: "W")
                }
            }
        }
    }

    private var bestEffortsCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Best efforts")
                ForEach(activity.bestEfforts) { effort in
                    Button {
                        selectedEffort = selectedEffort?.id == effort.id ? nil : effort
                    } label: {
                        HStack {
                            Text(effort.name)
                                .font(.system(size: 14, weight: selectedEffort?.id == effort.id ? .bold : .regular))
                            if isAllTimeBest(effort) { Pill(text: "PR", color: Theme.warning) }
                            Spacer()
                            Text(Fmt.paceFromSeconds(effort.paceSecondsPerKm * units.metersPerUnit / 1000) + " " + units.paceUnit)
                                .font(.system(size: 12))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                            Text(Fmt.duration(effort.time))
                                .font(.system(size: 14, weight: .semibold))
                                .monospacedDigit()
                                .frame(width: 66, alignment: .trailing)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if selectedEffort != nil {
                    Text("Tap again to clear the highlight.")
                        .font(.system(size: 11))
                        .foregroundStyle(.tertiary)
                }
            }
        }
    }

    private func isAllTimeBest(_ effort: BestEffort) -> Bool {
        let records = store.personalRecords(sport: activity.sport)
        guard let record = records.first(where: { $0.0.name == effort.name }) else { return false }
        return record.1.id == activity.id
    }

    private var segmentEfforts: [(Segment, SegmentEffort)] {
        store.segmentEfforts
            .filter { $0.activityID == activity.id }
            .compactMap { effort -> (Segment, SegmentEffort)? in
                guard let segment = store.segments.first(where: { $0.id == effort.segmentID }) else { return nil }
                return (segment, effort)
            }
    }

    private var segmentsCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Segments")
                ForEach(segmentEfforts, id: \.1.id) { segment, effort in
                    NavigationLink {
                        SegmentDetailView(segment: segment)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(segment.name).font(.system(size: 14)).lineLimit(1)
                                Text(Fmt.distanceWithUnit(segment.distance, units, decimals: 2)
                                     + " · " + String(format: "%.1f%%", segment.averageGrade * 100))
                                    .font(.system(size: 11))
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if effort.isPR { Pill(text: "PR", color: Theme.warning) }
                            else if effort.rank > 0 { Pill(text: "#\(effort.rank)", color: .secondary) }
                            Text(Fmt.duration(effort.time))
                                .font(.system(size: 14, weight: .semibold))
                                .monospacedDigit()
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var lapsCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Laps")
                ForEach(activity.laps) { lap in
                    HStack {
                        Text("\(lap.index)").font(.system(size: 13, weight: .semibold)).frame(width: 24, alignment: .leading)
                        Text(Fmt.distanceWithUnit(lap.distance, units)).font(.system(size: 13)).foregroundStyle(.secondary)
                        Spacer()
                        if let hr = lap.avgHR {
                            Text("\(Int(hr)) bpm").font(.system(size: 12)).foregroundStyle(.secondary)
                        }
                        Text(Fmt.duration(lap.moving)).font(.system(size: 14, weight: .semibold)).monospacedDigit()
                    }
                }
            }
        }
    }

    private var matched: [Activity] {
        store.matchedActivities(for: activity)
    }

    private var matchedCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Same route")
                Text("You have covered this route \(matched.count + 1) times.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                ForEach(matched.prefix(6)) { other in
                    HStack {
                        Text(Fmt.shortDayFormatter.string(from: other.startDate))
                            .font(.system(size: 13))
                        Spacer()
                        if other.movingTime < activity.movingTime {
                            Text(Fmt.signed(other.movingTime - activity.movingTime, decimals: 0) + "s")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(Theme.positive)
                        } else {
                            Text(Fmt.signed(other.movingTime - activity.movingTime, decimals: 0) + "s")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(Theme.negative)
                        }
                        Text(Fmt.duration(other.movingTime))
                            .font(.system(size: 14, weight: .semibold))
                            .monospacedDigit()
                            .frame(width: 70, alignment: .trailing)
                    }
                }
            }
        }
    }

    private var photosCard: some View {
        Card(padding: 12) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(activity.photos) { photo in
                        if let image = UIImage(contentsOfFile: Store.photoURL(photo.filename).path) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 130, height: 130)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                }
            }
        }
    }

    private var actionsCard: some View {
        Card {
            VStack(spacing: 12) {
                if activity.hasGPS {
                    Button {
                        showSegmentCreator = true
                    } label: {
                        Label("Create a segment from this activity", systemImage: "flag")
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Divider()
                }
                Menu {
                    Button("GPX") { export(as: .gpx) }
                    Button("TCX") { export(as: .tcx) }
                    Button("FIT") { export(as: .fit) }
                } label: {
                    Label("Export this activity", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .font(.system(size: 14))
        }
    }

    // MARK: - Actions

    private func export(as format: ActivityFile.Format) {
        let safeName = activity.name.replacingOccurrences(of: "/", with: "-")
        let filename = "\(safeName)-\(Int(activity.startDate.timeIntervalSince1970)).\(format.rawValue)"

        let url: URL?
        switch format {
        case .gpx:
            url = Exporter.write(Exporter.gpx(activity: activity, points: points), filename: filename)
        case .tcx:
            url = Exporter.write(Exporter.tcx(activity: activity, points: points), filename: filename)
        case .fit:
            url = Exporter.write(Exporter.fit(activity: activity, points: points), filename: filename)
        }
        if let url {
            exportURL = url
            showShare = true
        }
    }

    private func saveAsRoute() {
        let path = activity.previewPath
        guard path.count > 1 else { return }
        let elevations = Geo.downsample(streams.altitude.map { $0 }, limit: path.count)
        let route = SavedRoute(name: activity.name,
                               sport: activity.sport,
                               path: path,
                               elevations: elevations,
                               distance: activity.distance,
                               elevationGain: activity.elevationGain,
                               sourceActivityID: activity.id)
        store.addRoute(route)
    }
}

/// Wraps UIActivityViewController so exported files can go anywhere.
struct ShareSheet: UIViewControllerRepresentable {
    var items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct EditActivityView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @Binding var activity: Activity

    private var gearBinding: Binding<UUID> {
        Binding(
            get: { activity.gearID ?? UUID() },
            set: { newValue in
                let exists = store.gear.contains { $0.id == newValue }
                activity.gearID = exists ? newValue : nil
            })
    }

    var body: some View {
        Form {
            Section("Details") {
                TextField("Name", text: $activity.name)
                Picker("Sport", selection: $activity.sport) {
                    ForEach(SportType.allCases) { Text($0.name).tag($0) }
                }
                TextField("Notes", text: $activity.notes, axis: .vertical).lineLimit(3...8)
            }
            Section("Gear") {
                Picker("Gear", selection: gearBinding) {
                    Text("None").tag(UUID())
                    ForEach(store.gear.filter { $0.kind == activity.sport.gearKind }) { item in
                        Text(item.displayName).tag(item.id)
                    }
                }
            }
            Section {
                Toggle("Commute", isOn: $activity.isCommute)
                Toggle("Private", isOn: $activity.isPrivate)
            }
        }
        .navigationTitle("Edit")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") {
                    store.update(activity)
                    dismiss()
                }
                .fontWeight(.semibold)
            }
        }
    }
}
