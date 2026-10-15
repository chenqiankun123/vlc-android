package org.videolan.vlc.util

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.AppContextProvider
import org.videolan.tools.BitmapCache
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.BitmapUtil
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ThumbnailsProviderTest {
    private fun bitmap(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }

    private fun media(id: Long, initialArtwork: String? = null, generated: Boolean = false): MediaWrapper {
        var artwork = initialArtwork
        return mockk<MediaWrapper>(relaxed = true).also { item ->
            every { item.id } returns id
            every { item.type } returns MediaWrapper.TYPE_VIDEO
            every { item.uri } returns Uri.parse("file:///storage/video$id.mp4")
            every { item.length } returns 10_000L
            every { item.lastModified } returns 1L
            every { item.isThumbnailGenerated } returns generated
            every { item.artworkMrl } answers { artwork }
            every { item.artworkURL = any() } answers { artwork = firstArg() }
        }
    }

    // Preserve Android separators in artwork MRLs when Robolectric runs on Windows.
    private fun thumbnailPath(id: Long) =
        AppContextProvider.appContext.getExternalFilesDir(null)!!.absolutePath + "/medialib/$id.jpg"

    private fun thumbnailFile(id: Long) = File(thumbnailPath(id)).also { it.parentFile!!.mkdirs() }

    @Before
    fun setup() {
        BitmapCache.clear()
        ReflectionHelpers.setStaticField(ThumbnailsProvider::class.java, "appDir", null)
        ReflectionHelpers.setStaticField(ThumbnailsProvider::class.java, "cacheDir", null)
        mockkObject(VideoThumbnailExtractor, AudioUtil)
    }

    @After
    fun cleanup() {
        unmockkObject(VideoThumbnailExtractor, AudioUtil)
        BitmapCache.clear()
    }

    @Test
    fun repairsExistingBlackArtworkDespiteGeneratedFlagAndEvictsAllSizes() {
        thumbnailFile(101)
        val path = thumbnailPath(101)
        val black = bitmap(Color.BLACK)
        assertTrue(BitmapUtil.saveOnDisk(black, path))
        every { AudioUtil.readCoverBitmap(path, 64) } returns black
        val item = media(101, "file://$path", generated = true)
        BitmapCache.addBitmapToMemCache("${path}_32", bitmap(Color.BLACK))
        val good = bitmap(Color.BLUE)
        every { VideoThumbnailExtractor.extract(any(), any(), any()) } returns good

        assertSame(good, ThumbnailsProvider.getMediaThumbnail(item, 64))
        assertNull(BitmapCache.getBitmapFromMemCache("${path}_32"))
        verify(exactly = 1) { item.setThumbnail(path) }
        assertSame(good, ThumbnailsProvider.getMediaThumbnail(item, 64))
        verify(exactly = 1) { VideoThumbnailExtractor.extract(any(), any(), any()) }
    }

    @Test
    fun validGeneratedArtworkUsesDiskWithoutExtractingAgain() {
        thumbnailFile(102)
        val path = thumbnailPath(102)
        val blue = bitmap(Color.BLUE)
        assertTrue(BitmapUtil.saveOnDisk(blue, path))
        every { AudioUtil.readCoverBitmap(path, 64) } returns blue
        val item = media(102, path, generated = true)

        assertNotNull(ThumbnailsProvider.getMediaThumbnail(item, 64))
        verify(exactly = 0) { VideoThumbnailExtractor.extract(any(), any(), any()) }
    }

    @Test
    fun preservesCustomBlackArtwork() {
        val file = File(AppContextProvider.appContext.cacheDir, "custom.jpg")
        val black = bitmap(Color.BLACK)
        val item = media(103, file.absolutePath)
        BitmapCache.addBitmapToMemCache("${file.absolutePath}_64", black)

        assertSame(black, ThumbnailsProvider.getMediaThumbnail(item, 64))
        verify(exactly = 0) { VideoThumbnailExtractor.extract(any(), any(), any()) }
    }

    @Test
    fun failedSaveDoesNotPublishArtwork() {
        val item = media(104)
        val good = bitmap(Color.BLUE)
        every { VideoThumbnailExtractor.extract(any(), any(), any()) } returns good
        mockkObject(BitmapUtil) {
            every { BitmapUtil.saveOnDisk(any(), any(), any(), any()) } returns false
            assertSame(good, ThumbnailsProvider.getVideoThumbnail(item, 64))
            verify(exactly = 0) { item.setThumbnail(any()) }
            verify(exactly = 0) { item.artworkURL = any() }
        }
    }

    @Test
    fun unregisteredMediaOnlyUsesMemoryCache() {
        val item = media(0)
        val good = bitmap(Color.BLUE)
        every { VideoThumbnailExtractor.extract(any(), any(), any()) } returns good

        assertSame(good, ThumbnailsProvider.getVideoThumbnail(item, 64))
        verify(exactly = 0) { item.setThumbnail(any()) }
        assertSame(good, ThumbnailsProvider.getVideoThumbnail(item, 64))
        verify(exactly = 1) { VideoThumbnailExtractor.extract(any(), any(), any()) }
    }

    @Test
    fun failedExtractionRequestsFallbackOnceAndThrottlesRepeatedLoads() {
        val item = media(105)
        every { VideoThumbnailExtractor.extract(any(), any(), any()) } returns null

        assertNull(ThumbnailsProvider.getVideoThumbnail(item, 64))
        assertNull(ThumbnailsProvider.getVideoThumbnail(item, 64))
        verify(exactly = 1) { VideoThumbnailExtractor.extract(any(), any(), any()) }
        verify(exactly = 1) { item.requestThumbnail(64, 0.4f) }
    }
}
