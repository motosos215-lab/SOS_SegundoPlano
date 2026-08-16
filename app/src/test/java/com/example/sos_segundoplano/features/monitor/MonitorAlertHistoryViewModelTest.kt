package com.example.sos_segundoplano.features.monitor

import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertDetail
import com.example.sos_segundoplano.domain.monitor.MonitorAlertOpaquePayload
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsResult
import com.example.sos_segundoplano.domain.monitor.MonitorAlertStatus
import com.example.sos_segundoplano.domain.monitor.NotificationDeliveryAttemptId
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
class MonitorAlertHistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun loadsEmptyAndThenListOnRefresh() = runTest {
        val repository = FakeHistoryRepository(mutableListOf(emptyList(), listOf(alert("Pending"))))
        val viewModel = MonitorAlertHistoryViewModel(repository)
        assertEquals(MonitorAlertHistoryUiState.Empty, viewModel.state.value)
        viewModel.refresh()
        assertTrue(viewModel.state.value is MonitorAlertHistoryUiState.Content)
    }

    @Test fun failedRefreshKeepsPreviousList() = runTest {
        val repository = FakeHistoryRepository(mutableListOf(listOf(alert("Acknowledged")), null))
        val viewModel = MonitorAlertHistoryViewModel(repository)
        viewModel.refresh()
        assertTrue((viewModel.state.value as MonitorAlertHistoryUiState.Content).alerts.isNotEmpty())
    }

    private fun alert(status: String) = MonitorAlertAcknowledgement("id", "dispatch", "attempt-1", "incident", "trip", "contact", status, null, null, null, null, null, "2026-08-12T16:51:09Z", null)
    private class FakeHistoryRepository(private val results: MutableList<List<MonitorAlertAcknowledgement>?>) : MonitorAlertsRepository {
        override suspend fun listAlerts() = results.removeAt(0)?.let { MonitorAlertsResult.Success(it) } ?: MonitorAlertsResult.Failure(null, null, "network")
        override suspend fun getAlerts(): MonitorAlertsResult<MonitorAlertOpaquePayload> = error("unused")
        override suspend fun getAlert(id: NotificationDeliveryAttemptId) = error("unused") as MonitorAlertsResult<MonitorAlertDetail>
        override suspend fun getStatus(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertStatus> = error("unused")
        override suspend fun getLocation(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertOpaquePayload> = error("unused")
        override suspend fun markViewed(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertOpaquePayload> = error("unused")
        override suspend fun acknowledge(id: NotificationDeliveryAttemptId, responseType: String, message: String) = error("unused") as MonitorAlertsResult<MonitorAlertDetail>
        override suspend fun decline(id: NotificationDeliveryAttemptId, reason: String) = error("unused") as MonitorAlertsResult<MonitorAlertDetail>
    }
}
