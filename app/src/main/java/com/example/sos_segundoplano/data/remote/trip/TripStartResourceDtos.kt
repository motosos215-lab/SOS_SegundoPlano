package com.example.sos_segundoplano.data.remote.trip

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class VehiclesDataDto(
    val vehicles: List<VehicleResourceDto>? = null
)

@JsonClass(generateAdapter = false)
data class VehicleResourceDto(
    val id: String? = null,
    val completionStatus: String? = null,
    val isPrimary: Boolean? = null,
    val isActive: Boolean? = null
)

@JsonClass(generateAdapter = false)
data class DevicesDataDto(
    val devices: List<DeviceResourceDto>? = null
)

@JsonClass(generateAdapter = false)
data class DeviceResourceDto(
    val id: String? = null,
    val deviceType: String? = null,
    val linkStatus: String? = null,
    val parentDeviceId: String? = null,
    val isPrimary: Boolean? = null,
    val isActive: Boolean? = null
)
