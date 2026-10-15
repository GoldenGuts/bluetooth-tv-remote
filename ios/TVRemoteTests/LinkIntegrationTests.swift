import Foundation
import Security
import Testing
@testable import TVRemote

/**
 The real pairing and link against ios/scripts/mock-box.py on this Mac (the simulator shares its 127.0.0.1).
 Runs only from ios/scripts/test-link.sh, which starts the mock and sets TVREMOTE_MOCK_DIR and TVREMOTE_MOCK_MODE.
 */
@MainActor
struct LinkIntegrationTests {

    nonisolated static let env = ProcessInfo.processInfo.environment
    nonisolated static let dir = env["TVREMOTE_MOCK_DIR"] ?? ""
    nonisolated static let mode = env["TVREMOTE_MOCK_MODE"] ?? ""
    static let host = "127.0.0.1"

    /// Results from @Sendable callbacks.
    @MainActor
    final class Recorder {
        var oks: [Bool] = []
        func add(_ ok: Bool) { oks.append(ok) }
    }

    @Test(.enabled(if: !dir.isEmpty && mode != "webos")) func linkAgainstMockBox() async throws {
        let identity = try BoxIdentity.load()
        try (SecCertificateCopyData(identity.cert) as Data).write(to: URL(fileURLWithPath: Self.dir + "/client.der"))
        if Self.mode.hasPrefix("atv") { try await pair() }

        let link = BoxLink(deviceName: "iPhone", appName: "TV Remote")
        var keyboard: (Bool, String)?
        link.onKeyboard = { keyboard = ($0, $1) }
        link.setInteractive(true)
        link.start(Self.host)
        try await waitFor("connected") { link.state == .connected }

        let r = Recorder()
        link.send(Key.up, r.add)
        link.send(Key.ok, r.add)
        link.text("hi", r.add)
        try await waitFor("3 results") { r.oks.count == 3 }
        #expect(r.oks == [true, true, true])

        switch Self.mode {
        case "atv2":
            #expect(link.model == "MockTV")
            try await waitForLog("remote v2 configure from iPhone app TV Remote")
            try await waitForLog("key 19")
            try await waitForLog("key 23")
            try await waitForLog("key 36")   // h
            try await waitForLog("key 37")   // i
            try await waitForLog("remote v2 pong 7")
        case "atv1":
            #expect(link.model == "AM9 PRO")
            try await waitForLog("interactive 1")
            try await waitFor("ShowIME") { keyboard != nil }
            #expect(keyboard?.0 == true && keyboard?.1 == "abc")
            try await waitForLog("key 19")
            try await waitForLog("key 23")
            try await waitForLog("commit hi")
            link.delete(2, r.add)
            link.key(Key.codeEnter, r.add)
            try await waitForLog("delete 2")
            try await waitForLog("editor action 3")   // the field's Search action, from ShowIME
            try await waitForLog("remote v1 pong")
        case "kodi", "kodi18":
            let old = Self.mode == "kodi18"   // Kodi 18 has no Input.ButtonEvent
            #expect(link.model == "Kodi · MockELEC")
            #expect(link.hasPointer)
            try await waitForLog(old ? "kodi Input.Up" : #"kodi Input.ButtonEvent {"button": "up", "keymap": "R1"}"#)
            try await waitForLog(old ? "kodi Input.Select" : #"kodi Input.ButtonEvent {"button": "select", "keymap": "R1"}"#)
            try await waitForLog(#"kodi Input.SendText {"done": false, "text": "hi"}"#)
            link.send(Key.home, r.add)
            try await waitFor("keyboard") { keyboard != nil }
            #expect(keyboard?.0 == true && keyboard?.1 == "xyz")
            link.text("!", r.add)
            link.key(Key.codeEnter, r.add)
            try await waitForLog(#"kodi Input.SendText {"done": true, "text": "xyz!"}"#)
            link.pointerMove(0.1, 0)
            link.pointerClick(r.add)
            try await waitForLog("eventserver helo")
            try await waitForLog("eventserver mouse")
            let before = r.oks.count
            link.send(Key.power, r.add)   // Kodi has no power key: the key does not go out
            try await waitFor("power result") { r.oks.count == before + 1 }
            #expect(r.oks.last == false)
        default:
            Issue.record("unknown mode \(Self.mode)")
        }
        link.stop()
        #expect(link.state == .off)
    }

    /// The LG TV link: the prompt, buttons, text, pointer, power, Wake-on-LAN, and the saved key next time.
    @Test(.enabled(if: mode == "webos")) func tvAgainstMockTV() async throws {
        TVLink.forget()
        UserDefaults.standard.removeObject(forKey: "tv.secure")
        let tv = TVLink()
        var states: [TVLink.State] = []
        var keyboard: Bool?
        tv.onStatus = { state, _ in states.append(state) }
        tv.onKeyboard = { keyboard = $0 }
        tv.start(Self.host)
        try await waitFor("connected") { tv.state == .connected }
        #expect(states.contains(.pairing), "the prompt on the TV")
        #expect(TVLink.paired)
        #expect(tv.model == "LG OLED65G5PUA")
        #expect(tv.hasPointer)
        try await waitForLog("register: prompt on the TV")
        try await waitForLog("prompt accepted")

        let r = Recorder()
        for key in [Key.up, Key.ok, Key.back, Key.settings, Key.input, Key.volumeUp, Key.mute, Key.playPause] {
            tv.send(key, r.add)
        }
        try await waitFor("8 results") { r.oks.count == 8 }
        #expect(r.oks.allSatisfy { $0 })
        for line in ["button UP", "button ENTER", "button BACK", "button MENU", "button INPUT_HUB", "button VOLUMEUP",
                     "button MUTE", "button PAUSE"] {
            try await waitForLog(line)   // PAUSE: the TV said that something plays
        }
        tv.send(Key.home, r.add)
        try await waitFor("keyboard") { keyboard == true }

        tv.text("hi", r.add)
        tv.delete(2, r.add)
        tv.enter(r.add)
        try await waitForLog("insert hi")
        try await waitForLog("delete 2")
        try await waitForLog("enter")
        tv.pointerMove(10, -5)
        tv.pointerClick(r.add)
        tv.scroll(1, r.add)
        try await waitForLog("move 10 -5")
        try await waitForLog("click")
        try await waitForLog("scroll 40")
        tv.send(Key.power, r.add)
        try await waitForLog("turn off")

        tv.stop()
        #expect(tv.state == .off)
        TVLink.wolPort = 19009   // the mock listens here
        #expect(tv.wakeOnLan())
        try await waitForLog("wol aa:bb:cc:dd:ee:ff")

        // Next time the TV knows the key: no prompt
        let again = TVLink()
        again.start(Self.host)
        try await waitFor("connected again") { again.state == .connected }
        try await waitForLog("register with key")
        again.stop()
    }

    private func pair() async throws {
        UserDefaults.standard.removeObject(forKey: "box.pin")
        let p = BoxPairing(host: Self.host, clientName: "iPhone")
        var shown = 0
        var result: (Bool, String)?
        p.codeShown = { shown = $0 }
        p.done = { result = ($0, $1) }
        p.start()
        try await waitFor("code") { shown > 0 || result != nil }
        #expect(result == nil, "pairing failed early: \(result?.1 ?? "")")
        #expect(shown == (Self.mode == "atv1" ? 4 : 6))
        let code = try String(contentsOfFile: Self.dir + "/code.txt", encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        #expect(!p.submit("XYZ"), "not hex: refused at once")
        #expect(p.submit(code))
        try await waitFor("paired") { result != nil }
        #expect(result?.0 == true, "pairing: \(result?.1 ?? "")")
        #expect(BoxIdentity.paired)
        #expect(BoxIdentity.version == (Self.mode == "atv1" ? 1 : 2))
        try await waitForLog("pairing secret ok")
    }

    private func waitFor(_ what: String, timeout: Double = 15, _ condition: () -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > end {
                Issue.record("timed out waiting for \(what)")
                throw CancellationError()
            }
            try await Task.sleep(for: .milliseconds(50))
        }
    }

    private func waitForLog(_ line: String) async throws {
        try await waitFor("log: \(line)") {
            let log = (try? String(contentsOfFile: Self.dir + "/log.txt", encoding: .utf8)) ?? ""
            return log.split(separator: "\n").contains { $0 == line }
        }
    }
}
