package in.weenja.bttvremote;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.SparseArray;

import in.weenja.bttvremote.common.Protocol;

/** The link to Remote Core. Every send gets an answer: the core result, or "failed" after a timeout. */
final class CoreClient {

    interface Listener {
        void status(int state, int flags, Bundle data);
    }

    interface Result {
        void done(boolean ok);
    }

    /** No answer in this time = the key did not go out. */
    private static final long RESULT_TIMEOUT_MS = 150;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseArray<Result> pending = new SparseArray<>();
    private final Messenger replies;

    private Messenger core;
    private boolean bound;
    private boolean startRequested;
    private int nextToken = 1;

    /** Sent to the core on each connect, so the core uses the right key map. */
    int tvType = Protocol.TV_LG;

    int state = Protocol.STATE_OFF;
    int flags;
    Bundle data = new Bundle();

    CoreClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        replies = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == Protocol.MSG_STATUS) {
                    state = msg.arg1;
                    flags = msg.arg2;
                    data = msg.getData();
                    if ((flags & Protocol.FLAG_FOREGROUND) == 0) startCore();
                    listener.status(state, flags, data);
                } else if (msg.what == Protocol.MSG_RESULT) {
                    finish(msg.arg1, msg.arg2 == 1);
                }
            }
        });
    }

    /** Returns false if Remote Core is not installed (or this app lacks the permission). */
    boolean bind() {
        if (bound) return true;
        Intent intent = new Intent().setComponent(new ComponentName(Protocol.CORE_PACKAGE, Protocol.CORE_SERVICE));
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        return bound;
    }

    void unbind() {
        if (!bound) return;
        if (core != null) post(Message.obtain(null, Protocol.MSG_UNREGISTER));
        context.unbindService(connection);
        bound = false;
        core = null;
    }

    boolean isConnected() {
        return core != null && state == Protocol.STATE_CONNECTED;
    }

    void send(int action, Result result) {
        Message m = Message.obtain(null, Protocol.MSG_ACTION, action, track(result));
        if (!post(m)) finishLater(m.arg2);
    }

    void text(String text, Result result) {
        Message m = Message.obtain(null, Protocol.MSG_TEXT, 0, track(result));
        Bundle b = new Bundle();
        b.putString("text", text);
        m.setData(b);
        if (!post(m)) finishLater(m.arg2);
    }

    void setTv(int type) {
        tvType = type;
        post(Message.obtain(null, Protocol.MSG_SET_TV, type, 0));
    }

    /** Any key report (1 = keyboard, 2 = consumer). Needs Remote Core 1.1 or later. */
    void key(int reportId, byte[] press, Result result) {
        Message m = Message.obtain(null, Protocol.MSG_KEY, reportId, track(result));
        Bundle b = new Bundle();
        b.putByteArray("press", press);
        m.setData(b);
        if (!post(m)) finishLater(m.arg2);
    }

    /** The core service must run in the foreground; only a visible activity may start it. */
    void startCore() {
        if (startRequested) return;
        startRequested = true;
        try {
            context.startActivity(new Intent()
                    .setComponent(new ComponentName(Protocol.CORE_PACKAGE, Protocol.CORE_START))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
        }
    }

    void openCoreApp() {
        try {
            context.startActivity(new Intent()
                    .setComponent(new ComponentName(Protocol.CORE_PACKAGE, Protocol.CORE_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    private int track(Result result) {
        int token = nextToken++;
        if (nextToken == Integer.MAX_VALUE) nextToken = 1;
        pending.put(token, result != null ? result : ok -> { });
        main.postDelayed(() -> finish(token, false), RESULT_TIMEOUT_MS);
        return token;
    }

    private void finishLater(int token) {
        main.post(() -> finish(token, false));
    }

    private void finish(int token, boolean ok) {
        Result r = pending.get(token);
        if (r == null) return;
        pending.remove(token);
        r.done(ok);
    }

    private boolean post(Message m) {
        if (core == null) return false;
        m.replyTo = replies;
        try {
            core.send(m);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            core = new Messenger(service);
            post(Message.obtain(null, Protocol.MSG_SET_TV, tvType, 0));
            post(Message.obtain(null, Protocol.MSG_REGISTER));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The core process died. Android binds again when it restarts.
            core = null;
            state = Protocol.STATE_OFF;
            flags = 0;
            startRequested = false;
            listener.status(state, flags, new Bundle());
        }
    };
}
