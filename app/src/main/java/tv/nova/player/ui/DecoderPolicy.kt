package tv.nova.player.ui

import java.util.Locale

/** Production uses hardware video. Instrumentation may opt in on software-only emulators. */
internal object DecoderPolicy {
    @Volatile
    var allowSoftwareVideoForTests = false

    fun isSoftwareDecoder(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.startsWith("omx.google.") || normalized.startsWith("c2.android.") ||
            normalized.startsWith("omx.sw.") || normalized.startsWith("c2.sw.") ||
            normalized.contains(".sw.") || normalized.contains("ffmpeg") || normalized.contains("avcodec")
    }

    fun shouldUseDecoder(name: String): Boolean = allowSoftwareVideoForTests || !isSoftwareDecoder(name)
}
