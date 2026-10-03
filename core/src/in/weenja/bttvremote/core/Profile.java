package in.weenja.bttvremote.core;

import in.weenja.bttvremote.common.Protocol;

/** Logical action -> HID report. The LG map is tested on an LG G5 (webOS 25) unless marked. */
final class Profile {

    static final int ID_KEYBOARD = 1;
    static final int ID_CONSUMER = 2;

    /** Keyboard (id 1) + consumer control (id 2). The verified report map: do not change it without a new pairing. */
    static final byte[] DESCRIPTOR = bytes(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, 0xFF, 0x00,
            0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, 0x81, 0x00,
            0xC0,
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
            0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0);

    /** One key press: report id + press bytes. The release is all zeros of the same length. */
    static final class Key {
        final int reportId;
        final byte[] press;

        Key(int reportId, byte[] press) {
            this.reportId = reportId;
            this.press = press;
        }

        byte[] release() {
            return new byte[press.length];
        }
    }

    static Key forAction(int action, int tv) {
        if (tv == Protocol.TV_ANDROID) {
            if (action == Protocol.BACK) return consumer(0x224);    // AC Back
            if (action == Protocol.HOME) return consumer(0x223);    // AC Home
        }
        switch (action) {
            case Protocol.UP: return keyboard(0, 0x52);
            case Protocol.DOWN: return keyboard(0, 0x51);
            case Protocol.LEFT: return keyboard(0, 0x50);
            case Protocol.RIGHT: return keyboard(0, 0x4F);
            case Protocol.OK: return keyboard(0, 0x28);
            case Protocol.BACK: return keyboard(0, 0x29);
            case Protocol.HOME: return keyboard(0x08, 0);        // left GUI (Win) key
            case Protocol.SETTINGS: return consumer(0x40);
            case Protocol.INPUT: return consumer(0x82);
            case Protocol.VOLUME_UP: return consumer(0xE9);
            case Protocol.VOLUME_DOWN: return consumer(0xEA);
            case Protocol.MUTE: return consumer(0xE2);
            case Protocol.PLAY_PAUSE: return consumer(0xCD);
            case Protocol.REWIND: return consumer(0xB4);         // not verified
            case Protocol.FAST_FORWARD: return consumer(0xB3);   // not verified
            case Protocol.POWER: return consumer(0x30);          // not verified
            default: return null;
        }
    }

    /** US layout. Returns null for characters a keyboard cannot type. */
    static Key forChar(char c) {
        if (c >= 'a' && c <= 'z') return keyboard(0, 0x04 + c - 'a');
        if (c >= 'A' && c <= 'Z') return keyboard(SHIFT, 0x04 + c - 'A');
        if (c >= '1' && c <= '9') return keyboard(0, 0x1E + c - '1');
        if (c == '0') return keyboard(0, 0x27);
        switch (c) {
            case ' ': return keyboard(0, 0x2C);
            case '\n': return keyboard(0, 0x28);
            case '\b': return keyboard(0, 0x2A);            // backspace
            case '\t': return keyboard(0, 0x2B);
            case '-': return keyboard(0, 0x2D);
            case '_': return keyboard(SHIFT, 0x2D);
            case '=': return keyboard(0, 0x2E);
            case '+': return keyboard(SHIFT, 0x2E);
            case '[': return keyboard(0, 0x2F);
            case '{': return keyboard(SHIFT, 0x2F);
            case ']': return keyboard(0, 0x30);
            case '}': return keyboard(SHIFT, 0x30);
            case '\\': return keyboard(0, 0x31);
            case '|': return keyboard(SHIFT, 0x31);
            case ';': return keyboard(0, 0x33);
            case ':': return keyboard(SHIFT, 0x33);
            case '\'': return keyboard(0, 0x34);
            case '"': return keyboard(SHIFT, 0x34);
            case '`': return keyboard(0, 0x35);
            case '~': return keyboard(SHIFT, 0x35);
            case ',': return keyboard(0, 0x36);
            case '<': return keyboard(SHIFT, 0x36);
            case '.': return keyboard(0, 0x37);
            case '>': return keyboard(SHIFT, 0x37);
            case '/': return keyboard(0, 0x38);
            case '?': return keyboard(SHIFT, 0x38);
            case '!': return keyboard(SHIFT, 0x1E);
            case '@': return keyboard(SHIFT, 0x1F);
            case '#': return keyboard(SHIFT, 0x20);
            case '$': return keyboard(SHIFT, 0x21);
            case '%': return keyboard(SHIFT, 0x22);
            case '^': return keyboard(SHIFT, 0x23);
            case '&': return keyboard(SHIFT, 0x24);
            case '*': return keyboard(SHIFT, 0x25);
            case '(': return keyboard(SHIFT, 0x26);
            case ')': return keyboard(SHIFT, 0x27);
            default: return null;
        }
    }

    private static final int SHIFT = 0x02;

    private static Key keyboard(int modifiers, int usage) {
        return new Key(ID_KEYBOARD, new byte[]{(byte) modifiers, 0, (byte) usage, 0, 0, 0, 0, 0});
    }

    private static Key consumer(int usage) {
        return new Key(ID_CONSUMER, new byte[]{(byte) (usage & 0xFF), (byte) (usage >> 8)});
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    private Profile() {
    }
}
