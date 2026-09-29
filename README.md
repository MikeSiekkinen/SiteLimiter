# Site Limiter

Daily time budgets for websites, on Android, across whatever browsers you use.
No network code, no analytics, no accounts. All state is one `SharedPreferences`
file in the app's private storage.

It is a nudge, not a lock: the block screen offers a 5- or 15-minute snooze and an
"off for the rest of today". Those escape hatches sit one tap behind a
"…or keep going" disclosure, so closing the tab stays the easy path.

## Why an app and not a browser extension

Chrome for Android has no extension support at all, and Brave for Android doesn't
either (its ad blocking is built in, not an extension). Only Firefox for Android
can load add-ons. An extension would have meant abandoning both browsers you use.

An app can watch every browser at once. The mechanism is an `AccessibilityService`
that reads the browser's URL bar out of the view hierarchy — the same technique the
Play Store blockers use, which is exactly why they ask for such an alarming
permission. Here the permission is granted to code you built and can read.

## Build

Toolchain (install JDK 21 and the Android SDK; set `JAVA_HOME` and `ANDROID_HOME` locally):

- Gradle wrapper 9.8.0, AGP 9.4.1 with built-in Kotlin, Kotlin 2.4.20
- JDK 21; Java/Kotlin bytecode targets Java 17
- compileSdk 37, minSdk 26, targetSdk 34

```sh
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` creates an unsigned release APK. The release workflow signs it
with a permanent private key. See [release setup](docs/RELEASING.md).

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and tap it.

## Setup on the phone — in order

1. **Open Site Limiter** and add a limit, e.g. `reddit.com` / `30`.
   Subdomains count toward the parent, so `old.reddit.com` and `www.reddit.com`
   both spend the `reddit.com` budget.

2. **Tick the browsers to watch.** The list is every app on the device that can
   open an `https://` link. Brave and Chrome should both be there.

3. **Allow display over other apps.** Not cosmetic — Android forbids background
   activity starts, and holding this permission is the exemption that lets the
   block screen appear. Without it the app falls back to just sending you to the
   home screen when a budget runs out.

4. **Enable the accessibility service.** Settings → Accessibility → Installed apps
   (or "Downloaded apps") → **Site Limiter watcher** → On.

   > **Android 13 and newer will grey this out for a sideloaded app.** This is the
   > "restricted settings" protection. To clear it: Settings → Apps → Site Limiter →
   > **⋮ menu (top right)** → **Allow restricted settings**. Then go back and turn
   > the accessibility service on. Nearly everyone gets stuck here once.

## How the tracking works

- The service listens for window state/content changes from the browsers you
  ticked, and reads the omnibox node — `<package>:id/url_bar` on every Chromium
  fork (Chrome, Brave, Edge, Vivaldi, Kiwi, Opera), with specific IDs for Firefox,
  Samsung Internet and DuckDuckGo, plus a bounded tree scan as a fallback.
- A 5-second ticker banks elapsed time against the matching domain and checks the
  budget. Screen-off is checked explicitly, because no accessibility events arrive
  while the screen is off. A single flush also refuses to bank any gap longer than
  three ticks: `elapsedRealtime()` keeps counting through deep sleep, so without that
  cap, locking the phone on a page and picking it up the next morning would charge
  the whole night to the budget.
- The active window, not event ordering, is the authoritative answer to "which app is
  in front" — checked once per timer tick. Event ordering alone loses the browser when
  the notification shade opens and never picks it back up on a static page.
- A focused URL bar is ignored: its text is what you are typing, not the page.
- You get a "5 min left" toast once per day per domain before the wall.
- Budgets roll over at the hour you configure. Set it to 4 if your day genuinely
  ends at 2am.

## What was verified on device

Galaxy S24+, Android 16, against Brave and Chrome:

