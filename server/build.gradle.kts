import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 2.3.10：与根 monorepo libs.versions.toml 对齐；Koog 1.3.0 元数据按 2.3.0 编译，
    // Kotlin < 2.3 拒绝读取（"incompatible version of Kotlin"）。
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
}

group = "com.mamba.picme"
version = "0.9.27"

repositories { mavenCentral() }

// Koog 1.3.0 传递依赖把 ktor-client/server-core 抬到 3.3.3；混用 3.0.3 的
// default-headers 二进制引用 kotlinx-datetime 0.6 API（Clock$System 在 0.7 已删），
// 运行时首个响应即 CNFE。故全族对齐 3.3.3。
val ktorVersion = "3.3.3"
val exposedVersion = "0.55.0"

dependencies {
    // Ktor server (CIO engine, 省内存)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    // 管理后台：服务端渲染 HTML（kotlinx.html 由其传递依赖带入）
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    // Browser VNC WebSocket 代理（App ↔ bridge 双向帧转发）
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    // Ktor client（LLM 流式代理）
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    // Exposed + SQLite + HikariCP
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
    // 腾讯 COS
    implementation("com.qcloud:cos_api:5.6.227")
    // 序列化 & 日志
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    // QR code generation (server-side, no CDN dependency)
    implementation("com.google.zxing:core:3.5.3")
    // Skill 中心 markdown 渲染（预览页「渲染预览」Tab + 编辑页实时预览初值；零传递依赖）
    implementation("org.commonmark:commonmark:0.24.0")

    // Koog 云端 Agent 运行时（ADR-017/018：Koog JVM 定向）。
    // 版本与 :shared 对齐（1.3.0，勿各自漂移——两端事件/协议语义需同版互认）。
    // serialization-jackson 排除沿用 :shared 约定（Koog 主链路走 kotlinx-serialization；
    // JVM 侧不排也能跑，但少拉 jackson-module-kotlin 一整棵树，两项目保持一致）。
    implementation("ai.koog:koog-agents:1.3.0") {
        exclude(group = "ai.koog", module = "serialization-jackson")
    }

    // :shared jvm 变体（composite build 替换；分层 Agent runtime M1：契约/策略/执行器同源）。
    // Kotlin("jvm") 消费方经 Gradle Module Metadata 自动选中 jvm 变体。
    implementation("com.mamba.picme:shared:1.0.0")

    // 测试（server 首次引入测试基建）
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

application {
    mainClass.set("com.mamba.picme.server.ApplicationKt")
}

// skill 资产进分发包（Skill 中心，2026-11-07）：installDist 输出 skills/ 目录，
// systemd WorkingDirectory=/home/ubuntu/picme-server 下 SkillCenter 缺省相对路径
// "skills" 即解析命中，VPS 零 env 改动。⚠️ 仓库 server/skills/ 是生产 skill 的 SSOT
// ——deploy.sh rsync --delete 语义下，后台直改 VPS 的 skill 会被下次部署覆盖。
distributions {
    main {
        contents {
            from("skills") { into("skills") }
        }
    }
}

// 把 gradle version 注入 jar manifest，HealthzRoute 运行时读取，避免版本号硬编码漂移
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}

// migrations/ 下的 *.sql 进 classpath，运行时由 Migrations 读取执行
sourceSets {
    main {
        resources {
            srcDir("migrations")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
