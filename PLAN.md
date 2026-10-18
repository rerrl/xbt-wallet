# xbt-wallet — plan

This document is the working plan for **xbt-wallet**. It is the reference that
future work runs on: if a change contradicts something written here, either the
change is wrong or this document gets updated first.

---

## 1. Vision

**xbt-wallet is a SeedSigner-first, watch-only, mobile-first XBT / SeedSigner
coordinator** (the Bitcoin BLAKE2b fork), built for GrapheneOS before anything
else. It manages xpubs, not wallets: it holds no keys and signs nothing.

- **Watch-only coordinator.** The phone holds public keys only. Private keys are
  generated on, and never leave, a dedicated air-gapped signer (SeedSigner).
- **SeedSigner-first.** SeedSigner is not one supported signer among many — it is
  the signer the UX is designed around, and the primary assumption of every data
  flow (QR formats, derivation paths, address verification on-device).
- **GrapheneOS-first, Android-only.** No Play Services, no Google dependencies,
  no cloud sync, no telemetry. Android is the target platform; there is no iOS,
  desktop or web client on the roadmap.
- **Air-gapped by design.** Data moves between phone and signer by animated QR
  (BC-UR / fountain codes). No USB, no SD card, no Bluetooth, no network.
- **MIT licensed.** Free and open source, always. Anyone can read, build and
  audit the whole thing.
- **Clean-room.** This project is written from public specifications. Public
  reference implementations may be *read* as documentation
  (see §6); code is not copied from them.

---

## 2. Product principles

1. **Keys never touch the phone.** No seed input, no private key import, no
   signing. If a feature would require a secret on the device, the feature is
   wrong — redesign it as a signer round-trip instead.
2. **The QR dance is THE primary flow, not a settings option.** Sending is
   scan → sign on the air-gapped signer → scan back. It is the happy path, the
   default path, and the path every other screen is built to serve. There is no
   "hot wallet mode" to fall back to.
3. **Sparrow-grade basics.** The everyday wallet operations — balances, history,
   addresses, receive, UTXO management, coin control, fee selection — should be
   as solid, clear and unsurprising as Sparrow's. Boring correctness beats novel
   UX in a wallet.
4. **Lean and auditable.** Small dependency surface, few moving parts, code that
   one person can read in an evening. Every added library is a liability: a
   wallet's attack surface is its dependency tree.
5. **Verify, don't trust.** Anything the coordinator can check, the coordinator
   checks: derivation paths, address ownership, sighash type, transaction
   structure, signature encoding.

---

## 3. Stack decisions

| Area | Decision |
| --- | --- |
| Language / UI | **Kotlin** + **Jetpack Compose** with **Material 3**. No XML layouts, no View system, no AppCompat. |
| Build | Gradle **Kotlin DSL**, version catalog (`gradle/libs.versions.toml`), AGP 8.x. |
| Min / target SDK | `minSdk 26`, `targetSdk` current stable (36 at scaffold time). |
| Package / applicationId | `com.xbtwallet.app`. |
| Build system style | Single module (`:app`) for now; feature modules get split out only when the build time justifies it. |
| BC-UR / animated QR | **`sparrowwallet/hummingbird`** — a well-tested, dependency-light UR implementation used by Sparrow; gives UR encode/decode and the fountain-code multi-frame QR machinery. |
| Chain data | **Electrum protocol** client against **electrs-blake2b** (an Electrum server for XBT). No SPV, no full node on the phone. |
| Transport | **Tor first** via **`tor-android`** (bundled Tor, no Orbot dependency). **Direct IP/TLS** connection is an accepted alternative for users who run their own node on the LAN. Both paths are first-class, selectable per server profile. |
| Internal data model | Stores **descriptors (BIP-380)**, always — even when the UI shows the friendlier word "xpub". A descriptor is self-describing (script type, key origin/derivation path, fingerprint) where a bare xpub is not. |
| Persistence | Local DB schema assumes **multiple wallets from day one**: a wallet table, a key/descriptor table, an account table, an address table, a UTXO table, all keyed by wallet id. Even the v0.1.0 single-wallet UI writes through the multi-wallet schema. |
| Networking / crypto libs | Deliberately minimal and picked per-feature as features land, not up front. Nothing is added to the skeleton. |
| No | Play Services, Firebase, Google Analytics, Crashlytics, cloud backup, `allowBackup`. |

