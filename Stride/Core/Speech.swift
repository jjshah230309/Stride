import Foundation

/// Numbers as a person would say them out loud.
///
/// A synthesiser reading "4:32" or "154" gives you "four colon thirty two" and
/// "one hundred and fifty four". Runners say "four thirty-two" and "one
/// fifty-four", so the words are built here rather than left to the voice.
enum SpokenNumber {

    private static let ones = [
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    ]

    private static let tens = [
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    ]

    /// Conversational form: 154 becomes "one fifty-four", 205 "two oh five".
    static func casual(_ n: Int) -> String {
        if n < 0 { return "minus " + casual(-n) }
        if n < 20 { return ones[n] }
        if n < 100 {
            let remainder = n % 10
            return remainder == 0 ? tens[n / 10] : tens[n / 10] + "-" + ones[remainder]
        }
        if n < 1000 {
            let hundreds = ones[n / 100]
            let remainder = n % 100
            if remainder == 0 { return hundreds + " hundred" }
            if remainder < 10 { return hundreds + " oh " + ones[remainder] }
            return hundreds + " " + casual(remainder)
        }
        return formal(n)
    }

    /// Full form: 486 becomes "four hundred and eighty-six". Right for counts.
    static func formal(_ n: Int) -> String {
        if n < 0 { return "minus " + formal(-n) }
        if n < 100 { return casual(n) }
        if n < 1000 {
            let remainder = n % 100
            let head = ones[n / 100] + " hundred"
            return remainder == 0 ? head : head + " and " + casual(remainder)
        }
        let thousands = n / 1000
        let remainder = n % 1000
        var out = formal(thousands) + " thousand"
        if remainder > 0 {
            out += (remainder < 100 ? " and " : " ") + formal(remainder)
        }
        return out
    }

    /// Pace the way it is actually said: "four thirty-two", "four oh five",
    /// "five minutes flat".
    static func pace(_ secondsPerUnit: Double) -> String? {
        guard secondsPerUnit.isFinite, secondsPerUnit > 0, secondsPerUnit < 60 * 60 else { return nil }
        let total = Int(secondsPerUnit.rounded())
        let minutes = total / 60
        let seconds = total % 60
        if seconds == 0 { return casual(minutes) + " minutes flat" }
        if seconds < 10 { return casual(minutes) + " oh " + ones[seconds] }
        return casual(minutes) + " " + casual(seconds)
    }

    /// Durations read as a clock: "forty-two minutes", "one hour twelve".
    static func duration(_ seconds: TimeInterval, verbose: Bool = false) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "unknown" }
        let total = Int(seconds.rounded())
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60

        if verbose {
            var parts: [String] = []
            if hours > 0 { parts.append("\(casual(hours)) hour" + (hours == 1 ? "" : "s")) }
            if minutes > 0 { parts.append("\(casual(minutes)) minute" + (minutes == 1 ? "" : "s")) }
            if secs > 0 || parts.isEmpty { parts.append("\(casual(secs)) second" + (secs == 1 ? "" : "s")) }
            return parts.joined(separator: " ")
        }

        if hours > 0 {
            if minutes == 0 { return casual(hours) + (hours == 1 ? " hour" : " hours") }
            return casual(hours) + " hour" + (hours == 1 ? "" : "s") + " " + casual(minutes)
        }
        if minutes == 0 { return casual(secs) + " second" + (secs == 1 ? "" : "s") }
        if secs == 0 { return casual(minutes) + " minute" + (minutes == 1 ? "" : "s") }
        if secs < 10 { return casual(minutes) + " oh " + ones[secs] }
        return casual(minutes) + " " + casual(secs)
    }

    /// One decimal place, spoken: 28.4 becomes "twenty-eight point four".
    static func decimal(_ value: Double) -> String {
        let rounded = (value * 10).rounded() / 10
        let whole = Int(rounded)
        let tenth = Int(((rounded - Double(whole)) * 10).rounded())
        if tenth <= 0 { return formal(whole) }
        return formal(whole) + " point " + casual(tenth)
    }

    /// Distances: "five kilometres", "seven point two miles", "eight hundred metres".
    static func distance(_ meters: Double, _ unit: UnitSystem) -> String {
        let value = meters / unit.metersPerUnit
        if value < 0.995 {
            if unit == .metric { return formal(Int(meters.rounded())) + " metres" }
            return formal(Int((meters / 0.3048).rounded())) + " feet"
        }
        let rounded = (value * 10).rounded() / 10
        let whole = Int(rounded)
        let tenths = Int(((rounded - Double(whole)) * 10).rounded())
        let noun = (whole == 1 && tenths == 0) ? unit.spokenDistanceUnitSingular : unit.spokenDistanceUnit
        if tenths == 0 { return formal(whole) + " " + noun }
        if tenths == 5 { return formal(whole) + " and a half " + noun }
        return formal(whole) + " point " + ones[tenths] + " " + noun
    }
}

