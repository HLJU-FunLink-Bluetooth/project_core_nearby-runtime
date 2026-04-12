package com.hlju.funlinkbluetooth.core.nearby.runtime

import com.google.android.gms.nearby.connection.Payload
import com.hlju.funlinkbluetooth.core.plugin.api.FunLinkPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class SdkConversionsTest {

    @Test
    fun streamPayloadConversion_keepsReadableInputStream() {
        val source = "stream-audio".toByteArray()
        val payload = Payload.fromStream(ByteArrayInputStream(source))

        val converted = payload.toFunLinkPayload()

        assertTrue(converted is FunLinkPayload.Stream)
        val inputStream = (converted as FunLinkPayload.Stream).inputStream
        assertNotNull(inputStream)
        assertArrayEquals(source, inputStream!!.readBytes())
    }
}
