# Play 商店图 zh-CN/zh-TW v2.2.3 刷新 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **本计划为 MCP 运维 runbook（画布/导出/发布），非代码 TDD；且因仓库 mid-merge，全程零 git 提交。**

**Goal:** 将 Ardot Play Store Assets 页 zh-CN / zh-TW 两行 8 帧 + 2 张 feature-graphic 的 Screen 刷新为 v2.2.3 源帧渲染，导出装填三处目录并发布到 Google Play。

**Architecture:** 8 个共享源帧（token+语言变量绑定）→ 逐语言整批：Pass 1 zh-CN 走 UI Language 模式切换导出；Pass 2 zh-TW 走逐 TEXT 繁体字面量覆写（覆写前 live 盘点为还原基线）→ 灌入 18 个 Screen IMAGE fill → Play 帧整帧导出 1080×1920 → 装填 → SOCKS 桥发布。

**Tech Stack:** ardot MCP（batch_read/batch_edit/export_nodes/upload_images/fetch_variables）、zai OCR、ffmpeg SSIM、sips、scripts/play-upload-resumable.py。

**Spec:** `docs/superpowers/specs/2026-09-18-playstore-zh-v2.2.3-refresh-design.md`

**⚠️ 中止规则（最高优先级）:** 任一 Pass 中途失败/中断 → **先执行该 Pass 的还原步骤**（写回 English mode / 回填 baseline 字面量）再排查。源帧是共享资产，不得停留在中间态。

---

### Task 1: 预检与基线盘点

**Files:** Create: `/tmp/playstore-zh-work/`（工作目录）、`/tmp/playstore-zh-work/baseline-<slot>.json` ×8

- [ ] **Step 1.1: 建工作目录 + 本地资产预检**

```bash
mkdir -p /tmp/playstore-zh-work/{zh-cn-src,zh-tw-src,frames-zh-cn,frames-zh-tw,fg}
ls /tmp/socks-bridge.py 2>/dev/null; echo "---"
head -5 scripts/play-upload-resumable.py && grep -n "prune-types\|--langs" scripts/play-upload-resumable.py | head
ls google-play-listing/zh-CN/screenshot-captions.json google-play-listing/zh-TW/screenshot-captions.json
ls docs-site/assets/shots/zh-CN/ | head -12; ls docs-site/assets/shots/zh-TW/ | head -12
```

Expected: 工作目录建好；bridge 脚本存在与否记录（Task 6 用）；upload 脚本含 `--prune-types`/`--langs` 参数；两个 captions.json 存在；docs-site zh 目录文件名模式记录（应为 `NN-slug.jpg`）。

- [ ] **Step 1.2: 核实源帧 id（gallery/search 两处未 live 验证过）**

batch_read `parentId:"103:1"`（Gallery 页）readDepth 1 → 找到 `gallery/grid-store01` 与 `search/store01` 的实际 id（预期 300:29 / 300:539，以实读为准，后续任务统一替换）。
其余 6 源帧 id 已 live 验证：300:651、301:13、301:96、300:215、300:723、108:94。

- [ ] **Step 1.3: 核实 16 个目标 Screen id + FG 结构**

batch_read `nodeIds:["302:39","302:47","302:55","302:63","302:71","302:79","302:87","302:95","302:103","302:111","302:119","302:127","302:135","302:143","302:151","302:159","152:140"]` readDepth 2。
Expected: 每帧含 IMAGE fill 的 Screen 子节点 = root+7（zh-CN 行应为 302:46/54/62/70/78/86/94/102；zh-TW 行按实读记录）；`152:140`（zh-TW FG）的 Screen 子节点预期 161:21。记录 FG 帧宽高（en FG 用于 export scale 校准，预期 1024×500 量级）。

- [ ] **Step 1.4: 源帧语言模式可用性**

对 8 源帧逐一 `fetch_variables({nodeId})`，记录 `availableVariableModes`。
Expected: 含 `{"182:133": [...,"182:134",...]}`。任一源帧不可用 → 该帧降级：与 zh-TW 同法走「字面量覆写 + 还原」（词表对齐 values-zh-rCN），在 Task 2 中对该帧单独处理。

