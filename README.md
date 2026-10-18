# xbt-wallet

An **XBT / SeedSigner coordinator** — a SeedSigner-first, watch-only Android
overview for **XBT** (the Bitcoin BLAKE2b fork), built for GrapheneOS. Keys
never touch the phone: the app is a coordinator that pairs with an air-gapped
signer over animated QR. It is a watch-only overview of balances and addresses,
not a wallet. MIT licensed. See [PLAN.md](PLAN.md) for the full plan and
roadmap.

Status: **v0.1.0 in progress** — the skeleton, the crypto layer, and the
signer-first restructure:

- **Signers** — the app's unit of organisation. A signer is a name plus a
  type, and today the only type is a watch-only xpub. Signers are a plain list
  (there is no "active signer"), and everything that acts on one happens on
  that signer's own screen.
- **Add signer** — paste an account-level `xpub`/`ypub`/`zpub`, validated
  clean-room (Base58Check, version bytes, compressed key, checksum) and stored
  as a watch-only signer.
- **Addresses viewer** — derived native-segwit receive/change addresses per
  account key, with the derivation index shown and tap-to-copy. It stays in the
  code and is wired under a signer's own screen in the next card.
- **Private Electrum server** — enter host/port, pick SSL or TCP, hit *Test
  connection* and watch the Electrum JSON-RPC handshake in an on-screen log.

All crypto is public-key only: no private key, seed or signing code exists
anywhere in this repository. Balances, history, the receive QR and sending are
still missing, and there is no Tor in this iteration (direct IP connections
only).

## Home screen

Top to bottom: **Server** (private Electrum endpoint), **Add signer**, and
**Signers**.

Under the Server button the screen shows the live server status: the hint
*Connect to your own Electrum server* until an endpoint is saved, then a status
dot with *Connected to `<host>:<port>`* (green) or *Server disconnected* (red) —
and *Checking…* while a check is in flight, so nothing flashes red before the
first reply. A saved endpoint is checked as soon as the screen appears and then
once a minute while it stays on screen; see
[Private Electrum server](#private-electrum-server).

**Add signer** opens the import screen (the real add-signer flow lands in the
next card). **Signers** opens the list of saved signers — each shown by its name
with a *watch-only* type badge — and tapping one opens that signer's own screen.
There is deliberately no "active signer" any more: the old active-signer card,
the **Select active xpub** picker and the standalone **Addresses** button are
gone, and per-signer screens (addresses included) are reached by opening a
signer instead.

## Add signer

`Add signer` on the home screen takes a signer name plus an account-level
extended public key. The key is validated against the public specifications:

- **Base58Check** — alphabet, then the 4-byte double-SHA256 checksum.
- **Version bytes** — `xpub`, `ypub` and `zpub` are all accepted, because all
  three encode the same secp256k1 public key; they differ only in which script
  type the exporter had in mind. Private-key headers (`xprv`) and testnet
  headers (`tpub`) are refused outright.
- **Key data** — must be a 33-byte compressed point that actually lies on the
  curve.

The app is native-segwit single-sig, so whatever you paste is treated as a
**BIP-84-style account key** and every address is derived as P2WPKH (`bc1q…`).
The screen says so, and it warns that a manual import carries no master
fingerprint or derivation path. Signers are stored as a JSON list (id, name,
type, xpub, timestamp) under the `signers` key in `SharedPreferences` — a list
keyed by id from day one, per PLAN.md's multi-signer stance. An install that
predates the signer rename had its xpubs under the old `wallets` key; the store
converts that blob to watch-only signers on first read and writes it forward, so
no saved xpub is lost.

## Addresses

The addresses viewer shows the addresses derived from a signer's account key:
external chain `0/i` under the **Receive** tab and internal chain `1/i` under
**Change**, both native segwit P2WPKH. Each row shows its derivation index; the
first 20 per tab are listed, with *Show 20 more* to extend. Tap a row to copy
the address. There is no gap-limit logic yet — nothing is fetched from the chain,
so the wallet cannot tell which addresses have been used. The viewer stays in
the tree and is wired under each signer's own screen in the next card.

## Crypto layer

Everything under `app/src/main/java/com/xbtwallet/app/crypto/` is Android-free
and unit-tested on the JVM against published vectors:

| File | What it does |
| --- | --- |
| `Base58.kt` | Base58 + Base58Check encode/decode |
| `Hashes.kt`, `Ripemd160.kt` | SHA-256, HMAC-SHA512, RIPEMD-160 (`HASH160`) |
| `Bech32.kt` | bech32 / bech32m (BIP-173, BIP-350) |
| `Secp256k1.kt` | public point add/multiply, compressed (de)serialisation |
| `ExtendedPublicKey.kt` | BIP-32 parsing + public child derivation (CKDpub) |
| `SegwitAddress.kt` | `bc1q…` address building |
| `XpubImport.kt` | validation seam + the inline error wording |

There is no function in that package that consumes a private key. The BIP-84
test vector (`zpub6rFR7y4Q…` → `bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu`) is
asserted in `ExtendedPublicKeyTest`, along with the BIP-173 bech32 vectors and
the published RIPEMD-160 vectors.

```bash
./gradlew clean assembleDebug testDebugUnitTest   # 105 unit tests, all green
```

## Private Electrum server

`Server` on the home screen opens the server screen. It stores one endpoint
(host, port, SSL/TCP) in `SharedPreferences` and tests it by connecting over
TLS or plain TCP and running `server.version` (protocol 1.4), `server.features`
and `server.banner` — every request and reply, plus any error, is appended to
the connection log. The client lives in
`app/src/main/java/com/xbtwallet/app/electrum/` and is free of Android imports
so it can be unit-tested on the JVM against a mock Electrum responder:

```bash
./gradlew testDebugUnitTest   # 19 tests: client handshake, ping, JSON, failure modes
```

The home screen reuses the same client for its heartbeat: `ElectrumClient.ping()`
connects, sends one `server.ping` with no parameters and closes — the cheap
probe, and one that writes nothing to the connection log. The home screen shows
the result beside the Server button. The loop only runs while the home screen is
on display (leaving it, or the process going to the background, cancels it) and
re-checks immediately when the saved endpoint changes: no background service, no
wake lock, no new permission.

Limitations of this iteration: no Tor, no certificate pinning (an untrusted or
self-signed server certificate is rejected and reported in the log rather than
accepted), and no xpub data is fetched yet — the connection is all it does.

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
