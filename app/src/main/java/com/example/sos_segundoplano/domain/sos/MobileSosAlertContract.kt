package com.example.sos_segundoplano.domain.sos

/** Canonical backend values. Automatic severity/priority mapping pending backend contract. */
enum class MobileSosIncidentType(val apiValue: String) {
    CountdownTimeout("CountdownTimeout"),
    UserRequestedHelp("UserRequestedHelp"),
    CriticalEvent("CriticalEvent"),
    ManualSos("ManualSos"),
    Unknown("Unknown")
}

enum class MobileSosSeverity(val apiValue: String) {
    Unknown("Unknown"), Low("Low"), Medium("Medium"), High("High")
}

enum class MobileSosPriority(val apiValue: String) {
    Low("Low"), Medium("Medium"), High("High"), Critical("Critical")
}

enum class MobileSosReason(val apiValue: String) {
    IncidentCreated("IncidentCreated"),
    ManualSos("ManualSos"),
    CountdownTimeout("CountdownTimeout"),
    CriticalEvent("CriticalEvent"),
    UserRequestedHelp("UserRequestedHelp"),
    Unknown("Unknown")
}
