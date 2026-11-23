package eg.wadeh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eg.wadeh.app.BuildConfig
import eg.wadeh.app.R
import eg.wadeh.app.data.Loaded
import eg.wadeh.app.data.Repository
import eg.wadeh.core.api.ListingCard
import eg.wadeh.core.api.Page
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: Loaded<T>) : LoadState<T>
    data class Failed(val message: String?) : LoadState<Nothing>
}

class ListingsViewModel(private val repo: Repository, private val saved: SavedStateHandle) : ViewModel() {
    val query: StateFlow<String> = saved.getStateFlow("q", "")
    private val _state = MutableStateFlow<LoadState<Page<ListingCard>>>(LoadState.Loading)
    val state: StateFlow<LoadState<Page<ListingCard>>> = _state
    private var job: Job? = null
    /** Chips parsed on-device from the buyer's words (works offline and with AI switched off). */
    val parsed = MutableStateFlow<eg.wadeh.core.ParsedQuery?>(null)
    private val removed = MutableStateFlow(setOf<String>())
    fun removeChip(kind: String) { removed.value = removed.value + kind; refresh(reparse = false) }
    private var generation = 0

    init { refresh() }

    fun setQuery(q: String) { saved["q"] = q }

    /** Cancels the previous request so a slow, older response can never replace newer results. */
    fun refresh(reparse: Boolean = true) {
        job?.cancel()
        val gen = ++generation
        if (reparse) { removed.value = emptySet(); parsed.value = query.value.takeIf { it.isNotBlank() }?.let { eg.wadeh.core.QueryParser.parse(it) } }
        val chips = parsed.value?.chips.orEmpty().filter { it.kind !in removed.value }
        parsed.value = parsed.value?.copy(chips = chips)
        val filters = buildMap {
            chips.forEach { c -> when (c.kind) {
                "unit_type" -> put("unitType", c.value); "min_bedrooms" -> put("minBedrooms", c.value); "offer_type" -> put("offerType", c.value)
                "max_price" -> put("maxPrice", (c.value.toLong() / 100).toString()); "max_down_payment" -> put("maxCashToStart", (c.value.toLong() / 100).toString())
            } }
        }
        val place = chips.firstOrNull { it.kind == "place" }?.evidence
        val q = if (chips.isEmpty()) query.value else (place ?: "")
        if (_state.value !is LoadState.Ready) _state.value = LoadState.Loading
        job = viewModelScope.launch {
            val r = runCatching { repo.listings(q.ifBlank { null }, filters = filters) }
            if (gen != generation) return@launch
            _state.value = r.fold({ LoadState.Ready(it) }, { LoadState.Failed(it.message) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListingsScreen(vm: ListingsViewModel, onOpen: (String) -> Unit, onToggleLanguage: () -> Unit) {
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()
    val arabic = isArabic()
    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (arabic) "واضح" else "Wadeh", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                    if (BuildConfig.ENV_NAME.isNotEmpty()) EnvBadge(BuildConfig.ENV_NAME)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onToggleLanguage, modifier = Modifier.testTag("lang_toggle")) { Text(stringResource(R.string.language_toggle)) }
                }
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query, onValueChange = vm::setQuery, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("search_field"),
                    placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.refresh() }),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LoadState.Failed -> ErrorState(onRetry = vm::refresh)
                is LoadState.Ready -> {
                    val page = s.value.data
                    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("results")) {
                        if (s.value.fromCache) item { OfflineBanner(s.value.fetchedAt) }
                        vm.parsed.value?.takeIf { it.chips.isNotEmpty() }?.let { pq -> item { ChipsRow(pq, arabic, vm::removeChip) } }
                        item {
                            Text(pluralStringResource(R.plurals.results_count, page.items.size, num(page.items.size, arabic)), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        if (page.items.isEmpty()) item { EmptyState() }
                        items(page.items, key = { it.id }) { card -> ListingCardView(card, arabic) { onOpen(card.id) } }
                    }
                }
            }
        }
    }
}

