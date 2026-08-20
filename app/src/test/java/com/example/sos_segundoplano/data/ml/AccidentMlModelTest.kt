package com.example.sos_segundoplano.data.ml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentMlModelTest {
    @Test fun bundledV11ModelParsesAndUsesExpectedThreshold() {
        val model = AccidentMlModel.fromJson(readBundledModel())
        assertEquals("1.1.0-pilot-ready", model.version)
        assertEquals(0.71, model.threshold, 0.0)
    }

    @Test fun normalSampleStaysBelowThreshold() {
        val model = AccidentMlModel.fromJson(readBundledModel())
        val prediction = model.predict(normalSample(), hardRuleDetected = false)
        assertTrue(prediction.probability < model.threshold)
        assertFalse(prediction.mlDetected)
        assertFalse(prediction.possibleAccident)
    }

    @Test fun accidentLikeSampleCrossesThreshold() {
        val model = AccidentMlModel.fromJson(readBundledModel())
        val prediction = model.predict(accidentSample(), hardRuleDetected = false)
        assertTrue(prediction.probability >= model.threshold)
        assertTrue(prediction.mlDetected)
        assertTrue(prediction.possibleAccident)
    }

    @Test fun hardRuleStillForcesPossibleAccidentWhenMlIsLow() {
        val model = AccidentMlModel.fromJson(readBundledModel())
        val prediction = model.predict(normalSample(), hardRuleDetected = true)
        assertFalse(prediction.mlDetected)
        assertTrue(prediction.possibleAccident)
    }

    private fun normalSample() = AccidentMlInput(
        speedKmh = 28.0,
        deltaSpeedKmh = 1.0,
        decelerationKmhS = 0.0,
        gpsAccuracyM = 8.0,
        accelPeakG = 0.45,
        gyroPeakDps = 35.0,
        stillnessSeconds = 0.0,
        jerkPeakGS = 0.7,
    )

    private fun accidentSample() = AccidentMlInput(
        speedKmh = 62.0,
        deltaSpeedKmh = -50.0,
        decelerationKmhS = 25.0,
        gpsAccuracyM = 7.0,
        accelPeakG = 3.4,
        gyroPeakDps = 360.0,
        stillnessSeconds = 18.0,
        jerkPeakGS = 8.0,
    )

    private fun readBundledModel(): String {
        val candidates = listOf(
            File("src/main/assets/motosos_accident_model_v1_1_pilot_ready.json"),
            File("app/src/main/assets/motosos_accident_model_v1_1_pilot_ready.json"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Bundled accident ML model was not found from ${System.getProperty("user.dir")}")
        return file.readText()
    }
}
