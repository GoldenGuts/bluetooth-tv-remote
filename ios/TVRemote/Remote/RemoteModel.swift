import Observation
import SwiftUI
import UIKit

/**
 The remote. A black screen that takes gestures anywhere. A port of the Android RemoteActivity: keys go to an LG TV
 (webOS, Wi-Fi, in place of the Android Bluetooth link) or to an Android TV or Kodi box (Wi-Fi). With both, input
 and power go to the TV, and volume and mute go where Settings say. The vibration plays only when the key went out,
 else the error buzz.
 */
@Observable
final class RemoteModel: GestureEngine.Listener, SurfaceInput {

    // MARK: - what the screens show

    var statusLine = "starting…"
    var dotColor = Color.gray
    var panelHeader = ""
    var panelConnected = false
    var labelText = ""
    var labelBig = false
    var hint = ""
    var hintVisible = true
    var hintFaded = false
    var statusVisible = true
    var hasBox = false
    var hasTV = false
    /// With a TV and a box: keys go to the box (the "Send keys to" switch).
    var boxIsTarget = true
    /// The TV link, for the setup page.
    private(set) var tvState = TVLink.State.off
    private(set) var tvDetail = ""
    private(set) var tvName = ""
    var panelOpen = false {
        didSet { updateBrightness() }
    }
    var touchpad = false
    var practice = false {
        didSet { showStatus() }
    }
    var showHelp = false {
        didSet { updateBrightness() }
    }
    var showTutorial = false {
        didSet { updateBrightness() }
    }
    var showSetup = false {
        didSet { updateBrightness() }
    }
    var listening = false
    /// The typing field: it mirrors the text box on the box.
    private(set) var fieldText = ""
    /// The field should take the keyboard (the box opened a text field).
    var focusPending = false

    // MARK: - parts

    @ObservationIgnored let haptics = Haptics()
    @ObservationIgnored let box = BoxLink(deviceName: UIDevice.current.model, appName: "TV Remote")
    @ObservationIgnored let tv = TVLink()
    @ObservationIgnored private let motion = Motion()
    @ObservationIgnored private let voice = Voice()
    @ObservationIgnored private var engine: GestureEngine!
    @ObservationIgnored weak var feedback: FeedbackView?

    @ObservationIgnored private var mm: CGFloat = Screen.pointsPerMm
    @ObservationIgnored private var resumed = false
    @ObservationIgnored private var greeted = false
    @ObservationIgnored private var lastBoxState = BoxLink.State.off
    @ObservationIgnored private var lastTVState = TVLink.State.off
    @ObservationIgnored private var turns = 0
    @ObservationIgnored private var primaryId = -1
    @ObservationIgnored private var menuActive = false
    @ObservationIgnored private var slotFired = [Bool](repeating: false, count: 5)
    @ObservationIgnored private var edgeFromRight = false
    @ObservationIgnored private var lastGyro: TimeInterval = 0
    @ObservationIgnored private var knobAxis = 0
    @ObservationIgnored private var knobDir = 1
    @ObservationIgnored private var gravityOn = false
    @ObservationIgnored private var tickTimer: Timer?
    @ObservationIgnored private var clearLabel: Task<Void, Never>?
    @ObservationIgnored private var screenTimeout: Task<Void, Never>?
    @ObservationIgnored private var userBrightness: CGFloat?
    /// True when the box or TV opened the panel and the keyboard: its closing closes them again.
    @ObservationIgnored private var keyboardFromBox = false

    init() {
        engine = GestureEngine(Tuning.gestureConfig(), listener: self)
        box.onStatus = { [weak self] state, detail in self?.boxStatus(state, detail) }
        box.onKeyboard = { [weak self] open, text in
            if self?.boxTarget == true { self?.keyboard(open, text) }
        }
        tv.onStatus = { [weak self] state, detail in self?.tvStatus(state, detail) }
        tv.onKeyboard = { [weak self] open in
            if self?.boxTarget == false { self?.keyboard(open, "") }
        }
        motion.onRotation = { [weak self] _, y, z, t in self?.rotation(y, z, t) }
        motion.onGravity = { [weak self] x, y in self?.gravity(x, y) }
        voice.onState = { [weak self] on in
            self?.listening = on
            if on { self?.label("listening…") }
        }
        voice.onText = { [weak self] said in self?.voiceText(said) }
        voice.onProblem = { [weak self] problem in
            self?.label(problem)
            self?.haptics.play(.error)
        }
        hint = hintText()
        if !UserDefaults.standard.bool(forKey: "tutorialSeen") {
            UserDefaults.standard.set(true, forKey: "tutorialSeen")
            showTutorial = true
        }
    }

