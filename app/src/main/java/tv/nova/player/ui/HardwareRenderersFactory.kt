package tv.nova.player.ui

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Excludes known software video decoders and disables decoder fallback loops. */
@androidx.annotation.OptIn(UnstableApi::class)
class HardwareRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private val hardwareOnly = MediaCodecSelector { mimeType, secure, tunneling ->
        MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling).filter { info ->
            DecoderPolicy.shouldUseDecoder(info.name)
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out += MediaCodecVideoRenderer(
            context,
            hardwareOnly,
            allowedVideoJoiningTimeMs,
            false,
            eventHandler,
            eventListener,
            50,
        )
    }
}
