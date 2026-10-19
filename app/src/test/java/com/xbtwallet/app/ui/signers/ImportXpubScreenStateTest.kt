package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.crypto.XpubScanRoute
import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.SignerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import screen's scanner state, exercised without any Compose UI.
 *
 * The camera itself is not testable on the JVM and the panel is verified by
 * `assembleDebug`, but *when the panel is allowed to appear* is a rule, not a
 * view: the panel opens only once CAMERA is granted, a refusal must leave the
 * paste path working, and a scan must enter the field through the same
 * `onXpubChange` → `validateXpubInput` seam a paste uses. That is what these
 * tests pin.
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

    private fun state() = ImportXpubScreenState(SignerStore(InMemoryStore())) {}

    @Test
    fun `the embedded panel is closed and nothing is denied at first`() {
        val state = state()
        assertFalse("the scanner must not open by itself", state.showScanner)
        assertFalse("no denial has happened yet", state.cameraDenied)
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

    @Test
    fun `cancelling the panel leaves the field untouched`() {
        val state = state()
        state.onXpubChange("half-typed")
        state.onCameraPermissionGranted()
        state.onScannerDismissed()
        assertFalse("cancel closes the panel", state.showScanner)
        assertEquals("cancel must not rewrite the field", "half-typed", state.xpub)
    }

    @Test
    fun `a refused camera still allows a pasted key`() {
        val state = state()
        state.onCameraPermissionDenied()
        state.onXpubChange(zpub)
        assertEquals(zpub, state.xpub)
        assertEquals("zpub", state.recognisedVersion)
        assertNull(state.xpubError)
    }

    @Test
    fun `a scanned payload takes the same validation path as a paste`() {
        val state = state()
        state.onCameraPermissionGranted()
        state.onXpubChange(XpubScanRoute.extract("bitcoin:$zpub?amount=1&label=Cold"))
        state.onScannerDismissed()

        assertEquals("the routed payload lands in the field", zpub, state.xpub)
        assertEquals("and is validated exactly as a paste", "zpub", state.recognisedVersion)
        assertNull("a valid scan shows no error", state.xpubError)
        assertFalse(state.showScanner)
    }

    @Test
    fun `a scanned payload that is nonsense is refused by the shared validator`() {
        val state = state()
        state.onXpubChange(XpubScanRoute.extract("bitcoin:not-a-key?amount=1"))
        assertEquals("not-a-key", state.xpub)
        assertNull("nonsense never becomes a recognised version", state.recognisedVersion)
    }
}
