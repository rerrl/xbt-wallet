package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.Signer
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
 * import is QR-only, a valid scan populates the key and its metadata, a bad scan
 * clears everything before it records its error, and the save is refused until
 * both a name and a parsed key are present. That is what these tests pin.
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
        assertFalse("the scanner must not open by itself", state.showScanner)
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
        assertNull("a bare URI carries no fingerprint", state.masterFingerprint)
        assertNull("a bare URI carries no path", state.derivationPath)
        assertFalse(state.showScanner)
    }

    @Test
    fun `a key-expression scan captures the fingerprint and path`() {
        val state = state()
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")

        assertEquals(zpub, state.scannedXpub)
        assertEquals("ab12cd34", state.masterFingerprint)
        assertEquals("84'/0'/0'", state.derivationPath)
        assertNull(state.scanError)
    }

    @Test
    fun `a fingerprint-only scan captures the fingerprint and no path`() {
        val state = state()
        state.onScanResult("[ab12cd34]$zpub")

        assertEquals("ab12cd34", state.masterFingerprint)
        assertNull(state.derivationPath)
    }

    @Test
    fun `an invalid scan records the validator's message and clears the fields`() {
        val state = state()
        state.onScanResult("bitcoin:not-a-key?amount=1")

        assertNull("a bad scan leaves no key behind", state.scannedXpub)
        assertNull(state.recognisedVersion)
        assertNull(state.masterFingerprint)
        assertNull(state.derivationPath)
        assertTrue("the validator's reason is surfaced", state.scanError != null)
    }

    @Test
    fun `a bad scan clears a previously good one`() {
        val state = state()
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")
        assertEquals(zpub, state.scannedXpub)

        state.onScanResult("nonsense")

        assertNull("the earlier key must not linger looking imported", state.scannedXpub)
        assertNull(state.masterFingerprint)
        assertNull(state.derivationPath)
        assertTrue(state.scanError != null)
    }

    @Test
    fun `rescanning replaces everything`() {
        val state = state()
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")
        state.onScanResult("bitcoin:$zpub?amount=2")

        assertEquals(zpub, state.scannedXpub)
        assertNull("the second, bare-key scan drops the earlier metadata", state.masterFingerprint)
        assertNull(state.derivationPath)
        assertNull(state.scanError)
    }

    @Test
    fun `cancelling the panel leaves the scan untouched`() {
        val state = state()
        state.onScanResult(zpub)
        state.onCameraPermissionGranted()
        state.onScannerDismissed()

        assertFalse("cancel closes the panel", state.showScanner)
        assertEquals("cancel must not rewrite what was scanned", zpub, state.scannedXpub)
    }

    // --- import ------------------------------------------------------------

    @Test
    fun `import saves the signer with its captured metadata`() {
        val state = state()
        state.onNameChange("Cold storage")
        state.onScanResult("[ab12cd34/84'/0'/0']$zpub")

        state.import()

        val signer = saved
        assertTrue("the signer was saved", signer != null)
        assertEquals("Cold storage", signer!!.name)
        assertEquals(
            SignerType.WatchOnlyXpub(zpub, masterFingerprint = "ab12cd34", derivationPath = "84'/0'/0'"),
            signer.type,
        )
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
