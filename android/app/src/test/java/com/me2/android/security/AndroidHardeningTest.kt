package com.me2.android.security

import com.me2.android.BuildConfig
import com.me2.android.net.BackendUrlPolicy
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Hardening Android: manifest, network security config, reglas de backup, demo solo debug, URLs de medios. */
class AndroidHardeningTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement
    private fun Element.children(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    @Test fun soloLoginExportadaYSinBackupNiCleartextEnElManifest() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val app = manifest.children("application").single()
        assertEquals("false", app.getAttributeNS(ns, "allowBackup"))
        assertEquals("false", app.getAttributeNS(ns, "usesCleartextTraffic"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ns, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(ns, "fullBackupContent"))
        val componentes = listOf("activity", "receiver", "service", "provider").flatMap { app.children(it) }
        assertTrue(componentes.isNotEmpty())
        componentes.forEach { assertTrue("exported explícito en ${it.getAttributeNS(ns, "name")}", it.hasAttributeNS(ns, "exported")) }
        val exportados = componentes.filter { it.getAttributeNS(ns, "exported") == "true" }.map { it.getAttributeNS(ns, "name") }
        // Exportadas: el login (launcher) y el trampolín sin UI de la vuelta de Mercado Pago (me2://pago).
        // MainActivity sigue sin exportarse.
        assertEquals(listOf(".payments.PaymentReturnActivity", ".LoginActivity"), exportados)
    }

    @Test fun vueltaDeMercadoPagoSoloAceptaMe2PagoNavegableYSinHistorial() {
        val app = xml("src/main/AndroidManifest.xml").children("application").single()
        val trampolin = app.children("activity").single { it.getAttributeNS(ns, "name") == ".payments.PaymentReturnActivity" }
        assertEquals("true", trampolin.getAttributeNS(ns, "noHistory"))
        assertEquals("true", trampolin.getAttributeNS(ns, "excludeFromRecents"))
        val filtro = trampolin.children("intent-filter").single()
        assertEquals(listOf("android.intent.action.VIEW"), filtro.children("action").map { it.getAttributeNS(ns, "name") })
        assertEquals(setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"), filtro.children("category").map { it.getAttributeNS(ns, "name") }.toSet())
        val data = filtro.children("data").single()
        assertEquals("me2", data.getAttributeNS(ns, "scheme"))
        assertEquals("pago", data.getAttributeNS(ns, "host"))
        val main = app.children("activity").single { it.getAttributeNS(ns, "name") == ".MainActivity" }
        assertEquals("false", main.getAttributeNS(ns, "exported"))
    }

    @Test fun permisosDeAlarmasYNotificacionesParaAndroid12a14() {
        val permisos = xml("src/main/AndroidManifest.xml").children("uses-permission").associate { it.getAttributeNS(ns, "name") to it.getAttributeNS(ns, "maxSdkVersion") }
        assertTrue("POST_NOTIFICATIONS (Android 13+)", "android.permission.POST_NOTIFICATIONS" in permisos)
        assertTrue("USE_EXACT_ALARM: despertador exacto en Android 13/14 sin depender de Ajustes", "android.permission.USE_EXACT_ALARM" in permisos)
        assertEquals("SCHEDULE_EXACT_ALARM solo hasta Android 12L", "32", permisos["android.permission.SCHEDULE_EXACT_ALARM"])
        assertFalse("sin pantalla completa (restringida en Android 14)", "android.permission.USE_FULL_SCREEN_INTENT" in permisos)
    }

    @Test fun releaseSinTraficoEnClaroYDebugAcotadoAlBackendLocal() {
        val release = xml("src/main/res/xml/network_security_config.xml")
        assertEquals("false", release.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        assertTrue("release no tiene excepciones cleartext", release.children("domain-config").none { it.getAttribute("cleartextTrafficPermitted") == "true" })
        assertTrue(release.children("certificates").none { it.getAttribute("src") == "user" })
        val debug = xml("src/debug/res/xml/network_security_config.xml")
        val dominios = debug.children("domain").map { it.textContent.trim() }.toSet()
        assertEquals(setOf("10.0.2.2", "localhost", "127.0.0.1"), dominios)
    }

    @Test fun backupYTransferenciaExcluyenBasesDePreferenciasYArchivos() {
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        for (seccion in listOf("cloud-backup", "device-transfer")) {
            val excluidos = rules.children(seccion).single().children("exclude").map { it.getAttribute("domain") }.toSet()
            assertTrue("$seccion: $excluidos", excluidos.containsAll(setOf("database", "sharedpref", "file", "root")))
        }
        val legacy = xml("src/main/res/xml/backup_rules.xml").children("exclude").map { it.getAttribute("domain") }.toSet()
        assertTrue(legacy.containsAll(setOf("database", "sharedpref", "file", "root")))
    }

    @Test fun demoSoloEnDebugYSinClavesDeLlmEnElApk() {
        val gradle = File("build.gradle.kts").readText()
        val release = gradle.substringAfter("release {").substringBefore("}")
        assertTrue(release.contains("\"DEMO_LOGIN_ENABLED\", \"false\""))
        assertFalse(gradle.contains("OPENROUTER_API_KEY"))
        assertTrue("este test corre sobre debug", BuildConfig.DEBUG)
        // Sin modo demo: apagado también en debug (solo login con Google).
        assertTrue(gradle.substringAfter("debug {").substringBefore("}").contains("\"DEMO_LOGIN_ENABLED\", \"false\""))
        assertFalse(BuildConfig.DEMO_LOGIN_ENABLED)
    }

    @Test fun urlsDeMediosSoloDelBackendYTokenSoloASuOrigen() {
        val base = "https://api.me2.app"
        assertEquals("https://api.me2.app/api/media/normal/x", BackendUrlPolicy.resolve(base, "/api/media/normal/x"))
        assertEquals("https://api.me2.app/api/media/normal/x", BackendUrlPolicy.resolve(base, "https://api.me2.app/api/media/normal/x"))
        assertNull(BackendUrlPolicy.resolve(base, "https://evil.example/robar"))
        assertNull(BackendUrlPolicy.resolve(base, "//evil.example/robar"))
        assertNull(BackendUrlPolicy.resolve(base, "http://api.me2.app/api/media/x"))
        assertNull(BackendUrlPolicy.resolve(base, "/api/../../etc"))
        assertTrue(BackendUrlPolicy.mayAttachToken(base, "https://api.me2.app:443/api/media/normal/x"))
        assertFalse(BackendUrlPolicy.mayAttachToken(base, "https://api.me2.app.evil.example/x"))
    }
}
