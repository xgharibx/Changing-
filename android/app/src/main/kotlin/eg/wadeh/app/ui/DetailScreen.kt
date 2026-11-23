package eg.wadeh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eg.wadeh.app.R
import eg.wadeh.app.data.Repository
import eg.wadeh.core.CashFlowEngine
import eg.wadeh.core.CashFlowSummary
import eg.wadeh.core.ScheduledPayment
import eg.wadeh.core.api.ChangeItem
import eg.wadeh.core.api.ListingDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DetailViewModel(private val repo: Repository, val id: String, val store: eg.wadeh.app.data.SavedStore) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<ListingDetail>>(LoadState.Loading)
    val state: StateFlow<LoadState<ListingDetail>> = _state
    init { refresh() }
    fun refresh() { viewModelScope.launch {
        _state.value = runCatching { repo.detail(id) }.fold({ LoadState.Ready(it) }, { LoadState.Failed(it.message) })
        (_state.value as? LoadState.Ready)?.value?.let { if (!it.fromCache) store.markSeen(it.data.card) }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: DetailViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val state by vm.state.collectAsState()
    val arabic = isArabic()
    Scaffold(topBar = {
        TopAppBar(title = {
            val t = (state as? LoadState.Ready)?.value?.data?.card?.project?.pick(arabic) ?: ""
            Text(t, maxLines = 1)
        }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back)) } },
    actions = {
        val homes by vm.store.homes.collectAsState()
        val card = (state as? LoadState.Ready)?.value?.data?.card
        if (card != null) {
            val saved = homes.any { it.listingId == card.id }
            IconToggleButton(checked = saved, onCheckedChange = { vm.store.toggle(card) }, modifier = Modifier.testTag("save_toggle")) {
                Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = stringResource(if (saved) R.string.unsave else R.string.save))
            }
        }
    })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LoadState.Failed -> ErrorState(vm::refresh)
                is LoadState.Ready -> {
                    val homes by vm.store.homes.collectAsState()
                    val saved = homes.firstOrNull { it.listingId == s.value.data.card.id }
                    DetailBody(s.value.data, s.value.fromCache, s.value.fetchedAt, arabic, onOpen, note = saved?.note, onNote = if (saved != null) { n -> vm.store.setNote(saved.listingId, n) } else null)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val st = LocalStatus.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).border(1.dp, st.line, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun KeyFigure(label: String, value: String, sub: String? = null, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = LocalStatus.current.money)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LocalStatus.current.muted) }
        }
    }
}

