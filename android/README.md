# 小灵岛 for Android

English | [中文说明](README.zh.md)

This repository is the standalone home of 小灵岛 (xiaoldao), a native Android notification island.
小灵岛 took its inspiration from islandcn — an MIT-licensed Next.js site plus a shadcn registry,
not a native app — and reimplements everything in plain Java; no code of it is copied here (see NOTICE).
A black pill sits at the top of the screen at a fixed height, and only its width follows the content.

It replaced an earlier WebView shell. Nothing here loads a web page. Two optional HTTPS lookups
exist — per-line lyrics and a temperature — and both are described below with their limits.

## What it shows

Eight kinds of card remain: the first four are decided by `ContentParser` from title, body and
application, the rest by their own sources.

| Kind | Trigger | Card |
|---|---|---|
| 微信 | notification from `com.tencent.mm` | avatar + sender + message |
| QQ | notification from `com.tencent.mobileqq` | avatar + sender + message |
| 取件码 | SMS with 取件码/取货码/提货码 plus a code nearby | 快递点：取件码 (`菜鸟驿站：1-2-3456`), a tap opens that SMS conversation |
| 外卖 | "预计 12:35 送达", "还有 18 分钟" | countdown that refreshes every 15 s, gated per platform |
| 待办 | notification from 小米笔记 / a notes app | tag + the todo text |
| 来电 | call state callback | tag + 号码 / 未知号码，tap 打开拨号盘 |
| 闹钟 | next clock alarm, 15 min ahead | tag + 07:30 + 还有 N 分钟响铃 |
| 音乐 | a playing media session, shown only while the launcher is in front | 46 dp strip: album art + one lyric line, lit character by character |

Plain notifications, verification codes and navigation are not shown at all any more — they were
removed on request, so that an app which is not one of the eight above cannot interrupt anything.

Every card is the same fixed 46 dp height and only its width follows the content: one icon, one bold
line, one body line. The icon is the notifying app's own — its large icon first (WeChat and QQ put the
contact's avatar there), then a `Person` avatar from a `MessagingStyle` notification, then the launcher
icon, then the small icon — so a card says which app it came from. Two lines are all that height holds,
so the copyable code moved into the title row and a live countdown replaces the body line instead of
adding a third one. The pill starts at 250 dp, widens until the sentence fits up to the screen edge, and
only past that scrolls a marquee; the lyric line is exempt because the per-character highlight owns it.

A double tap copies just the pickup code — `1-2-3456`, without the `菜鸟驿站：` prefix; the notification
itself is left in the shade, so the copy is never destructive. A swipe left closes that message for good:
the same notification never returns to the island, not even after the service restarts, and on an alarm
card it also cancels the reminder slots of that alarm which have not been shown yet.

### Bluetooth battery chips — removed in 2.16

**This feature is gone.** Android has no public accessory-battery API, so 2.16 deleted the chips
and the `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` permissions along with them. What follows is kept
as the record of why the dead ends were dead ends. If you are reading this before 2.16 in the
release list, those builds still had it.

When 蓝牙设备电量显示 is on, the right end of the pill shows one chip per connected accessory — watch,
earbuds, mouse, anything else — as an icon with the percentage underneath, up to three devices. Each
device has its own switch under that parent, so one watch can be hidden without hiding the rest, and
a hidden device gets neither a chip nor a GATT read. A lyric line always carries the chips, because
that is the moment the level matters; the pill widens instead of squeezing the text.

Android has no public battery API for accessories, so a level is hunted down in three steps, cheapest
first:

1. the hidden `BluetoothDevice.getBatteryLevel()`, which is the number Settings shows — greylisted, so
   some ROMs answer -1 or throw;
2. the platform's own `BATTERY_LEVEL_CHANGED` broadcast, sent when a device pushes a level (typically
   at connect), cached and persisted;
3. a GATT read of the standard battery service (`0x180F`/`0x2A19`), LE and dual-mode devices only, at
   most once every ten minutes per address, closing the connection as soon as the value arrives.

Consequences worth knowing: a classic-only headset has no GATT server, so for those only the first two
steps can answer; a device that never reports keeps its icon and shows `--`, because a wrong number on
a watch is worse than none.

### Standby pill (桌面常驻)

When 桌面常驻 is on and nothing else is on screen, a second, narrower pill stays on the launcher with
the items you enabled:

