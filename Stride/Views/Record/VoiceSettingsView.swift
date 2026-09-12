import SwiftUI
import AVFoundation

/// Everything about the spoken splits. This is the feature most people buy a
/// subscription for, so it is deliberately over-configurable.
struct VoiceSettingsView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var session: RecordingSession
    @Environment(\.dismiss) private var dismiss
    @State private var editingRun = true

    private var units: UnitSystem { store.settings.units }
    private var voice: Binding<VoiceSettings> { $store.settings.voice }

    var body: some View {
        Form {
            Section {
                Toggle("Speak while recording", isOn: voice.enabled)
                if store.settings.voice.enabled {
                    Button {
                        session.coach.settings = store.settings.voice
                        session.coach.units = units
                        session.coach.previewVoice()
                    } label: {
                        Label("Hear a preview", systemImage: "play.circle.fill")
                    }
                }
            } footer: {
                Text("Splits are spoken over your music. Your music ducks for a moment rather than stopping, and it keeps working with the screen locked.")
            }

            if store.settings.voice.enabled {
                triggerSection
                metricSection
                deliverySection
                announcementSection
                coachingSection
                voiceSection
            }
        }
        .navigationTitle("Voice Coach")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { dismiss() }
            }
        }
        .onDisappear {
            session.settings = store.settings
            store.flush()
        }
    }

    // MARK: - Sections

    private var triggerSection: some View {
        Section("When to speak") {
            Picker("Trigger", selection: voice.trigger) {
                ForEach(AnnouncementTrigger.allCases) { Text($0.name).tag($0) }
            }
            if store.settings.voice.trigger == .distance || store.settings.voice.trigger == .both {
                Picker("Every", selection: voice.distanceInterval) {
                    Text("0.25 \(units.distanceUnit)").tag(0.25)
                    Text("0.5 \(units.distanceUnit)").tag(0.5)
                    Text("1 \(units.distanceUnit)").tag(1.0)
                    Text("2 \(units.distanceUnit)").tag(2.0)
                    Text("5 \(units.distanceUnit)").tag(5.0)
                }
            }
            if store.settings.voice.trigger == .time || store.settings.voice.trigger == .both {
                Picker("Interval", selection: voice.timeInterval) {
                    Text("1 minute").tag(60.0)
                    Text("2 minutes").tag(120.0)
                    Text("5 minutes").tag(300.0)
                    Text("10 minutes").tag(600.0)
                    Text("15 minutes").tag(900.0)
                }
            }
        }
    }

    private var metricSection: some View {
        Section {
            Picker("Sport", selection: $editingRun) {
                Text("Running").tag(true)
                Text("Cycling").tag(false)
            }
            .pickerStyle(.segmented)

            ForEach(currentMetrics, id: \.self) { metric in
                HStack {
                    Image(systemName: "line.3.horizontal")
                        .foregroundStyle(.tertiary)
                    Text(metric.name)
                    Spacer()
                    Button {
                        remove(metric)
                    } label: {
                        Image(systemName: "minus.circle.fill")
                            .foregroundStyle(Theme.negative)
                    }
                    .buttonStyle(.plain)
                }
            }
            .onMove(perform: move)

            Menu {
                ForEach(availableMetrics, id: \.self) { metric in
                    Button(metric.name) { add(metric) }
                }
            } label: {
                Label("Add a metric", systemImage: "plus.circle")
            }
            .disabled(availableMetrics.isEmpty)
        } header: {
            Text("What to say")
        } footer: {
            Text(previewSentence)
                .italic()
        }
    }

    private var announcementSection: some View {
        Section("Also announce") {
            Toggle("Countdown before starting", isOn: voice.countdownBeforeStart)
            Toggle("Start", isOn: voice.announceStart)
            Toggle("Pause and resume", isOn: voice.announcePauseResume)
            Toggle("Auto-pause", isOn: voice.announceAutoPause)
            Toggle("Laps", isOn: voice.announceLaps)
            Toggle("Finish summary", isOn: voice.announceFinish)
            Toggle("Live segments", isOn: voice.announceSegments)
            Toggle("Halfway to your goal", isOn: voice.announceHalfway)
            Toggle("Goal milestones", isOn: voice.announceGoalMilestones)
        }
    }

    private var coachingSection: some View {
        Section {
            Toggle("Target pace alerts", isOn: voice.targetPaceAlertsEnabled)
            if store.settings.voice.targetPaceAlertsEnabled {
                HStack {
                    Text("Target pace")
                    Spacer()
                    Text(Fmt.paceFromSeconds(store.settings.voice.targetPaceSeconds) + " " + units.paceUnit)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Slider(value: voice.targetPaceSeconds, in: 150...600, step: 5)
                Picker("Tell me when I am off by", selection: voice.targetPaceToleranceSeconds) {
                    Text("5 seconds").tag(5.0)
                    Text("10 seconds").tag(10.0)
                    Text("15 seconds").tag(15.0)
                    Text("30 seconds").tag(30.0)
                }
            }
            Toggle("Heart rate zone alerts", isOn: voice.zoneAlertsEnabled)
            if store.settings.voice.zoneAlertsEnabled {
                Picker("Stay in zone", selection: voice.targetHeartRateZone) {
                    ForEach(1...5, id: \.self) { z in
                        Text("Zone \(z) — \(HeartRateZones.names[z - 1])").tag(z)
                    }
                }
            }
        } header: {
            Text("Coaching")
        } footer: {
            Text("Alerts are spaced at least 45 seconds apart so they never nag.")
        }
    }

    private var deliverySection: some View {
        Section {
            Picker("Style", selection: voice.style) {
                ForEach(SpeechStyle.allCases) { Text($0.name).tag($0) }
            }
            .pickerStyle(.segmented)
            Text(store.settings.voice.style.explanation)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Toggle("Occasional encouragement", isOn: voice.coachRemarks)
        } header: {
            Text("Delivery")
        } footer: {
            Text("Numbers are spoken the way a person says them — \u{201C}four thirty-two\u{201D} rather than \u{201C}four minutes thirty-two seconds\u{201D} — with real pauses between phrases instead of a run-on sentence.")
        }
    }

    private var voiceSection: some View {
        Section {
            Picker("Voice", selection: voice.voiceIdentifier) {
                Text("Best available").tag("")
                ForEach(VoiceCoach.availableVoices(), id: \.identifier) { v in
                    Text("\(v.name) · \(VoiceCoach.qualityName(v))").tag(v.identifier)
                }
            }
            VStack(alignment: .leading) {
                Text("Speed").font(.footnote).foregroundStyle(.secondary)
                Slider(value: voice.rate, in: 0.3...0.7)
            }
            VStack(alignment: .leading) {
                Text("Pitch").font(.footnote).foregroundStyle(.secondary)
                Slider(value: voice.pitch, in: 0.7...1.4)
            }
            VStack(alignment: .leading) {
                Text("Volume").font(.footnote).foregroundStyle(.secondary)
                Slider(value: voice.volume, in: 0.2...1.0)
            }
            Toggle("Duck music while speaking", isOn: voice.duckMusic)
        } header: {
            Text("Voice")
        } footer: {
            Text(voiceQualityAdvice)
        }
    }

    /// How many of the installed voices are better than the stock one.
    private var betterVoiceCount: Int {
        VoiceCoach.availableVoices().filter { VoiceCoach.quality($0) > 1 }.count
    }

    /// Deliberately does not name a Settings path. Apple has moved and renamed
    /// that screen between iOS versions, so anything specific here goes stale.
    private var voiceQualityAdvice: String {
        let base = "Stride always picks the best voice installed. Standard voices are the flat, robotic ones; Enhanced and Premium sound markedly more human, and choosing one makes far more difference than any other setting here."
        if betterVoiceCount > 0 {
            return base + "\n\nYou have \(betterVoiceCount) better-than-standard voice\(betterVoiceCount == 1 ? "" : "s") installed — they are labelled in the list above."
        }
        return base + "\n\nOnly Standard voices are installed on this iPhone. Extra voices are downloaded from the accessibility settings in iOS, under the section for reading text aloud. Apple renames that screen between releases, so look for speech or spoken content rather than an exact menu name."
    }

    // MARK: - Metric list helpers

    private var currentMetrics: [SpokenMetric] {
        editingRun ? store.settings.voice.runMetrics : store.settings.voice.rideMetrics
    }

    private var availableMetrics: [SpokenMetric] {
        SpokenMetric.allCases.filter { metric in
            guard !currentMetrics.contains(metric) else { return false }
            return editingRun ? metric.suitsRunning : metric.suitsCycling
        }
    }

    private func add(_ metric: SpokenMetric) {
        if editingRun {
            store.settings.voice.runMetrics.append(metric)
        } else {
            store.settings.voice.rideMetrics.append(metric)
        }
    }

    private func remove(_ metric: SpokenMetric) {
        if editingRun {
            store.settings.voice.runMetrics.removeAll { $0 == metric }
        } else {
            store.settings.voice.rideMetrics.removeAll { $0 == metric }
        }
    }

    private func move(from source: IndexSet, to destination: Int) {
        if editingRun {
            store.settings.voice.runMetrics.move(fromOffsets: source, toOffset: destination)
        } else {
            store.settings.voice.rideMetrics.move(fromOffsets: source, toOffset: destination)
        }
    }

    /// A worked example, built by the same code that speaks, so the preview can
    /// never drift from what you actually hear.
    private var previewSentence: String {
        var snapshot = CoachSnapshot()
        snapshot.sport = editingRun ? .run : .ride
        snapshot.splitIndex = 3
        snapshot.splitTime = 272
        snapshot.splitDistance = units.metersPerUnit
        snapshot.splitPaceSecondsPerUnit = 272
        snapshot.totalDistance = units.metersPerUnit * 3
        snapshot.movingTime = 831
        snapshot.totalTime = 845
        snapshot.averagePaceSecondsPerUnit = 277
        snapshot.currentPaceSecondsPerUnit = 268
        snapshot.gradeAdjustedPaceSecondsPerUnit = 264
        snapshot.averageSpeed = 7.2
        snapshot.currentSpeed = 7.4
        snapshot.heartRate = 154
        snapshot.averageHeartRate = 151
        snapshot.cadence = editingRun ? 176 : 88
        snapshot.power = 243
        snapshot.averagePower = 231
        snapshot.elevationGain = 84
        snapshot.calories = 320

        let coach = VoiceCoach()
        coach.settings = store.settings.voice
        coach.units = units
        let builder = coach.buildSplit(snapshot, isTimeInterval: false)
        guard !builder.isEmpty else { return "Nothing selected — nothing will be spoken." }
        return "\u{201C}" + builder.plainText + "\u{201D}"
    }
}

