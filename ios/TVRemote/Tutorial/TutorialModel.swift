import Observation
import SwiftUI
import UIKit

/**
 Lessons for the one-thumb map, one gesture at a time, then free practice.
 Nothing goes to the box. Touch and gyroscope feed the engine like the remote.
 */
@Observable
final class TutorialModel: GestureEngine.Listener, SurfaceInput {

    private enum Goal { case tap, right, up3, repeatHold, back, lift, slide, knob }
    private static let words = ["up", "right", "down", "left"]

    private struct Lesson {
        let goal: Goal
        let slot: Int
        let title: String
        let text: String
        let hint: String
    }

    // MARK: - what the screen shows

    var stepText = ""
    var dots: [Color] = []
    var title = ""
    var bodyText = ""
    var status = ""
    var statusColor = Color.gray
    var bigLabel = ""
    var bigVisible = false
    var showBack = false
    var showSkip = true
    var showDone = false

    // MARK: - parts

    @ObservationIgnored private let haptics: Haptics
    @ObservationIgnored private let motion = Motion()
    @ObservationIgnored private let config: GestureConfig
    @ObservationIgnored private var engine: GestureEngine!
    @ObservationIgnored private var lessons: [Lesson] = []
    @ObservationIgnored let mm = Screen.pointsPerMm
    @ObservationIgnored weak var feedback: FeedbackView?
    @ObservationIgnored weak var demo: DemoView?

    @ObservationIgnored private var index = 0
    @ObservationIgnored private var practice = false
    @ObservationIgnored private var advancing = false
    @ObservationIgnored private var lastGyro: TimeInterval = 0
    @ObservationIgnored private var tickTimer: Timer?
    @ObservationIgnored private var pending: [Task<Void, Never>] = []
    @ObservationIgnored private var clearBig: Task<Void, Never>?

    // the current touch
    @ObservationIgnored private var primaryId = -1
    @ObservationIgnored private var fingerDown = false
    @ObservationIgnored private var menuActive = false
    @ObservationIgnored private var edgeFromRight = false
    @ObservationIgnored private var stale = false
    @ObservationIgnored private var wrong = false
    @ObservationIgnored private var acted = false
    @ObservationIgnored private var downAt: Int64 = 0
    @ObservationIgnored private var count = 0
    @ObservationIgnored private var liftHint: String?
    @ObservationIgnored private var slotFired = [Bool](repeating: false, count: 5)

    init(haptics: Haptics) {
        self.haptics = haptics
        var c = Tuning.gestureConfig()
        c.edgeBack = true
        config = c
        engine = GestureEngine(c, listener: self)
        haptics.strength = Float(Tuning.app(Tuning.hapticStrength)) / 100
        motion.onRotation = { [weak self] _, y, z, t in self?.rotation(y, z, t) }
        buildLessons()
    }

    var demoConfig: GestureConfig { config }

    func attach(_ surface: FeedbackView, _ demo: DemoView) {
        feedback = surface
        self.demo = demo
        surface.input = self
        surface.setMenuLabels((0..<4).map(Tuning.menuName), center: Tuning.menuName(4))
        surface.setHold(menuArmMs: config.menuArmMs, slopMm: config.holdSlopMm, pointsPerMm: mm)
        surface.onResize = { [weak self] size in
            guard let self else { return }
            engine.setSurface(widthMm: Float(size.width / mm), heightMm: Float(size.height / mm))
        }
        #if DEBUG
        if let lesson = Shots.lesson {
            index = lesson
            demo.frozenT = 1900   // a drag with all its ticks
        }
        #endif
        showLesson(index)
    }

    func start() {
        motion.start()
        lastGyro = 0
        UIApplication.shared.isIdleTimerDisabled = true
    }

    func stop() {
        engine.cancel(time: Screen.now())
        haptics.stop()
        motion.stop()
        tickTimer?.invalidate()
        pending.forEach { $0.cancel() }
        demo?.stopAll()
    }

    // MARK: - lessons

