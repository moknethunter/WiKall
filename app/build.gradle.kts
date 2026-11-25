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
        versionCode = 3
        versionName = "0.3.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // These libraries embed equivalent upstream license metadata.
    packaging.resources.excludes += setOf(
        "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE", "META-INF/LICENSE.txt",
        "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/INDEX.LIST",
    )
    packaging.resources.merges += "META-INF/io.netty.versions.properties"

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
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.okhttp)
    kapt(libs.room.compiler)
    kapt(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
