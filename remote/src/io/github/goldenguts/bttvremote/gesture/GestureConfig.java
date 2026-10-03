package io.github.goldenguts.bttvremote.gesture;

/** Gesture thresholds. Distances in mm, times in ms. */
public final class GestureConfig {
    // One finger: tap and D-pad drag
    public float tapMaxMm = 3f;
    public long tapMaxMs = 250;
    public float firstStepMm = 12f;
    public float stepMm = 8f;
    public float axisRatio = 1.3f;
    public float axisDecideMaxMm = 18f;
    public boolean reverseSteps = true;
    // Auto-repeat while held away from the start
    public long repeatDelayMs = 400;
    public long repeatIntervalMs = 150;
    public float holdStillMm = 2f;
    // Thumb menu: hold still, then lift or flick
    public long menuArmMs = 400;
    public float menuSelectMm = 10f;
    public float holdSlopMm = 4f;
    // Knob: wrist twist while the thumb menu is open
    public boolean knob = true;
    public float knobStepDeg = 15f;
    // Two fingers
    public long twoFingerJoinMs = 150;
    public long twoTapMaxMs = 300;
    public float twoTapMaxMm = 4f;
    public float twoStepMm = 15f;
    public long muteArmMs = 350;
    public long muteHoldMs = 700;
    // Rejection
    public float edgeRejectMm = 3f;
    public float palmMm = 14f;
    // In-app Back: swipe inward from the side edge strip
    public boolean edgeBack = false;
    public float edgeBackMm = 10f;
    /** Thumb-arc bias, see GestureEngine for the rotation direction. */
    public float biasDeg = 0f;

    public GestureConfig copy() {
        GestureConfig c = new GestureConfig();
        c.tapMaxMm = tapMaxMm;
        c.tapMaxMs = tapMaxMs;
        c.firstStepMm = firstStepMm;
        c.stepMm = stepMm;
        c.axisRatio = axisRatio;
        c.axisDecideMaxMm = axisDecideMaxMm;
        c.reverseSteps = reverseSteps;
        c.repeatDelayMs = repeatDelayMs;
        c.repeatIntervalMs = repeatIntervalMs;
        c.holdStillMm = holdStillMm;
        c.menuArmMs = menuArmMs;
        c.menuSelectMm = menuSelectMm;
        c.holdSlopMm = holdSlopMm;
        c.knob = knob;
        c.knobStepDeg = knobStepDeg;
        c.twoFingerJoinMs = twoFingerJoinMs;
        c.twoTapMaxMs = twoTapMaxMs;
        c.twoTapMaxMm = twoTapMaxMm;
        c.twoStepMm = twoStepMm;
        c.muteArmMs = muteArmMs;
        c.muteHoldMs = muteHoldMs;
        c.edgeRejectMm = edgeRejectMm;
        c.palmMm = palmMm;
        c.edgeBack = edgeBack;
        c.edgeBackMm = edgeBackMm;
        c.biasDeg = biasDeg;
        return c;
    }
}
