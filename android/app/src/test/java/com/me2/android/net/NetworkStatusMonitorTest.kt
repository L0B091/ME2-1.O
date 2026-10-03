package com.me2.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import android.net.NetworkCapabilities
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowNetworkCapabilities
import com.me2.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NetworkStatusMonitorTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun labelReflectsState() {
        assertEquals(R.string.autosync_active, NetworkStatusMonitor.labelFor(true))
        assertEquals(R.string.autosync_offline, NetworkStatusMonitor.labelFor(false))
        assertEquals("AUTO-SYNC // OFFLINE", context.getString(R.string.autosync_offline))
    }

    @Test
    fun publishesOfflineOnLostAndActiveOnReconnect() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(cm)
        val network = cm.activeNetwork!!
        val states = mutableListOf<Boolean>()
        val monitor = NetworkStatusMonitor(context) { states += it }
        monitor.start()
        shadowOf(Looper.getMainLooper()).idle()
        val callback = shadow.networkCallbacks.single()

        shadow.setActiveNetworkInfo(null)
        callback.onLost(network)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, states.last())

        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        callback.onCapabilitiesChanged(network, caps)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, states.last())
        // Sin repetir estados iguales: cada transición se publica una sola vez.
        assertTrue(states.zipWithNext().none { (x, y) -> x == y })
        monitor.stop()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }
}
