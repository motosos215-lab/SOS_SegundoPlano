package com.example.sos_segundoplano.features.monitor

import com.example.sos_segundoplano.domain.emergency.CreateEmergencyContact
import com.example.sos_segundoplano.domain.emergency.EmergencyContact
import com.example.sos_segundoplano.domain.emergency.EmergencyContactPermissions
import com.example.sos_segundoplano.domain.emergency.EmergencyContactsRepository
import com.example.sos_segundoplano.domain.emergency.EmergencyContactsResult
import com.example.sos_segundoplano.domain.emergency.EmergencyInvitation
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatus
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatusRepository
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatusResult
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
class MonitorLinkingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun qrLoadsInvitationAndAcceptLinksMonitor() = runTest {
        val emergency = FakeEmergencyRepository()
        val push = FakePushStatusRepository(true)
        val viewModel = MonitorLinkingViewModel(emergency, push)

        viewModel.onQrPayload("motosos://monitor-link?code=8X7Q-3M2K-9L6R")
        assertEquals(listOf("8X7Q-3M2K-9L6R"), emergency.lookups)
        assertTrue(viewModel.state.value.phase is MonitorLinkingPhase.InvitationReady)

        viewModel.acceptInvitation()
        assertEquals(listOf("8X7Q-3M2K-9L6R"), emergency.accepts)
        assertEquals(MonitorLinkingPhase.Linked, viewModel.state.value.phase)
        assertTrue(viewModel.state.value.pushReadiness is MonitorPushReadiness.Ready)
    }

    @Test fun expiredInvitationShowsControlledMessage() = runTest {
        val emergency = FakeEmergencyRepository().apply {
            lookupResult = EmergencyContactsResult.Failure(400, "invitation_expired", "expired")
        }
        val viewModel = MonitorLinkingViewModel(emergency, FakePushStatusRepository(true))

        viewModel.onCodeChanged("8X7Q-3M2K-9L6R")
        viewModel.lookupInvitation()

        assertEquals("La invitación ya expiró. Solicita un código nuevo al Rider.", viewModel.state.value.message)
    }

    @Test fun linkedMonitorWithoutActiveFcmIsReportedAsMissing() = runTest {
        val viewModel = MonitorLinkingViewModel(FakeEmergencyRepository(), FakePushStatusRepository(false))
        viewModel.onCodeChanged("8X7Q-3M2K-9L6R")
        viewModel.lookupInvitation()
        viewModel.acceptInvitation()
        assertTrue(viewModel.state.value.pushReadiness is MonitorPushReadiness.Missing)
    }

    private class FakePushStatusRepository(private val active: Boolean) : MonitorPushTokenStatusRepository {
        override suspend fun getStatus(): MonitorPushTokenStatusResult = MonitorPushTokenStatusResult.Success(
            MonitorPushTokenStatus(
                activeTokenCount = if (active) 1 else 0,
                revokedTokenCount = 0,
                hasActiveAndroidFcm = active,
                hasActiveIosApns = false,
                hasActiveWebPush = false,
                hasActiveWebFcm = false,
                lastRegisteredAtUtc = null
            )
        )
    }

    private class FakeEmergencyRepository : EmergencyContactsRepository {
        val lookups = mutableListOf<String>()
        val accepts = mutableListOf<String>()
        var lookupResult: EmergencyContactsResult<EmergencyInvitation> = EmergencyContactsResult.Success(
            EmergencyInvitation(
                driverFullName = "Rider MotoSOS",
                contactFullName = "Monitor MotoSOS",
                permissions = permissions,
                expiresAtUtc = "2026-08-20T12:00:00Z",
                status = "Invited"
            )
        )

        override suspend fun getInvitation(code: String): EmergencyContactsResult<EmergencyInvitation> {
            lookups += code
            return lookupResult
        }

        override suspend fun acceptInvitation(code: String): EmergencyContactsResult<EmergencyContact> {
            accepts += code
            return EmergencyContactsResult.Success(contact)
        }

        override suspend fun list() = EmergencyContactsResult.Success(emptyList<EmergencyContact>())
        override suspend fun create(request: CreateEmergencyContact) = error("unused")
        override suspend fun invite(contactId: String) = error("unused")

        companion object {
            private val permissions = EmergencyContactPermissions(true, true, false, false)
            private val contact = EmergencyContact(
                id = "contact-id",
                userId = "rider-id",
                fullName = "Monitor MotoSOS",
                relationship = "Contacto de emergencia",
                phoneNumber = "+525555555555",
                email = "monitor@example.test",
                priority = 1,
                invitationStatus = "Linked",
                linkingCode = "8X7Q-3M2K-9L6R",
                linkingCodeExpiresAtUtc = "2026-08-20T12:00:00Z",
                linkedUserId = "monitor-id",
                permissions = permissions,
                isPrimary = true,
                isActive = true,
                createdAtUtc = "2026-08-20T10:00:00Z",
                updatedAtUtc = "2026-08-20T10:01:00Z",
                invitedAtUtc = "2026-08-20T10:00:30Z",
                linkedAtUtc = "2026-08-20T10:01:00Z",
                revokedAtUtc = null
            )
        }
    }
}
