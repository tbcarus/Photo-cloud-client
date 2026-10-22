package ru.tbcarus.photo_cloud_client.core.storage

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Запись пары в SharedPreferences: при замене refresh — синхронно одним Editor с проверкой результата. */
class EncryptedPrefsTokenStorageTest {

    private companion object {
        val OLD = Tokens("old-access", "refresh-1")
        val REPLACED = Tokens("new-access", "refresh-2")
    }

    private val prefs = FakeSharedPreferences().apply {
        values["accessToken"] = OLD.accessToken
        values["refreshToken"] = OLD.refreshToken
    }
    private val storage = EncryptedPrefsTokenStorage(prefs)

    @Test
    fun replacementOfRefreshIsCommittedAsOnePair() {
        assertTrue(storage.replaceIf(OLD, REPLACED))

        assertEquals(1, prefs.editors)
        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
        assertEquals(REPLACED, storage.getTokens())
        assertEquals(REPLACED, storage.tokensFlow.value)
    }

    @Test
    fun unconfirmedCommitThrowsWithoutTokens() {
        prefs.commitResult = false

        val exception = assertThrows(IOException::class.java) { storage.replaceIf(OLD, REPLACED) }

        val text = exception.message.orEmpty()
        assertFalse(listOf(OLD, REPLACED).any { text.contains(it.accessToken) || text.contains(it.refreshToken) })
        // Как SharedPreferencesImpl: память уже изменена, на диск запись не подтверждена.
        assertEquals(REPLACED, storage.getTokens())
        assertEquals(REPLACED, storage.tokensFlow.value)
    }

    @Test
    fun replacementOfAccessOnlyAndClearStayAsynchronous() {
        val accessOnly = Tokens("new-access", OLD.refreshToken)
        assertTrue(storage.replaceIf(OLD, accessOnly))
        assertTrue(storage.replaceIf(accessOnly, null))

        assertEquals(0, prefs.commits)
        assertEquals(2, prefs.applies)
        assertNull(storage.getTokens())
    }

    @Test
    fun changedPairIsNotReplaced() {
        assertFalse(storage.replaceIf(REPLACED, Tokens("late-access", "late-refresh")))

        assertEquals(0, prefs.editors)
        assertEquals(OLD, storage.getTokens())
    }

    /** SharedPreferences в памяти: commit() и apply() сразу меняют память, как SharedPreferencesImpl. */
    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, String>()
        var commitResult = true
        var editors = 0
        var commits = 0
        var applies = 0

        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
        override fun edit(): SharedPreferences.Editor {
            editors++
            return Editor()
        }
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val strings = mutableMapOf<String, String?>()
            private var cleared = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                strings[key] = value
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                cleared = true
                return this
            }
            override fun commit(): Boolean {
                commits++
                applyToMemory()
                return commitResult
            }
            override fun apply() {
                applies++
                applyToMemory()
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this

            private fun applyToMemory() {
                if (cleared) values.clear()
                strings.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }
}
