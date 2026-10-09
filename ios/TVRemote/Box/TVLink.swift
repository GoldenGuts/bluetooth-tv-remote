import Foundation
import Network

/**
 The Wi-Fi link to an LG TV (webOS): the TV's own remote-app API, as LG's ThinQ app uses it. JSON requests
 ("ssap://...") go over a WebSocket (ws on port 3000, or wss on 3001 when the firmware asks for TLS). The first
 time, the TV shows a prompt; OK on the TV remote gives a client key that later connections show. Buttons and the
 mouse pointer go over a second WebSocket that the TV names. A TV that is off is woken with Wake-on-LAN.
 */
final class TVLink {

    enum State: Equatable { case off, waiting, connecting, pairing, connected }

    typealias Result = BoxLink.Result

    var onStatus: (State, String) -> Void = { _, _ in }
    /// A text field on the TV took focus (true) or lost it.
    var onKeyboard: (Bool) -> Void = { _ in }

    private static let wsPort = 3000, wssPort = 3001
    /// Wake-on-LAN goes to this UDP port (tests use another: port 9 needs root on the Mac).
    static var wolPort: UInt16 = 9
    /// Keys pressed while the link connects wait this long, then fail.
    private static let queueMs: Int64 = 1500
    /// A request the TV does not answer in this time failed.
    private static let requestMs = 5000

    private static let keyKey = "tv.key"
    private static let macKey = "tv.mac"
    private static let secureKey = "tv.secure"

    private struct Pending {
        let run: @MainActor () -> Void
        let result: Result
        let deadline: Int64
    }

    private var host = ""
    private var generation = 0
    private var started = false
    private var main: Conn?
    private var pointer: Conn?
    private var ready = false
    private var nextId = 1
    /// Requests waiting for an answer, and subscriptions (they answer more than once).
    private var replies: [String: ([String: Any]?) -> Void] = [:]
    private var subscriptions: Set<String> = []
    private var queue: [Pending] = []
    private var sleeper: (id: Int, c: CheckedContinuation<Void, Never>)?
    private var sleepId = 0
    /// The person refused the prompt (or it timed out): no new prompt until start() again.
    private var refused = false
    private var playing: Bool?

    private(set) var state = State.off
    private(set) var detail = ""
    /// The TV name, e.g. "LG OLED65G5".
    private(set) var model = ""
    /// The TV's power state: "Active", "Active Standby", "Screen Off", "Suspend", or "" (not known yet).
    private(set) var power = ""

    static var paired: Bool { UserDefaults.standard.string(forKey: keyKey) != nil }

    /// An LG TV answers on its remote-app ports.
    static func at(_ host: String) async -> Bool {
        if await Conn.probe(host, UInt16(wssPort)) { return true }
        return await Conn.probe(host, UInt16(wsPort))
    }

    /// Forgets the client key: the next connection shows the prompt on the TV again.
    static func forget() {
        UserDefaults.standard.removeObject(forKey: keyKey)
        UserDefaults.standard.removeObject(forKey: macKey)
    }

    var isConnected: Bool { ready }
    var hasPointer: Bool { ready && pointer != nil }
    /// The TV is in standby: the link works, the screen is off.
    var standby: Bool { ready && ["Active Standby", "Suspend", "Power Off"].contains(power) }

    // MARK: - start and stop

    /// Connects and stays connected until stop(). Same host again: tries again now if it waits.
    func start(_ host: String) {
        refused = false
        if started && host == self.host {
            wake()
            return
        }
        stopLink()
        self.host = host
        started = true
        generation += 1
        let gen = generation
        Task { await run(gen) }
    }

    func stop() {
        guard started else { return }
        stopLink()
        report(.off, "")
    }

    // MARK: - keys

    /// A remote action: a button, a volume step, power.
    func send(_ action: Int, _ r: @escaping Result) {
        if action == Key.power {
            power(r)
            return
        }
        guard let name = button(action) else {
            Task { r(false) }
            return
        }
        submit({ [weak self] in self?.press(name, r) }, r)
    }

    /// Text into the focused text field on the TV.
    func text(_ text: String, _ r: @escaping Result) {
        submit({ [weak self] in self?.request("com.webos.service.ime/insertText", ["text": text, "replace": 0], r) }, r)
    }

    /// Deletes n characters before the cursor.
    func delete(_ n: Int, _ r: @escaping Result) {
        if n <= 0 { return }
        submit({ [weak self] in self?.request("com.webos.service.ime/deleteCharacters", ["count": n], r) }, r)
    }

    func enter(_ r: @escaping Result) {
        submit({ [weak self] in self?.request("com.webos.service.ime/sendEnterKey", [:], r) }, r)
    }

