package io.github.goldenguts.bttvremote.gesture;

import java.util.ArrayList;

/**
 * Turns touch points (mm, ms) into remote actions. Pure Java, no timers: time moves only through the
 * event times and tick(). Before an event is handled, the time-based rules (holds, repeats) run up to
 * the event time with the old positions.
 *
 * Rotation: movement is mapped to directions in a "logical" frame. The frame is turned CLOCKWISE on the
 * screen (x right, y down) by 90 * quarterTurns + biasDeg degrees. So with quarterTurns = 1 a physical
 * drag to the right (+x) is UP and a drag down (+y) is RIGHT. With biasDeg = 20 a drag that goes up and
 * 20 degrees to the right (a thumb arc) is a clean UP.
 *
 * Thumb menu (one finger): hold still (holdSlopMm) for menuArmMs gives MENU_OPEN. From the point where
 * the menu opened: lift within menuSelectMm = PLAY_PAUSE; a flick of menuSelectMm picks a direction (axis
 * rule as for the D-pad): up = HOME, left = MUTE, right = SETTINGS (once, rest of touch ignored), down =
 * INPUT, then one more INPUT per twoStepMm further down. There is no lift-to-cancel after MENU_OPEN: a lift
 * near the point is PLAY_PAUSE; a lift after moving out but before a direction is decided gives nothing.
 *
 * Knob: while the menu is open and no slide direction is decided, twist() deltas ratchet (with reverse)
 * one KNOB_RIGHT / KNOB_LEFT per knobStepDeg. After the first knob step the menu is in knob mode: slides
 * select nothing and the lift gives nothing. After a slide decision, twists are ignored.
 *
 * Edge back (edgeBack on): a pointer that starts in the edge strip and, while no other non-edge pointer
 * is down, moves inward by edgeBackMm (raw screen x, |dx| >= axisRatio * |dy| from its start) gives BACK
 * once. If another finger is down when it moves, or it is a palm, it stays a plain grip pointer.
 *
 * Choices where the spec is open:
 * - Rejection (palm, third finger) emits only REJECTED, also when the mute arm was active: the caller
 *   stops any arming vibration on REJECTED. cancel() emits ARM_CANCELLED if the mute arm was active.
 * - Edge (grip) pointers are ignored entirely, also for the palm size test, and never count as fingers.
 * - A gesture ends when its own finger(s) lift. Other fingers still down (grip) are ignored until they lift.
 * - Without reverse steps the ratchet keeps its high-water mark: back and forward again gives no step
 *   until the finger passes the furthest step. Two-finger INPUT always works like that.
 * - Axis tie (|x| == |y|) picks the vertical axis.
 */
public final class GestureEngine {

    public enum Action {
        UP, DOWN, LEFT, RIGHT, OK, HOME, PLAY_PAUSE, REWIND, FAST_FORWARD, INPUT, SETTINGS, MUTE,
        KNOB_RIGHT, KNOB_LEFT, BACK
    }

    public enum Cue { MENU_OPEN, MUTE_ARMING, ARM_CANCELLED, REJECTED }

    public interface Listener {
        void onAction(Action action, boolean repeat);
        void onCue(Cue cue);
    }

    private static final float EPS = 1e-3f;

    private static final int IDLE = 0, ONE = 1, TWO = 2, DONE = 3, REJECTED = 4;
    private static final int EDGE = 0, STALE = 1, LIVE = 2;           // pointer roles
    // Hold state. One finger: NONE -> MENU (open) or OFF (moved). Two fingers: NONE -> ON (mute arming) or OFF.
    private static final int ARM_NONE = 0, ARM_ON = 1, ARM_OFF = 2, ARM_MENU = 3;
    private static final int AXIS_NONE = 0, AXIS_X = 1, AXIS_Y = 2;

    private static final class Ptr {
        final int id;
        int role;
        float x, y;
        final float x0, y0;
        int backSide;      // edge back candidate: +1 = inward is +x (left edge), -1 = right edge, 0 = none

