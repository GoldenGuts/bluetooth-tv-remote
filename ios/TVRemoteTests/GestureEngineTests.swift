import Foundation
import Testing
@testable import TVRemote

/// The Android GestureEngineTest, case by case. Surface 70 x 150 mm, touches start at (35, 75).
@MainActor
final class Rig: GestureEngine.Listener {
    var out: [String] = []
    var e: GestureEngine!
    var t: Int64 = 0
    var px = [Float](repeating: 0, count: 16), py = [Float](repeating: 0, count: 16)

    init(_ cfg: GestureConfig = GestureConfig()) {
        e = GestureEngine(cfg, listener: self)
        e.setSurface(widthMm: 70, heightMm: 150)
    }

    func onAction(_ action: GestureEngine.Action, repeat isRepeat: Bool) {
        out.append(isRepeat ? action.rawValue + "(r)" : action.rawValue)
    }

    func onCue(_ cue: GestureEngine.Cue) {
        out.append("~" + cue.rawValue)
    }

    /// Ticks every 10 ms up to the time, then events happen at that time.
    @discardableResult func at(_ time: Int64) -> Rig {
        var k = (t / 10 + 1) * 10
        while k < time {
            e.tick(k)
            k += 10
        }
        if time > t { e.tick(time) }
        t = time
        return self
    }

    @discardableResult func down(_ id: Int, _ x: Float, _ y: Float, _ size: Float = 6) -> Rig {
        px[id] = x
        py[id] = y
        e.down(id, x, y, size: size, time: t)
        return self
    }

    @discardableResult func move(_ id: Int, _ x: Float, _ y: Float, _ size: Float = 6) -> Rig {
        px[id] = x
        py[id] = y
        e.move(id, x, y, size: size, time: t)
        return self
    }

    @discardableResult func twist(_ deg: Float) -> Rig {
        e.twist(deg, time: t)
        return self
    }

    /// Twists `deg` in total, in `n` equal deltas 10 ms apart.
    @discardableResult func twistBy(_ deg: Float, _ n: Int) -> Rig {
        for _ in 0..<n { at(t + 10).twist(deg / Float(n)) }
        return self
    }

    @discardableResult func up(_ id: Int) -> Rig {
        e.up(id, time: t)
        return self
    }

    /// Moves pointer `id` by (dx, dy) in 10 ms steps over `ms`.
    @discardableResult func glide(_ id: Int, _ dx: Float, _ dy: Float, _ ms: Int64) -> Rig {
        glide2(id, -1, dx, dy, ms)
    }

    /// Moves one or two pointers together by (dx, dy) in 10 ms steps.
    @discardableResult func glide2(_ a: Int, _ b: Int, _ dx: Float, _ dy: Float, _ ms: Int64) -> Rig {
        let n = Int(ms / 10)
        let ax = px[a], ay = py[a], bx = b >= 0 ? px[b] : 0, by = b >= 0 ? py[b] : 0
        let start = t
        for i in 1...max(1, n) where n > 0 {
            at(start + Int64(i) * 10)
            let f = Float(i) / Float(n)
            move(a, ax + dx * f, ay + dy * f)
            if b >= 0 { move(b, bx + dx * f, by + dy * f) }
        }
        return self
    }

    var log: String { out.joined(separator: " ") }
}

@MainActor
struct GestureEngineTests {

    private func cfg(_ change: (inout GestureConfig) -> Void) -> GestureConfig {
        var c = GestureConfig()
        change(&c)
        return c
    }

    @Test func tap() {
        #expect(Rig().down(1, 35, 75).at(100).up(1).log == "OK")
        #expect(Rig().down(1, 35, 75).at(260).up(1).log == "", "slow tap 260 ms -> nothing")
        #expect(Rig().down(1, 35, 75).at(50).move(1, 39, 75).at(100).up(1).log == "", "tap with 4 mm move")
        #expect(Rig().down(1, 35, 75).at(300).up(1).log == "", "dead zone lift at 300 ms")
    }

