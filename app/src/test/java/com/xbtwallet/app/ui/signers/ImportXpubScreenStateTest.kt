package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.data.SignerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import screen's scan state machine, exercised without any Compose UI.
 *
 * The camera itself is not testable on the JVM and the panel is verified by
 * `assembleDebug`, but *what a scan does to the state* is a rule, not a view:
 * import is QR-only, a valid scan populates the key, a bad scan clears
 * everything before it records its error, the panel comes down as soon as
 * a scan has produced a result, a rescan throws the last result away and opens a
 * clean panel, and the save is refused until both a name and a parsed key are
 * present. A key-expression QR is still accepted, but the fingerprint and path it
 * carries are deliberately dropped — this is the watch-only path, and a text
 * import must never become signing-ready. That is what these tests pin.
 */
class ImportXpubScreenStateTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private val zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private var saved: Signer? = null

    private fun state(onSaved: (Signer) -> Unit = { saved = it }) =
        ImportXpubScreenState(SignerStore(InMemoryStore()), onSaved)

    // --- the panel gate ----------------------------------------------------

    @Test
    fun `the embedded panel is closed and nothing is denied at first`() {
        val state = state()
        assertFalse("the state never opens the panel itself — the screen asks on entry", state.showScanner)
        assertFalse("no denial has happened yet", state.cameraDenied)
        assertNull(state.scannedXpub)
        assertNull(state.scanError)
    }

    @Test
    fun `a granted camera permission opens the embedded panel`() {
        val state = state()
        state.onCameraPermissionGranted()
        assertTrue("a granted permission opens the panel", state.showScanner)
        assertFalse("a grant clears any earlier denial", state.cameraDenied)
    }

    @Test
    fun `a denied camera permission never opens the panel`() {
        val state = state()
        state.onCameraPermissionDenied()
        assertFalse("no camera means no panel", state.showScanner)
        assertTrue("the denial must be visible so the hint can be shown", state.cameraDenied)
    }

    @Test
    fun `a later grant clears the denial and opens the panel`() {
        val state = state()
        state.onCameraPermissionDenied()
        state.onCameraPermissionGranted()
        assertTrue(state.showScanner)
        assertFalse(state.cameraDenied)
    }

    // --- a scan ------------------------------------------------------------

    @Test
    fun `a valid scan populates the key and clears any error`() {
        val state = state()
        state.onCameraPermissionGranted()
        state.onScanResult("bitcoin:$zpub?amount=1&label=Cold")
        state.onScannerDismissed()

        assertEquals("the routed key lands in the state", zpub, state.scannedXpub)
        assertEquals("and is validated exactly as any key would be", "zpub", state.recognisedVersion)
        assertNull("a valid scan shows no error", state.scanError)
        assertFalse(state.showScanner)
    }

    @Test
    fun `a key-expression scan keeps the key but drops its metadata`() {
        // A key expression stays a valid import — routed and validated exactly as
        // before — but this is the watch-only text-QR path, so whatever
        // fingerprint and path the QR carried are deliberately discarded.
        val state = state()
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")

        assertEquals(zpub, state.scannedXpub)
        assertEquals("zpub", state.recognisedVersion)
        assertNull(state.scanError)
    }

    @Test
    fun `a fingerprint-only key expression is accepted and its fingerprint is dropped`() {
        val state = state()
        state.onScanResult("[ab12cd34]$zpub")

        assertEquals(zpub, state.scannedXpub)
        assertEquals("zpub", state.recognisedVersion)
        assertNull(state.scanError)
    }

    @Test
    fun `an invalid scan records the validator's message and clears the fields`() {
        val state = state()
        state.onScanResult("bitcoin:not-a-key?amount=1")

        assertNull("a bad scan leaves no key behind", state.scannedXpub)
        assertNull(state.recognisedVersion)
        assertTrue("the validator's reason is surfaced", state.scanError != null)
    }

    @Test
    fun `a bad scan clears a previously good one`() {
        val state = state()
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")
        assertEquals(zpub, state.scannedXpub)

        state.onScanResult("nonsense")

        assertNull("the earlier key must not linger looking imported", state.scannedXpub)
        assertTrue(state.scanError != null)
    }

    @Test
    fun `rescanning replaces everything`() {
        val state = state()
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")
        state.onScanResult("bitcoin:$zpub?amount=2")

        assertEquals(zpub, state.scannedXpub)
        assertNull(state.scanError)
    }

    @Test
    fun `hiding the panel after a scan keeps what was read`() {
        val state = state()
        state.onScanResult(zpub)
        state.onCameraPermissionGranted()
        state.onScannerDismissed()

        assertFalse("the panel comes down once a scan has landed", state.showScanner)
        assertEquals("and hiding it must not rewrite what was scanned", zpub, state.scannedXpub)
    }

    // --- rescan ------------------------------------------------------------

    @Test
    fun `a rescan clears the last scan and reopens the panel`() {
        val state = state()
        state.onNameChange("Cold storage")
        state.onCameraPermissionGranted()
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")
        state.onScannerDismissed()

        state.onRescanRequested()

        assertTrue("rescan brings the panel back", state.showScanner)
        assertNull("and the key the user is rescanning is gone", state.scannedXpub)
        assertNull(state.recognisedVersion)
        assertNull(state.scanError)

        // Nothing is importable from the discarded scan: the parsed key went with
        // the fields the confirmation was drawn from.
        state.import()
        assertNull("a discarded scan cannot be saved", saved)
        assertTrue("the save is refused, as it is before any scan", state.scanError != null)
    }

    @Test
    fun `a rescan after a bad scan clears the message and opens a clean panel`() {
        val state = state()
        state.onCameraPermissionGranted()
        state.onScanResult("nonsense")
        state.onScannerDismissed()
        assertTrue("the bad scan said why", state.scanError != null)

        state.onRescanRequested()

        assertTrue(state.showScanner)
        assertNull("the last complaint is not carried into the new scan", state.scanError)

        state.onScanResult(zpub)
        assertEquals("the fresh scan reads normally", zpub, state.scannedXpub)
        assertNull(state.scanError)
    }

    @Test
    fun `a rescan clears an earlier camera denial`() {
        val state = state()
        state.onCameraPermissionDenied()

        // Reached only with the permission in hand — the screen checks first —
        // and a panel that is up must not sit behind a stale denial hint.
        state.onRescanRequested()

        assertTrue("the panel is up", state.showScanner)
        assertFalse("the denial hint is gone", state.cameraDenied)
    }

    // --- import ------------------------------------------------------------

    @Test
    fun `import of a key-expression scan saves a bare watch-only key`() {
        // The key expression is accepted, but what reaches the store is watch-only:
        // no fingerprint, no derivation path, and so not signing-ready. That is the
        // ruling this whole path exists to enforce.
        val state = state()
        state.onNameChange("Cold storage")
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")

        state.import()

        val signer = saved
        assertTrue("the signer was saved", signer != null)
        assertEquals("Cold storage", signer!!.name)
        assertEquals(SignerType.WatchOnlyXpub(zpub), signer.type)
        assertFalse("a text-qr import is never signing-ready", signer.signingReady)
    }

    @Test
    fun `import of a bare-key scan saves null metadata`() {
        val state = state()
        state.onNameChange("Cold storage")
        state.onScanResult(zpub)

        state.import()

        assertEquals(SignerType.WatchOnlyXpub(zpub), saved?.type)
    }

    @Test
    fun `import keeps the text-qr origin`() {
        val state = state()
        state.onNameChange("Cold storage")
        state.onScanResult(zpub)

        state.import()

        // A key that arrived as a plain xpub QR is not a signing-device import,
        // and saying so is what keeps its tag the grey "watch-only" pill.
        assertEquals(SignerOrigin.TEXT_QR, saved?.importedVia)
    }

    @Test
    fun `import is refused without a name`() {
        val state = state()
        state.onScanResult(zpub)

        state.import()

        assertNull("nothing is saved without a name", saved)
        assertTrue("the name error is set", state.nameError != null)
    }

    @Test
    fun `import is refused without a scan`() {
        val state = state()
        state.onNameChange("Cold storage")

        state.import()

        assertNull("nothing is saved without a scanned key", saved)
        assertTrue("the scan error is set", state.scanError != null)
    }

    @Test
    fun `import after a bad scan is refused and keeps the validator's message`() {
        val state = state()
        state.onNameChange("Cold storage")
        state.onScanResult("nonsense")
        val message = state.scanError

        state.import()

        assertNull("a bad scan cannot be imported", saved)
        assertEquals("the specific validator reason is kept", message, state.scanError)
    }

    @Test
    fun `a name error clears once a name is entered`() {
        val state = state()
        state.onScanResult(zpub)
        state.import()
        assertTrue(state.nameError != null)

        state.onNameChange("Cold storage")

        assertNull(state.nameError)
    }
}
