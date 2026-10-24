package com.xbtwallet.app.ui.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app lock's rules, driven without a device.
 *
 * The prompt is behind [LockAuthenticator], so a scripted fake stands in for
 * biometrics and device credentials and the whole gate is a JVM test: cold start
 * is locked, a cancel leaves it locked with the button able to ask again, every
 * backgrounding re-locks, and a device with no lock screen opens with a one-time
 * warning and refuses to create a hot wallet.
 */
class AppLockStateTest {

    /** A prompt that answers the way the test tells it to. */
    private class ScriptedAuthenticator(
        var succeeds: Boolean = true,
        var message: String? = null,
    ) : LockAuthenticator {
        var calls = 0
            private set

        var lastCryptoObject: Any? = null
            private set

        override fun authenticate(
            cryptoObject: Any?,
            onSuccess: () -> Unit,
            onCancel: (String?) -> Unit,
        ) {
            calls++
            lastCryptoObject = cryptoObject
            if (succeeds) onSuccess() else onCancel(message)
        }
    }

    private var warningDismissals = 0

    private fun lock(
        deviceSecure: Boolean = true,
        warningDismissed: Boolean = false,
        authenticator: ScriptedAuthenticator = ScriptedAuthenticator(),
    ): Pair<AppLockState, ScriptedAuthenticator> = AppLockState(
        deviceSecure = deviceSecure,
        authenticator = authenticator,
        warningDismissed = warningDismissed,
        onWarningDismissed = { warningDismissals++ },
    ) to authenticator

    // --- cold start ----------------------------------------------------------

    @Test
    fun `cold start is locked on a device with a lock screen`() {
        val (state, _) = lock()

        assertTrue("nothing renders behind the gate", state.locked)
        assertFalse(state.authenticating)
        assertNull(state.error)
        assertFalse("there is nothing to warn about", state.warningVisible)
    }

    @Test
    fun `cold start is open on a device with no lock screen`() {
        val (state, _) = lock(deviceSecure = false)

        assertFalse("there is nothing to authenticate against", state.locked)
        assertTrue("but the user is told", state.warningVisible)
    }

    // --- unlocking -----------------------------------------------------------

    @Test
    fun `a successful prompt clears the gate`() {
        val (state, auth) = lock()

        state.unlock()

        assertEquals(1, auth.calls)
        assertFalse(state.locked)
        assertFalse(state.authenticating)
        assertNull(state.error)
    }

    @Test
    fun `a dismissed prompt leaves the gate up and the button can ask again`() {
        val (state, auth) = lock(authenticator = ScriptedAuthenticator(succeeds = false, message = "Too many attempts"))

        state.unlock()

        assertTrue("the app is still locked", state.locked)
        assertEquals("Too many attempts", state.error)
        assertFalse(state.authenticating)

        // The Unlock button re-invokes the prompt, and this time it succeeds.
        auth.succeeds = true
        state.unlock()

        assertEquals("the button asks again", 2, auth.calls)
        assertFalse(state.locked)
        assertNull("a success clears the last refusal", state.error)
    }

    @Test
    fun `a prompt the user backed out of reads as no message`() {
        val (state, _) = lock(authenticator = ScriptedAuthenticator(succeeds = false, message = null))

        state.unlock()

        assertTrue(state.locked)
        assertNull(state.error)
    }

    @Test
    fun `unlock does nothing while a prompt is already up`() {
        val auth = object : LockAuthenticator {
            var calls = 0
            override fun authenticate(cryptoObject: Any?, onSuccess: () -> Unit, onCancel: (String?) -> Unit) {
                calls++
                // Deliberately never answers: the prompt stays in flight.
            }
        }
        val state = AppLockState(deviceSecure = true, authenticator = auth, warningDismissed = false, onWarningDismissed = {})

        state.unlock()
        state.unlock()

        assertEquals("a second tap must not stack prompts", 1, auth.calls)
        assertTrue(state.authenticating)
    }

    @Test
    fun `unlock is a no-op on a device with no lock screen`() {
        val (state, auth) = lock(deviceSecure = false)

        state.unlock()

        assertEquals(0, auth.calls)
        assertFalse(state.locked)
    }

    // --- re-locking on every backgrounding -----------------------------------

    @Test
    fun `leaving the foreground locks again`() {
        val (state, _) = lock()
        state.unlock()
        assertFalse(state.locked)

        state.onLeftForeground()

        assertTrue("any backgrounding re-locks — there is no grace period", state.locked)
        assertFalse(state.authenticating)
        assertNull(state.error)
    }

    @Test
    fun `leaving the foreground is a no-op with no lock screen`() {
        val (state, _) = lock(deviceSecure = false)

        state.onLeftForeground()

        assertFalse(state.locked)
    }

    // --- the no-device-lock warning ------------------------------------------

    @Test
    fun `dismissing the warning hides it and records the choice`() {
        val (state, _) = lock(deviceSecure = false)

        state.dismissWarning()

        assertFalse(state.warningVisible)
        assertEquals(1, warningDismissals)

        // Idempotent: a second dismissal writes nothing more.
        state.dismissWarning()
        assertEquals(1, warningDismissals)
    }

    @Test
    fun `an already-dismissed warning is not shown again`() {
        val (state, _) = lock(deviceSecure = false, warningDismissed = true)

        assertFalse(state.warningVisible)
    }

    // --- the hot-wallet block ------------------------------------------------

    @Test
    fun `creating a hot wallet is blocked exactly when there is no lock screen`() {
        val (secure, _) = lock(deviceSecure = true)
        val (insecure, _) = lock(deviceSecure = false)

        assertFalse(secure.hotWalletCreationBlocked)
        assertTrue(insecure.hotWalletCreationBlocked)
    }
}
