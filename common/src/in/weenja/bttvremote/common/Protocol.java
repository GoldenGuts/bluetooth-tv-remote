package in.weenja.bttvremote.common;

/** Messages between TV Remote (UI) and Remote Core (GATT server). Compiled into both APKs: change both together. */
public final class Protocol {

    private Protocol() {
    }

    public static final String CORE_PACKAGE = "in.weenja.bttvremote.core";
    public static final String CORE_SERVICE = "in.weenja.bttvremote.core.CoreService";
    public static final String CORE_START = "in.weenja.bttvremote.core.StartActivity";
    /** Donations: the browser opens it. The apps talk only to the TV (and the remote to a box on the local network). */
    public static final String SPONSOR_URL = "https://github.com/sponsors/GoldenGuts";

    public static final String CORE_ACTIVITY = "in.weenja.bttvremote.core.CoreActivity";

    // ---- client -> core ----

    /** replyTo gets STATUS now and on each change. */
    public static final int MSG_REGISTER = 1;
    public static final int MSG_UNREGISTER = 2;
    /** arg1 = action, arg2 = token (0 = no reply). Reply: RESULT. */
    public static final int MSG_ACTION = 3;
    /** data "text". arg2 = token. Reply: RESULT. */
    public static final int MSG_TEXT = 4;
    /**
     * Any key: arg1 = report id (1 keyboard, 2 consumer), data "press" = the press bytes. arg2 = token.
     * Reply: RESULT. Lets TV Remote send new keys without a Remote Core update (each core update can break the pairing).
     */
    public static final int MSG_KEY = 5;
    /** arg1 = TV type (TV_LG, TV_ANDROID). The core keeps it. */
    public static final int MSG_SET_TV = 6;

    /** LG webOS: Home = Win key, Back = Escape (tested on an LG G5). */
    public static final int TV_LG = 0;
    /** Android TV, Google TV, Fire TV: Home and Back are consumer keys (not tested). */
    public static final int TV_ANDROID = 1;

    // ---- core -> client ----

    /** arg1 = state, arg2 = flags, data: "host" (name), "address", "log" (recent lines). */
    public static final int MSG_STATUS = 10;
    /** arg1 = token, arg2 = 1 if the stack accepted the report, else 0. */
    public static final int MSG_RESULT = 11;

    public static final int STATE_OFF = 0;        // Bluetooth off or no permission
    public static final int STATE_WAITING = 1;    // advertising, no host
    public static final int STATE_CONNECTED = 2;  // host selected, reports go out

    public static final int FLAG_FOREGROUND = 1;  // the core service runs in the foreground
    /** The HID service handle differs from the handle at pairing: the TV will not find the keys. */
    public static final int FLAG_STALE = 2;

    // ---- logical actions ----

    public static final int UP = 1;
    public static final int DOWN = 2;
    public static final int LEFT = 3;
    public static final int RIGHT = 4;
    public static final int OK = 5;
    public static final int BACK = 6;
    public static final int HOME = 7;
    public static final int SETTINGS = 8;
    public static final int INPUT = 9;
    public static final int VOLUME_UP = 10;
    public static final int VOLUME_DOWN = 11;
    public static final int MUTE = 12;
    public static final int PLAY_PAUSE = 13;
    public static final int REWIND = 14;
    public static final int FAST_FORWARD = 15;
    public static final int POWER = 16;

    private static final String[] NAMES = {"?", "up", "down", "left", "right", "ok", "back", "home", "settings",
            "input", "volume_up", "volume_down", "mute", "play_pause", "rewind", "fast_forward", "power"};

    public static String name(int action) {
        return action > 0 && action < NAMES.length ? NAMES[action] : "?";
    }

    /** For deep links: bttvremote://key/home. Returns 0 if unknown. */
    public static int action(String name) {
        for (int i = 1; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return 0;
    }
}
