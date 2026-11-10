package com.inkwell.notes.export.sync

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class VaultRedirectTest {
    // ------------------------------------------------------------------ the rule (pure)

    private val www = "https://www.yourpitbox.com/notes/abc/api/ping".toHttpUrl()
    private val apex = "https://yourpitbox.com/notes/abc/api/ping".toHttpUrl()

    @Test
    fun `a permanent move from www to the bare host is followed`() {
        assertEquals("yourpitbox.com", VaultRedirect.movedHost(www, 301, "https://yourpitbox.com/notes/abc/api/ping"))
        assertEquals("yourpitbox.com", VaultRedirect.movedHost(www, 308, "https://yourpitbox.com/notes/abc/api/ping"))
    }

    @Test
    fun `a permanent move from the bare host to www is followed`() {
        assertEquals("www.yourpitbox.com", VaultRedirect.movedHost(apex, 301, "https://www.yourpitbox.com/notes/abc/api/ping"))
    }

    @Test
    fun `host case, a trailing slash and a query on the target do not matter`() {
        assertEquals("yourpitbox.com", VaultRedirect.movedHost(www, 301, "https://YourPitBox.com/notes/abc/api/ping/"))
        assertEquals("yourpitbox.com", VaultRedirect.movedHost(www, 301, "https://yourpitbox.com/notes/abc/api/ping?from=www"))
        assertEquals("yourpitbox.com", VaultRedirect.movedHost(www, 301, "  https://yourpitbox.com:443/notes/abc/api/ping  "))
    }

    @Test
    fun `temporary redirects are not followed`() {
        for (code in listOf(300, 302, 303, 307)) {
            assertNull("HTTP $code", VaultRedirect.movedHost(www, code, "https://yourpitbox.com/notes/abc/api/ping"))
        }
    }

    @Test
    fun `another site, another subdomain or a lookalike is not followed`() {
        for (target in listOf(
            "https://evil.example/notes/abc/api/ping",
            "https://yourpitbox.com.evil.example/notes/abc/api/ping",
            "https://notes.yourpitbox.com/notes/abc/api/ping",
            "https://www2.yourpitbox.com/notes/abc/api/ping",
            "https://wwwyourpitbox.com/notes/abc/api/ping",
            "https://pitbox.com/notes/abc/api/ping",
        )) {
            assertNull(target, VaultRedirect.movedHost(www, 301, target))
        }
        assertNull("bare host to a lookalike", VaultRedirect.movedHost(apex, 301, "https://www.yourpitbox.co/notes/abc/api/ping"))
    }

    @Test
    fun `a downgrade to http, another port, another path or credentials are not followed`() {
        assertNull(VaultRedirect.movedHost(www, 301, "http://yourpitbox.com/notes/abc/api/ping"))
        assertNull(VaultRedirect.movedHost(www, 301, "https://yourpitbox.com:8443/notes/abc/api/ping"))
        assertNull(VaultRedirect.movedHost(www, 301, "https://yourpitbox.com/notes/other/api/ping"))
        assertNull(VaultRedirect.movedHost(www, 301, "https://yourpitbox.com/"))
        assertNull(VaultRedirect.movedHost(www, 301, "https://user:pw@yourpitbox.com/notes/abc/api/ping"))
    }

    @Test
    fun `a missing, empty or same-host location is not followed`() {
        assertNull(VaultRedirect.movedHost(www, 301, null))
        assertNull(VaultRedirect.movedHost(www, 301, ""))
        assertNull(VaultRedirect.movedHost(www, 301, "/notes/abc/api/ping"))
        assertNull(VaultRedirect.movedHost(www, 301, "https://www.yourpitbox.com/notes/abc/api/ping"))
    }

    @Test
    fun `targetHost names where an unfollowed redirect points`() {
        assertEquals("evil.example", VaultRedirect.targetHost(www, "https://evil.example/x"))
        assertEquals("www.yourpitbox.com", VaultRedirect.targetHost(www, "/elsewhere"))
        assertNull(VaultRedirect.targetHost(www, null))
    }

    @Test
    fun `an endpoint on another host keeps the link`() {
        val endpoint = VaultUrl.endpointOrNull("https://www.yourpitbox.com/notes/abc")!!
        val moved = endpoint.withHost("yourpitbox.com")
        assertEquals("https://yourpitbox.com/notes/abc", moved.baseUrl)
        assertEquals(VaultUrl.endpointOrNull("yourpitbox.com/notes/abc"), moved)
        assertFalse(moved.fingerprint == endpoint.fingerprint)
    }

    // ------------------------------------------------------------------ the client against a site that moved

    private val vault = FakeVault()
    private val port get() = vault.server.port
    /** Requests that reached the old host and were redirected (never seen by the vault). */
    private val redirected = CopyOnWriteArrayList<RecordedRequest>()
    private val moves = CopyOnWriteArrayList<Pair<String, String>>()

    /** www.vault.test answers with [redirect]; vault.test is the vault. Both resolve to the local server. */
    private fun site(redirect: (RecordedRequest) -> MockResponse) {
        val inner = vault.server.dispatcher
        vault.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Host").orEmpty().startsWith("www.")) {
                    redirected += request
                    return redirect(request)
                }
                return inner.dispatch(request)
            }
        }
    }

    private fun movedPermanently(code: Int = 301, host: String = "vault.test") = { r: RecordedRequest ->
        MockResponse().setResponseCode(code).setHeader("Location", "http://$host:$port${r.path}")
    }

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.endsWith("vault.test")) InetAddress.getAllByName(vault.server.hostName).toList() else Dns.SYSTEM.lookup(hostname)
        })
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun endpoint(host: String) = VaultEndpoint.insecureForTests("http://$host:$port/notes/${vault.linkId}".toHttpUrl())

    private fun client(credentials: VaultCredentials = MemoryCredentials()) =
        VaultClient(http(), endpoint("www.vault.test"), credentials, onMoved = { from, to -> moves += from.base.host to to.base.host })

    @After
    fun tearDown() = vault.close()

    @Test
    fun `the client follows the move once, adopts the new host and reports it`() = runBlocking {
        site(movedPermanently())
        val client = client()

        assertEquals("Your Pit Box · Notes", client.ping())
        assertEquals("vault.test", client.endpoint.base.host)
        assertEquals(listOf("www.vault.test" to "vault.test"), moves)
        assertEquals("only the first request went to the old host", 1, redirected.size)

        client.listDocIds()
        client.putDoc("doc-1", VaultDocUpsert("t", "NOTEBOOK", 1, 2))
        assertEquals(1, redirected.size)
        assertEquals(1, moves.size)
        assertTrue(vault.requests.all { it.getHeader("Host")!!.startsWith("vault.test") })
        assertEquals(listOf("POST /api/session", "GET /api/ping", "GET /api/docs", "PUT /api/docs/doc-1"), vault.calls())
    }

    @Test
    fun `a request with a body arrives whole at the new host`() = runBlocking {
        site(movedPermanently(code = 308))
        val credentials = MemoryCredentials()
        client(credentials).login("correct horse")

        assertEquals("POST", redirected.single().method)
        val login = vault.requests.single()
        assertEquals("/api/session", vault.relative(login))
        assertEquals("correct horse", FakeVault.jsonOf(login)["password"].toString().trim('"'))

        val files = client(credentials.apply { stored = null })
        files.putBytes("doc-1", "page.pdf", ByteArray(3000) { it.toByte() }, "application/pdf")
        val put = vault.requests.last()
        assertEquals("/api/docs/doc-1/files/page.pdf", vault.relative(put))
        assertEquals(3000L, put.bodySize)
    }

    @Test
    fun `a redirect to another site is an error and sends nothing there`() = runBlocking {
        site { MockResponse().setResponseCode(301).setHeader("Location", "https://evil.example/notes/${vault.linkId}/api/session") }
        val client = client()
        try {
            client.login("correct horse")
            fail("expected Redirected")
        } catch (e: VaultException.Redirected) {
            assertEquals(301, e.code)
            assertEquals("evil.example", e.toHost)
            assertTrue(e.blocksVault)
            assertFalse(e.retryable)
            assertFalse("no link in the message", e.message!!.contains(vault.linkId))
        }
        assertEquals("www.vault.test", client.endpoint.base.host)
        assertTrue(moves.isEmpty())
        assertTrue(vault.requests.isEmpty())
    }

    @Test
    fun `a temporary move is not followed`() = runBlocking {
        site(movedPermanently(code = 302))
        try {
            client().login("correct horse")
            fail("expected Redirected")
        } catch (e: VaultException.Redirected) {
            assertEquals(302, e.code)
        }
        assertTrue(vault.requests.isEmpty())
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a second redirect is not followed and nothing is adopted`() = runBlocking {
        site(movedPermanently())
        vault.failNext += MockResponse().setResponseCode(301).setHeader("Location", "http://www.vault.test:$port/notes/${vault.linkId}/api/session")
        val client = client()
        try {
            client.login("correct horse")
            fail("expected Redirected")
        } catch (e: VaultException.Redirected) {
            assertEquals(301, e.code)
        }
        assertEquals(1, redirected.size)
        assertEquals("www.vault.test", client.endpoint.base.host)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a plain 404 at the new host is not adopted`() = runBlocking {
        site(movedPermanently())
        vault.failNext += MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("Not Found\n")
        val client = client()
        try {
            client.login("correct horse")
            fail("expected NotAVault")
        } catch (e: VaultException.NotAVault) {
        }
        assertEquals("www.vault.test", client.endpoint.base.host)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `the vault answering that a document is missing still counts as the vault`() = runBlocking {
        val credentials = MemoryCredentials()
        VaultClient(http(), endpoint("vault.test"), credentials).login("correct horse").also { credentials.stored = it }
        site(movedPermanently())
        val client = client(credentials)

        assertFalse(client.hasDoc("not-there"))
        assertEquals("vault.test", client.endpoint.base.host)
        assertEquals(1, moves.size)
    }

    @Test
    fun `the connection test reports the new address`() = runBlocking {
        site(movedPermanently())
        val r = VaultConnectionTest.run(http(), endpoint("www.vault.test"), "correct horse")
        val ok = r.getOrNull()
        assertNotNull(r.exceptionOrNull()?.message, ok)
        assertEquals("vault.test", ok!!.movedTo!!.base.host)
        assertTrue(ok.message, ok.message.contains("moved to vault.test"))
        assertFalse(ok.message.contains(vault.linkId))
    }

    @Test
    fun `the connection test explains a redirect it does not follow`() = runBlocking {
        site { MockResponse().setResponseCode(302).setHeader("Location", "https://login.example/") }
        val r = VaultConnectionTest.run(http(), endpoint("www.vault.test"), "correct horse")
        val message = r.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("redirects to login.example (HTTP 302)"))
        assertFalse(message.contains(vault.linkId))
    }

}
