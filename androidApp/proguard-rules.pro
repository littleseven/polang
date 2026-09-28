# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Logger class for reflection binding from beauty-engine module
-keep class com.mamba.picme.core.common.Logger { public *; }

# ONNX Runtime: 保留所有 ONNX Runtime Java 类，防止 R8 裁剪导致 SIGSEGV
# 参考：https://github.com/microsoft/onnxruntime/issues/17847
-keep class ai.onnxruntime.** { *; }

# R8: javax.lang.model 仅在编译期注解处理时需要，运行时不存在
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
# ML Kit: 防止 R8 裁剪/混淆通过反射访问的内部注册表，避免 release 构建下
# MultiFlavorDetectorCreator 等类出现 NPE（Attempt to read from field 'HashMap ...' on null）
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# TAG 数据备份/还原：Moshi 通过反射/Kotlin 元数据解析，R8 会裁剪字段导致列表为 null
-keep class com.mamba.picme.domain.backup.model.** { *; }
-keepclassmembers class com.mamba.picme.domain.backup.model.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations, AnnotationDefault

# Feishu/Lark OAPI SDK: FeishuChannelHandler 通过反射访问 ws.Client 的 protected 成员
# (autoReconnect 字段 / disconnect() 方法 / executor 线程池) 做断连清理——SDK 未公开 stop API。
# 这些 protected 成员会被 R8 混淆导致 NoSuchFieldException/NoSuchMethodException，需保留原名。
# 验证：release 包 logcat 不再出现 PoLang:FeishuHandler NoSuchFieldException autoReconnect。
-keep class com.lark.oapi.ws.Client { *; }

# === Moshi 反射序列化模型（KotlinJsonAdapterFactory + 内置 EnumJsonAdapter）===
# EnumJsonAdapter 构造时以 enumClass.getField(constant.name()) 反射取常量字段，
# R8 重命名字段后抛 NoSuchFieldException → AssertionError（release 包进 PhotoEditor 即崩，
# 2026-09-28：PhotoEditRecipeRepository 构建 BeautySettings 适配器触发）。
# KotlinJsonAdapterFactory 同样依赖数据类字段原名 + kotlin.Metadata 绑定。
# 凡经 moshi.adapter(SomeClass::class.java) 反射适配的模型与其枚举字段类型，一律保留：
-keep class com.mamba.picme.beauty.api.** { *; }
-keep class com.mamba.picme.domain.agent.capability.optimize.OptimizeResultDto { *; }
# OptimizePreset 内嵌 BeautyPreset/FilterPreset/AdjustmentPreset，Moshi 会逐层反射适配，整包保留
-keep class com.mamba.picme.domain.agent.capability.optimize.preset.** { *; }

# === JS 引擎 + JSBridge（QuickJS）===
# JS bridge：NativeHandler 工厂 object、JsBridge、JsEngine 实现经反射装配
-keep class com.mamba.picme.agent.core.js.** { *; }
# Koog @Tool 反射工具集：@Tool 方法只被 Koog 反射（组合根 asToolsByClass()/reflect tools()）
# 调用，无任何直接调用点，R8 会把这些方法当无用代码裁剪——裁剪后 asTools() 的
# require(isNotEmpty()) 抛 IllegalArgumentException("No tools found in ...")，
# release 包启动即崩（Application.onCreate → AndroidAgentComposition.initialize）。
# 必须保留三个工具类的全部成员；参数注解依赖上方全局 keepattributes。
# langchain4j 时代的 @Tool 反射扫描 keep（PoLangToolService）已随 fork 删除（2026-08-07 Phase 6）。
-keep class com.mamba.picme.agent.core.inference.remote.tool.ChatToolService { *; }
-keep class com.mamba.picme.agent.core.inference.remote.tool.CameraToolService { *; }
-keep class com.mamba.picme.agent.core.inference.remote.tool.RemoteControlToolService { *; }
# Koog 注解类：@Tool/@LLMDescription 实例经 RuntimeVisibleAnnotations 保留，防御性保留注解类本体
-keep class ai.koog.agents.core.tools.annotations.** { *; }

# Ktor: IntellijIdeaDebugDetector 引用 java.lang.management（JVM 专属 API）探测 IDE 调试器，
# Android 上不存在该类——Ktor 内部已做运行时防护（懒加载 + 异常兜底），安全忽略。
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
