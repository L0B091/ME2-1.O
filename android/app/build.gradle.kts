import java.net.URI
import java.util.Properties

fun String.gradleQuoted(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

// local.properties (gitignored) también puede definir la URL del backend.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

// URL del backend: SOLO desde configuración de build (nunca hardcodeada, nunca un túnel).
// Orden: env ME2_BACKEND_URL → -PME2_BACKEND_URL / gradle.properties → ME2_BACKEND_BASE_URL (gradle/local.properties)
//        → local.properties ME2_BACKEND_URL → env ME2_ANDROID_BACKEND_BASE_URL.
val explicitBackendUrl = firstNonBlank(
    System.getenv("ME2_BACKEND_URL"),
    findProperty("ME2_BACKEND_URL") as String?,
    findProperty("ME2_BACKEND_BASE_URL") as String?,
    localProps.getProperty("ME2_BACKEND_URL"),
    localProps.getProperty("ME2_BACKEND_BASE_URL"),
    System.getenv("ME2_ANDROID_BACKEND_BASE_URL")
).trim().trimEnd('/')

// SOLO debug: si no hay URL, loopback del emulador al host (comportamiento previo). Release NO tiene default.
val debugBackendUrl = explicitBackendUrl.ifBlank { "http://10.0.2.2:3000" }

/** Motivo por el que la URL no sirve para release (null = OK). Release no permite cleartext ni túneles efímeros. */
fun releaseBackendUrlProblem(url: String): String? {
    if (url.isBlank()) return "falta ME2_BACKEND_URL"
    val uri = runCatching { URI(url) }.getOrNull() ?: return "URL inválida: $url"
    val host = uri.host?.lowercase().orEmpty()
    return when {
        uri.scheme?.lowercase() != "https" -> "release exige https (sin cleartext): $url"
        host.isBlank() -> "URL sin host: $url"
        host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1" -> "host local/emulador no válido en release: $url"
        host.endsWith("trycloudflare.com") -> "túnel efímero de Cloudflare no permitido en release: $url"
        else -> null
    }
}
val releaseBackendUrlError = releaseBackendUrlProblem(explicitBackendUrl)

val googleWebClientId = firstNonBlank(
    findProperty("ME2_GOOGLE_WEB_CLIENT_ID") as String?,
    System.getenv("ME2_ANDROID_GOOGLE_WEB_CLIENT_ID")
)

// Product path: Google Sign-In is the only login. Default ON.
val enableGoogleAuth =
    firstNonBlank(
        findProperty("ME2_ENABLE_GOOGLE_AUTH") as String?,
        System.getenv("ME2_ANDROID_ENABLE_GOOGLE_AUTH"),
        "true"
    ).toBoolean()

// TODO(mercado-pago): public key for future native SDK; empty = safe stub (checkout via backend).
val mercadoPagoPublicKey = firstNonBlank(
    findProperty("ME2_MERCADO_PAGO_PUBLIC_KEY") as String?,
    System.getenv("ME2_ANDROID_MERCADO_PAGO_PUBLIC_KEY"),
    findProperty("ME2_MP_PUBLIC_KEY") as String?,
    System.getenv("ME2_ANDROID_MP_PUBLIC_KEY")
)

val mercadoPagoUrl = firstNonBlank(
    findProperty("ME2_MERCADO_PAGO_URL") as String?,
    System.getenv("ME2_ANDROID_MERCADO_PAGO_URL"),
    "https://www.mercadopago.com.ar/"
)

// LLM keys (OpenRouter/Groq/Dolphin) viven SOLO en el backend: nunca se compilan en el APK.

// Login demo ("Ver UI (demo)") apagado en todos los builds: el acceso es solo con Google. Una sesión demo guardada
// por versiones anteriores se borra al abrir el login (LoginActivity.openSessionStorage).

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.me2.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.me2.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MERCADO_PAGO_URL", mercadoPagoUrl.gradleQuoted())
        buildConfigField("String", "MERCADO_PAGO_PUBLIC_KEY", mercadoPagoPublicKey.gradleQuoted())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", googleWebClientId.gradleQuoted())
        buildConfigField("boolean", "ENABLE_GOOGLE_AUTH", enableGoogleAuth.toString())
        buildConfigField("boolean", "DEMO_LOGIN_ENABLED", "false")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEMO_LOGIN_ENABLED", "false")
            buildConfigField("String", "BACKEND_BASE_URL", debugBackendUrl.gradleQuoted())
        }
        release {
            buildConfigField("boolean", "DEMO_LOGIN_ENABLED", "false")
            // Sin default: la validación de abajo corta el empaquetado si falta o es inválida.
            buildConfigField("String", "BACKEND_BASE_URL", explicitBackendUrl.gradleQuoted())
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Falla de build clara (no silenciosa) si se empaqueta un release sin URL propia del backend.
val verificarBackendUrlRelease = tasks.register("verificarBackendUrlRelease") {
    group = "verification"
    description = "Exige ME2_BACKEND_URL https propia (sin 10.0.2.2 ni túnel) para builds release."
    val error = releaseBackendUrlError
    doLast {
        if (error != null) {
            throw GradleException(
                "ME2 release: $error. Definí ME2_BACKEND_URL=https://tu-servidor (env, -P, gradle.properties " +
                    "o android/local.properties). Ver docs/DEPLOY_PENDIENTES.md."
            )
        }
    }
}
// Si se pidió empaquetar release (assembleRelease, bundleRelease, assemble, build…) se valida ANTES de compilar;
// las tareas de empaquetado también dependen de la validación como red de seguridad. Los tests unitarios no se ven afectados.
val pideReleaseEmpaquetado = gradle.startParameter.taskNames.any {
    val n = it.substringAfterLast(':')
    n == "assemble" || n == "build" || n == "bundle" || Regex("(assemble|bundle|package|install)Release.*").matches(n)
}
tasks.configureEach {
    val esEmpaquetado = name in setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle", "installRelease")
    if (esEmpaquetado || (pideReleaseEmpaquetado && name == "preReleaseBuild")) {
        dependsOn(verificarBackendUrlRelease)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    kapt("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.work:work-testing:2.9.1")
}
