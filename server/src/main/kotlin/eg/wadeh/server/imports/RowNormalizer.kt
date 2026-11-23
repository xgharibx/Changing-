package eg.wadeh.server.imports

import eg.wadeh.core.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate

@Serializable
data class NormalizedRow(
    val projectRef: String,
    val phase: String?,
    val building: String?,
    val unitCode: String,
    val listingKey: String,
    val unitType: UnitType,
    val bedrooms: Int?,
    val bathrooms: Int?,
    val areaM2: String?,
    val areaBasis: AreaBasis,
    val floor: String?,
    val finishing: Finishing,
    val availability: Availability,
    val offerType: OfferType,
    val currency: String,
    val listPriceMinor: Long?,
    val cashPriceMinor: Long?,
    val monthlyRentMinor: Long?,
    val plan: PaymentPlan?,
    val handover: String?,
    val assertedOn: String,          // ISO date
    val correction: Boolean,
    val correctionReason: String?,
    val notes: String?,
) {
    /** Hash of the commercial facts only. The asserted date is excluded so re-sending identical facts is detectable. */
    fun offerFacts(): String = kotlinx.serialization.json.Json.encodeToString(
        OfferFacts.serializer(), OfferFacts(currency, listPriceMinor, cashPriceMinor, monthlyRentMinor, plan, handover, notes)
    )
}

@Serializable
data class OfferFacts(val currency: String, val list: Long?, val cash: Long?, val rent: Long?, val plan: PaymentPlan?, val handover: String?, val notes: String?)

data class RowResult(val row: NormalizedRow?, val errors: List<String>, val warnings: List<String>)

object RowNormalizer {
    private val unitTypes = mapOf(
        "apartment" to UnitType.APARTMENT, "شقه" to UnitType.APARTMENT, "flat" to UnitType.APARTMENT,
        "studio" to UnitType.STUDIO, "ستوديو" to UnitType.STUDIO, "استوديو" to UnitType.STUDIO,
        "duplex" to UnitType.DUPLEX, "دوبلكس" to UnitType.DUPLEX,
        "penthouse" to UnitType.PENTHOUSE, "بنتهاوس" to UnitType.PENTHOUSE, "روف" to UnitType.PENTHOUSE,
        "villa" to UnitType.VILLA, "فيلا" to UnitType.VILLA, "standalone" to UnitType.VILLA,
        "townhouse" to UnitType.TOWNHOUSE, "تاون هاوس" to UnitType.TOWNHOUSE,
        "twinhouse" to UnitType.TWINHOUSE, "twin house" to UnitType.TWINHOUSE, "توين هاوس" to UnitType.TWINHOUSE,
        "chalet" to UnitType.CHALET, "شاليه" to UnitType.CHALET,
        "land" to UnitType.LAND, "ارض" to UnitType.LAND,
        "office" to UnitType.OFFICE, "مكتب" to UnitType.OFFICE, "اداري" to UnitType.OFFICE,
        "retail" to UnitType.RETAIL, "محل" to UnitType.RETAIL, "تجاري" to UnitType.RETAIL,
        "clinic" to UnitType.CLINIC, "عياده" to UnitType.CLINIC,
    )
    private val finishing = mapOf(
        "core and shell" to Finishing.CORE_AND_SHELL, "core & shell" to Finishing.CORE_AND_SHELL, "على المحاره" to Finishing.CORE_AND_SHELL, "طوب احمر" to Finishing.CORE_AND_SHELL,
        "semi finished" to Finishing.SEMI_FINISHED, "semi-finished" to Finishing.SEMI_FINISHED, "نصف تشطيب" to Finishing.SEMI_FINISHED, "نص تشطيب" to Finishing.SEMI_FINISHED,
        "fully finished" to Finishing.FULLY_FINISHED, "finished" to Finishing.FULLY_FINISHED, "تشطيب كامل" to Finishing.FULLY_FINISHED, "متشطب" to Finishing.FULLY_FINISHED, "كامل التشطيب" to Finishing.FULLY_FINISHED,
        "furnished" to Finishing.FURNISHED, "مفروش" to Finishing.FURNISHED,
    )
    private val availability = mapOf(
        "available" to Availability.AVAILABLE, "متاح" to Availability.AVAILABLE, "متاحه" to Availability.AVAILABLE,
        "released" to Availability.RELEASED, "طرح جديد" to Availability.RELEASED,
        "on hold" to Availability.ON_HOLD, "hold" to Availability.ON_HOLD, "معلق" to Availability.ON_HOLD, "مؤقت" to Availability.ON_HOLD,
        "reserved" to Availability.RESERVED, "محجوز" to Availability.RESERVED, "محجوزه" to Availability.RESERVED,
        "sold" to Availability.SOLD, "مباع" to Availability.SOLD, "مباعه" to Availability.SOLD, "تم البيع" to Availability.SOLD,
        "rented" to Availability.RENTED, "مؤجر" to Availability.RENTED, "مؤجره" to Availability.RENTED,
        "withdrawn" to Availability.WITHDRAWN, "مسحوب" to Availability.WITHDRAWN, "ملغي" to Availability.WITHDRAWN,
        "announced" to Availability.ANNOUNCED, "قريبا" to Availability.ANNOUNCED,
    )
    private val areaBasis = mapOf(
        "gross" to AreaBasis.GROSS, "اجمالي" to AreaBasis.GROSS, "اجماليه" to AreaBasis.GROSS,
        "net" to AreaBasis.NET, "صافي" to AreaBasis.NET, "صافيه" to AreaBasis.NET,
        "saleable" to AreaBasis.SALEABLE, "بيعيه" to AreaBasis.SALEABLE, "bua" to AreaBasis.BUILT_UP, "built up" to AreaBasis.BUILT_UP,
        "land" to AreaBasis.LAND, "ارض" to AreaBasis.LAND,
    )
    private val frequency = mapOf(
        "monthly" to 1, "شهري" to 1, "quarterly" to 3, "ربع سنوي" to 3, "كل 3 شهور" to 3, "ربعي" to 3,
        "semi-annual" to 6, "semi annual" to 6, "نصف سنوي" to 6, "annual" to 12, "yearly" to 12, "سنوي" to 12,
    )
    private val yes = setOf("yes", "y", "true", "1", "نعم", "اه", "ايوه")
    private val no = setOf("no", "n", "false", "0", "لا")

