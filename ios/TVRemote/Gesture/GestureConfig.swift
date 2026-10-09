/// Gesture thresholds. Distances in mm, times in ms. A value type: the engine keeps its own copy.
struct GestureConfig: Equatable {
    // One finger: tap and D-pad drag
    var tapMaxMm: Float = 3
    var tapMaxMs: Int64 = 250
    var firstStepMm: Float = 12
    var stepMm: Float = 8
    var axisRatio: Float = 1.3
    var axisDecideMaxMm: Float = 18
    var reverseSteps = true
    // Auto-repeat while held away from the start
    var repeatDelayMs: Int64 = 400
    var repeatIntervalMs: Int64 = 150
    var holdStillMm: Float = 2
    // Flick: one swipe = one step at flickMm (or a lift after flickLiftMm). A long drag adds steps from flickMoreMm
    var flick = false
    var flickMm: Float = 8
    var flickLiftMm: Float = 5
    var flickMoreMm: Float = 30
    /// A step against the last step needs this much drag back (below the step size: the step size). Stops the thumb spring-back.
    var reverseMm: Float = 0
    // Thumb menu: hold still, then lift or flick
    var menuArmMs: Int64 = 400
    var menuSelectMm: Float = 10
    var holdSlopMm: Float = 4
    // Knob: wrist twist while the thumb menu is open
    var knob = true
    var knobStepDeg: Float = 15
    // Two fingers
    var twoFingerJoinMs: Int64 = 150
    var twoTapMaxMs: Int64 = 300
    var twoTapMaxMm: Float = 4
    var twoStepMm: Float = 15
    var muteArmMs: Int64 = 350
    var muteHoldMs: Int64 = 700
    // Rejection
    var edgeRejectMm: Float = 3
    var palmMm: Float = 14
    // In-app Back: swipe inward from the side edge strip
    var edgeBack = false
    var edgeBackMm: Float = 10
    /// Thumb-arc bias, see GestureEngine for the rotation direction.
    var biasDeg: Float = 0

    enum Ref {
        case float(WritableKeyPath<GestureConfig, Float>)
        case long(WritableKeyPath<GestureConfig, Int64>)
        case bool(WritableKeyPath<GestureConfig, Bool>)
    }

    /// Every adjustable field by name (the saved key is "g.<name>"). edgeBack is left out: a setting of its own sets it.
    static let fields: [(name: String, ref: Ref)] = [
        ("tapMaxMm", .float(\.tapMaxMm)), ("tapMaxMs", .long(\.tapMaxMs)),
        ("firstStepMm", .float(\.firstStepMm)), ("stepMm", .float(\.stepMm)),
        ("axisRatio", .float(\.axisRatio)), ("axisDecideMaxMm", .float(\.axisDecideMaxMm)),
        ("reverseSteps", .bool(\.reverseSteps)),
        ("repeatDelayMs", .long(\.repeatDelayMs)), ("repeatIntervalMs", .long(\.repeatIntervalMs)),
        ("holdStillMm", .float(\.holdStillMm)),
        ("flick", .bool(\.flick)), ("flickMm", .float(\.flickMm)), ("flickLiftMm", .float(\.flickLiftMm)),
        ("flickMoreMm", .float(\.flickMoreMm)), ("reverseMm", .float(\.reverseMm)),
        ("menuArmMs", .long(\.menuArmMs)), ("menuSelectMm", .float(\.menuSelectMm)), ("holdSlopMm", .float(\.holdSlopMm)),
        ("knob", .bool(\.knob)), ("knobStepDeg", .float(\.knobStepDeg)),
        ("twoFingerJoinMs", .long(\.twoFingerJoinMs)), ("twoTapMaxMs", .long(\.twoTapMaxMs)),
        ("twoTapMaxMm", .float(\.twoTapMaxMm)), ("twoStepMm", .float(\.twoStepMm)),
        ("muteArmMs", .long(\.muteArmMs)), ("muteHoldMs", .long(\.muteHoldMs)),
        ("edgeRejectMm", .float(\.edgeRejectMm)), ("palmMm", .float(\.palmMm)),
        ("edgeBackMm", .float(\.edgeBackMm)), ("biasDeg", .float(\.biasDeg)),
    ]

    /// A field as a number (booleans: 1 or 0).
    func value(_ name: String) -> Float {
        guard let ref = Self.fields.first(where: { $0.name == name })?.ref else { return 0 }
        switch ref {
        case .float(let k): return self[keyPath: k]
        case .long(let k): return Float(self[keyPath: k])
        case .bool(let k): return self[keyPath: k] ? 1 : 0
        }
    }

    mutating func set(_ name: String, _ value: Float) {
        guard let ref = Self.fields.first(where: { $0.name == name })?.ref else { return }
        switch ref {
        case .float(let k): self[keyPath: k] = value
        case .long(let k): self[keyPath: k] = Int64(value.rounded())
        case .bool(let k): self[keyPath: k] = value != 0
        }
    }
}
