package repository

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Which category a room belongs to, and what to call that category.
 *
 * [position] is the server's own ordering hint. It is a double because the
 * endpoint returns a float; we keep it as one so ties are possible and the caller
 * has a defined tiebreak (name, then id) rather than an arbitrary order.
 */
internal data class RoomCategory(
    val categoryId: String,
    val position: Double,
)

/**
 * Reads the room → category assignment so the drawer can group rooms the way a
 * person actually arranged them, instead of dumping everything under one header.
 *
 * ## Where the data comes from
 *
 * `GET /_matrix/client/v1/categories` (MSC3315) returns the whole assignment in
 * ONE request:
 *
 * ```json
 * { "categories": { "!room:server": { "room": "!cat:server", "position": 2.0 } } }
 * ```
 *
 * The endpoint does not carry category NAMES — only ids — so each distinct
 * category costs one further state fetch for its `m.room.create` / `m.space.name`.
 * Categories are few (a handful per space) and the whole thing runs once per room
 * -list load, never on a poll, so the cost is bounded and small.
 *
 * ## Why there is a fallback and not an error path
 *
 * Not every homeserver implements this endpoint. Synapse historically did not, so
 * a hard requirement here would leave a large share of servers with an empty
 * drawer. When the fetch fails or returns nothing, [load] yields an empty map and
 * the UI falls back to grouping by channel type — which is what Discord shows when
 * nobody has created categories anyway. The feature is an improvement, never a
 * precondition.
 *
 * ## One request per load, cached
 *
 * The result is cached on [MatrixContext] with a TTL, because the drawer asks for
 * channels on every space switch and the category layout changes about once a
 * year. Re-fetching it per switch would be a request per navigation for data that
 * cannot have changed.
 */
internal class MatrixCategories(private val context: MatrixContext) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** roomId -> category. Empty means "this server told us nothing", not "no categories". */
    suspend fun load(forceRefresh: Boolean = false): Map<String, RoomCategory> {
        if (!forceRefresh) {
            context.categoriesCache?.let { (cached, at) ->
                if (getTimeMillisCompat() - at < CACHE_TTL_MS) return cached
            }
        }
        val fetched = fetch()
        context.categoriesCache = fetched to getTimeMillisCompat()
        return fetched
    }

    /**
     * Category display names, keyed by category id. Best-effort: a category whose
     * name cannot be resolved still groups its rooms, just under a generated label.
     *
     * Guarded by a Mutex rather than `synchronized`, because resolving a name
     * suspends on network I/O and holding a monitor across a suspension blocks
     * every other thread that touches this object -- including the ones the HTTP
     * client needs to make progress. That is a deadlock, not a slow path.
     */
    suspend fun categoryNames(categories: Map<String, RoomCategory>): Map<String, String> {
        val wanted = categories.values.map { it.categoryId }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        nameLock.withLock {
            if (nameCache.keys.containsAll(wanted)) return nameCache
            return resolveNames(wanted)
        }
    }

    private val nameLock = Mutex()
    private var nameCache: Map<String, String> = emptyMap()

    private suspend fun resolveNames(categoryIds: Set<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(categoryIds.size)
        for (categoryId in categoryIds) {
            out[categoryId] = fetchCategoryName(categoryId) ?: fallbackCategoryName(categoryId)
        }
        // Merge rather than replace: a second load with a subset of categories
        // must not throw away names already paid for.
        nameCache = nameCache + out
        return out
    }

    /**
     * A category is an ordinary room whose `m.room.create` carries
     * `type: "m.category"`. Its human name is either inline on that event or in
     * `m.space.name` — Element writes the former for nested categories and the
     * latter for top-level ones, so both are tried before giving up.
     */
    private suspend fun fetchCategoryName(categoryId: String): String? {
        for (eventType in listOf("m.room.create", "m.space.name")) {
            val name = runCatching {
                val response = context.httpClient.get(
                    context.fullUrl("/rooms/$categoryId/state/$eventType")
                ) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                }
                if (response.status.value != 200) return@runCatching null
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                (body["name"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (!name.isNullOrEmpty()) return name
        }
        return null
    }

    /**
     * Category ids are room ids, which are meaningless to a human. Showing
     * "!kAbCdEf:matrix.org" as a section header is worse than showing nothing, so
     * fall back to a readable ordinal-free label derived from the localpart only
     * when it is short and has no leading punctuation.
     */
    private fun fallbackCategoryName(categoryId: String): String {
        val localpart = categoryId.substringBefore(':').removePrefix("!")
        return when {
            localpart.isBlank() -> "Other rooms"
            localpart.length <= 24 && localpart.all { it.isLetterOrDigit() || it == '-' || it == '_' } ->
                localpart.replaceFirstChar { it.uppercase() }
            else -> "Other rooms"
        }
    }

    private suspend fun fetch(): Map<String, RoomCategory> = try {
        val response = context.httpClient.get(context.fullUrl("/categories", v1 = true)) {
            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
        }
        if (response.status.value !in 200..299) {
            // 404 = endpoint not implemented (Synapse and others). 403 = no
            // permission. Both mean "no categories available", which the caller
            // handles by falling back to type grouping. Not worth a stack trace.
            response.drain()
            if (util.OrbitLog.debugEnabled) {
                util.OrbitLog.d("MatrixCategories", "/categories unavailable status=${response.status.value}; falling back to type grouping")
            }
            emptyMap()
        } else {
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val categories = root["categories"] as? kotlinx.serialization.json.JsonObject
                ?: return emptyMap()
            val out = LinkedHashMap<String, RoomCategory>(categories.size)
            for ((roomId, value) in categories) {
                val entry = value as? JsonObject ?: continue
                val categoryId = (entry["room"] as? JsonPrimitive)?.content?.trim()
                if (categoryId.isNullOrEmpty()) continue
                val position = runCatching {
                    (entry["position"] as? JsonPrimitive)?.doubleOrNull
                }.getOrNull() ?: 0.0
                out[roomId] = RoomCategory(categoryId, position)
            }
            if (util.OrbitLog.debugEnabled && out.isNotEmpty()) {
                util.OrbitLog.d("MatrixCategories", "loaded ${out.size} room categories across ${out.values.map { it.categoryId }.distinct().size} groups")
            }
            out
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (util.OrbitLog.debugEnabled) {
            util.OrbitLog.d("MatrixCategories", "/categories fetch failed: ${e.message}; falling back to type grouping")
        }
        emptyMap()
    }

    companion object {
        /** Categories change by hand, so a long TTL is safe and keeps nav free. */
        const val CACHE_TTL_MS = 5 * 60_000L

        private fun getTimeMillisCompat(): Long = io.ktor.util.date.getTimeMillis()
    }
}
