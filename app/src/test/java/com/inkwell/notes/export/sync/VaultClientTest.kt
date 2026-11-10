package com.inkwell.notes.export.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultClientTest {
    private val vault = FakeVault()
    private val tmp: File = Files.createTempDirectory("vault-client").toFile()

    @After
    fun tearDown() {
        vault.close()
        tmp.deleteRecursively()
    }

    @Test
    fun `login posts the password as JSON to api session and returns the token`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        val session = client.login("correct horse")

        val r = vault.requests.single()
        assertEquals("POST", r.method)
        assertEquals("/api/session", vault.relative(r))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(JsonPrimitive("correct horse"), FakeVault.jsonOf(r)["password"])
        assertEquals(1, FakeVault.jsonOf(r).size)
        assertNull("login never carries a bearer token", r.getHeader("Authorization"))
        assertEquals(vault.issuedTokens.single(), session.token)
        assertTrue(session.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `wrong password is reported as WrongPassword`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        try {
            client.login("nope")
            fail("expected WrongPassword")
        } catch (e: VaultException.WrongPassword) {
            assertFalse(e.retryable)
            assertFalse("message must not echo the password", e.message!!.contains("nope"))
        }
    }

    @Test
    fun `429 lockout reports the retry time from the body`() = runBlocking {
        vault.lockedSeconds = 754
        val client = vault.client(MemoryCredentials())
        try {
            client.login("correct horse")
            fail("expected Locked")
        } catch (e: VaultException.Locked) {
            assertEquals(754, e.retryAfterSeconds)
            assertTrue(e.blocksVault)
            assertTrue(e.message!!.contains("13 minutes"))
        }
    }

    @Test
    fun `429 without a body falls back to Retry-After`() = runBlocking {
        vault.failNext += MockResponse().setResponseCode(429).setHeader("Retry-After", "90")
        try {
            vault.client(MemoryCredentials()).login("correct horse")
            fail("expected Locked")
        } catch (e: VaultException.Locked) {
            assertEquals(90, e.retryAfterSeconds)
        }
    }

    @Test
    fun `a wrong link answers 404 and is reported as not a vault`() = runBlocking {
        val wrong = VaultEndpoint.insecureForTests(vault.server.url("/notes/not-the-link"))
        val client = VaultClient(FakeVault.httpClient(), wrong, MemoryCredentials())
        try {
            client.login("correct horse")
            fail("expected NotAVault")
        } catch (e: VaultException.NotAVault) {
            assertTrue(e.blocksVault)
        }
    }

    @Test
    fun `unreachable server is retryable`() = runBlocking {
        val endpoint = vault.endpoint
        vault.close()
        try {
            VaultClient(FakeVault.httpClient(), endpoint, MemoryCredentials()).login("correct horse")
            fail("expected Unreachable")
        } catch (e: VaultException.Unreachable) {
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `authorised calls send the bearer token and reuse the cached session`() = runBlocking {
        val creds = MemoryCredentials()
        val client = vault.client(creds)
        assertEquals("Your Pit Box · Notes", client.ping())
        assertEquals("Your Pit Box · Notes", client.ping())

        assertEquals(listOf("POST /api/session", "GET /api/ping", "GET /api/ping"), vault.calls())
        val token = vault.issuedTokens.single()
        vault.requests.drop(1).forEach { assertEquals("Bearer $token", it.getHeader("Authorization")) }
        assertEquals(token, creds.stored?.token)
    }

    @Test
    fun `a 401 clears the session, logs in again once and retries`() = runBlocking {
        val creds = MemoryCredentials()
        val client = vault.client(creds)
        client.ping()
        vault.validTokens.clear() // server-side: password changed / token invalidated

        client.putDoc("doc-1", VaultDocUpsert("t", "NOTEBOOK", 1, 2))

        assertEquals(
            listOf("POST /api/session", "GET /api/ping", "PUT /api/docs/doc-1", "POST /api/session", "PUT /api/docs/doc-1"),
            vault.calls(),
        )
        assertEquals(2, vault.issuedTokens.size)
        assertEquals("Bearer ${vault.issuedTokens[1]}", vault.requests.last().getHeader("Authorization"))
        assertEquals(vault.issuedTokens[1], creds.stored?.token)
    }

    @Test
    fun `a second 401 right after a fresh login is not retried forever`() = runBlocking {
        val creds = MemoryCredentials()
        val client = vault.client(creds)
        vault.failNext += FakeVault.json(200, """{"token":"t1","expiresAt":${System.currentTimeMillis() + 3_600_000}}""")
        vault.failNext += FakeVault.json(401, """{"error":"unauthorized"}""")
        vault.failNext += FakeVault.json(200, """{"token":"t2","expiresAt":${System.currentTimeMillis() + 3_600_000}}""")
        vault.failNext += FakeVault.json(401, """{"error":"unauthorized"}""")
        try {
            client.ping()
            fail("expected Unauthorized")
        } catch (e: VaultException.Unauthorized) {
            assertEquals(4, vault.requests.size)
            assertNull(creds.stored)
        }
    }

    @Test
    fun `an expired cached session is replaced before the call`() = runBlocking {
        val creds = MemoryCredentials().apply { stored = VaultSession("old", System.currentTimeMillis() - 1) }
        vault.client(creds).ping()
        assertEquals(listOf("POST /api/session", "GET /api/ping"), vault.calls())
    }

    @Test
    fun `missing password is reported before any request`() = runBlocking {
        try {
            vault.client(MemoryCredentials(pw = null)).ping()
            fail("expected MissingPassword")
        } catch (e: VaultException.MissingPassword) {
            assertTrue(vault.requests.isEmpty())
        }
    }

    @Test
    fun `PUT doc sends the DocUpsert fields of the contract`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        val d = doc(folderId = "f2")
        client.putDoc(VaultNames.docId(d.id), SyncPayloads.docUpsert(d, "Economics / Term 1", "# Markets\n\nYield curve"))

        val r = vault.requests.last()
        assertEquals("PUT", r.method)
        assertEquals("/api/docs/${d.id}", vault.relative(r))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = FakeVault.jsonOf(r)
        assertEquals("20261015_Financial Markets", body["title"]!!.jsonPrimitive.content)
        assertEquals("NOTEBOOK", body["kind"]!!.jsonPrimitive.content)
        assertEquals(d.createdAt, body["createdAt"]!!.jsonPrimitive.long)
        assertEquals(d.modifiedAt, body["modifiedAt"]!!.jsonPrimitive.long)
        assertEquals("Economics / Term 1", body["folderPath"]!!.jsonPrimitive.content)
        assertEquals(12, body["pageCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("finance", "lecture"), body["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("# Markets\n\nYield curve", body["markdown"]!!.jsonPrimitive.content)
        assertEquals(setOf("title", "kind", "createdAt", "modifiedAt", "folderPath", "pageCount", "tags", "markdown"), body.keys)
    }

    @Test
    fun `metadata-only upsert omits markdown and an empty folder path`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        client.putDoc("d1", SyncPayloads.docUpsert(doc(), "", null))
        val body = FakeVault.jsonOf(vault.requests.last())
        assertFalse(body.containsKey("markdown"))
        assertFalse(body.containsKey("folderPath"))
    }

    @Test
    fun `PUT transcript sends the TranscriptUpsert fields of the contract`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        val rec = recording(id = "rec-42")
        val segments = listOf(
            com.inkwell.notes.core.model.TranscriptSegment(0, 4_000, "Good morning.", "Prof. Lee"),
            com.inkwell.notes.core.model.TranscriptSegment(4_000, 9_500, "Today: yield curves.", null),
        )
        client.putTranscript("doc-1", VaultNames.recordingId(rec.id), SyncPayloads.transcriptUpsert(rec, segments, "openai:whisper-1"))

        val r = vault.requests.last()
        assertEquals("PUT", r.method)
        assertEquals("/api/docs/doc-1/transcripts/rec-42", vault.relative(r))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = FakeVault.jsonOf(r)
        assertEquals(setOf("title", "startedAt", "durationMs", "source", "segments"), body.keys)
        assertEquals("Lecture 3", body["title"]!!.jsonPrimitive.content)
        assertEquals(rec.startedAt, body["startedAt"]!!.jsonPrimitive.long)
        assertEquals(65_000L, body["durationMs"]!!.jsonPrimitive.long)
        assertEquals("openai:whisper-1", body["source"]!!.jsonPrimitive.content)
        val segs = body["segments"] as JsonArray
        assertEquals(2, segs.size)
        val first = segs[0].jsonObject
        assertEquals(0L, first["startMs"]!!.jsonPrimitive.long)
        assertEquals(4_000L, first["endMs"]!!.jsonPrimitive.long)
        assertEquals("Good morning.", first["text"]!!.jsonPrimitive.content)
        assertEquals("Prof. Lee", first["speaker"]!!.jsonPrimitive.content)
        assertFalse("null speaker is omitted", segs[1].jsonObject.containsKey("speaker"))
    }

    @Test
    fun `PUT file streams raw bytes with content type and length`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        val pdf = File(tmp, "n.pdf").apply { writeBytes(FakeSource.pdfBytes("d")) }
        client.putFile("doc-1", VaultNames.NOTES_PDF, pdf, VaultNames.MIME_PDF)

        val r = vault.requests.last()
        assertEquals("PUT", r.method)
        assertEquals("/api/docs/doc-1/files/notes.pdf", vault.relative(r))
        assertEquals("application/pdf", r.getHeader("Content-Type"))
        assertEquals(pdf.length().toString(), r.getHeader("Content-Length"))
        assertTrue(r.body.readByteArray().contentEquals(pdf.readBytes()))
    }

    @Test
    fun `markdown and audio files use their content types and names`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        client.putBytes("doc-1", VaultNames.NOTES_MD, "# Notes\n".encodeToByteArray(), VaultNames.MIME_MARKDOWN)
        val audio = File(tmp, "a.m4a").apply { writeBytes(FakeSource.audioBytes("rec-9")) }
        client.putFile("doc-1", VaultNames.audioFile("rec-9"), audio, VaultNames.MIME_AUDIO)

        val md = vault.requests[vault.requests.size - 2]
        assertEquals("/api/docs/doc-1/files/notes.md", vault.relative(md))
        assertEquals("text/markdown; charset=utf-8", md.getHeader("Content-Type"))
        assertEquals("# Notes\n", md.body.readUtf8())
        val m4a = vault.requests.last()
        assertEquals("/api/docs/doc-1/files/rec_rec-9.m4a", vault.relative(m4a))
        assertEquals("audio/mp4", m4a.getHeader("Content-Type"))
        assertEquals("2048", m4a.getHeader("Content-Length"))
    }

    @Test
    fun `files over 95 MB are refused locally`() = runBlocking {
        val big = File(tmp, "big.m4a")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(VaultNames.MAX_FILE_BYTES + 1) }
        try {
            vault.client(MemoryCredentials()).putFile("doc-1", "rec_x.m4a", big, VaultNames.MIME_AUDIO)
            fail("expected TooLarge")
        } catch (e: VaultException.TooLarge) {
            assertFalse(e.retryable)
            assertTrue(vault.requests.isEmpty())
        }
    }

    @Test
    fun `server errors are retryable and bad requests are not`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        vault.failNext += FakeVault.json(502, """{"error":"bad_gateway"}""")
        try {
            client.ping()
            fail("expected Server")
        } catch (e: VaultException.Server) {
            assertEquals(502, e.code)
            assertTrue(e.retryable)
        }
        client.ping() // logs in fine now
        vault.failNext += FakeVault.json(400, """{"error":"invalid_request","detail":"kind must be one of NOTEBOOK"}""")
        try {
            client.putDoc("doc-1", VaultDocUpsert("t", "NOTEBOOK", 1, 1))
            fail("expected Rejected")
        } catch (e: VaultException.Rejected) {
            assertEquals(400, e.code)
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("kind must be one of"))
        }
    }

    @Test
    fun `listDocIds reads the doc summaries`() = runBlocking {
        vault.docIds += setOf("a-1", "b-2")
        assertEquals(setOf("a-1", "b-2"), vault.client(MemoryCredentials()).listDocIds())
        assertEquals("GET /api/docs", vault.calls().last())
    }

    @Test
    fun `hasDoc is true for a stored document and false for a JSON not_found`() = runBlocking {
        vault.docIds += "doc-1"
        val client = vault.client(MemoryCredentials())
        assertTrue(client.hasDoc("doc-1"))
        assertFalse(client.hasDoc("doc-2"))
        assertEquals("GET /api/docs/doc-2", vault.calls().last())
    }

    @Test
    fun `hasDoc on a URL that is not a vault still reports NotAVault`() = runBlocking {
        val wrong = VaultEndpoint.insecureForTests(vault.server.url("/notes/not-the-link"))
        val client = VaultClient(FakeVault.httpClient(), wrong, MemoryCredentials())
        try {
            client.hasDoc("doc-1")
            fail("expected NotAVault")
        } catch (e: VaultException.NotAVault) {
            // A plain 404 page must not be mistaken for "document missing".
        }
    }

    @Test
    fun `storedMarkdown reads the vault's note text and is null for a missing document`() = runBlocking {
        vault.docIds += "doc-1"
        vault.markdowns["doc-1"] = "# Stored\n\nText the vault keeps"
        val client = vault.client(MemoryCredentials())
        assertEquals("# Stored\n\nText the vault keeps", client.storedMarkdown("doc-1"))
        assertNull(client.storedMarkdown("doc-2"))
        assertEquals(listOf("POST /api/session", "GET /api/docs/doc-1", "GET /api/docs/doc-2"), vault.calls())
    }

    @Test
    fun `connection test logs in then pings and names the vault`() = runBlocking {
        val r = VaultConnectionTest.run(FakeVault.httpClient(), vault.endpoint, "correct horse")
        assertEquals("Connected to Your Pit Box · Notes", r.getOrThrow().message)
        assertEquals(listOf("POST /api/session", "GET /api/ping"), vault.calls())
        assertEquals("Bearer ${vault.issuedTokens.single()}", vault.requests.last().getHeader("Authorization"))
    }

    @Test
    fun `connection test explains each failure`() = runBlocking {
        val wrong = VaultConnectionTest.run(FakeVault.httpClient(), vault.endpoint, "bad")
        assertEquals("Wrong password.", wrong.exceptionOrNull()!!.message)

        vault.lockedSeconds = 600
        val locked = VaultConnectionTest.run(FakeVault.httpClient(), vault.endpoint, "correct horse")
        assertEquals("Locked after too many attempts. Try again in 10 minutes.", locked.exceptionOrNull()!!.message)
        vault.lockedSeconds = null

        val notVault = VaultConnectionTest.run(FakeVault.httpClient(), VaultEndpoint.insecureForTests(vault.server.url("/notes/zzz")), "correct horse")
        assertTrue(notVault.exceptionOrNull()!!.message!!.contains("404"))

        val empty = VaultConnectionTest.run(FakeVault.httpClient(), vault.endpoint, "")
        assertEquals("Enter the vault password.", empty.exceptionOrNull()!!.message)

        val endpoint = vault.endpoint
        vault.close()
        val down = VaultConnectionTest.run(FakeVault.httpClient(), endpoint, "correct horse")
        assertTrue(down.exceptionOrNull()!!.message!!.startsWith("Can't reach"))
    }

    @Test
    fun `exceptions never contain the link id or the token`() = runBlocking {
        val client = vault.client(MemoryCredentials())
        client.ping()
        vault.failNext += FakeVault.json(400, """{"error":"invalid_request"}""")
        val e = runCatching { client.putDoc("doc-1", VaultDocUpsert("t", "NOTEBOOK", 1, 1)) }.exceptionOrNull()!!
        assertFalse(e.message!!.contains(vault.linkId))
        assertFalse(e.message!!.contains(vault.issuedTokens.single()))
        assertFalse(vault.endpoint.toString().contains(vault.linkId))
    }
}
