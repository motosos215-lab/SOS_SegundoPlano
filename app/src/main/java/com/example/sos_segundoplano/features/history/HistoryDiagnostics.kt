package com.example.sos_segundoplano.features.history

import android.util.Log
import com.example.sos_segundoplano.BuildConfig

/** Debug-only, ID-free observability for the two remote history lists. */
object HistoryDiagnostics {
    private const val TAG = "MotoSOS.History"

    fun debug(message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching { Log.d(TAG, message) }
    }

    fun riderApiSuccess(
        pageNumber: Int?,
        pageSize: Int?,
        totalCount: Int?,
        receivedCount: Int,
        hasCountdownTimeout: Boolean,
        hasManualSos: Boolean,
        hasOpen: Boolean
    ): String = listOfNotNull(
        "event=rider_history_api_result",
        "result=success",
        pageNumber?.let { "page_number=$it" },
        pageSize?.let { "page_size=$it" },
        totalCount?.let { "total_count=$it" },
        "received_count=$receivedCount",
        "has_countdown_timeout=$hasCountdownTimeout",
        "has_manual_sos=$hasManualSos",
        "has_open=$hasOpen"
    ).joinToString(" ")

    fun riderMapping(receivedCount: Int, mappedCount: Int, hasCountdownTimeout: Boolean): String =
        "event=rider_history_mapping received_count=$receivedCount mapped_count=$mappedCount has_countdown_timeout=$hasCountdownTimeout"

    fun monitorApiSuccess(
        pageNumber: Int,
        pageSize: Int,
        totalCount: Int,
        receivedCount: Int,
        hasPending: Boolean,
        hasViewed: Boolean,
        hasAcknowledged: Boolean,
        hasDeclined: Boolean,
        hasDeliveryAttempt: Boolean
    ): String = "event=monitor_history_api_result result=success page_number=$pageNumber page_size=$pageSize total_count=$totalCount received_count=$receivedCount has_pending=$hasPending has_viewed=$hasViewed has_acknowledged=$hasAcknowledged has_declined=$hasDeclined has_delivery_attempt=$hasDeliveryAttempt"

    fun monitorMapping(receivedCount: Int, mappedCount: Int, hasAcknowledged: Boolean): String =
        "event=monitor_history_mapping received_count=$receivedCount mapped_count=$mappedCount has_acknowledged=$hasAcknowledged"

    fun apiFailure(prefix: String, statusCode: Int?, error: String?): String {
        val result = when {
            statusCode != null -> "http_error status=$statusCode"
            error == "network_unavailable" -> "network_error"
            error == "timeout" -> "timeout"
            error == "response_json_invalid" || error == "response_contract_incomplete" || error == "response_invalid" -> "parse_error"
            error == "access_token_unavailable" || error == "role_not_allowed" -> "auth_error"
            else -> "error type=${safeType(error)}"
        }
        return "event=${prefix}_api_result result=$result"
    }

    fun safeType(value: String?): String = value
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
        ?: "unknown"
}
