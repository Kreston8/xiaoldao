package com.kreston8.islandcn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * The mascot: two pale eyes on the dark pill, and nothing else.
 *
 * <p>No face, no colour, no sprite sheet, because that is exactly what was asked for. The mood and
 * the blink both come from a hash of the wall-clock slot, so the rhythm survives dropped frames,
 * needs no extra timer and never looks like a metronome. v2.10 doubled the drawing size, shortened
 * the slots and added a sideways gaze plus a small bounce after each blink, because at 24 dp the
 * eyes read as two dots that never moved.
 */
final class DaozaiView extends View {

    /** 30 fps is enough for a blink and keeps a permanently visible window cheap. */
    private static final long FRAME_MS = 33L;
    /** One mood lasts this long: smile or round eyes, decided per slot. */
    private static final float MOOD_S = 2.4f;
    /** A blink every 1.7 s, with a quarter of the slots skipped so the beat is uneven. */
    private static final float BLINK_S = 1.7f;
    private static final float BLINK_LEN = 0.15f;
    /** How often the eyes look somewhere new. */
    private static final float GAZE_S = 1.2f;
    private static final int EYE_COLOR = 0xFFE9E9F0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private boolean running;
    private long start;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    DaozaiView(Context context) {
        super(context);
        paint.setColor(EYE_COLOR);
        paint.setStyle(Paint.Style.FILL);
        start = SystemClock.elapsedRealtime();
    }

    /** Starts or stops the frame loop; a hidden pill calls this so it costs nothing. */
    void animate(boolean on) {
        if (on == running) {
            return;
        }
        running = on;
        removeCallbacks(tick);
        if (on) {
            invalidate();
            postDelayed(tick, FRAME_MS);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onDetachedFromWindow() {
        animate(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility != VISIBLE) {
            animate(false);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0f) {
            return;
        }
        float t = (SystemClock.elapsedRealtime() - start) / 1000f;
        long slot = (long) (t / MOOD_S);
        boolean smile = (hash(slot * 2654435761L) & 1L) == 1L;

        // A slow vertical bob and a sideways look, both derived from the clock: the pair visibly
        // moves even on the slots where the mood does not change.
        float bob = (float) Math.sin(2 * Math.PI * t / MOOD_S) * size * 0.03f;
        long gazeSlot = (long) (t / GAZE_S);
        int look = (int) (hash(gazeSlot * 40503L) % 7L) - 3;
        float shift = size * 0.02f * look;

        float cx = getWidth() / 2f + shift;
        float cy = getHeight() / 2f + bob;
        float gap = size * 0.46f;
        float open = openAt(t);

        paint.setStyle(Paint.Style.FILL);
        if (smile) {
            // A crescent: an arc thick enough to read as a closed, happy eye.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.085f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float halfWidth = size * 0.135f;
            float height = size * 0.26f * (0.55f + 0.45f * open);
            for (int side = -1; side <= 1; side += 2) {
                float left = cx + side * gap / 2f - halfWidth;
                box.set(left, cy - height * 0.55f, left + halfWidth * 2f, cy + height * 0.75f);
                canvas.drawArc(box, 205f, 130f, false, paint);
            }
        } else {
            float halfWidth = size * 0.085f;
            float halfHeight = size * 0.155f * Math.max(open, 0.06f);
            float radius = Math.min(halfWidth, halfHeight);
            for (int side = -1; side <= 1; side += 2) {
                float left = cx + side * gap / 2f - halfWidth;
                box.set(left, cy - halfHeight, left + halfWidth * 2f, cy + halfHeight);
                canvas.drawRoundRect(box, radius, radius, paint);
            }
        }
    }

    /**
     * How far the eyes are open right now, 0..1.
     *
     * <p>Each blink slot either blinks or is skipped by the hash, so the eyes close at uneven
     * intervals without a counter. The short overshoot after the blink is what makes it read as a
     * blink rather than a fade.
     */
    private static float openAt(float t) {
        float phase = t % BLINK_S;
        long slot = (long) (t / BLINK_S);
        if ((hash(slot * 2246822519L) & 3L) == 0L) {
            // A quarter of the slots skip the blink entirely.
            return 1f;
        }
        float blinkAt = ((hash(slot * 3266489917L) >>> 8) & 1023L) / 1023f
                * (BLINK_S - BLINK_LEN - 0.15f);
        float delta = phase - blinkAt;
        if (delta >= 0f && delta < BLINK_LEN) {
            float close = (float) Math.sin(Math.PI * delta / BLINK_LEN);
            return 1f - 0.94f * close;
        }
        float after = delta - BLINK_LEN;
        if (after >= 0f && after < 0.10f) {
            return 1f + 0.12f * (1f - after / 0.10f);
        }
        return 1f;
    }

    /** A cheap 64-bit finaliser: enough to look random, and identical on every device. */
    private static long hash(long value) {
        long x = value;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x & Long.MAX_VALUE;
    }
}
