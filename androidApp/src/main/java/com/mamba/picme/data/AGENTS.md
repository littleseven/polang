# Data 层开发指令 (Data Consistency & Performance)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：确保 PoLang 的媒体元数据和用户设置在任何情况下都安全、高效地存储。

**主要维护者**：项目开发者

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[ATOMICITY] 事务原子性**：涉及多个表（如媒体 + 标签）的更新必须使用 `@Transaction`。
- **[IDEMPOTENCE] 幂等性**：重复扫描同一文件不应产生多条记录，必须根据文件路径或 Hash 进行 `OnConflictStrategy.REPLACE`。
- **[LAZY_LOAD] 延迟加载**：严禁在 Repository 的 `allMedia` Flow 中加载大图字节数组，仅加载 URI 和元数据。
- **[PRIVACY] 本地存储**：本模块管理的数据必须 100% 本地化，禁止引入云端同步依赖。

## 2. 技术实现规范 (Technical Implementation)

### 2.1 Room 数据库规范
- **Entity 定义**：必须使用 `@Entity(tableName = "...")`明确指定表名
- **DAO 接口**：所有查询方法必须返回 `Flow<List<T>>` 以支持 UI 自动刷新
- **版本管理**：修改 Entity 后必须立即更新 `AppDatabase.kt` 的版本号并处理 Migration

### 2.2 DataStore 偏好设置
- **Key 命名规范**：使用类型安全 API，如 `intPreferencesKey("PREF_BEAUTY_LEVEL")`
- **Flow 暴露**：通过 `userPreferences.data.map{}`将偏好设置转换为 Flow 流
- **Repository 封装**：所有读写操作必须经过 Repository 层

### 2.3 Repository 层职责
- **位置**：所有 Repository 实现必须位于 `data/repository/`
- **接口暴露**：必须实现 `domain/repository/` 定义的接口
- **线程管理**：所有数据库查询必须在 `Dispatchers.IO` 执行
- **过渡红线（2026-11-07 分层止血期）**：`domain/` 禁止新增对 `data/repository/` 具体实现的 import。现存例外（`domain/person/PersonQueryResolver`、`domain/memory/MemoryContextProviderImpl`、`domain/tag/TagGenerationScheduler` → `PersonRepository`）为迁层试点过渡态，后续批次以 `domain/repository/` 接口抽象消解；新代码一律走接口，不得效仿。

## 3. Agent 执行规约 (Execution Rules)

- 修改 Room Entity 后，必须立即建议更新 `database/AppDatabase.kt` 的版本号，并处理 Migration
- 所有的 `repository` 实现必须位于 `data/repository/`，并暴露给 `domain/repository/` 的接口
- **[PERF]** 数据库查询严禁在主线程执行，必须通过 `Dispatchers.IO`
- **[MUST]** 新增全局 Service 或 Repository 后，必须同步更新 `AppContainer.kt`

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 是否在 Room 查询中使用了 `Flow` 以实现 UI 实时自动刷新？
- [ ] DataStore 的 Key 命名是否符合规范（如 `PREF_BEAUTY_LEVEL`）？
- [ ] 是否在 UI 线程中执行了数据库操作？（必须使用 Dispatchers.IO）
- [ ] 是否正确处理了数据库迁移？（避免崩溃）
- [ ] Repository 是否实现了 domain 层定义的接口？
- [ ] 是否避免了在 Flow 中加载大图字节数组？（仅加载 URI 和元数据）

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 零云端存储 → 所有数据本地存储，不依赖云端同步
- ✅ 120fps 滚动 → Repository 仅加载元数据，不加载大图
- ✅ 隐私安全 → 用户数据 100% 本地化，无云端同步

**技术决策记录**：
- 选择 Room 而非 Realm：更好的 Kotlin 协程支持，更小的包体积
- 使用 DataStore 而非 SharedPreferences：类型安全、支持 Flow、无主线程阻塞
- Flow 替代 LiveData：冷流特性更适合数据同步场景