    private func buildLessons() {
        let menu = config.menuArmMs < 100_000   // Settings can turn the thumb menu off
        add(.tap, 0, "Tap = OK", "Tap anywhere on the black area.", "a quick tap")
        if config.flick {
            add(.right, 0, "Swipe = arrow", "Swipe right. One swipe = one tick = one step.", "a short swipe right")
            add(.up3, 0, "Many steps", "Drag up a long way in ONE touch: one tick, then more ticks after about "
                + "\(Int((config.flickMoreMm / 10).rounded())) cm.", "drag up far, 3 ticks in one touch")
        } else {
            add(.right, 0, "Drag = arrows", "Drag right until you feel a tick. Each tick = one step.", "drag right")
            add(.up3, 0, "Many steps", "Drag up for 3 ticks in ONE touch. A new touch starts the count again.",
                "drag up 3 ticks in one touch")
        }
        if config.repeatDelayMs < 100_000 {
            add(.repeatHold, 0, "Hold = repeat", "Drag down one tick, then stop and keep holding. It repeats while you hold.",
                "drag down, then stop and hold")
        }
        if twoTapBack() {
            add(.back, 0, "Back", "Tap with two fingers. Or swipe in from the left or right edge.", "a two-finger tap")
        } else {
            add(.back, 0, "Back", "Swipe in from the left or right edge of the screen.", "swipe in from the side edge")
        }
        if !menu { return }
        if Tuning.menuAction(4) != 0 {
            add(.lift, 4, "Hold, lift = " + Tuning.menuName(4), "Hold still. The ring fills while you wait. At the thud, lift.",
                "hold still until the thud, then lift")
        }
        for d in 0..<4 where Tuning.menuAction(d) != 0 {   // "Nothing": no lesson
            add(.slide, d, "Hold, slide \(Self.words[d]) = " + Tuning.menuName(d),
                "Hold still until the thud, then slide \(Self.words[d]) without lifting.", "hold, then slide " + Self.words[d])
        }
        if config.knob && motion.hasGyro {
            let like = Tuning.app(Tuning.knobAxis) == 1 ? "a steering wheel" : "a doorknob"
            add(.knob, 0, "Knob", "Hold until the thud, then twist your wrist like \(like). One notch per step.",
                "hold, then twist your wrist")
        }
    }

    private func add(_ goal: Goal, _ slot: Int, _ title: String, _ text: String, _ hint: String) {
        lessons.append(Lesson(goal: goal, slot: slot, title: title, text: text, hint: hint))
    }

    private func showLesson(_ i: Int) {
        pending.forEach { $0.cancel() }
        pending.removeAll()
        index = max(0, min(lessons.count, i))
        practice = false
        advancing = false
        if fingerDown { stale = true }   // the rest of this touch belongs to the old lesson
        count = 0
        wrong = false
        liftHint = nil
        bigLabel = ""
        let n = lessons.count
        dots = (0..<n).map { $0 < index ? Theme.accent : $0 == index ? .white : Color(white: 0.24) }
        showBack = index > 0
        if index == n {
            stepText = "Done"
            title = "You know it all."
            bodyText = "Every gesture works with one thumb, eyes on the TV."
            status = ""
            showSkip = false
            showDone = true
            demo?.play(.none)
            return
        }
        let l = lessons[index]
        stepText = "Step \(index + 1) of \(n)"
        title = l.title
        bodyText = l.text
        say("Your turn", .gray)
        showSkip = true
        showDone = false
        switch l.goal {
        case .tap: demo?.play(.tap)
        case .right: demo?.play(.drag, dir: FeedbackView.RIGHT)
        case .up3: demo?.play(.drag, dir: FeedbackView.UP, steps: 3)
        case .repeatHold: demo?.play(.repeatHold, dir: FeedbackView.DOWN)
        case .back: demo?.play(.edge, dir: FeedbackView.LEFT)
        case .lift: demo?.play(.lift, label: Tuning.menuName(4))
        case .slide: demo?.play(.slide, dir: l.slot, label: Tuning.menuName(l.slot))
        case .knob: demo?.play(.twist)
        }
    }

    func back() {
        showLesson(practice ? lessons.count : index - 1)
    }

    func skip() {
        showLesson(index + 1)
    }

    func startPractice() {
        showLesson(lessons.count)
        practice = true
        stepText = "Practice"
        title = "Practice"
        bodyText = "Every gesture shows its name. Nothing is checked, nothing goes to the TV."
        showDone = false
    }

    private func success() {
        advancing = true
        say("✓ Nice", Theme.accent)
        later(180) { [weak self] in self?.haptics.play(.ready) }   // after the action's own vibration
        later(900) { [weak self] in
            guard let self else { return }
            showLesson(index + 1)
        }
    }

    private func later(_ ms: Int, _ run: @escaping () -> Void) {
        pending.append(Task {
            try? await Task.sleep(for: .milliseconds(ms))
            if !Task.isCancelled { run() }
        })
    }

