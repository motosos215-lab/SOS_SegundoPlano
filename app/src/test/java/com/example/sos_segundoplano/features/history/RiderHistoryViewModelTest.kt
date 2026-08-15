package com.example.sos_segundoplano.features.history

import com.example.sos_segundoplano.domain.history.RiderHistoryRepository
import com.example.sos_segundoplano.domain.history.RiderHistoryResult
import com.example.sos_segundoplano.domain.history.RiderIncidentHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryItem
import com.squareup.moshi.JsonDataException
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
class RiderHistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun initialEmptyAndDataStatesAreExposedForEachHistory() = runTest {
        val empty = RiderHistoryViewModel(FakeHistoryRepository())
        assertEquals(RiderHistoryUiState.Empty, empty.trips.value)
        assertEquals(RiderHistoryUiState.Empty, empty.incidents.value)

        val data = RiderHistoryViewModel(FakeHistoryRepository(
            trips = mutableListOf(successTrips()), incidents = mutableListOf(successIncidents())
        ))
        assertTrue(data.trips.value is RiderHistoryUiState.Content)
        assertTrue(data.incidents.value is RiderHistoryUiState.Content)
    }

    @Test fun retryAndRefreshUpdateTripsWhileFailureKeepsPreviousContent() = runTest {
        val repository = FakeHistoryRepository(trips = mutableListOf(failure(), successTrips(), failure()))
        val viewModel = RiderHistoryViewModel(repository)
        assertTrue(viewModel.trips.value is RiderHistoryUiState.Error)
        viewModel.refreshTrips()
        assertTrue(viewModel.trips.value is RiderHistoryUiState.Content)
        viewModel.refreshTrips()
        val content = viewModel.trips.value as RiderHistoryUiState.Content
        assertEquals(1, content.items.size)
        assertEquals("network", content.error)
    }

    @Test fun retryAndRefreshUpdateIncidentsWhileFailureKeepsPreviousContent() = runTest {
        val repository = FakeHistoryRepository(incidents = mutableListOf(successIncidents(), failure()))
        val viewModel = RiderHistoryViewModel(repository)
        assertTrue(viewModel.incidents.value is RiderHistoryUiState.Content)
        viewModel.refreshIncidents()
        val content = viewModel.incidents.value as RiderHistoryUiState.Content
        assertEquals("ManualSos", content.items.single().cause)
        assertEquals("network", content.error)
    }

    @Test fun unexpectedHistoryParsingFailureBecomesRetryableError() = runTest {
        val viewModel = RiderHistoryViewModel(ThrowingHistoryRepository())

        assertTrue(viewModel.trips.value is RiderHistoryUiState.Error)
        assertTrue(viewModel.incidents.value is RiderHistoryUiState.Error)
    }

    private fun successTrips() = RiderHistoryResult.Success(listOf(RiderTripHistoryItem("Finished", "2026-08-12T16:00:00Z", "2026-08-12T17:30:00Z")))
    private fun successIncidents() = RiderHistoryResult.Success(listOf(RiderIncidentHistoryItem("ManualSos", "High", "Open", "2026-08-12T16:00:00Z")))
    private fun <T> failure() = RiderHistoryResult.Failure("network") as RiderHistoryResult<List<T>>

    private class FakeHistoryRepository(
        private val trips: MutableList<RiderHistoryResult<List<RiderTripHistoryItem>>> = mutableListOf(RiderHistoryResult.Success(emptyList())),
        private val incidents: MutableList<RiderHistoryResult<List<RiderIncidentHistoryItem>>> = mutableListOf(RiderHistoryResult.Success(emptyList()))
    ) : RiderHistoryRepository {
        override suspend fun trips() = trips.removeAt(0)
        override suspend fun incidents() = incidents.removeAt(0)
    }

    private class ThrowingHistoryRepository : RiderHistoryRepository {
        override suspend fun trips(): RiderHistoryResult<List<RiderTripHistoryItem>> =
            throw JsonDataException("fixture parsing failure")
        override suspend fun incidents(): RiderHistoryResult<List<RiderIncidentHistoryItem>> =
            throw JsonDataException("fixture parsing failure")
    }
}
