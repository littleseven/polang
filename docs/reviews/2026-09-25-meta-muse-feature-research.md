# Meta Muse 客户端功能调研——面向 PoLang 的借鉴分析

> **调研日期**: 2026-09-25（Muse 上线后第 17 天，信息仍在快速演进，本文快照仅供参考）
> **调研方式**: 4 路并行网络调研（产品全景 / 技术架构 / 交互 UX / 国内上手+模型价格），关键结论经多源交叉验证；PoLang 侧基线取自 `PRODUCT.md` v3.0、`CAPABILITY_REGISTRY.md` v1.4、`AGENT_ARCHITECTURE.md` v4.1、`ADR-014`（2026-09-25 定稿）
> **核心目的**: 为 PoLang 功能借鉴服务——对标 Muse 的能力与交互，评估哪些能用现有架构（Koog + Kimi/GLM 远程推理 + 端侧 MNN）低成本复刻
> **性质**: 外部产品调研，非承诺性规划；遵循「交付即清理」约定，不需要时可直接删除

---

## 1. Muse 速览（一页）

| 维度 | 事实 | 置信度 |
|---|---|---|
| 是什么 | Meta 2026-09-08 官宣并上线的「个人 AI Agent」——从「给答案的 AI」转向「替你干活的 AI」 | ✅ 官方 |
| 平台 | iOS / Android（`com.facebook.aura`）/ Web（muse.ai）/ WhatsApp 内嵌；Mac 版 9 月中旬跟进；AI 眼镜 "coming soon"；掌上硬件 Muse Charm 已公布（12 月发货） | ✅ 多源 |
| 可用地区 | **仅美国（18+）**，初期邀请码制；英国/欧盟/加拿大无官方时间表 | ✅ 多源 |
| 定价 | 免费层 + Muse Power $20/月 + Muse Maximum $100/月；无广告，靠交易抽佣盈利；免费层「每周约 1 亿 token」为单源 | ✅ / 单源标注 |
| 底层模型 | **Muse Spark**（Meta Superintelligence Labs 首个模型，2026-04-08 发布，闭源闭权重，当前 1.3）；1M token 上下文；**不是 Llama 5**；另有开源端侧 **Muse Glimmer**（30B，Apache 2.0，未确认内置手机 App） | ✅ 官方 |
| 核心架构 | 每用户一台云端专属 **Muse Secure VM**（浏览器+文件系统+凭据），关 App 后继续执行任务；独立 **Sentinel** 权限组件审批敏感动作 | ✅ 官方 |
| 市场表现 | 10 天登顶美区 App Store 总榜（超 ChatGPT），12 天约 250-280 万安装，Meta 股价涨 6-11% | ✅ 多源 |
| 舆情主线 | 「好用但渗人」：任务完成度获认可，隐私 creep（默认入训练、自主罗列用户兴趣、iMessage 读取争议）遭集中批评 | ✅ 多源 |

---

## 2. 功能全景

### 2.1 Agent 执行能力

- **任务执行范围**【已确认】：发邮件（Gmail 实测可读写删）、订酒店（Expedia）、购物（Shopify 全目录 + Best Buy/Gap/Sephora/Walmart/Wayfair）、填表单、日程/健康/智能家居管理；购物走 **Link by Stripe 一次性虚拟卡**（agent 看不到真卡号）。
- **跨 App 操作 = 混合四种机制**【已确认】：① 审查制官方 Connectors（Connect 2026 新增 19 家：PayPal/Walmart/Instacart/Notion/GitHub 等）；② VM 内浏览器代理（开页/填表/比价）；③ Mac computer use（可驱动 Mac 上任意 App）；④ **Custom Connector——Muse 自己写代码对接任意第三方 API/CLI（不审查）**。
- **Amazon 已封禁 Muse**【已确认，09-21】：理由为违反 ToS、抓取凭据、未声明 agent 身份——agentic commerce 的平台博弈才刚开始。
- **「关掉 App 还在干活」**【已确认】：任务在云端 VM 持续执行，价格监控/邮件处理等长任务后台跑，**需要决策或状态变化时才回联用户**。评测普遍视其为范式转变。
- **人工坐席兜底**【单源，Reuters 独家】：代打电话等任务部分由 "human concierge" 完成（内部数据显示人工成功率 95-98%）——「全自动」的水分值得警惕。
- **开发者生态**【已确认】：Muse Connector Platform 开放申请（一周 1500+）；消费级 App **不支持 MCP**；Meta Model API（OpenAI 兼容）开放 Spark 1.1+。

### 2.2 记忆与个性化

