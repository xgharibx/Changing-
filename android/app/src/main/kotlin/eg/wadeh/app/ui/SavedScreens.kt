package eg.wadeh.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eg.wadeh.app.R
import eg.wadeh.app.data.OwnedShare
import eg.wadeh.app.data.Repository
import eg.wadeh.app.data.SavedHome
import eg.wadeh.app.data.SavedStore
import eg.wadeh.core.api.ChangeItem
import eg.wadeh.core.api.CompareResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class SavedViewModel(private val repo: Repository, val store: SavedStore) : ViewModel() {
    private val _changes = MutableStateFlow<Map<String, List<ChangeItem>>>(emptyMap())
    /** listingId -> changes recorded after the buyer last looked at that home. */
    val changes: StateFlow<Map<String, List<ChangeItem>>> = _changes
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline

    fun refresh() {
        val homes = store.homes.value
        if (homes.isEmpty()) { _changes.value = emptyMap(); return }
        viewModelScope.launch {
            val since = homes.minOf { Instant.parse(it.lastSeenAt) }
            runCatching { repo.changesSince(homes.map { it.listingId }, since) }
                .onSuccess { all ->
                    _offline.value = false
                    _changes.value = homes.associate { h ->
                        h.listingId to all[h.listingId].orEmpty().filter { it.kind != "offer_published" && Instant.parse(it.recordedAt).isAfter(Instant.parse(h.lastSeenAt)) }
                    }
                }
                .onFailure { _offline.value = true }
        }
    }

    fun revoke(s: OwnedShare, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching { repo.revokeShare(s.token, s.ownerSecret) }.isSuccess
        if (ok) store.markRevoked(s.token)
        onDone(ok)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(vm: SavedViewModel, onOpen: (String) -> Unit, onCompare: (List<String>) -> Unit) {
    val homes by vm.store.homes.collectAsState()
    val shares by vm.store.shares.collectAsState()
    val changes by vm.changes.collectAsState()
    val offline by vm.offline.collectAsState()
    val arabic = isArabic(); val st = LocalStatus.current
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(homes.size) { vm.refresh() }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.saved_title)) }) },
        bottomBar = {
            if (selected.size >= 2) Surface(tonalElevation = 3.dp) {
                Button(onClick = { onCompare(selected.toList()) }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("compare_button")) {
                    Text(stringResource(R.string.compare_n, selected.size))
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (offline) item { OfflineBanner(homes.maxOfOrNull { Instant.parse(it.lastSeenAt) } ?: Instant.now()) }
            if (homes.isEmpty()) item {
                Column(Modifier.padding(vertical = 24.dp)) {
                    Text(stringResource(R.string.saved_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.saved_empty_body), style = MaterialTheme.typography.bodyMedium, color = st.muted)
                }
            } else item { Text(stringResource(R.string.select_to_compare), style = MaterialTheme.typography.labelMedium, color = st.muted) }
            items(homes, key = { it.listingId }) { h ->
                val diff = changes[h.listingId].orEmpty()
                val isSel = h.listingId in selected
                Column {
                    Row(Modifier.fillMaxWidth().toggleable(value = isSel, role = Role.Checkbox, enabled = isSel || selected.size < 4) {
                        selected = if (isSel) selected - h.listingId else selected + h.listingId
                    }.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isSel, onCheckedChange = null)
                        Text(stringResource(R.string.include_in_comparison), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        if (diff.isNotEmpty()) Tag(pluralStringResource(R.plurals.changes_since_seen, diff.size, num(diff.size, arabic)), st.ok, st.okBg)
                    }
                    ListingCardView(h.card, arabic) { onOpen(h.listingId) }
                    if (h.note.isNotBlank()) Text("✎ " + h.note, style = MaterialTheme.typography.bodySmall, color = st.muted, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
                }
            }
            if (shares.isNotEmpty()) {
                item { Text(stringResource(R.string.your_links), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() }) }
                items(shares, key = { it.token }) { s -> ShareRow(s, vm) }
            }
        }
    }
}

@Composable
private fun ShareRow(s: OwnedShare, vm: SavedViewModel) {
    val st = LocalStatus.current; val arabic = isArabic()
    var failed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().border(1.dp, st.line, RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.homes_count, s.listingIds.size, num(s.listingIds.size, arabic)), style = MaterialTheme.typography.bodyMedium)
            Text(if (s.revoked) stringResource(R.string.link_revoked) else stringResource(R.string.link_expires, date(s.expiresAt, arabic)), style = MaterialTheme.typography.labelSmall, color = if (s.revoked) st.bad else st.muted)
            if (failed) Text(stringResource(R.string.revoke_failed), style = MaterialTheme.typography.labelSmall, color = st.bad)
        }
        if (!s.revoked) OutlinedButton(onClick = { vm.revoke(s) { ok -> failed = !ok } }, modifier = Modifier.testTag("revoke_${s.token.take(6)}")) { Text(stringResource(R.string.revoke)) }
    }
}

