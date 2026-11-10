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
 * Destination settings written by any version must load: a settings file that fails to load is
 * replaced by defaults, which would reset every preference of the user.
 */
class DestinationSettingsCompatTest {
    private val json = SettingsRepository.json

    @Test
    fun `settings that copy to the notes vault load with the vault, keeping everything else`() {
        val old = """
            {"theme":"DARK","destinations":{"notes":["FOLDER","VAULT"],"transcripts":["VAULT"],"audio":[],
             "notesFormat":"PDF","folderUri":"content://tree/x","folderLabel":"Sync",
             "vaultUrl":"https://yourpitbox.com/notes/abc","autoSync":false,"audioOnUnmeteredOnly":false}}
        """.trimIndent()
        val s = json.decodeFromString(AppSettings.serializer(), old)
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals(setOf(OutputTarget.FOLDER, OutputTarget.VAULT), s.destinations.notes)
        assertEquals(setOf(OutputTarget.VAULT), s.destinations.transcripts)
        assertTrue(s.destinations.audio.isEmpty())
        assertEquals("https://yourpitbox.com/notes/abc", s.destinations.vaultUrl)
        assertEquals("content://tree/x", s.destinations.folderUri)
        assertFalse(s.destinations.autoSync)
        assertFalse(s.destinations.audioOnUnmeteredOnly)
        assertNull(s.destinations.driveAccount)
        assertFalse("sharing by link is opt-in", s.destinations.driveShareLink)
    }

    @Test
    fun `settings written without the vault load with no vault`() {
        val s = json.decodeFromString(AppSettings.serializer(), """{"destinations":{"notes":["DRIVE"],"driveAccount":"me@example.com"}}""")
        assertEquals(setOf(OutputTarget.DRIVE), s.destinations.notes)
        assertEquals("", s.destinations.vaultUrl)
    }

    @Test
    fun `Drive and vault targets, account and link round-trip`() {
        val prefs = DestinationPrefs(
            notes = setOf(OutputTarget.DRIVE, OutputTarget.FOLDER, OutputTarget.VAULT),
            transcripts = setOf(OutputTarget.VAULT),
            audio = setOf(OutputTarget.DRIVE),
            driveAccount = "me@example.com",
            vaultUrl = "https://yourpitbox.com/notes/abc",
        )
        val text = json.encodeToString(AppSettings.serializer(), AppSettings(destinations = prefs))
        val back = json.decodeFromString(AppSettings.serializer(), text).destinations
        assertEquals(prefs, back)
    }

    @Test
    fun `the vault password never appears in the settings`() {
        val text = json.encodeToString(AppSettings.serializer(), AppSettings(destinations = DestinationPrefs(vaultUrl = "https://yourpitbox.com/notes/abc")))
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("session", ignoreCase = true))
    }

    @Test
    fun `unknown targets from another version are skipped, not fatal`() {
        val s = json.decodeFromString(
            AppSettings.serializer(),
            """{"destinations":{"notes":["DRIVE","SOMETHING_NEW","VAULT"],"transcripts":["OTHER"],"audio":["FOLDER","LATER"]}}""",
        )
        assertEquals(setOf(OutputTarget.DRIVE, OutputTarget.VAULT), s.destinations.notes)
        assertTrue(s.destinations.transcripts.isEmpty())
        assertEquals(setOf(OutputTarget.FOLDER), s.destinations.audio)
    }
}
