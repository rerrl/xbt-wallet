# xbt-wallet

**Your keys never touch your phone.** xbt-wallet is an air-gapped coordinator for **XBT** (the Bitcoin BLAKE2b fork) that pairs with your SeedSigner over animated QR codes. The app watches your balances, builds your transactions, and hands them to the signer to sign — the seed stays on the signer, where it belongs. Built for GrapheneOS. MIT licensed. Zero Google dependencies.

## Why xbt-wallet

- **Send, fully air-gapped.** Pick the exact coins to spend, finalize the transaction on the phone, and sign it on your SeedSigner through animated QR codes — then broadcast from the app. Nothing ever asks you for a seed, because the app has nowhere to put one.
- **Replay protected.** Every transaction is signed with SIGHASH_ALL + SIGHASH_UNIFIED, and the app refuses to broadcast anything signed without it. Your coins cannot be replayed onto another chain.
- **Your own server, your own privacy.** The app talks only to *your* Electrum server. `.onion` endpoints are reached through the bundled Tor — no Orbot, no third-party servers, no price feeds phoning home.
- **Made for SeedSigner.** The whole flow is designed around the device you already carry: import by scanning its animated xpub export, sign by scanning its animated transaction QR. Keystone and Jade speak the same language and work the same way.
- **No Google, ever.** No Play Services, no Firebase, no ML Kit — built for GrapheneOS and headed for F-Droid.

## How a send works

1. **Choose your coins.** On the UTXO screen, tap the outputs you want to spend and hit *Send selected*.
2. **Build the transaction.** Recipient (type it or scan a QR), amount (with a fee-aware *Max*), fee rate, and an optional label — then *Finalize transaction for signing*.
3. **Sign on the SeedSigner.** The phone shows the unsigned transaction as a big animated QR. On the SeedSigner: *Scan → Sign*, review the details — change comes back to a verified address — and let it show the signed transaction QR.
4. **Scan it back.** Hit *Scan QR* in the app and read the signed transaction off the SeedSigner's screen.
5. **Broadcast.** One button sends it to your Electrum server. It shows up in your history as unconfirmed until the next block.

Watching is just as simple: add a wallet by scanning the SeedSigner's animated xpub export (*Export Xpub → single-sig → native segwit → Animated*), and the app keeps its balance, UTXOs, addresses and transaction history in sync from your server.

## Status and roadmap

v0.1.0 in progress. Air-gapped watch + send with a SeedSigner (or any BC-UR signing device) works end to end against your own Electrum server. The two fiat figures on the wallet screen are clearly-marked placeholders — the app requests no price feed. On the roadmap: a hot wallet for small everyday spends, and more.

## Appendix

The sections below are the detailed, technical documentation: screen-by-screen behaviour, the navigation model, key validation, and the chain-sync design. For the full plan see [PLAN.md](PLAN.md).

## Home screen

Top to bottom: **Server** (private Electrum endpoint), **Add wallet**, and
**Wallets**.