@Composable
internal fun DetailBody(d: ListingDetail, fromCache: Boolean, fetchedAt: java.time.Instant, arabic: Boolean, onOpen: (String) -> Unit,
                        note: String? = null, onNote: ((String) -> Unit)? = null) {
    val c = d.card; val st = LocalStatus.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("detail")) {
        if (fromCache) item { OfflineBanner(fetchedAt) }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvailabilityChip(c.availability)
                    if (c.isTestData) { Spacer(Modifier.width(6.dp)); Tag(stringResource(R.string.test_data), st.warn, st.warnBg) }
                }
                Spacer(Modifier.height(6.dp))
                Text(listOfNotNull(stringByName("unit_type", c.unitType), c.unitCode?.let { stringResource(R.string.unit_code, isolate(it)) }).joinToString(" · "), style = MaterialTheme.typography.titleLarge)
                Text(c.locality.pick(arabic) + " · " + stringResource(R.string.developer_by, c.developer.pick(arabic)), style = MaterialTheme.typography.bodySmall, color = st.muted)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(money(c.priceMinor, c.currency, arabic), style = MaterialTheme.typography.headlineSmall, color = st.money, modifier = Modifier.testTag("headline_price"))
                    Spacer(Modifier.width(6.dp))
                    Text(stringByName("price_basis", c.priceBasis), style = MaterialTheme.typography.labelMedium, color = st.muted, modifier = Modifier.padding(bottom = 4.dp))
                }
                c.cashPriceMinor?.takeIf { c.priceBasis != "cash" }?.let { Text(stringResource(R.string.field_cash_price) + ": " + money(it, c.currency, arabic), style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(6.dp))
                val facts = listOfNotNull(
                    c.area?.let { stringResource(R.string.m2, numStr(it.removeSuffix(".00"), arabic)) + " " + stringByName("area_basis", c.areaBasis) },
                    c.floor?.let { stringResource(R.string.floor, numStr(it, arabic)) },
                    stringByName("finishing", c.finishing),
                    c.handover?.let { stringResource(R.string.handover, date(it, arabic)) },
                )
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.sold_by, c.seller.pick(arabic)), style = MaterialTheme.typography.bodySmall, color = st.muted)
            }
        }
        d.cashflow?.let { cf -> item { MoneySection(cf, arabic) } }
        if (onNote != null) item {
            var text by rememberSaveable(d.card.id) { mutableStateOf(note.orEmpty()) }
            OutlinedTextField(value = text, onValueChange = { text = it; onNote(it) }, modifier = Modifier.fillMaxWidth().testTag("private_note"),
                label = { Text(stringResource(R.string.private_note)) }, supportingText = { Text(stringResource(R.string.private_note_hint)) }, minLines = 2)
        }
        item { ChangesSection(d.changes, c.currency, arabic) }
        item {
            Section(stringResource(R.string.section_evidence)) {
                Text(c.evidence.label.pick(arabic), style = MaterialTheme.typography.bodyMedium, color = if (c.evidence.stale) st.warn else MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("evidence_label"))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.offer_version, c.offerVersion, date(c.evidence.receivedAt, arabic)), style = MaterialTheme.typography.bodySmall, color = st.muted)
                c.locationPrecisionM?.let { Text(stringResource(R.string.location_precision, it), style = MaterialTheme.typography.bodySmall, color = st.muted) }
            }
        }
        if (d.otherOffersForUnit.isNotEmpty()) {
            item { Text(stringResource(R.string.other_offers), style = MaterialTheme.typography.titleMedium) }
            items(d.otherOffersForUnit, key = { it.id }) { o -> ListingCardView(o, arabic) { onOpen(o.id) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MoneySection(cf: CashFlowSummary, arabic: Boolean) {
    val st = LocalStatus.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.section_money)) {
        KeyFigure(stringResource(R.string.cash_needed_start), money(cf.cashToStartMinor, cf.currency, arabic), cf.firstPaymentDate?.let { date(it, arabic) }, emphasis = true)
        KeyFigure(stringResource(R.string.first_12_months), money(cf.first12MonthsMinor, cf.currency, arabic))
        cf.largestSingleDate?.let { KeyFigure(stringResource(R.string.largest_payment), money(cf.largestSingleDateMinor, cf.currency, arabic), date(it, arabic)) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = st.line)
        if (cf.exactTotalKnown) {
            KeyFigure(stringResource(R.string.known_total), money(cf.exactMandatoryTotalMinor, cf.currency, arabic), emphasis = true)
        } else {
            KeyFigure(stringResource(R.string.known_total), money(cf.knownMandatoryMinor, cf.currency, arabic), "+ " + stringResource(R.string.exact_total_unknown), emphasis = true)
            cf.unresolved.filter { it.mandatory }.forEach { u ->
                Row(Modifier.fillMaxWidth().background(st.warnBg, RoundedCornerShape(6.dp)).padding(8.dp).testTag("unresolved_fee")) {
                    Icon(Icons.Outlined.HelpOutline, contentDescription = null, tint = st.warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.unresolved_fee, stringByName("kind", u.kind.name)), color = st.warn, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringResource(R.string.hide_schedule) else stringResource(R.string.show_schedule, cf.schedule.size)) }
        if (expanded) {
            // Group payments falling on the same date so "largest single date" is visible in the list.
            cf.schedule.forEach { p -> ScheduleRow(p, cf.currency, arabic) }
        }
        Text(stringResource(R.string.engine_note, cf.engineVersion), style = MaterialTheme.typography.labelSmall, color = st.muted)
    }
}

@Composable
private fun ScheduleRow(p: ScheduledPayment, currency: String, arabic: Boolean) {
    val st = LocalStatus.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringByName("kind", p.kind.name) + (p.sequence?.let { " " + stringResource(R.string.seq_of, it, p.of ?: 0).let { s -> if (arabic) eg.wadeh.core.Digits.toArabicIndic(s) else s } } ?: ""), style = MaterialTheme.typography.bodySmall)
            if (p.creditedFrom.isNotEmpty()) Text(stringResource(R.string.credited, stringByName("kind", "RESERVATION")), style = MaterialTheme.typography.labelSmall, color = st.muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(p.amountMinor?.let { money(it, currency, arabic) } ?: "?", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(date(p.date, arabic), style = MaterialTheme.typography.labelSmall, color = st.muted)
        }
    }
}

@Composable
private fun ChangesSection(changes: List<ChangeItem>, currency: String, arabic: Boolean) {
    val st = LocalStatus.current
    val meaningful = changes.filter { it.kind != "offer_published" }
    Section(stringResource(R.string.section_changes)) {
        if (meaningful.isEmpty()) Text(stringResource(R.string.no_changes), style = MaterialTheme.typography.bodyMedium, color = st.muted)
        meaningful.forEach { ch ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("change_${ch.kind}")) {
                Text(stringByName("change", ch.kind) + (ch.field?.takeIf { it.endsWith("price") || it == "monthly_rent" }?.let { " · " + stringByName("field", it) } ?: ""), style = MaterialTheme.typography.titleSmall)
                if (ch.kind != "plan_changed") Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(changeValue(ch, ch.oldValue, currency, arabic), style = MaterialTheme.typography.bodyMedium, color = st.muted)
                    Icon(if (arabic) Icons.Outlined.ArrowBack else Icons.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.padding(horizontal = 6.dp).size(16.dp))
                    Text(changeValue(ch, ch.newValue, currency, arabic), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                if (!ch.likeForLike) {
                    val why = ch.reasons.map { stringByName("reason", it) }
                    Text(stringResource(R.string.not_like_for_like) + (if (why.isNotEmpty()) " — " + why.joinToString(if (arabic) "، " else ", ") else ""), style = MaterialTheme.typography.labelSmall, color = st.warn)
                }
                Text(listOfNotNull(ch.effectiveOn?.let { stringResource(R.string.effective_on, date(it, arabic)) }, stringResource(R.string.source_label, ch.source.pick(arabic))).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = st.muted)
            }
        }
    }
}

@Composable
private fun changeValue(ch: ChangeItem, v: String?, currency: String, arabic: Boolean): String = when {
    v == null -> "—"
    ch.field in setOf("list_price", "cash_price", "monthly_rent") -> v.toLongOrNull()?.let { money(it, ch.currency ?: currency, arabic) } ?: v
    ch.field == "availability" -> stringByName("availability", v)
    ch.field == "handover" -> date(v, arabic)
    else -> numStr(v, arabic)
}
