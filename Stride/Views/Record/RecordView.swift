import SwiftUI

struct PendingSave: Identifiable {
    let id = UUID()
    var activity: Activity
    var points: [TrackPoint]
}

struct RecordView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var session: RecordingSession
    @EnvironmentObject private var health: HealthKitManager

    @State private var pending: PendingSave?
    @State private var sport: SportChoice = .run
    @State private var showSettings = false
    @State private var confirmFinish = false
    @State private var showRecovery = false

    private var units: UnitSystem { store.settings.units }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.pageBackground.ignoresSafeArea()
                VStack(spacing: 0) {
                    header
                    ScrollView {
                        VStack(spacing: 16) {
                            if session.state == .idle {
                                SportToggle(selection: $sport)
                                    .onChange(of: sport) { applySport() }
                            }
                            if let live = session.liveSegments.active {
                                LiveSegmentBanner(state: live, units: units)
                            }
                            mapCard
                            distanceBlock
                            statRow
                            spokenSplitsCard
                            if !session.splits.isEmpty { splitList }
                            if session.state == .idle { readinessPanel }
                        }
                        .padding(.horizontal, 20)
                        .padding(.bottom, 16)
                    }
                    controls
                }
                if session.state == .countdown { countdownOverlay }
            }
            .navigationBarHidden(true)
            .sheet(isPresented: $showSettings) {
                NavigationStack { RecordingSettingsView() }
            }
            .sheet(item: $pending) { item in
                SaveActivityView(pending: item)
            }
            .confirmationDialog("Finish this \(session.sport.shortName.lowercased())?",
                                isPresented: $confirmFinish, titleVisibility: .visible) {
                Button("Finish and save") {
                    let result = session.finish()
                    pending = PendingSave(activity: result.0, points: result.1)
                }
                Button("Discard", role: .destructive) { session.discard() }
                Button("Keep going", role: .cancel) { }
            }
            .alert("Unfinished activity found", isPresented: $showRecovery) {
                Button("Resume") {
                    if let snapshot = session.recoveryAvailable {
                        session.restoreFromRecovery(snapshot)
                    }
                }
                Button("Save what was recorded") { saveRecovered() }
                Button("Discard", role: .destructive) { session.dismissRecovery() }
            } message: {
                if let snapshot = session.recoveryAvailable {
                    Text("Stride was interrupted during a \(snapshot.sport.shortName.lowercased()) on \(Fmt.shortDayFormatter.string(from: snapshot.startDate)). \(Fmt.durationCompact(snapshot.elapsed)) was recorded.")
                }
            }
            .onAppear {
                session.settings = store.settings
                sport = SportChoice.from(store.settings.display.defaultSport)
                if session.state == .idle {
                    session.sport = store.settings.display.defaultSport
                    session.warmUp()
                }
                if session.recoveryAvailable != nil { showRecovery = true }
            }
            .onDisappear { session.stopWarmUp() }
        }
    }

    private func applySport() {
        // Keep the specific discipline if it already belongs to this family.
        if SportChoice.from(session.sport) != sport {
            session.sport = sport.defaultSport
        }
        store.settings.display.defaultSport = session.sport
        session.warmUp()
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            Text(headerTitle)
                .font(.screenTitle)
                .foregroundStyle(Theme.primaryText)
            Spacer()
            HStack(spacing: 10) {
                if session.state.isActive {
                    Button {
                        session.speakCurrentStats()
                    } label: {
                        Image(systemName: "waveform")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Theme.deepGreen)
                            .frame(width: 44, height: 44)
                            .background(Theme.lime)
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                }
                CircleIconButton(symbol: "slider.horizontal.3") { showSettings = true }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 14)
    }

    private var headerTitle: String {
        switch session.state {
        case .idle, .finished: return "Record"
        case .paused, .autoPaused: return "Paused"
        default: return "Recording"
        }
    }

    // MARK: - Map

    private var mapCard: some View {
        ZStack(alignment: .topLeading) {
            RouteMap(path: session.path,
                     showsStartEnd: session.path.count > 2,
                     interactive: true,
                     followsUser: session.path.count < 2,
                     showsUserLocation: true,
                     color: Theme.mapLine,
                     style: MapStyleOption(rawValue: store.settings.display.mapStyle) ?? .standard)
                .frame(height: 210)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))

            HStack(spacing: 6) {
                MapBadge(text: gpsText, symbol: "map")
                if session.location.signalStrength > 0 {
                    GPSIndicator(strength: session.location.signalStrength)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(Color.white.opacity(0.94))
                        .clipShape(Capsule())
                }
            }
            .padding(12)
        }
    }

    private var gpsText: String {
        switch session.location.signalStrength {
        case 3: return "Live GPS"
        case 2, 1: return "Live GPS"
        default: return session.location.isAuthorized ? "Finding GPS" : "Location off"
        }
    }

    // MARK: - Numbers

    private var distanceBlock: some View {
        VStack(spacing: 2) {
            Kicker(text: "Distance")
            Text(Fmt.distance(session.distance, units))
                .font(.figure(62, .bold))
                .monospacedDigit()
                .foregroundStyle(session.state.isPaused ? Theme.secondaryText : Theme.primaryText)
            Text(units.distanceUnit)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Theme.secondaryText)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 6)
    }

    private var statRow: some View {
        HStack(spacing: 0) {
            statColumn(label: "Time",
                       value: Fmt.durationPadded(session.movingTime),
                       unit: session.state == .autoPaused ? "auto-paused" : nil)
            statColumn(label: session.sport.usesPace ? "Pace" : "Speed",
                       value: session.sport.usesPace
                        ? Fmt.paceFromSeconds(session.currentPaceSecondsPerUnit)
                        : Fmt.speed(session.currentSpeed, units),
                       unit: session.sport.usesPace ? units.paceUnit : units.speedUnit)
            statColumn(label: "Heart rate",
                       value: session.heartRate > 30 ? "\(Int(session.heartRate))" : "--",
                       unit: session.heartRate > 30 ? session.heartRateSource.label : "bpm")
        }
    }

    private func statColumn(label: String, value: String, unit: String?) -> some View {
        VStack(spacing: 3) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .kerning(0.7)
                .foregroundStyle(Theme.secondaryText)
            Text(value)
                .font(.figure(24, .bold))
                .monospacedDigit()
                .foregroundStyle(Theme.primaryText)
            Text(unit ?? " ")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Theme.secondaryText)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Spoken splits

    private var spokenSplitsCard: some View {
        InkCard(padding: 16, radius: 20) {
            HStack(spacing: 14) {
                Image(systemName: "headphones")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Theme.lime)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Spoken splits")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                    Text(voiceSummary)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.onInkSecondary)
                }
                Spacer(minLength: 8)
                Toggle("", isOn: $store.settings.voice.enabled)
                    .labelsHidden()
                    .tint(Theme.lime)
            }
        }
        .onTapGesture { showSettings = true }
    }

    private var voiceSummary: String {
        let v = store.settings.voice
        guard v.enabled else { return "Off" }
        switch v.trigger {
        case .off: return "Off"
        case .distance:
            return "Every \(Fmt.trimmed(v.distanceInterval)) \(units.distanceUnit)"
        case .time:
            return "Every \(Fmt.durationCompact(v.timeInterval))"
        case .both:
            return "Every \(Fmt.trimmed(v.distanceInterval)) \(units.distanceUnit) and \(Fmt.durationCompact(v.timeInterval))"
        }
    }

    // MARK: - Splits

    private var splitList: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                Kicker(text: "Splits")
                ForEach(session.splits.reversed()) { split in
                    HStack {
                        Text("\(split.index)")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Theme.green)
                            .frame(width: 22, alignment: .leading)
                        Text(Fmt.distanceWithUnit(split.distance, units))
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.secondaryText)
                        Spacer()
                        Text(Fmt.duration(split.moving))
                            .font(.system(size: 15, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(Theme.primaryText)
                    }
                }
            }
        }
    }

    // MARK: - Readiness

    private var readinessPanel: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                Kicker(text: "Before you start")
                readinessRow(ok: session.location.isAuthorized,
                             text: session.location.isAuthorized ? "Location allowed" : "Location permission needed",
                             action: session.location.isAuthorized ? nil : { session.location.requestAlwaysAuthorization() })
                readinessRow(ok: session.location.signalStrength >= 2,
                             text: session.location.signalStrength >= 2 ? "GPS locked on" : "Waiting for a good GPS fix",
                             action: nil)
                if !store.starredSegments.isEmpty && store.settings.display.showLiveSegments {
                    readinessRow(ok: true,
                                 text: "\(store.starredSegments.count) live segment\(store.starredSegments.count == 1 ? "" : "s") armed",
                                 action: nil)
                }
                if session.heartRateMonitor.isConnected {
                    readinessRow(ok: true, text: "\(session.heartRateMonitor.deviceName) connected", action: nil)
                }
                if store.settings.display.readFromAppleHealth && !session.heartRateMonitor.isConnected {
                    readinessRow(ok: health.hasFreshHeartRate,
                                 text: health.hasFreshHeartRate
                                    ? "Heart rate arriving from Apple Health"
                                    : "Start a workout on your Apple Watch for heart rate",
                                 action: nil)
                }
            }
        }
    }

    private func readinessRow(ok: Bool, text: String, action: (() -> Void)?) -> some View {
        HStack(spacing: 10) {
            Image(systemName: ok ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                .font(.system(size: 15))
                .foregroundStyle(ok ? Theme.green : Theme.warning)
            Text(text)
                .font(.system(size: 13))
                .foregroundStyle(Theme.primaryText)
            Spacer()
            if let action {
                Button("Fix", action: action)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.green)
            }
        }
    }

    // MARK: - Controls

    private var controls: some View {
        HStack(spacing: 22) {
            CircleIconButton(symbol: "arrow.counterclockwise",
                             background: Theme.surface,
                             foreground: session.state.isActive ? Theme.primaryText : Theme.tertiaryText,
                             size: 58) {
                session.lap()
            }
            .disabled(!session.state.isActive)

            Button {
                primaryAction()
            } label: {
                Image(systemName: session.state == .recording ? "pause.fill" : "play.fill")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Theme.deepGreen)
                    .frame(width: 86, height: 86)
                    .background(Theme.lime)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)

            CircleIconButton(symbol: "flag.fill",
                             background: session.state.isActive ? Theme.ink : Theme.surface,
                             foreground: session.state.isActive ? .white : Theme.tertiaryText,
                             size: 58) {
                confirmFinish = true
            }
            .disabled(!session.state.isActive)
        }
        .padding(.top, 10)
        .padding(.bottom, 14)
    }

    private func primaryAction() {
        switch session.state {
        case .idle, .finished: session.start()
        case .recording: session.pause()
        case .paused, .autoPaused: session.resume()
        case .countdown: break
        }
    }

    private var countdownOverlay: some View {
        ZStack {
            Theme.ink.opacity(0.94).ignoresSafeArea()
            Text(session.countdownValue > 0 ? "\(session.countdownValue)" : "GO")
                .font(.figure(120, .bold))
                .foregroundStyle(Theme.lime)
        }
    }

    private func saveRecovered() {
        guard let snapshot = session.recoveryAvailable else { return }
        var activity = Activity(name: Activity.defaultName(for: snapshot.sport, at: snapshot.startDate),
                                sport: snapshot.sport,
                                startDate: snapshot.startDate)
        activity.id = snapshot.activityID
        activity.laps = snapshot.laps
        activity = ActivityAnalyzer.analyse(activity: activity, points: snapshot.points,
                                            profile: store.settings.profile)
        pending = PendingSave(activity: activity, points: snapshot.points)
        session.dismissRecovery()
    }
}

