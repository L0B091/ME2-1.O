package com.me2.android.location

import com.me2.android.data.LocalLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceLocationPolicyTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val ba = DeviceFix(-34.6037, -58.3816, "Buenos Aires", "America/Argentina/Buenos_Aires", now - 10 * min)
    private val chatRosario = LocalLocation("Rosario", -32.95, -60.66, "America/Argentina/Cordoba")

    @Test fun throttle30MinOr5Km() {
        assertTrue(DeviceLocationPolicy.shouldUpdate(null, -34.6, -58.38, now))
        // 10 min y misma zona → no refresca.
        assertFalse(DeviceLocationPolicy.shouldUpdate(ba, -34.61, -58.39, now))
        // Más de 30 min → refresca.
        assertTrue(DeviceLocationPolicy.shouldUpdate(ba.copy(fixedAt = now - 31 * min), -34.6037, -58.3816, now))
        // Se movió > 5 km (La Plata ~ 50 km) → refresca aunque sea reciente.
        assertTrue(DeviceLocationPolicy.shouldUpdate(ba, -34.92, -57.95, now))
        // Reloj hacia atrás: refresca.
        assertTrue(DeviceLocationPolicy.shouldUpdate(ba.copy(fixedAt = now + 5 * min), -34.6037, -58.3816, now))
    }

    @Test fun telefonoFrescoGanaSobreCiudadDelChat() {
        assertEquals("Buenos Aires", DeviceLocationPolicy.effective(ba, chatRosario, now)!!.city)
        assertEquals(-34.6037, DeviceLocationPolicy.effective(ba, chatRosario, now)!!.lat!!, 1e-6)
    }

    @Test fun telefonoViejoCedeAnteElChatPeroSirveSiNoHayOtra() {
        val viejo = ba.copy(fixedAt = now - 7 * 60 * min)
        assertEquals("Rosario", DeviceLocationPolicy.effective(viejo, chatRosario, now)!!.city)
        assertEquals("Buenos Aires", DeviceLocationPolicy.effective(viejo, null, now)!!.city)
        assertEquals("Rosario", DeviceLocationPolicy.effective(null, chatRosario, now)!!.city)
        assertNull(DeviceLocationPolicy.effective(null, null, now))
    }

    @Test fun distanciaYSerializacion() {
        assertEquals(0.0, DeviceLocationPolicy.distanceKm(1.0, 1.0, 1.0, 1.0), 1e-9)
        assertEquals(278.0, DeviceLocationPolicy.distanceKm(-34.6037, -58.3816, -32.95, -60.66), 10.0)
        assertEquals(ba, DeviceFix.fromJson(ba.toJson()))
        assertNull(DeviceFix.fromJson(org.json.JSONObject().put("lat", 200).put("lon", 0)))
    }
}
