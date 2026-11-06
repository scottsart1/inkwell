package com.inkwell.notes.core.settings

import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings written before the notes vault was removed must still load: a settings file that
 * fails to load is replaced by defaults, which would reset every preference of the user.
 */
class DestinationSettingsCompatTest {
    private val json = SettingsRepository.json

    @Test
    fun `settings that copied to the notes vault load, keeping everything else`() {
        val old = """
            {"theme":"DARK","destinations":{"notes":["FOLDER","VAULT"],"transcripts":["VAULT"],"audio":[],
             "notesFormat":"PDF","folderUri":"content://tree/x","folderLabel":"Sync",
             "vaultUrl":"https://yourpitbox.com/notes/abc","autoSync":false,"audioOnUnmeteredOnly":false}}
        """.trimIndent()
        val s = json.decodeFromString(AppSettings.serializer(), old)
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals(setOf(OutputTarget.FOLDER), s.destinations.notes)
        assertTrue(s.destinations.transcripts.isEmpty())
        assertEquals("content://tree/x", s.destinations.folderUri)
        assertFalse(s.destinations.autoSync)
        assertFalse(s.destinations.audioOnUnmeteredOnly)
        assertNull(s.destinations.driveAccount)
    }

    @Test
    fun `Drive targets and account round-trip`() {
        val prefs = DestinationPrefs(
            notes = setOf(OutputTarget.DRIVE, OutputTarget.FOLDER),
            audio = setOf(OutputTarget.DRIVE),
            driveAccount = "me@example.com",
        )
        val text = json.encodeToString(AppSettings.serializer(), AppSettings(destinations = prefs))
        assertFalse("nothing about the vault is written any more", text.contains("vault", ignoreCase = true))
        val back = json.decodeFromString(AppSettings.serializer(), text).destinations
        assertEquals(prefs, back)
    }

    @Test
    fun `unknown targets from a newer version are skipped, not fatal`() {
        val s = json.decodeFromString(AppSettings.serializer(), """{"destinations":{"notes":["DRIVE","SOMETHING_NEW"]}}""")
        assertEquals(setOf(OutputTarget.DRIVE), s.destinations.notes)
    }
}
