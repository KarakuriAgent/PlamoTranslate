import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 配布用（リリース版）の署名鍵。鍵とパスワードはGitに入れず、リポジトリの外の安全な場所に置く
 * （docs/06_開発環境.md「配布用のAPK」）。次の順に探し、どちらも無ければリリース版は署名しない。
 *
 * 1. 環境変数 `PLAMO_KEYSTORE_FILE`（`.jks` のパス）・`PLAMO_KEYSTORE_PASSWORD`・`PLAMO_KEY_ALIAS`・`PLAMO_KEY_PASSWORD`。
 *    GitHub ActionsでSecretsから渡す（docs/06_開発環境.md「CI」）。`PLAMO_KEYSTORE_FILE` があれば残りも必須
 * 2. 各PCの `local.properties` の `plamo.keystore` に書いた `keystore.properties`。
 *    `storeFile` は相対パスなら `keystore.properties` のフォルダーから探す
 */
class ReleaseKey(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun releaseKeyFromEnv(): ReleaseKey? {
    val storeFile = providers.environmentVariable("PLAMO_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() } ?: return null
    fun required(name: String): String = providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("PLAMO_KEYSTORE_FILE があるのに $name がありません（署名の環境変数は4つそろえる）")
    val file = File(storeFile)
    if (!file.isFile) throw GradleException("PLAMO_KEYSTORE_FILE の鍵がありません: $file")
    return ReleaseKey(file, required("PLAMO_KEYSTORE_PASSWORD"), required("PLAMO_KEY_ALIAS"), required("PLAMO_KEY_PASSWORD"))
}

fun releaseKeyFromLocalProperties(): ReleaseKey? {
    val propsFile = rootProject.file("local.properties").takeIf { it.exists() }
        // パスが日本語を含んでもよいよう、UTF-8で読む（InputStreamで読むとISO-8859-1になり化ける）
        ?.let { Properties().apply { it.reader(Charsets.UTF_8).use(::load) }.getProperty("plamo.keystore") }
        ?.let(::File)
        ?.takeIf { it.exists() }
        ?: return null
    val props = Properties().apply { propsFile.inputStream().use(::load) }
    return ReleaseKey(
        storeFile = propsFile.resolveSibling(props.getProperty("storeFile")),
        storePassword = props.getProperty("storePassword"),
        keyAlias = props.getProperty("keyAlias"),
        keyPassword = props.getProperty("keyPassword"),
    )
}

val releaseKey: ReleaseKey? = releaseKeyFromEnv() ?: releaseKeyFromLocalProperties()

android {
    namespace = "app.plamotranslate"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.plamotranslate"
        minSdk = 33  // 性能ヒント（APerformanceHint）がAPI 33から
        targetSdk = 36
        versionCode = 11
        versionName = "0.5.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey.storeFile
                storePassword = releaseKey.storePassword
                keyAlias = releaseKey.keyAlias
                keyPassword = releaseKey.keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // 単体テストでは android.util.Log などを何もしない形で動かす（ModelDownloadsTest）
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        jniLibs {
            // リンク用に作ったOpenCLローダーは同梱しない。実行時は端末のドライバー（/vendor/lib64/libOpenCL.so）を使う
            excludes += "**/libOpenCL.so"
            // sherpa-onnxのAARはx86用だけONNX Runtimeを別ファイルで持ち、漫画用OCRのものと名前がぶつかる。
            // 同梱するのはarm64だけ（abiFilters）なので、x86の分は使わない
            excludes += "lib/x86/libonnxruntime.so"
            // llama.cppの計算部分（libggml-opencl.so、libggml-cpu-*.so）は、実行時にフォルダーを探して読み込む。
            // APKの中に圧縮せず置く既定の形だとファイルとして見えないので、インストール時に展開させる
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core-llm"))
    implementation(project(":core-ocr"))
    implementation(project(":core-asr"))
    implementation(project(":core-tts"))
    // core-asr・core-tts が使うsherpa-onnxのAAR。ライブラリモジュールはローカルAARを同梱できないので、ここで同梱する
    implementation(files(rootProject.file("third_party/sherpa-onnx/sherpa-onnx-static-link-onnxruntime-${libs.versions.sherpa.onnx.get()}.aar")))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

// JDK 21で動かし、Java 17相当のバイトコードを出す（JDK 17は入れていない）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
