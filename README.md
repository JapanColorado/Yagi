# Yagi - Podcasts and Audiobooks

Yagi helps you manage and play podcasts and virtual podcasts/audio books. It lets you manage podcasts, audio books, YouTube and RSS news feeds in one application, and supports a high degree of customization to meet your different playback needs.

Yagi started as FocusPodcast: this repository is an independent, F-Droid-style fork of [allentown521/FocusPodcast](https://github.com/allentown521/FocusPodcast) (itself derived from [AntennaPod](https://github.com/AntennaPod/AntennaPod)). Compared with upstream it:

- ships a single build with **no ads, analytics, crash reporting, in-app purchases or cloud services** of any kind;
- vendors its former submodules in-tree and keeps the whole codebase under `dev.russell.yagi.*`;
- has an English-only source tree (translations for Chinese and Japanese are kept);
- enforces a set of quality gates in CI-style checks: zero Kotlin `!!`, zero `printStackTrace`, no empty catch blocks, every Rx chain with an error handler, detekt with no baseline, and a JVM unit-test suite;
- is not published on Google Play. Upstream's F-Droid listing is not this fork; build it yourself or install a release APK from this repository.

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Screenshot 1" height="200"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Screenshot 2" height="200"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Screenshot 3" height="200"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Screenshot 4" height="200"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" alt="Screenshot 5" height="200"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" alt="Screenshot 6" height="200"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" alt="Screenshot 7" height="200"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.png" alt="Screenshot 8" height="200"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/9.png" alt="Screenshot 9" height="200">

## Features

- **No ads, no tracking.** No server, no analytics, no crash reporting; nothing leaves your device except the feeds and audio you ask for.
- **Material Design 3** with custom theme colours, a pure-black theme, multiple Now Playing themes, a customisable home page and navigation drawer, and a choice of home-screen widgets.
- **Offline listening** with downloads to internal storage or SD card, and automatic clean-up of played episodes.
- **Podcast groups** to organise subscriptions by genre or mood.
- **Discovery:** search by name or keyword, browse latest/hot/popular podcasts by category, trending lists, or paste an RSS/Atom, iTunes or YouTube channel URL. OPML import and password-protected feeds are supported.
- **Playback:** audio and video podcasts, online or offline, Bluetooth, lock-screen player, drive mode, sleep timer, chapters and remembered playback position.
- **Audio effects:** variable speed (audio and video), volume boost, skip silence, mono down-mix, custom fast-forward/rewind lengths, and skipping intros and outros.
- **Automation:** scheduled feed refresh at a chosen frequency or time of day, automatic download of new episodes, automatic deletion after playback, low-storage clean-up, and Intent-based control from other apps.
- **Other:** multi-language UI, tablet layout, full backup/restore of app data, playback history, full-text search over titles and show notes, and an Android 13 monochrome icon.

## Moving from FocusPodcast

Yagi has its own app id (`dev.russell.yagi`), so it installs as a new app next to FocusPodcast rather than updating it. To keep your subscriptions, history and settings, use Settings > Import/Export > Database export in FocusPodcast and Database import in Yagi.

Two things do not come across, because Android keeps each app's files and folder permissions to itself:

- **Downloaded episodes** stay in FocusPodcast's own storage folder, which Yagi cannot read. With ten or more downloads, Yagi takes them all going missing at once for unreachable storage and keeps them marked as downloaded; each one is reset to not downloaded when you try to play it. Download again whatever you still want offline.
- **Local folders and audiobooks** you added from device storage need access granted again: remove them and add the same folders again in Yagi.

## Feedback
Bug reports and feature requests for this fork go in this repository's [issue tracker](../../issues). Please read [CONTRIBUTING.md](CONTRIBUTING.md) first for how to report a bug or request a feature. Issues with upstream FocusPodcast belong in the [upstream tracker](https://github.com/allentown521/FocusPodcast/issues).

## Privacy

Yagi collects nothing and has no server; see [PRIVACY.md](PRIVACY.md) for what it connects to and why.

## License

Yagi is licensed under the GNU General Public License (GPL-3.0). You can find the license text in the LICENSE file.

## Building

Every push to `main` that passes CI publishes a signed APK on the [Releases page](https://github.com/JapanColorado/Yagi/releases). To install it and get updates automatically, add `https://github.com/JapanColorado/Yagi` as a GitHub source in [Obtainium](https://github.com/ImranR98/Obtainium).

The build is driven by [pixi](https://pixi.sh), which provides JDK 25. You need an Android SDK with platform 37 and build-tools 36.0.0 (default location `~/Android/Sdk`, override with `ANDROID_HOME`). There are no git submodules and no build flavors.

```bash
pixi install          # one-time: fetches the JDK
pixi run build        # signed, minified release APK (needs secrets.properties)
pixi run install      # build + install the release APK on a connected device
pixi run run          # install + launch
pixi run build-debug / install-debug / run-debug   # debug variants (no secrets needed)
pixi run test         # JVM unit tests (JUnit4 + Robolectric)
pixi run lint         # Android lint
pixi run detekt       # detekt static analysis
pixi run nn           # Kotlin !! ratchet (must stay at 0)
pixi run check        # build-debug + test + lint + nn + detekt
pixi run logs         # logcat for the running app
```

Release builds additionally need a `secrets.properties` (copy `secrets.properties.sample`) with your signing keystore. See [CONTRIBUTING.md](CONTRIBUTING.md) for details, and `docs/REFACTOR_BASELINE.md` for the metrics the fork's quality gates are built around.

## Credits
Yagi is a fork of FocusPodcast, written by [allen town](https://bento.me/allentown) ([FocusApps](https://focus.hk.cn)); if you like the app, consider sponsoring the upstream author on [Ko-fi](https://ko-fi.com/focusapps) or [Liberapay](https://liberapay.com/FocusApps/donate). FocusPodcast is in turn built on [AntennaPod](https://github.com/AntennaPod/AntennaPod), and the name is a nod to it: a Yagi is a kind of antenna.
