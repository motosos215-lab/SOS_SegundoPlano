package com.example.sos_segundoplano.core.background

sealed interface MonitoringServiceStartResult {
    data object Started : MonitoringServiceStartResult
    data object Failed : MonitoringServiceStartResult
}
