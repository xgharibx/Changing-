package eg.wadeh.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Golden fixtures. All data is synthetic. */
object Fixtures {
    private fun egp(pounds: Long) = pounds * 100
    private fun d(s: String) = PreciseDate.day(LocalDate.parse(s))

    /** EGY-Q1 from the founder brief. */
    fun egyQ1(parkingKnown: Boolean = true) = PaymentPlan(
        currency = "EGP", listPriceMinor = egp(8_000_000),
        items = listOf(
            PlanItem("res", ItemKind.RESERVATION, Amount.Fixed(egp(100_000)), Due.On(d("2027-01-01")), includedIn = "dep"),
            PlanItem("dep", ItemKind.DEPOSIT, Amount.Fixed(egp(800_000)), Due.On(d("2027-01-01"))),
            PlanItem("q", ItemKind.INSTALMENT, Amount.Fixed(egp(300_000)), Due.Every(d("2027-04-01"), 3, 24)),
            PlanItem("mnt", ItemKind.MAINTENANCE, Amount.Fixed(egp(400_000)), Due.On(d("2030-01-01"))),
            PlanItem("park", ItemKind.PARKING, if (parkingKnown) Amount.Fixed(egp(120_000)) else Amount.Unknown, Due.On(d("2030-01-01")),
                certainty = if (parkingKnown) Certainty.FIXED else Certainty.UNKNOWN),
        ),
    )
}

class CashFlowTest {
    private fun egp(p: Long) = p * 100

    @Test fun `EGY-Q1 known mandatory total is 8,520,000`() {
        val s = CashFlowEngine.compute(Fixtures.egyQ1())
        assertEquals(egp(8_520_000), s.knownMandatoryMinor)
        assertTrue(s.exactTotalKnown)
        assertEquals(egp(8_520_000), s.exactMandatoryTotalMinor)
    }

    @Test fun `EGY-Q1 due through 31 Dec 2027 inclusive is 1,700,000`() {
        val s = CashFlowEngine.compute(Fixtures.egyQ1())
        assertEquals(egp(1_700_000), CashFlowEngine.dueBetween(s.schedule, LocalDate.of(2000, 1, 1), LocalDate.of(2027, 12, 31)))
    }

    @Test fun `EGY-Q1 due on 1 Jan 2030 is 820,000 and is the largest date`() {
        val s = CashFlowEngine.compute(Fixtures.egyQ1())
        assertEquals(egp(820_000), CashFlowEngine.dueOn(s.schedule, LocalDate.of(2030, 1, 1)))
        assertEquals("2030-01-01", s.largestSingleDate)
        assertEquals(egp(820_000), s.largestSingleDateMinor)
    }

    @Test fun `reservation is credited inside the deposit, not added`() {
        val s = CashFlowEngine.compute(Fixtures.egyQ1())
        assertEquals(egp(800_000), s.cashToStartMinor)
        val dep = s.schedule.single { it.itemId == "dep" }
        assertEquals(listOf("res"), dep.creditedFrom)
        assertTrue(s.schedule.none { it.itemId == "res" })
        assertEquals(24, s.schedule.count { it.itemId == "q" })
        assertEquals("2033-01-01", s.schedule.filter { it.itemId == "q" }.last().date)
    }

    @Test fun `unknown parking gives 8,400,000 known subtotal and no exact total`() {
        val s = CashFlowEngine.compute(Fixtures.egyQ1(parkingKnown = false))
        assertEquals(egp(8_400_000), s.knownMandatoryMinor)
        assertFalse(s.exactTotalKnown)
        assertNull(s.exactMandatoryTotalMinor)
        assertEquals(listOf("park"), s.unresolved.map { it.itemId })
    }

