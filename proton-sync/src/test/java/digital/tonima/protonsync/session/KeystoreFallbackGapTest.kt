package digital.tonima.protonsync.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import digital.tonima.protonsync.api.dto.AuthResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Keystore-unavailable behaviour, asserted to **fail closed** (specs/test-plan.md SESS-05/SESS-10;
 * security-audit.md §2.4.17, §3.1). Robolectric ships no AndroidKeyStore provider (architecture
 * §10), which is exactly the condition under test: the bridge must refuse to store session
 * material rather than degrade to a weaker container — the reference app's plaintext `B64:`
 * fallback is forbidden for Kairos.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreFallbackGapTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the production session store refuses to build without a Keystore`() {
        val error = assertThrows(SessionCryptoException::class.java) {
            SessionStore.create(context)
        }

        assertTrue(error.message!!.contains("refusing"))
        assertFalse(
            "a failed crypto must not create the session directory",
            File(context.filesDir, "proton-session").exists(),
        )
    }

    @Test
    fun `a keystore failure during login is surfaced and writes nothing - FINDING F-4`() {
        // FINDING F-4 (LOD-12): adoption happens BEFORE the persist, so when the persist throws
        // the caller sees the exception (good — no silent success) but the in-memory session is
        // left adopted and ACTIVE with nothing on disk. Adopt-after-persist (or clear on failure)
        // is the fail-closed shape; security-audit §2.3.16 also requires the account to be created
        // only after a successful persist. Owner: code-agent (LOD-12 F-4).
        val sessionFile = File(context.filesDir, "proton-session/session.dat")
        val manager = SessionManager(SessionStore(sessionFile, FailingCrypto()))

        val error = assertThrows(SessionCryptoException::class.java) {
            manager.onAuthenticated(
                AuthResponse(
                    code = 1000,
                    accessToken = "access",
                    refreshToken = "refresh",
                    uid = "uid",
                    scope = "full",
                ),
            )
        }

        assertTrue(error.message!!.contains("keystore gone"))
        assertFalse("nothing may be persisted when the cipher is dead", sessionFile.exists())
        assertEquals(SessionState.ACTIVE, manager.currentState())
    }

    @Test
    fun `wipe completes without the Keystore key and is idempotent`() {
        // SESS-10: deletion must not need the key — a wiped or invalidated Keystore key can never
        // strand session material on disk.
        val dir = File(context.filesDir, "proton-session").apply { mkdirs() }
        val sessionFile = File(dir, "session.dat").apply { writeBytes("PSS1".toByteArray() + ByteArray(32) { 3 }) }
        val store = SessionStore(sessionFile, FailingCrypto())

        store.wipe()
        store.wipe()

        assertFalse(sessionFile.exists())
    }

    /** Test double for a Keystore that is gone: both directions refuse. */
    private class FailingCrypto : SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            throw SessionCryptoException("keystore gone")

        override fun decrypt(data: ByteArray): ByteArray =
            throw SessionCryptoException("keystore gone")
    }
}
