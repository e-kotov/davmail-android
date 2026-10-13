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

The gateway runs as a foreground service with a persistent notification. It restarts after a
reboot or an app update if it was running.

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
./gradlew assembleDebug
```

Releases are built by `.github/workflows/release.yml` when a `vX.Y.Z` tag is pushed. The APK is
signed with the key stored in the repository secrets `DAVMAIL_ANDROID_KEYSTORE_B64` and
`DAVMAIL_ANDROID_STORE_PASSWORD` (key alias `davmail-android`).

## License

GPL-2.0, as DavMail (see `LICENSE`).
