package com.kreston8.islandcn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The island itself: one fixed-height pill whose width follows its text.
 *
 * <p>v2.12 removed the expand/collapse pair. The pill used to sit as a 78 x 32 dot and spring open
 * into a 46 dp card on a tap; now it is always the card, so the first tap can mean something else
 * (open the message) instead of "grow". The overlay window is still WRAP_CONTENT, which is what
 * lets the card be exactly as wide as its own content.
 *
 * <p>The four gestures are the whole interaction model:
 * <ul>
 *   <li>double tap — copy what the card carries (a pickup code, a sentence);</li>
 *   <li>left swipe — close this card for good, and let the service remember it;</li>
 *   <li>long press, then up — shake, hide the island for three minutes, bring it back by itself;</li>
 *   <li>plain up swipe — page to the next queued card;</li>
 *   <li>single tap — open the message (an SMS thread, the clock, the app).</li>
 * </ul>
 *
 * <p>A single tap has to wait {@link #DOUBLE_MS} before it becomes "open", because a second tap in
 * that window means copy. Every other gesture fires on release.
 *
 * <p>The card body keeps three optional rows from earlier versions: a kind tag, a copyable code and
 * a countdown; the Bluetooth chips sit at the right end.
 */
public final class IslandView extends FrameLayout {

    /** What the island asks the service to do when the user touches it. */
    interface Actions {
        /** Single tap: open the message, the clock or the app behind the card. */
        void onOpen();

        /** Left swipe: close this card and never show that key again. */
        void onDismiss();

        /** Double tap: put this text on the clipboard. */
        void onCopy(String value);

        /** Up swipe: page to the next queued card. */
        void onNext();

        /** Long press then up: hide the island for this many milliseconds. */
        void onHideFor(long millis);
    }

    private static final int H_DP = 46;
    private static final float R_DP = 23f;
    private static final int W_MAX_DP = 368;
    private static final int W_MIN_DP = 190;
    private static final int W_MUSIC_MIN_DP = 240;
    private static final long TICK_MS = 15000L;
    private static final long CYCLE_MS = 2600L;
    private static final long LYRIC_MS = 100L;
    private static final int BT_MAX = 3;
    /** Roughly one chip: the icon is 14 dp, the percentage under it a little wider. */
    private static final int BT_CHIP_DP = 26;
    /** A second tap inside this window means copy; the single tap waits exactly this long. */
    private static final long DOUBLE_MS = 240L;
    /** How long a finger has to rest before the pill starts to shake. */
    private static final long PRESS_MS = 380L;
    /** Swipe distance, in dp, that counts as a gesture rather than a shaky tap. */
    private static final int SWIPE_DP = 42;
    /** Long press plus up: the island disappears for three minutes. */
    private static final long HIDE_MS = 180_000L;

    private final float density;
    private final int minWidthPx;
    private final int maxWidthPx;
    private final int musicMinPx;
    /** Current width before the Bluetooth chips, recomputed for every card. */
    private int baseWidthPx;
    private final FrameLayout card;
    private final GradientDrawable cardBackground = new GradientDrawable();
    private final LinearLayout expandedBox;
    private final ImageView appIcon;
    private final LinearLayout headerRow;
    private final TextView tagView;
    private final TextView titleView;
    private final TextView textView;
    private final LinearLayout copyRow;
    private final TextView codeView;
    private final TextView countdownView;
    private final LinearLayout btRow;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::updateCountdown;
    private final Runnable advance = this::advanceCycle;
    private final Runnable tapSoon = this::fireOpen;

    /** True while the card is the slim music strip, which sizes and pads differently. */
    private boolean musicMode;
    private Actions actions;
    private String copyValue;
    /** Everything the card shows, copied on a double tap; empty until the first bind. */
    private String copyAll = "";
    private long etaMillis;
    private int kind = IslandEvent.KIND_GENERIC;
    private CharSequence[] cycle;
    private int cycleIndex;
    private int cardWidthPx;

    private final Runnable lyricTick = this::tickLyric;
    private Lyric lyric;
    private long lyricBase;
    private long lyricAt;
    private int sungLine = -1;
    private int sungChars = -1;

    /** Where the finger went down, in raw screen coordinates. */
    private float downX;
    private float downY;
    private long lastTapAt;
    /** True when this touch sequence is the second tap of a double tap. */
    private boolean secondTap;
    /** True once the long press has fired and the pill shakes, waiting for the up swipe. */
    private boolean armed;
    /** True once the finger travelled far enough to be a swipe. */
    private boolean moved;
    private ValueAnimator shaker;
    private final Runnable press = this::arm;

    IslandView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        maxWidthPx = Math.min(
                dp(W_MAX_DP),
                getResources().getDisplayMetrics().widthPixels - dp(24));
        minWidthPx = Math.min(dp(W_MIN_DP), maxWidthPx);
        musicMinPx = Math.min(dp(W_MUSIC_MIN_DP), maxWidthPx);
        baseWidthPx = minWidthPx;
        cardWidthPx = minWidthPx;

        card = new FrameLayout(context);
        cardBackground.setShape(GradientDrawable.RECTANGLE);
        cardBackground.setColor(Look.background(context));
        cardBackground.setStroke(dp(1), Look.stroke(context));
        cardBackground.setCornerRadius(dp(R_DP));
        card.setBackground(cardBackground);
        addView(card, new FrameLayout.LayoutParams(minWidthPx, dp(H_DP)));
        // The window wraps this view, so the card can only travel inside it if there is slack on
        // both sides; without the padding the long-press shake would be clipped by the surface.
        setPadding(dp(6), 0, dp(6), 0);

        expandedBox = new LinearLayout(context);
        expandedBox.setOrientation(LinearLayout.HORIZONTAL);
        expandedBox.setGravity(Gravity.CENTER_VERTICAL);
        expandedBox.setPadding(dp(10), 0, dp(6), 0);
        card.addView(expandedBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        appIcon = new ImageView(context);
        appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        expandedBox.addView(appIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.leftMargin = dp(10);
        columnParams.rightMargin = dp(4);
        expandedBox.addView(column, columnParams);

        headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tagView = new TextView(context);
        tagView.setTextColor(0xFF7CE38B);
        tagView.setTextSize(12f);
        tagView.setTypeface(Typeface.DEFAULT_BOLD);
        tagView.setBackground(rounded(0xFF1E2A22, 6f));
        tagView.setPadding(dp(6), dp(2), dp(6), dp(2));
        tagView.setVisibility(View.GONE);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagParams.rightMargin = dp(6);
        headerRow.addView(tagView, tagParams);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15.5f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        headerRow.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        textView = new TextView(context);
        textView.setTextColor(0xFFB9B9C2);
        textView.setTextSize(13.5f);
        textView.setMaxLines(1);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(1);
        column.addView(textView, textParams);

        copyRow = new LinearLayout(context);
        copyRow.setOrientation(LinearLayout.HORIZONTAL);
        copyRow.setGravity(Gravity.CENTER_VERTICAL);
        copyRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.leftMargin = dp(6);
        // Sits in the title row: a third line would not fit the 46 dp strip.
        headerRow.addView(copyRow, copyParams);

        codeView = new TextView(context);
        codeView.setTextColor(0xFF7CE38B);
        codeView.setTextSize(14.5f);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setLetterSpacing(0.14f);
        codeView.setBackground(rounded(0xFF1E2A22, 8f));
        codeView.setPadding(dp(6), dp(1), dp(6), dp(1));
        copyRow.addView(codeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView copyHint = new TextView(context);
        copyHint.setText(R.string.copy_hint);
        copyHint.setTextColor(0xFF8A8A94);
        copyHint.setTextSize(11f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.leftMargin = dp(5);
        copyRow.addView(copyHint, hintParams);
        copyRow.setOnClickListener(v -> {
            if (actions != null && copyValue != null) {
                actions.onCopy(copyValue);
            }
        });

        countdownView = new TextView(context);
        countdownView.setTextColor(0xFFF0B860);
        countdownView.setTextSize(13f);
        countdownView.setVisibility(View.GONE);
        LinearLayout.LayoutParams countdownParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countdownParams.topMargin = dp(1);
        column.addView(countdownView, countdownParams);

        btRow = new LinearLayout(context);
        btRow.setOrientation(LinearLayout.HORIZONTAL);
        btRow.setGravity(Gravity.CENTER_VERTICAL);
        btRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams btParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btParams.leftMargin = dp(4);
        // Right end of the pill; the close button that used to follow it is gone, because closing
        // is now the left swipe.
        expandedBox.addView(btRow, btParams);

        card.setOnTouchListener(this::touch);
        applySizes();
    }

    void setActions(Actions actions) {
        this.actions = actions;
    }

    void bind(IslandEvent event) {
        if (event == null) {
            return;
        }
        boolean music = event.kind == IslandEvent.KIND_MUSIC;
        setMusicMode(music);
        if (event.icon != null) {
            appIcon.setImageBitmap(event.icon);
        }
        CharSequence heading = event.title == null || event.title.length() == 0
                ? event.appLabel
                : event.title;
        titleView.setText(heading);

        int tagRes = tagOf(event.kind);
        if (tagRes != 0) {
            tagView.setText(tagRes);
            tagView.setVisibility(View.VISIBLE);
        } else {
            tagView.setVisibility(View.GONE);
        }

        if (event.text == null || event.text.length() == 0) {
            textView.setVisibility(View.GONE);
        } else {
            textView.setVisibility(View.VISIBLE);
            textView.setText(event.text);
        }

        copyValue = event.copyText;
        if (copyValue == null || copyValue.isEmpty()) {
            copyRow.setVisibility(View.GONE);
        } else {
            codeView.setText(copyValue);
            copyRow.setVisibility(View.VISIBLE);
        }
        // One body line only: the strip is as tall as the music card.
        textView.setMaxLines(1);

        etaMillis = event.etaMillis;
        kind = event.kind;
        startCycle(music ? null : event.cycle);
        ticker.removeCallbacks(tick);
        boolean hasEta = etaMillis > System.currentTimeMillis();
        // A 46 dp strip holds two lines, so the countdown takes the body line when it has one.
        countdownView.setVisibility(hasEta ? View.VISIBLE : View.GONE);
        textView.setVisibility(hasEta ? View.GONE : View.VISIBLE);
        if (hasEta) {
            updateCountdown();
        }

        boolean karaoke =
                startLyric(event.kind == IslandEvent.KIND_MUSIC ? event.lyric : null, event.position);
        if (music && !karaoke) {
            // No timed lyric for this track, so the single line falls back to the song name.
            textView.setVisibility(View.VISIBLE);
            textView.setText(heading);
        }

        // Grow to fit the line first; only a line that still does not fit scrolls.
        copyAll = joinAll(heading);
        fitWidth();
        renderBt(event.bt);
        applySizes();
        applyScrolling(!karaoke);
        setContentDescription(event.appLabel);
    }

    /**
     * Everything the card currently shows, for the double-tap copy.
     *
     * <p>Heading, the visible body line and the code, each added once; a pickup card reads
     * "菜鸟驿站：1-2-3456" because the place leads and the code follows.
     */
    private String joinAll(CharSequence heading) {
        StringBuilder out = new StringBuilder();
        appendPart(out, heading == null ? null : heading.toString(), false);
        String body = null;
        if (countdownView.getVisibility() == View.VISIBLE) {
            body = countdownView.getText().toString();
        } else if (textView.getVisibility() == View.VISIBLE) {
            body = textView.getText().toString();
        }
        appendPart(out, body, kind == IslandEvent.KIND_PICKUP);
        appendPart(out, copyValue, kind == IslandEvent.KIND_PICKUP);
        return out.toString();
    }

    private static void appendPart(StringBuilder out, String part, boolean colon) {
        if (part == null) {
            return;
        }
        String value = part.trim();
        if (value.isEmpty() || out.indexOf(value) >= 0) {
            return;
        }
        if (out.length() > 0) {
            out.append(colon ? "：" : " · ");
        }
        out.append(value);
    }

    /** Fresh Bluetooth levels for the chips already on screen, without touching the rest. */
    void setChips(java.util.List<IslandEvent.Bt> devices) {
        renderBt(devices);
        applySizes();
    }

    /** Repaints the pill after the colour or the opacity changed in the settings screen. */
    void refreshLook() {
        cardBackground.setColor(Look.background(getContext()));
        cardBackground.setStroke(dp(1), Look.stroke(getContext()));
        card.invalidate();
        invalidate();
    }

    void stopTicker() {
        ticker.removeCallbacks(tick);
        ticker.removeCallbacks(advance);
        ticker.removeCallbacks(lyricTick);
        ticker.removeCallbacks(tapSoon);
        ticker.removeCallbacks(press);
        armed = false;
        secondTap = false;
        stopShake();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTicker();
        super.onDetachedFromWindow();
    }

    /**
     * The whole touch model.
     *
     * <p>The card consumes the sequence ({@code true} on down) so it can tell a swipe from a tap;
     * a clickable child, the copy chip, still wins because children get the event first. That is
     * also why the close button is gone: it would have been a second, invisible tap target right
     * where the swipe starts.
     */
    private boolean touch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                moved = false;
                armed = false;
                long now = SystemClock.uptimeMillis();
                secondTap = now - lastTapAt <= DOUBLE_MS;
                lastTapAt = now;
                ticker.removeCallbacks(tapSoon);
                if (secondTap) {
                    copyCard();
                } else {
                    ticker.postDelayed(press, PRESS_MS);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float moveX = event.getRawX() - downX;
                float moveY = event.getRawY() - downY;
                if (!moved && (Math.abs(moveX) > dp(8) || Math.abs(moveY) > dp(8))) {
                    moved = true;
                    if (!armed) {
                        // A swipe, not a press: cancel the pending shake.
                        ticker.removeCallbacks(press);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                ticker.removeCallbacks(press);
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (armed) {
                    armed = false;
                    stopShake();
                    if (-dy > dp(SWIPE_DP)) {
                        if (actions != null) {
                            actions.onHideFor(HIDE_MS);
                        }
                    }
                    return true;
                }
                if (secondTap) {
                    secondTap = false;
                    return true;
                }
                if (dx < -dp(SWIPE_DP) && Math.abs(dx) > Math.abs(dy)) {
                    if (actions != null) {
                        actions.onDismiss();
                    }
                    return true;
                }
                if (-dy > dp(SWIPE_DP) && Math.abs(dy) > Math.abs(dx)) {
                    if (actions != null) {
                        actions.onNext();
                    }
                    return true;
                }
                if (!moved) {
                    // Held back so the second tap of a double tap can cancel it.
                    ticker.postDelayed(tapSoon, DOUBLE_MS);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                ticker.removeCallbacks(press);
                ticker.removeCallbacks(tapSoon);
                armed = false;
                secondTap = false;
                stopShake();
                return true;
            default:
                return true;
        }
    }

    private void fireOpen() {
        if (actions != null) {
            actions.onOpen();
        }
    }

    /**
     * Double tap: the code when the card carries one, the visible line otherwise.
     *
     * <p>A card with neither (an alarm, say) copies nothing at all, which is what the person asked
     * for: codes and the daily sentence, nothing else.
     */
    private void copyCard() {
        if (actions == null) {
            return;
        }
        String value = null;
        if (copyValue != null && !copyValue.isEmpty()) {
            value = copyValue;
        } else if (textView.getVisibility() == View.VISIBLE
                && !textView.getText().toString().trim().isEmpty()) {
            value = textView.getText().toString().trim();
        }
        if (value != null) {
            actions.onCopy(value);
        }
    }

    /** Long press: the pill shakes so the next gesture has something to confirm it. */
    private void arm() {
        armed = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        startShake();
    }

    private void startShake() {
        stopShake();
        final float amplitude = dp(5);
        shaker = ValueAnimator.ofFloat(0f, 1f);
        shaker.setDuration(620L);
        shaker.setRepeatCount(ValueAnimator.INFINITE);
        shaker.setInterpolator(new LinearInterpolator());
        shaker.addUpdateListener(animation -> {
            float phase = (float) animation.getAnimatedValue() * (float) Math.PI * 6f;
            float wave = (float) Math.sin(phase);
            card.setTranslationX(wave * amplitude);
            // A squeeze that only ever shrinks, so the shiver survives a tightly composited window.
            float squeeze = 1f - 0.025f * Math.abs(wave);
            card.setScaleX(squeeze);
            card.setScaleY(squeeze);
        });
        shaker.start();
    }

    private void stopShake() {
        if (shaker != null) {
            shaker.cancel();
            shaker = null;
        }
        card.setTranslationX(0f);
        card.setScaleX(1f);
        card.setScaleY(1f);
    }

    private void updateCountdown() {
        if (countdownView.getVisibility() != View.VISIBLE) {
            return;
        }
        long left = etaMillis - System.currentTimeMillis();
        boolean alarm = kind == IslandEvent.KIND_ALARM;
        if (left <= 0L) {
            countdownView.setText(alarm ? R.string.alarm_ringing : R.string.eta_arriving);
            ticker.removeCallbacks(tick);
            return;
        }
        long minutes = (left + 59_999L) / 60_000L;
        countdownView.setText(getContext().getString(
                alarm ? R.string.alarm_minutes_left : R.string.eta_minutes, minutes));
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, TICK_MS);
    }

    /** Starts rotating the extra lines (song, then artist) while the card is on screen. */
    private void startCycle(CharSequence[] lines) {
        ticker.removeCallbacks(advance);
        cycle = lines != null && lines.length > 1 ? lines : null;
        cycleIndex = 0;
        if (cycle == null) {
            return;
        }
        // The heading already shows the first line, so start on the one after it.
        if (cycle[0] != null && TextUtils.equals(cycle[0], titleView.getText())) {
            cycleIndex = 1;
            textView.setText(cycle[1]);
        }
        scheduleAdvance();
    }

    private void scheduleAdvance() {
        ticker.removeCallbacks(advance);
        if (cycle != null) {
            ticker.postDelayed(advance, CYCLE_MS);
        }
    }

    private void advanceCycle() {
        if (cycle == null) {
            return;
        }
        cycleIndex = (cycleIndex + 1) % cycle.length;
        textView.setText(cycle[cycleIndex]);
        textView.setVisibility(View.VISIBLE);
        scheduleAdvance();
    }

    private static int tagOf(int kind) {
        switch (kind) {
            case IslandEvent.KIND_OTP:
                return R.string.tag_otp;
            case IslandEvent.KIND_PICKUP:
                return R.string.tag_pickup;
            case IslandEvent.KIND_DELIVERY:
                return R.string.tag_delivery;
            case IslandEvent.KIND_TODO:
                return R.string.tag_todo;
            case IslandEvent.KIND_CALL:
                return R.string.tag_call;
            case IslandEvent.KIND_ALARM:
                return R.string.tag_alarm;
            case IslandEvent.KIND_NAV:
                return R.string.tag_nav;
            case IslandEvent.KIND_MUSIC:
                return R.string.tag_music;
            default:
                return 0;
        }
    }

    /**
     * Turns the card into the slim music strip, or back into the normal card.
     *
     * <p>Music is the only kind that must stay small: the cover plus one lyric line, so it covers
     * as little of the launcher as possible.
     */
    private void setMusicMode(boolean music) {
        LinearLayout.LayoutParams icon = (LinearLayout.LayoutParams) appIcon.getLayoutParams();
        musicMode = music;
        if (music) {
            headerRow.setVisibility(View.GONE);
            tagView.setVisibility(View.GONE);
            copyRow.setVisibility(View.GONE);
            countdownView.setVisibility(View.GONE);
            textView.setTextSize(16.5f);
            textView.setMaxLines(1);
            textView.setTextColor(Color.WHITE);
            icon.width = dp(28);
            icon.height = dp(28);
            appIcon.setLayoutParams(icon);
            expandedBox.setPadding(dp(10), 0, dp(12), 0);
            baseWidthPx = musicMinPx;
            cardWidthPx = musicMinPx;
        } else {
            headerRow.setVisibility(View.VISIBLE);
            textView.setTextSize(13.5f);
            textView.setMaxLines(1);
            textView.setTextColor(0xFFB9B9C2);
            icon.width = dp(28);
            icon.height = dp(28);
            appIcon.setLayoutParams(icon);
            expandedBox.setPadding(dp(10), 0, dp(6), 0);
            baseWidthPx = minWidthPx;
            cardWidthPx = minWidthPx;
        }
    }

    /**
     * Starts the karaoke line for the track.
     *
     * @return true when the track has timed lyrics, so the caller can fall back to plain text.
     */
    private boolean startLyric(Lyric lines, long position) {
        ticker.removeCallbacks(lyricTick);
        lyric = lines == null || lines.isEmpty() ? null : lines;
        sungLine = -1;
        sungChars = -1;
        if (lyric == null) {
            return false;
        }
        lyricBase = Math.max(0L, position);
        lyricAt = SystemClock.elapsedRealtime();
        paintLyric(lyricBase);
        scheduleLyric();
        return true;
    }

    private void scheduleLyric() {
        ticker.removeCallbacks(lyricTick);
        if (lyric != null) {
            ticker.postDelayed(lyricTick, LYRIC_MS);
        }
    }

    private void tickLyric() {
        if (lyric == null) {
            return;
        }
        paintLyric(lyricBase + (SystemClock.elapsedRealtime() - lyricAt));
        scheduleLyric();
    }

    /**
     * Paints one line and lights up the characters already sung.
     *
     * <p>Timestamps are per line, so the character position is interpolated across the line. Spans
     * are only rebuilt when the character count changes, a handful of updates per second.
     */
    private void paintLyric(long position) {
        int index = lyric.indexAt(position);
        if (index < 0) {
            // Before the first stamp there is no line yet, so show the opening one, still unlit.
            if (lyric.lines.length == 0) {
                return;
            }
            index = 0;
        }
        long start = lyric.times[index];
        long end = lyric.endAt(index);
        String line = lyric.lines[index];
        float ratio = end > start ? (position - start) / (float) (end - start) : 1f;
        int lit = Math.round(line.length() * Math.max(0f, Math.min(1f, ratio)));
        if (index == sungLine && lit == sungChars) {
            return;
        }
        sungLine = index;
        sungChars = lit;
        SpannableString styled = new SpannableString(line);
        if (lit > 0) {
            styled.setSpan(new ForegroundColorSpan(Color.WHITE), 0, lit,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new StyleSpan(Typeface.BOLD), 0, lit,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (lit < line.length()) {
            styled.setSpan(new ForegroundColorSpan(0x66FFFFFF), lit, line.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        textView.setText(styled);
    }

    /**
     * Applies the one size the island has.
     *
     * <p>Height never changes any more; the width is whatever {@link #fitWidth()} measured, plus the
     * chips. The corner radius stays half the height, so the card is always a pill.
     */
    private void applySizes() {
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = cardWidthPx;
        params.height = dp(H_DP);
        card.setLayoutParams(params);
        cardBackground.setCornerRadius(dp(R_DP));
        expandedBox.setVisibility(View.VISIBLE);
        expandedBox.setAlpha(1f);
    }

    /**
     * Draws one chip per connected Bluetooth device, up to {@link #BT_MAX}.
     *
     * <p>Android exposes no public battery API for accessories: the percentage is whatever the
     * device last pushed through the system's own broadcast. A device that never reported one
     * keeps its icon and shows {@code --} instead of inventing a number.
     */
    private void renderBt(java.util.List<IslandEvent.Bt> devices) {
        btRow.removeAllViews();
        int count = devices == null ? 0 : Math.min(devices.size(), BT_MAX);
        if (count == 0) {
            btRow.setVisibility(View.GONE);
            cardWidthPx = baseWidthPx;
            return;
        }
        for (int i = 0; i < count; i++) {
            IslandEvent.Bt device = devices.get(i);
            if (device == null) {
                continue;
            }
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                chipParams.leftMargin = dp(5);
            }
            btRow.addView(btChip(device), chipParams);
        }
        btRow.setVisibility(View.VISIBLE);
        // The chips sit beside the text instead of eating it, so the pill grows by their width.
        cardWidthPx = Math.min(maxWidthPx, baseWidthPx + dp(BT_CHIP_DP) * count);
    }

    /**
     * Grows the pill to fit its text instead of cutting it off.
     *
     * <p>The old fixed widths are now minimums: a short message keeps the familiar size, a long one
     * widens until the line fits, and only past {@link #maxWidthPx} does the marquee take over.
     * Measuring the text views' own paints keeps the estimate honest with the applied fonts.
     */
    private void fitWidth() {
        int chrome = musicMode
                ? dp(10) + dp(28) + dp(10) + dp(12) + dp(6)
                : dp(10) + dp(28) + dp(10) + dp(4) + dp(6);
        int header = 0;
        if (headerRow.getVisibility() == View.VISIBLE) {
            if (tagView.getVisibility() == View.VISIBLE) {
                header += Math.round(tagView.getPaint().measureText(tagView.getText().toString()))
                        + tagView.getPaddingLeft() + tagView.getPaddingRight() + dp(6);
            }
            if (copyRow.getVisibility() == View.VISIBLE) {
                header += measure(codeView) + dp(6) + codeView.getPaddingLeft()
                        + codeView.getPaddingRight() + dp(40);
            }
        }
        int title = headerRow.getVisibility() == View.VISIBLE ? measure(titleView) : 0;
        int body = measure(textView);
        if (countdownView.getVisibility() == View.VISIBLE) {
            body = Math.max(body, measure(countdownView));
        }
        int need = chrome + Math.max(title, body + header);
        baseWidthPx = Math.max(musicMode ? musicMinPx : minWidthPx,
                Math.min(maxWidthPx, need));
        cardWidthPx = baseWidthPx;
    }

    private static int measure(TextView view) {
        CharSequence text = view.getText();
        if (text == null || text.length() == 0) {
            return 0;
        }
        return Math.round(view.getPaint().measureText(text, 0, text.length()));
    }

    /**
     * Turns marquee on for the two text lines, or off while karaoke paints them.
     *
     * <p>The lit characters are rebuilt several times a second; a marquee restarts whenever the
     * text changes, so the lyric line would jump back to the left mid-word. Lyric lines therefore
     * ellipsize instead of scrolling.
     */
    private void applyScrolling(boolean scroll) {
        TextView[] lines = {titleView, textView};
        for (TextView view : lines) {
            view.setSingleLine(true);
            if (scroll) {
                view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                view.setMarqueeRepeatLimit(-1);
                view.setSelected(true);
            } else {
                view.setEllipsize(TextUtils.TruncateAt.END);
                view.setSelected(false);
            }
        }
    }

    private View btChip(IslandEvent.Bt device) {
        LinearLayout chip = new LinearLayout(getContext());
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageResource(device.icon);
        chip.addView(icon, new LinearLayout.LayoutParams(dp(14), dp(14)));
        TextView percent = new TextView(getContext());
        percent.setText(device.level < 0 ? "--" : device.level + "%");
        percent.setTextColor(device.level > 0 && device.level <= 20 ? 0xFFF0B860 : 0xFFB9B9C2);
        percent.setTextSize(10.5f);
        percent.setIncludeFontPadding(false);
        percent.setSingleLine(true);
        chip.addView(percent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chip.setContentDescription(device.name + " " + percent.getText());
        return chip;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
