package ru.tbcarus.photo_cloud_client.ui.components

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.utils.Routes

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BottomNavItemsTest {

    @Test
    fun libraryReplacesProfileAndPanelKeepsFiveItems() {
        assertEquals(
            listOf(Routes.Network, Routes.Login, Routes.Settings, Routes.Library, Routes.Files),
            bottomNavItems.map { it.route }
        )
        assertEquals(listOf("Network", "Login", "Settings", "Library", "Files"), bottomNavItems.map { it.label })
    }
}
