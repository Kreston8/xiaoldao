package com.kreston8.islandcn;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.HorizontalScrollView;
import android.widget.Toast;

import java.util.function.Consumer;

/** Setup screen: the two permissions, the on/off switch, and a preview button. */
public final class MainActivity extends Activity {

    private static final int COLOR_BG = 0xFF0A0A0C;
    private static final int COLOR_CARD = 0xFF15151A;
    private static final int COLOR_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_SECONDARY = 0xFF9A9AA4;
    private static final int COLOR_OK = 0xFF34C759;
    private static final int COLOR_MISSING = 0xFFFF9F0A;
    private static final int COLOR_IDLE = 0xFF7C7C86;
    /** Fill of the round "?" toggles; the one piece of chrome that is not a card. */
    private static final int COLOR_HELP = 0xFF2A2A33;
    /** Fold key for the always-on card, which has a literal title rather than a string id. */
    private static final int FOLD_ALWAYS_ON = 1;

    private TextView overlayState;
    private TextView accessState;
    private TextView smsState;
    private TextView phoneState;
    private TextView usageState;
    private TextView serviceState;
    private Button startStop;
    private TextView btState;
    private TextView btDiagView;
    private Button cityButton;
    private LinearLayout standbyRows;
    private TextView weatherState;
    private TextView cityHint;
    private LinearLayout swatches;
    private SeekBar alphaBar;
    private TextView alphaValue;
    private TextView batteryState;
    /** Fold keys of the small print the user has unfolded, kept for the life of the process. */
    private final java.util.Set<Integer> unfolded = new java.util.HashSet<Integer>();
    /** Every first-level switch, so resume can re-read them and rebuild the open second level. */
    private final java.util.List<Primary> primaries = new java.util.ArrayList<Primary>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(36));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text(R.string.setup_title, 26f, COLOR_PRIMARY, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(titleRow);

        TextView subtitle = text(R.string.setup_subtitle, 13f, COLOR_SECONDARY, false);
        subtitle.setLineSpacing(dp(3), 1f);
        LinearLayout subtitleBox = foldBox(R.string.setup_subtitle, subtitle);
        addFill(root, subtitleBox, 8);
        titleRow.addView(helpButton(R.string.setup_subtitle, subtitleBox), helpParams());

        LinearLayout overlayCard = permCard(R.string.perm_overlay_title, R.string.perm_overlay_desc, 0,
                v -> Perms.openOverlaySettings(this));
        overlayState = permState(overlayCard, R.string.missing);
        addFill(root, overlayCard, 14);

        LinearLayout accessCard = permCard(R.string.perm_access_title, R.string.perm_access_desc, 0,
                v -> Perms.openNotificationAccessSettings(this));
        accessState = permState(accessCard, R.string.missing);
        addFill(root, accessCard, 10);

        LinearLayout smsCard = permCard(R.string.perm_sms_title, R.string.perm_sms_desc, 0,
                v -> requestSms());
        smsState = permState(smsCard, R.string.missing);
        addFill(root, smsCard, 10);

        LinearLayout phoneCard = permCard(R.string.perm_phone_title, R.string.perm_phone_desc, 0,
                v -> requestPhone());
        phoneState = permState(phoneCard, R.string.missing);
        addFill(root, phoneCard, 10);

        LinearLayout usageCard = permCard(R.string.perm_usage_title, R.string.perm_usage_desc, 0,
                v -> Perms.openUsageAccessSettings(this));
        usageState = permState(usageCard, R.string.missing);
        addFill(root, usageCard, 10);

        LinearLayout btCard = permCard(R.string.perm_bt_title, R.string.perm_bt_desc, R.string.bt_hint,
                v -> requestBluetooth());
        btState = permState(btCard, R.string.missing);
        btDiagView = new TextView(this);
        btDiagView.setTextSize(12f);
        btDiagView.setTextColor(0xFF8A8A94);
        btDiagView.setLineSpacing(dp(3), 1f);
        addFill(btCard, btDiagView, 6);
        addFill(root, btCard, 10);

        LinearLayout serviceCard = card();
        LinearLayout serviceRow = new LinearLayout(this);
        serviceRow.setOrientation(LinearLayout.HORIZONTAL);
        serviceRow.setGravity(Gravity.CENTER_VERTICAL);
        serviceRow.addView(text(R.string.state_title, 16f, COLOR_PRIMARY, true),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        serviceState = text(R.string.service_off, 13f, COLOR_IDLE, true);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        stateParams.leftMargin = dp(10);
        serviceRow.addView(serviceState, stateParams);
        startStop = inlineButton(R.string.start, v -> toggleService());
        serviceRow.addView(startStop, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addFill(serviceCard, serviceRow, 0);
        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        previewRow.addView(smallButton(R.string.preview, v -> preview()),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        addFill(serviceCard, previewRow, 10);
        addFill(root, serviceCard, 10);

        LinearLayout keepCard = card();
        TextView keepDesc = text("MIUI 上要让岛一直活着，需要三步：①「自启动」里允许本 App；"
                        + "②省电策略设为「无限制」；③在最近任务里把本 App 锁定。\n"
                        + "本 App 已是前台服务，正常情况退出界面后仍会常驻。",
                13f, COLOR_SECONDARY, false);
        keepDesc.setLineSpacing(dp(3), 1f);
        helpHeader(keepCard, "后台常驻", FOLD_ALWAYS_ON, keepDesc);
        batteryState = text("未忽略电池优化", 13f, COLOR_MISSING, true);
        addFill(keepCard, batteryState, 8);
        LinearLayout keepRow = new LinearLayout(this);
        keepRow.setOrientation(LinearLayout.HORIZONTAL);
        Button batteryButton = button("忽略电池优化", v -> requestIgnoreBattery());
        batteryButton.setTextSize(13f);
        Button appButton = button("应用设置", v -> openAppSettings());
        appButton.setTextSize(13f);
        LinearLayout.LayoutParams batteryParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        batteryParams.rightMargin = dp(8);
        keepRow.addView(batteryButton, batteryParams);
        keepRow.addView(appButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addFill(keepCard, keepRow, 12);
        addFill(root, keepCard, 10);

        LinearLayout featuresCard = card();
        addFill(featuresCard, text(R.string.settings_title, 16f, COLOR_PRIMARY, true), 0);
        addFill(featuresCard, text(R.string.settings_desc, 13f, COLOR_SECONDARY, false), 6);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        addFill(featuresCard, rows, 0);
        primaryRow(rows, R.string.label_notify_wechat, Prefs.KEY_NOTIFY_WECHAT, true, null);
        primaryRow(rows, R.string.label_qq, Prefs.KEY_NOTIFY_QQ, true, null);
        primaryRow(rows, R.string.label_notify_pickup, Prefs.KEY_NOTIFY_PICKUP, true, null);
        primaryRow(rows, R.string.label_delivery, Prefs.KEY_DELIVERY, true, this::deliveryRows);
        primaryRow(rows, R.string.label_todo, Prefs.KEY_TODO, true, null);
        primaryRow(rows, R.string.label_call, Prefs.KEY_CALL, true, null);
        primaryRow(rows, R.string.label_alarm, Prefs.KEY_ALARM, true, null);
        primaryRow(rows, R.string.label_music, Prefs.KEY_MUSIC, true,
                box -> subRow(box, getString(R.string.label_lyrics), Prefs.KEY_LYRICS));
        primaryRow(rows, R.string.label_bt, Prefs.KEY_BT, true, this::bluetoothRows);
        addFill(featuresCard, text(R.string.music_hint, 12f, 0xFF8A8A94, false), 12);
        addFill(featuresCard, text(R.string.lyrics_hint, 12f, 0xFF8A8A94, false), 6);
        addFill(root, featuresCard, 10);

        LinearLayout lookCard = card();
        addFill(lookCard, text(R.string.look_title, 16f, COLOR_PRIMARY, true), 0);
        addFill(lookCard, text(R.string.look_desc, 13f, COLOR_SECONDARY, false), 6);
        addFill(lookCard, text(R.string.look_color, 13f, COLOR_PRIMARY, true), 14);
        swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView swatchScroll = new HorizontalScrollView(this);
        swatchScroll.setHorizontalScrollBarEnabled(false);
        swatchScroll.setClipToPadding(false);
        swatchScroll.setFillViewport(false);
        swatchScroll.addView(swatches);
        addFill(lookCard, swatchScroll, 8);
        renderSwatches();
        addFill(lookCard, text(R.string.look_alpha, 13f, COLOR_PRIMARY, true), 16);
        alphaBar = new SeekBar(this);
        alphaBar.setMax(100);
        alphaBar.setProgress(Look.percent(this));
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    applyLook(value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        addFill(lookCard, alphaBar, 4);
        alphaValue = text(R.string.look_alpha, 12f, 0xFF8A8A94, false);
        alphaValue.setText(getString(R.string.look_alpha_fmt, Look.percent(this)));
        addFill(lookCard, alphaValue, 2);
        addFill(root, lookCard, 10);

        LinearLayout standbyCard = card();
        addFill(standbyCard, text(R.string.label_standby, 16f, COLOR_PRIMARY, true), 0);
        addFill(standbyCard, text(R.string.standby_hint, 13f, COLOR_SECONDARY, false), 6);
        switchRow(standbyCard, R.string.label_standby_enable, Prefs.KEY_STANDBY, false);
        addFill(standbyCard, text(R.string.standby_order_title, 13f, COLOR_PRIMARY, true), 14);
        standbyRows = new LinearLayout(this);
        standbyRows.setOrientation(LinearLayout.VERTICAL);
        addFill(standbyCard, standbyRows, 0);
        renderStandbyRows();
        cityButton = button(R.string.weather_city_set, v -> askCity());
        addTop(standbyCard, cityButton, 12);
        cityHint = text(R.string.weather_city_hint, 12f, 0xFF8A8A94, false);
        cityHint.setLineSpacing(dp(3), 1f);
        addFill(standbyCard, cityHint, 6);
        weatherState = text(R.string.weather_state_wait, 12f, 0xFF8A8A94, false);
        weatherState.setLineSpacing(dp(3), 1f);
        addFill(standbyCard, weatherState, 8);
        addTop(standbyCard, button(R.string.weather_test, v -> testWeather()), 10);
        updateCity();
        addFill(standbyCard, text(R.string.weather_city_desc, 12f, 0xFF8A8A94, false), 10);
        addFill(standbyCard, text(R.string.standby_need_usage, 12f, 0xFF8A8A94, false), 6);
        addFill(root, standbyCard, 10);

        TextView miui = text(R.string.miui_hint, 12f, 0xFF8A8A94, false);
        miui.setLineSpacing(dp(3), 1f);
        addFill(root, miui, 22);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        syncPrimaries();
    }

    private void refreshState() {
        boolean overlay = Perms.canOverlay(this);
        overlayState.setText(overlay ? R.string.granted : R.string.missing);
        overlayState.setTextColor(overlay ? COLOR_OK : COLOR_MISSING);

        boolean access = Perms.hasNotificationAccess(this);
        accessState.setText(access ? R.string.granted : R.string.missing);
        accessState.setTextColor(access ? COLOR_OK : COLOR_MISSING);

        boolean sms = canReceiveSms();
        smsState.setText(sms ? R.string.granted : R.string.missing);
        smsState.setTextColor(sms ? COLOR_OK : COLOR_MISSING);

        boolean phone = canWatchCalls();
        phoneState.setText(phone ? R.string.granted : R.string.missing);
        phoneState.setTextColor(phone ? COLOR_OK : COLOR_MISSING);

        boolean usage = Perms.hasUsageAccess(this);
        usageState.setText(usage ? R.string.granted : R.string.missing);
        usageState.setTextColor(usage ? COLOR_OK : COLOR_MISSING);

        boolean bluetooth = canReadBluetooth();
        btState.setText(bluetooth ? R.string.granted : R.string.missing);
        btState.setTextColor(bluetooth ? COLOR_OK : COLOR_MISSING);
        String btDiag = BtWatcher.probe(this);
        if (btDiagView != null) {
            btDiagView.setText("诊断：" + btDiag);
        }

        boolean running = IslandService.isRunning();
        serviceState.setText(running ? R.string.service_on : R.string.service_off);
        serviceState.setTextColor(running ? COLOR_OK : COLOR_IDLE);
        startStop.setText(running ? R.string.stop_island : R.string.start);
        if (batteryState != null) {
            boolean ignoring = ignoresBatteryOptimizations();
            batteryState.setText(ignoring ? "已忽略电池优化" : "未忽略电池优化");
            batteryState.setTextColor(ignoring ? COLOR_OK : COLOR_MISSING);
        }
        refreshWeatherState();
    }

    private void toggleService() {
        if (IslandService.isRunning()) {
            stopService(new Intent(this, IslandService.class));
            startStop.postDelayed(this::refreshState, 300);
            return;
        }
        if (!Perms.canOverlay(this)) {
            toast(R.string.need_overlay);
            Perms.openOverlaySettings(this);
            return;
        }
        startForegroundService(new Intent(this, IslandService.class));
        startStop.postDelayed(this::refreshState, 700);
    }

    private void preview() {
        if (!IslandService.isRunning()) {
            toast(R.string.preview_hint);
            return;
        }
        IslandBridge.post(IslandEvent.preview(this));
    }

    private boolean canReceiveSms() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestSms() {
        if (canReceiveSms()) {
            toast(R.string.granted);
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, 2);
    }

    private boolean canWatchCalls() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPhone() {
        if (canWatchCalls()) {
            toast(R.string.granted);
            return;
        }
        requestPermissions(new String[]{
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG}, 3);
    }

    private void restartService() {
        stopService(new Intent(this, IslandService.class));
        if (!Perms.canOverlay(this)) {
            return;
        }
        // The stop is asynchronous; starting right away can land on the dying instance.
        startStop.postDelayed(() -> {
            try {
                startForegroundService(new Intent(this, IslandService.class));
            } catch (Throwable ignored) {
                // The screen went away while waiting; the next launch starts it again.
            }
        }, 400);
        startStop.postDelayed(this::refreshState, 1200);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshState();
        if (requestCode == 3 && IslandService.isRunning()) {
            // The call watcher only registers when the service starts, so pick up the new grant.
            restartService();
        }
        if (requestCode == 4 && IslandService.isRunning()) {
            // The battery receiver registers with the service, so the new grant needs a restart.
            restartService();
        }
    }

    /** One settings row: label on the left, toggle on the right. */
    private void switchRow(LinearLayout parent, int labelRes, String key) {
        switchRow(parent, labelRes, key, true);
    }

    /**
     * @param defaultOn false for the two additions that change the shape of the island, so an
     *                  existing install keeps the layout it already knows until asked otherwise.
     */
    private void switchRow(LinearLayout parent, int labelRes, String key, boolean defaultOn) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(labelRes, 14f, COLOR_PRIMARY, false), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(this);
        toggle.setChecked(defaultOn ? Prefs.get(this, key) : Prefs.opt(this, key));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            Prefs.set(this, key, checked);
            if (IslandService.isRunning() && !isLiveKey(key)) {
                // These watchers only register at start-up, so they need to be re-read.
                restartService();
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addFill(parent, row, 10);
    }

    /** The standby switches are read on every timer tick, so toggling one needs no restart. */
    private boolean isStandbyKey(String key) {
        return Prefs.KEY_STANDBY.equals(key)
                || Prefs.KEY_STANDBY_WEATHER.equals(key)
                || Prefs.KEY_STANDBY_AQI.equals(key)
                || Prefs.KEY_STANDBY_PUPPY.equals(key)
                || Prefs.KEY_STANDBY_QUOTE.equals(key)
                || Prefs.KEY_STANDBY_BT.equals(key);
    }

    /**
     * Switches read while the service runs, so flipping one needs no restart: the source filters are
     * consulted on every notification, the platform filter on every delivery card, and the battery
     * chips are rebuilt each time the pill is drawn.
     */
    private boolean isLiveKey(String key) {
        return isStandbyKey(key)
                || Prefs.KEY_NOTIFY_WECHAT.equals(key)
                || Prefs.KEY_NOTIFY_QQ.equals(key)
                || Prefs.KEY_NOTIFY_PICKUP.equals(key)
                || Prefs.KEY_DELIVERY.equals(key)
                || Prefs.KEY_DELIVERY_MEITUAN.equals(key)
                || Prefs.KEY_DELIVERY_SHANGOU.equals(key)
                || Prefs.KEY_DELIVERY_JD.equals(key)
                || Prefs.KEY_DELIVERY_QIXIAN.equals(key)
                || Prefs.KEY_BT.equals(key)
                || key.startsWith(Prefs.KEY_BT_DEVICE);
    }

    /** A first-level switch plus the second-level rows it owns. */
    private final class Primary {
        private final String key;
        private final boolean defaultOn;
        private final LinearLayout subs;
        private final Consumer<LinearLayout> fill;

        Primary(String key, boolean defaultOn, LinearLayout subs, Consumer<LinearLayout> fill) {
            this.key = key;
            this.defaultOn = defaultOn;
            this.subs = subs;
            this.fill = fill;
        }

        boolean on() {
            return defaultOn ? Prefs.get(MainActivity.this, key) : Prefs.opt(MainActivity.this, key);
        }

        /** Collapses or rebuilds the second level, so a closed parent never shows stale rows. */
        void sync() {
            boolean on = on();
            subs.removeAllViews();
            subs.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on && fill != null) {
                fill.accept(subs);
            }
        }
    }

    /**
     * One first-level row: the switch that owns a whole feature, then, while it is on, the
     * second-level switches underneath it. Closed parents fold away on their own.
     */
    private Primary primaryRow(LinearLayout parent, int labelRes, String key, boolean defaultOn,
                               Consumer<LinearLayout> fill) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(labelRes, 15f, COLOR_PRIMARY, false), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout subs = new LinearLayout(this);
        subs.setOrientation(LinearLayout.VERTICAL);
        subs.setPadding(dp(6), 0, 0, 0);
        Primary primary = new Primary(key, defaultOn, subs, fill);
        Switch toggle = new Switch(this);
        toggle.setChecked(primary.on());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            Prefs.set(this, key, checked);
            primary.sync();
            if (IslandService.isRunning() && !isLiveKey(key)) {
                // These watchers only register at start-up, so they need to be re-read.
                restartService();
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addFill(parent, row, 12);
        addFill(parent, subs, 0);
        primaries.add(primary);
        primary.sync();
        return primary;
    }

    /** One second-level row: same shape as a first-level one, smaller type, indented. */
    private void subRow(LinearLayout parent, CharSequence label, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(13f);
        name.setTextColor(COLOR_SECONDARY);
        row.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(this);
        toggle.setChecked(Prefs.get(this, key));
        toggle.setOnCheckedChangeListener((button, checked) -> Prefs.set(this, key, checked));
        row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addFill(parent, row, 6);
    }

    /** The four delivery platforms, each with its own switch under the 外卖进度 master. */
    private void deliveryRows(LinearLayout parent) {
        subRow(parent, getString(R.string.label_delivery_meituan), Prefs.KEY_DELIVERY_MEITUAN);
        subRow(parent, getString(R.string.label_delivery_shangou), Prefs.KEY_DELIVERY_SHANGOU);
        subRow(parent, getString(R.string.label_delivery_jd), Prefs.KEY_DELIVERY_JD);
        subRow(parent, getString(R.string.label_delivery_qixian), Prefs.KEY_DELIVERY_QIXIAN);
    }

    /**
     * One switch per connected accessory. The list is read live rather than remembered, because
     * pairings change while the screen is open and a cached list would offer devices in a drawer.
     */
    private void bluetoothRows(LinearLayout parent) {
        java.util.List<String[]> devices;
        try {
            devices = BtWatcher.connected(this);
        } catch (Throwable ignored) {
            devices = new java.util.ArrayList<String[]>();
        }
        if (devices.isEmpty()) {
            TextView none = text(R.string.bt_no_device, 12f, 0xFF8A8A94, false);
            none.setLineSpacing(dp(3), 1f);
            addFill(parent, none, 8);
            return;
        }
        for (String[] device : devices) {
            String address = device[0];
            String name = device.length > 1 && device[1] != null && !device[1].isEmpty()
                    ? device[1] : address;
            subRow(parent, name, Prefs.KEY_BT_DEVICE + address);
        }
    }

    /** Re-reads every switch and rebuilds whatever second level is open (called on resume). */
    private void syncPrimaries() {
        for (Primary primary : primaries) {
            primary.sync();
        }
    }

    /** One row per standby item: its own switch, then the two arrows, in the stored order. */
    private void renderStandbyRows() {
        if (standbyRows == null) {
            return;
        }
        standbyRows.removeAllViews();
        String[] order = Prefs.order(this);
        for (int i = 0; i < order.length; i++) {
            final int index = i;
            String item = order[i];
            String key = itemKey(item);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Switch toggle = new Switch(this);
            toggle.setChecked(Prefs.get(this, key));
            toggle.setOnCheckedChangeListener((button, checked) -> Prefs.set(this, key, checked));
            row.addView(toggle, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView label = text(labelOf(item), 13f, COLOR_PRIMARY, false);
            label.setPadding(dp(8), 0, 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(smallButton(R.string.standby_move_up, v -> move(index, -1)));
            row.addView(smallButton(R.string.standby_move_down, v -> move(index, 1)));
            addFill(standbyRows, row, 8);
        }
    }

    /** The preference behind one standby item, so its switch and the pill always agree. */
    private String itemKey(String item) {
        if ("weather".equals(item)) {
            return Prefs.KEY_STANDBY_WEATHER;
        }
        if ("puppy".equals(item)) {
            return Prefs.KEY_STANDBY_PUPPY;
        }
        if ("aqi".equals(item)) {
            return Prefs.KEY_STANDBY_AQI;
        }
        if ("bt".equals(item)) {
            return Prefs.KEY_STANDBY_BT;
        }
        return Prefs.KEY_STANDBY_QUOTE;
    }

    /** Swaps one item with its neighbour and stores the result as a comma list. */
    private void move(int index, int delta) {
        String[] order = Prefs.order(this);
        int target = index + delta;
        if (target < 0 || target >= order.length) {
            return;
        }
        String swap = order[index];
        order[index] = order[target];
        order[target] = swap;
        Prefs.setText(this, Prefs.KEY_STANDBY_ORDER, String.join(",", order));
        renderStandbyRows();
    }

    private int labelOf(String item) {
        if ("weather".equals(item)) {
            return R.string.label_standby_weather;
        }
        if ("puppy".equals(item)) {
            return R.string.label_standby_puppy;
        }
        if ("aqi".equals(item)) {
            return R.string.label_standby_aqi;
        }
        if ("bt".equals(item)) {
            return R.string.label_standby_bt;
        }
        return R.string.label_standby_quote;
    }

    /** City for the temperature item; typing it is what turns that one request on. */
    private void askCity() {
        EditText input = new EditText(this);
        input.setHint(R.string.weather_city_hint);
        input.setText(Prefs.text(this, Prefs.KEY_CITY));
        new AlertDialog.Builder(this)
                .setTitle(R.string.weather_city_title)
                .setMessage(R.string.weather_city_desc)
                .setView(input)
                .setPositiveButton(R.string.weather_city_set, (dialog, which) -> {
                    Prefs.setText(this, Prefs.KEY_CITY, input.getText().toString().trim());
                    updateCity();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateCity() {
        String city = Prefs.text(this, Prefs.KEY_CITY).trim();
        cityButton.setText(city.isEmpty() ? getString(R.string.weather_city_set) : city);
        refreshWeatherState();
    }

    /**
     * What the standby pill's last weather request did, taken straight from the view that made it.
     *
     * <p>Before this the only sign of a failure was a row that never appeared, which is exactly the
     * "weather does nothing" report this state is here to answer.
     */
    private void refreshWeatherState() {
        if (weatherState == null) {
            return;
        }
        weatherState.setText(StandbyView.Weather.statusText(this));
    }

    /**
     * Runs one weather request on the spot and shows the raw answer, so a misspelt city, a blocked
     * network and an empty response can be told apart instead of guessed at.
     */
    private void testWeather() {
        String city = Prefs.text(this, Prefs.KEY_CITY).trim();
        weatherState.setText(R.string.weather_testing);
        new Thread(() -> {
            String result = StandbyView.Weather.probe(this, city);
            runOnUiThread(() -> weatherState.setText(result));
        }, "weather-probe").start();
    }

    private boolean canReadBluetooth() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetooth() {
        if (canReadBluetooth()) {
            toast(R.string.granted);
            return;
        }
        requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN}, 4);
    }

    /**
     * One round swatch per preset, the chosen one ringed in white.
     *
     * <p>Every preset is dark because every line on the island is white text; the ring is the only
     * state, so the row stays a single line.
     */
    private void renderSwatches() {
        if (swatches == null) {
            return;
        }
        swatches.removeAllViews();
        int chosen = Look.color(this) & 0xFFFFFF;
        for (int i = 0; i < Look.presets(); i++) {
            final int color = Look.preset(i);
            boolean on = (color & 0xFFFFFF) == chosen;
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(color);
            dot.setStroke(dp(on ? 2.5f : 1f), on ? 0xFFFFFFFF : 0xFF3C3C46);
            View swatch = new View(this);
            swatch.setBackground(dot);
            swatch.setContentDescription(getString(R.string.look_color));
            swatch.setOnClickListener(v -> {
                Look.setColor(MainActivity.this, color);
                renderSwatches();
                Look.ping(MainActivity.this);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
            params.rightMargin = dp(10);
            swatches.addView(swatch, params);
        }
    }

    /** Stores the new opacity and repaints the two overlays straight away. */
    private void applyLook(int percent) {
        Look.setPercent(this, percent);
        if (alphaValue != null) {
            alphaValue.setText(getString(R.string.look_alpha_fmt, percent));
        }
        Look.ping(this);
    }

    private Button smallButton(int resId, View.OnClickListener listener) {
        Button button = button(resId, listener);
        button.setTextSize(11f);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        return button;
    }

    /** A slim button that fits on a card's title line: 12sp, tight padding, no minimum size. */
    private Button inlineButton(int resId, View.OnClickListener listener) {
        Button button = button(resId, listener);
        button.setTextSize(12f);
        button.setPadding(dp(12), dp(5), dp(12), dp(5));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setIncludeFontPadding(false);
        return button;
    }

    /**
     * One permission card in two lines: "标题" then its "?" right beside it, with 去授权 pinned to
     * the right edge of the same row, then the status line. The small print still folds away.
     *
     * @param extraRes a second paragraph to put in the fold box, or 0 for none
     */
    private LinearLayout permCard(int titleRes, int descRes, int extraRes, View.OnClickListener grant) {
        LinearLayout cardBox = card();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(text(descRes, 13f, COLOR_SECONDARY, false),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        if (extraRes != 0) {
            TextView extra = text(extraRes, 12f, 0xFF8A8A94, false);
            extra.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams extraParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            extraParams.topMargin = dp(6);
            body.addView(extra, extraParams);
        }
        LinearLayout box = foldBox(titleRes, body);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(titleRes, 16f, COLOR_PRIMARY, true),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams toggleParams = helpParams();
        toggleParams.leftMargin = dp(6);
        row.addView(helpButton(titleRes, box), toggleParams);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(inlineButton(R.string.grant, grant),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        addFill(cardBox, row, 0);
        addFill(cardBox, box, 6);
        return cardBox;
    }

    /** The one-line permission status, added straight under a card's title row. */
    private TextView permState(LinearLayout cardBox, int resId) {
        TextView state = text(resId, 13f, COLOR_MISSING, true);
        addFill(cardBox, state, 8);
        return state;
    }

    /** The round grey "?" that unfolds the small print belonging to one title. */
    private View helpButton(final int key, final View body) {
        TextView toggle = new TextView(this);
        toggle.setText("?");
        toggle.setTextSize(12f);
        toggle.setTextColor(COLOR_PRIMARY);
        toggle.setGravity(Gravity.CENTER);
        toggle.setBackground(circle(COLOR_HELP));
        toggle.setContentDescription("展开说明");
        toggle.setOnClickListener(v -> toggleFold(key, body));
        return toggle;
    }

    private LinearLayout.LayoutParams helpParams() {
        return new LinearLayout.LayoutParams(dp(26), dp(26));
    }

    /**
     * A card's title line with its "?" at the right edge, plus the small print it folds away.
     *
     * <p>Only the description goes into the foldable box: the status line under it stays outside,
     * so a granted or missing mark never hides behind the toggle.
     */
    private LinearLayout helpHeader(LinearLayout cardView, int titleRes, View body) {
        return helpHeader(cardView, getString(titleRes), titleRes, body);
    }

    private LinearLayout helpHeader(LinearLayout cardView, CharSequence title, int key, View body) {
        LinearLayout box = foldBox(key, body);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 16f, COLOR_PRIMARY, true), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(helpButton(key, box), helpParams());
        addFill(cardView, row, 0);
        addFill(cardView, box, 6);
        return box;
    }

    /** The container that folds one block of small print; its open state is remembered by key. */
    private LinearLayout foldBox(int key, View body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.setVisibility(unfolded.contains(key) ? View.VISIBLE : View.GONE);
        return box;
    }

    /** Remembers one block's open state, so a rebuilt page shows the same small print. */
    private void toggleFold(int key, View box) {
        if (unfolded.contains(key)) {
            unfolded.remove(key);
            animateFold(box, false);
        } else {
            unfolded.add(key);
            animateFold(box, true);
        }
    }

    /** Opens or closes one block by animating its height over 170ms, so the page does not jump. */
    private void animateFold(final View box, final boolean show) {
        final ViewGroup.LayoutParams params = box.getLayoutParams();
        int width = box.getParent() instanceof View ? ((View) box.getParent()).getWidth() : 0;
        if (params == null || width <= 0) {
            // Never laid out (or already detached): all this box can do is appear and disappear.
            box.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        final int from = show ? 0 : box.getHeight();
        int target = 0;
        if (show) {
            // Measured at the parent's width, because a GONE child has no width of its own yet.
            box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            target = box.getMeasuredHeight();
            params.height = 0;
        }
        box.setVisibility(View.VISIBLE);
        box.requestLayout();
        ValueAnimator animator = ValueAnimator.ofInt(from, target);
        animator.setDuration(170);
        animator.addUpdateListener(animation -> {
            params.height = ((Integer) animation.getAnimatedValue()).intValue();
            box.requestLayout();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                box.setVisibility(show ? View.VISIBLE : View.GONE);
                box.requestLayout();
            }
        });
        animator.start();
    }

    /** Whether the system exempts this app from battery optimisation; false before API 23. */
    private boolean ignoresBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The battery exemption page, falling back to the app details page when a ROM has none. */
    private void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
            Toast.makeText(this, "打不开电池优化设置页，已打开应用设置页", Toast.LENGTH_LONG).show();
            openAppSettings();
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
            Toast.makeText(this, "打不开应用设置页", Toast.LENGTH_LONG).show();
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(rounded(COLOR_CARD, 18f));
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        return layout;
    }

    private Button button(int resId, View.OnClickListener listener) {
        return button(getString(resId), listener);
    }

    private Button button(CharSequence label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(COLOR_PRIMARY);
        button.setBackground(rounded(0xFF23232B, 12f));
        button.setPadding(dp(18), dp(8), dp(18), dp(8));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(int resId, float size, int color, boolean bold) {
        return text(getString(resId), size, color, bold);
    }

    private TextView text(CharSequence label, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    /** The fill behind the round "?" toggles. */
    private GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private void addFill(LinearLayout parent, View child, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        parent.addView(child, params);
    }

    private void addTop(LinearLayout parent, View child, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        parent.addView(child, params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
