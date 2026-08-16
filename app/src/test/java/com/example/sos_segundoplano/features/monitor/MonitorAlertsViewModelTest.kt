package com.example.sos_segundoplano.features.monitor

import com.example.sos_segundoplano.domain.monitor.MonitorAlertDetail
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsResult
import com.example.sos_segundoplano.domain.monitor.MonitorAlertStatus
import com.example.sos_segundoplano.domain.monitor.NotificationDeliveryAttemptId
import com.example.sos_segundoplano.domain.push.MonitorPushPayload
import com.example.sos_segundoplano.domain.push.PendingMonitorAlert
import com.example.sos_segundoplano.domain.push.PendingMonitorAlertCoordinator
import com.example.sos_segundoplano.domain.push.PendingMonitorAlertStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorAlertsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun noPendingAlertShowsReady() = runTest {
        val viewModel = MonitorAlertsViewModel(FakeRepository(), PendingMonitorAlertCoordinator(FakeStore()))
        assertEquals(MonitorAlertsUiState.Ready, viewModel.state.value)
    }

    @Test fun pendingAlertLoadsDetailAndMarksViewedOnce() = runTest {
        val repository = FakeRepository()
        val viewModel = MonitorAlertsViewModel(repository, PendingMonitorAlertCoordinator(FakeStore("attempt-1")))
        assertTrue(viewModel.state.value is MonitorAlertsUiState.Alert)
        assertEquals(listOf("attempt-1"), repository.detailIds)
        assertEquals(listOf("attempt-1"), repository.viewIds)
        viewModel.retry()
        assertEquals(1, repository.viewIds.size)
    }

    @Test fun acknowledgeAndDeclineUseThePendingAttemptIdAndPreventSecondAction() = runTest {
        val repository = FakeRepository()
        val viewModel = MonitorAlertsViewModel(repository, PendingMonitorAlertCoordinator(FakeStore("attempt-1")))
        viewModel.acknowledge("Puedo apoyar")
        viewModel.acknowledge("Duplicado")
        assertEquals(listOf("attempt-1"), repository.acknowledgeIds)
        assertTrue(repository.declineIds.isEmpty())
    }

    @Test fun riderGateNeverCallsMonitorApi() = runTest {
        val repository = FakeRepository()
        MonitorAlertsViewModel(repository, PendingMonitorAlertCoordinator(FakeStore("attempt-1")), isMonitorSession = { false })
        assertTrue(repository.detailIds.isEmpty())
    }

    @Test fun refreshKeepsDetailAndDoesNotMarkViewedAgain() = runTest {
        val repository = FakeRepository()
        val viewModel = MonitorAlertsViewModel(repository, PendingMonitorAlertCoordinator(FakeStore("attempt-1")))
        viewModel.refresh()
        assertEquals(2, repository.detailIds.size)
        assertEquals(1, repository.viewIds.size)
    }

    @Test fun newFcmAttemptIsConsumedOnceAndDifferentAttemptCreatesNewRefreshKey() = runTest {
        val repository = FakeRepository()
        val coordinator = PendingMonitorAlertCoordinator(FakeStore())
        val viewModel = MonitorAlertsViewModel(repository, coordinator)

        coordinator.record(payload("attempt-1"))
        coordinator.record(payload("attempt-1"))
        assertEquals(listOf("attempt-1"), repository.detailIds)
        assertEquals("attempt-1", viewModel.consumedFcmAttemptId.value)

        coordinator.record(payload("attempt-2"))
        assertEquals(listOf("attempt-1", "attempt-2"), repository.detailIds)
        assertEquals("attempt-2", viewModel.consumedFcmAttemptId.value)
    }

    @Test fun successfulAcknowledgeAndDeclineAdvanceRefreshRevisionOnlyOnSuccess() = runTest {
        val acknowledgeViewModel = MonitorAlertsViewModel(FakeRepository(), PendingMonitorAlertCoordinator(FakeStore("attempt-1")))
        acknowledgeViewModel.acknowledge("Puedo apoyar")
        assertEquals(1L, acknowledgeViewModel.successfulActionRevision.value)

        val declineViewModel = MonitorAlertsViewModel(FakeRepository(), PendingMonitorAlertCoordinator(FakeStore("attempt-2")))
        declineViewModel.decline("No puedo apoyar")
        assertEquals(1L, declineViewModel.successfulActionRevision.value)
    }

    @Test fun failedAcknowledgeAndDeclineDoNotAdvanceRefreshRevision() = runTest {
        val acknowledgeRepository = FakeRepository().apply {
            acknowledgeResult = MonitorAlertsResult.Failure(statusCode = null, errorCode = null, message = "No disponible")
        }
        val acknowledgeViewModel = MonitorAlertsViewModel(acknowledgeRepository, PendingMonitorAlertCoordinator(FakeStore("attempt-1")))
        acknowledgeViewModel.acknowledge("Puedo apoyar")
        assertEquals(0L, acknowledgeViewModel.successfulActionRevision.value)

        val declineRepository = FakeRepository().apply {
            declineResult = MonitorAlertsResult.Failure(statusCode = null, errorCode = null, message = "No disponible")
        }
        val declineViewModel = MonitorAlertsViewModel(declineRepository, PendingMonitorAlertCoordinator(FakeStore("attempt-2")))
        declineViewModel.decline("No puedo apoyar")
        assertEquals(0L, declineViewModel.successfulActionRevision.value)
        assertEquals(
            MonitorAlertNotice.NonBlockingError("No pudimos registrar la respuesta. Intenta nuevamente."),
            (declineViewModel.state.value as MonitorAlertsUiState.Alert).notice
        )
    }

    private class FakeStore(id: String? = null) : PendingMonitorAlertStore {
        private var pending = id?.let { PendingMonitorAlert(it, null, null, 1L) }
        override fun read() = pending
        override fun save(alert: PendingMonitorAlert): Boolean { pending = alert; return true }
        override fun clear(): Boolean { pending = null; return true }
    }
    private fun payload(attemptId: String) = MonitorPushPayload(
        notificationDeliveryAttemptId = attemptId,
        alertDispatchId = "dispatch-$attemptId",
        incidentId = "incident-$attemptId",
        channel = "Push",
        title = null,
        body = null
    )
    private class FakeRepository : MonitorAlertsRepository {
        val detailIds = mutableListOf<String>(); val viewIds = mutableListOf<String>(); val acknowledgeIds = mutableListOf<String>(); val declineIds = mutableListOf<String>()
        var acknowledgeResult: MonitorAlertsResult<MonitorAlertDetail> = MonitorAlertsResult.Success(MonitorAlertDetail(null))
        var declineResult: MonitorAlertsResult<MonitorAlertDetail> = MonitorAlertsResult.Success(MonitorAlertDetail(null))
        override suspend fun listAlerts(): MonitorAlertsResult<List<MonitorAlertAcknowledgement>> = MonitorAlertsResult.Success(emptyList())
        override suspend fun getAlerts() = error("unused")
        override suspend fun getAlert(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertDetail> { detailIds += id.value; return MonitorAlertsResult.Success(MonitorAlertDetail(null)) }
        override suspend fun getStatus(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertStatus> = MonitorAlertsResult.Failure(null, null, "unavailable")
        override suspend fun getLocation(id: NotificationDeliveryAttemptId) = error("unused")
        override suspend fun markViewed(id: NotificationDeliveryAttemptId): MonitorAlertsResult<com.example.sos_segundoplano.domain.monitor.MonitorAlertOpaquePayload> { viewIds += id.value; return MonitorAlertsResult.Success(com.example.sos_segundoplano.domain.monitor.MonitorAlertOpaquePayload(Unit)) }
        override suspend fun acknowledge(id: NotificationDeliveryAttemptId, responseType: String, message: String): MonitorAlertsResult<MonitorAlertDetail> { acknowledgeIds += id.value; return acknowledgeResult }
        override suspend fun decline(id: NotificationDeliveryAttemptId, reason: String): MonitorAlertsResult<MonitorAlertDetail> { declineIds += id.value; return declineResult }
    }
}
