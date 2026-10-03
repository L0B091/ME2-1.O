package com.me2.android.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.me2.android.data.LocalLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Cualquier respuesta al permiso (permitir, denegar, revocar después) no crashea y el chat sigue con una ubicación válida o ninguna. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DeviceLocationPermissionTest {
    private val app = RuntimeEnvironment.getApplication()
    private val chatCity = LocalLocation("Rosario", -32.95, -60.66, null)

    private fun fakeFix(lat: Double, lon: Double) {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = Location(LocationManager.NETWORK_PROVIDER).apply { latitude = lat; longitude = lon; time = System.currentTimeMillis() }
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(lm).simulateLocation(loc)
    }

    @Test fun denegado_noCrashea_yUsaLaCiudadDelChat() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val store = DeviceLocationStore(app)
        assertNull(DeviceLocationProvider(app).refreshIfNeeded(store))
        assertNull(store.load())
        assertEquals("Rosario", DeviceLocationPolicy.effective(store.load(), chatCity, System.currentTimeMillis())!!.city)
        assertNull(DeviceLocationPolicy.effective(store.load(), null, System.currentTimeMillis()))
    }

    @Test fun permitido_guardaElFix_yGanaSobreElChat() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        fakeFix(-33.33, -60.22)
        val store = DeviceLocationStore(app)
        val fix = DeviceLocationProvider(app).refreshIfNeeded(store)
        assertNotNull(fix)
        assertEquals(-33.33, DeviceLocationPolicy.effective(store.load(), chatCity, System.currentTimeMillis())!!.lat!!, 1e-6)
    }

    @Test fun revocadoDespues_noCrashea_yElUltimoFixSigueSiendoValido() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        fakeFix(-33.33, -60.22)
        val store = DeviceLocationStore(app)
        DeviceLocationProvider(app).refreshIfNeeded(store)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertNull(DeviceLocationProvider(app).refreshIfNeeded(store, System.currentTimeMillis() + 60 * 60_000L))
        assertNotNull(store.load())
    }
}
