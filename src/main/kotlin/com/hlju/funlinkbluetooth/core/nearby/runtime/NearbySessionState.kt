package com.hlju.funlinkbluetooth.core.nearby.runtime

import com.hlju.funlinkbluetooth.core.model.ConnectionRole
import com.hlju.funlinkbluetooth.core.model.ConnectionStatus
import com.hlju.funlinkbluetooth.core.model.NearbyEndpointInfo

data class NearbySessionState(
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val role: ConnectionRole = ConnectionRole.HOST,
    val isAdvertising: Boolean = false,
    val isDiscovering: Boolean = false,
    val isStartingAdvertising: Boolean = false,
    val isStartingDiscovery: Boolean = false,
    val connectedEndpoints: Set<NearbyEndpointInfo> = emptySet(),
    val discoveredEndpoints: List<NearbyEndpointInfo> = emptyList(),
    val pendingConnections: List<PendingConnectionInfo> = emptyList(),
    val lastError: String? = null,
    val endpointBandwidth: Map<String, Int> = emptyMap(),
    val currentQuality: Int = 0,
    val allowDisruptiveUpgrade: Boolean = true
)

data class PendingConnectionInfo(
    val endpointId: String,
    val endpointName: String
)
