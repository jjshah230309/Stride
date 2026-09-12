import Foundation

// MARK: - Voice coach

enum AnnouncementTrigger: String, Codable, CaseIterable, Identifiable {
    case distance
    case time
    case both
    case off

    var id: String { rawValue }
    var name: String {
        switch self {
        case .distance: return "Every split"
        case .time: return "Every interval"
        case .both: return "Splits and intervals"
        case .off: return "Off"
        }
    }
}

/// Everything the coach can read out. Order here is the order it speaks.
enum SpokenMetric: String, Codable, CaseIterable, Identifiable {
    case splitNumber
    case splitPace
    case splitTime
    case totalDistance
    case totalTime
    case averagePace
    case currentPace
    case averageSpeed
    case currentSpeed
    case heartRate
    case averageHeartRate
    case cadence
    case power
    case averagePower
    case elevationGain
    case calories
    case gradeAdjustedPace

    var id: String { rawValue }

    var name: String {
        switch self {
        case .splitNumber: return "Split number"
        case .splitPace: return "Split pace"
        case .splitTime: return "Split time"
        case .totalDistance: return "Total distance"
        case .totalTime: return "Total time"
        case .averagePace: return "Average pace"
        case .currentPace: return "Current pace"
        case .averageSpeed: return "Average speed"
        case .currentSpeed: return "Current speed"
        case .heartRate: return "Heart rate"
        case .averageHeartRate: return "Average heart rate"
        case .cadence: return "Cadence"
        case .power: return "Power"
        case .averagePower: return "Average power"
        case .elevationGain: return "Elevation gain"
        case .calories: return "Calories"
        case .gradeAdjustedPace: return "Grade adjusted pace"
        }
    }

    var suitsRunning: Bool {
        switch self {
        case .averageSpeed, .currentSpeed: return false
        default: return true
        }
    }

    var suitsCycling: Bool {
        switch self {
        case .splitPace, .averagePace, .currentPace, .gradeAdjustedPace: return false
        default: return true
        }
    }

    static var runDefaults: [SpokenMetric] {
        [.splitNumber, .splitTime, .splitPace, .averagePace]
    }

    static var rideDefaults: [SpokenMetric] {
        [.totalDistance, .totalTime, .averageSpeed, .averagePower]
    }
}

struct VoiceSettings: Codable, Hashable {
    var enabled: Bool = true
    var trigger: AnnouncementTrigger = .distance
    /// In display distance units — 1.0 means every kilometre or every mile.
    var distanceInterval: Double = 1.0
    /// In seconds.
    var timeInterval: TimeInterval = 300

    var runMetrics: [SpokenMetric] = SpokenMetric.runDefaults
    var rideMetrics: [SpokenMetric] = SpokenMetric.rideDefaults

    var announceStart: Bool = true
    var announcePauseResume: Bool = true
    var announceFinish: Bool = true
    var announceAutoPause: Bool = false
    var announceLaps: Bool = true
    var announceSegments: Bool = true
    var announceGoalMilestones: Bool = true
    var announceHalfway: Bool = false
    var countdownBeforeStart: Bool = true

    /// Target pace nagging, in seconds per display unit. Zero disables it.
    var targetPaceSeconds: Double = 0
    var targetPaceToleranceSeconds: Double = 15
    var targetPaceAlertsEnabled: Bool = false
    var targetHeartRateZone: Int = 0        // 0 = off, otherwise 1…5
    var zoneAlertsEnabled: Bool = false

    /// How much scaffolding goes around the numbers.
    var style: SpeechStyle = .natural
    /// The occasional "nicely on pace" after a split.
    var coachRemarks: Bool = false

    var rate: Double = 0.5                  // AVSpeechUtterance rate, 0…1
    var pitch: Double = 1.0
    var volume: Double = 1.0
    var voiceIdentifier: String = ""        // empty means the system default
    var duckMusic: Bool = true
    var useVoiceWhileLocked: Bool = true

    func metrics(for sport: SportType) -> [SpokenMetric] {
        sport.usesPace ? runMetrics : rideMetrics
    }
}

// MARK: - Zones

struct HeartRateZones: Codable, Hashable {
    /// Upper bound of each of the five zones, as a fraction of max HR.
    var bounds: [Double] = [0.60, 0.70, 0.80, 0.90, 1.20]
    var maxHR: Double = 190
    var restingHR: Double = 60
    var lactateThresholdHR: Double = 170
    var useThresholdBased: Bool = false

    static let names = ["Endurance", "Moderate", "Tempo", "Threshold", "Anaerobic"]
    static let shortNames = ["Z1", "Z2", "Z3", "Z4", "Z5"]

    /// Lower and upper bpm for a zone index 0…4.
    func range(_ index: Int) -> (Double, Double) {
        let reference = useThresholdBased ? lactateThresholdHR : maxHR
        let thresholdBounds: [Double] = [0.81, 0.89, 0.94, 1.00, 1.30]
        let b = useThresholdBased ? thresholdBounds : bounds
        let lower = index == 0 ? 0 : b[index - 1] * reference
        let upper = b[Swift.min(index, b.count - 1)] * reference
        return (lower, upper)
    }

    func zone(for hr: Double) -> Int {
        for i in 0..<5 {
            let (_, upper) = range(i)
            if hr < upper { return i }
        }
        return 4
    }
}

struct PowerZones: Codable, Hashable {
    var ftp: Double = 200
    /// Coggan's seven zones as fractions of FTP.
    var bounds: [Double] = [0.55, 0.75, 0.90, 1.05, 1.20, 1.50, 4.00]

