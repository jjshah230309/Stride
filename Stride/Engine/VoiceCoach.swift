import Foundation
import AVFoundation
import Combine

/// Speaks splits, laps, segment feedback and pace alerts.
///
/// Two things make this sound human rather than like a station announcement:
/// the numbers are turned into the words a person would say (`SpokenNumber`),
/// and the phrases are joined with real pauses through SSML instead of being
/// strung together with commas.
final class VoiceCoach: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {

    @Published private(set) var isSpeaking: Bool = false
    @Published var lastSpoken: String = ""

    var settings: VoiceSettings = VoiceSettings()
    var units: UnitSystem = .metric

    private let synthesizer = AVSpeechSynthesizer()
    private var sessionActive = false
    private var resolvedVoice: AVSpeechSynthesisVoice?
    private var resolvedIdentifier: String?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    // MARK: - Voice selection

    /// Ranks the installed voices. The compact default is the robotic one; an
    /// enhanced or premium voice is the single biggest improvement available.
    static func quality(_ voice: AVSpeechSynthesisVoice) -> Int {
        switch voice.quality {
        case .premium: return 3
        case .enhanced: return 2
        default: return 1
        }
    }

    static func qualityName(_ voice: AVSpeechSynthesisVoice) -> String {
        switch voice.quality {
        case .premium: return "Premium"
        case .enhanced: return "Enhanced"
        default: return "Standard"
        }
    }

