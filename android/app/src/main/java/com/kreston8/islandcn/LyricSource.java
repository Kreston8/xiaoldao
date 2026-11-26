package com.kreston8.islandcn;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The only part of this app that touches the network: timed lyrics from 网易云音乐.
 *
 * <p>The player itself never publishes lyrics, so the lines and their timestamps have to be looked
 * up by title and artist. Everything is bounded: one request at a time on a daemon thread, six
 * second timeouts, a 512 KB reply cap, a sixteen track memory cache and no disk writes at all. When
 * the request fails the card simply keeps showing the text the player publishes, and the failure is
 * not remembered, so a later attempt for the same track can still fill the lines in.
 */
final class LyricSource {

    private static final String HOST = "https://music.163.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 "
                    + "Mobile Safari/537.36";
    private static final int TIMEOUT_MS = 6000;
    private static final int MAX_BYTES = 512 * 1024;
    private static final int CACHE_SIZE = 16;

    interface Listener {
        void onLyric(String key, Lyric lyric);
    }

    private static final Map<String, Lyric> CACHE =
            new LinkedHashMap<String, Lyric>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Lyric> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    private static ExecutorService worker;
    private static Handler main;

    private LyricSource() {
    }

    /** Identity of a track for caching: title plus artist, case folded. */
    static String keyOf(String title, String artist) {
        return (title == null ? "" : title.trim()) + "|"
                + (artist == null ? "" : artist.trim().toLowerCase(Locale.ROOT));
    }

    /** Delivers the lyric for this track, from the cache or from the network, on the main thread. */
    static void get(final String title, final String artist, final Listener listener) {
        if (title == null || title.isEmpty()) {
            return;
        }
        final String key = keyOf(title, artist);
        Lyric cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) {
            listener.onLyric(key, cached);
            return;
        }
        ensureWorker();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Lyric lyric;
                try {
                    lyric = fetch(title, artist);
                } catch (Throwable ignored) {
                    lyric = Lyric.empty();
                }
                // A failure or an empty answer is not a fact about the song, so it is not cached:
                // one dropped request would otherwise leave this track lyric-less for the whole
                // life of the process, because the next get() hits the cache and returns nothing.
                if (cacheable(lyric)) {
                    synchronized (CACHE) {
                        CACHE.put(key, lyric);
                    }
                }
                final Lyric result = lyric;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onLyric(key, result);
                    }
                });
            }
        });
    }

    /** True for a real lyric, which is the only kind of answer worth remembering. */
    private static boolean cacheable(Lyric lyric) {
        return lyric != null && !lyric.isEmpty();
    }

    /** Releases the worker thread when nothing is playing; the cache stays. */
    static void idle() {
        ExecutorService current = worker;
        worker = null;
        if (current != null) {
            current.shutdown();
        }
    }

    private static void ensureWorker() {
        if (worker != null) {
            return;
        }
        synchronized (LyricSource.class) {
            if (worker == null) {
                main = new Handler(Looper.getMainLooper());
                worker = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "lyric");
                    thread.setDaemon(true);
                    return thread;
                });
            }
        }
    }

    private static Lyric fetch(String title, String artist) throws Exception {
        String query = artist == null || artist.isEmpty() ? title : title + " " + artist;
        JSONObject found = new JSONObject(
                read(HOST + "/api/search/get?s=" + URLEncoder.encode(query, "UTF-8")
                        + "&type=1&limit=1"));
        JSONObject result = found.optJSONObject("result");
        if (result == null) {
            return Lyric.empty();
        }
        JSONArray songs = result.optJSONArray("songs");
        if (songs == null || songs.length() == 0) {
            return Lyric.empty();
        }
        long id = songs.getJSONObject(0).optLong("id", 0L);
        if (id <= 0L) {
            return Lyric.empty();
        }
        JSONObject document = new JSONObject(
                read(HOST + "/api/song/lyric?id=" + id + "&lv=-1&kv=-1&tv=-1"));
        JSONObject lrc = document.optJSONObject("lrc");
        return lrc == null ? Lyric.empty() : Lyric.parse(lrc.optString("lyric", ""));
    }

    private static String read(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", HOST + "/");
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return "";
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BYTES) {
                    break;
                }
            }
            in.close();
            return trim(new String(out.toByteArray(), "UTF-8"));
        } finally {
            connection.disconnect();
        }
    }

    /** Keeps only the outermost JSON object; some replies carry trailing bytes. */
    private static String trim(String body) {
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        return start < 0 || end <= start ? "" : body.substring(start, end + 1);
    }
}
