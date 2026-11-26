package com.kreston8.islandcn;

import android.content.Context;

import java.util.Locale;

/**
 * Which delivery platform a card came from, and whether that one platform is switched on.
 *
 * <p>The platform is read from the package name first because that never changes, and from the
 * visible words second because several platforms share one app (美团闪购 lives inside 美团, and 七鲜
 * is a 京东 app). The words win over the package for exactly those two: a card that says 七鲜 is not
 * a 京东到家 card even though both ship in a {@code com.jd.*} package.
 */
final class Delivery {

    static final int NONE = 0;
    static final int MEITUAN = 1;
    static final int SHANGOU = 2;
    static final int JD = 3;
    static final int QIXIAN = 4;

    private Delivery() {
    }

    static int platformOf(String pkg, String appLabel, CharSequence title, CharSequence text) {
        String pack = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String words = (nz(appLabel) + " " + nz(title) + " " + nz(text)).toLowerCase(Locale.ROOT);
        if (pack.contains("qixian") || words.contains("七鲜")) {
            return QIXIAN;
        }
        if (pack.contains("shangou") || words.contains("闪购")) {
            return SHANGOU;
        }
        if (pack.contains("sankuai") || pack.contains("meituan") || words.contains("美团")) {
            return MEITUAN;
        }
        if (pack.contains("jingdong") || pack.startsWith("com.jd.") || pack.contains(".jd.")
                || words.contains("京东")) {
            return JD;
        }
        return NONE;
    }

    /**
     * True when this delivery card may be shown.
     *
     * <p>The 外卖进度 master switch is checked first, then the platform's own switch. A card whose
     * platform could not be recognised is kept, because dropping an unknown courier is worse than
     * showing an extra line.
     */
    static boolean allowed(Context context, IslandEvent event) {
        if (!Prefs.get(context, Prefs.KEY_DELIVERY)) {
            return false;
        }
        switch (platformOf(event.pkg, event.appLabel, event.title, event.text)) {
            case MEITUAN:
                return Prefs.get(context, Prefs.KEY_DELIVERY_MEITUAN);
            case SHANGOU:
                return Prefs.get(context, Prefs.KEY_DELIVERY_SHANGOU);
            case JD:
                return Prefs.get(context, Prefs.KEY_DELIVERY_JD);
            case QIXIAN:
                return Prefs.get(context, Prefs.KEY_DELIVERY_QIXIAN);
            default:
                return true;
        }
    }

    private static String nz(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
