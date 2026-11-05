package ru.tbcarus.photo_cloud_client.ui.screens.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerZoomTest {

    private val container = Size(1000f, 2000f)
    // Пейзажное фото 4000×3000 в портретном экране: вписано по ширине.
    private val fitted = ViewerZoom.fitted(4000, 3000, container)

    @Test
    fun imageIsFittedKeepingProportions() {
        assertEquals(Size(1000f, 750f), fitted)
        assertEquals(Size(1500f, 2000f), ViewerZoom.fitted(3000, 4000, Size(2000f, 2000f)))
        assertEquals(Size.Zero, ViewerZoom.fitted(0, 10, container))
    }

    @Test
    fun pinchKeepsFocusPointAndIsLimited() {
        val focus = Offset(200f, 100f)
        val zoomed = ViewerZoom.transform(ViewerTransform(), zoom = 2f, pan = Offset.Zero, focus = focus, container = container, fitted = fitted)

        assertEquals(2f, zoomed.scale)
        // Точка изображения под пальцами: (focus - offset) / scale до и после совпадает.
        assertEquals(focus.x / 1f, (focus.x - zoomed.offset.x) / zoomed.scale, 0.01f)
        val max = ViewerZoom.transform(zoomed, zoom = 100f, pan = Offset.Zero, focus = Offset.Zero, container = container, fitted = fitted)
        assertEquals(ViewerZoom.MAX_SCALE, max.scale)
        val min = ViewerZoom.transform(zoomed, zoom = 0.01f, pan = Offset.Zero, focus = Offset.Zero, container = container, fitted = fitted)
        assertEquals(ViewerTransform(), min)
    }

    @Test
    fun panIsClampedToImageEdges() {
        val zoomed = ViewerTransform(scale = 2f)
        val moved = ViewerZoom.transform(zoomed, 1f, pan = Offset(5000f, 5000f), focus = Offset.Zero, container = container, fitted = fitted)

        // По ширине изображение 2000 при экране 1000: сдвиг до 500. По высоте 1500 при экране 2000: остаётся по центру.
        assertEquals(Offset(500f, 0f), moved.offset)
        val back = ViewerZoom.transform(zoomed, 1f, pan = Offset(-5000f, 0f), focus = Offset.Zero, container = container, fitted = fitted)
        assertEquals(-500f, back.offset.x)
    }

    @Test
    fun undefinedGesturePointResetsInsteadOfLosingImage() {
        val broken = ViewerZoom.transform(ViewerTransform(scale = 2f), 1f, Offset.Zero, focus = Offset(Float.NaN, Float.NaN),
            container = container, fitted = fitted)

        assertEquals(ViewerTransform(), broken)
    }

    @Test
    fun doubleTapZoomsToPointAndBack() {
        val tap = Offset(300f, 0f)
        val zoomed = ViewerZoom.doubleTap(ViewerTransform(), tap, container, fitted)

        assertTrue(zoomed.zoomed)
        assertEquals(ViewerZoom.DOUBLE_TAP_SCALE, zoomed.scale)
        assertEquals(tap.x * (1 - ViewerZoom.DOUBLE_TAP_SCALE), zoomed.offset.x, 0.01f)
        assertFalse(ViewerZoom.doubleTap(zoomed, tap, container, fitted).zoomed)
    }
}
