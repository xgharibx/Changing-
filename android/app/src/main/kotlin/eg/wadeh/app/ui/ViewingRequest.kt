package eg.wadeh.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eg.wadeh.app.R
import eg.wadeh.app.data.OwnedInquiry
import eg.wadeh.app.data.Repository
import eg.wadeh.app.data.SavedStore
import eg.wadeh.core.api.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Standard viewing grid shown to buyers; the server enforces the slot policy and returns real alternatives. */
fun nextSlots(now: Instant = Instant.now(), n: Int = 6): List<Instant> {
    val z = ZoneId.of("Africa/Cairo"); val out = mutableListOf<Instant>(); var d = LocalDate.now(z).plusDays(1)
    while (out.size < n) { for (h in listOf(10, 13, 16)) { val s = d.atTime(h, 0).atZone(z).toInstant(); if (s.isAfter(now) && out.size < n) out += s }; d = d.plusDays(1) }
    return out
}

@Composable
fun ViewingRequestSheet(card: ListingCard, repo: Repository, store: SavedStore, onDone: () -> Unit) {
    val arabic = isArabic(); val st = LocalStatus.current; val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }; val slots = remember { nextSlots() }; var slot by remember { mutableStateOf(slots.first()) }
    var version by remember { mutableStateOf(card.offerVersionId) }
    // The idempotency key survives retries of the same submission so a flaky network cannot create two leads.
    val key = remember { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }; var changed by remember { mutableStateOf<OfferChanged?>(null) }
    var result by remember { mutableStateOf<InquiryStatus?>(null) }; var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(onDismissRequest = { if (!busy) onDone() }, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("viewing_sheet"),
        title = { Text(stringResource(R.string.viewing_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                result?.let { r ->
                    Text(stringResource(if (r.status == "waitlisted") R.string.viewing_waitlisted else R.string.viewing_pending, r.recipient.pick(arabic)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("viewing_result"))
                    r.alternatives.forEach { Text("• " + date(it, arabic), style = MaterialTheme.typography.bodySmall) }
                    return@Column
                }
                changed?.let { c ->
                    Text(stringResource(R.string.offer_changed_title), style = MaterialTheme.typography.titleSmall, color = st.warn)
                    c.changes.firstOrNull { it.kind == "price_changed" }?.let { ch ->
                        Text("${money(ch.oldValue?.toLongOrNull(), card.currency, arabic)} → ${money(ch.newValue?.toLongOrNull(), card.currency, arabic)}", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(stringResource(R.string.offer_changed_body), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                Text(stringResource(R.string.viewing_recipient, card.seller.pick(arabic)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.viewing_shared), style = MaterialTheme.typography.labelSmall, color = st.muted)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("viewing_name"))
                OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.your_phone)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth().testTag("viewing_phone"))
                Text(stringResource(R.string.choose_time), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                slots.forEach { s -> Row(Modifier.fillMaxWidth().selectable(selected = s == slot, role = Role.RadioButton) { slot = s }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s == slot, onClick = null); Text(date(s.toString(), arabic)) } }
                Row(Modifier.fillMaxWidth().toggleable(consent, role = Role.Checkbox) { consent = it }.padding(top = 8.dp).testTag("viewing_consent"), verticalAlignment = Alignment.Top) {
                    Checkbox(consent, null); Text(stringResource(R.string.viewing_consent, card.seller.pick(arabic)), style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = st.bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (result != null) TextButton(onClick = onDone) { Text(stringResource(R.string.done)) }
            else Button(enabled = consent && name.isNotBlank() && phone.length >= 10 && !busy, modifier = Modifier.testTag("viewing_submit"), onClick = {
                busy = true; error = null
                scope.launch {
                    val r = CreateInquiryRequest(key + if (version != card.offerVersionId) "-v2" else "", card.id, version, "in_app", name, phone, consent, CONSENT_TEXT_VERSION, slot.toString(), if (arabic) "ar" else "en")
                    when (val o = runCatching { repo.createInquiry(r) }.getOrElse { Repository.InquiryOutcome.Refused(0, it.message ?: "") }) {
                        is Repository.InquiryOutcome.Created -> { result = o.s; o.s.buyerToken?.let { store.addInquiry(OwnedInquiry(o.s.id, it, card.id, Instant.now().toString())) } }
                        is Repository.InquiryOutcome.Changed -> { changed = o.c; version = o.c.current.offerVersionId }
                        is Repository.InquiryOutcome.Refused -> error = if (o.code == 0) "offline" else o.message
                    }
                    busy = false
                }
            }) { Text(stringResource(if (changed != null) R.string.continue_new_price else R.string.send_request)) }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDone, enabled = !busy) { Text(stringResource(R.string.back)) } })
}
