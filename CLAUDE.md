# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PoLang is a technology research project centered on an AI-Agent-driven smart gallery (破浪相册). It explores three technical tracks in one codebase: **(1) On-device Agent Runtime + local/remote inference** — `AgentOrchestrator` + `CapabilityRegistry` map natural language to device capabilities, with local MNN-LLM (on-device VLM tagging, Qwen3-VL) and remote OpenAI-compatible inference via Koog; **(2) Smart gallery & image editing** — natural-language search, conversational editing, matting/ID-photo, Florence-2 auto-tagging, JS sandbox; **(3) Self-developed OpenGL ES + EGL beauty/filter engine** plus a self-hosted Ktor backend (remote-inference gateway, account system, admin console). This project does not pursue commercialization; its core value lies in technical exploration and engineering practice.

**Current focus (2026-09, app v1.0.39)** is the smart gallery as the default home with AI chat as the core assistant capability (相册/图片编辑为主入口; evolution direction, upgraded 2026-09-26: **对话式相册管理（自然语言委托 → 后台任务执行 → 完成回联）+ 图片后处理智能编辑** — 任务 is a first-class product concept: task center dual-tab + UserTask protocol M1 + engineer task cards shipped, roadmap in PRODUCT.md §6.6). **Camera line frozen (2026-08-16 decision)**: freeze takes effect once dual-platform camera UI parity converges — code retained (engine real-time testbed + content feed for the edit flow), no new features (G5 深化取消), no parity polish after freeze; pre-freeze cleanup: remove Android scene panel (`SceneSelector`/`ScenePreset`/`scene_*` strings). Shipped: natural-language search, conversational image editing, matting/ID-photo, Florence-2 auto-tagging, JS sandbox, fact memory + person-relationship graph (capabilities live). See `PRODUCT.md` for the latest product roadmap.

Key technological decisions:
- **On-device Agent**: `shared/` KMP module (package `com.mamba.picme.agent.core`) implements the Agent orchestration layer (AgentOrchestrator, CapabilityRegistry, etc.) that maps natural language to device capabilities. The on-device text LLM (Qwen3.5-2B) was removed in 2026-08 — camera/chat inference now goes through remote OpenAI-compatible tool_calls; `LocalLlmEngine` retains only on-device VLM tagging (`imageInference`, Qwen3-VL-2B).
- **Remote inference**: Standard OpenAI Chat Completions API protocol via Koog (JetBrains KMP Agent 框架), with DeepSeek adapter support. Local/remote pipelines fully separated per ADR-005. The self-maintained langchain4j fork (`:agent-core`) was migrated to Koog in 2026-08 and the `:agent-core` module deleted (commit 1cbe9353).
- **Privacy-first**: 用户图片/视频**文件**不得上传到远程大模型/推理服务器（人脸检测/OCR/分类/打标等媒体处理 100% 端侧）；文本、元数据、相册聚合摘要可走远程推理（chat 默认远程）。飞书/Telegram 等用户自配置 IM 通道回传媒体给用户本人不在此列（用户自有通道，非模型推理上传）。详见 ADR-008。
- **Self-developed Engine**: Full OpenGL ES + EGL pipeline (no third-party beauty SDKs); GPUPixel has been completely removed.

## Common Commands

```bash
# Build debug APK
./gradlew :androidApp:assembleDebug

# Run JVM unit tests (no device required)
./gradlew test
# Or module-specific:
./gradlew :androidApp:testDebugUnitTest
./gradlew :engines:beauty-engine:testDebugUnitTest

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Code quality
./gradlew lint
./gradlew ktlintCheck
./gradlew detekt

# Clean build
./gradlew clean

# Install to device
adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk

# View PoLang logs
adb logcat -s "PoLang:*"

# Full dev verification loop (compile → install → launch → screenshot → logs)
./scripts/auto-dev-loop.sh
```

## High-Level Architecture

### Module Structure

Seven Gradle modules defined in `settings.gradle.kts`:
- **`:androidApp`** — Main Android application (Camera, Gallery, Editor, Settings)
- **`:engines:beauty-api`** — Pure Kotlin library; stable API contracts shared between `:androidApp` and `:engines:beauty-engine`
  (BeautySettings, FilterType, StyleFilter, Face, FaceDetector, FrameSyncConfig, etc.)
