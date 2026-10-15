package in.weenja.bttvremote;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import in.weenja.bttvremote.common.Protocol;
import in.weenja.bttvremote.gesture.GestureConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adjustable thresholds. Every public field of GestureConfig is a setting (key "g.<field>"),
 * plus the app settings below. The list builds itself, so a new config field needs no UI code.
 */
final class Tuning {

    static final String PREFS = "remote";
    static final String HAPTIC_STRENGTH = "hapticStrength";   // percent
    static final String BRIGHTNESS = "brightness";            // percent
    static final String KEEP_ON_MIN = "keepOnMinutes";
    static final String GRAVITY = "gravityTurn";
    static final String SHOW_LABELS = "showLabels";
    static final String KNOB_AXIS = "knobAxis";               // 0 = doorknob (long axis), 1 = steering wheel
    static final String KNOB_KEYS = "knobKeys";               // 0 = D-pad left/right, 1 = rewind/fast forward, 2 = volume
    static final String EDGE_BACK = "edgeBack";               // 0 = off, 1 = cover screen only, 2 = always
    static final String KNOB_DIR = "knobDirection";           // 1 = normal, 0 = reversed
    /** 1: the gesture bar stays, a swipe up from the bottom edge is the phone's Home, never an arrow. */
    static final String BOTTOM_HOME = "bottomHome";
    static final String MENU_ON = "menuOn";
    static final String REPEAT_ON = "repeatOn";
    static final String TWO_FINGER = "twoFinger";
    static final String TWO_TAP = "twoTap";                   // two-finger tap: 0 = Play/Pause, 1 = Back
    static final String ANIMATIONS = "animations";
    static final String HINTS = "hints";
    static final String STATUS_LINE = "statusLine";
    static final String VOLUME_KEYS = "volumeKeys";
    static final String VOLUME_TO = "volumeTo";               // with the box as target: 0 = TV, 1 = box, 2 = TV, else box
    static final String POCKET = "pocketGuard";
    static final String HAPTICS_ON = "hapticsOn";
    /** menuSlot0..4 = up, right, down, left, lift: a Protocol action, 0 = nothing. */
    static final String MENU_SLOT = "menuSlot";
    /** One value for both screens: the key map of the TV. */
    static final String TV_TYPE = "tvType";
    /** One value for both screens: 0 = keys go to the TV (Bluetooth), 1 = to the Android TV box (Wi-Fi). */
    static final String TARGET = "target";
    /** The IP address of the Android TV box. */
    static final String BOX_HOST = "boxHost";
    /** 1: the TV itself is an Android TV or Google TV, reached over Wi-Fi: every key goes there, no Remote Core. */
    static final String TV_WIFI = "tvWifi";
    /** The setup ran to its end (or was skipped): first start then opens the remote, not the setup. */
    static final String SETUP_DONE = "setupDone";
    /** A thumb-menu choice that is not a key: switch between the TV and the box. */
    static final int SWITCH_TARGET = 100;
    /** A thumb-menu choice: touchpad mode on (a mouse pointer on a Kodi box). */
    static final int TOUCHPAD = 101;

    /** App defaults that differ from the engine defaults (the engine tests keep theirs). */
    static final long MENU_ARM_MS = 600;
    static final float REVERSE_MM = 14f;
    static final float EDGE_MM = 5f;

    /** Choices for a thumb-menu slot, with names. */
    static final int[] MENU_CHOICES = {0, Protocol.HOME, Protocol.BACK, Protocol.INPUT, Protocol.SETTINGS,
            Protocol.MUTE, Protocol.PLAY_PAUSE, Protocol.OK, SWITCH_TARGET, TOUCHPAD};
    static final String[] MENU_CHOICE_NAMES = {"Nothing", "Home", "Back", "Input list", "Settings",
            "Mute", "Play/Pause", "OK", "TV ↔ box", "Touchpad"};
    private static final int[] MENU_DEFAULTS = {Protocol.HOME, Protocol.SETTINGS, Protocol.INPUT, Protocol.MUTE,
            Protocol.PLAY_PAUSE};

    /**
     * Each setting has a main-screen value and an optional cover-screen value (key + ".cover").
     * The cover screen uses the main value until you change it there: other size, and maybe other sensor axes.
     */
    static boolean cover;

    private Tuning() {
    }

    /** The key to read: the cover value if one exists, else the main value. */
    private static String readKey(SharedPreferences p, String key) {
        return cover && p.contains(key + ".cover") ? key + ".cover" : key;
    }

    /** The key to write for the screen in use. */
    static String writeKey(String key) {
        return cover ? key + ".cover" : key;
    }

