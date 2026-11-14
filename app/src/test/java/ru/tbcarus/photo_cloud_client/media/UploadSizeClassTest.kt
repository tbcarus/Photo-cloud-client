package ru.tbcarus.photo_cloud_client.media

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadSizeClassTest {
    @Test fun choosesProtocolAtLimits() {
        assertEquals(UploadSizeClass.UNKNOWN, SyncConfig.uploadSizeClass(-1))
        assertEquals(UploadSizeClass.UNKNOWN, SyncConfig.uploadSizeClass(0))
        assertEquals(UploadSizeClass.MULTIPART, SyncConfig.uploadSizeClass(SyncConfig.MULTIPART_MAX_BYTES))
        assertEquals(UploadSizeClass.RESUMABLE, SyncConfig.uploadSizeClass(SyncConfig.MULTIPART_MAX_BYTES + 1))
        assertEquals(UploadSizeClass.RESUMABLE, SyncConfig.uploadSizeClass(SyncConfig.RESUMABLE_MAX_BYTES))
        assertEquals(UploadSizeClass.TOO_LARGE, SyncConfig.uploadSizeClass(SyncConfig.RESUMABLE_MAX_BYTES + 1))
    }
}
