package eg.wadeh.app.data

import android.content.Context
import eg.wadeh.core.api.ListingCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/**
 * Guest-first, on-device saved homes. Private notes never leave the device (they are not part of any API call or share link).
 * Stored under files/saved (included in device-to-device transfer, excluded from cloud backup; see data_extraction_rules).
 */
@Serializable
data class SavedHome(
    val listingId: String,
    val savedAt: String,
    val lastSeenAt: String,          // when the buyer last opened this home
    val lastSeenVersion: Int,
    val card: ListingCard,           // compact snapshot with its own observation times, for offline
    val note: String = "",
)

@Serializable
data class OwnedShare(val token: String, val ownerSecret: String, val url: String, val listingIds: List<String>, val createdAt: String, val expiresAt: String, val revoked: Boolean = false)

@Serializable
private data class SavedFile(val schema: Int = 1, val homes: List<SavedHome> = emptyList(), val shares: List<OwnedShare> = emptyList())

class SavedStore(context: Context) {
    private val file = File(File(context.filesDir, "saved").apply { mkdirs() }, "saved.json")
    private val _state = MutableStateFlow(load())
    val homes: StateFlow<List<SavedHome>> get() = _homes
    private val _homes = MutableStateFlow(_state.value.homes)
    private val _shares = MutableStateFlow(_state.value.shares)
    val shares: StateFlow<List<OwnedShare>> get() = _shares

    private fun load(): SavedFile = runCatching { wireJson.decodeFromString(SavedFile.serializer(), file.readText()) }.getOrDefault(SavedFile())

    @Synchronized private fun write(f: SavedFile) {
        // Atomic replace so a crash mid-write cannot corrupt saved homes.
        val tmp = File(file.parentFile, "saved.json.tmp")
        tmp.writeText(wireJson.encodeToString(SavedFile.serializer(), f))
        tmp.renameTo(file)
        _state.value = f; _homes.value = f.homes; _shares.value = f.shares
    }

    fun isSaved(id: String) = _homes.value.any { it.listingId == id }

    fun toggle(card: ListingCard) {
        val f = _state.value
        write(if (isSaved(card.id)) f.copy(homes = f.homes.filterNot { it.listingId == card.id })
              else f.copy(homes = f.homes + SavedHome(card.id, Instant.now().toString(), Instant.now().toString(), card.offerVersion, card)))
    }

    /** Called when the buyer opens a saved home: it becomes the new "last seen" baseline. */
    fun markSeen(card: ListingCard) {
        val f = _state.value
        if (!isSaved(card.id)) return
        write(f.copy(homes = f.homes.map { if (it.listingId == card.id) it.copy(lastSeenAt = Instant.now().toString(), lastSeenVersion = card.offerVersion, card = card) else it }))
    }

    fun setNote(id: String, note: String) {
        val f = _state.value
        write(f.copy(homes = f.homes.map { if (it.listingId == id) it.copy(note = note.take(2000)) else it }))
    }

    fun addShare(s: OwnedShare) { val f = _state.value; write(f.copy(shares = f.shares + s)) }
    fun markRevoked(token: String) { val f = _state.value; write(f.copy(shares = f.shares.map { if (it.token == token) it.copy(revoked = true) else it })) }
}
