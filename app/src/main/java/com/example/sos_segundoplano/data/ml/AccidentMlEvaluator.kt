package com.example.sos_segundoplano.data.ml

import android.content.Context
import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalWindow
import com.example.sos_segundoplano.domain.rules.RiskAssessment

class AccidentMlEvaluator(
    private val model: AccidentMlModel,
    private val extractor: AccidentMlFeatureExtractor = AccidentMlFeatureExtractor(),
) {
    fun reset() = extractor.reset()

    fun evaluate(window: ProcessedSignalWindow, assessment: RiskAssessment): RiskAssessment {
        val input = extractor.extract(window, assessment) ?: return assessment.copy(
            mlProbability = null,
            mlDetected = false,
            mlHardRuleDetected = false,
            mlModelVersion = model.version,
        )
        val hardRuleDetected = assessment.hasMlHardRuleSupport()
        val prediction = model.predict(input, hardRuleDetected)
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "model=${prediction.modelVersion} " +
                    "speed=${fmt(input.speedKmh)} delta=${fmt(input.deltaSpeedKmh)} " +
                    "decel=${fmt(input.decelerationKmhS)} gpsAcc=${fmt(input.gpsAccuracyM)} " +
                    "accelG=${fmt(input.accelPeakG)} gyroDps=${fmt(input.gyroPeakDps)} " +
                    "still=${fmt(input.stillnessSeconds)} jerk=${fmt(input.jerkPeakGS)} " +
                    "p=${fmt(prediction.probability)} threshold=${fmt(prediction.threshold)} " +
                    "hard=$hardRuleDetected ml=${prediction.mlDetected} possible=${prediction.possibleAccident}"
            )
        }
        return assessment.copy(
            mlProbability = prediction.probability,
            mlDetected = prediction.mlDetected,
            mlHardRuleDetected = hardRuleDetected,
            mlModelVersion = prediction.modelVersion,
        )
    }

    private fun fmt(value: Double): String = "%.3f".format(java.util.Locale.US, value)

    companion object {
        private const val TAG = "MotoSOS-AccidentML"
        private const val ASSET_NAME = "motosos_accident_model_v1_1_pilot_ready.json"

        fun fromAssets(context: Context): AccidentMlEvaluator? = runCatching {
            val rawJson = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            AccidentMlEvaluator(AccidentMlModel.fromJson(rawJson))
        }.onFailure { error ->
            if (BuildConfig.DEBUG) Log.w(TAG, "ml_disabled reason=${error::class.java.simpleName}")
        }.getOrNull()
    }
}
