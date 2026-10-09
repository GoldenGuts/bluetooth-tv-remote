import Foundation

/**
 Turns touch points (mm, ms) into remote actions. Pure Swift, no timers: time moves only through the
 event times and tick(). Before an event is handled, the time-based rules (holds, repeats) run up to
 the event time with the old positions. A port of the Android GestureEngine: keep the two in step.

 Rotation: movement is mapped to directions in a "logical" frame. The frame is turned CLOCKWISE on the
 screen (x right, y down) by 90 * quarterTurns + biasDeg degrees. So with quarterTurns = 1 a physical
 drag to the right (+x) is UP and a drag down (+y) is RIGHT. With biasDeg = 20 a drag that goes up and
 20 degrees to the right (a thumb arc) is a clean UP.

 Flick (flick on): one swipe = one step. The step comes at flickMm, or at the lift after flickLiftMm. More steps
 come only from holding (repeat) or from a long drag: past flickMoreMm the drag steps like the jog wheel.

 Spring-back: a step against the last step direction needs reverseMm of drag back (at least the step size),
 so the thumb relaxing at the end of a swipe does not step back.

 Thumb menu (one finger): hold still (holdSlopMm) for menuArmMs gives MENU_OPEN. From the point where
 the menu opened: lift within menuSelectMm = PLAY_PAUSE; a flick of menuSelectMm picks a direction (axis
 rule as for the D-pad): up = HOME, left = MUTE, right = SETTINGS (once, rest of touch ignored), down =
 INPUT, then one more INPUT per twoStepMm further down. There is no lift-to-cancel after MENU_OPEN: a lift
 near the point is PLAY_PAUSE; a lift after moving out but before a direction is decided gives nothing.

 Knob: while the menu is open and no slide direction is decided, twist() deltas ratchet (with reverse)
 one KNOB_RIGHT / KNOB_LEFT per knobStepDeg. After the first knob step the menu is in knob mode: slides
 select nothing and the lift gives nothing. After a slide decision, twists are ignored.

 Edge back (edgeBack on): a pointer that starts in the edge strip and, while no other non-edge pointer
 is down, moves inward by edgeBackMm (raw screen x, |dx| >= axisRatio * |dy| from its start) gives BACK
 once. If another finger is down when it moves, or it is a palm, it stays a plain grip pointer.

 Choices where the spec is open:
 - Rejection (palm, third finger) emits only REJECTED, also when the mute arm was active: the caller
   stops any arming vibration on REJECTED. cancel() emits ARM_CANCELLED if the mute arm was active.
 - Edge (grip) pointers are ignored entirely, also for the palm size test, and never count as fingers.
 - A gesture ends when its own finger(s) lift. Other fingers still down (grip) are ignored until they lift.
 - Without reverse steps the ratchet keeps its high-water mark: back and forward again gives no step
   until the finger passes the furthest step. Two-finger INPUT always works like that.
 - Axis tie (|x| == |y|) picks the vertical axis.
 */
final class GestureEngine {

    enum Action: String {
        case UP, DOWN, LEFT, RIGHT, OK, HOME, PLAY_PAUSE, REWIND, FAST_FORWARD, INPUT, SETTINGS, MUTE,
             KNOB_RIGHT, KNOB_LEFT, BACK
    }

    enum Cue: String { case MENU_OPEN, MUTE_ARMING, ARM_CANCELLED, REJECTED }

    protocol Listener: AnyObject {
        func onAction(_ action: Action, repeat: Bool)
        func onCue(_ cue: Cue)
    }

    private static let EPS: Float = 1e-3

    private enum Mode { case idle, one, two, done, rejected }
    private enum Role { case edge, stale, live }
    // Hold state. One finger: none -> menu (open) or off (moved). Two fingers: none -> on (mute arming) or off.
    private enum Arm { case none, on, off, menu }
    private enum Axis { case none, x, y }

    private final class Ptr {
        let id: Int
        var role: Role = .live
        var x: Float, y: Float
        let x0: Float, y0: Float
        var backSide = 0   // edge back candidate: +1 = inward is +x (left edge), -1 = right edge, 0 = none

        init(_ id: Int, _ x: Float, _ y: Float) {
            self.id = id
            self.x = x
            self.y = y
            x0 = x
            y0 = y
        }
    }

    private weak var listener: Listener?
    private var ptrs: [Ptr] = []
    private var pending: GestureConfig
    private var pendingTurns = 0
    private var widthMm: Float = 0
    private var enabled = true

