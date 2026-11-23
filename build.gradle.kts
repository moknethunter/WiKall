val distribution = gradle.gradleHomeDir ?: error("Gradle distribution directory is unavailable")

tasks.register<Exec>("assembleDebug") {
    group = "build"
    description = "Compile, align, sign, and verify the Android test APK."
    environment("WIKALL_GRADLE_LIB", distribution.resolve("lib"))
    commandLine("bash", "tools/android-build.sh", "apk")
}

tasks.register<Exec>("test") {
    group = "verification"
    description = "Run JVM contract tests, including a real loopback TLS session."
    environment("WIKALL_GRADLE_LIB", distribution.resolve("lib"))
    commandLine("bash", "tools/android-build.sh", "test")
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