- **`:engines:beauty-engine`** — Independent Android library; self-developed OpenGL ES + EGL real-time beauty engine
- **`:shared`** — Kotlin Multiplatform library (android/jvm/iOS targets); **Agent orchestration layer** infrastructure
  (AgentOrchestrator, CapabilityRegistry, KoogChatAgent, KoogReActAgent, RemoteChatEngine, ExecutionEngine, PrivacyGuard,
  JS sandbox engine-agnostic layer in commonMain; LocalLlmEngine, MemoryManager, voice/ASR, DataStore stores in androidMain).
  Package `com.mamba.picme.agent.core.*`. VLM JNI bridge `.so` is built by `:engines:agent-native`.
- **`:engines:mnn-core`** — MNN inference JNI wrappers
- **`:engines:agent-native`** — VLM JNI bridge (`libagent_native.so`), consumed by `:shared` androidMain via AAR
- **`:engines:sentencepiece`** — tokenizer

> ⚠️ **模块语义（重要）**：`:shared` = Agent 编排层 KMP 模块（commonMain 引擎无关层：AgentOrchestrator/CapabilityRegistry/KoogChatAgent/KoogReActAgent/RemoteChatEngine/…；androidMain 平台实现：LocalLlmEngine/语音/DataStore；包 `com.mamba.picme.agent.core`）。远程推理经 **Koog**（JetBrains KMP Agent 框架，外部依赖）编排——2026-08 由自维护的 langchain4j fork 迁移而来，原 `:agent-core` 模块已删除；原 `:runtime-core` 已于 Phase 4 整体迁入 `:shared` 后删除。依赖链：`:androidApp → :shared → Koog（外部依赖）`。

GPUPixel has been fully removed; all GPU capabilities are provided by the self-developed engine.

### Clean Architecture (App Module)

```
features/  →  domain/usecase/  →  domain/repository/  →  data/
   ↓                ↓
shared/   beauty-api/   beauty-engine/  (strict boundaries — see below)
```

- **Features**: Compose UI + ViewModels. Camera features include an Agent interaction panel for natural language control.
- **Domain**: Pure Kotlin, no Android dependencies. Includes `domain/usecase/AiAgentUseCase` as Facade to `:shared` (Agent orchestration layer).
- **Data**: Repository implementations, Room DB, DataStore preferences, and LLM model download management (`LlmModelDownloadManager`).
- **shared**: Agent orchestration layer KMP module (engine-agnostic logic in commonMain; Android platform impls in androidMain; package `com.mamba.picme.agent.core`).

### Beauty-Engine Layered Architecture (Critical Dependency Boundary)

```
App Layer
    ↓ (only dependency allowed)
beauty-api/                 ← Pure Kotlin API contracts (BeautySettings, FilterType,
                               StyleFilter, Face, FaceDetector, FrameSyncConfig, etc.)
    ↑
beauty-engine:api/          ← Implementation-facing API (BeautyParams, BeautyPreviewProvider,
                               BeautyPreviewEngine, PhotoProcessor, BeautyPerfStats, etc.)
    ↑
beauty-engine:render/       ← Internal OpenGL ES + EGL pipeline (BeautyRenderer,
                               CameraPreviewRenderer, PhotoProcessorImpl, EGLCore)
    ↑
beauty-engine:internal/     ← Face detection adapters (MediaPipe/MNN), frame-sync system
```

**Dependency rules**:
- App code **must only** depend on `beauty-api/` and `beauty-engine:api/` classes. Direct references to `render/` or `internal/` are forbidden.
- `beauty-api/` is a pure Kotlin module with zero Android/OpenGL dependencies.
- `beauty-engine:api/` depends on `beauty-api/` for shared types.
- `beauty-engine:render/` implements `api/` interfaces and may depend on Android/OpenGL ES libraries.
- All GPU/EGL operations are encapsulated inside `beauty-engine:render/`.

### Face Detection Architecture

Multi-engine detection unified to 106 landmarks via adapter pattern:
- **MediaPipe Face Mesh 468→106** (default): TFLite GPU delegate inference with precise 468→106 semantic mapping.
- **MNN 2D106** (alternative): Local MNN inference for landmark detection.
- Auto mode: prefers MediaPipe; cascades through alternatives on miss or init failure.

All detection implementations live in `beauty-engine/internal/facedetect/` with adapter pattern (`FaceLandmarkAdapter`).
App layer consumes only `beauty-api/facedetect/` contracts. The old InsightFace ONNX and NCNN paths have been fully removed.

### Frame-Sync Makeup System

