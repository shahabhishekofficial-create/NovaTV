package tv.nova.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.WeakHashMap

/** Small bitmap loader: ~120×68 samples, 2 MiB RAM, 4 MiB disk, no third-party code. */
class LogoLoader(private val context: android.content.Context, private val online: () -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directory = File(context.cacheDir, "logos").apply { mkdirs() }
    private val jobs = WeakHashMap<ImageView, Job>()
    private val connections = WeakHashMap<ImageView, ConnectionHolder>()
    private val memory = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun bind(url: String?, view: ImageView) {
        cancel(view)
        view.setImageResource(tv.nova.player.R.drawable.logo_placeholder)
        if (url.isNullOrBlank()) return
        view.tag = url
        memory.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        val holder = ConnectionHolder()
        connections[view] = holder
        val job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    ensureActive()
                    readDisk(url)?.let { return@withContext it }
                    if (!online()) return@withContext null
                    download(url, holder)?.also { writeDisk(url, it) }
                } catch (_: Exception) {
                    null
                }
            }
            ensureActive()
            if (view.tag == url && bitmap != null) {
                memory.put(url, bitmap)
                view.setImageBitmap(bitmap)
            }
        }
        jobs[view] = job
    }

    fun cancel(view: ImageView) {
        jobs.remove(view)?.cancel()
        connections.remove(view)?.disconnect()
        view.tag = null
    }

    fun close() {
        scope.cancel()
        connections.values.forEach { it.disconnect() }
        connections.clear()
    }

    private fun readDisk(url: String): Bitmap? {
        val file = File(directory, sha256(url))
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun download(url: String, holder: ConnectionHolder): Bitmap? {
        var current = URL(url)
        val temporary = File(context.cacheDir, "logo-${Thread.currentThread().id}-${System.nanoTime()}")
        try {
            repeat(6) { attempt ->
                val connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "NovaTV/1.0")
                }
                holder.connection = connection
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null || attempt == 5) return null
                    current = URL(current, location)
                    return@repeat
                }
                if (code !in 200..299) return null
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val bytes = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            if (total > 2 * 1024 * 1024) return null
                            output.write(bytes, 0, count)
                        }
                    }
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temporary.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return null
                var sample = 1
                while (options.outWidth / (sample * 2) >= 120 && options.outHeight / (sample * 2) >= 68) sample *= 2
                return BitmapFactory.decodeFile(temporary.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            return null
        } finally {
            holder.disconnect()
            temporary.delete()
        }
    }

    private fun writeDisk(url: String, bitmap: Bitmap) {
        val target = File(directory, sha256(url))
        val temporary = File(directory, "${target.name}.tmp")
        try {
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temporary.renameTo(target)) temporary.delete()
            var files = directory.listFiles()?.filter { it.isFile }?.toMutableList().orEmpty().toMutableList()
            var size = files.sumOf { it.length() }
            files.sortBy { it.lastModified() }
            while (size > 4 * 1024 * 1024 && files.isNotEmpty()) {
                val removed = files.removeAt(0)
                size -= removed.length()
                removed.delete()
            }
        } catch (_: Exception) {
            temporary.delete()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class ConnectionHolder {
        @Volatile var connection: HttpURLConnection? = null
        fun disconnect() {
            connection?.disconnect()
            connection = null
        }
    }
}
