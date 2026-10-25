package tv.nova.player.ui

import android.os.Debug
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.nova.player.R

@RunWith(AndroidJUnit4::class)
class PlaybackInteractionTest : TvTestBase() {
    @Test
    fun testFormatsOverlayPauseStatsAndZap() {
        launchHome(mediaChannels())
        openChannel("HLS Test HD")
        showStats()
        waitForPlayback("HLS")
        assertVisibleTextFits()

        val overlayWasVisible = isVisible(R.id.playerTitle)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK did not toggle overlay", isVisible(R.id.playerTitle) != overlayWasVisible)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(overlayWasVisible, isVisible(R.id.playerTitle))

        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        waitFor("pause state") { text(R.id.statsPanel).contains("State: Paused") }
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        waitForPlayback("resume")

        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        waitFor("media next") { text(R.id.playerTitle).startsWith("MP4 Test HD") }
        waitForPlayback("MP4 after zap")
        key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        waitFor("media previous") { text(R.id.playerTitle).startsWith("HLS Test HD") }
        key(KeyEvent.KEYCODE_CHANNEL_UP)
        waitFor("channel up") { text(R.id.playerTitle).startsWith("MP4 Test HD") }
        key(KeyEvent.KEYCODE_CHANNEL_DOWN)
        waitFor("channel down") { text(R.id.playerTitle).startsWith("HLS Test HD") }

        key(KeyEvent.KEYCODE_BACK)
        waitFor("leave HLS") { !isVisible(R.id.playerContainer) }
        assertGridFocus("HLS Test HD")
        listOf("MP4 Test HD", "TS Test HD", "FLV Test HD", "Live HLS Test HD").forEach { name ->
            openChannel(name)
            showStats()
            waitForPlayback(name)
            screenshot("playback-${name.replace(' ', '-')}")
            key(KeyEvent.KEYCODE_BACK)
            waitFor("leave $name") { !isVisible(R.id.playerContainer) }
            assertGridFocus(name)
        }
    }

    @Test
    fun testFailoverRetryAndFiftySwitches() {
        launchHome(mediaChannels(switchCount = 10))
        openChannel("Failover Test HD")
        showStats()
        waitFor("second source selected", 45_000) { text(R.id.playerTitle).contains("Source 2/2") }
        waitForPlayback("failover")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("leave failover") { !isVisible(R.id.playerContainer) }

        openChannel("Dead Test HD")
        waitFor("offline error", 45_000) { isVisible(R.id.errorCard) }
        assertEquals("Channel is offline", text(R.id.errorTitle))
        assertVisibleTextFits()
        screenshot("playback-offline-retry")
        clickView(R.id.retryButton)
        waitFor("button retry restarts") { !isVisible(R.id.errorCard) }
        waitFor("button retry fails cleanly", 45_000) { isVisible(R.id.errorCard) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("OK retry restarts") { !isVisible(R.id.errorCard) }
        waitFor("OK retry fails cleanly", 45_000) { isVisible(R.id.errorCard) }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("leave failed channel") { !isVisible(R.id.playerContainer) }

        setSearch("Switch")
        waitFor("ten-channel switching queue") { gridCount() == 10 }
        val switchQueue = gridChannelNames()
        val switchOffset = switchQueue.indexOf("Switch 1 HD")
        check(switchOffset >= 0) { "Switch 1 HD missing from $switchQueue" }
        openChannel("Switch 1 HD")
        showStats()
        waitForPlayback("switch baseline")
        val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        repeat(50) { index ->
            val expected = switchQueue[(switchOffset + index + 1) % switchQueue.size]
            key(KeyEvent.KEYCODE_MEDIA_NEXT)
            waitFor("switch ${index + 1} to $expected; actual '${text(R.id.playerTitle)}'") { text(R.id.playerTitle).startsWith(expected) }
        }
        waitForPlayback("after 50 switches")
        val heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val nativeAfter = Debug.getNativeHeapAllocatedSize()
        logEvidence("50 switches completed; managed heap $heapBefore -> $heapAfter bytes; native heap $nativeBefore -> $nativeAfter bytes")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("home after switching") { !isVisible(R.id.playerContainer) }
        assertGridFocus("Switch 1 HD")
    }

    private fun showStats() {
        if (!isVisible(R.id.statsPanel)) key(KeyEvent.KEYCODE_INFO)
        waitFor("stats panel") { isVisible(R.id.statsPanel) }
    }

    private fun waitForPlayback(label: String) {
        waitFor("$label playback. Stats: ${runCatching { text(R.id.statsPanel) }.getOrDefault("unavailable")}", 60_000) {
            val stats = text(R.id.statsPanel)
            stats.contains("State: Playing") &&
                stats.contains("Resolution: 1280×720") &&
                stats.contains(Regex("Audio: .+Hz")) &&
                !stats.contains("Audio: Not reported")
        }
        logEvidence("$label: ${text(R.id.statsPanel).replace('\n', ';')}")
    }
}
