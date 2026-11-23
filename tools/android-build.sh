#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
lib="${WIKALL_GRADLE_LIB:?Run the Gradle task}"
jvm="${JAVA_HOME:+$JAVA_HOME/bin/}java"
keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
package=app/src/main/kotlin/com/mokasas/wikall
stdlib="$lib/kotlin-stdlib-2.0.20.jar"
json="$lib/kotlinx-serialization-json-jvm-1.6.2.jar"
serialization="$lib/kotlinx-serialization-core-jvm-1.6.2.jar"
runtime="$stdlib:$json:$serialization"
compile() {
    "$jvm" -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -no-reflect -jvm-target 1.8 "$@"
}
if [[ "$1" == test ]]; then
    mkdir -p build/jvm
    junit="$lib/junit-4.13.2.jar:$lib/hamcrest-core-1.3.jar"
    compile -classpath "$runtime:$junit" -d build/jvm/tests.jar \
        "$package"/{Profile,PinnedTls,Signaling,CallState}.kt \
        app/src/test/kotlin/com/mokasas/wikall/ContractTest.kt
    "$keytool" -genkeypair -noprompt -alias fixture -dname "CN=WiKall Test" \
        -keyalg RSA -keysize 2048 -validity 2 -storetype PKCS12 \
        -keystore build/jvm/fixture.p12 -storepass fixture-only >/dev/null 2>&1 || \
        "$keytool" -list -keystore build/jvm/fixture.p12 -storepass fixture-only >/dev/null
    "$jvm" -cp "$runtime:$junit:build/jvm/tests.jar" org.junit.runner.JUnitCore com.mokasas.wikall.ContractTest
    exit
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
test -n "$sdk" || { echo "Set ANDROID_HOME to the Android SDK path." >&2; exit 1; }
platform="$sdk/platforms/android-35/android.jar"
tools="$sdk/build-tools/35.0.0"
out="$PWD/build/android"
mkdir -p "$out/dex" "$PWD/build/outputs/apk"
"$tools/aapt2" compile --dir app/src/main/res -o "$out/res.zip"
"$tools/aapt2" link -I "$platform" --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 35 --version-code 1 --version-name 0.1.0 \
    -o "$out/resources.apk" "$out/res.zip"
compile -classpath "$platform:$runtime" -d "$out/app.jar" "$package"/*.kt
"$tools/d8" --release --lib "$platform" --min-api 29 --output "$out/dex" \
    "$out/app.jar" "$stdlib" "$json" "$serialization"
cp "$out/resources.apk" "$out/unaligned.apk"
zip -q -j "$out/unaligned.apk" "$out/dex/"classes*.dex
"$tools/zipalign" -f -p 4 "$out/unaligned.apk" "$out/aligned.apk"
keystore="${WIKALL_DEBUG_KEYSTORE:-$PWD/build/wikall-debug.p12}"
if [[ ! -f "$keystore" ]]; then
    "$keytool" -genkeypair -noprompt -alias androiddebugkey -dname "CN=Android Debug,O=WiKall,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12 \
        -keystore "$keystore" -storepass android
fi
apk="$PWD/build/outputs/apk/WiKall-Debug.apk"
"$jvm" -jar "$tools/lib/apksigner.jar" sign --ks "$keystore" --ks-key-alias androiddebugkey --ks-pass pass:android \
    --out "$apk" "$out/aligned.apk"
"$jvm" -jar "$tools/lib/apksigner.jar" verify --verbose "$apk"
"$tools/zipalign" -c 4 "$apk"