- [ ] **Step 1.5: 全量 TEXT 基线盘点（Pass C 还原 + zh-TW 覆写的唯一依据）**

对 8 源帧逐一 batch_read `patterns:[{"type":"TEXT"}]`、`parentId:<源帧>`、`searchDepth:20`（patterns 查询才返回全量 TEXT，structure.json 压缩会漏盘）。把每帧结果（id + characters（含 `$ref` 引用态）+ name）存 `/tmp/playstore-zh-work/baseline-<slot>.json`。
同时用 `patterns:[{"name":"(?i)perf"}]` 查 PerfRow 调试行残留 → 有则导出期 visible:false、导完还原。

- [ ] **Step 1.6: captions SSOT 读取**

```bash
cat google-play-listing/zh-CN/screenshot-captions.json google-play-listing/zh-TW/screenshot-captions.json
```

记录每槽 headline/subline 文案（Task 3/5 的 OCR 比对基准；本计划不修改它们）。

---

### Task 2: Pass 1 — zh-CN 模式切换渲染灌图

**Files:** Create: `/tmp/playstore-zh-work/zh-cn-src/*.png`；Modify(云端): 8 源帧 variableModes（瞬态）、9 个 Screen IMAGE fill（zh-CN 行 + FG 161:19）

- [ ] **Step 2.1: 切中文模式（一次 batch_edit，8 个 U 操作）**

operations（`<src>` 按 Step 1.2/1.4 实读 id；此处用已知 id 占位，执行时替换）：

```
U1=U("300:29",{variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})
U2=U("300:539",{variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})
U3=U("300:651",{variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})
U4=U("301:13",{variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})
U5=U("300:723",{variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})
U6=U("300:215",{variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})
U7=U("301:96",{variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})
U8=U("108:94",{variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})
```

检查响应 `potentialIssues`——出现 "Variable set 182:133 is not available" 即该帧降级（见 Step 1.4）。

- [ ] **Step 2.2: 导出 8 源帧 scale2**

export_nodes `nodeIds:[8源帧]` `format:png` `scale:2` `outputDir:/tmp/playstore-zh-work/zh-cn-src`（PNG ≤5/批 → 分 5+3 两次调用）。结果 nodeId→文件路径记入工作记录。
Expected: 8 个 786×1704 PNG。**空白图（文件异常小/均匀纯色）= 冷缓存套路 → 重试 export。**（尺寸验证：`sips -g pixelWidth -g pixelHeight`）

- [ ] **Step 2.3: 灌 9 个 Screen（upload_images ≤10/批，一次完成）**

items = 8 源帧导出文件 → 对应 zh-CN Screen（302:46→01、302:54→02、302:62→03、302:70→04、302:78→05、302:86→06、302:94→07、302:102→08，按 Step 1.3 实读为准）+ 01 的导出文件 → `161:19`（FG）。

- [ ] **Step 2.4: 灌图核验（结构层，不烧截图配额）**

batch_read `nodeIds:["302:46","161:19"]`（抽样 2 个）properties ["fills"]。
Expected: imageHash 已变化（302:46 不再是 `9ffdacbe…`）。

- [ ] **Step 2.5: 还原 English（显式写回，null 无效）**

一次 batch_edit，8 个 U 操作，modeId 全部 `"182:132"`（源帧 id 同 Step 2.1）。

- [ ] **Step 2.6: 还原核验**

fetch_variables 抽查 2 源帧（300:29、301:13）：variableModes 回到默认 English。

---

### Task 3: zh-CN 成品导出 + 装填 + 验收

**Files:** Create: `/tmp/playstore-zh-work/frames-zh-cn/*.png`；Modify(不提交): `androidApp/src/main/play/listings/zh-CN/graphics/phone-screenshots/01..08.png`、`androidApp/src/main/play/listings/zh-CN/graphics/feature-graphic/feature-graphic.png`、`google-play-listing/zh-CN/{screenshots/01..08.png,feature-graphic.png}`、`docs-site/assets/shots/zh-CN/*.jpg`