- **天气温度** — the current temperature with a weather icon picked from the WMO code, e.g. `23°`.
  Needs a city name typed in the app; there is no location permission and no GPS. The geocoder is
  asked for ten candidates and each is scored (exact name > district `admin3` > city `admin2` >
  province `admin1` > prefix), so 「杭州 西湖」 resolves to 西湖区 instead of 杭州市; what actually
  resolved is printed in the app as 已定位到 …, because Open-Meteo does not list every district.
- **空气质量 AQI** — the US AQI for the same city, graded into four colours (<50 green, <100 yellow,
  <150 orange, above that red).
- **小灵岛表情** — two eyes and nothing else: no face, no blush, no colour. 32 dp, a 2.4 s mood slot
  that picks round eyes or a smiling crescent, a blink every 1.7 s with a quarter of them skipped, a
  gaze drift every 1.2 s and a small bounce after each blink, so it visibly moves. Drawn in code as
  an approximation of the mascot, not the original artwork.
- **每日一句** — one line of encouragement at 17.5sp in a bold serif face (宋体). The index is
  days-since-epoch × 7 mod 30, so it lands on a different line every day instead of walking the list.
- **蓝牙电量** — the same accessory percentages as the chips, read through the passive path only: the
  standby pill never opens a GATT connection, so its 4-second refresh cannot disturb a headset.
- **Double tap to copy** — a double tap on the standby pill copies only the 每日一句 sentence; a
  double tap on a pickup-code card copies only the bare code, without the 驿站名： prefix. No other
  card copies anything.

The five items sit one per row — switch, name, 上移, 下移 — under the master switch, so hiding an item
never needs a reorder. While a notification card exists as well the two swap every 3 seconds, and
closing that notification by hand leaves the standby pill alone on screen. A lyric line outranks both:
it keeps the pill, gives a fresh notification exactly 3 seconds, then comes back at the position the
track has reached meanwhile. The pill still disappears the moment the launcher is no longer in front
or the screen is locked.

### Appearance (外观)

The settings screen opens with an 外观 card: eight dark tints and a 0–100% opacity slider, applied to
both windows at once — the card that pops up and the standby pill — so the two can never disagree.
The hair-line border fades with the fill, because a see-through pill with a solid outline looks like
a wire frame. The presets are dark on purpose: every line on the island is white, so a pale fill
would make it unreadable, and opacity is the knob for "make it quieter" instead. Dragging the slider
is a live preview.

## How it works

- `IslandService` (`IslandService.java`) is a foreground service that owns one
  `TYPE_APPLICATION_OVERLAY` window, plus a second one for the standby pill. Both are `WRAP_CONTENT`,
  so animating the card's layout params is what grows and shrinks them, and
  `Gravity.TOP | CENTER_HORIZONTAL` keeps them centred.
- `IslandListener` (`IslandListener.java`) is a `NotificationListenerService`. It drops group
  summaries, ongoing events and its own status notification, then hands title, body, icon and
  content intent to the island.
- `SmsReceiver` (`SmsReceiver.java`) listens for `SMS_RECEIVED` so a code arrives even when the
  messaging app does not post a notification. Ordinary texts are dropped; when this receiver is
  active the messaging app's own notification is skipped to avoid showing the code twice.
- `PhoneWatcher` (`PhoneWatcher.java`) registers a call-state callback, `TelephonyCallback` on
  Android 12+ and the legacy `PhoneStateListener` below it. Only the legacy API still carries the
  caller id, hence 未知号码 on newer phones.
- `AlarmWatcher` (`AlarmWatcher.java`) reads `AlarmManager.getNextAlarmClock()` and schedules one
  inexact wake-up fifteen minutes earlier, which is what posts the 闹钟 card. It re-arms itself
  whenever the system announces a changed alarm, arms a second wake-up a minute past the alarm time
  that withdraws the card, and reconciles card against reality on every call: if the system no longer
  reports the alarm that was announced, the card is removed.
- Cards are only built from the sources the whitelist names; the old navigation whitelist
  (`IslandListener.NAV_PACKAGES`) is no longer consulted, because 导航 was removed on request.
- `ContentParser` (`ContentParser.java`) is the classifier: keyword plus regular expression matching
  for codes, pickup numbers and delivery ETAs.
