package tv.nova.player.ui

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import tv.nova.player.R
import tv.nova.player.data.Store

class HomeInteractionTest : TvTestBase() {
    fun testHomeButtonsSearchFiltersAndBack() {
        launchHome()
        waitForText("Star News HD")
        assertHomeControlsFit()
        assertVisibleTextFits()

        setSearch("sports")
        waitFor("group search") { gridCount() == 2 }
        setSearch("star")
        waitFor("ranked name search") { gridCount() == 1 }
        setSearch("nothing-matches-this")
        waitForText("No match for", contains = true)
        setSearch("")
        waitFor("clear search") { gridCount() == 4 }

        clickView(R.id.hdButton)
        waitFor("HD filter") { gridCount() == 3 }
        assertEquals("HD+ ✓", text(R.id.hdButton))
        clickView(R.id.hdButton)
        waitFor("HD filter off") { gridCount() == 4 }

        setSearch("star")
        waitFor("query before refresh") { gridCount() == 1 }
        clickView(R.id.refreshButton)
        waitFor("offline refresh result") { text(R.id.statusText).contains("cached", ignoreCase = true) }
        assertEquals("star", activity.findViewById<EditText>(R.id.searchInput).text.toString())
        assertTrue("Reload stole search focus", activity.findViewById<EditText>(R.id.searchInput).hasFocus())

        key(KeyEvent.KEYCODE_SEARCH)
        assertTrue(activity.findViewById<EditText>(R.id.searchInput).hasFocus())
        key(KeyEvent.KEYCODE_BACK)
        waitFor("BACK clears search") { gridCount() == 4 }
        assertTrue(activity.findViewById<EditText>(R.id.searchInput).text.isEmpty())
        screenshot("home-controls-${name ?: "test"}")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("BACK leaves app") { activity.isFinishing }
    }

    fun testFavouritesChannelPickerAndRecents() {
        launchHome()
        focusGrid(0)
        longKey(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("favourite persisted") { Store(activity).favourites().isNotEmpty() }
        clickText("Favourites", contains = true)
        waitFor("favourites rail") { gridCount() == 1 }
        clickText("All", contains = true)
        waitFor("all rail") { gridCount() == 4 }

        key(KeyEvent.KEYCODE_MENU)
        waitForText("Channel picker")
        assertVisibleTextFits()
        clickText("Close")
        key(KeyEvent.KEYCODE_MENU)
        waitForText("Channel picker")
        clickText("Cricket HD", contains = true)
        waitFor("picker starts playback") { isVisible(R.id.playerContainer) }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("player BACK") { !isVisible(R.id.playerContainer) && gridCount() == 4 }
        assertTrue(Store(activity).recents().isNotEmpty())
        clickText("Recently watched", contains = true)
        waitFor("recent rail") { gridCount() == 1 }
        waitForText("Cricket HD")
    }

    fun testVoiceFallbackAndExternalIntents() {
        launchHome()
        val voiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        if (activity.packageManager.resolveActivity(voiceIntent, 0) == null) {
            clickView(R.id.voiceButton)
            waitForText("Voice typing is not available", contains = true)
            waitFor("voice fallback focuses search") { activity.findViewById<EditText>(R.id.searchInput).hasFocus() }
        } else {
            logEvidence("A recognizer exists; external voice UI is not driven by this test")
        }

        val search = Intent(Intent.ACTION_SEARCH).putExtra(android.app.SearchManager.QUERY, "sports")
        onMain { activity.onNewIntent(search) }
        waitFor("ACTION_SEARCH") { gridCount() == 2 }
        assertEquals("sports", activity.findViewById<EditText>(R.id.searchInput).text.toString())

        val playlistUrl = "http://10.0.2.2:8766/deep-link.m3u"
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("novatv://playlist?url=${Uri.encode(playlistUrl)}"))
        onMain { activity.onNewIntent(deepLink) }
        waitForText("Add playlist")
        waitForText(playlistUrl)
        clickText("Cancel")
        clickText("Close")
        waitForText("Star News HD")
    }
}
