package ru.tbcarus.photo_cloud_client.transfer

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SafOffsetTest {
    @Test fun seekableDescriptorStartsAtExactOffsetOnEachOpen() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("saf-offset-", ".bin", context.cacheDir)
        try {
            val bytes = ByteArray(8193) { (it % 127).toByte() }
            file.writeBytes(bytes)
            val documents = LocalDocuments(context)
            val uri = Uri.fromFile(file)
            assertEquals(bytes[8192].toInt() and 0xff, documents.openAt(uri, 8192).use { it.read() })
            assertEquals(bytes[8192].toInt() and 0xff, documents.openAt(uri, 8192).use { it.read() })
        } finally {
            file.delete()
        }
    }

    @Test fun streamingProviderWithoutSkipStartsAtExactOffsetOnEachOpen() {
        val bytes = ByteArray(8193) { (it % 127).toByte() }
        fun open() = object : ByteArrayInputStream(bytes) {
            override fun skip(n: Long): Long = 0
        }

        assertEquals(bytes[8192].toInt() and 0xff, open().skipExactly(8192).read())
        assertEquals(bytes[8192].toInt() and 0xff, open().skipExactly(8192).read())
        assertThrows(IOException::class.java) { open().skipExactly(bytes.size.toLong() + 1) }
    }
}