    // Per gesture (set at gesture start)
    private var c: GestureConfig
    private var turns = 0
    private var cos: Float = 1, sin: Float = 0
    private var mode = Mode.idle
    private var p1: Ptr?, p2: Ptr?
    private var t0: Int64 = 0
    private var sx: Float = 0, sy: Float = 0         // logical start: finger (one) or centroid (two)
    private var maxDist: Float = 0
    private var emitted = false
    private var arm = Arm.none
    private var axis = Axis.none
    private var lockSign = 0
    private var ratchet: Float = 0
    private var lastSign = 0                         // direction of the last ratchet step
    private var jog = false                          // flick: the drag passed flickMoreMm
    private var settingsDone = false
    private var qx: Float = 0, qy: Float = 0         // repeat: where the finger settled, and when
    private var qt: Int64 = 0
    private var nextRepeat: Int64 = 0
    private var repeating = false
    private var mox: Float = 0, moy: Float = 0       // menu: logical point where it opened, and the max distance from it
    private var menuMax: Float = 0
    private var knobAcc: Float = 0, knobRatchet: Float = 0   // knob: twist since MENU_OPEN, and the last step position
    private var knobMode = false
    private var soloX: Float = 0, soloY: Float = 0   // two fingers: the finger left after its partner lifted
    private var tapSpoiled = false

    init(_ config: GestureConfig, listener: Listener) {
        pending = config
        c = config
        self.listener = listener
    }

    /// Takes effect at the next touch start.
    func setConfig(_ config: GestureConfig) {
        pending = config
    }

    /// Height is not used (only the side edges reject grip). widthMm <= 0 disables the right edge test.
    func setSurface(widthMm: Float, heightMm: Float) {
        self.widthMm = widthMm
    }

    /// 0..3, the frame turns clockwise by 90 degrees per turn. Takes effect at the next touch start.
    func setQuarterTurns(_ turns: Int) {
        pendingTurns = ((turns % 4) + 4) % 4
    }

    func setEnabled(_ enabled: Bool) {
        if !enabled && self.enabled {
            for p in ptrs {
                if p.role == .live { p.role = .stale }
                p.backSide = 0
            }
            mode = .idle
            p1 = nil
            p2 = nil
        }
        self.enabled = enabled
    }

    var isActive: Bool { !ptrs.isEmpty }

    func down(_ pointerId: Int, _ xMm: Float, _ yMm: Float, size sizeMm: Float, time timeMs: Int64) {
        if let old = find(pointerId) { ptrs.removeAll { $0 === old } }
        let p = Ptr(pointerId, xMm, yMm)
        ptrs.append(p)
        if !enabled {
            p.role = .stale
            return
        }
        if mode == .idle { startConfig() }
        advance(timeMs)
        let left = xMm < c.edgeRejectMm
        if left || (widthMm > 0 && xMm > widthMm - c.edgeRejectMm) {
            p.role = .edge
            if c.edgeBack && mode == .idle && sizeMm <= c.palmMm { p.backSide = left ? 1 : -1 }
            return
        }
        p.role = .live
        switch mode {
        case .idle:
            if sizeMm > c.palmMm { reject() } else { startOne(p, timeMs) }
        case .one:
            if sizeMm > c.palmMm || liveCount() >= 3 {
                reject()
            } else if timeMs - t0 <= c.twoFingerJoinMs && maxDist <= c.tapMaxMm + Self.EPS && !emitted
                        && axis == .none && arm == .none {
                startTwo(p)
            }
            // else: late or after movement = grip, ignored
        case .two:
            if sizeMm > c.palmMm || liveCount() >= 3 { reject() }
        default:
            break // done, rejected: ignored until the gesture ends
        }
    }

    func move(_ pointerId: Int, _ xMm: Float, _ yMm: Float, size sizeMm: Float, time timeMs: Int64) {
        guard let p = find(pointerId) else { return }
        if !enabled || p.role != .live {
            p.x = xMm
            p.y = yMm
            if enabled && p.backSide != 0 { edgeBack(p, sizeMm) }
            return
        }
        advance(timeMs)
        p.x = xMm
        p.y = yMm
        if mode != .one && mode != .two { return }
        if sizeMm > c.palmMm {
            reject()
            return
        }
        if mode == .one && p === p1 { moveOne(timeMs) }
        else if mode == .two && (p === p1 || p === p2) { moveTwo(p) }
    }

