import SwiftUI
import PhotosUI

struct SaveActivityView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var session: RecordingSession
    @EnvironmentObject private var health: HealthKitManager
    @Environment(\.dismiss) private var dismiss

    @State var pending: PendingSave
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var isSaving = false
    @State private var savingNote: String = ""

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        NavigationStack {
            Form {
                if pending.activity.hasGPS {
                    Section {
                        RouteMap(path: pending.activity.previewPath,
                                 interactive: false,
                                 color: pending.activity.sport.accentColor)
                            .frame(height: 170)
                            .listRowInsets(EdgeInsets())
                    }
                }

                Section {
                    summaryGrid
                }

                if isSaving && !savingNote.isEmpty {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView().controlSize(.small)
                            Text(savingNote).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }

                Section("Details") {
                    TextField("Activity name", text: $pending.activity.name)
                    Picker("Sport", selection: $pending.activity.sport) {
                        ForEach(SportType.allCases) { Text($0.name).tag($0) }
                    }
                    TextField("How did it go?", text: $pending.activity.notes, axis: .vertical)
                        .lineLimit(3...6)
                }

                Section("Effort") {
                    Picker("Perceived exertion", selection: exertionBinding) {
                        Text("Not set").tag(0)
                        Text("1 — Easy").tag(1)
                        Text("2").tag(2)
                        Text("3 — Moderate").tag(3)
                        Text("4").tag(4)
                        Text("5 — Hard").tag(5)
                        Text("6").tag(6)
                        Text("7 — Max effort").tag(7)
                    }
                    LabeledContent("Relative Effort", value: "\(Int(pending.activity.relativeEffort))")
                    LabeledContent("Training load", value: "\(Int(pending.activity.trainingLoad))")
                }

                if !relevantGear.isEmpty {
                    Section("Gear") {
                        Picker("Gear", selection: gearBinding) {
                            Text("None").tag(UUID())
                            ForEach(relevantGear) { item in
                                Text(item.displayName).tag(item.id)
                            }
                        }
                    }
                }

                Section("Photos") {
                    PhotosPicker(selection: $photoItems, maxSelectionCount: 8, matching: .images) {
                        Label("Add photos", systemImage: "photo.on.rectangle")
                    }
                    if !pending.activity.photos.isEmpty {
                        Text("\(pending.activity.photos.count) photo\(pending.activity.photos.count == 1 ? "" : "s") attached")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    Toggle("Commute", isOn: $pending.activity.isCommute)
                    Toggle("Keep private", isOn: $pending.activity.isPrivate)
                }

                if !matchedSegments.isEmpty {
                    Section("Segments on this activity") {
                        ForEach(matchedSegments, id: \.0.id) { segment, effort in
                            HStack {
                                Text(segment.name).lineLimit(1)
                                Spacer()
                                if effort.isPR { Pill(text: "PR", color: Theme.warning) }
                                Text(Fmt.duration(effort.time))
                                    .monospacedDigit()
                                    .font(.system(size: 14, weight: .semibold))
                            }
                        }
                    }
                }
            }
            .navigationTitle("Save Activity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Discard", role: .destructive) {
                        session.discard()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { save() }
                        .fontWeight(.semibold)
                        .disabled(isSaving)
                }
            }
            .onChange(of: photoItems) { loadPhotos() }
        }
        .interactiveDismissDisabled(true)
    }

    private var summaryGrid: some View {
        let columns = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]
        return LazyVGrid(columns: columns, spacing: 16) {
            StatTile(label: "Distance",
                     value: Fmt.distance(pending.activity.distance, units),
                     unit: units.distanceUnit, size: 20, alignment: .center)
            StatTile(label: "Moving", value: Fmt.duration(pending.activity.movingTime),
                     size: 20, alignment: .center)
            StatTile(label: pending.activity.sport.usesPace ? "Pace" : "Speed",
                     value: pending.activity.sport.usesPace
                        ? Fmt.pace(distance: pending.activity.distance, time: pending.activity.movingTime, units)
                        : Fmt.avgSpeed(distance: pending.activity.distance, time: pending.activity.movingTime, units),
                     size: 20, alignment: .center)
            StatTile(label: "Elev gain",
                     value: Fmt.elevation(pending.activity.elevationGain, units),
                     unit: units.elevationUnit, size: 20, alignment: .center)
            StatTile(label: "Calories", value: Fmt.int(pending.activity.calories),
                     size: 20, alignment: .center)
            StatTile(label: "Avg HR",
                     value: pending.activity.avgHR.map { "\(Int($0))" } ?? "--",
                     size: 20, alignment: .center)
        }
        .padding(.vertical, 4)
    }

    private var exertionBinding: Binding<Int> {
        Binding(get: { pending.activity.perceivedExertion ?? 0 },
                set: { pending.activity.perceivedExertion = $0 == 0 ? nil : $0 })
    }

    private var gearBinding: Binding<UUID> {
        Binding(get: { pending.activity.gearID ?? UUID() },
                set: { newValue in
                    pending.activity.gearID = relevantGear.contains(where: { $0.id == newValue }) ? newValue : nil
                })
    }

    private var relevantGear: [Gear] {
        store.gear.filter { $0.kind == pending.activity.sport.gearKind && !$0.isRetired }
    }

    private var matchedSegments: [(Segment, SegmentEffort)] {
        let efforts = SegmentMatcher.findEfforts(activity: pending.activity,
                                                 activityID: pending.activity.id,
                                                 points: pending.points,
                                                 segments: store.segments)
        return efforts.compactMap { effort -> (Segment, SegmentEffort)? in
            guard let segment = store.segments.first(where: { $0.id == effort.segmentID }) else { return nil }
            var e = effort
            let existing = store.efforts(for: segment)
            e.isPR = existing.isEmpty || effort.time < (existing.first?.time ?? .greatestFiniteMagnitude)
            return (segment, e)
        }
    }

    private func loadPhotos() {
        for item in photoItems {
            item.loadTransferable(type: Data.self) { result in
                guard case .success(let data) = result, let data else { return }
                guard let filename = Store.savePhoto(data) else { return }
                DispatchQueue.main.async {
                    pending.activity.photos.append(ActivityPhoto(filename: filename))
                }
            }
        }
    }

    private func save() {
        isSaving = true

        // Read the whole window back from Health first. A watch syncs its samples
        // across in bursts, so what arrived live is usually incomplete — this
        // picks up everything it actually measured.
        guard store.settings.display.readFromAppleHealth,
              store.settings.display.backfillHeartRateFromHealth,
              HealthKitManager.isAvailable,
              pending.points.count > 1 else {
            finishSaving(with: pending.points)
            return
        }

        savingNote = "Reading heart rate from Apple Health…"
        let activity = pending.activity
        health.heartRateSamples(from: activity.startDate.addingTimeInterval(-30),
                                to: activity.endDate.addingTimeInterval(30)) { samples in
            guard !samples.isEmpty else {
                finishSaving(with: pending.points)
                return
            }
            let result = HealthKitManager.applyHeartRate(samples,
                                                         to: pending.points,
                                                         activityStart: activity.startDate)
            finishSaving(with: result.points)
        }
    }

    private func finishSaving(with points: [TrackPoint]) {
        // Heart rate may have changed, so every derived figure is recomputed:
        // zones, Relative Effort, calories and training load all depend on it.
        var activity = ActivityAnalyzer.analyse(activity: pending.activity,
                                                points: points,
                                                profile: store.settings.profile)
        activity.name = pending.activity.name
        activity.notes = pending.activity.notes
        activity.gearID = pending.activity.gearID
        activity.photos = pending.activity.photos
        activity.isCommute = pending.activity.isCommute
        activity.isPrivate = pending.activity.isPrivate
        activity.perceivedExertion = pending.activity.perceivedExertion

        store.add(activity, points: points)
        if store.settings.display.writeToAppleHealth {
            health.save(activity: activity, points: points) { _ in }
        }
        session.discard()
        isSaving = false
        dismiss()
    }
}

