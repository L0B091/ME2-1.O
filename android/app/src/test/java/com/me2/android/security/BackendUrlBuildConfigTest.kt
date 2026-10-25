package com.me2.android.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regresión 8143ba5: el APK debug apuntaba a http://10.0.2.2:3000 y el login fallaba en el celular. */
class BackendUrlBuildConfigTest {
    private val gradle = File("build.gradle.kts").readText()

    @Test fun debugUsaRenderPorDefectoYNoElEmulador() {
        assertTrue(gradle.contains("val defaultBackendUrl = \"https://me2-backend.onrender.com\""))
        assertTrue(gradle.contains("explicitBackendUrl.ifBlank { defaultBackendUrl }"))
        assertFalse(gradle.contains("ifBlank { \"http://10.0.2.2:3000\" }"))
    }

    @Test fun elBuildFallaSiLaUrlEsLocalOHttp() {
        assertTrue(gradle.contains("dependsOn(verificarGoogleClientId, verificarBackendUrl)"))
        assertTrue(gradle.contains("releaseBackendUrlProblem(debugBackendUrl)"))
    }

    @Test fun buildConfigGeneradoApuntaARender() {
        val bc = File("build/generated/source/buildConfig/debug/com/me2/android/BuildConfig.java")
        if (bc.exists()) assertTrue(bc.readText().contains("\"https://me2-backend.onrender.com\""))
    }
}
