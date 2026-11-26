# 岛仔 for Android

English | [中文说明](README.zh.md)

岛仔 (Daozai) is a notification island for Android: one black pill at the top of the screen that
opens for the things you actually need to see, plus a smaller standby pill that stays on the home
screen. Plain Java, two overlay windows, no WebView, no background polling beyond what the features
need.

The app was inspired by [islandcn](https://github.com/opencoredev/islandcn) (MIT, (c) 2026 Leo, a
Next.js site plus a shadcn registry, not a native app). Every line of Android code here was written
for this project; nothing from islandcn was copied. See [NOTICE](NOTICE).

## What it shows

| Card | Trigger | What you get |
|---|---|---|
| Message | notifications from WeChat / QQ / SMS | avatar or icon, sender, one line of text |
| Verification code | a code-like number in the notification or SMS | the code alone, big, with a copy button |
| Pickup code | 取件码 / 取货码 / 提货码 in an SMS | the code and the pickup point, tap opens that SMS |
| Delivery | 派送中 / 已签收 / 即将送达 | the courier's wording and an arrival estimate |
| To-do | 小米笔记 reminders (needs root, see below) | the item and its time |
| Call | ringing / alarm | number or contact, tap to go back to the call |
| Music | 网易云音乐 playing | title, artist, progress and the current lyric line |
| Standby pill | home screen | clock, weather, battery, connected-device chips |

Tapping the pill opens the source app. Double-tap copies the card's text. Swipe left dismisses.
Long-press shakes the pill; swiping up while it shakes hides it for three minutes. On the standby
pill the same gestures work, and long-press copies what is on it.

## Install

1. Open [Releases](../../releases) and download the newest `daozai-*.apk`.
2. Install it. MIUI will ask you to allow installs from this source.
3. Open the app and grant, one line at a time: 悬浮窗权限, 通知使用权, and — if you want those
   cards — 短信权限, 电话权限, 使用情况访问, 附近设备（蓝牙）.
4. In MIUI: 应用管理 → 岛仔 → 权限 → 后台弹出界面 on; 省电策略 → 无限制; 自启动 on. Without these
   three the island cannot be brought back after you leave the app.

There is one universal APK. No ABI split, no native library.

## What it cannot do

- **To-do cards** can only read 小米笔记 (`com.miui.notes`). It publishes no provider, so the app
  reads its database directly and that needs root. Without root the switch does nothing, and the
  settings page says so.
- **Bluetooth battery** has no public API on Android. The app asks the system for cached values,
  listens for the battery broadcast, reads the GATT battery service, and asks the A2DP / HEADSET /
  GATT profile proxies for connected devices. A classic-only headset has no GATT server to read,
  and some buds report battery only over the vendor's own channel, so you may see an icon without a
  number. The diagnostic line in the settings page says which source answered.
- **Weather** is typed by hand: no location permission is requested. The tap opens 小米天气 when it
  is installed; MIUI must allow 后台弹出界面 for that jump to survive.
- **Calls**: Android 12 and newer gives no phone number to a third-party app. The card shows what
  the call UI exposes, and tapping it returns to the call screen.
- **SMS** cards open the conversation, not one single message.
- **Lyrics** come from `music.163.com`; a track it does not have shows the title and artist only.
- The island never draws over the status bar, and it hides on the lock screen.

## Network

Four hosts, all only when the feature is used: `geocoding-api.open-meteo.com`,
`api.open-meteo.com`, `air-quality-api.open-meteo.com` (the typed city, cached 30 minutes) and
`music.163.com` (lyrics). No analytics, no login, no other traffic.

## Build

CI builds on every push that touches `android/**` and publishes a release with the APK. The local
route is the same Gradle project:

```
cd android
gradle assembleRelease          # needs JDK 17 and an Android SDK with platform 34
```

Version numbers: `2.<batch>.<run number>`, where `versionCode = 1000 + <run number>` so a build from
this repository can always update one from the older `islandcn` fork.

Permissions, every switch and the full list of limits are in [android/README.md](android/README.md)
([中文](android/README.zh.md)).

## Licence

MIT, see [LICENSE](LICENSE). Credits and trademark notes in [NOTICE](NOTICE).
