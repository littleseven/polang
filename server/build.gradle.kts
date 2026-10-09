import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.mamba.picme"
version = "0.9.3"

repositories { mavenCentral() }

val ktorVersion = "3.0.3"
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

    // 测试（server 首次引入测试基建）
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

application {
    mainClass.set("com.mamba.picme.server.ApplicationKt")
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
