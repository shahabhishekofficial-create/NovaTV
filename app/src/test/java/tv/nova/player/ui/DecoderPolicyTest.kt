package tv.nova.player.ui

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderPolicyTest {
    @After
    fun reset() {
        DecoderPolicy.allowSoftwareVideoForTests = false
    }

    @Test
    fun productionRejectsKnownSoftwareVideoDecoders() {
        DecoderPolicy.allowSoftwareVideoForTests = false
        assertFalse(DecoderPolicy.shouldUseDecoder("c2.android.avc.decoder"))
        assertFalse(DecoderPolicy.shouldUseDecoder("OMX.google.h264.decoder"))
        assertFalse(DecoderPolicy.shouldUseDecoder("c2.sw.av1.decoder"))
        assertTrue(DecoderPolicy.shouldUseDecoder("OMX.qcom.video.decoder.avc"))
    }

    @Test
    fun instrumentationOptInDoesNotRejectSoftwareVideo() {
        DecoderPolicy.allowSoftwareVideoForTests = true
        assertTrue(DecoderPolicy.shouldUseDecoder("c2.android.avc.decoder"))
        assertTrue(DecoderPolicy.shouldUseDecoder("OMX.qcom.video.decoder.avc"))
    }
}
