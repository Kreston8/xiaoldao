package com.kreston8.islandcn;

import android.animation.ValueAnimator;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The always-on pill on the launcher: weather, air quality, the mascot and one line of the day.
 *
 * <p>It is a second overlay window rather than a card, because it has to appear when nothing else
 * is happening and disappear the moment another app shows up; the service decides that and only
 * asks this view to rebuild its contents. The service also alternates this pill with a notification
 * card, which is why visibility is never set from here.
 *
 * <p>Both online extras are honest about their limits: temperature and air quality come from public
 * endpoints and only after the user typed a city, so an empty city keeps the app offline.
 */
final class StandbyView extends FrameLayout {

    private static final int H_DP = 50;
    private static final float R_DP = 25f;
    private static final int GAP_DP = 8;
    /** How long the finger has to rest before the pill starts shaking. */
    private static final long PRESS_MS = 380L;
    /** Upward travel, once armed, that counts as the hide swipe. Shorter than the card's: the pill is thin. */
    private static final int SWIPE_DP = 30;
    /** A second tap inside this window is a double tap, not a new gesture. */
    private static final long DOUBLE_MS = 240L;


    private final LinearLayout row;
    private final GradientDrawable background;
    private final float density;

    private String signature = "";
    private boolean hasContent;
    private DaozaiView face;
    /** The line of the day, kept as it stands so a double tap copies the quote alone. */
    private String copyText = "";
    /** When the last tap on the pill landed, for telling a double tap from a single one. */
    private long lastTapAt = 0L;
    /** Fires arm() once the finger has rested; cancelled by any movement, lift or cancel. */
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable press = this::arm;
    /** The service's "hide for a while", or null when the pill was built on its own. */
    private Runnable hideAction;
    /** The running shiver, or null while the pill is still. */
    private ValueAnimator shaker;
    private float downX;
    private float downY;
    private boolean moved;
    private boolean armed;
    /** True when the finger went down on the temperature or the air badge. */
    private boolean tapWeather;
    /** The two weather readouts, hit-tested by hand so they stay buttons without stealing gestures. */
    private View weatherBox;
    private View aqiBadge;

