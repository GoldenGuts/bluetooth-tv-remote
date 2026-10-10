package in.weenja.bttvremote.core;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import in.weenja.bttvremote.common.Cards;
import in.weenja.bttvremote.common.Protocol;

import java.util.List;

/** Setup and status, in the same card style as the remote. Not needed for daily use. */
public class CoreActivity extends Activity {

    private TextView status;
    private TextView linkValue;
    private TextView tvValue;
    private TextView batteryValue;
    private TextView log;

    static boolean hasPermissions(Context c) {
        return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        getWindow().setStatusBarColor(Cards.BACKGROUND);
        getWindow().setNavigationBarColor(Cards.BACKGROUND);
        if (hasPermissions(this)) {
            startCore();
        } else {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (hasPermissions(this)) startCore();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CoreService.statusListener = () -> runOnUiThread(this::refresh);
        CoreService.setCoreScreen(true);   // the TV can find the phone while this screen is open (pairing)
        refresh();
    }

    @Override
    protected void onPause() {
        CoreService.statusListener = null;
        CoreService.setCoreScreen(false);
        super.onPause();
    }

    private void startCore() {
        startForegroundService(new Intent(this, CoreService.class));
    }

    private void refresh() {
        CoreService core = CoreService.instance();
        boolean connected = core != null && core.state() == Protocol.STATE_CONNECTED;
        if (!hasPermissions(this)) {
            status.setText("Bluetooth permission is needed");
        } else {
            status.setText(core != null ? core.statusLine() : "Starting…");
        }
        status.setTextColor(connected ? Cards.ACCENT : Cards.SUBTEXT);
        linkValue.setText(core == null ? "Off" : connected ? "Connected"
                : core.state() != Protocol.STATE_WAITING ? "Off"
                : core.isAdvertising() ? "Waiting for the TV" : "Idle");
        String host = core != null ? core.hostName() : null;
        tvValue.setText(host != null ? host : "None");
        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        batteryValue.setText(unrestricted ? "Allowed ✓" : "Restricted");
        batteryValue.setTextColor(unrestricted ? Cards.ACCENT : Cards.DANGER);
        log.setText(core != null && !core.logText().isEmpty() ? core.logText() : "No events yet");
    }

    private void chooseHost() {
        CoreService core = CoreService.instance();
        if (core == null) return;
        List<BluetoothDevice> devices = core.connectedDevices();
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = core.name(devices.get(i)) + "\n" + devices.get(i).getAddress();
        new AlertDialog.Builder(this, Cards.DIALOG_THEME)
                .setTitle(names.length == 0 ? "No Bluetooth LE device is connected" : "Send keys to")
                .setItems(names, (d, i) -> core.selectHost(devices.get(i)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void allowBackground() {
        // The list of apps; the direct request needs a permission that Google Play restricts.
        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    private void openSponsor() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Protocol.SPONSOR_URL)));
        } catch (RuntimeException ignored) {
        }
    }

    private void test(int action) {
        CoreService core = CoreService.instance();
        if (core != null) core.action(action, null);
    }

    // ---- ui ----

    private View buildUi() {
        Cards ui = new Cards(this);
        status = ui.title("Remote Core", "");
        status.setTextSize(15);

        ui.group("Bluetooth link");
        linkValue = ui.row("Link", "The TV can find the phone while this screen or the remote is open");
        tvValue = ui.row("TV", "The device that gets the keys");
        ((View) tvValue.getParent().getParent()).setOnClickListener(v -> chooseHost());
        ui.link("Choose TV", "If the keys go nowhere", this::chooseHost);

        ui.group("Keep it running");
        batteryValue = ui.row("Background use", "Needed so the link survives");
        ((View) batteryValue.getParent().getParent()).setOnClickListener(v -> allowBackground());
        SharedPreferences prefs = getSharedPreferences(CoreService.PREFS, MODE_PRIVATE);
        Switch volume = ui.switchRow("Volume keys with the screen off", "Test feature: may not work on every phone");
        volume.setChecked(prefs.getBoolean(CoreService.KEY_SCREEN_OFF_VOLUME, false));
        volume.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(CoreService.KEY_SCREEN_OFF_VOLUME, on).apply());

        ui.group("Test keys");
        ui.pills(new String[]{"◀", "▲", "▼", "▶"}, new Runnable[]{
                () -> test(Protocol.LEFT), () -> test(Protocol.UP), () -> test(Protocol.DOWN), () -> test(Protocol.RIGHT)});
        ui.pills(new String[]{"OK", "Back", "Home"}, new Runnable[]{
                () -> test(Protocol.OK), () -> test(Protocol.BACK), () -> test(Protocol.HOME)});

        ui.group(null);
        ui.link("Support the app", "Free and open source. Sponsor on GitHub", this::openSponsor);

        ui.group("Log");
        LinearLayout box = ui.rowBox();
        box.setBackground(null);
        log = ui.label("", 11, Cards.SUBTEXT);
        log.setTypeface(Typeface.MONOSPACE);
        box.addView(log);

        return ui.scroll();
    }
}
