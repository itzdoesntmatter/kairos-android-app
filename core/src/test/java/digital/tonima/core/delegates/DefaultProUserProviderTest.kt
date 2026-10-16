package digital.tonima.core.delegates

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProUserProviderTest {
    @Test
    fun `every user is a Pro user`() =
        runTest {
            assertTrue(DefaultProUserProvider().isProUser.first())
        }

    @Test
    fun `every user is an AI user`() =
        runTest {
            assertTrue(DefaultProUserProvider().isAiUser.first())
        }

    @Test
    fun `refresh keeps everyone free`() =
        runTest {
            val provider = DefaultProUserProvider()

            provider.refresh()

            assertTrue(provider.isProUser.first())
            assertTrue(provider.isAiUser.first())
        }
}
