package in.weenja.bttvremote.box;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The few protobuf rules the Android TV Remote protocol needs: varints, strings, bytes, nested messages.
 * On the wire, each message has its length (a varint) in front.
 */
final class Proto {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Proto varint(int field, long value) {
        raw(field << 3);
        raw(value);
        return this;
    }

    Proto bytes(int field, byte[] value) {
        raw(field << 3 | 2);
        raw(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    Proto string(int field, String value) {
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    Proto message(int field, Proto value) {
        return bytes(field, value.toBytes());
    }

    byte[] toBytes() {
        return out.toByteArray();
    }

    /** Length + body, ready to write to the socket. */
    byte[] frame() {
        byte[] body = toBytes();
        Proto f = new Proto();
        f.raw(body.length);
        f.out.write(body, 0, body.length);
        return f.toBytes();
    }

    private void raw(long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write((int) v);
    }

    // ---- reading ----

    /** Field number -> Long (varint, fixed) or byte[] (length-delimited). A repeated field keeps its last value. */
    static Map<Integer, Object> parse(byte[] b) throws IOException {
        Map<Integer, Object> fields = new HashMap<>();
        int[] pos = {0};
        while (pos[0] < b.length) {
            long tag = varint(b, pos);
            int field = (int) (tag >>> 3);
            switch ((int) (tag & 7)) {
                case 0:
                    fields.put(field, varint(b, pos));
                    break;
                case 1:
                    fields.put(field, fixed(b, pos, 8));
                    break;
                case 2:
                    int n = (int) varint(b, pos);
                    if (n < 0 || pos[0] + n > b.length) throw new IOException("bad length");
                    byte[] v = new byte[n];
                    System.arraycopy(b, pos[0], v, 0, n);
                    pos[0] += n;
                    fields.put(field, v);
                    break;
                case 5:
                    fields.put(field, fixed(b, pos, 4));
                    break;
                default:
                    throw new IOException("bad wire type");
            }
        }
        return fields;
    }

    static long num(Map<Integer, Object> m, int field) {
        Object v = m.get(field);
        return v instanceof Long ? (Long) v : 0;
    }

    static Map<Integer, Object> sub(Map<Integer, Object> m, int field) throws IOException {
        Object v = m.get(field);
        return parse(v instanceof byte[] ? (byte[]) v : new byte[0]);
    }

    static String str(Map<Integer, Object> m, int field) {
        Object v = m.get(field);
        return v instanceof byte[] ? new String((byte[]) v, StandardCharsets.UTF_8) : "";
    }

    /** One framed message from the stream. */
    static byte[] read(InputStream in) throws IOException {
        long n = 0;
        for (int shift = 0; ; shift += 7) {
            int c = in.read();
            if (c < 0) throw new EOFException("closed by the box");
            n |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            if (shift > 28) throw new IOException("bad length");
        }
        if (n > 1 << 20) throw new IOException("message too long");
        byte[] b = new byte[(int) n];
        for (int off = 0; off < b.length; ) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new EOFException("closed by the box");
            off += r;
        }
        return b;
    }

    private static long varint(byte[] b, int[] pos) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= b.length) throw new IOException("truncated");
            int c = b[pos[0]++] & 0xFF;
            v |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) return v;
        }
        throw new IOException("bad varint");
    }

    private static long fixed(byte[] b, int[] pos, int n) throws IOException {
        if (pos[0] + n > b.length) throw new IOException("truncated");
        long v = 0;
        for (int i = 0; i < n; i++) v |= (long) (b[pos[0]++] & 0xFF) << (8 * i);
        return v;
    }
}
