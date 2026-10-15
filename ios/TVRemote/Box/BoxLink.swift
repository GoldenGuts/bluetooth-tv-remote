import Foundation
import Network

/**
 The Wi-Fi link to the box: the Google TV remote protocol (port 6466, TLS with the paired certificate), or Kodi
 (JSON-RPC on 9090, EventServer on UDP 9777). Keys are Android key codes or Kodi actions. The link reconnects by
 itself while started. A port of the Android BoxLink, with async reads on the main actor in place of threads.
 */
final class BoxLink {

    enum State: Equatable { case off, waiting, connected, connecting }

    typealias Result = @MainActor @Sendable (Bool) -> Void
    private static let ignore: Result = { _ in }

    /// A text field on the box opened its keyboard (with the text already in it), or closed it.
    var onKeyboard: (Bool, String) -> Void = { _, _ in }
    var onStatus: (State, String) -> Void = { _, _ in }

    static let kodi = 0
    private static let port: UInt16 = 6466
    private static let kodiPort: UInt16 = 9090
    private static let esPort: UInt16 = 9777
    // ping, key, power, volume, app link (no IME: some boxes then show "use the keyboard on the phone")
    private static let allFeatures = 1 | 2 | 32 | 64 | 512
    private static let short = 3
    /// No traffic for this long: the link may be dead without a sign (v1 boxes never ping). Reconnect first.
    private static let staleMs: Int64 = 20_000
    /// Keys pressed while the link connects wait this long, then fail.
    private static let queueMs: Int64 = 1500
    /// Interactive mode (v1): the box switches to its virtual keyboard. Text waits this long after the switch.
    private static let imeSwitchMs: Int64 = 400

    private struct Pending {
        let frame: @MainActor () -> [UInt8]?
        let text: Bool
        let result: Result
        let deadline: Int64
    }

    private let deviceName: String
    private let appName: String

    private var host = ""
    private var generation = 0
    private var started = false
    private var conn: Conn?
    private var queue: [Pending] = []
    /// A reconnect for staleness: the status line does not need to flicker.
    private var quiet = false
    private var ready = false
    private var lastTraffic: Int64 = 0
    private var features = BoxLink.allFeatures
    private var version = 2
    private var counter: Int64 = 0
    private var sleeper: (id: Int, c: CheckedContinuation<Void, Never>)?
    private var sleepId = 0

    private(set) var state = State.off
    private(set) var detail = ""
    /// The box name it reports, e.g. "AM9 PRO" or "Kodi · CoreELEC".
    private(set) var model = ""

    private var interactive = false
    private var interactiveAt: Int64 = 0
    /// The Enter action of the field that has the keyboard (EditorInfo.IME_MASK_ACTION), from ShowIME.
    private var editorAction = 0
    /// The text in Kodi's keyboard: Input.SendText replaces it all, so the phone sends the whole text each time.
    private let kodiField = NSMutableString()
    /// Kodi 19 or later (JSON-RPC 12): it has Input.ButtonEvent.
    private var kodiButtons = false

    init(deviceName: String, appName: String) {
        self.deviceName = deviceName
        self.appName = appName
    }

