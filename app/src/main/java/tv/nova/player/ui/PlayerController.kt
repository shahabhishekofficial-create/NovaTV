package tv.nova.player.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import tv.nova.player.R
import tv.nova.player.data.Channel
import tv.nova.player.data.StreamResolver
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerController(
    private val activity: Activity,
    private val onPosition: (Channel, Long) -> Unit,
    private val onLeave: (Channel, Long) -> Unit,
) {
    private val root: View = activity.findViewById(R.id.playerContainer)
    private val surface: android.view.SurfaceView = activity.findViewById(R.id.playerSurface)
    private val title: TextView = activity.findViewById(R.id.playerTitle)
    private val clock: TextView = activity.findViewById(R.id.playerClock)
    private val buffering: TextView = activity.findViewById(R.id.bufferingText)
    private val stats: TextView = activity.findViewById(R.id.statsPanel)
    private val hints: TextView = activity.findViewById(R.id.playerHints)
    private val errorCard: View = activity.findViewById(R.id.errorCard)
    private val errorTitle: TextView = activity.findViewById(R.id.errorTitle)
    private val errorMessage: TextView = activity.findViewById(R.id.errorMessage)
    private val retryButton: View = activity.findViewById(R.id.retryButton)
    private val handler = Handler(Looper.getMainLooper())
    private val resolver = StreamResolver()
    private val player = ExoPlayer.Builder(activity, HardwareRenderersFactory(activity)).build()
    private var queue: List<Channel> = emptyList()
    private var current: Channel? = null
    private var sourceIndex = 0
    private var retries = 0
    private var droppedFrames = 0
    private var bandwidthEstimate = 0L
    private var decoderName = ""
    private var failed = false
    private var closed = false
    private var resumePosition = 0L
    private var resumeApplied = false
    private var hideOverlayRunnable: Runnable? = null
    private var scheduledRunnable: Runnable? = null

    val isShowing: Boolean get() = root.visibility == View.VISIBLE
    val currentChannel: Channel? get() = current

    private val ticker = object : Runnable {
        override fun run() {
            if (!isShowing || closed) return
            clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering.visibility = if (playbackState == Player.STATE_BUFFERING && !failed) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY && !resumeApplied && resumePosition > 0 && !player.isCurrentMediaItemLive) {
                player.seekTo(resumePosition)
                resumeApplied = true
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (closed || !isShowing) return
            buffering.visibility = View.GONE
            when {
                retries < 2 && !isCodecError(error) -> {
                    retries++
                    buffering.text = "Connection failed — retry ${retries}/2…"
                    buffering.visibility = View.VISIBLE
                    schedule(700L * retries) { startSource() }
                }
                current?.let { sourceIndex + 1 < it.sources.size } == true -> {
                    sourceIndex++
                    retries = 0
                    buffering.text = "Trying another source for this channel…"
                    buffering.visibility = View.VISIBLE
                    schedule(350) { startSource() }
                }
                else -> showError(error)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateStats()
    }

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) {
            droppedFrames += dropped
        }
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoder: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            decoderName = decoder
        }
        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            bandwidthEstimate = bitrateEstimate
        }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        retryButton.setOnClickListener { retry() }
    }

    fun play(channel: Channel, newQueue: List<Channel>, savedPosition: Long = 0L) {
        if (closed) return
        cancelScheduled()
        savePosition()
        queue = newQueue.ifEmpty { listOf(channel) }
        current = channel
        sourceIndex = 0
        retries = 0
        droppedFrames = 0
        bandwidthEstimate = 0
        decoderName = ""
        resumePosition = savedPosition
        resumeApplied = false
        failed = false
        root.visibility = View.VISIBLE
        player.setVideoSurfaceView(surface)
        title.text = channel.name
        errorCard.visibility = View.GONE
        showOverlay()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        startSource()
    }

    private fun startSource() {
        val channel = current ?: return
        if (closed || !isShowing) return
        failed = false
        errorCard.visibility = View.GONE
        buffering.text = activity.getString(R.string.buffering)
        title.text = if (channel.sources.size > 1) "${channel.name}  •  Source ${sourceIndex + 1}/${channel.sources.size}" else channel.name
        player.setMediaSource(resolver.resolve(channel.sources[sourceIndex]), true)
        player.prepare()
        player.playWhenReady = true
    }

    fun next() = step(1)
    fun previous() = step(-1)
    private fun step(direction: Int) {
        val channel = current ?: return
        if (queue.isEmpty()) return
        val identityIndex = queue.indexOfFirst { it === channel }
        val index = if (identityIndex >= 0) identityIndex else queue.indexOf(channel).coerceAtLeast(0)
        val next = (index + direction + queue.size) % queue.size
        play(queue[next], queue)
    }

    fun pauseResume() {
        if (failed) return retry()
        player.playWhenReady = !player.playWhenReady
        showOverlay()
    }

    fun toggleOverlay() {
        if (failed) return retry()
        if (title.visibility == View.VISIBLE) hideOverlay() else showOverlay()
    }

    fun toggleStats() {
        stats.visibility = if (stats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateStats()
    }

    fun retry() {
        if (!failed) return
        retries = 0
        failed = false
        startSource()
        showOverlay()
    }

    fun leave() {
        if (!isShowing) return
        val channel = current
        val position = player.currentPosition
        savePosition()
        cancelScheduled()
        handler.removeCallbacks(ticker)
        player.stop()
        player.clearMediaItems()
        player.clearVideoSurfaceView(surface)
        root.visibility = View.GONE
        failed = false
        if (channel != null) onLeave(channel, position)
    }

    fun close() {
        if (closed) return
        savePosition()
        closed = true
        cancelScheduled()
        handler.removeCallbacks(ticker)
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
        player.release()
    }

    private fun savePosition() {
        val channel = current ?: return
        val position = player.currentPosition
        if (position > 0 && !player.isCurrentMediaItemLive) onPosition(channel, position)
    }

    private fun showError(error: PlaybackException) {
        failed = true
        player.playWhenReady = false
        buffering.visibility = View.GONE
        errorCard.visibility = View.VISIBLE
        title.visibility = View.VISIBLE
        hints.visibility = View.VISIBLE
        clock.visibility = View.VISIBLE
        val (heading, detail) = describeError(error)
        errorTitle.text = heading
        errorMessage.text = detail
        retryButton.requestFocus()
    }

    private fun describeError(error: PlaybackException): Pair<String, String> {
        var cause: Throwable? = error
        var responseCode = -1
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) responseCode = cause.responseCode
            if (cause is UnknownHostException || cause is ConnectException || cause is SocketTimeoutException) {
                return "Stream unreachable" to "The server could not be reached. Check your connection, URL and playlist headers."
            }
            cause = cause.cause
        }
        return when {
            responseCode == 401 || responseCode == 403 -> "Server refused this stream" to "Access was denied (HTTP $responseCode). The playlist may need a different User-Agent or Referer."
            responseCode == 404 || responseCode == 410 -> "Channel is offline" to "The stream no longer exists (HTTP $responseCode). Refresh the playlist or try another channel."
            isCodecError(error) -> "Unsupported video codec" to "This TV has no hardware decoder for the stream. NovaTV does not loop through slow software decoders."
            responseCode >= 500 -> "Server refused this stream" to "The stream server returned HTTP $responseCode. Try again or choose another source."
            else -> "Playback failed" to "The stream could not be decoded or reached. Retry, change channels, or check the playlist."
        }
    }

    private fun isCodecError(error: PlaybackException): Boolean = error.errorCode in 4000..4999

    private fun showOverlay() {
        title.visibility = View.VISIBLE
        clock.visibility = View.VISIBLE
        hints.visibility = View.VISIBLE
        hideOverlayRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { if (!failed) hideOverlay() }
        hideOverlayRunnable = runnable
        handler.postDelayed(runnable, 5000)
    }

    private fun hideOverlay() {
        title.visibility = View.GONE
        clock.visibility = View.GONE
        hints.visibility = View.GONE
    }

    private fun updateStats() {
        if (stats.visibility != View.VISIBLE) return
        val size = player.videoSize
        val format = player.videoFormat
        val audio = player.audioFormat
        val formatBitrate = format?.bitrate ?: -1
        val bitrate = if (formatBitrate > 0) formatBitrate.toLong() else bandwidthEstimate
        val state = when {
            failed -> "Error"
            player.playbackState == Player.STATE_BUFFERING -> "Buffering"
            player.playbackState == Player.STATE_ENDED -> "Ended"
            player.playbackState == Player.STATE_IDLE -> "Idle"
            !player.playWhenReady -> "Paused"
            else -> "Playing"
        }
        stats.text = buildString {
            append("State: "); append(state)
            append("\nResolution: "); append(if (size.width > 0) "${size.width}×${size.height}" else "Detecting…")
            append("\nBitrate: "); append(if (bitrate > 0) String.format(Locale.ROOT, "%.1f Mbps", bitrate / 1_000_000f) else "Not reported")
            append("\nCodec: "); append(format?.codecs ?: decoderName.ifBlank { "Detecting…" })
            append("\nAudio: ")
            if (audio == null) {
                append("Not reported")
            } else {
                append(audio.sampleMimeType ?: "Unknown")
                append(" • ")
                append(if (audio.sampleRate > 0) "${audio.sampleRate} Hz" else "rate unknown")
                append(" • ")
                append(if (audio.channelCount > 0) "${audio.channelCount} ch" else "channels unknown")
            }
            append("\nDropped frames: "); append(droppedFrames)
            append("\nBuffer: "); append(player.totalBufferedDuration / 1000); append(" s")
            append("\nMode: "); append(if (player.isCurrentMediaItemLive) "Live" else "On demand")
        }
    }

    private fun schedule(delay: Long, action: () -> Unit) {
        scheduledRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { action() }
        scheduledRunnable = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun cancelScheduled() {
        scheduledRunnable?.let { handler.removeCallbacks(it) }
        scheduledRunnable = null
        hideOverlayRunnable?.let { handler.removeCallbacks(it) }
        hideOverlayRunnable = null
    }
}
