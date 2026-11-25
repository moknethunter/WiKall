plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mokasas.wikall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mokasas.wikall"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 5
        versionName = "0.4.0-rc1"
        val firebase = mapOf(
            "google_app_id" to "WI_FCM_APP_ID",
            "gcm_defaultSenderId" to "WI_FCM_SENDER_ID",
            "project_id" to "WI_FCM_PROJECT",
            "google_api_key" to "WI_FCM_API_KEY",
        ).mapValues { (_, name) -> System.getenv(name).orEmpty() }
        if (firebase.values.all(String::isNotBlank)) {
            firebase.forEach { (name, value) -> resValue("string", name, value) }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    System.getenv("WIKALL_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { key ->
        signingConfigs.getByName("debug") { storeFile = file(key) }
    }
}

kotlin.jvmToolchain(17)

kapt {
    correctErrorTypes = true
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.hilt.android)
    implementation(libs.webrtc)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    kapt(libs.room.compiler)
    kapt(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
