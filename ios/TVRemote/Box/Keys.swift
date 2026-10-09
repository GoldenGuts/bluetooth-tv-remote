/// Logical remote actions (the numbers match Protocol.java of the Android app) and their Android key codes.
enum Key {
    static let up = 1
    static let down = 2
    static let left = 3
    static let right = 4
    static let ok = 5
    static let back = 6
    static let home = 7
    static let settings = 8
    static let input = 9
    static let volumeUp = 10
    static let volumeDown = 11
    static let mute = 12
    static let playPause = 13
    static let rewind = 14
    static let fastForward = 15
    static let power = 16

    private static let names = ["?", "up", "down", "left", "right", "ok", "back", "home", "settings",
                                "input", "volume up", "volume down", "mute", "play pause", "rewind",
                                "fast forward", "power"]

    static func name(_ action: Int) -> String {
        action > 0 && action < names.count ? names[action] : "?"
    }

    /// android.view.KeyEvent codes for the Google TV remote protocol.
    static func code(_ action: Int) -> Int {
        switch action {
        case up: return 19
        case down: return 20
        case left: return 21
        case right: return 22
        case ok: return 23
        case back: return 4
        case home: return 3
        case settings: return 176
        case input: return 178
        case volumeUp: return 24
        case volumeDown: return 25
        case mute: return 164
        case playPause: return 85
        case rewind: return 89
        case fastForward: return 90
        case power: return 26
        default: return 0
        }
    }

    static let codeDel = 67
    static let codeEnter = 66

    /// A typed character as an Android key code, or 0. Upper case comes out as lower case (no shift).
    static func charCode(_ c: Character) -> Int {
        guard let s = c.unicodeScalars.first, c.unicodeScalars.count == 1 else { return 0 }
        let v = Int(s.value)
        if (97...122).contains(v) { return 29 + v - 97 }   // a-z
        if (65...90).contains(v) { return 29 + v - 65 }    // A-Z
        if (48...57).contains(v) { return 7 + v - 48 }     // 0-9
        switch c {
        case " ": return 62
        case "\n": return codeEnter
        case "\u{8}": return codeDel
        case "\t": return 61
        case ",": return 55
        case ".": return 56
        case "`": return 68
        case "-": return 69
        case "=": return 70
        case "[": return 71
        case "]": return 72
        case "\\": return 73
        case ";": return 74
        case "'": return 75
        case "/": return 76
        case "@": return 77
        case "+": return 81
        case "*": return 17
        case "#": return 18
        default: return 0
        }
    }
}
