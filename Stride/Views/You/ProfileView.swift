import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var scheme

    private var units: UnitSystem { store.settings.units }
    private var totals: Totals { Totals.of(store.activities) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(spacing: 12) {
                            Image(systemName: "person.crop.circle.fill")
                                .font(.system(size: 44))
                                .foregroundStyle(Theme.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(store.settings.profile.name.isEmpty ? "Athlete" : store.settings.profile.name)
                                    .font(.system(size: 19, weight: .bold))
                                Text("\(totals.count) activities · \(Fmt.distanceWithUnit(totals.distance, units, decimals: 0))")
                                    .font(.system(size: 12))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        HStack(spacing: 0) {
                            StatTile(label: "Streak", value: "\(store.currentStreak)d", size: 18, alignment: .center)
                            StatTile(label: "Time", value: Fmt.durationCompact(totals.movingTime), size: 18, alignment: .center)
                            StatTile(label: "Elevation",
                                     value: Fmt.elevation(totals.elevationGain, units),
                                     unit: units.elevationUnit, size: 18, alignment: .center)
                        }
                    }
                    .padding(.vertical, 6)
                }

                Section("Training") {
                    NavigationLink { AthleteProfileView() } label: {
                        Label("Profile and zones", systemImage: "figure.run.circle")
                    }
                    NavigationLink { GearListView() } label: {
                        Label("Gear", systemImage: "shoe.2")
                    }
                }

                Section("Recording") {
                    NavigationLink { VoiceSettingsView() } label: {
                        Label("Voice coach", systemImage: "speaker.wave.2")
                    }
                    NavigationLink { RecordingSettingsView() } label: {
                        Label("Recording and GPS", systemImage: "record.circle")
                    }
                    NavigationLink { HeartRateMonitorView() } label: {
                        Label("Heart rate monitor", systemImage: "heart")
                    }
                }

                Section("General") {
                    Picker("Units", selection: $store.settings.display.units) {
                        ForEach(UnitSystem.allCases) { Text($0.title).tag($0) }
                    }
                    Toggle("Week starts on Monday", isOn: $store.settings.display.weekStartsMonday)
                    Toggle("Write workouts to Apple Health", isOn: $store.settings.display.writeToAppleHealth)
                    Toggle("Read heart rate from Apple Health", isOn: $store.settings.display.readFromAppleHealth)
                }

                Section("Data") {
                    NavigationLink { DataView() } label: {
                        Label("Export and maintenance", systemImage: "externaldrive")
                    }
                }

                Section {
                    NavigationLink { AboutView() } label: {
                        Label("About Stride", systemImage: "info.circle")
                    }
                }
            }
            .navigationTitle("You")
        }
    }
}

