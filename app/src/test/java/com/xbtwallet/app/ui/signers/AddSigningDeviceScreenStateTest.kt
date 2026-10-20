package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.crypto.UrAccountAssembler
import com.xbtwallet.app.crypto.UrTestVectors
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
 * The signing-device screen's state machine, exercised without any Compose UI.
 *
 * The camera is not testable on the JVM and the animated panel is verified by
 * `assembleDebug`, but *what a scan does to the state* is a rule, not a view —
 * and here it is a rule with an extra axis the text path does not have, because a
 * scan is an assembly of frames rather than one QR code. So these tests drive the
 * state frame by frame: the panel stays open while the export is incomplete,
 * progress moves, out-of-order frames still complete, a cancelled or timed-out
 * scan leaves the screen usable, and the record that lands in the store is the
 * *same* one the watch-only path writes.
 */
class AddSigningDeviceScreenStateTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private val backing = InMemoryStore()
    private var saved: Signer? = null

    private fun state(): AddSigningDeviceScreenState =
        AddSigningDeviceScreenState(SignerStore(backing, now = { 42L }, newId = { "id-1" })) { saved = it }

    private fun frames() = UrTestVectors.bcrAnimated

    /** Feed a whole export and return the state, leaving it on the completed screen. */
    private fun stateAfterAnimatedScan(): AddSigningDeviceScreenState {
        val state = state()
        state.onCameraPermissionGranted()
        frames().forEach(state::onScanFrame)
        return state
    }

    // --- the panel gate ----------------------------------------------------

    @Test
    fun `the embedded panel is closed and nothing is denied at first`() {
        val state = state()
        assertFalse("the scanner must not open by itself", state.showScanner)
        assertFalse("no denial has happened yet", state.cameraDenied)
        assertEquals("nothing has been assembled yet", 0, state.scanProgress)
        assertNull(state.scannedXpub)
        assertNull(state.scanError)
        assertTrue(state.warnings.isEmpty())
    }

    @Test
    fun `a granted camera permission opens the panel`() {
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

    // --- an animated scan --------------------------------------------------

    @Test
    fun `a completed static export fills the fields`() {
        val state = state()
        state.onCameraPermissionGranted()
        state.onScanFrame(UrTestVectors.bcrStatic)

        assertEquals(UrTestVectors.bcrZpub, state.scannedXpub)
        assertEquals("zpub", state.recognisedVersion)
        assertEquals("37b5eed4", state.masterFingerprint)
        assertEquals("84'/0'/0'", state.derivationPath)
        assertNull("a complete export shows no error", state.scanError)
        assertFalse("the panel closes when the export is whole", state.showScanner)
    }

    @Test
    fun `an animated export reports progress before it completes`() {
        val state = state()
        state.onCameraPermissionGranted()

        state.onScanFrame(frames().first())

        assertTrue("the panel must stay open while the export is incomplete", state.showScanner)
        assertTrue("progress must move off zero", state.scanProgress > 0)
        assertNull("nothing is imported from a partial export", state.scannedXpub)
        assertNull(state.scanError)
    }

    @Test
    fun `an animated export completes once enough frames have arrived`() {
        val state = stateAfterAnimatedScan()

        assertEquals(UrTestVectors.bcrZpub, state.scannedXpub)
        assertEquals("37b5eed4", state.masterFingerprint)
        assertEquals("84'/0'/0'", state.derivationPath)
        assertFalse("the panel closes itself on completion", state.showScanner)
        assertEquals("and the progress indicator resets", 0, state.scanProgress)
        assertNull(state.scanError)
    }

    @Test
    fun `out-of-order frames complete the export`() {
        val state = state()
        state.onCameraPermissionGranted()
        listOf(5, 0, 7, 2, 1, 6, 3, 4).forEach { state.onScanFrame(frames()[it]) }

        assertEquals(UrTestVectors.bcrZpub, state.scannedXpub)
        assertFalse(state.showScanner)
    }

    // --- what is refused, and how ------------------------------------------

    @Test
    fun `a text qr is refused in the signing-device screen's own words`() {
        val state = state()
        state.onCameraPermissionGranted()
        state.onScanFrame("bitcoin:zpub6rFR7y4Q2Aij?amount=1")

        assertEquals(UrAccountAssembler.NOT_A_SIGNING_DEVICE_EXPORT, state.scanError)
        assertNull("a bad scan leaves no key behind", state.scannedXpub)
        assertNull(state.masterFingerprint)
        assertNull(state.derivationPath)
        assertFalse("the panel closes so the message is visible", state.showScanner)
    }

    @Test
    fun `a transaction qr is refused as a transaction qr`() {
        val state = state()
        state.onCameraPermissionGranted()
        state.onScanFrame("ur:crypto-psbt/1-2/lpadaacskpc")

        assertEquals(UrAccountAssembler.TRANSACTION_QR_MESSAGE, state.scanError)
        assertNull(state.scannedXpub)
    }

    @Test
    fun `a bad scan clears a previously good one`() {
        val state = stateAfterAnimatedScan()
        assertEquals(UrTestVectors.bcrZpub, state.scannedXpub)

        state.onCameraPermissionGranted()
        state.onScanFrame("nonsense")

        assertNull("the earlier key must not linger looking imported", state.scannedXpub)
        assertNull(state.masterFingerprint)
        assertNull(state.derivationPath)
        assertTrue(state.scanError != null)
    }

    // --- cancel and timeout leave the screen usable ------------------------

    @Test
    fun `cancelling the panel leaves a completed export untouched`() {
        val state = stateAfterAnimatedScan()
        state.onCameraPermissionGranted()
        state.onScannerDismissed()

        assertFalse("cancel closes the panel", state.showScanner)
        assertEquals("cancel must not rewrite what was decoded", UrTestVectors.bcrZpub, state.scannedXpub)
        assertEquals("84'/0'/0'", state.derivationPath)
    }

    @Test
    fun `cancelling a half-assembled export throws the frames away`() {
        val state = state()
        state.onCameraPermissionGranted()
        state.onScanFrame(frames()[0])
        state.onScanFrame(frames()[1])
        assertTrue(state.scanProgress > 0)

        state.onScannerDismissed()

        assertFalse(state.showScanner)
        assertEquals("the next scan starts from nothing", 0, state.scanProgress)

        // A fresh scan of the *same* frames must still assemble: a stale partial
        // assembly must not be spliced onto the new one.
        state.onCameraPermissionGranted()
        frames().forEach(state::onScanFrame)
        assertEquals(UrTestVectors.bcrZpub, state.scannedXpub)
    }

    @Test
    fun `a timeout closes the panel and leaves the screen usable`() {
        val state = state()
        state.onCameraPermissionGranted()
        state.onScanFrame(frames().first())

        state.onScanTimedOut()

        assertFalse("a stalled scan must not leave the camera up forever", state.showScanner)
        assertEquals(0, state.scanProgress)
        assertEquals(AddSigningDeviceScreenState.SCAN_TIMED_OUT_MESSAGE, state.scanError)

        // Usable: the scan button opens a clean panel again, and a real scan works.
        state.onCameraPermissionGranted()
        assertTrue(state.showScanner)
        assertEquals("the new scan starts from nothing", 0, state.scanProgress)
        frames().forEach(state::onScanFrame)
        assertEquals(UrTestVectors.bcrZpub, state.scannedXpub)
        assertNull("a good scan clears the earlier timeout", state.scanError)
    }

    // --- import ------------------------------------------------------------

    @Test
    fun `import saves the decoded account with its fingerprint and path`() {
        val state = stateAfterAnimatedScan()
        state.onNameChange("Cold storage")

        state.import()

        val signer = saved
        assertTrue("the signer was saved", signer != null)
        assertEquals("Cold storage", signer!!.name)
        assertEquals(
            "the record is the same one the watch-only path writes",
            SignerType.WatchOnlyXpub(
                UrTestVectors.bcrZpub,
                masterFingerprint = "37b5eed4",
                derivationPath = "84'/0'/0'",
            ),
            signer.type,
        )
    }

    @Test
    fun `the saved signer round-trips through storage`() {
        val state = stateAfterAnimatedScan()
        state.onNameChange("Cold storage")
        state.import()

        // A brand-new store over the same backing: this is what a relaunch sees.
        val reloaded = SignerStore(backing).list().single()
        val type = reloaded.type as SignerType.WatchOnlyXpub
        assertEquals(UrTestVectors.bcrZpub, type.xpub)
        assertEquals("37b5eed4", type.masterFingerprint)
        assertEquals("84'/0'/0'", type.derivationPath)
        assertEquals("Cold storage", reloaded.name)
    }

    @Test
    fun `a signer imported from a signing device records that origin`() {
        val state = stateAfterAnimatedScan()
        state.onNameChange("Cold storage")

        state.import()

        // The origin is the only thing that tells this signer apart from one
        // typed in as text, and it is what earns it the orange tag — so it has to
        // survive the save, not just sit on the state object.
        assertEquals(SignerOrigin.SIGNING_DEVICE_UR, saved?.importedVia)
        assertEquals(SignerOrigin.SIGNING_DEVICE_UR, SignerStore(backing).list().single().importedVia)
    }

    @Test
    fun `import is refused without a name`() {
        val state = stateAfterAnimatedScan()

        state.import()

        assertNull("nothing is saved without a name", saved)
        assertTrue("the name error is set", state.nameError != null)
    }

    @Test
    fun `import is refused without a scan`() {
        val state = state()
        state.onNameChange("Cold storage")

        state.import()

        assertNull("nothing is saved without a decoded export", saved)
        assertTrue("the scan error is set", state.scanError != null)
    }

    @Test
    fun `import after a bad scan is refused and keeps the scan's message`() {
        val state = state()
        state.onNameChange("Cold storage")
        state.onScanFrame("nonsense")
        val message = state.scanError

        state.import()

        assertNull("a bad scan cannot be imported", saved)
        assertEquals("the specific reason is kept", message, state.scanError)
    }

    @Test
    fun `a name error clears once a name is entered`() {
        val state = stateAfterAnimatedScan()
        state.import()
        assertTrue(state.nameError != null)

        state.onNameChange("Cold storage")

        assertNull(state.nameError)
    }
}
