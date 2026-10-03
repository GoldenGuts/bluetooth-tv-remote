import in.weenja.bttvremote.gesture.GestureConfig;
import in.weenja.bttvremote.gesture.GestureEngine;
import in.weenja.bttvremote.gesture.GestureEngine.Action;
import in.weenja.bttvremote.gesture.GestureEngine.Cue;

import java.util.ArrayList;
import java.util.List;

/** Desktop tests for GestureEngine. Plain main(), no JUnit. Surface 70 x 150 mm, touches start at (35, 75). */
public final class GestureEngineTest {

    private static int failures;
    private static int count;

    /** Scripted touch sequence. at() ticks every 10 ms up to the time, then events happen at that time. */
    static final class Rig implements GestureEngine.Listener {
        final List<String> out = new ArrayList<>();
        final GestureEngine e;
        long t;
        final float[] px = new float[16], py = new float[16];

        Rig(GestureConfig cfg) {
            e = new GestureEngine(cfg, this);
            e.setSurface(70f, 150f);
        }

        public void onAction(Action a, boolean repeat) { out.add(repeat ? a + "(r)" : a.toString()); }

        public void onCue(Cue q) { out.add("~" + q); }

        Rig at(long time) {
            for (long k = (t / 10 + 1) * 10; k < time; k += 10) e.tick(k);
            if (time > t) e.tick(time);
            t = time;
            return this;
        }

        Rig down(int id, float x, float y) { return down(id, x, y, 6f); }

        Rig down(int id, float x, float y, float size) {
            px[id] = x;
            py[id] = y;
            e.down(id, x, y, size, t);
            return this;
        }

        Rig move(int id, float x, float y) { return move(id, x, y, 6f); }

        Rig move(int id, float x, float y, float size) {
            px[id] = x;
            py[id] = y;
            e.move(id, x, y, size, t);
            return this;
        }

        Rig twist(float deg) {
            e.twist(deg, t);
            return this;
        }

        /** Twists `deg` in total, in `n` equal deltas 10 ms apart. */
        Rig twistBy(float deg, int n) {
            for (int i = 0; i < n; i++) at(t + 10).twist(deg / n);
            return this;
        }

        Rig up(int id) {
            e.up(id, t);
            return this;
        }

        /** Moves pointer `id` by (dx, dy) in 10 ms steps over `ms`. */
        Rig glide(int id, float dx, float dy, long ms) {
            return glide2(id, -1, dx, dy, ms);
        }

        /** Moves one or two pointers together by (dx, dy) in 10 ms steps. */
        Rig glide2(int a, int b, float dx, float dy, long ms) {
            int n = (int) (ms / 10);
            float ax = px[a], ay = py[a], bx = b >= 0 ? px[b] : 0, by = b >= 0 ? py[b] : 0;
            long start = t;
            for (int i = 1; i <= n; i++) {
                at(start + i * 10L);
                float f = i / (float) n;
                move(a, ax + dx * f, ay + dy * f);
                if (b >= 0) move(b, bx + dx * f, by + dy * f);
            }
            return this;
        }

        String log() { return String.join(" ", out); }
    }

    static Rig rig() { return new Rig(new GestureConfig()); }

    static void check(String name, Rig r, String expected) { check(name, r.log(), expected); }

