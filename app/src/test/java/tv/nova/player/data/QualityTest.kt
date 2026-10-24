package tv.nova.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {
    @Test fun commonQualityLabelsNormalize() {
        assertEquals(Quality.UHD, Quality.normalize("Sports 4K"))
        assertEquals(Quality.UHD, Quality.normalize(null, "2160p"))
        assertEquals(Quality.FHD, Quality.normalize("FULL HD"))
        assertEquals(Quality.FHD, Quality.normalize("1080P"))
        assertEquals(Quality.HD, Quality.normalize("StarPlusHD"))
        assertEquals(Quality.HD, Quality.normalize("720p"))
        assertEquals(Quality.SD, Quality.normalize("480p"))
        assertEquals(Quality.UNKNOWN, Quality.normalize("Hindi Movie"))
    }
}
