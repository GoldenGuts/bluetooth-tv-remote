import UIKit

/**
 The "ghost thumb": a quiet loop that shows the gesture of the current lesson. It takes no touches.
 It hides while a finger is down and comes back 1.5 s after the lift. Distances are real mm.
 */
final class DemoView: UIView {

    enum Kind { case none, tap, drag, repeatHold, edge, lift, slide, twist }

    private static let pauseMs: Double = 600
    private static let open: Double = 1050   // the hold ring is full, the "thud"

    private let config: GestureConfig
    private let mm: CGFloat

    private var kind = Kind.none, dir = 0, steps = 1
    private var label = ""
    private var loopStart: Double = 0, lastFrame: Double = 0
    private var fade: CGFloat = 0   // the whole demo, 0..1
    private var wanted = false, fingerIsDown = false
    private var link: CADisplayLink?
    private var reveal: Task<Void, Never>?
    /// Screenshots: the loop stays at this time (ms). Only debug builds set it.
    var frozenT: Double?

    init(config: GestureConfig, pointsPerMm: CGFloat) {
        self.config = config
        mm = pointsPerMm
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isOpaque = false
        backgroundColor = .clear
        contentMode = .redraw
        isAccessibilityElement = false
    }

    required init?(coder: NSCoder) {
        fatalError("not used")
    }

    /// dir: FeedbackView.UP..LEFT. steps: for drag. label: the menu name for lift and slide.
    func play(_ kind: Kind, dir: Int = 0, steps: Int = 1, label: String? = nil) {
        self.kind = kind
        self.dir = dir
        self.steps = max(1, steps)
        self.label = label ?? ""
        loopStart = Self.now()
        wanted = kind != .none && !fingerIsDown
        kick()
    }

    func fingerDown() {
        fingerIsDown = true
        reveal?.cancel()
        wanted = false
        kick()
    }

