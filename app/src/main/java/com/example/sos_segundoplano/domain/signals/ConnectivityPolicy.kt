package com.example.sos_segundoplano.domain.signals

object ConnectivityPolicy {
    fun reading(
        hasNetwork: Boolean,
        validated: Boolean,
        metered: Boolean,
        transport: NetworkTransport,
        timestampMillis: Long
    ): SignalReading<ConnectivitySample> = SignalReading(
        SignalAvailability.Available,
        ConnectivitySample(
            connected = hasNetwork,
            validated = hasNetwork && validated,
            metered = hasNetwork && metered,
            transport = if (hasNetwork) transport else NetworkTransport.None,
            timestampMillis = timestampMillis
        )
    )
}
