import SwiftUI
import UniformTypeIdentifiers

// MARK: - Gear

struct GearListView: View {
    @EnvironmentObject private var store: AppStore
    @State private var showEditor = false
    @State private var editing: Gear?

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        List {
            ForEach(GearKind.allCases) { kind in
                let items = store.gear.filter { $0.kind == kind && !$0.isRetired }
                if !items.isEmpty {
                    Section(kind.name) {
                        ForEach(items) { item in
                            Button { editing = item } label: { row(item) }
                                .buttonStyle(.plain)
                        }
                    }
                }
            }
            let retired = store.gear.filter(\.isRetired)
            if !retired.isEmpty {
                Section("Retired") {
                    ForEach(retired) { item in
                        Button { editing = item } label: { row(item) }
                            .buttonStyle(.plain)
                    }
                }
            }
            Section {
                Button {
                    editing = nil
                    showEditor = true
                } label: {
                    Label("Add gear", systemImage: "plus.circle")
                }
            }
        }
        .navigationTitle("Gear")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showEditor) {
            NavigationStack { GearEditorView(gear: nil) }
        }
        .sheet(item: $editing) { item in
            NavigationStack { GearEditorView(gear: item) }
        }
    }

    private func row(_ item: Gear) -> some View {
        let distance = store.mileage(for: item)
        let limit = item.retirementDistance > 0 ? item.retirementDistance : item.kind.defaultRetirementDistance
        let fraction = limit > 0 ? distance / limit : 0
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label(item.displayName, systemImage: item.kind.symbol)
                    .font(.system(size: 15, weight: .semibold))
                if item.isDefault { Pill(text: "Default", color: Theme.accent) }
                Spacer()
                Text(Fmt.distanceWithUnit(distance, units, decimals: 0))
                    .font(.system(size: 14, weight: .semibold))
                    .monospacedDigit()
            }
            ProgressBar(progress: fraction,
                        color: fraction > 0.9 ? Theme.negative : (fraction > 0.75 ? Theme.warning : Theme.positive),
                        height: 5)
            HStack {
                Text("\(store.activityCount(for: item)) activities")
                Spacer()
                if fraction >= 1 {
                    Text("Past its recommended life").foregroundStyle(Theme.negative)
                } else {
                    Text("\(Fmt.distanceWithUnit(Swift.max(0, limit - distance), units, decimals: 0)) to go")
                }
            }
            .font(.system(size: 10))
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 3)
    }
}