- **跨会话长期记忆**【已确认】：「只提过一次的细节也记得」，驱动主动建议；支持 "forget" 单条遗忘与 Reset 全删；断开 connector 不追溯清除已有记忆；**Activity log 全量审计**可回查。
- **Onboarding 一键导入**【单源，细节较全】：命名 agent → 从 IG/FB/WhatsApp/Threads 一键导入既有记忆 → 勾选授权应用 → 建每日 feed → 设长期 goals。
- **记忆实现机制未披露**（存储/检索结构均无公开信息；「Memory Llama」传闻不成立）。

### 2.3 交互形态

- **外壳仍是消息流**【已确认】：官方设计「对话可产生持续状态、定时任务、浏览器动作、文件与审批」，聊天框本身不变。独立 App 底部导航含 chat + **feed**（每日个性化简报）+ **goals**（长期目标）【单源】。
- **审批交互**【已确认】：敏感动作（发邮件/支付）强制人工批准；购物须确认**订单+收货信息+支付方式三要素**；Sentinel 审批弹窗在 **App UI 而非对话流内**（防提示注入绕过）；服务级权限可随时断开。
- **实时 Avatar**【已确认，Connect 09-23】：音频驱动 Diffusion Transformer，流式 448×768@25fps、端到端约 870ms；任意参考图可驱动、跨轮次角色一致、音画唇形同步、Video Seal 水印。
- **语音**【已确认】：实时语音模式，任务后台执行时语音对话不中断。
- **多模态输入**【已确认】：聊天直接发图/截图（实测用例：发航班截图找替代航线）；屏幕共享形态未查到。
- **⚠️ Muse Image 事件**【已确认】：上传照片+描述修改的图像功能，上线约 **3 天即因「默认使用用户 Instagram 照片」引发 backlash 被撤下**——对 AI 相册团队最有警示意义的先例。
- **WhatsApp vs 独立 App**【已确认】：WhatsApp 内以联系人形式对话（功能子集），独立 App 多出 feed/goals 等界面化入口，同账号打通。

### 2.4 底层架构（公开信息）

- **端云分工**：主体在云（每用户专属 Secure VM，含 agent+数据+浏览器+凭证）；端侧开源 Glimmer 与手机 App 的关系未确认；Mac 版走本地权限直读 iMessage/Notes。
- **模型级多智能体**【已确认】：Spark 1.1 官方明确「训练其编排 multi-agent 系统」——主 agent 规划委派并行 subagent；另有系统级隔离的 Sentinel 作权限权威。
- **安全细节**【已确认】：邮箱 connector 用「确定性过滤+分类器」滤除 OTP/重置链接；凭证占位符在网络边界替换；漏洞赏金最高 $30 万（含 prompt injection）；用户自持密钥的 Confidential VM 年内推出。
- **基准**：Meta 自报 HLE 58% / FrontierScience 38%；Artificial Analysis 独立测 Spark 1.3 (max) 62 分；GAIA/tau-bench 等无权威公开成绩。
- **隐私限制**【已确认】：当前 Meta 运营需要时**仍可访问 VM 数据**；对话默认用于训练（可 opt-out，NBC 专门教用户退出）。

---

## 3. 借鉴矩阵（核心章节）

### 3.0 前提：业务边界

**PoLang 的非目标（PRODUCT.md §3）明确「不做跨应用调用、不做系统级自动化」**，而 Muse 的核心卖点恰是跨 App 执行与支付。因此本矩阵的原则是：**借交互范式与技术模式，不越业务边界**——Muse 的任务范式映射到 PoLang 的相册域长任务（打标/整理/批量编辑），而非跨界执行。这也符合 PoLang「不与大厂正面军备赛、差异化锚定对话式 Agent + 记忆图谱 + 端侧隐私」的既定战略（2026-08-16 决策）。

### 3.1 矩阵总表

图例：改造量为模块级粗估（含 UI/数据/测试）；推理成本按 GLM-5.3 官方价估算（见附录 B）；优先级综合价值/成本比。

