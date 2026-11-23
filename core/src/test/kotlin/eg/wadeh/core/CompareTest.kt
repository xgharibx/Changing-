package eg.wadeh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompareTest {
    private fun q1(id: String, parkingKnown: Boolean = true, basis: String = "GROSS") =
        CompareInput(id, "EGP", 800_000_000, "instalment_list", null, "165.00", basis, "2029-Q4", Fixtures.egyQ1(parkingKnown))

    @Test fun `engine figures flow into comparison unchanged`() {
        val c = Compare.build(listOf(q1("a")))
        val r = c.rows.single()
        assertEquals(852_000_000L, r.knownMandatoryMinor)
        assertEquals(80_000_000L, r.cashToStartMinor)
        assertEquals(170_000_000L, r.first12MonthsMinor)
        assertEquals(82_000_000L, r.largestSingleDateMinor)
        assertEquals(4_848_485L, r.pricePerM2Minor) // 8,000,000 / 165 = 48,484.85
    }

    @Test fun `unknown mandatory fee is never declared the lowest total`() {
        val c = Compare.build(listOf(q1("known"), q1("unknown", parkingKnown = false)))
        assertFalse("known_mandatory" in c.lowest)
        assertTrue("known_mandatory_lowest" in c.suppressed)
    }

    @Test fun `price per m2 suppressed when area bases differ`() {
        val c = Compare.build(listOf(q1("g"), q1("n", basis = "NET")))
        assertTrue(c.rows.all { it.pricePerM2Minor == null })
        assertEquals("Area basis differs: GROSS vs NET", c.suppressed["price_per_m2"])
    }

    @Test fun `different currencies suppress every lowest marker`() {
        val c = Compare.build(listOf(q1("e"), q1("k").copy(currency = "KWD", plan = Fixtures.egyQ1().copy(currency = "KWD"))))
        assertTrue(c.lowest.isEmpty()); assertTrue("all_money" in c.suppressed)
    }
}