struct GearEditorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var gear: Gear?

    @State private var kind: GearKind = .shoes
    @State private var name = ""
    @State private var brand = ""
    @State private var model = ""
    @State private var notes = ""
    @State private var isDefault = false
    @State private var isRetired = false
    @State private var startingDistance: Double = 0
    @State private var retirementDistance: Double = 0
    @State private var weightKg: Double = 0
    @State private var loaded = false

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        Form {
            Section {
                Picker("Type", selection: $kind) {
                    ForEach(GearKind.allCases) { Text($0.name).tag($0) }
                }
                TextField("Nickname", text: $name)
                TextField("Brand", text: $brand)
                TextField("Model", text: $model)
            }
            Section("Mileage") {
                HStack {
                    Text("Already on it")
                    Spacer()
                    TextField("0", value: startingBinding, format: .number)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                    Text(units.distanceUnit).foregroundStyle(.secondary)
                }
                HStack {
                    Text("Remind me at")
                    Spacer()
                    TextField("0", value: retirementBinding, format: .number)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                    Text(units.distanceUnit).foregroundStyle(.secondary)
                }
            }
            if kind == .bike {
                Section {
                    HStack {
                        Text("Weight")
                        Spacer()
                        TextField("0", value: weightBinding, format: .number)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 70)
                        Text(units.weightUnit).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Bike")
                } footer: {
                    Text("Used by the power estimate. Leave at zero to fall back to the default in your profile.")
                }
            }
            Section {
                Toggle("Use by default", isOn: $isDefault)
                Toggle("Retired", isOn: $isRetired)
                TextField("Notes", text: $notes, axis: .vertical).lineLimit(2...5)
            }
            if let gear {
                Section {
                    Button("Delete", role: .destructive) {
                        store.deleteGear(gear)
                        dismiss()
                    }
                }
            }
        }
        .navigationTitle(gear == nil ? "Add Gear" : "Edit Gear")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") { save() }.fontWeight(.semibold)
                    .disabled(name.isEmpty && brand.isEmpty)
            }
        }
        .onAppear(perform: load)
    }

    private var startingBinding: Binding<Double> {
        Binding(get: { startingDistance / units.metersPerUnit },
                set: { startingDistance = $0 * units.metersPerUnit })
    }

    private var retirementBinding: Binding<Double> {
        Binding(get: { retirementDistance / units.metersPerUnit },
                set: { retirementDistance = $0 * units.metersPerUnit })
    }

    private var weightBinding: Binding<Double> {
        Binding(get: { weightKg / units.kilogramsPerWeightUnit },
                set: { weightKg = $0 * units.kilogramsPerWeightUnit })
    }

    private func load() {
        guard !loaded, let gear else {
            loaded = true
            return
        }
        kind = gear.kind
        name = gear.name
        brand = gear.brand
        model = gear.model
        notes = gear.notes
        isDefault = gear.isDefault
        isRetired = gear.isRetired
        startingDistance = gear.startingDistance
        retirementDistance = gear.retirementDistance
        weightKg = gear.weightKg
        loaded = true
    }

    private func save() {
        var item = gear ?? Gear(kind: kind, name: name)
        item.kind = kind
        item.name = name.isEmpty ? "\(brand) \(model)" : name
        item.brand = brand
        item.model = model
        item.notes = notes
        item.isDefault = isDefault
        item.isRetired = isRetired
        item.startingDistance = startingDistance
        item.retirementDistance = retirementDistance
        item.weightKg = weightKg

        if isDefault {
            for other in store.gear where other.kind == kind && other.id != item.id && other.isDefault {
                var updated = other
                updated.isDefault = false
                store.updateGear(updated)
            }
        }
        if gear == nil { store.addGear(item) } else { store.updateGear(item) }
        dismiss()
    }
}

// MARK: - Data

struct DataView: View {
    @EnvironmentObject private var store: AppStore

    private enum ImportMode {
        case activity
        case backup
    }

    @State private var importMode: ImportMode = .activity
    @State private var showImporter = false
    @State private var exportURL: URL?
    @State private var showShare = false
    @State private var confirmReanalyse = false
    @State private var importCount = 0
    @State private var importFailures = ""
    @State private var importFormats: [String: Int] = [:]

    @State private var isWorking = false
    @State private var workLabel = ""
    @State private var workProgress: Double = 0
    @State private var pendingRestore: URL?
    @State private var pendingSummary: Backup.Summary?
    @State private var showRestoreChoice = false
    @State private var message: String?
    @State private var errorMessage: String?

    var body: some View {
        Form {
            if !store.damagedFiles.isEmpty { damagedSection }
            if isWorking { workingSection }
            backupSection
            automaticSection
            exportSection
            importSection
            maintenanceSection
        }
        .navigationTitle("Data")
        .navigationBarTitleDisplayMode(.inline)
        .disabled(isWorking)
        .sheet(isPresented: $showShare) {
            if let exportURL { ShareSheet(items: [exportURL]) }
        }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: importMode == .backup ? [.data] : ActivityFile.readableContentTypes,
                      allowsMultipleSelection: importMode == .activity) { result in
            switch importMode {
            case .activity: handleActivityImport(result)
            case .backup: handleBackupPicked(result)
            }
        }
        .confirmationDialog(restoreTitle, isPresented: $showRestoreChoice, titleVisibility: .visible) {
            Button("Merge — keep what is here") { runRestore(mode: .merge) }
            Button("Replace everything", role: .destructive) { runRestore(mode: .replace) }
            Button("Cancel", role: .cancel) { pendingRestore = nil }
        } message: {
            if let pendingSummary {
                Text("This backup was made \(Fmt.shortDayFormatter.string(from: pendingSummary.createdAt)) and holds \(pendingSummary.activities) activities, \(pendingSummary.segments) segments, \(pendingSummary.routes) routes and \(pendingSummary.goals) goals.")
            }
        }
        .alert("Done", isPresented: Binding(get: { message != nil },
                                            set: { if !$0 { message = nil } })) {
            Button("OK") { message = nil }
        } message: {
            Text(message ?? "")
        }
        .alert("Something went wrong", isPresented: Binding(get: { errorMessage != nil },
                                                           set: { if !$0 { errorMessage = nil } })) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    // MARK: - Sections

