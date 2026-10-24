package tv.nova.player.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.UUID

data class PairingPayload(val name: String, val url: String, val userAgent: String?, val referer: String?)

/** Optional, token-protected LAN form. No service and no open socket after setup closes. */
class PairingServer(private val onPlaylist: (PairingPayload) -> Unit) {
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private var token = ""

    fun start(): String? {
        stop()
        val address = localAddress() ?: return null
        return try {
            token = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            val socket = ServerSocket(8765)
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            server = socket
            scope = newScope
            newScope.launch {
                while (isActive) {
                    try {
                        val client = socket.accept()
                        clients += client
                        launch {
                            try {
                                client.soTimeout = 6_000
                                handle(client)
                            } catch (_: Exception) {
                                // Malformed LAN requests simply close; the TV UI remains responsive.
                            } finally {
                                clients -= client
                                runCatching { client.close() }
                            }
                        }
                    } catch (_: Exception) {
                        if (!isActive || socket.isClosed) break
                    }
                }
            }
            "http://${address.hostAddress}:8765/?token=$token"
        } catch (_: Exception) {
            stop()
            null
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        runCatching { server?.close() }
        server = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val request = readLine(input, 2048) ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        var contentLength = 0
        while (true) {
            val line = readLine(input, 2048)
            if (line.isNullOrEmpty()) break
            if (line.startsWith("Content-Length:", true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                if (contentLength !in 0..8192) return
            }
        }
        val query = parseForm(target.substringAfter('?', ""))
        if (query["token"] != token && method == "GET") {
            respond(socket, 403, "Pairing token missing or expired.")
            return
        }
        when (method) {
            "GET" -> respond(socket, 200, form())
            "POST" -> {
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < body.size) {
                    val count = input.read(body, offset, body.size - offset)
                    if (count < 0) return
                    offset += count
                }
                val values = parseForm(body.toString(Charsets.UTF_8)) + query
                if (values["token"] != token) {
                    respond(socket, 403, "Pairing token missing or expired.")
                    return
                }
                val url = values["url"]?.trim().orEmpty()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    respond(socket, 400, "Enter an http:// or https:// playlist URL.")
                    return
                }
                onPlaylist(PairingPayload(
                    values["name"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "Phone playlist",
                    url,
                    values["userAgent"]?.trim().takeUnless { it.isNullOrEmpty() },
                    values["referer"]?.trim().takeUnless { it.isNullOrEmpty() },
                ))
                respond(socket, 200, success())
            }
        }
    }

    private fun form(): String = page("Pair a playlist", """
        <p>Paste an M3U/M3U8 playlist. It goes directly to this TV; nothing is uploaded to NovaTV.</p>
        <form method="post" action="/playlist">
          <input type="hidden" name="token" value="$token">
          <label>Playlist URL<input required name="url" type="url" placeholder="http://example.com/list.m3u"></label>
          <label>Name (optional)<input name="name" placeholder="My playlist"></label>
          <label>User-Agent (optional)<input name="userAgent"></label>
          <label>Referer (optional)<input name="referer"></label>
          <button type="submit">Send to NovaTV</button>
        </form>
    """.trimIndent())

    private fun success(): String = page("Playlist sent", "<p>Your playlist is on the TV. You can close this page.</p>")
    private fun page(title: String, body: String): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>$title</title><style>body{font:18px sans-serif;background:#080b14;color:#f7faff;max-width:700px;margin:auto;padding:24px}label{display:block;margin:18px 0}input{display:block;box-sizing:border-box;width:100%;padding:14px;margin-top:6px;font-size:18px}button,button:focus{background:#8b7cff;color:#080b14;padding:15px 22px;font-size:18px;border:0;border-radius:6px}a{color:#b9afff}</style></head><body><h1>$title</h1>$body</body></html>"""

    private fun respond(socket: Socket, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val reason = if (code == 200) "OK" else "Error"
        val headers = "HTTP/1.1 $code $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val bytes = ByteArray(max + 1)
        var size = 0
        while (size <= max) {
            val next = input.read()
            if (next < 0) return if (size == 0) null else String(bytes, 0, size, Charsets.ISO_8859_1)
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes[size++] = next.toByte()
        }
        if (size > max) throw IllegalArgumentException("Request line too long")
        return String(bytes, 0, size, Charsets.ISO_8859_1)
    }

    private fun parseForm(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        return value.split('&').mapNotNull { pair ->
            val equals = pair.indexOf('=')
            if (equals < 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, equals), "UTF-8")
            val content = URLDecoder.decode(pair.substring(equals + 1), "UTF-8")
            key to content
        }.toMap()
    }

    private fun localAddress(): Inet4Address? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?: NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
    } catch (_: Exception) {
        null
    }
}