    func attach(_ view: FeedbackView) {
        feedback = view
        view.input = self
        view.onResize = { [weak self] size in
            guard let self else { return }
            engine.setSurface(widthMm: Float(size.width / mm), heightMm: Float(size.height / mm))
        }
        applySettings()
    }

    // MARK: - life cycle

    func resume() {
        resumed = true
        greeted = false
        hintFaded = false
        mm = Screen.pointsPerMm
        motion.start()
        lastGyro = 0
        applySettings()
        touched()
        let host = Tuning.host, tvHost = Tuning.tv
        hasBox = !host.isEmpty
        hasTV = !tvHost.isEmpty
        boxIsTarget = boxTarget
        if hasBox {
            box.setInteractive(boxTarget)
            box.start(host)   // Android TV needs a pairing, Kodi none: the link finds out
        } else {
            box.stop()
        }
        if hasTV { tv.start(tvHost) } else { tv.stop() }
        if !hasBox && !hasTV { greet() }   // no link will come up: say so now
        showStatus()
        #if DEBUG
        Shots.apply(self)
        #endif
    }

    func pause() {
        resumed = false
        engine.cancel(time: Screen.now())
        haptics.stop()
        haptics.pause()
        motion.stop()
        voice.finish()
        UIDevice.current.isProximityMonitoringEnabled = false
        UIApplication.shared.isIdleTimerDisabled = false
        screenTimeout?.cancel()
        if touchpad { setTouchpad(false) }
        box.stop()
        tv.stop()
        updateBrightness()
    }

    /// An address or a pairing changed in Settings: connect again.
    func boxChanged() {
        box.stop()
        tv.stop()
        if resumed { resume() }
    }

    // MARK: - gestures -> keys

    func onAction(_ action: GestureEngine.Action, repeat isRepeat: Bool) {
        let twoFinger = !menuActive && Self.isMenuAction(action) || action == .REWIND || action == .FAST_FORWARD
        if twoFinger && Tuning.app(Tuning.twoFinger) == 0 { return }
        showFeedback(action)
        if menuActive && Self.isMenuAction(action) {
            let slot = Self.slot(action)
            let assigned = Tuning.menuAction(slot)
            // Slide down repeats (one per 15 mm) for the input list and volume only; other choices fire once.
            if assigned == 0 || (slotFired[slot] && !Self.repeats(assigned)) { return }
            slotFired[slot] = true
            if assigned == Tuning.switchTarget { switchTarget(!boxTarget) }
            else if assigned == Tuning.touchpad { setTouchpad(true) }
            else { send(assigned, Self.pattern(assigned)) }
            return
        }
        if action == .PLAY_PAUSE && Tuning.app(Tuning.twoTap) == 1 {
            send(Key.back, .back)   // two-finger tap: Back needs no aim (Play/Pause stays on hold + lift)
            return
        }
        switch action {
        case .UP: send(Key.up, .tickV)
        case .DOWN: send(Key.down, .tickV)
        case .LEFT: send(Key.left, .tickH)
        case .RIGHT: send(Key.right, .tickH)
        case .OK: send(Key.ok, .click)
        case .HOME: send(Key.home, .home)
        case .PLAY_PAUSE: send(Key.playPause, .play)
        case .REWIND: send(Key.rewind, .tickH)
        case .FAST_FORWARD: send(Key.fastForward, .tickH)
        case .INPUT: send(Key.input, .input)
        case .SETTINGS: send(Key.settings, .settings)
        case .MUTE: send(Key.mute, .mute)
        case .BACK: send(Key.back, .back)
        case .KNOB_LEFT: send(knobKey(right: false), .tickH)
        case .KNOB_RIGHT: send(knobKey(right: true), .tickH)
        }
    }

