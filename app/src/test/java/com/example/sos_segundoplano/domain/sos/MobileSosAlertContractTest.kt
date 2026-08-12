package com.example.sos_segundoplano.domain.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MobileSosAlertContractTest {
    @Test fun manualSosUsesOnlyConfirmedCanonicalValues() {
        assertEquals("ManualSos", MobileSosIncidentType.ManualSos.apiValue)
        assertEquals("High", MobileSosSeverity.High.apiValue)
        assertEquals("High", MobileSosPriority.High.apiValue)
        assertEquals("ManualSos", MobileSosReason.ManualSos.apiValue)
    }

    @Test fun crashDetectedIsNotAnEmittableCanonicalIncidentType() {
        assertFalse(MobileSosIncidentType.values().any { it.apiValue == "CrashDetected" })
    }
}
