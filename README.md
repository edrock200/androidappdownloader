# App Downloader

An Android app (phones, tablets, **Fire TV** and **Android TV**) for saving links to APKs and
installing them in one tap. It understands three kinds of sources:

| You enter | Example | What happens |
|---|---|---|
| A direct link | `https://example.com/app.apk` | Downloads and installs the APK. If the link is a web page, the page is scanned for an `.apk` link. |
| An **aftv.news** code | `12345` or `aftv.news/12345` | Resolves the AFTVnews short code (the same codes used by the Downloader app) and downloads its target. |
| A **GitHub** repository | `https://github.com/owner/repo` | Finds the newest release with an APK built for your device, installs it, and **keeps checking for new releases**. |

## Features

- Saved list of apps with installed version, latest GitHub release and an **Update** button when a newer release exists.
  Every time the app is opened it checks all apps; ones with an update get a gold ★ and an
  **Update all** button appears at the top of the list.
- Background checks for updates to every app, GitHub releases and changed link files alike
  (every 3/6/12/24 hours, or manual only), with a notification listing available updates.
- Picks the right release asset for the device's CPU (arm64-v8a, armeabi-v7a, x86, x86_64, universal). An optional per-repo filter (text or regex, e.g. `universal` or `fdroid.*\.apk`) handles repos with several flavours; pre-releases can be included per repo.
- Installs plain `.apk` files and split bundles (`.xapk`, `.apks`) through Android's PackageInstaller.
- Open, uninstall, re-download or edit any saved app; share a link from another app to add it.
- **Update history** (⋮ menu): date, time and version of every install and update of each app.
- Optional GitHub personal access token (Settings) to raise the API rate limit from 60 to 5,000 requests/hour and to reach private repositories.
- Built for TV remotes on Fire TV, Android TV and Google TV: it appears in the TV launcher, the
  selected button is clearly outlined, there's extra margin for TV overscan, and options that need a
  web browser are hidden on TVs without one.
- **Change detection for links:** for direct links and aftv.news codes the app checks whether the
  website has a newer file than the one installed, without downloading it all: it compares the end
  of the website's file (where an APK's signature lives) with the installed copy, falling back to the
  file details saved at the last install and to the website's modification date. Cards show
  *Newer file on the website* / *Same as your installed copy*. Sites that give no file details and
  don't allow partial downloads (some download scripts) can't be checked and say so.