    /// Moves the TV's mouse pointer, in TV pixels.
    func pointerMove(_ dx: Int, _ dy: Int) {
        guard ready, dx != 0 || dy != 0 else { return }
        pointer?.sendText("type:move\ndx:\(dx)\ndy:\(dy)\ndown:0\n\n")
    }

    func pointerClick(_ r: @escaping Result) {
        submit({ [weak self] in self?.pointerSend("type:click\n\n", r) }, r)
    }

    /// Wheel steps: up is negative.
    func scroll(_ steps: Int, _ r: @escaping Result) {
        submit({ [weak self] in self?.pointerSend("type:scroll\ndx:0\ndy:\(steps * 40)\n\n", r) }, r)
    }

    /// The LG button for an action. Play/Pause follows the player state when the TV tells it.
    private func button(_ action: Int) -> String? {
        switch action {
        case Key.up: return "UP"
        case Key.down: return "DOWN"
        case Key.left: return "LEFT"
        case Key.right: return "RIGHT"
        case Key.ok: return "ENTER"
        case Key.back: return "BACK"
        case Key.home: return "HOME"
        case Key.settings: return "MENU"         // quick settings, like the remote's gear key
        case Key.input: return "INPUT_HUB"       // the inputs on the home dashboard
        case Key.volumeUp: return "VOLUMEUP"
        case Key.volumeDown: return "VOLUMEDOWN"
        case Key.mute: return "MUTE"
        case Key.rewind: return "REWIND"
        case Key.fastForward: return "FASTFORWARD"
        case Key.playPause:
            let pause = playing ?? true
            playing = !pause
            return pause ? "PAUSE" : "PLAY"
        default: return nil
        }
    }

    static func buttonMessage(_ name: String) -> String {
        "type:button\nname:\(name)\n\n"
    }

    private func press(_ name: String, _ r: @escaping Result) {
        pointerSend(Self.buttonMessage(name), r)
    }

    private func pointerSend(_ message: String, _ r: @escaping Result) {
        guard let pointer else {
            r(false)
            return
        }
        pointer.sendText(message) { [weak self] ok in
            if !ok { self?.drop() }
            r(ok)
        }
    }

    /// On: off and standby wake up (Wake-on-LAN, and a turn-on request in standby). Off: the TV turns off.
    private func power(_ r: @escaping Result) {
        if ready && !standby {
            submit({ [weak self] in self?.request("system/turnOff", [:], r) }, r)
            return
        }
        if ready { request("system/turnOn", [:]) { _ in } }
        let sent = wakeOnLan()
        wake()   // connect again soon: the TV takes a few seconds
        Task { r(sent) }
    }

    /// Magic packets to the TV's address and its subnet. Needs "Turn on via Wi-Fi" on the TV.
    @discardableResult
    func wakeOnLan() -> Bool {
        let macs = UserDefaults.standard.stringArray(forKey: Self.macKey) ?? []
        guard !host.isEmpty, !macs.isEmpty else { return false }
        var targets = [host]
        let parts = host.split(separator: ".")
        if parts.count == 4 { targets.append(parts.dropLast().joined(separator: ".") + ".255") }
        for mac in macs {
            guard let packet = Self.magicPacket(mac) else { continue }
            for t in targets {
                let c = NWConnection(host: NWEndpoint.Host(t), port: NWEndpoint.Port(rawValue: Self.wolPort) ?? 9, using: .udp)
                c.start(queue: .main)
                c.send(content: Data(packet), completion: .contentProcessed { _ in c.cancel() })
            }
        }
        return true
    }

    /// 6 x 0xFF, then the MAC address 16 times.
    static func magicPacket(_ mac: String) -> [UInt8]? {
        let bytes = mac.split(whereSeparator: { $0 == ":" || $0 == "-" }).compactMap { UInt8($0, radix: 16) }
        guard bytes.count == 6 else { return nil }
        return [UInt8](repeating: 0xFF, count: 6) + (0..<16).flatMap { _ in bytes }
    }

    private func submit(_ run: @escaping @MainActor () -> Void, _ r: @escaping Result) {
        if ready {
            run()
            return
        }
        if !started {
            Task { r(false) }
            return
        }
        let deadline = Self.now() + Self.queueMs
        queue.append(Pending(run: run, result: r, deadline: deadline))
        wake()
        Task {
            try? await Task.sleep(for: .milliseconds(Self.queueMs + 20))
            expire()
        }
    }

    private func expire() {
        let now = Self.now()
        while let p = queue.first, p.deadline <= now {
            queue.removeFirst()
            p.result(false)
        }
    }

