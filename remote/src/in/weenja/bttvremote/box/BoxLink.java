package in.weenja.bttvremote.box;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import in.weenja.bttvremote.common.Protocol;

/**
 * The Wi-Fi link to an Android TV box: the Google TV remote protocol (port 6466, TLS with the paired certificate).
 * Keys are Android key codes. The link reconnects by itself while started.
 */
public final class BoxLink {

    public interface Listener {
        void status(int state, String detail);

        /** A text field on the box opened its keyboard (with the text already in it), or closed it. */
        default void keyboard(boolean open, String text) {
        }
    }

    public interface Result {
        void done(boolean ok);
    }

    public static final int OFF = 0;          // not started, or not paired
    public static final int WAITING = 1;      // no link; tries again soon (detail says why)
    public static final int CONNECTED = 2;
    public static final int CONNECTING = 3;

    private static final int PORT = 6466;
    // ping, key, power, volume, app link (no IME: some boxes then show "use the keyboard on the phone")
    private static final int FEATURES = 1 | 2 | 32 | 64 | 512;
    private static final int SHORT = 3;

    /** No traffic for this long: the link may be dead without a sign (v1 boxes never ping). Reconnect first. */
    private static final long STALE_MS = 20_000;
    /** Keys pressed while the link connects wait this long, then fail. */
    private static final long QUEUE_MS = 1500;

    private final File dir;
    private final String deviceName;
    private final Executor callbacks;
    private final Listener listener;
    /** Writes keys in order; also expires queued keys. */
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private final Object lock = new Object();
    private final Object writeLock = new Object();

    // guarded by lock
    private String host;
    private int generation;
    private boolean started;
    private Socket socket;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    /** A reconnect for staleness: the status line does not need to flicker. */
    private boolean quiet;

    private volatile OutputStream out;
    private volatile boolean ready;
    private volatile long lastTraffic;
    private int features = FEATURES;
    private volatile int version = 2;
    private long counter;   // writer thread only

    public volatile int state = OFF;
    public volatile String detail = "";
    /** The box name it reports, e.g. "AM9 PRO". */
    public volatile String model = "";

    /** Interactive mode (v1): the box switches to its virtual keyboard. Text waits this long after the switch. */
    private static final long IME_SWITCH_MS = 400;
    private volatile boolean interactive;
    private volatile long interactiveAt;
    /** The Enter action of the field that has the keyboard (EditorInfo.IME_MASK_ACTION), from ShowIME. */
    private volatile int editorAction;

    private static final class Pending {
        final Supplier<byte[]> frame;
        final boolean text;
        final Result result;
        final long deadline;

        Pending(Supplier<byte[]> frame, boolean text, Result result, long deadline) {
            this.frame = frame;
            this.text = text;
            this.result = result;
            this.deadline = deadline;
        }
    }

    public BoxLink(File dir, String deviceName, Executor callbacks, Listener listener) {
        this.dir = dir;
        this.deviceName = deviceName;
        this.callbacks = callbacks;
        this.listener = listener;
    }

    public static boolean paired(File dir) {
        return BoxIdentity.paired(dir);
    }

    /** Connects and stays connected until stop(). Same host again: only tries again now if it waits. */
    public void start(String host) {
        synchronized (lock) {
            if (started && host.equals(this.host)) {
                lock.notifyAll();
                return;
            }
            stopLocked();
            this.host = host;
            started = true;
            int gen = ++generation;
            new Thread(() -> run(gen), "box-link").start();
        }
    }

    public void stop() {
        synchronized (lock) {
            if (!started) return;
            stopLocked();
        }
        report(OFF, "");
    }

    public boolean isConnected() {
        return ready;
    }

    /** The box runs Kodi (for example CoreELEC), not Android TV. Known after the first connect. */
    public boolean isKodi() {
        return version == KODI;
    }

    /** A remote action. The frame is made at send time: the protocol is known only once the link is up. */
    public void send(int action, Result r) {
        submit(() -> version == KODI ? kodiAction(action)
                : version == 1 && action == Protocol.HOME ? intentV1(HOME_INTENT)
                : keyFrame(keyCode(action)), false, r);
    }

