package com.example.sos_segundoplano.domain.emergency

sealed interface EmergencyContactsResult<out T> {
    data class Success<T>(val value: T) : EmergencyContactsResult<T>
    data class Failure(val statusCode: Int?, val errorCode: String?, val message: String?) : EmergencyContactsResult<Nothing>
}

data class EmergencyContactPermissions(
    val canViewRealTimeLocation: Boolean,
    val canReceiveCriticalAlerts: Boolean,
    val canViewIncidentHistory: Boolean,
    val canViewVitalSigns: Boolean
)

data class EmergencyContact(
    val id: String,
    val userId: String,
    val fullName: String,
    val relationship: String,
    val phoneNumber: String,
    val email: String,
    val priority: Int,
    val invitationStatus: String,
    val linkingCode: String?,
    val linkingCodeExpiresAtUtc: String?,
    val linkedUserId: String?,
    val permissions: EmergencyContactPermissions,
    val isPrimary: Boolean,
    val isActive: Boolean,
    val createdAtUtc: String,
    val updatedAtUtc: String,
    val invitedAtUtc: String?,
    val linkedAtUtc: String?,
    val revokedAtUtc: String?
)

data class CreateEmergencyContact(
    val fullName: String,
    val relationship: String,
    val phoneNumber: String,
    val email: String,
    val priority: Int,
    val permissions: EmergencyContactPermissions,
    val saveMode: String
)

data class EmergencyInvitation(
    val driverFullName: String,
    val contactFullName: String,
    val permissions: EmergencyContactPermissions,
    val expiresAtUtc: String,
    val status: String
)

interface EmergencyContactsRepository {
    suspend fun list(): EmergencyContactsResult<List<EmergencyContact>>
    suspend fun create(request: CreateEmergencyContact): EmergencyContactsResult<EmergencyContact>
    suspend fun invite(contactId: String): EmergencyContactsResult<EmergencyContact>
    suspend fun getInvitation(code: String): EmergencyContactsResult<EmergencyInvitation>
    suspend fun acceptInvitation(code: String): EmergencyContactsResult<EmergencyContact>
}
