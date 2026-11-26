package com.kreston8.islandcn;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The in-app updater: one read of the release list, one download, one trip through the system
 * installer.
 *
 * <p>Where the request goes, and nowhere else: {@code api.github.com} for the list of releases of
 * {@code Kreston8/xiaoldao}, then {@code objects.githubusercontent.com} for the APK file itself. The
 * lyric lookup and the weather lookup are the app's only other sockets, and each of those sits
 * behind its own switch; this one sits behind 自动更新 being on, or behind the 检查更新 button.
 *
 * <p>What it deliberately cannot do: install without a tap. A silent update needs either root or
 * device-owner rights, and this app has neither, so the download is automatic but the last step is
 * the system installer, which the person has to confirm. That is a platform rule, not a shortcut
 * taken here.
 *
 * <p>Every step writes one line into {@link Diag}, so a failed check reads as "检查更新失败：…"
 * in the settings screen instead of a button that appears to do nothing.
 */
final class Updater {

    /** The only API endpoint this app ever reads from GitHub. */
    private static final String API =
            "https://api.github.com/repos/Kreston8/xiaoldao/releases?per_page=50";

    /** Release tags look like {@code xiaoldao-v2.21.9}; anything else is ignored. */
    private static final String PREFIX = "xiaoldao-v";

    /** One hour between automatic checks. */
    private static final long AUTO_MS = 60L * 60_000L;

    private static final int CONNECT_MS = 12_000;
    private static final int READ_MS = 30_000;

    /** File name inside the cache directory; {@link UpdateProvider} serves exactly this name. */
    static final String APK = "update.apk";

    /** Authority of the one-file provider that hands the APK to the system installer. */
    static final String AUTHORITY = "com.kreston8.islandcn.update";

    /** Anything smaller than this is an error page, not an APK. */
    private static final long MIN_APK = 10_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** One check at a time, whichever of the two callers asked. */
    private static volatile boolean busy;

    /** Progress and result lines, delivered on the main thread; null for the silent path. */
    interface Listener {
        void status(CharSequence text, boolean ok);
    }

    private Updater() {
    }

