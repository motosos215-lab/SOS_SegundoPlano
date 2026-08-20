package com.example.sos_segundoplano.features.monitor

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object MonitorLinkingCodeParser {
    private val codePattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")

    fun parse(payload: String): String? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null
        val candidate = when {
            trimmed.contains("://") -> extractCodeFromUri(trimmed)
            else -> trimmed
        } ?: return null
        return normalize(candidate)
    }

    private fun extractCodeFromUri(value: String): String? = runCatching {
        val uri = URI(value)
        val supportedScheme = uri.scheme.equals("motosos", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)
        if (!supportedScheme) return@runCatching null

        uri.rawQuery
            ?.split('&')
            ?.asSequence()
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = decode(part.substring(0, separator))
                val valuePart = decode(part.substring(separator + 1))
                key to valuePart
            }
            ?.firstOrNull { (key, _) -> key.equals("code", ignoreCase = true) }
            ?.second
    }.getOrNull()

    private fun normalize(value: String): String? {
        val normalized = value.trim().uppercase()
        return normalized.takeIf(codePattern::matches)
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
