package com.example.sos_segundoplano.domain.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsAccuracyPolicyTest {
    @Test fun classifiesExcellentFromZeroToFiveMeters() {
        assertEquals(GpsAccuracyClassification.Excellent, GpsAccuracyPolicy.classify(0f))
        assertEquals(GpsAccuracyClassification.Excellent, GpsAccuracyPolicy.classify(5f))
    }

    @Test fun classifiesHighAboveFiveToTenMeters() {
        assertEquals(GpsAccuracyClassification.High, GpsAccuracyPolicy.classify(5.01f))
        assertEquals(GpsAccuracyClassification.High, GpsAccuracyPolicy.classify(10f))
    }

    @Test fun classifiesMediumAboveTenToTwentyFiveMeters() {
        assertEquals(GpsAccuracyClassification.Medium, GpsAccuracyPolicy.classify(10.01f))
        assertEquals(GpsAccuracyClassification.Medium, GpsAccuracyPolicy.classify(25f))
    }

    @Test fun classifiesLowAboveTwentyFiveToFiftyMeters() {
        assertEquals(GpsAccuracyClassification.Low, GpsAccuracyPolicy.classify(25.01f))
        assertEquals(GpsAccuracyClassification.Low, GpsAccuracyPolicy.classify(50f))
    }

    @Test fun classifiesVeryLowAboveFiftyMeters() {
        assertEquals(GpsAccuracyClassification.VeryLow, GpsAccuracyPolicy.classify(50.01f))
    }

    @Test fun rejectsMissingInvalidAndNegativeAccuracy() {
        assertNull(GpsAccuracyPolicy.classify(null))
        assertNull(GpsAccuracyPolicy.classify(Float.NaN))
        assertNull(GpsAccuracyPolicy.classify(Float.POSITIVE_INFINITY))
        assertNull(GpsAccuracyPolicy.classify(-1f))
    }
}
