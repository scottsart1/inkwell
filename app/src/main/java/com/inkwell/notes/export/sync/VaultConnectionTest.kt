package com.inkwell.notes.export.sync

import okhttp3.OkHttpClient

/**
 * "Test connection" of the notes vault: log in with the given password, then ping with the new
 * token. Failures come back as [SyncCheckFailure] with a message ready to show.
 */
object VaultConnectionTest {
    /** [movedTo]: the vault answered from another host of its site, which the link should now use. */
    data class Success(val message: String, val session: VaultSession, val movedTo: VaultEndpoint? = null)

    suspend fun run(http: OkHttpClient, endpoint: VaultEndpoint, password: String, clock: () -> Long = System::currentTimeMillis): Result<Success> {
        if (password.isEmpty()) return Result.failure(SyncCheckFailure("Enter the vault password."))
        val credentials = object : VaultCredentials {
            @Volatile var current: VaultSession? = null
            override fun password(): String = password
            override fun session(): VaultSession? = current
            override fun saveSession(session: VaultSession?) {
                current = session
            }
        }
        val client = VaultClient(http, endpoint, credentials, clock)
        return try {
            val session = client.login(password)
            credentials.current = session
            val name = client.ping().ifBlank { DEFAULT_NAME }
            val moved = client.endpoint.takeIf { it != endpoint }
            val message = if (moved == null) "Connected to $name" else "Connected to $name. The vault moved to ${moved.base.host}; the link now uses it."
            Result.success(Success(message, credentials.current ?: session, moved))
        } catch (e: VaultException) {
            Result.failure(SyncCheckFailure(message(e, endpoint), e))
        }
    }

    fun message(e: VaultException, endpoint: VaultEndpoint): String = when (e) {
        is VaultException.WrongPassword -> "Wrong password."
        is VaultException.MissingPassword -> "Enter the vault password."
        is VaultException.Locked -> "Locked after too many attempts. Try again in ${VaultException.waitText(e.retryAfterSeconds)}."
        is VaultException.NotAVault -> "Not a vault link: ${endpoint.base.host} answered 404. Check the private link."
        is VaultException.Unreachable -> "Can't reach ${endpoint.base.host}. Check the internet connection."
        is VaultException.InsecureConnection -> "Couldn't make a secure connection to ${endpoint.base.host}."
        is VaultException.NotConfigured -> "The vault on ${endpoint.base.host} isn't set up yet."
        is VaultException.Server -> "The vault had a problem (HTTP ${e.code}). Try again later."
        is VaultException.BadResponse -> "${endpoint.base.host} didn't answer like a notes vault. Check the link."
        is VaultException.Unauthorized -> "The vault accepted the password but refused the session. Try again."
        is VaultException.Redirected -> "${endpoint.base.host} redirects to ${e.toHost ?: "another address"} (HTTP ${e.code}). Open the link in a browser and use the address it ends at."
        else -> e.message ?: "The connection test failed."
    }

    const val DEFAULT_NAME = "Your Pit Box · Notes"
}
