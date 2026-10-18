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
- **Add signer** — a chooser: *Add watch-only xpub* leads to the import form,
  where an account-level `xpub`/`ypub`/`zpub` is validated clean-room
  (Base58Check, version bytes, compressed key, checksum) and stored as a
  watch-only signer. *Create hot wallet* is shown disabled — no seed code exists.
- **Signers list** — every saved signer, oldest first, with its type badge and a
  head…tail key snippet; tap to open, **⋮** → *Remove signer* (with a confirm
  dialog) to delete.
- **Signer screen** — one signer's own screen: its **live balance** from your
  own Electrum server (sats first, then the coin amount and two clearly-marked
  placeholder fiat lines), then **UTXOs**, **Addresses**, and **Send**/**Receive**
  shown disabled until the air-gapped signing flow exists.
- **UTXO list** — every spendable output the scan found: amount, outpoint,
  address chain/index, and confirmation height (or *unconfirmed*).
- **Addresses viewer** — derived native-segwit receive/change addresses per
  account key, now annotated with on-chain usage: *used*, *unused*, or the
  highlighted *first unused*.
- **Private Electrum server** — enter host/port, pick SSL or TCP, hit *Test
  connection* and watch the Electrum JSON-RPC handshake in an on-screen log.

All crypto is public-key only: no private key, seed or signing code exists
anywhere in this repository. Transaction history, the receive QR and sending are
still missing, there is no Tor in this iteration (direct IP connections only),
and the two fiat figures on the signer screen are **hardcoded placeholders** —
this build requests no price feed from anyone.

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

**Add signer** opens the Add-signer chooser: **Add watch-only xpub** leads to the
import form, and **Create hot wallet** is shown, greyed out and tagged *Not
available yet*. **Signers** opens the list of saved signers — each shown by
its name, a *watch-only* type badge and a head…tail snippet of its key — and
tapping one opens that signer's own screen. Each row has a **⋮** menu whose
*Remove signer* asks for confirmation first. There is deliberately no "active
signer" any more: the old active-signer card, the **Select active xpub** picker
and the standalone **Addresses** button are gone, and per-signer screens
(addresses included) are reached by opening a signer instead.

## Navigation

Screens are an enum and a `when` — no navigation library (PLAN.md §2.4) — over a
small **back stack** that makes *back* mean exactly one thing: one level up.
Navigating pushes a destination onto the stack; Android's back button pops it.

- HOME → **Add signer** → the paste form → back → the chooser → back → HOME.
- A signer's screen → **UTXOs** (or **Addresses**) → back → that signer's screen.

Each stack entry carries its own argument, so a per-signer screen remembers
*which* signer it is showing: opening UTXOs from one signer and backing out can
never leave the app pointing at another. The stack survives a rotation, and back
at HOME is left to the system (it leaves the app) rather than swallowed. Saving a
new signer resets the walk to HOME → Signers, so back from the list does not
return into a form that has already been submitted.

## Add signer

`Add signer` is a fork, not a form — the screen opens with a plain-language
intro naming both ways to pair with a signer, then shows the two buttons:

- **Add watch-only xpub** — the live path. It takes a signer name plus an
  account-level extended public key and stores a watch-only signer. The intro
  says what it is for: a watch-only xpub is for use with your SeedSigner — or any
  wallet you want to keep an eye on — without the seed ever touching this phone.
- **Create hot wallet** — **not available yet**. Generating a seed on this phone
  is a capability this build does not have, so the button is disabled and
  carries a small *Not available yet* tag. The copy presents it as the
  convenience it will be — *like cash in your pocket … for small, everyday
  amounts* — not as something the app is talking you out of. There is no seed
  generation code behind it.

The pasted key is validated against the public specifications:

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

## Signers

The **Signers** screen is the app's inventory. Every saved signer is listed
oldest first, by the name the user gave it, with a *watch-only* type badge and a
snippet of its key — the first 10 and last 6 characters, elided in the middle, so
two keys are told apart at a glance without an xpub filling the row. Tapping a
row opens that signer's own screen. When nothing is saved yet the screen says so
and offers an **Add signer** shortcut, so an empty list is never a dead end.

Each row has a **⋮** menu with *Remove signer*, which opens a confirm dialog
(name plus the fact that only the public key is stored here, so nothing is
deleted on the signer itself). Deleting writes straight through the store and the
list drops the row; if a screen was still pointing at the removed signer, its
stack entry stops resolving and the app restarts at Home rather than
crashing. The screen keeps only the two bits of interaction state that belong to
it (which row's menu is open, which signer is awaiting confirmation); the store
is written on the screen that owns it.

## Signer screen

Tapping a signer opens its own screen — the place every per-signer action lives,
and the first screen that talks to the chain. Top to bottom: the signer's name
and its *watch-only* badge, its **balance**, and the four actions.

**Balance** is this account's confirmed *plus* unconfirmed satoshis, read from
your own Electrum server. The scan runs off the UI thread and the card shows
what is happening at each stage: *Loading balance…* with a running
"checked N addresses" count while the scan is in flight, the sats figure
(`1,234,567 sats`) with the coin amount (`0.01234567 XBT`) when it lands, or an
error with a **Retry** when the server could not be reached. With no server
saved at all there is no error to show — the card says *Connect a server* and
explains that balances come from the user's own server and nobody else's.

Under the balance sit **UTXOs** and **Addresses** (each opens its own screen,
see below) and **Send**/**Receive**, shown disabled with *coming soon*: sending
is the air-gapped QR flow, which does not exist yet, and a wallet that offers a
Send button that cannot send is a wallet that lies.