    /// Connects and stays connected until stop(). Same host again: only tries again now if it waits.
    func start(_ host: String) {
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

    var isConnected: Bool { ready }

    /// The box runs Kodi (for example CoreELEC), not Android TV. Known after the first connect.
    var isKodi: Bool { version == Self.kodi }

    /// A mouse pointer works on this box now (Kodi).
    var hasPointer: Bool { version == Self.kodi && ready }

    /// A remote action. The frame is made at send time: the protocol is known only once the link is up.
    func send(_ action: Int, _ r: @escaping Result) {
        submit({ [unowned self] in
            if version == Self.kodi { return kodiAction(action) }
            if version == 1 && action == Key.home { return Self.intentV1(Self.homeIntent) }
            return keyFrame(Key.code(action))
        }, text: false, r)
    }

    /// One Android key code, pressed and released. While the link connects, the key waits up to 1.5 s.
    func key(_ code: Int, _ r: @escaping Result) {
        submit({ [unowned self] in
            if version == 1 && code == Key.codeEnter && (2...7).contains(editorAction) {
                return Self.imeV1(1, Self.int32(editorAction))   // Go, Search, Send, Next, Done
            }
            if version != Self.kodi { return keyFrame(code) }
            if code != Key.codeEnter { return nil }
            let done = kodiField as String   // Enter: Kodi takes the text and closes its keyboard
            kodiField.setString("")
            return Self.kodiText(done, done: true)
        }, text: false, r)
    }

    private func keyFrame(_ code: Int) -> [UInt8]? {
        if code == 0 { return nil }
        return version == 1 ? keyV1(code) : Proto().message(10, Proto().varint(1, code).varint(2, Self.short)).frame()
    }

    /**
     Typing on the phone starts (true) or ends (false). A v1 box then switches to its own virtual keyboard,
     which takes text from the phone; letter keys do not work there. A v2 box needs nothing.
     */
    func setInteractive(_ on: Bool) {
        if interactive == on { return }
        interactive = on
        if on { interactiveAt = Self.now() }
        if version == 1 && ready { conn?.send(Self.interactiveV1(on)) }
    }

    /// Deletes n characters (UTF-16 units) before the cursor.
    func delete(_ n: Int, _ r: @escaping Result) {
        if n <= 0 { return }
        if version == Self.kodi {
            submit({ [unowned self] in
                kodiField.deleteCharacters(in: NSRange(location: max(0, kodiField.length - n), length: min(n, kodiField.length)))
                return Self.kodiText(kodiField as String, done: false)
            }, text: true, r)
            return
        }
        if version == 1 {
            submit({ Self.imeV1(4, Self.int32(n) + Self.int32(0)) }, text: true, r)
            return
        }
        for i in 0..<n { key(Key.codeDel, i == n - 1 ? r : Self.ignore) }
    }

    /// Text at the cursor. v1: committed through the virtual keyboard, any character.
    /// v2: key presses; upper case comes out as lower case (no shift).
    func text(_ text: String, _ r: @escaping Result) {
        if version == Self.kodi {
            submit({ [unowned self] in
                kodiField.append(text)
                return Self.kodiText(kodiField as String, done: false)
            }, text: true, r)
            return
        }
        if version == 1 {
            if !interactive { setInteractive(true) }
            let utf8 = Array(text.utf8)
            // string present, length, bytes; cursor after
            let args = [UInt8(0)] + Self.int32(utf8.count) + utf8 + Self.int32(1)
            submit({ Self.imeV1(3, args) }, text: true, r)
            return
        }
        let codes = text.map(Key.charCode).filter { $0 != 0 }
        let all = codes.count == text.count
        if codes.isEmpty {
            Task { r(all) }
            return
        }
        let last: Result = { ok in r(ok && all) }
        for (i, code) in codes.enumerated() {
            key(code, i == codes.count - 1 ? last : Self.ignore)
        }
    }

    private func submit(_ frame: @escaping @MainActor () -> [UInt8]?, text: Bool, _ r: @escaping Result) {
        let now = Self.now()
        if ready && now - lastTraffic < Self.staleMs {
            lastTraffic = now
            sendNow(Pending(frame: frame, text: text, result: r, deadline: now))
            return
        }
        if !started {
            Task { r(false) }
            return
        }
        if ready {
            quiet = true
            ready = false
            closeConn()
        }
        queue.append(Pending(frame: frame, text: text, result: r, deadline: now + Self.queueMs))
        wake()
        Task {
            try? await Task.sleep(for: .milliseconds(Self.queueMs + 20))
            expire()
        }
    }

    private func sendNow(_ p: Pending) {
        let wait = p.text && version == 1 ? interactiveAt + Self.imeSwitchMs - Self.now() : 0
        let frame = p.frame, result = p.result
        let send = { [weak self] in
            guard let self, let f = frame(), let conn = self.conn else {
                Task { result(false) }
                return
            }
            conn.send(f) { [weak self] ok in
                if !ok { self?.closeConn() }   // the reader sees it and the loop connects again
                result(ok)
            }
        }
        if wait > 0 {
            Task {
                try? await Task.sleep(for: .milliseconds(wait))
                send()
            }
        } else {
            send()
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
                if quiet && conn == nil {
                    delay = 0   // closed on purpose for staleness: go again at once
                } else {
                    report(.waiting, Conn.reason(error))
                }
            }
            ready = false
            closeConn()
            if !alive(gen) { break }
            if delay > 0 { await pause(queue.isEmpty ? delay : 200) }   // keys wait: try again soon
            delay = max(500, min(delay * 2, 10_000))
        }
    }

