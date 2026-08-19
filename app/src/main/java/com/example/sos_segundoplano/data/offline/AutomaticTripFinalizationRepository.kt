package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult
import com.example.sos_segundoplano.domain.offline.ClaimedAutomaticTripFinalization

interface AutomaticTripFinalizationRepository {
    suspend fun claimNextAutomaticTripFinalization(workerId: String, now: Long): AutomaticTripFinalizationClaimResult
    suspend fun claimAutomaticTripFinalization(bundleKey: String, workerId: String, now: Long): AutomaticTripFinalizationClaimResult
    suspend fun completeAutomaticTripFinalization(claim: ClaimedAutomaticTripFinalization, now: Long): Boolean
    suspend fun releaseAutomaticTripFinalization(claim: ClaimedAutomaticTripFinalization, permanent: Boolean, now: Long): Boolean
}
