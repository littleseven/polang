# 调试工具模块技术实现规范 (Debug Tools)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**: 提供测试数据生成、日志查看与应用状态监控等开发调试能力

**主要维护者**: 项目开发者

**阅读对象**: RD、QA、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[DEV_ONLY] 仅限开发环境**: 调试功能仅在 Debug 构建中启用，Release 包必须完全移除
- **[LOCAL] 纯本地操作**: 所有测试数据生成与日志记录均在设备端完成，严禁上传云端
- **[PERF] 并发控制**: 图片下载与人脸检测必须限制并发数（Semaphore = 2），避免资源耗尽
- **[SAFETY] 内容过滤**: 自动跳过无效图片或敏感内容，确保测试数据合规
- **[I18N] 多语言支持**: UI 文案必须提取到 strings.xml，支持中英文切换

## 2. 技术实现规范 (Technical Implementation)

### 2.1 测试数据生成器 (SampleDataGenerator)

**技术规范**:
- **单例模式**: 使用 `object SampleDataGenerator` 确保全局唯一实例
- **状态管理**: 使用 `MutableStateFlow` 暴露生成状态（isGenerating、isPaused、progress、logs）
- **并发控制**: 使用 `Semaphore(2)` 限制同时下载任务数，避免网络拥堵
- **暂停/恢复**: 通过 `_isPaused` 标志位实现协程挂起与恢复
- **日志环形缓冲**: 最多保留 200 条日志，超出后自动删除最旧记录

**代码示例**:
```kotlin
object SampleDataGenerator {
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()
    
    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()
    
    private val _progress = MutableStateFlow("")
    val progress = _progress.asStateFlow()
    
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()
    
    fun pause(context: Context) {
        _isPaused.value = true
        _progress.value = context.getString(R.string.pause)
        addLog("Action: Paused")
    }
    
    fun resume() {
        _isPaused.value = false
        addLog("Action: Resumed")
    }
}
```

### 2.2 图片搜索与下载

**技术规范**:
- **关键词分类**: 
  - 人物类：50+ 明星姓名列表
  - 风景类：50+ 自然景观关键词
  - 泳装/性感类：组合关键词（明星 + 场景）
- **并行搜索**: 使用 `async` 并发请求多个搜索引擎接口
- **重试机制**: 下载失败时最多重试 3 次，每次间隔递增
- **User-Agent 轮换**: 随机切换 UA 模拟不同设备，降低被封禁风险
- **文件命名**: 格式为 `{PREFIX}_{KEYWORD}_{TIMESTAMP}.jpg`，便于追溯

**代码示例**:
```kotlin
private suspend fun downloadWithRetry(
    url: String,
    context: Context,
    fileName: String,
    maxRetries: Int = 3
): File? {
    repeat(maxRetries) { attempt ->
        try {
            val file = File(context.cacheDir, fileName)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", getRandomUA())
                connectTimeout = 10000
                readTimeout = 10000
            }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                FileOutputStream(file).use { output ->
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                return file
            }
        } catch (e: Exception) {
            addLog("Download failed (attempt ${attempt + 1}): $url")
            delay((attempt + 1) * 1000L) // 递增延迟
        }
    }
    return null
}
```

### 2.2a Pexels 图库（Pexels API 测试图下载）

**技术规范**:
- **官方 API**: Retrofit 接口 `PexelsApi`（`v1/search` / `v1/curated`），Key 以 `@Header("Authorization")` 逐请求传入
- **Key 存取**: `PexelsKeyStore`（独立 SharedPreferences `debug_pexels_prefs`），仅存本地，不进日志不进 git
- **状态机**: `PexelsViewModel` + sealed `PexelsUiState`（NoKey / Loading / Ready / Error），错误用 `PexelsErrorKind` 枚举（401 清 Key 回 NoKey；429 提示限流）
- **下载链路**: 复用 `SampleDataGenerator.savePexelsPhoto()`，`TEST_PEXELS_` 前缀，兼容 `clearTestData()`
- **限流**: 免费档 200 次/小时、20,000 次/月；per_page=30
- **署名**: 页面底部常驻「Photos provided by Pexels」（API 使用条款要求）
- **设计文档**: 2026-08-06 pexels-test-image-download 设计稿（已随交付清理，git 历史可查）

### 2.3 内容审核与人脸检测