Solves makeup "flying off" caused by face detection (~10 fps) and rendering (30–60 fps) running at different rates.

- Core components in `beauty-engine/internal/framesync/`: `FrameSyncBridge`, `FrameSyncManager`, `MotionTracker`.
- Rendering thread queries `FrameSyncManager` by `FrameId` to get time-aligned face data (exact match → history fallback → prediction compensation → hide).
- `FrameSyncConfig` and `FrameSyncResult` contracts live in `beauty-api/` for cross-module sharing.
- **Recording must reuse the same `FrameSyncManager` instance** as preview to ensure consistent behavior.
- Note: `DetectionQueue` and `FaceDetectionWorker` are design-phase concepts; current implementation uses synchronous detection via `FaceDetectionProvider`.

### Agent Runtime (On-Device & Remote)

```
User Input ("找出去年夏天的照片" / "磨皮50")
    → AiAgentUseCase (Facade in app domain/usecase/)
    → AgentOrchestrator (in :shared commonMain)
    ├── Camera: processCameraInput → KoogReActAgent + CameraToolService (remote tool_calls)
    └── Chat: streamChat / ChatToolService (remote tool_calls, OpenAI-compatible)
    → Koog agent loop → CapabilityRegistry.dispatch
    → ImageEditCapability / NavigationCapability / SystemCapability + Chat*Capability (execute)
```

- **Module**: `:shared` — KMP module containing all Agent orchestration components (package `com.mamba.picme.agent.core`; Android composition root `androidApp/src/main/java/com/mamba/picme/agent/AndroidAgentComposition.kt`).
- **Local model**: on-device text LLM removed (2026-08); MNN-LLM runtime only hosts VLM tagging (Qwen3-VL-2B). Local inference pipeline (`LocalInferencePipeline`) deleted with it.
- **Remote protocol**: Standard OpenAI Chat Completions API (tool_calls, streaming, multi-turn dialogue). Koog (JetBrains KMP Agent 框架) as consumer layer — `shared/src/commonMain/.../inference/remote/koog/` (KoogChatAgent / KoogReActAgent / RemoteModelFactory).
- **Capabilities**: Registered `Capability` classes (14) — app/chat-scoped: `ImageEditCapability` (conversational `edit_image`), `GalleryCapability`, `SettingsCapability`, `AiOptimizeCapability`, `ChatSearchCapability` / `ChatGallerySummaryCapability` / `ChatStartTagScanCapability` / `ChatRunScriptCapability` / `ChatMediaWriteCapability`, `PersonRelationCapability`, `MemoryCapability`; activity-scoped: `NavigationCapability`, `SystemCapability` (app/settings launch + cross-app a11y); page-scoped: `CameraCapability` (camera screen register/unregister). (`AutoTagCapability` / `RemoteControlCapability` / `BeautyCapability` exist in code but are NOT registered — see registry doc.) Command→Capability routing SSOT: `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`.
- **Privacy**: `PrivacyGuard` classifies user input by privacy level (PUBLIC/SENSITIVE/RESTRICTED) for routing decisions; text inference is fully remote since the on-device text LLM removal.
- **Memory**: `MemoryManager` maintains conversation context for multi-turn dialogue.
- **Voice**: Voice interaction support via `voice/` sub-package (ASR, VAD, AudioRecorder, SherpaOnnxAsrEngine).
- **Remote**: Remote LLM orchestration via `remote/` sub-package (OpenAI-compatible API, IntentCache).
- **ADR-005 (2026-06-15)**: Local/Remote protocols formally separated. Unified `InferenceRouter` removed. ~1500 lines of redundant Tool Calling wrappers removed.

### Zero-Copy GPU Pipeline (Preview)

```
CameraX → SurfaceTexture → OpenGL ES Shader → SurfaceView
```

- Preview path is fully GPU-based; `glReadPixels` back to CPU is prohibited in preview.
- Photo processing uses the same shader pipeline via off-screen FBO rendering (`PhotoProcessorImpl`) to guarantee preview/photo consistency. CPU fallback exists at `core/image/`.

## Code Style & Constraints

