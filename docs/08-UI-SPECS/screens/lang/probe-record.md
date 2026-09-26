# UI Language 变量集探针台账（Task 0）

- 日期：2026-08-23
- 结论：**GO**——TEXT_CONTENT 绑定语法可行，mode 切换/双 override/快照导出全部验证通过
- spec：已随交付清理（2026-08-23 ardot-language-modes，git 历史可查）
- 探针帧：gallery/grid（105:45，Gallery 页）
- 探针后画布终态：三节点保持绑定（Step 1 种子），帧 override 显式写回双默认（Dark + English）

## 1. id 台账

| 项 | id | 备注 |
|----|----|------|
| 变量集 UI Language | `182:133` | defaultMode=English |
| mode English | `182:132` | 集默认 |
| mode 中文 | `182:134` | |
| gallery.title | `182:135` | Gallery / 相册 |
| gallery.today | `182:136` | Today / 今天 |
| gallery.yesterday | `182:137` | Yesterday / 昨天 |
| 绑定节点（相册） | `105:47` | title_album，绑 `182:135` |
| 绑定节点（今天） | `107:33` | group_title，绑 `182:136` |
| 绑定节点（昨天） | `107:35` | group_title，绑 `182:137` |
| 保持 literal | `107:34` / `107:36` | (6)/(3) 计数，按 spec §3.2 不绑 |

对照组（已有变量集）：PoLang Tokens = `2:2`，Dark=`2:0`（默认）、Light=`79:1`。

## 2. 可用绑定语法（候选 1 首发命中，未动用候选 2/3）

```
U("<nodeId>", {characters:"$<varId>"})
```

- 实例：`U("105:47", {characters:"$182:135"})`
- 生效判据：`batch_read` 该节点 `boundVariables.characters` 出现
  `{"id":"VariableID:182:135","type":"VARIABLE_ALIAS"}`；截图渲染为解析值（无 `$` 字面量泄漏）
- 节点原有 fontSize/fills 绑定不受 characters 绑定影响（三者同存于 boundVariables）
- `fetch_variables` 返回全量变量（PoLang Tokens 437 项），取 UI Language id 需从落盘文件过滤

## 3. 回滚配方（已实测验证）

- **解绑单节点**：字面量覆写 `U("<nodeId>", {characters:"相册"})`
  ——实测 characters 回字面量、boundVariables.characters 条目消失（fontSize/fills 保留）。
  注意：解绑后渲染不再随 mode 切换，勿留半解绑态。
  另注：memory 警告「U 对已绑属性传纯值=静默 no-op」不适用于 characters 解绑——探针实测字面量覆写即真解绑（boundVariables 条目消失）。
- **删变量集**：`apply_variables` 传 `replace:true` 且不含该集（未在本探针执行，备查）。
- ⚠️ `replace:true` 语义=输入中未出现的变量集全删——直接以最小载荷执行本配方会把 PoLang Tokens（437 变量）一并清掉。安全做法：先 `fetch_variables` 取全量，以「全量减 UI Language」为载荷；或直接在桌面端 UI 删集。

## 4. mode 切换验证（Step 0.5 通过）

写帧根 override：`U("105:45", {variableModes:[{variableSetId:"182:133",modeId:"<modeId>"}]})`

| modeId | 渲染结果 | 截图 |
|--------|----------|------|
| `182:134`（中文） | 相册 / 今天 (6) / 昨天 (3)，计数保留 | `/tmp/probe-zh.png` |
| `182:132`（English） | Gallery / Today (6) / Yesterday (3)，无折行裁切 | `/tmp/probe-en.png` |

两态截图 MD5 不同且图像分析双确认，EN 文案（Gallery/Yesterday 更长）在该帧无折行/裁切。

## 5. 双 override 并存（Step 0.6 通过 + 语义发现）

`U("105:45", {variableModes:[{variableSetId:"2:2",modeId:"79:1"},{variableSetId:"182:133",modeId:"182:134"}]})`
→ 同帧同时呈现浅色主题 + 中文文案（截图 `/tmp/probe-dual.png`）。**两 set 不互斥，并存可用。**

### ⚠️ variableModes 写入语义（实测，影响工具设计）

1. **`variableModes:null` 与 `variableModes:[]` 均为静默 no-op**——override 移除不掉
   （响应仍回 `success`，截图哈希不变坐实）。
2. **数组写入是 merge 而非 replace**：只写 lang 条目时，已存在的 theme override 保留。
3. **还原配方 = 显式写回各 set 默认 mode**：
   `U("105:45", {variableModes:[{variableSetId:"2:2",modeId:"2:0"},{variableSetId:"182:133",modeId:"182:132"}]})`
4. 推论：spec §4.1 `ardot-preview-mode.sh --lang auto` 的「移除 override」语义须实现为
   「写回 English 默认 mode」，不能靠 null 清除。

## 6. structure.json 导出形态（Step 0.7 实测）

- **已绑定节点：`characters` 导出为引用字符串**（`"$182:135"` / `"$182:136"`），
  与 fills（`"$2:149"`）、fontSize（`"$2:114"`）同构
- **未绑定节点：导出字面量**（`"(6)"`）
- 快照 diff 审查时：绑定操作表现为 `相册 → $182:135` 的 characters 变更，**不是数据丢失**，
  勿误判；`sync-ardot-variables.py` 按 SET_NAME 过滤不受影响（spec §3.5）

## 7. 未验证项

- **⑤ 桌面端直改绑定文本行为**（原 Step 0.8）：按控制器指示跳过，用户协助项另行安排。
  spec §8 对应风险行保持未验证状态。

## 8. 其他实证细节

- `batch_edit` 响应对 `variableModes` 写入一律回显 `updated:{}`（空对象），即便写入成功——
  判定必须靠截图/下一跳写入对比，不能信回显。
- 单节点截图与整帧截图均随 override 写入即时刷新（渲染非陈旧缓存；本探针 12:19 判别实验
  证实：写入 en-only 后哈希立刻变化）。CDN 回传 URL 偶发复用旧文件名，读图以本地落盘文件为准。
- `apply_variables`（merge 模式）建集返回 `created:5`（1 集 + 2 mode + ... 计数口径未细究）。
