# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.8.0] - 2026-11-12

### Added
- "Install all" after restoring a backup on a new device, and a "Stop" button while updating several apps.
- Settings shows the result of the last scheduled update, and has "Wi-Fi only" for scheduled downloads.
- Downloads from GitHub are checked against the published checksum; private GitHub repositories now download with a token.
- Restoring a backup shows exactly which apps and settings it will add.

### Changed
- Background updates on Android 14+ wait until the app isn't in use, so videos aren't interrupted.
- Much easier with a TV remote: the highlight no longer jumps away when you press a button, long lists scroll, and colours suit TVs better.
- Settings save as soon as you change them; restored and saved backups are handled more safely.
- Clearer messages instead of technical errors, and the app explains why a change can't be detected.
- Old Fire TV devices can now download from sites that use Let's Encrypt certificates.
- The app is about a third of its previous size.

### Fixed
- No more false "update" stars when the internet is down, or when your installed copy is newer than the website's.
- An install prompt is no longer lost if you leave the app during a download; you can show it again.
- Cancel works during "Update all", and old install attempts no longer interfere with new ones.
- A full disk no longer crashes the app, and a damaged saved list is recovered instead of wiped.
- Withdrawn GitHub releases are no longer offered as updates, and failed automatic updates aren't retried every night.
- Scheduled updates restart if they were lost, stay at your local time after travelling, don't repeat the same notification every hour, and retry when the network was down.
- An expired GitHub token no longer stops update checks.
- Many smaller fixes to app bundles, backups, links and notifications.

## [1.7.1] - 2026-11-08

### Fixed
- New versions behind plain links and aftv.news codes are now detected for apps you installed some
  other way or before version 1.3.0. Before, those always showed "can't tell". App Downloader now
  compares the website's file with the copy installed on your device, downloading only a small part
  of it, and otherwise uses the date the website's file was changed.
- Links that open a web page with a download link on it are now checked too.
- When a change can't be detected, the app now says why.

## [1.7.0] - 2026-11-06

### Added
- "Update history" in each app's ⋮ menu shows the date and time of every install and update and
  which version it went to, for example "Updated 1.2.0 → 1.3.0". Scheduled updates are marked
  "automatic". Updates made outside App Downloader (for example by the Play Store) are noticed
  when you open the app. Apps added before this version start with the version currently installed.

## [1.6.0] - 2026-11-05

### Added
- Opening App Downloader now checks every app in your list for a new version.
- Apps with an update get a gold star, and their button changes to "Update".
- An "Update all" button appears at the top of the list when updates are found. It updates
  every starred app, one after another.

## [1.5.0] - 2026-11-05

### Added
- Back up & restore in Settings: save your list of apps and your settings to a file, and restore
  them later or on another device. Choose whether to add to your current list or replace it.
- Copy everything to another device with a QR code: "Show QR code" on one device, then
  "Scan QR code" on a phone. Your GitHub token is never put in a QR code.
- "Restore from link or aftv.news code" loads a backup file from the internet, which is handy on
  TVs: type the code with the remote.
- On TVs without a file picker, backups are saved to and restored from the Downloads folder.

## [1.4.3] - 2026-11-05

### Changed
- "Check for App Downloader updates each time it's opened" is now a tick box in Settings (on by
  default). When ticked, every time you open App Downloader it checks for a new version and asks
  whether you want to install it.

## [1.4.2] - 2026-11-05

### Fixed
- App Downloader now checks for its own updates every time you open it, including when it was
  still running in the background (the usual case on TVs). Before, it only checked after a restart.
  It also retries if the network isn't ready yet, for example right after the TV wakes up.

## [1.4.1] - 2026-11-05

### Changed
- The schedule now asks for a day of the week ("Today", "Tomorrow", "Saturday"…) instead of a
  calendar date, and only for one-off and weekly schedules. Daily and hourly schedules just need a time.

## [1.4.0] - 2026-11-05

### Added
- Scheduled updates can now also repeat throughout the day: every 1, 2, 4, 8 or 12 hours,
  starting from the date and time you pick.

## [1.3.0] - 2026-11-05

### Added
- Plain links and aftv.news codes now show whether the file on the website has changed since
  you installed it ("Changed on the website" / "No change"), checked without downloading it.
  The ⟳ button and "Check for update" now cover these apps too, and you get notified about changes.
- New setting "Only update links whose file has changed" (on by default) for scheduled updates and
  "Update all now". If a website doesn't say whether its file changed, it is still downloaded and
  compared, so no update is missed.

## [1.2.0] - 2026-11-05

### Added
- Scheduled updates: pick a date and time in Settings (once, every day or every week) and
  App Downloader updates all your apps at that time.
- Each app now has an "Auto-update" tick box, so you choose which apps are included.
- "Update all now" in Settings updates every ticked app straight away.
- If Android needs you to confirm an install, the update is downloaded in advance and waits
  for you. Next time you open App Downloader it offers to install it with one click.

## [1.1.0] - 2026-11-03

### Added
- App Downloader now checks for its own updates when you open it and offers to install them.
  You can turn this off in Settings, or check right away with "Check for app update now".
- Settings shows which version of App Downloader you have.

### Changed
- Much easier to use with a TV remote: the selected button is outlined and slightly enlarged,
  and the app you're on is outlined too.
- Extra space around the edges on TVs, so nothing gets cut off.
- Clearer help for allowing installs on Android TV and Google TV, not just Fire TV.
- Options that need a web browser are hidden on TVs that don't have one.

## [1.0.0] - 2026-11-03

### Added
- Save and install APKs from direct URLs, aftv.news codes and GitHub repositories.
- Web-page links are scanned for an `.apk` link when they don't point at an APK directly.
- GitHub release monitoring with ABI-aware asset selection, optional asset filter and pre-release
  opt-in per repository.
- Background update checks (3/6/12/24 h or manual) with notifications.
- Installation of plain APKs and split bundles (`.xapk`, `.apks`) via PackageInstaller.
- Open, uninstall, re-download, edit and remove actions per app; share-to-add from other apps.
- Settings for GitHub token, check interval and deleting APKs after install.
- Fire TV / Android TV support (leanback launcher, D-pad navigation).
- CI workflow that tests, builds and publishes APKs on version tags.