/// How chatty the coach is. Content is chosen by the metric list; this decides
/// how much scaffolding goes around the numbers.
enum SpeechStyle: String, Codable, CaseIterable, Identifiable {
    case brief
    case natural
    case detailed

    var id: String { rawValue }

    var name: String {
        switch self {
        case .brief: return "Brief"
        case .natural: return "Natural"
        case .detailed: return "Detailed"
        }
    }

    var explanation: String {
        switch self {
        case .brief: return "Numbers only. Fastest to hear mid-effort."
        case .natural: return "Short labels, the way a training partner would say it."
        case .detailed: return "Full sentences with units spelled out."
        }
    }
}

/// Builds SSML so the synthesiser breathes between phrases instead of running
/// everything together behind commas.
struct SpeechBuilder {
    private var phrases: [String] = []
    var breakMilliseconds: Int = 220
    var ratePercentage: Int = 100

    mutating func add(_ phrase: String?) {
        guard let phrase, !phrase.isEmpty else { return }
        phrases.append(phrase)
    }

    var isEmpty: Bool { phrases.isEmpty }

    /// Plain text: the fallback if SSML is refused, and what the settings preview
    /// shows. Commas keep it readable as one sentence.
    var plainText: String {
        guard !phrases.isEmpty else { return "" }
        var joined = phrases.joined(separator: ", ")
        if let first = joined.first {
            joined = String(first).uppercased() + joined.dropFirst()
        }
        return joined + "."
    }

    var ssml: String {
        let body = phrases
            .map { SpeechBuilder.escape($0) }
            .joined(separator: "<break time=\"\(breakMilliseconds)ms\"/>")
        if ratePercentage == 100 {
            return "<speak>\(body)</speak>"
        }
        return "<speak><prosody rate=\"\(ratePercentage)%\">\(body)</prosody></speak>"
    }

    static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}

/// The numbers the coach reads from. Filled in by the recording session.
struct CoachSnapshot {
    var sport: SportType = .run
    var splitIndex: Int = 0
    var splitDistance: Double = 0
    var splitTime: TimeInterval = 0
    var splitPaceSecondsPerUnit: Double = 0
    var totalDistance: Double = 0
    var totalTime: TimeInterval = 0
    var movingTime: TimeInterval = 0
    var averagePaceSecondsPerUnit: Double = 0
    var currentPaceSecondsPerUnit: Double = 0
    var averageSpeed: Double = 0
    var currentSpeed: Double = 0
    var heartRate: Double = 0
    var averageHeartRate: Double = 0
    var cadence: Double = 0
    var power: Double = 0
    var averagePower: Double = 0
    var elevationGain: Double = 0
    var calories: Double = 0
    var gradeAdjustedPaceSecondsPerUnit: Double = 0
}

/// Turns a snapshot into the words the coach will say. Pure text — no audio, no
/// frameworks — so the phrasing can be checked without a device.
struct CoachScript {
    var settings: VoiceSettings
    var units: UnitSystem

    func split(_ s: CoachSnapshot, isTimeInterval: Bool) -> SpeechBuilder {
        var builder = SpeechBuilder()
        builder.breakMilliseconds = settings.style == .brief ? 300 : 220
        for metric in settings.metrics(for: s.sport) {
            builder.add(phrase(for: metric, snapshot: s, isTimeInterval: isTimeInterval))
        }
        if settings.coachRemarks, let remark = remark(for: s) {
            builder.add(remark)
        }
        return builder
    }