    @Test func dpad() {
        #expect(Rig().down(1, 35, 75).glide(1, 12, 0, 100).up(1).log == "RIGHT")
        #expect(Rig().down(1, 35, 75).glide(1, 11, 0, 100).up(1).log == "")
        #expect(Rig().down(1, 35, 75).glide(1, 28, 0, 200).up(1).log == "RIGHT RIGHT RIGHT")
        #expect(Rig().down(1, 35, 75).at(50).move(1, 63, 75).up(1).log == "RIGHT RIGHT RIGHT", "one jump of 28")
        #expect(Rig().down(1, 35, 75).glide(1, 0, -12, 100).up(1).log == "UP")
        #expect(Rig().down(1, 35, 75).glide(1, 0, 20, 150).up(1).log == "DOWN DOWN")
        #expect(Rig().down(1, 35, 75).glide(1, -12, 0, 100).up(1).log == "LEFT")

        let diag = Rig().down(1, 35, 75).glide(1, 12, 11, 100)
        #expect(diag.log == "", "diagonal 12,11 waits")
        diag.at(120).move(1, 35 + 13.5, 75 + 12).up(1)
        #expect(diag.log == "RIGHT", "diagonal decides at 18 mm (dominant x)")

        #expect(Rig().down(1, 35, 75).glide(1, 2, -12, 100).glide(1, 8, -16, 150).up(1).log == "UP UP UP",
                "thumb arc: sideways drift after lock")
        #expect(Rig().down(1, 35, 75).glide(1, 28, 0, 200).glide(1, -16, 0, 150).up(1).log
                == "RIGHT RIGHT RIGHT LEFT LEFT", "reverse")
        let noRev = cfg { $0.reverseSteps = false }
        #expect(Rig(noRev).down(1, 35, 75).glide(1, 28, 0, 200).glide(1, -16, 0, 150)
            .glide(1, 16, 0, 150).glide(1, 8, 0, 80).up(1).log == "RIGHT RIGHT RIGHT RIGHT", "high-water mark")
    }

    @Test func springBack() {
        let sb = cfg { $0.reverseMm = 14 }
        #expect(Rig(sb).down(1, 35, 75).glide(1, 12, 0, 100).glide(1, -8, 0, 60).up(1).log == "RIGHT")
        #expect(Rig(sb).down(1, 35, 75).glide(1, 28, 0, 200).glide(1, -10, 0, 80).up(1).log == "RIGHT RIGHT RIGHT")
        #expect(Rig(sb).down(1, 35, 75).glide(1, 28, 0, 200).glide(1, -22, 0, 150).up(1).log
                == "RIGHT RIGHT RIGHT LEFT LEFT")
        #expect(Rig(sb).down(1, 35, 75).glide(1, 28, 0, 200).glide(1, -14, 0, 100).glide(1, 10, 0, 80)
            .glide(1, 4, 0, 40).up(1).log == "RIGHT RIGHT RIGHT LEFT RIGHT")
    }

    @Test func flick() {
        let fl = cfg {
            $0.flick = true
            $0.reverseMm = 14
        }
        #expect(Rig(fl).down(1, 35, 75).glide(1, 8, 0, 60).up(1).log == "RIGHT")
        #expect(Rig(fl).down(1, 35, 75).glide(1, 25, 0, 120).up(1).log == "RIGHT")
        #expect(Rig(fl).down(1, 35, 75).at(50).move(1, 60, 75).up(1).log == "RIGHT")
        #expect(Rig(fl).down(1, 35, 75).glide(1, 20, 0, 100).glide(1, -15, 0, 80).up(1).log == "RIGHT")
        #expect(Rig(fl).down(1, 35, 75).glide(1, 6, 0, 40).up(1).log == "RIGHT", "short flick on lift")
        #expect(Rig(fl).down(1, 35, 75).glide(1, 4, 0, 40).up(1).log == "")
        #expect(Rig(fl).down(1, 35, 75).glide(1, 5, 5, 40).up(1).log == "", "diagonal on lift")
        #expect(Rig(fl).down(1, 35, 75).at(100).up(1).log == "OK")
        #expect(Rig(fl).down(1, 35, 75).glide(1, 46, 0, 300).up(1).log == "RIGHT RIGHT RIGHT RIGHT", "long drag")
        #expect(Rig(fl).down(1, 35, 75).glide(1, 0, -10, 60).up(1).log == "UP")
        #expect(Rig(fl).down(1, 35, 75).glide(1, 10, 0, 60).at(600).up(1).log == "RIGHT RIGHT(r) RIGHT(r)")
        #expect(Rig(fl).down(1, 35, 75).at(450).up(1).log == "~MENU_OPEN PLAY_PAUSE")
    }

    @Test func autoRepeat() {
        #expect(Rig().down(1, 35, 75).glide(1, 12, 0, 100).at(820).up(1).log == "RIGHT RIGHT(r) RIGHT(r) RIGHT(r)")
        #expect(Rig().down(1, 35, 75).glide(1, 8, 0, 100).at(1500).up(1).log == "")
        let rs = Rig().down(1, 35, 75).glide(1, 12, 0, 100).at(560).move(1, 47, 78).at(900)
        #expect(rs.log == "RIGHT RIGHT(r)", "repeat stops on move")
        rs.at(970).up(1)
        #expect(rs.log == "RIGHT RIGHT(r) RIGHT(r)", "repeat resumes after settling again")
        #expect(Rig().down(1, 35, 75).glide(1, 12, 0, 100).at(700).glide(1, 8, 0, 50).up(1).log
                == "RIGHT RIGHT(r) RIGHT(r) RIGHT", "repeats do not move the ratchet")
        #expect(Rig().down(1, 35, 75).glide(1, 0, -20, 100).at(510).up(1).log == "UP UP UP(r)")
    }

    @Test func thumbMenu() {
        let m = Rig().down(1, 35, 75).at(399)
        #expect(m.log == "")
        m.at(400)
        #expect(m.log == "~MENU_OPEN")
        m.at(1000).up(1)
        #expect(m.log == "~MENU_OPEN PLAY_PAUSE")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, 3, 6, 50).up(1).log == "~MENU_OPEN PLAY_PAUSE")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, 0, -10, 50).glide(1, 0, -30, 200).glide(1, 30, 0, 200)
            .at(2000).up(1).log == "~MENU_OPEN HOME")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, 0, 40, 300).at(1500).up(1).log
                == "~MENU_OPEN INPUT INPUT INPUT")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, 0, 25, 200).glide(1, 0, -40, 200).up(1).log
                == "~MENU_OPEN INPUT INPUT")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, -12, 0, 60).glide(1, -20, 0, 100).up(1).log == "~MENU_OPEN MUTE")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, 12, 0, 60).up(1).log == "~MENU_OPEN SETTINGS")
        #expect(Rig().down(1, 35, 75).at(100).move(1, 38, 75).at(450).glide(1, 9, 0, 50).up(1).log
                == "~MENU_OPEN PLAY_PAUSE", "measured from where it opened")
        let md = Rig().down(1, 35, 75).at(450).glide(1, 8, 8, 50)
        #expect(md.log == "~MENU_OPEN", "diagonal waits")
        md.glide(1, 6, 5, 50).up(1)
        #expect(md.log == "~MENU_OPEN SETTINGS")
        #expect(Rig().down(1, 35, 75).at(200).move(1, 40, 75).at(1500).up(1).log == "")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, 0, 12, 50).at(1500).up(1).log == "~MENU_OPEN INPUT")
    }

    @Test func twoFingers() {
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(150).up(1).at(180).up(2).log == "PLAY_PAUSE")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(300).up(1).at(320).up(2).log == "")
        #expect(Rig().down(1, 30, 75).at(200).down(2, 50, 75).at(230).up(1).at(260).up(2).log == "OK")
        #expect(Rig().down(1, 30, 75).glide(1, 12, 0, 100).at(120).down(2, 50, 90).glide2(1, 2, 8, 0, 80)
            .up(1).up(2).log == "RIGHT RIGHT")
        #expect(Rig().down(1, 30, 75).at(30).move(1, 34, 75).at(60).down(2, 50, 75).glide2(1, 2, 12, 0, 100)
            .up(1).up(2).log == "RIGHT")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).glide2(1, 2, 30, 0, 200).up(1).up(2).log
                == "FAST_FORWARD FAST_FORWARD")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).glide2(1, 2, -30, 0, 200).glide2(1, 2, 15, 0, 100)
            .up(1).up(2).log == "REWIND REWIND FAST_FORWARD")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).glide2(1, 2, 0, -45, 300).glide2(1, 2, 0, 30, 200)
            .up(1).up(2).log == "INPUT INPUT INPUT")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).glide2(1, 2, 0, 40, 300).up(1).up(2).log == "SETTINGS")
        let mu = Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(349)
        #expect(mu.log == "")
        mu.at(700).glide2(1, 2, 30, 0, 200).up(1).up(2)
        #expect(mu.log == "~MUTE_ARMING MUTE")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(500).up(2).at(900).up(1).log
                == "~MUTE_ARMING ~ARM_CANCELLED")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(400).glide2(1, 2, 15, 0, 100).up(1).up(2).log
                == "~MUTE_ARMING ~ARM_CANCELLED FAST_FORWARD")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(100).up(2).glide(1, 20, 0, 100).up(1).log == "")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(100).up(2).at(200).up(1).log == "PLAY_PAUSE")
    }

    @Test func rejection() {
        #expect(Rig().down(1, 1, 75).at(1000).glide(1, 20, 0, 100).up(1).down(2, 68.5, 75).at(1200).up(2).log == "")
        #expect(Rig().down(9, 1, 75).at(100).down(1, 35, 75).at(150).up(1).at(300).down(2, 35, 75).at(350).up(2)
            .at(400).up(9).log == "OK OK")
        #expect(Rig().down(9, 1, 75).at(100).down(1, 30, 75).at(150).down(2, 40, 75).at(250).up(1).up(2).log
                == "PLAY_PAUSE")
        #expect(Rig().down(1, 35, 75, 20).at(100).glide(1, 20, 0, 100).at(900).up(1).log == "~REJECTED")
        #expect(Rig().down(1, 35, 75).glide(1, 6, 0, 50).move(1, 41, 75, 16).glide(1, 20, 0, 100).up(1).log == "~REJECTED")
        #expect(Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(100).down(3, 50, 75)
            .glide2(1, 2, 30, 0, 200).up(1).up(2).up(3).log == "~REJECTED")
        #expect(Rig().down(1, 35, 75, 20).at(100).up(1).at(200).down(1, 35, 75).at(250).up(1).log == "~REJECTED OK")
    }

    @Test func rotation() {
        let q = Rig()
        q.e.setQuarterTurns(1)
        q.down(1, 35, 75).glide(1, 12, 0, 100).up(1).at(200).down(1, 35, 75).glide(1, 0, 12, 100).up(1)
            .at(400).down(1, 35, 75).glide(1, -12, 0, 100).up(1)
            .at(600).down(1, 35, 75).glide(1, 0, -12, 100).up(1)
        #expect(q.log == "UP RIGHT DOWN LEFT")
        let q2 = Rig()
        q2.down(1, 35, 75).at(20)
        q2.e.setQuarterTurns(1)
        q2.glide(1, 12, 0, 100).up(1).at(300).down(1, 35, 75).glide(1, 12, 0, 100).up(1)
        #expect(q2.log == "RIGHT UP", "quarter turns apply at the next touch start")
        let q3 = Rig()
        q3.e.setQuarterTurns(2)
        q3.down(1, 35, 75).glide(1, 12, 0, 100).up(1)
        #expect(q3.log == "LEFT")
        #expect(Rig(cfg { $0.biasDeg = 45 }).down(1, 35, 75).glide(1, 9, -9, 100).up(1).log == "UP")
        #expect(Rig().down(1, 35, 75).glide(1, 9, -9, 100).up(1).log == "")
        let bias2 = cfg { $0.biasDeg = 20 }
        let s20 = Float(sin(20 * Double.pi / 180)), c20 = Float(cos(20 * Double.pi / 180))
        #expect(Rig(bias2).down(1, 35, 75).glide(1, 28 * s20, -28 * c20, 200).up(1).log == "UP UP UP")
        #expect(Rig(bias2).down(1, 35, 75).glide(1, 12.5, 0, 100).up(1).log == "RIGHT")
    }

    @Test func enableConfigCancel() {
        let en = Rig().down(1, 35, 75).at(420)
        en.e.setEnabled(false)
        en.at(800).glide(1, 0, -20, 100).up(1)
        en.e.setEnabled(true)
        en.at(1000).down(1, 35, 75).at(1100).up(1)
        #expect(en.log == "~MENU_OPEN OK")
        let en2 = Rig()
        en2.e.setEnabled(false)
        en2.down(1, 35, 75).at(100).up(1)
        #expect(en2.log == "")
        let cf = Rig().down(1, 35, 75).at(50)
        cf.e.setConfig(cfg { $0.tapMaxMs = 100 })
        cf.at(200).up(1).at(300).down(1, 35, 75).at(500).up(1)
        #expect(cf.log == "OK", "setConfig takes effect at next touch")
        let cc = Rig().down(1, 30, 75).at(50).down(2, 40, 75).at(400)
        cc.e.cancel(time: 400)
        cc.at(800)
        #expect(cc.log + (cc.e.isActive ? " active" : "") == "~MUTE_ARMING ~ARM_CANCELLED")
        #expect(Rig().down(9, 1, 75).e.isActive, "isActive with only an edge pointer")
    }

    @Test func knob() {
        #expect(Rig().down(1, 35, 75).at(450).twistBy(31, 6).twistBy(-47, 8).twistBy(20, 4).up(1).log
                == "~MENU_OPEN KNOB_RIGHT KNOB_RIGHT KNOB_LEFT KNOB_LEFT KNOB_LEFT KNOB_RIGHT")
        #expect(Rig().down(1, 35, 75).at(100).twistBy(40, 10).at(450).twistBy(14, 2).at(500).up(1).log
                == "~MENU_OPEN PLAY_PAUSE")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, 0, 12, 60).twistBy(60, 4).up(1).log == "~MENU_OPEN INPUT")
        #expect(Rig().down(1, 35, 75).at(450).glide(1, 0, -12, 60).twistBy(60, 4).up(1).log == "~MENU_OPEN HOME")
        #expect(Rig().down(1, 35, 75).at(450).twistBy(16, 2).at(600).up(1).log == "~MENU_OPEN KNOB_RIGHT")
        #expect(Rig().down(1, 35, 75).at(450).twistBy(-15, 1).glide(1, 0, -20, 100).twistBy(-15, 1).up(1).log
                == "~MENU_OPEN KNOB_LEFT KNOB_LEFT")
        #expect(Rig().twistBy(90, 3).log == "")
        #expect(Rig(cfg { $0.knob = false }).down(1, 35, 75).at(450).twistBy(60, 4).up(1).log == "~MENU_OPEN PLAY_PAUSE")
    }

    @Test func edgeBack() {
        let eb = cfg { $0.edgeBack = true }
        #expect(Rig(eb).down(1, 1, 75).glide(1, 10, 2, 80).glide(1, 20, 0, 100).at(600).up(1).log == "BACK")
        #expect(Rig(eb).down(1, 68.5, 75).glide(1, -10, -1, 80).up(1).log == "BACK")
        #expect(Rig(eb).down(1, 1, 75).glide(1, 0, 30, 200).glide(1, 1, 0, 20).up(1).log == "")
        #expect(Rig(eb).down(1, 1, 75).glide(1, 10, 9, 80).up(1).log == "", "steep inward swipe")
        #expect(Rig(eb).down(1, 68.5, 75).glide(1, 10, 0, 80).up(1).log == "", "outward is not inward")
        #expect(Rig(eb).down(2, 35, 75).at(10).down(1, 1, 75).glide(1, 12, 0, 80).at(700).up(1).up(2).log
                == "~MENU_OPEN PLAY_PAUSE", "while another finger is down")
        #expect(Rig(eb).down(1, 1, 75).glide(1, 12, 0, 80).at(200).down(2, 35, 75).at(250).up(2).up(1).log == "BACK OK")
        #expect(Rig().down(1, 1, 75).glide(1, 12, 0, 80).up(1).down(2, 68.5, 75).glide(2, -12, 0, 80).up(2).log == "")
    }
}
