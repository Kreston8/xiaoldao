package com.kreston8.islandcn;

import java.util.Calendar;

/**
 * 农历日期、二十四节气、节日倒数。纯本地算术，不联网、不要权限、不算天文。
 *
 * <p>两张表由 {@code gen_lunar.py} 从星历算出后写死在这里：农历表覆盖 1900-2100，节气表覆盖
 * 2024-2074。农历表不是抄来的，是脚本自己按朔望月长度编的，并且编完之后把表解出来，从
 * 1900-01-31 一直到 2100-12-31 逐天跟星历对了一遍（73412 天，零差异），所以这里不靠猜。
 */
final class Lunar {
    private Lunar() {
    }

    private static final int FIRST = 1900;
    private static final int LAST = 2100;
    private static final int TERM_FIRST = 2024;
    private static final int TERM_LAST = 2074;

    /**
     * One int per lunar year: bits 0-3 leap month (0 = none), bit 4 leap month is long,
     * bits 5-16 month 1..12 is long.
     */
    private static final int[] INFO = {
            0x017a48,   // 1900
            0x00ea40,   // 1901
            0x01d4a0,   // 1902
            0x016545,   // 1903
            0x00c960,   // 1904
            0x015360,   // 1905
            0x0154d4,   // 1906
            0x00ad40,   // 1907
            0x016b20,   // 1908
            0x017542,   // 1909
            0x00ea40,   // 1910
            0x01b4a6,   // 1911
            0x0164a0,   // 1912
            0x014960,   // 1913
            0x014975,   // 1914
            0x0055a0,   // 1915
            0x00ad60,   // 1916
            0x00b6a2,   // 1917
            0x01b520,   // 1918
            0x01d257,   // 1919
            0x01d240,   // 1920
            0x01a4a0,   // 1921
            0x01a5a5,   // 1922
            0x014ac0,   // 1923
            0x0056c0,   // 1924
            0x015ab4,   // 1925
            0x00da80,   // 1926
            0x01d520,   // 1927
            0x01e942,   // 1928
            0x01d240,   // 1929
            0x00d4c6,   // 1930
            0x00a560,   // 1931
            0x014ae0,   // 1932
            0x012ad5,   // 1933
            0x016b40,   // 1934
            0x00da80,   // 1935
            0x00ec33,   // 1936
            0x00e920,   // 1937
            0x016277,   // 1938
            0x015260,   // 1939
            0x00a560,   // 1940
            0x00a376,   // 1941
            0x0155a0,   // 1942
            0x00ad40,   // 1943
            0x01b4b4,   // 1944
            0x017480,   // 1945
            0x016920,   // 1946
            0x01a962,   // 1947
            0x0152a0,   // 1948
            0x0155a7,   // 1949
            0x00a6c0,   // 1950
            0x0155a0,   // 1951
            0x015955,   // 1952
            0x00b640,   // 1953
            0x01b4a0,   // 1954
            0x01d453,   // 1955
            0x01a940,   // 1956
            0x00b2a8,   // 1957
            0x0152e0,   // 1958
            0x00aac0,   // 1959
            0x00aea6,   // 1960
            0x015aa0,   // 1961
            0x00da40,   // 1962
            0x00eaa4,   // 1963
            0x01d4a0,   // 1964
            0x00c940,   // 1965
            0x00c9e3,   // 1966
            0x015360,   // 1967
            0x015b47,   // 1968
            0x00ad40,   // 1969
            0x016d20,   // 1970
            0x017645,   // 1971
            0x016a40,   // 1972
            0x0164a0,   // 1973
            0x016564,   // 1974
            0x014960,   // 1975
            0x015568,   // 1976
            0x0055a0,   // 1977
            0x00ada0,   // 1978
            0x00b536,   // 1979
            0x01b520,   // 1980
            0x01b240,   // 1981
            0x01d2a4,   // 1982
            0x01a4a0,   // 1983
            0x01c9aa,   // 1984
            0x014ac0,   // 1985
            0x0056c0,   // 1986
            0x005ea6,   // 1987
            0x00daa0,   // 1988
            0x01d520,   // 1989
            0x01ea45,   // 1990
            0x01d240,   // 1991
            0x01a4c0,   // 1992
            0x00a5c3,   // 1993
            0x014ae0,   // 1994
            0x015ac8,   // 1995
            0x006b40,   // 1996
            0x00daa0,   // 1997
            0x016d25,   // 1998
            0x00e920,   // 1999
            0x00d260,   // 2000
            0x015364,   // 2001
            0x00a560,   // 2002
            0x014b60,   // 2003
            0x0155c2,   // 2004
            0x00ad40,   // 2005
            0x01baa7,   // 2006
            0x017480,   // 2007
            0x016920,   // 2008
            0x01aa65,   // 2009
            0x0152a0,   // 2010
            0x00a5a0,   // 2011
            0x00aba4,   // 2012
            0x0156a0,   // 2013
            0x017549,   // 2014
            0x00ba40,   // 2015
            0x01b4a0,   // 2016
            0x01d156,   // 2017
            0x01a940,   // 2018
            0x0192a0,   // 2019
            0x0153c4,   // 2020
            0x00aac0,   // 2021
            0x0156a0,   // 2022
            0x015b42,   // 2023
            0x00da40,   // 2024
            0x00eca6,   // 2025
            0x00e4a0,   // 2026
            0x00c960,   // 2027
            0x00cae5,   // 2028
            0x019560,   // 2029
            0x00ab40,   // 2030
            0x00adc3,   // 2031
            0x016d20,   // 2032
            0x01ea4b,   // 2033
            0x016a40,   // 2034
            0x0164a0,   // 2035
            0x01a176,   // 2036
            0x014960,   // 2037
            0x009560,   // 2038
            0x005765,   // 2039
            0x00b5a0,   // 2040
            0x016d40,   // 2041
            0x01b542,   // 2042
            0x01b240,   // 2043
            0x01d4a7,   // 2044
            0x01a4a0,   // 2045
            0x014aa0,   // 2046
            0x0149b5,   // 2047
            0x0096c0,   // 2048
            0x00b6a0,   // 2049
            0x00da53,   // 2050
            0x01d920,   // 2051
            0x01f248,   // 2052
            0x01d240,   // 2053
            0x01a4c0,   // 2054
            0x00a2d6,   // 2055
            0x014ae0,   // 2056
            0x00aac0,   // 2057
            0x006cb4,   // 2058
            0x00eaa0,   // 2059
            0x00e920,   // 2060
            0x00e963,   // 2061
            0x00d260,   // 2062
            0x015567,   // 2063
            0x00a560,   // 2064
            0x014b60,   // 2065
            0x015745,   // 2066
            0x00ad40,   // 2067
            0x016ca0,   // 2068
            0x017544,   // 2069
            0x016940,   // 2070
            0x01b2a8,   // 2071
            0x0152a0,   // 2072
            0x00a5a0,   // 2073
            0x00ada6,   // 2074
            0x0156a0,   // 2075
            0x00b540,   // 2076
            0x00baa4,   // 2077
            0x01b4a0,   // 2078
            0x01a940,   // 2079
            0x01c9a3,   // 2080
            0x0192c0,   // 2081
            0x0199c7,   // 2082
            0x00aac0,   // 2083
            0x0156a0,   // 2084
            0x015a55,   // 2085
            0x00da40,   // 2086
            0x01d4a0,   // 2087
            0x00e544,   // 2088
            0x00d160,   // 2089
            0x00d2e8,   // 2090
            0x009560,   // 2091
            0x00ab60,   // 2092
            0x00aad6,   // 2093
            0x016d40,   // 2094
            0x00ea40,   // 2095
            0x0172a4,   // 2096
            0x0168a0,   // 2097
            0x015160,   // 2098
            0x0149e2,   // 2099
            0x009560,   // 2100
    };