    /// A light touch of encouragement, based on this split against the average.
    func remark(for s: CoachSnapshot) -> String? {
        guard s.sport.usesPace,
              s.splitPaceSecondsPerUnit > 0,
              s.averagePaceSecondsPerUnit > 0,
              s.splitIndex > 1 else { return nil }
        let delta = s.splitPaceSecondsPerUnit - s.averagePaceSecondsPerUnit
        switch delta {
        case ..<(-12): return "that one was quick"
        case (-12)..<(-4): return "picking it up nicely"
        case (-4)...4: return "right on pace"
        case 4...15: return "easing off a touch"
        default: return "settle in and find the rhythm"
        }
    }

    func phrase(for metric: SpokenMetric, snapshot s: CoachSnapshot, isTimeInterval: Bool) -> String? {
        let style = settings.style
        let unitWord = units == .metric ? "kilometre" : "mile"

        switch metric {
        case .splitNumber:
            let number = SpokenNumber.casual(s.splitIndex)
            if isTimeInterval {
                switch style {
                case .brief: return number
                case .natural: return "Interval " + number
                case .detailed: return "Interval " + number + " complete"
                }
            }
            switch style {
            case .brief: return number
            case .natural: return unitWord.capitalizedFirst + " " + number
            case .detailed: return unitWord.capitalizedFirst + " " + number + " complete"
            }

        case .splitTime:
            guard s.splitTime > 0 else { return nil }
            let value = SpokenNumber.duration(s.splitTime, verbose: style == .detailed)
            switch style {
            case .brief: return value
            case .natural: return "in " + value
            case .detailed: return "split time " + value
            }

        case .splitPace:
            guard let pace = SpokenNumber.pace(s.splitPaceSecondsPerUnit) else { return nil }
            switch style {
            case .brief: return pace
            case .natural: return "pace " + pace
            case .detailed:
                return "split pace " + SpokenNumber.duration(s.splitPaceSecondsPerUnit, verbose: true) + " per " + unitWord
            }

        case .totalDistance:
            guard s.totalDistance > 0 else { return nil }
            let value = SpokenNumber.distance(s.totalDistance, units)
            switch style {
            case .brief: return value
            case .natural: return value + " so far"
            case .detailed: return "total distance " + value
            }

        case .totalTime:
            guard s.movingTime > 0 else { return nil }
            let value = SpokenNumber.duration(s.movingTime, verbose: style == .detailed)
            switch style {
            case .brief: return value
            case .natural: return "running time " + value
            case .detailed: return "total moving time " + value
            }

        case .averagePace:
            guard let pace = SpokenNumber.pace(s.averagePaceSecondsPerUnit) else { return nil }
            switch style {
            case .brief: return "average " + pace
            case .natural: return "averaging " + pace
            case .detailed:
                return "average pace " + SpokenNumber.duration(s.averagePaceSecondsPerUnit, verbose: true) + " per " + unitWord
            }

        case .currentPace:
            guard let pace = SpokenNumber.pace(s.currentPaceSecondsPerUnit) else { return nil }
            switch style {
            case .brief: return pace
            case .natural: return "currently " + pace
            case .detailed:
                return "current pace " + SpokenNumber.duration(s.currentPaceSecondsPerUnit, verbose: true) + " per " + unitWord
            }

        case .gradeAdjustedPace:
            guard let pace = SpokenNumber.pace(s.gradeAdjustedPaceSecondsPerUnit) else { return nil }
            switch style {
            case .brief: return "adjusted " + pace
            case .natural: return "flat equivalent " + pace
            case .detailed: return "grade adjusted pace " + pace + " per " + unitWord
            }

        case .averageSpeed:
            guard s.averageSpeed > 0.2 else { return nil }
            let value = SpokenNumber.decimal(Fmt.speedValue(s.averageSpeed, units))
            switch style {
            case .brief: return "average " + value + " " + units.shortSpokenSpeedUnit
            case .natural: return "averaging " + value + " " + units.shortSpokenSpeedUnit
            case .detailed: return "average speed " + value + " " + units.spokenSpeedUnit
            }

        case .currentSpeed:
            guard s.currentSpeed > 0.2 else { return nil }
            let value = SpokenNumber.decimal(Fmt.speedValue(s.currentSpeed, units))
            switch style {
            case .brief: return value + " " + units.shortSpokenSpeedUnit
            case .natural: return "holding " + value + " " + units.shortSpokenSpeedUnit
            case .detailed: return "current speed " + value + " " + units.spokenSpeedUnit
            }

        case .heartRate:
            guard s.heartRate > 30 else { return nil }
            let value = SpokenNumber.casual(Int(s.heartRate.rounded()))
            switch style {
            case .brief: return value
            case .natural: return "heart rate " + value
            case .detailed: return "heart rate " + SpokenNumber.formal(Int(s.heartRate.rounded())) + " beats per minute"
            }

        case .averageHeartRate:
            guard s.averageHeartRate > 30 else { return nil }
            let value = SpokenNumber.casual(Int(s.averageHeartRate.rounded()))
            switch style {
            case .brief: return value
            case .natural: return "average heart rate " + value
            case .detailed: return "average heart rate " + SpokenNumber.formal(Int(s.averageHeartRate.rounded())) + " beats per minute"
            }

        case .cadence:
            guard s.cadence > 5 else { return nil }
            let value = SpokenNumber.casual(Int(s.cadence.rounded()))
            let noun = s.sport.isRide ? "revolutions per minute" : "steps per minute"
            switch style {
            case .brief: return value
            case .natural: return "cadence " + value
            case .detailed: return "cadence " + value + " " + noun
            }

        case .power:
            guard s.power > 5 else { return nil }
            let value = SpokenNumber.formal(Int(s.power.rounded()))
            switch style {
            case .brief: return value + " watts"
            case .natural: return "power " + value
            case .detailed: return "power " + value + " watts"
            }

        case .averagePower:
            guard s.averagePower > 5 else { return nil }
            let value = SpokenNumber.formal(Int(s.averagePower.rounded()))
            switch style {
            case .brief: return "average " + value + " watts"
            case .natural: return "averaging " + value + " watts"
            case .detailed: return "average power " + value + " watts"
            }

        case .elevationGain:
            guard s.elevationGain > 1 else { return nil }
            let value = SpokenNumber.formal(Int(Fmt.elevationValue(s.elevationGain, units).rounded()))
            switch style {
            case .brief: return value + " up"
            case .natural: return "climbed " + value + " " + units.spokenElevationUnit
            case .detailed: return "elevation gain " + value + " " + units.spokenElevationUnit
            }

        case .calories:
            guard s.calories > 1 else { return nil }
            let value = SpokenNumber.formal(Int(s.calories.rounded()))
            switch style {
            case .brief: return value
            case .natural: return value + " calories"
            case .detailed: return value + " calories burned"
            }
        }
    }


