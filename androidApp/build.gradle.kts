import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.gpp)
}

// Release signing config —— 从环境变量读取（System.getenv，非 gradle.properties）。
// 本地构建请在 shell profile（如 ~/.zshrc）export：POLANG_RELEASE_STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD。
// STORE_FILE 可省略：未设置时回退到项目内置 keystore（defaultReleaseKeystore），故直接 gradle 或
// release-automation.sh 也能出正式签名包；CI 检出无该文件时留空 → 回退 debug 签名。发版推荐 ./scripts/build.sh release。
// 兼容旧命名 PICME_RELEASE_*（即将废弃）。
val defaultReleaseKeystore = file("keystore/picme-release.jks")
val releaseStoreFile: String =
    System.getenv("POLANG_RELEASE_STORE_FILE")
        ?: System.getenv("PICME_RELEASE_STORE_FILE")
        ?: if (defaultReleaseKeystore.exists()) defaultReleaseKeystore.absolutePath else ""
val releaseStorePassword: String =
    System.getenv("POLANG_RELEASE_STORE_PASSWORD")
        ?: System.getenv("PICME_RELEASE_STORE_PASSWORD") ?: ""
val releaseKeyAlias: String =
    System.getenv("POLANG_RELEASE_KEY_ALIAS")
        ?: System.getenv("PICME_RELEASE_KEY_ALIAS") ?: ""
val releaseKeyPassword: String =
    System.getenv("POLANG_RELEASE_KEY_PASSWORD")
        ?: System.getenv("PICME_RELEASE_KEY_PASSWORD") ?: ""

// 飞书远程控制 AppId/AppSecret（编译时从 local.properties 或环境变量注入。默认空字符串）
// local.properties: polang.feishu.app.id=cli_xxxxx, polang.feishu.app.secret=yyyyy
// 环境变量: POLANG_FEISHU_APP_ID=cli_xxxxx POLANG_FEISHU_APP_SECRET=yyyyy
// 兼容旧命名 picme.feishu.app.* / PICME_FEISHU_APP_*（即将废弃）
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val feishuAppId: String =
    localProperties.getProperty("polang.feishu.app.id")
        ?: localProperties.getProperty("picme.feishu.app.id")
        ?: System.getenv("POLANG_FEISHU_APP_ID")
        ?: System.getenv("PICME_FEISHU_APP_ID") ?: ""
val feishuAppSecret: String =
    localProperties.getProperty("polang.feishu.app.secret")
        ?: localProperties.getProperty("picme.feishu.app.secret")
        ?: System.getenv("POLANG_FEISHU_APP_SECRET")
        ?: System.getenv("PICME_FEISHU_APP_SECRET") ?: ""

// 远程诊断上报：构建时的 git short SHA（注入 BuildConfig.GIT_SHA）
val gitSha: String =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText
        .get()
        .trim()
        .ifEmpty { "unknown" }

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/detekt-config.yml")
    baseline = file("$projectDir/detekt-baseline.xml")
}

ktlint {
    version.set("1.3.1")
    android.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

// 使用 ktlint 插件进行代码风格检查
// 如需其他验证任务，请在 buildSrc 中定义并通过 plugins {} 声明
// tasks.named("preBuild").configure {
//     dependsOn("checkNoFullyQualifiedName")
// }

// Google Play 自动发布（GPP，com.github.triplet.play）——脚本入口 ./scripts/play-publish.sh，
// 运维手册见 docs/05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md。
// 认证二选一：
//   本地：export POLANG_PLAY_SERVICE_ACCOUNT_JSON=/path/to/service-account.json（文件路径）
//   CI：  export ANDROID_PUBLISHER_CREDENTIALS='<json 全文>'（GPP 内置读取，不落盘）
play {
    System
        .getenv("POLANG_PLAY_SERVICE_ACCOUNT_JSON")
        ?.takeIf { it.isNotBlank() }
        ?.let { serviceAccountCredentials.set(file(it)) }
    // 默认发 internal 轨道；可用 -PplayTrack=production 或任务 CLI --track 覆盖
    track.set(providers.gradleProperty("playTrack").orElse("internal"))
    defaultToAppBundles.set(true)
}

android {
    ndkVersion = "28.2.13676358"
    namespace = "com.mamba.picme"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mamba.picme"
        minSdk = 24
        targetSdk = 36
        versionCode = 10040
        versionName = "1.0.40"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // 飞书远程控制默认值：release 默认空串（用户必须自行配置）；debug 在 buildTypes.debug 中覆盖为注入值
        buildConfigField("String", "FEISHU_APP_ID", "\"\"")
        buildConfigField("String", "FEISHU_APP_SECRET", "\"\"")
        buildConfigField("String", "CLOUDFLARE_GATEWAY_TOKEN", "\"${System.getenv("CLOUDFLARE_GATEWAY_TOKEN") ?: ""}\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    androidResources {
        noCompress += "task"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            // 让 android.util.Log 等 Android stub 方法在 JVM 单测中返回默认值，而非抛 "not mocked"。
            isReturnDefaultValues = true
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile.isNotBlank()) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 通过 project property 控制是否启用混淆，release-plain 模式不混淆
            isMinifyEnabled =
                !(
                    project.findProperty("polang.release.plain")?.toString()?.toBoolean()
                        ?: project.findProperty("picme.release.plain")?.toString()?.toBoolean()
                        ?: false
                )
            isShrinkResources = isMinifyEnabled
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release 包默认使用正式签名；AAB 构建时会通过注入参数覆盖
            signingConfig =
                if (releaseStoreFile.isNotBlank()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            // 仅 debug 注入开发者飞书凭据（local.properties / 环境变量）；release 继承 defaultConfig 空串
            buildConfigField("String", "FEISHU_APP_ID", "\"${feishuAppId}\"")
            buildConfigField("String", "FEISHU_APP_SECRET", "\"${feishuAppSecret}\"")
        }
    }

    bundle {
        // AAB 统一使用 release 签名配置；如未配置环境变量则回退 debug 签名
        storeArchive {
            enable = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // `:androidApp` 直接使用 ONNX Runtime Java API（MobileCLIP / OPUS-MT），需要保留 `onnxruntime-android` 坐标。
            // Sherpa-ONNX AAR 同时内置同名 `libonnxruntime.so`，导致打包冲突。
            // 当前两个来源均为 ONNX Runtime 1.24.3，ABI 兼容；仅支持 arm64-v8a，故只保留该 ABI 的 pickFirst。
            // 升级任一依赖时，必须确保 `libonnxruntime.so` 版本一致，否则会出现 UnsatisfiedLinkError。
            pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
        }
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
    }
}

