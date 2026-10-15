package in.weenja.bttvremote.core;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import in.weenja.bttvremote.common.Protocol;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Owns the one GATT server of the app. The server must live as long as the process:
 * a second server gets new attribute handles, and the LG TV does not discover them again.
 * Advertising (so the TV can connect) runs only while the remote or this app is on screen, or for a while
 * after a key from another path, and stops when the TV connects. The connected link stays.
 */
public class CoreService extends Service {

    static final String PREFS = "core";
    static final String KEY_HOST = "host";                 // preferred host address
    static final String KEY_PAIRED_HANDLE = "pairedHandle"; // HID handle when the host last subscribed
    static final String KEY_SCREEN_OFF_VOLUME = "screenOffVolume";
    static final String KEY_TV = "tvType";
    /** Start intent extra: advertise for one window, as after a key (StartActivity). */
    static final String EXTRA_WAKE = "wake";

    private static final String CHANNEL = "core";
    private static final int NOTIFICATION = 1;
    private static final long SCREEN_OFF_VOLUME_WINDOW_MS = 10 * 60 * 1000;

    /** Lives with the process, not with the service object. Never create a second one. */
    private static BleHid ble;
    private static CoreService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Messenger> clients = new ArrayList<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private SharedPreferences prefs;
    private KeyQueue queue;
    private VolumeSession volume;
    private boolean foreground;
    private int hidHandle = -1;
    private long lastActionAt;

    /** CoreActivity is on screen (pairing, test keys): advertise without a time limit. */
    private static boolean coreScreen;
    private boolean remoteOnScreen;
    /** Ends of the advertising windows (elapsedRealtime): the remote on screen, a key from another path. */
    private long remoteUntil, keyUntil;
    private boolean hadHost;

