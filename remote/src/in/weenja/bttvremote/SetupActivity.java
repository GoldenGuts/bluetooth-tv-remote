package in.weenja.bttvremote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import in.weenja.bttvremote.common.Cards;
import in.weenja.bttvremote.common.Protocol;

/**
 * First start: asks which TV and which box, then shows only the steps for those devices, with a check mark
 * as each one works. Then the short tutorial. Settings and ••• can run it again.
 *
 * LG and other TVs: Bluetooth through Remote Core. Android TV or Google TV sets: Wi-Fi (their Bluetooth
 * accessory list hides a phone). Android TV and Kodi boxes: Wi-Fi.
 */
public class SetupActivity extends Activity {

    static final int TV_LG = 0, TV_ANDROID = 1, TV_OTHER = 2;
    static final int BOX_NONE = 0, BOX_ANDROID = 1, BOX_KODI = 2;
    private static final int PAGE_TV = 0, PAGE_BOX = 1, PAGE_CONNECT = 2;

    private SharedPreferences prefs;
    private Haptics haptics;
    private CoreClient core;
    private BoxSetup boxSetup;

    private int page = PAGE_TV;
    private int tv = -1, box = -1;

    // the connect page
    private TextView coreValue, pairValue, boxValue;
    private String boxResult;
    private boolean boxOk;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(Tuning.PREFS, MODE_PRIVATE);
        haptics = new Haptics(getSystemService(Vibrator.class));
        core = new CoreClient(this, (s, f, d) -> updateCore());
        boxSetup = new BoxSetup(this, prefs, haptics, this::boxDone);
        if (state != null) {
            page = state.getInt("page");
            tv = state.getInt("tv", -1);
            box = state.getInt("box", -1);
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (page == PAGE_TV) finish();
            else show(page == PAGE_CONNECT && tv != TV_ANDROID ? PAGE_BOX : PAGE_TV);
        });
        show(page);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
        out.putInt("tv", tv);
        out.putInt("box", box);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bluetoothTv()) {
            core.onScreen(true);   // the core advertises: the TV can find the phone while this page is open
            core.bind();
        }
        if (page == PAGE_CONNECT) updateCore();
    }

    @Override
    protected void onPause() {
        core.onScreen(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        core.unbind();
        boxSetup.cancel();
        super.onDestroy();
    }

    private boolean bluetoothTv() {
        return tv == TV_LG || tv == TV_OTHER;
    }

    private boolean wifiDevice() {
        return tv == TV_ANDROID || box == BOX_ANDROID || box == BOX_KODI;
    }

    private void show(int p) {
        page = p;
        Cards ui = new Cards(this);
        switch (p) {
            case PAGE_TV:
                ui.title("Set up", "Which TV do you have?");
                ui.group(null);
                ui.link("LG TV", "webOS · connects over Bluetooth", () -> pickTv(TV_LG));
                ui.link("Android TV or Google TV", "Sony, TCL, Philips, Hisense… · connects over Wi-Fi",
                        () -> pickTv(TV_ANDROID));
                ui.link("Another TV", "Samsung, Fire TV, others · Bluetooth keyboard (not tested)",
                        () -> pickTv(TV_OTHER));
                ui.group(null);
                ui.link("Skip the setup", "Settings can do it later", this::finishSetup);
                break;
            case PAGE_BOX:
                ui.title("Set up", "Do you watch through a box on the TV?");
                ui.group(null);
                ui.link("No, only the TV", null, () -> pickBox(BOX_NONE));
                ui.link("Android TV or Google TV box", "Shield, Chromecast with Google TV, Ugoos…",
                        () -> pickBox(BOX_ANDROID));
                ui.link("Kodi box", "CoreELEC, LibreELEC, OSMC", () -> pickBox(BOX_KODI));
                break;
            default:
                buildConnect(ui);
        }
        setContentView(ui.scroll());
        getWindow().setStatusBarColor(Cards.BACKGROUND);
        getWindow().setNavigationBarColor(Cards.BACKGROUND);
        if (p == PAGE_CONNECT && bluetoothTv()) {
            core.onScreen(true);   // the core advertises: the TV can find the phone while this page is open
            core.bind();
            updateCore();
        }
    }

    private void pickTv(int t) {
        tv = t;
        if (t == TV_ANDROID) {
            box = BOX_NONE;   // one Wi-Fi link: the TV is the box
            show(PAGE_CONNECT);
        } else {
            show(PAGE_BOX);
        }
    }

    private void pickBox(int b) {
        box = b;
        show(PAGE_CONNECT);
    }

    // ---- connect ----

    private void buildConnect(Cards ui) {
        ui.title("Connect", "Two minutes. Each step gets a check mark when it works.");
        int step = 1;
        coreValue = pairValue = boxValue = null;
        if (bluetoothTv()) {
            ui.group(step++ + " · The TV, over Bluetooth");
            coreValue = ui.row("Install Remote Core", "A small free app that keeps the Bluetooth link");
            ((View) coreValue.getParent().getParent()).setOnClickListener(v -> installCore());
            pairValue = ui.row("Pair the TV with this phone", pairSteps());
            ((View) pairValue.getParent().getParent()).setOnClickListener(v -> core.openCoreApp());
            ui.link("Not in the TV's list?", "Open Remote Core and keep it open while the TV searches",
                    core::openCoreApp);
        }
        if (wifiDevice()) {
            String what = tv == TV_ANDROID ? "The TV" : box == BOX_KODI ? "The Kodi box" : "The box";
            ui.group(step + " · " + what + ", over Wi-Fi");
            boxValue = ui.row(tv == TV_ANDROID ? "Find the TV" : "Find the box",
                    "Turn it on. The phone searches your Wi-Fi."
                            + (box == BOX_KODI ? " No pairing for Kodi." : " Then type the code it shows."));
            ((View) boxValue.getParent().getParent()).setOnClickListener(v -> boxSetup.find());
            boxValue.setText(boxOk ? "✓" : "Search");
            if (boxResult != null) {
                TextView result = ui.label(boxResult, 14, boxOk ? Cards.ACCENT : Cards.DANGER);
                result.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), 0);
                ui.page.addView(result);
            }
        }
        ui.group(null);
        ui.link("Next: learn 5 gestures", "About a minute. Nothing goes to the TV.", this::next);
    }

    private String pairSteps() {
        String phone = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (phone == null || phone.isEmpty()) phone = android.os.Build.MODEL;
        if (tv == TV_LG) {
            return "On the TV: Settings → All Settings → General → External Devices → Connect Bluetooth "
                    + "Controller (2026 TVs: System → Devices). Choose \"" + phone + "\" and accept the number "
                    + "on the TV and on the phone.";
        }
        return "On the TV, open its Bluetooth settings (often Bluetooth devices or Input devices), choose \""
                + phone + "\" and accept the number on both.";
    }

    private boolean coreInstalled() {
        try {
            getPackageManager().getPackageInfo(Protocol.CORE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void updateCore() {
        if (page != PAGE_CONNECT || coreValue == null) return;
        boolean installed = coreInstalled();
        coreValue.setText(installed ? "✓" : "Install");
        if (installed) core.bind();
        boolean paired = core.state == Protocol.STATE_CONNECTED && (core.flags & Protocol.FLAG_STALE) == 0;
        pairValue.setText(!installed ? "" : paired ? "✓ Connected" : (core.flags & Protocol.FLAG_STALE) != 0
                ? "Pair again" : "Waiting");
    }

    private void installCore() {
        if (coreInstalled()) {
            core.openCoreApp();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + Protocol.CORE_PACKAGE)));
        } catch (RuntimeException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://play.google.com/store/apps/details?id=" + Protocol.CORE_PACKAGE)));
        }
    }

    private void boxDone(boolean ok, String message) {
        boxOk = ok;
        boxResult = ok ? message : message + " · tap Find to try again";
        if (page == PAGE_CONNECT) show(PAGE_CONNECT);
    }

    /** Saves the choices; the remote reads them on resume. */
    private void apply() {
        SharedPreferences.Editor e = prefs.edit();
        e.putInt(Tuning.TV_TYPE, tv == TV_LG ? Protocol.TV_LG : Protocol.TV_ANDROID);
        e.putInt(Tuning.TV_WIFI, tv == TV_ANDROID ? 1 : 0);
        if (wifiDevice()) {
            e.putInt(Tuning.TARGET, 1);
        } else {
            e.putInt(Tuning.TARGET, 0);
        }
        e.apply();
    }

    private void next() {
        apply();
        finishSetup();
        startActivity(new Intent(this, TutorialActivity.class));
    }

    private void finishSetup() {
        prefs.edit().putBoolean(Tuning.SETUP_DONE, true).putBoolean("tutorialSeen", true).apply();
        finish();
    }
}
