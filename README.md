# xbt-wallet

**A simple, sovereign XBT wallet — made for GrapheneOS.**

xbt-wallet is a lightweight Android wallet for XBT (Bitcoin). A hot wallet for everyday spends, first-class SeedSigner support for serious savings. Your keys, your node, no Google anywhere.

## Why xbt-wallet

- **Made for GrapheneOS.** No Play Services, no Firebase, no trackers — built for people who de-Googled on purpose, and headed for F-Droid.
- **Sovereign by default.** The app connects only to *your* Electrum node — over plain IP or through the bundled Tor for `.onion` (no Orbot). There is no default server, so no third party ever learns your addresses or balances.
- **SeedSigner, first-class.** Import by scanning the SeedSigner's animated xpub export; send fully air-gapped: pick your coins, finalize on the phone, sign on the SeedSigner over animated QR, scan the signed transaction back, broadcast. Every signature is SIGHASH_ALL + UNIFIED — replay protected — and change comes back to a verified address.
- **Hot wallet for everyday.** Generate a 12 or 24-word seed right on the phone, with an optional passphrase that is never saved. The seed lives encrypted in the device keystore; spend without reaching for the signer.
- **Lightweight.** One small app, one dependency philosophy: nothing it doesn't need.

## Status

v0.1.0 in progress: watch + air-gapped send with a SeedSigner (or any BC-UR signing device) works end to end. The hot wallet is specified and is next. The fiat figures on the wallet screen are clearly-marked placeholders — the app requests no price feed.