/// Log a treadmill session, a turbo session, or anything else recorded elsewhere.
struct ManualActivityView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var sport: SportType = .run
    @State private var date = Date()
    @State private var distanceValue: Double = 5
    @State private var hours: Int = 0
    @State private var minutes: Int = 30
    @State private var seconds: Int = 0
    @State private var elevationValue: Double = 0
    @State private var avgHR: Double = 0
    @State private var notes = ""

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        Form {
            Section("Activity") {
                TextField("Name", text: $name)
                Picker("Sport", selection: $sport) {
                    ForEach(SportType.allCases) { Text($0.name).tag($0) }
                }
                DatePicker("Date", selection: $date)
            }
            Section("Numbers") {
                HStack {
                    Text("Distance")
                    Spacer()
                    TextField("0", value: $distanceValue, format: .number)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 90)
                    Text(units.distanceUnit).foregroundStyle(.secondary)
                }
                HStack {
                    Text("Duration")
                    Spacer()
                    Picker("h", selection: $hours) {
                        ForEach(0..<24, id: \.self) { Text("\($0)h").tag($0) }
                    }
                    .pickerStyle(.menu)
                    Picker("m", selection: $minutes) {
                        ForEach(0..<60, id: \.self) { Text("\($0)m").tag($0) }
                    }
                    .pickerStyle(.menu)
                    Picker("s", selection: $seconds) {
                        ForEach(0..<60, id: \.self) { Text("\($0)s").tag($0) }
                    }
                    .pickerStyle(.menu)
                }
                HStack {
                    Text("Elevation gain")
                    Spacer()
                    TextField("0", value: $elevationValue, format: .number)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                    Text(units.elevationUnit).foregroundStyle(.secondary)
                }
                HStack {
                    Text("Average heart rate")
                    Spacer()
                    TextField("0", value: $avgHR, format: .number)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                    Text("bpm").foregroundStyle(.secondary)
                }
            }
            Section("Notes") {
                TextField("Notes", text: $notes, axis: .vertical).lineLimit(3...6)
            }
        }
        .navigationTitle("Manual Activity")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") { save() }
                    .fontWeight(.semibold)
                    .disabled(duration <= 0)
            }
        }
    }

    private var duration: TimeInterval {
        TimeInterval(hours * 3600 + minutes * 60 + seconds)
    }

    private func save() {
        var activity = Activity(name: name.isEmpty ? Activity.defaultName(for: sport, at: date) : name,
                                sport: sport, startDate: date)
        activity.isManual = true
        activity.hasGPS = false
        activity.distance = distanceValue * units.metersPerUnit
        activity.movingTime = duration
        activity.elapsed = duration
        activity.elevationGain = elevationValue * units.metersPerElevationUnit
        activity.notes = notes
        if avgHR > 30 {
            activity.avgHR = avgHR
            // Without a stream we can only place the whole session in one zone.
            let zone = store.settings.profile.hrZones.zone(for: avgHR)
            var times = [TimeInterval](repeating: 0, count: 5)
            times[zone] = duration
            activity.hrZoneTimes = times
        }
        activity.calories = EffortScore.calories(profile: store.settings.profile, sport: sport,
                                                 movingTime: duration,
                                                 avgHR: avgHR > 30 ? avgHR : nil,
                                                 avgSpeed: duration > 0 ? activity.distance / duration : 0)
        activity.relativeEffort = activity.hrZoneTimes.isEmpty
            ? EffortScore.estimatedEffort(movingTime: duration,
                                          gradeAdjustedPace: activity.avgPaceSecondsPerKm,
                                          thresholdPace: store.settings.profile.paceZones.thresholdPace)
            : EffortScore.relativeEffort(zoneSeconds: activity.hrZoneTimes)
        activity.trainingLoad = activity.relativeEffort
        store.add(activity, points: [])
        dismiss()
    }
}
