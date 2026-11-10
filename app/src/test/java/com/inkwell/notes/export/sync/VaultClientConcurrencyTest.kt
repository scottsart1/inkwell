package com.inkwell.notes.export.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several uploads in flight when the vault invalidates the session: one re-login, not one per request. */
class VaultClientConcurrencyTest {
    private val vault = FakeVault()

    @After
    fun tearDown() = vault.close()

    @Test
    fun `parallel 401s share a single re-login`() = runBlocking {
        val creds = MemoryCredentials()
        val client = vault.client(creds)
        client.ping()
        vault.validTokens.clear() // password rotated server side: every cached token is now refused

        val results = (1..6).map { i -> async { client.putDoc("doc-$i", VaultDocUpsert("t", "NOTEBOOK", 1, 2)) } }.awaitAll()
        assertEquals(6, results.size)

        val logins = vault.calls().count { it == "POST /api/session" }
        assertEquals("initial login + one refresh", 2, logins)
        assertEquals(vault.issuedTokens.last(), creds.stored?.token)
        // Every doc landed, each exactly once with the fresh token after its retry.
        assertEquals((1..6).map { "doc-$it" }.toSet(), vault.docIds.toSet())
        val retried = vault.requests.filter { it.method == "PUT" && it.getHeader("Authorization") == "Bearer ${vault.issuedTokens.last()}" }
        assertEquals(6, retried.size)
        assertTrue(vault.requests.none { it.method == "DELETE" })
    }

    @Test
    fun `a cached session from another client instance is reused instead of logging in again`() = runBlocking {
        val creds = MemoryCredentials()
        vault.client(creds).ping()
        val second = vault.client(creds)
        second.listDocIds()
        assertEquals(listOf("POST /api/session", "GET /api/ping", "GET /api/docs"), vault.calls())
    }
}
