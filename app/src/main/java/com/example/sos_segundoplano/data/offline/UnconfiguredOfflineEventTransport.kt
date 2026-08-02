package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.OfflineEventTransport
import com.example.sos_segundoplano.domain.offline.OfflineEventTransportResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueItem
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload

class UnconfiguredOfflineEventTransport : OfflineEventTransport {
    override suspend fun send(item: OfflineQueueItem, payload: OfflineSyncPayload): OfflineEventTransportResult =
        OfflineEventTransportResult.NotConfigured()
}