/// The gear button on the Record screen: how recording behaves, plus a way
/// through to the voice coach.
struct RecordingSettingsView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    VoiceSettingsView()
                } label: {
                    Label("Voice coach", systemImage: "speaker.wave.2")
                }
            }
            Section("Recording screen") {
                Toggle("Auto-pause when you stop", isOn: $store.settings.display.autoPause)
                Toggle("Keep the screen awake", isOn: $store.settings.display.keepScreenOn)
                Picker("Countdown", selection: $store.settings.display.countdownSeconds) {
                    Text("Off").tag(0)
                    Text("3 seconds").tag(3)
                    Text("5 seconds").tag(5)
                    Text("10 seconds").tag(10)
                }
                Picker("Map style", selection: $store.settings.display.mapStyle) {
                    ForEach(MapStyleOption.allCases) { Text($0.name).tag($0.rawValue) }
                }
            }
            Section {
                Toggle("Read heart rate from Health", isOn: $store.settings.display.readFromAppleHealth)
                Toggle("Fill in heart rate after saving", isOn: $store.settings.display.backfillHeartRateFromHealth)
            } header: {
                Text("Apple Watch")
            } footer: {
                Text("Start a workout on your Watch when you start here, and its heart rate flows into Health and through to Stride. The Watch only measures every few seconds while one of its own workouts is running — outside that it reads every several minutes to save power, which is the Watch's behaviour and not something an iPhone app can change.\n\nBecause the Watch syncs in bursts, the live number can lag. Filling in after saving re-reads the whole activity from Health, so your splits, zones and Relative Effort end up using everything the Watch recorded rather than only what arrived in time.")
            }

            Section {
                Toggle("Live segments", isOn: $store.settings.display.showLiveSegments)
                Toggle("Speak segment progress", isOn: $store.settings.display.liveSegmentVoice)
            } header: {
                Text("Segments")
            } footer: {
                Text("Star a segment to race it live. Stride watches for you entering it and calls out how you compare with your own best time.")
            }
            Section {
                Picker("Discard fixes worse than", selection: $store.settings.display.gpsAccuracyFilter) {
                    Text("10 m (strict)").tag(10.0)
                    Text("20 m").tag(20.0)
                    Text("30 m (default)").tag(30.0)
                    Text("50 m (lenient)").tag(50.0)
                }
            } header: {
                Text("GPS")
            } footer: {
                Text("Stricter filtering gives cleaner distance under trees and between buildings, but can drop samples when the signal is poor.")
            }
        }
        .navigationTitle("Recording")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
        }
    }
}