    /** One Android key code, pressed and released. While the link connects, the key waits up to 1.5 s. */
    public void key(int code, Result r) {
        submit(() -> {
            if (version == 1 && code == KEY_ENTER && editorAction >= 2 && editorAction <= 7) {
                return imeV1(1, ByteBuffer.allocate(4).putInt(editorAction).array());   // Go, Search, Send, Next, Done
            }
            if (version != KODI) return keyFrame(code);
            if (code != KEY_ENTER) return null;
            String done = kodiField.toString();   // Enter: Kodi takes the text and closes its keyboard
            kodiField.setLength(0);
            return kodiText(done, true);
        }, false, r);
    }

    private byte[] keyFrame(int code) {
        if (code == 0) return null;
        return version == 1 ? keyV1(code) : new Proto().message(10, new Proto().varint(1, code).varint(2, SHORT)).frame();
    }

    /**
     * Typing on the phone starts (true) or ends (false). A v1 box then switches to its own virtual keyboard,
     * which takes text from the phone; letter keys do not work there. A v2 box needs nothing.
     */
    public void interactive(boolean on) {
        if (interactive == on) return;
        interactive = on;
        if (on) interactiveAt = now();
        if (version == 1 && ready) writer.execute(() -> write(interactiveV1(on)));
    }

    /** Deletes n characters before the cursor. */
    public void delete(int n, Result r) {
        if (n <= 0) return;
        if (version == KODI) {
            submit(() -> {
                kodiField.setLength(Math.max(0, kodiField.length() - n));
                return kodiText(kodiField.toString(), false);
            }, true, r);
            return;
        }
        if (version == 1) {
            submit(() -> imeV1(4, ByteBuffer.allocate(8).putInt(n).putInt(0).array()), true, r);
            return;
        }
        for (int i = 0; i < n; i++) key(KEY_DEL, i == n - 1 ? r : ok -> { });
    }

    private void submit(Supplier<byte[]> frame, boolean text, Result r) {
        synchronized (lock) {
            long now = now();
            if (ready && now - lastTraffic < STALE_MS) {
                lastTraffic = now;
                sendNow(frame, text, r);
                return;
            }
            if (!started) {
                callbacks.execute(() -> r.done(false));
                return;
            }
            if (ready) {
                quiet = true;
                ready = false;
                closeSocket();
            }
            queue.add(new Pending(frame, text, r, now + QUEUE_MS));
            lock.notifyAll();
        }
        writer.schedule(this::expire, QUEUE_MS + 20, TimeUnit.MILLISECONDS);
    }

