package com.example.sos_segundoplano.data.offline

/**
 * Local-only identity for an automatic SOS pair. The owner is the backend Rider ID;
 * the key is derived from the durable client incident ID, never generated per retry.
 */
data class OfflineBundleMetadata(
    val ownerUserId: String,
    val bundleKey: String,
    val remoteTripId: String?
)
