package com.hlju.funlinkbluetooth.core.nearby.runtime

import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.hlju.funlinkbluetooth.core.plugin.api.FunLinkPayload
import com.hlju.funlinkbluetooth.core.plugin.api.TransferStatus
import com.hlju.funlinkbluetooth.core.plugin.api.TransferUpdate

fun Payload.toFunLinkPayload(): FunLinkPayload = when (type) {
    Payload.Type.BYTES -> FunLinkPayload.Bytes(id = id, data = asBytes() ?: ByteArray(0))
    Payload.Type.FILE -> FunLinkPayload.File(id = id, uri = asFile()?.asUri(), fileName = null)
    Payload.Type.STREAM -> FunLinkPayload.Stream(id = id, inputStream = asStream()?.asInputStream())
    else -> FunLinkPayload.Bytes(id = id, data = ByteArray(0))
}

fun PayloadTransferUpdate.toTransferUpdate(): TransferUpdate {
    val transferStatus = when (status) {
        PayloadTransferUpdate.Status.IN_PROGRESS -> TransferStatus.IN_PROGRESS
        PayloadTransferUpdate.Status.SUCCESS -> TransferStatus.SUCCESS
        PayloadTransferUpdate.Status.FAILURE -> TransferStatus.FAILURE
        PayloadTransferUpdate.Status.CANCELED -> TransferStatus.CANCELED
        else -> TransferStatus.FAILURE
    }
    return TransferUpdate(
        payloadId = payloadId,
        status = transferStatus,
        bytesTransferred = bytesTransferred,
        totalBytes = totalBytes
    )
}