- [ ] **Step 3.1: 整帧导出**

export_nodes zh-CN 8 帧（302:39…302:95）`scale:1` → `frames-zh-cn/`（5+3 两批）；FG `152:128` `scale:1` → `fg/zh-cn/`。
Expected: 8 个 1080×1920 + FG（帧原生尺寸）。空白 → 重试。

- [ ] **Step 3.2: 装填三处目录**

```bash
W=/tmp/playstore-zh-work
# 按 export_nodes 返回的 nodeId→路径映射重命名（示例以 302:39=01 为准）
for pair in "302:39:01-gallery" "302:47:02-search" "302:55:03-chat" "302:63:04-people" \
            "302:71:05-person-groups" "302:79:06-chat-welcome" "302:87:07-insight" "302:95:08-privacy"; do :; done  # 执行时用实际映射表逐个 cp
cp <01..08>.png androidApp/src/main/play/listings/zh-CN/graphics/phone-screenshots/
cp <01..08>.png google-play-listing/zh-CN/screenshots/
for f in 01..08; do sips -s format jpeg <f>.png --out docs-site/assets/shots/zh-CN/<NN-slug>.jpg; done  # slug 按 Step 1.1 实录文件名
cp <fg>.png androidApp/src/main/play/listings/zh-CN/graphics/feature-graphic/feature-graphic.png
cp <fg>.png google-play-listing/zh-CN/feature-graphic.png
sips -s format jpeg <fg>.png --out docs-site/assets/shots/zh-CN/<fg-slug>.jpg  # docs-site 若有 FG 镜像则同步，无则跳过
```

- [ ] **Step 3.3: 逐帧 OCR 简体验收（硬 gate）**

对 8 成品 + FG 用 `extract_text_from_screenshot`（local path）逐帧 OCR：
1. 判定语言=简体（词汇 grep `androidApp/src/main/res/values-zh-rCN/strings.xml`：相册/搜索/设置/相簿扫描…出现简体专属词、**零繁体词**（相簿/搜尋/設定））。
2. headline/subline 与 Step 1.6 captions SSOT 逐字一致（注意排版约定：headline 行尾无逗号）。
3. chat 类帧（03/06/07）核对无 PerfRow 调试统计行残留。

- [ ] **Step 3.4: 风格一致性（SSIM + 尺寸）**

```bash
for n in 01-gallery 02-search 03-chat 04-people 05-person-groups 06-chat-welcome 07-insight 08-privacy; do
  ffmpeg -i google-play-listing/zh-CN/screenshots/$n.png -i google-play-listing/en-US/screenshots/$n.png -filter_complex ssim -f null - 2>&1 | grep Psnr || true
done
sips -g pixelWidth -g pixelHeight google-play-listing/zh-CN/screenshots/01-gallery.png  # 1080×1920
```

Expected: SSIM(Zh vs En 同槽) 高（差异应仅语言文字；en 为今日已发布基准，量级参考 ≥0.85）；尺寸 1080×1920。异常低 → 查该帧灌图/导出错槽。

---

### Task 4: Pass 2 — zh-TW 繁体覆写渲染 + Pass C 还原

**Files:** Create: `/tmp/playstore-zh-work/zh-tw-src/*.png`；Modify(云端瞬态): 8 源帧 TEXT characters + 9 个 Screen IMAGE fill（zh-TW 行 + FG 161:21）

- [ ] **Step 4.1: 生成覆写清单（确定性规则，非临场发挥）**

依据 Step 1.5 baseline JSON，对每个源帧每个 TEXT 节点产出目标繁体值：
- **$ref 绑定节点** → 查 `docs/08-UI-SPECS/screens/lang/ledger.json`（变量 SSOT）取 zh 值 → 按词表/逐句转繁体；
- **EN 字面量节点**（营销/消息体）→ 依语境译繁体。
词表（对齐 values-zh-rTW，勿用大陆用词）：相簿/搜尋/設定/遠端/本機/張/**社會（禁「社交」）**/智能助手小浪（禁「智慧助理」）/相簿掃描/相簿整理。人名/stat 数字 zh==en 不动。
产出 `/tmp/playstore-zh-work/overlay-tw-<slot>.json`：`[{id, from, to}]`。核对方式：`grep` values-zh-rTW/strings.xml 确认每个 UI 词与资源一致。

