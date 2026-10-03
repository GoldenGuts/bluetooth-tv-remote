package in.weenja.bttvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import in.weenja.bttvremote.common.Protocol;
import in.weenja.bttvremote.Haptics.Pattern;
import in.weenja.bttvremote.gesture.GestureConfig;
import in.weenja.bttvremote.gesture.GestureEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The remote. A black screen that takes gestures anywhere.
 * Keys go to Remote Core; the vibration plays only when the core accepted the key, else the error buzz.
 */
public class RemoteActivity extends Activity implements GestureEngine.Listener, SensorEventListener {

    private static final int VOICE_REQUEST = 1;
    private static final long TICK_MS = 16;
    private static final int CALIBRATION_SWIPES = 10;

    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private GestureEngine engine;
    private Haptics haptics;
    private CoreClient core;
    private SensorManager sensors;
    private PowerManager.WakeLock pocketGuard;

    private LinearLayout root;
    private FrameLayout surfaceFrame;
    private ScrollView panel;
    private View dot;
    private TextView label;
    private TextView statusText;
    private TextView hint;
    private FeedbackView feedback;
    private int primaryId = -1;
    private boolean menuActive;
    private TextView panelStatus;
    private EditText textField;

    private float pxPerMm;
    private boolean onCover;
    private long lastGyroNs;
    private boolean practice;
    private boolean panelOpen;
    private boolean flex;
    private boolean resumed;
    private boolean greeted;
    private int lastState = -1;
    private int turns;