    private var workingSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(workLabel).font(.footnote)
                ProgressView(value: workProgress)
            }
        }
    }

    private var damagedSection: some View {
        Section {
            Label("Some data could not be read", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(Theme.warning)
                .font(.system(size: 14, weight: .semibold))
            Text("These files were set aside rather than overwritten, so nothing has been destroyed: \(store.damagedFiles.joined(separator: ", ")). You can find the saved copies in the Files app under On My iPhone → Stride, named with a .damaged suffix. Restoring a backup is the easiest way to recover.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var backupSection: some View {
        Section {
            Button {
                runBackup()
            } label: {
                Label("Back up now", systemImage: "arrow.up.doc")
            }
            Button {
                importMode = .backup
                showImporter = true
            } label: {
                Label("Restore from a backup", systemImage: "arrow.down.doc")
            }
            NavigationLink {
                SavedBackupsView()
            } label: {
                LabeledContent {
                    Text("\(savedCount)")
                } label: {
                    Label("Saved backups", systemImage: "clock.arrow.circlepath")
                }
            }
        } header: {
            Text("Backup")
        } footer: {
            Text("One file holding every activity and its full GPS track, plus your segments, routes, gear, goals and settings. This is what moves your training to a new phone — and what you should take before changing the app's signing team, which resets the app's storage.")
        }
    }

    private var savedCount: Int {
        AutoBackup.existing().count
    }

    private var automaticSection: some View {
        Section {
            Toggle("Back up automatically", isOn: $store.settings.backup.automatic)
            if store.settings.backup.automatic {
                Picker("How often", selection: $store.settings.backup.intervalDays) {
                    ForEach(BackupSettings.intervalChoices, id: \.0) { days, name in
                        Text(name).tag(days)
                    }
                }
                Picker("Keep the last", selection: $store.settings.backup.keepCount) {
                    Text("3 backups").tag(3)
                    Text("5 backups").tag(5)
                    Text("10 backups").tag(10)
                    Text("20 backups").tag(20)
                }
            }
            if let last = store.settings.backup.lastRun {
                LabeledContent("Last backup", value: Fmt.relativeDay(last))
            } else if store.settings.backup.automatic {
                LabeledContent("Last backup", value: "Due on next launch")
            }
            if let next = store.settings.backup.nextDue(), store.settings.backup.lastRun != nil {
                LabeledContent("Next", value: next <= Date() ? "Due now" : Fmt.shortDayFormatter.string(from: next))
            }
        } header: {
            Text("Automatic")
        } footer: {
            Text("Checked each time Stride opens. Backups are written to On My iPhone → Stride → Backups in the Files app, where you can copy them to iCloud Drive or your Mac. Older ones beyond the number you keep are deleted.")
        }
    }

    private var exportSection: some View {
        Section {
            Button {
                exportCSV()
            } label: {
                Label("Export activities as CSV", systemImage: "tablecells")
            }
            Text("A spreadsheet of every activity's totals. Individual tracks export as GPX or TCX from each activity's own menu.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("Export")
        }
    }

    private var importSection: some View {
        Section {
            Button {
                importMode = .activity
                showImporter = true
            } label: {
                Label("Import activity files", systemImage: "square.and.arrow.down")
            }
            if importCount > 0 {
                Text(importSummary)
                    .font(.footnote)
                    .foregroundStyle(Theme.positive)
            }
            if !importFailures.isEmpty {
                Text(importFailures)
                    .font(.footnote)
                    .foregroundStyle(Theme.warning)
            }
            Text("GPX, TCX and FIT are all understood, and the format is worked out from the file itself rather than its name. A bulk export from Strava, Garmin, Wahoo or Coros can be selected all at once.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("Import")
        }
    }

    private var maintenanceSection: some View {
        Section("Maintenance") {
            Button("Rebuild every segment leaderboard") {
                store.rematchAllSegments()
            }
            Button("Recalculate all activities") {
                confirmReanalyse = true
            }
            LabeledContent("Activities", value: "\(store.activities.count)")
            LabeledContent("Segments", value: "\(store.segments.count)")
            LabeledContent("Segment efforts", value: "\(store.segmentEfforts.count)")
            LabeledContent("Routes", value: "\(store.routes.count)")
            LabeledContent("Storage", value: storageSize)
        }
        .confirmationDialog("Recalculate every activity from its raw track?",
                            isPresented: $confirmReanalyse, titleVisibility: .visible) {
            Button("Recalculate") { store.reanalyseAll() }
        }
    }

    private var restoreTitle: String {
        "Restore this backup?"
    }

    // MARK: - Backup actions

    private func runBackup() {
        let snapshot = store.backupSnapshot()
        let destination = Store.exportsDirectory.appendingPathComponent(Backup.suggestedFilename())
        isWorking = true
        workLabel = "Writing backup…"
        workProgress = 0
        Store.prepare()

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try Backup.write(snapshot, to: destination) { fraction in
                    DispatchQueue.main.async { workProgress = fraction }
                }
                DispatchQueue.main.async {
                    isWorking = false
                    exportURL = destination
                    showShare = true
                }
            } catch {
                DispatchQueue.main.async {
                    isWorking = false
                    errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func handleBackupPicked(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let picked = urls.first else { return }
        let accessed = picked.startAccessingSecurityScopedResource()
        defer { if accessed { picked.stopAccessingSecurityScopedResource() } }

        // Copy it somewhere we own before doing anything slow with it.
        Store.prepare()
        let local = Store.exportsDirectory.appendingPathComponent("incoming-backup")
        try? FileManager.default.removeItem(at: local)
        do {
            try FileManager.default.copyItem(at: picked, to: local)
            pendingSummary = try Backup.inspect(local)
            pendingRestore = local
            showRestoreChoice = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func runRestore(mode: Backup.RestoreMode) {
        guard let source = pendingRestore else { return }
        let existing = store.activityIDs
        isWorking = true
        workLabel = mode == .replace ? "Replacing your data…" : "Merging backup…"
        workProgress = 0

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let restored = try Backup.read(source, existingActivityIDs: existing, mode: mode) { fraction in
                    DispatchQueue.main.async { workProgress = fraction }
                }
                DispatchQueue.main.async {
                    store.apply(restored, mode: mode, restoreSettings: mode == .replace)
                    store.rematchAllSegments()
                    isWorking = false
                    pendingRestore = nil
                    let skipped = restored.skipped > 0 ? " \(restored.skipped) entries could not be read." : ""
                    message = "Restored \(restored.activities.count) activities.\(skipped)"
                    try? FileManager.default.removeItem(at: source)
                }
            } catch {
                DispatchQueue.main.async {
                    isWorking = false
                    pendingRestore = nil
                    errorMessage = error.localizedDescription
                }
            }
        }
    }

    // MARK: - Other actions

    private var storageSize: String {
        let keys: Set<URLResourceKey> = [.fileSizeKey]
        var total = 0
        if let enumerator = FileManager.default.enumerator(at: Store.documents,
                                                           includingPropertiesForKeys: Array(keys)) {
            for case let url as URL in enumerator {
                let values = try? url.resourceValues(forKeys: keys)
                total += values?.fileSize ?? 0
            }
        }
        return ByteCountFormatter.string(fromByteCount: Int64(total), countStyle: .file)
    }

    private func exportCSV() {
        let csv = Exporter.csv(activities: store.activities, units: store.settings.units)
        if let url = Exporter.write(csv, filename: "stride-activities.csv") {
            exportURL = url
            showShare = true
        }
    }

    private var importSummary: String {
        let breakdown = importFormats
            .sorted { $0.key < $1.key }
            .map { "\($0.value) \($0.key)" }
            .joined(separator: ", ")
        return "Imported \(importCount) activities (\(breakdown))."
    }

    private func handleActivityImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        var imported = 0
        var formats: [String: Int] = [:]
        var problems: [String] = []

        for url in urls {
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            let filename = url.lastPathComponent
            guard let data = try? Data(contentsOf: url) else {
                problems.append("\(filename): could not be read")
                continue
            }
            do {
                let file = try ActivityFile.load(data: data, filename: filename)
                // Routes are not activities; they belong on the Routes tab.
                guard !file.isRoute else {
                    problems.append("\(filename): is a route, not an activity")
                    continue
                }
                let activity = ActivityFile.makeActivity(from: file, profile: store.settings.profile)
                store.add(activity, points: file.points)
                imported += 1
                formats[file.format.name, default: 0] += 1
                if let warning = file.warning {
                    problems.append("\(filename): \(warning)")
                }
            } catch {
                problems.append("\(filename): \(error.localizedDescription)")
            }
        }

        importCount = imported
        importFormats = formats
        importFailures = problems.prefix(3).joined(separator: "\n")
    }
}



/// The backups already on the phone: share one out, put one back, or clear space.
struct SavedBackupsView: View {
    @EnvironmentObject private var store: AppStore

    @State private var entries: [AutoBackup.Entry] = []
    @State private var shareURL: URL?
    @State private var showShare = false
    @State private var pending: AutoBackup.Entry?
    @State private var summary: Backup.Summary?
    @State private var showRestoreChoice = false
    @State private var message: String?
    @State private var isWorking = false

    var body: some View {
        List {
            if entries.isEmpty {
                Text("No backups yet. Turn on automatic backups, or use \u{201C}Back up now\u{201D}.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(entries) { entry in
                row(entry)
            }
            .onDelete { offsets in
                for index in offsets { AutoBackup.delete(entries[index]) }
                reload()
            }
        }
        .navigationTitle("Saved Backups")
        .navigationBarTitleDisplayMode(.inline)
        .disabled(isWorking)
        .onAppear(perform: reload)
        .sheet(isPresented: $showShare) {
            if let shareURL { ShareSheet(items: [shareURL]) }
        }
        .confirmationDialog("Restore this backup?", isPresented: $showRestoreChoice, titleVisibility: .visible) {
            Button("Merge — keep what is here") { restore(mode: .merge) }
            Button("Replace everything", role: .destructive) { restore(mode: .replace) }
            Button("Cancel", role: .cancel) { pending = nil }
        } message: {
            if let summary {
                Text("Made \(Fmt.shortDayFormatter.string(from: summary.createdAt)) with \(summary.activities) activities, \(summary.segments) segments and \(summary.routes) routes.")
            }
        }
        .alert("Done", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") { message = nil }
        } message: {
            Text(message ?? "")
        }
    }

    private func row(_ entry: AutoBackup.Entry) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(Fmt.relativeDay(entry.created))
                .font(.system(size: 15, weight: .semibold))
            Text(subtitle(entry))
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
            actions(entry)
        }
        .padding(.vertical, 4)
    }

    private func subtitle(_ entry: AutoBackup.Entry) -> String {
        entry.sizeText + " · " + entry.filename
    }

    private func actions(_ entry: AutoBackup.Entry) -> some View {
        HStack(spacing: 16) {
            Button {
                shareURL = entry.url
                showShare = true
            } label: {
                Label("Share", systemImage: "square.and.arrow.up")
            }
            Button {
                prepareRestore(entry)
            } label: {
                Label("Restore", systemImage: "arrow.down.doc")
            }
        }
        .font(.system(size: 13, weight: .semibold))
        .buttonStyle(.plain)
        .foregroundStyle(Theme.green)
        .padding(.top, 2)
    }

    private func reload() {
        entries = AutoBackup.existing()
    }

    private func prepareRestore(_ entry: AutoBackup.Entry) {
        guard let found = try? Backup.inspect(entry.url) else {
            message = "That backup could not be read."
            return
        }
        summary = found
        pending = entry
        showRestoreChoice = true
    }

    private func restore(mode: Backup.RestoreMode) {
        guard let entry = pending else { return }
        let existing = store.activityIDs
        isWorking = true
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let restored = try Backup.read(entry.url, existingActivityIDs: existing, mode: mode)
                DispatchQueue.main.async {
                    store.apply(restored, mode: mode, restoreSettings: mode == .replace)
                    store.rematchAllSegments()
                    isWorking = false
                    pending = nil
                    message = "Restored \(restored.activities.count) activities."
                }
            } catch {
                DispatchQueue.main.async {
                    isWorking = false
                    pending = nil
                    message = error.localizedDescription
                }
            }
        }
    }
}