    func onCue(_ cue: GestureEngine.Cue) {
        switch cue {
        case .MENU_OPEN:
            haptics.play(.menu)
            menuActive = true
            feedback?.menuOpened()
        case .MUTE_ARMING:
            haptics.play(.rise)
        case .ARM_CANCELLED, .REJECTED:
            haptics.stop()
        }
    }

    private func knobKey(right: Bool) -> Int {
        switch Tuning.app(Tuning.knobKeys) {
        case 1: return right ? Key.fastForward : Key.rewind
        case 2: return right ? Key.volumeUp : Key.volumeDown
        default: return right ? Key.right : Key.left
        }
    }

    private static func isMenuAction(_ a: GestureEngine.Action) -> Bool {
        a == .HOME || a == .SETTINGS || a == .INPUT || a == .MUTE || a == .PLAY_PAUSE
    }

    /// Engine action in the thumb menu -> slot 0..4 = up, right, down, left, lift.
    static func slot(_ a: GestureEngine.Action) -> Int {
        switch a {
        case .HOME: return 0
        case .SETTINGS: return 1
        case .INPUT: return 2
        case .MUTE: return 3
        default: return 4
        }
    }

    private static func repeats(_ action: Int) -> Bool {
        action == Key.input || action == Key.volumeUp || action == Key.volumeDown
    }

    static func pattern(_ action: Int) -> Haptics.Pattern {
        switch action {
        case Key.home: return .home
        case Key.back: return .back
        case Key.input: return .input
        case Key.settings: return .settings
        case Key.mute: return .mute
        case Key.playPause: return .play
        case Key.volumeUp, Key.volumeDown: return .tickV
        default: return .click
        }
    }

    /// Sends one action to the TV or the box. The vibration tells whether it went out.
    func send(_ action: Int, _ pattern: Haptics.Pattern) {
        label(Key.name(action))
        if practice {
            haptics.play(pattern)
            return
        }
        let viaBox = toBox(action)
        deliver(action) { [weak self] ok in
            guard let self else { return }
            haptics.play(ok ? pattern : .error)
            if ok { return }
            feedback?.error()
            if !hasBox && !hasTV { label("set up a TV or box first") }
            else if !viaBox { label("TV: " + (tvProblem() ?? "the key did not go out")) }
            else if box.isKodi && (action == Key.input || action == Key.power) { label("Kodi has no \(Key.name(action)) key") }
            else if box.state != .connected { label("box: " + (box.detail.isEmpty ? "not connected" : box.detail)) }
        }
    }

    // MARK: - TV or box

    /// Keys go to the box: there is no TV, or there are both and the box is chosen.
    var boxTarget: Bool {
        !hasTV || (hasBox && Tuning.app(Tuning.target) == 1)
    }

    private static func volumeKey(_ action: Int) -> Bool {
        action == Key.volumeUp || action == Key.volumeDown || action == Key.mute
    }

    /**
     With the box as the target: the TV keeps its inputs and power. Volume and mute go to the TV, the box, or the
     TV while its link works (a box changes the TV volume only with HDMI-CEC; else it turns its own output down).
     */
    private func toBox(_ action: Int) -> Bool {
        if !hasTV { return true }
        if !boxTarget { return false }
        if action == Key.input || action == Key.power { return false }
        if !Self.volumeKey(action) { return true }
        let to = Tuning.app(Tuning.volumeTo)
        return to == 1 || (to == 2 && tvProblem() != nil)
    }

    private func deliver(_ action: Int, _ r: @escaping BoxLink.Result) {
        if toBox(action) { box.send(action, r) } else { tv.send(action, r) }
    }

    /// The vibration tells the link state of the new target: ready or no link.
    func switchTarget(_ toBox: Bool) {
        guard hasTV && hasBox else {
            label(hasTV ? "no box set up" : "no TV set up")
            haptics.play(.error)
            return
        }
        Tuning.put(Tuning.target, toBox ? 1 : 0)
        boxIsTarget = toBox
        box.setInteractive(toBox)
        label(toBox ? "→ box" : "→ TV")
        if touchpad && !(toBox ? box.hasPointer : tv.hasPointer) { setTouchpad(false) }
        showStatus()
        haptics.play(targetConnected ? .ready : .noLink)
    }

