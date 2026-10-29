import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing is OPTIONAL and driven entirely by environment/keystore.properties, so the
 * repository stays free of secrets:
 *
 *   RELEASE_KEYSTORE_FILE    path to the .jks file
 *   RELEASE_STORE_PASSWORD   keystore password
 *   RELEASE_KEY_ALIAS        key alias
 *   RELEASE_KEY_PASSWORD     key password
 *
 * When they are absent (or the keystore file is missing) the release variant falls back to the
 * debug signing config so the build never breaks. A stable signing key is what allows an updated
 * APK to install over the previous one — with the ephemeral CI debug keystore every build had a
 * different signature, so updating required an uninstall and silently wiped the stored API keys.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(key: String, legacyKey: String): String? =
    (keystoreProperties.getProperty(key)
        ?: keystoreProperties.getProperty(legacyKey)
        ?: System.getenv(key))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("RELEASE_KEYSTORE_FILE", "storeFile")?.let { file(it) }
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")

val hasReleaseSigning = releaseStoreFile?.exists() == true &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.copilot.earbud"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.copilot.earbud"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Shrinking stays OFF on purpose: this app relies on reflection-free but dynamic JSON
            // parsing and TTS/ONNX callbacks; enabling R8 here would need a full device regression
            // pass, which has not been done.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android & Lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose (Material 3)
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Networking: Retrofit & OkHttp (Multipart & Streaming support)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // ONNX Runtime Mobile for Silero VAD (with built-in acoustic energy fallback if model asset omitted)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // Encrypted SharedPreferences for API Keys
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Unit tests (JVM only - no device required)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
