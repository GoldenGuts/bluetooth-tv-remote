package in.weenja.bttvremote;

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
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import in.weenja.bttvremote.Haptics.Pattern;

import java.util.Locale;

/**
 * Settings in grouped cards, like the phone's own settings. Values are per screen (main / cover), see Tuning.
 * RemoteActivity applies them when it comes back to the front.
 */
public class SettingsActivity extends Activity {

    private static final int DIALOG_THEME = Cards.DIALOG_THEME;

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

        ui.group("Thumb menu");
        toggle("Thumb menu", "Hold still, then slide or lift", Tuning.MENU_ON);
        slider("Hold time", "menuArmMs", 300, 1500, 50, v -> String.format(Locale.US, "%.2f s", v / 1000f));
        slider("Slide distance", "menuSelectMm", 6, 20, 1, v -> Math.round(v) + " mm");
        String[] where5 = {"Slide up", "Slide right", "Slide down", "Slide left", "Lift"};
        for (int i = 0; i < 5; i++) menuChoice(where5[i], i);

        ui.group("Arrows");
        slider("First step", "firstStepMm", 6, 25, 1, v -> Math.round(v) + " mm");
        slider("Each next step", "stepMm", 4, 20, 1, v -> Math.round(v) + " mm");
        toggleField("Drag back steps back", null, "reverseSteps");
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
        choice("Sends", Tuning.KNOB_KEYS, new String[]{"Left / right", "Rewind / forward"});

        ui.group("Gestures");
        choice("Edge swipe = Back", Tuning.EDGE_BACK, new String[]{"Off", "Cover screen", "Always"});
        toggle("Two-finger gestures", null, Tuning.TWO_FINGER);
        toggle("Volume keys control the TV", null, Tuning.VOLUME_KEYS);

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
            if (key.equals("tutorialSeen") || key.equals("helpSeen") || key.equals(Tuning.TV_TYPE)) continue;
            if (Tuning.cover == key.endsWith(".cover")) e.remove(key);
        }
        e.apply();
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