    private fun <T> lookup(map: Map<String, T>, raw: String?): T? = raw?.let { r -> map[ArabicText.normalize(r)] ?: map.entries.firstOrNull { ArabicText.normalize(it.key) == ArabicText.normalize(r) }?.value }

    fun normalize(raw: Map<String, String>, defaultCurrency: String = "EGP"): RowResult {
        val errors = mutableListOf<String>(); val warnings = mutableListOf<String>()
        fun req(k: String): String? = raw[k]?.takeUnless { SupplierParse.blank(it) }.also { if (it == null) errors += "$k: required" }
        fun opt(k: String): String? = raw[k]?.takeUnless { SupplierParse.blank(it) }

        val projectRef = req("project_code")
        val unitCode = req("unit_code")?.let { Digits.toWestern(it).trim().uppercase() }
        val unitType = req("unit_type")?.let { v -> lookup(unitTypes, v) ?: run { errors += "unit_type: '$v' not recognised"; null } }
        val avail = req("availability")?.let { v -> lookup(availability, v) ?: run { errors += "availability: '$v' not recognised"; null } }
        val currency = (opt("currency")?.let { Digits.toWestern(it).trim().uppercase().replace("جنيه", "EGP").replace("ج.م", "EGP") } ?: defaultCurrency)
            .let { if (!Currencies.isSupported(it)) { errors += "currency: '$it' not supported"; defaultCurrency } else it }
        val offerType = when (opt("offer_type")?.let(ArabicText::normalize)) { null, "sale", "بيع" -> OfferType.SALE; "rent", "ايجار" -> OfferType.RENT; else -> { errors += "offer_type: use sale or rent"; OfferType.SALE } }

        fun int(k: String): Int? = when (val p = SupplierParse.int(raw[k])) { is Parsed.Ok -> p.value; is Parsed.Err -> { errors += "$k: ${p.message}"; null }; Parsed.Missing -> null }
        fun money(k: String): Long? = when (val p = SupplierParse.amount(raw[k])) {
            is Parsed.Ok -> { p.warning?.let { warnings += "$k: $it" }; Money.ofMajor(p.value, currency).minor }
            is Parsed.Err -> { errors += "$k: ${p.message}"; null }
            Parsed.Missing -> null
        }
        fun date(k: String): PreciseDate? = when (val p = SupplierParse.date(raw[k])) {
            is Parsed.Ok -> { p.warning?.let { warnings += "$k: $it" }; p.value }
            is Parsed.Err -> { errors += "$k: ${p.message}"; null }
            Parsed.Missing -> null
        }
        /** "10%" -> percent bps; otherwise an amount. */
        fun moneyOrPercent(k: String): Amount? {
            val v = opt(k) ?: return null
            val w = Digits.toWestern(v).trim()
            if (w.endsWith("%") || w.endsWith("٪")) {
                val n = w.dropLast(1).trim().toBigDecimalOrNull() ?: run { errors += "$k: bad percentage '$v'"; return null }
                return Amount.Percent(n.multiply(BigDecimal(100)).toLong(), PercentBasis.LIST_PRICE)
            }
            return money(k)?.let { Amount.Fixed(it) }
        }

        val bedrooms = int("bedrooms"); val bathrooms = int("bathrooms")
        val area = when (val p = SupplierParse.decimal(raw["area_m2"])) { is Parsed.Ok -> p.value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(); is Parsed.Err -> { errors += "area_m2: ${p.message}"; null }; Parsed.Missing -> null }
        val basis = opt("area_basis")?.let { lookup(areaBasis, it) ?: run { warnings += "area_basis: '$it' not recognised, stored as UNKNOWN"; AreaBasis.UNKNOWN } } ?: AreaBasis.UNKNOWN
        if (area != null && basis == AreaBasis.UNKNOWN) warnings += "area_basis missing: buyers cannot compare price per m² reliably"
        val fin = opt("finishing")?.let { lookup(finishing, it) ?: run { warnings += "finishing: '$it' not recognised"; Finishing.UNKNOWN } } ?: Finishing.UNKNOWN

        val listPrice = money("list_price"); val cashPrice = money("cash_price"); val rent = money("monthly_rent")
        val asserted = date("price_sheet_date")
        if (raw["price_sheet_date"] != null && asserted != null && asserted.precision != PreciseDate.Precision.DAY) errors += "price_sheet_date: needs a full date"
        if (asserted != null && asserted.precision == PreciseDate.Precision.DAY && asserted.earliest().isAfter(LocalDate.now().plusDays(1))) errors += "price_sheet_date: is in the future"
        if (offerType == OfferType.SALE && listPrice == null && cashPrice == null && avail?.isOfferable == true) errors += "price: an available sale offer needs list_price or cash_price"
        if (offerType == OfferType.RENT && rent == null && avail?.isOfferable == true) errors += "monthly_rent: required for an available rental"

        val handoverRaw = opt("handover")
        // "Ready to move" is a state, not a date: never derive it from the sheet date (that would fake a change on every new sheet).
        val isReady = handoverRaw != null && ArabicText.normalize(handoverRaw) in setOf("ready", "immediate", "فوري", "استلام فوري", "جاهز", "جاهز للسكن")
        val handover: PreciseDate? = if (handoverRaw == null || isReady) null else date("handover")

        // Payment plan
        var plan: PaymentPlan? = null
        val dp = moneyOrPercent("down_payment"); val dpDate = date("down_payment_date")
        val instAmt = money("installment_amount"); val instCount = int("installment_count"); val firstInst = date("first_installment_date")
        val freq = opt("installment_frequency")?.let { v -> lookup(frequency, v) ?: run { errors += "installment_frequency: '$v' not recognised (monthly/quarterly/semi-annual/annual)"; null } }
        val anyPlan = listOf(dp, instAmt, instCount, firstInst, freq).any { it != null }
        if (anyPlan && offerType == OfferType.SALE) {
            if (listPrice == null) errors += "list_price: required when a payment plan is given"
            if (instAmt != null && (instCount == null || freq == null || firstInst == null)) errors += "installments: amount, count, frequency and first date are all required"
            if (firstInst != null && firstInst.precision != PreciseDate.Precision.DAY) errors += "first_installment_date: needs a full date"
            if (listPrice != null) {
                val items = mutableListOf<PlanItem>()
                val res = money("reservation")
                val resIncluded = opt("reservation_included")?.let { ArabicText.normalize(it) }.let { if (it == null) true else if (it in yes) true else if (it in no) false else { errors += "reservation_included: use yes/no"; true } }
                if (dp != null) items += PlanItem("dep", ItemKind.DEPOSIT, dp, dpDate?.let { Due.On(it) } ?: Due.Unknown)
                if (res != null) items += PlanItem("res", ItemKind.RESERVATION, Amount.Fixed(res), dpDate?.let { Due.On(it) } ?: Due.Unknown, includedIn = if (resIncluded && dp != null) "dep" else null)
                if (instAmt != null && instCount != null && freq != null && firstInst != null)
                    items += PlanItem("inst", ItemKind.INSTALMENT, Amount.Fixed(instAmt), Due.Every(firstInst, freq, instCount))
                moneyOrPercent("maintenance")?.let { m ->
                    val dueRaw = opt("maintenance_due")
                    val due = if (dueRaw == null || ArabicText.normalize(dueRaw) in setOf("handover", "at handover", "عند الاستلام", "الاستلام")) Due.AtHandover else date("maintenance_due")?.let { Due.On(it) } ?: Due.Unknown
                    items += PlanItem("mnt", ItemKind.MAINTENANCE, m, due)
                }
                opt("parking")?.let { p ->
                    val n = ArabicText.normalize(p)
                    val parkDueRaw = opt("parking_due")
                    val parkDue = if (parkDueRaw == null || ArabicText.normalize(parkDueRaw) in setOf("handover", "at handover", "عند الاستلام", "الاستلام")) Due.AtHandover else date("parking_due")?.let { Due.On(it) } ?: Due.Unknown
                    when {
                        n in setOf("none", "no", "لا يوجد", "لا", "not required", "غير مطلوب") -> {}
                        n in setOf("required", "مطلوب", "اجباري", "required unknown", "required, amount unknown") ->
                            items += PlanItem("park", ItemKind.PARKING, Amount.Unknown, parkDue, certainty = Certainty.UNKNOWN)
                        else -> money("parking")?.let { items += PlanItem("park", ItemKind.PARKING, Amount.Fixed(it), parkDue) }
                    }
                }
                plan = PaymentPlan(currency, listPrice, items = items, handover = handover)
                runCatching { CashFlowEngine.compute(plan) }.onFailure { errors += "payment plan: ${it.message}" }.onSuccess { s ->
                    val principal = s.schedule.filter { it.kind == ItemKind.DEPOSIT || it.kind == ItemKind.INSTALMENT }.sumOf { it.amountMinor ?: 0 }
                    if (principal != 0L && principal != listPrice)
                        warnings += "payment plan: deposit + instalments = ${MoneyFormat.format(Money(principal, currency), false)} but list_price = ${MoneyFormat.format(Money(listPrice, currency), false)}"
                }
            }
        }
        if (cashPrice != null && listPrice != null && cashPrice > listPrice) warnings += "cash_price is higher than list_price"

        val correction = opt("correction")?.let { ArabicText.normalize(it) in yes } ?: false
        val reason = opt("correction_reason")
        if (correction && reason == null) errors += "correction_reason: required when correction = yes"

        if (errors.isNotEmpty() || projectRef == null || unitCode == null || unitType == null || avail == null || asserted == null)
            return RowResult(null, errors.ifEmpty { listOf("row incomplete") }, warnings)

        return RowResult(
            NormalizedRow(
                projectRef = projectRef.trim(), phase = opt("phase"), building = opt("building")?.let { Digits.toWestern(it).uppercase() },
                unitCode = unitCode, listingKey = opt("listing_id")?.let { Digits.toWestern(it).trim() } ?: unitCode,
                unitType = unitType, bedrooms = bedrooms, bathrooms = bathrooms, areaM2 = area, areaBasis = basis,
                floor = opt("floor")?.let(Digits::toWestern), finishing = fin, availability = avail, offerType = offerType, currency = currency,
                listPriceMinor = listPrice, cashPriceMinor = cashPrice, monthlyRentMinor = rent, plan = plan, handover = if (isReady) "ready" else handover?.iso(),
                assertedOn = asserted.iso(), correction = correction, correctionReason = reason, notes = opt("notes"),
            ), emptyList(), warnings
        )
    }
}
