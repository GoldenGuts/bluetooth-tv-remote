package in.weenja.bttvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.net.Inet4Address;
import java.util.List;

import in.weenja.bttvremote.Haptics.Pattern;
import in.weenja.bttvremote.box.BoxFinder;
import in.weenja.bttvremote.box.BoxLink;
import in.weenja.bttvremote.box.BoxPairing;
import in.weenja.bttvremote.common.Cards;

/**
 * Finding, choosing and pairing the box (or an Android TV) over Wi-Fi, for the setup and for Settings.
 * Kodi needs no pairing; Android TV shows a code that the person types here.
 */
final class BoxSetup {

    interface Listener {
        /** The box is ready (true) or the attempt ended without a link (false). */
        void done(boolean ok, String message);
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Haptics haptics;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BoxFinder finder = new BoxFinder();
    private BoxPairing pairing;

    BoxSetup(Activity activity, SharedPreferences prefs, Haptics haptics, Listener listener) {
        this.activity = activity;
        this.prefs = prefs;
        this.haptics = haptics;
        this.listener = listener;
    }

    File boxDir() {
        return new File(activity.getFilesDir(), "box");
    }

    void cancel() {
        finder.cancel();
        if (pairing != null) pairing.cancel();
    }

    /** The phone's own IPv4 address on the current network (Wi-Fi), or null. */
    private Inet4Address phoneAddress() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress()) {
                return (Inet4Address) a.getAddress();
            }
        }
        return null;
    }

    /** Searches the Wi-Fi and lists what answers; a tap connects. One box only: it connects at once. */
    void find() {
        Cards ui = new Cards(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        TextView status = ui.label("Searching your Wi-Fi…", 15, Cards.SUBTEXT);
        status.setPadding(0, ui.dp(8), 0, ui.dp(12));
        list.addView(status);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        AlertDialog[] dialog = new AlertDialog[1];
        dialog[0] = new AlertDialog.Builder(activity, Cards.DIALOG_THEME)
                .setTitle("Find the box")
                .setView(scroll)
                .setPositiveButton("Type the address", (d, w) -> {
                    finder.cancel();
                    askAddress();
                })
                .setNegativeButton("Cancel", (d, w) -> finder.cancel())
                .setOnCancelListener(d -> finder.cancel())
                .show();
        Inet4Address self = phoneAddress();
        if (self == null) {
            status.setText("The phone is not on Wi-Fi. Connect it to the same Wi-Fi as the box.");
            return;
        }
        finder.scan(self, new BoxFinder.Listener() {
            @Override
            public void found(BoxFinder.Found f) {
                main.post(() -> {
                    TextView row = ui.label(f.label(), 17, Cards.TEXT);
                    row.setPadding(0, ui.dp(14), 0, ui.dp(14));
                    row.setBackground(ui.pressable(ui.rounded(0, 12)));
                    row.setOnClickListener(v -> {
                        finder.cancel();
                        dialog[0].dismiss();
                        use(f.host);
                    });
                    list.addView(row);
                });
            }

            @Override
            public void done(List<BoxFinder.Found> all) {
                main.post(() -> {
                    if (!dialog[0].isShowing()) return;
                    if (all.size() == 1) {   // the usual case: no choice to make
                        dialog[0].dismiss();
                        use(all.get(0).host);
                        return;
                    }
                    status.setText(all.isEmpty()
                            ? "No box answered. Check that the box is on and on the same Wi-Fi as the phone. "
                            + "Kodi: Settings → Services → Control → Allow remote control from applications "
                            + "on other systems. Android TV: the Google TV remote service must be on."
                            : "Tap your box.");
                });
            }
        });
    }

    void askAddress() {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(prefs.getString(Tuning.BOX_HOST, ""));
        field.setHint("192.168.1.50");
        field.setSingleLine();
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        field.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(activity, Cards.DIALOG_THEME)
                .setTitle("Box address")
                .setMessage("The IP address of the box. On the box: Settings → Network → your Wi-Fi "
                        + "(Kodi: Settings → System information → Network).")
                .setView(field)
                .setPositiveButton("Connect", (d, w) -> {
                    String host = field.getText().toString().trim();
                    if (!host.isEmpty()) use(host);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Saves the address, then Kodi: ready; Android TV: pair. A dual-boot box may run either. */
    void use(String host) {
        prefs.edit().putString(Tuning.BOX_HOST, host).putInt(Tuning.TARGET, 1).apply();
        AlertDialog wait = new AlertDialog.Builder(activity, Cards.DIALOG_THEME)
                .setTitle("Connecting")
                .setMessage("Connecting to " + host + "…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            boolean kodi = BoxLink.kodiAt(host);
            main.post(() -> {
                wait.dismiss();
                if (activity.isFinishing()) return;
                if (!kodi) {
                    pair();
                    return;
                }
                haptics.play(Pattern.READY);
                listener.done(true, "Kodi · " + host + " · no pairing needed");
            });
        }, "box-check").start();
    }

    void pair() {
        String host = prefs.getString(Tuning.BOX_HOST, "");
        if (host.isEmpty()) {
            find();
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(activity, Cards.DIALOG_THEME)
                .setTitle("Pair")
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
                haptics.play(ok ? Pattern.READY : Pattern.ERROR);
                listener.done(ok, ok ? "Paired · " + host : message);
            }
        });
        pairing.start();
    }

    private AlertDialog askCode(int length) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(length),
                new android.text.InputFilter.AllCaps()});
        field.setHint(length == 4 ? "4D35" : "4D35A1");
        field.setSingleLine();
        field.setTextSize(24);
        field.setGravity(Gravity.CENTER);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        field.setPadding(pad, pad, pad, pad);
        AlertDialog d = new AlertDialog.Builder(activity, Cards.DIALOG_THEME)
                .setTitle("Code on the TV")
                .setMessage("The TV shows a code now. Type its " + length + " characters.")
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
}