    func up(_ pointerId: Int, time timeMs: Int64) {
        guard let p = find(pointerId) else { return }
        ptrs.removeAll { $0 === p }
        if !enabled || p.role != .live { return }
        advance(timeMs)
        switch mode {
        case .one:
            guard p === p1, let p1 else { return }
            if arm == .menu {
                if !knobMode && axis == .none && menuMax < c.menuSelectMm - Self.EPS { action(.PLAY_PAUSE, false) }
            } else if !emitted && axis == .none && arm == .none
                        && timeMs - t0 < c.tapMaxMs && maxDist < c.tapMaxMm {
                action(.OK, false)
            } else if c.flick && !emitted && axis == .none {
                // A short flick that lifts before flickMm: one step if the direction is clear
                let (lx, ly) = toLogical(p1.x, p1.y)
                let dx = lx - sx, dy = ly - sy
                if hypot(Double(dx), Double(dy)) >= Double(c.flickLiftMm - Self.EPS) && clearAxis(dx, dy) {
                    step(lock(dx, dy) > 0 ? 1 : -1, false)
                }
            }
            endGesture()
        case .two:
            if p !== p1 && p !== p2 { return }
            if let a = p1, let b = p2 {
                // First of the two lifts: no hold any more, the other finger only matters for the tap
                if arm == .on { cue(.ARM_CANCELLED) }
                arm = .off
                let other = p === a ? b : a
                (soloX, soloY) = toLogical(other.x, other.y)
            }
            if p === p1 { p1 = nil } else { p2 = nil }
            if p1 == nil && p2 == nil {
                if !emitted && axis == .none && !tapSpoiled && arm != .on
                    && timeMs - t0 < c.twoTapMaxMs && maxDist < c.twoTapMaxMm {
                    action(.PLAY_PAUSE, false)
                }
                endGesture()
            }
        case .done:
            if p === p1 { p1 = nil }
            if p === p2 { p2 = nil }
            if p1 == nil && p2 == nil { endGesture() }
        case .rejected:
            if liveCount() == 0 { endGesture() }
        default:
            break
        }
    }

    /// System cancel: all pointers are gone. Gives ARM_CANCELLED if an arm was active, nothing else.
    func cancel(time timeMs: Int64) {
        if (mode == .one || mode == .two) && enabled && arm == .on { cue(.ARM_CANCELLED) }
        ptrs.removeAll()
        mode = .idle
        p1 = nil
        p2 = nil
    }

    /// Gyroscope rotation delta in degrees, + = turn right. Only used while the thumb menu is open.
    func twist(_ deltaDeg: Float, time timeMs: Int64) {
        if !enabled || mode != .one { return }
        advance(timeMs)
        if mode != .one || arm != .menu || axis != .none || !c.knob { return }
        knobAcc += deltaDeg
        let size = c.knobStepDeg
        while knobAcc - knobRatchet >= size - Self.EPS {
            knobRatchet += size
            knobMode = true
            action(.KNOB_RIGHT, false)
        }
        while knobRatchet - knobAcc >= size - Self.EPS {
            knobRatchet -= size
            knobMode = true
            action(.KNOB_LEFT, false)
        }
    }

    func tick(_ timeMs: Int64) {
        if enabled { advance(timeMs) }
    }

    // MARK: - gesture state

    private func startConfig() {
        c = pending
        turns = pendingTurns
        let b = Double(c.biasDeg) * .pi / 180
        cos = c.biasDeg == 0 ? 1 : Float(Foundation.cos(b))
        sin = c.biasDeg == 0 ? 0 : Float(Foundation.sin(b))
    }

    private func resetGesture() {
        p1 = nil
        p2 = nil
        maxDist = 0
        emitted = false
        arm = .none
        axis = .none
        lockSign = 0
        ratchet = 0
        lastSign = 0
        jog = false
        settingsDone = false
        repeating = false
        tapSpoiled = false
    }

    private func startOne(_ p: Ptr, _ t: Int64) {
        resetGesture()
        mode = .one
        p1 = p
        t0 = t
        (sx, sy) = toLogical(p.x, p.y)
    }

    private func startTwo(_ p: Ptr) {
        mode = .two
        p2 = p
        maxDist = 0
        (sx, sy) = centroid()
    }

    private func reject() {
        cue(.REJECTED)
        mode = .rejected
        p1 = nil
        p2 = nil
    }

    private func endGesture() {
        for q in ptrs where q.role == .live { q.role = .stale }
        mode = .idle
        p1 = nil
        p2 = nil
    }

