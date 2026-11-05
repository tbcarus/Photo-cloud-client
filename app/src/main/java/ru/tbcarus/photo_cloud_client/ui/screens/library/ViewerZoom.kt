package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Масштаб и сдвиг изображения в просмотре. Изображение вписано в контейнер ([fitted] — его размер при масштабе 1)
 * и трансформируется относительно центра контейнера; сдвиг ограничен так, чтобы край увеличенного изображения
 * не отходил от края экрана, а меньшая экрана сторона оставалась по центру.
 */
data class ViewerTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    val zoomed: Boolean get() = scale > 1f
}

object ViewerZoom {

    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Размер изображения [imageWidth]×[imageHeight], вписанного в [container] с сохранением пропорций. */
    fun fitted(imageWidth: Int, imageHeight: Int, container: Size): Size {
        if (imageWidth <= 0 || imageHeight <= 0 || container.width <= 0f || container.height <= 0f) return Size.Zero
        val ratio = minOf(container.width / imageWidth, container.height / imageHeight)
        return Size(imageWidth * ratio, imageHeight * ratio)
    }

    /**
     * Жест: [zoom] — множитель пальцев, [pan] — сдвиг, [focus] — центр жеста относительно центра контейнера.
     * Точка изображения под [focus] остаётся под пальцами.
     */
    fun transform(current: ViewerTransform, zoom: Float, pan: Offset, focus: Offset, container: Size, fitted: Size): ViewerTransform {
        val scale = (current.scale * zoom).coerceIn(1f, MAX_SCALE)
        val ratio = scale / current.scale
        val offset = focus - (focus - current.offset) * ratio + pan
        return clamp(ViewerTransform(scale, offset), container, fitted)
    }

    /** Двойное нажатие: увеличение к точке [tap] (относительно центра) или возврат к исходному виду. */
    fun doubleTap(current: ViewerTransform, tap: Offset, container: Size, fitted: Size): ViewerTransform {
        if (current.zoomed) return ViewerTransform()
        return clamp(ViewerTransform(DOUBLE_TAP_SCALE, tap * (1f - DOUBLE_TAP_SCALE)), container, fitted)
    }

    fun clamp(transform: ViewerTransform, container: Size, fitted: Size): ViewerTransform {
        // Неопределённая точка жеста дала бы NaN и изображение пропало бы с экрана.
        if (!transform.scale.isFinite() || !transform.offset.x.isFinite() || !transform.offset.y.isFinite()) return ViewerTransform()
        val maxX = maxOf(0f, (fitted.width * transform.scale - container.width) / 2f)
        val maxY = maxOf(0f, (fitted.height * transform.scale - container.height) / 2f)
        val offset = Offset(transform.offset.x.coerceIn(-maxX, maxX), transform.offset.y.coerceIn(-maxY, maxY))
        return if (transform.scale <= 1f) ViewerTransform() else transform.copy(offset = offset)
    }
}
