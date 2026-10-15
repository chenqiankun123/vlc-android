package org.videolan.vlc.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.min

/** Only used for generated video thumbnails, never for user-supplied artwork. */
internal object VideoThumbnailExtractor {
    private const val MAX_SIZE = 512
    private const val SAMPLE_SIZE = 32
    private const val BLACK_LEVEL = 16

    fun isUsable(bitmap: Bitmap): Boolean {
        val columns = min(SAMPLE_SIZE, bitmap.width)
        val rows = min(SAMPLE_SIZE, bitmap.height)
        var visible = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val pixel = bitmap.getPixel(
                    ((column + 0.5) * bitmap.width / columns).toInt(),
                    ((row + 0.5) * bitmap.height / rows).toInt()
                )
                if (Color.alpha(pixel) > BLACK_LEVEL &&
                    maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > BLACK_LEVEL) {
                    // Require more than 1% visible samples, tolerating compression noise.
                    if (++visible * 100 > columns * rows) return true
                }
            }
        }
        return false
    }

    internal fun candidateTimesUs(durationMs: Long): LongArray {
        if (durationMs <= 0) return longArrayOf(1_000_000, 3_000_000, 10_000_000)
        val durationUs = durationMs.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
        return longArrayOf(10, 40, 70, 90).map { durationUs / 100 * it }.distinct().toLongArray()
    }

    fun extract(context: Context, uri: Uri, durationMs: Long): Bitmap? {
        if (!uri.scheme.isSchemeFile() && uri.scheme != "content") return null
        if (uri.scheme.isSchemeFile()) {
            val initial = try {
                ThumbnailUtils.createVideoThumbnail(uri.path ?: return null, MediaStore.Video.Thumbnails.MINI_KIND)
            } catch (_: RuntimeException) {
                null
            }
            if (initial != null) {
                if (isUsable(initial)) return initial
                initial.recycle()
            }
        }

        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme.isSchemeFile()) retriever.setDataSource(uri.path)
            else retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 } ?: durationMs
            for (timeUs in candidateTimesUs(duration)) {
                val frame = try {
                    // CLOSEST_SYNC can keep returning the opening black keyframe in a long GOP.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, MAX_SIZE, MAX_SIZE)
                    else retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: RuntimeException) {
                    null
                } ?: continue
                val scale = min(1f, MAX_SIZE.toFloat() / maxOf(frame.width, frame.height))
                val thumbnail = if (scale < 1f) Bitmap.createScaledBitmap(frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1), true).also {
                    if (it !== frame) frame.recycle()
                } else frame
                if (isUsable(thumbnail)) return thumbnail
                thumbnail.recycle()
            }
        } catch (_: RuntimeException) {
            // Unsupported codecs, inaccessible files and revoked content grants use the ML fallback.
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Some platform implementations also throw while releasing a failed data source.
            }
        }
        return null
    }
}