- `IslandView` draws one pill and nothing else with plain platform views: a fixed 46 dp height whose
  width is re-measured per card, so a long delivery line makes it wider and a short code narrower.
  `OvershootInterpolator` gives the spring when the width changes. That height holds two lines, which
  is why a copyable code sits in the title row and a countdown takes over the body line.
- `BtWatcher` (`BtWatcher.java`) is the battery reading, in three steps: the hidden
  `BluetoothDevice.getBatteryLevel()` (the same number Settings prints), then the hidden
  `BATTERY_LEVEL_CHANGED` broadcast, then a GATT read of the standard battery service
  (`0x180F`/`0x2A19`) for LE and dual-mode devices. A read opens a connection to the accessory, so
  each address is asked at most once every ten minutes and the link is closed the moment the value
  arrives, or after nine seconds. The last known level per address lives in `SharedPreferences`, and a
  fresh one is pushed to the card through a listener instead of waiting for the next notification.
  Every platform call is guarded: without `BLUETOOTH_CONNECT` nothing is read rather than the overlay
  crashing.
- `StandbyView` (`StandbyView.java`) renders the standby pill and owns both lookups: geocoding
  (`geocoding-api.open-meteo.com`) turns the typed city into coordinates, then
  `api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code` returns the temperature and the
  WMO code that picks its icon, and `air-quality-api.open-meteo.com/v1/air-quality?current=us_aqi`
  adds the AQI. The city may name a district: ten candidates are requested in Chinese (with
  `countryCode=CN` when the input is Han) and each is scored — exact name match 60, district
  `admin3` 55, city `admin2` 50, province `admin1` 35, prefix 40 — so 「杭州 西湖」 lands on 西湖区
  rather than 杭州市, and the resolved label is printed as 已定位到 … so a miss is visible instead of silent.
  6 s timeout, one request in flight, nothing requested without a city. A non-2xx answer throws
  with its status code instead of being parsed as empty, and the latest outcome is kept as a line
  the app prints under the city field (立即测试天气 runs one request on the spot).
  A successful reading is written to this device — temperature, WMO code, AQI, resolved place and
  coordinates, stamped with the city name and the time — so a killed process shows the last known
  number at once instead of an empty chip until the next answer arrives; an entry older than 12
  hours is dropped. Those coordinates are reused, so the usual tick needs one request instead of
  two. A failure now retries after 90 s, then 3 min, then 10 min, then every 30 min, and a half
  answer (a forecast without an AQI) is merged with the previous value instead of blanking it.
  `xiaoldaoView` (`xiaoldaoView.java`) draws the mascot: two eyes and nothing else. A hash of the 2.4 s
  mood slot picks round eyes or a smiling crescent, a second hash of a 1.7 s slot decides the blinks
  (a quarter of them skipped) with a small bounce afterwards, and a third of a 1.2 s slot moves the
  gaze left or right — so a dropped frame costs nothing and there is no state to keep.
- `IslandBridge` is a static, weakly referenced bus between the components, which run in the same
  process.
- `MediaWatcher` (`MediaWatcher.java`) reads the playing session through `MediaSessionManager`, which
  rides on the notification access the app already has. Track changes arrive as
  `MediaController.Callback`, so nothing polls for metadata. The only timer is a 2.5 s `UsageEvents`
  query that resolves the foreground package against every `CATEGORY_HOME` handler — anything that is
  not the launcher hides the strip — and it runs only while a session is playing: pause, screen off
  or a missing usage-access grant cancels it. One card per track.
- `Prefs` (`Prefs.java`) is the switch store: one boolean per first-level feature (`on_wechat`,
  `on_qq`, `on_pickup`, `on_delivery` plus `dl_meituan` / `dl_shangou` / `dl_jd` / `dl_qixian`,
  `on_todo`, `on_call`, `on_alarm`, `on_music` with `on_lyrics`, `on_bt` with one `bt_ok:<address>`
  per accessory), `on_standby` plus five item switches and an order string for the standby pill, the
  typed city, and the two look values `look_color` (`#RRGGBB`) and `look_alpha` (`0`–`100`).
  `IslandService.allow()` is the whitelist: it decides whether an event may exist at all, then
  `Prefs.enabled()` drops a card whose own switch is off, both before the overlay is touched.