- [ ] **Step 4.2: 覆写（batch_edit ≤25 ops/批）**

每节点 `U(<id>,{characters:"<繁体值>"})`。U 纯值即真解绑（characters 无 no-op 陷阱）；值与既有变量值相同时可能被自动重绑 $ref——简繁同形句无碍，还原一律按 baseline。

- [ ] **Step 4.3: 导出 + 灌图（同 Task 2 流程）**

export 8 源帧 scale2 → `zh-tw-src/`（空白重试）→ upload_images 9 items → zh-TW Screens（302:110/118/126/134/142/150/158/166 按 Step 1.3 实读）+ FG `161:21`。
抽样 batch_read 核验 imageHash 变化。

- [ ] **Step 4.4: Pass C 还原（立即执行，不等验收）**

按 baseline JSON 逐节点回填：绑定节点 `U(<id>,{characters:"$<varId>"})`（baseline 记录的 $ref 原样恢复）；字面量节点回原 EN 值。variableModes 全程未动（Task 2 已还原），无需再写。

- [ ] **Step 4.5: 还原零残留核验（硬 gate）**

重跑 Step 1.5 的 patterns TEXT 盘点，与 baseline JSON diff。
Expected: 每源帧全量 TEXT（id、characters、绑定态）与 baseline 完全一致。任何漂移 → 立即按 baseline 修正后复验。

---

### Task 5: zh-TW 成品导出 + 装填 + 繁体验收

**Files:** 同 Task 3 的 zh-TW 对应路径

- [ ] **Step 5.1: 整帧导出 + 装填**

同 Task 3 Step 3.1/3.2，目标：`listings/zh-TW/`、`google-play-listing/zh-TW/`、`docs-site/assets/shots/zh-TW/*.jpg`。

- [ ] **Step 5.2: 逐帧 OCR 繁体验收（硬 gate）**

1. 语言=繁体：词汇 grep `values-zh-rTW/strings.xml`（相簿/搜尋/設定/社會/智能助手小浪…）；**零简体残留**——重点盯「相册/设置/搜索/社交/智慧」。
2. headline/subline ↔ zh-TW captions SSOT 逐字一致。
3. 03/06/07 无 PerfRow 残留。

- [ ] **Step 5.3: SSIM + 尺寸**

同 Task 3 Step 3.4（zh-TW vs en 同槽）。

---

### Task 6: 发布（SOCKS 桥 → Play API）

- [ ] **Step 6.1: 网络前置探活**

```bash
curl -s --connect-timeout 8 --socks5-hostname 127.0.0.1:51081 https://www.google.com/generate_204 -o /dev/null -w "%{http_code}\n"
```

Expected: 204。非 204 → ABC 代理未开，**停下找用户**（GUI 客户端需手动连接）。

- [ ] **Step 6.2: 起桥（仅当 51999 未监听）**

