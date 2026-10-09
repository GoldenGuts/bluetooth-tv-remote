package in.weenja.bttvremote.box;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;

/**
 * Pairing with an Android TV box (port 6467, the "polo" protocol of the Google TV remote).
 * The box shows a code; the code proves that both sides see the same two certificates.
 * Version 2 (newer Google TV): protobuf, 6-character code. Version 1 (older Android TV, Ugoos): JSON, 4 characters.
 * The pairing tries version 2 first; a version-1 box answers that in JSON, and the pairing starts again in version 1.
 */
public final class BoxPairing {

    public interface Listener {
        /** The box shows the code now: ask for it, then call submit(). */
        void codeShown(int length);

        void done(boolean ok, String message);
    }

    private static final int PORT = 6467;
    private static final int STATUS_OK = 200;
    private static final int ENCODING_HEX = 3;
    private static final int ROLE_INPUT = 1;
    // message types; in version 2 they are the field numbers of the outer message
    private static final int REQUEST_ACK = 11, OPTIONS = 20, CONFIGURATION_ACK = 31, SECRET_ACK = 41;

    private final File dir;
    private final String host;
    private final String clientName;
    private final Executor callbacks;
    private final Listener listener;

    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile BoxIdentity identity;
    private volatile X509Certificate boxCert;
    private volatile boolean finished;
    private volatile int version = 2;

    public BoxPairing(File dir, String host, String clientName, Executor callbacks, Listener listener) {
        this.dir = dir;
        this.host = host;
        this.clientName = clientName;
        this.callbacks = callbacks;
        this.listener = listener;
    }

    public void start() {
        new Thread(this::run, "box-pairing").start();
    }

    public void cancel() {
        finished = true;
        close();
    }

    /** Returns false at once if the code does not fit (a typo); the box keeps showing it, so try again. */
    public boolean submit(String code) {
        code = code.trim().toUpperCase(Locale.US);
        int length = codeLength(version);
        if (!code.matches("[0-9A-F]{" + length + "}") || identity == null || boxCert == null) return false;
        byte[] secret;
        try {
            secret = secret(identity.cert, boxCert, code);
        } catch (Exception e) {
            return false;
        }
        if (secret == null) return false;
        byte[] frame = version == 1
                ? json(40, "{\"secret\":\"" + Base64.getEncoder().encodeToString(secret) + "\"}")
                : outer().message(40, new Proto().bytes(1, secret)).frame();
        new Thread(() -> {
            try {
                write(frame);
            } catch (IOException e) {
                finish(false, "Lost the link to the box: " + e.getMessage());
            }
        }, "box-pairing-send").start();
        return true;
    }

    private static int codeLength(int version) {
        return version == 1 ? 4 : 6;
    }

    private void run() {
        try {
            identity = BoxIdentity.load(dir);
            InputStream in = open();
            write(outer().message(10, new Proto().string(1, "atvremote").string(2, clientName)).frame());
            in.mark(1);
            int first = in.read();
            in.reset();
            if (first == 0) {
                // A 4-byte length in front of JSON: a version-1 box. It refused the version-2 request.
                version = 1;
                close();
                in = open();
                write(json(10, "{\"service_name\":\"androidtvremote\",\"client_name\":" + quote(clientName) + "}"));
            }
            while (!finished) {
                if (version == 1) handleJson(readJson(in));
                else handleProto(Proto.parse(Proto.read(in)));
            }
        } catch (Exception e) {
            finish(false, BoxLink.reason(e));
        } finally {
            close();
        }
    }

    private InputStream open() throws Exception {
        SSLSocket s = identity.connect(host, PORT, null);
        socket = s;
        if (finished) throw new IOException("cancelled");
        s.setSoTimeout(0);   // the person may need a while to read the code
        boxCert = (X509Certificate) s.getSession().getPeerCertificates()[0];
        out = s.getOutputStream();
        return new BufferedInputStream(s.getInputStream());
    }

