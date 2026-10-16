package digital.tonima.protonsync.api

import digital.tonima.protonsync.auth.TokenRefresher
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionStore
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProtonApiClientFactoryTest {

    @Test
    fun `three distinct SPKI pins cover leaf, intermediate and root`() {
        val pins = listOf(
            ProtonApiClientFactory.SPKI_PIN_LEAF,
            ProtonApiClientFactory.SPKI_PIN_INTERMEDIATE,
            ProtonApiClientFactory.SPKI_PIN_ROOT,
        )

        assertEquals(3, pins.distinct().size)
        pins.forEach { pin ->
            assertTrue(pin.startsWith("sha256/"))
            assertEquals(44 + 7, pin.length) // "sha256/" + 44 base64 chars
        }
    }

    @Test
    fun `client identity is honest — never impersonates Proton's identifiers`() {
        val identity = ProtonClientIdentity(versionName = "1.2.3", versionCode = 42)

        assertEquals("Kairos_42", identity.appVersionHeader)
        assertTrue(identity.userAgent.startsWith("Kairos/1.2.3 (Android "))
        assertFalse(identity.userAgent.contains("ProtonMail"))
        assertFalse(identity.userAgent.contains("me.proton"))
        assertFalse(identity.appVersionHeader.contains("ProtonMail"))
    }

    @Test
    fun `creates a ProtonApiClient for the pinned base url`() {
        val sessionStore = SessionStore(
            java.io.File.createTempFile("proton-test", ".dat").apply { deleteOnExit() },
            FakeCrypto(),
        )
        val manager = SessionManager(sessionStore)
        val refresher = TokenRefresher(api = mockk(relaxed = true), sessionManager = manager)
        val factory = ProtonApiClientFactory(ProtonClientIdentity(versionName = "test", versionCode = 1))

        val client = factory.create(manager, refresher)

        // Smoke: the Retrofit proxy builds (validates the whole interceptor/pinner wiring).
        assertEquals(ProtonApiClient::class.java, client.javaClass.interfaces.first())
    }

    private class FakeCrypto : digital.tonima.protonsync.session.SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(7) + plaintext
        override fun decrypt(data: ByteArray): ByteArray = data.copyOfRange(1, data.size)
    }
}