struct AthleteProfileView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var health: HealthKitManager

    private var units: UnitSystem { store.settings.units }
    private var profile: Binding<AthleteProfile> { $store.settings.profile }

    var body: some View {
        Form {
            Section("You") {
                TextField("Name", text: profile.name)
                HStack {
                    Text("Weight")
                    Spacer()
                    TextField("", value: weightBinding, format: .number)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                    Text(units.weightUnit).foregroundStyle(.secondary)
                }
                Stepper(value: profile.birthYear, in: 1930...2018) {
                    LabeledContent("Born", value: String(store.settings.profile.birthYear))
                }
                Picker("Sex", selection: profile.isMale) {
                    Text("Male").tag(true)
                    Text("Female").tag(false)
                }
                Button("Import weight from Apple Health") {
                    health.fetchLatestBodyMass { kg in
                        if let kg { store.settings.profile.weightKg = kg }
                    }
                }
                .font(.system(size: 14))
            }

            Section {
                HStack {
                    Text("Max heart rate")
                    Spacer()
                    TextField("", value: profile.hrZones.maxHR, format: .number)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 70)
                    Text("bpm").foregroundStyle(.secondary)
                }
                HStack {
                    Text("Resting heart rate")
                    Spacer()
                    TextField("", value: profile.hrZones.restingHR, format: .number)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 70)
                    Text("bpm").foregroundStyle(.secondary)
                }
                Toggle("Base zones on threshold heart rate", isOn: profile.hrZones.useThresholdBased)
                if store.settings.profile.hrZones.useThresholdBased {
                    HStack {
                        Text("Threshold heart rate")
                        Spacer()
                        TextField("", value: profile.hrZones.lactateThresholdHR, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 70)
                        Text("bpm").foregroundStyle(.secondary)
                    }
                }
                Button("Use predicted max (\(Int(store.settings.profile.predictedMaxHR)) bpm)") {
                    store.settings.profile.hrZones.maxHR = store.settings.profile.predictedMaxHR
                }
                .font(.system(size: 14))
                ForEach(0..<5, id: \.self) { zone in
                    let range = store.settings.profile.hrZones.range(zone)
                    HStack {
                        Circle().fill(Theme.zoneColors[zone]).frame(width: 8, height: 8)
                        Text("Z\(zone + 1) \(HeartRateZones.names[zone])")
                            .font(.system(size: 13))
                        Spacer()
                        Text(zone == 4 ? "\(Int(range.0))+ bpm" : "\(Int(range.0))–\(Int(range.1)) bpm")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("Heart rate zones")
            } footer: {
                Text("Zones drive Relative Effort, calories and the Fitness & Freshness chart. Getting max heart rate roughly right matters more than getting it exactly right.")
            }

            Section("Power") {
                HStack {
                    Text("FTP")
                    Spacer()
                    TextField("", value: profile.powerZones.ftp, format: .number)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 70)
                    Text("W").foregroundStyle(.secondary)
                }
                ForEach(0..<store.settings.profile.powerZones.bounds.count, id: \.self) { zone in
                    let range = store.settings.profile.powerZones.range(zone)
                    HStack {
                        Circle().fill(Theme.powerZoneColors[zone]).frame(width: 8, height: 8)
                        Text("Z\(zone + 1) \(PowerZones.names[zone])").font(.system(size: 13))
                        Spacer()
                        Text(zone == store.settings.profile.powerZones.bounds.count - 1
                             ? "\(Int(range.0))+ W" : "\(Int(range.0))–\(Int(range.1)) W")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section {
                HStack {
                    Text("Threshold pace")
                    Spacer()
                    Text(Fmt.paceFromSeconds(store.settings.profile.paceZones.thresholdPace * units.metersPerUnit / 1000)
                         + " " + units.paceUnit)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Slider(value: profile.paceZones.thresholdPace, in: 180...480, step: 5)
            } header: {
                Text("Running pace")
            } footer: {
                Text("Roughly the pace you could hold for an hour flat out. Used for effort estimates when you record without heart rate.")
            }

            Section {
                HStack {
                    Text("Bike + kit weight")
                    Spacer()
                    TextField("", value: bikeWeightBinding, format: .number)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 70)
                    Text(units.weightUnit).foregroundStyle(.secondary)
                }
                HStack {
                    Text("Aerodynamic drag (CdA)")
                    Spacer()
                    TextField("", value: profile.dragArea, format: .number)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 70)
                }
                HStack {
                    Text("Rolling resistance")
                    Spacer()
                    TextField("", value: profile.rollingResistance, format: .number)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 70)
                }
            } header: {
                Text("Estimated cycling power")
            } footer: {
                Text("Without a power meter, watts are modelled from speed, gradient, mass and drag. Defaults suit a road bike on the hoods; 0.32 CdA and 0.005 rolling resistance are reasonable starting points.")
            }

            Section {
                Button("Recalculate every activity") {
                    store.reanalyseAll()
                }
                Text("Run this after changing your weight, zones or FTP so historical numbers match.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var weightBinding: Binding<Double> {
        Binding(get: { store.settings.profile.weightKg / units.kilogramsPerWeightUnit },
                set: { store.settings.profile.weightKg = $0 * units.kilogramsPerWeightUnit })
    }

    private var bikeWeightBinding: Binding<Double> {
        Binding(get: { store.settings.profile.bikeWeightKg / units.kilogramsPerWeightUnit },
                set: { store.settings.profile.bikeWeightKg = $0 * units.kilogramsPerWeightUnit })
    }
}

struct HeartRateMonitorView: View {
    @EnvironmentObject private var session: RecordingSession

    private var monitor: HeartRateMonitor { session.heartRateMonitor }

    var body: some View {
        Form {
            Section {
                if monitor.isConnected {
                    HStack {
                        Label(monitor.deviceName, systemImage: "heart.fill")
                            .foregroundStyle(Theme.negative)
                        Spacer()
                        Text(monitor.heartRate > 0 ? "\(Int(monitor.heartRate)) bpm" : "—")
                            .font(.system(size: 15, weight: .bold))
                            .monospacedDigit()
                    }
                    if let battery = monitor.batteryLevel {
                        LabeledContent("Strap battery", value: "\(battery)%")
                    }
                    Button("Disconnect", role: .destructive) { monitor.forgetDevice() }
                } else {
                    Text(monitor.bluetoothReady ? "No monitor connected." : "Bluetooth is off.")
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Status")
            } footer: {
                Text("Any Bluetooth chest strap or armband that advertises the standard Heart Rate Service will work — Polar, Garmin, Wahoo, Coospo and the rest.")
            }

            Section("Nearby") {
                if monitor.isScanning {
                    HStack {
                        ProgressView().controlSize(.small)
                        Text("Scanning…").foregroundStyle(.secondary)
                    }
                }
                ForEach(monitor.discovered, id: \.id) { device in
                    Button {
                        monitor.connect(device.id)
                    } label: {
                        HStack {
                            Text(device.name)
                            Spacer()
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                }
                Button("Scan for monitors") { monitor.scan() }
            }

            Section {
                Text("An Apple Watch works too — leave \"Read heart rate from Apple Health\" on and start a workout on the watch. A strap paired here updates faster.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Heart Rate")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { monitor.startIfNeeded() }
    }
}

struct AboutView: View {
    var body: some View {
        List {
            Section {
                VStack(spacing: 8) {
                    Image(systemName: "figure.run.circle.fill")
                        .font(.system(size: 54))
                        .foregroundStyle(Theme.accent)
                    Text("Stride").font(.system(size: 24, weight: .bold, design: .rounded))
                    Text("Version 1.0")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            Section("Where your data lives") {
                Text("Everything stays on this iPhone. There is no account, no server and no analytics. Your activities are files in the app's own folder and are included in an encrypted iPhone backup.")
                    .font(.footnote)
            }
            Section("Honest limits") {
                Text("Segment leaderboards, the heatmap and matched routes are built from your own history — there is no global database of other people's efforts to compare against. Power on rides without a meter is a physics estimate, not a measurement. Calories and Relative Effort are models, so treat them as trends rather than truth.")
                    .font(.footnote)
            }
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }
}
