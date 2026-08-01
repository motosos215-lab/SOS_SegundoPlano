package com.example.sos_segundoplano.domain.signals

object GpsAccuracyPolicy {
    fun classify(accuracyMeters: Float?): GpsAccuracyClassification? {
        if (accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters < 0f) return null
        return when {
            accuracyMeters <= 5f -> GpsAccuracyClassification.Excellent
            accuracyMeters <= 10f -> GpsAccuracyClassification.High
            accuracyMeters <= 25f -> GpsAccuracyClassification.Medium
            accuracyMeters <= 50f -> GpsAccuracyClassification.Low
            else -> GpsAccuracyClassification.VeryLow
        }
    }
}

enum class GpsAccuracyClassification {
    Excellent,
    High,
    Medium,
    Low,
    VeryLow
}
