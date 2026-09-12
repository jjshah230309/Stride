import Foundation
import CoreLocation
import Combine
import UIKit

enum RecordingState: String {
    case idle
    case countdown
    case recording
    case paused
    case autoPaused
    case finished

    var isActive: Bool { self == .recording || self == .paused || self == .autoPaused }
    var isPaused: Bool { self == .paused || self == .autoPaused }
}

/// The recorder. Owns the sensors, builds the track, works out splits, and drives
/// the voice coach.
final class RecordingSession: NSObject, ObservableObject {

    // MARK: - Published state

    @Published private(set) var state: RecordingState = .idle
    @Published var sport: SportType = .run

    @Published private(set) var elapsed: TimeInterval = 0
    @Published private(set) var movingTime: TimeInterval = 0
    @Published private(set) var distance: Double = 0
    @Published private(set) var currentSpeed: Double = 0
    @Published private(set) var elevationGain: Double = 0
    @Published private(set) var elevationLoss: Double = 0
    @Published private(set) var currentGrade: Double = 0
    @Published private(set) var altitude: Double = 0
    @Published private(set) var heartRate: Double = 0
    @Published private(set) var cadence: Double = 0
    @Published private(set) var power: Double = 0
    @Published private(set) var calories: Double = 0
    @Published private(set) var countdownValue: Int = 0
    @Published private(set) var heartRateSource: HeartRateSource = .none

    enum HeartRateSource: String {
        case none, strap, watch, health

        var label: String {
            switch self {
            case .none: return "No heart rate"
            case .strap: return "Chest strap"
            case .watch: return "Apple Watch"
            case .health: return "Apple Health"
            }
        }
    }

    @Published private(set) var splits: [Split] = []
    @Published private(set) var laps: [Lap] = []
    @Published private(set) var currentSplitDistance: Double = 0
    @Published private(set) var currentSplitTime: TimeInterval = 0
    @Published private(set) var lastSplitPace: Double = 0

    @Published private(set) var path: [Coord] = []
    @Published private(set) var currentCoord: Coord?
    @Published var recoveryAvailable: Store.RecoverySnapshot?

    // MARK: - Collaborators

    let location = LocationEngine()
    let motion = MotionEngine()
    let heartRateMonitor = HeartRateMonitor()
    let coach = VoiceCoach()
    let liveSegments = LiveSegmentEngine()

    weak var store: AppStore?
    var health: HealthKitManager?

    // MARK: - Internals

    private var activityID = UUID()
    private var startDate = Date()
    private var points: [TrackPoint] = []
    private var timer: Timer?
    private var lastTick: Date?
    private var lastAcceptedLocation: CLLocation?
    private var pendingDistanceAnchor: CLLocation?
    private var altitudeFilter = Smooth.Kalman1D(initial: 0)
    private var barometerAnchor: Double?
    private var lastAltitude: Double?
    private var elevationReference: Double?
    private var slowSeconds: Double = 0
    private var fastSeconds: Double = 0
    private var hrSamples: [Double] = []
    private var powerSamples: [Double] = []
    private var lastPointTime: TimeInterval = -1

    private var splitAnchorDistance: Double = 0
    private var splitAnchorTime: TimeInterval = 0
    private var splitAnchorIndex: Int = 0
    private var nextDistanceMark: Double = 0
    private var nextTimeMark: TimeInterval = 0
    private var splitCounter: Int = 0
    private var lapAnchorTime: TimeInterval = 0
    private var lapAnchorDistance: Double = 0
    private var lapAnchorElevation: Double = 0
    private var halfwayAnnounced = false
    private var lastPaceAlert: Date = .distantPast
    private var lastRecoveryWrite: Date = .distantPast
    private var cancellables = Set<AnyCancellable>()

    var settings: AppSettings = AppSettings() {
        didSet {
            coach.settings = settings.voice
            coach.units = settings.units
        }
    }

    // MARK: - Setup

