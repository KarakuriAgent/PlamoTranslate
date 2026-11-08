plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.plamotranslate.ocr"
    compileSdk = 36

    defaultConfig {
        minSdk = 33  // 性能ヒント（APerformanceHint）がAPI 33から
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    // モデル同梱版（bundled）。端末がオフラインでも初回から動く（docs/05_画面翻訳.md）
    // 英字専用（英語の漫画の手書き風の大文字に強い。2026-11-06）
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.chinese)
    // 縦書きを読める漫画モデル（PP-OCRv6 manga）を動かす。2026-11-05作者承認（大きな依存）
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
}

// JDK 21で動かし、Java 17相当のバイトコードを出す（JDK 17は入れていない）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