    private var targetConnected: Bool {
        boxTarget ? box.state == .connected : tv.state == .connected
    }

    /// Why the TV link cannot send keys, or nil when it can.
    private func tvProblem() -> String? {
        if !hasTV { return "no TV set up" }
        switch tv.state {
        case .connected: return tv.standby ? "the TV is in standby" : nil
        case .pairing: return "press OK on the TV"
        case .connecting: return "connecting"
        case .off: return "not connected"
        case .waiting: return tv.detail.isEmpty ? "not connected" : tv.detail
        }
    }

    private func showFeedback(_ action: GestureEngine.Action) {
        guard let f = feedback else { return }
        switch action {
        case .UP: f.step(FeedbackView.UP)
        case .RIGHT, .FAST_FORWARD: f.step(FeedbackView.RIGHT)
        case .DOWN: f.step(FeedbackView.DOWN)
        case .LEFT, .REWIND: f.step(FeedbackView.LEFT)
        case .OK: f.tap()
        case .BACK: f.back(fromRight: edgeFromRight)
        case .KNOB_LEFT: f.knob(-1)
        case .KNOB_RIGHT: f.knob(1)
        case .HOME: menuOrTap(FeedbackView.UP)
        case .SETTINGS: menuOrTap(FeedbackView.RIGHT)
        case .INPUT: menuOrTap(FeedbackView.DOWN)
        case .MUTE: menuOrTap(FeedbackView.LEFT)
        case .PLAY_PAUSE:
            if !menuActive && Tuning.app(Tuning.twoTap) == 1 { f.back(fromRight: edgeFromRight) } else { menuOrTap(4) }
        }
    }

    private func menuOrTap(_ dir: Int) {
        if menuActive { feedback?.menuPicked(dir) } else { feedback?.tap() }   // two-finger gestures
    }

    private func hintText() -> String {
        "tap = OK   ·   swipe = arrow\nhold = menu   ·   "
            + (Tuning.app(Tuning.twoTap) == 1 ? "two-finger tap = back" : "edge swipe = back")
    }

    // MARK: - touches

    func surfaceBegan(_ touches: [UITouch], in v: FeedbackView, first: Bool) {
        touched()
        if calibrationLeft > 0 {
            if first, let t = touches.first { calStart = t.location(in: v) }
            return
        }
        if touchpad {
            touchpadBegan(touches, v, first)
            return
        }
        for (i, t) in touches.enumerated() {
            let p = t.location(in: v)
            if first && i == 0 {
                primaryId = Self.id(t)
                menuActive = false
                slotFired = [Bool](repeating: false, count: 5)
                edgeFromRight = p.x > v.bounds.width / 2
                v.touchDown(p)
                hintFaded = true
            }
            engine.down(Self.id(t), Float(p.x / mm), Float(p.y / mm), size: size(t), time: Screen.ms(t.timestamp))
        }
        startTicks()
    }

    func surfaceMoved(_ touches: [UITouch], in v: FeedbackView) {
        if calibrationLeft > 0 { return }
        if touchpad {
            touchpadMoved(touches, v)
            return
        }
        for t in touches {
            let p = t.location(in: v)
            if Self.id(t) == primaryId { v.touchMove(p) }
            engine.move(Self.id(t), Float(p.x / mm), Float(p.y / mm), size: size(t), time: Screen.ms(t.timestamp))
        }
    }

    func surfaceEnded(_ touches: [UITouch], in v: FeedbackView, last: Bool) {
        if calibrationLeft > 0 {
            if last, let t = touches.first { calibrate(t.location(in: v)) }
            return
        }
        if touchpad {
            touchpadEnded(touches, v, last)
            return
        }
        for t in touches {
            engine.up(Self.id(t), time: Screen.ms(t.timestamp))
            if Self.id(t) == primaryId { v.touchUp() }
        }
    }

    func surfaceCancelled(in v: FeedbackView) {
        rightClick?.cancel()
        engine.cancel(time: Screen.now())
        v.touchUp()
    }

    private static func id(_ t: UITouch) -> Int {
        ObjectIdentifier(t).hashValue
    }

    /// The contact size in mm (iOS gives a radius in points).
    private func size(_ t: UITouch) -> Float {
        Float(t.majorRadius * 2 / mm)
    }

