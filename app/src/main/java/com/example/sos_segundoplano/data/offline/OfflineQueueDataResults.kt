package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.ClaimedOfflineQueueItem
import com.example.sos_segundoplano.domain.offline.OfflineQueueItem

sealed interface BundleInsertResult {
    data class Persisted(val incidentQueueItemId: Long, val requestQueueItemId: Long) : BundleInsertResult
}

class IncompleteOfflineBundleException : IllegalStateException("offline_bundle_incomplete")

sealed interface ScheduleResult {
    data object Scheduled : ScheduleResult
    data object Deferred : ScheduleResult
    data object Unavailable : ScheduleResult
}

sealed interface OfflineQueueEntityMappingResult {
    data class Success(val item: OfflineQueueItem) : OfflineQueueEntityMappingResult
    data object UnknownEventType : OfflineQueueEntityMappingResult
    data object UnknownStatus : OfflineQueueEntityMappingResult
    data object UnknownErrorCategory : OfflineQueueEntityMappingResult
}

sealed interface ClaimedOfflineQueueMappingResult {
    data class Success(val item: ClaimedOfflineQueueItem) : ClaimedOfflineQueueMappingResult
    data object InvalidClaim : ClaimedOfflineQueueMappingResult
    data class InvalidItem(val reason: OfflineQueueEntityMappingResult) : ClaimedOfflineQueueMappingResult
}