    static GestureConfig load(SharedPreferences p) {
        GestureConfig c = new GestureConfig();
        for (Field f : fields()) {
            String key = readKey(p, "g." + f.getName());
            if (!p.contains(key)) continue;
            try {
                Class<?> t = f.getType();
                if (t == float.class) f.setFloat(c, p.getFloat(key, f.getFloat(c)));
                else if (t == long.class) f.setLong(c, p.getLong(key, f.getLong(c)));
                else if (t == int.class) f.setInt(c, p.getInt(key, f.getInt(c)));
                else if (t == boolean.class) f.setBoolean(c, p.getBoolean(key, f.getBoolean(c)));
            } catch (ReflectiveOperationException | ClassCastException ignored) {
            }
        }
        return c;
    }

    /** The gesture config the app uses: saved values, app defaults, and the on/off switches. */
    static GestureConfig gestureConfig(SharedPreferences p) {
        GestureConfig c = load(p);
        appDefaults(p, c);
        if (app(p, MENU_ON) == 0) c.menuArmMs = 1_000_000_000L;      // never opens
        if (app(p, REPEAT_ON) == 0) c.repeatDelayMs = 1_000_000_000L;
        return c;
    }

    /** App defaults for the fields not saved (p null: all of them). */
    static void appDefaults(SharedPreferences p, GestureConfig c) {
        if (unset(p, "menuArmMs")) c.menuArmMs = MENU_ARM_MS;
        if (unset(p, "flick")) c.flick = true;
        if (unset(p, "reverseMm")) c.reverseMm = REVERSE_MM;
        if (unset(p, "edgeRejectMm")) c.edgeRejectMm = EDGE_MM;
    }

    private static boolean unset(SharedPreferences p, String field) {
        return p == null || !p.contains(readKey(p, "g." + field));
    }

    /** A GestureConfig field as a number (booleans: 1 or 0), with the app defaults, without the on/off switches. */
    static float field(SharedPreferences p, String name) {
        GestureConfig c = load(p);
        appDefaults(p, c);
        try {
            Object v = GestureConfig.class.getField(name).get(c);
            if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
            return ((Number) v).floatValue();
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    static void setField(SharedPreferences p, String name, float value) {
        String key = writeKey("g." + name);
        try {
            Class<?> t = GestureConfig.class.getField(name).getType();
            SharedPreferences.Editor e = p.edit();
            if (t == float.class) e.putFloat(key, value);
            else if (t == long.class) e.putLong(key, Math.round(value));
            else if (t == int.class) e.putInt(key, Math.round(value));
            else if (t == boolean.class) e.putBoolean(key, value != 0);
            e.apply();
        } catch (ReflectiveOperationException ignored) {
        }
    }

    static int menuAction(SharedPreferences p, int slot) {
        return p.getInt(readKey(p, MENU_SLOT + slot), MENU_DEFAULTS[slot]);
    }

    static String menuName(SharedPreferences p, int slot) {
        return actionName(menuAction(p, slot));
    }

    static String actionName(int action) {
        for (int i = 0; i < MENU_CHOICES.length; i++) if (MENU_CHOICES[i] == action) return MENU_CHOICE_NAMES[i];
        return Protocol.name(action);
    }

    static void putInt(SharedPreferences p, String setting, int value) {
        p.edit().putInt(writeKey(setting), value).apply();
    }

    static int app(SharedPreferences p, String setting) {
        String key = readKey(p, setting);
        switch (setting) {
            case HAPTIC_STRENGTH: return p.getInt(key, 100);
            case BRIGHTNESS: return p.getInt(key, 15);
            case KEEP_ON_MIN: return p.getInt(key, 5);
            case GRAVITY: return p.getInt(key, 0);   // off: lying on a couch, gravity is not the thumb's "up"
            case SHOW_LABELS: return p.getInt(key, 1);
            case KNOB_AXIS: return p.getInt(key, 0);
            case KNOB_KEYS: return p.getInt(key, 0);   // LG apps seek with left/right; 0xB4/0xB3 not verified
            case EDGE_BACK: return p.getInt(key, 2);
            case TWO_TAP: return p.getInt(key, 1);
            case VOLUME_TO: return p.getInt(key, 2);
            case KNOB_DIR: return p.getInt(key, 1);
            case MENU_ON: case REPEAT_ON: case TWO_FINGER: case ANIMATIONS: case HINTS: case STATUS_LINE:
            case VOLUME_KEYS: case POCKET: case HAPTICS_ON: case BOTTOM_HOME:
                return p.getInt(key, 1);
            case TV_WIFI: return p.getInt(TV_WIFI, 0);   // one value for both screens
            default: return 0;
        }
    }

    static void show(Context context, SharedPreferences p, Runnable changed) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 12);
        list.setPadding(pad, pad, pad, pad);

        intRow(context, list, p, HAPTIC_STRENGTH, "Vibration strength %", 0, 150, 10, changed);
        intRow(context, list, p, BRIGHTNESS, "Screen brightness %", 0, 100, 1, changed);
        intRow(context, list, p, KEEP_ON_MIN, "Keep screen on, minutes", 1, 60, 1, changed);
        intRow(context, list, p, GRAVITY, "Turn directions with gravity (1 = on)", 0, 1, 1, changed);
        intRow(context, list, p, SHOW_LABELS, "Show action names (1 = on)", 0, 1, 1, changed);
        intRow(context, list, p, KNOB_AXIS, "Knob twist: 0 = doorknob, 1 = steering wheel", 0, 1, 1, changed);
        intRow(context, list, p, KNOB_KEYS, "Knob sends: 0 = left/right, 1 = rewind/fast forward, 2 = volume", 0, 2, 1, changed);
        intRow(context, list, p, KNOB_DIR, "Knob direction: 1 = normal, 0 = reversed", 0, 1, 1, changed);
        intRow(context, list, p, EDGE_BACK, "Edge swipe = Back: 0 off, 1 cover screen, 2 always", 0, 2, 1, changed);
        intRow(context, list, p, TWO_TAP, "Two-finger tap: 0 = Play/Pause, 1 = Back", 0, 1, 1, changed);

        GestureConfig defaults = new GestureConfig();
        appDefaults(null, defaults);
        GestureConfig current = load(p);
        for (Field f : fields()) fieldRow(context, list, p, f, current, defaults, changed);

        Button reset = new Button(context);
        reset.setText(cover ? "Reset cover screen to the main screen values" : "Reset gestures to defaults");
        reset.setOnClickListener(v -> {
            SharedPreferences.Editor e = p.edit();
            for (Field f : fields()) e.remove(writeKey("g." + f.getName()));
            e.apply();
            changed.run();
        });
        list.addView(reset);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        new AlertDialog.Builder(context).setTitle(cover ? "Settings: cover screen" : "Settings: main screen").setView(scroll).setPositiveButton("Done", null).show();
    }

