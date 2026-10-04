fun String.gradleQuoted(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

val backendBaseUrl = firstNonBlank(
    System.getenv("ME2_BACKEND_URL"),
    findProperty("ME2_BACKEND_BASE_URL") as String?,
    System.getenv("ME2_ANDROID_BACKEND_BASE_URL"),
    // Emulator loopback to host machine; override with ME2_BACKEND_URL for physical device LAN IP.
    "http://10.0.2.2:3000"
)

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
        buildConfigField("String", "BACKEND_BASE_URL", backendBaseUrl.gradleQuoted())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", googleWebClientId.gradleQuoted())
        buildConfigField("boolean", "ENABLE_GOOGLE_AUTH", enableGoogleAuth.toString())
        buildConfigField("boolean", "DEMO_LOGIN_ENABLED", "false")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEMO_LOGIN_ENABLED", "false")
        }
        release {
            buildConfigField("boolean", "DEMO_LOGIN_ENABLED", "false")
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
