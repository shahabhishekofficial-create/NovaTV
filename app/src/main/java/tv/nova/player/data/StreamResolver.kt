package tv.nova.player.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.hls.HlsMediaSource

@androidx.annotation.OptIn(UnstableApi::class)
class StreamResolver {
    fun resolve(source: StreamSource): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(source.userAgent ?: "NovaTV/1.0 (Android TV)")
        source.referer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val isHls = Uri.parse(source.url).path?.endsWith(".m3u8", true) == true
        val item = MediaItem.Builder().setUri(source.url).apply {
            if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        // Media3 retries transient load failures three times; PlayerController adds bounded zapping retries.
        val retryPolicy = DefaultLoadErrorHandlingPolicy(3)
        return if (isHls) {
            HlsMediaSource.Factory(http).setLoadErrorHandlingPolicy(retryPolicy).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(http).setLoadErrorHandlingPolicy(retryPolicy).createMediaSource(item)
        }
    }
}