| # | Muse 能力 | PoLang 现状 | 差距 | 复刻路径 | 改造量 | 推理成本 | 红线检查 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| 1 | 后台长任务 + 「需要决策才回联」 | TAG 扫描/批量编辑已后台执行（`TagScanOrchestrator`），但无统一任务抽象与完成通知 | 任务不是一等公民 | TaskRegistry + chat 内任务进度卡 + 完成回联（通知栏/聊天流） | 中：新 registry + 卡片 UI + Service 通知，约 500-800 行 | ≈0（端侧执行；触发时 1 次远程调用） | ✅ 无碰撞（媒体处理全端侧） | **P0** |
| 2 | 审批三要素卡（订单/收货/支付） | `CommandRisk` 三级分级 + JS 写通路弹窗确认（Tier A） | 对话直调路径确认弱；无要素聚合展示 | DESTRUCTIVE 批量操作前聚合卡：「将删除 N 张 · 含 X 张收藏 · 回收站可恢复」 | 低：约 100-200 行 UI | 0 | ✅ 无 | **P0** |
| 3 | Activity log 全量审计 | chat 历史存在（Room）但非结构化审计 | 无统一 agent 操作审计流 | `agent_actions` Room 表（命令/风险级/结果/时间）+ 设置页查看 | 低-中 | 0 | ✅ 无 | P1 |
| 4 | 每日 feed 简报 | 回忆页 + `run_gallery_script` 统计能力 + ADR-014 RICH_HTML 卡片已定方向 | 无周期性主动产物 | WorkManager 定时生成周报/简报 → L1 模板卡（统计/回忆/整理建议） | 中：依赖 ADR-014 落地，顺势挂车 | 低（统计纯端侧 JS；文案 1 次远程调用 ≈¥0.01-0.05） | ✅ 相册聚合摘要可走远程（ADR-008 设计内） | **P1**（ADR-014 后） |
| 5 | 记忆主动化（基于记忆主动建议） | `memory_facts`/`person_relations` 被动 recall | 无主动触发路径 | PRODUCT.md P2「主动建议」落地：进相册/回忆页时按记忆+相册事件触发（「去年今日」「和小宝的合照又多了」） | 中 | 低 | ✅ 无 | P2 |
| 6 | Onboarding 叙事（一键导入记忆） | 首次打标扫描即冷启动 | 无包装，扫描期体验生硬 | 扫描进度叙事化（「AI 正在认识你的相册」）+ 能力渐进揭示 | 低：纯 UX | 0 | ✅ 无 | P2 |
| 7 | 多模态发图分析 | ✅ 已有（图片消息 → 分析/对话式编辑） | — | 无需借鉴；属已对齐项 | — | — | — | 已对齐 |
| 8 | Connector / 跨 App / 支付 / 代打电话 | 非目标 | — | **不借**（PRODUCT.md §3） | — | — | — | 不借 |
| 9 | Custom Connector（agent 自写代码对接） | QuickJS 沙箱（12 只读 handler；写操作待做） | LLM 产代码 与 ADR-014 D5「LLM 永不产代码只产数据」铁律冲突 | **不借**，维持铁律；仅沿「白名单脚本库」方向演进 | — | — | ⚠️ 记录张力 | 不借 |
| 10 | 实时 Avatar（音频驱动 DiT） | 无；语音线已降级默认关（2026-08-19，2026-09-20 定性「无语音输入需求」） | — | **不借**（与产品方向相斥） | — | — | — | 不借 |
| 11 | 每用户云 VM | 编排全端侧；Ktor 定位网关不做编排（`server/README.md`） | — | **不借**（无需求；端侧媒体任务是天然优势） | — | — | — | 不借 |
| 12 | WhatsApp 内嵌形态 | IM 远程控制线（飞书/Telegram RemoteChannel）同构但已冻结 | — | 保持冻结；架构已同构无需动作 | — | — | — | 不借 |

### 3.2 重点项展开

**#1 任务卡范式（P0，性价比最高）**
Muse 最受认可的范式转变是「关掉 App 还在干活 + 需要决策才回来」。PoLang 的对应物不是云 VM，而是**端侧长任务**：全量打标（数千张 × 3-Pass）、批量编辑、整理中心清理——这些天然是「分钟级后台任务」，且**零隐私成本**（Muse 做不到的：任务数据不出端）。
落地形态：
- `AgentTaskRegistry`（:shared commonMain 纯逻辑 + androidMain Service 桥），统一登记 扫描/批量编辑/整理 三类任务（`tag.scan_status` 已有会话状态可吸收）
- chat 内**任务卡**（复用 ADR-014 预览卡容器）：进行中进度条 → 完成后回渲染结果摘要
- 完成回联：前台通知（批量任务完成/失败/需决策）
差异化叙事：**「隐私版 Agent 任务」——同样的后台任务范式，任务数据 100% 不出端**。

> **落地跟进（2026-09-25）**：工程师模式场景已立项 spec——`docs/superpowers/specs/2026-09-25-engineer-task-card-design.md`（任务卡 + 任务中心页）。spec 决策 D1/D2 相对本节有两点收敛：任务卡改为**原生 Compose**（不复用 ADR-014 预览卡容器，进度实时刷新与静态 HTML 语义相斥）；相册域 TaskRegistry 暂不捆绑、仅保持组件通用形状。

