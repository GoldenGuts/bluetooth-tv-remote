package in.weenja.bttvremote.box;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Android TV and Kodi boxes on the phone's Wi-Fi: it tries the remote ports of every address in the
 * phone's /24 network (6466: Google TV remote service, 9090: Kodi JSON-RPC). Many boxes do not announce
 * themselves (the Ugoos AM9 Pro has no mDNS entry), so a name-based search alone misses them.
 */
public final class BoxFinder {

    public static final int ANDROID_TV = 1, KODI = 2;

    public static final class Found {
        public final String host;
        public final int kind;
        /** Kodi: its friendly name. Android TV: empty (the box tells its name only after pairing). */
        public final String name;

        Found(String host, int kind, String name) {
            this.host = host;
            this.kind = kind;
            this.name = name;
        }

        public String label() {
            if (kind == KODI) return (name.isEmpty() ? "Kodi" : "Kodi · " + name) + " · " + host;
            return "Android TV or Google TV · " + host;
        }
    }

    public interface Listener {
        /** On a scan thread: one more box. */
        void found(Found f);

        /** On a scan thread: the scan is over. */
        void done(List<Found> all);
    }

    private static final int TIMEOUT_MS = 500;
    private volatile ExecutorService pool;

    /** Scans the /24 network of this address (the phone's Wi-Fi address). Calls the listener on other threads. */
    public void scan(Inet4Address self, Listener listener) {
        cancel();
        ExecutorService p = Executors.newFixedThreadPool(64);
        pool = p;
        byte[] a = self.getAddress();
        List<Found> all = new ArrayList<>();
        for (int i = 1; i < 255; i++) {
            if (i == (a[3] & 0xFF)) continue;
            String host = (a[0] & 0xFF) + "." + (a[1] & 0xFF) + "." + (a[2] & 0xFF) + "." + i;
            p.execute(() -> {
                Found f = null;
                if (open(host, 6466)) f = new Found(host, ANDROID_TV, "");
                else if (open(host, 9090)) f = new Found(host, KODI, kodiName(host));
                if (f == null) return;
                synchronized (all) {
                    all.add(f);
                }
                listener.found(f);
            });
        }
        p.shutdown();
        new Thread(() -> {
            try {
                if (!p.awaitTermination(30, TimeUnit.SECONDS)) return;
            } catch (InterruptedException e) {
                return;
            }
            if (pool != p) return;   // cancelled or replaced
            List<Found> copy;
            synchronized (all) {
                copy = new ArrayList<>(all);
            }
            copy.sort((x, y) -> Integer.compare(last(x.host), last(y.host)));
            listener.done(copy);
        }, "box-finder").start();
    }

    public void cancel() {
        ExecutorService p = pool;
        pool = null;
        if (p != null) p.shutdownNow();
    }

    private static int last(String host) {
        return Integer.parseInt(host.substring(host.lastIndexOf('.') + 1));
    }

    private static boolean open(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final Pattern FRIENDLY = Pattern.compile("\"System\\.FriendlyName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Kodi's friendly name, or "" when it does not answer in time. */
    static String kodiName(String host) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, 9090), TIMEOUT_MS);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            out.write(("{\"jsonrpc\":\"2.0\",\"method\":\"XBMC.GetInfoLabels\","
                    + "\"params\":{\"labels\":[\"System.FriendlyName\"]},\"id\":1}").getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            StringBuilder b = new StringBuilder();
            byte[] buf = new byte[1024];
            while (b.length() < 8192) {
                int n = in.read(buf);
                if (n < 0) break;
                b.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                Matcher m = FRIENDLY.matcher(b);
                if (m.find()) return m.group(1);
                if (b.indexOf("\"id\":1") >= 0 && b.indexOf("}") >= 0 && b.toString().contains("result")) break;
            }
        } catch (IOException ignored) {
        }
        return "";
    }
}
