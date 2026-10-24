package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.ui.OrangeOutlineButton
import com.xbtwallet.app.ui.Route
import com.xbtwallet.app.ui.Screen
import com.xbtwallet.app.ui.theme.XbtPrimary
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * **Add wallet** — the one way a wallet enters the app, as three cards.
 *
 * Each card is a *kind of wallet*, and each carries its own copy and its own
 * buttons — no intro paragraph above them and no footer below, because the card
 * is now the unit: a title, a sentence saying who it is for, and the buttons
 * that start that path.
 *
 *  1. **SeedSigner / External signer** — one `Select`, which opens
 *     [SigningDeviceChoiceScreen] and asks the follow-up question there
 *     (SeedSigner vs some other device). Keeping that question on its own screen
 *     is what preserves the two origins the app already stamps: only the
 *     SeedSigner tap earns the orange *seedsigner* tag.
 *  2. **Hot wallet** — two buttons, *Create* and *Import*, both of which land in
 *     the `ui.hotwallet` screens. **This is UI scaffolding for the ratified
 *     hot-wallet phase and nothing more: no seed material is generated,
 *     imported, or stored anywhere yet, and the hot-wallet screens' terminal
 *     buttons are disabled.** The card is here so the shape of the flow can be
 *     seen and judged before any of the cryptography behind it exists.
 *  3. **Watch only** — one `Select`, the unchanged [ImportXpubScreen] text path.
 *     A bare `xpub`/`ypub`/`zpub`, a `bitcoin:` URI, or a BIP-32 key expression
 *     (`[ab12cd34/84'/0'/0']zpub…`) from any coordinator.
 *
 * The two animated-BC-UR rows that used to live here moved to
 * [SigningDeviceChoiceScreen] with [AddWalletRow] and [routeFor] unchanged —
 * they are the *answer* to card 1's question, not a thing this screen offers.
 *
 * The screen owns no state; it renders the copy and forwards the taps. The copy
 * lives in the constants below, free of Compose imports, so [AddSignerCopyTest]
 * can pin it as Kotlin rather than a rendering detail a JVM test cannot reach.
 */

/**
 * The border worn by a card, per wallet kind. Each card is a way in, and the
 * hairline colour is the only thing that tells the three apart at a glance: the
 * orange the app already uses for a SeedSigner-signing path, an orange-red for
 * the keys-on-this-phone path, and a soft half-strength blue for the path that
 * can never spend. The fill is transparent, so the border is the whole cue.
 */

/** Card 1's border: the wallet's own bitcoin orange. */
private val AddWalletExternalBorder = XbtPrimary

/** Card 2's border: orange-red, hotter than the SeedSigner orange. */
private val AddWalletHotBorder = Color(0xFFFF5722)

/** Card 3's border: a soft, half-transparent blue — public keys only. */
private val AddWalletWatchBorder = Color(0xFF3D7BD9).copy(alpha = 0.6f)

/** The header's back affordance, unchanged. */
internal const val ADD_SIGNER_BACK_LABEL = "\u2190 Back"

/** The screen title, in the app's user-facing word for the unit: a wallet. */
internal const val ADD_SIGNER_TITLE = "Add wallet"

/** The label every card's single-option button shares. */
internal const val ADD_SIGNER_SELECT_BUTTON = "Select"

/** The Hot wallet card's first button: start a dice-roll creation. */
internal const val ADD_SIGNER_CREATE_BUTTON = "Create"

/** The Hot wallet card's second button: import an existing seed. */
internal const val ADD_SIGNER_IMPORT_BUTTON = "Import"

/** Card 1's title — the external-signer family, SeedSigner first among them. */
internal const val ADD_SIGNER_EXTERNAL_TITLE = "SeedSigner / External signer"

/** Card 1's copy: who this card is for. */
internal const val ADD_SIGNER_EXTERNAL_COPY =
    "Select this if your wallet supports QR-based transaction signing."

/** Card 2's title. */
internal const val ADD_SIGNER_HOT_TITLE = "Hot wallet"

/** Card 2's copy: keys on this phone, and the two ways to put them there. */
internal const val ADD_SIGNER_HOT_COPY =
    "Private keys live on this device. Create a new wallet with dice rolls, or " +
        "import an existing seed."

