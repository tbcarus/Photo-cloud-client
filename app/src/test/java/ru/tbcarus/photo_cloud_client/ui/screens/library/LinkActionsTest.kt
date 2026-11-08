package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.LinkShareDto

/** Копирование и системная отправка публичной ссылки: в буфер и в Intent попадает полный адрес из ответа Server. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkActionsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val url = "https://cloud.example.com/s/3q2-7wJmS0ZpX8yQd1rKc5Hn0aTfLbVgUe4oWiYzNxA"

    @Test
    fun copyPutsFullUrlIntoClipboard() {
        copyLinkToClipboard(context, url)

        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertEquals(1, clip.itemCount)
        assertEquals(url, clip.getItemAt(0).text.toString())
        assertEquals("text/plain", clip.description.getMimeType(0))
    }

    @Test
    fun sendOpensSystemChooserWithPlainTextUrl() {
        assertTrue(sendLink(context, url))

        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(url, send.getStringExtra(Intent.EXTRA_TEXT))
        // Конкретное приложение и адресат не выбираются: только системный выбор.
        assertNull(send.component)
        assertNull(send.`package`)
    }

    @Test
    fun onlyServerHttpUrlIsShareable() {
        assertEquals(url, LinkShareDto(1, "FILE", 2, url = url).shareableUrl)
        // Ключ на Server сменился: адреса нет, собирать его из id нельзя.
        assertNull(LinkShareDto(1, "FILE", 2, url = null).shareableUrl)
        assertNull(LinkShareDto(1, "FILE", 2, url = "javascript:alert(1)").shareableUrl)
    }
}
