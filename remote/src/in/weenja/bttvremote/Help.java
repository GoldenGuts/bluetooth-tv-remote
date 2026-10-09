package in.weenja.bttvremote;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One page with every gesture and vibration. Opens by itself the first time. */
final class Help {

    private static final int ACCENT = Color.rgb(138, 180, 248);

    private Help() {
    }

    static void show(Context c) {
        show(c, null);
    }

    static void show(Context c, Runnable tutorial) {
        android.content.SharedPreferences prefs = c.getSharedPreferences(Tuning.PREFS, Context.MODE_PRIVATE);
        String[] menu = new String[5];
        for (int i = 0; i < 5; i++) menu[i] = Tuning.menuName(prefs, i);
        float hold = Tuning.gestureConfig(prefs).menuArmMs / 1000f;
        Dialog d = new Dialog(c, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(Color.BLACK);
        int pad = dp(c, 20);
        p.setPadding(pad, pad, pad, pad);

        title(c, p, "TV Remote");
        body(c, p, "Use it without a look. The gesture starts anywhere on the black area.");
        if (tutorial != null) {
            Button start = new Button(c);
            start.setText("Start the tutorial (learn by doing)");
            start.setAllCaps(false);
            start.setOnClickListener(v -> {
                d.dismiss();
                tutorial.run();
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(c, 56));
            slp.topMargin = dp(c, 12);
            p.addView(start, slp);
        }

        heading(c, p, "One thumb");
        item(c, p, "Tap", "OK");
        item(c, p, "Swipe ↑ ↓ ← →", "Arrows. One swipe = one step. A long drag (3 cm or more) adds steps.");
        item(c, p, "Drag, then stop", "Repeats the arrow while you hold");
        item(c, p, "Swipe in from the edge", "Back (also the Good Lock back button)");
        item(c, p, "Volume keys", "TV volume, a tick per step. Screen off: turn it on in Remote Core");

        heading(c, p, "Thumb menu: hold still " + String.format(java.util.Locale.US, "%.1f", hold) + " s");
        body(c, p, "A ring fills around your thumb, then a thud. Without lifting:");
        p.addView(new MenuDiagram(c, menu), new LinearLayout.LayoutParams(-1, dp(c, 210)));
        item(c, p, "Twist the wrist", "Seek knob: one notch per 15°. The lift then does nothing.");

        heading(c, p, "Two fingers (optional)");
        item(c, p, "Two-finger tap", "Back (or Play/Pause: Settings → Gestures)");
        item(c, p, "Two-finger drag ← →", "Rewind / forward");
        item(c, p, "Two-finger drag ↑ / ↓", "Input list / Settings");
        item(c, p, "Two-finger hold", "Mute");

        heading(c, p, "Android TV or Kodi box (Wi-Fi)");
        item(c, p, "••• → Send keys to", "Keys go to the box, with the same gestures. Input and power stay on the TV. "
                + "Set up the box in Settings");
        item(c, p, "TV ↔ box", "Switch without a look: put it on a thumb-menu slot in Settings");
        item(c, p, "••• → Touchpad", "Kodi only: a mouse pointer. Slide, tap, hold, two fingers");

        heading(c, p, "Vibrations");
        item(c, p, "Rising buzz on open", "Connected to the TV");
        item(c, p, "Two low buzzes on open", "NOT connected");
        item(c, p, "One long low buzz", "The key did NOT go out");
        item(c, p, "Three low buzzes", "Connection lost");
        item(c, p, "Light tick", "Arrow step (up/down sharper, left/right lower)");

        heading(c, p, "Screen");
        item(c, p, "Dot at the top", "Green = connected, red = not connected, gray = core off. "
                + "With the box: its Wi-Fi link, orange = connecting");
        item(c, p, "••• at the top right", "Extras: text, voice, inputs, practice, settings, help");
        item(c, p, "Practice", "Gestures without keys, with big labels");

        Button close = new Button(c);
        close.setText("Got it");
        close.setAllCaps(false);
        close.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 56));
        lp.topMargin = dp(c, 24);
        p.addView(close, lp);

        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(p);
        d.setContentView(scroll);
        d.show();
    }

    /** The cross around the thumb, as it looks when the menu opens. */
    private static final class MenuDiagram extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private final String[] menu;

        MenuDiagram(Context c, String[] menu) {
            super(c);
            this.menu = menu;
            dp = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * dp);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(14 * dp);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = 70 * dp;
            line.setColor(Color.argb(90, 220, 220, 225));
            c.drawCircle(cx, cy, 40 * dp, line);
            line.setColor(ACCENT);
            line.setStrokeWidth(2.5f * dp);
            c.drawCircle(cx, cy, 14 * dp, line);
            line.setStrokeWidth(1.5f * dp);
            text.setColor(Color.argb(220, 220, 220, 225));
            c.drawText("↑ " + menu[0], cx, cy - r, text);
            c.drawText("↓ " + menu[2], cx, cy + r + 10 * dp, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("← " + menu[3], cx - 50 * dp, cy + 5 * dp, text);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(menu[1] + " →", cx + 50 * dp, cy + 5 * dp, text);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(ACCENT);
            text.setTextSize(12 * dp);
            c.drawText("lift = " + menu[4], cx, cy + 34 * dp, text);
            text.setTextSize(14 * dp);
        }
    }

    private static void title(Context c, LinearLayout p, String s) {
        TextView t = text(c, p, s, 26, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private static void heading(Context c, LinearLayout p, String s) {
        TextView t = text(c, p, s, 15, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 22), 0, dp(c, 6));
    }

    private static void body(Context c, LinearLayout p, String s) {
        text(c, p, s, 15, Color.LTGRAY).setPadding(0, dp(c, 4), 0, dp(c, 4));
    }

    private static void item(Context c, LinearLayout p, String gesture, String result) {
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));
        TextView g = new TextView(c);
        g.setText(gesture);
        g.setTextSize(15);
        g.setTextColor(Color.WHITE);
        TextView r = new TextView(c);
        r.setText(result);
        r.setTextSize(15);
        r.setTextColor(Color.GRAY);
        r.setGravity(Gravity.START);
        row.addView(g, new LinearLayout.LayoutParams(0, -2, 0.42f));
        row.addView(r, new LinearLayout.LayoutParams(0, -2, 0.58f));
        p.addView(row);
    }

    private static TextView text(Context c, LinearLayout p, String s, int size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        p.addView(t);
        return t;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
