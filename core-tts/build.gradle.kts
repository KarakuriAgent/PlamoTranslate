plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.plamotranslate.tts"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // sherpa-onnx（読み上げ。音声認識と同じAAR）。ONNX Runtimeを内部に静的に組み込んだ版で、漫画用OCRのONNX Runtimeと
    // ライブラリ名がぶつからない。AARは tools/get-sherpa-onnx.ps1 で取得する（Gitに入れない）。2026-11-06作者承認（大きな依存）。
    // ライブラリモジュールはローカルAARを同梱できないので、ここではコンパイルだけに使い、appで同梱する
    compileOnly(files(rootProject.file("third_party/sherpa-onnx/sherpa-onnx-static-link-onnxruntime-${libs.versions.sherpa.onnx.get()}.aar")))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

// JDK 21で動かし、Java 17相当のバイトコードを出す（JDK 17は入れていない）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