    /// One recognized action in a lesson: right, a step on the way, or wrong.
    private func check(_ a: GestureEngine.Action, _ isRepeat: Bool) {
        let name = name(a)
        if practice {
            big(name)
            return
        }
        if advancing || index >= lessons.count { return }
        let l = lessons[index]
        switch l.goal {
        case .tap:
            if a == .OK { return success() }
        case .right:
            if a == .RIGHT { return success() }
        case .up3:
            if a == .UP || (a == .DOWN && count > 0) {   // dragging back counts down
                count += a == .UP ? 1 : -1
                if count >= 3 { return success() }
                say("\(count) of 3", .white)
                liftHint = "That was \(count) \(count == 1 ? "step" : "steps"). Try again: 3 ticks in one touch."
                return
            }
        case .repeatHold:
            if a == .DOWN && isRepeat { return success() }
            if a == .DOWN || (a == .UP && count > 0) {
                count += 1
                say("Now stop and keep holding…", .white)
                liftHint = "Steps, but no repeat. Try: stop after the tick and keep holding."
                return
            }
        case .back:
            if a == .BACK { return success() }
        case .lift, .slide:
            if menuActive && Self.slot(a) == l.slot { return success() }
        case .knob:
            if a == .KNOB_LEFT || a == .KNOB_RIGHT {
                count += 1
                if count >= 2 { return success() }
                say("1 notch. Keep twisting…", .white)
                liftHint = "That was 1 notch. Try: twist a bit more, 2 notches."
                return
            }
        }
        wrong = true
        say("That was \(name). Try: \(l.hint).", .white)
    }

    /// The touch ended without a result: say what was missing.
    private func touchEnded(_ t: Int64) {
        fingerDown = false
        demo?.fingerUp()
        if practice || stale || advancing || wrong || index >= lessons.count { return }
        let l = lessons[index]
        if let liftHint {
            say(liftHint, .white)
            return
        }
        if acted { return }
        let menuLesson = l.goal == .lift || l.goal == .slide || l.goal == .knob
        let s: String
        if menuActive {
            s = l.goal == .slide ? "Slide a bit further before you lift."
                : l.goal == .knob ? "Twist your wrist while you hold." : "Nothing picked. Try: \(l.hint)."
        } else if menuLesson {
            s = "No thud yet. Keep the thumb still a bit longer."
        } else if l.goal == .tap {
            s = t - downAt > config.tapMaxMs ? "A bit quicker: touch and lift at once." : "Lighter: do not move while you tap."
        } else if l.goal == .back {
            s = "Start right at the edge and swipe inward."
        } else {
            s = "Nothing yet. Try: \(l.hint), until the tick."
        }
        say(s, .white)
    }

    // MARK: - engine

    private func twoTapBack() -> Bool {
        Tuning.app(Tuning.twoFinger) == 1 && Tuning.app(Tuning.twoTap) == 1
    }

    func onAction(_ action: GestureEngine.Action, repeat isRepeat: Bool) {
        var a = action
        let twoFinger = !menuActive && Self.slot(a) >= 0 || a == .REWIND || a == .FAST_FORWARD
        if twoFinger && Tuning.app(Tuning.twoFinger) == 0 { return }
        if a == .PLAY_PAUSE && !menuActive && twoTapBack() { a = .BACK }   // like the remote
        showFeedback(a)
        if menuActive && Self.slot(a) >= 0 {
            let s = Self.slot(a)
            let assigned = Tuning.menuAction(s)
            // like the remote: slide down repeats for the input list and volume only, other choices fire once
            let repeats = assigned == Key.input || assigned == Key.volumeUp || assigned == Key.volumeDown
            if slotFired[s] && !repeats { return }
            slotFired[s] = true
            if assigned != 0 { haptics.play(assigned == Tuning.touchpad ? .menu : RemoteModel.pattern(assigned)) }
        } else {
            haptics.play(Self.pattern(a))
        }
        if stale { return }
        acted = true
        check(a, isRepeat)
    }

    func onCue(_ cue: GestureEngine.Cue) {
        switch cue {
        case .MENU_OPEN:
            haptics.play(.menu)
            menuActive = true
            feedback?.menuOpened()
            if practice || stale || advancing || index >= lessons.count { break }
            let l = lessons[index]
            if l.goal == .lift { say("Now lift.", .white) }
            else if l.goal == .slide { say("Now slide \(Self.words[l.slot]).", .white) }
            else if l.goal == .knob { say("Now twist your wrist.", .white) }
        case .MUTE_ARMING:
            haptics.play(.rise)
        case .ARM_CANCELLED, .REJECTED:
            haptics.stop()
        }
    }

    private func showFeedback(_ a: GestureEngine.Action) {
        guard let f = feedback else { return }
        switch a {
        case .UP: f.step(FeedbackView.UP)
        case .RIGHT, .FAST_FORWARD: f.step(FeedbackView.RIGHT)
        case .DOWN: f.step(FeedbackView.DOWN)
        case .LEFT, .REWIND: f.step(FeedbackView.LEFT)
        case .OK: f.tap()
        case .BACK: f.back(fromRight: edgeFromRight)
        case .KNOB_LEFT: f.knob(-1)
        case .KNOB_RIGHT: f.knob(1)
        default:
            if menuActive { f.menuPicked(Self.slot(a)) } else { f.tap() }   // two-finger gestures
        }
    }

