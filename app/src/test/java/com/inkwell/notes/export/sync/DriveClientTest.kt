package com.inkwell.notes.export.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The HTTP side of Google Drive v3 as [DriveClient] speaks it, against a local server. */
class DriveClientTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val queue = ArrayDeque<(RecordedRequest) -> MockResponse>()
    private val tokensHandedOut = CopyOnWriteArrayList<Boolean>()
    private var token = "tok-1"
    private val tmp: File = Files.createTempDirectory("drive-client").toFile()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return json(500, """{"error":{"code":500}}""")
                return next(request)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private fun client() = DriveClient(http, { refresh -> tokensHandedOut += refresh; if (refresh) token = "tok-2"; token }, server.url("/").toString())

    private fun respond(r: (RecordedRequest) -> MockResponse) = synchronized(queue) { queue.addLast(r) }
    private fun respond(code: Int, body: String) = respond { json(code, body) }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=UTF-8").setBody(body)

    private fun body(r: RecordedRequest) = Json.parseToJsonElement(r.body.clone().readUtf8()).jsonObject

    @Test
    fun `account email comes from about with a bearer token`() = runBlocking {
        respond(200, """{"user":{"emailAddress":"me@example.com","displayName":"Me"}}""")
        assertEquals("me@example.com", client().accountEmail())
        val r = requests.single()
        assertEquals("/drive/v3/about", r.requestUrl!!.encodedPath)
        assertEquals("user(emailAddress)", r.requestUrl!!.queryParameter("fields"))
        assertEquals("Bearer tok-1", r.getHeader("Authorization"))
    }

    @Test
    fun `get treats trashed and missing items as absent`() = runBlocking {
        respond(200, """{"id":"a","name":"Inkwell","mimeType":"application/vnd.google-apps.folder","parents":["root"],"trashed":false}""")
        respond(200, """{"id":"b","name":"old","mimeType":"application/vnd.google-apps.folder","trashed":true}""")
        respond(404, """{"error":{"code":404,"message":"File not found: c.","errors":[{"reason":"notFound"}]}}""")
        val c = client()
        assertEquals("Inkwell", c.get("a")!!.name)
        assertNull(c.get("b"))
        assertNull(c.get("c"))
    }

    @Test
    fun `queries escape quotes and backslashes and follow pages`() = runBlocking {
        respond(200, """{"files":[{"id":"1","name":"x","mimeType":"text/plain"}],"nextPageToken":"p2"}""")
        respond(200, """{"files":[{"id":"2","name":"y","mimeType":"text/plain","appProperties":{"inkwellFile":"notes.md"}}]}""")
        val items = client().children("par'ent\\id")
        assertEquals(listOf("1", "2"), items.map { it.id })
        assertEquals(mapOf("inkwellFile" to "notes.md"), items[1].properties)
        val q = requests[0].requestUrl!!.queryParameter("q")
        assertEquals("'par\\'ent\\\\id' in parents and trashed = false", q)
        assertNull(requests[0].requestUrl!!.queryParameter("pageToken"))
        assertEquals("p2", requests[1].requestUrl!!.queryParameter("pageToken"))
        assertEquals("drive", requests[0].requestUrl!!.queryParameter("spaces"))
    }

    @Test
    fun `property search and folder search build the right queries`() = runBlocking {
        respond(200, """{"files":[]}""")
        respond(200, """{"files":[{"id":"f","name":"Term 1","mimeType":"application/vnd.google-apps.folder"}]}""")
        val c = client()
        c.findByProperty("inkwellDoc", "0b6f2c1e", parentId = null)
        assertEquals("f", c.findFolder("root1", "Term 1")!!.id)
        assertEquals(
            "appProperties has { key='inkwellDoc' and value='0b6f2c1e' } and trashed = false",
            requests[0].requestUrl!!.queryParameter("q"),
        )
        assertEquals(
            "'root1' in parents and name = 'Term 1' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
            requests[1].requestUrl!!.queryParameter("q"),
        )
    }

    @Test
    fun `folders are created with parent and app properties`() = runBlocking {
        respond(200, """{"id":"new","name":"Inkwell","mimeType":"application/vnd.google-apps.folder","parents":["root"],"appProperties":{"inkwellRoot":"1"}}""")
        val item = client().createFolder("Inkwell", null, mapOf("inkwellRoot" to "1"))
        assertEquals("new", item.id)
        val r = requests.single()
        assertEquals("POST", r.method)
        val b = body(r)
        assertEquals("Inkwell", b["name"]!!.jsonPrimitive.content)
        assertEquals("application/vnd.google-apps.folder", b["mimeType"]!!.jsonPrimitive.content)
        assertEquals(null, b["parents"])
        assertEquals("1", b["appProperties"]!!.jsonObject["inkwellRoot"]!!.jsonPrimitive.content)

        respond(200, """{"id":"sub","name":"Term 1","mimeType":"application/vnd.google-apps.folder","parents":["p"]}""")
        client().createFolder("Term 1", "p")
        assertEquals("p", body(requests[1])["parents"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `moving and renaming use addParents and removeParents`() = runBlocking {
        respond(200, """{"id":"d","name":"New title","mimeType":"application/vnd.google-apps.folder","parents":["p2"]}""")
        client().update("d", "New title", addParent = "p2", removeParents = listOf("p1", "p0"))
        val r = requests.single()
        assertEquals("PATCH", r.method)
        assertEquals("/drive/v3/files/d", r.requestUrl!!.encodedPath)
        assertEquals("p2", r.requestUrl!!.queryParameter("addParents"))
        assertEquals("p1,p0", r.requestUrl!!.queryParameter("removeParents"))
        assertEquals("New title", body(r)["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a new file is uploaded in a resumable session, converted when asked`() = runBlocking {
        respond { MockResponse().setResponseCode(200).setHeader("Location", server.url("/upload/session/abc").toString()) }
        respond(200, """{"id":"doc1","name":"transcript_Lecture","mimeType":"application/vnd.google-apps.document","parents":["f"]}""")
        val html = "<h1>Lecture</h1>".encodeToByteArray()
        val item = client().upload(
            DriveUpload(null, "f", "transcript_Lecture", "text/html", DriveNames.MIME_GOOGLE_DOC, mapOf("inkwellFile" to "transcript:r1"), DriveContent.Bytes(html)),
        )
        assertEquals("doc1", item.id)
        val start = requests[0]
        assertEquals("POST", start.method)
        assertEquals("/upload/drive/v3/files", start.requestUrl!!.encodedPath)
        assertEquals("resumable", start.requestUrl!!.queryParameter("uploadType"))
        assertEquals("text/html", start.getHeader("X-Upload-Content-Type"))
        assertEquals(html.size.toString(), start.getHeader("X-Upload-Content-Length"))
        val meta = body(start)
        assertEquals(DriveNames.MIME_GOOGLE_DOC, meta["mimeType"]!!.jsonPrimitive.content)
        assertEquals("f", meta["parents"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("transcript:r1", meta["appProperties"]!!.jsonObject["inkwellFile"]!!.jsonPrimitive.content)
        val put = requests[1]
        assertEquals("PUT", put.method)
        assertEquals("/upload/session/abc", put.requestUrl!!.encodedPath)
        assertTrue(put.getHeader("Content-Type")!!.startsWith("text/html"))
        assertEquals("<h1>Lecture</h1>", put.body.readUtf8())
    }

    @Test
    fun `replacing a file patches it and streams the content from disk`() = runBlocking {
        val audio = File(tmp, "a.m4a").apply { writeBytes(ByteArray(70_000) { it.toByte() }) }
        respond { MockResponse().setResponseCode(200).setHeader("Location", server.url("/upload/session/def").toString()) }
        respond(200, """{"id":"aud","name":"audio_Lecture.m4a","mimeType":"audio/mp4","parents":["f"]}""")
        client().upload(DriveUpload("aud", "f", "audio_Lecture.m4a", "audio/mp4", null, mapOf("inkwellFile" to "audio:r1"), DriveContent.FromFile(audio)))
        val start = requests[0]
        assertEquals("PATCH", start.method)
        assertEquals("/upload/drive/v3/files/aud", start.requestUrl!!.encodedPath)
        val meta = body(start)
        assertEquals(null, meta["parents"])
        assertEquals(null, meta["mimeType"])
        assertEquals("audio_Lecture.m4a", meta["name"]!!.jsonPrimitive.content)
        assertEquals(70_000L, requests[1].bodySize)
    }

    @Test
    fun `sharing by link creates a view-only anyone permission and withdrawing deletes it`() = runBlocking {
        respond(200, """{"id":"anyoneWithLink"}""")
        respond(204, "")
        respond(404, """{"error":{"code":404,"errors":[{"reason":"notFound"}]}}""")
        val c = client()
        c.shareWithAnyone("root1")
        val share = requests[0]
        assertEquals("POST", share.method)
        assertEquals("/drive/v3/files/root1/permissions", share.requestUrl!!.encodedPath)
        val b = body(share)
        assertEquals("reader", b["role"]!!.jsonPrimitive.content)
        assertEquals("anyone", b["type"]!!.jsonPrimitive.content)
        assertEquals("false", b["allowFileDiscovery"]!!.jsonPrimitive.content)

        c.stopSharing("root1")
        assertEquals("DELETE", requests[1].method)
        assertEquals("/drive/v3/files/root1/permissions/anyoneWithLink", requests[1].requestUrl!!.encodedPath)
        // Not shared any more (or never): withdrawing again is fine.
        c.stopSharing("root1")
        assertEquals("https://drive.google.com/drive/folders/root1", DriveClient.folderUrl("root1"))
    }

    @Test
    fun `a refused token is replaced once, then reported`() = runBlocking {
        respond(401, """{"error":{"code":401,"message":"Invalid Credentials","errors":[{"reason":"authError"}]}}""")
        respond(200, """{"user":{"emailAddress":"me@example.com"}}""")
        assertEquals("me@example.com", client().accountEmail())
        assertEquals(listOf(false, true), tokensHandedOut)
        assertEquals("Bearer tok-2", requests[1].getHeader("Authorization"))

        respond(401, "{}")
        respond(401, "{}")
        try {
            client().accountEmail()
            fail("expected Unauthorized")
        } catch (e: DriveException.Unauthorized) {
            // reported, not retried forever
        }
    }

    @Test
    fun `an upload refused for its token starts a new session with a fresh token`() = runBlocking {
        respond(401, "{}")
        respond { MockResponse().setResponseCode(200).setHeader("Location", server.url("/upload/session/x").toString()) }
        respond(200, """{"id":"n","name":"notes.md","mimeType":"text/markdown"}""")
        val item = client().upload(DriveUpload(null, "f", "notes.md", "text/markdown", null, emptyMap(), DriveContent.Bytes("# hi".encodeToByteArray())))
        assertEquals("n", item.id)
        assertEquals(3, requests.size)
        assertEquals("Bearer tok-2", requests[1].getHeader("Authorization"))
    }

    @Test
    fun `Google's error reasons map to what the user can do`() {
        assertTrue(DriveClient.classify(403, listOf("storageQuotaExceeded"), null) is DriveException.Full)
        assertTrue(DriveClient.classify(403, listOf("userRateLimitExceeded"), null) is DriveException.RateLimited)
        assertTrue(DriveClient.classify(429, emptyList(), null) is DriveException.RateLimited)
        assertTrue(DriveClient.classify(403, listOf("accessNotConfigured"), "Google Drive API has not been used in project 123 before") is DriveException.NotSetUp)
        assertTrue(DriveClient.classify(403, listOf("insufficientPermissions"), null) is DriveException.NeedsSignIn)
        assertTrue(DriveClient.classify(404, listOf("notFound"), null) is DriveException.NotFound)
        assertTrue(DriveClient.classify(503, emptyList(), null) is DriveException.Server)
        val rejected = DriveClient.classify(400, listOf("badContent"), null)
        assertTrue(rejected is DriveException.Rejected)
        assertTrue(!rejected.retryable)
    }

    @Test
    fun `server answers that are not Drive JSON are reported, not crashed on`() = runBlocking {
        respond { MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>captive portal</html>") }
        try {
            client().children("x")
            fail("expected BadResponse")
        } catch (e: DriveException.BadResponse) {
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `no network is reported as unreachable`() = runBlocking {
        val dead = DriveClient(http, { "t" }, "http://127.0.0.1:1/")
        try {
            dead.children("x")
            fail("expected Unreachable")
        } catch (e: DriveException.Unreachable) {
            assertTrue(e.blocksDrive)
        }
    }

    @Test
    fun `long ids are hashed to fit Drive's property limit`() {
        val short = "0b6f2c1e-8a4d-4c1b-9f7e-1d2c3b4a5f60"
        assertEquals("transcript:$short", DriveNames.transcriptKey(short))
        val long = "x".repeat(200)
        val key = DriveNames.audioKey(long)
        assertTrue(key.startsWith("h:"))
        assertEquals(key, DriveNames.audioKey(long))
        assertTrue(DriveNames.PROP_FILE.length + key.length <= 124)
    }
}
