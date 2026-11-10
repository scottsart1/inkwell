package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.TranscriptSegment
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class VaultUrlAndPayloadTest {
    private fun ok(raw: String): String = (VaultUrl.parse(raw) as VaultUrl.Parsed.Valid).endpoint.baseUrl
    private fun invalid(raw: String) = assertTrue("expected invalid: $raw", VaultUrl.parse(raw) is VaultUrl.Parsed.Invalid)

    @Test
    fun `normalises whitespace, trailing slashes, case, query and fragment`() {
        val expected = "https://yourpitbox.com/notes/Ab3xQ9"
        assertEquals(expected, ok("https://yourpitbox.com/notes/Ab3xQ9"))
        assertEquals(expected, ok("  https://yourpitbox.com/notes/Ab3xQ9/  "))
        assertEquals(expected, ok("https://yourpitbox.com/notes/Ab3xQ9///"))
        assertEquals(expected, ok("HTTPS://YourPitBox.com/notes/Ab3xQ9"))
        assertEquals(expected, ok("https://yourpitbox.com/Notes/Ab3xQ9"))
        assertEquals(expected, ok("https://yourpitbox.com/notes/Ab3xQ9?utm=x#top"))
        assertEquals(expected, ok("yourpitbox.com/notes/Ab3xQ9"))
        assertEquals(expected, ok("https://yourpitbox.com:443/notes/Ab3xQ9"))
        assertEquals("https://vault.example.org:8443/notes/k", ok("https://vault.example.org:8443/notes/k"))
    }

    @Test
    fun `link ids are case sensitive and keep their characters`() {
        assertNotEquals(ok("https://yourpitbox.com/notes/abc"), ok("https://yourpitbox.com/notes/ABC"))
        assertEquals("https://yourpitbox.com/notes/a-b_c.d~e", ok("https://yourpitbox.com/notes/a-b_c.d~e"))
    }

    @Test
    fun `rejects anything that is not https host notes link`() {
        invalid("http://yourpitbox.com/notes/abc")
        invalid("ftp://yourpitbox.com/notes/abc")
        invalid("https://yourpitbox.com/notes")
        invalid("https://yourpitbox.com/notes/")
        invalid("https://yourpitbox.com/")
        invalid("https://yourpitbox.com/vault/abc")
        invalid("https://yourpitbox.com/notes/abc/api/ping")
        invalid("https://user:pw@yourpitbox.com/notes/abc")
        invalid("https://yourpitbox.com/notes/a b")
        invalid("https:///notes/abc")
        invalid("https://yourpitbox.com/notes/..")
        assertTrue(VaultUrl.parse("   ") is VaultUrl.Parsed.Empty)
        assertNull(VaultUrl.normalize("http://yourpitbox.com/notes/abc"))
    }

    @Test
    fun `http is refused with an explanation`() {
        val r = VaultUrl.parse("http://yourpitbox.com/notes/abc") as VaultUrl.Parsed.Invalid
        assertTrue(r.reason.contains("https://"))
    }

    @Test
    fun `api urls are built below the link`() {
        val e = VaultUrl.endpointOrNull("https://yourpitbox.com/notes/Ab3")!!
        assertEquals("https://yourpitbox.com/notes/Ab3/api/session", e.api("session").toString())
        assertEquals("https://yourpitbox.com/notes/Ab3/api/docs/d-1/files/rec_r.1.m4a", e.api("docs", "d-1", "files", "rec_r.1.m4a").toString())
        assertFalse("toString must not leak the link", e.toString().contains("Ab3"))
        assertEquals(e, VaultUrl.endpointOrNull("https://yourpitbox.com/notes/Ab3/"))
        assertEquals(e.fingerprint, VaultUrl.endpointOrNull(" https://YOURPITBOX.com/notes/Ab3 ")!!.fingerprint)
        assertNotEquals(e.fingerprint, VaultUrl.endpointOrNull("https://yourpitbox.com/notes/Ab4")!!.fingerprint)
    }

    @Test
    fun `vault ids keep valid local ids and map others deterministically`() {
        val uuid = "0b6f2c1e-8a4d-4c1b-9f7e-1d2c3b4a5f60"
        assertEquals(uuid, VaultNames.docId(uuid))
        val odd = VaultNames.docId("doc/with spaces_and_underscores")
        assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(odd))
        assertEquals(odd, VaultNames.docId("doc/with spaces_and_underscores"))
        assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(VaultNames.docId("x".repeat(80))))
        assertEquals("rec_1.2", VaultNames.recordingId("rec_1.2"))
        assertTrue(Regex("^[A-Za-z0-9._-]{1,100}$").matches(VaultNames.recordingId("..")))
        assertTrue(Regex("^[A-Za-z0-9._-]{1,120}$").matches(VaultNames.audioFile("r".repeat(100))))
    }

    @Test
    fun `markdown is cut to 2 MB at a character boundary with a note`() {
        val big = "é".repeat(VaultNames.MAX_MARKDOWN_BYTES) // 2 bytes each
        val fitted = SyncPayloads.fitUtf8(big, VaultNames.MAX_MARKDOWN_BYTES)
        assertTrue(fitted.encodeToByteArray().size <= VaultNames.MAX_MARKDOWN_BYTES)
        assertTrue(fitted.endsWith(SyncPayloads.TRUNCATION_NOTE))
        assertFalse(fitted.contains('�'))
        assertEquals("short", SyncPayloads.fitUtf8("short", 100))
    }

    @Test
    fun `doc upsert clamps fields to the vault limits`() {
        val d = doc().copy(title = "T".repeat(800), tags = listOf("", "ok", "x".repeat(300)) + List(250) { "t$it" })
        val u = SyncPayloads.docUpsert(d, "F".repeat(1500), "body")
        assertEquals(500, u.title.length)
        assertEquals(1000, u.folderPath!!.length)
        assertEquals(200, u.tags!!.size)
        assertEquals("ok", u.tags!!.first())
        assertEquals(200, u.tags!![1].length)
        assertEquals("Untitled", SyncPayloads.docUpsert(doc().copy(title = " "), "", null).title)
    }

    @Test
    fun `transcript markdown is readable and timestamped`() {
        val rec = recording(title = "Lecture 3")
        val md = SyncPayloads.transcriptMarkdown(
            rec,
            listOf(
                TranscriptSegment(0, 4_000, "Good morning.", "Prof. Lee"),
                TranscriptSegment(4_000, 9_000, "Yield curves today.", "Prof. Lee"),
                TranscriptSegment(3_725_000, 3_730_000, "Questions?", null),
            ),
            "20261015_Financial Markets",
            TimeZone.getTimeZone("UTC"),
        )
        assertTrue(md.startsWith("# Lecture 3\n\n20261015_Financial Markets · 2025-10-15 01:00 · 1:05\n"))
        assertTrue(md.contains("**[0:00]** **Prof. Lee:** Good morning."))
        assertTrue("speaker named once per run", md.contains("**[0:04]** Yield curves today."))
        assertTrue(md.contains("**[1:02:05]** Questions?"))
    }

    @Test
    fun `cached sessions are bound to one vault`() {
        val s = VaultSession("tok", 123)
        val stored = VaultSessionCodec.encode(s, "fp1")
        assertEquals(s, VaultSessionCodec.decode(stored, "fp1"))
        assertNull(VaultSessionCodec.decode(stored, "fp2"))
        assertNull(VaultSessionCodec.decode("garbage", "fp1"))
        assertNull(VaultSessionCodec.decode(null, "fp1"))
        assertFalse(s.toString().contains("tok"))
        assertTrue(VaultSession("t", 200_000).isUsable(now = 100_000))
        assertFalse(VaultSession("t", 120_000).isUsable(now = 100_000))
    }

    @Test
    fun `test endpoints can be http only through the test factory`() {
        val e = VaultEndpoint.insecureForTests("http://127.0.0.1:9/notes/x".toHttpUrl())
        assertEquals("http://127.0.0.1:9/notes/x", e.baseUrl)
    }
}
