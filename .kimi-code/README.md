# polang · kimi-code 原生 Hooks

> 三个观察型/异步 Hook，把「改文件后检查、turn 结束 GLM 交叉审查、完成桌面通知」自动化。
> 设计 spec 已随交付清理（v2 Option A，git 历史可查）。
> 实现计划已随交付清理（2026-08-22 文档整理，git 历史可查）。

## 这是什么

kimi-code 有原生 hooks(`HookEngine`/`runHook`)，但 `config.toml` **只有 user 级**(经二进制反编译核实，无项目级回退)。所以本目录采用 **Option A**：

- **仓内 SSOT**：所有脚本 + 配置片段(`hooks.toml`)落在仓内 `.kimi-code/`。
- **受管追加**：`install-hooks.sh` 把 `hooks.toml` 内容以 marker 包裹**幂等追加**进 `~/.kimi-code/config.toml`，可一键删。
- **cwd 自隔离**：每条 hook 的 `command` 是相对路径 `[ -x .kimi-code/hooks/X.sh ] && … || true`。kimi 以 cwd = 会话工作目录执行；只有 polang 根下脚本存在 → 其他仓库静默 no-op，零副作用。

## 四个 Hook

| 事件 | 脚本 | 行为 | 阻断? |
|---|---|---|---|
| `PostToolUse` (`Edit\|Write`) | `hooks/post-edit-check.sh` | 改 `.kt`/`.java`→i18n 硬编码检查；改 `docs/*.md`/`PRODUCT`/`FEATURES.md`→doc-sync 摘要 | 否(观察型，stdout 回显) |
| `Stop` | `hooks/stop-auto-review.sh` | 有 `.kt`/`.xml` 改动且 >10min 未审→后台异步触发 GLM 交叉审查(`kimi-review`) | 否(异步派活后秒返) |
| `SubagentStop` | `hooks/notify.sh subagent` | osascript 桌面通知「子agent 完成」 | 否 |
| `Notification` | `hooks/notify.sh notification` | 从 payload 取 `title`/`body` 组装 osascript 通知 | 否 |

全部 **fail-open**：任何脚本异常/超时都放行，绝不阻塞 agent 主流程。

## 安装 / 卸载

```bash
# 安装(把 marker 块受管追加进 ~/.kimi-code/config.toml；幂等)
.kimi-code/install-hooks.sh install

# 卸载(干净移除 marker 块，user config 其余内容逐字节不变)
.kimi-code/install-hooks.sh remove
```

`install` 会跑 `kimi doctor` 校验配置。重复 `install` 是 no-op；在没装过 block 的 config 上跑 `remove` 也是 no-op。

## 调试

所有脚本认环境变量 `KIMI_POLANG_HOOK_DEBUG=1`，开启后把决策打到 stderr(不影响 kimi 行为)：

```bash
echo '{"hook_event_name":"PostToolUse","tool_input":{"path":"/tmp/x.kt"}}' \
  | KIMI_POLANG_HOOK_DEBUG=1 .kimi-code/hooks/post-edit-check.sh
```

Hook B(异步审查)的运行态文件 `.kimi-code/.last-review`(时间戳)与 `.kimi-code/.last-review.log`(审查输出)已加入 `.gitignore`。

### Hook B 的三重递归/成本防护

1. **env 哨兵**：派活时设 `KIMI_POLANG_HOOK_REVIEW=1`，审查进程自身的 Stop 不再触发审查。
2. **代码改动 guard**：`git status --porcelain` 含 `*.kt`/`*.xml` 才触发(纯问答轮跳过)。
3. **600s 冷却**：距上次审查 <10min 跳过(避免每轮堆 GLM 调用)。

> 隐私红线 `[PRIVACY]` **不依赖** hook——hook 是 fail-open，不能当唯一闸门；仍由权限审批 + 人工兜底。

## 文件清单

```
.kimi-code/
├── README.md                  ← 本文件
├── hooks.toml                 ← [[hooks]] 配置 SSOT(受管追加源)
├── install-hooks.sh           ← 幂等追加/移除 user config 的 marker 块
└── hooks/
    ├── post-edit-check.sh     ← Hook A 入口
    ├── stop-auto-review.sh    ← Hook B 入口(异步)
    ├── notify.sh              ← Hook C 入口
    └── lib/
        └── i18n-hardcode.sh   ← i18n 硬编码检测
```
