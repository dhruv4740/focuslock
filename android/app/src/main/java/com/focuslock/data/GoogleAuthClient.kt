package com.focuslock.data

import android.app.Activity
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Thin Android glue over the modern Credential Manager API (NOT the
 * deprecated GoogleSignInClient/play-services-auth flow). Obtains a Google ID
 * token; [FocusPolicyClient] is responsible for linking it onto the existing
 * Firebase anonymous user via linkWithCredential so pre-existing sync-code
 * data is preserved rather than lost.
 *
 * getCredential() shows system UI (an account picker / bottom sheet), so this
 * intentionally stays callback-shaped and runs on the main thread — unlike
 * the rest of the data layer's blocking Tasks.await() pattern, which is fine
 * for pure network calls but wrong for anything that has to paint UI.
 */
object GoogleAuthClient {

    private const val TAG = "GoogleAuthClient"

    /**
     * Launches the Google account picker and returns the ID token on success.
     * [webClientId] must be the Firebase-registered OAuth *web* client ID —
     * see the comment on R.string.google_oauth_web_client_id in strings.xml.
     */
    fun signIn(activity: Activity, webClientId: String, onResult: (Result<String>) -> Unit) {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val credentialManager = CredentialManager.create(activity)
        credentialManager.getCredentialAsync(
            activity,
            request,
            null,
            ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(response: GetCredentialResponse) {
                    try {
                        val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
                        onResult(Result.success(credential.idToken))
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "Could not parse Google ID token credential", e)
                        onResult(Result.failure(e))
                    }
                }

                override fun onError(e: GetCredentialException) {
                    Log.e(TAG, "Google sign-in failed", e)
                    onResult(Result.failure(e))
                }
            }
        )
    }
}
