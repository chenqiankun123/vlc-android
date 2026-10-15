package org.videolan.vlc.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VideoThumbnailExtractorTest {
    private fun bitmap(color: Int, width: Int = 64, height: Int = 64) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun rejectsOpaqueBlackAndTransparentFrames() {
        assertFalse(VideoThumbnailExtractor.isUsable(bitmap(Color.BLACK)))
        assertFalse(VideoThumbnailExtractor.isUsable(bitmap(Color.TRANSPARENT)))
        assertFalse(VideoThumbnailExtractor.isUsable(bitmap(Color.argb(0, 255, 255, 255))))
    }

    @Test
    fun rejectsNearBlackCompressionNoise() {
        val frame = bitmap(Color.rgb(12, 15, 16))
        frame.setPixel(1, 1, Color.WHITE)
        assertFalse(VideoThumbnailExtractor.isUsable(frame))
    }

    @Test
    fun preservesDarkScenesAndLetterboxing() {
        assertTrue(VideoThumbnailExtractor.isUsable(bitmap(Color.rgb(20, 20, 20))))
        val frame = bitmap(Color.BLACK)
        for (y in 24 until 40) for (x in 0 until 64) frame.setPixel(x, y, Color.BLUE)
        assertTrue(VideoThumbnailExtractor.isUsable(frame))
    }

    @Test
    fun handlesTinyAndNonSquareBitmaps() {
        assertFalse(VideoThumbnailExtractor.isUsable(bitmap(Color.BLACK, 1, 1)))
        assertTrue(VideoThumbnailExtractor.isUsable(bitmap(Color.RED, 1, 1)))
        assertTrue(VideoThumbnailExtractor.isUsable(bitmap(Color.RED, 1, 64)))
        assertTrue(VideoThumbnailExtractor.isUsable(bitmap(Color.RED, 128, 16)))
    }

    @Test
    fun samplesBeyondTheOpeningAtMicrosecondPositions() {
        assertArrayEquals(longArrayOf(1_000_000, 4_000_000, 7_000_000, 9_000_000),
            VideoThumbnailExtractor.candidateTimesUs(10_000))
        assertArrayEquals(longArrayOf(1_000_000, 3_000_000, 10_000_000),
            VideoThumbnailExtractor.candidateTimesUs(0))
        assertArrayEquals(VideoThumbnailExtractor.candidateTimesUs(0),
            VideoThumbnailExtractor.candidateTimesUs(-1))
    }

    @Test
    fun keepsShortClipAndLargeDurationPositionsInRange() {
        for (duration in longArrayOf(1, 101, 500, Long.MAX_VALUE)) {
            val endUs = duration.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
            val times = VideoThumbnailExtractor.candidateTimesUs(duration)
            assertTrue(times.size <= 4)
            assertTrue(times.all { it >= 0 && it < endUs })
            assertEquals(times.distinct().size, times.size)
        }
    }
}