    /** The version in the manifest, as built by CI: {@code 2.21.9}. */
    static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (info.versionName != null && info.versionName.length() > 0) {
                return info.versionName;
            }
        } catch (Throwable ignored) {
            // Falls through to the placeholder below.
        }
        return "?";
    }

    /** The version code in the manifest: {@code 1000 + run number}, so it always increases. */
    static long versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** What the settings screen prints: {@code 2.21.9（1009）}. */
    static String label(Context context) {
        return versionName(context) + "（" + versionCode(context) + "）";
    }

    /** Where a downloaded package lives until the installer has read it. */
    static File apk(Context context) {
        return new File(context.getCacheDir(), APK);
    }

    /** The release tag of the package already on disk, or an empty string. */
    static String ready(Context context) {
        String tag = Prefs.text(context, Prefs.KEY_UPDATE_TAG);
        if (tag == null || tag.length() == 0) {
            return "";
        }
        File file = apk(context);
        if (!file.isFile() || file.length() < MIN_APK) {
            return "";
        }
        return tag.startsWith(PREFIX) ? tag.substring(PREFIX.length()) : tag;
    }

    /**
     * Reads the release list once and, when something newer exists, downloads it and opens the
     * installer. {@code auto} only changes the wording of the log lines.
     */
    static void check(final Context context, final Listener listener, final boolean auto) {
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        if (busy) {
            say(listener, "正在检查，请稍等", false);
            return;
        }
        busy = true;
        say(listener, "正在检查更新…", false);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    work(app, listener, auto);
                } finally {
                    busy = false;
                }
            }
        }, "update-check");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The hourly hook, called from the service's four-second poll and cheap enough for it: two
     * preference reads and one subtraction in the common case.
     */
    static void autoTick(Context context) {
        if (context == null || busy) {
            return;
        }
        if (!Prefs.opt(context, Prefs.KEY_AUTO_UPDATE)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = Prefs.number(context, Prefs.KEY_UPDATE_AT);
        // A clock that moved backwards must not silence the check for a whole day.
        if (last > 0L && now >= last && now - last < AUTO_MS) {
            return;
        }
        check(context, null, true);
    }

    /**
     * Hands the downloaded package to the system installer.
     *
     * <p>Three things can stop this, and each one leaves a line behind: no downloaded file, the
     * 「安装未知应用」 permission never granted (the settings page is opened instead), and an
     * activity start refused because the app is in the background.
     */
    static void install(Context context) {
        // From a visible screen the installer is started by that Activity, which no ROM blocks.
        // From the background it needs NEW_TASK, and the 「悬浮窗」 grant is what lets it through.
        final boolean fromScreen = context instanceof android.app.Activity;
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        File file = apk(app);
        if (!file.isFile() || file.length() < MIN_APK) {
            Diag.note(app, "安装：还没有下载好的安装包");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !app.getPackageManager().canRequestPackageInstalls()) {
            Diag.note(app, "安装：没有「安装未知应用」权限，已打开设置");
            openInstallPermission(app);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse("content://" + AUTHORITY + "/" + APK),
                "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!fromScreen) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            (fromScreen ? context : app).startActivity(intent);
            Diag.note(app, "已打开系统安装器");
        } catch (Throwable error) {
            Diag.note(app, "打不开安装器：" + error.getClass().getSimpleName());
        }
    }

    /** Sends the person to the per-app 「安装未知应用」 switch; the installer cannot be used before. */
    static void openInstallPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // Some MIUI builds hide the page; the permission can still be granted from 应用信息.
        }
    }

    private static void work(Context app, Listener listener, boolean auto) {
        try {
            JSONArray list = new JSONArray(get(API));
            int[] best = null;
            String tag = null;
            String[] links = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String candidate = item.optString("tag_name", "");
                if (candidate == null || !candidate.startsWith(PREFIX)) {
                    continue;
                }
                int[] version = parse(candidate.substring(PREFIX.length()));
                if (version == null || (best != null && !newer(version, best))) {
                    continue;
                }
                String[] link = asset(item);
                if (link == null) {
                    continue;
                }
                best = version;
                tag = candidate;
                links = link;
            }
            if (tag == null || links == null) {
                Diag.note(app, "检查更新：没找到可用的安装包");
                say(listener, "没有找到可下载的安装包", false);
                return;
            }
            Prefs.setNumber(app, Prefs.KEY_UPDATE_AT, System.currentTimeMillis());
            String newest = tag.substring(PREFIX.length());
            int[] mine = parse(versionName(app));
            if (mine != null && !newer(best, mine)) {
                Diag.note(app, "检查更新：已是最新 " + versionName(app));
                say(listener, "已是最新版本 " + versionName(app), true);
                return;
            }
            say(listener, "发现 " + newest + "，正在下载…", false);
            File file = apk(app);
            download(app, links, file);
            Prefs.setText(app, Prefs.KEY_UPDATE_TAG, tag);
            Diag.note(app, (auto ? "自动更新" : "检查更新") + "：已下载 " + newest
                    + "（" + (file.length() / 1024L) + " KB）");
            say(listener, "已下载 " + newest + "，正在打开系统安装器…", true);
            install(app);
        } catch (Throwable error) {
            Diag.note(app, "检查更新失败：" + brief(error));
            say(listener, "检查失败：" + brief(error), false);
        }
    }

    /**
     * The URLs to try for the first {@code .apk} asset of one release, best first, or null.
     *
     * <p>Two of them, because they fail differently. The {@code /releases/assets/{id}} form is
     * answered by {@code api.github.com} itself, the host the app already used a moment ago to
     * read the list; {@code browser_download_url} starts at {@code github.com}, which some
     * networks reset. Whichever answers first with a real zip wins.
     */
    private static String[] asset(JSONObject item) {
        JSONArray assets = item.optJSONArray("assets");
        for (int a = 0; assets != null && a < assets.length(); a++) {
            JSONObject entry = assets.optJSONObject(a);
            if (entry == null) {
                continue;
            }
            String name = entry.optString("name", "");
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".apk")) {
                continue;
            }
            String browser = entry.optString("browser_download_url", "");
            long id = entry.optLong("id", 0L);
            if (id > 0L) {
                String api = "https://api.github.com/repos/Kreston8/xiaoldao/releases/assets/" + id;
                return browser.length() > 0 ? new String[]{api, browser} : new String[]{api};
            }
            if (browser.length() > 0) {
                return new String[]{browser};
            }
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            InputStream in = connection.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Fetches the package, trying every URL the API offered, three times each.
     *
     * <p>Three because a single reset says nothing about the next second on a mobile network, and
     * every attempt leaves a line in the log naming the host and the error, so a failure that only
     * happens on the phone stops being invisible.
     */
    private static void download(Context app, String[] urls, File to) throws Exception {
        Throwable last = null;
        for (int round = 1; round <= 3; round++) {
            // The order alternates: the two URLs fail for different reasons, so the host that just
            // failed is tried second on the next round instead of being asked again first.
            String[] order = urls;
            if (urls.length > 1 && round % 2 == 0) {
                order = new String[urls.length];
                for (int i = 0; i < urls.length; i++) {
                    order[i] = urls[urls.length - 1 - i];
                }
            }
            for (String url : order) {
                try {
                    fetch(url, to);
                    if (round > 1) {
                        Diag.note(app, "下载：第 " + round + " 轮成功（" + host(url) + "）");
                    }
                    return;
                } catch (Throwable error) {
                    last = error;
                    Diag.note(app, "下载失败（" + host(url) + " 第 " + round + " 轮）："
                            + brief(error));
                }
            }
            // A reset one second ago says nothing about the next second on a mobile network, but
            // retrying in the same instant turns a blip into a failure.
            if (round < 3) {
                try {
                    Thread.sleep(800L * round);
                } catch (InterruptedException ignored) {
                    // Nothing to do here: the loop's own condition still ends it.
                }
            }
        }
        if (last instanceof Exception) {
            throw (Exception) last;
        }
        throw new IllegalStateException(last == null ? "没有可用的下载地址" : String.valueOf(last));
    }

    /**
     * One request, following redirects by hand, then renaming the finished file into place.
     *
     * <p>By hand, because {@link HttpURLConnection} hides the hop it took: when GitHub answers
     * 302 to {@code release-assets.githubusercontent.com} and the network drops that host, a
     * silent follow makes the app look broken instead of the network. Following it here lets the
     * log name the host that actually failed. The body is only accepted if it starts with
     * {@code PK}, which is what separates a package from an error page that answered 200.
     */
    private static void fetch(String from, File to) throws Exception {
        String url = from;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection connection = open(url);
            try {
                if (url.contains("/releases/assets/")) {
                    // The asset endpoint answers with the 302 that leads to the file only when the
                    // client asks for bytes. Without this it answers 200 with the asset's JSON,
                    // which then failed the zip check and burned three attempts before the fallback
                    // URL was even tried. That is the slow, sometimes failing download, fixed.
                    connection.setRequestProperty("Accept", "application/octet-stream");
                }
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null || next.length() == 0) {
                        throw new IllegalStateException("HTTP " + code + " 没有跳转地址");
                    }
                    url = next.startsWith("http") ? next : new URL(new URL(url), next).toString();
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + code);
                }
                long declared = declaredLength(connection);
                File part = new File(to.getParentFile(), APK + ".part");
                InputStream in = connection.getInputStream();
                try {
                    FileOutputStream out = new FileOutputStream(part);
                    try {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                        out.flush();
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
                if (declared > 0L && part.length() != declared) {
                    // A body that ends early can still start with PK and be far over ten kilobytes,
                    // and then the installer is the thing that fails. The promised length is the
                    // only way to catch that before the user does.
                    long got = part.length();
                    part.delete();
                    throw new IllegalStateException("只收到 " + got + " / " + declared + " 字节");
                }
                if (part.length() < MIN_APK) {
                    // Read the size before deleting: the old order always logged zero bytes, which
                    // is the one number that would have said what went wrong.
                    long got = part.length();
                    part.delete();
                    throw new IllegalStateException("安装包不完整：" + got + " 字节");
                }
                if (!isPackage(part)) {
                    part.delete();
                    throw new IllegalStateException("下载到的不是安装包");
                }
                if (to.exists() && !to.delete()) {
                    throw new IllegalStateException("旧安装包删不掉");
                }
                if (!part.renameTo(to)) {
                    throw new IllegalStateException("安装包存不下来");
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IllegalStateException("跳转次数太多");
    }

    /** The byte count the server promised, or -1 when it did not say. */
    private static long declaredLength(HttpURLConnection connection) {
        try {
            String value = connection.getHeaderField("Content-Length");
            if (value == null || value.length() == 0) {
                return -1L;
            }
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** An APK is a zip: the first two bytes have to be {@code PK}, whatever the headers claim. */
    private static boolean isPackage(File file) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            return in.read() == 'P' && in.read() == 'K';
        } catch (Throwable error) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // A failed close on a handle we are done with changes nothing.
                }
            }
        }
    }

    /** Just the host of a URL, for the log. */
    private static String host(String url) {
        if (url == null) {
            return "?";
        }
        try {
            String value = new URL(url).getHost();
            return value == null ? url : value;
        } catch (Throwable error) {
            return url;
        }
    }

    /** A class name plus the head of its message, which is the part that names the cause. */
    private static String brief(Throwable error) {
        String name = error.getClass().getSimpleName();
        String text = error.getMessage();
        if (text == null || text.length() == 0) {
            return name;
        }
        if (text.length() > 90) {
            text = text.substring(0, 90);
        }
        return name + " " + text;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_MS);
        connection.setReadTimeout(READ_MS);
        // Redirects are followed by hand in fetch(), which is also what names the failing host.
        connection.setInstanceFollowRedirects(false);
        // Never let a proxy hand back gzip: the body is written to disk as bytes, not decoded.
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "xiaoldao-updater");
        return connection;
    }

    /** {@code 2.21.9} becomes {@code {2, 21, 9}}; anything unparsable becomes null. */
    private static int[] parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        String[] parts = value.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        int[] out = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return out;
    }

    /** Component-wise comparison; a release is only newer when it is newer in the first difference. */
    private static boolean newer(int[] candidate, int[] current) {
        for (int i = 0; i < 3; i++) {
            if (candidate[i] != current[i]) {
                return candidate[i] > current[i];
            }
        }
        return false;
    }

    private static void say(final Listener listener, final CharSequence text, final boolean ok) {
        if (listener == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                listener.status(text, ok);
            }
        });
    }
}
