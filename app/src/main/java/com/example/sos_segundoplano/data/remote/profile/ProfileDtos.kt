package com.example.sos_segundoplano.data.remote.profile

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class ProfileDataDto(val user: ProfileUserDto?)

@JsonClass(generateAdapter = false)
data class ProfileUserDto(
    val id: String?,
    val email: String?,
    val fullName: String?,
    val phoneNumber: String?,
    val role: String?,
    val isActive: Boolean?,
    val createdAtUtc: String?,
    val updatedAtUtc: String?,
    val lastLoginAtUtc: String?
)
