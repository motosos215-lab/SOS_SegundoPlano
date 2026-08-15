package com.example.sos_segundoplano.features.monitor

import com.example.sos_segundoplano.domain.monitor.MonitorAlertDetail
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsResult
import com.example.sos_segundoplano.domain.monitor.NotificationDeliveryAttemptId
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

    private class FakeStore(id: String? = null) : PendingMonitorAlertStore {
        private var pending = id?.let { PendingMonitorAlert(it, null, null, 1L) }
        override fun read() = pending
        override fun save(alert: PendingMonitorAlert): Boolean { pending = alert; return true }
        override fun clear(): Boolean { pending = null; return true }
    }
    private class FakeRepository : MonitorAlertsRepository {
        val detailIds = mutableListOf<String>(); val viewIds = mutableListOf<String>(); val acknowledgeIds = mutableListOf<String>(); val declineIds = mutableListOf<String>()
        override suspend fun listAlerts(): MonitorAlertsResult<List<MonitorAlertAcknowledgement>> = MonitorAlertsResult.Success(emptyList())
        override suspend fun getAlerts() = error("unused")
        override suspend fun getAlert(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertDetail> { detailIds += id.value; return MonitorAlertsResult.Success(MonitorAlertDetail(null)) }
        override suspend fun getStatus(id: NotificationDeliveryAttemptId) = error("unused")
        override suspend fun getLocation(id: NotificationDeliveryAttemptId) = error("unused")
        override suspend fun markViewed(id: NotificationDeliveryAttemptId): MonitorAlertsResult<com.example.sos_segundoplano.domain.monitor.MonitorAlertOpaquePayload> { viewIds += id.value; return MonitorAlertsResult.Success(com.example.sos_segundoplano.domain.monitor.MonitorAlertOpaquePayload(Unit)) }
        override suspend fun acknowledge(id: NotificationDeliveryAttemptId, responseType: String, message: String): MonitorAlertsResult<MonitorAlertDetail> { acknowledgeIds += id.value; return MonitorAlertsResult.Success(MonitorAlertDetail(null)) }
        override suspend fun decline(id: NotificationDeliveryAttemptId, reason: String): MonitorAlertsResult<MonitorAlertDetail> { declineIds += id.value; return MonitorAlertsResult.Success(MonitorAlertDetail(null)) }
    }
}
