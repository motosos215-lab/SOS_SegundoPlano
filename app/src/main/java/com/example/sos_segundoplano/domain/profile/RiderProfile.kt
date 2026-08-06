package com.example.sos_segundoplano.domain.profile

import java.time.Instant

data class RiderProfile(
    val id: String,
    val email: String,
    val fullName: String,
    val phoneNumber: String?,
    val role: String,
    val isActive: Boolean,
    val createdAtUtc: Instant,
    val updatedAtUtc: Instant,
    val lastLoginAtUtc: Instant?
)
