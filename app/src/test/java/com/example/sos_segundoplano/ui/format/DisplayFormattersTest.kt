package com.example.sos_segundoplano.ui.format

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormattersTest {
    @Test fun formatsIsoTimeInRequestedLocalZone() {
        assertEquals("12/08/2026 10:51", DisplayFormatters.dateTime("2026-08-12T16:51:09Z", ZoneId.of("America/Mexico_City")))
    }

    @Test fun localizesKnownMonitorAndRiderValues() {
        assertEquals("Atendida", monitorStatusLabel("Acknowledged"))
        assertEquals("Puedo ayudar", monitorResponseLabel("CanAssist"))
        assertEquals("Finalizado", tripStatusLabel("Finished"))
        assertEquals("SOS manual", incidentCauseLabel("ManualSos"))
        assertEquals("Evento crítico", incidentCauseLabel("CriticalEvent"))
        assertEquals("Evento sin clasificar", incidentCauseLabel(null))
    }

    @Test fun safelyRejectsBlankAndInvalidDates() {
        assertEquals(null, DisplayFormatters.dateTime(null))
        assertEquals(null, DisplayFormatters.dateTime(""))
        assertEquals(null, DisplayFormatters.dateTime("not-a-date"))
    }

    @Test fun formatsFinishedTripDurationsWithoutTimezoneOrDayRollover() {
        assertEquals("00:00:46", DisplayFormatters.duration("2026-08-12T10:00:00Z", "2026-08-12T10:00:46Z"))
        assertEquals("00:01:46", DisplayFormatters.duration("2026-08-12T10:00:00Z", "2026-08-12T10:01:46Z"))
        assertEquals("01:05:09", DisplayFormatters.duration("2026-08-12T10:00:00Z", "2026-08-12T11:05:09Z"))
        assertEquals("27:00:00", DisplayFormatters.duration("2026-08-12T10:00:00Z", "2026-08-13T13:00:00Z"))
        assertEquals("00:01:46", DisplayFormatters.duration("2026-08-12T10:00:00-06:00", "2026-08-12T16:01:46Z"))
    }

    @Test fun rejectsIncompleteInvalidAndNegativeTripDurations() {
        assertEquals(null, DisplayFormatters.duration("2026-08-12T10:00:00Z", null))
        assertEquals(null, DisplayFormatters.duration("invalid", "2026-08-12T10:00:00Z"))
        assertEquals(null, DisplayFormatters.duration("2026-08-12T10:00:01Z", "2026-08-12T10:00:00Z"))
    }
}
