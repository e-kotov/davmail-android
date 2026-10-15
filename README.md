# DavMail for Android

[DavMail](https://github.com/mguessan/davmail) as an Android app: a CalDAV gateway to Microsoft
Exchange (EWS) that runs on the phone itself, for use with [DAVx⁵](https://www.davx5.com/) or
another CalDAV client. It embeds the release of the fork
[e-kotov/davmail](https://github.com/e-kotov/davmail) pinned in `gradle/davmail.properties`
(URL and SHA-256), including that fork's `davmail.folderAliases` patch.

Only the CalDAV listener runs. It binds to `127.0.0.1` unless `davmail.allowRemote=true`, so
other devices cannot reach it. The Exchange user name and password are entered in the CalDAV
client and never stored by this app. Logs stay at WARN unless the config says otherwise, so
event contents are not written.

## Use

1. Install the APK from [Releases](https://github.com/e-kotov/davmail-android/releases) (check it
   against `SHA256SUMS`).
2. Open DavMail. Paste or import a DavMail properties file: at least `davmail.mode=EWS`,
   `davmail.url=https://<server>/EWS/Exchange.asmx` and `davmail.caldavPort`. Then tap
   **Save and start**.
3. Tap **Allow running in background** to exempt the app from battery optimisation. On Samsung
   phones, also add it to *Never sleeping apps*.
4. In DAVx⁵, add an account with a URL and user name. Use the base URL
   `http://127.0.0.1:<caldavPort>/principals/users/<email>/` and your Exchange login.

To hide your main calendar, add `davmail.calendarsExclude=@default` to the config,
then **Save and start** and refresh the calendar list in DAVx⁵. To show only selected
calendars instead, use `davmail.calendarsInclude=alias_a,alias_b`. Both lists may be set:
include selects the starting set, then exclude removes entries (exclusion wins).
An absent include list means all calendars; an explicitly empty include list means none.
Names are exact top-level calendar or alias names; wildcards are not supported.
The old `davmail.hideDefaultCalendar=true` setting remains compatible. These settings
change only this gateway's visibility and never delete Exchange data.

The gateway runs as a foreground service with a persistent notification. It restarts after a
reboot or an app update if it was running.

## Import a copy or link a config file

- **Import a copy…** reads the selected file once into the editor and detaches any existing
  link. Review it and press **Save and start**. Later source-file edits do not affect this copy.
- **Link file and watch changes…** asks Android for persistent read access to one document,
  starts the gateway, and checks its contents every 5 seconds while running. A changed file
  must match across two polls, so stable changes normally apply within 5–10 seconds. The
  gateway restarts only when the contents change; no provider change notifications are needed.
- Linked mode shows the last applied config read-only. Edit the original file instead. **Use
  saved copy instead** detaches the link, releases document access, and makes the cached config
  editable. The app never writes back to the linked document.
- Invalid, oversized (over 256 KiB), or unreadable files keep the last working config and show
  a status message. A failed gateway restart rolls back to the previous config. The last applied
  config is cached privately for restart/boot when the linked document is unavailable. Endpoint
  reachability still depends on the Exchange server and credentials supplied by the client.
- A stopped gateway does not watch files. Starting it again checks the linked file. If Android
  pauses the app, checks resume when the process runs again; normal battery exemptions still apply.

For chezmoi in Termux, keep the source properties in the Termux dotfiles repository, deploy it
with `chezmoi apply`, and copy it to a stable shared-storage file such as
`~/storage/shared/Download/davmail.properties` (Termux storage access must already be enabled).
Select that file once with **Link file and watch changes…**. Subsequent edits to that same file
are picked up automatically while the gateway runs; you do not need another import or restart.
Prefer writing the complete file promptly. If a provider invalidates the document URI after a
file is moved, deleted or replaced, select it again. Android documents this limitation of
[persistent document access](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions).
The app does not need root or broad storage permissions.

## How it works on Android

- `DavGateway.main` touches `java.awt`, so the app starts only `CaldavServer`
  (`app/src/main/java/.../Gateway.java`).
- Android's boot class path contains an old `org.apache.http`, which shadows HttpClient 4.5. The
  build relocates `org.apache.http`, `org.apache.commons.logging` and `org.apache.commons.codec`
  with jarjar (`gradle/jarjar-rules.txt`).
- DavMail's bundled `log4j.properties` is dropped because reload4j's configurator needs
  `java.beans`. DavMail sets up its file appender in code instead.

## Build

JDK 17 or newer and an Android SDK with platform 35:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Checks run on GitHub Actions via `.github/workflows/checks.yml` (Robolectric tests of Android
file grants/config/UI and reload recovery, lint, and debug compilation). No Exchange account is
used in tests. Release builds repeat tests and lint before packaging/signing.

Releases are built by `.github/workflows/release.yml` when a `vX.Y.Z` tag is pushed. The APK is
signed with the key stored in the repository secrets `DAVMAIL_ANDROID_KEYSTORE_B64` and
`DAVMAIL_ANDROID_STORE_PASSWORD` (key alias `davmail-android`).

## License

GPL-2.0, as DavMail (see `LICENSE`).
