package ru.tbcarus.photo_cloud_client.library.original

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalDecoderTest {

    private fun fits(width: Int, height: Int): Boolean {
        val sample = OriginalDecoder.sampleSize(width, height)
        val w = (width + sample - 1) / sample
        val h = (height + sample - 1) / sample
        return maxOf(w, h) <= OriginalDecoder.MAX_SIDE && w.toLong() * h <= OriginalDecoder.MAX_PIXELS
    }

    @Test
    fun smallImagesAreDecodedAsIs() {
        assertEquals(1, OriginalDecoder.sampleSize(1600, 1200))
        assertEquals(1, OriginalDecoder.sampleSize(4000, 3000))
        assertEquals(1, OriginalDecoder.sampleSize(0, 100))
    }

    @Test
    fun largePhotosAreSubsampledWithinMemoryLimits() {
        // 24 Мп по стороне не проходит, 50 Мп — и по стороне, и по пикселям, 108 Мп и панорама — тоже.
        assertEquals(2, OriginalDecoder.sampleSize(6000, 4000))
        assertEquals(3, OriginalDecoder.sampleSize(8660, 5773))
        assertEquals(2, OriginalDecoder.sampleSize(4097, 100))
        for ((w, h) in listOf(6000 to 4000, 8660 to 5773, 12000 to 9000, 30000 to 1000, 3000 to 20000, 16383 to 16383)) {
            assertTrue("$w×$h", fits(w, h))
        }
        // Предел соблюдается при наименьшем шаге: шаг на единицу меньше уже не помещается.
        val sample = OriginalDecoder.sampleSize(8660, 5773)
        val w = (8660 + sample - 2) / (sample - 1)
        assertTrue(w > OriginalDecoder.MAX_SIDE)
    }
}