- **Scheduled updates:** choose a time (once, daily, weekly, or every 1/2/4/8/12 hours) and every
  app with *Auto-update* ticked is updated then. On Android 12+ apps that App Downloader installed
  are updated silently (on Android 14+ only when the app isn't in use); anything Android wants you
  to confirm is downloaded and waiting, and the app offers to install it next time you open it.
  Optional *Wi-Fi only*; scheduled runs also wait for enough battery and storage. Settings shows
  the result of the last run. *Update all now* runs the same thing immediately.
- **Safe updates:** downloads from GitHub are checked against the size and checksum GitHub
  publishes; an older version or a different app is never installed without asking; a secure
  (https) link that redirects to an unencrypted one is stopped, and unencrypted links are marked.
- **Back up & restore** (Settings): save the app list and settings to a file and restore them,
  show them as a QR code that another copy of the app scans, or restore from a link / aftv.news code.
- Keeps itself up to date: when opened it checks this repository for a newer version and offers to
  install it. This can be turned off in Settings.

## Install

The latest version is always at this link:

**https://github.com/edrock200/androidappdownloader/releases/latest/download/AppDownloader.apk**

On a Fire TV or Android TV, open the *Downloader* app and enter code **9697744**
(or go to [aftv.news/9697744](https://aftv.news/9697744)). Each
release is also listed on the [Releases page](https://github.com/edrock200/androidappdownloader/releases).
The very first build (1.0.0) is in the [`releases/`](releases/) folder.

Release APKs are signed with a single key, certificate SHA-256
`F9:11:E3:37:FE:B1:1B:96:BD:B6:8F:06:96:F9:BE:CC:48:E6:BB:BD:66:D2:CE:9D:03:25:D6:1B:16:B5:9A:D4`.
If you installed an earlier debug build, uninstall it first, because Android won't update an app
signed with a different key. On first install you will be asked to allow it to install unknown
apps — on Fire TV that is **Settings › My Fire TV › Developer options › Install unknown apps**.

Tip: add `https://github.com/edrock200/androidappdownloader` inside the app and it will keep itself
up to date.

## Using the app

1. Tap **Add** (or **Add an app** on the empty screen).
2. Enter a link, an aftv.news code or a GitHub repository. The field tells you which one it detected.
   - Leave **Name** blank to use the app's own name once it's downloaded.
   - For GitHub repos you can set an **APK file filter** (when a release has several APKs) and
     choose to **include pre-releases**.
3. Tap **Save & install**, or **Save** to install later. Accept Android's install prompt.

Each saved app shows its installed version and, for GitHub repos, the latest release. Cards with an
update get a ★, are highlighted and sorted to the top; tap **Update** to install it, or
**Update all** at the top of the list (which turns into **Stop** while it runs). Use the ⟳ button
in the top bar to check every app now, and the ⋮ menu on a card for *Check for update*,
*Open release page / link*, *Update history*, *Edit*, *Uninstall app* and *Remove from list*.
After restoring a backup on a new device, **Install all** installs the restored apps.

If you installed an app some other way, open **Edit** on its entry and fill in its **package name**
so the installed version is tracked.

### Settings

| Setting | Default | Notes |
|---|---|---|
| Only update links whose file has changed | on | Scheduled updates and *Update all now* skip links the website says are unchanged. Links that can't be checked are still downloaded and compared by version. |
| Scheduled updates | off | *Set date & time* picks when to update all ticked apps, and whether to repeat daily, weekly, or throughout the day every 1, 2, 4, 8 or 12 hours (starting at the chosen time). Saved as soon as you tap *Set*. |
| Check GitHub for updates | Every 12 hours | 3/6/12/24 hours, or *Never* for manual checks only |
| GitHub token | empty | A fine-grained token with no extra permissions is enough for public repos |
| Delete APK after installing | on | Downloaded files are kept in the app's cache otherwise |
| Check for App Downloader updates each time it's opened | ticked | Every time the app is opened it checks for a newer version and asks whether to install it. *Check for app update now* checks immediately. |

### Troubleshooting

| Message | Fix |
|---|---|
| *This link opens a web page, not an APK file* | The page had no `.apk` link. Use *Open in browser* to find the direct link and add that instead. |
| *GitHub rate limit reached* | Add a GitHub token in Settings. |
| *No APK for this device in the latest releases* | The repo has no build for your CPU, or the filter excludes everything. Adjust the filter. |
| *Conflicts with the installed app* | The installed copy is signed with a different key. Uninstall it, then install again. |
| Install prompt never appears on Fire TV | Allow installs under Settings › My Fire TV › Developer options › Install unknown apps. |
| Install prompt never appears on Android TV / Google TV | Allow installs under Settings › Apps › Security & restrictions › Unknown sources (older models: Settings › Device Preferences › Security & restrictions). |
| Scheduled update didn't install anything | Android only allows silent updates on Android 12+ for apps App Downloader installed itself. Otherwise updates are downloaded and waiting. Open the app and choose *Install now*. Fire TV always asks to confirm. |
| Scheduled update ran late | Android may delay background work by a few minutes to save power, and waits for a network connection. |
| No update notifications on a TV | Many TV launchers don't show notifications. Apps with updates are highlighted at the top of the list when you open the app. |

### Backing up and copying to another device

| From | To | How |
|---|---|---|
| Any device | Same or another device | *Save backup file*, then *Restore from file*. On TVs without a file picker the file goes to the Downloads folder. |
| TV or phone | Phone | *Show QR code* on the first device, *Scan QR code* on the phone. Scanning needs a camera and Google Play services. |
| Phone | TV | Save a backup file on the phone, upload it somewhere with a direct download link (e.g. a GitHub Gist's *Raw* link), optionally make an aftv.news code for it, then *Restore from link or aftv.news code* on the TV. TVs have no camera, so they can't scan QR codes. |

Backups contain each app's link, name, filters and auto-update choice, plus your settings. The
GitHub token is only included in files when you tick *Include GitHub token in backup files*, and
never in QR codes. Restoring doesn't install anything; install the apps from the list afterwards.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Release signing

Android only accepts an update if it is signed with the same key as the installed copy, so published
builds should use one stable key. Generate one once:

```sh
keytool -genkeypair -v -keystore release.jks -alias appdownloader -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # value for SIGNING_KEYSTORE_BASE64
```

Then add the repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Only the release job can read them: ordinary
pushes and pull requests build without any secrets, and their test builds are marked
`-dev+<commit>` so they can't be mistaken for a release. The release job refuses to publish an APK
whose signing certificate doesn't match the pinned SHA-256 above, and attaches a `.sha256` file.

### Publishing a release

1. Describe the changes in plain language in `CHANGELOG.md` under a heading such as `## [1.2.0]`.
   This text becomes the release notes and the Discord announcement.
2. On GitHub, open **Actions › Android build › Run workflow**, enter the version (e.g. `1.2.0`)
   and click **Run workflow**. Pushing a tag (`git tag v1.2.0 && git push origin v1.2.0`) does the same.

The version comes from what you enter (or the tag): `v1.2.3` becomes versionName `1.2.3` and versionCode `10203`, so
there is nothing to edit in Gradle. Minor and patch numbers must stay below 100, without leading
zeros, and the version must be newer than every earlier release.

The workflow then runs the tests and lint, stops if the changelog has no section for the version,
builds and signs the app, checks the signing certificate, creates the GitHub release as a draft with
`AppDownloader.apk` and its `.sha256` (the asset name never changes, so the "latest" link above
always works), publishes it, and announces it on Discord. Re-running it for a version that is
already published does nothing; if only the Discord post failed, run the workflow again with the
same version and **announce_only** ticked.

| Repository setting | Kind | Purpose |
|---|---|---|
| `DISCORD_WEBHOOK` | Secret | Discord channel webhook URL for release announcements (skipped if unset) |
| `AFTV_CODE` | Variable (optional) | Overrides the aftv.news code shown in release notes and the announcement. Defaults to `9697744`, which points at the "latest" link. |

Locally, set the same variables (with `SIGNING_KEYSTORE_FILE` pointing at the `.jks`) before running
`./gradlew assembleRelease`.

## How it works

- **aftv.news codes** are resolved by loading `https://go.aftvnews.com/<code>` and reading the
  destination from the redirect page.
- **GitHub** monitoring uses the public REST API (`/repos/{owner}/{repo}/releases`). An update is
  reported when the newest matching release tag differs from the one the app installed, or — for apps
  installed some other way — when its version number is higher than the installed `versionName`.
  The installed app is matched by package name, learned from the first APK the app downloads (or set
  manually under *Edit*).
- Entries are stored as JSON in the app's private storage; background checks use WorkManager.

## Project structure

```
app/src/main/java/io/github/edrock200/appdownloader/
├── App.kt, AppController.kt   # singletons + download/install/update-check coordinator
├── data/                      # AppEntry model, JSON repository, settings, input & version parsing
├── net/                       # OkHttp client, aftv.news resolver, GitHub API, asset selection
├── install/                   # downloader, APK inspector, PackageInstaller session + receiver
├── work/                      # WorkManager update checks and notifications
└── ui/                        # Compose screens, dialogs, ViewModel, theme, TV helpers
.github/workflows/android.yml  # CI build, signed releases, Discord announcement
.github/scripts/               # release-notes.sh, discord-notify.sh
```

See [CLAUDE.md](CLAUDE.md) for a deeper tour and [CONTRIBUTING.md](CONTRIBUTING.md) for how to
contribute. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Downloading APKs and checking releases |
| `REQUEST_INSTALL_PACKAGES` | Installing downloaded APKs |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | Android 12+: updating apps it installed without an extra prompt where the system allows it |
| `REQUEST_DELETE_PACKAGES` | The *Uninstall* menu action |
| `QUERY_ALL_PACKAGES` | Reading the installed version of the apps you add |
| `POST_NOTIFICATIONS` | Update notifications |
| `READ/WRITE_EXTERNAL_STORAGE` (Android 9 and older only) | Saving and reading backups in Downloads on TVs without a file picker |
