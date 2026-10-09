import Foundation

/**
 Adjustable thresholds and app settings in UserDefaults. Every GestureConfig field is a setting (key "g.<field>"),
 plus the app settings below. Same keys and defaults as the Android app, without the TV (Bluetooth) settings.
 */
enum Tuning {

    static let hapticStrength = "hapticStrength"   // percent
    static let brightness = "brightness"           // percent
    static let keepOnMin = "keepOnMinutes"
    static let gravity = "gravityTurn"
    static let showLabels = "showLabels"
    static let knobAxis = "knobAxis"               // 0 = doorknob (long axis), 1 = steering wheel
    static let knobKeys = "knobKeys"               // 0 = D-pad left/right, 1 = rewind/fast forward, 2 = volume
    static let edgeBack = "edgeBack"               // 0 = off, 1 = on
    static let knobDir = "knobDirection"           // 1 = normal, 0 = reversed
    static let menuOn = "menuOn"
    static let repeatOn = "repeatOn"
    static let twoFinger = "twoFinger"
    static let twoTap = "twoTap"                   // two-finger tap: 0 = Play/Pause, 1 = Back
    static let animations = "animations"
    static let hints = "hints"
    static let statusLine = "statusLine"
    static let volumeTo = "volumeTo"               // with the box as target: 0 = TV, 1 = box, 2 = TV, else box
    static let pocket = "pocketGuard"
    static let hapticsOn = "hapticsOn"
    /// menuSlot0..4 = up, right, down, left, lift: a Key action, 0 = nothing.
    static let menuSlot = "menuSlot"
    /// The IP address of the box.
    static let boxHost = "boxHost"
    /// The IP address of the LG TV.
    static let tvHost = "tvHost"
    /// With a TV and a box: 0 = keys go to the TV, 1 = to the box.
    static let target = "target"
    /// A thumb-menu choice that is not a key: switch between the TV and the box.
    static let switchTarget = 100
    /// A thumb-menu choice: touchpad mode on (a mouse pointer on Kodi or the LG TV).
    static let touchpad = 101

    /// App defaults that differ from the engine defaults (the engine tests keep theirs).
    static let menuArmMs: Int64 = 600
    static let reverseMm: Float = 14
    static let edgeMm: Float = 5

    /// Choices for a thumb-menu slot, with names. Volume is here because an app cannot take the volume buttons.
    static let menuChoices = [0, Key.home, Key.back, Key.input, Key.settings, Key.mute, Key.playPause, Key.ok,
                              Key.volumeUp, Key.volumeDown, switchTarget, touchpad]
    static let menuChoiceNames = ["Nothing", "Home", "Back", "Input list", "Settings", "Mute", "Play/Pause", "OK",
                                  "Volume up", "Volume down", "TV ↔ box", "Touchpad"]
    private static let menuDefaults = [Key.home, Key.settings, Key.input, Key.mute, Key.playPause]

    static var defaults: UserDefaults { .standard }

    /// The saved gesture values over the engine defaults.
    static func load() -> GestureConfig {
        var c = GestureConfig()
        for (name, ref) in GestureConfig.fields {
            let key = "g." + name
            guard let v = defaults.object(forKey: key) as? NSNumber else { continue }
            switch ref {
            case .float(let k): c[keyPath: k] = v.floatValue
            case .long(let k): c[keyPath: k] = v.int64Value
            case .bool(let k): c[keyPath: k] = v.boolValue
            }
        }
        return c
    }

    /// The gesture config the app uses: saved values, app defaults, and the on/off switches.
    static func gestureConfig() -> GestureConfig {
        var c = load()
        appDefaults(&c, saved: true)
        if app(menuOn) == 0 { c.menuArmMs = 1_000_000_000 }      // never opens
        if app(repeatOn) == 0 { c.repeatDelayMs = 1_000_000_000 }
        return c
    }