| Behaviour | Result |
| --- | --- |
| `com.android.chrome:id/url_bar` exists | yes, `EditText`, shows the **full URL with query string** |
| `com.brave.browser:id/url_bar` exists | yes, `EditText`, shows the elided `reddit.com` |
| Host detected from a live browser | `host -> reddit.com` |
| Time accrual accuracy | 11 → 21 → 31 → 41 → 51 → 61 s. Exact, no drift |
| Block fires at the limit | yes, on the first tick past 60 s |
| Background activity start | works, via the `SYSTEM_ALERT_WINDOW` exemption |
| Snooze 5 min | writes a +297 s expiry, returns to the browser, suppresses the block |
| Off for today | writes today's date, suppresses the block |
| Clock keeps running while snoozed | yes, by design |
| 0-minute limit (block entirely) | blocks within one tick |
| Deleting a rule clears its usage/snooze/off | yes |
| Browser enumeration | exactly 4 real browsers, no junk link handlers |
| Coexists with another accessibility service | yes, ran alongside a password manager |

Chrome showing the full URL rather than an elided domain is what exposed the
userinfo-parsing bug now covered by `HostParsingTest`.

## Known limits, honestly

- **Verified end to end** on a Galaxy S24+ (SM-S926U1, Android 16 / SDK 36) against
  Brave and Chrome. Time accrued at exactly 1s per second with no drift, the block
  screen fired on the tick after the budget was spent, and snooze / off-for-today
  both suppressed it correctly. See "What was verified" below.
- **Scrolled-away toolbar.** When Chromium hides the toolbar on scroll, the URL bar
  leaves the accessibility tree and the app keeps counting the last known host. If
  you navigate elsewhere while scrolled down, time is misattributed until the
  toolbar reappears. Fine for a productivity nudge; would matter for a lock.
- **Detection latency** is up to ~5 seconds, so you can overshoot a budget slightly.
- **Trivially bypassed** — incognito is still tracked, but turning the accessibility
  service off takes four taps. That is the intended design.
- **Reddit's native app is not covered.** This tracks websites in browsers only.

## Troubleshooting

If time never accrues, the URL bar ID is the first suspect. With the browser open
on a page:

```sh
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml -
grep -o 'resource-id="[^"]*url[^"]*"' w.xml
```

Then add whatever it prints to `urlBarIds()` in `app/src/main/java/com/mikes/sitelimiter/Browsers.kt`.

Technical diagnostics use fixed event identifiers and stack-frame code locations. Domains, URLs, browser selection, usage and exception messages are never logged, including in debug builds. There is no remote logging. See [Privacy over all](docs/PRIVACY.md).

## Accessibility boundaries

The service subscribes only to selected browser packages. An empty selection does not subscribe to all apps. A five-second foreground check observes the active package name so leaving a browser still stops accrual; it does not inspect other apps' text. Browser polling recovers static pages after switching back. Android still grants the service a broad capability to retrieve window content; the package filter reduces delivered events and the code restricts how that capability is used.

## Source map

| File | Role |
| --- | --- |
| `UrlWatcherService.kt` | Accessibility service: URL extraction, time accounting, blocking |
| `Browsers.kt` | Browser packages, per-browser URL-bar IDs, host parsing |
| `Prefs.kt` | All persistence: rules, usage, snoozes, day boundary |
| `BlockActivity.kt` | The wall, with snooze / off-for-today |
| `MainActivity.kt` | Setup, limits, browser selection |

## Dependency versions

Stable versions verified against Google Maven and Maven Central on 2026-09-29: AndroidX Core 1.19.1 (includes the former core-ktx extensions), AppCompat 1.8.0, Material 1.14.0, and JUnit 4.13.2, the latest release of the existing `junit:junit` artifact. No preview versions or dynamic version selectors are used. The Gradle distribution is verified with its official SHA-256 checksum.

## Continuous integration

[Android tests and APK](.github/workflows/android.yml) runs the business-logic tests and builds APKs on pushes and pull requests. Publishing a release attaches a signed APK and checksum to that release. A manual signing check tests the same signing path without publishing. See [release setup](docs/RELEASING.md).