        Ptr(int id, float x, float y) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.x0 = x;
            this.y0 = y;
        }
    }

    private final Listener listener;
    private final ArrayList<Ptr> ptrs = new ArrayList<>();
    private GestureConfig pending;
    private int pendingTurns;
    private float widthMm;
    private boolean enabled = true;

    // Per gesture (set at gesture start)
    private GestureConfig c;
    private int turns;
    private float cos = 1f, sin = 0f;
    private int mode = IDLE;
    private Ptr p1, p2;
    private long t0;
    private float sx, sy;          // logical start: finger (one) or centroid (two)
    private float maxDist;
    private boolean emitted;
    private int arm;
    private int axis;
    private int lockSign;
    private float ratchet;
    private boolean settingsDone;
    private float qx, qy;          // repeat: where the finger settled, and when
    private long qt;
    private long nextRepeat;
    private boolean repeating;
    private float mox, moy;        // menu: logical point where it opened, and the max distance from it
    private float menuMax;
    private float knobAcc, knobRatchet; // knob: twist since MENU_OPEN, and the last step position
    private boolean knobMode;
    private float soloX, soloY;    // two fingers: the finger left after its partner lifted
    private boolean tapSpoiled;

    // Scratch for toLogical()
    private float lx, ly;

    public GestureEngine(GestureConfig config, Listener listener) {
        this.pending = config.copy();
        this.c = pending;
        this.listener = listener;
    }

    /** Takes effect at the next touch start. The config is copied. */
    public void setConfig(GestureConfig config) {
        pending = config.copy();
    }

    /** Height is not used (only the side edges reject grip). widthMm <= 0 disables the right edge test. */
    public void setSurface(float widthMm, float heightMm) {
        this.widthMm = widthMm;
    }

    /** 0..3, the frame turns clockwise by 90 degrees per turn. Takes effect at the next touch start. */
    public void setQuarterTurns(int turns) {
        pendingTurns = ((turns % 4) + 4) % 4;
    }

    public void setEnabled(boolean enabled) {
        if (!enabled && this.enabled) {
            for (Ptr p : ptrs) {
                if (p.role == LIVE) p.role = STALE;
                p.backSide = 0;
            }
            mode = IDLE;
            p1 = p2 = null;
        }
        this.enabled = enabled;
    }

    public boolean isActive() {
        return !ptrs.isEmpty();
    }

    public void down(int pointerId, float xMm, float yMm, float sizeMm, long timeMs) {
        Ptr old = find(pointerId);
        if (old != null) ptrs.remove(old);
        Ptr p = new Ptr(pointerId, xMm, yMm);
        ptrs.add(p);
        if (!enabled) {
            p.role = STALE;
            return;
        }
        if (mode == IDLE) startConfig();
        advance(timeMs);
        boolean left = xMm < c.edgeRejectMm;
        if (left || (widthMm > 0 && xMm > widthMm - c.edgeRejectMm)) {
            p.role = EDGE;
            if (c.edgeBack && mode == IDLE && sizeMm <= c.palmMm) p.backSide = left ? 1 : -1;
            return;
        }
        p.role = LIVE;
        switch (mode) {
            case IDLE:
                if (sizeMm > c.palmMm) reject();
                else startOne(p, timeMs);
                break;
            case ONE:
                if (sizeMm > c.palmMm || liveCount() >= 3) reject();
                else if (timeMs - t0 <= c.twoFingerJoinMs && maxDist <= c.tapMaxMm + EPS && !emitted
                        && axis == AXIS_NONE && arm == ARM_NONE) startTwo(p);
                // else: late or after movement = grip, ignored
                break;
            case TWO:
                if (sizeMm > c.palmMm || liveCount() >= 3) reject();
                break;
            default:
                break; // DONE, REJECTED: ignored until the gesture ends
        }
    }

    public void move(int pointerId, float xMm, float yMm, float sizeMm, long timeMs) {
        Ptr p = find(pointerId);
        if (p == null) return;
        if (!enabled || p.role != LIVE) {
            p.x = xMm;
            p.y = yMm;
            if (enabled && p.backSide != 0) edgeBack(p, sizeMm);
            return;
        }
        advance(timeMs);
        p.x = xMm;
        p.y = yMm;
        if (mode != ONE && mode != TWO) return;
        if (sizeMm > c.palmMm) {
            reject();
            return;
        }
        if (mode == ONE && p == p1) moveOne(timeMs);
        else if (mode == TWO && (p == p1 || p == p2)) moveTwo(p);
    }

    public void up(int pointerId, long timeMs) {
        Ptr p = find(pointerId);
        if (p == null) return;
        ptrs.remove(p);
        if (!enabled || p.role != LIVE) return;
        advance(timeMs);
        switch (mode) {
            case ONE:
                if (p != p1) return;
                if (arm == ARM_MENU) {
                    if (!knobMode && axis == AXIS_NONE && menuMax < c.menuSelectMm - EPS) action(Action.PLAY_PAUSE, false);
                } else if (!emitted && axis == AXIS_NONE && arm == ARM_NONE
                        && timeMs - t0 < c.tapMaxMs && maxDist < c.tapMaxMm) action(Action.OK, false);
                endGesture();
                break;
            case TWO:
                if (p != p1 && p != p2) return;
                if (p1 != null && p2 != null) {
                    // First of the two lifts: no hold any more, the other finger only matters for the tap
                    if (arm == ARM_ON) cue(Cue.ARM_CANCELLED);
                    arm = ARM_OFF;
                    Ptr other = p == p1 ? p2 : p1;
                    toLogical(other.x, other.y);
                    soloX = lx;
                    soloY = ly;
                }
                if (p == p1) p1 = null;
                else p2 = null;
                if (p1 == null && p2 == null) {
                    if (!emitted && axis == AXIS_NONE && !tapSpoiled && arm != ARM_ON
                            && timeMs - t0 < c.twoTapMaxMs && maxDist < c.twoTapMaxMm) {
                        action(Action.PLAY_PAUSE, false);
                    }
                    endGesture();
                }
                break;
            case DONE:
                if (p == p1) p1 = null;
                if (p == p2) p2 = null;
                if (p1 == null && p2 == null) endGesture();
                break;
            case REJECTED:
                if (liveCount() == 0) endGesture();
                break;
            default:
                break;
        }
    }

    /** System cancel: all pointers are gone. Gives ARM_CANCELLED if an arm was active, nothing else. */
    public void cancel(long timeMs) {
        if ((mode == ONE || mode == TWO) && enabled && arm == ARM_ON) cue(Cue.ARM_CANCELLED);
        ptrs.clear();
        mode = IDLE;
        p1 = p2 = null;
    }

    /** Gyroscope rotation delta in degrees, + = turn right. Only used while the thumb menu is open. */
    public void twist(float deltaDeg, long timeMs) {
        if (!enabled || mode != ONE) return;
        advance(timeMs);
        if (mode != ONE || arm != ARM_MENU || axis != AXIS_NONE || !c.knob) return;
        knobAcc += deltaDeg;
        float size = c.knobStepDeg;
        while (knobAcc - knobRatchet >= size - EPS) {
            knobRatchet += size;
            knobMode = true;
            action(Action.KNOB_RIGHT, false);
        }
        while (knobRatchet - knobAcc >= size - EPS) {
            knobRatchet -= size;
            knobMode = true;
            action(Action.KNOB_LEFT, false);
        }
    }

    public void tick(long timeMs) {
        if (enabled) advance(timeMs);
    }

    // ---- gesture state ----

    private void startConfig() {
        c = pending;
        turns = pendingTurns;
        double b = Math.toRadians(c.biasDeg);
        cos = c.biasDeg == 0f ? 1f : (float) Math.cos(b);
        sin = c.biasDeg == 0f ? 0f : (float) Math.sin(b);
    }

    private void resetGesture() {
        p1 = p2 = null;
        maxDist = 0f;
        emitted = false;
        arm = ARM_NONE;
        axis = AXIS_NONE;
        lockSign = 0;
        ratchet = 0f;
        settingsDone = false;
        repeating = false;
        tapSpoiled = false;
    }

    private void startOne(Ptr p, long t) {
        resetGesture();
        mode = ONE;
        p1 = p;
        t0 = t;
        toLogical(p.x, p.y);
        sx = lx;
        sy = ly;
    }

    private void startTwo(Ptr p) {
        mode = TWO;
        p2 = p;
        maxDist = 0f;
        centroid();
        sx = lx;
        sy = ly;
    }

    private void reject() {
        cue(Cue.REJECTED);
        mode = REJECTED;
        p1 = p2 = null;
    }

    private void endGesture() {
        for (Ptr q : ptrs) if (q.role == LIVE) q.role = STALE;
        mode = IDLE;
        p1 = p2 = null;
    }

    /** Time-based rules: Home and Mute holds, auto-repeat. */
    private void advance(long t) {
        if (mode == ONE) {
            if (arm == ARM_NONE && maxDist <= c.holdSlopMm && t - t0 >= c.menuArmMs) {
                arm = ARM_MENU;
                toLogical(p1.x, p1.y);
                mox = lx;
                moy = ly;
                menuMax = 0f;
                knobAcc = knobRatchet = 0f;
                knobMode = false;
                cue(Cue.MENU_OPEN);
            } else if (arm != ARM_MENU && axis != AXIS_NONE && t - qt >= c.repeatDelayMs) {
                repeat(t);
            }
        } else if (mode == TWO && p1 != null && p2 != null) {
            if (arm != ARM_OFF && !emitted && axis == AXIS_NONE && maxDist <= c.holdSlopMm) {
                if (arm == ARM_NONE && t - t0 >= c.muteArmMs) {
                    arm = ARM_ON;
                    cue(Cue.MUTE_ARMING);
                }
                if (arm == ARM_ON && t - t0 >= c.muteHoldMs) {
                    mode = DONE;
                    action(Action.MUTE, false);
                }
            }
        }
    }

    private void repeat(long t) {
        toLogical(p1.x, p1.y);
        float along = axis == AXIS_X ? lx - sx : ly - sy;
        int sign = along >= c.firstStepMm - EPS ? 1 : along <= -c.firstStepMm + EPS ? -1 : 0;
        if (sign == 0 || (!c.reverseSteps && sign != lockSign)) return;
        if (!repeating) {
            repeating = true;
            nextRepeat = qt + c.repeatDelayMs;
        }
        if (t >= nextRepeat) {
            step(sign, true);
            nextRepeat += c.repeatIntervalMs;
            if (nextRepeat <= t) nextRepeat = t + c.repeatIntervalMs; // late tick: no burst
        }
    }

    private void moveOne(long t) {
        if (arm == ARM_MENU) {
            moveMenu();
            return;
        }
        toLogical(p1.x, p1.y);
        float cx = lx, cy = ly;
        float dx = cx - sx, dy = cy - sy;
        float d = (float) Math.hypot(dx, dy);
        trackHold(d);
        if (axis == AXIS_NONE) {
            if (d < c.firstStepMm - EPS) return;
            if (!clearAxis(dx, dy) && d < c.axisDecideMaxMm - EPS) return; // near 45 degrees: wait
            float along = lock(dx, dy);
            ratchet = lockSign * Math.min(Math.abs(along), c.firstStepMm);
            step(lockSign, false);
            runRatchet(along, c.stepMm, c.reverseSteps);
            settle(cx, cy, t);
        } else {
            runRatchet(axis == AXIS_X ? dx : dy, c.stepMm, c.reverseSteps);
            if (Math.hypot(cx - qx, cy - qy) > c.holdStillMm) settle(cx, cy, t);
        }
    }

    private void moveMenu() {
        toLogical(p1.x, p1.y);
        float dx = lx - mox, dy = ly - moy;
        float d = (float) Math.hypot(dx, dy);
        if (d > menuMax) menuMax = d;
        if (knobMode) return; // slides select nothing after a knob step
        if (axis != AXIS_NONE) {
            runRatchet(dy, c.twoStepMm, false); // only the INPUT branch stays in the menu
            return;
        }
        if (d < c.menuSelectMm - EPS || (!clearAxis(dx, dy) && d < c.axisDecideMaxMm - EPS)) return;
        float along = lock(dx, dy);
        if (axis == AXIS_Y && lockSign > 0) {
            ratchet = Math.min(along, c.menuSelectMm);
            step(1, false);
            runRatchet(along, c.twoStepMm, false);
            return;
        }
        mode = DONE;
        action(axis == AXIS_Y ? Action.HOME : lockSign < 0 ? Action.MUTE : Action.SETTINGS, false);
    }

    private void edgeBack(Ptr p, float sizeMm) {
        if (sizeMm > c.palmMm || ptrs.size() > edgeCount()) {
            p.backSide = 0; // palm, or another finger is down: plain grip from now on
            return;
        }
        float in = p.backSide * (p.x - p.x0), dy = Math.abs(p.y - p.y0);
        if (in >= c.edgeBackMm - EPS && in >= c.axisRatio * dy - EPS) {
            p.backSide = 0;
            listener.onAction(Action.BACK, false);
        }
    }

    private int edgeCount() {
        int n = 0;
        for (Ptr q : ptrs) if (q.role == EDGE) n++;
        return n;
    }

    private boolean clearAxis(float dx, float dy) {
        float ax = Math.abs(dx), ay = Math.abs(dy);
        float mx = Math.max(ax, ay), mn = Math.min(ax, ay);
        return mn <= 0f || mx / mn >= c.axisRatio - EPS;
    }

    private void moveTwo(Ptr moved) {
        if (p1 == null || p2 == null) {
            // Partner gone: no new actions, but a moving finger spoils the tap
            toLogical(moved.x, moved.y);
            if (Math.hypot(lx - soloX, ly - soloY) >= c.twoTapMaxMm) tapSpoiled = true;
            return;
        }
        centroid();
        float dx = lx - sx, dy = ly - sy;
        float d = (float) Math.hypot(dx, dy);
        trackHold(d);
        if (axis == AXIS_NONE) {
            if (d < c.twoStepMm - EPS) return;
            float along = lock(dx, dy);
            if (axis == AXIS_Y && lockSign > 0) {
                settingsDone = true;
                step(1, false);
                return;
            }
            ratchet = lockSign * Math.min(Math.abs(along), c.twoStepMm);
            step(lockSign, false);
            runRatchet(along, c.twoStepMm, axis == AXIS_X && c.reverseSteps);
        } else if (!settingsDone) {
            runRatchet(axis == AXIS_X ? dx : dy, c.twoStepMm, axis == AXIS_X && c.reverseSteps);
        }
    }

    private void trackHold(float d) {
        if (d > maxDist) maxDist = d;
        if (maxDist > c.holdSlopMm && (arm == ARM_NONE || arm == ARM_ON)) {
            if (arm == ARM_ON) cue(Cue.ARM_CANCELLED);
            arm = ARM_OFF;
        }
    }

    /** Locks the dominant axis and returns the offset along it. */
    private float lock(float dx, float dy) {
        axis = Math.abs(dx) > Math.abs(dy) ? AXIS_X : AXIS_Y;
        float along = axis == AXIS_X ? dx : dy;
        lockSign = along > 0 ? 1 : -1;
        return along;
    }

    /** Jog wheel: one step per `size` away from the last step. Without reverse, only the lock direction. */
    private void runRatchet(float along, float size, boolean reverse) {
        if (reverse || lockSign > 0) {
            while (along - ratchet >= size - EPS) {
                ratchet += size;
                step(1, false);
            }
        }
        if (reverse || lockSign < 0) {
            while (ratchet - along >= size - EPS) {
                ratchet -= size;
                step(-1, false);
            }
        }
    }

    private void settle(float x, float y, long t) {
        qx = x;
        qy = y;
        qt = t;
        repeating = false;
    }

    private void step(int sign, boolean repeat) {
        Action a;
        if (arm == ARM_MENU) {
            a = Action.INPUT;
        } else if (mode == TWO) {
            if (axis == AXIS_X) a = sign > 0 ? Action.FAST_FORWARD : Action.REWIND;
            else a = sign > 0 ? Action.SETTINGS : Action.INPUT;
        } else if (axis == AXIS_X) {
            a = sign > 0 ? Action.RIGHT : Action.LEFT;
        } else {
            a = sign > 0 ? Action.DOWN : Action.UP;
        }
        action(a, repeat);
    }

    private void action(Action a, boolean repeat) {
        emitted = true;
        listener.onAction(a, repeat);
    }

    private void cue(Cue q) {
        listener.onCue(q);
    }

    // ---- geometry ----

    /** Screen mm -> logical frame (turned clockwise by 90 * turns + biasDeg), result in lx, ly. */
    private void toLogical(float x, float y) {
        float ax, ay;
        switch (turns) {
            case 1: ax = y; ay = -x; break;
            case 2: ax = -x; ay = -y; break;
            case 3: ax = -y; ay = x; break;
            default: ax = x; ay = y; break;
        }
        lx = ax * cos + ay * sin;
        ly = -ax * sin + ay * cos;
    }

    private void centroid() {
        toLogical((p1.x + p2.x) * 0.5f, (p1.y + p2.y) * 0.5f);
    }

    private Ptr find(int id) {
        for (Ptr p : ptrs) if (p.id == id) return p;
        return null;
    }

    private int liveCount() {
        int n = 0;
        for (Ptr p : ptrs) if (p.role == LIVE) n++;
        return n;
    }
}
