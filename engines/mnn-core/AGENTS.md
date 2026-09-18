# :engines:mnn-core 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:engines:mnn-core` 模块的实现细节。
> - 顶层治理规则以根目录 `AGENTS.md` 为准。

**模块定位**：MNN 推理运行时共享模块
**主要维护者**：项目开发者
**阅读对象**：RD、AI Agent
**版本**：1.0
**最后更新**：2026-07-15
**状态**：生效中

---

## 1. 模块概述

`:engines:mnn-core` 是 **MNN 推理运行时共享模块**，为 Android Library。它集中管理 MNN 预编译库（`libMNN.so`、`libOpenCL.so`）和 MNN 资源加载/释放锁，供 `:shared` androidMain（VLM 打标）和 `:engines:beauty-engine`（人脸检测）共同依赖。

该模块的独立避免了 `:engines:beauty-engine` 因使用 MNN 而反向依赖 `:shared`。

## 2. 提供的 API

- `MnnResourceManager`：MNN 模型资源路径管理
- `MnnGlobalReleaseLock`：MNN 资源释放全局锁
- `MnnLogger`：模块内部日志封装（避免依赖 `:shared` 的 Logger 造成反向依赖）

## 3. Native 库

- `libMNN.so`
- `libOpenCL.so`

## 4. 依赖方向

```
:shared(androidMain) ───→ :engines:mnn-core ←─── :engines:beauty-engine
```

## 5. Native 构建约束

- ABI：`arm64-v8a`
- minSdk：24
- ndkVersion：28.2.13676358
- 预编译 `.so` 打包模块（`src/main/jniLibs/arm64-v8a/`），无 native 编译（无 CMake / externalNativeBuild / STL 配置）

## 6. 编译验证

```bash
./gradlew :engines:mnn-core:assembleDebug
```

> **维护者**：项目开发者
> **最后更新**：2026-07-06