    static func availableVoices() -> [AVSpeechSynthesisVoice] {
        let preferred = Locale.current.language.languageCode?.identifier ?? "en"
        return AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix(preferred) || $0.language.hasPrefix("en") }
            .sorted { a, b in
                let qa = quality(a), qb = quality(b)
                if qa != qb { return qa > qb }
                return a.name < b.name
            }
    }

    /// The best voice on the device, so a fresh install does not default to the
    /// flattest one available.
    static func bestAvailableVoice() -> AVSpeechSynthesisVoice? {
        availableVoices().first
    }

    private func voiceToUse() -> AVSpeechSynthesisVoice? {
        if resolvedIdentifier == settings.voiceIdentifier, let resolvedVoice {
            return resolvedVoice
        }
        resolvedIdentifier = settings.voiceIdentifier
        if !settings.voiceIdentifier.isEmpty,
           let chosen = AVSpeechSynthesisVoice(identifier: settings.voiceIdentifier) {
            resolvedVoice = chosen
        } else {
            resolvedVoice = Self.bestAvailableVoice()
                ?? AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
        }
        return resolvedVoice
    }

    // MARK: - Audio session

    private func activateSession() {
        guard !sessionActive else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            var options: AVAudioSession.CategoryOptions = [.mixWithOthers]
            if settings.duckMusic {
                options.insert(.duckOthers)
                options.insert(.interruptSpokenAudioAndMixWithOthers)
            }
            try session.setCategory(.playback, mode: .spokenAudio, options: options)
            try session.setActive(true, options: [])
            sessionActive = true
        } catch {
            NSLog("Stride: audio session error \(error.localizedDescription)")
        }
    }

    private func deactivateSession() {
        guard sessionActive else { return }
        sessionActive = false
        // Let the tail of the utterance play out before handing audio back.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }

    // MARK: - Speaking

    private func configure(_ utterance: AVSpeechUtterance) {
        utterance.rate = Float(Swift.max(0.2, Swift.min(0.8, settings.rate)))
        utterance.pitchMultiplier = Float(Swift.max(0.5, Swift.min(2.0, settings.pitch)))
        utterance.volume = Float(Swift.max(0.1, Swift.min(1.0, settings.volume)))
        utterance.preUtteranceDelay = 0
        utterance.postUtteranceDelay = 0.15
        utterance.voice = voiceToUse()
    }

    func speak(_ text: String, force: Bool = false) {
        var builder = SpeechBuilder()
        builder.add(text.hasSuffix(".") ? String(text.dropLast()) : text)
        speak(builder, force: force)
    }

    func speak(_ builder: SpeechBuilder, force: Bool = false) {
        guard !builder.isEmpty else { return }
        guard settings.enabled || force else { return }
        activateSession()

        // SSML gives real pauses between phrases; fall back if it is rejected.
        let utterance = AVSpeechUtterance(ssmlRepresentation: builder.ssml)
            ?? AVSpeechUtterance(string: builder.plainText)
        configure(utterance)

        let spoken = builder.plainText
        DispatchQueue.main.async {
            self.lastSpoken = spoken
            self.isSpeaking = true
        }
        synthesizer.speak(utterance)
    }

    /// Cut in ahead of anything queued — used for segment alerts, which go stale fast.
    func speakImmediately(_ builder: SpeechBuilder) {
        synthesizer.stopSpeaking(at: .word)
        speak(builder)
    }

    func speakImmediately(_ text: String) {
        var builder = SpeechBuilder()
        builder.add(text)
        speakImmediately(builder)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        deactivateSession()
    }

    func previewVoice() {
        var snapshot = CoachScript.sampleSnapshot
        snapshot.totalDistance = units.metersPerUnit * 3
        snapshot.splitDistance = units.metersPerUnit
        var builder = CoachScript(settings: settings, units: units).split(snapshot, isTimeInterval: false)
        if builder.isEmpty { builder.add("Nothing is selected, so nothing will be spoken") }
        speak(builder, force: true)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { self.isSpeaking = false }
        if !synthesizer.isSpeaking { deactivateSession() }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { self.isSpeaking = false }
        if !synthesizer.isSpeaking { deactivateSession() }
    }

    // MARK: - Composed announcements

    /// All phrasing lives in CoachScript, which has no audio dependency.
    private var script: CoachScript {
        CoachScript(settings: settings, units: units)
    }

    func announceCountdown(_ n: Int) {
        speak(n > 0 ? SpokenNumber.casual(n) : "Go")
    }

    func announceStart(sport: SportType) {
        guard settings.announceStart else { return }
        speak(script.start(sport: sport))
    }

    func announcePause(automatic: Bool) {
        guard automatic ? settings.announceAutoPause : settings.announcePauseResume else { return }
        speak(automatic ? "Auto paused" : "Paused")
    }

    func announceResume(automatic: Bool) {
        guard automatic ? settings.announceAutoPause : settings.announcePauseResume else { return }
        speak(automatic ? "Rolling again" : "Resumed")
    }

    func announceFinish(_ s: CoachSnapshot) {
        guard settings.announceFinish else { return }
        speak(script.finish(s))
    }

    func announceLap(number: Int, snapshot s: CoachSnapshot) {
        guard settings.announceLaps else { return }
        speak(script.lap(number: number, snapshot: s))
    }

    func announceHalfway(_ s: CoachSnapshot) {
        guard settings.announceHalfway else { return }
        speak(script.halfway(s))
    }

    /// The split announcement — the one that matters most.
    func announceSplit(_ s: CoachSnapshot, isTimeInterval: Bool = false) {
        speak(script.split(s, isTimeInterval: isTimeInterval))
    }

    func buildSplit(_ s: CoachSnapshot, isTimeInterval: Bool) -> SpeechBuilder {
        script.split(s, isTimeInterval: isTimeInterval)
    }

    // MARK: - Coaching alerts

    func announcePaceAlert(current: Double, target: Double) {
        guard settings.targetPaceAlertsEnabled else { return }
        guard let builder = script.paceAlert(current: current, target: target) else { return }
        speakImmediately(builder)
    }

    func announceZoneAlert(zone: Int, target: Int) {
        guard settings.zoneAlertsEnabled else { return }
        guard let builder = script.zoneAlert(zone: zone, target: target) else { return }
        speakImmediately(builder)
    }

    func announceGoalMilestone(_ text: String) {
        guard settings.announceGoalMilestones else { return }
        speak(text)
    }

    // MARK: - Live segments

    func announceSegmentStart(name: String, prTime: TimeInterval?) {
        guard settings.announceSegments else { return }
        speakImmediately(script.segmentStart(name: name, prTime: prTime))
    }

    func announceSegmentProgress(deltaSeconds: Double, remaining: Double, units: UnitSystem) {
        guard settings.announceSegments else { return }
        speak(script.segmentProgress(deltaSeconds: deltaSeconds, remaining: remaining))
    }

    func announceSegmentFinish(name: String, time: TimeInterval, isPR: Bool, delta: Double?) {
        guard settings.announceSegments else { return }
        speakImmediately(script.segmentFinish(name: name, time: time, isPR: isPR, delta: delta))
    }
}
