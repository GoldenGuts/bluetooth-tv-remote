import UIKit

/// Raw touches from the surface. The owner turns them into engine events.
protocol SurfaceInput: AnyObject {
    /// first: no other finger was down (Android's ACTION_DOWN).
    func surfaceBegan(_ touches: [UITouch], in view: FeedbackView, first: Bool)
    func surfaceMoved(_ touches: [UITouch], in view: FeedbackView)
    /// last: no finger is down any more (Android's ACTION_UP).
    func surfaceEnded(_ touches: [UITouch], in view: FeedbackView, last: Bool)
    func surfaceCancelled(in view: FeedbackView)
}

/**
 The gesture surface. It draws quiet feedback: thin gray lines, one dim accent, short fades.
 Nothing blinks, so it stays out of the way while the eyes are on the TV.
 */
final class FeedbackView: UIView {

    static let UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3

    static let accent = UIColor(red: 138 / 255, green: 180 / 255, blue: 248 / 255, alpha: 1)
    private static let errorColor = UIColor(red: 200 / 255, green: 90 / 255, blue: 80 / 255, alpha: 1)

    weak var input: SurfaceInput?
    /// The size changed: the engine needs the width for the edge strips.
    var onResize: ((CGSize) -> Void)?

    private var menuLabels = ["Home", "Settings", "Input", "Mute"]   // by direction UP..LEFT
    private var centerLabel = "Play/Pause"
    private var quiet = false

    /// Short-lived marks: a step arrow, a tap ripple, an error ring.
    private struct Mark {
        let kind: Int   // 0 arrow, 1 ripple, 2 error, 3 back
        let x: CGFloat, y: CGFloat
        let dir: Int
        let start: Double
        let life: Double
    }

    private var marks: [Mark] = []

    // the current touch
    private var down = false
    private var startX: CGFloat = 0, startY: CGFloat = 0, x: CGFloat = 0, y: CGFloat = 0
    private var downAt: Double = 0
    private var holdPossible = false
    private var holdMs: Double = 400
    private var slopPx: CGFloat = 0

    // thumb menu
    private var menuOpen = false
    private var menuX: CGFloat = 0, menuY: CGFloat = 0
    private var menuPick = -1   // -1 none, 0..3 direction, 4 center
    private var menuClosedAt: Double = 0
    private var knobSteps = 0
    private var knobUsed = false

    private var quarterTurns = 0
    private var link: CADisplayLink?
    private var active = Set<ObjectIdentifier>()

