package tv.nova.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val referer: String? = null,
    val lastLoaded: Long = 0L,
) {
    companion object {
        val presets = listOf(
            Playlist(name = "India — iptv-org", url = "https://iptv-org.github.io/iptv/countries/in.m3u"),
            Playlist(name = "Sports — iptv-org", url = "https://iptv-org.github.io/iptv/categories/sports.m3u"),
            Playlist(name = "Everything — iptv-org", url = "https://iptv-org.github.io/iptv/index.m3u"),
        )
    }
}

/** Private local preferences and files. There is deliberately no account or remote database. */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("novatv", Context.MODE_PRIVATE)

    @Synchronized fun playlists(): List<Playlist> {
        val result = ArrayList<Playlist>()
        try {
            val array = JSONArray(preferences.getString("playlists", "[]"))
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                result += Playlist(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    url = item.getString("url"),
                    userAgent = item.optString("userAgent").ifBlank { null },
                    referer = item.optString("referer").ifBlank { null },
                    lastLoaded = item.optLong("lastLoaded"),
                )
            }
        } catch (_: Exception) {
            // A damaged preference should not prevent the app from opening.
        }
        return result
    }

    @Synchronized fun savePlaylist(playlist: Playlist) {
        val all = playlists().toMutableList()
        val index = all.indexOfFirst { it.id == playlist.id }
        if (index >= 0) all[index] = playlist else all += playlist
        writePlaylists(all)
        if (activePlaylistId().isBlank()) preferences.edit().putString("active", playlist.id).apply()
    }

    @Synchronized fun deletePlaylist(id: String) {
        writePlaylists(playlists().filterNot { it.id == id })
        if (activePlaylistId() == id) {
            preferences.edit().putString("active", playlists().firstOrNull()?.id.orEmpty()).apply()
        }
        cacheFile(id).delete()
    }

    @Synchronized fun activePlaylist(): Playlist? {
        val id = activePlaylistId()
        return playlists().firstOrNull { it.id == id } ?: playlists().firstOrNull()?.also { setActive(it.id) }
    }

    fun activePlaylistId(): String = preferences.getString("active", "").orEmpty()
    fun setActive(id: String) = preferences.edit().putString("active", id).apply()
    fun hdOnly(): Boolean = preferences.getBoolean("hdOnly", false)
    fun setHdOnly(value: Boolean) = preferences.edit().putBoolean("hdOnly", value).apply()

    @Synchronized fun markLoaded(id: String, timeMillis: Long) {
        val playlist = playlists().firstOrNull { it.id == id } ?: return
        savePlaylist(playlist.copy(lastLoaded = timeMillis))
    }

    fun isFavourite(url: String): Boolean = favourites().contains(url)
    fun favourites(): Set<String> = preferences.getStringSet("favourites", emptySet())?.toSet().orEmpty()

    @Synchronized fun toggleFavourite(url: String): Boolean {
        val values = favourites().toMutableSet()
        val added = values.add(url)
        if (!added) values.remove(url)
        preferences.edit().putStringSet("favourites", values).apply()
        return added
    }

    @Synchronized fun addRecent(url: String) {
        val values = recents().toMutableList()
        values.remove(url)
        values.add(0, url)
        preferences.edit().putString("recents", JSONArray(values.take(100).map { it }).toString()).apply()
    }

    fun recents(): List<String> = try {
        val array = JSONArray(preferences.getString("recents", "[]"))
        List(array.length()) { array.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized fun saveResume(url: String, position: Long) {
        val values = resumeValues()
        if (position > 0) values.put(url, position) else values.remove(url)
        preferences.edit().putString("resume", values.toString()).apply()
    }

    fun resume(url: String): Long = resumeValues().optLong(url, 0L)
    private fun resumeValues(): JSONObject = try {
        JSONObject(preferences.getString("resume", "{}").orEmpty())
    } catch (_: Exception) {
        JSONObject()
    }

    fun cacheFile(playlist: Playlist): File = cacheFile(playlist.id)
    private fun cacheFile(id: String): File = File(File(appContext.filesDir, "catalogues"), id).apply {
        parentFile?.mkdirs()
    }

    private fun writePlaylists(items: List<Playlist>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("name", item.name); put("url", item.url)
                put("userAgent", item.userAgent.orEmpty()); put("referer", item.referer.orEmpty())
                put("lastLoaded", item.lastLoaded)
            })
        }
        preferences.edit().putString("playlists", array.toString()).apply()
    }
}