    private static void intRow(Context c, LinearLayout list, SharedPreferences p, String key, String label,
            int min, int max, int step, Runnable changed) {
        TextView value = row(c, list, label, delta -> {
            int v = Math.max(min, Math.min(max, app(p, key) + delta * step));
            p.edit().putInt(writeKey(key), v).apply();
            changed.run();
            return String.valueOf(v);
        });
        value.setText(String.valueOf(app(p, key)));
    }

    private static void fieldRow(Context c, LinearLayout list, SharedPreferences p, Field f,
            GestureConfig current, GestureConfig defaults, Runnable changed) {
        String key = writeKey("g." + f.getName());
        TextView value = row(c, list, f.getName() + " (default " + read(f, defaults) + ")", delta -> {
            try {
                Class<?> t = f.getType();
                if (t == float.class) {
                    float v = f.getFloat(current);
                    v = Math.max(0, v + delta * (Math.abs(v) < 5 && v != 0 ? 0.5f : 1f));
                    f.setFloat(current, v);
                    p.edit().putFloat(key, v).apply();
                } else if (t == long.class) {
                    long v = Math.max(0, f.getLong(current) + delta * 25L);
                    f.setLong(current, v);
                    p.edit().putLong(key, v).apply();
                } else if (t == int.class) {
                    int v = f.getInt(current) + delta;
                    f.setInt(current, v);
                    p.edit().putInt(key, v).apply();
                } else if (t == boolean.class) {
                    boolean v = !f.getBoolean(current);
                    f.setBoolean(current, v);
                    p.edit().putBoolean(key, v).apply();
                }
            } catch (ReflectiveOperationException ignored) {
            }
            changed.run();
            return read(f, current);
        });
        value.setText(read(f, current));
    }

    interface Step {
        String apply(int delta);
    }

    private static TextView row(Context c, LinearLayout list, String label, Step step) {
        TextView name = new TextView(c);
        name.setText(label);
        name.setTextColor(Color.GRAY);
        name.setPadding(0, dp(c, 10), 0, 0);
        list.addView(name);
        LinearLayout row = new LinearLayout(c);
        TextView value = new TextView(c);
        value.setTextSize(20);
        value.setTextColor(Color.WHITE);
        Button minus = new Button(c);
        minus.setText("−");
        Button plus = new Button(c);
        plus.setText("+");
        minus.setOnClickListener(v -> value.setText(step.apply(-1)));
        plus.setOnClickListener(v -> value.setText(step.apply(1)));
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(minus, new LinearLayout.LayoutParams(dp(c, 64), -2));
        row.addView(plus, new LinearLayout.LayoutParams(dp(c, 64), -2));
        list.addView(row);
        return value;
    }

    private static String read(Field f, GestureConfig c) {
        try {
            Object v = f.get(c);
            return v instanceof Float ? String.format(Locale.US, "%.1f", (Float) v) : String.valueOf(v);
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    private static List<Field> fields() {
        List<Field> out = new ArrayList<>();
        for (Field f : GestureConfig.class.getFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isFinal(m)) continue;
            if (f.getName().equals("edgeBack")) continue;   // set by EDGE_BACK and the current screen
            out.add(f);
        }
        return out;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