- `Look` (`Look.java`) holds the two look values and turns them into what a drawable wants: the tint
  with the chosen opacity folded in, plus a border that fades with it. Both overlays read it when they
  are built and again on `ACTION_LOOK`, which the settings screen sends on every slider move, so a
  change lands in both windows at once; with the service stopped the request is dropped instead of
  starting it.
- `Lyric` parses LRC (`[mm:ss.cc]` lines, credit lines filtered out) and answers "which line at this
  position" by binary search; `LyricSource` does the one HTTPS lookup per track with a 6 s timeout,
  a 512 KB cap and a 16 entry memory-only cache.

Behaviour: the pill has one shape only — a 46 dp capsule just below the status bar, centred, whose
width follows what it holds, so a delivery card is long and a pickup code short. Nothing expands and
nothing collapses.

- **Single tap** opens what the card stands for: the alarm card opens the clock's alarm list, a pickup
  card opens that SMS conversation.
- **Double tap** copies, and only for two things: a pickup code (the code alone, without the 驿站名：
  prefix) and the standby pill's 每日一句 (that sentence alone). Every other card ignores it.
- **Swipe left** closes that one message for good — the same notification never comes back to the
  island, not even after the service restarts. The music card is the only exception: the current
  track is dropped, the next one still shows up. On an alarm card the same swipe also cancels the
  reminder slots of that alarm which have not been shown yet.
- **Swipe up, no long press** cycles what is rotating: several pickup codes or notifications, a
  notification and the standby pill, and the lyric line and a notification while a track plays.
- **Long press, then swipe up** hides the island for 3 minutes; it comes back on its own.
- **免打扰 / 仅优先 / 静音** keeps notifications, pickup codes, delivery, todos, calls and alarms off
  the island entirely. The standby pill and the lyric line are unaffected, and nothing that was held
  back pops up when the mode ends.
- A card that arrives while another app is in front is dropped rather than queued, and it is not
  replayed on the way back to the launcher either. That is the usage-access rule; without the grant
  the earlier behaviour returns and a card pops over any screen.
- Notifications the Xiaomi clock app posts itself (作息提醒 / 该睡觉了) never reach the island.
- An alarm reminds at T-10, T-5 and T-1 minutes, five seconds each, then the standby pill comes back
  — or the lyric line straight away, if a track is playing. Nothing after T; the system alarm rings
  by itself.

Removing the notification from the shade still hides the island. The music card never times out: it
keeps the karaoke line ticking, and it goes away only when the track stops, when you swipe it off, or
when another app comes to the front. Returning to the desktop brings it straight back: the foreground
answer is cached, because `UsageEvents` reports *changes*, so an empty window means "nothing moved", not
"nothing is in front" — treating it as the latter hid the card a few seconds after it appeared.
The line itself is kept honest by trusting the player only as far as it is trustworthy: the
position is extrapolated while `getLastPositionUpdateTime()` is plausible, the card re-anchors
when the reported position drifts more than 700 ms from the running estimate, LRC `[offset:±ms]`
tags are applied, and a lyric whose total length is far from the track duration (a different
recording) is dropped instead of shown out of sync.

## The switches

The app is a two-level list. Nine first-level switches, top to bottom:

| First level | Default | Second level (only while the parent is on) |
|---|---|---|
| 微信 | on | — |
| QQ | on | — |
| 短信取件码 | on | — |
| 外卖进度 | on | 美团, 闪购, 京东, 七鲜 — one per platform, each gating its own arrival card |
| 待办提醒 | on | — |
| 来电显示 | on | — |
| 闹钟 | on | — |
| 音乐播放与歌词 | on | 联网歌词 — the one lyric lookup per track |
| 自动更新 | off | one check per hour against `api.github.com`, then the package is handed to the system installer |

A second level only exists while its parent is on: closing the parent folds the sub-list away and
throws its rows out, so nothing stays rendered behind a closed switch. Everything that is not in
this table is gone, and with it three kinds of card: **plain notifications, verification codes and
navigation no longer reach the island at all.** `IslandService.allow()` is a whitelist, so an app
that is not represented above cannot pop anything. The platform a delivery card belongs to is read
from the package name first (`sankuai`/`meituan`, `jingdong`/`com.jd.*`, `shangou`, `qixian`) and
from the card's own words second, because 七鲜 ships inside a `com.jd.*` package; an unrecognised
courier is kept rather than dropped. Outside that card:

