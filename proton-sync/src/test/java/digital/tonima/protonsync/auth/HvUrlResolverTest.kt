package digital.tonima.protonsync.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redirect-chain resolution that stands in for "verify the CustomTab's actual resolved URL"
 * (security-audit §2.2.22). The probe is faked so the walk is deterministic: what is pinned here
 * is the policy — every hop is checked, a chain that leaves the allowlist is refused, relative
 * `Location` headers resolve against their base, and a network failure degrades to
 * [HvUrlResolver.Resolution.Unresolved] rather than a dead end.
 */
class HvUrlResolverTest {

    /** Scripted probe: url → result, with a default of "terminal at the requested url". */
    private class FakeProbe(
        private val script: Map<String, HvRedirectProbe.Result>,
    ) : HvRedirectProbe {
        val probed = mutableListOf<String>()

        override fun probe(url: String): HvRedirectProbe.Result {
            probed += url
            return script[url] ?: HvRedirectProbe.Result.Terminal(url)
        }
    }

    @Test
    fun `a direct challenge url resolves to itself`() = runTest {
        val probe = FakeProbe(emptyMap())

        val resolution = HvUrlResolver(probe).resolve("https://hcaptcha.com/challenge")

        assertEquals(HvUrlResolver.Resolution.Resolved("https://hcaptcha.com/challenge"), resolution)
        assertEquals(listOf("https://hcaptcha.com/challenge"), probe.probed)
    }

    @Test
    fun `allowlisted hops are followed and the final url is what gets opened`() = runTest {
        val probe =
            FakeProbe(
                mapOf(
                    "https://proton.me/verify" to
                        HvRedirectProbe.Result.Redirect("https://proton.me/verify", "/hv?token=1"),
                    "https://proton.me/hv?token=1" to
                        HvRedirectProbe.Result.Redirect("https://proton.me/hv?token=1", "https://hcaptcha.com/challenge"),
                ),
            )

        val resolution = HvUrlResolver(probe).resolve("https://proton.me/verify")

        assertEquals(HvUrlResolver.Resolution.Resolved("https://hcaptcha.com/challenge"), resolution)
        assertEquals(
            // Every hop is probed, including the terminal one — that is what makes the
            // resolved URL "the URL the tab would load", not just a checked intermediate.
            listOf("https://proton.me/verify", "https://proton.me/hv?token=1", "https://hcaptcha.com/challenge"),
            probe.probed,
        )
    }

    @Test
    fun `a chain that leaves the allowlist is refused`() = runTest {
        val probe =
            FakeProbe(
                mapOf(
                    "https://proton.me/verify" to
                        HvRedirectProbe.Result.Redirect("https://proton.me/verify", "https://evil.example/steal"),
                ),
            )

        val resolution = HvUrlResolver(probe).resolve("https://proton.me/verify")

        assertTrue(resolution is HvUrlResolver.Resolution.Refused)
        // The hostile hop is never even probed: the allowlist check runs first.
        assertEquals(listOf("https://proton.me/verify"), probe.probed)
    }

    @Test
    fun `the original url is checked before anything is probed`() = runTest {
        val probe = FakeProbe(emptyMap())

        val resolution = HvUrlResolver(probe).resolve("https://evil.example/verify")

        assertTrue(resolution is HvUrlResolver.Resolution.Refused)
        assertTrue(probe.probed.isEmpty())
    }

    @Test
    fun `a redirect loop stops at the hop ceiling`() = runTest {
        val looping =
            HvRedirectProbe.Result.Redirect("https://proton.me/a", "https://proton.me/a")
        val probe = FakeProbe(mapOf("https://proton.me/a" to looping))

        val resolution = HvUrlResolver(probe, maxRedirects = 3).resolve("https://proton.me/a")

        assertTrue(resolution is HvUrlResolver.Resolution.Unresolved)
        assertEquals(3, probe.probed.size)
    }

    @Test
    fun `an unreachable host degrades to unresolved for the caller's fallback`() = runTest {
        val probe =
            FakeProbe(
                mapOf(
                    "https://proton.me/verify" to HvRedirectProbe.Result.Unreachable("timeout"),
                ),
            )

        val resolution = HvUrlResolver(probe).resolve("https://proton.me/verify")

        assertEquals(HvUrlResolver.Resolution.Unresolved("timeout"), resolution)
    }

    @Test
    fun `a redirect to a non-http scheme is refused`() = runTest {
        val probe =
            FakeProbe(
                mapOf(
                    "https://proton.me/verify" to
                        HvRedirectProbe.Result.Redirect("https://proton.me/verify", "javascript:alert(1)"),
                ),
            )

        val resolution = HvUrlResolver(probe).resolve("https://proton.me/verify")

        assertTrue(resolution is HvUrlResolver.Resolution.Refused)
    }
}