    /**
     * Text at the cursor. v1: committed through the virtual keyboard, any character.
     * v2: key presses; upper case comes out as lower case (no shift).
     */
    public void text(String text, Result r) {
        if (version == KODI) {
            submit(() -> {
                kodiField.append(text);
                return kodiText(kodiField.toString(), false);
            }, true, r);
            return;
        }
        if (version == 1) {
            if (!interactive) interactive(true);
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            ByteBuffer args = ByteBuffer.allocate(1 + 4 + utf8.length + 4);
            args.put((byte) 0).putInt(utf8.length).put(utf8).putInt(1);   // string present, length, bytes; cursor after
            submit(() -> imeV1(3, args.array()), true, r);
            return;
        }
        java.util.List<Integer> codes = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            int code = charCode(text.charAt(i));
            if (code != 0) codes.add(code);
        }
        boolean all = codes.size() == text.length();
        if (codes.isEmpty()) {
            callbacks.execute(() -> r.done(all));
            return;
        }
        for (int i = 0; i < codes.size(); i++) {
            key(codes.get(i), i == codes.size() - 1 ? ok -> r.done(ok && all) : ok -> { });
        }
    }

    private void sendNow(Supplier<byte[]> frame, boolean text, Result r) {
        Runnable send = () -> {
            byte[] f = frame.get();
            boolean ok = f != null && write(f);
            callbacks.execute(() -> r.done(ok));
        };
        long wait = text && version == 1 ? interactiveAt + IME_SWITCH_MS - now() : 0;
        if (wait > 0) writer.schedule(send, wait, TimeUnit.MILLISECONDS);
        else writer.execute(send);
    }

    private void expire() {
        synchronized (lock) {
            long now = now();
            while (!queue.isEmpty() && queue.peek().deadline <= now) {
                Result r = queue.poll().result;
                callbacks.execute(() -> r.done(false));
            }
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    // ---- the session ----

    private boolean alive(int gen) {
        synchronized (lock) {
            return started && gen == generation;
        }
    }

    private void run(int gen) {
        long delay = 500;
        while (alive(gen)) {
            try {
                session(gen);
                delay = 500;
            } catch (Exception e) {
                if (!alive(gen)) break;
                boolean wasQuiet;
                synchronized (lock) {
                    wasQuiet = quiet && socket == null;   // closed on purpose for staleness: go again at once
                }
                if (wasQuiet) delay = 0;
                else report(WAITING, reason(e));
            }
            ready = false;
            out = null;
            synchronized (lock) {
                closeSocket();
                if (!alive(gen)) break;
                if (delay > 0) {
                    try {
                        lock.wait(queue.isEmpty() ? delay : 200);   // keys wait: try again soon
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            delay = Math.max(500, Math.min(delay * 2, 10_000));
        }
    }

    /**
     * Android TV first (6466). If nothing listens there but Kodi does (9090), the box runs Kodi:
     * a dual-boot box (Android and CoreELEC) keeps one address, so the link follows whichever OS is up.
     */
    private void session(int gen) throws Exception {
        BoxIdentity id = BoxIdentity.load(dir);
        String pin = id.pin();
        boolean silent;
        synchronized (lock) {
            silent = quiet;
        }
        if (!silent) report(CONNECTING, "");
        SSLSocket s;
        try {
            if (pin == null) {
                if (open(host, KODI_PORT)) {
                    kodiSession(gen);
                    return;
                }
                throw new IOException("not paired: Settings → Android TV box → Pair");
            }
            try {
                version = id.version();
                s = id.connect(host, PORT, pin);
            } catch (ConnectException e) {
                if (!open(host, KODI_PORT)) throw e;
                kodiSession(gen);
                return;
            }
        } finally {
            synchronized (lock) {
                quiet = false;
            }
        }
        synchronized (lock) {
            if (!alive(gen)) {
                s.close();
                return;
            }
            socket = s;
        }
        out = s.getOutputStream();
        InputStream in = new BufferedInputStream(s.getInputStream());
        if (version == 1) {
            s.setSoTimeout(0);   // a v1 box sends nothing after the configure ack
            writer.execute(() -> counter = 0);
            write(configureV1());
            DataInputStream d = new DataInputStream(in);
            while (alive(gen)) handleV1(d);
        } else {
            s.setSoTimeout(20_000);   // the box pings every 5 s
            while (alive(gen)) handle(Proto.parse(Proto.read(in)));
        }
    }

    /** The link works: send the keys that waited. */
    private void becomeReady() {
        synchronized (lock) {
            ready = true;
            lastTraffic = now();
            long now = lastTraffic;
            if (version == 1 && interactive) {
                interactiveAt = now;
                writer.execute(() -> write(interactiveV1(true)));
            }
            while (!queue.isEmpty()) {
                Pending p = queue.poll();
                if (p.deadline > now) sendNow(p.frame, p.text, p.result);
                else callbacks.execute(() -> p.result.done(false));
            }
        }
        report(CONNECTED, "");
    }

    // ---- pointer: Kodi EventServer (UDP 9777) moves Kodi's mouse; clicks and scrolls go over JSON-RPC ----

    private static final int ES_PORT = 9777;
    private static final int ES_HELO = 1, ES_MOUSE = 4;
    private final int esUid = new java.util.Random().nextInt();
    private java.net.DatagramSocket es;   // writer thread only
    private long esHelloAt;               // writer thread only
    private float px = 0.5f, py = 0.5f;   // pointer position, 0..1 of the screen; writer thread only

    /** A mouse pointer works on this box now (Kodi). */
    public boolean hasPointer() {
        return version == KODI && ready;
    }

    /** Moves the pointer by a part of the screen (1 = the full width or height). */
    public void pointerMove(float dx, float dy) {
        if (version != KODI) return;
        writer.execute(() -> {
            px = Math.max(0, Math.min(1, px + dx));
            py = Math.max(0, Math.min(1, py + dy));
            ByteBuffer m = ByteBuffer.allocate(5);
            m.put((byte) 0x01).putShort((short) Math.round(px * 65535)).putShort((short) Math.round(py * 65535));   // absolute
            esSend(ES_MOUSE, m.array());
        });
    }

    /** Click on the item under the pointer: the pointer gives it focus, Select takes it (in the player: the bar). */
    public void pointerClick(Result r) {
        submit(() -> kodiAction(Protocol.OK), false, r);
    }

    public void pointerRightClick(Result r) {
        submit(() -> kodi("Input.ContextMenu", null), false, r);
    }

    /** Wheel steps: up is negative. */
    public void scroll(int steps, Result r) {
        for (int i = 0; i < Math.abs(steps); i++) {
            submit(() -> kodiExecute(steps < 0 ? "scrollup" : "scrolldown"), false, i == Math.abs(steps) - 1 ? r : ok -> { });
        }
    }

    /** EventServer packet: "XBMC", version 2.0, type, sequence 1 of 1, payload size, client id, 10 reserved bytes. */
    private void esSend(int type, byte[] payload) {
        try {
            if (es == null) es = new java.net.DatagramSocket();
            long now = now();
            if (now - esHelloAt > 30_000) {   // Kodi forgets a client after 60 s of silence
                esHelloAt = now;
                byte[] name = "Bluetooth TV Remote".getBytes(StandardCharsets.UTF_8);
                ByteBuffer h = ByteBuffer.allocate(name.length + 1 + 1 + 2 + 4 + 4);
                h.put(name).put((byte) 0).put((byte) 0).putShort((short) 0).putInt(0).putInt(0);   // no icon
                esRaw(ES_HELO, h.array());
            }
            esRaw(type, payload);
        } catch (IOException ignored) {
            // UDP: a lost move is replaced by the next one
        }
    }

    private void esRaw(int type, byte[] payload) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(32 + payload.length);
        b.put("XBMC".getBytes(StandardCharsets.US_ASCII)).put((byte) 2).put((byte) 0).putShort((short) type)
                .putInt(1).putInt(1).putShort((short) payload.length).putInt(esUid).put(new byte[10]).put(payload);
        es.send(new java.net.DatagramPacket(b.array(), b.capacity(), new InetSocketAddress(host, ES_PORT)));
    }

    // ---- Kodi JSON-RPC (port 9090: raw TCP, JSON objects back to back, no auth) ----

    public static final int KODI = 0;
    private static final int KODI_PORT = 9090;
    /** The text in Kodi's keyboard: Input.SendText replaces it all, so the phone sends the whole text each time. */
    private final StringBuilder kodiField = new StringBuilder();   // writer thread only
    /** Kodi 19 or later (JSON-RPC 12): it has Input.ButtonEvent. */
    private volatile boolean kodiButtons;

    /** Kodi answers and the Android TV remote does not: no pairing needed. Blocks up to 3 s. */
    public static boolean kodiAt(String host) {
        return !open(host, PORT) && open(host, KODI_PORT);
    }

    private static boolean open(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void kodiSession(int gen) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, KODI_PORT), 3000);
            s.setTcpNoDelay(true);
        } catch (IOException e) {
            s.close();
            throw e;
        }
        synchronized (lock) {
            if (!alive(gen)) {
                s.close();
                return;
            }
            socket = s;
        }
        version = KODI;
        writer.execute(() -> kodiField.setLength(0));
        out = s.getOutputStream();
        InputStream in = new BufferedInputStream(s.getInputStream());
        s.setSoTimeout(5000);
        write(kodi("XBMC.GetInfoLabels", "{\"labels\":[\"System.FriendlyName\"]}"));
        write(kodi("JSONRPC.Version", null));
        String name = jsonString(kodiReply(in), "System.FriendlyName");   // Kodi answers in order
        java.util.regex.Matcher major = java.util.regex.Pattern.compile("\"major\"\\s*:\\s*(\\d+)").matcher(kodiReply(in));
        kodiButtons = major.find() && Integer.parseInt(major.group(1)) >= 12;
        model = name.isEmpty() ? "Kodi" : "Kodi · " + name;
        s.setSoTimeout(0);   // Kodi sends notifications only when something happens
        becomeReady();
        while (alive(gen)) {
            String m = readJson(in);
            lastTraffic = now();
            if (m.contains("\"Input.OnInputRequested\"")) {
                String value = unescape(jsonString(m, "value"));
                writer.execute(() -> {
                    kodiField.setLength(0);
                    kodiField.append(value);
                });
                callbacks.execute(() -> listener.keyboard(true, value));
            } else if (m.contains("\"Input.OnInputFinished\"")) {
                callbacks.execute(() -> listener.keyboard(false, ""));
            }
        }
    }

    /** The next answer to a request: notifications (they have a method) may come first. */
    private static String kodiReply(InputStream in) throws IOException {
        while (true) {
            String m = readJson(in);
            if (!m.contains("\"method\"")) return m;
        }
    }

    private byte[] kodiAction(int action) {
        String button = kodiButtons ? kodiButton(action) : null;
        if (button != null) return kodiButtonEvent(button);
        switch (action) {
            case Protocol.UP: return kodi("Input.Up", null);
            case Protocol.DOWN: return kodi("Input.Down", null);
            case Protocol.LEFT: return kodi("Input.Left", null);
            case Protocol.RIGHT: return kodi("Input.Right", null);
            case Protocol.OK: return kodi("Input.Select", null);
            case Protocol.BACK: return kodi("Input.Back", null);
            case Protocol.HOME: return kodi("Input.Home", null);
            case Protocol.SETTINGS: return kodi("Input.ContextMenu", null);
            case Protocol.PLAY_PAUSE: return kodiExecute("playpause");
            case Protocol.REWIND: return kodiExecute("stepback");
            case Protocol.FAST_FORWARD: return kodiExecute("stepforward");
            case Protocol.VOLUME_UP: return kodiExecute("volumeup");
            case Protocol.VOLUME_DOWN: return kodiExecute("volumedown");
            case Protocol.MUTE: return kodiExecute("mute");
            default: return null;
        }
    }

    /**
     * Keys whose meaning depends on the screen go through Kodi's remote keymap, as from a real remote:
     * in the video player OK opens the player bar and left/right step. Input.Select, Input.Left and the
     * like skip the keymap, and the player ignores them.
     */
    private static String kodiButton(int action) {
        switch (action) {
            case Protocol.UP: return "up";
            case Protocol.DOWN: return "down";
            case Protocol.LEFT: return "left";
            case Protocol.RIGHT: return "right";
            case Protocol.OK: return "select";
            case Protocol.BACK: return "back";
            case Protocol.SETTINGS: return "menu";
            default: return null;
        }
    }

    private static byte[] kodiButtonEvent(String button) {
        return kodi("Input.ButtonEvent", "{\"button\":\"" + button + "\",\"keymap\":\"R1\"}");
    }

    private static byte[] kodiExecute(String action) {
        return kodi("Input.ExecuteAction", "{\"action\":\"" + action + "\"}");
    }

    private static byte[] kodiText(String text, boolean done) {
        return kodi("Input.SendText", "{\"text\":" + quote(text) + ",\"done\":" + done + "}");
    }

    private static byte[] kodi(String method, String params) {
        return ("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"" + (params != null ? ",\"params\":" + params : "")
                + ",\"id\":1}").getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') q.append('\\').append(c);
            else if (c < 0x20) q.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
            else q.append(c);
        }
        return q.append('"').toString();
    }

    /** One JSON object from the stream: count braces outside strings. */
    private static String readJson(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int depth = 0;
        boolean inString = false, escape = false;
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("closed by the box");
            if (depth == 0 && c != '{') continue;
            b.write(c);
            if (b.size() > 1 << 20) throw new IOException("message too long");
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return b.toString("UTF-8");
            }
        }
    }

    private static String unescape(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                o.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n': o.append('\n'); break;
                case 't': o.append('\t'); break;
                case 'r': o.append('\r'); break;
                case 'b': o.append('\b'); break;
                case 'f': o.append('\f'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        o.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    break;
                default: o.append(e);   // " \ /
            }
        }
        return o.toString();
    }

    private static String jsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    // ---- version 1 (older Android TV remote service): [1, type, length (2 bytes), payload] ----

    private static final int V1_CONFIGURE = 0, V1_KEY = 2, V1_SHOW_IME = 5, V1_HIDE_IME = 6, V1_CONFIGURE_ACK = 7,
            V1_INTENT = 16, V1_IME = 18, V1_INTERACTIVE = 19, V1_PING = 20, V1_PONG = 21;

    /**
     * Home as a start of the home screen, not the Home key. Android TV drops the Home key until the secure
     * setting tv_user_setup_complete is 1, and boxes without Google's setup (Ugoos) never set it.
     */
    static final String HOME_INTENT = "intent:#Intent;action=android.intent.action.MAIN;"
            + "category=android.intent.category.HOME;end";

    /**
     * ShowIME: the box's EditorInfo, then its ExtractedText. Sent only to an interactive client.
     * inputType, imeOptions, privateImeOptions, actionLabel, actionId, selStart, selEnd, capsMode, hint, label,
     * packageName, fieldId, fieldName, a flag, 127, then the text (absent flag, string, six ints).
     */
    private void showImeV1(ByteBuffer b) {
        String text = "";
        try {
            b.getInt();                         // inputType
            editorAction = b.getInt() & 0xFF;   // imeOptions & IME_MASK_ACTION
            stringV1(b);
            stringV1(b);
            b.getInt();
            b.getInt();
            b.getInt();
            b.getInt();
            stringV1(b);
            stringV1(b);
            stringV1(b);
            b.getInt();
            stringV1(b);
            b.get();
            b.get();
            if (b.get() == 0) text = stringV1(b);
        } catch (RuntimeException ignored) {
            // a shorter packet from another service version: open the keyboard anyway
        }
        String t = text == null ? "" : text;
        callbacks.execute(() -> listener.keyboard(true, t));
    }

    /** A string: 0 = present, then length (4 bytes) and UTF-8; any other first byte = null. */
    private static String stringV1(ByteBuffer b) {
        if (b.get() != 0) return null;
        byte[] s = new byte[b.getInt()];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private void handleV1(DataInputStream in) throws IOException {
        in.readUnsignedByte();   // version, always 1
        int type = in.readUnsignedByte();
        byte[] payload = new byte[in.readUnsignedShort()];
        in.readFully(payload);
        lastTraffic = now();
        if (type == V1_CONFIGURE_ACK) {
            model = v1Model(payload);
            becomeReady();
        } else if (type == V1_PING) {
            write(new byte[]{1, V1_PONG, 0, 0});
        } else if (type == V1_SHOW_IME) {
            showImeV1(ByteBuffer.wrap(payload));
        } else if (type == V1_HIDE_IME) {
            editorAction = 0;
            callbacks.execute(() -> listener.keyboard(false, ""));
        }
    }

    /** Configure: two ints (1, 1), 32, 3, six zero bytes, then the name with a 1-byte length. */
    private byte[] configureV1() {
        byte[] name = "test".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 + 2 + 6 + 1 + name.length);
        b.put((byte) 1).put((byte) V1_CONFIGURE).putShort((short) (b.capacity() - 4));
        b.putInt(1).putInt(1).put((byte) 32).put((byte) 3).put(new byte[6]).put((byte) name.length).put(name);
        return b.array();
    }

    /** Key press and release: a counter (8 bytes), 0 = down / 1 = up (4 bytes), the key code (4 bytes). */
    private byte[] keyV1(int code) {
        ByteBuffer b = ByteBuffer.allocate(40);
        for (int up = 0; up < 2; up++) {
            b.put((byte) 1).put((byte) V1_KEY).putShort((short) 16);
            b.putLong(counter++).putInt(up).putInt(code);
        }
        return b.array();
    }

    /** An intent URI (Intent.toUri with the intent scheme): the box starts it as an activity. */
    private static byte[] intentV1(String uri) {
        byte[] u = uri.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(4 + u.length);
        b.put((byte) 1).put((byte) V1_INTENT).putShort((short) u.length).put(u);
        return b.array();
    }

    /** Interactive on/off: the box switches its keyboard to the virtual one (and back). */
    private static byte[] interactiveV1(boolean on) {
        return new byte[]{1, V1_INTERACTIVE, 0, 1, (byte) (on ? 1 : 0)};
    }

    /**
     * A call on the box's input connection: 1 performEditorAction, 2 setComposingText, 3 commitText,
     * 4 deleteSurroundingText, ... (from the box's remote service, AtvRemote.ServerPacketParser).
     */
    private static byte[] imeV1(int call, byte[] args) {
        ByteBuffer b = ByteBuffer.allocate(4 + 1 + args.length);
        b.put((byte) 1).put((byte) V1_IME).putShort((short) (1 + args.length)).put((byte) call).put(args);
        return b.array();
    }

    /** The ack holds strings with a 1-byte length; the last one is the full device name. */
    private static String v1Model(byte[] p) {
        String last = "";
        int i = 4;   // remote id
        while (i < p.length) {
            if (i + 4 <= p.length && p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 0) {
                i += 4;
                continue;
            }
            int n = p[i] & 0xFF;
            if (n == 0 || i + 1 + n > p.length) break;
            last = new String(p, i + 1, n, StandardCharsets.UTF_8);
            i += 1 + n;
        }
        return last;
    }

    private void handle(Map<Integer, Object> m) throws IOException {
        lastTraffic = now();
        if (m.containsKey(1)) {             // configure: say what this remote does
            Map<Integer, Object> cfg = Proto.sub(m, 1);
            Map<Integer, Object> info = Proto.sub(cfg, 2);
            model = Proto.str(info, 1);
            features = FEATURES & (int) Proto.num(cfg, 1);
            write(new Proto().message(1, new Proto()
                    .varint(1, features)
                    .message(2, new Proto().string(1, deviceName).string(2, "Bluetooth TV Remote")
                            .varint(3, 1).string(4, "1").string(5, "in.weenja.bttvremote").string(6, "1"))).frame());
            if ((features & 2) == 0) report(WAITING, "the box does not take keys");
        } else if (m.containsKey(2)) {      // set active
            write(new Proto().message(2, new Proto().varint(1, features)).frame());
            becomeReady();
        } else if (m.containsKey(8)) {      // ping
            long v = Proto.num(Proto.sub(m, 8), 1);
            write(new Proto().message(9, new Proto().varint(1, v)).frame());
        } else if (m.containsKey(40)) {     // start: the box is ready
            if (!ready) becomeReady();
        }
    }

    private boolean write(byte[] frame) {
        OutputStream o = out;
        if (o == null) return false;
        try {
            synchronized (writeLock) {
                o.write(frame);
                o.flush();
            }
            return true;
        } catch (IOException e) {
            synchronized (lock) {
                closeSocket();   // the reader sees it and the loop connects again
            }
            return false;
        }
    }

    private void stopLocked() {
        started = false;
        generation++;
        ready = false;
        out = null;
        quiet = false;
        closeSocket();
        while (!queue.isEmpty()) {
            Result r = queue.poll().result;
            callbacks.execute(() -> r.done(false));
        }
        lock.notifyAll();
    }

    private void closeSocket() {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    private void report(int state, String detail) {
        if (this.state == state && this.detail.equals(detail)) return;
        this.state = state;
        this.detail = detail;
        callbacks.execute(() -> listener.status(state, detail));
    }

    static String reason(Exception e) {
        if (e instanceof SocketTimeoutException || e instanceof NoRouteToHostException) return "the box does not answer";
        if (e instanceof ConnectException) return "the box refused the link (remote service off?)";
        if (e instanceof EOFException) return "the box closed the link";
        if (e instanceof SSLException) {
            String m = String.valueOf(e.getMessage());
            return m.contains("pair again") ? "the box certificate changed: pair again" : "the box refused this phone: pair again";
        }
        if (e instanceof java.net.UnknownHostException) return "unknown address";
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }

    // ---- key codes (android.view.KeyEvent) ----

    static int keyCode(int action) {
        switch (action) {
            case Protocol.UP: return 19;
            case Protocol.DOWN: return 20;
            case Protocol.LEFT: return 21;
            case Protocol.RIGHT: return 22;
            case Protocol.OK: return 23;
            case Protocol.BACK: return 4;
            case Protocol.HOME: return 3;
            case Protocol.SETTINGS: return 176;
            case Protocol.INPUT: return 178;
            case Protocol.VOLUME_UP: return 24;
            case Protocol.VOLUME_DOWN: return 25;
            case Protocol.MUTE: return 164;
            case Protocol.PLAY_PAUSE: return 85;
            case Protocol.REWIND: return 89;
            case Protocol.FAST_FORWARD: return 90;
            case Protocol.POWER: return 26;
            default: return 0;
        }
    }

    public static final int KEY_DEL = 67;
    public static final int KEY_ENTER = 66;

    static int charCode(char c) {
        if (c >= 'a' && c <= 'z') return 29 + c - 'a';
        if (c >= 'A' && c <= 'Z') return 29 + c - 'A';
        if (c >= '0' && c <= '9') return 7 + c - '0';
        switch (c) {
            case ' ': return 62;
            case '\n': return KEY_ENTER;
            case '\b': return KEY_DEL;
            case '\t': return 61;
            case ',': return 55;
            case '.': return 56;
            case '`': return 68;
            case '-': return 69;
            case '=': return 70;
            case '[': return 71;
            case ']': return 72;
            case '\\': return 73;
            case ';': return 74;
            case '\'': return 75;
            case '/': return 76;
            case '@': return 77;
            case '+': return 81;
            case '*': return 17;
            case '#': return 18;
            default: return 0;
        }
    }
}
