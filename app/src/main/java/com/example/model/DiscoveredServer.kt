package com.example.model

/**
 * Encapsulates discovery details found via UDP Broadcast on LAN networks.
 */
data class DiscoveredServer(
    val ipAddress: String,
    val hostName: String,
    val tcpPort: Int,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
