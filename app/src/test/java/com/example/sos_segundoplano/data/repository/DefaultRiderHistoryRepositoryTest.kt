package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import com.example.sos_segundoplano.data.remote.incident.CreateIncidentDataDto
import com.example.sos_segundoplano.data.remote.incident.CreateIncidentRequestDto
import com.example.sos_segundoplano.data.remote.incident.IncidentHistoryPageDto
import com.example.sos_segundoplano.data.remote.incident.IncidentsApi
import com.example.sos_segundoplano.data.remote.trip.ActiveTripDataDto
import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.StartTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.TripHistoryDto
import com.example.sos_segundoplano.data.remote.trip.TripLocationDto
import com.example.sos_segundoplano.data.remote.trip.TripHistoryPageDto
import com.example.sos_segundoplano.data.remote.trip.TripMutationDataDto
import com.example.sos_segundoplano.data.remote.trip.TripsApi
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.history.RiderHistoryResult
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.Instant

class DefaultRiderHistoryRepositoryTest {
    @Test fun extractsListsFromPagedWrappersAndTreatsMissingListsAsEmpty() = runBlocking {
        val repository = DefaultRiderHistoryRepository(
            RiderAuth(),
            FakeTripsApi(ApiEnvelopeDto(true, TripHistoryPageDto(listOf(TripHistoryDto(status = "Finished", startLocation = TripLocationDto(19.4326, -99.1332, 12.5, "gps", "2026-08-12T10:00:00Z"), endLocation = TripLocationDto(19.435, -99.136, 10.0, "gps", "2026-08-12T10:01:46Z")))), null)),
            FakeIncidentsApi(ApiEnvelopeDto(true, IncidentHistoryPageDto(null), null))
        )

        val trips = repository.trips() as RiderHistoryResult.Success
        val incidents = repository.incidents() as RiderHistoryResult.Success

        assertEquals("Finished", trips.value.single().status)
        assertEquals(19.4326, trips.value.single().startLocation?.latitude)
        assertEquals("gps", trips.value.single().endLocation?.provider)
        assertTrue(incidents.value.isEmpty())
    }

    @Test fun moshiParsingFailureBecomesControlledRepositoryFailure() = runBlocking {
        val repository = DefaultRiderHistoryRepository(
            RiderAuth(),
            FakeTripsApi(failure = JsonDataException("expected object page")),
            FakeIncidentsApi(ApiEnvelopeDto(true, IncidentHistoryPageDto(emptyList()), null))
        )

        assertEquals(RiderHistoryResult.Failure("response_contract_incomplete"), repository.trips())
    }
}

private class RiderAuth : AuthRepository {
    private val state = MutableStateFlow<SessionState>(SessionState.Authenticated(
        AuthUser("rider-1", "rider@example.test", "Rider", "", UserRole.Rider, true), Instant.MAX, true
    ))
    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
    override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
    override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = AuthResult.Success(AccessToken("fixture-token"))
    override suspend fun refreshSession(): AuthResult<AuthUser> = error("unused")
    override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
    override fun observeSession(): StateFlow<SessionState> = state
}

private class FakeTripsApi(
    private val response: ApiEnvelopeDto<TripHistoryPageDto>? = null,
    private val failure: Exception? = null
) : TripsApi {
    override suspend fun listTrips(authorization: String): Response<ApiEnvelopeDto<TripHistoryPageDto>> {
        failure?.let { throw it }
        return Response.success(requireNotNull(response))
    }
    override suspend fun vehicles(authorization: String) = error("unused")
    override suspend fun devices(authorization: String) = error("unused")
    override suspend fun activeTrip(authorization: String): Response<ApiEnvelopeDto<ActiveTripDataDto>> = error("unused")
    override suspend fun startTrip(authorization: String, request: StartTripRequestDto): Response<ApiEnvelopeDto<TripMutationDataDto>> = error("unused")
    override suspend fun finishTrip(authorization: String, tripId: String, request: FinishTripRequestDto): Response<ApiEnvelopeDto<TripMutationDataDto>> = error("unused")
}

private class FakeIncidentsApi(
    private val response: ApiEnvelopeDto<IncidentHistoryPageDto>
) : IncidentsApi {
    override suspend fun listIncidents(authorization: String): Response<ApiEnvelopeDto<IncidentHistoryPageDto>> = Response.success(response)
    override suspend fun createIncident(authorization: String, request: CreateIncidentRequestDto): Response<ApiEnvelopeDto<CreateIncidentDataDto>> = error("unused")
}
