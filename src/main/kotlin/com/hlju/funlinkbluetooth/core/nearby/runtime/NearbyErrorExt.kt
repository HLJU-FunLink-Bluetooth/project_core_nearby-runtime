package com.hlju.funlinkbluetooth.core.nearby.runtime

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.hlju.funlinkbluetooth.core.model.NearbyError

/**
 * SDK-aware version of [NearbyError.fromException].
 * Handles Google Play Services and Nearby Connections specific exception types.
 */
fun NearbyError.Companion.fromNearbyException(prefix: String, exception: Exception): NearbyError {
    return when (exception) {
        is ApiException -> {
            val message = ConnectionsStatusCodes.getStatusCodeString(exception.statusCode)
            NearbyError.Api("$prefix：$message", exception.statusCode)
        }
        is SecurityException -> {
            NearbyError.Auth(exception.localizedMessage)
        }
        else -> {
            val detail = exception.localizedMessage ?: exception.message ?: "未知错误"
            NearbyError.Unknown(prefix, detail)
        }
    }
}
