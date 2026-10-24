package tv.nova.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class PlaylistException(message: String, cause: Throwable? = null) : IOException(message, cause)

class PlaylistRepository(private val store: Store) {
    suspend fun cached(playlist: Playlist): List<Channel>? = withContext(Dispatchers.IO) {
        val file = store.cacheFile(playlist)
        if (!file.isFile || file.length() == 0L) return@withContext null
        try {
            file.inputStream().buffered().use { M3uParser.parse(it, playlist.userAgent, playlist.referer) }
        } catch (_: Exception) {
            null
        }
    }

    /** Downloads to a temporary file, then parses that file; neither step holds the playlist in memory. */
    suspend fun refresh(playlist: Playlist): List<Channel> = withContext(Dispatchers.IO) {
        val destination = store.cacheFile(playlist)
        val temporary = File(destination.parentFile, "${destination.name}.download")
        var connection: HttpURLConnection? = null
        try {
            destination.parentFile?.mkdirs()
            connection = openFollowingRedirects(playlist)
            connection.inputStream.use { input ->
                FileOutputStream(temporary).buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            val channels = temporary.inputStream().buffered().use {
                M3uParser.parse(it, playlist.userAgent, playlist.referer)
            }
            if (!temporary.renameTo(destination)) {
                temporary.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                temporary.delete()
            }
            store.markLoaded(playlist.id, System.currentTimeMillis())
            channels
        } catch (error: Exception) {
            temporary.delete()
            if (error is PlaylistException) throw error
            val message = when (error) {
                is UnknownHostException -> "Playlist server could not be found. Check the URL and your connection."
                is ConnectException -> "Playlist server is unreachable. Check the URL and your connection."
                is SocketTimeoutException -> "Playlist server took too long to respond. Try refresh again."
                is IOException -> "Playlist could not be loaded: ${error.message ?: "network error"}"
                else -> "Playlist could not be read: ${error.message ?: "unknown error"}"
            }
            throw PlaylistException(message, error)
        } finally {
            connection?.disconnect()
        }
    }

    private fun openFollowingRedirects(playlist: Playlist): HttpURLConnection {
        var current = URL(playlist.url)
        repeat(6) { attempt ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", playlist.userAgent ?: "NovaTV/1.0 (Android TV)")
                playlist.referer?.let { setRequestProperty("Referer", it) }
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw PlaylistException("Playlist server returned an invalid redirect.")
                if (attempt == 5) throw PlaylistException("Playlist redirected too many times.")
                current = URL(current, location)
                if (current.protocol !in setOf("http", "https")) throw PlaylistException("Playlist redirect used an unsupported protocol.")
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                val message = when (code) {
                    401, 403 -> "Server refused the playlist (HTTP $code). Check access rights or headers."
                    404, 410 -> "Playlist was not found (HTTP $code). Check its URL."
                    else -> "Playlist server returned HTTP $code."
                }
                throw PlaylistException(message)
            }
            return connection
        }
        throw PlaylistException("Playlist redirected too many times.")
    }
}
