package in.weenja.bttvremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import in.weenja.bttvremote.common.Protocol;
import in.weenja.bttvremote.Haptics.Pattern;
import in.weenja.bttvremote.gesture.GestureConfig;
import in.weenja.bttvremote.gesture.GestureEngine;
import in.weenja.bttvremote.gesture.GestureEngine.Action;

import java.util.ArrayList;
import java.util.List;

/**
 * Lessons for the one-thumb map, one gesture at a time, then free practice.
 * It never talks to Remote Core: nothing is sent. Touch and gyroscope feed the engine like RemoteActivity.
 */
public class TutorialActivity extends Activity implements GestureEngine.Listener, SensorEventListener {

    private static final int ACCENT = Color.rgb(138, 180, 248);
    private static final long TICK_MS = 16;
    private static final int G_TAP = 0, G_RIGHT = 1, G_UP3 = 2, G_REPEAT = 3, G_BACK = 4, G_LIFT = 5, G_SLIDE = 6, G_KNOB = 7;
    private static final String[] WORDS = {"up", "right", "down", "left"};

    private static final class Lesson {
        final int goal, slot;
        final String title, text, hint;

        Lesson(int goal, int slot, String title, String text, String hint) {
            this.goal = goal;
            this.slot = slot;
            this.title = title;
            this.text = text;
            this.hint = hint;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Lesson> lessons = new ArrayList<>();

    private SharedPreferences prefs;
    private GestureConfig config;
    private GestureEngine engine;
    private Haptics haptics;
    private SensorManager sensors;
    private float pxPerMm;
    private long lastGyroNs;

    private TextView stepText, dotsText, titleText, bodyText, statusText, bigLabel, backButton, skipButton;
    private FeedbackView feedback;
    private DemoView demo;
    private View donePanel;

    private int index;
    private boolean practice, advancing;

    // the current touch
    private int primaryId = -1;
    private boolean fingerDown, menuActive, edgeFromRight, stale, wrong, acted;
    private long downAt;
    private int count;
    private String liftHint;
    private final boolean[] slotFired = new boolean[5];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        prefs = getSharedPreferences(Tuning.PREFS, MODE_PRIVATE);
        haptics = new Haptics(getSystemService(Vibrator.class));
        haptics.strength = Tuning.app(prefs, Tuning.HAPTIC_STRENGTH) / 100f;
        sensors = getSystemService(SensorManager.class);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        pxPerMm = (dm.xdpi + dm.ydpi) / 2f / 25.4f;
        config = Tuning.gestureConfig(prefs);
        config.edgeBack = true;
        engine = new GestureEngine(config, this);
        buildLessons();

        setContentView(buildUi());
        String[] labels = new String[4];
        for (int d = 0; d < 4; d++) labels[d] = Tuning.menuName(prefs, d);
        feedback.setMenuLabels(labels, Tuning.menuName(prefs, 4));
        feedback.setHold(config.menuArmMs, config.holdSlopMm, pxPerMm);
        hideSystemBars();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);