| Switch | Default | Effect |
|---|---|---|
| 桌面常驻 | off | the standby pill on the launcher |
| 天气温度 / 空气质量 AQI / 小灵岛表情 / 每日一句 / 蓝牙电量 | on | which items the standby pill holds; each has its own switch on one row |
| 城市 | empty | no city means no weather request at all; a district works too (「北京 朝阳」) |

## Permissions the user has to grant

1. **Display over other apps** (`SYSTEM_ALERT_WINDOW`) for the overlay itself.
2. **Notification access** (`BIND_NOTIFICATION_LISTENER_SERVICE`) for the content.
3. **SMS** (`RECEIVE_SMS`), optional, for codes that never reach the shade.
4. **Phone** (`READ_PHONE_STATE`), optional, for incoming-call cards. On Android 12+ the card still
   says 未知号码, because the caller id is no longer shared with third-party apps.
5. **Usage access** (`PACKAGE_USAGE_STATS`), optional but required for the desktop-only rule and for
   the standby pill: it is how the app knows the launcher is in front. One cheap query every 2.5 s
   while a track plays, and one every 4 s while the standby pill is active.
6. **Nearby devices** (`BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN`) was needed up to 2.16 for the
   accessory percentages; 2.16 removed the feature and the two permissions with it.
7. On MIUI / HyperOS also enable **后台弹出界面** for this app, otherwise the system drops overlays
   started from the background.
8. Android 13+ asks for `POST_NOTIFICATIONS` so the foreground service can show its status
   notification.
9. **Battery optimisation** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, optional), plus 自启动, an
   unrestricted 省电策略 and 设置 → 省电与电池 → 「锁屏后清理内存」 = 永不 in MIUI; locking the card in
   the recent tasks helps as well. Android 12+ refuses to start a foreground service from the
   background, and refuses it silently, so the revival runs on two clocks: `onTaskRemoved` — and a
   service destroyed while the switch is still on — queues an **alarm eight seconds out** and a
   persisted one-shot job ten seconds out, and the fifteen-minute watchdog stays behind both. The
   alarm is the reliable one: delivering a broadcast puts the app on the temporary allow-list for
   a few seconds, which is exactly what the restart needs. A refused attempt is not dropped; it
   re-arms itself after 30 s, 60 s, 3 min, 10 min and then every 15 minutes. The 后台常驻 card has
   a button that opens the MIUI 自启动 screen (falling back to the app page), and the 保活诊断 card
   below it prints the last sixty moments — task removed, alarm fired, launch refused, and why the
   standby pill was hidden — with a copy button, because "it did not come back" is not something
   anybody should have to describe from memory. The launcher test also forgets a "another app is
   in front" answer that nothing has refreshed for two minutes, instead of hiding the pill for the
   rest of the day when a ROM stops delivering usage events. `RECEIVE_BOOT_COMPLETED` (no prompt)
   restores the island after a reboot, and only if it was on. A 强行停止 still cancels everything:
   nothing an app schedules survives that, which is the system's design and not a bug.
10. **Install unknown apps** (`REQUEST_INSTALL_PACKAGES`), only for updates: the package
    downloaded by 检查更新 goes to the system installer, and Android refuses to run that installer
    for an app without this grant. The person still confirms the install; nothing here installs by
    itself.

## What it is not

- Not a `SystemUI` plugin. It cannot draw above the status bar (the status bar window is a higher
  layer), so the island is anchored just below it instead of around the punch hole.
- No interaction with the lock screen: the system hides `TYPE_APPLICATION_OVERLAY` windows while the
  device is locked.
- The 待办 card only sees Mi Notes' *reminder notifications*. Reading the note database itself needs
  root, and there is no public provider for it, so the island cannot list upcoming todos.
- 来电 cards cannot show the number on Android 12+: the platform stopped passing the caller id to
  third-party apps. The in-call screen is also a full-screen activity, so it covers the overlay while
  the phone is actually ringing.
- The 闹钟 card is a heads-up about fifteen minutes early; when the alarm fires, the clock app's
  full-screen intent covers everything. The card is withdrawn a minute after the alarm time, and
  `AlarmWatcher.schedule` retracts it as soon as `getNextAlarmClock()` stops reporting that exact
  alarm. One alarm is announced once: the trigger time is remembered in `Prefs.KEY_ALARM_SHOWN`.
  A ROM that keeps reporting an alarm the clock app no longer lists would still produce one card;
  the 闹钟 switch is the way out of that.
