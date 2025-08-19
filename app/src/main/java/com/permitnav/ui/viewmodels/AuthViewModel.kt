package com.permitnav.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthViewModel : ViewModel() {
    
    private val auth = FirebaseAuth.getInstance()
    private var googleSignInClient: GoogleSignInClient? = null
    
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    
    companion object {
        private const val TAG = "AuthViewModel"
    }
    
    /**
     * Sign in with email and password
     */
    fun signIn(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                val result = auth.signInWithEmailAndPassword(email, password).await()
                Log.d(TAG, "✅ Sign in successful: ${result.user?.email}")
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    user = result.user,
                    isAuthenticated = true
                )
                onSuccess()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Sign in failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = getAuthErrorMessage(e)
                )
            }
        }
    }
    
    /**
     * Sign up with email and password
     */
    fun signUp(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                Log.d(TAG, "✅ Sign up successful: ${result.user?.email}")
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    user = result.user,
                    isAuthenticated = true
                )
                onSuccess()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Sign up failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = getAuthErrorMessage(e)
                )
            }
        }
    }
    
    /**
     * Initialize Google Sign-In
     */
    fun signInWithGoogle(context: Context, onIntentReady: (Intent) -> Unit) {
        try {
            // Configure Google Sign-In
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(context.getString(com.permitnav.R.string.default_web_client_id))
                .requestEmail()
                .build()
            
            googleSignInClient = GoogleSignIn.getClient(context, gso)
            
            val signInIntent = googleSignInClient?.signInIntent
            if (signInIntent != null) {
                onIntentReady(signInIntent)
            } else {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to initialize Google Sign-In"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Google Sign-In initialization failed", e)
            _uiState.value = _uiState.value.copy(
                error = "Google Sign-In setup failed"
            )
        }
    }
    
    /**
     * Handle Google Sign-In result
     */
    fun handleGoogleSignInResult(data: Intent?, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                
                Log.d(TAG, "🔍 Google Sign-In account: ${account.email}")
                firebaseAuthWithGoogle(account, onSuccess)
                
            } catch (e: ApiException) {
                Log.e(TAG, "❌ Google Sign-In failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Google Sign-In failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Authenticate with Firebase using Google credentials
     */
    private suspend fun firebaseAuthWithGoogle(account: GoogleSignInAccount, onSuccess: () -> Unit) {
        try {
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            val result = auth.signInWithCredential(credential).await()
            
            Log.d(TAG, "✅ Firebase auth with Google successful: ${result.user?.email}")
            
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                user = result.user,
                isAuthenticated = true
            )
            onSuccess()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firebase auth with Google failed", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Authentication failed: ${e.message}"
            )
        }
    }
    
    /**
     * Sign out user
     */
    fun signOut() {
        viewModelScope.launch {
            try {
                auth.signOut()
                googleSignInClient?.signOut()
                
                _uiState.value = AuthUiState() // Reset to initial state
                Log.d(TAG, "✅ User signed out")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Sign out failed", e)
            }
        }
    }
    
    /**
     * Check if user is currently authenticated
     */
    fun checkAuthState(): Boolean {
        val currentUser = auth.currentUser
        val isAuthenticated = currentUser != null
        
        _uiState.value = _uiState.value.copy(
            user = currentUser,
            isAuthenticated = isAuthenticated
        )
        
        Log.d(TAG, "🔍 Auth state check: ${if (isAuthenticated) "authenticated" else "not authenticated"}")
        return isAuthenticated
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Get user-friendly error messages
     */
    private fun getAuthErrorMessage(exception: Exception): String {
        return when (exception.message) {
            "The email address is badly formatted." -> "Please enter a valid email address"
            "The password is invalid or the user does not have a password." -> "Invalid email or password"
            "There is no user record corresponding to this identifier. The user may have been deleted." -> "No account found with this email"
            "The email address is already in use by another account." -> "An account with this email already exists"
            "The password is invalid or the user does not have a password." -> "Invalid password"
            "A network error (such as timeout, interrupted connection or unreachable host) has occurred." -> "Network error. Please check your connection"
            else -> exception.message ?: "Authentication failed"
        }
    }
}

data class AuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val user: com.google.firebase.auth.FirebaseUser? = null,
    val error: String? = null
)