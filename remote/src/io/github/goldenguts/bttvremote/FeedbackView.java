package io.github.goldenguts.bttvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The gesture surface. It draws quiet feedback: thin gray lines, one dim accent, short fades.
 * Nothing blinks, so it stays out of the way while the eyes are on the TV.
 */
final class FeedbackView extends View {

    static final int UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3;

    private static final int ACCENT = Color.rgb(138, 180, 248);
    private static final int ERROR = Color.rgb(200, 90, 80);
    private String[] menuLabels = {"Home", "Settings", "Input", "Mute"};   // by direction UP..LEFT
    private String centerLabel = "Play/Pause";
    private boolean quiet;

    private final float dp;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Path path = new Path();

    /** Short-lived marks: a step arrow, a tap ripple, an error ring. */
    private static final class Mark {
        final int kind;   // 0 arrow, 1 ripple, 2 error, 3 back
        final float x, y;
        final int dir;
        final long start;
        final long life;

        Mark(int kind, float x, float y, int dir, long life) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.dir = dir;
            this.start = SystemClock.uptimeMillis();
            this.life = life;
        }
    }

    private final List<Mark> marks = new ArrayList<>();

    // the current touch
    private boolean down;
    private float startX, startY, x, y;
    private long downAt;
    private boolean holdPossible;
    private long holdMs = 400;
    private float slopPx;

    // thumb menu
    private boolean menuOpen;
    private float menuX, menuY;
    private int menuPick = -1;   // -1 none, 0..3 direction, 4 center
    private long menuPickAt;
    private long menuClosedAt;
    private int knobSteps;
    private boolean knobUsed;

    private int quarterTurns;

    FeedbackView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * dp);
        setBackgroundColor(Color.BLACK);
    }

    void setHold(long menuArmMs, float slopMm, float pxPerMm) {
        holdMs = menuArmMs;
        slopPx = slopMm * pxPerMm;
    }

    void setMenuLabels(String[] byDir, String center) {
        menuLabels = byDir;
        centerLabel = center;
    }

    /** Animations off: only the touch point and the menu labels, no arrows or ripples. */
    void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    /** Gravity turn, so arrows point where the step went on the screen. */
    void setQuarterTurns(int turns) {
        quarterTurns = turns;
    }

    // ---- input from the activity ----

    void touchDown(float px, float py) {
        down = true;
        startX = x = px;
        startY = y = py;
        downAt = SystemClock.uptimeMillis();
        holdPossible = true;
        menuOpen = false;
        menuPick = -1;
        knobSteps = 0;
        knobUsed = false;
        animate(true);
    }

    void touchMove(float px, float py) {
        x = px;
        y = py;
        if (Math.hypot(px - startX, py - startY) > slopPx) holdPossible = false;
    }

    void touchUp() {
        down = false;
        holdPossible = false;
        if (menuOpen) {
            menuClosedAt = SystemClock.uptimeMillis();
            menuOpen = false;
        }
        animate(true);
    }

    /** A D-pad or seek step in a logical direction. */
    void step(int dir) {
        holdPossible = false;
        marks.add(new Mark(0, x, y, screenDir(dir), 380));
        animate(true);
    }

    void tap() {
        marks.add(new Mark(1, x, y, 0, 260));
        animate(true);
    }

    void back(boolean fromRight) {
        marks.add(new Mark(3, fromRight ? getWidth() - 24 * dp : 24 * dp, getHeight() / 2f, fromRight ? LEFT : RIGHT, 420));
        animate(true);
    }

    void error() {
        marks.add(new Mark(2, x, y, 0, 600));
        animate(true);
    }

    void menuOpened() {
        menuOpen = true;
        holdPossible = false;
        menuX = x;
        menuY = y;
        animate(true);
    }

    /** dir 0..3 = UP..LEFT (logical), 4 = center (Play/Pause). */
    void menuPicked(int dir) {
        menuPick = dir == 4 ? 4 : screenDir(dir);
        menuPickAt = SystemClock.uptimeMillis();
        if (!down) menuClosedAt = menuPickAt;
        animate(true);
    }

    void knob(int delta) {
        knobUsed = true;
        knobSteps += delta;
        animate(true);
    }

    private int screenDir(int dir) {
        return (dir + quarterTurns) % 4;
    }

    // ---- drawing ----

    private boolean animating;

    private void animate(boolean start) {
        if (start && !animating) {
            animating = true;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean busy = down;

        if (down) {
            // the finger and the start point
            line.setStrokeWidth(1.5f * dp);
            line.setColor(gray(70));
            c.drawCircle(startX, startY, 16 * dp, line);
            fill.setColor(gray(90));
            c.drawCircle(x, y, 5 * dp, fill);
            if (Math.hypot(x - startX, y - startY) > 16 * dp) {
                line.setColor(gray(40));
                c.drawLine(startX, startY, x, y, line);
            }
            // hold timer: the ring fills until the menu opens
            if (holdPossible && !menuOpen) {
                float p = Math.min(1f, (now - downAt) / (float) holdMs);
                if (p > 0.15f) {
                    line.setColor(alpha(ACCENT, 150));
                    line.setStrokeWidth(2.5f * dp);
                    arc.set(startX - 16 * dp, startY - 16 * dp, startX + 16 * dp, startY + 16 * dp);
                    c.drawArc(arc, -90, 360 * p, false, line);
                }
            }
        }

        // thumb menu labels, and a short fade after a pick
        long sinceClose = menuClosedAt > 0 ? now - menuClosedAt : 0;
        boolean menuVisible = menuOpen || (menuPick >= 0 && sinceClose < 450);
        if (menuVisible) {
            float fade = menuOpen ? 1f : 1f - sinceClose / 450f;
            drawMenu(c, fade);
            busy = true;
        }

        if (quiet) marks.removeIf(m -> m.kind != 2);   // keep "not sent"
        for (Iterator<Mark> it = marks.iterator(); it.hasNext(); ) {
            Mark m = it.next();
            float t = (now - m.start) / (float) m.life;
            if (t >= 1f) {
                it.remove();
                continue;
            }
            busy = true;
            drawMark(c, m, t);
        }

        if (busy) {
            postInvalidateOnAnimation();
        } else {
            animating = false;
        }
    }

    private void drawMenu(Canvas c, float fade) {
        float r = 72 * dp;
        line.setStrokeWidth(1.5f * dp);
        line.setColor(gray((int) (45 * fade)));
        c.drawCircle(menuX, menuY, r * 0.55f, line);
        for (int d = 0; d < 4; d++) {
            int sd = screenDir(d);
            float[] v = vector(sd);
            float lx = clamp(menuX + v[0] * r, 36 * dp, getWidth() - 36 * dp);
            float ly = clamp(menuY + v[1] * r, 24 * dp, getHeight() - 12 * dp);
            boolean picked = menuPick == sd;
            boolean dim = menuPick >= 0 && !picked;
            int a = (int) ((picked ? 255 : dim ? 50 : 150) * fade);
            text.setColor(picked ? alpha(ACCENT, a) : gray(a));
            text.setTextSize((picked ? 15 : 13) * dp);
            c.drawText(menuLabels[d], lx, ly + 5 * dp, text);
        }
        // center: Play/Pause, or the knob dial after a twist
        if (knobUsed) {
            line.setColor(alpha(ACCENT, (int) (160 * fade)));
            line.setStrokeWidth(2 * dp);
            float kr = 26 * dp;
            arc.set(menuX - kr, menuY - kr, menuX + kr, menuY + kr);
            c.drawArc(arc, -90 + knobSteps * 15 - 20, 40, false, line);
            text.setColor(gray((int) (150 * fade)));
            text.setTextSize(12 * dp);
            c.drawText("seek " + (knobSteps > 0 ? "+" : "") + knobSteps, menuX, menuY + 46 * dp, text);
        } else {
            boolean picked = menuPick == 4;
            text.setColor(picked ? alpha(ACCENT, (int) (255 * fade)) : gray((int) ((menuPick >= 0 ? 50 : 130) * fade)));
            text.setTextSize(12 * dp);
            c.drawText("lift: " + centerLabel, menuX, menuY + 46 * dp, text);
        }
    }

    private void drawMark(Canvas c, Mark m, float t) {
        int a = (int) (255 * (1 - t));
        switch (m.kind) {
            case 0: {   // step arrow: a chevron that drifts out and fades
                float[] v = vector(m.dir);
                float dist = (34 + 14 * t) * dp;
                chevron(c, m.x + v[0] * dist, m.y + v[1] * dist, v, 9 * dp, alpha(ACCENT, (int) (a * 0.7f)));
                break;
            }
            case 1: {   // tap ripple
                line.setStrokeWidth(2 * dp);
                line.setColor(alpha(ACCENT, (int) (a * 0.6f)));
                c.drawCircle(m.x, m.y, (10 + 30 * t) * dp, line);
                break;
            }
            case 2: {   // not sent
                line.setStrokeWidth(2.5f * dp);
                line.setColor(alpha(ERROR, (int) (a * 0.8f)));
                c.drawCircle(m.x, m.y, 22 * dp, line);
                text.setColor(alpha(ERROR, a));
                text.setTextSize(13 * dp);
                c.drawText("not sent", m.x, m.y - 32 * dp, text);
                break;
            }
            case 3: {   // back: a chevron at the edge
                float[] v = vector(m.dir == LEFT ? RIGHT : LEFT);   // points back toward the edge
                chevron(c, m.x, m.y, v, 14 * dp, alpha(ACCENT, (int) (a * 0.7f)));
                break;
            }
        }
    }

    private void chevron(Canvas c, float cx, float cy, float[] v, float size, int color) {
        // tip in direction v, two arms back
        float px = -v[1], py = v[0];
        path.reset();
        path.moveTo(cx - v[0] * size + px * size, cy - v[1] * size + py * size);
        path.lineTo(cx, cy);
        path.lineTo(cx - v[0] * size - px * size, cy - v[1] * size - py * size);
        line.setStrokeWidth(2.5f * dp);
        line.setColor(color);
        c.drawPath(path, line);
    }

    private static float[] vector(int dir) {
        switch (dir) {
            case UP: return new float[]{0, -1};
            case RIGHT: return new float[]{1, 0};
            case DOWN: return new float[]{0, 1};
            default: return new float[]{-1, 0};
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int gray(int a) {
        return Color.argb(Math.max(0, Math.min(255, a)), 220, 220, 225);
    }

    private static int alpha(int color, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)), Color.red(color), Color.green(color), Color.blue(color));
    }
}