    @Test fun `rental keeps deposit and rent on their own lines`() {
        val plan = PaymentPlan(
            currency = "EGP", listPriceMinor = egp(25_000),
            items = listOf(
                PlanItem("rent", ItemKind.RENT, Amount.Fixed(egp(25_000)), Due.Every(PreciseDate.day(LocalDate.parse("2027-02-01")), 1, 12)),
                PlanItem("sec", ItemKind.SECURITY_DEPOSIT, Amount.Fixed(egp(50_000)), Due.On(PreciseDate.day(LocalDate.parse("2027-02-01"))), refundable = true),
                PlanItem("brk", ItemKind.BROKERAGE, Amount.Fixed(egp(12_500)), Due.On(PreciseDate.day(LocalDate.parse("2027-02-01")))),
            ),
        )
        val s = CashFlowEngine.compute(plan)
        assertEquals(egp(300_000), s.rentMinor)
        assertEquals(egp(50_000), s.refundableMinor)
        assertEquals(egp(12_500), s.knownMandatoryMinor) // only non-refundable, non-rent charges
    }

    @Test fun `awkward rounding puts the remainder on the last instalment`() {
        val plan = PaymentPlan(
            currency = "EGP", listPriceMinor = egp(1_000_000),
            items = listOf(PlanItem("q", ItemKind.INSTALMENT, Amount.Split(egp(1_000_000)), Due.Every(PreciseDate.day(LocalDate.parse("2027-01-15")), 1, 3))),
        )
        val s = CashFlowEngine.compute(plan)
        assertEquals(listOf(33_333_333L, 33_333_333L, 33_333_334L), s.schedule.map { it.amountMinor })
        assertEquals(egp(1_000_000), s.knownMandatoryMinor)
    }

    @Test fun `percentage rounds half up once per item`() {
        // 7.5% of EGP 1,234,567.89 = 92,592.59175 -> 92,592.59
        val plan = PaymentPlan("EGP", 123_456_789, items = listOf(
            PlanItem("m", ItemKind.MAINTENANCE, Amount.Percent(750, PercentBasis.LIST_PRICE), Due.AtHandover)))
        assertEquals(9_259_259L, CashFlowEngine.resolveAmount(plan, plan.items[0].amount))
    }

    @Test fun `discount changes percentage fee basis only for contract-price items`() {
        // List 8,000,000; 10% cash discount -> contract 7,200,000. Maintenance 8% of contract price, club 1% of list price.
        val plan = PaymentPlan("EGP", egp(8_000_000), contractPriceMinor = egp(7_200_000), items = listOf(
            PlanItem("cash", ItemKind.DEPOSIT, Amount.Fixed(egp(7_200_000)), Due.On(PreciseDate.day(LocalDate.parse("2027-01-01")))),
            PlanItem("m", ItemKind.MAINTENANCE, Amount.Percent(800, PercentBasis.CONTRACT_PRICE), Due.On(PreciseDate.day(LocalDate.parse("2028-01-01")))),
            PlanItem("c", ItemKind.CLUB, Amount.Percent(100, PercentBasis.LIST_PRICE), Due.On(PreciseDate.day(LocalDate.parse("2028-01-01")))),
        ))
        val s = CashFlowEngine.compute(plan)
        assertEquals(egp(576_000), s.schedule.single { it.itemId == "m" }.amountMinor) // not 640,000
        assertEquals(egp(80_000), s.schedule.single { it.itemId == "c" }.amountMinor)
        assertEquals(egp(7_856_000), s.knownMandatoryMinor)
    }

    @Test fun `handover-due item with quarter precision is unresolved in date but counted in total`() {
        val plan = PaymentPlan("EGP", egp(1_000_000), handover = PreciseDate.quarter(2029, 3), items = listOf(
            PlanItem("h", ItemKind.HANDOVER, Amount.Fixed(egp(100_000)), Due.AtHandover)))
        val s = CashFlowEngine.compute(plan)
        assertEquals("2029-Q3", s.schedule.single().date)
        assertEquals(egp(100_000), s.knownMandatoryMinor)
        assertEquals(0L, s.first12MonthsMinor) // not day-precise, so never claimed inside a window
    }

    @Test fun `three-minor-digit currency works (portability)`() {
        val plan = PaymentPlan("KWD", 100_000_000, items = listOf(
            PlanItem("m", ItemKind.MAINTENANCE, Amount.Percent(333, PercentBasis.LIST_PRICE), Due.Unknown)))
        assertEquals(3_330_000L, CashFlowEngine.resolveAmount(plan, plan.items[0].amount))
        assertEquals("KWD 100,000", MoneyFormat.format(Money(100_000_000, "KWD"), arabic = false))
    }
}
