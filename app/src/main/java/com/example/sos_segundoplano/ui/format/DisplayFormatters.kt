package com.example.sos_segundoplano.ui.format

import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DisplayFormatters {
    private val localDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    fun dateTime(iso: String?, zoneId: ZoneId = ZoneId.systemDefault()): String? = iso
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?.atZone(zoneId)
        ?.format(localDateTime)

    fun duration(startedAtUtc: String?, finishedAtUtc: String?): String? = runCatching {
        val startedAt = Instant.parse(startedAtUtc)
        val finishedAt = Instant.parse(finishedAtUtc)
        Duration.between(startedAt, finishedAt).takeIf { !it.isNegative }?.let { duration ->
            "%02d:%02d:%02d".format(duration.toHours(), duration.toMinutes() % 60, duration.seconds % 60)
        }
    }.getOrNull()
}

fun monitorStatusLabel(value: String?): String = when (value) {
    "Pending" -> "Pendiente"
    "Viewed" -> "Vista"
    "Acknowledged" -> "Atendida"
    "Declined" -> "Rechazada"
    else -> value.orEmpty()
}

fun monitorResponseLabel(value: String?): String = when (value) {
    "CanAssist" -> "Puedo ayudar"
    "CannotAssist" -> "No puedo ayudar"
    "None" -> "Sin respuesta"
    else -> value.orEmpty()
}

fun tripStatusLabel(value: String?): String = when (value) {
    "Active" -> "En curso"
    "Finished" -> "Finalizado"
    else -> value.orEmpty()
}

fun incidentCauseLabel(value: String?): String = when (value) {
    "ManualSos" -> "SOS manual"
    "CountdownTimeout" -> "Tiempo de confirmación agotado"
    "UserRequestedHelp" -> "Solicitud de ayuda"
    "CriticalEvent" -> "Evento crítico"
    "Unknown", null, "" -> "Evento sin clasificar"
    else -> value
}