**技术规范**:
- **ML Kit 已移除（2026-08）**：`SampleDataGenerator.analyzeFace()` 现为常量 stub（返回 `FaceAnalysisResult(count = 1, maxHeightRatio = 0.35f)`），仅用于 debug 样本数据筛选，不影响主链路；ML Kit Face Detection 依赖已从 `:androidApp` 移除
- **内容过滤规则**（基于上述估算值，规则保留）:
  - 泳装/性感类：必须检测到人脸，且脸部高度占比 < 40%，皮肤区域 > 10%
  - 人物类：至少检测到 1 张人脸
  - 风景类：无人脸检测要求

### 2.4 日志窗口 (LogWindow)

**技术规范**:
- **实时滚动**: 新日志插入顶部，自动滚动到最新条目
- **过滤功能**: 支持关键词模糊搜索（忽略大小写）
- **样式区分**: 
  - 时间戳：灰色小字
  - 关键操作：加粗显示
  - 错误信息：红色高亮
- **字体选择**: 使用等宽字体 (`FontFamily.Monospace`) 提升可读性

**代码示例**:
```kotlin
@Composable
private fun LogWindow(
    filterText: String,
    onFilterTextChange: (String) -> Unit,
    filteredLogs: List<String>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = filterText,
            onValueChange = onFilterTextChange,
            label = { Text(stringResource(R.string.filter_logs)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth()
        )
        
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(filteredLogs) { log ->
                Text(
                    text = log,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (log.contains("ERROR")) Color.Red 
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
```

### 2.5 数据清理 (Clear Data)

**技术规范**:
- **范围限定**: 仅删除带有 `TEST_` 前缀的测试数据，严禁误删用户照片
- **双重确认**: 点击清除按钮时必须弹出确认对话框
- **异步删除**: 在后台线程批量删除数据库记录与文件
- **进度反馈**: 显示删除进度条与剩余数量

## 3. Agent 执行规约 (Execution Rules)

- **构建隔离**: 调试模块必须通过 `BuildConfig.DEBUG` 条件编译，Release 包不可见
- **权限最小化**: 仅申请必要的存储读写权限，不申请相机、位置等无关权限
- **网络超时**: 所有网络请求必须设置 timeout（连接 10s，读取 10s）
- **异常捕获**: 下载、检测、保存等操作必须包裹 try-catch，避免崩溃
- **资源释放**: ML Kit Detector 使用后必须调用 `close()` 释放 native 资源
- **日志脱敏**: 严禁在日志中输出用户隐私信息（如真实姓名、联系方式）
- **I18N**: 所有 UI 文案必须提取到 strings.xml

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 调试功能是否通过 BuildConfig.DEBUG 隔离？(Release 包不可见)
- [ ] 并发下载是否限制了最大数量？(Semaphore <= 3)
- [ ] ML Kit Detector 使用后是否调用了 close()？(避免内存泄漏)
- [ ] 测试数据清理是否仅针对 TEST_ 前缀？(严禁误删用户数据)
- [ ] 网络请求是否设置了超时时间？(避免无限等待)
- [ ] 日志窗口是否限制了最大条目数？(避免 OOM)
- [ ] 内容过滤是否正确应用了规则？(泳装/性感类需人脸 + 皮肤检测)
- [ ] 暂停/恢复功能是否正常？(协程挂起与恢复)
- [ ] 下载失败是否有重试机制？(最多 3 次)
- [ ] 用户点击清除时是否有二次确认？(避免误操作)
- [ ] Pexels API Key 是否仅存本地 SharedPreferences？（严禁入库/进日志）
- [ ] 401/429 是否分别走 NoKey 回退与限流提示？（不可笼统报网络错误）
- [ ] Pexels 下载图片是否带 TEST_PEXELS_ 前缀？（兼容 TEST_ 前缀清理）
- [ ] 页面是否保留「Photos provided by Pexels」署名？（API 条款要求）

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ 开发专用 → 仅 Debug 包可见，Release 包完全移除
- ✅ 纯本地生成 → 无云端依赖，所有数据在设备端创建
- ✅ 并发安全 → Semaphore 限制并发数，避免资源耗尽
- ✅ 内容合规 → 自动过滤无效或敏感图片，确保测试数据质量

**技术决策记录**:
- 选择 ML Kit 而非自定义模型：Google 官方维护，精度高，离线可用
- 使用 StateFlow 而非 LiveData：与 Compose 生态无缝集成，支持冷流
- 限制并发数为 2：平衡下载速度与系统负载，避免触发反爬机制
- 日志保留 200 条：兼顾调试需求与内存占用，采用环形缓冲策略
- 测试数据添加 TEST_ 前缀：便于批量清理，降低误删风险