    private static func now() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }

    // MARK: - requests

    private func request(_ uri: String, _ payload: [String: Any], _ r: @escaping Result) {
        request(uri, payload) { reply in r(reply != nil) }
    }

    /// A request; the reply gets the payload, or nil on an error or no answer.
    private func request(_ uri: String, _ payload: [String: Any], subscribe: Bool = false,
                         reply: @escaping ([String: Any]?) -> Void) {
        guard let main else {
            reply(nil)
            return
        }
        let id = String(nextId)
        nextId += 1
        replies[id] = reply
        if subscribe { subscriptions.insert(id) }
        main.sendText(Self.json(["id": id, "type": subscribe ? "subscribe" : "request", "uri": "ssap://" + uri,
                                 "payload": payload]))
        if subscribe { return }
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Self.requestMs))
            if let late = self?.replies.removeValue(forKey: id) { late(nil) }
        }
    }

    static func json(_ object: [String: Any]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    /// The registration: the permissions this remote asks for, and the client key from an earlier prompt.
    static func registration(clientKey: String?) -> [String: Any] {
        var payload: [String: Any] = [
            "forcePairing": false,
            "pairingType": "PROMPT",
            "manifest": [
                "manifestVersion": 1,
                "appVersion": "1.1",
                "permissions": [
                    "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
                    "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT",
                    "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN",
                    "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL",
                    "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS",
                    "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS",
                    "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO", "SEARCH",
                    "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP",
                    "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS",
                ],
            ] as [String: Any],
        ]
        if let clientKey { payload["client-key"] = clientKey }
        return ["id": "register_0", "type": "register", "payload": payload]
    }

    // MARK: - the session

    private func alive(_ gen: Int) -> Bool {
        started && gen == generation
    }

    private func run(_ gen: Int) async {
        var delay = 500
        while alive(gen) {
            do {
                try await session(gen)
                delay = 500
            } catch {
                if !alive(gen) { break }
                report(.waiting, refused ? detail : Self.reason(error))
            }
            dropConnections()
            if !alive(gen) { break }
            if refused {
                await pause(86_400_000)   // until start() again (Settings → TV → Pair again)
            } else if delay > 0 {
                await pause(queue.isEmpty ? delay : 200)
            }
            delay = max(1000, min(delay * 2, 10_000))
        }
    }

    private static func reason(_ error: Error) -> String {
        switch error {
        case LinkError.timeout: return "the TV does not answer (off?)"
        case let e as NWError:
            if case .posix(.ECONNREFUSED) = e { return "the TV refused the link (off?)" }
            return Conn.reason(e).replacingOccurrences(of: "the box", with: "the TV")
        default:
            return Conn.reason(error).replacingOccurrences(of: "the box", with: "the TV")
        }
    }

    private func session(_ gen: Int) async throws {
        report(.connecting, "")
        let c = try await openMain()
        guard alive(gen) else {
            c.close()
            return
        }
        main = c
        c.idleTimeout(nil)   // the prompt on the TV may wait a while for the person
        request("system/getSystemInfo", [:]) { [weak self] info in
            if let name = info?["modelName"] as? String, !name.isEmpty { self?.model = "LG " + name }
        }
        c.sendText(Self.json(Self.registration(clientKey: UserDefaults.standard.string(forKey: Self.keyKey))))
        while alive(gen) {
            let text = try await c.readMessage()
            guard let m = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { continue }
            try handle(m, gen)
        }
    }

    /// ws on 3000 first (older firmware), wss on 3001 when the TV wants TLS. The last way that worked goes first.
    private func openMain() async throws -> Conn {
        let secureFirst = UserDefaults.standard.bool(forKey: Self.secureKey)
        var lastError: Error = LinkError.closed
        for secure in secureFirst ? [true, false] : [false, true] {
            let url = URL(string: "\(secure ? "wss" : "ws")://\(host):\(secure ? Self.wssPort : Self.wsPort)/")!
            let c = Conn(webSocket: url)
            do {
                try await c.open(timeout: 5)
                UserDefaults.standard.set(secure, forKey: Self.secureKey)
                return c
            } catch {
                lastError = error
                if case LinkError.timeout = error { throw error }   // off or away: the other port will not answer either
            }
        }
        throw lastError
    }

    private func handle(_ m: [String: Any], _ gen: Int) throws {
        let id = m["id"].map { "\($0)" } ?? ""
        let type = m["type"] as? String ?? ""
        let payload = m["payload"] as? [String: Any]
        if id == "register_0" {
            switch type {
            case "response":
                if payload?["pairingType"] as? String == "PROMPT" {
                    report(.pairing, "press OK on the TV to allow this iPhone")
                }
            case "registered":
                if let key = payload?["client-key"] as? String {
                    UserDefaults.standard.set(key, forKey: Self.keyKey)
                }
                registered(gen)
            default:   // "error": the person said no, or the prompt timed out
                refused = true
                let error = m["error"] as? String ?? ""
                report(.waiting, "the TV refused this iPhone" + (error.isEmpty ? "" : " (\(error))") + ": pair again")
                throw LinkError.message(detail)
            }
            return
        }
        guard let reply = replies[id] else { return }
        if !subscriptions.contains(id) { replies[id] = nil }
        let ok = type != "error" && (payload?["returnValue"] as? Bool ?? true)
        reply(ok ? payload ?? [:] : nil)
    }

    /// The TV knows this iPhone: open the button socket, then read the power state and the TV's MAC address.
    private func registered(_ gen: Int) {
        main?.idleTimeout(30)   // the power-state check below answers every 10 s
        request("com.webos.service.networkinput/getPointerInputSocket", [:]) { [weak self] reply in
            guard let self, alive(gen) else { return }
            guard let path = reply?["socketPath"] as? String, let url = pointerURL(path) else {
                drop()
                return
            }
            Task { await self.openPointer(url, gen) }
        }
        request("com.webos.service.connectionmanager/getinfo", [:]) { info in
            let macs = ["wiredInfo", "wifiInfo"].compactMap { (info?[$0] as? [String: Any])?["macAddress"] as? String }
                .filter { !$0.isEmpty && $0 != "00:00:00:00:00:00" }
            if !macs.isEmpty { UserDefaults.standard.set(macs, forKey: Self.macKey) }
        }
        request("com.webos.service.tvpower/power/getPowerState", [:], subscribe: true) { [weak self] state in
            guard let self, let s = state?["state"] as? String else { return }
            power = s
            if ready { report(.connected, standby ? "standby" : "") }
        }
        request("com.webos.media/getForegroundAppInfo", [:], subscribe: true) { [weak self] info in
            let apps = info?["foregroundAppInfo"] as? [[String: Any]] ?? []
            if let state = apps.first?["playState"] as? String { self?.playing = state == "playing" }
        }
        request("com.webos.service.ime/registerRemoteKeyboard", [:], subscribe: true) { [weak self] info in
            guard let widget = info?["currentWidget"] as? [String: Any] else { return }
            self?.onKeyboard(widget["focus"] as? Bool ?? false)
        }
        // A check every 10 s keeps the link known to be alive (the idle timeout closes a dead one)
        Task { [weak self] in
            while let self, alive(gen) {
                try? await Task.sleep(for: .seconds(10))
                guard alive(gen), main != nil else { return }
                request("com.webos.service.tvpower/power/getPowerState", [:]) { _ in }
            }
        }
    }

    /// The TV names its button socket with ws:// and port 3000; a TV that wants TLS takes it on wss:// and 3001.
    private func pointerURL(_ path: String) -> URL? {
        var p = path
        if UserDefaults.standard.bool(forKey: Self.secureKey) && p.hasPrefix("ws://") {
            p = "wss://" + p.dropFirst(5).replacingOccurrences(of: ":\(Self.wsPort)/", with: ":\(Self.wssPort)/")
        }
        return URL(string: p)
    }

    private func openPointer(_ url: URL, _ gen: Int) async {
        let c = Conn(webSocket: url)
        do {
            try await c.open(timeout: 5)
        } catch {
            if alive(gen) { drop() }
            return
        }
        guard alive(gen) else {
            c.close()
            return
        }
        pointer = c
        becomeReady()
        // The socket sends nothing; a read ends when it closes, and then the link connects again
        Task { [weak self] in
            while true {
                do { _ = try await c.readMessage() } catch {
                    if let self, self.alive(gen), self.pointer === c { self.drop() }
                    return
                }
            }
        }
    }

    /// The link works: run the keys that waited.
    private func becomeReady() {
        ready = true
        let now = Self.now()
        let waiting = queue
        queue.removeAll()
        for p in waiting {
            if p.deadline > now { p.run() } else { p.result(false) }
        }
        report(.connected, standby ? "standby" : "")
    }

    /// Something broke: close, and the session loop connects again.
    private func drop() {
        main?.close()
    }

    private func dropConnections() {
        ready = false
        main?.close()
        main = nil
        pointer?.close()
        pointer = nil
        subscriptions.removeAll()
        let waiting = replies
        replies.removeAll()
        for (_, reply) in waiting { reply(nil) }
    }

    private func stopLink() {
        started = false
        generation += 1
        dropConnections()
        let waiting = queue
        queue.removeAll()
        for p in waiting {
            let result = p.result
            Task { result(false) }
        }
        wake()
    }

    private func pause(_ ms: Int) async {
        sleepId += 1
        let id = sleepId
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            sleeper = (id, c)
            Task {
                try? await Task.sleep(for: .milliseconds(ms))
                wake(id)
            }
        }
    }

    private func wake(_ id: Int? = nil) {
        guard let s = sleeper, id == nil || s.id == id else { return }
        sleeper = nil
        s.c.resume()
    }

    private func report(_ state: State, _ detail: String) {
        if self.state == state && self.detail == detail { return }
        self.state = state
        self.detail = detail
        onStatus(state, detail)
    }
}