    static void check(String name, String actual, String expected) {
        count++;
        if (actual.equals(expected)) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + "\n     expected: [" + expected + "]\n     actual:   [" + actual + "]");
        }
    }

    public static void main(String[] args) {
        // ---- one finger: tap ----
        check("tap -> OK", rig().down(1, 35, 75).at(100).up(1), "OK");
        check("slow tap 260 ms -> nothing", rig().down(1, 35, 75).at(260).up(1), "");
        check("tap with 4 mm move -> nothing", rig().down(1, 35, 75).at(50).move(1, 39, 75).at(100).up(1), "");
        check("dead zone lift at 300 ms -> nothing", rig().down(1, 35, 75).at(300).up(1), "");

        // ---- one finger: D-pad ----
        check("drag right 12 -> RIGHT", rig().down(1, 35, 75).glide(1, 12, 0, 100).up(1), "RIGHT");
        check("drag right 11 -> nothing", rig().down(1, 35, 75).glide(1, 11, 0, 100).up(1), "");
        check("drag right 28 -> 3 steps", rig().down(1, 35, 75).glide(1, 28, 0, 200).up(1), "RIGHT RIGHT RIGHT");
        check("one jump of 28 -> 3 steps", rig().down(1, 35, 75).at(50).move(1, 63, 75).up(1), "RIGHT RIGHT RIGHT");
        check("drag up 12 -> UP", rig().down(1, 35, 75).glide(1, 0, -12, 100).up(1), "UP");
        check("drag down 20 -> DOWN x2", rig().down(1, 35, 75).glide(1, 0, 20, 150).up(1), "DOWN DOWN");
        check("drag left 12 -> LEFT", rig().down(1, 35, 75).glide(1, -12, 0, 100).up(1), "LEFT");

        Rig diag = rig().down(1, 35, 75).glide(1, 12, 11, 100);
        check("diagonal 12,11 waits", diag.log(), "");
        diag.at(120).move(1, 35 + 13.5f, 75 + 12).up(1);
        check("diagonal decides at 18 mm (dominant x)", diag, "RIGHT");

        check("thumb arc: sideways drift after lock -> only UP",
                rig().down(1, 35, 75).glide(1, 2, -12, 100).glide(1, 8, -16, 150).up(1), "UP UP UP");
        check("reverse: right 28 then back to 12",
                rig().down(1, 35, 75).glide(1, 28, 0, 200).glide(1, -16, 0, 150).up(1),
                "RIGHT RIGHT RIGHT LEFT LEFT");
        GestureConfig noRev = new GestureConfig();
        noRev.reverseSteps = false;
        check("reverseSteps=false: no LEFT, high-water mark",
                new Rig(noRev).down(1, 35, 75).glide(1, 28, 0, 200).glide(1, -16, 0, 150)
                        .glide(1, 16, 0, 150).glide(1, 8, 0, 80).up(1),
                "RIGHT RIGHT RIGHT RIGHT");

        // ---- one finger: auto-repeat ----
        check("repeat after hold away from start",
                rig().down(1, 35, 75).glide(1, 12, 0, 100).at(820).up(1),
                "RIGHT RIGHT(r) RIGHT(r) RIGHT(r)");
        check("no repeat when holding before first step",
                rig().down(1, 35, 75).glide(1, 8, 0, 100).at(1500).up(1), "");
        Rig rs = rig().down(1, 35, 75).glide(1, 12, 0, 100).at(560).move(1, 47, 78).at(900);
        check("repeat stops on move", rs.log(), "RIGHT RIGHT(r)");
        rs.at(970).up(1);
        check("repeat resumes after settling again", rs, "RIGHT RIGHT(r) RIGHT(r)");
        check("repeats do not move the ratchet",
                rig().down(1, 35, 75).glide(1, 12, 0, 100).at(700).glide(1, 8, 0, 50).up(1),
                "RIGHT RIGHT(r) RIGHT(r) RIGHT");
        check("repeat after up drag goes UP",
                rig().down(1, 35, 75).glide(1, 0, -20, 100).at(510).up(1), "UP UP UP(r)");

        // ---- one finger: thumb menu ----
        Rig m = rig().down(1, 35, 75).at(399);
        check("menu: nothing before 400 ms", m.log(), "");
        m.at(400);
        check("menu: MENU_OPEN at 400 ms", m.log(), "~MENU_OPEN");
        m.at(1000).up(1);
        check("menu: lift -> PLAY_PAUSE", m, "~MENU_OPEN PLAY_PAUSE");
        check("menu: small wobble then lift -> PLAY_PAUSE",
                rig().down(1, 35, 75).at(450).glide(1, 3, 6, 50).up(1), "~MENU_OPEN PLAY_PAUSE");
        check("menu: up 10 -> HOME, nothing after",
                rig().down(1, 35, 75).at(450).glide(1, 0, -10, 50).glide(1, 0, -30, 200).glide(1, 30, 0, 200)
                        .at(2000).up(1),
                "~MENU_OPEN HOME");
        check("menu: down 40 -> INPUT x3 (10 + 15 + 15)",
                rig().down(1, 35, 75).at(450).glide(1, 0, 40, 300).at(1500).up(1),
                "~MENU_OPEN INPUT INPUT INPUT");
        check("menu: down then back up -> no reverse",
                rig().down(1, 35, 75).at(450).glide(1, 0, 25, 200).glide(1, 0, -40, 200).up(1),
                "~MENU_OPEN INPUT INPUT");
        check("menu: left -> MUTE", rig().down(1, 35, 75).at(450).glide(1, -12, 0, 60).glide(1, -20, 0, 100).up(1),
                "~MENU_OPEN MUTE");
        check("menu: right -> SETTINGS", rig().down(1, 35, 75).at(450).glide(1, 12, 0, 60).up(1),
                "~MENU_OPEN SETTINGS");
        check("menu: measured from where it opened",
                rig().down(1, 35, 75).at(100).move(1, 38, 75).at(450).glide(1, 9, 0, 50).up(1),
                "~MENU_OPEN PLAY_PAUSE");
        Rig md = rig().down(1, 35, 75).at(450).glide(1, 8, 8, 50);
        check("menu: diagonal waits", md.log(), "~MENU_OPEN");
        md.glide(1, 6, 5, 50).up(1);
        check("menu: diagonal decides at 18 mm", md, "~MENU_OPEN SETTINGS");
        check("menu: move 5 mm at 200 ms then hold -> no menu",
                rig().down(1, 35, 75).at(200).move(1, 40, 75).at(1500).up(1), "");
        check("menu: no D-pad repeat inside menu",
                rig().down(1, 35, 75).at(450).glide(1, 0, 12, 50).at(1500).up(1), "~MENU_OPEN INPUT");

        // ---- two fingers ----
        check("two-finger tap -> PLAY_PAUSE",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).at(150).up(1).at(180).up(2), "PLAY_PAUSE");
        check("two-finger tap too slow -> nothing",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).at(300).up(1).at(320).up(2), "");
        check("second finger late (200 ms) -> ignored, tap -> OK",
                rig().down(1, 30, 75).at(200).down(2, 50, 75).at(230).up(1).at(260).up(2), "OK");
        check("second finger after a step -> ignored",
                rig().down(1, 30, 75).glide(1, 12, 0, 100).at(120).down(2, 50, 90).glide2(1, 2, 8, 0, 80)
                        .up(1).up(2),
                "RIGHT RIGHT");
        check("second finger after 4 mm move -> grip, drag stays D-pad",
                rig().down(1, 30, 75).at(30).move(1, 34, 75).at(60).down(2, 50, 75).glide2(1, 2, 12, 0, 100)
                        .up(1).up(2),
                "RIGHT");
        check("two-finger right 30 -> FAST_FORWARD x2",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).glide2(1, 2, 30, 0, 200).up(1).up(2),
                "FAST_FORWARD FAST_FORWARD");
        check("two-finger left 30 then back 15 -> REWIND x2, FAST_FORWARD",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).glide2(1, 2, -30, 0, 200).glide2(1, 2, 15, 0, 100)
                        .up(1).up(2),
                "REWIND REWIND FAST_FORWARD");
        check("two-finger up 45 -> INPUT x3",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).glide2(1, 2, 0, -45, 300).glide2(1, 2, 0, 30, 200)
                        .up(1).up(2),
                "INPUT INPUT INPUT");
        check("two-finger down 40 -> SETTINGS once",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).glide2(1, 2, 0, 40, 300).up(1).up(2), "SETTINGS");
        Rig mu = rig().down(1, 30, 75).at(50).down(2, 40, 75).at(349);
        check("two-finger hold: nothing before 350", mu.log(), "");
        mu.at(700).glide2(1, 2, 30, 0, 200).up(1).up(2);
        check("two-finger hold -> MUTE_ARMING, MUTE, nothing after", mu, "~MUTE_ARMING MUTE");
        check("two-finger hold, one lifts at 500 -> ARM_CANCELLED",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).at(500).up(2).at(900).up(1),
                "~MUTE_ARMING ~ARM_CANCELLED");
        check("two-finger hold, move -> ARM_CANCELLED then FAST_FORWARD",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).at(400).glide2(1, 2, 15, 0, 100).up(1).up(2),
                "~MUTE_ARMING ~ARM_CANCELLED FAST_FORWARD");
        check("two fingers, one lifts early, other drags -> nothing",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).at(100).up(2).glide(1, 20, 0, 100).up(1), "");
        check("two fingers, one lifts early, both within tap -> PLAY_PAUSE",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).at(100).up(2).at(200).up(1), "PLAY_PAUSE");

        // ---- rejection ----
        check("edge pointer ignored (left and right)",
                rig().down(1, 1, 75).at(1000).glide(1, 20, 0, 100).up(1).down(2, 68.5f, 75).at(1200).up(2), "");
        check("edge pointer does not block a real touch",
                rig().down(9, 1, 75).at(100).down(1, 35, 75).at(150).up(1).at(300).down(2, 35, 75).at(350).up(2)
                        .at(400).up(9),
                "OK OK");
        check("edge pointer does not count as a third finger",
                rig().down(9, 1, 75).at(100).down(1, 30, 75).at(150).down(2, 40, 75).at(250).up(1).up(2),
                "PLAY_PAUSE");
        check("palm on down -> REJECTED, no action",
                rig().down(1, 35, 75, 20f).at(100).glide(1, 20, 0, 100).at(900).up(1), "~REJECTED");
        check("palm mid-drag -> REJECTED, nothing after",
                rig().down(1, 35, 75).glide(1, 6, 0, 50).move(1, 41, 75, 16f).glide(1, 20, 0, 100).up(1),
                "~REJECTED");
        check("third finger -> REJECTED",
                rig().down(1, 30, 75).at(50).down(2, 40, 75).at(100).down(3, 50, 75)
                        .glide2(1, 2, 30, 0, 200).up(1).up(2).up(3),
                "~REJECTED");
        check("after rejection the next touch works",
                rig().down(1, 35, 75, 20f).at(100).up(1).at(200).down(1, 35, 75).at(250).up(1), "~REJECTED OK");

        // ---- rotation ----
        Rig q = rig();
        q.e.setQuarterTurns(1);
        q.down(1, 35, 75).glide(1, 12, 0, 100).up(1).at(200).down(1, 35, 75).glide(1, 0, 12, 100).up(1)
                .at(400).down(1, 35, 75).glide(1, -12, 0, 100).up(1)
                .at(600).down(1, 35, 75).glide(1, 0, -12, 100).up(1);
        check("quarter turns=1: right=UP, down=RIGHT, left=DOWN, up=LEFT", q, "UP RIGHT DOWN LEFT");
        Rig q2 = rig();
        q2.down(1, 35, 75).at(20);
        q2.e.setQuarterTurns(1);
        q2.glide(1, 12, 0, 100).up(1).at(300).down(1, 35, 75).glide(1, 12, 0, 100).up(1);
        check("quarter turns apply at the next touch start", q2, "RIGHT UP");
        Rig q3 = rig();
        q3.e.setQuarterTurns(2);
        q3.down(1, 35, 75).glide(1, 12, 0, 100).up(1);
        check("quarter turns=2: right=LEFT", q3, "LEFT");
        GestureConfig bias = new GestureConfig();
        bias.biasDeg = 45f;
        check("biasDeg=45: up-right 45 deg -> UP at once", new Rig(bias).down(1, 35, 75).glide(1, 9, -9, 100).up(1),
                "UP");
        check("biasDeg=0: same up-right drag still waits", rig().down(1, 35, 75).glide(1, 9, -9, 100).up(1), "");
        GestureConfig bias2 = new GestureConfig();
        bias2.biasDeg = 20f;
        float s20 = (float) Math.sin(Math.toRadians(20)), c20 = (float) Math.cos(Math.toRadians(20));
        check("biasDeg=20: thumb arc 20 deg right of up -> UP x3 with no sideways",
                new Rig(bias2).down(1, 35, 75).glide(1, 28 * s20, -28 * c20, 200).up(1), "UP UP UP");
        check("biasDeg=20: physical right is RIGHT (rotated, still dominant x)",
                new Rig(bias2).down(1, 35, 75).glide(1, 12.5f, 0, 100).up(1), "RIGHT");

        // ---- enable, config, cancel ----
        Rig en = rig().down(1, 35, 75).at(420);
        en.e.setEnabled(false);
        en.at(800).glide(1, 0, -20, 100).up(1);
        en.e.setEnabled(true);
        en.at(1000).down(1, 35, 75).at(1100).up(1);
        check("setEnabled(false) mid-menu drops silently, later tap works", en, "~MENU_OPEN OK");
        Rig en2 = rig();
        en2.e.setEnabled(false);
        en2.down(1, 35, 75).at(100).up(1);
        check("disabled: tap ignored", en2, "");
        Rig cf = rig().down(1, 35, 75).at(50);
        GestureConfig fast = new GestureConfig();
        fast.tapMaxMs = 100;
        cf.e.setConfig(fast);
        cf.at(200).up(1).at(300).down(1, 35, 75).at(500).up(1);
        check("setConfig takes effect at next touch", cf, "OK");
        Rig cc = rig().down(1, 30, 75).at(50).down(2, 40, 75).at(400);
        cc.e.cancel(400);
        cc.at(800);
        check("cancel() while mute arming -> ARM_CANCELLED, inactive", cc.log() + (cc.e.isActive() ? " active" : ""),
                "~MUTE_ARMING ~ARM_CANCELLED");
        Rig ia = rig().down(9, 1, 75);
        check("isActive with only an edge pointer", String.valueOf(ia.e.isActive()), "true");

        // ---- knob (wrist twist in the thumb menu) ----
        check("knob: steps both ways with reverse",
                rig().down(1, 35, 75).at(450).twistBy(31, 6).twistBy(-47, 8).twistBy(20, 4).up(1),
                "~MENU_OPEN KNOB_RIGHT KNOB_RIGHT KNOB_LEFT KNOB_LEFT KNOB_LEFT KNOB_RIGHT");
        check("knob: twist before menu open is ignored",
                rig().down(1, 35, 75).at(100).twistBy(40, 10).at(450).twistBy(14, 2).at(500).up(1),
                "~MENU_OPEN PLAY_PAUSE");
        check("knob: after a slide decision (INPUT) twists are ignored",
                rig().down(1, 35, 75).at(450).glide(1, 0, 12, 60).twistBy(60, 4).up(1), "~MENU_OPEN INPUT");
        check("knob: after HOME twists are ignored",
                rig().down(1, 35, 75).at(450).glide(1, 0, -12, 60).twistBy(60, 4).up(1), "~MENU_OPEN HOME");
        check("knob: lift after knob step -> no PLAY_PAUSE",
                rig().down(1, 35, 75).at(450).twistBy(16, 2).at(600).up(1), "~MENU_OPEN KNOB_RIGHT");
        check("knob mode: slides select nothing, twists keep stepping",
                rig().down(1, 35, 75).at(450).twistBy(-15, 1).glide(1, 0, -20, 100).twistBy(-15, 1).up(1),
                "~MENU_OPEN KNOB_LEFT KNOB_LEFT");
        check("knob: twist with no finger down is ignored", rig().twistBy(90, 3).log(), "");
        GestureConfig noKnob = new GestureConfig();
        noKnob.knob = false;
        check("knob=false: twist ignored, lift -> PLAY_PAUSE",
                new Rig(noKnob).down(1, 35, 75).at(450).twistBy(60, 4).up(1), "~MENU_OPEN PLAY_PAUSE");

        // ---- edge back ----
        GestureConfig eb = new GestureConfig();
        eb.edgeBack = true;
        check("edge back: left edge, right 10 mm -> BACK once",
                new Rig(eb).down(1, 1, 75).glide(1, 10, 2, 80).glide(1, 20, 0, 100).at(600).up(1), "BACK");
        check("edge back: right edge, left 10 mm -> BACK",
                new Rig(eb).down(1, 68.5f, 75).glide(1, -10, -1, 80).up(1), "BACK");
        check("edge back: moving along the edge -> nothing",
                new Rig(eb).down(1, 1, 75).glide(1, 0, 30, 200).glide(1, 1, 0, 20).up(1), "");
        check("edge back: steep inward swipe (ratio < 1.3) -> nothing",
                new Rig(eb).down(1, 1, 75).glide(1, 10, 9, 80).up(1), "");
        check("edge back: outward is not inward",
                new Rig(eb).down(1, 68.5f, 75).glide(1, 10, 0, 80).up(1), "");
        check("edge back: while another finger is down -> grip, nothing",
                new Rig(eb).down(2, 35, 75).at(10).down(1, 1, 75).glide(1, 12, 0, 80).at(700).up(1).up(2),
                "~MENU_OPEN PLAY_PAUSE");
        check("edge back: real touch after BACK still works",
                new Rig(eb).down(1, 1, 75).glide(1, 12, 0, 80).at(200).down(2, 35, 75).at(250).up(2).up(1),
                "BACK OK");
        check("edgeBack=false: inward swipe from edge -> nothing",
                rig().down(1, 1, 75).glide(1, 12, 0, 80).up(1).down(2, 68.5f, 75).glide(2, -12, 0, 80).up(2), "");

        System.out.println();
        System.out.println((count - failures) + "/" + count + " passed" + (failures > 0 ? ", " + failures + " FAILED" : ""));
        if (failures > 0) System.exit(1);
    }
}
