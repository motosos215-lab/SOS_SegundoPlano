package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.profile.ProfileRemoteDataSource
import com.example.sos_segundoplano.data.remote.profile.ProfileUserDto
import com.example.sos_segundoplano.domain.auth.AccessDenied
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.auth.InactiveAccount
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.RateLimited
import com.example.sos_segundoplano.domain.auth.ServerFailure
import com.example.sos_segundoplano.domain.auth.authenticatedIdentityOrNull
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.StorageFailure
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.auth.Unauthorized
import com.example.sos_segundoplano.domain.profile.ProfileAccessDenied
import com.example.sos_segundoplano.domain.profile.ProfileAccountUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileFailure
import com.example.sos_segundoplano.domain.profile.ProfileInactiveAccount
import com.example.sos_segundoplano.domain.profile.ProfileInvalidResponse
import com.example.sos_segundoplano.domain.profile.ProfileNetworkUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileRateLimited
import com.example.sos_segundoplano.domain.profile.ProfileResult
import com.example.sos_segundoplano.domain.profile.ProfileServerFailure
import com.example.sos_segundoplano.domain.profile.ProfileSessionExpired
import com.example.sos_segundoplano.domain.profile.ProfileStorageFailure
import com.example.sos_segundoplano.domain.profile.ProfileTimeout
import com.example.sos_segundoplano.domain.profile.ProfileUnauthorized
import com.example.sos_segundoplano.domain.profile.RiderProfile
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import java.time.DateTimeException
import java.time.OffsetDateTime

class DefaultProfileRepository(
    private val remoteDataSource: ProfileRemoteDataSource,
    private val authRepository: AuthRepository
) : ProfileRepository {
    override suspend fun loadProfile(): ProfileResult<RiderProfile> = try {
        val sessionIdentity = currentSessionIdentity() ?: return ProfileSessionExpired
        val firstToken = when (val token = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> token.value.reveal()
            is AuthFailure -> return token.toProfileFailure()
        }

        when (val first = remoteDataSource.me(firstToken.asBearer())) {
            is ProfileResult.Success -> first.value.toDomainOrFailure(sessionIdentity)
            ProfileUnauthorized -> loadAfterSingleRefresh(sessionIdentity)
            is ProfileFailure -> handleTerminalOrRecoverable(first, sessionIdentity)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    }

    private suspend fun loadAfterSingleRefresh(sessionIdentity: AuthSessionIdentity): ProfileResult<RiderProfile> {
        if (!isCurrentSession(sessionIdentity)) return ProfileSessionExpired
        when (val refreshed = authRepository.refreshSession()) {
            is AuthResult.Success -> Unit
            is AuthFailure -> return refreshed.toProfileFailure()
        }
        val refreshedToken = when (val token = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> token.value.reveal()
            is AuthFailure -> return token.toProfileFailure()
        }
        return when (val second = remoteDataSource.me(refreshedToken.asBearer())) {
            is ProfileResult.Success -> second.value.toDomainOrFailure(sessionIdentity)
            ProfileUnauthorized -> {
                logoutIfCurrentSession(sessionIdentity)
                ProfileUnauthorized
            }
            is ProfileFailure -> handleTerminalOrRecoverable(second, sessionIdentity)
        }
    }

    private suspend fun handleTerminalOrRecoverable(
        failure: ProfileFailure,
        sessionIdentity: AuthSessionIdentity
    ): ProfileFailure {
        if (failure == ProfileAccountUnavailable || failure == ProfileInactiveAccount || failure == ProfileAccessDenied) {
            logoutIfCurrentSession(sessionIdentity)
        }
        return failure
    }

    private suspend fun ProfileUserDto.toDomainOrFailure(sessionIdentity: AuthSessionIdentity): ProfileResult<RiderProfile> {
        val normalizedId = id?.trim().orEmpty()
        val normalizedEmail = email?.trim().orEmpty()
        val normalizedFullName = fullName?.trim().orEmpty()
        val apiRole = role.orEmpty()
        if (normalizedId.isEmpty() || normalizedEmail.isEmpty() || normalizedFullName.isEmpty()) {
            return ProfileInvalidResponse
        }
        if (isActive != true) {
            logoutIfCurrentSession(sessionIdentity)
            return ProfileInactiveAccount
        }
        if (apiRole != RIDER_ROLE) {
            return ProfileAccessDenied
        }
        val created = createdAtUtc.parseInstantOrNull() ?: return ProfileInvalidResponse
        val updated = updatedAtUtc.parseInstantOrNull() ?: return ProfileInvalidResponse
        val lastLogin = lastLoginAtUtc?.takeIf { it.isNotBlank() }?.parseInstantOrNull()
        if (!lastLoginAtUtc.isNullOrBlank() && lastLogin == null) return ProfileInvalidResponse
        return ProfileResult.Success(
            RiderProfile(
                id = normalizedId,
                email = normalizedEmail,
                fullName = normalizedFullName,
                phoneNumber = phoneNumber?.trim()?.takeIf { it.isNotEmpty() },
                role = apiRole,
                isActive = true,
                createdAtUtc = created,
                updatedAtUtc = updated,
                lastLoginAtUtc = lastLogin
            )
        )
    }

    private fun String?.parseInstantOrNull() = try {
        this?.let { OffsetDateTime.parse(it).toInstant() }
    } catch (_: DateTimeException) {
        null
    }

    private fun String.asBearer(): String = "Bearer $this"

    private fun currentSessionIdentity(): AuthSessionIdentity? =
        authRepository.observeSession().value.authenticatedIdentityOrNull()
            ?.takeIf { it.userId.isNotBlank() }

    private fun isCurrentSession(expected: AuthSessionIdentity): Boolean =
        currentSessionIdentity() == expected

    private suspend fun logoutIfCurrentSession(expected: AuthSessionIdentity) {
        if (isCurrentSession(expected)) {
            authRepository.logoutIfCurrent(expected)
        }
    }

    private fun AuthFailure.toProfileFailure(): ProfileFailure = when (this) {
        is Unauthorized -> ProfileUnauthorized
        SessionExpired -> ProfileSessionExpired
        is AccessDenied -> ProfileAccessDenied
        InactiveAccount -> ProfileInactiveAccount
        is RateLimited -> ProfileRateLimited
        is NetworkUnavailable -> ProfileNetworkUnavailable
        is Timeout -> ProfileTimeout
        is ServerFailure -> ProfileServerFailure
        is InvalidResponse -> ProfileInvalidResponse
        is StorageFailure -> ProfileStorageFailure
        else -> ProfileSessionExpired
    }

    private companion object {
        const val RIDER_ROLE = "Rider"
    }
}