    /// Engine action in the thumb menu -> slot 0..4 = up, right, down, left, lift. -1 = not a menu action.
    private static func slot(_ a: GestureEngine.Action) -> Int {
        switch a {
        case .HOME: return 0
        case .SETTINGS: return 1
        case .INPUT: return 2
        case .MUTE: return 3
        case .PLAY_PAUSE: return 4
        default: return -1
        }
    }

    private func name(_ a: GestureEngine.Action) -> String {
        switch a {
        case .UP: return "Up"
        case .DOWN: return "Down"
        case .LEFT: return "Left"
        case .RIGHT: return "Right"
        case .OK: return "Tap (OK)"
        case .BACK: return "Back"
        case .KNOB_LEFT: return "Knob ‹"
        case .KNOB_RIGHT: return "Knob ›"
        case .REWIND: return "Rewind (2 fingers)"
        case .FAST_FORWARD: return "Forward (2 fingers)"
        default: break
        }
        let s = Self.slot(a)
        if !menuActive {
            let n = a == .PLAY_PAUSE ? "Play/Pause" : a == .HOME ? "Home" : a == .INPUT ? "Input list"
                : a == .SETTINGS ? "Settings" : "Mute"
            return n + " (2 fingers)"
        }
        if Tuning.menuAction(s) == 0 { return s == 4 ? "hold + lift" : "hold + slide " + Self.words[s] }
        return Tuning.menuName(s)
    }

    private static func pattern(_ a: GestureEngine.Action) -> Haptics.Pattern {
        switch a {
        case .UP, .DOWN: return .tickV
        case .OK: return .click
        case .BACK: return .back
        case .HOME: return .home
        case .PLAY_PAUSE: return .play
        case .INPUT: return .input
        case .SETTINGS: return .settings
        case .MUTE: return .mute
        default: return .tickH   // left, right, rewind, forward, knob
        }
    }

    // MARK: - touches

    func surfaceBegan(_ touches: [UITouch], in v: FeedbackView, first: Bool) {
        for (i, t) in touches.enumerated() {
            let p = t.location(in: v)
            if first && i == 0 {
                primaryId = Self.id(t)
                menuActive = false
                edgeFromRight = p.x > v.bounds.width / 2
                fingerDown = true
                stale = advancing
                wrong = false
                acted = false
                count = 0
                liftHint = nil
                downAt = Screen.ms(t.timestamp)
                slotFired = [Bool](repeating: false, count: 5)
                v.touchDown(p)
                demo?.fingerDown()
            }
            engine.down(Self.id(t), Float(p.x / mm), Float(p.y / mm), size: Float(t.majorRadius * 2 / mm),
                        time: Screen.ms(t.timestamp))
        }
        startTicks()
    }

    func surfaceMoved(_ touches: [UITouch], in v: FeedbackView) {
        for t in touches {
            let p = t.location(in: v)
            if Self.id(t) == primaryId { v.touchMove(p) }
            engine.move(Self.id(t), Float(p.x / mm), Float(p.y / mm), size: Float(t.majorRadius * 2 / mm),
                        time: Screen.ms(t.timestamp))
        }
    }

    func surfaceEnded(_ touches: [UITouch], in v: FeedbackView, last: Bool) {
        for t in touches {
            engine.up(Self.id(t), time: Screen.ms(t.timestamp))
            if Self.id(t) == primaryId { v.touchUp() }
        }
        if last, let t = touches.first { touchEnded(Screen.ms(t.timestamp)) }
    }

    func surfaceCancelled(in v: FeedbackView) {
        engine.cancel(time: Screen.now())
        v.touchUp()
        fingerDown = false
        demo?.fingerUp()
    }

    private static func id(_ t: UITouch) -> Int {
        ObjectIdentifier(t).hashValue
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

    // MARK: - gyroscope: the knob, exactly like the remote

    private func rotation(_ y: Double, _ z: Double, _ t: TimeInterval) {
        let last = lastGyro
        lastGyro = t
        if last == 0 || !engine.isActive { return }
        var rate = Tuning.app(Tuning.knobAxis) == 1 ? -z : y
        if Tuning.app(Tuning.knobDir) == 0 { rate = -rate }
        engine.twist(Float(rate * (t - last) * 180 / .pi), time: Screen.now())
    }

    // MARK: - text

    private func say(_ s: String, _ color: Color) {
        status = s
        statusColor = color
    }

    private func big(_ name: String) {
        bigLabel = name
        bigVisible = true
        clearBig?.cancel()
        clearBig = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1500))
            if !Task.isCancelled { self?.bigVisible = false }
        }
    }
}
