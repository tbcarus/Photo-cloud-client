package ru.tbcarus.photo_cloud_client.library.original

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File

/**
 * Декодирование оригинала для полноэкранного просмотра через ImageDecoder: EXIF-ориентацию он применяет сам,
 * а прореживание при чтении не даёт разворачивать многомегапиксельный оригинал целиком.
 */
object OriginalDecoder {

    /** Длинная сторона декодированного кадра: запас для увеличения на экранах до 2K без лишней памяти. */
    const val MAX_SIDE = 4096
    /** Предел пикселей кадра (около 48 МБ в ARGB_8888). */
    const val MAX_PIXELS = 12_000_000L

    fun decode(file: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
        }

    /** Наименьший шаг прореживания, при котором кадр укладывается в [MAX_SIDE] и [MAX_PIXELS]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE, maxPixels: Long = MAX_PIXELS): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (true) {
            val w = (width + sample - 1) / sample
            val h = (height + sample - 1) / sample
            if (maxOf(w, h) <= maxSide && w.toLong() * h <= maxPixels) return sample
            sample++
        }
    }
}
