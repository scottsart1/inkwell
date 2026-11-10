package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.TranscriptSegment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Drives the real [VaultClient] (OkHttp request shapes) against the real Worker running
 * locally under `wrangler dev` (source in web/notes-vault; its contract is docs/VAULT_API.md).
 * Skipped unless the environment names it:
 *
 * ```
 * cd web/notes-vault && npm ci && printf 'NOTES_LINK_ID=devlink\nNOTES_PASSWORD=pw\nSESSION_SECRET=s\n' > .dev.vars
 * npx wrangler d1 migrations apply notes-vault --local && npx wrangler dev --local --port 8787
 * INKWELL_VAULT_DEV_URL=http://127.0.0.1:8787/notes/devlink INKWELL_VAULT_DEV_PASSWORD=pw \
 *   ./gradlew :app:testDebugUnitTest --tests '*VaultDevServerTest*'
 * ```
 *
 * The lockout test locks the dev server's address for 15 minutes; clear it with
 * `npx wrangler d1 execute notes-vault --local --command 'DELETE FROM login_attempts'`.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VaultDevServerTest {
    private val devUrl: String? = System.getenv("INKWELL_VAULT_DEV_URL")
    private val devPassword: String? = System.getenv("INKWELL_VAULT_DEV_PASSWORD")
    private val tmp: File = Files.createTempDirectory("vault-dev").toFile()
    private val http = FakeVault.httpClient()

    private fun endpoint(): VaultEndpoint {
        assumeTrue("set INKWELL_VAULT_DEV_URL and INKWELL_VAULT_DEV_PASSWORD to run against wrangler dev", devUrl != null && devPassword != null)
        return VaultEndpoint.insecureForTests(devUrl!!.toHttpUrl())
    }

    private fun client(password: String? = devPassword): VaultClient = VaultClient(http, endpoint(), MemoryCredentials(password))

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    /** Raw read of the vault, independent of the client under test. */
    private fun get(path: String, token: String): Pair<Int, String> {
        val url = endpoint().base.newBuilder().addPathSegments("api").apply { path.split('/').forEach { addPathSegment(it) } }.build()
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
            return r.code to r.body!!.string()
        }
    }

    private fun detail(id: String, token: String): JsonObject {
        val (code, body) = get("docs/$id", token)
        assertEquals(body, 200, code)
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test
    fun `t1 login ping and wrong password`() = runBlocking {
        val c = client()
        val session = c.login(devPassword!!)
        assertTrue(session.token.isNotBlank())
        assertTrue(session.expiresAt > System.currentTimeMillis() + 29L * 24 * 3600 * 1000)
        assertEquals("Your Pit Box · Notes", c.ping())
        try {
            client().login("definitely-not-$devPassword")
            fail("expected WrongPassword")
        } catch (e: VaultException.WrongPassword) {
            // 401 {"error":"invalid_password"} from the real Worker
        }
        val wrongLink = VaultEndpoint.insecureForTests(endpoint().base.newBuilder().setPathSegment(1, "nope").build())
        try {
            VaultClient(http, wrongLink, MemoryCredentials(devPassword)).ping()
            fail("expected NotAVault")
        } catch (e: VaultException.NotAVault) {
            // plain 404
        }
        val test = VaultConnectionTest.run(http, endpoint(), devPassword)
        assertEquals("Connected to Your Pit Box · Notes", test.getOrThrow().message)
    }

    @Test
    fun `t2 doc transcript and file uploads land as the contract describes`() = runBlocking {
        val creds = MemoryCredentials(devPassword)
        val c = VaultClient(http, endpoint(), creds)
        val d = doc(id = UUID.randomUUID().toString(), folderId = "f2")
        val id = VaultNames.docId(d.id)
        val markdown = "# Markets\n\nYield curve — naïve 😀 test\n"
        c.putDoc(id, SyncPayloads.docUpsert(d, "Economics / Term 1", markdown))
        val token = creds.stored!!.token

        var det = detail(id, token)
        assertEquals(d.title, det["title"]!!.jsonPrimitive.content)
        assertEquals("NOTEBOOK", det["kind"]!!.jsonPrimitive.content)
        assertEquals(d.createdAt, det["createdAt"]!!.jsonPrimitive.long)
        assertEquals(d.modifiedAt, det["modifiedAt"]!!.jsonPrimitive.long)
        assertEquals("Economics / Term 1", det["folderPath"]!!.jsonPrimitive.content)
        assertEquals(12, det["pageCount"]!!.jsonPrimitive.int)
        assertEquals(listOf("finance", "lecture"), det["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(markdown, det["markdown"]!!.jsonPrimitive.content)
        assertTrue(id in c.listDocIds())

        // Transcript with and without speaker.
        val rec = recording(id = "rec_${UUID.randomUUID()}", docId = d.id)
        val segments = listOf(TranscriptSegment(0, 4_000, "Good morning.", "Prof. Lee"), TranscriptSegment(4_000, 9_500, "Yield curves.", null))
        c.putTranscript(id, VaultNames.recordingId(rec.id), SyncPayloads.transcriptUpsert(rec, segments, "openai:whisper-1"))
        det = detail(id, token)
        val t = det["transcripts"]!!.jsonArray.single().jsonObject
        assertEquals(rec.id, t["recordingId"]!!.jsonPrimitive.content)
        assertEquals("Lecture 3", t["title"]!!.jsonPrimitive.content)
        assertEquals(65_000L, t["durationMs"]!!.jsonPrimitive.long)
        assertEquals("openai:whisper-1", t["source"]!!.jsonPrimitive.content)
        val segs = t["segments"]!!.jsonArray
        assertEquals("Prof. Lee", segs[0].jsonObject["speaker"]!!.jsonPrimitive.content)
        assertEquals("Yield curves.", segs[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1, det["transcriptCount"]!!.jsonPrimitive.int)

        // Files: streamed PDF, markdown bytes with a charset parameter, audio by convention name.
        val pdf = File(tmp, "n.pdf").apply { writeBytes(ByteArray(70_000) { (it * 31 + 7).toByte() }) }
        c.putFile(id, VaultNames.NOTES_PDF, pdf, VaultNames.MIME_PDF)
        c.putBytes(id, VaultNames.NOTES_MD, markdown.encodeToByteArray(), VaultNames.MIME_MARKDOWN)
        val audio = File(tmp, "a.m4a").apply { writeBytes(FakeSource.audioBytes(rec.id)) }
        c.putFile(id, VaultNames.audioFile(rec.id), audio, VaultNames.MIME_AUDIO)
        c.putBytes(id, "empty.bin", ByteArray(0), "application/octet-stream")

        det = detail(id, token)
        val files = det["files"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("notes.pdf", "notes.md", "rec_${rec.id}.m4a", "empty.bin"), files.keys)
        assertEquals(70_000, files["notes.pdf"]!!["size"]!!.jsonPrimitive.int)
        assertEquals("application/pdf", files["notes.pdf"]!!["contentType"]!!.jsonPrimitive.content)
        assertEquals("text/markdown; charset=utf-8", files["notes.md"]!!["contentType"]!!.jsonPrimitive.content)
        assertEquals("audio/mp4", files["rec_${rec.id}.m4a"]!!["contentType"]!!.jsonPrimitive.content)
        assertEquals(0, files["empty.bin"]!!["size"]!!.jsonPrimitive.int)
        assertEquals(4, det["fileCount"]!!.jsonPrimitive.int)

        val (code, body) = get("docs/$id/files/notes.md", token)
        assertEquals(200, code)
        assertEquals(markdown, body)

        // Metadata-only upsert (notes stay on the tablet): the vault keeps the title, clears the text.
        c.putDoc(id, SyncPayloads.docUpsert(d.copy(title = "Renamed"), "", null))
        det = detail(id, token)
        assertEquals("Renamed", det["title"]!!.jsonPrimitive.content)
        assertEquals("", det["markdown"]!!.jsonPrimitive.content)
        assertTrue(det["folderPath"]!!.jsonPrimitive.contentOrNull() == null)
        // Files and transcripts survive a doc upsert.
        assertEquals(4, det["fileCount"]!!.jsonPrimitive.int)
        assertEquals(1, det["transcriptCount"]!!.jsonPrimitive.int)
        assertTrue(det["ok"] == null || det["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `t3 the sync engine pushes notes transcript and audio through the real Worker`() = runBlocking {
        val prefs = DestinationPrefs(
            notes = setOf(OutputTarget.VAULT), transcripts = setOf(OutputTarget.VAULT), audio = setOf(OutputTarget.VAULT),
            vaultUrl = "https://yourpitbox.com/notes/ignored-by-the-harness", notesFormat = NotesExportFormat.PDF_AND_MARKDOWN,
            audioOnUnmeteredOnly = false,
        )
        val creds = MemoryCredentials(devPassword)
        val h = EngineHarness(tmp, prefs, vault = null, credentials = creds, endpoint = endpoint())
        val d = doc(id = UUID.randomUUID().toString(), title = "20261016_Engine Run")
        val rec = recording(id = "rec-${UUID.randomUUID()}", docId = d.id)
        h.source.docs[d.id] = d
        h.source.markdowns[d.id] = "# Engine\n\nbody"
        h.source.recordings[rec.id] = rec
        h.source.transcripts[rec.id] = StoredTranscript(listOf(TranscriptSegment(0, 1_000, "hello", null)), "openai:whisper-1")
        h.engine.enqueueEverything(prefs)
        val report = h.engine.run()

        assertNull(report.error, report.error)
        assertEquals(3, report.succeeded)
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.DONE })
        val det = detail(VaultNames.docId(d.id), creds.stored!!.token)
        assertEquals(d.title, det["title"]!!.jsonPrimitive.content)
        assertEquals("# Engine\n\nbody", det["markdown"]!!.jsonPrimitive.content)
        assertEquals(1, det["transcriptCount"]!!.jsonPrimitive.int)
        val names = det["files"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("notes.pdf", "notes.md", "rec_${rec.id}.m4a"), names)
        assertEquals(d.modifiedAt.coerceAtLeast(h.now), h.source.synced[d.id])
    }

    @Test
    fun `t9 repeated wrong passwords lock the vault and the client reports the wait`() = runBlocking {
        var locked: VaultException.Locked? = null
        for (i in 0 until 12) {
            try {
                client().login("wrong-$i")
            } catch (e: VaultException.WrongPassword) {
                continue
            } catch (e: VaultException.Locked) {
                locked = e
                break
            }
        }
        assertTrue("expected a 429 within 12 attempts", locked != null)
        assertTrue(locked!!.retryAfterSeconds in 1..(15 * 60))
        assertTrue(locked.blocksVault)
        // Even the right password is refused while locked.
        try {
            client().login(devPassword!!)
            fail("expected Locked")
        } catch (e: VaultException.Locked) {
            assertFalse(e.message!!.contains(devPassword!!))
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