    override init() {
        super.init()
        location.onLocation = { [weak self] loc in
            self?.handle(location: loc)
        }
        // SwiftUI only watches the object a view declares, so the nested engines'
        // changes are republished through this one. Without this the GPS pips,
        // the live segment banner and the strap's heart rate never redraw.
        let children: [ObservableObjectPublisher] = [
            location.objectWillChange,
            motion.objectWillChange,
            heartRateMonitor.objectWillChange,
            liveSegments.objectWillChange,
            coach.objectWillChange
        ]
        for publisher in children {
            publisher
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in self?.objectWillChange.send() }
                .store(in: &cancellables)
        }

        recoveryAvailable = Store.readRecovery()
    }

    func prepare(store: AppStore, health: HealthKitManager) {
        self.store = store
        self.health = health
        self.settings = store.settings
        self.sport = store.settings.display.defaultSport
        heartRateMonitor.startIfNeeded()
    }

    /// Warm the GPS up on the record screen so the first fix is not thirty seconds late.
    func warmUp() {
        location.configure(for: sport)
        location.startWarmup()
    }

    func stopWarmUp() {
        guard !state.isActive else { return }
        location.stop()
    }

    // MARK: - Control

    func start() {
        guard state == .idle || state == .finished else { return }
        reset()
        if settings.voice.countdownBeforeStart && settings.display.countdownSeconds > 0 {
            state = .countdown
            countdownValue = settings.display.countdownSeconds
            runCountdown()
        } else {
            beginRecording()
        }
    }

    private func runCountdown() {
        guard countdownValue > 0 else {
            coach.announceCountdown(0)
            beginRecording()
            return
        }
        coach.announceCountdown(countdownValue)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, self.state == .countdown else { return }
            self.countdownValue -= 1
            self.runCountdown()
        }
    }

    private func beginRecording() {
        activityID = UUID()
        startDate = Date()
        state = .recording
        lastTick = Date()

        location.configure(for: sport)
        location.startRecording()
        motion.reset()
        motion.start(trackSteps: sport.isFoot)
        // Ask for Health permission at the moment it is first needed.
        if settings.display.writeToAppleHealth || settings.display.readFromAppleHealth {
            health?.requestAuthorizationIfNeeded()
        }
        if settings.display.readFromAppleHealth {
            health?.startHeartRateStream()
        }
        heartRateMonitor.reconnectLast()

        if let store {
            var bests: [UUID: TimeInterval] = [:]
            for segment in store.starredSegments {
                if let pb = store.personalBest(for: segment) { bests[segment.id] = pb.time }
            }
            liveSegments.load(segments: store.starredSegments, bests: bests, sport: sport)
        }

        nextDistanceMark = splitDistanceMeters
        nextTimeMark = settings.voice.timeInterval
        UIApplication.shared.isIdleTimerDisabled = settings.display.keepScreenOn

        coach.announceStart(sport: sport)
        startTimer()
    }

    func pause(automatic: Bool = false) {
        guard state == .recording else { return }
        state = automatic ? .autoPaused : .paused
        coach.announcePause(automatic: automatic)
    }

    func resume(automatic: Bool = false) {
        guard state.isPaused else { return }
        state = .recording
        lastTick = Date()
        coach.announceResume(automatic: automatic)
    }

    func togglePause() {
        if state.isPaused { resume() } else { pause() }
    }

    func lap() {
        guard state.isActive else { return }
        let lapIndex = laps.count + 1
        let lapTime = movingTime - lapAnchorTime
        let lapDistance = distance - lapAnchorDistance
        guard lapDistance > 5 || lapTime > 5 else { return }
        let lap = Lap(index: lapIndex,
                      startTime: lapAnchorTime,
                      endTime: movingTime,
                      distance: lapDistance,
                      moving: lapTime,
                      elevGain: elevationGain - lapAnchorElevation,
                      avgHR: hrSamples.isEmpty ? nil : hrSamples.reduce(0, +) / Double(hrSamples.count),
                      avgPower: powerSamples.isEmpty ? nil : powerSamples.reduce(0, +) / Double(powerSamples.count))
        laps.append(lap)
        lapAnchorTime = movingTime
        lapAnchorDistance = distance
        lapAnchorElevation = elevationGain

        var snapshot = makeSnapshot()
        snapshot.splitIndex = lapIndex
        snapshot.splitTime = lapTime
        snapshot.splitDistance = lapDistance
        snapshot.splitPaceSecondsPerUnit = lapDistance > 1 ? lapTime / (lapDistance / settings.units.metersPerUnit) : 0
        coach.announceLap(number: lapIndex, snapshot: snapshot)
    }

    /// Finish and hand back the analysed activity plus its track, ready to save.
    func finish() -> (Activity, [TrackPoint]) {
        stopTimer()
        state = .finished
        location.stop()
        motion.stop()
        health?.stopHeartRateStream()
        UIApplication.shared.isIdleTimerDisabled = false

        coach.announceFinish(makeSnapshot())

        var activity = Activity(name: Activity.defaultName(for: sport, at: startDate),
                                sport: sport,
                                startDate: startDate)
        activity.id = activityID
        activity.laps = laps
        activity.gearID = store?.defaultGear(for: sport)?.id
        activity = ActivityAnalyzer.analyse(activity: activity, points: points,
                                            profile: settings.profile)
        Store.clearRecovery()
        recoveryAvailable = nil
        return (activity, points)
    }

    func discard() {
        stopTimer()
        location.stop()
        motion.stop()
        health?.stopHeartRateStream()
        coach.stop()
        UIApplication.shared.isIdleTimerDisabled = false
        Store.clearRecovery()
        recoveryAvailable = nil
        reset()
        state = .idle
    }

    private func reset() {
        points.removeAll()
        path.removeAll()
        splits.removeAll()
        laps.removeAll()
        hrSamples.removeAll()
        powerSamples.removeAll()
        elapsed = 0; movingTime = 0; distance = 0; currentSpeed = 0
        elevationGain = 0; elevationLoss = 0; currentGrade = 0
        calories = 0; power = 0; cadence = 0
        currentSplitDistance = 0; currentSplitTime = 0; lastSplitPace = 0
        splitAnchorDistance = 0; splitAnchorTime = 0; splitAnchorIndex = 0
        splitCounter = 0; lapAnchorTime = 0; lapAnchorDistance = 0; lapAnchorElevation = 0
        halfwayAnnounced = false
        slowSeconds = 0; fastSeconds = 0
        lastAcceptedLocation = nil
        pendingDistanceAnchor = nil
        barometerAnchor = nil
        lastAltitude = nil
        elevationReference = nil
        lastPointTime = -1
        liveSegments.reset()
    }

    // MARK: - Recovery

    func restoreFromRecovery(_ snapshot: Store.RecoverySnapshot) {
        reset()
        activityID = snapshot.activityID
        sport = snapshot.sport
        startDate = snapshot.startDate
        points = snapshot.points
        laps = snapshot.laps
        path = points.map(\.coord).filter(\.isValid)
        if let last = points.last {
            distance = last.d
            elapsed = last.t
            altitude = last.alt
        }
        movingTime = ActivityAnalyzer.movingTime(Streams(points))
        let smoothed = Smooth.movingAverage(points.map(\.alt), window: 9)
        let change = Smooth.elevationChange(smoothed, threshold: 1.0)
        elevationGain = change.gain
        elevationLoss = change.loss
        splitCounter = Int(distance / splitDistanceMeters)
        nextDistanceMark = Double(splitCounter + 1) * splitDistanceMeters
        nextTimeMark = settings.voice.timeInterval * Double(Int(movingTime / Swift.max(1, settings.voice.timeInterval)) + 1)
        splitAnchorDistance = distance
        splitAnchorTime = movingTime
        splitAnchorIndex = Swift.max(0, points.count - 1)
        lapAnchorDistance = distance
        lapAnchorTime = movingTime
        state = .paused
        lastTick = Date()
        location.configure(for: sport)
        location.startRecording()
        motion.start(trackSteps: sport.isFoot)
        startTimer()
        recoveryAvailable = nil
    }

    func dismissRecovery() {
        Store.clearRecovery()
        recoveryAvailable = nil
    }

    // MARK: - Timer

    private func startTimer() {
        stopTimer()
        let t = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func tick() {
        guard state.isActive else { return }
        let now = Date()
        let dt = lastTick.map { now.timeIntervalSince($0) } ?? 1
        lastTick = now
        guard dt > 0, dt < 60 else { return }

        elapsed += dt
        if state == .recording {
            movingTime += dt
            currentSplitTime = movingTime - splitAnchorTime
            currentSplitDistance = distance - splitAnchorDistance
        }

        // Cadence from the pedometer while running.
        if sport.isFoot && motion.cadence > 0 {
            cadence = motion.cadence
        }
        resolveHeartRate()
        if heartRate > 30 { hrSamples.append(heartRate) }

        updateCalories()
        checkAutoPause(dt: dt)
        checkTimeSplit()
        checkPaceAlert()
        writeRecoveryIfNeeded()
    }

    /// A worn chest strap wins outright. Otherwise take what Apple Health has,
    /// which on a wrist means whatever your Watch has synced across. Anything
    /// stale is dropped rather than left frozen on screen looking live.
    private func resolveHeartRate() {
        if heartRateMonitor.isConnected, heartRateMonitor.heartRate > 30 {
            heartRate = heartRateMonitor.heartRate
            heartRateSource = .strap
            return
        }
        if let health, health.hasFreshHeartRate {
            heartRate = health.latestHeartRate
            heartRateSource = health.latestFromWatch ? .watch : .health
            return
        }
        heartRate = 0
        heartRateSource = .none
    }

    private func updateCalories() {
        let avgHR = hrSamples.isEmpty ? nil : hrSamples.reduce(0, +) / Double(hrSamples.count)
        calories = EffortScore.calories(profile: settings.profile, sport: sport,
                                        movingTime: movingTime, avgHR: avgHR,
                                        avgSpeed: movingTime > 0 ? distance / movingTime : 0)
    }

    private func checkAutoPause(dt: Double) {
        guard settings.display.autoPause, !sport.isIndoor else { return }
        let threshold = sport.autoPauseThreshold
        if state == .recording {
            if currentSpeed < threshold {
                slowSeconds += dt
                if slowSeconds >= 4 { pause(automatic: true); slowSeconds = 0 }
            } else {
                slowSeconds = 0
            }
        } else if state == .autoPaused {
            if currentSpeed > threshold * 1.4 {
                fastSeconds += dt
                if fastSeconds >= 2 { resume(automatic: true); fastSeconds = 0 }
            } else {
                fastSeconds = 0
            }
        }
    }

    // MARK: - Location handling

    private func handle(location loc: CLLocation) {
        let coord = Coord(loc.coordinate)
        DispatchQueue.main.async { self.currentCoord = coord }
        guard state.isActive else {
            liveSegments.updateNearby(coord: coord)
            return
        }
        guard loc.horizontalAccuracy <= settings.display.gpsAccuracyFilter else { return }

        let now = Date().timeIntervalSince(startDate)
        guard now > lastPointTime else { return }
        let dt = lastPointTime < 0 ? 1 : now - lastPointTime

        // Altitude: barometer if the phone has one, GPS otherwise.
        let rawAltitude: Double
        if motion.hasBarometer {
            if barometerAnchor == nil { barometerAnchor = loc.altitude - motion.relativeAltitude }
            rawAltitude = (barometerAnchor ?? loc.altitude) + motion.relativeAltitude
        } else {
            if lastAltitude == nil { altitudeFilter = Smooth.Kalman1D(initial: loc.altitude) }
            rawAltitude = altitudeFilter.update(loc.altitude,
                                                measurementNoise: Swift.max(2, loc.verticalAccuracy))
        }
        let newAltitude = rawAltitude

        // Distance: only count movement that clears the noise floor for this fix.
        var increment = 0.0
        var instantaneousSpeed = loc.speed >= 0 ? loc.speed : 0
        if let anchor = pendingDistanceAnchor ?? lastAcceptedLocation {
            let raw = loc.distance(from: anchor)
            let floor = Swift.max(1.5, loc.horizontalAccuracy * 0.35)
            let dtAnchor = Swift.max(0.5, loc.timestamp.timeIntervalSince(anchor.timestamp))
            let impliedSpeed = raw / dtAnchor
            if impliedSpeed > sport.maxPlausibleSpeed * 1.6 {
                // A GPS jump. Drop it and re-anchor.
                pendingDistanceAnchor = loc
                lastPointTime = now
                return
            }
            if raw >= floor {
                increment = raw
                pendingDistanceAnchor = nil
                lastAcceptedLocation = loc
                if loc.speed < 0 { instantaneousSpeed = raw / dtAnchor }
            } else {
                // Hold the anchor so slow, real movement still accumulates.
                pendingDistanceAnchor = anchor
            }
        } else {
            lastAcceptedLocation = loc
        }

        if state == .autoPaused && increment > 0 && instantaneousSpeed > sport.autoPauseThreshold * 1.4 {
            resume(automatic: true)
        }

        let moving = state == .recording && instantaneousSpeed >= sport.autoPauseThreshold * 0.6

        if moving {
            distance += increment
        }

        // Elevation, with a dead band so a flat run does not "climb".
        if elevationReference == nil { elevationReference = newAltitude }
        if let reference = elevationReference {
            let delta = newAltitude - reference
            if delta > 1.0 {
                elevationGain += delta
                elevationReference = newAltitude
            } else if delta < -1.0 {
                elevationLoss += -delta
                elevationReference = newAltitude
            }
        }

        // Grade over the last stretch, for power and grade adjusted pace.
        if let previous = lastAltitude, increment > 1 {
            let raw = (newAltitude - previous) / increment
            currentGrade = currentGrade * 0.7 + Swift.max(-0.45, Swift.min(0.45, raw)) * 0.3
        }
        lastAltitude = newAltitude

        // Power estimate.
        let acceleration = dt > 0 ? (instantaneousSpeed - currentSpeed) / dt : 0
        let watts: Double
        if sport.isRide {
            let bikeMass = store?.defaultGear(for: sport).map { $0.weightKg > 0 ? $0.weightKg : settings.profile.bikeWeightKg }
                ?? settings.profile.bikeWeightKg
            watts = PowerModel.cyclingWatts(speed: instantaneousSpeed,
                                            gradient: currentGrade,
                                            acceleration: acceleration,
                                            totalMassKg: settings.profile.weightKg + bikeMass,
                                            crr: settings.profile.rollingResistance,
                                            cda: settings.profile.dragArea)
        } else {
            watts = PowerModel.runningWatts(speed: instantaneousSpeed,
                                            gradient: currentGrade,
                                            massKg: settings.profile.weightKg)
        }
        if watts > 0 { powerSamples.append(watts) }

        DispatchQueue.main.async {
            self.currentSpeed = instantaneousSpeed
            self.altitude = newAltitude
            self.power = watts
        }

        let point = TrackPoint(t: now,
                               lat: coord.lat,
                               lon: coord.lon,
                               alt: newAltitude,
                               d: distance,
                               v: instantaneousSpeed,
                               hr: heartRate > 30 ? heartRate : nil,
                               cad: cadence > 5 ? cadence : nil,
                               pw: watts > 0 ? watts : nil,
                               moving: moving,
                               acc: loc.horizontalAccuracy)
        points.append(point)
        path.append(coord)
        lastPointTime = now

        checkDistanceSplit()
        checkHalfway()
        updateLiveSegments(coord: coord, dt: dt)
    }

    // MARK: - Splits

    private var splitDistanceMeters: Double {
        Swift.max(50, settings.voice.distanceInterval * settings.units.metersPerUnit)
    }

    private func checkDistanceSplit() {
        let trigger = settings.voice.trigger
        guard trigger == .distance || trigger == .both else { return }
        while distance >= nextDistanceMark {
            splitCounter += 1
            let splitTime = movingTime - splitAnchorTime
            let splitDistance = distance - splitAnchorDistance
            recordSplit(index: splitCounter, distance: splitDistance, time: splitTime)
            var snapshot = makeSnapshot()
            snapshot.splitIndex = splitCounter
            snapshot.splitTime = splitTime
            snapshot.splitDistance = splitDistance
            snapshot.splitPaceSecondsPerUnit = splitDistance > 1
                ? splitTime / (splitDistance / settings.units.metersPerUnit) : 0
            lastSplitPace = snapshot.splitPaceSecondsPerUnit
            coach.announceSplit(snapshot, isTimeInterval: false)

            splitAnchorDistance = distance
            splitAnchorTime = movingTime
            splitAnchorIndex = points.count - 1
            nextDistanceMark += splitDistanceMeters
        }
    }

    private func checkTimeSplit() {
        let trigger = settings.voice.trigger
        guard trigger == .time || trigger == .both else { return }
        guard settings.voice.timeInterval > 0 else { return }
        while movingTime >= nextTimeMark {
            splitCounter += 1
            var snapshot = makeSnapshot()
            snapshot.splitIndex = splitCounter
            snapshot.splitTime = settings.voice.timeInterval
            snapshot.splitDistance = distance - splitAnchorDistance
            if snapshot.splitDistance > 1 {
                snapshot.splitPaceSecondsPerUnit = settings.voice.timeInterval / (snapshot.splitDistance / settings.units.metersPerUnit)
            }
            coach.announceSplit(snapshot, isTimeInterval: true)
            splitAnchorDistance = distance
            splitAnchorTime = movingTime
            nextTimeMark += settings.voice.timeInterval
        }
    }

    private func recordSplit(index: Int, distance splitDistance: Double, time splitTime: TimeInterval) {
        let slice = splitAnchorIndex < points.count ? Array(points[splitAnchorIndex...]) : []
        let elevations = slice.map(\.alt)
        let change = Smooth.elevationChange(elevations, threshold: 1.0)
        let hrValues = slice.compactMap(\.hr)
        let pwValues = slice.compactMap(\.pw)
        let cadValues = slice.compactMap(\.cad)
        var split = Split(index: index,
                          distance: splitDistance,
                          elapsed: splitTime,
                          moving: splitTime,
                          elevGain: change.gain,
                          elevLoss: change.loss,
                          avgHR: hrValues.isEmpty ? nil : hrValues.reduce(0, +) / Double(hrValues.count),
                          avgPower: pwValues.isEmpty ? nil : pwValues.reduce(0, +) / Double(pwValues.count),
                          avgCadence: cadValues.isEmpty ? nil : cadValues.reduce(0, +) / Double(cadValues.count))
        if slice.count > 2 {
            let streams = Streams(slice)
            split.gapSeconds = GradeAdjustedPace.adjustedPace(streams: streams, from: 0, to: streams.count - 1)
                .map { $0 * settings.units.metersPerUnit / 1000 }
        }
        splits.append(split)
    }

    private func checkHalfway() {
        guard settings.voice.announceHalfway, !halfwayAnnounced else { return }
        // Halfway only means something against a goal distance.
        guard let goal = store?.goals.first(where: { $0.metric == .distance && $0.isActive }) else { return }
        let target = goal.target
        guard target > 0, distance >= target / 2 else { return }
        halfwayAnnounced = true
        coach.announceHalfway(makeSnapshot())
    }

    private func checkPaceAlert() {
        guard settings.voice.targetPaceAlertsEnabled, settings.voice.targetPaceSeconds > 0 else { return }
        guard Date().timeIntervalSince(lastPaceAlert) > 45 else { return }
        guard currentSpeed > 0.4 else { return }
        let current = settings.units.metersPerUnit / currentSpeed
        let target = settings.voice.targetPaceSeconds
        if abs(current - target) > settings.voice.targetPaceToleranceSeconds {
            lastPaceAlert = Date()
            coach.announcePaceAlert(current: current, target: target)
        }
    }

    // MARK: - Live segments

    private func updateLiveSegments(coord: Coord, dt: Double) {
        guard settings.display.showLiveSegments else { return }
        let events = liveSegments.update(coord: coord, elapsed: movingTime,
                                         totalDistance: distance, dt: dt)
        guard settings.display.liveSegmentVoice else { return }
        for event in events {
            switch event {
            case .started(let name, let pr):
                coach.announceSegmentStart(name: name, prTime: pr)
            case .progress(let delta, let remaining):
                coach.announceSegmentProgress(deltaSeconds: delta, remaining: remaining, units: settings.units)
            case .finished(let name, let time, let isPR, let delta):
                coach.announceSegmentFinish(name: name, time: time, isPR: isPR, delta: delta)
            case .abandoned:
                break
            }
        }
    }

    // MARK: - Snapshot

    func makeSnapshot() -> CoachSnapshot {
        var s = CoachSnapshot()
        s.sport = sport
        s.totalDistance = distance
        s.totalTime = elapsed
        s.movingTime = movingTime
        s.currentSpeed = currentSpeed
        s.averageSpeed = movingTime > 0 ? distance / movingTime : 0
        let unit = settings.units.metersPerUnit
        if distance > 1 && movingTime > 0 {
            s.averagePaceSecondsPerUnit = movingTime / (distance / unit)
        }
        if currentSpeed > 0.2 {
            s.currentPaceSecondsPerUnit = unit / currentSpeed
            s.gradeAdjustedPaceSecondsPerUnit = s.currentPaceSecondsPerUnit / GradeAdjustedPace.factor(gradient: currentGrade)
        }
        s.heartRate = heartRate
        s.averageHeartRate = hrSamples.isEmpty ? 0 : hrSamples.reduce(0, +) / Double(hrSamples.count)
        s.cadence = cadence
        s.power = power
        s.averagePower = powerSamples.isEmpty ? 0 : powerSamples.reduce(0, +) / Double(powerSamples.count)
        s.elevationGain = elevationGain
        s.calories = calories
        return s
    }

    /// Read the current numbers out on demand, from the record screen's speaker button.
    func speakCurrentStats() {
        var snapshot = makeSnapshot()
        snapshot.splitIndex = splitCounter + 1
        coach.announceSplit(snapshot, isTimeInterval: false)
    }

    // MARK: - Crash recovery

    private func writeRecoveryIfNeeded() {
        guard Date().timeIntervalSince(lastRecoveryWrite) > 10 else { return }
        lastRecoveryWrite = Date()
        let snapshot = Store.RecoverySnapshot(activityID: activityID,
                                              sport: sport,
                                              startDate: startDate,
                                              points: points,
                                              elapsed: elapsed,
                                              pausedDuration: elapsed - movingTime,
                                              laps: laps,
                                              savedAt: Date())
        DispatchQueue.global(qos: .utility).async {
            Store.writeRecovery(snapshot)
        }
    }

    // MARK: - Derived display values

    var averagePaceSecondsPerUnit: Double {
        guard distance > 1, movingTime > 0 else { return 0 }
        return movingTime / (distance / settings.units.metersPerUnit)
    }

    var currentPaceSecondsPerUnit: Double {
        guard currentSpeed > 0.2 else { return 0 }
        return settings.units.metersPerUnit / currentSpeed
    }

    var averageSpeedValue: Double {
        movingTime > 0 ? distance / movingTime : 0
    }
}
