plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.skie)
}

kotlin {
    android {
        namespace = "com.mamba.picme.shared"
        compileSdk = 36
        minSdk = 24
    }
    jvm()
    val iosX64 = iosX64()
    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()

    // iOS framework 产物（iosApp 消费；Phase 5 Task 1）
    // 注：Kotlin 2.2+ XCFramework DSL 类已改名 XCFrameworkConfig（原 XCFramework 被移除）
    val sharedKit = org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkConfig(project, "SharedKit")
    listOf(iosX64, iosArm64, iosSimulatorArm64).forEach { target ->
        target.binaries.framework {
            baseName = "SharedKit"
            isStatic = false
            sharedKit.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // 排除 serialization-jackson：Android variant 传递引入 jackson-module-kotlin
            //（MethodHandle.invokeExact，需 API 26），minSdk 24 下 D8 拒绝 dex。
            // ⚠️ 2026-09-25 双重核实（Koog 1.3.0）：koog-agents-1.3.0.pom（JVM 视角）虽已不直列
            // 此依赖、:shared jvmRuntimeClasspath 也无 jackson，但 Android variant 的
            // debugRuntimeClasspath 仍经 ai.koog:serialization-jackson:1.3.0 拉入
            // jackson-module-kotlin:2.21.3——删本 exclude 实测 mergeExtDexDebug 即失败。
            // 验证须以 Android 侧 D8 为准，勿信 JVM 侧依赖树。Koog 主链路用 kotlinx-serialization，
            // 不依赖此可选模块。
            // 注：KMP KotlinDependencyHandler 不支持 Provider+配置块的重载，故用字符串记法，
            // 版本号仍取自版本目录（libs.versions.koog），不产生第二处版本源。
            implementation("ai.koog:koog-agents:${libs.versions.koog.get()}") {
                exclude(group = "ai.koog", module = "serialization-jackson")
            }
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.core.ktx)
            // sherpa-onnx v1.13.3（2026-06，内置 ONNX Runtime 1.24.3，支持 16KB page size）
            // compileOnly + app 直接依赖：规避 Library 模块打包 AAR 时禁止直接依赖本地 .aar 限制
            //（模式自原 :runtime-core 平移；运行时 AAR 由 :androidApp 直接依赖本目录同文件打包）
            compileOnly(files("libs/sherpa-onnx-1.13.3.aar"))
            // VLM 引擎（inference/local/llm，自原 :runtime-core 迁入）：
            // MnnResourceManager/MnnGlobalReleaseLock 来自 :engines:mnn-core；
            // libagent_native.so 由 :engines:agent-native（独立 AGP library 模块）构建，
            // 经 implementation 传递至 :androidApp 打包（AGP 9 KMP 库插件不支持 externalNativeBuild，
            // 官方推荐独立 com.android.library 模块承载 JNI 构建）。
            implementation(project(":engines:mnn-core"))
            implementation(project(":engines:agent-native"))
        }
        // iosMain 暂无额外依赖（Phase 6.2 chat 链路仅用 commonMain 传递依赖 + K/N 平台 API）
    }
}

// ── ADR-013 §3 守卫：commonMain 纯度 ─────────────────────────────────────────────
// 业务逻辑下沉 commonMain，平台实现各端自理（androidMain/iosMain）。本任务在编译 common
// 元数据前校验：禁 @Composable / 禁平台 import（android.*·java.*·androidx.compose.*）/ 禁
// actual 声明。违任一即 fail，防住单次坏提交把平台依赖漏进 commonMain 静默搞挂 iOS 编译。
// 注：expect 声明允许（contract §2.4 按需扁平 expect）；actual 才禁。
tasks.register("checkCommonMainPurity") {
    group = "verification"
    description = "ADR-013: commonMain 不得含 @Composable / 平台 import / actual 声明"
    val srcDir = file("src/commonMain/kotlin")
    inputs.dir(srcDir).withPropertyName("commonMainSources")
    val patterns: List<Pair<Any, String>> = listOf(
        "@Composable" to "Compose UI 注解（UI 不得入 commonMain）",
        Regex("^import\\s+(android|java)\\.") to "平台 import android.*/java.*（iOS K/N 不可用）",
        Regex("^import\\s+androidx\\.compose") to "Compose 依赖（UI 不得入 commonMain）",
        Regex("\\bactual\\s+(fun|class|interface|val|var|object|typealias|property|annotation)\\b")
            to "actual 声明（平台实现属 androidMain/iosMain，不入 commonMain）"
    )
    doLast {
        val violations = mutableListOf<String>()
        fileTree("src/commonMain/kotlin") { include("**/*.kt") }.forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                patterns.forEach { (pat, msg) ->
                    val hit = when (pat) {
                        is String -> pat in raw
                        is Regex -> pat.containsMatchIn(raw)
                        else -> false
                    }
                    if (hit) violations += "${f.relativeTo(srcDir)}:${i + 1}: $msg"
                }
            }
        }
        if (violations.isNotEmpty()) {
            violations.sorted().forEach { logger.error(it) }
            throw org.gradle.api.GradleException(
                "commonMain 纯度校验失败（ADR-013 §2.1/§2.3）：见上 ${violations.size} 处违规"
            )
        }
    }
}
// 绑定 common 元数据编译与 check 生命周期：androidApp 构建经此链路，保证每次构建都校验
tasks.matching { it.name in setOf("compileKotlinMetadata", "check") }.configureEach {
    dependsOn("checkCommonMainPurity")
}
