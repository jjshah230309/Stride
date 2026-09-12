import Foundation
import CoreLocation
import Combine

/// Wraps CLLocationManager with the settings a GPS tracker actually needs:
/// navigation-grade accuracy, no automatic pausing, and background updates so
/// the track keeps building with the phone in a pocket.
final class LocationEngine: NSObject, ObservableObject, CLLocationManagerDelegate {

    @Published var authorization: CLAuthorizationStatus = .notDetermined
    @Published var latest: CLLocation?
    @Published var accuracy: Double = -1
    @Published var isRunning: Bool = false
    /// Rough signal quality, 0 (none) to 3 (strong), for the GPS pips in the UI.
    @Published var signalStrength: Int = 0
    @Published var heading: Double = 0

    /// Every accepted fix is handed to this closure.
    var onLocation: ((CLLocation) -> Void)?

    private let manager = CLLocationManager()
    private var warmupFixes = 0

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .fitness
        authorization = manager.authorizationStatus
    }

    var isAuthorized: Bool {
        authorization == .authorizedAlways || authorization == .authorizedWhenInUse
    }

    func requestAuthorization() {
        manager.requestWhenInUseAuthorization()
    }

    /// Ask for Always so the track survives the phone locking for a long ride.
    func requestAlwaysAuthorization() {
        manager.requestAlwaysAuthorization()
    }

    func configure(for sport: SportType) {
        manager.activityType = sport.isRide ? .otherNavigation : .fitness
    }

    /// Start receiving fixes without recording, so the GPS has locked on before
    /// the athlete presses start.
    func startWarmup() {
        guard isAuthorized else {
            requestAuthorization()
            return
        }
        warmupFixes = 0
        manager.startUpdatingLocation()
        manager.startUpdatingHeading()
        isRunning = true
    }

    func startRecording() {
        guard isAuthorized else {
            requestAuthorization()
            return
        }
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        manager.startUpdatingHeading()
        isRunning = true
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
        manager.allowsBackgroundLocationUpdates = false
        isRunning = false
        signalStrength = 0
    }

    // MARK: - Delegate

    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        DispatchQueue.main.async { self.authorization = status }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        DispatchQueue.main.async { self.authorization = manager.authorizationStatus }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard newHeading.headingAccuracy >= 0 else { return }
        DispatchQueue.main.async { self.heading = newHeading.trueHeading }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let horizontal = location.horizontalAccuracy

        DispatchQueue.main.async {
            self.latest = location
            self.accuracy = horizontal
            self.signalStrength = Self.strength(for: horizontal)
        }

        // Reject rubbish: no fix, stale cached fixes, and the first couple of
        // wildly inaccurate readings a cold GPS produces.
        guard horizontal > 0, horizontal < 100 else { return }
        guard abs(location.timestamp.timeIntervalSinceNow) < 5 else { return }
        if warmupFixes < 2 && horizontal > 30 {
            warmupFixes += 1
            return
        }
        warmupFixes = 5
        onLocation?(location)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("Stride: location error \(error.localizedDescription)")
    }

    static func strength(for accuracy: Double) -> Int {
        switch accuracy {
        case ..<0: return 0
        case 0..<8: return 3
        case 8..<20: return 2
        case 20..<45: return 1
        default: return 0
        }
    }
}
