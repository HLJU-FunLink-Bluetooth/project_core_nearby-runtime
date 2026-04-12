package com.hlju.funlinkbluetooth.core.nearby.runtime

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.hlju.funlinkbluetooth.core.model.ConnectionRole
import com.hlju.funlinkbluetooth.core.model.ConnectionStatus
import com.hlju.funlinkbluetooth.core.model.NearbyEndpointInfo
import com.hlju.funlinkbluetooth.core.model.NearbyError
import com.hlju.funlinkbluetooth.core.plugin.api.FunLinkPayload
import com.hlju.funlinkbluetooth.core.plugin.api.PluginWireEnvelope
import com.hlju.funlinkbluetooth.core.plugin.api.TransferUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class NearbySessionController(context: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId: String = context.packageName

    private val deviceId = UUID.randomUUID().toString().take(8)

    private val connectionStateLock = Any()
    private val outgoingRequests = mutableSetOf<String>()
    private val awaitingConnectionResult = mutableSetOf<String>()
    private val endpointNames = mutableMapOf<String, String>()

    private val _state = MutableStateFlow(NearbySessionState())
    val state: StateFlow<NearbySessionState> = _state.asStateFlow()

    val currentQuality: Int get() = _state.value.currentQuality

    // Callbacks for the host to delegate events — set once by app module
    var onMessageReceived: ((endpointId: String, bytes: ByteArray) -> Unit)? = null
    var onPayloadReceived: ((endpointId: String, payload: FunLinkPayload) -> Unit)? = null
    var onPayloadTransferUpdate: ((endpointId: String, update: TransferUpdate) -> Unit)? = null
    var onEndpointConnected: ((endpointId: String, endpointName: String) -> Unit)? = null
    var onEndpointDisconnected: ((endpointId: String) -> Unit)? = null
    var onBandwidthChanged: ((endpointId: String, quality: Int) -> Unit)? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val remoteName = info.endpointName.ifBlank { endpointId }
            endpointNames[endpointId] = remoteName

            if (isSelfEndpointName(remoteName)) {
                clearEndpointTracking(endpointId)
                connectionsClient.rejectConnection(endpointId)
                updateStatus()
                return
            }

            val outgoingRequest = synchronized(connectionStateLock) {
                awaitingConnectionResult.add(endpointId)
                outgoingRequests.contains(endpointId)
            }

            if (outgoingRequest) {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { exception ->
                        synchronized(connectionStateLock) {
                            outgoingRequests.remove(endpointId)
                            awaitingConnectionResult.remove(endpointId)
                        }
                        _state.update { it.copy(lastError = NearbyError.fromNearbyException("接受连接失败", exception).displayMessage) }
                        updateStatus()
                    }
                return
            }

            addPendingConnection(endpointId, remoteName)
            updateStatus()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            synchronized(connectionStateLock) {
                outgoingRequests.remove(endpointId)
                awaitingConnectionResult.remove(endpointId)
            }
            removePendingConnection(endpointId)

            if (result.status.isSuccess) {
                val remoteName = endpointNames[endpointId] ?: endpointId
                if (isSelfEndpointName(remoteName)) {
                    clearEndpointTracking(endpointId)
                    connectionsClient.disconnectFromEndpoint(endpointId)
                    updateStatus()
                    return
                }

                var didConnect = false
                _state.update { s ->
                    val current = s.connectedEndpoints.toMutableSet()
                    if (current.none { it.endpointId == endpointId }) {
                        current.add(NearbyEndpointInfo(endpointId = endpointId, endpointName = remoteName))
                        didConnect = true
                    }
                    s.copy(connectedEndpoints = current, lastError = null)
                }
                if (didConnect) {
                    onEndpointConnected?.invoke(endpointId, remoteName)
                }
                removeDiscoveredEndpoint(endpointId)
            } else {
                clearEndpointTracking(endpointId)
                _state.update {
                    it.copy(lastError = NearbyError.Api("连接失败", result.status.statusCode).displayMessage)
                }
            }
            updateStatus()
        }

        override fun onDisconnected(endpointId: String) {
            clearEndpointTracking(endpointId)
            var didRemove = false
            _state.update { s ->
                val current = s.connectedEndpoints.toMutableSet()
                didRemove = current.removeAll { it.endpointId == endpointId }
                val bw = s.endpointBandwidth.toMutableMap()
                bw.remove(endpointId)
                s.copy(connectedEndpoints = current, endpointBandwidth = bw)
            }
            if (didRemove) {
                onEndpointDisconnected?.invoke(endpointId)
            }
            updateCurrentQuality()
            updateStatus()
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            _state.update { s ->
                val bw = s.endpointBandwidth.toMutableMap()
                bw[endpointId] = bandwidthInfo.quality
                val endpoints = s.connectedEndpoints.map {
                    if (it.endpointId == endpointId) it.copy(bandwidthQuality = bandwidthInfo.quality) else it
                }.toSet()
                s.copy(endpointBandwidth = bw, connectedEndpoints = endpoints)
            }
            onBandwidthChanged?.invoke(endpointId, bandwidthInfo.quality)
            updateCurrentQuality()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, discoveredInfo: DiscoveredEndpointInfo) {
            _state.update { s ->
                if (s.connectedEndpoints.any { it.endpointId == endpointId }) return@update s
                if (s.pendingConnections.any { it.endpointId == endpointId }) return@update s
                val waiting = synchronized(connectionStateLock) { awaitingConnectionResult.contains(endpointId) }
                if (waiting) return@update s

                val remoteName = discoveredInfo.endpointName.ifBlank { endpointId }
                if (isSelfEndpointName(remoteName)) return@update s
                endpointNames[endpointId] = remoteName

                val endpoints = s.discoveredEndpoints.toMutableList()
                val index = endpoints.indexOfFirst { it.endpointId == endpointId }
                val info = NearbyEndpointInfo(endpointId = endpointId, endpointName = remoteName)
                if (index >= 0) endpoints[index] = info else endpoints.add(info)
                s.copy(discoveredEndpoints = endpoints)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            removeDiscoveredEndpoint(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    onMessageReceived?.invoke(endpointId, bytes)
                }
                Payload.Type.FILE, Payload.Type.STREAM -> {
                    onPayloadReceived?.invoke(endpointId, payload.toFunLinkPayload())
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            onPayloadTransferUpdate?.invoke(endpointId, update.toTransferUpdate())
        }
    }

    // --- SDK operation wrappers ---

    fun startAdvertising(name: String, allowUpgrade: Boolean = true) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .setConnectionType(if (allowUpgrade) ConnectionType.DISRUPTIVE else ConnectionType.NON_DISRUPTIVE)
            .build()
        connectionsClient.startAdvertising(name, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { onAdvertisingStarted() }
            .addOnFailureListener { onOperationError("启动广播失败", it) }
    }

    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        onAdvertisingStopped()
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { onDiscoveryStarted() }
            .addOnFailureListener { onOperationError("扫描失败", it) }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        onDiscoveryStopped()
    }

    fun requestConnection(endpointId: String) {
        if (!prepareOutgoingConnection(endpointId)) return
        val allowUpgrade = _state.value.allowDisruptiveUpgrade
        val options = ConnectionOptions.Builder()
            .setConnectionType(if (allowUpgrade) ConnectionType.DISRUPTIVE else ConnectionType.NON_DISRUPTIVE)
            .build()
        connectionsClient.requestConnection(connectionRequestName(), endpointId, connectionLifecycleCallback, options)
            .addOnFailureListener { onOutgoingConnectionFailed(endpointId, it) }
    }

    fun acceptConnection(endpointId: String) {
        if (!prepareAcceptConnection(endpointId)) return
        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { onAcceptConnectionFailed(endpointId, it) }
    }

    fun rejectConnection(endpointId: String) {
        rejectPendingConnection(endpointId)
        connectionsClient.rejectConnection(endpointId)
            .addOnFailureListener { onOperationError("拒绝连接失败", it) }
    }

    fun stopAllEndpoints() {
        connectionsClient.stopAllEndpoints()
        resetConnections()
    }

    fun sendNearbyPayload(endpointIds: List<String>, payload: Payload) {
        connectionsClient.sendPayload(endpointIds, payload)
            .addOnFailureListener { onOperationError("发送失败", it) }
    }

    fun cancelNearbyPayload(payloadId: Long) {
        connectionsClient.cancelPayload(payloadId)
    }

    fun sendFunLinkPayload(
        endpointIds: List<String>,
        payload: FunLinkPayload,
        context: Context,
        pluginId: String
    ): Long? {
        return when (payload) {
            is FunLinkPayload.Bytes -> {
                val envelopeBytes = PluginWireEnvelope.bytes(pluginId = pluginId, data = payload.data)
                val nearbyPayload = Payload.fromBytes(envelopeBytes)
                dispatchNearbyPayload(endpointIds, nearbyPayload)
            }

            is FunLinkPayload.File -> {
                val uri = payload.uri ?: return null
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                val nearbyPayload = Payload.fromFile(pfd)
                val metadata = Payload.fromBytes(
                    PluginWireEnvelope.payloadMeta(
                        pluginId = pluginId,
                        payloadId = nearbyPayload.id,
                        payloadType = PluginWireEnvelope.PayloadType.FILE,
                        fileName = payload.fileName
                    )
                )
                dispatchNearbyPayload(endpointIds, metadata)
                dispatchNearbyPayload(endpointIds, nearbyPayload)
            }

            is FunLinkPayload.Stream -> {
                val stream = payload.inputStream ?: return null
                val nearbyPayload = Payload.fromStream(stream)
                val metadata = Payload.fromBytes(
                    PluginWireEnvelope.payloadMeta(
                        pluginId = pluginId,
                        payloadId = nearbyPayload.id,
                        payloadType = PluginWireEnvelope.PayloadType.STREAM
                    )
                )
                dispatchNearbyPayload(endpointIds, metadata)
                dispatchNearbyPayload(endpointIds, nearbyPayload)
            }
        }
    }

    private fun dispatchNearbyPayload(endpointIds: List<String>, payload: Payload): Long {
        sendNearbyPayload(endpointIds, payload)
        return payload.id
    }

    companion object {
        const val MAX_BYTES_SIZE = 32768
    }

    // --- State management ---

    fun setRole(role: ConnectionRole) {
        _state.update { it.copy(role = role) }
        updateStatus()
    }

    fun onAdvertisingStarted() {
        _state.update { it.copy(isAdvertising = true) }
        updateStatus()
    }

    fun onAdvertisingStopped() {
        _state.update { it.copy(isAdvertising = false) }
        updateStatus()
    }

    fun onDiscoveryStarted() {
        _state.update { it.copy(isDiscovering = true) }
        updateStatus()
    }

    fun onDiscoveryStopped() {
        _state.update { it.copy(isDiscovering = false, discoveredEndpoints = emptyList()) }
        updateStatus()
    }

    fun onOperationError(prefix: String, exception: Exception) {
        _state.update { it.copy(lastError = NearbyError.fromNearbyException(prefix, exception).displayMessage) }
        updateStatus()
    }

    fun onValidationError(message: String) {
        _state.update { it.copy(lastError = message) }
        updateStatus()
    }

    fun clearLastError() {
        _state.update { it.copy(lastError = null) }
        updateStatus()
    }

    fun setAllowDisruptiveUpgrade(allow: Boolean) {
        _state.update { it.copy(allowDisruptiveUpgrade = allow) }
    }

    fun connectionRequestName(): String = deviceId

    fun prepareOutgoingConnection(endpointId: String): Boolean {
        var rejected = false
        _state.update { s ->
            if (s.connectedEndpoints.any { it.endpointId == endpointId }) return@update s
            if (s.pendingConnections.any { it.endpointId == endpointId }) return@update s
            if (isSelfEndpointName(endpointNames[endpointId])) {
                rejected = true
                return@update s.copy(lastError = "已忽略自身设备连接请求")
            }
            s.copy(lastError = null)
        }
        if (rejected) {
            updateStatus()
            return false
        }
        val canRequest = synchronized(connectionStateLock) {
            if (outgoingRequests.contains(endpointId) || awaitingConnectionResult.contains(endpointId)) false
            else {
                outgoingRequests.add(endpointId)
                awaitingConnectionResult.add(endpointId)
                true
            }
        }
        if (!canRequest) return false
        updateStatus()
        return true
    }

    fun onOutgoingConnectionFailed(endpointId: String, exception: Exception) {
        synchronized(connectionStateLock) {
            outgoingRequests.remove(endpointId)
            awaitingConnectionResult.remove(endpointId)
        }
        _state.update { it.copy(lastError = NearbyError.fromNearbyException("发起连接失败", exception).displayMessage) }
        updateStatus()
    }

    fun prepareAcceptConnection(endpointId: String): Boolean {
        if (_state.value.pendingConnections.none { it.endpointId == endpointId }) return false
        removePendingConnection(endpointId)
        synchronized(connectionStateLock) { awaitingConnectionResult.add(endpointId) }
        _state.update { it.copy(lastError = null) }
        updateStatus()
        return true
    }

    fun onAcceptConnectionFailed(endpointId: String, exception: Exception) {
        synchronized(connectionStateLock) { awaitingConnectionResult.remove(endpointId) }
        _state.update { it.copy(lastError = NearbyError.fromNearbyException("接受连接失败", exception).displayMessage) }
        updateStatus()
    }

    fun rejectPendingConnection(endpointId: String) {
        removePendingConnection(endpointId)
        synchronized(connectionStateLock) {
            outgoingRequests.remove(endpointId)
            awaitingConnectionResult.remove(endpointId)
        }
        endpointNames.remove(endpointId)
        updateStatus()
    }

    fun resetConnections() {
        val disconnectedIds = _state.value.connectedEndpoints.map { it.endpointId }
        synchronized(connectionStateLock) {
            outgoingRequests.clear()
            awaitingConnectionResult.clear()
        }
        endpointNames.clear()
        _state.update { NearbySessionState(role = it.role) }
        disconnectedIds.forEach { onEndpointDisconnected?.invoke(it) }
    }

    // --- Private helpers ---

    private fun updateCurrentQuality() {
        _state.update { s ->
            val bandwidths = s.endpointBandwidth.values
            val quality = if (bandwidths.isEmpty()) 0 else bandwidths.minOf { it }
            s.copy(currentQuality = quality)
        }
    }

    private fun updateStatus() {
        _state.update { s ->
            val hasAwaiting = synchronized(connectionStateLock) { awaitingConnectionResult.isNotEmpty() }
            val newStatus = when {
                s.connectedEndpoints.isNotEmpty() -> ConnectionStatus.ACTIVE
                s.pendingConnections.isNotEmpty() || hasAwaiting -> ConnectionStatus.CONNECTING
                !s.lastError.isNullOrBlank() -> ConnectionStatus.ERROR
                s.isDiscovering -> ConnectionStatus.DISCOVERING
                s.isAdvertising -> ConnectionStatus.ADVERTISING
                else -> ConnectionStatus.IDLE
            }
            s.copy(status = newStatus)
        }
    }

    private fun addPendingConnection(endpointId: String, endpointName: String) {
        _state.update { s ->
            val current = s.pendingConnections.toMutableList()
            val index = current.indexOfFirst { it.endpointId == endpointId }
            val info = PendingConnectionInfo(endpointId = endpointId, endpointName = endpointName)
            if (index >= 0) current[index] = info else current.add(info)
            s.copy(pendingConnections = current)
        }
    }

    private fun removePendingConnection(endpointId: String) {
        _state.update { s ->
            if (s.pendingConnections.none { it.endpointId == endpointId }) return@update s
            s.copy(pendingConnections = s.pendingConnections.filterNot { it.endpointId == endpointId })
        }
    }

    private fun removeDiscoveredEndpoint(endpointId: String) {
        _state.update { s ->
            if (s.discoveredEndpoints.none { it.endpointId == endpointId }) return@update s
            s.copy(discoveredEndpoints = s.discoveredEndpoints.filterNot { it.endpointId == endpointId })
        }
    }

    private fun isSelfEndpointName(endpointName: String?): Boolean {
        return endpointName != null && endpointName == deviceId
    }

    private fun clearEndpointTracking(endpointId: String) {
        synchronized(connectionStateLock) {
            outgoingRequests.remove(endpointId)
            awaitingConnectionResult.remove(endpointId)
        }
        removePendingConnection(endpointId)
        removeDiscoveredEndpoint(endpointId)
        endpointNames.remove(endpointId)
    }
}
