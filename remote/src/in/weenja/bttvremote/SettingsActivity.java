package in.weenja.bttvremote;

import in.weenja.bttvremote.common.Cards;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.text.InputType;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import in.weenja.bttvremote.Haptics.Pattern;
import in.weenja.bttvremote.box.BoxLink;
import in.weenja.bttvremote.box.BoxPairing;

import java.io.File;

import java.util.Locale;

/**
 * Settings in grouped cards, like the phone's own settings. Values are per screen (main / cover), see Tuning.
 * RemoteActivity applies them when it comes back to the front.
 */
public class SettingsActivity extends Activity {

    private static final int DIALOG_THEME = Cards.DIALOG_THEME;
    /** Intent extra: open the box address and pairing at once. */
    static final String SETUP_BOX = "setupBox";

    private SharedPreferences prefs;
    private Haptics haptics;
    private Cards ui;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        prefs = getSharedPreferences(Tuning.PREFS, MODE_PRIVATE);
        Display d = getDisplay();
        Tuning.cover = d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY;
        haptics = new Haptics(getSystemService(Vibrator.class));
        build();
        if (state == null && getIntent().getBooleanExtra(SETUP_BOX, false)) {
            main.post(() -> {
                if (prefs.getString(Tuning.BOX_HOST, "").isEmpty()) askAddress(this::checkBox);
                else checkBox();
            });
        }
    }

    private void build() {
        ui = new Cards(this);
        ui.title("Settings", Tuning.cover ? "Cover screen · only changes the cover screen"
                : "Main screen · the cover screen copies these until you change it there");

        ui.group("TV");
        String[] tvs = {"LG webOS (tested)", "Android TV, Google TV, Fire TV"};
        TextView tv = ui.row("TV type", "Home and Back keys differ per TV");
        tv.setText(tvs[prefs.getInt(Tuning.TV_TYPE, 0)]);
        ((View) tv.getParent().getParent()).setOnClickListener(v -> new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("TV type")
                .setSingleChoiceItems(tvs, prefs.getInt(Tuning.TV_TYPE, 0), (d, i) -> {
                    prefs.edit().putInt(Tuning.TV_TYPE, i).apply();   // RemoteActivity tells the core on resume
                    tv.setText(tvs[i]);
                    d.dismiss();
                })
                .show());

        ui.group("Android TV or Kodi box (Wi-Fi)");
        boxAddress = ui.row("Address", "The box's IP address on your Wi-Fi");
        String host = prefs.getString(Tuning.BOX_HOST, "");
        boxAddress.setText(host.isEmpty() ? "Not set" : host);
        ((View) boxAddress.getParent().getParent()).setOnClickListener(v -> askAddress(this::checkBox));
        boxPair = ui.row("Pair", "Android TV only: the box shows a code. Kodi needs no pairing");
        boxPair.setText(BoxLink.paired(boxDir()) ? "Paired" : "Not paired");
        ((View) boxPair.getParent().getParent()).setOnClickListener(v -> pairBox());

        ui.group("Thumb menu");
        toggle("Thumb menu", "Hold still, then slide or lift", Tuning.MENU_ON);
        slider("Hold time", "menuArmMs", 300, 1500, 50, v -> String.format(Locale.US, "%.2f s", v / 1000f));
        slider("Slide distance", "menuSelectMm", 6, 20, 1, v -> Math.round(v) + " mm");
        String[] where5 = {"Slide up", "Slide right", "Slide down", "Slide left", "Lift"};
        for (int i = 0; i < 5; i++) menuChoice(where5[i], i);

        ui.group("Arrows");
        Switch flick = ui.switchRow("One swipe = one step", "Off: one step per distance, like a jog wheel");
        flick.setChecked(Tuning.field(prefs, "flick") != 0);
        flick.setOnCheckedChangeListener((b, on) -> {
            Tuning.setField(prefs, "flick", on ? 1 : 0);
            flick.post(() -> {   // other sliders, same scroll position
                View old = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
                int y = old != null ? old.getScrollY() : 0;
                build();
                View now = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
                if (now != null) now.post(() -> now.scrollTo(0, y));
            });
        });
        if (Tuning.field(prefs, "flick") != 0) {
            slider("Step at", "flickMm", 4, 20, 1, v -> Math.round(v) + " mm");
            slider("Long drag adds steps from", "flickMoreMm", 15, 60, 1, v -> Math.round(v) + " mm");
        } else {
            slider("First step", "firstStepMm", 6, 25, 1, v -> Math.round(v) + " mm");
        }
        slider("Each next step", "stepMm", 4, 20, 1, v -> Math.round(v) + " mm");
        toggleField("Drag back steps back", null, "reverseSteps");
        slider("Step back needs", "reverseMm", 0, 30, 1, v -> Math.round(v) + " mm");
        toggle("Repeat while holding", null, Tuning.REPEAT_ON);
        slider("Repeat starts after", "repeatDelayMs", 200, 1500, 50, v -> String.format(Locale.US, "%.2f s", v / 1000f));
        slider("Repeat speed", "repeatIntervalMs", 60, 500, 10, v -> "every " + Math.round(v) + " ms");

        ui.group("Tap");
        slider("Longest tap", "tapMaxMs", 120, 400, 10, v -> Math.round(v) + " ms");
        slider("Tap may move", "tapMaxMm", 1.5f, 8, 0.5f, v -> String.format(Locale.US, "%.1f mm", v));

        ui.group("Seek knob");
        toggleField("Knob", "Hold, then twist the wrist", "knob");
        slider("Twist per notch", "knobStepDeg", 8, 40, 1, v -> Math.round(v) + "°");
        choice("Twist like a", Tuning.KNOB_AXIS, new String[]{"Doorknob", "Steering wheel"});
        choice("Direction", Tuning.KNOB_DIR, new String[]{"Reversed", "Normal"});
        choice("Sends", Tuning.KNOB_KEYS, new String[]{"Left / right", "Rewind / forward", "Volume"});

        ui.group("Gestures");
        choice("Edge swipe = Back", Tuning.EDGE_BACK, new String[]{"Off", "Cover screen", "Always"});
        toggle("Bottom swipe = phone Home", "Off: the remote takes the whole screen", Tuning.BOTTOM_HOME);
        toggle("Two-finger gestures", null, Tuning.TWO_FINGER);
        choice("Two-finger tap", Tuning.TWO_TAP, new String[]{"Play/Pause", "Back"});
        toggle("Volume keys control the TV", null, Tuning.VOLUME_KEYS);
        choice("Volume and mute go to", Tuning.VOLUME_TO,
                new String[]{"TV (Bluetooth)", "Box (Wi-Fi)", "TV, or the box when the TV link is down"});

        ui.group("Vibration");
        toggle("Vibration", null, Tuning.HAPTICS_ON);
        appSlider("Strength", Tuning.HAPTIC_STRENGTH, 10, 150, 10, v -> v + " %");
        ui.link("Feel each vibration", null, this::vibrationSheet);

        ui.group("Screen");
        appSlider("Brightness", Tuning.BRIGHTNESS, 1, 100, 1, v -> {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = v / 100f;   // preview here
            getWindow().setAttributes(lp);
            return v + " %";
        });
        appSlider("Keep on for", Tuning.KEEP_ON_MIN, 1, 30, 1, v -> v + " min");
        toggle("Animations", null, Tuning.ANIMATIONS);
        toggle("Hint line", null, Tuning.HINTS);
        toggle("Status line", null, Tuning.STATUS_LINE);
        toggle("Action names", null, Tuning.SHOW_LABELS);
        toggle("Pocket guard", "Screen off when the top sensor is covered", Tuning.POCKET);
        toggle("Turn with gravity", "Off is better on a couch", Tuning.GRAVITY);

        ui.group(null);
        ui.link("Tutorial", null, () -> startActivity(new Intent(this, TutorialActivity.class)));
        ui.link("Advanced values", null, () -> Tuning.show(this, prefs, this::build));
        LinearLayout reset = ui.rowBox();
        reset.addView(ui.label(Tuning.cover ? "Reset cover screen" : "Reset to defaults", 16, Cards.DANGER));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this, DIALOG_THEME)
                .setMessage("Reset all settings of this screen?")
                .setPositiveButton("Reset", (dlg, w) -> {
                    resetScreen();
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show());

        setContentView(ui.scroll());
        getWindow().setStatusBarColor(Cards.BACKGROUND);
        getWindow().setNavigationBarColor(Cards.BACKGROUND);
    }

    private void resetScreen() {
        SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.equals("tutorialSeen") || key.equals("helpSeen") || key.equals(Tuning.TV_TYPE)
                    || key.equals(Tuning.TARGET) || key.equals(Tuning.BOX_HOST)) continue;
            if (Tuning.cover == key.endsWith(".cover")) e.remove(key);
        }
        e.apply();
    }

    // ---- Android TV box ----

    private TextView boxAddress;
    private TextView boxPair;
    private BoxPairing pairing;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private File boxDir() {
        return new File(getFilesDir(), "box");
    }

    private void askAddress(Runnable then) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(prefs.getString(Tuning.BOX_HOST, ""));
        field.setHint("192.168.1.50");
        field.setSingleLine();
        field.setPadding(dp(20), dp(16), dp(20), dp(16));
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Box address")
                .setMessage("The IP address of the box. On the box: Settings → Network → your Wi-Fi "
                        + "(Kodi: Settings → System information → Network). "
                        + "Give it a fixed address in your router, so it does not change.")
                .setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    String host = field.getText().toString().trim();
                    if (!host.equals(prefs.getString(Tuning.BOX_HOST, ""))) {
                        prefs.edit().putString(Tuning.BOX_HOST, host).apply();
                        boxAddress.setText(host.isEmpty() ? "Not set" : host);
                        if (!host.isEmpty()) then.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Kodi (CoreELEC) needs no pairing; Android TV does. A dual-boot box may run either. */
    private void checkBox() {
        String host = prefs.getString(Tuning.BOX_HOST, "");
        new Thread(() -> {
            boolean kodi = BoxLink.kodiAt(host);
            main.post(() -> {
                if (isFinishing()) return;
                if (!kodi) {
                    pairBox();
                    return;
                }
                prefs.edit().putInt(Tuning.TARGET, 1).apply();
                haptics.play(Pattern.READY);
                new AlertDialog.Builder(this, DIALOG_THEME)
                        .setTitle("Kodi found")
                        .setMessage("The box runs Kodi: no pairing needed. Keys now go to Kodi. Input and power "
                                + "stay on the TV. Volume: Settings → Volume and mute go to.\n\n"
                                + "To switch: ••• → Send keys to, or put \"TV ↔ box\" in the thumb menu.\n\n"
                                + "When the box runs Android TV, the remote "
                                + "switches to it by itself. Pair it once from here while Android runs.")
                        .setPositiveButton("OK", null)
                        .show();
            });
        }, "box-check").start();
    }

    private void pairBox() {
        String host = prefs.getString(Tuning.BOX_HOST, "");
        if (host.isEmpty()) {
            askAddress(this::pairBox);
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Pair with the box")
                .setMessage("Connecting to " + host + "…")
                .setNegativeButton("Cancel", (d, w) -> pairing.cancel())
                .setCancelable(false)
                .show();
        AlertDialog[] codeDialog = new AlertDialog[1];
        pairing = new BoxPairing(boxDir(), host, android.os.Build.MODEL, main::post, new BoxPairing.Listener() {
            @Override
            public void codeShown(int length) {
                wait.dismiss();
                codeDialog[0] = askCode(length);
            }

            @Override
            public void done(boolean ok, String message) {
                wait.dismiss();
                if (codeDialog[0] != null) codeDialog[0].dismiss();
                boxPair.setText(BoxLink.paired(boxDir()) ? "Paired" : "Not paired");
                if (ok) {
                    prefs.edit().putInt(Tuning.TARGET, 1).apply();
                    haptics.play(Pattern.READY);
                }
                new AlertDialog.Builder(SettingsActivity.this, DIALOG_THEME)
                        .setTitle(ok ? "Paired" : "Pairing failed")
                        .setMessage(ok ? "Keys now go to the box. Input and power stay on the TV. Volume: Settings → Volume and mute go to.\n\n"
                                + "To switch: ••• → Send keys to, or put \"TV ↔ box\" in the thumb menu."
                                : message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
        pairing.start();
    }

    private AlertDialog askCode(int length) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(length),
                new android.text.InputFilter.AllCaps()});
        field.setHint(length == 4 ? "4D35" : "4D35A1");
        field.setSingleLine();
        field.setTextSize(24);
        field.setGravity(Gravity.CENTER);
        field.setPadding(dp(20), dp(16), dp(20), dp(16));
        AlertDialog d = new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Code on the TV")
                .setMessage("Type the " + length + " characters that the box shows.")
                .setView(field)
                .setPositiveButton("Pair", null)
                .setNegativeButton("Cancel", (dlg, w) -> pairing.cancel())
                .setCancelable(false)
                .show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (pairing.submit(field.getText().toString())) {
                d.setMessage("Checking…");
                v.setEnabled(false);
            } else {
                d.setMessage("That code does not fit. Check it and type it again.");
                haptics.play(Pattern.ERROR);
            }
        });
        field.requestFocus();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        return d;
    }

    @Override
    protected void onDestroy() {
        if (pairing != null) pairing.cancel();
        super.onDestroy();
    }

    private void vibrationSheet() {
        Object[][] tests = {
                {"Step ↑↓", Pattern.TICK_V}, {"Step ←→", Pattern.TICK_H}, {"OK", Pattern.CLICK},
                {"Back", Pattern.BACK}, {"Menu", Pattern.MENU}, {"Home", Pattern.HOME},
                {"Play/Pause", Pattern.PLAY}, {"Input", Pattern.INPUT}, {"Settings", Pattern.SETTINGS},
                {"Mute", Pattern.MUTE}, {"Connected", Pattern.READY}, {"No link", Pattern.NO_LINK},
                {"Not sent", Pattern.ERROR}, {"Link lost", Pattern.LOST}};
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(16), dp(8), dp(16), dp(8));
        for (Object[] t : tests) {
            Pattern pattern = (Pattern) t[1];
            TextView chip = ui.label((String) t[0], 15, Cards.TEXT);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(ui.pressable(ui.rounded(Color.rgb(34, 35, 40), 14)));
            chip.setMinHeight(dp(48));
            chip.setOnClickListener(v -> {
                haptics.strength = Tuning.app(prefs, Tuning.HAPTIC_STRENGTH) / 100f;
                haptics.play(pattern);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(chip, lp);
        }
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Feel each vibration")
                .setView(grid)
                .setPositiveButton("Done", null)
                .show();
    }

    // ---- rows ----

    interface Format {
        String of(float v);
    }

    interface IntFormat {
        String of(int v);
    }

    /** A slider for a GestureConfig field (float or long). */
    private void slider(String title, String field, float min, float max, float step, Format format) {
        TextView value = ui.row(title, null);
        float current = Tuning.field(prefs, field);
        value.setText(format.of(current));
        SeekBar bar = ui.seekBar(Math.round((max - min) / step), Math.round((Math.max(min, Math.min(max, current)) - min) / step),
                progress -> {
                    float v = min + progress * step;
                    Tuning.setField(prefs, field, v);
                    value.setText(format.of(v));
                });
        ((LinearLayout) value.getParent().getParent()).addView(bar);
    }

    /** A slider for an app setting (int). */
    private void appSlider(String title, String key, int min, int max, int step, IntFormat format) {
        TextView value = ui.row(title, null);
        int current = Tuning.app(prefs, key);
        value.setText(format.of(current));
        SeekBar bar = ui.seekBar((max - min) / step, (Math.max(min, Math.min(max, current)) - min) / step, progress -> {
            int v = min + progress * step;
            Tuning.putInt(prefs, key, v);
            value.setText(format.of(v));
        });
        ((LinearLayout) value.getParent().getParent()).addView(bar);
    }

    private void toggle(String title, String detail, String key) {
        Switch s = ui.switchRow(title, detail);
        s.setChecked(Tuning.app(prefs, key) == 1);
        s.setOnCheckedChangeListener((b, on) -> Tuning.putInt(prefs, key, on ? 1 : 0));
    }

    /** A switch for a boolean GestureConfig field. */
    private void toggleField(String title, String detail, String field) {
        Switch s = ui.switchRow(title, detail);
        s.setChecked(Tuning.field(prefs, field) != 0);
        s.setOnCheckedChangeListener((b, on) -> Tuning.setField(prefs, field, on ? 1 : 0));
    }

    private void choice(String title, String key, String[] options) {
        TextView value = ui.row(title, null);
        int current = Math.max(0, Math.min(options.length - 1, Tuning.app(prefs, key)));
        value.setText(options[current]);
        ((View) value.getParent().getParent()).setOnClickListener(v -> new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(title)
                .setSingleChoiceItems(options, Tuning.app(prefs, key), (d, i) -> {
                    Tuning.putInt(prefs, key, i);
                    value.setText(options[i]);
                    d.dismiss();
                })
                .show());
    }

    private void menuChoice(String title, int slot) {
        TextView value = ui.row(title, null);
        value.setText(Tuning.menuName(prefs, slot));
        ((View) value.getParent().getParent()).setOnClickListener(v -> {
            int selected = 0;
            for (int i = 0; i < Tuning.MENU_CHOICES.length; i++) {
                if (Tuning.MENU_CHOICES[i] == Tuning.menuAction(prefs, slot)) selected = i;
            }
            new AlertDialog.Builder(this, DIALOG_THEME)
                    .setTitle(title)
                    .setSingleChoiceItems(Tuning.MENU_CHOICE_NAMES, selected, (d, i) -> {
                        Tuning.putInt(prefs, Tuning.MENU_SLOT + slot, Tuning.MENU_CHOICES[i]);
                        value.setText(Tuning.MENU_CHOICE_NAMES[i]);
                        d.dismiss();
                    })
                    .show();
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
