package tv.nova.player.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.nova.player.R
import tv.nova.player.data.Store
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class PlaylistSetupTest : TvTestBase() {
    @Test
    fun testEditorPresetDeleteButtonsAndLayout() {
        launchHome()
        clickView(R.id.playlistsButton)
        waitForText("Playlists")
        assertVisibleTextFits()
        screenshot("playlist-setup-${testName.methodName}")

        clickText("Add URL")
        waitForText("Add playlist")
        clickText("Save")
        waitForText("Add playlist")
        setNodeText("My playlist", "Saved from test")
        setNodeText("M3U / M3U8 URL", "$MEDIA_HOST/saved.m3u")
        clickText("Save")
        waitFor("editor save") { Store(activity).playlists().any { it.name == "Saved from test" } }

        clickText("Instrumented playlist", contains = true)
        clickText("Delete")
        waitForText("Delete Instrumented playlist?")
        clickText("Cancel")
        clickText("Saved from test", contains = true)
        clickText("Delete")
        waitForText("Delete Saved from test?")
        clickText("Delete")
        waitFor("playlist deletion") { Store(activity).playlists().none { it.name == "Saved from test" } }

        clickText("Edit")
        waitForText("Edit playlist")
        setNodeText("Instrumented playlist", "Renamed playlist")
        clickText("Cancel")
        assertEquals("Instrumented playlist", Store(activity).playlists().single().name)

        clickText("Built-in presets")
        waitForText("Optional iptv-org presets")
        clickText("Cancel")
        clickText("Built-in presets")
        clickText("India — iptv-org")
        waitFor("preset added") { Store(activity).playlists().any { it.name == "India — iptv-org" } }
        clickText("India — iptv-org", contains = true)
        clickText("Delete")
        waitForText("Delete India — iptv-org?")
        clickText("Cancel")
        clickText("India — iptv-org", contains = true)
        clickText("Delete")
        waitForText("Delete India — iptv-org?")
        clickText("Delete")
        waitFor("preset deleted") { Store(activity).playlists().none { it.name == "India — iptv-org" } }

        clickText("Switch")
        clickText("Reload")
        clickText("Close")
        waitForText("Star News HD")
    }

    @Test
    fun testPairingSecuritySubmissionAndShutdown() {
        launchHome()
        clickView(R.id.playlistsButton)
        val pairingLabel = waitForText("Scan with your phone", contains = true, timeoutMs = 20_000).text.toString()
        val visibleUrl = Regex("http://\\S+").find(pairingLabel)?.value
            ?: throw AssertionError("No pairing URL in $pairingLabel")
        val token = android.net.Uri.parse(visibleUrl).getQueryParameter("token")
        assertFalse(token.isNullOrBlank())
        val localUrl = visibleUrl.replace(Regex("://[^/]+"), "://127.0.0.1:8765")

        val denied = request("GET", "http://127.0.0.1:8765/")
        assertEquals(403, denied.first)
        val form = request("GET", localUrl)
        assertEquals(200, form.first)
        assertTrue(form.second.contains("Pair a playlist"))
        assertTrue(form.second.contains(token!!))

        val invalid = request("POST", "http://127.0.0.1:8765/playlist", formBody(mapOf(
            "token" to token,
            "url" to "not-a-url",
        )))
        assertEquals(400, invalid.first)
        val sent = request("POST", "http://127.0.0.1:8765/playlist", formBody(mapOf(
            "token" to token,
            "url" to "$MEDIA_HOST/phone.m3u",
            "name" to "Phone paired",
            "userAgent" to "NovaTV instrumentation",
        )))
        assertEquals(200, sent.first)
        assertTrue(sent.second.contains("Playlist sent"))
        waitFor("pairing updates active playlist") { Store(activity).activePlaylist()?.name == "Phone paired" }

        clickText("Close")
        val stopped = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 8765), 500)
            }
            false
        }.getOrDefault(true)
        assertTrue("Pairing server survived setup close", stopped)
    }

    private fun request(method: String, url: String, body: String? = null): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            code to stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun formBody(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
}
