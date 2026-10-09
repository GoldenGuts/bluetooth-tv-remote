import AVFoundation
import Foundation
import Speech

/**
 Voice typing: speech to text with Apple's recognizer, on the iPhone when it can. Listening stops after a short
 silence or a second tap, then the words go to the box as text.
 */
final class Voice {

    var onText: (String) -> Void = { _ in }
    var onState: (Bool) -> Void = { _ in }
    var onProblem: (String) -> Void = { _ in }

    private(set) var listening = false
    private let audio = AVAudioEngine()
    private let recognizer = SFSpeechRecognizer()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var heard = ""
    private var silence: Task<Void, Never>?

    func toggle() {
        if listening { finish() } else { Task { await start() } }
    }

    private func start() async {
        guard let recognizer, recognizer.isAvailable else {
            onProblem("speech recognition is not available")
            return
        }
        guard await Self.speechAllowed(), await AVAudioApplication.requestRecordPermission() else {
            onProblem("allow Microphone and Speech Recognition in iOS Settings")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let req = SFSpeechAudioBufferRecognitionRequest()
            req.shouldReportPartialResults = true
            if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
            let input = audio.inputNode
            input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0), block: Self.tap(req))
            audio.prepare()
            try audio.start()
            request = req
            heard = ""
            listening = true
            onState(true)
            task = recognizer.recognitionTask(with: req) { [weak self] result, error in
                let text = result?.bestTranscription.formattedString
                let final = result?.isFinal ?? false
                MainActor.assumeIsolated { self?.update(text, final: final || error != nil) }
            }
            waitForSilence(4)   // nothing said at all: stop after 4 s
        } catch {
            cleanUp()
            onProblem("the microphone did not start")
        }
    }

    /// The tap runs on the audio thread: made outside the main actor.
    nonisolated private static func tap(_ req: SFSpeechAudioBufferRecognitionRequest) -> AVAudioNodeTapBlock {
        { buffer, _ in req.append(buffer) }
    }

    nonisolated private static func speechAllowed() async -> Bool {
        await withCheckedContinuation { c in
            SFSpeechRecognizer.requestAuthorization { @Sendable status in c.resume(returning: status == .authorized) }
        }
    }

    private func update(_ text: String?, final: Bool) {
        guard listening else { return }
        if let text { heard = text }
        if final { finish() } else { waitForSilence(1.5) }
    }

    private func waitForSilence(_ seconds: Double) {
        silence?.cancel()
        silence = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            if !Task.isCancelled { self?.finish() }
        }
    }

    func finish() {
        guard listening else { return }
        let said = heard.trimmingCharacters(in: .whitespaces)
        cleanUp()
        if !said.isEmpty { onText(said) }
    }

    private func cleanUp() {
        silence?.cancel()
        silence = nil
        if audio.isRunning {
            audio.stop()
            audio.inputNode.removeTap(onBus: 0)
        }
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if listening {
            listening = false
            onState(false)
        }
    }
}
