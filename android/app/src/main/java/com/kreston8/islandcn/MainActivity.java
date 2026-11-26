package com.kreston8.islandcn;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.HorizontalScrollView;
import android.widget.Toast;

import java.util.function.Consumer;

/** Setup screen: the two permissions, the on/off switch, and a preview button. */
public final class MainActivity extends Activity {

    /**
     * The palette, filled by {@link #applyTheme()} before a single view is built.
     *
     * <p>These are deliberately not constants: the day/night switch has to be able to hand
     * the whole screen a different set of colours, and everything below reads them by name.
     */
    private int COLOR_BG;
    private int COLOR_CARD;
    private int COLOR_PRIMARY;
    private int COLOR_SECONDARY;
    private int COLOR_OK;
    private int COLOR_MISSING;
    private int COLOR_IDLE;
    /** Fill of the round "?" toggles; the one piece of chrome that is not a card. */
    private int COLOR_HELP;
    /** Small print and hints, the monospaced log body, button fill, swatch hairline. */
    private int COLOR_HINT;
    private int COLOR_MONO;
    private int COLOR_BUTTON;
    private int COLOR_STROKE;
    /** Fixed accents that read the same on both palettes: the sun, the moon, a filled button. */
    private int COLOR_SUN;
    private int COLOR_MOON;
    private int COLOR_ACCENT;
    /** True while the light palette is in force; the switch in the title bar mirrors it. */
    private boolean lightTheme;
    /** Fold key for the always-on card, which has a literal title rather than a string id. */
    private static final int FOLD_ALWAYS_ON = 1;
    /** Fold key for the changelog; small numbers stay clear of string resource ids. */
    private static final int FOLD_CHANGELOG = 2;
    /** The diagnostic log, which used to be printed in full under its title. */
    private static final int FOLD_DIAG = 3;
    /** The gesture manual, folded, so the island page starts with the switch it is about. */
    private static final int FOLD_HELP = 4;
    /** Four pages behind the tab bar: 岛 / 常驻 / 权限 / 设置. */
    private static final int PAGE_COUNT = 4;
    private static final int PAGE_ISLAND = 0;
    private static final int PAGE_STANDBY = 1;
    private static final int PAGE_PERMS = 2;
    private static final int PAGE_SETTINGS = 3;
    private static final String[] PAGE_NAMES = {"岛", "常驻", "权限", "设置"};

