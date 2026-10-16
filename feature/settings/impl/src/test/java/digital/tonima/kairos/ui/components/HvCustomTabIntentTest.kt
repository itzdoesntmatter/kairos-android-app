package digital.tonima.kairos.ui.components

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Custom Tab launcher for the human-verification flow (security-audit §2.2.22 / C2).
 *
 * The ViewModel resolves and validates the challenge URL's redirect chain; this is the point of
 * use, where the exact string about to be handed to the browser is re-checked. The allowlist
 * decision itself lives in [digital.tonima.protonsync.auth.HvUrlPolicy] (unit-tested in
 * `:proton-sync`); what is pinned here is that the launcher refuses anything the policy
 * refuses, that a Custom Tab (never a plain browse intent, never a WebView) is what opens, and
 * that a non-Activity context still gets a launchable intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HvCustomTabIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an allowlisted url builds a view intent carrying the resolved url`() {
        val intent = hvCustomTabIntent(context, "https://hcaptcha.com/challenge?token=1")

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("https://hcaptcha.com/challenge?token=1", intent.data.toString())
        // A Custom Tab: the session extras are what makes the browser open in-tab.
        assertTrue(intent.hasExtra("android.support.customtabs.extra.SESSION"))
    }

    @Test
    fun `a url outside the allowlist is refused`() {
        assertNull(hvCustomTabIntent(context, "https://evil.example/verify"))
        assertNull(hvCustomTabIntent(context, "https://proton.me.evil.example/verify"))
    }

    @Test
    fun `a plaintext url is refused`() {
        assertNull(hvCustomTabIntent(context, "http://proton.me/verify"))
    }

    @Test
    fun `a malformed url is refused, never thrown`() {
        assertNull(hvCustomTabIntent(context, ""))
        assertNull(hvCustomTabIntent(context, "not a url"))
    }

    @Test
    fun `a non-activity context gets its own task`() {
        val intent = hvCustomTabIntent(context, "https://proton.me/verify")

        assertNotNull(intent)
        assertTrue(intent!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