@Composable
fun EnvBadge(name: String) {
    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(horizontal = 8.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
fun OfflineBanner(at: java.time.Instant) {
    val st = LocalStatus.current
    Row(Modifier.fillMaxWidth().background(st.warnBg, RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = st.warn)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.offline_banner, dateTime(at, isArabic())), color = st.warn, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ErrorState(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.WifiOff, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.load_error), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
        Text(stringResource(R.string.no_results_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.no_results_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AvailabilityChip(state: String) {
    val st = LocalStatus.current
    val (fg, bg, icon) = when (state) {
        "AVAILABLE", "RELEASED" -> Triple(st.ok, st.okBg, Icons.Outlined.CheckCircle)
        "ON_HOLD", "RESERVED", "UNKNOWN", "CONTESTED", "ANNOUNCED" -> Triple(st.warn, st.warnBg, Icons.Outlined.Schedule)
        else -> Triple(st.bad, st.badBg, Icons.Outlined.Block)
    }
    Row(Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringByName("availability", state), color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ListingCardView(c: ListingCard, arabic: Boolean, onClick: () -> Unit) {
    val st = LocalStatus.current
    Column(
        Modifier.fillMaxWidth().clip12().background(MaterialTheme.colorScheme.surface).border(1.dp, st.line, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = c.project.pick(arabic), onClick = onClick).padding(14.dp).testTag("listing_card")
    ) {
        // Where
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.project.pick(arabic), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            AvailabilityChip(c.availability)
        }
        Text(c.locality.pick(arabic) + " · " + c.developer.pick(arabic), style = MaterialTheme.typography.bodySmall, color = st.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        // What
        val facts = buildList {
            add(stringByName("unit_type", c.unitType))
            c.bedrooms?.let { add(pluralStringResource(R.plurals.beds, it, num(it, arabic))) }
            c.area?.let { add(stringResource(R.string.m2, numStr(it.removeSuffix(".00"), arabic)) + " " + stringByName("area_basis", c.areaBasis)) }
            c.unitCode?.let { add(stringResource(R.string.unit_code, isolate(it))) }
        }
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        // How much, on what basis
        Row(verticalAlignment = Alignment.Bottom) {
            Text(money(c.priceMinor, c.currency, arabic), style = MaterialTheme.typography.titleLarge, color = st.money, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(stringByName("price_basis", c.priceBasis), style = MaterialTheme.typography.labelMedium, color = st.muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        c.cashToStartMinor?.let { Text(stringResource(R.string.cash_to_start, money(it, c.currency, arabic)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        // How recently confirmed, by whom
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (c.evidence.stale) Icons.Outlined.History else Icons.Outlined.Verified, contentDescription = null, tint = if (c.evidence.stale) st.warn else st.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(c.evidence.label.pick(arabic), style = MaterialTheme.typography.labelSmall, color = if (c.evidence.stale) st.warn else st.muted, modifier = Modifier.weight(1f))
        }
        if (c.changeCount > 0 || c.isTestData) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (c.changeCount > 0) Tag(pluralStringResource(R.plurals.changes_badge, c.changeCount, num(c.changeCount, arabic)), st.ok, st.okBg)
            if (c.isTestData) Tag(stringResource(R.string.test_data), st.warn, st.warnBg)
        }
    }
}

@Composable
fun Tag(text: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Text(text, color = fg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

fun Modifier.clip12() = this.clip(RoundedCornerShape(12.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsRow(pq: eg.wadeh.core.ParsedQuery, arabic: Boolean, onRemove: (String) -> Unit) {
    val st = LocalStatus.current
    Column(Modifier.testTag("parsed_chips")) {
        Text(stringResource(R.string.understood_as), style = MaterialTheme.typography.labelMedium, color = st.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pq.chips.forEach { c ->
                val label = when (c.kind) {
                    "max_price", "max_down_payment", "max_instalment" -> stringByName("chip", c.kind) + " " + money(c.value.toLong(), "EGP", arabic) +
                        (c.extra["frequency"]?.takeIf { it != "unknown" }?.let { " / " + stringByName("freq", it) } ?: "") + (if (c.extra["bound"] == "approximate") " ≈" else "")
                    "unit_type" -> stringByName("unit_type", c.value)
                    "min_bedrooms" -> stringByName("chip", c.kind) + " " + num(c.value.toInt(), arabic)
                    "offer_type" -> stringByName("chip", "rent")
                    "commute" -> stringByName("chip", c.kind) + " " + (c.extra["destination_text"] ?: c.value) + (c.extra["arrive_by"]?.let { " · " + stringResource(R.string.arrive_by, numStr(it, arabic)) } ?: "")
                    else -> stringByName("chip", c.kind) + " " + c.evidence
                }
                InputChip(selected = true, onClick = { onRemove(c.kind) }, label = { Text(label) },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove_filter, label)) })
            }
        }
        if (pq.chips.any { it.kind == "commute" }) Text(stringResource(R.string.commute_not_applied), style = MaterialTheme.typography.labelSmall, color = st.warn)
        if (pq.chips.any { it.kind == "max_instalment" }) Text(stringResource(R.string.instalment_not_applied), style = MaterialTheme.typography.labelSmall, color = st.warn)
        if ("instalment_frequency" in pq.clarifications) Text(stringResource(R.string.ask_frequency), style = MaterialTheme.typography.labelSmall, color = st.warn)
    }
}