    /** 24 terms, one char per term ('0' + day of month), 小寒 first, year-major. */
    private static final String TERM_DAYS = ""
            + "6D4C5D4C5D5E6F7F7F8G7F6E5D3B5D4D5E5E7F7G7G8G7F7E5D4B5D5D5E5E7G7G7G8G7F7F5D4C6E5D6E6E7G8G8G8G7F7F"
            + "6D4C5D4C5D5E6F7F7F8G7F6E5D3B5D4D5E5E7F7G7G8G7F7E5D4B5D5D5E5E7G7G7G8G7F7F5D4C6E5D6E6E7G8G8G8G7F7F"
            + "6D4C5D4C5D5E6F7F7F8G7F6E5D3B5D4D5E5E7F7G7G8G7F7E5D4B5D5D5E5E7G7G7G8G7F7F5D4C6E5D5E6E7G7G8G8G7F7F"
            + "6D4C5D4C5D5E6F7F7F8G7F6E5D3B5D4D5E5E7F7G7G8G7F7E5D4B5D5D5E5E7G7G7G8G7F7F5D4C6E5D5E6E7G7G8G8G7F7F"
            + "6D4C5D4C5D5E6F7F7F8G7F6E5D3B5D4D5D5E7F7G7F8G7F7E5D4B5D4D5E5E7G7G7G8G7F7F5D4C6E5D5E6E7G7G8G8G7F7F"
            + "6D4C5D4C5D5E6F7F7F7G7F6E5D3B5D4C5D5E7F7G7F8G7F7E5D4B5D4D5E5E7F7G7G8G7F7F5D4C6E5D5E6E7G7G8G8G7F7F"
            + "6D4C5D4C5D5D6F7F7F7G7E6E5C3B5D4C5D5E6F7F7F8G7F7E5D3B5D4D5E5E7F7G7G8G7F7F5D4C5D5D5E6E7G7G7G8G7F7F"
            + "5D4C5D4C5D5D6F7F7F7G7E6E5C3B5D4C5D5E6F7F7F8G7F7E5D3B5D4D5E5E7F7G7G8G7F7F5D4C5D5D5E5E7G7G7G8G7F7F"
            + "5D4C5D4C5D5D6F7F7F7G7E6E5C3B5D4C5D5E6F7F7F8G7F6E5D3B5D4D5E5E7F7G7G8G7F7E5D4C5D5D5E5E7G7G7G8G7F7F"
            + "5D4C5D4C5D5D6F7F7F7F6E6E5C3B5D4C5D5E6F7F7F8G7F6E5D3B5D4D5E5E7F7G7G8G7F7E5D4B5D5D5E5E7G7G7G8G7F7F"
            + "5D4C5D4C5D5D6F7F7F7F6E6E5C3B5D4C5D5E6F7F7F8G7F6E5D3B5D4D5E5E7F7G7G8G7F7E5D4B5D5D5E5E7G7G7G8G7F7F"
            + "5D4C5D4C4D5D6F6F7F7F6E6E5C3B5D4C5D5E6F7F7F8G7F6E5D3B5D4D5D5E7F7G7F8G7F7E5D4B5D5D5E5E7G7G7G8G7F7F"
            + "5D4C5D4C4D5D6F6F7F7F6E6E5C3B5D4C5D5E6F7F7F7G7F6E5D3B5D4D5D5E7F7G7F8G7F7E"
            ;