    // thumb bias calibration: raw swipe start and angles
    private int calibrationLeft;
    private float calStartX, calStartY;
    private final List<Float> calAngles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        prefs = getSharedPreferences(Tuning.PREFS, MODE_PRIVATE);
        haptics = new Haptics(getSystemService(Vibrator.class));
        engine = new GestureEngine(Tuning.load(prefs), this);
        core = new CoreClient(this, this::coreStatus);
        sensors = getSystemService(SensorManager.class);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            pocketGuard = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "bttvremote:pocket");
        }
        measureDisplay();

        setContentView(buildUi());
        hideSystemBars();
        applySettings();
        if (!prefs.getBoolean("tutorialSeen", false)) {
            prefs.edit().putBoolean("tutorialSeen", true).apply();
            main.post(this::openTutorial);
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        greeted = false;
        core.setTv(prefs.getInt(Tuning.TV_TYPE, Protocol.TV_LG));
        if (!core.bind()) {
            label("Remote Core is not installed");
            haptics.play(Pattern.ERROR);
        }
        Sensor gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravity != null) sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_UI);
        Sensor hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hinge != null) sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_NORMAL);
        Sensor gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro != null) sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        lastGyroNs = 0;
        measureDisplay();
        applySettings();
        if (pocketGuard != null && !pocketGuard.isHeld() && Tuning.app(prefs, Tuning.POCKET) == 1) pocketGuard.acquire();
        touched();
        // With a status already known (core stays bound), greet at once.
        if (core.state != Protocol.STATE_OFF || core.data.containsKey("log")) greet();
    }

    @Override
    protected void onPause() {
        resumed = false;
        engine.cancel(SystemClock.uptimeMillis());
        haptics.stop();
        sensors.unregisterListener(this);
        if (pocketGuard != null && pocketGuard.isHeld()) pocketGuard.release();
        main.removeCallbacks(screenTimeout);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        core.unbind();
        super.onDestroy();
    }

    // ---- gestures -> keys ----

    @Override
    public void onAction(GestureEngine.Action action, boolean repeat) {
        boolean twoFinger = !menuActive && isMenuAction(action) || action == GestureEngine.Action.REWIND
                || action == GestureEngine.Action.FAST_FORWARD;
        if (twoFinger && Tuning.app(prefs, Tuning.TWO_FINGER) == 0) return;
        showFeedback(action);
        if (menuActive && isMenuAction(action)) {
            int slot = slot(action);
            int assigned = Tuning.menuAction(prefs, slot);
            // Slide down repeats (one per 15 mm) for the input list only; other choices fire once.
            if (assigned == 0 || (slotFired[slot] && assigned != Protocol.INPUT)) return;
            slotFired[slot] = true;
            send(assigned, pattern(assigned));
            return;
        }
        switch (action) {
            case UP: send(Protocol.UP, Pattern.TICK_V); break;
            case DOWN: send(Protocol.DOWN, Pattern.TICK_V); break;
            case LEFT: send(Protocol.LEFT, Pattern.TICK_H); break;
            case RIGHT: send(Protocol.RIGHT, Pattern.TICK_H); break;
            case OK: send(Protocol.OK, Pattern.CLICK); break;
            case HOME: send(Protocol.HOME, Pattern.HOME); break;
            case PLAY_PAUSE: send(Protocol.PLAY_PAUSE, Pattern.PLAY); break;
            case REWIND: send(Protocol.REWIND, Pattern.TICK_H); break;
            case FAST_FORWARD: send(Protocol.FAST_FORWARD, Pattern.TICK_H); break;
            case INPUT: send(Protocol.INPUT, Pattern.INPUT); break;
            case SETTINGS: send(Protocol.SETTINGS, Pattern.SETTINGS); break;
            case MUTE: send(Protocol.MUTE, Pattern.MUTE); break;
            case BACK: send(Protocol.BACK, Pattern.BACK); break;
            case KNOB_LEFT:
                send(Tuning.app(prefs, Tuning.KNOB_KEYS) == 1 ? Protocol.REWIND : Protocol.LEFT, Pattern.TICK_H);
                break;
            case KNOB_RIGHT:
                send(Tuning.app(prefs, Tuning.KNOB_KEYS) == 1 ? Protocol.FAST_FORWARD : Protocol.RIGHT, Pattern.TICK_H);
                break;
        }
    }

    private final boolean[] slotFired = new boolean[5];

    private static boolean isMenuAction(GestureEngine.Action a) {
        return a == GestureEngine.Action.HOME || a == GestureEngine.Action.SETTINGS || a == GestureEngine.Action.INPUT
                || a == GestureEngine.Action.MUTE || a == GestureEngine.Action.PLAY_PAUSE;
    }

    /** Engine action in the thumb menu -> slot 0..4 = up, right, down, left, lift. */
    private static int slot(GestureEngine.Action a) {
        switch (a) {
            case HOME: return 0;
            case SETTINGS: return 1;
            case INPUT: return 2;
            case MUTE: return 3;
            default: return 4;
        }
    }

    private static Pattern pattern(int action) {
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

    @Override
    public void onCue(GestureEngine.Cue cue) {
        switch (cue) {
            case MENU_OPEN:
                haptics.play(Pattern.MENU);
                menuActive = true;
                feedback.menuOpened();
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

    private void send(int action, Pattern pattern) {
        label(Protocol.name(action).replace('_', ' '));
        if (practice) {
            haptics.play(pattern);
            return;
        }
        core.send(action, ok -> {
            haptics.play(ok ? pattern : Pattern.ERROR);
            if (!ok) feedback.error();
        });
    }

    private void showFeedback(GestureEngine.Action action) {
        switch (action) {
            case UP: feedback.step(FeedbackView.UP); break;
            case RIGHT: case FAST_FORWARD: feedback.step(FeedbackView.RIGHT); break;
            case DOWN: feedback.step(FeedbackView.DOWN); break;
            case LEFT: case REWIND: feedback.step(FeedbackView.LEFT); break;
            case OK: feedback.tap(); break;
            case BACK: feedback.back(edgeFromRight); break;
            case KNOB_LEFT: feedback.knob(-1); break;
            case KNOB_RIGHT: feedback.knob(1); break;
            case HOME: menuOrTap(FeedbackView.UP); break;
            case SETTINGS: menuOrTap(FeedbackView.RIGHT); break;
            case INPUT: menuOrTap(FeedbackView.DOWN); break;
            case MUTE: menuOrTap(FeedbackView.LEFT); break;
            case PLAY_PAUSE: menuOrTap(4); break;
        }
    }

    private void menuOrTap(int dir) {
        if (menuActive) feedback.menuPicked(dir);
        else feedback.tap();   // two-finger gestures
    }

    private boolean edgeFromRight;

    private final OnBackInvokedCallback backCallback = () -> {
        if (panelOpen && !flex) {
            setPanelOpen(false);
            return;
        }
        send(Protocol.BACK, Pattern.BACK);
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (volume && Tuning.app(prefs, Tuning.VOLUME_KEYS) == 1) {
            int action = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? Protocol.VOLUME_UP : Protocol.VOLUME_DOWN;
            label(Protocol.name(action).replace('_', ' '));
            if (!practice) core.send(action, ok -> {
                if (!ok) haptics.play(Pattern.ERROR);
            });
            touched();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && Tuning.app(prefs, Tuning.VOLUME_KEYS) == 1) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean onSurfaceTouch(View v, MotionEvent e) {
        touched();
        if (calibrationLeft > 0) return calibrate(e);
        int a = e.getActionMasked();
        long t = e.getEventTime();
        int i = e.getActionIndex();
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                primaryId = e.getPointerId(0);
                menuActive = false;
                java.util.Arrays.fill(slotFired, false);
                edgeFromRight = e.getX() > v.getWidth() / 2f;
                feedback.touchDown(e.getX(), e.getY());
                hint.animate().alpha(0f).setDuration(600);
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
                break;
            case MotionEvent.ACTION_CANCEL:
                engine.cancel(t);
                feedback.touchUp();
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

    // ---- core status ----

    private void coreStatus(int state, int flags, Bundle data) {
        boolean stale = (flags & Protocol.FLAG_STALE) != 0;
        boolean connected = state == Protocol.STATE_CONNECTED && !stale;
        if (lastState == Protocol.STATE_CONNECTED && state != Protocol.STATE_CONNECTED && resumed) {
            haptics.play(Pattern.LOST);
        }
        lastState = state;
        if (resumed && !greeted) greet();
        int color = connected ? Color.rgb(40, 200, 80) : state == Protocol.STATE_OFF ? Color.GRAY : Color.rgb(220, 60, 50);
        ((GradientDrawable) dot.getBackground()).setColor(color);
        String host = data.getString("host");
        statusText.setText(practice ? "practice · no keys sent"
                : connected ? host + " · connected"
                : stale ? "pairing is stale · ••• → Pairing help"
                : state == Protocol.STATE_WAITING ? "not connected · waiting for the TV"
                : "Remote Core is off · ••• → Remote Core");
        panelStatus.setText(stale ? "Pairing is stale · see Pairing help"
                : state == Protocol.STATE_CONNECTED ? "Connected to " + host
                : state == Protocol.STATE_WAITING ? "Waiting for the TV" : "Remote Core or Bluetooth is off");
        panelStatus.setTextColor(connected ? Cards.ACCENT : Cards.SUBTEXT);
        coreLog = data.getString("log", "");
    }

    /** The first vibration after the screen comes on tells the link state. */
    private void greet() {
        greeted = true;
        boolean ok = core.state == Protocol.STATE_CONNECTED && (core.flags & Protocol.FLAG_STALE) == 0;
        haptics.play(ok ? Pattern.READY : Pattern.NO_LINK);
    }

    // ---- sensors ----

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_HINGE_ANGLE) {
            float angle = e.values[0];
            setFlex(angle > 60 && angle < 150);
            return;
        }
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // Knob: wrist twist while the thumb holds the menu. Only fed while a finger is down.
            long last = lastGyroNs;
            lastGyroNs = e.timestamp;
            if (last == 0 || !engine.isActive()) return;
            float rate = Tuning.app(prefs, Tuning.KNOB_AXIS) == 1 ? e.values[2] : e.values[1];   // rad/s
            // y axis: + = right edge goes down (a doorknob turned right). z axis: + = counter-clockwise, so negate.
            if (Tuning.app(prefs, Tuning.KNOB_AXIS) == 1) rate = -rate;
            if (Tuning.app(prefs, Tuning.KNOB_DIR) == 0) rate = -rate;   // closed, the sensor may sit in the flipped half
            float deg = (float) Math.toDegrees(rate * (e.timestamp - last) / 1e9);
            engine.twist(deg, SystemClock.uptimeMillis());
            return;
        }
        float x = e.values[0], y = e.values[1];
        // No "face down = sleep": lying on your back, the phone is face down in your hand.
        // Face down on a table covers the proximity sensor, and the pocket guard turns the screen off.
        // Gravity turn, in 90° steps, only between touches. Flat on a table: keep the last value.
        if (Tuning.app(prefs, Tuning.GRAVITY) == 0 || engine.isActive()) return;
        int next = turns;
        if (Math.abs(x) > 7f && Math.abs(x) > Math.abs(y) * 1.5f) next = x > 0 ? 1 : 3;   // top edge to the left : right
        else if (Math.abs(y) > 7f && Math.abs(y) > Math.abs(x) * 1.5f) next = y > 0 ? 0 : 2;
        if (next != turns) {
            turns = next;
            engine.setQuarterTurns(turns);
            feedback.setQuarterTurns(turns);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void setFlex(boolean on) {
        if (on == flex) return;
        flex = on;
        layoutPanel();
    }

    // ---- screen ----

    private void touched() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        main.removeCallbacks(screenTimeout);
        main.postDelayed(screenTimeout, Tuning.app(prefs, Tuning.KEEP_ON_MIN) * 60_000L);
    }

    private final Runnable screenTimeout =
            () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // Folding moves the app between the main and the cover screen: other size, other density.
        measureDisplay();
        applySettings();
        engine.cancel(SystemClock.uptimeMillis());
    }

    private void measureDisplay() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        pxPerMm = (dm.xdpi + dm.ydpi) / 2f / 25.4f;
        android.view.Display d = getDisplay();
        onCover = d != null && d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY;
        Tuning.cover = onCover;
        // Main screen: portrait, the directions stay with the hand. Cover screen: its own natural orientation.
        int want = onCover ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (getRequestedOrientation() != want) setRequestedOrientation(want);
    }

    private void applySettings() {
        GestureConfig config = Tuning.gestureConfig(prefs);
        int edgeBack = Tuning.app(prefs, Tuning.EDGE_BACK);
        // The cover screen may have no system back gesture, so the surface does its own edge swipe.
        config.edgeBack = edgeBack == 2 || (edgeBack == 1 && onCover);
        engine.setConfig(config);
        feedback.setHold(config.menuArmMs, config.holdSlopMm, pxPerMm);
        haptics.strength = Tuning.app(prefs, Tuning.HAPTICS_ON) == 0 ? 0f
                : Tuning.app(prefs, Tuning.HAPTIC_STRENGTH) / 100f;
        String[] labels = new String[4];
        for (int i = 0; i < 4; i++) labels[i] = Tuning.menuName(prefs, i);
        feedback.setMenuLabels(labels, Tuning.menuName(prefs, 4));
        feedback.setQuiet(Tuning.app(prefs, Tuning.ANIMATIONS) == 0);
        hint.setVisibility(Tuning.app(prefs, Tuning.HINTS) == 1 ? View.VISIBLE : View.GONE);
        statusText.setVisibility(Tuning.app(prefs, Tuning.STATUS_LINE) == 1 ? View.VISIBLE : View.GONE);
        if (pocketGuard != null && resumed) {
            boolean on = Tuning.app(prefs, Tuning.POCKET) == 1;
            if (on && !pocketGuard.isHeld()) pocketGuard.acquire();
            else if (!on && pocketGuard.isHeld()) pocketGuard.release();
        }
        setBrightness(Math.max(0.01f, Tuning.app(prefs, Tuning.BRIGHTNESS) / 100f));
        if (Tuning.app(prefs, Tuning.GRAVITY) == 0) {
            turns = 0;
            engine.setQuarterTurns(0);
        }
        feedback.setQuarterTurns(turns);
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void label(String text) {
        if (Tuning.app(prefs, Tuning.SHOW_LABELS) == 0 && !practice) return;
        label.setText(text);
        label.setTextSize(practice ? 40 : 18);
        label.setAlpha(practice ? 0.9f : 0.35f);
        main.removeCallbacks(clearLabel);
        main.postDelayed(clearLabel, practice ? 1500 : 700);
    }

    private final Runnable clearLabel = () -> label.setText("");

    // ---- thumb bias calibration ----

    /** Measures the angle of straight-up swipes. A thumb arc leans; biasDeg turns the axes to match. */
    private boolean calibrate(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            calStartX = e.getX();
            calStartY = e.getY();
        } else if (a == MotionEvent.ACTION_UP) {
            float dx = e.getX() - calStartX, dy = e.getY() - calStartY;
            if (-dy < 15 * pxPerMm) {
                haptics.play(Pattern.ERROR);
                return true;
            }
            calAngles.add((float) Math.toDegrees(Math.atan2(dx, -dy)));   // + = leans right
            haptics.play(Pattern.TICK_V);
            calibrationLeft--;
            if (calibrationLeft > 0) {
                label("swipe up: " + calibrationLeft + " more");
                return true;
            }
            float sum = 0;
            for (float f : calAngles) sum += f;
            float bias = Math.round(sum / calAngles.size());
            prefs.edit().putFloat(Tuning.writeKey("g.biasDeg"), bias).apply();
            applySettings();
            haptics.play(Pattern.READY);
            label(String.format(Locale.US, "thumb bias %.0f°", bias));
        }
        return true;
    }

    private void startCalibration() {
        setPanelOpen(false);
        calAngles.clear();
        calibrationLeft = CALIBRATION_SWIPES;
        boolean wasPractice = practice;
        practice = true;
        label("swipe straight up " + CALIBRATION_SWIPES + " times");
        practice = wasPractice;
    }

    // ---- extras panel ----

    private void setPanelOpen(boolean open) {
        panelOpen = open;
        layoutPanel();
    }

    /** Open phone: panel OR surface. Flex mode (half open): panel on top, surface below. */
    private void layoutPanel() {
        boolean showPanel = flex || panelOpen;
        boolean showSurface = flex || !panelOpen;
        panel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        surfaceFrame.setVisibility(showSurface ? View.VISIBLE : View.GONE);
        engine.cancel(SystemClock.uptimeMillis());
    }

    private void openTutorial() {
        startActivity(new Intent(this, TutorialActivity.class));
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void voice() {
        try {
            startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), VOICE_REQUEST);
        } catch (RuntimeException e) {
            label("no speech service");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_REQUEST || result != RESULT_OK || data == null) return;
        ArrayList<String> words = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (words == null || words.isEmpty()) return;
        String said = words.get(0);
        // A space between earlier text and the spoken words.
        String add = textField.length() > 0 && !said.isEmpty() ? " " + said : said;
        typeOnTv(add);
        setFieldQuietly(textField.getText() + add);
    }

    // ---- live typing: the field mirrors the TV text box ----

    private boolean quietField;

    private void setFieldQuietly(String text) {
        quietField = true;
        textField.setText(text);
        textField.setSelection(text.length());
        quietField = false;
    }

    private final android.text.TextWatcher liveTyping = new android.text.TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (quietField) return;
            // A change in the middle of the text is sent as if made at the end: the TV cursor is at the end.
            int tail = s.length() - (start + count);
            if (tail > 0) {
                sendBackspaces(before + tail);
                typeOnTv(s.subSequence(start, s.length()).toString());
            } else {
                sendBackspaces(before);
                if (count > 0) typeOnTv(s.subSequence(start, start + count).toString());
            }
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    };

    private void typeOnTv(String text) {
        if (text.isEmpty()) return;
        core.text(text, ok -> {
            if (!ok) haptics.play(Pattern.ERROR);
        });
    }

    private void sendBackspaces(int n) {
        for (int i = 0; i < n; i++) {
            core.key(1, new byte[]{0, 0, 0x2A, 0, 0, 0, 0, 0}, ok -> {
                if (!ok) haptics.play(Pattern.ERROR);
            });
        }
    }

    private void sendEnter() {
        core.key(1, new byte[]{0, 0, 0x28, 0, 0, 0, 0, 0}, ok -> haptics.play(ok ? Pattern.CLICK : Pattern.ERROR));
        setFieldQuietly("");
    }

    /** Input list, then (n) more steps, then OK. Fragile: it depends on where the list starts. */
    private void inputMacro(int steps) {
        core.send(Protocol.INPUT, null);
        long at = 2500;   // the TV needs 1 to 3 s to show the list
        for (int i = 0; i < steps; i++, at += 700) main.postDelayed(() -> core.send(Protocol.INPUT, null), at);
        main.postDelayed(() -> core.send(Protocol.OK, ok -> haptics.play(ok ? Pattern.INPUT : Pattern.ERROR)), at + 500);
    }

    private void pairingHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Pairing help")
                .setMessage("1. On the TV: Settings → Bluetooth → delete the phone.\n"
                        + "2. On the phone: Bluetooth → delete the TV.\n"
                        + "   Delete on BOTH sides, else the phone refuses the new pairing.\n"
                        + "3. If the TV still shows a link: phone Bluetooth off and on (the watch disconnects for a moment).\n"
                        + "4. On the TV: search for Bluetooth devices, choose the phone, accept the number on both.\n"
                        + "5. In Remote Core: Choose TV, if keys do not work.\n\n"
                        + "Pairing breaks when Remote Core restarts (update, crash, Bluetooth off/on). "
                        + "Updates of TV Remote do not break it.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ---- ui ----

    private View buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        panel = buildPanel();
        root.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));

        surfaceFrame = new FrameLayout(this);
        feedback = new FeedbackView(this);
        View surface = feedback;
        surface.setOnTouchListener(this::onSurfaceTouch);
        surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                engine.setSurface(mm(r - l), mm(b - t)));
        surfaceFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        dot = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.GRAY);
        dot.setBackground(circle);
        dot.setAlpha(0.5f);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dotLp.topMargin = dp(20);
        surfaceFrame.addView(dot, dotLp);

        statusText = new TextView(this);
        statusText.setTextColor(Color.GRAY);
        statusText.setTextSize(12);
        statusText.setAlpha(0.7f);
        statusText.setText("starting…");
        FrameLayout.LayoutParams stLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        stLp.topMargin = dp(32);
        surfaceFrame.addView(statusText, stLp);

        TextView help = new TextView(this);
        help.setText("?");
        help.setTextColor(Color.GRAY);
        help.setTextSize(18);
        help.setPadding(dp(18), dp(10), dp(18), dp(10));
        help.setOnClickListener(v -> Help.show(this, this::openTutorial));
        surfaceFrame.addView(help, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        hint = new TextView(this);
        hint.setText("tap = OK   ·   drag = arrows\nhold = menu   ·   edge swipe = back");
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setAlpha(0.6f);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hintLp.bottomMargin = dp(28);
        surfaceFrame.addView(hint, hintLp);

        TextView more = new TextView(this);
        more.setText("•••");
        more.setTextColor(Color.GRAY);
        more.setTextSize(18);
        more.setPadding(dp(16), dp(10), dp(16), dp(10));
        more.setOnClickListener(v -> setPanelOpen(true));
        surfaceFrame.addView(more, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        labelLp.bottomMargin = dp(90);
        surfaceFrame.addView(label, labelLp);

        root.addView(surfaceFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.setVisibility(View.GONE);
        return root;
    }

    private ScrollView buildPanel() {
        Cards ui = new Cards(this);

        // Header: name and link state on the left, close on the right.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(12), 0, dp(4));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(ui.label("TV Remote", 26, Cards.TEXT));
        panelStatus = ui.label("", 13, Cards.SUBTEXT);
        panelStatus.setPadding(0, dp(2), 0, 0);
        names.addView(panelStatus);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = ui.pill("✕");
        close.setOnClickListener(v -> setPanelOpen(false));
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ui.page.addView(head);

        ui.group("Type on the TV");
        LinearLayout box = ui.rowBox();
        box.setBackground(null);
        box.addView(ui.label("Select a search box on the TV first. Letters appear there as you type.", 13, Cards.SUBTEXT));
        textField = new EditText(this);
        // No suggestions: the keyboard then sends letter by letter, and nothing is replaced behind our back.
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        textField.setTextColor(Cards.TEXT);
        textField.setTextSize(17);
        textField.setHint("Tap here and type");
        textField.setHintTextColor(Cards.SUBTEXT);
        textField.setBackground(ui.rounded(Cards.PILL, 14));
        textField.setPadding(dp(16), dp(12), dp(16), dp(12));
        textField.addTextChangedListener(liveTyping);
        textField.setOnEditorActionListener((v, actionId, event) -> {
            sendEnter();
            return true;
        });
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        fieldLp.topMargin = dp(12);
        box.addView(textField, fieldLp);
        ui.pills(new String[]{"Voice", "⌫", "Clear", "Enter"}, new Runnable[]{
                this::voice,
                () -> {
                    sendBackspaces(1);
                    int n = textField.length();
                    if (n > 0) setFieldQuietly(textField.getText().subSequence(0, n - 1).toString());
                },
                () -> {
                    sendBackspaces(textField.length());
                    setFieldQuietly("");
                },
                this::sendEnter});

        ui.group("TV");
        ui.link("TV settings menu", null, () -> send(Protocol.SETTINGS, Pattern.SETTINGS));
        ui.link("Switch input", "Opens the input list, steps, then OK", this::inputDialog);
        LinearLayout power = ui.rowBox();
        power.addView(ui.texts("Power off", "Touch and hold"));
        ((TextView) ((LinearLayout) power.getChildAt(0)).getChildAt(0)).setTextColor(Cards.DANGER);
        power.setOnClickListener(v -> label("hold to turn the TV off"));
        power.setOnLongClickListener(v -> {
            send(Protocol.POWER, Pattern.HOME);
            return true;
        });

        ui.group("Learn");
        ui.link("Tutorial", null, this::openTutorial);
        ui.link("Cheat sheet", null, () -> Help.show(this, this::openTutorial));
        android.widget.Switch practiceSwitch = ui.switchRow("Practice mode", "Gestures without sending keys");
        practiceSwitch.setChecked(practice);
        practiceSwitch.setOnCheckedChangeListener((b, on) -> {
            practice = on;
            coreStatus(core.state, core.flags, core.data);
        });
        ui.link("Measure thumb angle", "Swipe up 10 times", this::startCalibration);

        ui.group(null);
        ui.link("Settings", null, this::openSettings);
        ui.link("Pairing help", null, this::pairingHelp);
        ui.link("Remote Core", "The Bluetooth link app", core::openCoreApp);
        ui.link("Support the app ♥", "Free and open source. Sponsor on GitHub", this::openSponsor);
        ui.link("Connection log", null, () -> new AlertDialog.Builder(this, Cards.DIALOG_THEME)
                .setTitle("Connection log")
                .setMessage(coreLog.isEmpty() ? "No log yet" : coreLog)
                .setPositiveButton("Close", null)
                .show());

        ScrollView scroll = ui.scroll();
        scroll.setOnApplyWindowInsetsListener(null);   // the remote hides the system bars
        return scroll;
    }

    private String coreLog = "";

    /** The browser opens the page: the app itself has no internet permission. */
    private void openSponsor() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SPONSOR_URL)));
        } catch (RuntimeException e) {
            label("no browser found");
        }
    }

    private static final String SPONSOR_URL = "https://github.com/sponsors/GoldenGuts";

    private void inputDialog() {
        String[] steps = {"Open the input list", "Next input", "2 inputs on", "3 inputs on"};
        new AlertDialog.Builder(this, Cards.DIALOG_THEME)
                .setTitle("Switch input (test)")
                .setItems(steps, (d, i) -> inputMacro(i))
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
