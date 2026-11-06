package com.inkwell.notes.export.sync

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Drive access through Google Play services (AuthorizationClient). Inkwell asks only for
 * the `drive.file` scope: it can see and change the files it created, nothing else in Drive.
 * Play services keeps the grant and hands out short-lived access tokens; Inkwell stores no
 * password, refresh token or access token.
 *
 * Google only answers apps it knows: a Google Cloud project needs an Android OAuth client with
 * Inkwell's package name and signing certificate, and the Drive API switched on. Without that,
 * every request fails with DEVELOPER_ERROR, reported as [DriveException.NotSetUp].
 */
class PlayServicesDriveAuth(
    context: Context,
    /** The connected account (email), or null when Drive is not connected. */
    private val account: () -> String?,
) : DriveTokens {
    private val app = context.applicationContext
    @Volatile private var last: String? = null

    override suspend fun accessToken(refresh: Boolean): String {
        val email = account()?.takeIf { it.isNotBlank() } ?: throw DriveException.NotConnected()
        val client = Identity.getAuthorizationClient(app)
        if (refresh) {
            last?.let { stale -> runCatching { client.clearToken(ClearTokenRequest.builder().setToken(stale).build()).awaitTask() } }
            last = null
        }
        val result = try {
            client.authorize(request(email)).awaitTask()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw map(e)
        } catch (e: Exception) {
            throw DriveException.SignInFailed(null)
        }
        // A consent screen is needed: only the user can do that, from Settings.
        if (result.hasResolution()) throw DriveException.NeedsSignIn()
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: throw DriveException.NeedsSignIn()
        last = token
        return token
    }

    companion object {
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        /** The request for Drive access; [email] null lets the user pick an account. */
        fun request(email: String?): AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .apply { if (email != null) setAccount(Account(email, "com.google")) }
            .build()

        /** Starts authorization from the settings screen; [email] asks for that account again. */
        suspend fun begin(context: Context, email: String? = null): Begin {
            val result = try {
                Identity.getAuthorizationClient(context).authorize(request(email)).awaitTask()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                throw map(e)
            } catch (e: Exception) {
                throw DriveException.SignInFailed(null)
            }
            return toBegin(result)
        }

        /** The answer of the consent screen launched for [Begin.NeedsConsent]. */
        fun finish(context: Context, data: Intent?): String {
            if (data == null) throw DriveException.Cancelled()
            val result = try {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            } catch (e: ApiException) {
                throw map(e)
            } catch (e: Exception) {
                throw DriveException.SignInFailed(null)
            }
            return result.accessToken?.takeIf { it.isNotBlank() } ?: throw DriveException.NeedsSignIn()
        }

        /** Withdraws Inkwell's Drive access for [email] (files already in Drive stay). */
        suspend fun revoke(context: Context, email: String) {
            val request = RevokeAccessRequest.builder()
                .setAccount(Account(email, "com.google"))
                .setScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
                .build()
            Identity.getAuthorizationClient(context).revokeAccess(request).awaitTask()
        }

        private fun toBegin(result: AuthorizationResult): Begin {
            val pending = result.pendingIntent
            if (result.hasResolution() && pending != null) return Begin.NeedsConsent(pending)
            val token = result.accessToken?.takeIf { it.isNotBlank() } ?: throw DriveException.NeedsSignIn()
            return Begin.Granted(token)
        }

        fun map(e: ApiException): DriveException = when (e.statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR -> DriveException.NotSetUp()
            CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERRUPTED -> DriveException.Unreachable(e)
            CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.RESOLUTION_REQUIRED -> DriveException.NeedsSignIn()
            CommonStatusCodes.CANCELED -> DriveException.Cancelled()
            CommonStatusCodes.API_NOT_CONNECTED -> DriveException.NotSetUp(
                "Google Play services is missing or out of date on this tablet. Update it from the Play Store and try again.",
            )
            else -> DriveException.SignInFailed(e.statusCode)
        }
    }

    sealed class Begin {
        /** Show Google's account and consent screen. */
        class NeedsConsent(val intent: PendingIntent) : Begin()
        class Granted(val token: String) : Begin()
    }
}

/** Suspends on a Play services task. */
internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener { cont.cancel() }
}