    /**
     Android TV first (6466). If nothing listens there but Kodi does (9090), the box runs Kodi:
     a dual-boot box (Android and CoreELEC) keeps one address, so the link follows whichever OS is up.
     */
    private func session(_ gen: Int) async throws {
        let identity = try BoxIdentity.load()
        if !quiet { report(.connecting, "") }
        let android: Conn?
        do {
            android = try await openAndroid(identity)
            quiet = false
        } catch {
            quiet = false
            throw error
        }
        guard let c = android else {
            try await kodiSession(gen)
            return
        }
        guard alive(gen) else {
            c.close()
            return
        }
        conn = c
        if version == 1 {
            c.idleTimeout(nil)   // a v1 box sends nothing after the configure ack
            counter = 0
            c.send(configureV1())
            while alive(gen) { try await handleV1(c) }
        } else {
            c.idleTimeout(20)    // the box pings every 5 s
            while alive(gen) { try handle(try Proto.parse(try await c.readFrame())) }
        }
    }

    /// The Android TV link, or nil when the box runs Kodi now.
    private func openAndroid(_ identity: BoxIdentity.Loaded) async throws -> Conn? {
        guard let pin = BoxIdentity.pin else {
            if await Conn.probe(host, Self.kodiPort) { return nil }
            throw LinkError.message("not paired: Settings → Box → Pair")
        }
        version = BoxIdentity.version
        let c = Conn(host: host, port: Self.port, tls: .init(identity: identity.identity, pin: pin))
        do {
            try await c.open(timeout: 8)
            return c
        } catch where Conn.refused(error) {
            if await Conn.probe(host, Self.kodiPort) { return nil }
            throw error
        }
    }

