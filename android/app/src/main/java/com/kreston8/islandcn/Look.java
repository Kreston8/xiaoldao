package com.kreston8.islandcn;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

/**
 * The island's own colours: one tint and one opacity, shared by the notification card and the
 * desktop standby pill so the two never disagree.
 *
 * <p>Both values live in the preference file as text, because they are only read when a window is
 * created or repainted — never on the hot path of an incoming notification.
 *
 * <p>The presets are deliberately dark: every line on the island is white or near-white, so a pale
 * fill would make the card unreadable. Opacity is the knob for "make it quieter", not the colour.
 */
final class Look {

    /** Ink, charcoal, navy, teal-blue, forest, plum, wine, bronze. */
    private static final int[] PRESETS = {
            0xFF000000, 0xFF16161C, 0xFF16202E, 0xFF0E2230,
            0xFF10241C, 0xFF241428, 0xFF2C1418, 0xFF33260F
    };

    private Look() {
    }

    static int presets() {
        return PRESETS.length;
    }

    static int preset(int index) {
        return PRESETS[index];
    }

    /** The fill colour without opacity; black until the user picks something else. */
    static int color(Context context) {
        String raw = Prefs.text(context, Prefs.KEY_LOOK_COLOR).trim();
        if (raw.isEmpty()) {
            return 0xFF000000;
        }
        try {
            return Color.parseColor(raw) | 0xFF000000;
        } catch (Throwable failure) {
            return 0xFF000000;
        }
    }

    /** Opacity as stored, 0–100. */
    static int percent(Context context) {
        String raw = Prefs.text(context, Prefs.KEY_LOOK_ALPHA).trim();
        if (raw.isEmpty()) {
            return 100;
        }
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(raw)));
        } catch (Throwable failure) {
            return 100;
        }
    }

    /** Opacity in 0–255, the range a drawable wants. */
    static int alpha(Context context) {
        return percent(context) * 255 / 100;
    }

    /** What a GradientDrawable is given: the tint with the chosen opacity folded in. */
    static int background(Context context) {
        return withAlpha(color(context), alpha(context));
    }

    /**
     * The hair-line border, faded with the fill.
     *
     * <p>A see-through pill with a solid outline looks like a wire frame, so the border drops with
     * the fill instead of staying at full strength.
     */
    static int stroke(Context context) {
        return withAlpha(0xFF23232A, alpha(context));
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    static void setColor(Context context, int color) {
        Prefs.setText(context, Prefs.KEY_LOOK_COLOR, String.format("#%06X", color & 0xFFFFFF));
    }

    static void setPercent(Context context, int percent) {
        Prefs.setText(context, Prefs.KEY_LOOK_ALPHA,
                String.valueOf(Math.max(0, Math.min(100, percent))));
    }

    /**
     * Asks the running service to repaint both windows now, instead of on the next four-second tick.
     *
     * <p>Starting an already-running service only delivers the command, so this is cheap and safe to
     * call on every slider move. With the service stopped there is nothing on screen to repaint, so
     * the request is dropped instead of starting the service from the settings screen.
     */
    static void ping(Context context) {
        if (!IslandService.isRunning()) {
            return;
        }
        try {
            context.startService(new Intent(context, IslandService.class)
                    .setAction(IslandService.ACTION_LOOK));
        } catch (Throwable failure) {
            // No service running, or the platform refused the start: the next launch picks it up.
        }
    }
}
