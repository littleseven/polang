pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "picme-server"

// 分层 Agent runtime M1（2026-11-07）：复合构建挂 monorepo 根构建，
// 以 "com.mamba.picme:shared:1.0.0" 坐标替换消费 :shared jvm 变体
//（契约/策略/执行器规则端云同源）。server 未被根构建 include，无环。
// ⚠️ 构建 server 自此需要本机 Android SDK（:shared 配置含 android target）；
// 部署构建在开发机执行（deploy.sh 开发机构建→rsync），VPS 只跑 jar。
includeBuild("..")
