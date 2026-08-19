package com.example.sos_segundoplano.data.ml

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.exp

/**
 * Local pilot-only accident model. It never sends data over the network and it never replaces
 * the explainable rule engine or manual SOS.
 */
data class AccidentMlInput(
    val speedKmh: Double,
    val deltaSpeedKmh: Double,
    val decelerationKmhS: Double,
    val gpsAccuracyM: Double,
    val accelPeakG: Double,
    val gyroPeakDps: Double,
    val stillnessSeconds: Double,
    val jerkPeakGS: Double,
) {
    fun asFeatureMap(): Map<String, Double> = mapOf(
        "speed_kmh" to speedKmh,
        "delta_speed_kmh" to deltaSpeedKmh,
        "deceleration_kmh_s" to decelerationKmhS,
        "gps_accuracy_m" to gpsAccuracyM,
        "accel_peak_g" to accelPeakG,
        "gyro_peak_dps" to gyroPeakDps,
        "stillness_seconds" to stillnessSeconds,
        "jerk_peak_g_s" to jerkPeakGS,
    )
}

data class AccidentMlPrediction(
    val probability: Double,
    val mlDetected: Boolean,
    val possibleAccident: Boolean,
    val threshold: Double,
    val modelVersion: String,
)

class AccidentMlModel private constructor(
    val version: String,
    private val features: List<String>,
    private val means: Map<String, Double>,
    private val stds: Map<String, Double>,
    private val weights: Map<String, Double>,
    private val bias: Double,
    val threshold: Double,
) {
    fun predict(input: AccidentMlInput, hardRuleDetected: Boolean): AccidentMlPrediction {
        val sample = input.asFeatureMap()
        var logit = bias
        for (feature in features) {
            val value = sample.getValue(feature)
            val mean = means.getValue(feature)
            val std = stds.getValue(feature).takeIf { it.isFinite() && it != 0.0 } ?: 1.0
            val weight = weights.getValue(feature)
            logit += weight * ((value - mean) / std)
        }
        val probability = sigmoid(logit)
        val mlDetected = probability >= threshold
        return AccidentMlPrediction(
            probability = probability,
            mlDetected = mlDetected,
            possibleAccident = hardRuleDetected || mlDetected,
            threshold = threshold,
            modelVersion = version,
        )
    }

    companion object {
        private val expectedFeatures = listOf(
            "speed_kmh",
            "delta_speed_kmh",
            "deceleration_kmh_s",
            "gps_accuracy_m",
            "accel_peak_g",
            "gyro_peak_dps",
            "stillness_seconds",
            "jerk_peak_g_s",
        )

        fun fromJson(rawJson: String): AccidentMlModel {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val artifact = requireNotNull(
                moshi.adapter(AccidentMlArtifact::class.java).fromJson(rawJson)
            ) { "accident_ml_json_empty" }
            require(artifact.features == expectedFeatures) { "accident_ml_features_mismatch" }
            require(artifact.threshold in 0.0..1.0) { "accident_ml_threshold_invalid" }
            expectedFeatures.forEach { feature ->
                require(artifact.normalization.mean[feature]?.isFinite() == true) { "accident_ml_mean_missing:$feature" }
                require(artifact.normalization.std[feature]?.isFinite() == true) { "accident_ml_std_missing:$feature" }
                require(artifact.normalization.std.getValue(feature) > 0.0) { "accident_ml_std_invalid:$feature" }
                require(artifact.weights[feature]?.isFinite() == true) { "accident_ml_weight_missing:$feature" }
            }
            require(artifact.bias.isFinite()) { "accident_ml_bias_invalid" }
            return AccidentMlModel(
                version = artifact.version.ifBlank { "unknown" },
                features = artifact.features,
                means = artifact.normalization.mean,
                stds = artifact.normalization.std,
                weights = artifact.weights,
                bias = artifact.bias,
                threshold = artifact.threshold,
            )
        }

        private fun sigmoid(value: Double): Double = if (value >= 0.0) {
            val z = exp(-value)
            1.0 / (1.0 + z)
        } else {
            val z = exp(value)
            z / (1.0 + z)
        }
    }
}

@JsonClass(generateAdapter = false)
internal data class AccidentMlArtifact(
    val version: String,
    val bias: Double,
    val features: List<String>,
    val normalization: AccidentMlNormalization,
    val weights: Map<String, Double>,
    val threshold: Double,
)

@JsonClass(generateAdapter = false)
internal data class AccidentMlNormalization(
    val mean: Map<String, Double>,
    val std: Map<String, Double>,
)
