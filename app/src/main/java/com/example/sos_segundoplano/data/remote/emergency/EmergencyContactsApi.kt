package com.example.sos_segundoplano.data.remote.emergency

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface EmergencyContactsApi {
    @POST("api/v1/emergency-contacts")
    suspend fun create(
        @Header("Authorization") authorization: String,
        @Body request: CreateEmergencyContactRequestDto
    ): Response<ApiEnvelopeDto<EmergencyContactDataDto>>

    @POST("api/v1/emergency-contacts/{contactId}/invite")
    suspend fun invite(
        @Header("Authorization") authorization: String,
        @Path("contactId") contactId: String,
        @Body request: EmptyBodyDto = EmptyBodyDto()
    ): Response<ApiEnvelopeDto<EmergencyContactDataDto>>

    @GET("api/v1/emergency-contacts/invitations/{code}")
    suspend fun invitation(
        @Header("Authorization") authorization: String,
        @Path("code") code: String
    ): Response<ApiEnvelopeDto<EmergencyInvitationDataDto>>

    @POST("api/v1/emergency-contacts/invitations/{code}/accept")
    suspend fun acceptInvitation(
        @Header("Authorization") authorization: String,
        @Path("code") code: String,
        @Body request: EmptyBodyDto = EmptyBodyDto()
    ): Response<ApiEnvelopeDto<EmergencyContactDataDto>>
}

class EmptyBodyDto

data class CreateEmergencyContactRequestDto(
    val fullName: String,
    val relationship: String,
    val phoneNumber: String,
    val email: String,
    val priority: Int,
    val permissions: EmergencyContactPermissionsDto,
    val saveMode: String
)

data class EmergencyContactPermissionsDto(
    val canViewRealTimeLocation: Boolean,
    val canReceiveCriticalAlerts: Boolean,
    val canViewIncidentHistory: Boolean,
    val canViewVitalSigns: Boolean
)

data class EmergencyContactDataDto(val contact: EmergencyContactDto? = null)

data class EmergencyContactDto(
    val id: String? = null,
    val userId: String? = null,
    val fullName: String? = null,
    val relationship: String? = null,
    val phoneNumber: String? = null,
    val email: String? = null,
    val priority: Int? = null,
    val invitationStatus: String? = null,
    val linkingCode: String? = null,
    val linkingCodeExpiresAtUtc: String? = null,
    val linkedUserId: String? = null,
    val permissions: EmergencyContactPermissionsDto? = null,
    val isPrimary: Boolean? = null,
    val isActive: Boolean? = null,
    val createdAtUtc: String? = null,
    val updatedAtUtc: String? = null,
    val invitedAtUtc: String? = null,
    val linkedAtUtc: String? = null,
    val revokedAtUtc: String? = null
)

data class EmergencyInvitationDataDto(val invitation: EmergencyInvitationDto? = null)

data class EmergencyInvitationDto(
    val driverFullName: String? = null,
    val contactFullName: String? = null,
    val permissions: EmergencyContactPermissionsDto? = null,
    val expiresAtUtc: String? = null,
    val status: String? = null
)