/**
 * What the Hot wallet card says instead of its buttons when the device has no
 * lock screen.
 *
 * A hot wallet's seed is sealed under a key bound to device authentication, so
 * a device with no lock screen has nothing to bind to: the wallet could be
 * created and then never opened. The card refuses rather than failing later, and
 * says why and what to do about it.
 */
internal const val ADD_SIGNER_HOT_LOCKED_COPY =
    "A hot wallet's seed is protected by your device lock, and this device has " +
        "no screen lock. Set a PIN, pattern or password in Android settings, then " +
        "reopen the app to create one."

/**
 * Whether the Hot wallet card offers its two buttons.
 *
 * A pure function of the one fact that decides it, so the rule is pinned by a
 * JVM test rather than only by the rendering.
 */
internal fun hotWalletOffered(deviceSecure: Boolean): Boolean = deviceSecure

/** Card 3's title. */
internal const val ADD_SIGNER_WATCH_TITLE = "Watch only"

/**
 * Card 3's copy: what watch-only can and cannot do.
 *
 * A watch-only wallet holds public keys only, so it can **look** but never
 * sign: balances, UTXOs, receive/change addresses and the transaction list are
 * all it does. Spending *and* preparing the transaction to spend are both out —
 * those need keys this phone does not have — so the copy names what is left
 * rather than promising transaction preparation it cannot deliver.
 */
internal const val ADD_SIGNER_WATCH_COPY =
    "The phone holds public keys only. Check balances, view UTXOs and receive/change " +
        "addresses, and share a receive address \u2014 but never spend or prepare a " +
        "transaction. Scan an xpub QR from any coordinator."

/** The SeedSigner option's label, as drawn on [SigningDeviceChoiceScreen]. */
internal const val ADD_SEEDSIGNER_BUTTON = "SeedSigner"

/** The SeedSigner option's caption, walking the user through its menu. */
internal const val ADD_SEEDSIGNER_CAPTION =
    "Export Xpub \u2192 single-sig \u2192 native segwit \u2192 Animated QR on your " +
        "SeedSigner, then scan it here."

/** The other-device option's label. */
internal const val ADD_SIGNING_DEVICE_BUTTON = "Other signing device"

/** The other-device option's caption, naming the devices it covers. */
internal const val ADD_SIGNING_DEVICE_CAPTION =
    "Keystone, Jade \u2014 scan the device's animated BC-UR export QR."

/**
 * The two options of the signing-device chooser, in the order they are shown.
 *
 * Both rows open the *same* screen ([Screen.ADD_SIGNING_DEVICE]); what they
 * cannot otherwise tell apart is which origin the saved wallet should declare,
 * and that is [routeFor].
 *
 * Not `internal` like the copy constants beside it, because it is the argument
 * [SigningDeviceChoiceScreen] takes — the one thing that screen has to be told,
 * since it cannot know which physical device is in the user's hand.
 *
 * There is no watch-only row any more: the watch-only path is card 3 of
 * [AddSignerScreen] now, and it needs no chooser because there is only one
 * watch-only import form.
 */
enum class AddWalletRow(val label: String, val caption: String) {
    SEEDSIGNER(ADD_SEEDSIGNER_BUTTON, ADD_SEEDSIGNER_CAPTION),
    OTHER_SIGNING_DEVICE(ADD_SIGNING_DEVICE_BUTTON, ADD_SIGNING_DEVICE_CAPTION),
}

/**
 * What a choice opens: the destination, plus the [SignerOrigin] the form behind
 * it will stamp on the wallet it saves.
 *
 * This is the whole point of the chooser — the two rows open the *same* screen,
 * and only this mapping says which one the user meant — so it is a pure
 * function, testable on the JVM, and the wiring in `XbtWalletApp` is a one-line
 * `nav.push(routeFor(row))` that cannot quietly disagree with it.
 *
 * A route that arrives with no origin at all reads as the SeedSigner path in
 * `XbtWalletApp`, which is what the store's own migration does to the records an
 * older build wrote; the two agree.
 */
internal fun routeFor(row: AddWalletRow): Route = when (row) {
    AddWalletRow.SEEDSIGNER ->
        Route(Screen.ADD_SIGNING_DEVICE, origin = SignerOrigin.SEEDSIGNER_UR)

    AddWalletRow.OTHER_SIGNING_DEVICE ->
        Route(Screen.ADD_SIGNING_DEVICE, origin = SignerOrigin.SIGNING_DEVICE_UR)
}

