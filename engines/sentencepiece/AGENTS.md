# :engines:sentencepiece 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:engines:sentencepiece` 模块的实现细节。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。

**模块定位**：SentencePiece tokenizer 的 Android JNI 封装模块
**主要维护者**：项目开发者
**阅读对象**：RD、AI Agent
**版本**：1.0
**最后更新**：2026-07-15
**状态**：生效中

---

## 1. 模块概述

`:engines:sentencepiece` 是 **SentencePiece tokenizer 的 Android JNI 封装模块**，为 Android Library（`com.android.library` 插件）。

它将 Google SentencePiece C++ 库编译为 `libsentencepiece_android.so`，并通过 JNI 暴露给 Kotlin/Java 使用。

---

## 2. 源码结构

- Java/Kotlin JNI 封装：`engines/sentencepiece/src/main/java/com/mamba/picme/sentencepiece/`
- Native 源码与 CMake：`engines/sentencepiece/src/main/cpp/`
- CMake 目标：`libsentencepiece_android.so`
- 预编译库目录：`engines/sentencepiece/src/main/jniLibs/`（目录可不存在，SO 由 CMake 构建产出）

---

## 3. JNI 接口

| Java/Kotlin 类 | native 库 | 说明 |
|----------------|-----------|------|
| `com.mamba.picme.sentencepiece.SentencePieceProcessor` | `libsentencepiece_android.so` | 加载模型、编码、解码、词表查询 |

公开方法：

- `loadModel(modelPath: String)` — 加载 `.spm` 模型文件
- `encode(text: String): IntArray` — 文本编码为 token ID 数组
- `encodeAsPieces(text: String): Array<String>` — 文本编码为 token 字符串数组
- `decode(ids: IntArray): String` — token ID 数组解码为文本
- `vocabSize(): Int` — 获取词表大小
- `idToPiece(id: Int): String` — 根据 ID 获取 token 字符串
- `pieceToId(piece: String): Int` — 根据 token 字符串获取 ID
- `close()` — 释放 native 资源

对应 native 方法：

- `nativeLoadModel` / `nativeEncode` / `nativeEncodeAsPieces` / `nativeDecode`
- `nativeVocabSize` / `nativeIdToPiece` / `nativePieceToId` / `nativeClose`

---

## 4. 依赖方向

```
:androidApp
    └── :engines:sentencepiece
```

`:engines:sentencepiece` 不依赖任何项目模块，仅依赖：
- `androidx.core:core-ktx`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`

---

## 5. Native 构建约束

- ABI：`arm64-v8a`
- minSdk：24
- STL：`c++_shared`
- CMake：3.22.1
- ndkVersion：28.2.13676358

---

## 6. 编译验证

```bash
./gradlew :engines:sentencepiece:assembleDebug
```

---

## 7. 消费者

- `:androidApp`：OPUS-MT 翻译模型的本地分词/解码。

---

> **维护者**：项目开发者
> **最后更新**：2026-07-06
