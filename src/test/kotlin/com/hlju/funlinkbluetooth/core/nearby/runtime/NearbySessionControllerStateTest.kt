package com.hlju.funlinkbluetooth.core.nearby.runtime

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NearbySessionControllerStateTest {

    private lateinit var context: Context
    private lateinit var connectionsClient: ConnectionsClient

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        connectionsClient = mockk(relaxed = true)
        mockkStatic(Nearby::class)
        every { Nearby.getConnectionsClient(any()) } returns connectionsClient
    }

    @After
    fun tearDown() {
        unmockkStatic(Nearby::class)
    }

    @Test
    fun advertisingStart_success_clearsStartingFlag() {
        val controller = NearbySessionController(context)

        controller.onAdvertisingStartRequested()
        assertTrue(controller.state.value.isStartingAdvertising)

        controller.onAdvertisingStarted()

        assertFalse(controller.state.value.isStartingAdvertising)
        assertTrue(controller.state.value.isAdvertising)
        assertEquals(com.hlju.funlinkbluetooth.core.model.ConnectionStatus.ADVERTISING, controller.state.value.status)
    }

    @Test
    fun discoveryStart_failure_clearsStartingFlag() {
        val controller = NearbySessionController(context)

        controller.onDiscoveryStartRequested()
        assertTrue(controller.state.value.isStartingDiscovery)

        controller.onOperationError("扫描失败", RuntimeException("boom"))

        assertFalse(controller.state.value.isStartingDiscovery)
        assertEquals(com.hlju.funlinkbluetooth.core.model.ConnectionStatus.ERROR, controller.state.value.status)
        assertTrue(controller.state.value.lastError.orEmpty().contains("扫描失败"))
    }

    @Test
    fun advertisingStart_stoppedState_clearsStartingFlag() {
        val controller = NearbySessionController(context)

        controller.onAdvertisingStartRequested()
        controller.onAdvertisingStopped()

        assertFalse(controller.state.value.isStartingAdvertising)
        assertFalse(controller.state.value.isAdvertising)
    }

    @Test
    fun resetConnections_clearsStartingFlags_andKeepsRole() {
        val controller = NearbySessionController(context)
        controller.setRole(com.hlju.funlinkbluetooth.core.model.ConnectionRole.CLIENT)
        controller.onAdvertisingStartRequested()
        controller.onDiscoveryStartRequested()

        controller.resetConnections()

        assertFalse(controller.state.value.isStartingAdvertising)
        assertFalse(controller.state.value.isStartingDiscovery)
        assertEquals(com.hlju.funlinkbluetooth.core.model.ConnectionRole.CLIENT, controller.state.value.role)
    }
}
