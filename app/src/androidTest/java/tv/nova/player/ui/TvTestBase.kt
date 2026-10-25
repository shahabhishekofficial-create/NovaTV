package tv.nova.player.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import tv.nova.player.R
import tv.nova.player.data.Playlist
import tv.nova.player.data.Store
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

abstract class TvTestBase {
    @get:Rule
    val testName = TestName()

    protected val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected lateinit var activity: HomeActivity
    private lateinit var evidenceDirectory: File

    @Before
    fun setUp() {
        DecoderPolicy.allowSoftwareVideoForTests = true
        val context = instrumentation.targetContext
        context.getSharedPreferences("novatv", 0).edit().clear().commit()
        File(context.filesDir, "catalogues").deleteRecursively()
        evidenceDirectory = File(context.getExternalFilesDir(null), "test-evidence").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) {
            screenshot("${testName.methodName}-final")
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
        DecoderPolicy.allowSoftwareVideoForTests = false
    }

    protected fun launchHome(
        m3u: String = HOME_CHANNELS,
        playlistName: String = "Instrumented playlist",
    ): HomeActivity {
        check(!::activity.isInitialized) { "This test case launches one Activity" }
        val context = instrumentation.targetContext
        val store = Store(context)
        val playlist = Playlist(
            id = "instrumented",
            name = playlistName,
            url = "http://127.0.0.1:1/offline-refresh.m3u",
        )
        store.savePlaylist(playlist)
        store.setActive(playlist.id)
        store.cacheFile(playlist).writeText(m3u)
        val launch = Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(launch) as HomeActivity
        waitFor("cached channel grid") { gridCount() > 0 }
        return activity
    }

    protected fun onMain(action: () -> Unit) {
        if (instrumentation.targetContext.mainLooper.thread == Thread.currentThread()) action()
        else instrumentation.runOnMainSync(action)
    }

    protected fun key(keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_BACK) leaveTouchMode()
        onMain {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        instrumentation.waitForIdleSync()
    }

    protected fun longKey(keyCode: Int) {
        onMain {
            val now = SystemClock.uptimeMillis()
            val base = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 1, 0)
            val down = KeyEvent.changeFlags(base, KeyEvent.FLAG_LONG_PRESS)
            activity.dispatchKeyEvent(down)
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
        instrumentation.waitForIdleSync()
    }