    func fingerUp() {
        fingerIsDown = false
        reveal?.cancel()
        reveal = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1500))
            guard let self, !Task.isCancelled, !self.fingerIsDown, self.kind != .none else { return }
            if self.fade == 0 { self.loopStart = Self.now() }
            self.wanted = true
            self.kick()
        }
    }

    func stopAll() {
        reveal?.cancel()
        link?.invalidate()
        link = nil
    }

    private func kick() {
        guard link == nil else { return }
        lastFrame = Self.now()
        let l = CADisplayLink(target: self, selector: #selector(frame(_:)))
        l.add(to: .main, forMode: .common)
        link = l
    }

    @objc private func frame(_ l: CADisplayLink) {
        setNeedsDisplay()
    }

    private static func now() -> Double {
        CACurrentMediaTime() * 1000
    }

    private func duration() -> Double {
        switch kind {
        case .tap: return 1800
        case .drag: return 1500 + 400 * Double(steps)
        case .edge: return 1900
        case .lift: return 2500
        case .twist: return 2900
        default: return 2700   // repeat, slide
        }
    }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        c.setLineCap(.round)
        c.setLineJoin(.round)
        let now = Self.now()
        let dt = min(50, now - lastFrame)
        lastFrame = now
        fade = wanted ? min(1, fade + CGFloat(dt / 300)) : max(0, fade - CGFloat(dt / 150))
        if fade > 0 && kind != .none {
            let d = duration()
            let t = frozenT ?? (now - loopStart).truncatingRemainder(dividingBy: d + Self.pauseMs)
            if t < d { draw(c, t, d) }
        }
        if fade <= 0 && !wanted {
            link?.invalidate()
            link = nil
        }
    }

    private func draw(_ c: CGContext, _ t: Double, _ d: Double) {
        let w = bounds.width, h = bounds.height, cx = w / 2, cy = h / 2
        let a = fade * seg(t, 0, 300) * (1 - seg(t, d - 400, d - 100))
        let v = FeedbackView.vector(dir)
        switch kind {
        case .tap:
            let press = seg(t, 600, 720) - seg(t, 800, 950)
            thumb(c, cx, cy, 1 - 0.15 * press, a)
            let r = seg(t, 800, 1350)
            if r > 0 && r < 1 {
                stroke(c, accent(fade * 110 * (1 - r)), 2) { $0.addEllipse(in: circle(cx, cy, 20 + 30 * r)) }
            }
        case .drag:
            let length = CGFloat(config.firstStepMm + Float(steps - 1) * config.stepMm)
            let k = shrink(v, length, w, h)
            let total = length * mm * k
            let sx = cx - v.x * total / 2, sy = cy - v.y * total / 2
            let f = ease(seg(t, 400, 600 + 400 * Double(steps)))
            let x = sx + v.x * total * f, y = sy + v.y * total * f
            trail(c, sx, sy, x, y, a)
            for i in 0..<steps {
                let at = CGFloat(config.firstStepMm + Float(i) * config.stepMm) * mm * k
                if total * f < at - 0.5 { break }
                tick(c, sx + v.x * at, sy + v.y * at, v, a)
                if steps > 1 {
                    text(String(i + 1), sx + v.x * at - v.y * 22, sy + v.y * at + v.x * 22 + 4, 11, FeedbackView.gray(a * 120))
                }
            }
            thumb(c, x, y, 1, a)
        case .repeatHold:
            let length = CGFloat(config.firstStepMm + 3)
            let k = shrink(v, length, w, h)
            let total = length * mm * k
            let sx = cx - v.x * total / 2, sy = cy - v.y * total / 2
            let f = ease(seg(t, 400, 900))
            let x = sx + v.x * total * f, y = sy + v.y * total * f
            trail(c, sx, sy, x, y, a)
            let first = CGFloat(config.firstStepMm) * mm * k
            if total * f >= first - 0.5 { tick(c, sx + v.x * first, sy + v.y * first, v, a) }
            // at rest: one drifting chevron per repeat
            let interval = Double(max(180, config.repeatIntervalMs))
            var tk = 900 + Double(config.repeatDelayMs)
            while tk <= t && tk < d - 500 {
                let p = CGFloat((t - tk) / 400)
                if p < 1 {
                    let dist = 28 + 16 * p
                    chevron(c, x + v.x * dist, y + v.y * dist, v, 7, accent(a * 110 * (1 - p)))
                }
                tk += interval
            }
            thumb(c, x, y, 1, a)
        case .edge:
            let travel = min(22 * mm, w * 0.5)
            let s = seg(t, 300, 1000)
            let f = 1 - (1 - s) * (1 - s)
            let x = w - 4 - travel * f
            stroke(c, FeedbackView.gray(a * 40), 3) {
                $0.move(to: CGPoint(x: w - 1.5, y: cy - 40))
                $0.addLine(to: CGPoint(x: w - 1.5, y: cy + 40))
            }
            let shown = seg(t, 650, 850) * (1 - seg(t, 1300, 1600))
            chevron(c, w - travel - 30, cy, FeedbackView.vector(FeedbackView.LEFT), 10, accent(a * 120 * shown))
            thumb(c, x, cy, 1, a)
        case .lift:
            let press = seg(t, 300, 420) - seg(t, 1700, 1850)
            let lift = seg(t, 1700, 2100)
            thumb(c, cx, cy, 1 - 0.12 * press + 0.1 * lift, a * (1 - lift))
            ring(c, cx, cy, t, a * (1 - lift))
            if t >= Self.open {
                let picked = t >= 1700
                text("lift: " + label, cx, cy + 52, 13,
                     picked ? accent(fade * 200 * (1 - seg(t, 2000, 2400))) : FeedbackView.gray(a * 110))
            }
        case .slide:
            let dist = CGFloat(config.menuSelectMm) * 1.4 * mm
            let f = ease(seg(t, 1250, 1650))
            let x = cx + v.x * dist * f, y = cy + v.y * dist * f
            trail(c, cx, cy, x, y, a)
            thumb(c, x, y, 1 - 0.12 * seg(t, 300, 420), a)
            ring(c, cx, cy, t, a * (1 - seg(t, 1250, 1450)))
            if t >= Self.open {
                let lx = clamp(cx + v.x * 72, 40, w - 40)
                let ly = clamp(cy + v.y * 72, 24, h - 12)
                let picked = f >= 1
                text(label, lx, ly + 5, picked ? 15 : 13,
                     picked ? accent(a * 220) : FeedbackView.gray(a * 100 * seg(t, Self.open, Self.open + 200)))
            }
        case .twist:
            thumb(c, cx, cy, 1 - 0.12 * seg(t, 300, 420), a)
            ring(c, cx, cy, t, a * (1 - seg(t, 1200, 1400)))
            if t < Self.open { break }
            let na = a * seg(t, Self.open, Self.open + 200)
            let ang = 45 * ease(seg(t, 1300, 2200))   // three notches of 15°
            for k in -2...5 {
                let r = (-90 + CGFloat(k) * 15) * .pi / 180
                let passed = k >= 1 && CGFloat(k) * 15 <= ang + 0.5
                stroke(c, passed ? accent(na * 150) : FeedbackView.gray(na * 50), 2) {
                    $0.move(to: CGPoint(x: cx + cos(r) * 37, y: cy + sin(r) * 37))
                    $0.addLine(to: CGPoint(x: cx + cos(r) * 43, y: cy + sin(r) * 43))
                }
            }
            let start = (-90 + ang - 20) * .pi / 180
            stroke(c, accent(na * 140), 2.5) {
                $0.addArc(center: CGPoint(x: cx, y: cy), radius: 30, startAngle: start,
                          endAngle: start + 40 * .pi / 180, clockwise: false)
            }
            let pr = (-90 + ang) * .pi / 180
            stroke(c, FeedbackView.gray(na * 110), 2) {
                $0.move(to: CGPoint(x: cx, y: cy))
                $0.addLine(to: CGPoint(x: cx + cos(pr) * 12, y: cy + sin(pr) * 12))
            }
        case .none:
            break
        }
    }

    // MARK: - pieces

    private func thumb(_ c: CGContext, _ x: CGFloat, _ y: CGFloat, _ scale: CGFloat, _ a: CGFloat) {
        c.setFillColor(FeedbackView.gray(a * 40).cgColor)
        c.fillEllipse(in: circle(x, y, 18 * scale))
        stroke(c, FeedbackView.gray(a * 80), 1.5) { $0.addEllipse(in: circle(x, y, 18 * scale)) }
    }

    /// The hold ring fills, then a soft bump at the "thud".
    private func ring(_ c: CGContext, _ x: CGFloat, _ y: CGFloat, _ t: Double, _ a: CGFloat) {
        let p = seg(t, 420, Self.open)
        if p <= 0 || a <= 0 { return }
        let bump = sin(.pi * seg(t, Self.open, Self.open + 220))
        stroke(c, accent(a * 130), 2 + 1.5 * bump) {
            $0.addArc(center: CGPoint(x: x, y: y), radius: 26 + 3 * bump, startAngle: -.pi / 2,
                      endAngle: -.pi / 2 + 2 * .pi * p, clockwise: false)
        }
    }

    private func trail(_ c: CGContext, _ sx: CGFloat, _ sy: CGFloat, _ x: CGFloat, _ y: CGFloat, _ a: CGFloat) {
        if hypot(x - sx, y - sy) < 1 { return }
        stroke(c, FeedbackView.gray(a * 35), 1.5) {
            $0.move(to: CGPoint(x: sx, y: sy))
            $0.addLine(to: CGPoint(x: x, y: y))
        }
    }

    /// A short mark across the path: one step.
    private func tick(_ c: CGContext, _ x: CGFloat, _ y: CGFloat, _ v: CGPoint, _ a: CGFloat) {
        let px = -v.y * 9, py = v.x * 9
        stroke(c, accent(a * 120), 2) {
            $0.move(to: CGPoint(x: x - px, y: y - py))
            $0.addLine(to: CGPoint(x: x + px, y: y + py))
        }
    }

    private func chevron(_ c: CGContext, _ cx: CGFloat, _ cy: CGFloat, _ v: CGPoint, _ size: CGFloat, _ color: UIColor) {
        let px = -v.y, py = v.x
        stroke(c, color, 2.5) {
            $0.move(to: CGPoint(x: cx - v.x * size + px * size, y: cy - v.y * size + py * size))
            $0.addLine(to: CGPoint(x: cx, y: cy))
            $0.addLine(to: CGPoint(x: cx - v.x * size - px * size, y: cy - v.y * size - py * size))
        }
    }

    /// Small screens: scale a drag down so it fits in 70% of the view.
    private func shrink(_ v: CGPoint, _ lengthMm: CGFloat, _ w: CGFloat, _ h: CGFloat) -> CGFloat {
        let span = (v.x != 0 ? w : h) * 0.7
        return min(1, span / (lengthMm * mm))
    }

    private func seg(_ t: Double, _ from: Double, _ to: Double) -> CGFloat {
        CGFloat(max(0, min(1, (t - from) / (to - from))))
    }

    private func ease(_ f: CGFloat) -> CGFloat {
        f * f * (3 - 2 * f)
    }

    private func accent(_ a: CGFloat) -> UIColor {
        FeedbackView.accent.withAlphaComponent(max(0, min(255, a)) / 255)
    }
}
