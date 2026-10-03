package in.weenja.bttvremote.core;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** HID over GATT: the phone advertises as a Bluetooth LE keyboard. */
final class BleHid {

    interface Listener {
        void log(String line);

        void hostChanged(BluetoothDevice host);

        /** The stack sent the last notification: the next report may go. */
        void notificationSent();

        /** Attribute handle of the HID service. The TV caches it at pairing. */
        void hidHandle(int handle);

        /** A device wrote a report CCCD: it discovered the services with the current handles. */
        void subscribed(BluetoothDevice device);
    }

    /** send() result when no host is selected. */
    static final int NO_HOST = -1;

    private static final UUID HID_SERVICE = uuid(0x1812);
    private static final UUID REPORT = uuid(0x2A4D);
    private static final UUID REPORT_MAP = uuid(0x2A4B);
    private static final UUID CCCD = uuid(0x2902);
    private static final UUID REPORT_REFERENCE = uuid(0x2908);

    private static final int READ = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED;
    private static final int WRITE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED;

    private final Context context;
    private final BluetoothManager manager;
    private final byte[] reportMap;
    private final Listener listener;

    private final Map<Object, byte[]> values = new HashMap<>();
    private final Map<Integer, BluetoothGattCharacteristic> reports = new HashMap<>();
    private final ArrayDeque<BluetoothGattService> pending = new ArrayDeque<>();

    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private volatile BluetoothDevice host;
    private volatile String preferred;

    BleHid(Context context, byte[] reportMap, Listener listener) {
        this.context = context;
        this.manager = context.getSystemService(BluetoothManager.class);
        this.reportMap = reportMap;
        this.listener = listener;
    }

    boolean isConnected() {
        return host != null;
    }

    /** Address of the host that gets the reports when more than one device is connected. */
    void setPreferred(String address) {
        preferred = address;
    }

    /** All LE links of the phone. This includes devices that are not HID hosts. */
    java.util.List<BluetoothDevice> connected() throws SecurityException {
        return manager.getConnectedDevices(BluetoothProfile.GATT_SERVER);
    }

    void select(BluetoothDevice device) {
        preferred = device.getAddress();
        host = null;
        setHost(device);
    }

    private boolean mayBeHost(BluetoothDevice device) {
        return preferred == null || preferred.equals(device.getAddress());
    }