Under the Server button the screen shows the live server status: the hint
*Connect to your own Electrum server* until an endpoint is saved, then a status
dot with *Connected to `<host>:<port>`* (green) or *Server disconnected* (red) —
and *Checking…* while a check is in flight, so nothing flashes red before the
first reply. A saved endpoint is checked as soon as the screen appears and then
once a minute while it stays on screen; see
[Private Electrum server](#private-electrum-server).

**Add wallet** opens the Add-wallet chooser (three rows: *SeedSigner*, *Other
signing device*, *Watch-only xpub*). **Wallets** opens the list of saved wallets —
each shown by its name, its tag (*watch-only*, *seedsigner* or *signing device*)
and a head…tail snippet of its key — and tapping one opens that wallet's own
screen. Each row has a **⋮** menu whose *Remove wallet* asks for confirmation
first. There is deliberately no "active wallet": the old active-signer card, the
**Select active xpub** picker and the standalone **Addresses** button are gone,
and per-wallet screens (addresses included) are reached by opening a wallet
instead.

## Navigation

Screens are an enum and a `when` — no navigation library (PLAN.md §2.4) — over a
small **back stack** that makes *back* mean exactly one thing: one level up.
Navigating pushes a destination onto the stack; Android's back button pops it.

- HOME → **Add wallet** → the chooser → a form → back → the chooser → back → HOME.
- A wallet's screen → **UTXOs** (or **Addresses**) → back → that wallet's screen.

Each stack entry carries its own argument, so a per-wallet screen remembers
*which* wallet it is showing: opening UTXOs from one wallet and backing out can
never leave the app pointing at another. The two animated rows of the chooser open
the *same* scanner, so the entry also carries the **origin** the user declared by
tapping a row — that is what the saved wallet is stamped with, and a rotation must
not lose it. The stack survives a rotation, and back at HOME is left to the system
(it leaves the app) rather than swallowed. Saving a new wallet resets the walk to
HOME → Wallets, so back from the list does not return into a form that has already
been submitted.

## Add wallet

`Add wallet` is a chooser, not a form — a plain-language intro saying what a wallet
*is* on this phone ("the phone holds public keys only … keys never leave your
signer"), then three rows:

- **SeedSigner** (filled) — the primary path. Reads the SeedSigner's own **animated
  BC-UR `crypto-account` export**: *Export Xpub → single-sig → native segwit →
  Animated QR*, then scan it here. The wallet is recorded with the **seedsigner**
  origin and wears the orange tag.
- **Other signing device** (tonal) — the same animated BC-UR payload, read off a
  Keystone, a Jade or anything else that speaks it. Recorded with the
  **signing-device** origin, so it wears the blue *signing device* tag rather than
  claiming to be a SeedSigner.
- **Watch-only xpub** (outlined) — a plain text QR from any coordinator: a bare
  `xpub`/`ypub`/`zpub`, a `bitcoin:` URI, or a `[fingerprint/path]xpub` expression.
  A Coldcard's text export lands here, which is why Coldcard is deliberately *not*
  promised on the two animated rows. Recorded with the **text-qr** origin and
  wears the grey *watch-only* tag.

Why three rows rather than one clever scanner: the animated path needs the camera
held on a moving code until the export is whole, and saying so up front is
friendlier than a generic scanner that guesses. The two animated rows share a
scanner but not a meaning — only the row the user tapped says whether the device
in hand is a SeedSigner or something else, so that tap is what the record keeps.

The scanned key is validated against the public specifications:

- **Base58Check** — alphabet, then the 4-byte double-SHA256 checksum.
- **Version bytes** — `xpub`, `ypub` and `zpub` are all accepted, because all
  three encode the same secp256k1 public key; they differ only in which script
  type the exporter had in mind. Private-key headers (`xprv`) and testnet
  headers (`tpub`) are refused outright.
- **Key data** — must be a 33-byte compressed point that actually lies on the
  curve.

The app is native-segwit single-sig, so whatever a QR carries is treated as a
**BIP-84-style account key** and every address is derived as P2WPKH (`bc1q…`).
The text path is **QR-only** — there is no paste field, because the QR is the
air-gapped channel and the only one that can carry an account's metadata — and
the *Scan QR* button opens a small camera **panel** right there on the import
screen, not a full-screen capture activity, and always in portrait like the rest
of the app. The payload is routed — a bare key, a `bitcoin:` URI with its scheme
and parameters stripped, or a `[fingerprint/path]xpub` expression — through the
same validator, so the camera can never accept what a keyboard would have refused.
The camera is ZXing (`zxing-android-embedded`, Apache-2.0, no Google dependency)
driving the library's `DecoratedBarcodeView` inside the app's own activity. The
camera permission is asked for by this app, not the library; a refusal says so and
leaves the screen usable.

The animated rows use the same name-and-save shape but a different scanner: it
keeps decoding **fountain-coded BC-UR frames** until the export is whole, showing
how much of it has arrived, and refuses a text QR in its own words rather than
complaining about Base58.

Wallets are stored as a JSON list (id, name, type, xpub, timestamp, import origin)
under the `signers` key in `SharedPreferences` — a list keyed by id from day one,
per PLAN.md's multi-wallet stance. The **origin** each wallet records is the row
the user tapped: `text-qr`, the animated SeedSigner path, or the other-device path.
`signing-device-ur` is the wire value that predates the split and now means *the
SeedSigner path* — every record an earlier build wrote with it was one, so an
existing wallet keeps the tag it is showing. An install that predates the earlier
rename had its xpubs under the old `wallets` key; the store converts that blob to
watch-only wallets on first read and writes it forward, so no saved xpub is lost.

## Wallets

The **Wallets** screen is the app's inventory. Every saved wallet is listed
oldest first, by the name the user gave it, with its tag (*watch-only* in grey,
*seedsigner* in the theme's orange, *signing device* in a calm blue) and a
snippet of its key — the first 10 and last 6 characters, elided in the middle, so
two keys are told apart at a glance without an xpub filling the row. Tapping a
row opens that wallet's own screen. When nothing is saved yet the screen says so
and offers an **Add wallet** shortcut, so an empty list is never a dead end.

Each row has a **⋮** menu with *Remove wallet*, which opens a confirm dialog
(name plus the fact that only the public key is stored here, so nothing is
deleted on the signer itself). Deleting writes straight through the store and the
list drops the row; if a screen was still pointing at the removed wallet, its
stack entry stops resolving and the app restarts at Home rather than
crashing. The screen keeps only the two bits of interaction state that belong to
it (which row's menu is open, which wallet is awaiting confirmation); the store
is written on the screen that owns it.

## Wallet screen

Tapping a wallet opens its own screen — the place every per-wallet action lives,
and the first screen that talks to the chain. Top to bottom: the wallet's name,
the sync spinner while a rescan runs, and its tag, then its **balance**, the four
actions, the account key, and the account's **transaction history**.

**Balance** is this account's confirmed *plus* unconfirmed satoshis, read from
your own Electrum server. The scan runs off the UI thread and the card shows
what is happening at each stage: *Loading balance…* with a running
"checked N addresses" count while the scan is in flight, the sats figure
(`1,234,567 sats`) with the coin amount (`0.01234567 XBT`) when it lands, or an
error with a **Retry** when the server could not be reached. With no server
saved at all there is no error to show — the card says *Connect a server* and
explains that balances come from the user's own server and nobody else's.

Under the balance sit **UTXOs** and **Addresses** (each opens its own screen,
see below) and **Send**/**Receive**, with Send reached through **UTXOs** →
*Send selected* rather than the wallet-screen button: sending is the air-gapped
QR flow, and a wallet that offers a Send button that cannot send is a wallet
that lies.

Two fiat lines dress the same balance in hardcoded placeholder rates, each
labelled as such: the XBT figure at **$420.69/XBT** and, because BTC and XBT
share the satoshi unit, the same balance as BTC at **$81,000/BTC**. Both live in
one file (`PlaceholderPrices.kt`) with comments saying exactly what they are.
There is no price API, no HTTP client and no new permission anywhere near this —
the only traffic the app makes is Electrum RPCs to the server the user entered.

At the foot of the screen, under the account key, a **Transactions** section
shows every transaction that touched any scanned address of the account — both
chains — deduped by txid and ordered **unconfirmed first, then newest block
first**, capped at the latest 50. Each row is the wallet's **net effect** on the
left and a confirmation badge on the right, with the truncated txid underneath:
`+0.00012345 XBT in` in green when value arrived, `-0.00012345 XBT out` in red
when it left, and a neutral amount when the two sides cancel (a self-send).
The badge is red *Unconfirmed*, amber `N confs` for one to five, and green
`N confs` from six, capped at `100+ confs`.

The net amount is computed from the transactions themselves: the scan's
`get_history` answers only say a tx touched an address, so the row fetches each
displayed transaction (`blockchain.transaction.get`, on the same connection) and
sums what it pays our scriptPubKeys minus what it spends of our own outputs. That
is what makes change correct: a send that returns coins to one of our change
addresses shows the change as incoming and the coin it came from as outgoing, so
the row reads `-(payment + fee)` rather than pretending the change is a payment.
Confirmations come from `blockchain.headers.subscribe` (the tip height) and the
height `get_history` reported.

Rows are static: no tap-to-copy and no detail view.

## Chain queries

Under the wallet screens is a small chain layer (`app/src/main/java/com/xbtwallet/app/chain/`),
still Android-free:

- **Scripthash** — Electrum indexes scripts by `sha256(scriptPubKey)` **reversed**,
  as lowercase hex. That reversal is the classic "everything looks unused" bug,
  so it is pinned by a vector test against BIP-84's first receiving address.
- **Gap-limit scan** — the account key's receive (`0/i`) and change (`1/i`)
  chains are walked in batches of 20, asking `blockchain.scripthash.get_history`
  about each address and stopping after the first batch with no used address in
  it (BIP-44's gap limit). The balance and the UTXO list are then read from the
  *used* addresses only.
- **Balance** is the sum of `blockchain.scripthash.get_balance` over those used
  addresses — one source of truth, deliberately *not* the sum of the UTXO list,
  so the headline number and the list can never disagree about themselves.

The three scripthash RPCs (`get_history`, `get_balance`, `listunspent`) run over
**one connection** per scan (`ElectrumClient.withChainSession`), together with the
transaction fetches and the tip lookup the history rows need: an account scan is
dozens of requests, and a connect/TLS/close per address would make an ordinary
balance look like a port scan to the user's own server. Each wallet's scan state
lives in an app-scoped store for the app's lifetime, so entering a wallet starts a
scan only when none is running and a scan in flight survives navigating between
that wallet's screens; re-entering within two minutes reuses the last completed
scan instead of re-running it (the manual Refresh always forces one).

## UTXOs

The UTXO screen lists what the scan found: one monospaced row per output, its
amount in sats on top and its provenance underneath — `txid:vout` with the
middle elided, the chain and address index it sits at, and the height it
confirmed at (or *unconfirmed*). An empty account says so.

Multi-select with a *Send selected* bar is the entry to the send flow; freezing
is still absent.

## Addresses

The addresses viewer shows the addresses derived from a wallet's account key:
external chain `0/i` under the **Receive** tab and internal chain `1/i` under
**Change**, both native segwit P2WPKH. Each row shows its derivation index and,
now, what the scan learned about it — *used*, *unused*, or the accent-marked
*first unused*, which is the address the next receive would hand out. Tap a row
to copy it.

The list is derived locally, so it appears instantly in every scan phase — even
with no server configured. It starts with one page of 20 addresses per chain, and
**Show 20 more** derives the next page on demand; a scan that has visited further
than the current page extends the list so no scanned row is truncated. Rows the
scan has answered about carry its verdict — *used*, *unused*, or the
accent-marked *first unused*, the address the next receive would hand out; rows
past the range the scan visited are marked *not scanned* rather than *unused*,
because the app genuinely has not asked about them and a receive screen that
guesses is how an address with history gets handed out twice. The scan annotates
rows in place; it never gates the list.

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
the published RIPEMD-160 vectors. The chain layer built on it
(`com.xbtwallet.app.chain/`: scripthash, derivation, the gap-limit scan) is the
same way — no Context, no socket — so the scan's rules are unit-tested against
an in-memory fake and the RPCs separately against a mock server.

```bash
./gradlew clean assembleDebug testDebugUnitTest   # 199 unit tests, all green
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
./gradlew testDebugUnitTest   # client handshake, ping, chain RPCs, JSON, failure modes
```

The same client carries the three `blockchain.scripthash.*` queries the wallet
screen's balance and UTXOs are built from, plus `blockchain.transaction.get` and
`blockchain.headers.subscribe` for the history rows, over one connection per scan
(`withChainSession`). Scripthash requests never touch the connection log — a
background balance read is not a connection test.

**Tor.** An endpoint whose host ends in `.onion` is dialled through the app's
bundled Tor (`info.guardianproject:tor-android`, pinned to 0.4.9.6 — the newest
release that supports this project's `compileSdk 36`): `MainActivity` installs an
`AndroidTorBootstrap` in `TorGateway`, the client's default `RoutingTransport`
picks Tor for onion hosts and a direct socket for everything else, and Tor's
bootstrap progress (`bootstrapping 50% …`) plus a clear failure line appear in the
same connection log as the handshake. Nothing changes about the handshake itself:
protocol, port (50001/50002) and the SSL/TLS path are identical on both routes, so
`.onion` + SSL works exactly as clearnet + SSL does. There is no Orbot dependency
and no toggle.

The home screen reuses the same client for its heartbeat: `ElectrumClient.ping()`
connects, sends one `server.ping` with no parameters and closes — the cheap
probe, and one that writes nothing to the connection log. The home screen shows
the result beside the Server button. The loop only runs while the home screen is
on display (leaving it, or the process going to the background, cancels it) and
re-checks immediately when the saved endpoint changes: no background service, no
wake lock, no new permission.

Limitations of this iteration: no certificate pinning (an untrusted or
self-signed server certificate is rejected and reported in the log rather than
accepted — the same rule applies over Tor), no testnet yet, and the transaction
history is capped at the latest 50 rows (older ones are not fetched or shown).
An endpoint is assumed to serve the XBT chain.

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