- Plain notifications, verification codes and navigation were removed outright in 2.10 — on request,
  not by accident: the island now only builds cards from the whitelist above.
- 音乐 shows what the player publishes. Real per-line lyrics are not obtainable offline: 网易云音乐
  keeps its lyrics inside its own UI and puts nothing in the media session, so the line comes from the
  public endpoint on `music.163.com` over HTTPS, once per track, only while 联网歌词 is on, capped at
  512 KB, cached in memory only. That endpoint gives one timestamp per line, not per syllable, so the
  character-by-character light-up is interpolated linearly inside each line: it shows which line is
  being sung and roughly how far into it the singer is, not word accuracy. When the lookup fails or
  the switch is off, that line shows the song name instead.
- The music strip and the standby pill are **desktop only** and need the **usage access** grant;
  without it the app cannot tell the launcher from an app, so the strip falls back to showing over any
  screen and the standby pill never appears.
- 天气温度 and 空气质量 AQI need a city typed by hand and hit two hosts on Open-Meteo: `api.open-meteo.com`
  for the forecast and `air-quality-api.open-meteo.com` for the AQI, with
  `geocoding-api.open-meteo.com` turning the name into coordinates.
- 自动更新 is off until asked for, and there is a 检查更新 button that does the same thing once. When
  either runs, the app reads the release list of `Kreston8/xiaoldao` from
  `api.github.com/repos/Kreston8/xiaoldao/releases?per_page=50`, compares the newest `xiaoldao-v…`
  tag with the installed `versionName`, and — only when the tag is newer — downloads that release's
  `.apk` from `objects.githubusercontent.com` into the app's own cache directory, then hands it to
  the system installer through a content provider that serves exactly that one file. Three
  consequences worth saying out loud: the check only happens while the island service is alive, a
  failed check writes its reason under 保活诊断 instead of failing silently, and there is no silent
  install — without root or device-owner rights the last tap is the person's, and the first update
  also needs 设置 → 安装未知应用 granted to this app.
- `BLUETOOTH_CONNECT` and the accessory percentage are gone with 2.16: the public API never
  reported a reliable number, so the feature was removed rather than guessed at.

## Build

Locally, with JDK 17 and the Android SDK (`compileSdk 34`):

```bash
cd android
gradle assembleRelease
# android/app/build/outputs/apk/release/app-release.apk
```

Signing is pinned: `app/build.gradle.kts` reads `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` and uses that PKCS12
keystore when they are set, so consecutive releases install over each other. Without them it falls
back to the debug key, and such a build needs an uninstall first. The key is not in this repo: it
lives in the repository secrets and in the maintainer's offline backup. Losing it means exactly one
more uninstall.

Or push to the `android-apk` branch / run the **Build Android APK** workflow by hand; it builds the
APK and attaches it to a GitHub release.

## Install on the phone

1. Download the `.apk` from the release assets.
2. Allow installs from unknown sources for the app you download with (MIUI: 设置 → 应用设置 →
   特殊权限 → 安装未知应用).
3. Open the app, grant the permissions, press 启动小灵岛, then 预览效果.

## Version numbers

`versionName` is `2.<batch>.<run>`, `versionCode` is the workflow run number, and the release tag is
`xiaoldao-v2.<batch>.<run>`. The middle digit is bumped once per feature batch, so released series skip
numbers: failed runs never ship, and each batch starts a new middle digit on purpose.

## Which download for which phone

One APK. There is nothing to choose: the app is plain Java in `classes.dex` with no native code, so
there is no `lib/` folder and therefore no per-ABI split, and the only device-specific decisions are
made at runtime (the launcher package is resolved through `CATEGORY_HOME`, the foreground package
through `UsageEvents`). `minSdk 26` means Android 8.0 and newer; `compileSdk`/`targetSdk 34` means it
is built and checked against Android 14. That one file installs on 小米 / 红米 / POCO with MIUI 12-14
or HyperOS, and on other skins, with the caveats listed above.

Splitting it per system would only be packaging theatre: the differences between those phones are
permissions the user grants and runtime detection, not code that can be compiled out.