    private void handleProto(Map<Integer, Object> m) throws Exception {
        int type = 0;
        for (int t : new int[]{REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK}) if (m.containsKey(t)) type = t;
        step((int) Proto.num(m, 2), type);
    }

    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*(\\d+)");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*(\\d+)");

    private void handleJson(String m) throws Exception {
        step(number(STATUS, m), number(TYPE, m));
    }

    /** The last match: the payload comes first and has its own "type" keys (the encodings). */
    private static int number(Pattern p, String s) {
        Matcher matcher = p.matcher(s);
        int value = 0;
        while (matcher.find()) value = Integer.parseInt(matcher.group(1));
        return value;
    }

    private void step(int status, int type) throws Exception {
        if (status != STATUS_OK) {
            finish(false, status == 402 ? "Wrong code. Pair again." : "The box refused (status " + status + ").");
            return;
        }
        int n = codeLength(version);
        switch (type) {
            case REQUEST_ACK:
                write(version == 1
                        ? json(20, "{\"output_encodings\":[{\"symbol_length\":" + n + ",\"type\":3}],"
                                + "\"input_encodings\":[{\"symbol_length\":" + n + ",\"type\":3}],\"preferred_role\":1}")
                        : outer().message(20, new Proto().message(1, encoding(n)).varint(3, ROLE_INPUT)).frame());
                break;
            case OPTIONS:
                write(version == 1
                        ? json(30, "{\"encoding\":{\"symbol_length\":" + n + ",\"type\":3},\"client_role\":1}")
                        : outer().message(30, new Proto().message(1, encoding(n)).varint(2, ROLE_INPUT)).frame());
                break;
            case CONFIGURATION_ACK:
                callbacks.execute(() -> listener.codeShown(n));
                break;
            case SECRET_ACK:
                identity.savePin(boxCert, version);
                finish(true, "Paired");
                break;
        }
    }

    private static Proto outer() {
        return new Proto().varint(1, 2).varint(2, STATUS_OK);
    }

    private static Proto encoding(int length) {
        return new Proto().varint(1, ENCODING_HEX).varint(2, length);
    }

    /** Version 1: 4-byte length, then the JSON message. */
    private static byte[] json(int type, String payload) {
        byte[] b = ("{\"protocol_version\":1,\"payload\":" + payload + ",\"type\":" + type + ",\"status\":200}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] f = new byte[4 + b.length];
        f[0] = (byte) (b.length >>> 24);
        f[1] = (byte) (b.length >>> 16);
        f[2] = (byte) (b.length >>> 8);
        f[3] = (byte) b.length;
        System.arraycopy(b, 0, f, 4, b.length);
        return f;
    }

    private static String readJson(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int n = d.readInt();
        if (n < 0 || n > 1 << 16) throw new IOException("bad length");
        byte[] b = new byte[n];
        d.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') q.append('\\').append(c);
            else if (c < 0x20) q.append(String.format(Locale.US, "\\u%04x", (int) c));
            else q.append(c);
        }
        return q.append('"').toString();
    }

    /**
     * SHA-256 over both RSA keys and the code after its first 2 characters. The first 2 characters are the
     * first byte of that hash: a check that the code (and the box) is right. Null if the check fails.
     */
    static byte[] secret(X509Certificate client, X509Certificate box, String code) throws Exception {
        RSAPublicKey c = (RSAPublicKey) client.getPublicKey();
        RSAPublicKey b = (RSAPublicKey) box.getPublicKey();
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        h.update(unsigned(c.getModulus()));
        h.update(unsigned(c.getPublicExponent()));
        h.update(unsigned(b.getModulus()));
        h.update(unsigned(b.getPublicExponent()));
        for (int i = 2; i < code.length(); i += 2) h.update((byte) Integer.parseInt(code.substring(i, i + 2), 16));
        byte[] d = h.digest();
        return (d[0] & 0xFF) == Integer.parseInt(code.substring(0, 2), 16) ? d : null;
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        int i = 0;
        while (i < b.length - 1 && b[i] == 0) i++;
        return Arrays.copyOfRange(b, i, b.length);
    }

    private void write(byte[] frame) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("not connected");
        synchronized (this) {
            o.write(frame);
            o.flush();
        }
    }

    private void finish(boolean ok, String message) {
        if (finished) return;
        finished = true;
        close();
        callbacks.execute(() -> listener.done(ok, message));
    }

    private void close() {
        SSLSocket s = socket;
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