    // MARK: - The other announcements

    func start(sport: SportType) -> SpeechBuilder {
        var builder = SpeechBuilder()
        switch settings.style {
        case .brief: builder.add("Started")
        case .natural: builder.add("Let's go")
        case .detailed: builder.add("Starting your \(sport.name.lowercased())")
        }
        return builder
    }

    func finish(_ s: CoachSnapshot) -> SpeechBuilder {
        var builder = SpeechBuilder()
        builder.add(settings.style == .brief ? "Done" : "Nice work")
        builder.add(SpokenNumber.distance(s.totalDistance, units))
        builder.add("in " + SpokenNumber.duration(s.movingTime, verbose: settings.style == .detailed))
        if s.sport.usesPace, let pace = SpokenNumber.pace(s.averagePaceSecondsPerUnit) {
            builder.add(settings.style == .brief ? pace : "averaging " + pace)
        } else if s.averageSpeed > 0 {
            builder.add("averaging " + SpokenNumber.decimal(Fmt.speedValue(s.averageSpeed, units))
                        + " " + units.spokenSpeedUnit)
        }
        return builder
    }

    func lap(number: Int, snapshot s: CoachSnapshot) -> SpeechBuilder {
        var builder = SpeechBuilder()
        builder.add("Lap " + SpokenNumber.casual(number))
        builder.add(SpokenNumber.duration(s.splitTime, verbose: settings.style == .detailed))
        if s.sport.usesPace, let pace = SpokenNumber.pace(s.splitPaceSecondsPerUnit) {
            builder.add(pace)
        }
        return builder
    }

