package com.focuslock.data

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth

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
    const val LEGACY_REQUEST_CODE = 4815
    private var legacyCallback: ((Result<String>) -> Unit)? = null

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
                    if (e is GetCredentialCancellationException) {
                        // User dismissed the picker — a normal outcome, not a failure
                        // to fall back from. Falling back here made cancelling
                        // impossible: the user would just get a second picker.
                        Log.i(TAG, "Sign-in cancelled by user")
                        onResult(Result.failure(e))
                        return
                    }
                    // Some OEM Google Play Services builds return "No credentials
                    // available" before showing the modern account sheet. Fall back
                    // to the older, explicit picker so the user can recover their
                    // account instead of seeing an unexplained dead end.
                    Log.w(TAG, "Credential Manager failed; opening account picker", e)
                    startLegacyPicker(activity, webClientId, onResult)
                }
            }
        )
    }

    private fun startLegacyPicker(activity: Activity, webClientId: String, onResult: (Result<String>) -> Unit) {
        legacyCallback = onResult
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        activity.startActivityForResult(GoogleSignIn.getClient(activity, options).signInIntent, LEGACY_REQUEST_CODE)
    }

    fun handleActivityResult(requestCode: Int, data: Intent?): Boolean {
        if (requestCode != LEGACY_REQUEST_CODE) return false
        val callback = legacyCallback
        legacyCallback = null
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val token = account.idToken ?: error("Google did not return an ID token")
            callback?.invoke(Result.success(token))
        } catch (e: Exception) {
            Log.e(TAG, "Google account picker failed", e)
            callback?.invoke(Result.failure(e))
        }
        return true
    }

    /**
     * Signs out of Firebase, the Credential Manager session (so the next sign-in
     * doesn't silently reuse this account — clearing only the legacy
     * GoogleSignInClient state, as before, left Credential Manager's own session
     * intact), and the legacy picker's session. FocusLock's local rules stay on
     * this phone; a fresh anonymous Firebase session is created automatically the
     * next time cloud data is needed.
     */
    fun signOut(activity: Activity, webClientId: String, onComplete: (Result<Unit>) -> Unit) {
        try {
            FirebaseAuth.getInstance().signOut()
            val credentialManager = CredentialManager.create(activity)
            credentialManager.clearCredentialStateAsync(
                ClearCredentialStateRequest(),
                null,
                ContextCompat.getMainExecutor(activity),
                object : CredentialManagerCallback<Void?, ClearCredentialException> {
                    override fun onResult(response: Void?) {
                        signOutLegacy(activity, webClientId, onComplete)
                    }

                    override fun onError(e: ClearCredentialException) {
                        Log.w(TAG, "clearCredentialState failed; continuing sign-out", e)
                        signOutLegacy(activity, webClientId, onComplete)
                    }
                }
            )
        } catch (e: Exception) {
            onComplete(Result.failure(e))
        }
    }

    private fun signOutLegacy(activity: Activity, webClientId: String, onComplete: (Result<Unit>) -> Unit) {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(activity, options).signOut()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) onComplete(Result.success(Unit))
                else onComplete(Result.failure(task.exception ?: IllegalStateException("Google sign-out failed")))
            }
    }
}