base {
    archivesName.set("polang")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.compose.markdown)
    implementation(libs.androidsvg)
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.accompanist.permissions)
    implementation(libs.play.services.location)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.video)
    implementation(libs.logging.interceptor)
    implementation(libs.okhttp)
    implementation(libs.moshi.kotlin)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    implementation(libs.oapi.sdk)
    implementation(libs.telegram.bot)

    // Media3 dependencies
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.quickjs.kt)
    implementation(libs.androidx.media3.common)

    implementation(libs.google.mlkit.text.recognition)
    implementation(libs.google.mlkit.text.recognition.chinese)

    // MediaPipe Face Landmarker（Gallery 调试用，直接显示 468 点原始数据）
    implementation(libs.mediapipe.face.landmarker)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    // 让 JVM 单元测试能使用真实的 org.json 实现，而非 Android stub
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // 美颜 API 接口模块（纯数据类型，被 beauty-engine 和 androidApp 共享）
    implementation(project(":engines:beauty-api"))
    // 美颜引擎模块
    implementation(project(":engines:beauty-engine"))
    implementation(project(":engines:mnn-core"))
    // sherpa-onnx: :shared androidMain 编译期 compileOnly（Phase 4 Task 11 前为 runtime-core），
    // androidApp 模块提供运行时 AAR 打包
    implementation(files("../shared/libs/sherpa-onnx-1.13.3.aar"))
    // Agent 核心模块（将来提取独立库）
    // GPUPixel 已移除，全部能力由自研引擎提供

    // SentencePiece tokenizer（OPUS-MT 编码解码 + tokenizer.json 词表映射）
    implementation(project(":engines:sentencepiece"))

    // KMP shared 模块（Phase 4：原 runtime-core 引擎无关逻辑已全部迁入；模块已删）
    implementation(project(":shared"))
    // Koog agent 框架：androidApp 侧直接使用（RemoteControlToolService 的 @Tool/@LLMDescription、
    // AndroidAgentComposition 的 ToolRegistry/asToolsByClass）；原 :runtime-core 已删除，
    // 改直接依赖。serialization-jackson 排除理由同 :shared（minSdk 24 D8 限制）。
    implementation("ai.koog:koog-agents:${libs.versions.koog.get()}") {
        exclude(group = "ai.koog", module = "serialization-jackson")
    }

    // ONNX Runtime（OPUS-MT 翻译模型推理后端）
    // 版本必须与 sherpa-onnx-1.13.3 内置的 ONNX Runtime 一致（1.24.3）
    // 否则 libonnxruntime4j_jni.so 与 libonnxruntime.so ABI 不匹配
    implementation(libs.onnxruntime.android)
    // Core library desugaring（mamba-agent 依赖需要）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}

// 修复 KSP 增量缓存损坏（file-to-id.tab is already registered）的根因：
// 让 clean 任务同时删除 KSP 缓存目录，避免历史损坏状态残留。
tasks.register<Delete>("cleanKspCaches") {
    group = "build"
    description = "Deletes KSP incremental caches to prevent 'file-to-id.tab is already registered' corruption."
    delete(layout.buildDirectory.dir("kspCaches"))
}

tasks.named<Delete>("clean").configure {
    dependsOn("cleanKspCaches")
}