    /// Screenshots: a fixed clock, so nothing fades. Only debug builds set it.
    var frozenAt: Double?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        isOpaque = true
        isMultipleTouchEnabled = true
        contentMode = .redraw
    }

    required init?(coder: NSCoder) {
        fatalError("not used")
    }

    func setHold(menuArmMs: Int64, slopMm: Float, pointsPerMm: CGFloat) {
        holdMs = Double(menuArmMs)
        slopPx = CGFloat(slopMm) * pointsPerMm
    }

    func setMenuLabels(_ byDir: [String], center: String) {
        menuLabels = byDir
        centerLabel = center
    }

    /// Animations off: only the touch point and the menu labels, no arrows or ripples.
    func setQuiet(_ quiet: Bool) {
        self.quiet = quiet
    }

    /// Gravity turn, so arrows point where the step went on the screen.
    func setQuarterTurns(_ turns: Int) {
        quarterTurns = turns
    }

    private var lastSize = CGSize.zero

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastSize {
            lastSize = bounds.size
            onResize?(bounds.size)
        }
    }

    // MARK: - touches

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        let first = active.isEmpty
        for t in touches { active.insert(ObjectIdentifier(t)) }
        input?.surfaceBegan(touches.sorted { $0.timestamp < $1.timestamp }, in: self, first: first)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        input?.surfaceMoved(Array(touches), in: self)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { active.remove(ObjectIdentifier(t)) }
        input?.surfaceEnded(Array(touches), in: self, last: active.isEmpty)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        active.removeAll()
        input?.surfaceCancelled(in: self)
    }

    // MARK: - input from the owner

    func touchDown(_ p: CGPoint) {
        down = true
        startX = p.x
        x = p.x
        startY = p.y
        y = p.y
        downAt = now()
        holdPossible = true
        menuOpen = false
        menuPick = -1
        knobSteps = 0
        knobUsed = false
        animate()
    }

    func touchMove(_ p: CGPoint) {
        x = p.x
        y = p.y
        if hypot(p.x - startX, p.y - startY) > slopPx { holdPossible = false }
    }

    func touchUp() {
        down = false
        holdPossible = false
        if menuOpen {
            menuClosedAt = now()
            menuOpen = false
        }
        animate()
    }

    /// A D-pad or seek step in a logical direction.
    func step(_ dir: Int) {
        holdPossible = false
        marks.append(Mark(kind: 0, x: x, y: y, dir: screenDir(dir), start: now(), life: 380))
        animate()
    }

    func tap() {
        marks.append(Mark(kind: 1, x: x, y: y, dir: 0, start: now(), life: 260))
        animate()
    }

    func back(fromRight: Bool) {
        let mx = fromRight ? bounds.width - 24 : 24
        marks.append(Mark(kind: 3, x: mx, y: bounds.height / 2, dir: fromRight ? Self.LEFT : Self.RIGHT, start: now(), life: 420))
        animate()
    }

    func error() {
        marks.append(Mark(kind: 2, x: x, y: y, dir: 0, start: now(), life: 600))
        animate()
    }

    func menuOpened() {
        menuOpen = true
        holdPossible = false
        menuX = x
        menuY = y
        animate()
    }

    /// dir 0..3 = UP..LEFT (logical), 4 = center (Play/Pause).
    func menuPicked(_ dir: Int) {
        menuPick = dir == 4 ? 4 : screenDir(dir)
        if !down { menuClosedAt = now() }
        animate()
    }

    func knob(_ delta: Int) {
        knobUsed = true
        knobSteps += delta
        animate()
    }

    private func screenDir(_ dir: Int) -> Int {
        (dir + quarterTurns) % 4
    }

    // MARK: - drawing

    private func now() -> Double {
        frozenAt ?? CACurrentMediaTime() * 1000
    }

    private func animate() {
        setNeedsDisplay()
        guard link == nil, frozenAt == nil else { return }
        let l = CADisplayLink(target: self, selector: #selector(frame(_:)))
        l.add(to: .main, forMode: .common)
        link = l
    }

    @objc private func frame(_ l: CADisplayLink) {
        setNeedsDisplay()
    }

    override func removeFromSuperview() {
        link?.invalidate()
        link = nil
        super.removeFromSuperview()
    }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        c.setFillColor(UIColor.black.cgColor)
        c.fill(bounds)
        c.setLineCap(.round)
        c.setLineJoin(.round)
        let now = now()
        var busy = down

        if down {
            // the finger and the start point
            stroke(c, Self.gray(70), 1.5) { $0.addEllipse(in: circle(startX, startY, 16)) }
            c.setFillColor(Self.gray(90).cgColor)
            c.fillEllipse(in: circle(x, y, 5))
            if hypot(x - startX, y - startY) > 16 {
                stroke(c, Self.gray(40), 1.5) {
                    $0.move(to: CGPoint(x: startX, y: startY))
                    $0.addLine(to: CGPoint(x: x, y: y))
                }
            }
            // hold timer: the ring fills until the menu opens
            if holdPossible && !menuOpen {
                let p = min(1, (now - downAt) / holdMs)
                if p > 0.15 {
                    stroke(c, Self.accent.withAlphaComponent(150 / 255), 2.5) {
                        $0.addArc(center: CGPoint(x: startX, y: startY), radius: 16, startAngle: -.pi / 2,
                                  endAngle: -.pi / 2 + 2 * .pi * p, clockwise: false)
                    }
                }
            }
        }

        // thumb menu labels, and a short fade after a pick
        let sinceClose = menuClosedAt > 0 ? now - menuClosedAt : 0
        let menuVisible = menuOpen || (menuPick >= 0 && sinceClose < 450)
        if menuVisible {
            let fade = menuOpen ? 1 : 1 - sinceClose / 450
            drawMenu(c, CGFloat(fade))
            busy = true
        }

        if quiet { marks.removeAll { $0.kind != 2 } }   // keep "not sent"
        marks.removeAll { frozenAt == nil && (now - $0.start) / $0.life >= 1 }
        for m in marks {
            busy = true
            drawMark(c, m, CGFloat(min(0.999, max(0, (now - m.start) / m.life))))
        }

        if !busy && frozenAt == nil {
            link?.invalidate()
            link = nil
        }
    }

    private func drawMenu(_ c: CGContext, _ fade: CGFloat) {
        let r: CGFloat = 72
        stroke(c, Self.gray(45 * fade), 1.5) { $0.addEllipse(in: circle(menuX, menuY, r * 0.55)) }
        for d in 0..<4 {
            let sd = screenDir(d)
            let v = Self.vector(sd)
            let lx = clamp(menuX + v.x * r, 36, bounds.width - 36)
            let ly = clamp(menuY + v.y * r, 24, bounds.height - 12)
            let picked = menuPick == sd
            let dim = menuPick >= 0 && !picked
            let a = (picked ? 255 : dim ? 50 : 150) * fade
            text(menuLabels[d], lx, ly + 5, picked ? 15 : 13, picked ? Self.accent.withAlphaComponent(a / 255) : Self.gray(a))
        }
        // center: Play/Pause, or the knob dial after a twist
        if knobUsed {
            let start = (-90 + CGFloat(knobSteps) * 15 - 20) * .pi / 180
            stroke(c, Self.accent.withAlphaComponent(160 * fade / 255), 2) {
                $0.addArc(center: CGPoint(x: menuX, y: menuY), radius: 26, startAngle: start,
                          endAngle: start + 40 * .pi / 180, clockwise: false)
            }
            text("seek \(knobSteps > 0 ? "+" : "")\(knobSteps)", menuX, menuY + 46, 12, Self.gray(150 * fade))
        } else {
            let picked = menuPick == 4
            let color = picked ? Self.accent.withAlphaComponent(fade) : Self.gray((menuPick >= 0 ? 50 : 130) * fade)
            text("lift: " + centerLabel, menuX, menuY + 46, 12, color)
        }
    }

    private func drawMark(_ c: CGContext, _ m: Mark, _ t: CGFloat) {
        let a = 255 * (1 - t)
        switch m.kind {
        case 0:   // step arrow: a chevron that drifts out and fades
            let v = Self.vector(m.dir)
            let dist = 34 + 14 * t
            chevron(c, m.x + v.x * dist, m.y + v.y * dist, v, 9, Self.accent.withAlphaComponent(a * 0.7 / 255))
        case 1:   // tap ripple
            stroke(c, Self.accent.withAlphaComponent(a * 0.6 / 255), 2) { $0.addEllipse(in: circle(m.x, m.y, 10 + 30 * t)) }
        case 2:   // not sent
            let red = Self.errorColor
            stroke(c, red.withAlphaComponent(a * 0.8 / 255), 2.5) { $0.addEllipse(in: circle(m.x, m.y, 22)) }
            text("not sent", m.x, m.y - 32, 13, red.withAlphaComponent(a / 255))
        default:  // back: a chevron at the edge, pointing back toward it
            let v = Self.vector(m.dir == Self.LEFT ? Self.RIGHT : Self.LEFT)
            chevron(c, m.x, m.y, v, 14, Self.accent.withAlphaComponent(a * 0.7 / 255))
        }
    }

    private func chevron(_ c: CGContext, _ cx: CGFloat, _ cy: CGFloat, _ v: CGPoint, _ size: CGFloat, _ color: UIColor) {
        // tip in direction v, two arms back
        let px = -v.y, py = v.x
        stroke(c, color, 2.5) {
            $0.move(to: CGPoint(x: cx - v.x * size + px * size, y: cy - v.y * size + py * size))
            $0.addLine(to: CGPoint(x: cx, y: cy))
            $0.addLine(to: CGPoint(x: cx - v.x * size - px * size, y: cy - v.y * size - py * size))
        }
    }

    // MARK: - shared drawing helpers (also used by DemoView)

    static func vector(_ dir: Int) -> CGPoint {
        switch dir {
        case UP: return CGPoint(x: 0, y: -1)
        case RIGHT: return CGPoint(x: 1, y: 0)
        case DOWN: return CGPoint(x: 0, y: 1)
        default: return CGPoint(x: -1, y: 0)
        }
    }

    static func gray(_ a: CGFloat) -> UIColor {
        UIColor(red: 220 / 255, green: 220 / 255, blue: 225 / 255, alpha: max(0, min(255, a)) / 255)
    }
}

func circle(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat) -> CGRect {
    CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)
}

func clamp(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat {
    max(lo, min(hi, v))
}

/// Strokes the path that `build` adds, in one color and width.
func stroke(_ c: CGContext, _ color: UIColor, _ width: CGFloat, _ build: (CGContext) -> Void) {
    c.beginPath()
    build(c)
    c.setStrokeColor(color.cgColor)
    c.setLineWidth(width)
    c.strokePath()
}

/// Text centered on x, with its baseline at y (like Android's drawText with center alignment).
func text(_ s: String, _ x: CGFloat, _ y: CGFloat, _ size: CGFloat, _ color: UIColor) {
    let font = UIFont.systemFont(ofSize: size)
    let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
    let w = (s as NSString).size(withAttributes: attrs).width
    (s as NSString).draw(at: CGPoint(x: x - w / 2, y: y - font.ascender), withAttributes: attrs)
}
