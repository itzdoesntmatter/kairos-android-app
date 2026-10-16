package digital.tonima.protonsync.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeValidatorTest {

    private val validator = ScopeValidator()

    @Test
    fun `full scope is valid`() {
        assertTrue(validator.isValid("full"))
        assertTrue(validator.isValid(" full "))
    }

    @Test
    fun `calendar scope is valid`() {
        assertTrue(validator.isValid("calendar"))
        assertTrue(validator.isValid("mail calendar settings"))
    }

    @Test
    fun `case differences do not matter`() {
        assertTrue(validator.isValid("Full"))
        assertTrue(validator.isValid("Calendar"))
    }

    @Test
    fun `scopes without calendar access are rejected`() {
        assertFalse(validator.isValid("mail"))
        assertFalse(validator.isValid("mail drive wallet"))
        assertFalse(validator.isValid(""))
    }
}