    static let names = ["Active Recovery", "Endurance", "Tempo", "Threshold", "VO2 Max", "Anaerobic", "Neuromuscular"]
    static let shortNames = ["Z1", "Z2", "Z3", "Z4", "Z5", "Z6", "Z7"]

    func range(_ index: Int) -> (Double, Double) {
        let lower = index == 0 ? 0 : bounds[index - 1] * ftp
        let upper = bounds[Swift.min(index, bounds.count - 1)] * ftp
        return (lower, upper)
    }

    func zone(for watts: Double) -> Int {
        for i in 0..<bounds.count {
            if watts < bounds[i] * ftp { return i }
        }
        return bounds.count - 1
    }
}

struct PaceZones: Codable, Hashable {
    /// Threshold pace in seconds per kilometre.
    var thresholdPace: Double = 300
    /// Upper bound of each zone as a multiple of threshold pace (slower = larger).
    var bounds: [Double] = [1.29, 1.14, 1.06, 1.00, 0.90]

    static let names = ["Easy", "Moderate", "Tempo", "Threshold", "Interval"]
    static let shortNames = ["Z1", "Z2", "Z3", "Z4", "Z5"]

    func zone(for secondsPerKm: Double) -> Int {
        guard secondsPerKm > 0 else { return 0 }
        for i in 0..<bounds.count {
            if secondsPerKm > bounds[i] * thresholdPace { return i }
        }
        return bounds.count - 1
    }
}

// MARK: - Athlete profile and app settings

struct AthleteProfile: Codable, Hashable {
    var name: String = ""
    var weightKg: Double = 70
    var heightCm: Double = 175
    var birthYear: Int = 1995
    var isMale: Bool = true

    var hrZones: HeartRateZones = HeartRateZones()
    var powerZones: PowerZones = PowerZones()
    var paceZones: PaceZones = PaceZones()

    /// Bike plus kit mass added to the rider for the cycling power estimate.
    var bikeWeightKg: Double = 9.0
    var rollingResistance: Double = 0.005
    var dragArea: Double = 0.32              // CdA in m²

    var age: Int {
        Swift.max(10, Calendar.current.component(.year, from: Date()) - birthYear)
    }

    /// Tanaka formula — a better predictor than 220 minus age.
    var predictedMaxHR: Double { 208 - 0.7 * Double(age) }
}

struct DisplaySettings: Codable, Hashable {
    var units: UnitSystem = .metric
    var autoPause: Bool = true
    var keepScreenOn: Bool = true
    var countdownSeconds: Int = 3
    var mapStyle: String = "standard"        // standard, hybrid, satellite
    var showLiveSegments: Bool = true
    var liveSegmentVoice: Bool = true
    var recordingMetrics: [String] = ["duration", "distance", "pace", "heartRate", "elevation", "calories"]
    var gpsAccuracyFilter: Double = 30       // discard fixes worse than this, in metres
    var writeToAppleHealth: Bool = true
    var readFromAppleHealth: Bool = true
    /// After saving, re-read heart rate from Health for the whole activity, which
    /// picks up everything an Apple Watch recorded even if it synced late.
    var backfillHeartRateFromHealth: Bool = true
    var defaultSport: SportType = .run
    var weekStartsMonday: Bool = true
    var hasCompletedOnboarding: Bool = false
}

/// How often a copy of everything is written, and how many are kept.
struct BackupSettings: Codable, Hashable {
    var automatic: Bool = false
    /// Days between backups. Months are expressed as 30, 90.
    var intervalDays: Int = 7
    var keepCount: Int = 5
    var lastRun: Date?
    var lastFilename: String = ""
    var lastActivityCount: Int = 0

    static let intervalChoices: [(Int, String)] = [
        (1, "Every day"),
        (3, "Every 3 days"),
        (7, "Every week"),
        (14, "Every 2 weeks"),
        (30, "Every month"),
        (90, "Every 3 months")
    ]

    var intervalName: String {
        BackupSettings.intervalChoices.first { $0.0 == intervalDays }?.1
            ?? "Every \(intervalDays) days"
    }

    func isDue(now: Date = Date()) -> Bool {
        guard automatic else { return false }
        guard let last = lastRun else { return true }
        return now.timeIntervalSince(last) >= Double(intervalDays) * 86400
    }

    func nextDue(now: Date = Date()) -> Date? {
        guard automatic else { return nil }
        guard let last = lastRun else { return now }
        return last.addingTimeInterval(Double(intervalDays) * 86400)
    }
}

struct AppSettings: Codable, Hashable {
    var profile: AthleteProfile = AthleteProfile()
    var display: DisplaySettings = DisplaySettings()
    var voice: VoiceSettings = VoiceSettings()
    var backup: BackupSettings = BackupSettings()

    var units: UnitSystem {
        get { display.units }
        set { display.units = newValue }
    }

    init() {}

    /// Every section is optional on the way in, so adding one in a later version
    /// cannot make an existing settings file unreadable.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        profile = (try? container.decode(AthleteProfile.self, forKey: .profile)) ?? AthleteProfile()
        display = (try? container.decode(DisplaySettings.self, forKey: .display)) ?? DisplaySettings()
        voice = (try? container.decode(VoiceSettings.self, forKey: .voice)) ?? VoiceSettings()
        backup = (try? container.decode(BackupSettings.self, forKey: .backup)) ?? BackupSettings()
    }
}
