package io.github.goldenguts.bttvremote;

import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * The vibration vocabulary. Each pattern uses composition primitives when the phone
 * supports all of them, else a predefined effect or a waveform that feels close.
 */
final class Haptics {

    enum Pattern { TICK_V, TICK_H, CLICK, BACK, MENU, RISE, HOME, PLAY, INPUT, SETTINGS, MUTE, READY, NO_LINK, ERROR, LOST }

    private final Vibrator vibrator;
    float strength = 1f;

    Haptics(Vibrator vibrator) {
        this.vibrator = vibrator;
    }

    void play(Pattern p) {
        if (vibrator == null || !vibrator.hasVibrator() || strength <= 0) return;
        VibrationEffect effect = composed(p);
        vibrator.vibrate(effect != null ? effect : fallback(p));
    }

    void stop() {
        if (vibrator != null) vibrator.cancel();
    }

    private VibrationEffect composed(Pattern p) {
        switch (p) {
            case TICK_V: return compose(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 0);
            case TICK_H: return compose(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 1f, 0);
            case CLICK: return compose(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 0);
            case BACK: return compose(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 0,
                    VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 40);
            case MENU: return compose(VibrationEffect.Composition.PRIMITIVE_THUD, 0.6f, 0);
            case RISE: return compose(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.6f, 0);
            case HOME: return compose(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 0,
                    VibrationEffect.Composition.PRIMITIVE_TICK, 1f, 30);
            case PLAY: return compose(VibrationEffect.Composition.PRIMITIVE_THUD, 0.5f, 0);
            case INPUT: return compose(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, 0,
                    VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 20);
            case SETTINGS: return compose(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 0,
                    VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 60);
            case MUTE: return compose(VibrationEffect.Composition.PRIMITIVE_THUD, 0.5f, 0,
                    VibrationEffect.Composition.PRIMITIVE_THUD, 0.5f, 80);
            case READY: return compose(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.8f, 0);
            default: return null;   // buzzes: waveforms are clearer than primitives
        }
    }

    private VibrationEffect fallback(Pattern p) {
        switch (p) {
            case TICK_V: return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            case TICK_H: return oneShot(12, 140);
            case CLICK: return VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            case BACK: return waveform(new long[]{0, 10, 40, 10}, new int[]{0, 200, 0, 200});
            case MENU: return oneShot(40, 160);
            case RISE: return waveform(new long[]{60, 60, 60, 60, 60}, new int[]{30, 60, 90, 120, 150});
            case HOME: return waveform(new long[]{0, 50, 30, 10}, new int[]{0, 255, 0, 200});
            case PLAY: return oneShot(35, 110);
            case INPUT: return waveform(new long[]{0, 15, 20, 10}, new int[]{0, 180, 0, 150});
            case SETTINGS: return VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK);
            case MUTE: return waveform(new long[]{0, 35, 80, 35}, new int[]{0, 110, 0, 110});
            case READY: return waveform(new long[]{30, 30, 30}, new int[]{60, 120, 200});
            case NO_LINK: return waveform(new long[]{0, 120, 80, 120}, new int[]{0, 90, 0, 90});
            case ERROR: return oneShot(300, 90);
            case LOST: return waveform(new long[]{0, 80, 60, 80, 60, 80}, new int[]{0, 100, 0, 100, 0, 100});
            default: return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        }
    }

    /** Triples of (primitive, scale, delay ms). Null if the phone lacks one of the primitives. */
    private VibrationEffect compose(Object... parts) {
        int n = parts.length / 3;
        int[] primitives = new int[n];
        for (int i = 0; i < n; i++) primitives[i] = (Integer) parts[i * 3];
        if (!vibrator.areAllPrimitivesSupported(primitives)) return null;
        VibrationEffect.Composition c = VibrationEffect.startComposition();
        for (int i = 0; i < n; i++) {
            float scale = Math.min(1f, (Float) parts[i * 3 + 1] * strength);
            c.addPrimitive(primitives[i], scale, (Integer) parts[i * 3 + 2]);
        }
        return c.compose();
    }

    private VibrationEffect oneShot(long ms, int amplitude) {
        return VibrationEffect.createOneShot(ms, scaled(amplitude));
    }

    private VibrationEffect waveform(long[] timings, int[] amplitudes) {
        int[] a = new int[amplitudes.length];
        for (int i = 0; i < a.length; i++) a[i] = amplitudes[i] == 0 ? 0 : scaled(amplitudes[i]);
        return VibrationEffect.createWaveform(timings, a, -1);
    }

    private int scaled(int amplitude) {
        return Math.max(1, Math.min(255, Math.round(amplitude * strength)));
    }
}