    /// The link works: send the keys that waited.
    private func becomeReady() {
        ready = true
        lastTraffic = Self.now()
        let now = lastTraffic
        if version == 1 && interactive {
            interactiveAt = now
            conn?.send(Self.interactiveV1(true))
        }
        let waiting = queue
        queue.removeAll()
        for p in waiting {
            if p.deadline > now { sendNow(p) } else { p.result(false) }
        }
        report(.connected, "")
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

    // MARK: - pointer: Kodi EventServer (UDP 9777) moves Kodi's mouse; clicks and scrolls go over JSON-RPC

    private static let esHelo: UInt16 = 1, esMouse: UInt16 = 4
    private let esUid = UInt32.random(in: 0...UInt32.max)
    private var es: NWConnection?
    private var esHost = ""
    private var esHelloAt: Int64 = -60_000
    private var px: Float = 0.5, py: Float = 0.5   // pointer position, 0..1 of the screen

    /// Moves the pointer by a part of the screen (1 = the full width or height).
    func pointerMove(_ dx: Float, _ dy: Float) {
        if version != Self.kodi { return }
        px = max(0, min(1, px + dx))
        py = max(0, min(1, py + dy))
        let x = UInt16((px * 65535).rounded()), y = UInt16((py * 65535).rounded())
        esSend(Self.esMouse, [0x01] + Self.int16(x) + Self.int16(y))   // absolute
    }

    /// Click on the item under the pointer: the pointer gives it focus, Select takes it (in the player: the bar).
    func pointerClick(_ r: @escaping Result) {
        submit({ [unowned self] in kodiAction(Key.ok) }, text: false, r)
    }

    func pointerRightClick(_ r: @escaping Result) {
        submit({ Self.kodi("Input.ContextMenu") }, text: false, r)
    }

    /// Wheel steps: up is negative.
    func scroll(_ steps: Int, _ r: @escaping Result) {
        let n = abs(steps)
        for i in 0..<n {
            submit({ Self.kodiExecute(steps < 0 ? "scrollup" : "scrolldown") }, text: false, i == n - 1 ? r : Self.ignore)
        }
    }

    /// EventServer packet: "XBMC", version 2.0, type, sequence 1 of 1, payload size, client id, 10 reserved bytes.
    private func esSend(_ type: UInt16, _ payload: [UInt8]) {
        if es == nil || esHost != host {
            es?.cancel()
            let c = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: Self.esPort) ?? 1, using: .udp)
            c.start(queue: .main)
            es = c
            esHost = host
            esHelloAt = -60_000
        }
        let now = Self.now()
        if now - esHelloAt > 30_000 {   // Kodi forgets a client after 60 s of silence
            esHelloAt = now
            let name = Array(appName.utf8)
            esRaw(Self.esHelo, name + [0, 0] + [0, 0] + [0, 0, 0, 0] + [0, 0, 0, 0])   // no icon
        }
        esRaw(type, payload)
    }

    private func esRaw(_ type: UInt16, _ payload: [UInt8]) {
        var b = Array("XBMC".utf8) + [2, 0] + Self.int16(type) + Self.int32(1) + Self.int32(1)
        b += Self.int16(UInt16(payload.count)) + Self.int32(Int(esUid)) + [UInt8](repeating: 0, count: 10) + payload
        es?.send(content: Data(b), completion: .idempotent)   // UDP: a lost move is replaced by the next one
    }

    // MARK: - Kodi JSON-RPC (port 9090: raw TCP, JSON objects back to back, no auth)

    /// Kodi answers and the Android TV remote does not: no pairing needed.
    static func kodiAt(_ host: String) async -> Bool {
        if await Conn.probe(host, port) { return false }
        return await Conn.probe(host, kodiPort)
    }

    private func kodiSession(_ gen: Int) async throws {
        let c = Conn(host: host, port: Self.kodiPort, tls: nil)
        try await c.open(timeout: 3)
        guard alive(gen) else {
            c.close()
            return
        }
        conn = c
        version = Self.kodi
        kodiField.setString("")
        c.idleTimeout(5)
        c.send(Self.kodi("XBMC.GetInfoLabels", "{\"labels\":[\"System.FriendlyName\"]}"))
        c.send(Self.kodi("JSONRPC.Version"))
        let info = try await kodiReply(c)   // Kodi answers in order
        let name = info?["System.FriendlyName"] as? String ?? ""
        let major = (try await kodiReply(c)?["version"] as? [String: Any])?["major"] as? Int ?? 0
        kodiButtons = major >= 12
        model = name.isEmpty ? "Kodi" : "Kodi · \(name)"
        c.idleTimeout(nil)   // Kodi sends notifications only when something happens
        becomeReady()
        while alive(gen) {
            let m = Self.object(try await c.readJsonObject())
            lastTraffic = Self.now()
            switch m?["method"] as? String {
            case "Input.OnInputRequested":
                let data = (m?["params"] as? [String: Any])?["data"] as? [String: Any]
                let value = data?["value"] as? String ?? ""
                kodiField.setString(value)
                onKeyboard(true, value)
            case "Input.OnInputFinished":
                onKeyboard(false, "")
            default:
                break
            }
        }
    }

    private static func object(_ bytes: [UInt8]) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: Data(bytes))) as? [String: Any]
    }

    /// The result of the next answer to a request: notifications (they have a method) may come first.
    private func kodiReply(_ c: Conn) async throws -> [String: Any]? {
        while true {
            let m = Self.object(try await c.readJsonObject())
            if m?["method"] == nil { return m?["result"] as? [String: Any] }
        }
    }

    private func kodiAction(_ action: Int) -> [UInt8]? {
        if kodiButtons, let button = Self.kodiButton(action) {
            return Self.kodi("Input.ButtonEvent", "{\"button\":\"\(button)\",\"keymap\":\"R1\"}")
        }
        switch action {
        case Key.up: return Self.kodi("Input.Up")
        case Key.down: return Self.kodi("Input.Down")
        case Key.left: return Self.kodi("Input.Left")
        case Key.right: return Self.kodi("Input.Right")
        case Key.ok: return Self.kodi("Input.Select")
        case Key.back: return Self.kodi("Input.Back")
        case Key.home: return Self.kodi("Input.Home")
        case Key.settings: return Self.kodi("Input.ContextMenu")
        case Key.playPause: return Self.kodiExecute("playpause")
        case Key.rewind: return Self.kodiExecute("stepback")
        case Key.fastForward: return Self.kodiExecute("stepforward")
        case Key.volumeUp: return Self.kodiExecute("volumeup")
        case Key.volumeDown: return Self.kodiExecute("volumedown")
        case Key.mute: return Self.kodiExecute("mute")
        default: return nil   // input, power: Kodi has no such key
        }
    }

    /**
     Keys whose meaning depends on the screen go through Kodi's remote keymap, as from a real remote:
     in the video player OK opens the player bar and left/right step. Input.Select, Input.Left and the
     like skip the keymap, and the player ignores them.
     */
    private static func kodiButton(_ action: Int) -> String? {
        switch action {
        case Key.up: return "up"
        case Key.down: return "down"
        case Key.left: return "left"
        case Key.right: return "right"
        case Key.ok: return "select"
        case Key.back: return "back"
        case Key.settings: return "menu"
        default: return nil
        }
    }

    private static func kodiExecute(_ action: String) -> [UInt8] {
        kodi("Input.ExecuteAction", "{\"action\":\"\(action)\"}")
    }

    private static func kodiText(_ text: String, done: Bool) -> [UInt8] {
        kodi("Input.SendText", "{\"text\":\(BoxPairing.quote(text)),\"done\":\(done)}")
    }

    private static func kodi(_ method: String, _ params: String? = nil) -> [UInt8] {
        Array("{\"jsonrpc\":\"2.0\",\"method\":\"\(method)\"\(params.map { ",\"params\":\($0)" } ?? ""),\"id\":1}".utf8)
    }

    // MARK: - version 1 (older Android TV remote service): [1, type, length (2 bytes), payload]

    private static let v1Configure: UInt8 = 0, v1Key: UInt8 = 2, v1ShowIme: UInt8 = 5, v1HideIme: UInt8 = 6,
                       v1ConfigureAck: UInt8 = 7, v1Intent: UInt8 = 16, v1Ime: UInt8 = 18, v1Interactive: UInt8 = 19, v1Ping: UInt8 = 20,
                       v1Pong: UInt8 = 21

    private func handleV1(_ c: Conn) async throws {
        _ = try await c.readByte()   // version, always 1
        let type = try await c.readByte()
        let payload = try await c.read(try await c.readUInt16())
        lastTraffic = Self.now()
        switch type {
        case Self.v1ConfigureAck:
            model = Self.v1Model(payload)
            becomeReady()
        case Self.v1Ping:
            c.send([1, Self.v1Pong, 0, 0])
        case Self.v1ShowIme:
            showImeV1(payload)
        case Self.v1HideIme:
            editorAction = 0
            onKeyboard(false, "")
        default:
            break
        }
    }

    /**
     ShowIME: the box's EditorInfo, then its ExtractedText. Sent only to an interactive client.
     inputType, imeOptions, privateImeOptions, actionLabel, actionId, selStart, selEnd, capsMode, hint, label,
     packageName, fieldId, fieldName, a flag, 127, then the text (absent flag, string, six ints).
     */
    private func showImeV1(_ payload: [UInt8]) {
        var r = V1Reader(payload)
        var text = ""
        if r.int() != nil, let options = r.int() {
            editorAction = options & 0xFF   // imeOptions & IME_MASK_ACTION
            _ = r.string()
            _ = r.string()
            for _ in 0..<4 { _ = r.int() }
            _ = r.string()
            _ = r.string()
            _ = r.string()
            _ = r.int()
            _ = r.string()
            _ = r.byte()
            _ = r.byte()
            if r.byte() == 0, let s = r.string() { text = s ?? "" }
        }
        // A shorter packet from another service version: open the keyboard anyway
        onKeyboard(true, text)
    }

    /// Reads ShowIME fields. Each read gives nil past the end.
    private struct V1Reader {
        let b: [UInt8]
        var pos = 0
        init(_ b: [UInt8]) { self.b = b }

        mutating func byte() -> UInt8? {
            guard pos < b.count else { return nil }
            pos += 1
            return b[pos - 1]
        }

        mutating func int() -> Int? {
            guard pos + 4 <= b.count else { return nil }
            let v = Int(Int32(bitPattern: UInt32(b[pos]) << 24 | UInt32(b[pos + 1]) << 16 | UInt32(b[pos + 2]) << 8 | UInt32(b[pos + 3])))
            pos += 4
            return v
        }

        /// A string: 0 = present, then length (4 bytes) and UTF-8; any other first byte = null (.some(nil)).
        mutating func string() -> String?? {
            guard let flag = byte() else { return nil }
            if flag != 0 { return .some(nil) }
            guard let n = int(), n >= 0, pos + n <= b.count else { return nil }
            pos += n
            return .some(String(decoding: b[pos - n..<pos], as: UTF8.self))
        }
    }

    /// Configure: two ints (1, 1), 32, 3, six zero bytes, then the name with a 1-byte length.
    private func configureV1() -> [UInt8] {
        let name = Array("test".utf8)
        let body = Self.int32(1) + Self.int32(1) + [32, 3] + [UInt8](repeating: 0, count: 6) + [UInt8(name.count)] + name
        return [1, Self.v1Configure] + Self.int16(UInt16(body.count)) + body
    }

    /// Key press and release: a counter (8 bytes), 0 = down / 1 = up (4 bytes), the key code (4 bytes).
    private func keyV1(_ code: Int) -> [UInt8] {
        var b: [UInt8] = []
        for up in 0..<2 {
            b += [1, Self.v1Key, 0, 16]
            b += Self.int64(counter) + Self.int32(up) + Self.int32(code)
            counter += 1
        }
        return b
    }

    /**
     Home as a start of the home screen, not the Home key. Android TV drops the Home key until the secure
     setting tv_user_setup_complete is 1, and boxes without Google's setup (Ugoos) never set it.
     */
    static let homeIntent = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.HOME;end"

    /// An intent URI (Intent.toUri with the intent scheme): the box starts it as an activity.
    private static func intentV1(_ uri: String) -> [UInt8] {
        let u = Array(uri.utf8)
        return [1, v1Intent] + int16(UInt16(u.count)) + u
    }

    /// Interactive on/off: the box switches its keyboard to the virtual one (and back).
    private static func interactiveV1(_ on: Bool) -> [UInt8] {
        [1, v1Interactive, 0, 1, on ? 1 : 0]
    }

    /**
     A call on the box's input connection: 1 performEditorAction, 2 setComposingText, 3 commitText,
     4 deleteSurroundingText, ... (from the box's remote service, AtvRemote.ServerPacketParser).
     */
    private static func imeV1(_ call: UInt8, _ args: [UInt8]) -> [UInt8] {
        [1, v1Ime] + int16(UInt16(1 + args.count)) + [call] + args
    }

    /// The ack holds strings with a 1-byte length; the last one is the full device name.
    static func v1Model(_ p: [UInt8]) -> String {
        var last = ""
        var i = 4   // remote id
        while i < p.count {
            if i + 4 <= p.count && p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 0 {
                i += 4
                continue
            }
            let n = Int(p[i])
            if n == 0 || i + 1 + n > p.count { break }
            last = String(decoding: p[(i + 1)..<(i + 1 + n)], as: UTF8.self)
            i += 1 + n
        }
        return last
    }

    // MARK: - version 2

    private func handle(_ m: [Int: Proto.Value]) throws {
        lastTraffic = Self.now()
        if m[1] != nil {               // configure: say what this remote does
            let cfg = try Proto.sub(m, 1)
            let info = try Proto.sub(cfg, 2)
            model = Proto.str(info, 1)
            features = Self.allFeatures & Int(Proto.num(cfg, 1))
            conn?.send(Proto().message(1, Proto()
                .varint(1, features)
                .message(2, Proto().string(1, deviceName).string(2, appName)
                    .varint(3, 1).string(4, "1").string(5, "in.weenja.tvremote").string(6, "1"))).frame())
            if features & 2 == 0 { report(.waiting, "the box does not take keys") }
        } else if m[2] != nil {        // set active
            conn?.send(Proto().message(2, Proto().varint(1, features)).frame())
            becomeReady()
        } else if m[8] != nil {        // ping
            let v = Proto.num(try Proto.sub(m, 8), 1)
            conn?.send(Proto().message(9, Proto().varint(1, v)).frame())
        } else if m[40] != nil {       // start: the box is ready
            if !ready { becomeReady() }
        }
    }

    // MARK: - helpers

    private func stopLink() {
        started = false
        generation += 1
        ready = false
        quiet = false
        closeConn()
        let waiting = queue
        queue.removeAll()
        for p in waiting {
            let result = p.result
            Task { result(false) }
        }
        wake()
        es?.cancel()
        es = nil
    }

    private func closeConn() {
        conn?.close()
        conn = nil
    }

    private func report(_ state: State, _ detail: String) {
        if self.state == state && self.detail == detail { return }
        self.state = state
        self.detail = detail
        onStatus(state, detail)
    }

    static func int16(_ v: UInt16) -> [UInt8] {
        [UInt8(v >> 8), UInt8(v & 0xFF)]
    }

    static func int32(_ v: Int) -> [UInt8] {
        let u = UInt32(truncatingIfNeeded: v)
        return [UInt8(u >> 24), UInt8(u >> 16 & 0xFF), UInt8(u >> 8 & 0xFF), UInt8(u & 0xFF)]
    }

    static func int64(_ v: Int64) -> [UInt8] {
        let u = UInt64(bitPattern: v)
        return (0..<8).map { UInt8(u >> (8 * (7 - UInt64($0))) & 0xFF) }
    }
}