### Hard Rules (Enforced)
- **No fully-qualified names** for `com.mamba.picme.*` in source; use imports. (Convention enforced by review — the historically referenced `checkNoFullyQualifiedName` Gradle task was never implemented; see ADR-013 §3.)
- **commonMain purity**: `:shared/commonMain` must contain no `@Composable`, no platform imports (`android.*` / `java.*` / `androidx.compose.*`), and no `actual` declarations — business logic only; platform impls belong in `androidMain` / `iosMain` (custom Gradle task `checkCommonMainPurity`, bound to `compileKotlinMetadata`; ADR-013). `expect` declarations are allowed.
- **No wildcard imports** (`*`).
- **Lambda parameters must be explicitly named**; implicit `it` is prohibited.
- **Log tags** must follow `PoLang:[ModuleName]` (e.g., `PoLang:Camera`, `PoLang:BeautyEngine`).
- **Indentation**: Kotlin/Java 4 spaces; XML/JSON/MD 2 spaces.

### I18N (Mandatory)
- **Never hardcode user-facing strings** in UI code.
- When adding or refactoring features, **must sync all supported languages**: `values/strings.xml` (EN/default), `values-zh-rCN/strings.xml` (Simplified Chinese), `values-zh-rTW/strings.xml` (Traditional Chinese), `values-es/strings.xml` (Spanish), `values-fr/strings.xml` (French).

### Global Red Lines
- **`[PRIVACY]`**: **禁止向远程大模型/推理服务器上传用户图片/视频文件**；人脸检测/OCR/分类/打标等媒体处理必须 100% 端侧。文本、元数据、相册摘要等非媒体数据可走远程推理（chat 默认远程）。飞书/Telegram 等用户自配置通道回传媒体给用户本人不属红线。> 原「Cloud inference is strictly prohibited」红线已于 2026-07-28 决策1 放宽（见 ADR-008）。
- **`[PERF]`**: Interaction feedback < 100 ms; shutter capture latency < 50 ms.
- **`[I18N]`**: All user-visible text must be extracted and synchronized across the five language sets above.

## Quality Toolchain

- **ktlint** (v1.3.1) — Kotlin code style
- **detekt** (v1.23.6, config: `detekt-config.yml`) — Static analysis
- **commonMain purity check** (`checkCommonMainPurity`, ADR-013) — Fails the build if `:shared/commonMain` contains `@Composable`, platform imports, or `actual` declarations; runs on every build via `compileKotlinMetadata`.
- **Unit tests** — Pure JVM tests covering coordinate algorithms, state machines, converters, end-to-end flows. ~50 test files across `androidApp/src/test/`, `beauty-engine/src/test/`, and `shared/src/commonTest` + `shared/src/jvmTest`.
- **Instrumentation tests** — Require connected device/emulator.

## Documentation Hierarchy

The project follows a three-layer documentation system. When implementation reveals spec gaps, code and docs must be updated in the **same atomic commit**.

```
PRODUCT.md          → Goals and constraints (What)
docs/01-PRODUCT/FEATURES.md    → Interaction and UX rules (How)
<module>/AGENTS.md  → Implementation specs and checklists
```

Key technical specs:
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — Rendering pipeline, fallback, cooldown recovery, observability (camera preview & frame-sync content merged here)
- `docs/03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md` — MediaPipe + MNN dual-engine architecture
- `docs/02-ARCHITECTURE/ADR/ADR-001-beauty-engine-architecture.md` — Layered module architecture decision
- `docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md` — GPU off-screen rendering for photo processing
- `docs/02-ARCHITECTURE/ADR/ADR-013-kmp-architecture-contract.md` — KMP architecture contract (AI-friendly / no-CMP / commonMain purity / flat Swift seam / engines asymmetry)
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — Agent runtime architecture design

## Build Configuration

- **compileSdk**: 36, **minSdk**: 24, **targetSdk**: 36
- **Java/Kotlin target**: 17（`:androidApp` / `:shared` / `:engines:mnn-core` / `:engines:agent-native`）；11（`:engines:beauty-api` / `:engines:beauty-engine`）
- **Dependency management**: Version Catalog (`gradle/libs.versions.toml`)
- **Plugins**: Android Application/Library, Kotlin Android + Compose, KSP, ktlint, detekt

## Useful Scripts

Located in `scripts/`:
- `auto-dev-loop.sh` — Full verification loop (compile, install, launch, screenshot, log collection)
- `ai-gate.sh` — Quality gate (lint + compile + install check)
- `quick-compile.sh` — Layered fast compile (syntax → compile → dex → APK, stop on first failure)
- `impact-analyzer.sh` — Change impact analysis (affected modules, red lines, doc sync needs)
- `screenshot-diff.py` — Pixel-level screenshot comparison for UI regression
- `smart-commit.sh` — Auto-generate Conventional Commits based on changes