---

## 4. Roadmap

Version numbers are about scope, not dates.

### v0.1.0 — Getting started

The first usable release: get a watch-only wallet onto the phone and receive
money into it.

- **Load / save xpub.**
  - **PRIMARY path:** scan a **SeedSigner QR** — the `crypto-account` /
    `crypto-output` (BIP-380 descriptor) export — importing the descriptor along
    with **master fingerprint** and **derivation path**.
  - **Fallback path:** manual xpub paste (with explicit network / script-type
    choice and a warning about the missing fingerprint).
- View **addresses**, **balances** and **transaction history**.
- **Address explorer:** browse derived addresses per account, with derivation
  index shown.
- **Receive flow:** pick a fresh unused address, render it as a QR, and support
  **verify-on-signer** — the user confirms on the signer screen that the address
  shown by the phone is the one the signer derives.
- **Connect to electrs (blake2b)** via **Tor** or **direct IP/TLS**.

### v0.2.0 — UTXO management

- UTXO viewer (per-wallet and per-address).
- **Coin control:** select which UTXOs to spend.
- **Mark frozen:** exclude UTXOs from automatic selection, with a clear visual
  state.

### v0.3.0 — Sending

The full air-gapped QR dance:

1. Build an **unsigned PSBT** with coin control and block-target fee presets.
2. Export as **animated `crypto-psbt`** QR (BC-UR, fountain codes).
3. Scan the **signed PSBT** back.
4. **Verify before broadcast:**
   - recompute the sighash,
   - require the **unified sighash byte** (`0x21`),
   - require **low-S** signatures,
   - require the final transaction to be **byte-identical** to the one the
     coordinator built (no surprise inputs, outputs, or change).
5. **Broadcast** via the configured electrs server.
6. **Simple block-target fee presets** (e.g. ~1 / ~3 / ~6 blocks), not a fee
   slider.

### Later (unscheduled)

- **Labels** with **BIP-329** export/import.
- **Multisig** (multi-signer descriptor round-trips).
- **RBF** (fee bumping a broadcast-but-unconfirmed transaction).

---

## 5. XBT chain notes

What matters for this wallet, and where it differs from BTC:

- **Fork.** XBT is a fork of Bitcoin. Proof-of-work switches to
  **BLAKE2b-256** at block **961,640** (the fork point).
- **Block headers.** XBT blocks carry **164-byte extended headers**. Header
  parsing must be
  `rust-bitcoin`'s **`blake2b_header.rs`-compatible** — i.e. the same field
  layout and hashing rules as that reference implementation, not the Bitcoin
  80-byte header.
- **Shared with BTC.** XBT **shares Bitcoin's genesis block** and the address
  scheme: version byte **`0x00`** → `bc1`/legacy `1...` mainnet, **`0x05`** →
  `3...`. Address encoding is therefore identical to Bitcoin's.
- **Replay protection.** Opt-in via **`SIGHASH_UNIFIED` (0x20)**: the unified
  sighash flag. Constructed sighash types use the **`0x21`** hashtype byte
  (`SIGHASH_ALL | SIGHASH_UNIFIED`).
- **PSBT policy.** PSBTs this wallet builds **request sighash `0x21`**, and the
  wallet **refuses any signature that does not carry it** — an un-unified
  signature is exactly the kind of thing replay protection exists to prevent.

---

## 6. Clean-room note

xbt-wallet is written from **public specifications**, not from anyone's source
code:

- **BIP-174** — PSBT
- **BIP-32 / BIP-39** — key derivation / mnemonics (signer-side, but the wallet
  understands paths and fingerprints)
- **BIP-380** — output descriptors
- **BC-UR** — Uniform Resources (animated QR)
- **RFC 7693** — BLAKE2 hash functions
- Knots **unified-sighash** documentation — XBT's replay-protection scheme

**`kilowallet` is used as reference documentation only**, never as a source of
code. It is an existing Android/Kotlin wallet for the same chain and it is
useful for confirming that a spec reading is correct — but no code is copied
from it into this repository. The one exception, where copying is safe and
explicitly permitted:

- **Test vectors** may be lifted from `kilowallet` (or other Apache-2.0
  sources), as test data only. Apache-2.0 test vectors are compatible with this
  project's MIT license.

Anything that looks like a coincidence in a data structure is a coincidence of
both projects reading the same spec.

---

## 7. Build status (v0.1.0 in progress)

What exists right now, in this repository:

- Android/Kotlin + Compose (Material 3) single-module project, dark theme,
  Gradle Kotlin DSL, version catalog, state-based navigation (no nav library).
- A home screen, top to bottom: **Server**, **Add signer** and **Signers**.
  Under the Server button the screen shows live server status — connected /
  disconnected / checking — refreshed by a heartbeat while it is on display.
  Signers are a plain list: there is no "active signer". **Add signer** opens a
  chooser — an intro naming both ways to pair, *Add watch-only xpub* leading to
  the paste-and-validate form, and *Create hot wallet* shown disabled with a
  *not available yet* tag, because seed generation on the phone is a capability
  this build does not have. The Signers list shows every
  saved signer oldest first, with its type badge and a head…tail key snippet, and
  offers a confirm-gated remove per row. The
  old active-signer card, **Select active xpub** picker and standalone
  **Addresses** button are gone; the Addresses viewer is reopened under a signer.
  The old Receive/Send placeholders are gone.
- **Signer screens** (v0.1.0's first chain-data feature). Opening a signer shows
  its **balance** — confirmed plus unconfirmed satoshis of that xpub, read from
  the user's own Electrum server, with a visible loading state, a *Connect a
  server* state when no endpoint is saved, and a retryable error when the server
  does not answer. Under it: **UTXOs**, **Addresses**, and **Send**/**Receive**
  disabled (coming soon). Fiat figures are **hardcoded placeholders** in
  `PlaceholderPrices.kt` ($420.69/XBT, $81,000/BTC, both labelled on screen) —
  this build makes no market-data request, adds no HTTP client and adds no
  permission. The UTXO list is monospaced rows of amount, `txid:vout`, chain and
  address index, and height. The address viewer's rows are annotated *used* /
  *unused*, with the first unused highlighted.
- **Chain layer** (`com.xbtwallet.app.chain/`, Android-free): Electrum
  **scripthash** (`sha256(scriptPubKey)` reversed), P2WPKH script building, and a
  **gap-limit scan** — receive (`0/i`) and change (`1/i`) walked in batches of 20
  until a batch has no used address in it, then `blockchain.scripthash.get_balance`
  summed over the used addresses for the headline number and
  `blockchain.scripthash.listunspent` for the UTXO list. The three scripthash RPCs
  share one connection per scan (`ElectrumClient.withChainSession`).
- **Crypto** (`com.xbtwallet.app.crypto/`, Android-free): Base58Check, SHA-256 /
  RIPEMD-160 / HMAC-SHA512, bech32 + bech32m, secp256k1 public-point arithmetic,
  BIP-32 parsing and public-only child derivation, P2WPKH address building.
  Public keys only — there is no private-key, seed or signing code path.
- **Storage** (`com.xbtwallet.app.data/`): a JSON signer list in
  SharedPreferences behind a `KeyValueStore` interface, so the store is
  JVM-testable. A signer is an id, a name and a type — `WatchOnlyXpub(xpub)`
  today; there is no active-signer id and no seed storage. Older `wallets` blobs
  are migrated to watch-only signers on first read. Plus the Electrum server
  settings from the previous iteration, behind a `ServerSettings` interface for
  the same reason.
- **Networking**: a clean-room Electrum JSON-RPC client (direct TLS/TCP, no Tor
  yet) used by the private-server screen, via its quiet `server.ping` probe by
  the home screen's status heartbeat, and via its scripthash RPCs by the signer
  screens' balance and UTXO views.
- **Tests**: 147 JVM unit tests, green, including the official BIP-84 and BIP-173
  vectors, the published RIPEMD-160 vectors, an Electrum scripthash vector, and
  the gap-limit scan's stop rule (including the partly-used batch case).

Not yet: a transaction history screen, the receive QR flow, descriptors/BIP-380
(the model still stores a bare xpub), Tor, precise/real fiat rates, and sending.