/// The live segment heads-up display: how far in, and how you compare.
struct LiveSegmentBanner: View {
    var state: LiveSegmentEngine.LiveState
    var units: UnitSystem

    private var deltaColor: Color {
        guard state.prTime != nil else { return Theme.secondaryText }
        return state.deltaToPR <= 0 ? Theme.green : Theme.negative
    }

    var body: some View {
        InkCard(padding: 16, radius: 20) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label(state.name, systemImage: "flag.checkered")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Spacer()
                    if state.prTime != nil {
                        Text(Fmt.signed(state.deltaToPR) + "s")
                            .font(.figure(20, .bold))
                            .monospacedDigit()
                            .foregroundStyle(state.deltaToPR <= 0 ? Theme.lime : Color(hex: 0xFF9A76))
                    }
                }
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Theme.inkBar)
                        Capsule()
                            .fill(state.deltaToPR <= 0 ? Theme.lime : Color(hex: 0xFF9A76))
                            .frame(width: Swift.max(0, Swift.min(1, state.fraction)) * geo.size.width)
                    }
                }
                .frame(height: 6)
                HStack {
                    Text(Fmt.duration(state.elapsed))
                        .font(.system(size: 12, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(.white)
                    Spacer()
                    Text(Fmt.distanceWithUnit(state.remaining, units) + " to go")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.onInkSecondary)
                }
            }
        }
    }
}
