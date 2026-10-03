package in.weenja.bttvremote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** The calm card style of the settings and the extras panel: grouped rounded cards on black, one accent. */
final class Cards {

    static final int BACKGROUND = Color.BLACK;
    static final int CARD = Color.rgb(23, 24, 28);
    static final int DIVIDER = Color.rgb(38, 39, 44);
    static final int PILL = Color.rgb(36, 37, 42);
    static final int TEXT = Color.rgb(236, 236, 240);
    static final int SUBTEXT = Color.rgb(140, 142, 150);
    static final int ACCENT = Color.rgb(138, 180, 248);
    static final int DANGER = Color.rgb(240, 120, 110);
    static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;

    private final Context c;
    final LinearLayout page;
    private LinearLayout card;

    Cards(Context context) {
        c = context;
        page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(48));
    }

    /** The page in a scroll view that keeps clear of the status and navigation bars. */
    ScrollView scroll() {
        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setClipToPadding(false);
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        return scroll;
    }

    TextView title(String text, String subtitle) {
        TextView t = label(text, 30, TEXT);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setPadding(dp(8), dp(24), 0, dp(4));
        page.addView(t);
        TextView sub = label(subtitle, 13, SUBTEXT);
        sub.setPadding(dp(8), 0, dp(8), dp(8));
        page.addView(sub);
        return sub;
    }

    /** Starts a new card, with a small heading above it (or a gap). */
    LinearLayout group(String heading) {
        if (heading != null) {
            TextView h = label(heading, 13, SUBTEXT);
            h.setPadding(dp(20), dp(28), 0, dp(8));
            page.addView(h);
        } else {
            page.addView(new View(c), new LinearLayout.LayoutParams(-1, dp(28)));
        }
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 22));
        card.setClipToOutline(true);
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** One row box in the current card, with a divider above it when needed. */
    LinearLayout rowBox() {
        if (card.getChildCount() > 0) {
            View rule = new View(c);
            rule.setBackgroundColor(DIVIDER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
            lp.setMargins(dp(20), 0, dp(20), 0);
            card.addView(rule, lp);
        }
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        box.setMinimumHeight(dp(56));
        box.setBackground(pressable(null));
        card.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    /** Title (and detail) on the left, a value on the right. Returns the value; value.getParent().getParent() is the row. */
    TextView row(String title, String detail) {
        LinearLayout box = rowBox();
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView value = label("", 15, ACCENT);
        value.setGravity(Gravity.END);
        value.setMaxLines(1);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setPadding(dp(12), 0, 0, 0);
        line.addView(texts(title, detail), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(value, new LinearLayout.LayoutParams(-2, -2));
        box.addView(line);
        return value;
    }

    /** A row that opens something: a "›" at the right. */
    void link(String title, String detail, Runnable open) {
        TextView value = row(title, detail);
        value.setText("›");
        value.setTextSize(20);
        value.setTextColor(SUBTEXT);
        ((View) value.getParent().getParent()).setOnClickListener(v -> open.run());
    }

    Switch switchRow(String title, String detail) {
        LinearLayout box = rowBox();
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        Switch s = new Switch(c);
        line.addView(texts(title, detail), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(s, new LinearLayout.LayoutParams(-2, -2));
        box.addView(line);
        box.setOnClickListener(v -> s.toggle());
        return s;
    }

    /** A row of equal rounded pills with gaps, inside the current card. */
    LinearLayout pills(String[] labels, Runnable[] actions) {
        LinearLayout box = rowBox();
        box.setBackground(null);
        LinearLayout line = new LinearLayout(c);
        for (int i = 0; i < labels.length; i++) {
            TextView p = pill(labels[i]);
            Runnable run = actions[i];
            if (run != null) p.setOnClickListener(v -> run.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i > 0) lp.leftMargin = dp(8);
            line.addView(p, lp);
        }
        box.addView(line);
        return line;
    }

    TextView pill(String text) {
        TextView p = label(text, 15, TEXT);
        p.setGravity(Gravity.CENTER);
        p.setBackground(pressable(rounded(PILL, 14)));
        return p;
    }

    interface Progress {
        void changed(int progress);
    }

    SeekBar seekBar(int max, int progress, Progress changed) {
        SeekBar bar = new SeekBar(c);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(60, 62, 70)));
        bar.setPadding(dp(4), dp(10), dp(4), dp(4));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) changed.changed(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-dp(12), 0, -dp(12), 0);   // the bar has its own side padding
        bar.setLayoutParams(lp);
        return bar;
    }

    LinearLayout texts(String title, String detail) {
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(title, 16, TEXT));
        if (detail != null) {
            TextView d = label(detail, 13, SUBTEXT);
            d.setPadding(0, dp(2), 0, 0);
            texts.addView(d);
        }
        return texts;
    }

    TextView label(String s, int size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    RippleDrawable pressable(GradientDrawable content) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), content,
                content != null ? content : rounded(Color.WHITE, 0));
    }

    int dp(int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