```bash
lsof -nP -iTCP:51999 -sTCP:LISTEN || { test -f /tmp/socks-bridge.py || cat > /tmp/socks-bridge.py <<'EOF'
#!/usr/bin/env python3
# HTTP CONNECT -> SOCKS5 127.0.0.1:51081, listen 127.0.0.1:51999
import socket, threading, struct, sys
SOCKS, LISTEN = ("127.0.0.1", 51081), ("127.0.0.1", 51999)
def s5(host, port):
    s = socket.create_connection(SOCKS, timeout=30)
    s.sendall(b"\x05\x01\x00"); assert s.recv(2) == b"\x05\x00"
    a = host.encode()
    s.sendall(b"\x05\x01\x00\x03" + bytes([len(a)]) + a + struct.pack(">H", port))
    r = s.recv(10); assert r[1] == 0, f"socks5 code={r[1]}"
    return s
def pipe(x, y):
    try:
        while True:
            d = x.recv(65536)
            if not d: break
            y.sendall(d)
    except OSError: pass
    finally:
        for z in (x, y):
            try: z.close()
            except OSError: pass
def handle(c):
    try:
        req = b""
        while b"\r\n\r\n" not in req: req += c.recv(4096)
        m, t = req.split(b"\r\n")[0].decode().split()[:2]
        if m != "CONNECT":
            c.sendall(b"HTTP/1.1 405\r\n\r\n"); return
        h, p = t.rsplit(":", 1); up = s5(h, int(p))
        c.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        t1 = threading.Thread(target=pipe, args=(c, up), daemon=True)
        t2 = threading.Thread(target=pipe, args=(up, c), daemon=True)
        t1.start(); t2.start(); t1.join(); t2.join()
    except Exception as e:
        sys.stderr.write(f"err: {e}\n")
        try: c.close()
        except OSError: pass
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(LISTEN); srv.listen(64); print(f"bridge {LISTEN} -> socks5 {SOCKS}", flush=True)
while True:
    c, _ = srv.accept(); threading.Thread(target=handle, args=(c,), daemon=True).start()
EOF
nohup python3 /tmp/socks-bridge.py > /tmp/socks-bridge.log 2>&1 & }
curl -s --connect-timeout 8 -x http://127.0.0.1:51999 https://www.google.com/generate_204 -o /dev/null -w "%{http_code}\n"  # Expect 204
```

（桥必须落文件再启动——inline `python3 -m` 一行流握手必写错。）

- [ ] **Step 6.3: 逐语言上传（先删后传，重试×3）**

```bash
export https_proxy=http://127.0.0.1:51999
for lang in zh-CN zh-TW; do
  for i in 1 2 3; do
    python3 scripts/play-upload-resumable.py --listing androidApp/src/main/play/listings \
      --prune-types phoneScreenshots,featureGraphic --langs $lang && break
    echo "retry $lang #$i"; sleep 5
  done
done
```

Expected: 每语言一次 edit+commit 成功（脚本内部 per-lang commit）。SSL UNEXPECTED_EOF/超时 = 代理间歇掐断 → 重试。**Play 手机截图上限 8，prune 失败则新图传不上**；若脚本对 featureGraphic 不适用 zh 语言（读 Step 6.3 首次运行输出判断），回退 `--prune-types phoneScreenshots` 单独跑，FG 单独传。

- [ ] **Step 6.4: API 回读核验（硬 gate）**

按脚本输出/回读能力比对 hosted vs local 哈希：每语言 8 截图 + 1 FG 共 9/9 一致。脚本若无 readback 能力，用其依赖的 Google API 凭据拉 listing 图像元数据比对 sha1。任何不一致 → 该语言重跑 Step 6.3。

---

### Task 7: 收尾（零提交）

- [ ] **Step 7.1: 更新记忆** — `play-store-assets-pipeline.md` 增补：zh 两行 v2.2.3 完成状态 + 本轮新坑（若有）。
- [ ] **Step 7.2: 交付报告** — 变更清单（画布 18 Screen、三处目录 2×9 文件、发布结果、验收证据：OCR/SSIM/回读哈希）；**git 待提交清单**（列文件路径，等 merge 收口后另起提交；spec + plan + 成品一并）。
- [ ] **Step 7.3: 不做 git 提交**（mid-merge 红线，用户已批准边界）。

---

## Self-Review

1. **Spec coverage**: 映射表(T1.2/1.3)、Pass1(T2)、Pass2+PassC(T4)、装填/镜像(T3.2/5.1)、验收四条(T3.3/3.4、T4.5、T5.2/5.3)、发布+回读(T6)、边界零提交(T7.3) — 全覆盖；降级路径(T1.4)。✓
2. **Placeholder scan**: 数据依赖处（源帧导出→文件名映射、docs-site slug）均给出确定性获取规则与实录步骤，无 TBD。✓
3. **一致性**: setId/modeId（182:133/132/134）、Screen id 口径（推定+实读校正）、还原基线（live baseline 非 structure.json）前后一致。✓
