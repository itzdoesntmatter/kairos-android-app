package digital.tonima.protonsync.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The human-verification URL policy (security-audit §2.2.22, auth-flow.md §2.7). One allowlist
 * serves the login flow, the redirect resolver and the Custom Tab launcher — these cases pin
 * the rules every one of those call sites relies on, including the look-alike hosts a hostile
 * server would try (`proton.me.evil.example`, userinfo tricks, plaintext).
 */
class HvUrlPolicyTest {

    @Test
    fun `the reference allowlist hosts are allowed over https`() {
        assertTrue(HvUrlPolicy.isAllowed("https://proton.me/verify"))
        assertTrue(HvUrlPolicy.isAllowed("https://protonmail.com/hv?token=1"))
        assertTrue(HvUrlPolicy.isAllowed("https://hcaptcha.com/challenge"))
    }

    @Test
    fun `the host check is case-insensitive`() {
        assertTrue(HvUrlPolicy.isAllowed("HTTPS://Hcaptcha.COM/challenge"))
    }

    @Test
    fun `plaintext http is refused`() {
        assertFalse(HvUrlPolicy.isAllowed("http://proton.me/verify"))
    }

    @Test
    fun `non-http schemes are refused`() {
        assertFalse(HvUrlPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(HvUrlPolicy.isAllowed("intent://proton.me/#Intent;end"))
        assertFalse(HvUrlPolicy.isAllowed("file://proton.me/etc/passwd"))
    }

    @Test
    fun `look-alike and suffix hosts are refused`() {
        assertFalse(HvUrlPolicy.isAllowed("https://proton.me.evil.example/verify"))
        assertFalse(HvUrlPolicy.isAllowed("https://notproton.me/verify"))
        assertFalse(HvUrlPolicy.isAllowed("https://protonmail.com.evil.example/hv"))
        assertFalse(HvUrlPolicy.isAllowed("https://evil.example/proton.me"))
    }

    @Test
    fun `userinfo cannot smuggle an allowed host`() {
        // URI parses the real host as evil.example; the allowlisted token is only userinfo.
        assertFalse(HvUrlPolicy.isAllowed("https://proton.me@evil.example/verify"))
    }

    @Test
    fun `malformed and relative input is refused, never thrown`() {
        assertFalse(HvUrlPolicy.isAllowed(""))
        assertFalse(HvUrlPolicy.isAllowed("   "))
        assertFalse(HvUrlPolicy.isAllowed("proton.me/verify"))
        assertFalse(HvUrlPolicy.isAllowed("://proton.me"))
        assertFalse(HvUrlPolicy.isAllowed("https://"))
    }
}