    private func startTicks() {
        tickTimer?.invalidate()
        let timer = Timer(timeInterval: 0.016, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.engine.tick(Screen.now())
                if !self.engine.isActive {
                    self.tickTimer?.invalidate()
                    self.tickTimer = nil
                }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        tickTimer = timer
    }

    // MARK: - touchpad: a mouse pointer on a Kodi box

    private static let tapMm: CGFloat = 2
    private static let tapMs: Int64 = 250
    private static let rightClickMs = 550
    private static let scrollStepMm: CGFloat = 7
    /// Pointer travel per mm of finger, as a part of the TV screen. Faster strokes go further.
    private static let pointerGain: CGFloat = 0.010

    @ObservationIgnored private var tpLast = CGPoint.zero
    @ObservationIgnored private var tpStart = CGPoint.zero
    @ObservationIgnored private var tpScrollMm: CGFloat = 0
    @ObservationIgnored private var tpRestX: CGFloat = 0
    @ObservationIgnored private var tpRestY: CGFloat = 0
    @ObservationIgnored private var tpDownAt: Int64 = 0
    @ObservationIgnored private var tpLastAt: Int64 = 0
    @ObservationIgnored private var tpMoved = false
    @ObservationIgnored private var tpTwoFinger = false
    @ObservationIgnored private var tpLongDone = false
    @ObservationIgnored private var tpTouches: [Int: CGPoint] = [:]
    @ObservationIgnored private var rightClick: Task<Void, Never>?

    func setTouchpad(_ wanted: Bool) {
        var on = wanted
        if on && !(boxTarget ? box.hasPointer : tv.hasPointer) {
            label("touchpad: Kodi or LG TV only")
            haptics.play(.error)
            on = false
        }
        touchpad = on
        engine.cancel(time: Screen.now())
        rightClick?.cancel()
        hint = on ? "slide = pointer   ·   tap = click\nhold = menu   ·   two fingers = scroll" : hintText()
        if on {
            panelOpen = false
            label("touchpad")
            haptics.play(.menu)
        }
    }

    private func touchpadBegan(_ touches: [UITouch], _ v: FeedbackView, _ first: Bool) {
        for t in touches { tpTouches[Self.id(t)] = t.location(in: v) }
        if first, let t = touches.first {
            let p = t.location(in: v)
            tpStart = p
            tpLast = p
            tpDownAt = Screen.ms(t.timestamp)
            tpLastAt = tpDownAt
            tpMoved = false
            tpTwoFinger = false
            tpLongDone = false
            rightClick = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(Self.rightClickMs))
                guard let self, !Task.isCancelled, touchpad, boxTarget, !tpMoved, !tpTwoFinger else { return }
                tpLongDone = true
                box.pointerRightClick { [weak self] ok in self?.haptics.play(ok ? .menu : .error) }
            }
            v.touchDown(p)
        }
        if tpTouches.count >= 2 && !tpTwoFinger {
            tpTwoFinger = true
            tpScrollMm = 0
            tpLast.y = tpTouches.values.prefix(2).map(\.y).reduce(0, +) / 2
            rightClick?.cancel()
        }
    }

    private func touchpadMoved(_ touches: [UITouch], _ v: FeedbackView) {
        for t in touches { tpTouches[Self.id(t)] = t.location(in: v) }
        guard let t = touches.first else { return }
        if tpTwoFinger {
            guard tpTouches.count >= 2 else { return }
            let y = tpTouches.values.prefix(2).map(\.y).reduce(0, +) / 2
            tpScrollMm += (y - tpLast.y) / mm
            tpLast.y = y
            while abs(tpScrollMm) >= Self.scrollStepMm {
                let dir = tpScrollMm > 0 ? -1 : 1   // fingers up = content up (like a phone)
                tpScrollMm -= (tpScrollMm > 0 ? 1 : -1) * Self.scrollStepMm
                let r: BoxLink.Result = { [weak self] ok in self?.haptics.play(ok ? .tickV : .error) }
                if boxTarget { box.scroll(dir, r) } else { tv.scroll(dir, r) }
            }
            return
        }
        let p = t.location(in: v), at = Screen.ms(t.timestamp)
        let dxMm = (p.x - tpLast.x) / mm, dyMm = (p.y - tpLast.y) / mm
        if !tpMoved && hypot((p.x - tpStart.x) / mm, (p.y - tpStart.y) / mm) > Self.tapMm {
            tpMoved = true
            rightClick?.cancel()
        }
        if tpMoved {
            let speed = hypot(dxMm, dyMm) / CGFloat(max(1, at - tpLastAt))   // mm per ms
            let gain = Self.pointerGain * (1 + min(3, speed * 4))
            if boxTarget {
                box.pointerMove(Float(dxMm * gain), Float(dyMm * gain))
            } else {
                // The TV moves its pointer in screen pixels (1920 wide): keep the rest for the next move
                tpRestX += dxMm * gain * 1920
                tpRestY += dyMm * gain * 1080
                let x = Int(tpRestX), y = Int(tpRestY)
                tpRestX -= CGFloat(x)
                tpRestY -= CGFloat(y)
                tv.pointerMove(x, y)
            }
        }
        tpLast = p
        tpLastAt = at
        v.touchMove(p)
    }

    private func touchpadEnded(_ touches: [UITouch], _ v: FeedbackView, _ last: Bool) {
        for t in touches { tpTouches[Self.id(t)] = nil }
        guard last, let t = touches.first else { return }
        tpTouches.removeAll()
        rightClick?.cancel()
        v.touchUp()
        if !tpMoved && !tpTwoFinger && !tpLongDone && Screen.ms(t.timestamp) - tpDownAt <= Self.tapMs {
            v.tap()
            let r: BoxLink.Result = { [weak self] ok in self?.haptics.play(ok ? .click : .error) }
            if boxTarget { box.pointerClick(r) } else { tv.pointerClick(r) }
        }
    }

    // MARK: - box status

    private func boxStatus(_ state: BoxLink.State, _ detail: String) {
        if resumed && boxTarget {
            if lastBoxState == .connected && state != .connected && state != .off { haptics.play(.lost) }
            if !greeted && state != .connecting { greet() }
        }
        lastBoxState = state
        if state == .connected && touchpad && boxTarget && !box.hasPointer { setTouchpad(false) }
        showStatus()
    }

    private func tvStatus(_ state: TVLink.State, _ detail: String) {
        if resumed && !boxTarget {
            if lastTVState == .connected && state != .connected && state != .off { haptics.play(.lost) }
            if !greeted && state != .connecting { greet() }
        }
        lastTVState = state
        tvState = state
        tvDetail = detail
        tvName = tv.model
        if state != .connected && touchpad && !boxTarget { setTouchpad(false) }
        showStatus()
    }

    /// The dot, the status line and the panel header show the link of the current target.
    func showStatus() {
        let connected = targetConnected
        var line: String
        let green = Color(red: 40 / 255, green: 200 / 255, blue: 80 / 255)
        let orange = Color(red: 230 / 255, green: 170 / 255, blue: 40 / 255)
        let red = Color(red: 220 / 255, green: 60 / 255, blue: 50 / 255)
        if !hasBox && !hasTV {
            dotColor = .gray
            line = "no TV or box set up · ••• → Settings"
            panelHeader = "No TV or box set up"
        } else if !boxTarget {
            let state = tv.state
            let name = tv.model.isEmpty ? "TV" : tv.model
            dotColor = connected ? (tv.standby ? .gray : green) : state == .connecting || state == .pairing ? orange
                : state == .off ? .gray : red
            line = connected ? name + " · Wi-Fi · " + (tv.standby ? "standby" : "connected")
                : state == .pairing ? "TV · press OK on the TV"
                : state == .connecting ? "TV · connecting"
                : state == .off ? "TV · not connected"
                : "TV · " + tv.detail
            panelHeader = connected ? "Connected to \(name) (Wi-Fi)"
                : state == .pairing ? "Press OK on the TV to allow this iPhone"
                : state == .connecting ? "Connecting to the TV"
                : "TV: " + (tv.detail.isEmpty ? "not connected" : tv.detail)
        } else {
            let state = box.state
            let name = box.model.isEmpty ? "box" : box.model
            dotColor = connected ? green : state == .connecting ? orange : state == .off ? .gray : red
            line = connected ? name + " · Wi-Fi · connected"
                : state == .connecting ? "box · connecting"
                : state == .off ? "box · not connected"
                : "box · " + box.detail
            panelHeader = connected ? "Connected to \(name) (Wi-Fi)"
                : state == .connecting ? "Connecting to the box"
                : "Box: " + (box.detail.isEmpty ? "not connected" : box.detail)
            // Input and power (and maybe volume) still need the TV link: say when it is down
            if hasTV, let problem = tvProblem() {
                line += toBox(Key.volumeUp) ? "\nvolume via box · TV link: " + problem : "\nTV: " + problem
            }
        }
        statusLine = practice ? "practice · no keys sent" : line
        panelConnected = connected
    }

    /// The first vibration after the screen comes on tells the link state.
    private func greet() {
        greeted = true
        haptics.play(targetConnected ? .ready : .noLink)
    }

    // MARK: - sensors

    private func rotation(_ y: Double, _ z: Double, _ t: TimeInterval) {
        // Knob: wrist twist while the thumb holds the menu. Only fed while a finger is down.
        let last = lastGyro
        lastGyro = t
        if last == 0 || !engine.isActive { return }
        // y axis: + = right edge goes down (a doorknob turned right). z axis: + = counter-clockwise, so negate.
        var rate = knobAxis == 1 ? -z : y
        if knobDir == 0 { rate = -rate }
        engine.twist(Float(rate * (t - last) * 180 / .pi), time: Screen.now())
    }

    private func gravity(_ x: Double, _ y: Double) {
        // Gravity turn, in 90° steps, only between touches. Flat on a table: keep the last value.
        if !gravityOn || engine.isActive { return }
        var next = turns
        if abs(x) > 7 && abs(x) > abs(y) * 1.5 { next = x > 0 ? 1 : 3 }   // top edge to the left : right
        else if abs(y) > 7 && abs(y) > abs(x) * 1.5 { next = y > 0 ? 0 : 2 }
        if next != turns {
            turns = next
            engine.setQuarterTurns(turns)
            feedback?.setQuarterTurns(turns)
        }
    }

    // MARK: - screen

    /// Keeps the screen on for a while after each touch.
    private func touched() {
        UIApplication.shared.isIdleTimerDisabled = true
        screenTimeout?.cancel()
        let minutes = Tuning.app(Tuning.keepOnMin)
        screenTimeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(minutes * 60))
            if !Task.isCancelled, self?.resumed == true { UIApplication.shared.isIdleTimerDisabled = false }
        }
    }

    func applySettings() {
        var config = Tuning.gestureConfig()
        config.edgeBack = Tuning.app(Tuning.edgeBack) == 1
        engine.setConfig(config)
        feedback?.setHold(menuArmMs: config.menuArmMs, slopMm: config.holdSlopMm, pointsPerMm: mm)
        haptics.strength = Tuning.app(Tuning.hapticsOn) == 0 ? 0 : Float(Tuning.app(Tuning.hapticStrength)) / 100
        feedback?.setMenuLabels((0..<4).map(Tuning.menuName), center: Tuning.menuName(4))
        feedback?.setQuiet(Tuning.app(Tuning.animations) == 0)
        hintVisible = Tuning.app(Tuning.hints) == 1
        if !touchpad { hint = hintText() }
        statusVisible = Tuning.app(Tuning.statusLine) == 1
        knobAxis = Tuning.app(Tuning.knobAxis)
        knobDir = Tuning.app(Tuning.knobDir)
        gravityOn = Tuning.app(Tuning.gravity) == 1
        if resumed { UIDevice.current.isProximityMonitoringEnabled = Tuning.app(Tuning.pocket) == 1 }
        updateBrightness()
        if !gravityOn {
            turns = 0
            engine.setQuarterTurns(0)
        }
        feedback?.setQuarterTurns(turns)
    }

    /// The remote's own brightness on the gesture surface only. iOS sets it for the whole screen, so the
    /// panel, settings and help get the person's brightness back.
    private func updateBrightness() {
        guard let screen = Screen.window?.screen else { return }
        let onSurface = resumed && !panelOpen && !showHelp && !showSetup && !showTutorial
        if onSurface {
            if userBrightness == nil { userBrightness = screen.brightness }
            screen.brightness = max(0.01, CGFloat(Tuning.app(Tuning.brightness)) / 100)
        } else if let b = userBrightness {
            screen.brightness = b
            userBrightness = nil
        }
    }

    func label(_ text: String) {
        if Tuning.app(Tuning.showLabels) == 0 && !practice && calibrationLeft == 0 { return }
        labelText = text
        labelBig = practice || calibrationLeft > 0
        clearLabel?.cancel()
        let ms = labelBig ? 1500 : 700
        clearLabel = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(ms))
            if !Task.isCancelled { self?.labelText = "" }
        }
    }

    // MARK: - thumb bias calibration

    private static let calibrationSwipes = 10
    @ObservationIgnored private var calibrationLeft = 0
    @ObservationIgnored private var calStart = CGPoint.zero
    @ObservationIgnored private var calAngles: [Double] = []

    /// Measures the angle of straight-up swipes. A thumb arc leans; biasDeg turns the axes to match.
    private func calibrate(_ end: CGPoint) {
        let dx = end.x - calStart.x, dy = end.y - calStart.y
        if -dy < 15 * mm {
            haptics.play(.error)
            return
        }
        calAngles.append(atan2(Double(dx), Double(-dy)) * 180 / .pi)   // + = leans right
        haptics.play(.tickV)
        calibrationLeft -= 1
        if calibrationLeft > 0 {
            label("swipe up: \(calibrationLeft) more")
            return
        }
        let bias = (calAngles.reduce(0, +) / Double(calAngles.count)).rounded()
        Tuning.setField("biasDeg", Float(bias))
        applySettings()
        haptics.play(.ready)
        label(String(format: "thumb bias %.0f°", bias))
    }

    func startCalibration() {
        panelOpen = false
        calAngles.removeAll()
        calibrationLeft = Self.calibrationSwipes
        label("swipe straight up \(Self.calibrationSwipes) times")
    }

    // MARK: - typing: the field mirrors the text box on the box

    /// The box or TV opened (or closed) a keyboard: open the panel with the phone keyboard, and the field's text.
    private func keyboard(_ open: Bool, _ text: String) {
        guard resumed else { return }
        if open {
            fieldText = text
            if !panelOpen {
                keyboardFromBox = true
                panelOpen = true
            }
            focusPending = true
            haptics.play(.menu)
        } else if keyboardFromBox {
            keyboardFromBox = false
            fieldText = ""
            panelOpen = false
        }
    }

    func panelClosed() {
        keyboardFromBox = false
    }

    /// The person changed the field. A change in the middle of the text is sent as if made at the end:
    /// the cursor on the box is at the end.
    func userTyped(_ new: String) {
        let old = fieldText
        fieldText = new
        let same = zip(old, new).prefix { $0 == $1 }.count
        let removed = String(old.dropFirst(same)), added = String(new.dropFirst(same))
        sendBackspaces(removed.utf16.count)
        typeOnBox(added)
    }

    private func voiceText(_ said: String) {
        // A space between earlier text and the spoken words.
        let add = fieldText.isEmpty ? said : " " + said
        typeOnBox(add)
        fieldText += add
        label(said)
    }

    func toggleVoice() {
        voice.toggle()
    }

    private func typeOnBox(_ text: String) {
        if text.isEmpty { return }
        let r: BoxLink.Result = { [weak self] ok in if !ok { self?.haptics.play(.error) } }
        if boxTarget { box.text(text, r) } else { tv.text(text, r) }
    }

    private func sendBackspaces(_ n: Int) {
        let r: BoxLink.Result = { [weak self] ok in if !ok { self?.haptics.play(.error) } }
        if boxTarget { box.delete(n, r) } else { tv.delete(n, r) }
    }

    func backspace() {
        guard let last = fieldText.last else {
            sendBackspaces(1)
            return
        }
        sendBackspaces(String(last).utf16.count)
        fieldText.removeLast()
    }

    func clearField() {
        sendBackspaces(fieldText.utf16.count)
        fieldText = ""
    }

    func sendEnter() {
        let r: BoxLink.Result = { [weak self] ok in self?.haptics.play(ok ? .click : .error) }
        if boxTarget { box.key(Key.codeEnter, r) } else { tv.enter(r) }
        fieldText = ""
    }
}
