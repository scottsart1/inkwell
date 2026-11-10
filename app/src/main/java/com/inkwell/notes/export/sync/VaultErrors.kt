package com.inkwell.notes.export.sync

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Everything that can go wrong talking to the vault, with a message fit to show the user.
 * Messages never contain the vault link, password, token or note content.
 */
sealed class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Worth trying again later without the user changing anything. */
    abstract val retryable: Boolean

    /** Blocks every vault upload until the user fixes settings (or the lock expires). */
    open val blocksVault: Boolean get() = false

    class MissingLink : VaultException("Add your vault link in Settings › Storage, sync & backup.") {
        override val retryable = false
        override val blocksVault = true
    }

    class MissingPassword : VaultException("Add the vault password in Settings › Storage, sync & backup.") {
        override val retryable = false
        override val blocksVault = true
    }

    class WrongPassword : VaultException("The vault password is wrong. Update it in Settings › Storage, sync & backup.") {
        override val retryable = false
        override val blocksVault = true
    }

    class Locked(val retryAfterSeconds: Long) : VaultException(
        "The vault is locked after too many password attempts. Try again in ${waitText(retryAfterSeconds)}.",
    ) {
        override val retryable = true
        override val blocksVault = true
    }

    class NotAVault : VaultException("This link doesn't lead to a notes vault (404). Check the vault link.") {
        override val retryable = false
        override val blocksVault = true
    }

    class NotConfigured : VaultException("The vault server isn't set up yet. Try again later.") {
        override val retryable = true
        override val blocksVault = true
    }

    class Unreachable(cause: Throwable?) : VaultException("Can't reach the vault. Check the internet connection; Inkwell will try again.", cause) {
        override val retryable = true
        override val blocksVault = true
    }

    class InsecureConnection(cause: Throwable?) : VaultException("A secure connection to the vault couldn't be made (certificate problem).", cause) {
        override val retryable = true
        override val blocksVault = true
    }

    /** Still 401 right after a fresh login. */
    class Unauthorized : VaultException("The vault refused the session. Test the connection in Settings.") {
        override val retryable = true
        override val blocksVault = true
    }

    /** A redirect other than the vault moving between `www.host` and `host` ([VaultRedirect]). */
    class Redirected(val code: Int, val toHost: String?) : VaultException(
        "The vault link now redirects to another address${toHost?.let { " ($it)" } ?: ""}. Open it in a browser and put the address it ends at in Settings › Storage, sync & backup.",
    ) {
        override val retryable = false
        override val blocksVault = true
    }

    class TooLarge(what: String) : VaultException("$what is larger than the vault accepts.") {
        override val retryable = false
    }

    /** 4xx other than the above: the request itself is wrong, retrying will not help. */
    class Rejected(val code: Int, detail: String?) : VaultException(
        "The vault rejected the upload (HTTP $code${detail?.let { ": " + it.take(160) } ?: ""}).",
    ) {
        override val retryable = false
    }

    class Server(val code: Int) : VaultException("The vault had a problem (HTTP $code). Inkwell will try again.") {
        override val retryable = true
    }

    class BadResponse : VaultException("The vault sent an unexpected answer. Check the vault link.") {
        override val retryable = true
    }

    companion object {
        fun waitText(seconds: Long): String {
            val s = seconds.coerceAtLeast(1)
            return when {
                s < 60 -> "$s seconds"
                s < 3600 -> ((s + 59) / 60).let { if (it == 1L) "1 minute" else "$it minutes" }
                else -> ((s + 3599) / 3600).let { if (it == 1L) "1 hour" else "$it hours" }
            }
        }

        /** Maps a transport failure. */
        fun fromIo(e: IOException): VaultException = when {
            e is SSLException || e.cause is CertificateException -> InsecureConnection(e)
            e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is InterruptedIOException -> Unreachable(e)
            else -> Unreachable(e)
        }
    }
}
