package com.example.sos_segundoplano.features.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RiderHistoryLocationTest {
    @Test fun buildsGenericGeoUriForMapHandler() {
        assertEquals(
            "geo:19.4326,-99.1332?q=19.4326,-99.1332",
            buildGeoUri(19.4326, -99.1332).toString()
        )
    }
}