    void start() throws SecurityException {
        if (server != null) {
            advertise();
            return;
        }
        server = manager.openGattServer(context, callback);
        context.registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));

        BluetoothGattService hid = new BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        hid.addCharacteristic(readOnly(0x2A4A, new byte[]{0x11, 0x01, 0x00, 0x02}));   // HID information
        hid.addCharacteristic(readOnly(0x2A4B, reportMap));
        hid.addCharacteristic(new BluetoothGattCharacteristic(uuid(0x2A4C),            // control point
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, WRITE));
        BluetoothGattCharacteristic mode = new BluetoothGattCharacteristic(uuid(0x2A4E),
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                READ | WRITE);
        values.put(mode, new byte[]{1});                                               // report protocol
        hid.addCharacteristic(mode);
        hid.addCharacteristic(inputReport(1, 8));
        hid.addCharacteristic(inputReport(2, 2));

        BluetoothGattService info = new BluetoothGattService(uuid(0x180A), BluetoothGattService.SERVICE_TYPE_PRIMARY);
        info.addCharacteristic(readOnly(0x2A29, "BT TV Remote".getBytes()));
        info.addCharacteristic(readOnly(0x2A50, new byte[]{0x02, 0x34, 0x12, 0x01, 0x00, 0x01, 0x00})); // PnP id

        BluetoothGattService battery = new BluetoothGattService(uuid(0x180F), BluetoothGattService.SERVICE_TYPE_PRIMARY);
        battery.addCharacteristic(readOnly(0x2A19, new byte[]{100}));

        // The stack accepts only one addService call at a time.
        pending.add(info);
        pending.add(battery);
        server.addService(hid);
    }

    void stop() {
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
            if (server != null) {
                context.unregisterReceiver(bondReceiver);
                server.close();
            }
        } catch (SecurityException ignored) {
        }
        server = null;
        host = null;
    }

    BluetoothDevice host() {
        return host;
    }

    boolean isStarted() {
        return server != null;
    }

    /** Returns 0 (BluetoothStatusCodes.SUCCESS), a stack error code, or NO_HOST. */
    int send(int id, byte[] data) throws SecurityException {
        BluetoothGattCharacteristic c = reports.get(id);
        BluetoothDevice target = host;
        if (server == null || target == null || c == null) return NO_HOST;
        int result = server.notifyCharacteristicChanged(target, c, false, data);
        Log.i("BleHid", "notify report " + id + " " + Arrays.toString(data) + " -> " + result);
        return result;
    }

    private BluetoothGattCharacteristic readOnly(int shortUuid, byte[] value) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(uuid(shortUuid),
                BluetoothGattCharacteristic.PROPERTY_READ, READ);
        values.put(c, value);
        return c;
    }

    private BluetoothGattCharacteristic inputReport(int id, int length) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY, READ);
        values.put(c, new byte[length]);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD, READ | WRITE);
        values.put(cccd, new byte[]{0, 0});
        c.addDescriptor(cccd);
        BluetoothGattDescriptor reference = new BluetoothGattDescriptor(REPORT_REFERENCE, READ);
        values.put(reference, new byte[]{(byte) id, 1});                               // 1 = input report
        c.addDescriptor(reference);
        reports.put(id, c);
        return c;
    }

    private void advertise() throws SecurityException {
        advertiser = manager.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) {
            listener.log("BLE: this phone cannot advertise");
            return;
        }
        advertiser.stopAdvertising(advertiseCallback);
        advertiser.startAdvertising(
                new AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(true)
                        .setTimeout(0)
                        .build(),
                new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(HID_SERVICE)).build(),
                new AdvertiseData.Builder().setIncludeDeviceName(true).build(),
                advertiseCallback);
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settings) {
            listener.log("BLE: advertising as a keyboard");
        }

        @Override
        public void onStartFailure(int error) {
            listener.log("BLE: advertise failed, code " + error);
        }
    };

    /**
     * When Android refuses a new pairing from a host, it deletes the bond but the link stays open.
     * The host cannot pair again until that link is closed.
     */
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            if (device == null) return;
            listener.log("BLE bond " + device.getAddress() + ": "
                    + (state == BluetoothDevice.BOND_BONDED ? "bonded" : state == BluetoothDevice.BOND_BONDING ? "pairing" : "NONE"));
            if (state != BluetoothDevice.BOND_NONE || server == null) return;
            try {
                server.cancelConnection(device);
            } catch (SecurityException ignored) {
            }
            if (device.equals(host)) {
                host = null;
                listener.hostChanged(null);
            }
        }
    };

    private void setHost(BluetoothDevice device) {
        if (device.equals(host)) return;
        host = device;
        listener.hostChanged(device);
    }

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            listener.log("BLE: service " + shortId(service.getUuid()) + " handle " + service.getInstanceId()
                    + (status != 0 ? " status " + status : ""));
            if (HID_SERVICE.equals(service.getUuid())) listener.hidHandle(service.getInstanceId());
            try {
                if (!pending.isEmpty()) server.addService(pending.poll());
                else advertise();
            } catch (SecurityException e) {
                listener.log("BLE: " + e.getMessage());
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            trace(device, "connection state " + newState + " status " + status);
            // This also fires for links where the phone is the central (watch, earbuds).
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.log("BLE link: " + device.getAddress());
                try {
                    if (device.getBondState() != BluetoothDevice.BOND_BONDED) return;
                    server.connect(device, false);   // keeps the link when advertising stops
                } catch (SecurityException ignored) {
                }
                // A bonded host does not subscribe again after a reconnect.
                if (device.getAddress().equals(preferred)) setHost(device);
            } else if (device.equals(host)) {
                host = null;
                listener.hostChanged(null);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                BluetoothGattCharacteristic characteristic) {
            trace(device, "read char " + shortId(characteristic.getUuid()) + " offset " + offset);
            // A bonded PC can read this too, so only a report subscription selects the host.
            if (REPORT_MAP.equals(characteristic.getUuid()) && offset == 0) {
                listener.log("BLE: " + device.getAddress() + " reads the report map");
            }
            respond(device, requestId, offset, values.get(characteristic));
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                BluetoothGattDescriptor descriptor) {
            trace(device, "read desc " + shortId(descriptor.getUuid()) + " of " + shortId(descriptor.getCharacteristic().getUuid()));
            respond(device, requestId, offset, values.get(descriptor));
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            trace(device, "write char " + shortId(characteristic.getUuid()) + " = " + Arrays.toString(value));
            if (responseNeeded) respond(device, requestId, 0, null);
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            trace(device, "mtu " + mtu);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            trace(device, "notification sent, status " + status);
            listener.notificationSent();
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            trace(device, "write desc " + shortId(descriptor.getUuid()) + " of "
                    + shortId(descriptor.getCharacteristic().getUuid()) + " = " + Arrays.toString(value));
            if (CCCD.equals(descriptor.getUuid())) {
                values.put(descriptor, value);
                listener.log("BLE: " + device.getAddress() + " subscribed");
                listener.subscribed(device);
                if (mayBeHost(device)) {
                    host = null;
                    setHost(device);
                }
            }
            if (responseNeeded) respond(device, requestId, 0, null);
        }
    };

    private void respond(BluetoothDevice device, int requestId, int offset, byte[] value) {
        byte[] part = null;
        if (value != null) part = offset >= value.length ? new byte[0] : Arrays.copyOfRange(value, offset, value.length);
        try {
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part);
        } catch (SecurityException ignored) {
        }
    }

    private static void trace(BluetoothDevice device, String what) {
        Log.i("BleHid", device.getAddress() + " " + what);
    }

    private static String shortId(UUID uuid) {
        return uuid.toString().substring(4, 8);
    }

    private static UUID uuid(int shortUuid) {
        return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", shortUuid));
    }
}