**#2 审批三要素卡（P0，成本最低）**
Muse 购物确认三要素（订单/收货/支付）的本质是**把「将要发生什么」的要素聚合到一张卡上再要授权**。PoLang 的 DESTRUCTIVE 操作（`delete_media` 批量删除）可直接套用：卡片聚合「数量 / 收藏占比 / 可恢复性（回收站编排 `TrashSessionController` 已支持）」。已有 `CommandRisk` 分级与 Tier A 确认体系，只是把确认信息从散点文本升级为结构化卡片。
顺带正名：Muse 的 Sentinel 把审批弹窗放 **App UI 而非对话流**（防提示注入），与 PoLang 现有 Tier A 设计同构——PoLang 已走在正确方向，Muse 提供了外部佐证。

**#4 每周相册简报（P1，技术全部就绪）**
Muse 的 feed（每日简报）映射到 PoLang = 相册周报：本周新增 N 张 / 人物 Top / 整理建议（截图清理）/ 回忆钩子。技术栈现成：`run_gallery_script` 只读 handler 已能产出全部统计 → L1 模板卡（ADR-014）渲染。唯一新增是 WorkManager 定时触发 + 模板。**建议等 ADR-014 实现 spec 落地后顺车做**，避免为它单独开 WebView 管线。

### 3.3 明确不借的项（及理由存档）

- **跨 App/支付/电话**：非目标 + 平台博弈风险（Amazon 封禁 Muse 即前车之鉴）+ 合规面不可控。
- **Custom Connector**：Muse 敢让 agent 写代码是因为有受控 VM 环境；PoLang 的 QuickJS 沙箱面向**预置脚本**，ADR-014 D5 已裁决「LLM 永不产代码只产数据」——该铁律应维持，安全收益大于表达力损失。
- **实时 Avatar / 语音**：语音线 2026-08-19 已降级、2026-09-20 定性无需求；Avatar 是重投入方向且与相册场景弱相关。
- **云 VM**：PoLang 的任务都是端侧媒体操作，无服务化诉求；Ktor 保持「网关不做编排」定位（ADR-005 精神）。

### 3.4 反例警示：Muse Image 三天下架 ↔ ADR-008 正确性

Muse Image 因「默认使用用户 Instagram 照片」上线 3 天即被舆论撤下；Muse 整体隐私舆情（默认入训练、iMessage 读取争议、「Better at Surveilling Than Helping Me」）集中于**媒体与通信数据的云端处理**。PoLang 的 ADR-008（媒体文件 100% 端侧，文本/元数据/聚合摘要才可远程）恰好系统性规避了这类雷区。
**结论：这不是「不能借鉴」，而是 PoLang 路线的外部验证——「端侧优先」应从工程约束升格为对外表达的产品差异化卖点**（对照 Muse 的 creep 舆情，PoLang 每个远程调用都不携带媒体原文）。

---

## 4. 上手体验路径（压缩版）

> 目的：实际摸一遍 Muse 交互做一手验证。**调研结论显示免费档足够**，不必订阅。

1. **网络（最关键）**：Muse 有 IP + 账号双重地区检查，**香港出口不满足**——现有 HK 服务器/SOCKS 桥不够用，需美国 VPS（$5-10/月）或商业 VPN（部分 VPN IP 已被拉黑，免费 VPN 基本无效）。
2. **下载**：iOS 美区 Apple ID 直接可下（最顺）；Android 美区 Play 或侧载 `com.facebook.aura`（APKPure/APKCombo 有 9/17、9/22 两版，无签名锁，但登录仍查地区）；Mac 版 9 月中旬已出，Intel Mac 兼容性未查到。
3. **注册**：新邮箱（从未绑过 Meta 服务）注册 Meta 账号，**不必绑 Facebook/Instagram**；全程美国 IP + 无痕窗口 + 清 Meta Cookie；优先网页端 muse.ai（App 会读 GPS）。
4. **费用**：白嫖档总成本 ≈ ¥20-80/月（仅美国出口）；Power $20≈¥142/月、Maximum $100≈¥710/月；支付能力（Link by Stripe 虚拟卡）对国卡友好度未验证，且 Amazon 已封禁。
5. **预期管理**：V2EX 一周实测帖评价平平（「这不就是国产某包手机助手吗」）；另有邀请码机制（48h 内激活双方得 10 亿 token，单源）。**研究价值（交互一手体验）> 使用价值**。

---

## 5. 结论：Top 借鉴项与建议路线

