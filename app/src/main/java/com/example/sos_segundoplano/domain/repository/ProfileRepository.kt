package com.example.sos_segundoplano.domain.repository

import com.example.sos_segundoplano.domain.profile.ProfileResult
import com.example.sos_segundoplano.domain.profile.RiderProfile

interface ProfileRepository {
    suspend fun loadProfile(): ProfileResult<RiderProfile>
}
