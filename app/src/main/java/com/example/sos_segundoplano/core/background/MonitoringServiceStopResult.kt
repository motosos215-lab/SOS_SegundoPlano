package com.example.sos_segundoplano.core.background

sealed interface MonitoringServiceStopResult {
    data object Stopped : MonitoringServiceStopResult
    data object AlreadyStopped : MonitoringServiceStopResult
    data object Failed : MonitoringServiceStopResult
}
