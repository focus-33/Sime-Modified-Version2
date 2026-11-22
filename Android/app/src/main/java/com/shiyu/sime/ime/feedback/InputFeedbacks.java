package com.shiyu.sime.ime.feedback;

import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Process-global key-press feedback (haptic only; the IME makes no sound).
 *
 * <p>For haptic we call {@link View#performHapticFeedback} without any
 * override flags, so the system's {@code HAPTIC_FEEDBACK_ENABLED} setting
 * still applies — same "follow system" semantics.
 *
 * <p>State is held in static volatile fields so {@link com.shiyu.sime.ime.keyboard.framework.KeyView}
 * can fire feedback without plumbing prefs through every constructor.
 * {@link com.shiyu.sime.SimeService} pushes the current prefs at
 * startup and on settings changes.
 */
public final class InputFeedbacks {

    private static volatile boolean vibrationEnabled = true;

    private InputFeedbacks() {}

    public static void setVibrationEnabled(boolean enabled) {
        vibrationEnabled = enabled;
    }

    /** Fire on key DOWN. Cheap when vibration is off. */
    public static void onKeyPress(View view) {
        if (vibrationEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    /**
     * Wrap a click listener so the system's default click sound is
     * suppressed and our {@link #onKeyPress} fires instead. Use
     * this for any clickable that isn't a {@link com.shiyu.sime.ime.keyboard.framework.KeyView}
     * (candidates, toolbar buttons, etc.) — KeyView already fires
     * feedback in its own touch dispatch and bypasses {@code performClick}.
     */
    public static void wireClick(View view, Runnable action) {
        view.setSoundEffectsEnabled(false);
        view.setOnClickListener(v -> {
            onKeyPress(v);
            action.run();
        });
    }
}
