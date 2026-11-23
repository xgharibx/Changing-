package eg.wadeh.app.data

import android.content.Context
import eg.wadeh.app.BuildConfig
import eg.wadeh.core.api.ChangeItem
import eg.wadeh.core.api.CompareResponse
import eg.wadeh.core.api.CreateShareRequest
import eg.wadeh.core.api.CreateShareResponse
import eg.wadeh.core.api.ListingCard
import eg.wadeh.core.api.Landmark
import eg.wadeh.core.api.RouteResult
import eg.wadeh.core.api.ListingDetail
import eg.wadeh.core.api.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false; classDiscriminator = "type"; encodeDefaults = true }

/** Data plus where it came from. A cached copy always carries the time it was fetched. */
data class Loaded<T>(val data: T, val fromCache: Boolean, val fetchedAt: Instant)

class ApiException(val status: Int, message: String) : IOException(message)

@Serializable private data class CacheEntry(val fetchedAt: String, val body: String)

/**
 * Network first; on failure, the last successful response with its fetch time.
 * The same public API and persistence path is used by every build flavour.
 */
class Repository(context: Context, private val baseUrl: String = BuildConfig.API_BASE_URL) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(BuildConfig.NETWORK_TIMEOUT_S.toLong(), TimeUnit.SECONDS).callTimeout(BuildConfig.NETWORK_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()
    private val cacheDir = File(context.cacheDir, "api").apply { mkdirs() }

    private suspend fun <T> get(path: String, params: Map<String, String?>, ser: KSerializer<T>, cacheKey: String): Loaded<T> = withContext(Dispatchers.IO) {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json").header("X-Request-Id", UUID.randomUUID().toString()).build()
        val file = File(cacheDir, cacheKey.hashCode().toUInt().toString(16) + ".json")
        try {
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw ApiException(res.code, "HTTP ${res.code}")
                val now = Instant.now()
                file.writeText(wireJson.encodeToString(CacheEntry.serializer(), CacheEntry(now.toString(), body)))
                Loaded(wireJson.decodeFromString(ser, body), false, now)
            }
        } catch (e: ApiException) {
            if (e.status == 404) file.delete() // a withdrawn offer must not be served from cache
            throw e
        } catch (e: IOException) {
            if (!file.exists()) throw e
            val entry = wireJson.decodeFromString(CacheEntry.serializer(), file.readText())
            Loaded(wireJson.decodeFromString(ser, entry.body), true, Instant.parse(entry.fetchedAt))
        }
    }

    suspend fun listings(q: String?, cursor: String? = null, filters: Map<String, String> = emptyMap()): Loaded<Page<ListingCard>> =
        get("/v1/listings", mapOf("q" to q, "cursor" to cursor, "limit" to "30") + filters, Page.serializer(ListingCard.serializer()), "listings|$q|$cursor|${filters.toSortedMap()}")

    suspend fun detail(id: String): Loaded<ListingDetail> = get("/v1/listings/$id", emptyMap(), ListingDetail.serializer(), "detail|$id")

    suspend fun landmarks(): Loaded<List<Landmark>> = get("/v1/landmarks", emptyMap(), ListSerializer(Landmark.serializer()), "landmarks")

    /** The origin is sent only for this request; it is not stored server-side (see privacy register PR-03). */
    suspend fun route(listingId: String, lng: Double, lat: Double, mode: String): Loaded<RouteResult> =
        get("/v1/route", mapOf("listing" to listingId, "fromLng" to lng.toString(), "fromLat" to lat.toString(), "mode" to mode), RouteResult.serializer(), "route|$listingId|$lng|$lat|$mode")

    suspend fun compare(ids: List<String>): Loaded<CompareResponse> =
        get("/v1/compare", mapOf("ids" to ids.joinToString(",")), CompareResponse.serializer(), "compare|${ids.sorted()}")

    private suspend fun <T> send(method: String, path: String, body: String?, ser: KSerializer<T>?, headers: Map<String, String> = emptyMap()): T? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(baseUrl + path).header("X-Request-Id", UUID.randomUUID().toString())
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, body?.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, "HTTP ${res.code}")
            ser?.let { wireJson.decodeFromString(it, text) }
        }
    }

    /** Changes per listing recorded after `since` (the oldest last-seen time among the requested homes). */
    suspend fun changesSince(ids: List<String>, since: Instant?): Map<String, List<ChangeItem>> =
        send("POST", "/v1/changes", wireJson.encodeToString(eg.wadeh.app.data.ChangesBody.serializer(), ChangesBody(ids, since?.toString())),
            MapSerializer(String.serializer(), ListSerializer(ChangeItem.serializer())))!!

    suspend fun createShare(ids: List<String>, lang: String): CreateShareResponse =
        send("POST", "/v1/shares", wireJson.encodeToString(CreateShareRequest.serializer(), CreateShareRequest(ids, lang)), CreateShareResponse.serializer())!!

    suspend fun revokeShare(token: String, secret: String) { send<Unit>("DELETE", "/v1/shares/$token", null, null, mapOf("X-Owner-Secret" to secret)) }
}

@Serializable data class ChangesBody(val ids: List<String>, val since: String?)