    static CoreService instance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        queue = new KeyQueue(main, (id, data) -> {
            try {
                return ble != null ? ble.send(id, data) : BleHid.NO_HOST;
            } catch (SecurityException e) {
                return BleHid.NO_HOST;
            }
        });
        volume = new VolumeSession(this, (action, result) -> {
            wakeLink();
            action(action, result);
        });
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(systemReceiver, filter);
        startBle();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent != null && intent.getBooleanExtra(EXTRA_WAKE, false)) wakeLink();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        // The server stays open on purpose (see the class comment). Only the service object goes away.
        main.removeCallbacks(advertiseCheck);
        if (ble != null) ble.setAdvertising(false);   // nothing would stop it later
        unregisterReceiver(systemReceiver);
        volume.release();
        instance = null;
        super.onDestroy();
    }

    // ---- actions ----

    /** Sends one logical action. result may be null. */
    void action(int action, KeyQueue.Result result) {
        Profile.Key key = Profile.forAction(action, prefs.getInt(KEY_TV, Protocol.TV_LG));
        if (key == null) {
            if (result != null) result.done(false);
            return;
        }
        lastActionAt = SystemClock.elapsedRealtime();
        queue.key(key, result);
    }

    void type(String text, KeyQueue.Result result) {
        boolean all = true;
        for (int i = 0; i < text.length(); i++) {
            Profile.Key key = Profile.forChar(text.charAt(i));
            if (key == null) {
                all = false;
                continue;
            }
            boolean last = i == text.length() - 1;
            queue.key(key, last && result != null ? ok -> result.done(ok) : null);
        }
        if (!all) log("Some characters have no key and were skipped");
    }

    // ---- advertising ----

    /** A key from another path (deep link, volume keys) while no TV is connected: advertise for a while. */
    void wakeLink() {
        wake(false);
    }

    /** A key from the remote extends its window; without MSG_ADVERTISE (older remote) it opens a key window. */
    private void wake(boolean fromRemote) {
        if (ble == null || ble.host() != null) return;
        long end = SystemClock.elapsedRealtime() + Protocol.ADVERTISE_MS;
        if (fromRemote && remoteOnScreen) remoteUntil = end;
        else keyUntil = end;
        updateAdvertising();
    }

    /** CoreActivity calls it from onResume and onPause. */
    static void setCoreScreen(boolean on) {
        coreScreen = on;
        CoreService s = instance;
        if (s != null) s.updateAdvertising();
    }

    private void updateAdvertising() {
        main.removeCallbacks(advertiseCheck);
        long now = SystemClock.elapsedRealtime();
        long until = Math.max(remoteUntil, keyUntil);
        boolean want = ble != null && ble.isStarted() && ble.host() == null && (coreScreen || until > now);
        if (want && until > now) main.postDelayed(advertiseCheck, until - now);
        if (ble != null) ble.setAdvertising(want);
        queue.setHold(want);
    }

    private final Runnable advertiseCheck = () -> {
        if (ble != null && ble.host() == null && !coreScreen) {
            log("No TV link in " + Protocol.ADVERTISE_MS / 1000 + " s: idle");
        }
        updateAdvertising();
    };

    void selectHost(BluetoothDevice device) {
        prefs.edit().putString(KEY_HOST, device.getAddress()).apply();
        if (ble != null) ble.select(device);
    }

    List<BluetoothDevice> connectedDevices() {
        try {
            return ble != null ? ble.connected() : new ArrayList<>();
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
    }

    // ---- state ----

    private void startBle() {
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (!hasPermissions()) {
            log("No Bluetooth permission: open Remote Core");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            log("Bluetooth is off");
            return;
        }
        try {
            if (ble == null) {
                ble = new BleHid(getApplicationContext(), Profile.DESCRIPTOR, bleListener);
            }
            ble.setPreferred(prefs.getString(KEY_HOST, null));
            ble.start();   // a no-op if the server is open
        } catch (SecurityException e) {
            log("BLE: " + e.getMessage());
        }
        updateAdvertising();
        pushStatus();
    }

    /** The listener outlives this service object, so it reaches the service through instance(). */
    private static final BleHid.Listener bleListener = new BleHid.Listener() {
        @Override
        public void log(String line) {
            CoreService s = instance;
            if (s != null) s.main.post(() -> s.log(line));
        }

        @Override
        public void hostChanged(BluetoothDevice host) {
            CoreService s = instance;
            if (s == null) return;
            s.main.post(() -> {
                s.log(host != null ? "Host: " + s.name(host) : "Host disconnected");
                if (host == null) {
                    s.queue.clear();
                    // The TV went away while the remote is on screen: advertise again, one more window
                    if (s.remoteOnScreen) s.remoteUntil = SystemClock.elapsedRealtime() + Protocol.ADVERTISE_MS;
                } else {
                    s.remoteUntil = 0;
                    s.keyUntil = 0;
                    if (!s.hadHost) s.queue.hostConnected();   // keys that waited for the TV go now
                }
                s.hadHost = host != null;
                s.updateAdvertising();   // stops it when the TV is connected
                s.pushStatus();
            });
        }

        @Override
        public void notificationSent() {
            CoreService s = instance;
            if (s != null) s.queue.notificationSent();
        }

        @Override
        public void hidHandle(int handle) {
            CoreService s = instance;
            if (s == null) return;
            s.main.post(() -> {
                s.hidHandle = handle;
                int paired = s.prefs.getInt(KEY_PAIRED_HANDLE, -1);
                s.log("HID handle " + handle + (paired < 0 ? " (no pairing yet)"
                        : paired == handle ? " (same as at pairing)" : " (pairing had " + paired + ": STALE)"));
                s.pushStatus();
            });
        }

        @Override
        public void subscribed(BluetoothDevice device) {
            CoreService s = instance;
            if (s == null) return;
            s.main.post(() -> {
                String preferred = s.prefs.getString(KEY_HOST, null);
                if (preferred == null || preferred.equals(device.getAddress())) {
                    s.prefs.edit().putInt(KEY_PAIRED_HANDLE, s.hidHandle).apply();
                }
                s.pushStatus();
            });
        }

        @Override
        public void advertising(boolean on) {
            CoreService s = instance;
            if (s != null) s.main.post(s::pushStatus);
        }
    };

    private boolean isStale() {
        int paired = prefs.getInt(KEY_PAIRED_HANDLE, -1);
        return paired >= 0 && hidHandle >= 0 && paired != hidHandle;
    }

    int state() {
        if (ble == null || !ble.isStarted()) return Protocol.STATE_OFF;
        return ble.host() != null ? Protocol.STATE_CONNECTED : Protocol.STATE_WAITING;
    }

    String statusLine() {
        int s = state();
        String host = ble != null && ble.host() != null ? name(ble.host()) : null;
        return (s == Protocol.STATE_CONNECTED ? "Connected: " + host
                : s == Protocol.STATE_WAITING ? (isAdvertising() ? "Waiting for the TV" : "Idle: the link starts with the remote")
                : "Off")
                + (isStale() ? " (pairing STALE: pair again)" : "");
    }

    /** The phone advertises: the TV can connect now. */
    boolean isAdvertising() {
        return ble != null && ble.isAdvertising();
    }

    /** The TV that gets the keys, or null. */
    String hostName() {
        return ble != null && ble.host() != null ? name(ble.host()) : null;
    }

    String logText() {
        return String.join("\n", log);
    }

    void pushStatus() {
        int flags = (foreground ? Protocol.FLAG_FOREGROUND : 0) | (isStale() ? Protocol.FLAG_STALE : 0)
                | (isAdvertising() ? Protocol.FLAG_ADVERTISING : 0);
        BluetoothDevice host = ble != null ? ble.host() : null;
        for (int i = clients.size() - 1; i >= 0; i--) {
            Message m = Message.obtain(null, Protocol.MSG_STATUS, state(), flags);
            Bundle data = new Bundle();
            if (host != null) {
                data.putString("host", name(host));
                data.putString("address", host.getAddress());
            }
            data.putString("log", logText());
            m.setData(data);
            try {
                clients.get(i).send(m);
            } catch (RemoteException e) {
                clients.remove(i);
            }
        }
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        if (statusListener != null) statusListener.run();
    }

    /** CoreActivity listens here (same process). */
    static Runnable statusListener;

    void log(String line) {
        Log.i("TvCore", line);
        if (log.size() == 40) log.removeFirst();
        log.addLast(time.format(new Date()) + " " + line);
        if (statusListener != null) statusListener.run();
    }

    String name(BluetoothDevice device) {
        try {
            String n = device.getName();
            return n != null ? n : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    // ---- foreground ----

    private void goForeground() {
        if (foreground) return;
        if (!hasPermissions()) {
            log("Cannot run in the foreground without Bluetooth permission");
            stopSelf();
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "TV link", NotificationManager.IMPORTANCE_LOW));
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            foreground = true;
        } catch (RuntimeException e) {
            log("Foreground start failed: " + e.getMessage());
        }
        startBle();
        pushStatus();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, CoreActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Remote Core")
                .setContentText(statusLine())
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    // ---- system events ----

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    log("Bluetooth on: new GATT server (new handles; the TV may need a new pairing)");
                    startBle();
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    if (ble != null) {
                        ble.stop();
                        ble = null;
                        hidHandle = -1;
                    }
                    queue.clear();
                    hadHost = false;
                    updateAdvertising();
                    pushStatus();
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                boolean recent = SystemClock.elapsedRealtime() - lastActionAt < SCREEN_OFF_VOLUME_WINDOW_MS;
                if (recent && lastActionAt > 0 && prefs.getBoolean(KEY_SCREEN_OFF_VOLUME, false)) {
                    volume.setActive(true);
                    log("Screen off: volume keys go to the TV");
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                volume.setActive(false);
            }
        }
    };

    // ---- messages from TV Remote ----

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            Messenger reply = msg.replyTo;
            int token = msg.arg2;
            switch (msg.what) {
                case Protocol.MSG_REGISTER:
                    if (reply != null && !clients.contains(reply)) clients.add(reply);
                    pushStatus();
                    break;
                case Protocol.MSG_UNREGISTER:
                    clients.remove(reply);
                    break;
                case Protocol.MSG_ADVERTISE:
                    remoteOnScreen = msg.arg1 == 1;
                    remoteUntil = remoteOnScreen ? SystemClock.elapsedRealtime() + Protocol.ADVERTISE_MS : 0;
                    updateAdvertising();
                    break;
                case Protocol.MSG_ACTION:
                    wake(true);
                    action(msg.arg1, token == 0 || reply == null ? null : ok -> result(reply, token, ok));
                    break;
                case Protocol.MSG_TEXT:
                    String text = msg.getData().getString("text", "");
                    log("Text: " + text.length() + " characters");
                    wake(true);
                    type(text, token == 0 || reply == null ? null : ok -> result(reply, token, ok));
                    break;
                case Protocol.MSG_SET_TV:
                    prefs.edit().putInt(KEY_TV, msg.arg1).apply();
                    break;
                case Protocol.MSG_KEY:
                    byte[] press = msg.getData().getByteArray("press");
                    if (press == null || (msg.arg1 != Profile.ID_KEYBOARD && msg.arg1 != Profile.ID_CONSUMER)) {
                        if (token != 0 && reply != null) result(reply, token, false);
                        break;
                    }
                    lastActionAt = SystemClock.elapsedRealtime();
                    wake(true);
                    queue.key(new Profile.Key(msg.arg1, press),
                            token == 0 || reply == null ? null : ok -> result(reply, token, ok));
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    });

    private static void result(Messenger reply, int token, boolean ok) {
        try {
            reply.send(Message.obtain(null, Protocol.MSG_RESULT, token, ok ? 1 : 0));
        } catch (RemoteException ignored) {
        }
    }
}