| 排序 | 借鉴项 | 一句话理由 | 建议时点 |
|---|---|---|---|
| 1 | **任务卡范式**（后台任务+进度卡+完成回联） | Muse 最受认可的范式，PoLang 端侧任务天然适配，零隐私成本，差异化最强 | 立即可做（不依赖 ADR-014） |
| 2 | **审批三要素卡** | 成本最低（~百行 UI），强化既有 CommandRisk 体系，防呆直接收益 | 立即可做 |
| 3 | **Agent 审计日志** | 记忆/任务/审批三件套的信任基础设施 | 随 #1 顺带 |
| 4 | **每周相册简报 feed** | 技术全部就绪（JS 统计 + ADR-014 卡片），产品感知度高 | ADR-014 实现 spec 后 |
| 5 | **记忆主动化** | 已在 PRODUCT.md P2 规划内，Muse 验证了价值方向 | P2 按既定节奏 |

**两条战略级收获**：
1. **ADR-008 获外部验证**（Muse Image 事件）：端侧隐私从约束升格为卖点。
2. **审批外置获外部佐证**（Sentinel 设计）：PoLang Tier A 确认体系方向正确，继续深化而非推倒。

**不建议投入**：跨 App 执行/支付、Custom Connector、实时 Avatar、云 VM（理由见 §3.3）。

---

## 附录 A：主要来源

**官方**：[Meta Newsroom: Introducing Muse (09-08)](https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent) · [Muse Spark 首发 (04-08)](https://ai.meta.com/blog/introducing-muse-spark-scaling-towards-personal-superintelligence) · [Spark 1.1 / Model API (07-09)](https://ai.meta.com/blog/introducing-muse-spark-meta-model-api) · [Realtime Avatar (09-23)](https://research.meta.ai/blog/bringing-your-muse-to-life)

**媒体报道**：[TechCrunch: Connect 2026 Muse 汇总 (09-24)](https://techcrunch.com/2026/09/23/everything-new-coming-to-metas-ai-agent-muse) · [The Verge 上手 (09-10)](https://www.theverge.com/tech/993391/meta-muse-ai-hands-on) · [GeekWire: Amazon 封禁 Muse (09-21)](https://www.geekwire.com/2026/amazon-blocks-metas-muse-ai-assistant-in-new-standoff-over-agentic-shopping) · [The Verge: Muse Charm (09-23)](https://www.theverge.com/tech/999750/muse-charm-meta-ai-hardware) · Reuters（人工坐席独家）· NYT / WSJ / Wired / CNBC（舆情主线）

**上手实操**：[Comparitech 翻墙实操 (09-15)](https://www.comparitech.com/blog/vpn-privacy/access-meta-muse-anywhere) · APKCombo（`com.facebook.aura`）· V2EX / LinuxDo 实测帖

**单源项**（引用需谨慎）：免费层周 token 限额（DeepLearning.AI）· feed/goals 导航与 onboarding 细节（Mika Reyes 09-10）· Behemoth 仅研究用途（TheLec）· 邀请码奖励（V2EX）

## 附录 B：推理成本基准（2026-09-25 官方定价页直抓）

| 模型 | 输入（命中/未命中）¥/M token | 输出 ¥/M token | 定位 |
|---|---|---|---|
| Kimi K3（旗舰） | 2 / 20 | 100 | 强推理主力 |
| Kimi K2.7-Code | 1.3 / 6.5 | 27 | 编码 |
| **GLM-5.3（旗舰）** | 2 / 8 | **28** | **agentic 性价比最优（输出比 K3 便宜 3.6×）** |
| GLM-5.3-Flash | 0.23 / 0.8 | 2.8 | 高频轻任务 |
| DeepSeek V4-Pro | $0.022-0.044 / $0.66-1.32（峰谷） | $1.98-3.96 | 网关现有路由 |

**场景估算**（粗估）：一次 3 步 tool_calls 任务 ≈ 累计 25K in + 3K out → GLM-5.3 ≈ **¥0.13/次**、Flash ≈ ¥0.013/次、K3 ≈ ¥0.34/次；日均 20 次任务/月 → GLM-5.3 ≈ ¥78/月。个人使用走 Kimi Coding Plan（$19-199/月国际档）/ GLM 包月池则边际成本近零；PoLang app 内置路由（api.polang.net → DeepSeek/TokenHub）另按网关侧计量。
来源：[Kimi 定价](https://platform.moonshot.cn/docs/pricing/chat) · [智谱定价](https://docs.bigmodel.cn/cn/guide/start/pricing.md) · [DeepSeek 定价](https://api-docs.deepseek.com/quick_start/pricing)
