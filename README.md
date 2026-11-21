# Push Me Not

Push Me Not is an Android app for filtering notifications with local rules and keeping a searchable history.

## Contents

- [Install](#install)
- [Getting started](#getting-started)
- [Notification history](#notification-history)
- [Development](#development)
- [Release builds](#release-builds)

## Install

Push Me Not 0.2.9 supports Android 12 and later. Version 0.2.8 requires Android 14 or later.

1. Open [GitHub Releases](https://github.com/VitalyOstanin/pushmenot/releases/latest).
2. Download the signed `push-me-not-<version>.apk` from the release assets.
3. Open the APK and follow Android's installation prompt. If prompted, allow the
   browser or file manager to install apps from that source.
4. Open Push Me Not and grant notification access from Settings.

The release assets include `SHA256SUMS.txt` so you can verify the APK you
downloaded. Install subsequent releases over the previous release to keep your
rules and history. Debug builds use a different signing certificate. Export
your rules and save a rules backup before switching from a debug build to the
release. Android requires removing the debug build first.

In **Settings → Updates**, tap **Check for updates**. When a newer stable
release is available, select **Install update** to download, verify and install
the signed APK from within the app. Android may ask you to authorize Push Me Not
as an installation source or confirm the update. You can also open the GitHub
release page and install the APK manually.

## Getting started

Open the journal from Push Me Not or from its shortcut in the notification shade. Under **In the notification shade**, review a suggested rule for a visible notification. The draft uses an exact title or text match and is scoped to the app and channel when available. If the app is not yet filtered, you can enable filtering and save the rule together. You can also create a rule from a saved journal entry. Rules can distinguish a promotion from an order or payment update posted to the same channel.

Rules apply to notifications from apps with filtering enabled. All conditions in a rule must match. When several rules match, Push Me Not uses this priority order:

1. App-specific Keep
2. Global Keep
3. App-specific Hide
4. Global Hide

Open **Rules → Help** to see how matching and priority work.

At the same priority, the first match in the saved order wins. When no rule matches, the channel setting takes precedence over the app setting. The default is to keep the notification. To hide unmatched notifications, open the app’s rules and select **Notifications without a matching rule**. Protected notifications and notifications whose content is unavailable are kept. For example, a Keep rule for “Order ready for pickup” can preserve pickup updates in a channel covered by a broader Hide rule.

Android delivers notifications to the listener after posting them. A banner, sound, or vibration may occur before the app dismisses a matching notification. SMS notification cards can be filtered when the messaging app provides the relevant fields. Messages remain in the messaging app.

## Notification history

Each card starts with the sending app’s name and icon when they are available.
A readable Android channel name appears below the app name when available.
Open **Notification details** to see the exact channel ID used by rules.
Each entry shows the recorded decision and a separate assessment against the
current rules. If the assessment depends on missing content or past notification
frequency, the journal explains that it cannot determine the result.
**Open notification** uses the original Android action while its token is
available. Otherwise, **Open app** launches the sending app if it has a
launcher activity.

Use Settings to manage history size, content saving, notification access, background
settings, app language, and which apps are filtered. The journal records notifications
that Android delivers, including notifications from apps without filtering and those
that are dismissed by filtering. In **Settings → Export history**, choose an app
and a time range to save a JSON file. The default export includes app identifiers,
receipt times rounded down to the hour in UTC, and filtering results. Titles,
text, and saved images are individually opt-in and require confirmation before
opening the Android file picker. The app masks recognizable email addresses,
links, long numbers, and labeled codes in exported text. Review the file before
sharing it because text and images can still contain personal data.

Content saving is on by default. Turning it off removes saved content after
confirmation. Text rules still evaluate incoming notifications for selected
apps. Rules can be imported or exported from the Rules tab. Export can save the file
or hand it to the Android Sharesheet. Open a .pushmenot rules file
from another Android app to review the import. The import can add new rules or
replace the current ruleset after confirmation. New exports include the list of
apps with filtering enabled, including apps without app-specific rules. Import
can enable filtering for those apps while preserving existing app selections.
Filter settings for apps that are missing take effect when the app is installed.

The Rules tab also offers Version history. Every saved change creates a local
snapshot. You can compare a snapshot with the active rules and restore it. A
backup file contains the active rules, app and channel modes, and all snapshots. The backup excludes
notification history and system permissions.

Saving a ruleset also checks notifications that are still visible. Matching
notifications can be dismissed without counting them as new arrivals. Frequency
limits continue to apply as new notifications arrive.

The journal shows how many received notifications were hidden today and this
week. These counts start when the app starts tracking and survive a journal
clear. A quiet notification can open the journal directly when Android permits
the app to show notifications.

The app saves public text fields, individual messages, sender names, and action
labels supplied by Android. It can store a thumbnail from a delivered BigPicture
bitmap or resource. Thumbnails are at most 512 pixels on the longest side and use
up to 32 KiB each. The newest 2,048 thumbnails are kept, up to 64 MiB in total.
Thumbnail data is stored locally. The journal currently displays text and metadata.
Android can redact content or provide it only through an app-specific layout.
Action intents and external image URIs are omitted.

## Development

The app supports English and Russian. On Android 12, it follows the device
language. On Android 13 and later, Android settings can set the app language
independently. Project documentation uses English and records architectural
decisions as numbered ADRs.

From a workstation with Podman and an x86-64 Linux host, run:

- `tools/build.sh` to build the debug APK.
- `tools/test.sh` to run JVM tests.
- `tools/e2e.sh` to build the app and run instrumentation tests on an isolated
  Android 14 emulator. Use `tools/e2e.sh 31` for Android 12 or
  `tools/e2e.sh 33` for Android 13. Emulator runs require access to `/dev/kvm`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The emulator report is written to `app/build/reports/androidTests/emulator.txt`.

See the [ADR index](docs/ADR/README.md) for proposed architectural decisions.

## Release builds

CI uses a pinned checkout and Java setup, Gradle dependency verification, and
builds with the Gradle task cache disabled. A version tag matching the app
version builds and signs a release APK, verifies the signing certificate, and
uploads it with its checksum to a draft GitHub Release. Publish the draft after
testing the signed APK. Continue using the same signing key for updates.