    StandbyView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(Look.background(context));
        background.setStroke(dp(1), Look.stroke(context));
        background.setCornerRadius(dp(R_DP));
        setBackground(background);

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(H_DP)));
        setVisibility(View.INVISIBLE);

        // Double tap copies the line of the day, long press then swipe up silences everything for
        // three minutes. Both live here because the pill is what the person actually sees.
        setOnTouchListener(this::touch);
    }

    /** The service's "hide for a while", handed in so this view needs no reference to it. */
    void setHideAction(Runnable action) {
        hideAction = action;
    }

    /**
     * The pill's gestures.
     *
     * <p>A tap is meaningless here (there is nothing to open), so a double tap copies and a long
     * press arms a swipe. Swallowing the stream is what makes the long press repeatable: the launcher
     * behind the pill never sees a half gesture.
     */
    private boolean touch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                moved = false;
                armed = false;
                tapWeather = inside(weatherBox, downX, downY) || inside(aqiBadge, downX, downY);
                long now = SystemClock.uptimeMillis();
                if (now - lastTapAt <= DOUBLE_MS) {
                    lastTapAt = 0L;
                    copyPill();
                } else {
                    lastTapAt = now;
                    ticker.postDelayed(press, PRESS_MS);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && (Math.abs(event.getRawX() - downX) > dp(8)
                        || Math.abs(event.getRawY() - downY) > dp(8))) {
                    moved = true;
                    if (!armed) {
                        // A swipe that starts as a swipe is not a long press.
                        ticker.removeCallbacks(press);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                ticker.removeCallbacks(press);
                if (armed) {
                    armed = false;
                    stopShake();
                    float travel = event.getRawY() - downY;
                    if (-travel > dp(SWIPE_DP) && hideAction != null) {
                        hideAction.run();
                    }
                    return true;
                }
                // A tap on the temperature or the air badge is a button, not decoration.
                if (!moved && tapWeather) {
                    openWeather();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                ticker.removeCallbacks(press);
                armed = false;
                lastTapAt = 0L;
                stopShake();
                return true;
            default:
                return true;
        }
    }

    /**
     * Whether a raw screen point falls inside a child of the pill.
     *
     * <p>The children are not made clickable on purpose: a clickable child would consume the
     * gesture and the pill would lose its double tap and its long press over that area. Asking the
     * view where it is on screen keeps both.
     */
    private boolean inside(View child, float rawX, float rawY) {
        if (child == null || child.getWidth() == 0 || child.getHeight() == 0) {
            return false;
        }
        try {
            int[] origin = new int[2];
            child.getLocationOnScreen(origin);
            return rawX >= origin[0] && rawY >= origin[1]
                    && rawX < origin[0] + child.getWidth() && rawY < origin[1] + child.getHeight();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The long press landed: say so with a shiver and a tick. */
    private void arm() {
        if (armed) {
            return;
        }
        armed = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        startShake();
    }

    /**
     * Shivers the content inside the pill.
     *
     * <p>The pill's background is this view, and this view is the whole window, so translating it
     * would be clipped by its own surface. The row inside moves instead, which reads as the pill
     * shaking while nothing leaves its bounds.
     */
    private void startShake() {
        stopShake();
        shaker = ValueAnimator.ofFloat(0f, 1f);
        shaker.setDuration(90L);
        shaker.setRepeatCount(ValueAnimator.INFINITE);
        shaker.setRepeatMode(ValueAnimator.RESTART);
        shaker.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            float wave = (float) Math.sin(fraction * Math.PI * 2f);
            // The content moves inside the row's 12 dp padding and tilts a hair, so the shiver stays
            // visible without the window surface clipping it, and the pill squeezes inward.
            row.setTranslationX(wave * dp(3));
            row.setRotation(wave * 0.9f);
            float squeeze = 1f - 0.03f * Math.abs(wave);
            setScaleX(squeeze);
            setScaleY(squeeze);
        });
        shaker.start();
    }

    private void stopShake() {
        if (shaker != null) {
            shaker.cancel();
            shaker = null;
        }
        row.setTranslationX(0f);
        row.setRotation(0f);
        setScaleX(1f);
        setScaleY(1f);
    }

    /**
     * Opens the phone's own weather app.
     *
     * <p>A package lookup alone is not enough on MIUI: the activity is there but
     * getLaunchIntentForPackage() can come back empty, so the known MIUI components are tried by
     * name first. Only when nothing at all can be started does this fall back to the web page.
     */
    private void openWeather() {
        if (startWeather(new ComponentName("com.miui.weather2", "com.miui.weather2.ActivityWeatherMain"))
                || startWeather(new ComponentName("com.miui.weather2", "com.miui.weather2.WeatherMainActivity"))
                || startWeather(new ComponentName("com.miui.weather2", "com.miui.weather2.MainActivity"))
                || startWeather(new ComponentName("com.miui.weather", "com.miui.weather.ActivityWeatherMain"))
                || startWeatherPackage("com.miui.weather2")
                || startWeatherPackage("com.miui.weather")
                || startWeatherByLabel()
                || startWeatherPackage("com.huawei.android.totemweather")
                || startWeatherPackage("com.coloros.weather2")
                || startWeatherPackage("com.android.weather")) {
            return;
        }
        try {
            Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse("https://weather.miui.com"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            getContext().startActivity(page);
        } catch (Throwable ignored) {
            Toast.makeText(getContext(), R.string.weather_no_app, Toast.LENGTH_LONG).show();
        }
    }

    /** Starts one explicit weather activity; false when the component is absent or blocked. */
    private boolean startWeather(ComponentName component) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            getContext().startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Starts an app by package name through its launcher intent. */
    private boolean startWeatherPackage(String pkg) {
        try {
            Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            getContext().startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Last non-web resort: take the first launcher entry whose label says weather.
     *
     * <p>This is what catches the app when its package name is an OEM name nobody can spell.
     */
    private boolean startWeatherByLabel() {
        try {
            PackageManager pm = getContext().getPackageManager();
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(probe, 0)) {
                if (info == null || info.activityInfo == null) {
                    continue;
                }
                CharSequence label = info.loadLabel(pm);
                String name = label == null ? "" : label.toString().toLowerCase(Locale.ROOT);
                if (!name.contains("天气") && !name.contains("weather")) {
                    continue;
                }
                if (startWeather(new ComponentName(info.activityInfo.packageName, info.activityInfo.name))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    boolean hasContent() {
        return hasContent;
    }

    /**
     * Rebuilds the pill from the stored order and switches.
     *
     * <p>Called on a timer, so the children are only replaced when something actually changed;
     * otherwise the mascot would restart its blink cycle four times a minute.
     *
     * @return true when at least one item is switched on and ready to draw.
     */
    boolean refresh() {
        Context context = getContext();
        String[] order = Prefs.order(context);
        boolean weatherOn = Prefs.get(context, Prefs.KEY_STANDBY_WEATHER);
        boolean aqiOn = Prefs.get(context, Prefs.KEY_STANDBY_AQI);
        boolean faceOn = Prefs.get(context, Prefs.KEY_STANDBY_PUPPY);
        boolean quoteOn = Prefs.get(context, Prefs.KEY_STANDBY_QUOTE);
        boolean btOn = Prefs.get(context, Prefs.KEY_STANDBY_BT);
        // Read once per rebuild: peek() reflects into the platform for every accessory, and the pill
        // is rebuilt every four seconds.
        java.util.List<IslandEvent.Bt> devices = null;
        if (btOn) {
            try {
                devices = BtWatcher.peek(context);
            } catch (Throwable failure) {
                devices = null;
            }
        }
        String deviceSignature = "";
        if (devices != null) {
            StringBuilder builder = new StringBuilder();
            for (IslandEvent.Bt device : devices) {
                if (device != null) {
                    builder.append(device.name).append('/').append(device.level).append(';');
                }
            }
            deviceSignature = builder.toString();
        }

        String temperature = null;
        String air = null;
        int code = -1;
        if (weatherOn || aqiOn) {
            Weather.Reading reading =
                    Weather.snapshot(context, Prefs.text(context, Prefs.KEY_CITY), this::onWeather);
            if (reading != null) {
                temperature = reading.temp;
                code = reading.code;
                air = reading.aqi;
            }
        }
        String quote = quoteOn ? quote(context) : "";
        String next = String.join("|", order) + "#" + temperature + "#" + code + "#" + air
                + "#" + faceOn + "#" + quoteOn + "#" + quote + "#" + btOn + "#" + deviceSignature;
        if (next.equals(signature)) {
            return hasContent;
        }
        signature = next;

        face = null;
        weatherBox = null;
        aqiBadge = null;
        row.removeAllViews();
        int count = 0;
        // Nothing is copied until the quote is actually drawn.
        copyText = "";
        for (String item : order) {
            if ("weather".equals(item)) {
                if (!weatherOn || temperature == null) {
                    continue;
                }
                weatherBox = weatherIcon(code, temperature);
                row.addView(weatherBox);
                count++;
            } else if ("aqi".equals(item)) {
                if (!aqiOn || air == null || air.isEmpty()) {
                    continue;
                }
                TextView badge = label("AQI " + air, aqiColor(air), 12.5f, true);
                aqiBadge = badge;
                row.addView(badge, margin());
                count++;
            } else if ("puppy".equals(item)) {
                if (!faceOn) {
                    continue;
                }
                // 32 dp against a 50 dp pill: the eyes have to read as eyes at arm's length.
                face = new DaozaiView(context);
                row.addView(face, fixed(dp(32), dp(32)));
                face.animate(getVisibility() == View.VISIBLE && isShown());
                count++;
            } else if ("quote".equals(item)) {
                if (!quoteOn || quote.isEmpty()) {
                    continue;
                }
                // Serif and bold, the widest thing in the pill: a line longer than the cap scrolls
                // like an LED banner instead of being cut or widening the pill past the screen.
                TextView line = label(quote, 0xFFDDDDE4, 17.5f, true);
                line.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                line.setSingleLine(true);
                line.setHorizontallyScrolling(true);
                line.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                line.setMarqueeRepeatLimit(-1);
                line.setMaxWidth(dp(230));
                line.setSelected(true);
                row.addView(line, margin());
                // A double tap copies this exact line.
                copyText = quote;
                count++;
            } else if ("bt".equals(item)) {
                LinearLayout accessories = btChips(devices);
                if (accessories == null) {
                    continue;
                }
                row.addView(accessories, margin());
                count++;
            }
        }
        hasContent = count > 0;
        return hasContent;
    }

    /** Repaints the pill after the colour or the opacity changed in the settings screen. */
    void refreshLook() {
        background.setColor(Look.background(getContext()));
        background.setStroke(dp(1), Look.stroke(getContext()));
        invalidate();
    }

    /**
     * Copies the line of the day, so a double tap on the pill gives the quote alone.
     *
     * @return true when there was something to copy.
     */
    private boolean copyPill() {
        if (copyText.isEmpty()) {
            return false;
        }
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return false;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getContext().getString(R.string.app_name), copyText));
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Toast.makeText(getContext(), R.string.copied_all, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** A weather glyph plus the degree reading, sized to sit inside the 44 dp pill. */
    private LinearLayout weatherIcon(int code, String temperature) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, 0, 0, 0);

        ImageView glyph = new ImageView(getContext());
        glyph.setImageResource(iconFor(code));
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(glyph, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView degrees = label(temperature, Color.WHITE, 15f, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(4);
        box.addView(degrees, params);
        return box;
    }

    /**
     * Accessory glyphs with their percentages, stacked vertically so both fit in the pill's height.
     *
     * <p>At most three, the same cap the card uses. A device that never reported a level shows "--"
     * rather than an invented number.
     */
    private LinearLayout btChips(java.util.List<IslandEvent.Bt> devices) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int shown = 0;
        for (IslandEvent.Bt device : devices) {
            if (device == null || shown >= 3) {
                break;
            }
            LinearLayout one = new LinearLayout(getContext());
            one.setOrientation(LinearLayout.VERTICAL);
            one.setGravity(Gravity.CENTER_HORIZONTAL);

            ImageView glyph = new ImageView(getContext());
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(15), dp(15));
            iconParams.gravity = Gravity.CENTER_HORIZONTAL;
            glyph.setImageResource(device.icon != 0 ? device.icon : R.drawable.ic_bt_generic);
            glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
            one.addView(glyph, iconParams);

            TextView percent = label(device.level >= 0 ? device.level + "%" : "--",
                    0xFFC6C6CE, 9.5f, true);
            percent.setGravity(Gravity.CENTER);
            one.addView(percent);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (shown > 0) {
                params.leftMargin = dp(7);
            }
            box.addView(one, params);
            shown++;
        }
        return shown == 0 ? null : box;
    }

    /** Stops the animation when the pill is hidden, so a hidden window costs nothing. */
    void pause() {
        if (face != null) {
            face.animate(false);
        }
    }

    /** Restarts the mascot after the pill came back from being covered. */
    void resume() {
        if (face != null) {
            face.animate(true);
        }
    }

    private void onWeather() {
        // The fetch finished on the main thread; the next refresh picks the value up.
        signature = "";
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        pause();
        super.onDetachedFromWindow();
    }

    private TextView label(String text, int color, float size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    /** Wrap-content parameters with the gap before every item but the first. */
    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) {
            params.leftMargin = dp(GAP_DP);
        }
        return params;
    }

    private LinearLayout.LayoutParams fixed(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        if (row.getChildCount() > 0) {
            params.leftMargin = dp(GAP_DP);
        }
        return params;
    }

    /**
     * Today's line.
     *
     * <p>Indexed by days since the epoch with a stride of seven, so it changes exactly once a day,
     * never repeats on two days in a row, and does not walk the list in order.
     */
    private static String quote(Context context) {
        try {
            Resources resources = context.getResources();
            String[] quotes = resources.getStringArray(R.array.quotes);
            if (quotes.length == 0) {
                return "";
            }
            long days = System.currentTimeMillis() / 86_400_000L;
            return quotes[Math.floorMod(days * 7L, quotes.length)];
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** WMO weather code to a glyph: clear, cloudy, wet, frozen. */
    private static int iconFor(int code) {
        if (code >= 0 && code <= 1) {
            return R.drawable.ic_wx_sun;
        }
        if (code >= 71 && code <= 77 || code == 85 || code == 86) {
            return R.drawable.ic_wx_snow;
        }
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82 || code >= 95) {
            return R.drawable.ic_wx_rain;
        }
        return R.drawable.ic_wx_cloud;
    }

    /** US AQI bands, the same green/amber/orange/red a weather app would show. */
    private static int aqiColor(String value) {
        int level;
        try {
            level = Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return 0xFFC6C6CE;
        }
        if (level <= 50) {
            return 0xFF6FCF97;
        }
        if (level <= 100) {
            return 0xFFF2C94C;
        }
        if (level <= 150) {
            return 0xFFF2994A;
        }
        return 0xFFEB5757;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    /**
     * Current weather and air quality for a city name, or null while unknown.
     *
     * <p>Three requests, two hosts: the name becomes coordinates, the forecast gives the temperature
     * and the weather code, and the air-quality endpoint gives the US AQI. All three are cached for
     * half an hour, which is far cheaper than polling and still looks live. Nothing is ever requested
     * without a city, so leaving the field empty keeps the app completely offline.
     */
    static final class Weather {

        private static final long TTL_MS = 30 * 60_000L;
        private static final int TIMEOUT_MS = 6000;
        private static final int MAX_BYTES = 64 * 1024;

        private static String city = "";
        private static Reading cache;
        private static long at;
        private static boolean busy;
        /** What the last request did and when, so a failure can be shown instead of guessed at. */
        private static String status = "";
        private static long statusAt;
        /** Nothing is asked again before this moment after a failure. */
        private static long retryAt;
        private static final long FAIL_MS = 90_000L;

        private Weather() {
        }

        static final class Reading {
            final String temp;
            final int code;
            final String aqi;
            /** Where the numbers came from, so a wrong district can be spotted immediately. */
            final String place;

            Reading(String temp, int code, String aqi, String place) {
                this.temp = temp;
                this.code = code;
                this.aqi = aqi;
                this.place = place;
            }
        }

        static Reading snapshot(Context context, String name, Runnable done) {
            if (name == null || name.trim().isEmpty()) {
                status = context.getString(R.string.weather_state_nocity);
                statusAt = 0L;
                retryAt = 0L;
                return null;
            }
            String wanted = name.trim();
            if (!wanted.equals(city)) {
                // A different city invalidates the old reading instead of showing it for a while.
                city = wanted;
                cache = null;
                at = 0L;
                retryAt = 0L;
                status = "";
                statusAt = 0L;
            }
            long now = System.currentTimeMillis();
            if (cache != null && now - at < TTL_MS) {
                return cache;
            }
            if (now < retryAt) {
                // The pill refreshes every four seconds, so a failed request needs a quiet period
                // instead of hammering an endpoint that just said no.
                return cache;
            }
            fetch(context, wanted, done);
            return cache;
        }

        private static void fetch(final Context context, final String wanted, final Runnable done) {
            if (busy) {
                return;
            }
            busy = true;
            Thread worker = new Thread(() -> {
                Reading value = null;
                try {
                    value = request(context, wanted);
                } catch (Throwable failure) {
                    // Offline or blocked: remember why, and stay off the network for a while.
                    mark(context, context.getString(R.string.weather_err_network,
                            "open-meteo.com", reason(failure)), false);
                }
                final Reading result = value;
                busy = false;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (result != null && wanted.equals(city)) {
                        cache = result;
                        at = System.currentTimeMillis();
                    }
                    if (done != null) {
                        done.run();
                    }
                });
            }, "island-weather");
            worker.setDaemon(true);
            worker.start();
        }

        private static Reading request(Context context, String name) {
            // 「杭州 西湖」 must be asked as 西湖: the endpoint matches one place name, so the pair
            // used to come back empty and the chip stayed on 杭州市.
            String term = lookupTerm(name);
            JSONObject found = search(context, term);
            if (found == null) {
                return null;
            }
            JSONArray results = found.optJSONArray("results");
            if ((results == null || results.length() == 0) && !term.equals(name)) {
                // The split term failed, so the user probably typed one long name; try it whole.
                JSONObject whole = search(context, name);
                JSONArray again = whole == null ? null : whole.optJSONArray("results");
                if (again != null && again.length() > 0) {
                    results = again;
                    term = name;
                }
            }
            JSONObject place = results == null ? null : pick(results, term);
            if (place == null) {
                mark(context, context.getString(R.string.weather_err_place, name), false);
                return null;
            }
            double latitude = place.optDouble("latitude", Double.NaN);
            double longitude = place.optDouble("longitude", Double.NaN);
            if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                mark(context, context.getString(R.string.weather_err_place, name), false);
                return null;
            }

            String temperature = null;
            int code = -1;
            String forecastError = null;
            try {
                JSONObject forecast = new JSONObject(read(
                        "https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code"
                                + "&latitude=" + latitude + "&longitude=" + longitude));
                JSONObject current = forecast.optJSONObject("current");
                if (current != null && current.has("temperature_2m")) {
                    temperature = Math.round(current.optDouble("temperature_2m", 0d)) + "\u00b0";
                    code = current.optInt("weather_code", -1);
                }
            } catch (Exception failure) {
                forecastError = reason(failure);
            }

            String air = null;
            try {
                JSONObject quality = new JSONObject(read(
                        "https://air-quality-api.open-meteo.com/v1/air-quality?current=us_aqi"
                                + "&latitude=" + latitude + "&longitude=" + longitude));
                JSONObject current = quality.optJSONObject("current");
                if (current != null && current.has("us_aqi")) {
                    air = String.valueOf(Math.round(current.optDouble("us_aqi", 0d)));
                }
            } catch (Exception ignored) {
                // A missing AQI only hides one chip.
            }

            if (temperature == null && air == null) {
                mark(context, forecastError == null
                                ? context.getString(R.string.weather_err_empty)
                                : context.getString(R.string.weather_err_network,
                                "api.open-meteo.com", forecastError),
                        false);
                return null;
            }
            String label = placeLabel(place);
            mark(context, context.getString(R.string.weather_ok_place, label,
                    temperature == null ? "-" : temperature,
                    air == null ? "-" : air), true);
            return new Reading(temperature, code, air, label);
        }
        /** One geocoding call, or null after marking why it failed. */
        private static JSONObject search(Context context, String term) {
            try {
                return new JSONObject(read(
                        "https://geocoding-api.open-meteo.com/v1/search?count=10&language=zh&format=json"
                                + (hasHan(term) ? "&countryCode=CN" : "")
                                + "&name=" + encode(term)));
            } catch (Exception failure) {
                mark(context, context.getString(R.string.weather_err_network,
                        "geocoding-api.open-meteo.com", reason(failure)), false);
                return null;
            }
        }

        /**
         * The term actually asked for: 「杭州 西湖」 is sent as 西湖. The endpoint matches one place
         * name, so the pair used to come back empty and the card stayed on 杭州市.
         */
        private static String lookupTerm(String input) {
            String trimmed = input == null ? "" : input.trim();
            for (int i = trimmed.length() - 1; i > 0; i--) {
                char c = trimmed.charAt(i);
                if (c == ' ' || c == '\u3000' || c == ',' || c == '\uff0c' || c == '/' || c == '·') {
                    return trimmed.substring(i + 1).trim();
                }
            }
            return trimmed;
        }
        /** "北京市 朝阳", most specific part first deduplicated, so the user can see what resolved. */
        private static String placeLabel(JSONObject place) {
            if (place == null) {
                return "";
            }
            String[] parts = {place.optString("admin1", ""), place.optString("admin2", ""),
                    place.optString("name", "")};
            StringBuilder out = new StringBuilder();
            String previous = "";
            for (String part : parts) {
                String key = norm(part);
                if (key.isEmpty() || key.equals(previous)) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(part.trim());
                previous = key;
            }
            return out.toString();
        }

        /**
         * The best of the ten candidates for what was typed.
         *
         * <p>An exact name match beats a district or province that matched by name, and a larger
         * population only breaks ties, so typing 朝阳区 lands on a Beijing district instead of on
         * 朝阳市 in Liaoning.
         */
        private static JSONObject pick(JSONArray results, String raw) {
            String wanted = norm(raw);
            JSONObject best = null;
            int bestScore = -1;
            long bestPopulation = -1L;
            for (int i = 0; i < results.length(); i++) {
                JSONObject one = results.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                int score = score(one, wanted);
                long population = (long) one.optDouble("population", 0d);
                if (score > bestScore || (score == bestScore && population > bestPopulation)) {
                    best = one;
                    bestScore = score;
                    bestPopulation = population;
                }
            }
            return best;
        }

        private static int score(JSONObject one, String wanted) {
            String name = norm(one.optString("name", ""));
            String admin1 = norm(one.optString("admin1", ""));
            String admin2 = norm(one.optString("admin2", ""));
            String admin3 = norm(one.optString("admin3", ""));
            int score = 0;
            if (name.equals(wanted)) {
                score += 60;
            } else if (name.startsWith(wanted) || wanted.startsWith(name) && name.length() >= 2) {
                score += 40;
            }
            if (admin3.equals(wanted)) {
                score += 55;
            }
            if (admin2.equals(wanted)) {
                score += 50;
            }
            if (admin1.equals(wanted)) {
                score += 35;
            }
            if (!admin2.isEmpty() && (admin2.contains(wanted) || wanted.contains(admin2))) {
                score += 12;
            }
            return score;
        }

        /** Drops the administrative suffix and the case, so 朝阳区 and 朝阳 compare equal. */
        private static String norm(String value) {
            String out = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            String[] suffixes = {"特别行政区", "自治区", "自治州", "自治县", "新区", "地区", "街道",
                    "省", "市", "区", "县", "旗", "镇", "乡"};
            for (String suffix : suffixes) {
                if (out.length() > suffix.length() && out.endsWith(suffix)) {
                    out = out.substring(0, out.length() - suffix.length());
                    break;
                }
            }
            return out;
        }

        /** True when the name has Han characters, which is when a China-only lookup makes sense. */
        private static boolean hasHan(String value) {
            for (int i = 0; value != null && i < value.length(); i++) {
                char one = value.charAt(i);
                if (one >= 0x4E00 && one <= 0x9FFF) {
                    return true;
                }
            }
            return false;
        }

        /** What a thrown failure says, or a dash when it says nothing. */
        private static String reason(Throwable failure) {
            String message = failure == null ? null : failure.getMessage();
            return message == null || message.isEmpty() ? "?" : message;
        }

        /** Records the outcome and, after a failure, the moment the next attempt is allowed. */
        private static void mark(Context context, String message, boolean ok) {
            status = message;
            statusAt = System.currentTimeMillis();
            retryAt = ok ? 0L : statusAt + FAIL_MS;
        }

        /**
         * One line for the settings screen: what the last request did, how long ago, or why nothing
         * is drawn at all. A failed request used to leave no trace anywhere, which made a wrong city
         * name look exactly like a broken feature.
         */
        static String statusText(Context context) {
            String saved = Prefs.text(context, Prefs.KEY_CITY).trim();
            if (saved.isEmpty()) {
                return context.getString(R.string.weather_state_nocity);
            }
            if (status == null || status.isEmpty() || statusAt <= 0L) {
                return context.getString(R.string.weather_state_wait);
            }
            long ago = Math.max(0L, (System.currentTimeMillis() - statusAt) / 1000L);
            return context.getString(R.string.weather_state_ago, status, (int) Math.min(ago, 9999L));
        }

        /**
         * One request on the calling thread, for the "test now" button: the answer is the raw status
         * line, so a misspelt city and a blocked network finally look different.
         */
        static String probe(Context context, String name) {
            if (name == null || name.trim().isEmpty()) {
                return context.getString(R.string.weather_state_nocity);
            }
            String wanted = name.trim();
            Reading value = null;
            try {
                value = request(context, wanted);
            } catch (Throwable failure) {
                mark(context, context.getString(R.string.weather_err_network,
                        "open-meteo.com", reason(failure)), false);
            }
            if (value != null) {
                city = wanted;
                cache = value;
                at = System.currentTimeMillis();
            }
            return status == null || status.isEmpty()
                    ? context.getString(R.string.weather_err_empty) : status;
        }

        private static String read(String address) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            try {
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code / 100 != 2) {
                    // Handing an empty body to JSONObject only says "no value", which hides the
                    // reason (a blocked host, a rate limit); a status code names it.
                    throw new IOException("HTTP " + code);
                }
                InputStream in = connection.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) > 0 && out.size() < MAX_BYTES) {
                    out.write(buffer, 0, read);
                }
                in.close();
                return out.toString("UTF-8");
            } finally {
                connection.disconnect();
            }
        }

        private static String encode(String value) {
            try {
                return URLEncoder.encode(value, "UTF-8");
            } catch (UnsupportedEncodingException ignored) {
                return value;
            }
        }
    }

    /**
     * True when the launcher is the app in front.
     *
     * <p>Usage events only say what happened, so an empty window must not be read as "another app is
     * in front": that is what used to hide the pill a few seconds after it appeared. The last answer
     * is kept and only a newer event can change it; before anything is known the answer is true,
     * because an overlay pill over the launcher is the normal state.
     *
     * <p>Without {@code PACKAGE_USAGE_STATS} the answer stays at that default, which the service
     * turns into "no pill at all" before asking here.
     */
    static boolean homeInFront(Context context) {
        try {
            UsageStatsManager manager =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (manager == null) {
                return homeState == null || homeState;
            }
            Set<String> homes = homePackages(context);
            if (homes.isEmpty()) {
                return homeState == null || homeState;
            }
            long now = System.currentTimeMillis();
            long since = homeCheckedAt > 0L ? Math.max(homeCheckedAt - 2000L, now - 60000L)
                    : now - 60000L;
            homeCheckedAt = now;
            UsageEvents events = manager.queryEvents(since, now);
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.getNextEvent(event)) {
                    if (event.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) {
                        continue;
                    }
                    String pkg = event.getPackageName();
                    if (pkg == null || pkg.equals(context.getPackageName())) {
                        continue;
                    }
                    homeState = homes.contains(pkg);
                }
            }
            return homeState == null || homeState;
        } catch (Throwable ignored) {
            return homeState == null || homeState;
        }
    }

    /** Last answer about the launcher, kept across polls so a quiet window changes nothing. */
    private static Boolean homeState;
    private static long homeCheckedAt;

    private static Set<String> homePackages(Context context) {
        Set<String> homes = new HashSet<>();
        try {
            PackageManager manager = context.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo info : manager.queryIntentActivities(home, 0)) {
                if (info.activityInfo != null && info.activityInfo.packageName != null) {
                    homes.add(info.activityInfo.packageName);
                }
            }
        } catch (Throwable ignored) {
            // Fall through with an empty set.
        }
        return homes;
    }
}
