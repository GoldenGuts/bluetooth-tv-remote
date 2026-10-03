package io.github.goldenguts.bttvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;

import io.github.goldenguts.bttvremote.gesture.GestureConfig;

/**
 * The "ghost thumb": a quiet loop that shows the gesture of the current lesson. It takes no touches.
 * It hides while a finger is down and comes back 1.5 s after the lift. Distances are real mm.
 */
final class DemoView extends View {

    static final int NONE = -1, TAP = 0, DRAG = 1, REPEAT = 2, EDGE = 3, LIFT = 4, SLIDE = 5, TWIST = 6;

    private static final int ACCENT = Color.rgb(138, 180, 248);
    private static final long PAUSE_MS = 600;
    private static final long OPEN = 1050;   // the hold ring is full, the "thud"

    private final GestureConfig config;
    private final float dp, mm;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Path path = new Path();

    private int kind = NONE, dir, steps;
    private String label = "";
    private long loopStart, lastFrame;
    private float fade;   // the whole demo, 0..1
    private boolean wanted, fingerDown, running;

    DemoView(Context c, GestureConfig config) {
        super(c);
        this.config = config;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        dp = dm.density;
        mm = (dm.xdpi + dm.ydpi) / 2f / 25.4f;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** dir: FeedbackView.UP..LEFT. steps: for DRAG. label: the menu name for LIFT and SLIDE. */
    void play(int kind, int dir, int steps, String label) {
        this.kind = kind;
        this.dir = dir;
        this.steps = Math.max(1, steps);
        this.label = label == null ? "" : label;
        loopStart = SystemClock.uptimeMillis();
        wanted = kind != NONE && !fingerDown;
        kick();
    }

    void fingerDown() {
        fingerDown = true;
        removeCallbacks(reveal);
        wanted = false;
        kick();
    }

    void fingerUp() {
        fingerDown = false;
        removeCallbacks(reveal);
        postDelayed(reveal, 1500);
    }

    private final Runnable reveal = () -> {
        if (fingerDown || kind == NONE) return;
        if (fade == 0f) loopStart = SystemClock.uptimeMillis();
        wanted = true;
        kick();
    };

    private void kick() {
        if (running) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(reveal);
        running = false;
        super.onDetachedFromWindow();
    }

    private long duration() {
        switch (kind) {
            case TAP: return 1800;
            case DRAG: return 1500 + 400L * steps;
            case EDGE: return 1900;
            case LIFT: return 2500;
            case TWIST: return 2900;
            default: return 2700;   // REPEAT, SLIDE
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(50, now - lastFrame);
        lastFrame = now;
        fade = wanted ? Math.min(1f, fade + dt / 300f) : Math.max(0f, fade - dt / 150f);
        if (fade > 0f && kind != NONE) {
            long d = duration();
            long t = (now - loopStart) % (d + PAUSE_MS);
            if (t < d) draw(c, t, d);
        }
        if (fade > 0f || wanted) postInvalidateOnAnimation();
        else running = false;
    }

    private void draw(Canvas c, long t, long d) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float a = fade * seg(t, 0, 300) * (1f - seg(t, d - 400, d - 100));
        float[] v = vector(dir);
        switch (kind) {
            case TAP: {
                float press = seg(t, 600, 720) - seg(t, 800, 950);
                thumb(c, cx, cy, 1f - 0.15f * press, a);
                float r = seg(t, 800, 1350);
                if (r > 0f && r < 1f) {
                    line.setStrokeWidth(2 * dp);
                    line.setColor(accent(fade * 110 * (1 - r)));
                    c.drawCircle(cx, cy, (20 + 30 * r) * dp, line);
                }
                break;
            }
            case DRAG: {
                float k = shrink(v, config.firstStepMm + (steps - 1) * config.stepMm, w, h);
                float total = (config.firstStepMm + (steps - 1) * config.stepMm) * mm * k;
                float sx = cx - v[0] * total / 2, sy = cy - v[1] * total / 2;
                float f = ease(seg(t, 400, 600 + 400L * steps));
                float x = sx + v[0] * total * f, y = sy + v[1] * total * f;
                trail(c, sx, sy, x, y, a);
                for (int i = 0; i < steps; i++) {
                    float at = (config.firstStepMm + i * config.stepMm) * mm * k;
                    if (total * f < at - 0.5f) break;
                    tick(c, sx + v[0] * at, sy + v[1] * at, v, a);
                    if (steps > 1) {
                        text.setTextSize(11 * dp);
                        text.setColor(gray(a * 120));
                        c.drawText(String.valueOf(i + 1), sx + v[0] * at - v[1] * 22 * dp,
                                sy + v[1] * at + v[0] * 22 * dp + 4 * dp, text);
                    }
                }
                thumb(c, x, y, 1f, a);
                break;
            }
            case REPEAT: {
                float k = shrink(v, config.firstStepMm + 3, w, h);
                float total = (config.firstStepMm + 3) * mm * k;
                float sx = cx - v[0] * total / 2, sy = cy - v[1] * total / 2;
                float f = ease(seg(t, 400, 900));
                float x = sx + v[0] * total * f, y = sy + v[1] * total * f;
                trail(c, sx, sy, x, y, a);
                float first = config.firstStepMm * mm * k;
                if (total * f >= first - 0.5f) tick(c, sx + v[0] * first, sy + v[1] * first, v, a);
                // at rest: one drifting chevron per repeat
                long interval = Math.max(180, config.repeatIntervalMs);
                for (long tk = 900 + config.repeatDelayMs; tk <= t && tk < d - 500; tk += interval) {
                    float p = (t - tk) / 400f;
                    if (p >= 1f) continue;
                    float dist = (28 + 16 * p) * dp;
                    chevron(c, x + v[0] * dist, y + v[1] * dist, v, 7 * dp, accent(a * 110 * (1 - p)));
                }
                thumb(c, x, y, 1f, a);
                break;
            }
            case EDGE: {
                float travel = Math.min(22 * mm, w * 0.5f);
                float f = 1f - (1f - seg(t, 300, 1000)) * (1f - seg(t, 300, 1000));
                float x = w - 4 * dp - travel * f;
                line.setStrokeWidth(3 * dp);
                line.setColor(gray(a * 40));
                c.drawLine(w - 1.5f * dp, cy - 40 * dp, w - 1.5f * dp, cy + 40 * dp, line);
                float shown = seg(t, 650, 850) * (1f - seg(t, 1300, 1600));
                chevron(c, w - travel - 30 * dp, cy, vector(FeedbackView.LEFT), 10 * dp, accent(a * 120 * shown));
                thumb(c, x, cy, 1f, a);
                break;
            }
            case LIFT: {
                float press = seg(t, 300, 420) - seg(t, 1700, 1850);
                float lift = seg(t, 1700, 2100);
                thumb(c, cx, cy, 1f - 0.12f * press + 0.1f * lift, a * (1f - lift));
                ring(c, cx, cy, t, a * (1f - lift));
                if (t >= OPEN) {
                    boolean picked = t >= 1700;
                    text.setTextSize(13 * dp);
                    text.setColor(picked ? accent(fade * 200 * (1f - seg(t, 2000, 2400))) : gray(a * 110));
                    c.drawText("lift: " + label, cx, cy + 52 * dp, text);
                }
                break;
            }
            case SLIDE: {
                float dist = config.menuSelectMm * 1.4f * mm;
                float f = ease(seg(t, 1250, 1650));
                float x = cx + v[0] * dist * f, y = cy + v[1] * dist * f;
                trail(c, cx, cy, x, y, a);
                thumb(c, x, y, 1f - 0.12f * seg(t, 300, 420), a);
                ring(c, cx, cy, t, a * (1f - seg(t, 1250, 1450)));
                if (t >= OPEN) {
                    float lx = clamp(cx + v[0] * 72 * dp, 40 * dp, w - 40 * dp);
                    float ly = clamp(cy + v[1] * 72 * dp, 24 * dp, h - 12 * dp);
                    boolean picked = f >= 1f;
                    text.setTextSize((picked ? 15 : 13) * dp);
                    text.setColor(picked ? accent(a * 220) : gray(a * 100 * seg(t, OPEN, OPEN + 200)));
                    c.drawText(label, lx, ly + 5 * dp, text);
                }
                break;
            }
            case TWIST: {
                thumb(c, cx, cy, 1f - 0.12f * seg(t, 300, 420), a);
                ring(c, cx, cy, t, a * (1f - seg(t, 1200, 1400)));
                if (t < OPEN) break;
                float na = a * seg(t, OPEN, OPEN + 200);
                float ang = 45f * ease(seg(t, 1300, 2200));   // three notches of 15°
                line.setStrokeWidth(2 * dp);
                for (int k = -2; k <= 5; k++) {
                    double r = Math.toRadians(-90 + k * 15);
                    boolean passed = k >= 1 && k * 15 <= ang + 0.5f;
                    line.setColor(passed ? accent(na * 150) : gray(na * 50));
                    float cos = (float) Math.cos(r), sin = (float) Math.sin(r);
                    c.drawLine(cx + cos * 37 * dp, cy + sin * 37 * dp, cx + cos * 43 * dp, cy + sin * 43 * dp, line);
                }
                line.setStrokeWidth(2.5f * dp);
                line.setColor(accent(na * 140));
                arc.set(cx - 30 * dp, cy - 30 * dp, cx + 30 * dp, cy + 30 * dp);
                c.drawArc(arc, -90 + ang - 20, 40, false, line);
                double pr = Math.toRadians(-90 + ang);
                line.setStrokeWidth(2 * dp);
                line.setColor(gray(na * 110));
                c.drawLine(cx, cy, cx + (float) Math.cos(pr) * 12 * dp, cy + (float) Math.sin(pr) * 12 * dp, line);
                break;
            }
        }
    }

    // ---- pieces ----

    private void thumb(Canvas c, float x, float y, float scale, float a) {
        fill.setColor(gray(a * 40));
        c.drawCircle(x, y, 18 * dp * scale, fill);
        line.setStrokeWidth(1.5f * dp);
        line.setColor(gray(a * 80));
        c.drawCircle(x, y, 18 * dp * scale, line);
    }

    /** The hold ring fills, then a soft bump at the "thud". */
    private void ring(Canvas c, float x, float y, long t, float a) {
        float p = seg(t, 420, OPEN);
        if (p <= 0f || a <= 0f) return;
        float bump = (float) Math.sin(Math.PI * seg(t, OPEN, OPEN + 220));
        float r = (26 + 3 * bump) * dp;
        line.setStrokeWidth((2 + 1.5f * bump) * dp);
        line.setColor(accent(a * 130));
        arc.set(x - r, y - r, x + r, y + r);
        c.drawArc(arc, -90, 360 * p, false, line);
    }

    private void trail(Canvas c, float sx, float sy, float x, float y, float a) {
        if (Math.hypot(x - sx, y - sy) < 1) return;
        line.setStrokeWidth(1.5f * dp);
        line.setColor(gray(a * 35));
        c.drawLine(sx, sy, x, y, line);
    }

    /** A short mark across the path: one step. */
    private void tick(Canvas c, float x, float y, float[] v, float a) {
        float px = -v[1] * 9 * dp, py = v[0] * 9 * dp;
        line.setStrokeWidth(2 * dp);
        line.setColor(accent(a * 120));
        c.drawLine(x - px, y - py, x + px, y + py, line);
    }

    private void chevron(Canvas c, float cx, float cy, float[] v, float size, int color) {
        float px = -v[1], py = v[0];
        path.reset();
        path.moveTo(cx - v[0] * size + px * size, cy - v[1] * size + py * size);
        path.lineTo(cx, cy);
        path.lineTo(cx - v[0] * size - px * size, cy - v[1] * size - py * size);
        line.setStrokeWidth(2.5f * dp);
        line.setColor(color);
        c.drawPath(path, line);
    }

    /** Small screens: scale a drag down so it fits in 70% of the view. */
    private float shrink(float[] v, float lengthMm, float w, float h) {
        float span = (v[0] != 0 ? w : h) * 0.7f;
        return Math.min(1f, span / (lengthMm * mm));
    }

    private static float seg(long t, long from, long to) {
        return clamp((t - from) / (float) (to - from), 0f, 1f);
    }

    private static float ease(float f) {
        return f * f * (3 - 2 * f);
    }

    private static float[] vector(int dir) {
        switch (dir) {
            case FeedbackView.UP: return new float[]{0, -1};
            case FeedbackView.RIGHT: return new float[]{1, 0};
            case FeedbackView.DOWN: return new float[]{0, 1};
            default: return new float[]{-1, 0};
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int gray(float a) {
        return Color.argb(Math.max(0, Math.min(255, Math.round(a))), 220, 220, 225);
    }

    private static int accent(float a) {
        return Color.argb(Math.max(0, Math.min(255, Math.round(a))), Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT));
    }
}
