package com.hlju.funlinkbluetooth.core.nearby.runtime

import android.content.Context
import com.hlju.funlinkbluetooth.core.model.NearbyEndpointInfo
import com.hlju.funlinkbluetooth.core.plugin.api.FunLinkPayload
import com.hlju.funlinkbluetooth.core.plugin.api.PluginHostBindings

fun NearbySessionController.createHostBindings(
    context: Context,
    pluginId: String,
    onPayloadSent: (payloadId: Long, pluginId: String, endpointIds: List<String>) -> Unit
): PluginHostBindings {
    val controller = this
    return object : PluginHostBindings {
        override val connectedEndpointIds: List<String>
            get() = controller.state.value.connectedEndpoints.map { it.endpointId }
        override val connectedEndpoints: List<NearbyEndpointInfo>
            get() = controller.state.value.connectedEndpoints.toList()
        override val isConnected: Boolean
            get() = controller.state.value.connectedEndpoints.isNotEmpty()
        override val maxBytesSize: Int
            get() = NearbySessionController.MAX_BYTES_SIZE

        override fun sendPayload(endpointIds: List<String>, payload: FunLinkPayload): Long? {
            return controller.sendFunLinkPayload(
                endpointIds = endpointIds,
                payload = payload,
                context = context,
                pluginId = pluginId
            )?.also { payloadId ->
                onPayloadSent(payloadId, pluginId, endpointIds)
            }
        }

        override fun cancelPayload(payloadId: Long) {
            controller.cancelNearbyPayload(payloadId)
        }
    }
}