    private TextView overlayState;
    private TextView accessState;
    private TextView smsState;
    private TextView phoneState;
    private TextView usageState;
    private TextView serviceState;
    private Button startStop;
    /** The 预览效果 button, restyled in place, because it is one control with two states. */
    private Button previewButton;
    /** True while the sample card is on the real pill, in front of this screen. */
    private boolean previewing;
    /** True when the island was off and the preview is the only thing that started it. */
    private boolean previewStartedService;
    private TextView diagText;
    private Button cityButton;
    private LinearLayout standbyRows;
    private TextView weatherState;
    private TextView cityHint;
    private LinearLayout swatches;
    private SeekBar alphaBar;
    private TextView alphaValue;
    private TextView batteryState;
    /** The line under the version number: checking, up to date, downloading, or ready. */
    private TextView updateState;
    /** Disabled until a package is actually on disk, so it never looks broken when tapped. */
    private Button installButton;
    /** One ScrollView per page, so each keeps its own scroll position while you switch. */
    private View[] pages;
    /** The column inside each page; every card goes into exactly one of these four. */
    private LinearLayout[] columns;
    /** The four tab labels, restyled by {@link #showPage(int)}. */
    private TextView[] tabs;
    /** Which page is showing; survives a recreate() through the instance state. */
    private int page = 0;
    /** Fold keys of the small print the user has unfolded, kept for the life of the process. */
    private final java.util.Set<Integer> unfolded = new java.util.HashSet<Integer>();
    /** Every first-level switch, so resume can re-read them and rebuild the open second level. */
    private final java.util.List<Primary> primaries = new java.util.ArrayList<Primary>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            page = savedInstanceState.getInt("page", 0);
        }

        applyTheme();

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(COLOR_BG);
        setContentView(screen);
        screen.addView(titleBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        // One ScrollView per page, all built once: switching tabs then costs nothing and every
        // page keeps its own scroll position.
        FrameLayout host = new FrameLayout(this);
        screen.addView(host, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        pages = new View[PAGE_COUNT];
        columns = new LinearLayout[PAGE_COUNT];
        for (int i = 0; i < PAGE_COUNT; i++) {
            ScrollView scroller = new ScrollView(this);
            scroller.setFillViewport(true);
            scroller.setBackgroundColor(COLOR_BG);
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(dp(16), dp(14), dp(16), dp(28));
            scroller.addView(column);
            scroller.setVisibility(View.GONE);
            columns[i] = column;
            pages[i] = scroller;
            host.addView(scroller, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        View rule = new View(this);
        rule.setBackgroundColor(COLOR_HELP);
        screen.addView(rule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        screen.addView(tabBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        // The cards below are written in one run of code but belong to four pages, so the
        // current page column is simply re-pointed before each card is added.
        LinearLayout root = columns[PAGE_PERMS];
        pageHeader(root, "权限", "「悬浮窗」是必须的，其余四项都可以不给，只是对应的功能会缺席。"
                + "每次授权完回到这里，状态会自动重新读一遍。");

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

        root = columns[PAGE_ISLAND];
        pageHeader(root, "岛", "屏幕顶部那颗胶囊：通知、验证码、取件码、外卖、待办、音乐都在这里。");
        TextView subtitle = text(R.string.setup_subtitle, 12f, COLOR_HINT, false);
        subtitle.setLineSpacing(dp(3), 1f);
        addFill(root, foldRow("怎么用（单击 / 双击 / 左右滑 / 上滑 / 长按）", FOLD_HELP,
                subtitle), 2);

        LinearLayout serviceCard = card();
        TextView serviceDesc = text(R.string.state_desc, 13f, COLOR_SECONDARY, false);
        serviceDesc.setLineSpacing(dp(3), 1f);
        helpHeader(serviceCard, R.string.state_title, serviceDesc);
        serviceState = text(R.string.service_off, 13f, COLOR_MISSING, true);
        addFill(serviceCard, serviceState, 8);
        LinearLayout serviceRow = new LinearLayout(this);
        serviceRow.setOrientation(LinearLayout.HORIZONTAL);
        startStop = button(R.string.start, v -> toggleService());
        startStop.setTextSize(13f);
        previewButton = button("预览效果", v -> togglePreview());
        previewButton.setTextSize(13f);
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        startParams.rightMargin = dp(8);
        serviceRow.addView(startStop, startParams);
        serviceRow.addView(previewButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addFill(serviceCard, serviceRow, 12);

        addFill(root, serviceCard, 10);

        root = columns[PAGE_SETTINGS];
        pageHeader(root, "设置", "版本与更新、后台常驻、保活诊断。");
        // Version, update buttons, and the changelog fold. The fold starts closed, which is the
        // whole point of it: the page should not open on a wall of release notes.
        LinearLayout updateCard = card();
        LinearLayout versionRow = new LinearLayout(this);
        versionRow.setOrientation(LinearLayout.HORIZONTAL);
        versionRow.setGravity(Gravity.CENTER_VERTICAL);
        versionRow.addView(text("当前版本", 13f, COLOR_HINT, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView versionValue = text(Updater.label(this), 17f, COLOR_PRIMARY, true);
        versionValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        versionRow.addView(versionValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addFill(updateCard, versionRow, 0);
        updateState = text("还没有检查过更新", 13f, COLOR_SECONDARY, false);
        updateState.setLineSpacing(dp(3), 1f);
        addFill(updateCard, updateState, 8);
        LinearLayout updateRow = new LinearLayout(this);
        updateRow.setOrientation(LinearLayout.HORIZONTAL);
        Button checkButton = primaryButton("检查更新", v -> checkUpdate());
        checkButton.setTextSize(13f);
        installButton = button("安装已下载", v -> Updater.install(this));
        installButton.setTextSize(13f);
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        checkParams.rightMargin = dp(8);
        updateRow.addView(checkButton, checkParams);
        updateRow.addView(installButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addFill(updateCard, updateRow, 12);
        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.addView(text("自动更新", 14f, COLOR_PRIMARY, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch autoToggle = new Switch(this);
        autoToggle.setChecked(Prefs.opt(this, Prefs.KEY_AUTO_UPDATE));
        autoToggle.setOnCheckedChangeListener((button, checked) -> {
            Prefs.set(this, Prefs.KEY_AUTO_UPDATE, checked);
            refreshUpdateState();
        });
        autoRow.addView(autoToggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addFill(updateCard, autoRow, 12);
        addFill(updateCard, text("打开后每小时检查一次，发现新版本会自动下载并打开系统安装器。"
                + "系统不允许静默安装，最后一步要在系统弹窗里点一次「安装」；"
                + "第一次还需要允许本应用「安装未知应用」，这一步由系统设置页负责。"
                + "下载走 api.github.com 和 objects.githubusercontent.com 两个地址，"
                + "关掉这个开关就完全不联网检查。", 12f, COLOR_HINT, false), 6);
        TextView changelog = text(Changelog.TEXT, 12f, COLOR_MONO, false);
        changelog.setLineSpacing(dp(4), 1f);
        addFill(updateCard, foldRow("版本更新日志", FOLD_CHANGELOG, changelog), 12);
        addFill(root, updateCard, 10);

        LinearLayout keepCard = card();
        TextView keepDesc = text("MIUI 上要让岛一直活着，需要四步：①「自启动」里允许本 App；"
                        + "②省电策略设为「无限制」；③设置→省电与电池→「锁屏后清理内存」设为「永不」；"
                        + "④在最近任务里把本 App 下拉加锁。\n"
                        + "这四步做完，划掉应用岛也还在；万一进程还是被杀了，任务被划掉的 8 秒后会自动排队把它拉回来，"
                        + "失败就按 30 秒 / 1 分钟 / 3 分钟 / 10 分钟重试。只有手动「强行停止」之后无法自动复活，"
                        + "这是系统限制（任何 App 都一样），回到本页会自动重新拉起。",
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
        Button autoButton = button("打开「自启动」设置", v -> openAutoStart());
        autoButton.setTextSize(13f);
        addFill(keepCard, autoButton, 8);
        addFill(root, keepCard, 10);
        LinearLayout diagCard = card();
        addFill(diagCard, text("保活诊断", 16f, COLOR_PRIMARY, true), 0);
        // Sixty lines of log used to be printed the moment the screen opened; now the card is
        // one line until somebody asks for the log on purpose.
        TextView diagDesc = text("退出应用之后到底发生了什么，这里会写成几行：任务被划掉、闹钟触发、"
                + "启动请求被拒、常驻岛为什么被隐藏。复制之后可以直接贴给我。", 12f, COLOR_HINT, false);
        diagDesc.setLineSpacing(dp(3), 1f);
        diagText = text(Diag.text(this), 12f, COLOR_MONO, false);
        diagText.setTypeface(Typeface.MONOSPACE);
        diagText.setTextIsSelectable(true);
        LinearLayout diagBody = new LinearLayout(this);
        diagBody.setOrientation(LinearLayout.VERTICAL);
        addFill(diagBody, diagDesc, 0);
        addFill(diagBody, diagText, 10);
        LinearLayout diagRow = new LinearLayout(this);
        diagRow.setOrientation(LinearLayout.HORIZONTAL);
        Button copyButton = button("复制诊断", v -> copyDiag());
        copyButton.setTextSize(13f);
        Button clearButton = button("清空记录", v -> {
            Diag.clear(this);
            refreshDiag();
        });
        clearButton.setTextSize(13f);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.rightMargin = dp(8);
        diagRow.addView(copyButton, copyParams);
        diagRow.addView(clearButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addFill(diagBody, diagRow, 12);
        addFill(diagCard, foldRow("查看最近 60 条记录", FOLD_DIAG, diagBody), 8);
        addFill(root, diagCard, 10);

        root = columns[PAGE_ISLAND];
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
        addFill(featuresCard, text(R.string.music_hint, 12f, COLOR_HINT, false), 12);
        addFill(featuresCard, text(R.string.lyrics_hint, 12f, COLOR_HINT, false), 6);
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
        alphaValue = text(R.string.look_alpha, 12f, COLOR_HINT, false);
        alphaValue.setText(getString(R.string.look_alpha_fmt, Look.percent(this)));
        addFill(lookCard, alphaValue, 2);
        addFill(root, lookCard, 10);

        root = columns[PAGE_STANDBY];
        pageHeader(root, "常驻", "常驻岛是桌面上那颗小药丸，只在桌面显示；主岛仍然是屏幕顶部的胶囊。");

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
        cityHint = text(R.string.weather_city_hint, 12f, COLOR_HINT, false);
        cityHint.setLineSpacing(dp(3), 1f);
        addFill(standbyCard, cityHint, 6);
        weatherState = text(R.string.weather_state_wait, 12f, COLOR_HINT, false);
        weatherState.setLineSpacing(dp(3), 1f);
        addFill(standbyCard, weatherState, 8);
        addTop(standbyCard, button(R.string.weather_test, v -> testWeather()), 10);
        updateCity();
        addFill(standbyCard, text(R.string.weather_city_desc, 12f, COLOR_HINT, false), 10);
        addFill(standbyCard, text(R.string.standby_need_usage, 12f, COLOR_HINT, false), 6);
        addFill(root, standbyCard, 10);

        root = columns[PAGE_SETTINGS];
        TextView miui = text(R.string.miui_hint, 12f, COLOR_HINT, false);
        miui.setLineSpacing(dp(3), 1f);
        addFill(root, miui, 22);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        showPage(page);
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        refreshDiag();
        syncPrimaries();
        restoreIfWanted();
        showReadyPackage();
        // The service owns the card, so the button is read from it instead of remembered here:
        // after a theme switch this screen is new while the pill may still be up.
        previewing = IslandService.isPreviewing();
        applyPreviewButton();
    }

    /**
     * Leaving the screen ends the preview.
     *
     * <p>The pill is a system window, so it would otherwise follow the user into whatever app they
     * opened next — a sample verification code sitting over real work, with no button left on
     * screen to switch it off. The preview belongs to this page, so it goes with it. A theme switch
     * is not leaving: that is a recreate, and onResume reads the state back from the service.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (previewing && !isChangingConfigurations()) {
            stopPreview();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("page", page);
    }

    /** Re-reads the diagnostic log; the screen is reused, so this runs on every resume. */
    private void refreshDiag() {
        if (diagText != null) {
            diagText.setText(Diag.text(this));
        }
    }

    /** Copies the log, because retyping it from a screenshot is not a thing anybody should do. */
    private void copyDiag() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("island diag", Diag.text(this)));
        }
        Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
    }

    /**
     * The switch is a setting, not a one-shot button. If the user last asked for the island to run
     * and the process was killed while the app sat in the background, opening this screen starts it
     * again instead of showing "off" and demanding a second tap.
     */
    private void restoreIfWanted() {
        if (!Prefs.running(this) || IslandService.isRunning() || !Perms.canOverlay(this)) {
            return;
        }
        try {
            startForegroundService(new Intent(this, IslandService.class));
            serviceState.setText(R.string.service_restoring);
            serviceState.setTextColor(COLOR_MISSING);
            startStop.postDelayed(this::refreshState, 900);
        } catch (Throwable ignored) {
            // The state line already says the island is not running; the user can tap 启动小灵岛.
        }
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


        boolean running = IslandService.isRunning();
        boolean wanted = Prefs.running(this);
        if (running) {
            serviceState.setText(R.string.service_on);
            serviceState.setTextColor(COLOR_OK);
        } else if (wanted) {
            // The user asked for it and the process is gone: say so instead of pretending it is off.
            serviceState.setText(R.string.service_dead);
            serviceState.setTextColor(COLOR_MISSING);
        } else {
            serviceState.setText(R.string.service_off);
            serviceState.setTextColor(COLOR_IDLE);
        }
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
            // A plain stopService also runs onDestroy, so the "wants to run" flag has to be cleared
            // here; leaving it set is what made the island come back and the switch flip on its own.
            Prefs.setRunning(this, false);
            KeepAlive.stop(this);
            RestoreJob.cancel(this);
            stopService(new Intent(this, IslandService.class));
            startStop.postDelayed(this::refreshState, 300);
            return;
        }
        if (!Perms.canOverlay(this)) {
            toast(R.string.need_overlay);
            Perms.openOverlaySettings(this);
            return;
        }
        Prefs.setRunning(this, true);
        KeepAlive.start(this);
        RestoreJob.schedule(this);
        startForegroundService(new Intent(this, IslandService.class));
        startStop.postDelayed(this::refreshState, 700);
    }

    /**
     * The 「预览效果」 button: puts the real pill on screen, over this app, and takes it away again.
     *
     * <p>The island only draws while the launcher is in front — that is the rule the rest of this
     * file lives by — so a card posted as an ordinary event is dropped exactly when the settings
     * screen is the app in front, which is the one place a preview is of any use. The service
     * therefore takes a command of its own for that one sample card.
     */
    private void togglePreview() {
        if (previewing) {
            stopPreview();
        } else {
            startPreview();
        }
    }

    private void startPreview() {
        if (!Perms.canOverlay(this)) {
            toast(R.string.need_overlay);
            return;
        }
        boolean running = IslandService.isRunning();
        Intent intent = new Intent(this, IslandService.class)
                .setAction(IslandService.ACTION_PREVIEW)
                .putExtra(IslandService.EXTRA_ON, true);
        try {
            if (running) {
                startService(intent);
            } else {
                // A visible activity may start a foreground service, and the island has to come up
                // before a preview has a window to be drawn in.
                startForegroundService(intent);
            }
        } catch (Throwable error) {
            Diag.note(this, "预览启动失败：" + error.getClass().getSimpleName());
            Toast.makeText(this, "预览没能启动，看看「保活诊断」",
                    Toast.LENGTH_LONG).show();
            return;
        }
        previewStartedService = !running;
        previewing = true;
        applyPreviewButton();
        startStop.postDelayed(this::refreshState, 700);
    }

    /** Ends the preview, and the service too when the preview was the only reason it was running. */
    private void stopPreview() {
        previewing = false;
        applyPreviewButton();
        Intent preview = new Intent(this, IslandService.class)
                .setAction(IslandService.ACTION_PREVIEW)
                .putExtra(IslandService.EXTRA_ON, false);
        try {
            startService(preview);
            if (previewStartedService) {
                // The island was off before the button was pressed, so leave it off rather than let
                // a sample card keep a foreground service alive.
                startService(new Intent(this, IslandService.class)
                        .setAction(IslandService.ACTION_STOP));
            }
        } catch (Throwable error) {
            Diag.note(this, "预览收起失败：" + error.getClass().getSimpleName());
        }
        previewStartedService = false;
        startStop.postDelayed(this::refreshState, 700);
    }

    /** One control, two faces: an outline that offers the preview, a filled accent that ends it. */
    private void applyPreviewButton() {
        if (previewButton == null) {
            return;
        }
        previewButton.setText(previewing ? "关闭预览" : "预览效果");
        previewButton.setTextColor(previewing ? 0xFFFFFFFF : COLOR_PRIMARY);
        previewButton.setTypeface(previewing ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        previewButton.setBackground(rounded(previewing ? COLOR_ACCENT : COLOR_BUTTON, 13f));
        previewButton.setContentDescription(previewing
                ? "关闭预览，把屏幕顶部的岛收起来"
                : "在屏幕顶部预览小灵岛的效果");
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

    /** Reads the release list once, downloads the package, and hands it to the system installer. */
    private void checkUpdate() {
        Updater.check(this, (message, ok) -> {
            if (updateState == null) {
                return;
            }
            updateState.setText(message);
            updateState.setTextColor(ok ? COLOR_OK : COLOR_SECONDARY);
            refreshUpdateState();
        }, false);
    }

    /** Keeps the 安装已下载 button honest: greyed out while there is nothing to install. */
    private void refreshUpdateState() {
        if (installButton == null) {
            return;
        }
        boolean ready = Updater.ready(this).length() > 0;
        installButton.setEnabled(ready);
        installButton.setAlpha(ready ? 1f : 0.45f);
    }

    /** Says so on resume when a package was downloaded while the app was in the background. */
    private void showReadyPackage() {
        refreshUpdateState();
        String ready = Updater.ready(this);
        if (ready.length() > 0 && updateState != null) {
            updateState.setText("已下载 " + ready + "，点「安装已下载」交给系统安装器");
            updateState.setTextColor(COLOR_OK);
        }
    }

    /**
     * A fold whose title line is itself the button, and which starts closed.
     *
     * <p>Different from {@link #helpHeader}: no round "?", because this is a heading people are
     * meant to tap, not small print hidden behind a question mark.
     */
    private LinearLayout foldRow(CharSequence title, final int key, View body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 14f, COLOR_PRIMARY, false), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView mark = text(unfolded.contains(key) ? "收起" : "展开", 12f,
                COLOR_SECONDARY, false);
        row.addView(mark, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final LinearLayout inner = foldBox(key, body);
        row.setOnClickListener(v -> {
            toggleFold(key, inner);
            mark.setText(unfolded.contains(key) ? "收起" : "展开");
        });
        addFill(box, row, 0);
        addFill(box, inner, 6);
        return box;
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
                || Prefs.KEY_STANDBY_LUNAR.equals(key)
                || Prefs.KEY_STANDBY_USAGE.equals(key);
    }

    /**
     * Switches read while the service runs, so flipping one needs no restart: the source filters are
     * consulted on every notification and the platform filter on every delivery card.
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
                || Prefs.KEY_DELIVERY_QIXIAN.equals(key);
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
            TextView label = text(labelOf(item), 14f, COLOR_PRIMARY, false);
            label.setPadding(dp(8), 0, 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(smallButton(R.string.standby_move_up, v -> move(index, -1)));
            row.addView(smallButton(R.string.standby_move_down, v -> move(index, 1)));
            addFill(standbyRows, row, 8);
            if (i < order.length - 1) {
                View rule = new View(this);
                rule.setBackgroundColor(COLOR_HELP);
                LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
                ruleParams.topMargin = dp(10);
                standbyRows.addView(rule, ruleParams);
            }
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
        if ("lunar".equals(item)) {
            return Prefs.KEY_STANDBY_LUNAR;
        }
        if ("usage".equals(item)) {
            return Prefs.KEY_STANDBY_USAGE;
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
        if ("lunar".equals(item)) {
            return R.string.label_standby_lunar;
        }
        if ("usage".equals(item)) {
            return R.string.label_standby_usage;
        }
        return R.string.label_standby_quote;
    }

    /** City for the temperature item; typing it is what turns that one request on. */
    private void askCity() {
        EditText input = new EditText(this);
        input.setHint(R.string.weather_city_hint);
        input.setText(Prefs.text(this, Prefs.KEY_CITY));
        new AlertDialog.Builder(this, dialogTheme())
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
            dot.setStroke(dp(on ? 2.5f : 1f), on ? COLOR_PRIMARY : COLOR_STROKE);
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
        GradientDrawable custom = new GradientDrawable();
        custom.setShape(GradientDrawable.OVAL);
        custom.setColors(new int[]{0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6, 0xFFAF52DE, 0xFFFF3B30});
        custom.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        custom.setStroke(dp(1f), COLOR_STROKE);
        View picker = new View(this);
        picker.setBackground(custom);
        picker.setContentDescription(getString(R.string.look_custom));
        picker.setOnClickListener(v -> ColorPicker.show(this, Look.color(this), color -> {
            Look.setColor(MainActivity.this, color);
            renderSwatches();
            Look.ping(MainActivity.this);
        }));
        LinearLayout.LayoutParams customParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        customParams.rightMargin = dp(10);
        swatches.addView(picker, customParams);
    }

    /** Stores the new opacity and repaints the two overlays straight away. */
    private void applyLook(int percent) {
        Look.setPercent(this, percent);
        if (alphaValue != null) {
            alphaValue.setText(getString(R.string.look_alpha_fmt, percent));
        }
        Look.ping(this);
    }

    /** The filled button on a card: reads as the thing to tap, not as one option among two. */
    private Button primaryButton(CharSequence label, View.OnClickListener listener) {
        Button button = button(label, listener);
        button.setTextColor(0xFFFFFFFF);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(COLOR_ACCENT, 13f));
        return button;
    }

    /** The title block every page opens with, so the four pages read as one app. */
    private void pageHeader(LinearLayout root, String title, String note) {
        addFill(root, text(title, 22f, COLOR_PRIMARY, true), 2);
        TextView sub = text(note, 12f, COLOR_HINT, false);
        sub.setLineSpacing(dp(3), 1f);
        addFill(root, sub, 6);
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
            TextView extra = text(extraRes, 12f, COLOR_HINT, false);
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

    /**
     * MIUI's own autostart switch, the one that decides whether a swiped-away app may come back.
     *
     * <p>That page is not part of the public API, so the known class names are tried in order and
     * the app details page is the fallback: a wrong guess has to stay silent, never fatal.
     */
    private void openAutoStart() {
        String[][] pages = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.powercenter.PowerSettings"},
        };
        for (String[] page : pages) {
            try {
                startActivity(new Intent().setComponent(new ComponentName(page[0], page[1])));
                return;
            } catch (Throwable ignored) {
                // Not this ROM, or the activity was renamed: try the next name.
            }
        }
        try {
            startActivity(new Intent("miui.intent.action.OP_AUTO_START")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Throwable ignored) {
            // No MIUI here.
        }
        Toast.makeText(this, "没找到自启动页，已打开应用设置页", Toast.LENGTH_LONG).show();
        openAppSettings();
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    /**
     * The title bar: the app name in the middle, the day/night switch pinned to the right.
     *
     * <p>Both side slots are the same width on purpose, so the name stays centred whatever the
     * switch happens to measure on a given ROM.
     */
    private View titleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(COLOR_BG);
        bar.setPadding(dp(14), 0, dp(14), 0);

        // Empty slot of the same width as the theme pill, so the name is dead centre on every ROM.
        View slot = new View(this);
        bar.addView(slot, new LinearLayout.LayoutParams(dp(72), dp(1)));

        TextView title = text(R.string.app_name, 19f, COLOR_PRIMARY, true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout holder = new LinearLayout(this);
        holder.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        holder.addView(themePill(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(holder, new LinearLayout.LayoutParams(dp(72),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    /**
     * The theme choice as a sun and a moon, not as a switch.
     *
     * <p>A switch says "on" and "off", which on its own never says which side is which; tapping
     * the sun for the light page and the moon for the dark one needs no label. The chosen half
     * sits on a lit chip in its own colour, the other half stays grey.
     */
    private View themePill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(3), dp(3), dp(3), dp(3));
        pill.setBackground(rounded(COLOR_HELP, 15f));

        TextView sun = themeSegment("\u2600", lightTheme, COLOR_SUN, "白天");
        TextView moon = themeSegment("\u263E", !lightTheme, COLOR_MOON, "黑夜");
        sun.setOnClickListener(v -> switchTheme(true));
        moon.setOnClickListener(v -> switchTheme(false));
        pill.addView(sun, new LinearLayout.LayoutParams(dp(29), dp(26)));
        pill.addView(moon, new LinearLayout.LayoutParams(dp(29), dp(26)));
        return pill;
    }

    /** One half of {@link #themePill()}: a lit chip when it is the palette in force. */
    private TextView themeSegment(String glyph, boolean on, int accent, String name) {
        TextView view = new TextView(this);
        view.setText(glyph);
        view.setTextSize(15f);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(on ? accent : COLOR_HINT);
        view.setContentDescription(name);
        if (on) {
            view.setBackground(rounded(lightTheme ? 0xFFFFFFFF : 0xFF34343F, 12f));
        }
        return view;
    }

    /** Stores the choice and rebuilds the screen, because every colour is read from it. */
    private void switchTheme(boolean light) {
        if (light == lightTheme) {
            return;
        }
        Prefs.set(this, Prefs.KEY_LIGHT_THEME, light);
        recreate();
    }

    /** The bottom tab bar: four equal cells, the current one ringed and bold. */
    private View tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(COLOR_CARD);
        tabs = new TextView[PAGE_COUNT];
        for (int i = 0; i < PAGE_COUNT; i++) {
            final int index = i;
            TextView tab = text(PAGE_NAMES[i], 13.5f, COLOR_HINT, false);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(4), dp(10), dp(4), dp(10));
            tab.setOnClickListener(v -> showPage(index));
            bar.addView(tab, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tabs[i] = tab;
        }
        return bar;
    }

    /** Shows one page and repaints the tab bar; nothing is rebuilt, so scroll positions stay. */
    private void showPage(int index) {
        if (pages == null || tabs == null || index < 0 || index >= PAGE_COUNT) {
            return;
        }
        page = index;
        for (int i = 0; i < PAGE_COUNT; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            boolean on = i == index;
            tabs[i].setTextColor(on ? COLOR_PRIMARY : COLOR_HINT);
            tabs[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabs[i].setBackground(on ? rounded(COLOR_HELP, 15f) : null);
        }
        if (index == PAGE_SETTINGS) {
            refreshDiag();
        }
    }

    /** System dialogs (the city prompt) follow the same day/night choice as the page behind. */
    private int dialogTheme() {
        return lightTheme ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert;
    }

    /**
     * Reads the theme switch and fills the palette. Called first thing in {@link #onCreate}, so no
     * view is ever built with the previous set of colours.
     */
    private void applyTheme() {
        lightTheme = Prefs.opt(this, Prefs.KEY_LIGHT_THEME);
        COLOR_BG = lightTheme ? 0xFFF5F5F8 : 0xFF0A0A0C;
        COLOR_CARD = lightTheme ? 0xFFFFFFFF : 0xFF15151A;
        COLOR_PRIMARY = lightTheme ? 0xFF191920 : 0xFFFFFFFF;
        COLOR_SECONDARY = lightTheme ? 0xFF64646E : 0xFF9A9AA4;
        COLOR_OK = lightTheme ? 0xFF1E9E48 : 0xFF34C759;
        COLOR_MISSING = lightTheme ? 0xFFC47000 : 0xFFFF9F0A;
        COLOR_IDLE = lightTheme ? 0xFF8A8A93 : 0xFF7C7C86;
        COLOR_HELP = lightTheme ? 0xFFE8E8EF : 0xFF2A2A33;
        COLOR_HINT = lightTheme ? 0xFF77777F : 0xFF8A8A94;
        COLOR_MONO = lightTheme ? 0xFF33333B : 0xFFB8B8C2;
        COLOR_BUTTON = lightTheme ? 0xFFECECF2 : 0xFF23232B;
        COLOR_STROKE = lightTheme ? 0xFFC8C8D1 : 0xFF3C3C46;
        COLOR_SUN = 0xFFF0A03C;
        COLOR_MOON = 0xFF7C8CE0;
        COLOR_ACCENT = lightTheme ? 0xFF2F6FE0 : 0xFF4C8DFF;
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (lightTheme) {
            // Dark icons on a light bar; the reverse when the night palette is in force.
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(rounded(COLOR_CARD, 20f));
        layout.setPadding(dp(16), dp(15), dp(16), dp(15));
        // One hair of lift: on the light palette it separates white cards from the grey page,
        // on the night palette it costs nothing measurable.
        layout.setElevation(dp(1f));
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
        button.setBackground(rounded(COLOR_BUTTON, 13f));
        button.setPadding(dp(18), dp(9), dp(18), dp(9));
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
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