class CompareViewModel(private val repo: Repository, private val store: SavedStore, val ids: List<String>) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<CompareResponse>>(LoadState.Loading)
    val state: StateFlow<LoadState<CompareResponse>> = _state
    private val _shareError = MutableStateFlow(false)
    val shareError: StateFlow<Boolean> = _shareError
    init { refresh() }
    fun refresh() = viewModelScope.launch { _state.value = runCatching { repo.compare(ids) }.fold({ LoadState.Ready(it) }, { LoadState.Failed(it.message) }) }
    fun share(lang: String, onUrl: (String) -> Unit) = viewModelScope.launch {
        runCatching { repo.createShare(ids, lang) }.onSuccess {
            _shareError.value = false
            store.addShare(OwnedShare(it.token, it.ownerSecret, it.url, ids, Instant.now().toString(), it.expiresAt))
            onUrl(it.url)
        }.onFailure { _shareError.value = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: CompareViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val shareError by vm.shareError.collectAsState()
    val arabic = isArabic(); val ctx = LocalContext.current
    val chooserTitle = stringResource(R.string.share_comparison)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.compare_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { vm.share(if (arabic) "ar" else "en") { url ->
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), chooserTitle))
                } }, modifier = Modifier.testTag("share_button")) { Icon(Icons.Outlined.Share, contentDescription = chooserTitle) }
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LoadState.Failed -> ErrorState(onRetry = { vm.refresh() })
                is LoadState.Ready -> CompareBody(s.value.data, s.value.fromCache, s.value.fetchedAt, arabic, shareError)
            }
        }
    }
}

@Composable
internal fun CompareBody(r: CompareResponse, fromCache: Boolean, fetchedAt: Instant, arabic: Boolean, shareError: Boolean = false) {
    val st = LocalStatus.current
    val letters = if (arabic) listOf("أ", "ب", "ج", "د") else listOf("A", "B", "C", "D")
    val rows = r.cards.indices.map { i -> Triple(letters[i], r.cards[i], r.comparison.rows[i]) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("compare")) {
        if (fromCache) item { OfflineBanner(fetchedAt) }
        if (shareError) item { Text(stringResource(R.string.share_failed), color = st.bad) }
        item { Text(stringResource(R.string.compare_private_note), style = MaterialTheme.typography.labelSmall, color = st.muted) }
        items(rows) { (l, c, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(c.project.pick(arabic) + " · " + (c.unitCode?.let { isolate(it) } ?: ""), style = MaterialTheme.typography.titleSmall)
                    Text(c.evidence.label.pick(arabic), style = MaterialTheme.typography.labelSmall, color = if (c.evidence.stale) st.warn else st.muted)
                }
            }
        }
        fun metric(key: String?, labelRes: Int, value: (Int) -> String) = Triple(key, labelRes, value)
        val metrics = listOf(
            metric(null, R.string.cmp_price) { i -> money(r.cards[i].priceMinor, r.cards[i].currency, arabic) },
            metric("cash_to_start", R.string.cash_needed_start) { i -> money(r.comparison.rows[i].cashToStartMinor, r.cards[i].currency, arabic) },
            metric("first_12_months", R.string.first_12_months) { i -> money(r.comparison.rows[i].first12MonthsMinor, r.cards[i].currency, arabic) },
            metric(null, R.string.largest_payment) { i -> r.comparison.rows[i].largestSingleDateMinor?.let { money(it, r.cards[i].currency, arabic) + " · " + date(r.comparison.rows[i].largestSingleDate, arabic) } ?: "—" },
            metric("known_mandatory", R.string.known_total) { i -> money(r.comparison.rows[i].knownMandatoryMinor, r.cards[i].currency, arabic) },
            metric("price_per_m2", R.string.cmp_price_per_m2) { i -> r.comparison.rows[i].pricePerM2Minor?.let { money(it, r.cards[i].currency, arabic) } ?: "—" },
            metric(null, R.string.cmp_handover) { i -> date(r.cards[i].handover, arabic) },
        )
        items(metrics) { (key, label, value) ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).border(1.dp, st.line, RoundedCornerShape(10.dp)).padding(12.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                rows.forEachIndexed { i, (l, c, row) ->
                    val isLowest = key != null && r.comparison.lowest[key] == c.id
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(24.dp))
                        Text(value(i), style = MaterialTheme.typography.bodyLarge, fontWeight = if (isLowest) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (isLowest) Tag(stringResource(R.string.cmp_lowest), st.ok, st.okBg)
                        if (key == "known_mandatory" && !row.exactTotalKnown) Tag(stringResource(R.string.exact_total_unknown), st.warn, st.warnBg)
                    }
                }
                val why = when (key) {
                    "price_per_m2" -> r.comparison.suppressed["price_per_m2"]?.let { stringResource(R.string.cmp_ppm_suppressed) }
                    "known_mandatory" -> r.comparison.suppressed["known_mandatory_lowest"]?.let { stringResource(R.string.cmp_total_suppressed) }
                    else -> null
                }
                why?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = st.warn, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        item { Text(stringResource(R.string.engine_note, r.comparison.engineVersion), style = MaterialTheme.typography.labelSmall, color = st.muted) }
    }
}
