package com.inkwell.notes.export.sync

import java.io.IOException

/**
 * Everything that can go wrong copying to Google Drive, with a message fit to show the user.
 * Messages never contain tokens, file contents or note text.
 */
sealed class DriveException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Worth trying again later without the user changing anything. */
    abstract val retryable: Boolean

    /** Stops every Drive upload of this run (account, setup or connection problem). */
    open val blocksDrive: Boolean get() = false

    /** No Google account is connected in Settings. */
    class NotConnected : DriveException("Connect Google Drive in Settings › Storage, sync & backup.") {
        override val retryable = false
        override val blocksDrive = true
    }

    /** Google needs the user to approve access again (revoked, password changed, consent expired). */
    class NeedsSignIn : DriveException("Google Drive needs you to sign in again. Open Settings › Storage, sync & backup and tap Connect.") {
        override val retryable = false
        override val blocksDrive = true
    }

    /**
     * Google doesn't know this app yet (no Android OAuth client with Inkwell's package name and
     * signing certificate, or the Drive API is switched off in that Google Cloud project).
     */
    class NotSetUp(detail: String? = null) : DriveException(
        detail ?: "Google Drive isn't set up for Inkwell yet. Follow the one-time setup in Settings › Storage, sync & backup.",
    ) {
        override val retryable = false
        override val blocksDrive = true
    }

    /** Google Play services couldn't hand out a token for another reason (status [code]). */
    class SignInFailed(val code: Int?) : DriveException(
        "Google sign-in failed${code?.let { " (code $it)" } ?: ""}. Inkwell will try again; if it keeps failing, connect Google Drive again in Settings.",
    ) {
        override val retryable = true
        override val blocksDrive = true
    }

    /** The user closed Google's account or consent screen. */
    class Cancelled : DriveException("Google Drive wasn't connected.") {
        override val retryable = false
        override val blocksDrive = true
    }

    class Unreachable(cause: Throwable?) : DriveException("Can't reach Google Drive. Check the internet connection; Inkwell will try again.", cause) {
        override val retryable = true
        override val blocksDrive = true
    }

    /** Still refused (401) with a freshly issued token. */
    class Unauthorized : DriveException("Google Drive refused access. Open Settings › Storage, sync & backup and tap Connect again.") {
        override val retryable = false
        override val blocksDrive = true
    }

    class Full : DriveException("Your Google Drive is full. Free up space or buy more storage; Inkwell will try again.") {
        override val retryable = true
        override val blocksDrive = true
    }

    /** Google asked Inkwell to slow down (403/429 rate limits). */
    class RateLimited : DriveException("Google Drive asked Inkwell to slow down. Inkwell will try again shortly.") {
        override val retryable = true
        override val blocksDrive = true
    }

    /** The file or folder an id pointed to is gone (deleted in Drive). */
    class NotFound : DriveException("A file in Google Drive was removed while Inkwell was copying. Inkwell will copy it again.") {
        override val retryable = true
    }

    class Server(val code: Int) : DriveException("Google Drive had a problem (HTTP $code). Inkwell will try again.") {
        override val retryable = true
    }

    /** 4xx other than the above: the request itself is wrong, retrying will not help. */
    class Rejected(val code: Int, val reason: String?) : DriveException(
        "Google Drive rejected the upload (HTTP $code${reason?.let { ": " + it.take(80) } ?: ""}).",
    ) {
        override val retryable = false
    }

    class BadResponse : DriveException("Google Drive sent an unexpected answer. Inkwell will try again.") {
        override val retryable = true
    }

    companion object {
        fun fromIo(e: IOException): DriveException = Unreachable(e)
    }
}

/** A failed connection or folder check; [message] is ready to show. */
class SyncCheckFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
