package eg.wadeh.core

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ParseTest {
    private fun amt(s: String) = (SupplierParse.amount(s) as Parsed.Ok).value.stripTrailingZeros()

    @Test fun amounts() {
        assertEquals(BigDecimal("8000000").stripTrailingZeros(), amt("8,000,000"))
        assertEquals(BigDecimal("8000000").stripTrailingZeros(), amt("٨٬٠٠٠٬٠٠٠ ج.م"))
        assertEquals(BigDecimal("8000000").stripTrailingZeros(), amt("EGP 8.000.000"))
        assertEquals(BigDecimal("8500000").stripTrailingZeros(), amt("8.5 مليون"))
        assertEquals(BigDecimal("750000").stripTrailingZeros(), amt("750 ألف جنيه"))
        assertEquals(BigDecimal("1234.5").stripTrailingZeros(), amt("1234.50"))
        assertIs<Parsed.Err>(SupplierParse.amount("call us"))
        assertIs<Parsed.Missing>(SupplierParse.amount("—"))
    }

    @Test fun dates() {
        assertEquals("2027-01-03", (SupplierParse.date("٣/١/٢٠٢٧") as Parsed.Ok).value.iso())
        val amb = SupplierParse.date("03/01/2027") as Parsed.Ok
        assertNotNull(amb.warning)
        assertEquals("2027-01-03", amb.value.iso())
        assertEquals("2027-Q3", (SupplierParse.date("Q3 2027") as Parsed.Ok).value.iso())
        assertEquals("2028-03", (SupplierParse.date("2028-03") as Parsed.Ok).value.iso())
        assertIs<Parsed.Err>(SupplierParse.date("31/02/2027"))
    }

    @Test fun normalizeArabic() {
        assertEquals("التجمع الخامس", ArabicText.normalize("التَّجمّع الخامس"))
        assertEquals(ArabicText.normalize("القاهرة الجديدة"), ArabicText.normalize("القاهره الجديده"))
        assertEquals("مدينتي 2", ArabicText.normalize("مدينتي ٢"))
    }

    @Test fun money() {
        assertEquals("EGP 8,000,000", MoneyFormat.format(Money.ofMajor("8000000", "EGP"), false))
        assertEquals("٨٬٠٠٠٬٠٠٠ ج.م", MoneyFormat.format(Money.ofMajor("8000000", "EGP"), true))
        assertEquals("EGP 333,333.33", MoneyFormat.format(Money(33_333_333, "EGP"), false))
    }

    @Test fun availabilityRules() {
        val t0 = Instant.parse("2026-11-01T10:00:00Z"); val t1 = Instant.parse("2026-11-05T10:00:00Z"); val tOld = Instant.parse("2026-10-20T10:00:00Z")
        // Sold, then an OLDER available file -> ignored
        assertIs<AvailabilityRules.Decision.Ignore>(AvailabilityRules.decide(Availability.SOLD, t1, Availability.AVAILABLE, tOld, false, null))
        // Sold, then a NEWER available without correction -> ignored
        assertIs<AvailabilityRules.Decision.Ignore>(AvailabilityRules.decide(Availability.SOLD, t0, Availability.AVAILABLE, t1, false, null))
        // Newer correction with reason -> reopens
        assertIs<AvailabilityRules.Decision.Apply>(AvailabilityRules.decide(Availability.SOLD, t0, Availability.AVAILABLE, t1, true, "Buyer cancelled contract"))
        // Duplicate -> ignored
        assertIs<AvailabilityRules.Decision.Ignore>(AvailabilityRules.decide(Availability.SOLD, t0, Availability.SOLD, t1, false, null))
    }
}
