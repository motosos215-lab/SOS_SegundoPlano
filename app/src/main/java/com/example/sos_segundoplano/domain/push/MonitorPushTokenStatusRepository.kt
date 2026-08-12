package com.example.sos_segundoplano.domain.push

data class MonitorPushTokenStatus(
    val activeTokenCount: Int,
    val revokedTokenCount: Int,
    val hasActiveAndroidFcm: Boolean,
    val hasActiveIosApns: Boolean,
    val hasActiveWebPush: Boolean,
    val hasActiveWebFcm: Boolean,
    val lastRegisteredAtUtc: String?
)

sealed interface MonitorPushTokenStatusResult {
    data class Success(val value: MonitorPushTokenStatus) : MonitorPushTokenStatusResult
    data class Failure(val statusCode: Int?, val errorCode: String?) : MonitorPushTokenStatusResult
}

interface MonitorPushTokenStatusRepository {
    suspend fun getStatus(): MonitorPushTokenStatusResult
}
