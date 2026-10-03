package in.weenja.bttvremote.core;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import in.weenja.bttvremote.common.Protocol;

import java.util.List;

/** Setup and status. Not needed for daily use: TV Remote starts the core by itself. */
public class CoreActivity extends Activity {

    private TextView status;
    private TextView log;
    private TextView battery;

    static boolean hasPermissions(Context c) {
        return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
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
        else status.setText("Bluetooth permission is needed");
    }

    @Override
    protected void onResume() {
        super.onResume();
        CoreService.statusListener = () -> runOnUiThread(this::refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        CoreService.statusListener = null;
        super.onPause();
    }

    private void startCore() {
        startForegroundService(new Intent(this, CoreService.class));
    }

    private void refresh() {
        CoreService core = CoreService.instance();
        status.setText(core != null ? core.statusLine() : "Core service is not running");
        status.setTextColor(core != null && core.state() == Protocol.STATE_CONNECTED ? Color.GREEN : Color.LTGRAY);
        log.setText(core != null ? core.logText() : "");
        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        battery.setText(unrestricted ? "Battery: unrestricted ✓" : "Battery: optimized (tap to allow background)");
    }

    private void chooseHost() {
        CoreService core = CoreService.instance();
        if (core == null) return;
        List<BluetoothDevice> devices = core.connectedDevices();
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = core.name(devices.get(i)) + "\n" + devices.get(i).getAddress();
        new AlertDialog.Builder(this)
                .setTitle(names.length == 0 ? "No Bluetooth LE device is connected" : "Send keys to")
                .setItems(names, (d, i) -> core.selectHost(devices.get(i)))
                .show();
    }

    private void allowBackground() {
        if (getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) return;
        // The list of apps; the direct request needs a permission that Google Play restricts.
        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    private void test(int action) {
        CoreService core = CoreService.instance();
        if (core != null) core.action(action, null);
    }

    // ---- ui ----

    private android.view.View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        status = text(root, 20, Color.LTGRAY);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        battery = text(root, 14, Color.GRAY);
        battery.setPadding(0, dp(8), 0, dp(8));
        battery.setOnClickListener(v -> allowBackground());

        LinearLayout row = row(root);
        button(row, "Start", this::startCore);
        button(row, "Choose TV", this::chooseHost);

        SharedPreferences prefs = getSharedPreferences(CoreService.PREFS, MODE_PRIVATE);
        Switch volume = new Switch(this);
        volume.setText("Volume keys with the screen off (test)");
        volume.setTextColor(Color.LTGRAY);
        volume.setChecked(prefs.getBoolean(CoreService.KEY_SCREEN_OFF_VOLUME, false));
        volume.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(CoreService.KEY_SCREEN_OFF_VOLUME, on).apply());
        volume.setPadding(0, dp(12), 0, dp(12));
        root.addView(volume);

        TextView testTitle = text(root, 14, Color.GRAY);
        testTitle.setText("Test keys (without TV Remote)");
        LinearLayout keys = row(root);
        button(keys, "◀", () -> test(Protocol.LEFT));
        button(keys, "▲", () -> test(Protocol.UP));
        button(keys, "▼", () -> test(Protocol.DOWN));
        button(keys, "▶", () -> test(Protocol.RIGHT));
        LinearLayout keys2 = row(root);
        button(keys2, "OK", () -> test(Protocol.OK));
        button(keys2, "Back", () -> test(Protocol.BACK));
        button(keys2, "Home", () -> test(Protocol.HOME));

        log = new TextView(this);
        log.setTextSize(11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextColor(Color.GRAY);
        log.setPadding(0, dp(12), 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private TextView text(LinearLayout parent, int size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        parent.addView(t);
        return t;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void button(LinearLayout row, String label, Runnable run) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(dp(56));
        b.setOnClickListener(v -> run.run());
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