    /// Time-based rules: Home and Mute holds, auto-repeat.
    private func advance(_ t: Int64) {
        if mode == .one, let p1 {
            if arm == .none && maxDist <= c.holdSlopMm && t - t0 >= c.menuArmMs {
                arm = .menu
                (mox, moy) = toLogical(p1.x, p1.y)
                menuMax = 0
                knobAcc = 0
                knobRatchet = 0
                knobMode = false
                cue(.MENU_OPEN)
            } else if arm != .menu && axis != .none && t - qt >= c.repeatDelayMs {
                repeatStep(t)
            }
        } else if mode == .two && p1 != nil && p2 != nil {
            if arm != .off && !emitted && axis == .none && maxDist <= c.holdSlopMm {
                if arm == .none && t - t0 >= c.muteArmMs {
                    arm = .on
                    cue(.MUTE_ARMING)
                }
                if arm == .on && t - t0 >= c.muteHoldMs {
                    mode = .done
                    action(.MUTE, false)
                }
            }
        }
    }

    private func repeatStep(_ t: Int64) {
        guard let p1 else { return }
        let (lx, ly) = toLogical(p1.x, p1.y)
        let along = axis == .x ? lx - sx : ly - sy
        let first = firstMm()
        let sign = along >= first - Self.EPS ? 1 : along <= -first + Self.EPS ? -1 : 0
        if sign == 0 || (!c.reverseSteps && sign != lockSign) { return }
        if !repeating {
            repeating = true
            nextRepeat = qt + c.repeatDelayMs
        }
        if t >= nextRepeat {
            step(sign, true)
            nextRepeat += c.repeatIntervalMs
            if nextRepeat <= t { nextRepeat = t + c.repeatIntervalMs } // late tick: no burst
        }
    }

    private func moveOne(_ t: Int64) {
        if arm == .menu {
            moveMenu()
            return
        }
        guard let p1 else { return }
        let (cx, cy) = toLogical(p1.x, p1.y)
        let dx = cx - sx, dy = cy - sy
        let d = Float(hypot(Double(dx), Double(dy)))
        trackHold(d)
        if axis == .none {
            if d < firstMm() - Self.EPS { return }
            if !clearAxis(dx, dy) && d < c.axisDecideMaxMm - Self.EPS { return } // near 45 degrees: wait
            let along = lock(dx, dy)
            // Flick: the next step is a long drag away (flickMoreMm from the start)
            ratchet = Float(lockSign) * (c.flick ? c.flickMoreMm - c.stepMm : min(abs(along), c.firstStepMm))
            step(lockSign, false)
            drag(along)
            settle(cx, cy, t)
        } else {
            drag(axis == .x ? dx : dy)
            if hypot(Double(cx - qx), Double(cy - qy)) > Double(c.holdStillMm) { settle(cx, cy, t) }
        }
    }

    private func firstMm() -> Float {
        c.flick ? c.flickMm : c.firstStepMm
    }

    /// One-finger D-pad after the first step. Flick: nothing (not even back) until the drag passes flickMoreMm.
    private func drag(_ along: Float) {
        if c.flick && !jog {
            if Float(lockSign) * along < c.flickMoreMm - Self.EPS { return }
            jog = true
        }
        runRatchet(along, c.stepMm, c.reverseSteps)
    }

    private func moveMenu() {
        guard let p1 else { return }
        let (lx, ly) = toLogical(p1.x, p1.y)
        let dx = lx - mox, dy = ly - moy
        let d = Float(hypot(Double(dx), Double(dy)))
        if d > menuMax { menuMax = d }
        if knobMode { return } // slides select nothing after a knob step
        if axis != .none {
            runRatchet(dy, c.twoStepMm, false) // only the INPUT branch stays in the menu
            return
        }
        if d < c.menuSelectMm - Self.EPS || (!clearAxis(dx, dy) && d < c.axisDecideMaxMm - Self.EPS) { return }
        let along = lock(dx, dy)
        if axis == .y && lockSign > 0 {
            ratchet = min(along, c.menuSelectMm)
            step(1, false)
            runRatchet(along, c.twoStepMm, false)
            return
        }
        mode = .done
        action(axis == .y ? .HOME : lockSign < 0 ? .MUTE : .SETTINGS, false)
    }

    private func edgeBack(_ p: Ptr, _ sizeMm: Float) {
        if sizeMm > c.palmMm || ptrs.count > edgeCount() {
            p.backSide = 0 // palm, or another finger is down: plain grip from now on
            return
        }
        let inward = Float(p.backSide) * (p.x - p.x0), dy = abs(p.y - p.y0)
        if inward >= c.edgeBackMm - Self.EPS && inward >= c.axisRatio * dy - Self.EPS {
            p.backSide = 0
            listener?.onAction(.BACK, repeat: false)
        }
    }

    private func edgeCount() -> Int {
        ptrs.filter { $0.role == .edge }.count
    }

