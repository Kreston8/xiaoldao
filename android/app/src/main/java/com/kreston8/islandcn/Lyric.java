package com.kreston8.islandcn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A track's lyric lines with their start times.
 *
 * <p>Both arrays are sorted by time and have the same length; {@code times[0]} is the first sang
 * line. Credits such as "作词 : 毛不易" are dropped, because they are not what the card should show.
 */
final class Lyric {

    /** {@code [offset:±ms]}, which shifts every timestamp in the document. */
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d{1,7})\\s*\\]");

    /** {@code [mm:ss.xx]} or {@code [mm:ss]}, possibly several per line. */
    private static final Pattern STAMP =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /** Credit and production lines, which carry a timestamp but are never sung. */
    private static final Pattern CREDIT = Pattern.compile(
            "^\\s*(作词|作曲|编曲|制作人|监制|出品|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|词|曲|OP|SP|"
                    + "Lyrics|Composer|Arranger|Producer)\\s*[:：]",
            Pattern.CASE_INSENSITIVE);

    private static final Lyric EMPTY = new Lyric(new String[0], new long[0]);

    static Lyric empty() {
        return EMPTY;
    }

    final String[] lines;
    final long[] times;

    private Lyric(String[] lines, long[] times) {
        this.lines = lines;
        this.times = times;
    }

    boolean isEmpty() {
        return lines.length == 0;
    }

    /** Index of the line being sung at {@code position}, or -1 before the first line. */
    int indexAt(long position) {
        int low = 0;
        int high = times.length - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (times[mid] <= position) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    /**
     * When line {@code index} stops being sung. Uses the next line's start; the last line gets a
     * nominal duration so its characters still light up one by one.
     */
    long endAt(int index) {
        if (index < 0 || index >= times.length) {
            return 0L;
        }
        if (index + 1 < times.length) {
            return times[index + 1];
        }
        return times[index] + 4000L;
    }

    /** Nominal playing time of the whole lyric, used to spot one taken from another recording. */
    long duration() {
        return lines.length == 0 ? 0L : endAt(lines.length - 1);
    }

    /** Parses an LRC document; returns {@link #empty()} when nothing usable is in it. */
    static Lyric parse(String lrc) {
        if (lrc == null || lrc.isEmpty()) {
            return EMPTY;
        }
        List<long[]> stamps = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = STAMP.matcher(line);
            List<Long> found = new ArrayList<>();
            int end = 0;
            while (matcher.find()) {
                found.add(toMillis(matcher));
                end = matcher.end();
            }
            if (found.isEmpty()) {
                continue;
            }
            String text = line.substring(end).trim();
            if (text.isEmpty() || CREDIT.matcher(text).find()) {
                continue;
            }
            for (Long millis : found) {
                stamps.add(new long[]{millis, stamps.size()});
                texts.add(text);
            }
        }
        if (texts.isEmpty()) {
            return EMPTY;
        }
        // Sort the stamps, then order the texts the same way.
        Collections.sort(stamps, new Comparator<long[]>() {
            @Override
            public int compare(long[] left, long[] right) {
                return Long.compare(left[0], right[0]);
            }
        });
        // A global [offset:±ms] shifts the whole clock; positive means sing earlier.
        long shift = offsetOf(lrc);
        String[] lines = new String[stamps.size()];
        long[] times = new long[stamps.size()];
        for (int i = 0; i < stamps.size(); i++) {
            lines[i] = texts.get((int) stamps.get(i)[1]);
            times[i] = Math.max(0L, stamps.get(i)[0] - shift);
        }
        return new Lyric(lines, times);
    }

    /** The document's global {@code [offset:±ms]} tag, or 0 when it has none. */
    private static long offsetOf(String lrc) {
        Matcher matcher = OFFSET.matcher(lrc);
        if (!matcher.find()) {
            return 0L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long toMillis(Matcher matcher) {
        long minutes = Long.parseLong(matcher.group(1));
        long seconds = Long.parseLong(matcher.group(2));
        String fraction = matcher.group(3);
        long millis = 0L;
        if (fraction != null) {
            int value = Integer.parseInt(fraction);
            // ".5" is 500 ms, ".05" is 50 ms, ".005" is 5 ms.
            for (int i = fraction.length(); i < 3; i++) {
                value *= 10;
            }
            millis = value;
        }
        return minutes * 60_000L + seconds * 1000L + millis;
    }
}
