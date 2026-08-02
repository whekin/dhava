package com.nakvali.app

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class FirebaseAccount(
    val uid: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String,
    val emailVerified: Boolean,
)

internal data class BackendProfile(
    val id: String,
    val displayName: String,
    val email: String,
)

internal class SignInCancelled : Exception()

internal interface AuthGateway {
    fun currentAccount(): FirebaseAccount?
    suspend fun signIn(activity: Activity): FirebaseAccount
    suspend fun signOut(context: Context)
    suspend fun syncProfile(forceTokenRefresh: Boolean = false): BackendProfile
}

internal class FirebaseAuthGateway(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : AuthGateway {
    override fun currentAccount(): FirebaseAccount? = auth.currentUser?.toAccount()

    override suspend fun signIn(activity: Activity): FirebaseAccount {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(activity.getString(R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (_: GetCredentialCancellationException) {
            throw SignInCancelled()
        }
        val custom = credential as? CustomCredential
            ?: throw IllegalStateException("Google returned an unsupported credential")
        if (custom.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("Google returned an unsupported credential type")
        }
        val googleCredential = GoogleIdTokenCredential.createFrom(custom.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        val user = auth.signInWithCredential(firebaseCredential).await().user
            ?: throw IllegalStateException("Firebase sign-in returned no user")
        return user.toAccount()
    }

    override suspend fun signOut(context: Context) {
        auth.signOut()
        runCatching {
            CredentialManager.create(context)
                .clearCredentialState(ClearCredentialStateRequest())
        }
    }

    override suspend fun syncProfile(forceTokenRefresh: Boolean): BackendProfile = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: throw IllegalStateException("No signed-in Firebase user")
        val idToken = user.getIdToken(forceTokenRefresh).await().token
            ?: throw IllegalStateException("Firebase returned no ID token")
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL.trimEnd('/')}/api/v1/me")
            .header("Authorization", "Bearer $idToken")
            .apply {
                if (BuildConfig.API_ACCESS_KEY.isNotBlank()) {
                    header("X-Nakvali-Access-Key", BuildConfig.API_ACCESS_KEY)
                }
            }
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ProfileSyncException(response.code)
            }
            val json = org.json.JSONObject(body)
            BackendProfile(
                id = json.getString("id"),
                displayName = json.optString("display_name"),
                email = json.optString("email"),
            )
        }
    }
}

internal class ProfileSyncException(val statusCode: Int) : IOException()

private fun FirebaseUser.toAccount(): FirebaseAccount = FirebaseAccount(
    uid = uid,
    displayName = displayName.orEmpty(),
    email = email.orEmpty(),
    avatarUrl = photoUrl?.toString().orEmpty(),
    emailVerified = isEmailVerified,
)
