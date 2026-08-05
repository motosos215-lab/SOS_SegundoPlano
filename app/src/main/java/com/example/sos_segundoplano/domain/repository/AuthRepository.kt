package com.example.sos_segundoplano.domain.repository

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser>
    suspend fun restoreSession(): AuthResult<AuthUser?>
    suspend fun ensureValidAccessToken(): AuthResult<AccessToken>
    suspend fun refreshSession(): AuthResult<AuthUser>
    suspend fun logout(): AuthResult<Unit>
    fun observeSession(): StateFlow<SessionState>
}
