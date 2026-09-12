import Foundation
import HealthKit
import CoreLocation
import Combine

/// Two-way Apple Health support: heart rate in while you record, a proper workout
/// with its GPS route out when you finish.
final class HealthKitManager: NSObject, ObservableObject {

    @Published var isAuthorized: Bool = false
    @Published var latestHeartRate: Double = 0
    @Published var lastError: String?

    let store = HKHealthStore()
    private var heartRateQuery: HKAnchoredObjectQuery?
    private var routeBuilder: HKWorkoutRouteBuilder?

    static var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    private var readTypes: Set<HKObjectType> {
        var types = Set<HKObjectType>()
        if let hr = HKObjectType.quantityType(forIdentifier: .heartRate) { types.insert(hr) }
        if let resting = HKObjectType.quantityType(forIdentifier: .restingHeartRate) { types.insert(resting) }
        if let vo2 = HKObjectType.quantityType(forIdentifier: .vo2Max) { types.insert(vo2) }
        if let mass = HKObjectType.quantityType(forIdentifier: .bodyMass) { types.insert(mass) }
        if let height = HKObjectType.quantityType(forIdentifier: .height) { types.insert(height) }
        if let energy = HKObjectType.quantityType(forIdentifier: .activeEnergyBurned) { types.insert(energy) }
        types.insert(HKObjectType.workoutType())
        return types
    }

    private var writeTypes: Set<HKSampleType> {
        var types = Set<HKSampleType>()
        types.insert(HKObjectType.workoutType())
        types.insert(HKSeriesType.workoutRoute())
        if let energy = HKObjectType.quantityType(forIdentifier: .activeEnergyBurned) { types.insert(energy) }
        if let run = HKObjectType.quantityType(forIdentifier: .distanceWalkingRunning) { types.insert(run) }
        if let cycle = HKObjectType.quantityType(forIdentifier: .distanceCycling) { types.insert(cycle) }
        return types
    }

    /// True once we have been granted permission to write workouts.
    var canWriteWorkouts: Bool {
        guard Self.isAvailable else { return false }
        return store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized
    }

    /// Asks once, the first time recording starts, rather than at launch.
    func requestAuthorizationIfNeeded(_ completion: ((Bool) -> Void)? = nil) {
        guard Self.isAvailable else {
            completion?(false)
            return
        }
        if store.authorizationStatus(for: HKObjectType.workoutType()) == .notDetermined {
            requestAuthorization(completion)
        } else {
            isAuthorized = true
            completion?(true)
        }
    }

    func requestAuthorization(_ completion: ((Bool) -> Void)? = nil) {
        guard Self.isAvailable else {
            completion?(false)
            return
        }
        store.requestAuthorization(toShare: writeTypes, read: readTypes) { [weak self] success, error in
            DispatchQueue.main.async {
                self?.isAuthorized = success
                if let error { self?.lastError = error.localizedDescription }
                completion?(success)
            }
        }
    }

    // MARK: - Reading profile values

    func fetchLatestBodyMass(_ completion: @escaping (Double?) -> Void) {
        guard Self.isAvailable, let type = HKObjectType.quantityType(forIdentifier: .bodyMass) else {
            completion(nil)
            return
        }
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
        let query = HKSampleQuery(sampleType: type, predicate: nil, limit: 1, sortDescriptors: [sort]) { _, samples, _ in
            let kg = (samples?.first as? HKQuantitySample)?.quantity.doubleValue(for: .gramUnit(with: .kilo))
            DispatchQueue.main.async { completion(kg) }
        }
        store.execute(query)
    }

    func fetchRestingHeartRate(_ completion: @escaping (Double?) -> Void) {
        guard Self.isAvailable, let type = HKObjectType.quantityType(forIdentifier: .restingHeartRate) else {
            completion(nil)
            return
        }
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
        let query = HKSampleQuery(sampleType: type, predicate: nil, limit: 1, sortDescriptors: [sort]) { _, samples, _ in
            let unit = HKUnit.count().unitDivided(by: .minute())
            let bpm = (samples?.first as? HKQuantitySample)?.quantity.doubleValue(for: unit)
            DispatchQueue.main.async { completion(bpm) }
        }
        store.execute(query)
    }

    // MARK: - Live heart rate

    /// When the most recent sample was actually measured, not when it arrived.
    @Published private(set) var latestHeartRateAt: Date?
    /// True when that sample came from an Apple Watch rather than the phone or
    /// another app.
    @Published private(set) var latestFromWatch: Bool = false

    /// A watch in a workout writes every few seconds, and syncing adds a little
    /// more. Anything older than this is stale and should not be shown as live.
    private let freshnessWindow: TimeInterval = 45

    var hasFreshHeartRate: Bool {
        guard latestHeartRate > 30, let latestHeartRateAt else { return false }
        return Date().timeIntervalSince(latestHeartRateAt) < freshnessWindow
    }

