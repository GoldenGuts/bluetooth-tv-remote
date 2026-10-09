import CoreHaptics
import Foundation

/**
 The vibration vocabulary, on Core Haptics. Each pattern feels close to its Android twin: ticks are short
 sharp taps, thuds are soft and low, buzzes are long and dull. A new pattern stops the one that plays.
 */
final class Haptics {

    enum Pattern: CaseIterable {
        case tickV, tickH, click, back, menu, rise, home, play, input, settings, mute, ready, noLink, error, lost
    }

    var strength: Float = 1

    private var engine: CHHapticEngine?
    private var player: CHHapticPatternPlayer?
    private var running = false

    init() {
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics, let e = try? CHHapticEngine() else { return }
        e.playsHapticsOnly = true
        e.isAutoShutdownEnabled = false
        e.stoppedHandler = { @Sendable [weak self] _ in
            Task { @MainActor in self?.running = false }
        }
        e.resetHandler = { @Sendable [weak self] in
            Task { @MainActor in self?.running = false }
        }
        engine = e
    }

    func play(_ p: Pattern) {
        guard strength > 0, let engine else { return }
        if !running {
            try? engine.start()
            running = true
        }
        guard let pattern = try? CHHapticPattern(events: events(p), parameterCurves: curves(p)) else { return }
        try? player?.stop(atTime: CHHapticTimeImmediate)
        player = try? engine.makePlayer(with: pattern)
        try? player?.start(atTime: CHHapticTimeImmediate)
    }

    func stop() {
        try? player?.stop(atTime: CHHapticTimeImmediate)
    }

    /// The app goes to the background: the engine stops, and starts again on the next pattern.
    func pause() {
        engine?.stop()
        running = false
    }

    // MARK: - patterns

    private func tap(_ at: Double, _ intensity: Float, _ sharpness: Float) -> CHHapticEvent {
        CHHapticEvent(eventType: .hapticTransient, parameters: [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: min(1, intensity * strength)),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness),
        ], relativeTime: at)
    }

    private func buzz(_ at: Double, _ duration: Double, _ intensity: Float, _ sharpness: Float) -> CHHapticEvent {
        CHHapticEvent(eventType: .hapticContinuous, parameters: [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: min(1, intensity * strength)),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness),
        ], relativeTime: at, duration: duration)
    }

    private func events(_ p: Pattern) -> [CHHapticEvent] {
        switch p {
        case .tickV: return [tap(0, 0.7, 0.75)]
        case .tickH: return [tap(0, 0.8, 0.25)]
        case .click: return [tap(0, 1, 0.55)]
        case .back: return [tap(0, 0.8, 0.7), tap(0.045, 0.8, 0.7)]
        case .menu: return [tap(0, 0.7, 0), buzz(0, 0.04, 0.35, 0)]
        case .rise: return [buzz(0, 0.35, 0.6, 0.2)]
        case .home: return [tap(0, 1, 0), buzz(0, 0.05, 0.5, 0), tap(0.08, 1, 0.75)]
        case .play: return [tap(0, 0.55, 0), buzz(0, 0.035, 0.3, 0)]
        case .input: return [tap(0, 0.6, 0.55), tap(0.035, 0.8, 0.75)]
        case .settings: return [tap(0, 1, 0.55), tap(0.08, 1, 0.55)]
        case .mute: return [tap(0, 0.55, 0), tap(0.115, 0.55, 0)]
        case .ready: return [buzz(0, 0.12, 0.8, 0.4)]
        case .noLink: return [buzz(0, 0.12, 0.45, 0.05), buzz(0.2, 0.12, 0.45, 0.05)]
        case .error: return [buzz(0, 0.3, 0.4, 0.05)]
        case .lost: return [buzz(0, 0.08, 0.45, 0.05), buzz(0.14, 0.08, 0.45, 0.05), buzz(0.28, 0.08, 0.45, 0.05)]
        }
    }

    /// Rises: the intensity grows over the buzz.
    private func curves(_ p: Pattern) -> [CHHapticParameterCurve] {
        let length: Double
        switch p {
        case .rise: length = 0.35
        case .ready: length = 0.12
        default: return []
        }
        return [CHHapticParameterCurve(parameterID: .hapticIntensityControl, controlPoints: [
            .init(relativeTime: 0, value: 0.15),
            .init(relativeTime: length, value: 1),
        ], relativeTime: 0)]
    }
}