    /// App defaults for the fields not saved (saved false: all of them).
    static func appDefaults(_ c: inout GestureConfig, saved: Bool) {
        func unset(_ field: String) -> Bool { !saved || defaults.object(forKey: "g." + field) == nil }
        if unset("menuArmMs") { c.menuArmMs = menuArmMs }
        if unset("flick") { c.flick = true }
        if unset("reverseMm") { c.reverseMm = reverseMm }
        if unset("edgeRejectMm") { c.edgeRejectMm = edgeMm }
    }

    static func appDefaultConfig() -> GestureConfig {
        var c = GestureConfig()
        appDefaults(&c, saved: false)
        return c
    }

    /// A GestureConfig field as a number (booleans: 1 or 0), with the app defaults, without the on/off switches.
    static func field(_ name: String) -> Float {
        var c = load()
        appDefaults(&c, saved: true)
        return c.value(name)
    }

    static func setField(_ name: String, _ value: Float) {
        guard let ref = GestureConfig.fields.first(where: { $0.name == name })?.ref else { return }
        let key = "g." + name
        switch ref {
        case .float: defaults.set(value, forKey: key)
        case .long: defaults.set(Int64(value.rounded()), forKey: key)
        case .bool: defaults.set(value != 0, forKey: key)
        }
    }

    static func resetGestures() {
        for (name, _) in GestureConfig.fields { defaults.removeObject(forKey: "g." + name) }
    }

    static func menuAction(_ slot: Int) -> Int {
        let key = menuSlot + String(slot)
        return defaults.object(forKey: key) != nil ? defaults.integer(forKey: key) : menuDefaults[slot]
    }

    static func setMenuAction(_ slot: Int, _ action: Int) {
        defaults.set(action, forKey: menuSlot + String(slot))
    }

    static func menuName(_ slot: Int) -> String {
        actionName(menuAction(slot))
    }

    static func actionName(_ action: Int) -> String {
        if let i = menuChoices.firstIndex(of: action) { return menuChoiceNames[i] }
        return Key.name(action)
    }

    static func put(_ setting: String, _ value: Int) {
        defaults.set(value, forKey: setting)
    }

    static func app(_ setting: String) -> Int {
        if defaults.object(forKey: setting) != nil { return defaults.integer(forKey: setting) }
        switch setting {
        case hapticStrength: return 100
        case brightness: return 15
        case keepOnMin: return 5
        case gravity: return 0   // off: lying on a couch, gravity is not the thumb's "up"
        case knobAxis, knobKeys: return 0
        case edgeBack, twoTap, knobDir, target: return 1
        case volumeTo: return 2
        case showLabels, menuOn, repeatOn, twoFinger, animations, hints, statusLine, pocket, hapticsOn: return 1
        default: return 0
        }
    }

    static var host: String {
        get { defaults.string(forKey: boxHost) ?? "" }
        set { defaults.set(newValue, forKey: boxHost) }
    }

    static var tv: String {
        get { defaults.string(forKey: tvHost) ?? "" }
        set { defaults.set(newValue, forKey: tvHost) }
    }

    /// All settings back to the defaults. The box address, its pairing and the first-run flags stay.
    static func resetAll() {
        let keep: Set<String> = [boxHost, tvHost, target, "tutorialSeen", "box.pin", "box.version", "tv.key", "tv.mac",
                                 "tv.secure"]
        for key in defaults.dictionaryRepresentation().keys where !keep.contains(key) {
            if key.hasPrefix("g.") || key.hasPrefix(menuSlot) || appKeys.contains(key) { defaults.removeObject(forKey: key) }
        }
    }

    private static let appKeys: Set<String> = [hapticStrength, brightness, keepOnMin, gravity, showLabels, knobAxis,
                                               knobKeys, edgeBack, knobDir, menuOn, repeatOn, twoFinger, twoTap,
                                               animations, hints, statusLine, pocket, hapticsOn, volumeTo]
}