    private static let beatsPerMinute = HKUnit.count().unitDivided(by: .minute())

    /// Was this sample measured by a watch on a wrist?
    private static func isFromWatch(_ sample: HKQuantitySample) -> Bool {
        if let model = sample.device?.model, model.localizedCaseInsensitiveContains("watch") {
            return true
        }
        if let product = sample.sourceRevision.productType,
           product.localizedCaseInsensitiveContains("watch") {
            return true
        }
        return sample.sourceRevision.source.name.localizedCaseInsensitiveContains("watch")
    }

    /// Streams heart rate written while you record. On a wrist that means your
    /// Apple Watch — which only measures every few seconds once you have started
    /// a workout on the watch itself; outside a workout it reads every several
    /// minutes to save power, and no iPhone app can change that.
    func startHeartRateStream() {
        guard Self.isAvailable, let type = HKObjectType.quantityType(forIdentifier: .heartRate) else { return }
        stopHeartRateStream()

        // Show the last known reading straight away rather than a dash.
        seedLatestHeartRate()

        let predicate = HKQuery.predicateForSamples(withStart: Date().addingTimeInterval(-60),
                                                    end: nil,
                                                    options: .strictStartDate)

        let handler: (HKAnchoredObjectQuery, [HKSample]?, [HKDeletedObject]?, HKQueryAnchor?, Error?) -> Void = { [weak self] _, samples, _, _, _ in
            self?.absorb(samples)
        }

        let query = HKAnchoredObjectQuery(type: type, predicate: predicate,
                                          anchor: nil, limit: HKObjectQueryNoLimit,
                                          resultsHandler: handler)
        query.updateHandler = handler
        heartRateQuery = query
        store.execute(query)

        // Helps samples arrive while the screen is locked. Harmless if refused.
        store.enableBackgroundDelivery(for: type, frequency: .immediate) { _, error in
            if let error {
                NSLog("Stride: background heart rate delivery unavailable — \(error.localizedDescription)")
            }
        }
    }

    /// Batches can arrive unsorted, so take the genuinely newest, not the last.
    private func absorb(_ samples: [HKSample]?) {
        guard let quantities = samples as? [HKQuantitySample], !quantities.isEmpty else { return }
        guard let newest = quantities.max(by: { $0.endDate < $1.endDate }) else { return }
        let value = newest.quantity.doubleValue(for: Self.beatsPerMinute)
        guard value > 30, value < 240 else { return }
        let measuredAt = newest.endDate
        let fromWatch = Self.isFromWatch(newest)

        DispatchQueue.main.async {
            // Never let an out-of-order batch drag the reading backwards in time.
            if let existing = self.latestHeartRateAt, existing > measuredAt { return }
            self.latestHeartRate = value
            self.latestHeartRateAt = measuredAt
            self.latestFromWatch = fromWatch
        }
    }

    private func seedLatestHeartRate() {
        guard let type = HKObjectType.quantityType(forIdentifier: .heartRate) else { return }
        let recent = HKQuery.predicateForSamples(withStart: Date().addingTimeInterval(-600),
                                                  end: nil, options: .strictStartDate)
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
        let query = HKSampleQuery(sampleType: type, predicate: recent, limit: 1,
                                  sortDescriptors: [sort]) { [weak self] _, samples, _ in
            self?.absorb(samples)
        }
        store.execute(query)
    }

    func stopHeartRateStream() {
        if let heartRateQuery { store.stop(heartRateQuery) }
        heartRateQuery = nil
        latestHeartRate = 0
        latestHeartRateAt = nil
        latestFromWatch = false
    }

    // MARK: - Filling in heart rate after the fact

