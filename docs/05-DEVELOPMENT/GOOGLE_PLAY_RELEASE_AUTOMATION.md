# Google Play 发布自动化（Release Automation）

> **版本**：1.0
> **最后更新**：2026-08-20
> **适用范围**：PoLang Android（`com.mamba.picme`）Google Play 发布链路
> **底层组件**：[gradle-play-publisher（GPP）4.1.1](https://github.com/Triple-T/gradle-play-publisher)（插件 `com.github.triplet.play`，官方维护模式但功能稳定，支持 AGP 9）

## 1. 总览

```
release-automation.sh          play-publish.sh                Play Console
（版本号/CHANGELOG/git tag）→（GPP 封装：上传/文案/晋升）→（审核 → 上架）
        │                            │
        └─ CI: .github/workflows/release.yml（tag v* 触发，只发 internal）
```

- **构建**：`./scripts/build.sh aab`（release 签名，`POLANG_RELEASE_*` 环境变量）
- **发布**：`./scripts/play-publish.sh`（GPP 封装，默认 internal 轨道）
- **商店文案 SSOT**：`androidApp/src/main/play/listings/{en-US,zh-CN,zh-TW}/`（自 `google-play-listing/` 迁移，文件名不变）
- **发布说明**：`androidApp/src/main/play/release-notes/<lang>/<track>.txt`（≤500 字符）

## 2. 一次性准备（已完成项打勾）

- [x] Play Console 创建 app 记录并**手动上传首版 AAB**（Play API 不能创建 app 记录，首传必须手动）
- [x] GCP 项目启用 **AndroidPublisher API** → 创建 service account + JSON key
- [x] Play Console → 用户和权限 → 邀请 service account 邮箱 → 授予「发布应用（含测试轨道与正式版）+ 管理商店文案」权限（2026-08-20 完成；当日已实测 production 晋升成功。CI 的 production 拦截靠 `release.yml` Guard rail，不靠权限收口）
- [x] 本地：`export POLANG_PLAY_SERVICE_ACCOUNT_JSON=/path/to/key.json`（**JSON key 禁止入库**；当前已写入 `~/.zshrc`，2026-08-20 完成）
- [ ] CI：GitHub Secrets 配置 `ANDROID_PUBLISHER_CREDENTIALS`（JSON 全文）+ `POLANG_RELEASE_STORE_PASSWORD` / `POLANG_RELEASE_KEY_ALIAS` / `POLANG_RELEASE_KEY_PASSWORD`
- [x] 验证连通：`./gradlew :androidApp:bootstrapReleaseListing`（注意：会用线上内容重置本地 `play/` 目录）（2026-08-20 验证通过）

> **bootstrap 实测（2026-08-20）**：
> - 任务尾段对 `inappproducts` 端点报 `403 Please migrate to the new publishing API`——Google 已废弃该端点，
>   **无内购商品的 app 可忽略**，listing/图文/release notes 均已正常拉取（任务退出码非零属 GPP 已知问题）
> - Console 线上默认语言已于 **2026-08-20 改为 en-US**（面向海外市场；早期为 zh-CN）。
>   `default-language.txt` 必须与 Console 线上值保持一致（本地改为 en-US 已对齐），
>   默认语言只能在 Console 设置 → 商店设置里改，API/GPP 均不支持；
>   改默认语言会影响 Console 全 listing 关联，改动后务必同步本地文件
> - 线上存在本地没有的 **zh-HK** listing 及 zh-CN graphics，bootstrap 后已入库对齐

> 若开发者账号为 2023-11 后注册的**个人账号**：production 权限需 closed testing 满 12 名测试者 / 14 天，提前规划。

## 3. 日常操作

### 3.1 发新版本到 internal（本地）

```bash
./scripts/release-automation.sh --type patch --aab        # 版本号+CHANGELOG+tag+构建 AAB
./scripts/play-publish.sh --notes /tmp/notes.txt          # 上传 internal（默认 completed）
```

### 3.2 发新版本到 internal（CI）

```bash
git tag v1.0.37 && git push origin v1.0.37                # tag 触发 release.yml
# 或 GitHub Actions 页面手动 workflow_dispatch（可选 track/status）
```

### 3.3 只同步商店文案

```bash
./scripts/play-publish.sh --listing-only              # CI / 海外网络：GPP 一把梭（文本+图像）
./scripts/play-publish.sh --listing-only --resumable  # 本地直连：文本 GPP + 图像 Python 增量通道
```

> 本地直连网络务必加 `--resumable`：GPP 对 graphics 目录无 diff 全量上传，
> zh-CN 33MB 累计会在长连接上卡死（2026-08-20 实测 publishListing 挂起 10 分钟超时）。
> 组合通道会把 graphics 暂存出 play/ → GPP 只传文本 → Python 脚本逐张上传图像
> （远端 sha256 比对，只传增量，不删除远端图像）→ 自动恢复目录。

### 3.4 封闭式测试（alpha）

```bash
# 方式一：直接发新版本到 alpha
./scripts/play-publish.sh --resumable --track alpha

# 方式二：把 internal 已审核版本晋升（推荐，免重传）
./scripts/play-publish.sh --promote --from-track internal --track alpha --status completed
```

> 注意：closed track 需在 Play Console「封闭式测试」轨道配置**测试者名单**（邮箱列表或 Google Group），
> 否则版本对测试者不可见；测试者通过 Console 生成的邀请链接加入。

### 3.5 开放式测试（beta）

```bash
# 方式一：直接发新版本到 beta
./scripts/play-publish.sh --resumable --track beta

# 方式二：从 internal 晋升
./scripts/play-publish.sh --promote --from-track internal --track beta --status completed
```

> open track 无需名单，Play Console 提供**公开 opt-in 链接**，任何人点击即可加入测试。

> **实战记录（2026-08-20）**：v10037（1.0.37）经 `--promote --from-track internal --status completed`
> 同日晋升至 alpha 与 beta 两条轨道，GPP `promoteReleaseArtifact` 一次成功（晋升是纯 API 小请求，
> 直连网络无大文件上传问题，无需 `--resumable`）。

### 3.6 晋升到 production（人工，CI 禁止直发）

```bash
# 默认：直接全量发布（completed），提交后自动进审核，无需 Console 再确认
./scripts/play-publish.sh --promote --from-track internal --track production --status completed

# 可选：先发 draft 草稿，Console 复核后手动 roll out（2026-08-20 起用户已弃用该姿势）
./scripts/play-publish.sh --promote --from-track internal --track production --status draft

# 方式三：Play Console 手动 promote
```

> **Console 侧唯一要确认的设置**：设置 → **托管式发布（Managed publishing）** 必须**关闭**——
> 若开启，即使 `completed` 状态发布也会停在 Console 等手动「发布」按钮。
>
> 注意：service account 实际持有 production 发布权限（2026-08-20 实测晋升成功），
> CI 的 production 拦截完全依赖 `release.yml` 的 Guard rail step，勿删。

### 3.7 分阶段发布与比例调整

```bash
./scripts/play-publish.sh --promote --from-track internal --track production \
    --status inProgress --user-fraction 0.1               # 10% 灰度
./scripts/play-publish.sh --update-rollout production --user-fraction 0.5
./scripts/play-publish.sh --promote --from-track production --track production \
    --status completed                                    # 收尾必须 completed（userFraction=1.0 非法）
```

## 4. 认证机制

| 场景 | 环境变量 | 内容 |
|------|----------|------|
| 本地 | `POLANG_PLAY_SERVICE_ACCOUNT_JSON` | service account JSON **文件路径**（`play {}` 块读取） |
| CI | `ANDROID_PUBLISHER_CREDENTIALS` | JSON **全文**（GPP 内置读取，不落盘） |

两者都未设置时 `play-publish.sh` 直接报错退出。

## 4.5 分块续传上传通道（resumable，2026-08-20 实战新增）

**背景**：直连网络下 GPP（JVM Google API 客户端）上传 ~60MB AAB 会在 ~1 分钟处被中间设备掐断
（`Unexpected end of file from server`，三次重试同一位置失败）；小请求（bootstrap/listings）不受影响。

**方案**：`scripts/play-upload-resumable.py`（Python 标准库 + openssl 签名 JWT，无三方依赖），
实现 Play API resumable upload 协议——8MB 分块、单块失败查询断点续传（指数退避，单块最多 10 次）。

```bash
./scripts/play-publish.sh --resumable                 # 与默认模式同参数，仅上传通道不同
./scripts/play-publish.sh --resumable --track alpha --status inProgress --user-fraction 0.25
```

实战记录（v10037 internal）：上传 57MB 中断 2 次均自动续传完成；**commit 偶发 400**
（上传完成后服务端 bundle 校验有异步窗口，立即 commit 会抢跑）——用 `--edit-id <id> --skip-upload`
复用原 edit 重试 commit 即可成功，无需重传。

**listing 图像通道（2026-08-20 扩展）**：`play-upload-resumable.py --images <listings目录>`
逐张 media upload（单文件 ≤3MB 无需分块），远端 sha256 比对只传增量、不删远端图像；
已被 `play-publish.sh --listing-only --resumable` 自动编排（见 §3.3）。

**选型建议**：本地直连网络默认就用 `--resumable`；CI（GitHub Actions 海外 runner）用 GPP 默认通道即可。

## 5. 硬约束与常见坑

| 约束 | 说明 |
|------|------|
| versionCode 严格递增 | 同 code 重传报错；冲突可选 GPP `ResolutionStrategy.AUTO` 自动抬 |
| 文案字符上限 | title 30 / short 80 / full 4000 / release notes 500（脚本预检 notes 长度） |
| `userFraction=1.0` 非法 | 灰度收尾改 `--status completed` |
| edit 并发冲突 | Console 手动改动会使未 commit 的 API 事务失效；发布窗口内不要在 Console 改文案 |
| production 首次被拒 | 新 app 需先在 internal/closed 轨道发过至少一版 |
| 审核时长 | internal 几分钟~几小时；production 数小时~2 天（新账号可达 7 天） |

## 6. CI 工作流（`.github/workflows/release.yml`）

- 触发：tag `v*` 推送，或 `workflow_dispatch`（可选 track/status）
- 护栏：`production` 轨道直接报错退出（Guard rail step）
- 环境：`environment: google-play`（可在 GitHub 设置里加人工审批门）
- 产物：AAB 作为 workflow artifact 保留 30 天

## 7. 相关文档

- 商店文案与 ASO：`google-play-listing/README.md`（含迁移说明）、`google-play-listing/keyword-ledger.md`（关键词台账 SSOT；原 ASO 设计 spec 已随交付清理，git 历史可查）
- 构建与签名：`scripts/build.sh` 头注释、`androidApp/build.gradle.kts` signing 段
- 版本发布：`scripts/release-automation.sh` 头注释