        showLesson(state != null ? state.getInt("lesson", 0) : 0);
        if (state != null && state.getBoolean("practice")) startPractice();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("lesson", index);
        out.putBoolean("practice", practice);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sensor gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro != null) sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        lastGyroNs = 0;
    }

    @Override
    protected void onPause() {
        engine.cancel(SystemClock.uptimeMillis());
        haptics.stop();
        sensors.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- lessons ----

    private void buildLessons() {
        boolean menu = config.menuArmMs < 100_000;   // Settings can turn the thumb menu off
        add(G_TAP, 0, "Tap = OK", "Tap anywhere on the black area.", "a quick tap");
        add(G_RIGHT, 0, "Drag = arrows", "Drag right until you feel a tick. Each tick = one step.", "drag right");
        add(G_UP3, 0, "Many steps", "Drag up for 3 ticks in ONE touch. A new touch starts the count again.",
                "drag up 3 ticks in one touch");
        if (config.repeatDelayMs < 100_000) add(G_REPEAT, 0, "Hold = repeat",
                "Drag down one tick, then stop and keep holding. It repeats while you hold.",
                "drag down, then stop and hold");
        add(G_BACK, 0, "Back", "Swipe in from the left or right edge of the screen.", "swipe in from the side edge");
        if (!menu) return;
        String center = Tuning.menuName(prefs, 4);
        if (Tuning.menuAction(prefs, 4) != 0) add(G_LIFT, 4, "Hold, lift = " + center,
                "Hold still. The ring fills while you wait. At the thud, lift.", "hold still until the thud, then lift");
        for (int d = 0; d < 4; d++) {
            if (Tuning.menuAction(prefs, d) == 0) continue;   // "Nothing": no lesson
            add(G_SLIDE, d, "Hold, slide " + WORDS[d] + " = " + Tuning.menuName(prefs, d),
                    "Hold still until the thud, then slide " + WORDS[d] + " without lifting.", "hold, then slide " + WORDS[d]);
        }
        if (config.knob && sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
            String like = Tuning.app(prefs, Tuning.KNOB_AXIS) == 1 ? "a steering wheel" : "a doorknob";
            add(G_KNOB, 0, "Knob", "Hold until the thud, then twist your wrist like " + like + ". One notch per step.",
                    "hold, then twist your wrist");
        }
    }

    private void add(int goal, int slot, String title, String text, String hint) {
        lessons.add(new Lesson(goal, slot, title, text, hint));
    }

    private void showLesson(int i) {
        main.removeCallbacks(ready);
        main.removeCallbacks(next);
        index = Math.max(0, Math.min(lessons.size(), i));
        practice = advancing = false;
        if (fingerDown) stale = true;   // the rest of this touch belongs to the old lesson
        count = 0;
        wrong = false;
        liftHint = null;
        bigLabel.setText("");
        int n = lessons.size();
        SpannableStringBuilder dots = new SpannableStringBuilder();
        for (int k = 0; k < n; k++) {
            int s = dots.length();
            dots.append("● ");
            int color = k < index ? ACCENT : k == index ? Color.WHITE : Color.rgb(60, 60, 64);
            dots.setSpan(new ForegroundColorSpan(color), s, s + 1, 0);
        }
        dotsText.setText(dots);
        backButton.setVisibility(index > 0 ? View.VISIBLE : View.INVISIBLE);
        if (index == n) {
            stepText.setText("Done");
            titleText.setText("You know it all.");
            bodyText.setText("Every gesture works with one thumb, eyes on the TV.");
            statusText.setText("");
            skipButton.setVisibility(View.INVISIBLE);
            donePanel.setVisibility(View.VISIBLE);
            demo.play(DemoView.NONE, 0, 0, null);
            return;
        }
        Lesson l = lessons.get(index);
        stepText.setText("Step " + (index + 1) + " of " + n);
        titleText.setText(l.title);
        bodyText.setText(l.text);
        status("Your turn", Color.GRAY);
        skipButton.setVisibility(View.VISIBLE);
        donePanel.setVisibility(View.GONE);
        switch (l.goal) {
            case G_TAP: demo.play(DemoView.TAP, 0, 1, null); break;
            case G_RIGHT: demo.play(DemoView.DRAG, FeedbackView.RIGHT, 1, null); break;
            case G_UP3: demo.play(DemoView.DRAG, FeedbackView.UP, 3, null); break;
            case G_REPEAT: demo.play(DemoView.REPEAT, FeedbackView.DOWN, 1, null); break;
            case G_BACK: demo.play(DemoView.EDGE, FeedbackView.LEFT, 1, null); break;
            case G_LIFT: demo.play(DemoView.LIFT, 0, 1, Tuning.menuName(prefs, 4)); break;
            case G_SLIDE: demo.play(DemoView.SLIDE, l.slot, 1, Tuning.menuName(prefs, l.slot)); break;
            case G_KNOB: demo.play(DemoView.TWIST, 0, 1, null); break;
        }
    }

    private void startPractice() {
        showLesson(lessons.size());
        practice = true;
        stepText.setText("Practice");
        titleText.setText("Practice");
        bodyText.setText("Every gesture shows its name. Nothing is checked, nothing goes to the TV.");
        donePanel.setVisibility(View.GONE);
    }

    private void success() {
        advancing = true;
        status("✓ Nice", ACCENT);
        main.postDelayed(ready, 180);   // after the action's own vibration
        main.postDelayed(next, 900);
    }

    private final Runnable ready = () -> haptics.play(Pattern.READY);
    private final Runnable next = () -> showLesson(index + 1);

    /** One recognized action in a lesson: right, a step on the way, or wrong. */
    private void check(Action a, boolean repeat) {
        String name = name(a);
        if (practice) {
            big(name);
            return;
        }
        if (advancing || index >= lessons.size()) return;
        Lesson l = lessons.get(index);
        switch (l.goal) {
            case G_TAP:
                if (a == Action.OK) { success(); return; }
                break;
            case G_RIGHT:
                if (a == Action.RIGHT) { success(); return; }
                break;
            case G_UP3:
                if (a == Action.UP || (a == Action.DOWN && count > 0)) {   // dragging back counts down
                    count += a == Action.UP ? 1 : -1;
                    if (count >= 3) { success(); return; }
                    status(count + " of 3", Color.WHITE);
                    liftHint = "That was " + count + (count == 1 ? " step" : " steps") + ". Try again: 3 ticks in one touch.";
                    return;
                }
                break;
            case G_REPEAT:
                if (a == Action.DOWN && repeat) { success(); return; }
                if (a == Action.DOWN || (a == Action.UP && count > 0)) {
                    count++;
                    status("Now stop and keep holding…", Color.WHITE);
                    liftHint = "Steps, but no repeat. Try: stop after the tick and keep holding.";
                    return;
                }
                break;
            case G_BACK:
                if (a == Action.BACK) { success(); return; }
                break;
            case G_LIFT:
            case G_SLIDE:
                if (menuActive && slot(a) == l.slot) { success(); return; }
                break;
            case G_KNOB:
                if (a == Action.KNOB_LEFT || a == Action.KNOB_RIGHT) {
                    if (++count >= 2) { success(); return; }
                    status("1 notch. Keep twisting…", Color.WHITE);
                    liftHint = "That was 1 notch. Try: twist a bit more, 2 notches.";
                    return;
                }
                break;
        }
        wrong = true;
        status("That was " + name + ". Try: " + l.hint + ".", Color.WHITE);
    }

    /** The touch ended without a result: say what was missing. */
    private void touchEnded(long t) {
        fingerDown = false;
        demo.fingerUp();
        if (practice || stale || advancing || wrong || index >= lessons.size()) return;
        Lesson l = lessons.get(index);
        if (liftHint != null) {
            status(liftHint, Color.WHITE);
            return;
        }
        if (acted) return;
        boolean menuLesson = l.goal == G_LIFT || l.goal == G_SLIDE || l.goal == G_KNOB;
        String s;
        if (menuActive) s = l.goal == G_SLIDE ? "Slide a bit further before you lift."
                : l.goal == G_KNOB ? "Twist your wrist while you hold." : "Nothing picked. Try: " + l.hint + ".";
        else if (menuLesson) s = "No thud yet. Keep the thumb still a bit longer.";
        else if (l.goal == G_TAP) s = t - downAt > config.tapMaxMs ? "A bit quicker: touch and lift at once." : "Lighter: do not move while you tap.";
        else if (l.goal == G_BACK) s = "Start right at the edge and swipe inward.";
        else s = "Nothing yet. Try: " + l.hint + ", until the tick.";
        status(s, Color.WHITE);
    }

    // ---- engine ----

    @Override
    public void onAction(Action a, boolean repeat) {
        boolean twoFinger = !menuActive && slot(a) >= 0 || a == Action.REWIND || a == Action.FAST_FORWARD;
        if (twoFinger && Tuning.app(prefs, Tuning.TWO_FINGER) == 0) return;
        showFeedback(a);
        if (menuActive && slot(a) >= 0) {
            int s = slot(a);
            int assigned = Tuning.menuAction(prefs, s);
            // like the remote: slide down repeats for the input list only, other choices fire once
            if (slotFired[s] && assigned != Protocol.INPUT) return;
            slotFired[s] = true;
            if (assigned != 0) haptics.play(menuPattern(assigned));
        } else {
            haptics.play(pattern(a));
        }
        if (stale) return;
        acted = true;
        check(a, repeat);
    }

    @Override
    public void onCue(GestureEngine.Cue cue) {
        switch (cue) {
            case MENU_OPEN:
                haptics.play(Pattern.MENU);
                menuActive = true;
                feedback.menuOpened();
                if (practice || stale || advancing || index >= lessons.size()) break;
                Lesson l = lessons.get(index);
                if (l.goal == G_LIFT) status("Now lift.", Color.WHITE);
                else if (l.goal == G_SLIDE) status("Now slide " + WORDS[l.slot] + ".", Color.WHITE);
                else if (l.goal == G_KNOB) status("Now twist your wrist.", Color.WHITE);
                break;
            case MUTE_ARMING:
                haptics.play(Pattern.RISE);
                break;
            case ARM_CANCELLED:
            case REJECTED:
                haptics.stop();
                break;
        }
    }

    /** System Back never leaves the tutorial: it is the Back gesture. Exit is the button. */
    private final OnBackInvokedCallback backCallback = () -> {
        haptics.play(Pattern.BACK);
        feedback.back(true);
        check(Action.BACK, false);
    };

    private void showFeedback(Action a) {
        switch (a) {
            case UP: feedback.step(FeedbackView.UP); break;
            case RIGHT: case FAST_FORWARD: feedback.step(FeedbackView.RIGHT); break;
            case DOWN: feedback.step(FeedbackView.DOWN); break;
            case LEFT: case REWIND: feedback.step(FeedbackView.LEFT); break;
            case OK: feedback.tap(); break;
            case BACK: feedback.back(edgeFromRight); break;
            case KNOB_LEFT: feedback.knob(-1); break;
            case KNOB_RIGHT: feedback.knob(1); break;
            default:
                if (menuActive) feedback.menuPicked(slot(a));
                else feedback.tap();   // two-finger gestures
                break;
        }
    }

    /** Engine action in the thumb menu -> slot 0..4 = up, right, down, left, lift. -1 = not a menu action. */
    private static int slot(Action a) {
        switch (a) {
            case HOME: return 0;
            case SETTINGS: return 1;
            case INPUT: return 2;
            case MUTE: return 3;
            case PLAY_PAUSE: return 4;
            default: return -1;
        }
    }

    private String name(Action a) {
        switch (a) {
            case UP: return "Up";
            case DOWN: return "Down";
            case LEFT: return "Left";
            case RIGHT: return "Right";
            case OK: return "Tap (OK)";
            case BACK: return "Back";
            case KNOB_LEFT: return "Knob ‹";
            case KNOB_RIGHT: return "Knob ›";
            case REWIND: return "Rewind (2 fingers)";
            case FAST_FORWARD: return "Forward (2 fingers)";
        }
        int s = slot(a);
        if (!menuActive) {
            return (a == Action.PLAY_PAUSE ? "Play/Pause" : a == Action.HOME ? "Home"
                    : a == Action.INPUT ? "Input list" : a == Action.SETTINGS ? "Settings" : "Mute") + " (2 fingers)";
        }
        if (Tuning.menuAction(prefs, s) == 0) return s == 4 ? "hold + lift" : "hold + slide " + WORDS[s];
        return Tuning.menuName(prefs, s);
    }

    private static Pattern pattern(Action a) {
        switch (a) {
            case UP: case DOWN: return Pattern.TICK_V;
            case OK: return Pattern.CLICK;
            case BACK: return Pattern.BACK;
            case HOME: return Pattern.HOME;
            case PLAY_PAUSE: return Pattern.PLAY;
            case INPUT: return Pattern.INPUT;
            case SETTINGS: return Pattern.SETTINGS;
            case MUTE: return Pattern.MUTE;
            default: return Pattern.TICK_H;   // left, right, rewind, forward, knob
        }
    }

    private static Pattern menuPattern(int action) {
        switch (action) {
            case Protocol.HOME: return Pattern.HOME;
            case Protocol.BACK: return Pattern.BACK;
            case Protocol.INPUT: return Pattern.INPUT;
            case Protocol.SETTINGS: return Pattern.SETTINGS;
            case Protocol.MUTE: return Pattern.MUTE;
            case Protocol.PLAY_PAUSE: return Pattern.PLAY;
            default: return Pattern.CLICK;
        }
    }

    private boolean onSurfaceTouch(View v, MotionEvent e) {
        int a = e.getActionMasked();
        long t = e.getEventTime();
        int i = e.getActionIndex();
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                primaryId = e.getPointerId(0);
                menuActive = false;
                edgeFromRight = e.getX() > v.getWidth() / 2f;
                fingerDown = true;
                stale = advancing;
                wrong = acted = false;
                count = 0;
                liftHint = null;
                downAt = t;
                java.util.Arrays.fill(slotFired, false);
                feedback.touchDown(e.getX(), e.getY());
                demo.fingerDown();
                // fall through
            case MotionEvent.ACTION_POINTER_DOWN:
                engine.down(e.getPointerId(i), mm(e.getX(i)), mm(e.getY(i)), mm(e.getTouchMajor(i)), t);
                startTicks();
                break;
            case MotionEvent.ACTION_MOVE:
                int pi = e.findPointerIndex(primaryId);
                if (pi >= 0) feedback.touchMove(e.getX(pi), e.getY(pi));
                for (int p = 0; p < e.getPointerCount(); p++) {
                    engine.move(e.getPointerId(p), mm(e.getX(p)), mm(e.getY(p)), mm(e.getTouchMajor(p)), t);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                engine.up(e.getPointerId(i), t);
                if (e.getPointerId(i) == primaryId) feedback.touchUp();
                if (a == MotionEvent.ACTION_UP) touchEnded(t);
                break;
            case MotionEvent.ACTION_CANCEL:
                engine.cancel(t);
                feedback.touchUp();
                fingerDown = false;
                demo.fingerUp();
                break;
        }
        return true;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            engine.tick(SystemClock.uptimeMillis());
            if (engine.isActive()) main.postDelayed(this, TICK_MS);
        }
    };

    private void startTicks() {
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
    }

    private float mm(float px) {
        return px / pxPerMm;
    }

    // ---- gyroscope: the knob, exactly like RemoteActivity ----

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        long last = lastGyroNs;
        lastGyroNs = e.timestamp;
        if (last == 0 || !engine.isActive()) return;
        float rate = Tuning.app(prefs, Tuning.KNOB_AXIS) == 1 ? e.values[2] : e.values[1];   // rad/s
        if (Tuning.app(prefs, Tuning.KNOB_AXIS) == 1) rate = -rate;
        if (Tuning.app(prefs, Tuning.KNOB_DIR) == 0) rate = -rate;
        float deg = (float) Math.toDegrees(rate * (e.timestamp - last) / 1e9);
        engine.twist(deg, SystemClock.uptimeMillis());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---- ui ----

    private void status(String s, int color) {
        statusText.setText(s);
        statusText.setTextColor(color);
    }

    private void big(String name) {
        bigLabel.animate().cancel();
        bigLabel.setText(name);
        bigLabel.setAlpha(0.9f);
        main.removeCallbacks(clearBig);
        main.postDelayed(clearBig, 1500);
    }

    private final Runnable clearBig = () -> bigLabel.animate().alpha(0f).setDuration(400);

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(0, s.top, 0, s.bottom);   // no side padding: the edge swipe needs the real edge
            return WindowInsets.CONSUMED;
        });

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(8), 0, 0);
        LinearLayout progress = new LinearLayout(this);
        progress.setOrientation(LinearLayout.VERTICAL);
        stepText = text(progress, 13, Color.GRAY);
        dotsText = text(progress, 9, Color.GRAY);
        top.addView(progress, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(textButton("Exit", this::finish));
        root.addView(top);

        titleText = text(root, 26, Color.WHITE);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setPadding(dp(20), dp(10), dp(20), 0);
        bodyText = text(root, 15, Color.LTGRAY);
        bodyText.setPadding(dp(20), dp(4), dp(20), 0);

        FrameLayout area = new FrameLayout(this);
        feedback = new FeedbackView(this);
        feedback.setOnTouchListener(this::onSurfaceTouch);
        feedback.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> engine.setSurface(mm(r - l), mm(b - t)));
        area.addView(feedback, new FrameLayout.LayoutParams(-1, -1));
        demo = new DemoView(this, config);   // drawn on top, takes no touches
        area.addView(demo, new FrameLayout.LayoutParams(-1, -1));

        bigLabel = new TextView(this);
        bigLabel.setTextColor(Color.WHITE);
        bigLabel.setTextSize(40);
        bigLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams bigLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        bigLp.topMargin = dp(24);
        area.addView(bigLabel, bigLp);

        LinearLayout done = new LinearLayout(this);
        done.setOrientation(LinearLayout.VERTICAL);
        done.setGravity(Gravity.CENTER);
        done.setBackgroundColor(Color.BLACK);
        done.setClickable(true);   // the surface below gets nothing
        done.addView(button("Practice freely", this::startPractice), new LinearLayout.LayoutParams(dp(240), -2));
        done.addView(button("Close", this::finish), new LinearLayout.LayoutParams(dp(240), -2));
        donePanel = done;
        area.addView(done, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout.LayoutParams areaLp = new LinearLayout.LayoutParams(-1, 0, 1);
        areaLp.topMargin = dp(12);
        root.addView(area, areaLp);

        statusText = text(root, 16, Color.GRAY);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(20), dp(10), dp(20), dp(4));
        statusText.setMinLines(2);

        LinearLayout bottom = new LinearLayout(this);
        backButton = textButton("Back", () -> showLesson(practice ? lessons.size() : index - 1));
        skipButton = textButton("Skip", () -> showLesson(index + 1));
        bottom.addView(backButton);
        bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        bottom.addView(skipButton);
        root.addView(bottom);
        return root;
    }

    private TextView text(LinearLayout parent, int size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        parent.addView(t);
        return t;
    }

    private TextView textButton(String label, Runnable run) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(Color.LTGRAY);
        t.setPadding(dp(20), dp(14), dp(20), dp(14));
        t.setOnClickListener(v -> run.run());
        return t;
    }

    private Button button(String label, Runnable run) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(dp(56));
        b.setOnClickListener(v -> run.run());
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