    private fun leaveTouchMode() {
        val now = SystemClock.uptimeMillis()
        injectKeys(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0),
            KeyEvent(now, now + 60, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0),
        )
    }

    private fun injectKeys(vararg events: KeyEvent) {
        events.forEach { event ->
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Could not inject $event" }
        }
        instrumentation.waitForIdleSync()
    }

    protected fun clickView(id: Int) {
        val bounds = Rect()
        onMain {
            val view = activity.findViewById<View>(id)
            check(view.isShown) { "View $id is not visible" }
            check(view.getGlobalVisibleRect(bounds)) { "View $id has no visible click area" }
            check(bounds.width() == view.width && bounds.height() == view.height) { "View $id is clipped: $bounds" }
        }
        tap(bounds)
        instrumentation.waitForIdleSync()
    }

    protected fun setSearch(query: String) {
        onMain {
            val input = activity.findViewById<EditText>(R.id.searchInput)
            input.requestFocus()
            input.setText(query)
            input.setSelection(query.length)
        }
        instrumentation.waitForIdleSync()
    }

    protected fun focusGrid(position: Int) {
        leaveTouchMode()
        onMain { activity.findViewById<RecyclerView>(R.id.channelGrid).scrollToPosition(position) }
        waitFor("focus grid position $position") {
            var focused = false
            onMain {
                val grid = activity.findViewById<RecyclerView>(R.id.channelGrid)
                val item = grid.findViewHolderForAdapterPosition(position)?.itemView
                item?.requestFocus()
                focused = item?.hasFocus() == true
            }
            focused
        }
    }

    protected fun openChannel(name: String) {
        val grid = activity.findViewById<RecyclerView>(R.id.channelGrid)
        val adapter = grid.adapter as ChannelAdapter
        val position = adapter.snapshot.indexOfFirst { it.name == name }
        check(position >= 0) { "No channel named $name. ${windowText()}" }
        focusGrid(position)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("open $name") { isVisible(R.id.playerContainer) && text(R.id.playerTitle).startsWith(name) }
    }

    protected fun assertGridFocus(name: String) {
        waitFor("grid focus on $name") {
            val grid = activity.findViewById<RecyclerView>(R.id.channelGrid)
            grid.hasFocus() && grid.focusedChild?.contentDescription?.toString()?.contains(name) == true
        }
    }

    protected fun gridCount(): Int = activity.findViewById<RecyclerView>(R.id.channelGrid).adapter?.itemCount ?: 0

    protected fun gridChannelNames(): List<String> =
        (activity.findViewById<RecyclerView>(R.id.channelGrid).adapter as ChannelAdapter).snapshot.map { it.name }

    protected fun text(id: Int): String = activity.findViewById<TextView>(id).text.toString()

    protected fun isVisible(id: Int): Boolean = activity.findViewById<View>(id).visibility == View.VISIBLE

    protected fun waitForText(value: String, contains: Boolean = false, timeoutMs: Long = 15_000): AccessibilityNodeInfo {
        val result = AtomicReference<AccessibilityNodeInfo?>()
        waitFor("text '$value'. ${windowText()}", timeoutMs) {
            result.set(findText(value, contains))
            result.get() != null
        }
        return result.get() ?: throw AssertionError("No accessibility node for '$value'")
    }

    protected fun findText(value: String, contains: Boolean = false): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val labels = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null,
            )
            if (labels.any { label ->
                    if (contains) label.contains(value, ignoreCase = true) else label.equals(value, ignoreCase = true)
                }) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    protected fun setNodeText(value: String, replacement: String, contains: Boolean = false) {
        val node = waitForText(value, contains)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
        }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { "Could not edit '$value'" }
        instrumentation.waitForIdleSync()
    }

    protected fun clickText(value: String, contains: Boolean = false) {
        val label = waitForText(value, contains)
        var clickable: AccessibilityNodeInfo? = label
        var depth = 0
        while (clickable?.isClickable == false && depth++ < 20) clickable = clickable.parent
        if (clickable?.className == "android.widget.ListView") clickable = null
        if (clickable?.isClickable == true) {
            check(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click '$value'" }
        } else {
            val bounds = Rect()
            label.getBoundsInScreen(bounds)
            check(!bounds.isEmpty) { "No tap bounds for '$value'" }
            tap(bounds)
        }
        instrumentation.waitForIdleSync()
    }

    private fun tap(bounds: Rect) {
        check(!bounds.isEmpty) { "No tap bounds: $bounds" }
        val downTime = SystemClock.uptimeMillis()
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    protected fun windowText(): String {
        val root = runCatching { instrumentation.uiAutomation.rootInActiveWindow }.getOrNull() ?: return "No active window"
        val labels = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && labels.size < 60) {
            val node = queue.removeFirst()
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return labels.joinToString(" | ")
    }

    protected fun assertHomeControlsFit() {
        val ids = intArrayOf(
            R.id.searchInput, R.id.voiceButton, R.id.hdButton, R.id.playlistsButton,
            R.id.refreshButton, R.id.categoryRail, R.id.channelGrid,
        )
        ids.forEach { id ->
            val view = activity.findViewById<View>(id)
            val visible = Rect()
            check(view.getGlobalVisibleRect(visible)) { "${resourceName(id)} is outside the screen" }
            assertEquals("${resourceName(id)} is horizontally clipped", view.width, visible.width())
            assertEquals("${resourceName(id)} is vertically clipped", view.height, visible.height())
        }
    }

    protected fun assertVisibleTextFits() {
        val display = Rect()
        activity.window.decorView.getGlobalVisibleRect(display)
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = node.text?.toString()
            if (!label.isNullOrBlank() && node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                assertTrue("Text '$label' is clipped: $bounds, display=$display", display.contains(bounds))
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
    }

    protected fun waitFor(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            try {
                if (condition()) return
            } catch (error: Throwable) {
                lastFailure = error
            }
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out: $message${lastFailure?.let { ". Last error: $it" }.orEmpty()}")
    }

    protected fun screenshot(label: String) {
        runCatching {
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@runCatching
            val safe = label.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            File(evidenceDirectory, "$safe.png").outputStream().buffered().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
        }.onFailure { android.util.Log.w("NovaTvTest", "Screenshot failed", it) }
    }

    protected fun logEvidence(message: String) {
        android.util.Log.i("NovaTvTest", "${testName.methodName}: $message")
    }

    private fun resourceName(id: Int): String = runCatching { activity.resources.getResourceEntryName(id) }.getOrDefault(id.toString())

    companion object {
        const val MEDIA_HOST = "http://10.0.2.2:8766"
        val HOME_CHANNELS = """
            #EXTM3U
            #EXTINF:-1 group-title="News",Star News HD
            $MEDIA_HOST/video.m3u8
            #EXTINF:-1 group-title="Sports",Cricket HD
            $MEDIA_HOST/video.mp4
            #EXTINF:-1 group-title="Movies",Cinema SD
            $MEDIA_HOST/video.ts
            #EXTINF:-1 group-title="Sports",Football FHD
            $MEDIA_HOST/video.flv
        """.trimIndent() + "\n"

        fun mediaChannels(switchCount: Int = 0): String = buildString {
            append("#EXTM3U\n")
            fun channel(name: String, path: String, group: String = "Formats") {
                append("#EXTINF:-1 group-title=\"").append(group).append("\",")
                append(name).append('\n').append(MEDIA_HOST).append('/').append(path).append('\n')
            }
            channel("HLS Test HD", "video.m3u8")
            channel("MP4 Test HD", "video.mp4")
            channel("TS Test HD", "video.ts")
            channel("FLV Test HD", "video.flv")
            channel("Live HLS Test HD", "live.m3u8")
            channel("Failover Test HD", "missing.m3u8")
            channel("Failover Test HD", "video.m3u8")
            channel("Dead Test HD", "missing.m3u8")
            repeat(switchCount) { index -> channel("Switch ${index + 1} HD", "video.m3u8", "Switching") }
        }
    }
}