    private func clearAxis(_ dx: Float, _ dy: Float) -> Bool {
        let ax = abs(dx), ay = abs(dy)
        let mx = max(ax, ay), mn = min(ax, ay)
        return mn <= 0 || mx / mn >= c.axisRatio - Self.EPS
    }

    private func moveTwo(_ moved: Ptr) {
        guard p1 != nil, p2 != nil else {
            // Partner gone: no new actions, but a moving finger spoils the tap
            let (lx, ly) = toLogical(moved.x, moved.y)
            if hypot(Double(lx - soloX), Double(ly - soloY)) >= Double(c.twoTapMaxMm) { tapSpoiled = true }
            return
        }
        let (lx, ly) = centroid()
        let dx = lx - sx, dy = ly - sy
        let d = Float(hypot(Double(dx), Double(dy)))
        trackHold(d)
        if axis == .none {
            if d < c.twoStepMm - Self.EPS { return }
            let along = lock(dx, dy)
            if axis == .y && lockSign > 0 {
                settingsDone = true
                step(1, false)
                return
            }
            ratchet = Float(lockSign) * min(abs(along), c.twoStepMm)
            step(lockSign, false)
            runRatchet(along, c.twoStepMm, axis == .x && c.reverseSteps)
        } else if !settingsDone {
            runRatchet(axis == .x ? dx : dy, c.twoStepMm, axis == .x && c.reverseSteps)
        }
    }

    private func trackHold(_ d: Float) {
        if d > maxDist { maxDist = d }
        if maxDist > c.holdSlopMm && (arm == .none || arm == .on) {
            if arm == .on { cue(.ARM_CANCELLED) }
            arm = .off
        }
    }

    /// Locks the dominant axis and returns the offset along it.
    private func lock(_ dx: Float, _ dy: Float) -> Float {
        axis = abs(dx) > abs(dy) ? .x : .y
        let along = axis == .x ? dx : dy
        lockSign = along > 0 ? 1 : -1
        lastSign = lockSign
        return along
    }

    /// Jog wheel: one step per `size` away from the last step. Without reverse, only the lock direction.
    /// A step against the last step direction needs max(size, reverseMm).
    private func runRatchet(_ along: Float, _ size: Float, _ reverse: Bool) {
        let back = max(size, c.reverseMm)
        while true {
            let fwd = lastSign < 0 ? back : size, bwd = lastSign > 0 ? back : size
            if (reverse || lockSign > 0) && along - ratchet >= fwd - Self.EPS {
                ratchet += fwd
                lastSign = 1
                step(1, false)
            } else if (reverse || lockSign < 0) && ratchet - along >= bwd - Self.EPS {
                ratchet -= bwd
                lastSign = -1
                step(-1, false)
            } else {
                break
            }
        }
    }

    private func settle(_ x: Float, _ y: Float, _ t: Int64) {
        qx = x
        qy = y
        qt = t
        repeating = false
    }

    private func step(_ sign: Int, _ isRepeat: Bool) {
        let a: Action
        if arm == .menu {
            a = .INPUT
        } else if mode == .two {
            if axis == .x { a = sign > 0 ? .FAST_FORWARD : .REWIND }
            else { a = sign > 0 ? .SETTINGS : .INPUT }
        } else if axis == .x {
            a = sign > 0 ? .RIGHT : .LEFT
        } else {
            a = sign > 0 ? .DOWN : .UP
        }
        action(a, isRepeat)
    }

    private func action(_ a: Action, _ isRepeat: Bool) {
        emitted = true
        listener?.onAction(a, repeat: isRepeat)
    }

    private func cue(_ q: Cue) {
        listener?.onCue(q)
    }

    // MARK: - geometry

    /// Screen mm -> logical frame (turned clockwise by 90 * turns + biasDeg).
    private func toLogical(_ x: Float, _ y: Float) -> (Float, Float) {
        let ax: Float, ay: Float
        switch turns {
        case 1: (ax, ay) = (y, -x)
        case 2: (ax, ay) = (-x, -y)
        case 3: (ax, ay) = (-y, x)
        default: (ax, ay) = (x, y)
        }
        return (ax * cos + ay * sin, -ax * sin + ay * cos)
    }

    private func centroid() -> (Float, Float) {
        guard let p1, let p2 else { return (sx, sy) }
        return toLogical((p1.x + p2.x) * 0.5, (p1.y + p2.y) * 0.5)
    }

    private func find(_ id: Int) -> Ptr? {
        ptrs.first { $0.id == id }
    }

    private func liveCount() -> Int {
        ptrs.filter { $0.role == .live }.count
    }
}
