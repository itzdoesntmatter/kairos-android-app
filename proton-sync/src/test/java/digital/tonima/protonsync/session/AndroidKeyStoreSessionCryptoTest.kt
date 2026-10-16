package digital.tonima.protonsync.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric has no AndroidKeyStore provider (architecture §10: its Keystore is a mock;
 * the real AES-GCM round-trip is a device-gated W3 test). What IS testable here is the
 * security contract: without the Keystore the crypto refuses to operate — there is no
 * plaintext fallback path of any kind (security-audit §3.1 forbids the reference app's
 * `B64:` degradation).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyStoreSessionCryptoTest {

    @Test
    fun `without an AndroidKeyStore the crypto refuses to construct — no fallback path`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val e = assertThrows(SessionCryptoException::class.java) {
            AndroidKeyStoreSessionCrypto(context)
        }

        assertTrue(e.message!!.contains("refusing"))
    }
}