    func halfway(_ s: CoachSnapshot) -> SpeechBuilder {
        var builder = SpeechBuilder()
        builder.add("Halfway")
        builder.add(SpokenNumber.distance(s.totalDistance, units))
        builder.add("in " + SpokenNumber.duration(s.movingTime))
        return builder
    }

    func paceAlert(current: Double, target: Double) -> SpeechBuilder? {
        guard target > 0, current > 0 else { return nil }
        let delta = current - target
        guard abs(delta) > settings.targetPaceToleranceSeconds else { return nil }
        let seconds = Int(abs(delta).rounded())
        let plural = seconds == 1 ? "" : "s"
        var builder = SpeechBuilder()
        if delta > 0 {
            builder.add(SpokenNumber.casual(seconds) + " second" + plural + " behind target")
            builder.add("lift it a little")
        } else {
            builder.add(SpokenNumber.casual(seconds) + " second" + plural + " ahead of target")
            builder.add("ease back if you want to hold it")
        }
        return builder
    }

    func zoneAlert(zone: Int, target: Int) -> SpeechBuilder? {
        guard target > 0 else { return nil }
        var builder = SpeechBuilder()
        if zone + 1 > target {
            builder.add("Above zone " + SpokenNumber.casual(target))
            builder.add("ease off")
        } else if zone + 1 < target {
            builder.add("Below zone " + SpokenNumber.casual(target))
            builder.add("pick it up")
        } else {
            return nil
        }
        return builder
    }

    func segmentStart(name: String, prTime: TimeInterval?) -> SpeechBuilder {
        var builder = SpeechBuilder()
        builder.add("Segment " + name)
        if let pr = prTime, pr > 0 {
            builder.add("your best is " + SpokenNumber.duration(pr))
            builder.add("go")
        }
        return builder
    }

    func segmentProgress(deltaSeconds: Double, remaining: Double) -> SpeechBuilder {
        var builder = SpeechBuilder()
        let seconds = Int(abs(deltaSeconds).rounded())
        if seconds == 0 {
            builder.add("Level with your best")
        } else {
            let plural = seconds == 1 ? "" : "s"
            builder.add(SpokenNumber.casual(seconds) + " second" + plural
                        + (deltaSeconds < 0 ? " ahead of your best" : " behind your best"))
        }
        builder.add(SpokenNumber.distance(remaining, units) + " to go")
        return builder
    }

    func segmentFinish(name: String, time: TimeInterval, isPR: Bool, delta: Double?) -> SpeechBuilder {
        var builder = SpeechBuilder()
        if isPR {
            builder.add("New record on " + name)
            builder.add(SpokenNumber.duration(time))
        } else {
            builder.add(name + " done")
            builder.add(SpokenNumber.duration(time))
            if let delta, delta > 0 {
                builder.add(SpokenNumber.casual(Int(delta.rounded())) + " off your best")
            }
        }
        return builder
    }

    /// A worked example, used by the settings preview.
    static var sampleSnapshot: CoachSnapshot {
        var s = CoachSnapshot()
        s.sport = .run
        s.splitIndex = 3
        s.splitTime = 272
        s.splitDistance = 1000
        s.splitPaceSecondsPerUnit = 272
        s.totalDistance = 3000
        s.movingTime = 831
        s.totalTime = 845
        s.averagePaceSecondsPerUnit = 277
        s.currentPaceSecondsPerUnit = 268
        s.gradeAdjustedPaceSecondsPerUnit = 264
        s.averageSpeed = 7.2
        s.currentSpeed = 7.4
        s.heartRate = 154
        s.averageHeartRate = 151
        s.cadence = 176
        s.power = 243
        s.averagePower = 231
        s.elevationGain = 84
        s.calories = 320
        return s
    }
}

private extension String {
    var capitalizedFirst: String {
        guard let first = self.first else { return self }
        return String(first).uppercased() + dropFirst()
    }
}