Two fiat lines dress the same balance in hardcoded placeholder rates, each
labelled as such: the XBT figure at **$420.69/XBT** and, because BTC and XBT
share the satoshi unit, the same balance as BTC at **$81,000/BTC**. Both live in
one file (`PlaceholderPrices.kt`) with comments saying exactly what they are.
There is no price API, no HTTP client and no new permission anywhere near this —
the only traffic the app makes is Electrum RPCs to the server the user entered.

## Chain queries

Under the signer screens is a small chain layer (`app/src/main/java/com/xbtwallet/app/chain/`),
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

The three RPCs (`get_history`, `get_balance`, `listunspent`) run over **one
connection** per scan (`ElectrumClient.withChainSession`): an account scan is
dozens of requests, and a connect/TLS/close per address would make an ordinary
balance look like a port scan to the user's own server. The scan re-runs each
time a signer is opened — there is no caching in this iteration, which is cheap
enough and removes a whole class of stale-balance bugs.

## UTXOs

The UTXO screen lists what the scan found: one monospaced row per output, its
amount in sats on top and its provenance underneath — `txid:vout` with the
middle elided, the chain and address index it sits at, and the height it
confirmed at (or *unconfirmed*). An empty account says so. There is no coin
control, no freezing and no selection yet; those are v0.2.0.

## Addresses

The addresses viewer shows the addresses derived from a signer's account key:
external chain `0/i` under the **Receive** tab and internal chain `1/i` under
**Change**, both native segwit P2WPKH. Each row shows its derivation index and,
now, what the scan learned about it — *used*, *unused*, or the accent-marked
*first unused*, which is the address the next receive would hand out. Tap a row
to copy it.

The list starts as exactly what the scan visited, which is a full unused batch
past the last used address. **Show 20 more** derives further addresses on
demand; those rows are marked *not scanned* rather than *unused*, because the
app genuinely has not asked about them and a receive screen that guesses is how
an address with history gets handed out twice.

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
./gradlew clean assembleDebug testDebugUnitTest   # 147 unit tests, all green
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

The same client carries the three `blockchain.scripthash.*` queries the signer
screen's balance and UTXOs are built from, over one connection per scan
(`withChainSession`). Scripthash requests never touch the connection log — a
background balance read is not a connection test.

The home screen reuses the same client for its heartbeat: `ElectrumClient.ping()`
connects, sends one `server.ping` with no parameters and closes — the cheap
probe, and one that writes nothing to the connection log. The home screen shows
the result beside the Server button. The loop only runs while the home screen is
on display (leaving it, or the process going to the background, cancels it) and
re-checks immediately when the saved endpoint changes: no background service, no
wake lock, no new permission.

Limitations of this iteration: no Tor, no certificate pinning (an untrusted or
self-signed server certificate is rejected and reported in the log rather than
accepted), no transaction history yet, and no testnet: an endpoint is assumed to
serve the XBT chain.

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
