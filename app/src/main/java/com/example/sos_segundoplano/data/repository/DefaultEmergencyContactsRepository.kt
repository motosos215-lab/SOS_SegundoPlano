package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.emergency.CreateEmergencyContactRequestDto
import com.example.sos_segundoplano.data.remote.emergency.EmergencyContactDto
import com.example.sos_segundoplano.data.remote.emergency.EmergencyContactPermissionsDto
import com.example.sos_segundoplano.data.remote.emergency.EmergencyContactsRemoteDataSource
import com.example.sos_segundoplano.data.remote.emergency.EmergencyContactsRemoteResult
import com.example.sos_segundoplano.data.remote.emergency.EmergencyInvitationDto
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.emergency.CreateEmergencyContact
import com.example.sos_segundoplano.domain.emergency.EmergencyContact
import com.example.sos_segundoplano.domain.emergency.EmergencyContactPermissions
import com.example.sos_segundoplano.domain.emergency.EmergencyContactsRepository
import com.example.sos_segundoplano.domain.emergency.EmergencyContactsResult
import com.example.sos_segundoplano.domain.emergency.EmergencyInvitation
import com.example.sos_segundoplano.domain.repository.AuthRepository

class DefaultEmergencyContactsRepository(
    private val authRepository: AuthRepository,
    private val remote: EmergencyContactsRemoteDataSource
) : EmergencyContactsRepository {
    override suspend fun create(request: CreateEmergencyContact): EmergencyContactsResult<EmergencyContact> =
        riderCall { authorization -> remote.create(authorization, request.toDto()) }.mapContact()

    override suspend fun invite(contactId: String): EmergencyContactsResult<EmergencyContact> =
        riderCall { authorization -> remote.invite(authorization, contactId) }.mapContact()

    override suspend fun getInvitation(code: String): EmergencyContactsResult<EmergencyInvitation> =
        monitorCall { authorization -> remote.invitation(authorization, code) }.mapInvitation()

    override suspend fun acceptInvitation(code: String): EmergencyContactsResult<EmergencyContact> =
        monitorCall { authorization -> remote.acceptInvitation(authorization, code) }.mapContact()

    private suspend fun <T> riderCall(call: suspend (String) -> EmergencyContactsRemoteResult<T>) = callFor(UserRole.Rider, call)
    private suspend fun <T> monitorCall(call: suspend (String) -> EmergencyContactsRemoteResult<T>) = callFor(UserRole.Monitor, call)

    private suspend fun <T> callFor(role: UserRole, call: suspend (String) -> EmergencyContactsRemoteResult<T>): EmergencyContactsRemoteResult<T> {
        val session = authRepository.observeSession().value
        val actualRole = when (session) {
            is SessionState.Authenticated -> session.user.role
            is SessionState.Refreshing -> session.user.role
            else -> null
        }
        if (actualRole != role) return EmergencyContactsRemoteResult.Failure(403, "forbidden", "role_not_allowed")
        val firstToken = (authRepository.ensureValidAccessToken() as? AuthResult.Success)?.value?.reveal()
            ?: return EmergencyContactsRemoteResult.Failure(401, null, "access_token_unavailable")
        val first = call("Bearer $firstToken")
        if (first !is EmergencyContactsRemoteResult.Failure || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshed = (authRepository.ensureValidAccessToken() as? AuthResult.Success)?.value?.reveal() ?: return first
        return call("Bearer $refreshed")
    }

    private fun CreateEmergencyContact.toDto() = CreateEmergencyContactRequestDto(fullName, relationship, phoneNumber, email, priority, permissions.toDto(), saveMode)
    private fun EmergencyContactPermissions.toDto() = EmergencyContactPermissionsDto(canViewRealTimeLocation, canReceiveCriticalAlerts, canViewIncidentHistory, canViewVitalSigns)
    private fun EmergencyContactPermissionsDto.toDomain() = EmergencyContactPermissions(canViewRealTimeLocation, canReceiveCriticalAlerts, canViewIncidentHistory, canViewVitalSigns)

    private fun EmergencyContactsRemoteResult<EmergencyContactDto>.mapContact(): EmergencyContactsResult<EmergencyContact> = when (this) {
        is EmergencyContactsRemoteResult.Success -> {
            val contact = data.toDomain()
            if (contact == null) {
                EmergencyContactsResult.Failure(200, null, "response_contract_incomplete")
            } else {
                EmergencyContactsResult.Success<EmergencyContact>(contact)
            }
        }
        is EmergencyContactsRemoteResult.Failure -> EmergencyContactsResult.Failure(statusCode, errorCode, message)
    }

    private fun EmergencyContactsRemoteResult<EmergencyInvitationDto>.mapInvitation(): EmergencyContactsResult<EmergencyInvitation> = when (this) {
        is EmergencyContactsRemoteResult.Success -> {
            val invitation = data.toDomain()
            if (invitation == null) {
                EmergencyContactsResult.Failure(200, null, "response_contract_incomplete")
            } else {
                EmergencyContactsResult.Success<EmergencyInvitation>(invitation)
            }
        }
        is EmergencyContactsRemoteResult.Failure -> EmergencyContactsResult.Failure(statusCode, errorCode, message)
    }

    private fun EmergencyContactDto.toDomain(): EmergencyContact? {
        val permissions = permissions ?: return null
        return if (listOf(id, userId, fullName, relationship, phoneNumber, email, invitationStatus, createdAtUtc, updatedAtUtc).any { it.isNullOrBlank() } || priority == null || isPrimary == null || isActive == null) null
        else EmergencyContact(id!!, userId!!, fullName!!, relationship!!, phoneNumber!!, email!!, priority!!, invitationStatus!!, linkingCode, linkingCodeExpiresAtUtc, linkedUserId, permissions.toDomain(), isPrimary!!, isActive!!, createdAtUtc!!, updatedAtUtc!!, invitedAtUtc, linkedAtUtc, revokedAtUtc)
    }

    private fun EmergencyInvitationDto.toDomain(): EmergencyInvitation? {
        val permissions = permissions ?: return null
        return if (listOf(driverFullName, contactFullName, expiresAtUtc, status).any { it.isNullOrBlank() }) null
        else EmergencyInvitation(driverFullName!!, contactFullName!!, permissions.toDomain(), expiresAtUtc!!, status!!)
    }
}
