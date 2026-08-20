package com.example.sos_segundoplano.data.remote.emergency

import com.example.sos_segundoplano.data.remote.auth.AuthNetworkFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmergencyContactsApiTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    @Test fun createAndInviteUseCanonicalPathsAndBodies() = runBlocking {
        server.enqueue(json(200, contactResponse("Pending")))
        server.enqueue(json(200, contactResponse("Invited", "CODE-1")))
        val api = api()
        api.create("Bearer rider", CreateEmergencyContactRequestDto("Contact", "Friend", "+52", "monitor@example.com", 1, EmergencyContactPermissionsDto(true, true, false, false), "Continue"))
        api.invite("Bearer rider", "contact-1")
        val create = server.takeRequest()
        val invite = server.takeRequest()
        assertEquals("/api/v1/emergency-contacts", create.path)
        assertTrue(create.body.readUtf8().contains("\"canReceiveCriticalAlerts\":true"))
        assertEquals("/api/v1/emergency-contacts/contact-1/invite", invite.path)
        assertEquals("{}", invite.body.readUtf8())
    }

    @Test fun listUsesCanonicalPathAndMapsContacts() = runBlocking {
        server.enqueue(json(200, """{"success":true,"data":{"contacts":[{"id":"contact-1","userId":"rider-1","fullName":"Monitor MotoSOS","relationship":"Contacto de emergencia","phoneNumber":"+525555555555","email":"monitor@example.com","priority":1,"invitationStatus":"Linked","linkingCode":null,"linkingCodeExpiresAtUtc":null,"linkedUserId":"monitor-1","permissions":{"canViewRealTimeLocation":true,"canReceiveCriticalAlerts":true,"canViewIncidentHistory":false,"canViewVitalSigns":false},"isPrimary":true,"isActive":true,"createdAtUtc":"2026-08-11T00:00:00Z","updatedAtUtc":"2026-08-11T00:00:00Z","invitedAtUtc":"2026-08-11T00:00:00Z","linkedAtUtc":"2026-08-11T01:00:00Z","revokedAtUtc":null}]},"error":null}"""))
        val response = api().list("Bearer rider")
        assertEquals("/api/v1/emergency-contacts", server.takeRequest().path)
        assertEquals("Monitor MotoSOS", response.body()?.data?.contacts?.single()?.fullName)
        assertEquals("Linked", response.body()?.data?.contacts?.single()?.invitationStatus)
    }

    @Test fun invitationAndAcceptUseCodeAndMapContact() = runBlocking {
        server.enqueue(json(200, """{"success":true,"data":{"invitation":{"driverFullName":"Rider","contactFullName":"Monitor","permissions":{"canViewRealTimeLocation":true,"canReceiveCriticalAlerts":true,"canViewIncidentHistory":false,"canViewVitalSigns":false},"expiresAtUtc":"2026-08-12T00:00:00Z","status":"Invited"}},"error":null}"""))
        server.enqueue(json(200, contactResponse("Linked")))
        val api = api()
        assertEquals("Invited", api.invitation("Bearer monitor", "CODE-1").body()?.data?.invitation?.status)
        assertEquals("Linked", api.acceptInvitation("Bearer monitor", "CODE-1").body()?.data?.contact?.invitationStatus)
        assertEquals("/api/v1/emergency-contacts/invitations/CODE-1", server.takeRequest().path)
        assertEquals("/api/v1/emergency-contacts/invitations/CODE-1/accept", server.takeRequest().path)
    }

    private fun api() = AuthNetworkFactory.createEmergencyContactsApi(server.url("/").toString())
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)
    private fun contactResponse(status: String, code: String? = null) = """{"success":true,"data":{"contact":{"id":"contact-1","userId":"rider-1","fullName":"Contact","relationship":"Friend","phoneNumber":"+52","email":"monitor@example.com","priority":1,"invitationStatus":"$status","linkingCode":${code?.let { "\"$it\"" } ?: "null"},"linkingCodeExpiresAtUtc":null,"linkedUserId":null,"permissions":{"canViewRealTimeLocation":true,"canReceiveCriticalAlerts":true,"canViewIncidentHistory":false,"canViewVitalSigns":false},"isPrimary":true,"isActive":true,"createdAtUtc":"2026-08-11T00:00:00Z","updatedAtUtc":"2026-08-11T00:00:00Z","invitedAtUtc":null,"linkedAtUtc":null,"revokedAtUtc":null}},"error":null}"""
}
