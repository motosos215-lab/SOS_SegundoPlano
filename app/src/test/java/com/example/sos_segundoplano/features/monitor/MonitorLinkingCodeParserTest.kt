package com.example.sos_segundoplano.features.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorLinkingCodeParserTest {
    @Test fun acceptsManualCodeAndNormalizesCase() {
        assertEquals("8X7Q-3M2K-9L6R", MonitorLinkingCodeParser.parse(" 8x7q-3m2k-9l6r "))
    }

    @Test fun acceptsMotoSosQrPayload() {
        assertEquals(
            "8X7Q-3M2K-9L6R",
            MonitorLinkingCodeParser.parse("motosos://monitor-link?code=8X7Q-3M2K-9L6R")
        )
    }

    @Test fun acceptsHttpsQrPayloadWithCodeParameter() {
        assertEquals(
            "8X7Q-3M2K-9L6R",
            MonitorLinkingCodeParser.parse("https://motosos.example/vincular?code=8X7Q-3M2K-9L6R")
        )
    }

    @Test fun rejectsQrWithoutCodeAndUnsupportedScheme() {
        assertNull(MonitorLinkingCodeParser.parse("motosos://monitor-link"))
        assertNull(MonitorLinkingCodeParser.parse("ftp://example.test/?code=8X7Q-3M2K-9L6R"))
    }
}
