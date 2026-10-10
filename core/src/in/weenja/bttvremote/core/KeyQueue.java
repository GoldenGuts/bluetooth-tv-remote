package in.weenja.bttvremote.core;

import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayDeque;

/**
 * Sends reports one at a time. The stack refuses a notification while the last one is still pending,
 * so each report waits for onNotificationSent (or a short timeout) and for the hold time of a key press.
 * Without a host, keys wait while the phone advertises (setHold): a key can wake the link and still arrive.
 */
final class KeyQueue {

    interface Sender {
        /** 0 = accepted, BleHid.NO_HOST, or another error code. */
        int send(int reportId, byte[] data);
    }

    interface Result {
        void done(boolean ok);
    }

    /** Key down time. The test app used 80 ms with success; 50 ms is not verified yet. */
    static final long HOLD_MS = 50;
    /** Pause after a release, before the next press. */
    static final long GAP_MS = 20;

    private static final long SENT_TIMEOUT_MS = 40;
    private static final int BUSY = 201;          // BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
    private static final int MAX_RETRIES = 5;
    /** A key waits this long for the TV to connect; later it fails. */
    static final long HOST_WAIT_MS = 10_000;
    /** After the host connects, waiting keys give the link this long to settle (encryption). Not verified on a TV. */
    private static final long SETTLE_MS = 1_000;

    private static final class Item {
        final int reportId;
        final byte[] data;
        final long waitAfter;
        final Result result;
        final long queuedAt = SystemClock.uptimeMillis();
        int retries;

        Item(int reportId, byte[] data, long waitAfter, Result result) {
            this.reportId = reportId;
            this.data = data;
            this.waitAfter = waitAfter;
            this.result = result;
        }
    }

    private final Handler handler;
    private final Sender sender;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private boolean waitingForSent;
    private long notBefore;
    private boolean hold;

    KeyQueue(Handler handler, Sender sender) {
        this.handler = handler;
        this.sender = sender;
    }

    /** Press and release. result (may be null) gets the outcome of the press report. */
    void key(Profile.Key key, Result result) {
        handler.post(() -> {
            queue.add(new Item(key.reportId, key.press, HOLD_MS, result));
            queue.add(new Item(key.reportId, key.release(), GAP_MS, null));
            pump();
        });
    }

    void clear() {
        handler.post(() -> {
            for (Item item : queue) if (item.result != null) item.result.done(false);
            queue.clear();
        });
    }

    /** true while the phone advertises: keys without a host wait for it (up to HOST_WAIT_MS). */
    void setHold(boolean hold) {
        handler.post(() -> {
            this.hold = hold;
            pump();
        });
    }

    /** The host connected: keys that wait for it go out after SETTLE_MS. */
    void hostConnected() {
        handler.post(() -> {
            if (queue.isEmpty()) return;
            notBefore = Math.max(notBefore, SystemClock.uptimeMillis() + SETTLE_MS);
            handler.removeCallbacks(pumpLater);
            handler.postAtTime(pumpLater, notBefore);
        });
    }

    /** Call from any thread. */
    void notificationSent() {
        handler.post(() -> {
            if (!waitingForSent) return;
            waitingForSent = false;
            handler.removeCallbacks(sentTimeout);
            pump();
        });
    }

    private final Runnable sentTimeout = () -> {
        waitingForSent = false;
        pump();
    };

    private final Runnable pumpLater = this::pump;

    private void pump() {
        if (waitingForSent || queue.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now < notBefore) {
            handler.removeCallbacks(pumpLater);
            handler.postAtTime(pumpLater, notBefore);
            return;
        }
        Item item = queue.peek();
        int status = sender.send(item.reportId, item.data);
        if (status == BleHid.NO_HOST && hold && now - item.queuedAt < HOST_WAIT_MS) {
            // hostConnected() sends it; if no host comes, it fails at the end of the wait
            handler.removeCallbacks(pumpLater);
            handler.postAtTime(pumpLater, item.queuedAt + HOST_WAIT_MS);
            return;
        }
        if (status == BUSY && item.retries++ < MAX_RETRIES) {
            notBefore = now + 10;
            handler.postAtTime(pumpLater, notBefore);
            return;
        }
        queue.poll();
        if (item.result != null) item.result.done(status == 0);
        notBefore = now + item.waitAfter;
        if (status == 0) {
            waitingForSent = true;
            handler.postDelayed(sentTimeout, SENT_TIMEOUT_MS);
        } else {
            handler.postAtTime(pumpLater, notBefore);
        }
    }
}