    private static final String[] TERM_NAMES = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
            "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"};

    private static final String[] MONTHS = {
            "正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};
    private static final String[] DIGITS = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /** Solar ones first, then the lunar ones; 0 是元旦, 9 是除夕. */
    private static final String[] FESTIVALS = {
            "元旦", "劳动节", "国庆", "春节", "元宵", "端午", "七夕", "中秋", "重阳", "除夕"};

    /** 节日看得远一点，节气只看最近一周，免得岛上永远挂着一个大大的倒数。 */
    private static final int FESTIVAL_DAYS = 15;
    private static final int TERM_DAYS_WINDOW = 7;

    private static String cachedStamp = "";
    private static String cachedLine = "";

    /**
     * The one line the island draws, e.g. {@code 八月十六 · 距寒露 5 天}.
     *
     * <p>Cached per calendar day: the pill asks four times a minute and none of this changes
     * between midnight and midnight.
     */
    static String line(long now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1;
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        String stamp = year + "-" + month + "-" + day;
        if (stamp.equals(cachedStamp)) {
            return cachedLine;
        }
        String line;
        try {
            line = build(year, month, day);
        } catch (Throwable failure) {
            line = "";
        }
        cachedStamp = stamp;
        cachedLine = line;
        return line;
    }

    private static String build(int year, int month, int day) {
        int[] lunar = lunarOf(year, month, day);
        String base = lunar == null ? "" : monthName(lunar[1], lunar[3] == 1) + dayName(lunar[2]);
        int[] festival = nextFestival(year, month, day);
        int index = termIndexOn(year, month, day);
        int[] term = index >= 0 ? new int[]{0, index} : nextTerm(year, month, day);

        int festivalDays = festival == null ? Integer.MAX_VALUE : festival[0];
        int termDays = term == null ? Integer.MAX_VALUE : term[0];
        String tail = null;
        if (festivalDays <= FESTIVAL_DAYS && festivalDays <= termDays) {
            tail = festivalDays == 0
                    ? FESTIVALS[festival[1]]
                    : "距" + FESTIVALS[festival[1]] + " " + festivalDays + " 天";
        } else if (termDays <= TERM_DAYS_WINDOW) {
            tail = termDays == 0
                    ? TERM_NAMES[term[1]]
                    : "距" + TERM_NAMES[term[1]] + " " + termDays + " 天";
        }
        if (tail == null) {
            return base;
        }
        return base.isEmpty() ? tail : base + " · " + tail;
    }

    private static String monthName(int month, boolean leap) {
        if (month < 1 || month > 12) {
            return "";
        }
        return (leap ? "闰" : "") + MONTHS[month - 1] + "月";
    }

    /** 初一 … 初十, 十一 … 十九, 二十, 廿一 … 廿九, 三十. */
    private static String dayName(int day) {
        if (day == 10) {
            return "初十";
        }
        if (day == 20) {
            return "二十";
        }
        if (day == 30) {
            return "三十";
        }
        if (day < 1 || day > 30) {
            return "";
        }
        String tens = day < 10 ? "初" : (day < 20 ? "十" : "廿");
        return tens + DIGITS[day % 10 - 1];
    }

    /** @return {lunar year, month, day, isLeap} or null outside the table. */
    private static int[] lunarOf(int year, int month, int day) {
        long offset = dayNumber(year, month, day) - dayNumber(FIRST, 1, 31);
        if (offset < 0) {
            return null;
        }
        int lunarYear = FIRST;
        while (lunarYear <= LAST) {
            int days = yearDays(lunarYear);
            if (offset < days) {
                break;
            }
            offset -= days;
            lunarYear++;
        }
        if (lunarYear > LAST) {
            return null;
        }
        int info = INFO[lunarYear - FIRST];
        int leap = info & 0xF;
        for (int m = 1; m <= 12; m++) {
            int length = monthDays(info, m, false);
            if (offset < length) {
                return new int[]{lunarYear, m, (int) offset + 1, 0};
            }
            offset -= length;
            if (leap == m) {
                int extra = monthDays(info, m, true);
                if (offset < extra) {
                    return new int[]{lunarYear, m, (int) offset + 1, 1};
                }
                offset -= extra;
            }
        }
        return null;
    }

    private static int yearDays(int lunarYear) {
        if (lunarYear < FIRST || lunarYear > LAST) {
            return 0;
        }
        int info = INFO[lunarYear - FIRST];
        int leap = info & 0xF;
        int days = leap == 0 ? 0 : monthDays(info, leap, true);
        for (int m = 1; m <= 12; m++) {
            days += monthDays(info, m, false);
        }
        return days;
    }

    private static int monthDays(int info, int month, boolean leap) {
        int bit = leap ? ((info >> 4) & 1) : ((info >> (4 + month)) & 1);
        return bit == 1 ? 30 : 29;
    }

    /** Julian day number, so subtracting two dates is plain integer maths. */
    private static long dayNumber(int year, int month, int day) {
        int a = (14 - month) / 12;
        long y = year + 4800L - a;
        long m = month + 12L * a - 3;
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
    }

    private static int termIndexOn(int year, int month, int day) {
        if (year < TERM_FIRST || year > TERM_LAST) {
            return -1;
        }
        int base = (year - TERM_FIRST) * 24;
        for (int i = 0; i < 24; i++) {
            if (i / 2 + 1 == month && TERM_DAYS.charAt(base + i) - '0' == day) {
                return i;
            }
        }
        return -1;
    }

    /** @return {days from today, term index} of the next term on or after today, or null. */
    private static int[] nextTerm(int year, int month, int day) {
        long today = dayNumber(year, month, day);
        int bestDays = Integer.MAX_VALUE;
        int best = -1;
        for (int y = year; y <= year + 1; y++) {
            if (y < TERM_FIRST || y > TERM_LAST) {
                continue;
            }
            int base = (y - TERM_FIRST) * 24;
            for (int i = 0; i < 24; i++) {
                long at = dayNumber(y, i / 2 + 1, TERM_DAYS.charAt(base + i) - '0');
                int diff = (int) (at - today);
                if (diff >= 0 && diff < bestDays) {
                    bestDays = diff;
                    best = i;
                }
            }
        }
        return best < 0 ? null : new int[]{bestDays, best};
    }

    /** @return {days from today, festival index} of the next one on or after today, or null. */
    private static int[] nextFestival(int year, int month, int day) {
        long today = dayNumber(year, month, day);
        int[] lunar = lunarOf(year, month, day);
        int lunarYear = lunar == null ? year : lunar[0];
        int bestDays = Integer.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < FESTIVALS.length; i++) {
            for (int y = (i >= 3 ? lunarYear : year); y <= (i >= 3 ? lunarYear : year) + 1; y++) {
                long at = festivalDay(i, y);
                if (at < 0) {
                    continue;
                }
                int diff = (int) (at - today);
                if (diff >= 0 && diff < bestDays) {
                    bestDays = diff;
                    best = i;
                }
            }
        }
        return best < 0 ? null : new int[]{bestDays, best};
    }

    private static long festivalDay(int index, int year) {
        switch (index) {
            case 0:
                return dayNumber(year, 1, 1);
            case 1:
                return dayNumber(year, 5, 1);
            case 2:
                return dayNumber(year, 10, 1);
            case 3:
                return lunarDay(year, 1, 1);
            case 4:
                return lunarDay(year, 1, 15);
            case 5:
                return lunarDay(year, 5, 5);
            case 6:
                return lunarDay(year, 7, 7);
            case 7:
                return lunarDay(year, 8, 15);
            case 8:
                return lunarDay(year, 9, 9);
            default:
                return newYearsEve(year);
        }
    }

    private static long lunarDay(int lunarYear, int month, int day) {
        if (lunarYear < FIRST || lunarYear >= LAST) {
            return -1;
        }
        long offset = 0;
        for (int year = FIRST; year < lunarYear; year++) {
            offset += yearDays(year);
        }
        int info = INFO[lunarYear - FIRST];
        int leap = info & 0xF;
        for (int m = 1; m < month; m++) {
            offset += monthDays(info, m, false);
            if (leap == m) {
                offset += monthDays(info, m, true);
            }
        }
        return dayNumber(FIRST, 1, 31) + offset + day - 1;
    }

    private static long newYearsEve(int lunarYear) {
        long first = lunarDay(lunarYear, 12, 1);
        if (first < 0) {
            return -1;
        }
        return first + monthDays(INFO[lunarYear - FIRST], 12, false) - 1;
    }
}