/**
 * The `← Back` + title header every add-wallet screen wears, so the three
 * screens of the walk cannot drift apart on something as small as a spacer.
 */
@Composable
internal fun SignerScreenHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(ADD_SIGNER_BACK_LABEL) }
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = title, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * One card: a bold title, a sentence of copy, and the card's buttons.
 *
 * Shared by [AddSignerScreen] and [SigningDeviceChoiceScreen] so the two screens
 * are visibly the same thing — a list of choices — rather than two hand-built
 * layouts that happen to resemble each other.
 *
 * The card is an *outline*, not a panel: a transparent fill behind a 1.dp
 * coloured border, so a list of cards reads as a set of equally-weighty choices
 * and the border colour carries the only distinction between them.
 *
 * @param borderColor the card's 1.dp outline colour. Required, with no default:
 *   every card must say which kind of wallet it opens, and a silent fallback
 *   would let one card drift back to looking like the others.
 * @param copy the card's sentence, given the paragraph treatment
 *   (`bodySmall`/`onSurfaceVariant`) because it is supporting text under the
 *   title, not a heading.
 * @param buttons the card's button row, drawn after the copy.
 */
@Composable
internal fun AddWalletCard(
    title: String,
    copy: String,
    borderColor: Color,
    buttons: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = copy,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            buttons()
        }
    }
}

/**
 * @param hotWalletCreationBlocked whether the device has no lock screen, so a
 *   hot wallet must be refused. When true the Hot wallet card shows
 *   [ADD_SIGNER_HOT_LOCKED_COPY] instead of its buttons. Defaults to false so
 *   every existing caller (and the preview) keeps offering the card.
 */
@Composable
fun AddSignerScreen(
    onOpenSigningDeviceChoice: () -> Unit,
    onOpenHotWalletCreate: () -> Unit,
    onOpenHotWalletImport: () -> Unit,
    onOpenWatchOnly: () -> Unit,
    onBack: () -> Unit,
    hotWalletCreationBlocked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SignerScreenHeader(title = ADD_SIGNER_TITLE, onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            // --- 1. The external signer: one button, one follow-up question. --
            AddWalletCard(
                title = ADD_SIGNER_EXTERNAL_TITLE,
                copy = ADD_SIGNER_EXTERNAL_COPY,
                borderColor = AddWalletExternalBorder,
            ) {
                OrangeOutlineButton(
                    onClick = onOpenSigningDeviceChoice,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(ADD_SIGNER_SELECT_BUTTON)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 2. The hot wallet: two ways in, both needing a device lock. ---
            // On a device with no lock screen the card explains itself instead of
            // offering the two buttons: a hot wallet's seed is bound to device
            // authentication, and there is none here to bind it to.
            AddWalletCard(
                title = ADD_SIGNER_HOT_TITLE,
                copy = ADD_SIGNER_HOT_COPY,
                borderColor = AddWalletHotBorder,
            ) {
                if (!hotWalletOffered(deviceSecure = !hotWalletCreationBlocked)) {
                    Text(
                        text = ADD_SIGNER_HOT_LOCKED_COPY,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OrangeOutlineButton(
                            onClick = onOpenHotWalletCreate,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(ADD_SIGNER_CREATE_BUTTON)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OrangeOutlineButton(
                            onClick = onOpenHotWalletImport,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(ADD_SIGNER_IMPORT_BUTTON)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 3. Watch only: the unchanged text-xpub path. ----------------
            AddWalletCard(
                title = ADD_SIGNER_WATCH_TITLE,
                copy = ADD_SIGNER_WATCH_COPY,
                borderColor = AddWalletWatchBorder,
            ) {
                OrangeOutlineButton(
                    onClick = onOpenWatchOnly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(ADD_SIGNER_SELECT_BUTTON)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun AddSignerScreenPreview() {
    XbtWalletTheme {
        AddSignerScreen(
            onOpenSigningDeviceChoice = {},
            onOpenHotWalletCreate = {},
            onOpenHotWalletImport = {},
            onOpenWatchOnly = {},
            onBack = {},
        )
    }
}
