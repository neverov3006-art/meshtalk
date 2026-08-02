plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.meshtalk.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meshtalk.app"
        minSdk = 26 // Nearby Connections + BLE mesh require reasonably modern APIs
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // Core Android extensions (FileProvider, ContextCompat, etc.)
    implementation("androidx.core:core-ktx:1.13.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Nearby Connections (BT + WiFi Direct abstraction, offline P2P + mesh-friendly)
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // Location (for geotags)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Local persistence for message store / mesh relay cache
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background relay / retry work
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Serialization for wire protocol
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Internet transport: WebSocket client to the relay server
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Security: Android Keystore-backed crypto helpers
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR code: generate our own invite code
    implementation("com.google.zxing:core:3.5.3")

    // QR code: scan a friend's invite code
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.compose.ui:ui-viewbinding")

    testImplementation("junit:junit:4.13.2")
}
