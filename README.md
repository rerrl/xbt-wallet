# xbt-wallet

A SeedSigner-first, watch-only Android wallet for **XBT** (the Bitcoin BLAKE2b
fork), built for GrapheneOS. Keys never touch the phone: the app is a
coordinator that pairs with an air-gapped signer over animated QR.
MIT licensed. See [PLAN.md](PLAN.md) for the full plan and roadmap.

Status: **scaffold v0 + first feature** — project skeleton, a home screen, and a
**private Electrum server** screen: enter host/port, pick SSL or TCP, hit *Test
connection* and watch the Electrum JSON-RPC handshake in an on-screen log.
Keys, crypto and the database are still missing, and there is no Tor in this
iteration (direct IP connections only).

## Private Electrum server

`Server` on the home screen opens the server screen. It stores one endpoint
(host, port, SSL/TCP) in `SharedPreferences` and tests it by connecting over
TLS or plain TCP and running `server.version` (protocol 1.4), `server.features`
and `server.banner` — every request and reply, plus any error, is appended to
the connection log. The client lives in
`app/src/main/java/com/xbtwallet/app/electrum/` and is free of Android imports
so it can be unit-tested on the JVM against a mock Electrum responder:

```bash
./gradlew testDebugUnitTest   # 15 tests: client handshake, JSON, failure modes
```

Limitations of this iteration: no Tor, no certificate pinning (an untrusted or
self-signed server certificate is rejected and reported in the log rather than
accepted), and no wallet data is fetched yet — the connection is all it does.

## Run in dev mode

Requirements:

- **JDK 17** (required — AGP 8.13 / Kotlin 2.2 do not accept JDK 8/11; JDK 21 is
  not what this project is built and tested against). On this machine:
  `/home/andrew/android-tools/jdk-17.0.2`.
- **Android SDK** with platform **Android 36** and build-tools **36.0.0**. On
  this machine: `/home/andrew/android-tools/android-sdk`.
- `adb` for install (ships with platform-tools).

### Open in Android Studio

1. `File > Open…` and select this directory (`/home/andrew/xbt-wallet`).
2. Let Gradle sync. If it asks for a JDK, point it at **JDK 17**
   (`/home/andrew/android-tools/jdk-17.0.2`), and let it use the project's
   `local.properties` / your Android SDK for the SDK.
3. Pick the `app` configuration and Run.

### Build and install from the CLI

```bash
cd /home/andrew/xbt-wallet

# JDK 17 and the Android SDK this project builds against.
export JAVA_HOME=/home/andrew/android-tools/jdk-17.0.2
export ANDROID_HOME=/home/andrew/android-tools/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# Point Gradle at the SDK (once; local.properties is gitignored).
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build the debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Install on a connected device.
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Notes:

- If `ANDROID_HOME` is already exported in your shell and `local.properties`
  exists, `./gradlew assembleDebug` is the only command you need.
- The wrapper pins **Gradle 8.13**; the first run downloads it.
- `./gradlew clean assembleDebug` reproduces the build from scratch (verified:
  `BUILD SUCCESSFUL`).
- The APK is `com.xbtwallet.app`, `minSdk 26`, `targetSdk 36`, label
  `xbt-wallet`.