    /// Every heart rate sample across a window, oldest first.
    func heartRateSamples(from start: Date, to end: Date,
                          completion: @escaping ([(date: Date, value: Double, fromWatch: Bool)]) -> Void) {
        guard Self.isAvailable, let type = HKObjectType.quantityType(forIdentifier: .heartRate) else {
            completion([])
            return
        }
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: [])
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: true)
        let query = HKSampleQuery(sampleType: type, predicate: predicate,
                                  limit: HKObjectQueryNoLimit, sortDescriptors: [sort]) { _, samples, _ in
            let quantities = (samples as? [HKQuantitySample]) ?? []
            let mapped = quantities.compactMap { sample -> (Date, Double, Bool)? in
                let value = sample.quantity.doubleValue(for: Self.beatsPerMinute)
                guard value > 30, value < 240 else { return nil }
                return (sample.endDate, value, Self.isFromWatch(sample))
            }
            DispatchQueue.main.async { completion(mapped) }
        }
        store.execute(query)
    }

    struct BackfillResult {
        var points: [TrackPoint]
        var filled: Int
        var fromWatch: Bool

        var coverage: Double {
            points.isEmpty ? 0 : Double(filled) / Double(points.count)
        }
    }

    /// Attaches recorded heart rate to a finished activity's track.
    ///
    /// Live streaming from a watch lags — samples sync over in bursts — so a
    /// recording can end with gaps or nothing at all. Reading the window back
    /// afterwards gets everything the watch actually measured. Samples already
    /// captured live, typically from a chest strap, are left alone.
    static func applyHeartRate(_ samples: [(date: Date, value: Double, fromWatch: Bool)],
                               to points: [TrackPoint],
                               activityStart: Date,
                               tolerance: TimeInterval = 20) -> BackfillResult {
        guard !samples.isEmpty, !points.isEmpty else {
            return BackfillResult(points: points, filled: 0, fromWatch: false)
        }

        var updated = points
        var filled = 0
        var watchSeen = false
        var index = 0

        // Both sequences are in time order, so one pass is enough.
        for i in updated.indices where updated[i].hr == nil {
            let target = activityStart.addingTimeInterval(updated[i].t)
            while index + 1 < samples.count,
                  abs(samples[index + 1].date.timeIntervalSince(target))
                    <= abs(samples[index].date.timeIntervalSince(target)) {
                index += 1
            }
            let candidate = samples[index]
            if abs(candidate.date.timeIntervalSince(target)) <= tolerance {
                updated[i].hr = candidate.value
                filled += 1
                if candidate.fromWatch { watchSeen = true }
            }
        }
        return BackfillResult(points: updated, filled: filled, fromWatch: watchSeen)
    }

    // MARK: - Writing workouts

    static func workoutActivityType(for sport: SportType) -> HKWorkoutActivityType {
        switch sport {
        case .run, .treadmill: return .running
        case .trailRun: return .running
        case .walk: return .walking
        case .hike: return .hiking
        case .ride, .gravelRide, .mountainBike, .eBike, .indoorRide: return .cycling
        }
    }

    /// Save a finished activity to Health, route included, so it shows up in Fitness
    /// and counts towards the move ring.
    func save(activity: Activity, points: [TrackPoint], completion: ((Bool) -> Void)? = nil) {
        guard Self.isAvailable else {
            completion?(false)
            return
        }
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = Self.workoutActivityType(for: activity.sport)
        configuration.locationType = activity.sport.isIndoor ? .indoor : .outdoor

        let builder = HKWorkoutBuilder(healthStore: store, configuration: configuration, device: .local())
        let start = activity.startDate
        let end = activity.startDate.addingTimeInterval(Swift.max(1, activity.elapsed))

        builder.beginCollection(withStart: start) { [weak self] began, error in
            guard began, let self else {
                DispatchQueue.main.async { completion?(false) }
                return
            }
            var samples: [HKSample] = []

            if activity.calories > 0,
               let energyType = HKObjectType.quantityType(forIdentifier: .activeEnergyBurned) {
                let quantity = HKQuantity(unit: .kilocalorie(), doubleValue: activity.calories)
                samples.append(HKQuantitySample(type: energyType, quantity: quantity, start: start, end: end))
            }

            if activity.distance > 0 {
                let identifier: HKQuantityTypeIdentifier = activity.sport.isRide ? .distanceCycling : .distanceWalkingRunning
                if let distanceType = HKObjectType.quantityType(forIdentifier: identifier) {
                    let quantity = HKQuantity(unit: .meter(), doubleValue: activity.distance)
                    samples.append(HKQuantitySample(type: distanceType, quantity: quantity, start: start, end: end))
                }
            }

            let finish = {
                builder.endCollection(withEnd: end) { _, _ in
                    builder.finishWorkout { workout, _ in
                        guard let workout else {
                            DispatchQueue.main.async { completion?(false) }
                            return
                        }
                        self.saveRoute(points: points, start: start, workout: workout, completion: completion)
                    }
                }
            }

            if samples.isEmpty {
                finish()
            } else {
                builder.add(samples) { _, _ in finish() }
            }
        }
    }

    private func saveRoute(points: [TrackPoint], start: Date, workout: HKWorkout, completion: ((Bool) -> Void)?) {
        let locations: [CLLocation] = points.compactMap { p in
            let coord = p.coord
            guard coord.isValid else { return nil }
            return CLLocation(coordinate: coord.clCoordinate,
                              altitude: p.alt,
                              horizontalAccuracy: Swift.max(1, p.acc),
                              verticalAccuracy: 5,
                              course: -1,
                              speed: p.v,
                              timestamp: start.addingTimeInterval(p.t))
        }
        guard locations.count > 1 else {
            DispatchQueue.main.async { completion?(true) }
            return
        }
        let builder = HKWorkoutRouteBuilder(healthStore: store, device: .local())
        builder.insertRouteData(locations) { inserted, _ in
            guard inserted else {
                DispatchQueue.main.async { completion?(true) }
                return
            }
            builder.finishRoute(with: workout, metadata: nil) { _, _ in
                DispatchQueue.main.async { completion?(true) }
            }
        }
    }
}
