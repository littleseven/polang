# Browser-VNC 云端浏览器直播卡 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App 端 Koog Agent 获得云端浏览器能力——browser_* 工具闭环经 picme-server 转发 xuxing browser-agent-bridge（headless Chromium + CDP），Chat 内直播卡逐步更新画面，会话结束定格落库。

**Architecture:** 四线五阶段：A = `infra/browser-bridge/`（Node 20 + Playwright 单例 headless Chromium + 每会话临时 BrowserContext，CDP DOM 级驱动，按需抓帧，借鉴 openmuse）；B = picme-server `BrowserRoute`（X-App-Token 鉴权 + 配额 + per-user 并发=1 + 透传）；C = shared commonMain（协议 DTO + 7 命令 + BrowserSessionCapability + 7 @Tool + tool_browser part + 占位/overlay 管线）；D = androidApp（OkHttp transport + VM live 态 + BrowserLiveCard + 五语）；E = 文档/golden/端到端。设计 SSOT = `docs/superpowers/specs/2026-10-06-browser-vnc-live-card-design.md`（下称 spec）。

**Tech Stack:** Node 20 + Playwright（bridge）；Ktor 3.0.3 server + Exposed + SQLite（网关）；Kotlin Multiplatform + kotlinx.serialization（shared）；Jetpack Compose + Coil 2.7 + OkHttp（androidApp）。

**执行前置（根 AGENTS.md §3.4 强制）**：开工前在 `.worktrees/browser-vnc-live-card` 建隔离 worktree + 专用分支 `feat/browser-vnc-live-card`（遵循 using-git-worktrees skill），征得用户同意后动工；本计划全部改动落在该 worktree。

**分阶段验证门槛**：
- A：`cd infra/browser-bridge && npm test`（SSRF 单测）+ `BRIDGE_INTEGRATION=1 npm test`（真 Chrome 冒烟，本机有 Chrome 时）
- B：`./gradlew -p server build`
- C：`JITPACK=true ./gradlew :shared:jvmTest :shared:assemble`（含 golden 重生成步骤）
- D：`./gradlew :androidApp:assembleDebug` + 真机 dev-loop 闭环
- E：`python3 scripts/check_doc_sync.py`

---

## Phase A：infra/browser-bridge（xuxing 侧 bridge，全新组件）

技术决策（spec §2.2 + 计划级细化 + 2026-10-06 openmuse 借鉴修订）：

- **Playwright**（非 puppeteer-core）——借鉴 [CopilotKit/openmuse](https://github.com/CopilotKit/openmuse)（MIT）`apps/worker` 的验证过的形态：**单例 headless browser + 每会话临时 BrowserContext**（fresh storage = fresh profile 语义，比每会话一个 browser 进程轻得多）；`npx playwright install chromium` 安装浏览器，`CHROME_PATH` env 可覆盖。
- **帧 = 一次性 `page.screenshot`**：watch 模式轮询（1-2fps）下与 `Page.startScreencast` 体验等价、无 screencast ack 状态机（openmuse 的 live console 同为截图轮询，佐证此口径）；spec §2.2 watch 模式措辞在 Phase E 文档任务同步修正。
- **域名结果全部结构化进 JSON `status` 字段**（HTTP 恒 200，除 401/404/413 传输层），App/网关以 JSON status 为准。
- **openmuse 借鉴清单**（对应 `apps/worker/src/{network,browser,server}.ts`，均已在计划撰写时读源确认）：
  1. SSRF 全球单播许可名单：仅 80/443 端口、禁 userinfo、禁 .localhost/.local/.internal/.home/.lan、IPv4 增补 CGNAT/文档段/保留段、IPv6 仅 2000::/3 许可名单、DNS 5s 超时；
  2. **重定向落地复查**（goto 后对 `page.url()` 再校验——预导航 DNS 校验挡不住 302 跳内网）；
  3. **子请求级拦截**（`context.route('**/*')` 每个 img/XHR/iframe 过同一校验）+ WebSocket 全禁 + `--disable-quic` + WebRTC IP 泄露防护；
  4. per-session **串行队列**（动作与抓帧不并发）；
  5. 服务加固：token 时序安全比较（sha256 + timingSafeEqual）、request/headers 超时、安全响应头；
  6. `serviceWorkers: 'block'`、popup 自动关、dialog 自动 dismiss；
  7. **交互元素提取**：extract 返回正文 + 元素清单（index/tag/text/href），click/type 支持 selector|text|index 三模式定位——纯文本 LLM 猜不出盲 CSS selector，元素清单是它唯一可靠的定位依据（openmuse 用坐标/键盘输入绕开此问题，但其背后是截图 VLM 回路；我们用元素清单达到同等可定位性且保持 DOM 级精度）；
  8. **worker 固定评估代码**（调用方不可注入 JS——openmuse 安全边界，我们同样遵守：bridge 不接受任意 evaluate）。
- 不借鉴（YAGNI/不符合 M1 模型）：持久 profile 与 storageState、PDF 下载捕获、egress 代理第二层（我们用路由拦截单层，代理层列为可选加固）、Docker 打包（我们用 systemd）、坐标输入与接管（M2 候选）。

### Task 1: bridge 骨架 + SSRF 守卫（TDD）

**Files:**
- Create: `infra/browser-bridge/package.json`
- Create: `infra/browser-bridge/src/config.js`
- Create: `infra/browser-bridge/src/ssrf.js`
- Test: `infra/browser-bridge/test/ssrf.test.js`

- [x] **Step 1: 写 package.json + config.js**

`infra/browser-bridge/package.json`：

```json
{
  "name": "browser-agent-bridge",
  "version": "0.1.0",
  "private": true,
  "type": "commonjs",
  "engines": { "node": ">=20" },
  "dependencies": {
    "playwright": "^1.49.1"
  },
  "scripts": {
    "postinstall": "npx playwright install chromium",
    "start": "node src/server.js",
    "test": "node --test test/"
  }
}
```

`infra/browser-bridge/src/config.js`：

```js
'use strict';

module.exports = {
  port: parseInt(process.env.BRIDGE_PORT || '8788', 10),
  bind: process.env.BRIDGE_BIND || '0.0.0.0',
  token: process.env.BRIDGE_TOKEN || '',
  // 空串 = playwright 自带 chromium（postinstall 已装）；自定义 Chrome 路径可覆盖
  chromePath: process.env.CHROME_PATH || '',
  maxSessions: parseInt(process.env.MAX_SESSIONS || '8', 10),
  idleTimeoutMs: parseInt(process.env.IDLE_TIMEOUT_MS || '120000', 10),
  hardCapMs: parseInt(process.env.HARD_CAP_MS || '600000', 10),
  navTimeoutMs: 15000,
  viewport: { width: 1280, height: 720 },
  frameQuality: 60,
  maxExtractChars: 4000,
  maxElements: 40,
  maxBodyBytes: 64 * 1024,
};
```

- [x] **Step 2: 写失败测试 `test/ssrf.test.js`**

```js
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { isPublicIp, validatePublicUrl } = require('../src/ssrf');

test('isPublicIp: rejects loopback / RFC1918 / link-local / CGNAT / multicast / doc ranges', () => {
  for (const ip of ['127.0.0.1', '10.0.0.1', '172.16.0.1', '172.31.255.1', '192.168.1.1',
    '169.254.1.1', '100.64.0.1', '100.127.255.1', '0.0.0.0', '224.0.0.1',
    '192.0.0.1', '192.0.2.1', '192.88.99.1', '198.18.0.1', '198.51.100.1', '203.0.113.1',
    '::1', 'fe80::1', 'fd00::1', '2001:db8::1']) {
    assert.strictEqual(isPublicIp(ip), false, ip);
  }
});

test('isPublicIp: allows public v4/v6', () => {
  for (const ip of ['8.8.8.8', '1.1.1.1', '172.15.0.1', '172.32.0.1', '2606:4700:4700::1111']) {
    assert.strictEqual(isPublicIp(ip), true, ip);
  }
});

test('validatePublicUrl: rejects non-http protocol and non-80/443 ports', async () => {
  await assert.rejects(() => validatePublicUrl('file:///etc/passwd'), /blocked_url/);
  await assert.rejects(() => validatePublicUrl('http://8.8.8.8:8080/'), /blocked_url/);
  await assert.rejects(() => validatePublicUrl('https://user:pass@8.8.8.8/'), /blocked_url/);
});

test('validatePublicUrl: rejects private/内部 hostnames', async () => {
  await assert.rejects(() => validatePublicUrl('http://192.168.0.1/admin'), /blocked_url/);
  await assert.rejects(() => validatePublicUrl('http://127.0.0.1/'), /blocked_url/);
  await assert.rejects(() => validatePublicUrl('http://foo.localhost/'), /blocked_url/);
  await assert.rejects(() => validatePublicUrl('http://gateway.internal/'), /blocked_url/);
  await assert.rejects(() => validatePublicUrl('http://nas.lan/'), /blocked_url/);
});

test('validatePublicUrl: rejects malformed url', async () => {
  await assert.rejects(() => validatePublicUrl('not a url'), /blocked_url/);
});

test('validatePublicUrl: accepts public literal IP on 443', async () => {
  const u = await validatePublicUrl('https://8.8.8.8/');
  assert.strictEqual(u.href, 'https://8.8.8.8/');
});

test('validatePublicUrl: dns failure maps to dns_failed', async () => {
  await assert.rejects(
    () => validatePublicUrl('https://nonexistent.invalid/', async () => { throw new Error('ENOTFOUND'); }),
    /dns_failed/,
  );
});

test('validatePublicUrl: hostname resolving to private ip rejected', async () => {
  await assert.rejects(
    () => validatePublicUrl('https://evil.example/', async () => [{ address: '10.1.2.3', family: 4 }]),
    /blocked_url/,
  );
});
```

- [x] **Step 3: 跑测试确认失败**

Run: `cd infra/browser-bridge && npm install && npm test`
Expected: FAIL（`Cannot find module '../src/ssrf'`）

- [x] **Step 4: 实现 `src/ssrf.js`**（借鉴 openmuse `apps/worker/src/network.ts`，MIT）

```js
'use strict';

const dns = require('node:dns').promises;
const net = require('node:net');

class SsrfError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

/**
 * 保守全球单播许可名单（借鉴 CopilotKit/openmuse apps/worker/src/network.ts，MIT）：
 * 过渡/文档/私有/回环/组播/保留网段一律不是浏览器目的地。
 */
function isPublicIp(address) {
  if (net.isIPv4(address)) {
    const [a = 0, b = 0, c = 0] = address.split('.').map(Number);
    return !(
      a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 100 && b >= 64 && b <= 127) ||            // CGNAT 100.64/10
      (a === 169 && b === 254) ||                      // link-local
      (a === 172 && b >= 16 && b <= 31) ||             // RFC1918
      (a === 192 && (b === 168 || (b === 0 && (c === 0 || c === 2)) || (b === 88 && c === 99))) ||
      (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) ||
      (a === 203 && b === 0 && c === 113)
    );
  }
  if (!net.isIPv6(address) || address.includes('.') || address.includes('%')) return false;
  const halves = address.toLowerCase().split('::');
  const left = halves[0] ? halves[0].split(':') : [];
  const right = halves[1] ? halves[1].split(':') : [];
  const words = halves.length === 1
    ? left
    : [...left, ...Array(8 - left.length - right.length).fill('0'), ...right];
  const first = parseInt(words[0] ?? '0', 16);
  const second = parseInt(words[1] ?? '0', 16);
  return (
    first >= 0x2000 && first <= 0x3fff &&
    !(first === 0x2001 && (second < 0x200 || second === 0xdb8)) &&
    first !== 0x2002 &&
    !(first === 0x3fff && second < 0x1000)
  );
}

/**
 * 校验目标 URL：仅 http/https + 仅 80/443 端口 + 无 userinfo + 非内部后缀 +
 * 解析结果全部落在公网。抛 SsrfError（code: blocked_url / dns_failed）。
 * resolve 注入便于测试。
 */
async function validatePublicUrl(raw, resolve = (h) => dns.lookup(h, { all: true, verbatim: true })) {
  const blocked = () => new SsrfError('blocked_url');
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw blocked();
  }
  const hostname = url.hostname.replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase();
  if (
    (url.protocol !== 'http:' && url.protocol !== 'https:') ||
    url.username || url.password ||
    (url.port && url.port !== '80' && url.port !== '443') ||
    !hostname ||
    /(^|\.)(localhost|local|internal|home|lan)$/.test(hostname)
  ) {
    throw blocked();
  }
  let addresses;
  if (net.isIP(hostname)) {
    addresses = [{ address: hostname, family: net.isIP(hostname) }];
  } else {
    let timer;
    try {
      addresses = await Promise.race([
        resolve(hostname),
        new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('dns timeout')), 5000); }),
      ]);
    } catch {
      throw new SsrfError('dns_failed');
    } finally {
      clearTimeout(timer);
    }
  }
  if (!addresses || addresses.length === 0 || addresses.some((e) => !isPublicIp(e.address))) throw blocked();
  return url;
}

module.exports = { validatePublicUrl, isPublicIp, SsrfError };
```

- [x] **Step 5: 跑测试确认通过**

Run: `cd infra/browser-bridge && npm test`
Expected: PASS（8 个用例）

- [x] **Step 6: Commit**

```bash
git add infra/browser-bridge/package.json infra/browser-bridge/src/config.js infra/browser-bridge/src/ssrf.js infra/browser-bridge/test/ssrf.test.js
git commit -m "feat(bridge): 骨架 + OpenMuse 级 SSRF 守卫（单播许可名单/端口限制/重定向防线基础）"
```

### Task 2: 会话管理器（SessionManager，Playwright）

**Files:**
- Create: `infra/browser-bridge/src/sessions.js`
- Test: `infra/browser-bridge/test/sessions.test.js`

- [x] **Step 1: 写失败测试（生命周期/配额/串行/三模式定位/元素提取，Playwright 以 fake browserProvider 注入）**

```js
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { SessionManager, PoolExhaustedError, SessionExpiredError } = require('../src/sessions');

const CFG = {
  maxSessions: 1, idleTimeoutMs: 60000, hardCapMs: 600000, navTimeoutMs: 5000,
  viewport: { width: 1280, height: 720 }, frameQuality: 60, maxExtractChars: 5, maxElements: 40,
};

function fakePage() {
  const calls = [];
  const locator = () => ({
    click: async () => { calls.push('locator.click'); },
    pressSequentially: async (t) => { calls.push(`type:${t}`); },
  });
  return {
    calls,
    _url: 'https://example.com/',
    url() { return this._url; },
    title: async () => 'Example',
    goto: async (u) => { calls.push(`goto:${u}`); },
    setDefaultTimeout: () => {},
    on: () => {},
    locator,
    getByText: () => locator(),
    $$: async () => [
      { click: async () => { calls.push('el.click'); }, pressSequentially: async () => {}, evaluate: async (fn) => fn({ tagName: 'A', innerText: 'Link one', value: '', getAttribute: () => null }) },
    ],
    evaluate: async (fn) => (typeof fn === 'function' ? 'hello body' : ''),
    screenshot: async () => Buffer.from('jpeg-bytes'),
  };
}

function fakeBrowserProvider(page) {
  return async () => ({
    newContext: async () => ({
      route: async () => {},
      routeWebSocket: async () => {},
      on: () => {},
      newPage: async () => page,
      close: async () => {},
    }),
  });
}

test('open allocates session and respects pool cap', async () => {
  const mgr = new SessionManager({ ...CFG }, fakeBrowserProvider(fakePage()));
  const s = await mgr.open(null);
  assert.ok(s.id);
  await assert.rejects(() => mgr.open(null), PoolExhaustedError);
  await mgr.close(s.id);
  const s2 = await mgr.open(null);
  assert.ok(s2.id);
  await mgr.close(s2.id);
});

test('get on unknown id throws SessionExpiredError', () => {
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(fakePage()));
  assert.throws(() => mgr.get('nope'), SessionExpiredError);
});

test('extract truncates text and returns interactive elements with index', async () => {
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(fakePage()));
  const s = await mgr.open(null);
  const r = await mgr.act(s, { action: 'extract' });
  assert.strictEqual(r.status, 'ok');
  assert.strictEqual(r.textExtract, 'hello');
  assert.ok(Array.isArray(r.elements));
  assert.strictEqual(r.elements[0].index, 0);
  assert.strictEqual(r.elements[0].text, 'Link one');
  await mgr.close(s.id);
});

test('click by element index uses cached handle', async () => {
  const page = fakePage();
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  await mgr.act(s, { action: 'extract' });
  await mgr.act(s, { action: 'click', index: 0 });
  assert.ok(page.calls.includes('el.click'));
  await mgr.close(s.id);
});

test('click without any target fails structured', async () => {
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(fakePage()));
  const s = await mgr.open(null);
  await assert.rejects(() => mgr.act(s, { action: 'click' }), /missing_target/);
  await mgr.close(s.id);
});

test('reap closes idle sessions', async () => {
  const page = fakePage();
  let contextClosed = false;
  const mgr = new SessionManager({ ...CFG, maxSessions: 8, idleTimeoutMs: 10 }, async () => ({
    newContext: async () => ({
      route: async () => {}, routeWebSocket: async () => {}, on: () => {},
      newPage: async () => page,
      close: async () => { contextClosed = true; },
    }),
  }));
  const s = await mgr.open(null);
  s.lastActivity = Date.now() - 100;
  await mgr.reap(Date.now());
  assert.strictEqual(contextClosed, true);
  assert.throws(() => mgr.get(s.id), SessionExpiredError);
});
```

- [x] **Step 2: 跑测试确认失败**

Run: `cd infra/browser-bridge && npm test`
Expected: FAIL（`Cannot find module '../src/sessions'`）

- [x] **Step 3: 实现 `src/sessions.js`**

```js
'use strict';

const crypto = require('node:crypto');
const { chromium } = require('playwright');
const { validatePublicUrl } = require('./ssrf');

class PoolExhaustedError extends Error {
  constructor() { super('pool_exhausted'); this.code = 'pool_exhausted'; }
}
class SessionExpiredError extends Error {
  constructor() { super('session_expired'); this.code = 'session_expired'; }
}
class ActionFailedError extends Error {
  constructor(code, reason) { super(`${code}: ${reason}`); this.code = code; }
}

class Session {
  constructor(id, context, page) {
    this.id = id;
    this.context = context;
    this.page = page;
    this.createdAt = Date.now();
    this.lastActivity = Date.now();
    this.lastGoodFrame = null;
    this.actionCount = 0;
    this.elements = [];              // 最近 extract 的元素句柄缓存（index 定位用）
    this.queue = Promise.resolve();  // per-session 串行队列（借鉴 openmuse serial）
  }
  touch() { this.lastActivity = Date.now(); }
}

/**
 * 单例 headless browser + 每会话临时 BrowserContext（fresh storage，借鉴 openmuse
 * apps/worker 形态）：比每会话一个 browser 进程轻，隔离语义等价 fresh profile。
 */
class SessionManager {
  /** browserProvider 注入便于测试（返回带 newContext 的 browser 形态对象）。 */
  constructor(config, browserProvider = null) {
    this.config = config;
    this.browserProvider = browserProvider;
    this.browserPromise = null;
    this.sessions = new Map();
  }

  async _browser() {
    if (this.browserProvider) return this.browserProvider();
    if (!this.browserPromise) {
      this.browserPromise = chromium.launch({
        headless: true,
        executablePath: this.config.chromePath || undefined,
        args: [
          '--disable-dev-shm-usage', '--disable-gpu', '--no-first-run',
          '--disable-quic', '--disable-extensions',
          '--force-webrtc-ip-handling-policy=disable_non_proxied_udp',
        ],
      });
      this.browserPromise.catch(() => { this.browserPromise = null; }); // 崩溃后下次重建
    }
    return this.browserPromise;
  }

  count() { return this.sessions.size; }

  get(id) {
    const s = this.sessions.get(id);
    if (!s) throw new SessionExpiredError();
    return s;
  }

  /** per-session 串行：动作与抓帧不并发（借鉴 openmuse serial）。 */
  _serial(session, fn) {
    const next = session.queue.catch(() => {}).then(fn);
    session.queue = next;
    return next;
  }

  async _newContext() {
    const browser = await this._browser();
    const context = await browser.newContext({
      viewport: this.config.viewport,
      serviceWorkers: 'block',
    });
    // 子请求级 SSRF：img/XHR/iframe 全部过同一校验（借鉴 openmuse route 拦截）
    await context.route('**/*', async (route) => {
      try {
        await validatePublicUrl(route.request().url());
        await route.continue();
      } catch {
        await route.abort('blockedbyclient').catch(() => {});
      }
    });
    // WebSocket 全禁（防绕过 HTTP 校验的直连通道）
    await context.routeWebSocket('**/*', (socket) => socket.close());
    return context;
  }

  async open(url) {
    if (this.sessions.size >= this.config.maxSessions) throw new PoolExhaustedError();
    const id = crypto.randomUUID();
    const context = await this._newContext();
    try {
      const page = await context.newPage();
      // 必须先 newPage 再注册：'page' 事件对 newPage() 创建的主页面同样触发，先注册会误杀主页面（real-Chrome 实证）
      context.on('page', (popup) => { popup.close().catch(() => {}); });
      page.setDefaultTimeout(this.config.navTimeoutMs);
      page.on('dialog', (dialog) => { dialog.dismiss().catch(() => {}); });
      const session = new Session(id, context, page);
      this.sessions.set(id, session);
      if (url) await this._serial(session, () => this._navigate(session, url));
      return session;
    } catch (e) {
      this.sessions.delete(id);
      await context.close().catch(() => {});
      throw e;
    }
  }

  async _navigate(session, url) {
    const safe = await validatePublicUrl(url);
    await session.page.goto(safe.href, { waitUntil: 'domcontentloaded', timeout: this.config.navTimeoutMs });
    // 重定向落地复查：302 可跳到内网，预导航校验挡不住（openmuse 实证坑位）
    await validatePublicUrl(session.page.url());
  }

  async act(session, body) {
    return this._serial(session, async () => {
      const started = Date.now();
      session.touch();
      const action = body.action;
      let textExtract;
      let elements;
      if (action === 'navigate') await this._navigate(session, body.url);
      else if (action === 'click') await this._click(session, body);
      else if (action === 'type') await this._type(session, body);
      else if (action === 'extract') ({ textExtract, elements } = await this._extract(session));
      else if (action === 'screenshot') { /* 帧逻辑统一在下方 */ }
      else throw new ActionFailedError('unknown_action', `unknown action: ${action}`);
      session.actionCount += 1;
      let frame;
      if (body.wantFrame || action === 'screenshot') frame = await this._captureFrame(session);
      return this.snapshot(session, { textExtract, elements, frame, actionMs: Date.now() - started });
    });
  }

  /** 定位三模式：index（上次 extract 序号）> text（可见文本）> selector。 */
  async _locate(session, body) {
    if (typeof body.index === 'number') {
      const handle = session.elements[body.index];
      if (!handle) throw new ActionFailedError('stale_element', '元素序号无效或已过期，请重新 extract');
      return handle;
    }
    if (body.targetText) return session.page.getByText(body.targetText, { exact: false }).first();
    if (body.selector) return session.page.locator(body.selector).first();
    throw new ActionFailedError('missing_target', 'click/type 需要 selector、targetText 或 index 之一');
  }

  async _click(session, body) {
    const target = await this._locate(session, body);
    await this._staleGuard(() => target.click({ timeout: this.config.navTimeoutMs }));
  }

  async _type(session, body) {
    const target = await this._locate(session, body);
    await this._staleGuard(() => target.pressSequentially(String(body.text == null ? '' : body.text), { timeout: this.config.navTimeoutMs }));
  }

  /** 缓存句柄在页面变化后失效：Playwright 原始 not-attached 错误映射为结构化 stale_element。 */
  async _staleGuard(fn) {
    try {
      return await fn();
    } catch (e) {
      if (e && /not attached|detached|Target closed/i.test(String(e.message))) {
        throw new ActionFailedError('stale_element', '元素已失效，请重新 extract');
      }
      throw e;
    }
  }

  /**
   * 正文 + 交互元素清单。评估代码由 worker 固定，调用方不可注入 JS
   * （openmuse 安全边界：bridge 不接受任意 evaluate）。
   */
  async _extract(session) {
    const rawText = await session.page.evaluate(() => (document.body ? document.body.innerText : ''));
    const handles = await session.page.$$('a, button, input, select, textarea, [role="button"]');
    const capped = handles.slice(0, this.config.maxElements);
    const elements = await Promise.all(capped.map(async (handle, i) => {
      const meta = await handle.evaluate((el) => ({
        tag: el.tagName.toLowerCase(),
        text: (el.innerText || el.value || el.getAttribute('placeholder') || el.getAttribute('aria-label') || '').trim().slice(0, 80),
        href: el.getAttribute('href') || undefined,
        type: el.getAttribute('type') || undefined,
      })).catch(() => null);
      return meta ? { index: i, ...meta } : null;
    }));
    session.elements = capped;
    return {
      textExtract: typeof rawText === 'string' ? rawText.slice(0, this.config.maxExtractChars) : '',
      elements: elements.filter(Boolean),
    };
  }

  /** 无锁内部抓帧（act 已持队列）；公开路径走 captureFrame 串行包装。 */
  async _captureFrame(session) {
    const buf = await session.page.screenshot({ type: 'jpeg', quality: this.config.frameQuality });
    session.lastGoodFrame = buf.toString('base64');
    return session.lastGoodFrame;
  }

  async captureFrame(session) {
    return this._serial(session, async () => {
      session.touch();
      return this._captureFrame(session);
    });
  }

  async snapshot(session, extra) {
    return {
      status: 'ok',
      sessionId: session.id,
      currentUrl: session.page.url(),
      pageTitle: await session.page.title().catch(() => ''),
      textExtract: extra.textExtract,
      elements: extra.elements,
      frameJpegBase64: extra.frame,
      actionMs: extra.actionMs,
    };
  }

  async close(id) {
    const s = this.sessions.get(id);
    if (!s) return false;
    this.sessions.delete(id);
    await s.queue.catch(() => {}); // 先排空在途动作，避免 context 在 act 中途被关
    await s.context.close().catch(() => {});
    return true;
  }

  /** 空闲超时 + 硬上限回收；now 注入便于测试。 */
  async reap(now = Date.now()) {
    const victims = [];
    for (const s of this.sessions.values()) {
      if (now - s.lastActivity > this.config.idleTimeoutMs || now - s.createdAt > this.config.hardCapMs) {
        victims.push(s.id);
      }
    }
    for (const id of victims) await this.close(id);
    return victims.length;
  }
}

module.exports = { SessionManager, PoolExhaustedError, SessionExpiredError, ActionFailedError };
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd infra/browser-bridge && npm test`
Expected: PASS（SSRF 8 + sessions 6）

- [ ] **Step 5: Commit**

```bash
git add infra/browser-bridge/src/sessions.js infra/browser-bridge/test/sessions.test.js
git commit -m "feat(bridge): SessionManager——Playwright 共享 browser/临时 context/串行队列/三模式定位"
```

### Task 3: HTTP 服务 + 部署件

**Files:**
- Create: `infra/browser-bridge/src/server.js`
- Create: `infra/browser-bridge/browser-bridge.service`
- Create: `infra/browser-bridge/deploy.sh`
- Create: `infra/browser-bridge/.env.example`
- Create: `infra/browser-bridge/README.md`

- [x] **Step 1: 写 `src/server.js`**（token 时序安全比较 + 服务加固，借鉴 openmuse `apps/worker/src/server.ts`）

```js
'use strict';

const crypto = require('node:crypto');
const http = require('node:http');
const config = require('./config');
const { SessionManager, PoolExhaustedError, SessionExpiredError, ActionFailedError } = require('./sessions');
const { SsrfError } = require('./ssrf');

const manager = new SessionManager(config);
const expectedTokenHash = crypto.createHash('sha256').update(config.token).digest();

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > config.maxBodyBytes) {
        reject(new Error('body_too_large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(new Error('bad_json')); }
    });
    req.on('error', reject);
  });
}

function sendJson(res, code, obj) {
  const payload = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(payload);
}

function failPayload(session, err) {
  // 域名错误一律 200 + 结构化 status（401/404/413 由传输层处理）
  if (err instanceof PoolExhaustedError) return { status: 'pool_exhausted', errorCode: err.code, reason: 'browser pool is full' };
  if (err instanceof SessionExpiredError) return { status: 'session_expired', errorCode: err.code, reason: 'session expired or unknown' };
  if (err instanceof SsrfError) return { status: 'action_failed', errorCode: err.code, reason: `url rejected: ${err.code}` };
  if (err instanceof ActionFailedError) return { status: 'action_failed', errorCode: err.code, reason: err.message };
  const out = { status: 'action_failed', errorCode: 'internal', reason: err && err.message ? err.message : 'unknown error' };
  if (session && session.lastGoodFrame) out.lastGoodFrame = session.lastGoodFrame;
  return out;
}

const server = http.createServer(async (req, res) => {
  res.setHeader('cache-control', 'no-store');
  res.setHeader('x-content-type-options', 'nosniff');
  try {
    // 时序安全 token 比较（借鉴 openmuse：先哈希再 timingSafeEqual，防空 token 与长度侧信道）
    const providedHash = crypto.createHash('sha256').update(String(req.headers['x-bridge-token'] || '')).digest();
    if (!config.token || !crypto.timingSafeEqual(expectedTokenHash, providedHash)) {
      return sendJson(res, 401, { error: 'unauthorized' });
    }
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname;

    if (req.method === 'GET' && path === '/healthz') {
      return sendJson(res, 200, { ok: true, sessions: manager.count() });
    }

    if (req.method === 'POST' && path === '/session') {
      const body = await readBody(req);
      try {
        const session = await manager.open(body.url || null);
        let frame;
        if (body.wantFrame) frame = await manager.captureFrame(session);
        return sendJson(res, 200, await manager.snapshot(session, { frame, actionMs: 0 }));
      } catch (err) {
        return sendJson(res, 200, failPayload(null, err));
      }
    }

    const m = path.match(/^\/session\/([0-9a-f-]{36})(\/action|\/frame|\/close)?$/);
    if (!m) return sendJson(res, 404, { error: 'not_found' });
    const [, id, suffix] = m;

    if (req.method === 'POST' && suffix === '/action') {
      const body = await readBody(req);
      let session;
      try {
        session = manager.get(id);
      } catch (err) {
        return sendJson(res, 200, failPayload(null, err));
      }
      try {
        return sendJson(res, 200, await manager.act(session, body));
      } catch (err) {
        return sendJson(res, 200, failPayload(session, err));
      }
    }

    if (req.method === 'GET' && suffix === '/frame') {
      try {
        const session = manager.get(id);
        const frame = await manager.captureFrame(session);
        return sendJson(res, 200, {
          status: 'ok',
          sessionId: session.id,
          currentUrl: session.page.url(),
          pageTitle: await session.page.title().catch(() => ''),
          frameJpegBase64: frame,
        });
      } catch (err) {
        return sendJson(res, 200, failPayload(null, err));
      }
    }

    if (req.method === 'POST' && suffix === '/close') {
      const session = manager.sessions.get(id);
      const actionCount = session ? session.actionCount : 0;
      const lastGoodFrame = session ? session.lastGoodFrame : null;
      const closed = await manager.close(id);
      return sendJson(res, 200, { status: 'ok', sessionId: id, closed, actionCount, lastGoodFrame });
    }

    return sendJson(res, 404, { error: 'not_found' });
  } catch (err) {
    if (err && err.message === 'bad_json') return sendJson(res, 400, { error: 'bad_json' });
    if (err && err.message === 'body_too_large') return sendJson(res, 413, { error: 'body_too_large' });
    return sendJson(res, 500, { error: 'internal', message: err && err.message ? err.message : 'unknown' });
  }
});

// 服务加固（借鉴 openmuse server.ts）
server.requestTimeout = 30_000;
server.headersTimeout = 10_000;
server.keepAliveTimeout = 5_000;

setInterval(() => { manager.reap().catch(() => {}); }, 30_000).unref();

server.listen(config.port, config.bind, () => {
  console.log(`browser-agent-bridge listening on ${config.bind}:${config.port}`);
});
```

- [x] **Step 2: 本地冒烟（playwright postinstall 已装 chromium；无显示环境不影响 headless）**

```bash
cd infra/browser-bridge
BRIDGE_TOKEN=test-token node src/server.js &
sleep 1
curl -s -H "X-Bridge-Token: test-token" http://127.0.0.1:8788/healthz
# → {"ok":true,"sessions":0}
SID=$(curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","wantFrame":true}' http://127.0.0.1:8788/session | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')
curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"action":"extract"}' "http://127.0.0.1:8788/session/$SID/action" | head -c 400
# → {"status":"ok",...,"textExtract":"Example Domain...","elements":[{"index":0,"tag":"a","text":"More information..."}]}
curl -s -X POST -H "X-Bridge-Token: test-token" "http://127.0.0.1:8788/session/$SID/close"
# → {"status":"ok","closed":true,...}
# SSRF 负例（open 即拒）：
curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"url":"http://192.168.1.1/"}' http://127.0.0.1:8788/session
# → {"status":"action_failed","errorCode":"blocked_url",...}
kill %1
```

- [x] **Step 3: 写部署件**

`infra/browser-bridge/.env.example`：

```bash
BRIDGE_PORT=8788
# 生产设为 tailscale 网卡 IP，缺省 0.0.0.0 仅开发用
BRIDGE_BIND=
BRIDGE_TOKEN=change-me-shared-with-picme-server
CHROME_PATH=
MAX_SESSIONS=8
IDLE_TIMEOUT_MS=120000
HARD_CAP_MS=600000
```

`infra/browser-bridge/browser-bridge.service`：

```ini
[Unit]
Description=PoLang browser-agent-bridge (headless Chromium CDP bridge)
After=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/browser-bridge
EnvironmentFile=/opt/browser-bridge/.env
ExecStart=/usr/bin/node src/server.js
Restart=on-failure
RestartSec=3
MemoryMax=2G
NoNewPrivileges=true
ProtectSystem=strict
PrivateTmp=true
# 不加 ProtectHome=true：Playwright 浏览器缓存位于 ~/.cache/ms-playwright，需可读写

[Install]
WantedBy=multi-user.target
```

`infra/browser-bridge/deploy.sh`（开发机执行；xuxing ssh 别名沿用仓库惯例）：

```bash
#!/usr/bin/env bash
set -euo pipefail
# 注意：非 tty ssh 下的 sudo systemctl restart 需要 NOPASSWD 配置；
# 首次部署请手动在 xuxing 上执行本脚本内的 npm install / systemctl / curl 步骤。
cd "$(dirname "$0")"
rsync -av --delete --exclude node_modules --exclude .env ./ xuxing:/opt/browser-bridge/
ssh xuxing bash -s <<'EOF'
set -euo pipefail
cd /opt/browser-bridge
npm install --omit=dev
sudo systemctl restart browser-bridge
sleep 1
curl --fail-with-body -s \
  -H "X-Bridge-Token: $(grep -m1 '^BRIDGE_TOKEN=' .env | cut -d= -f2- | tr -d '\r\n')" \
  http://127.0.0.1:8788/healthz
EOF
```

`infra/browser-bridge/README.md`（冒烟段已内联，不再引用 plan；含 413 契约说明与防火墙注记）：

````markdown
# browser-agent-bridge

xuxing 侧云端浏览器桥：单例 headless Chromium + 每会话临时 BrowserContext（Playwright），
纯 CDP DOM 级驱动，为 picme-server `/v1/browser/*` 提供会话执行与按需抓帧。
设计 SSOT：`docs/superpowers/specs/2026-10-06-browser-vnc-live-card-design.md`。
安全边界借鉴 [CopilotKit/openmuse](https://github.com/CopilotKit/openmuse)（MIT）`apps/worker`：
SSRF 全球单播许可名单（仅 80/443）+ 重定向落地复查 + 子请求级拦截 + WebSocket 全禁 +
token 时序安全比较 + worker 固定评估代码（不接受任意 JS 注入）。

## 端点契约

- 端点：`POST /session`（open）、`POST /session/{id}/action`、`GET /session/{id}/frame`、
  `POST /session/{id}/close`、`GET /healthz`；全部要求 `X-Bridge-Token` 头。
- 域名结果一律 HTTP 200 + JSON `status`（ok/action_failed/pool_exhausted/session_expired）。
- 请求体超限（>64KB）时 `req.destroy()` 先于响应发出，客户端可能观察到连接重置而非 413——
  应将 body 发送过程中的 reset 视为 413 等价。
- click/type 定位三模式：index（extract 返回的元素序号，LLM 首选）/ targetText / selector。

## 冒烟

```bash
BRIDGE_TOKEN=test-token node src/server.js &
curl -s -H "X-Bridge-Token: test-token" http://127.0.0.1:8788/healthz
# → {"ok":true,"sessions":0}
SID=$(curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","wantFrame":true}' http://127.0.0.1:8788/session \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')
curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"action":"extract"}' "http://127.0.0.1:8788/session/$SID/action"
# → {"status":"ok",...,"textExtract":"...","elements":[...]}
curl -s -X POST -H "X-Bridge-Token: test-token" "http://127.0.0.1:8788/session/$SID/close"
# → {"status":"ok","closed":true,...}
# SSRF 负例（open 即拒）
curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"url":"http://192.168.1.1/"}' http://127.0.0.1:8788/session
# → {"status":"action_failed","errorCode":"blocked_url",...}
kill %1
```

## 部署

- `./deploy.sh`（rsync → npm install --omit=dev → systemctl restart → healthz）。
- **防火墙**：token 仅为传输层防线，生产必须配合 ufw / tailscale 限制 8788 仅内网可达；
  `BRIDGE_BIND` 生产设为 tailscale 网卡 IP（缺省 0.0.0.0 仅开发用）。

## 测试

- `npm test`（单测，fake browser provider）；真实浏览器冒烟见上方 curl 段。
````

- [x] **Step 4: 赋可执行权限并 commit**

```bash
chmod +x infra/browser-bridge/deploy.sh
git add infra/browser-bridge/
git commit -m "feat(bridge): HTTP 服务（时序安全 token + 超时加固）+ systemd unit + 部署脚本 + README"
```

---
## Phase B：picme-server 网关（server/）

背景事实（执行时对照）：路由装配在 `server/src/main/kotlin/com/mamba/picme/server/Application.kt:168-190`；鉴权是全局拦截器（`Application.kt:111-142`，`/v1/browser/**` 不在 `publicRoutes` 白名单 → 自动要求 X-App-Token）；路由内取身份用 `call.ownerTokenHash()`（`routes/ClaudeChatRoute.kt:146-151`，若该扩展为 file-private 则在 BrowserRoute.kt 复制同形实现）；反代透传范本 = `claudeDeliverRoute`（`ClaudeChatRoute.kt:93-121`）；出站 client 范本 = `issue/GitHubIssueClient.kt:45-77`；每日配额内存限流先例 = `IssueReportRoute.kt:26` + `Application.kt:166`。

### Task 4: AppConfig + .env.example + RateLimiter peek

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/ratelimit/RateLimiter.kt`
- Modify: `server/.env.example`
- Test: `server/src/test/kotlin/com/mamba/picme/server/ratelimit/RateLimiterTest.kt`（若已存在则追加用例）

- [x] **Step 1: AppConfig 加三个字段**

data class 字段区（仿 :52-59 渠道段分组注释）追加：

```kotlin
// Browser bridge
val browserBridgeUrl: String,
val browserBridgeToken: String,
val browserDailyQuota: Int,
```

`load()` 内追加：

```kotlin
browserBridgeUrl = env("BROWSER_BRIDGE_URL", ""),
browserBridgeToken = env("BROWSER_BRIDGE_TOKEN", ""),
browserDailyQuota = envInt("BROWSER_DAILY_QUOTA", 20),
```

> `browserBridgeUrl` 默认空串 = 未配置 → 路由返回 503 `browser_unavailable`（spec §6 降级路径）。

- [x] **Step 2: `.env.example` 追加**

```bash
# Browser bridge（xuxing headless Chromium 桥；留空 = browser 能力禁用，路由 503 降级）
BROWSER_BRIDGE_URL=
BROWSER_BRIDGE_TOKEN=
BROWSER_DAILY_QUOTA=20
```

- [x] **Step 3: RateLimiter 加 peek（不记录的检查）+ 可配 limit provider**

读现有 `ratelimit/RateLimiter.kt`（约 26 行），改造为（保持既有 `RateLimiter(maxRequests: Int, windowMs: Long = 60_000L)` 构造签名兼容，委托到 provider 主构造）。**落地实态（2026-10-06）**：provider 主构造为 **public**（private 会让 Task 7 的 `RateLimiter({ SettingsService.snapshot().browserDailyQuota }, ...)` 热配形态不可调用）；Int 次构造保留 `windowMs` 默认值（`Application.kt` 有一参调用）；`prune` 在 `synchronized(entry)` 内调用，锁语义与原实现完全一致：

```kotlin
class RateLimiter(
    private val limitProvider: () -> Int,
    private val windowMs: Long,
) {
    constructor(maxRequests: Int, windowMs: Long = 60_000L) : this({ maxRequests }, windowMs)

    private val log = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val entry = log.computeIfAbsent(ip) { mutableListOf() }
        synchronized(entry) {
            prune(entry, now)
            if (entry.size >= limitProvider()) return false
            entry.add(now)
            return true
        }
    }

    /** 只检查不记录——成功才计费的场景（browser 会话配额）先 peek 后 allow。 */
    fun peek(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val entry = log.computeIfAbsent(ip) { mutableListOf() }
        synchronized(entry) {
            prune(entry, now)
            return entry.size < limitProvider()
        }
    }

    /** 必须在 synchronized(entry) 内调用，保证与调用方检查/写入同一把锁。 */
    private fun prune(entry: MutableList<Long>, now: Long) {
        entry.removeAll { it <= now - windowMs }
    }
}
```

> 执行注意：落地以现有实现为准做最小改动，保留原类注释（含「不适用多实例部署」）与 per-entry `synchronized(entry)` 锁结构，只新增 `peek` 与 provider 委托构造。

- [x] **Step 4: 测试（新建或追加 RateLimiterTest）**

```kotlin
package com.mamba.picme.server.ratelimit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterPeekTest {

    @Test
    fun `peek does not consume quota`() {
        val limiter = RateLimiter(1, 60_000L)
        assertTrue(limiter.peek("u"))
        assertTrue(limiter.peek("u"))   // 两次 peek 都不消耗
        assertTrue(limiter.allow("u"))  // 第一次 allow 仍成功
        assertFalse(limiter.peek("u"))  // 满了
        assertFalse(limiter.allow("u"))
    }
}
```

- [x] **Step 5: 跑测试 + commit**

Run: `./gradlew -p server test --tests "*RateLimiter*"`
Expected: PASS

```bash
git add server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt server/src/main/kotlin/com/mamba/picme/server/ratelimit/RateLimiter.kt server/.env.example server/src/test/kotlin/com/mamba/picme/server/ratelimit/
git commit -m "feat(server): browser 配额基础——AppConfig 三项 + RateLimiter peek/provider"
```

### Task 5: SettingsService + 管理后台表单（每日配额可配）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/config/SettingsService.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt`（seed）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`（settings POST 参数）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`（settings 表单输入框）

- [x] **Step 1: SettingsService 五处改动**（先例路径见 explore：`SettingsService.kt:19-20/22/25/37-53`）

```kotlin
const val KEY_BROWSER_DAILY = "browser_daily_quota"
```

- `Snapshot` data class 加 `val browserDailyQuota: Int`；`@Volatile` 默认快照同步加（默认 20）；
- `readAll()` 加该 key 读取，缺省 `?: 20`；
- `update()` 签名加 `browserDailyQuota: Int? = null`（null = 不改），非空时写库 + 刷新快照。

- [x] **Step 2: Migrations seed**

`db/Migrations.kt` `seedSettings`（:175-181 区域）加：

```kotlin
seedIfAbsent(SettingsService.KEY_BROWSER_DAILY, config.browserDailyQuota.toString(), now)
```

> 对照现有 `seedIfAbsent` 签名（:183-192）传参；若现有 seed 值存 Int 转 String 形式不同，按现状对齐。

- [x] **Step 3: AdminRoutes settings 表单**

`POST /admin/settings`（:258-277）解析段加：

```kotlin
val browserDaily = params["browser_daily_quota"]?.toIntOrNull()
```

并传给 `SettingsService.update(..., browserDailyQuota = browserDaily)`。

- [x] **Step 4: AdminViews settings 页**

在现有 free/guest 额度输入框同款区块后加一行（label「Browser 每日会话配额」，`name="browser_daily_quota"`，value 取 `SettingsService.snapshot().browserDailyQuota`）。

- [x] **Step 5: 编译 + 相关测试 + commit**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL（现有 AdminRoutesTest/AdminViewsTest 若因 Snapshot 构造参数增加而编译失败，同步补默认参数值）

```bash
git add server/src/main/kotlin/com/mamba/picme/server/config/SettingsService.kt server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt server/src/main/kotlin/com/mamba/picme/server/admin/
git commit -m "feat(server): browser 每日会话配额入 server_setting + 管理后台可配"
```

### Task 6: BrowserBridgeClient + BrowserConcurrencyRegistry + 会话统计表

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/browser/BrowserBridgeClient.kt`
- Create: `server/src/main/kotlin/com/mamba/picme/server/browser/BrowserConcurrencyRegistry.kt`
- Create: `server/src/main/kotlin/com/mamba/picme/server/browser/BrowserSessionStats.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt`（SchemaUtils.create 列表）
- Create: `server/migrations/011_browser_session.sql`
- Test: `server/src/test/kotlin/com/mamba/picme/server/browser/BrowserConcurrencyRegistryTest.kt`

- [ ] **Step 1: 写失败测试 `BrowserConcurrencyRegistryTest.kt`**

```kotlin
package com.mamba.picme.server.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserConcurrencyRegistryTest {

    @Test
    fun `one active session per user`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
        assertFalse(reg.tryAcquire("u1", now = 2_000L))  // 同用户冲突
        assertTrue(reg.tryAcquire("u2", now = 2_000L))   // 不同用户互不影响
        reg.release("u1")                                 // 未 bind，走 null 清理路径
        assertTrue(reg.tryAcquire("u1", now = 3_000L))
    }

    @Test
    fun `stale lease is swept`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
        // 61s 后租约过期，崩溃未释放也能再获取
        assertTrue(reg.tryAcquire("u1", now = 62_000L))
    }

    @Test
    fun `bind and activeCount`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        reg.tryAcquire("u1", now = 1_000L)
        reg.bind("u1", "sess-1")
        assertEquals(1, reg.activeCount())
        reg.release("u1", "sess-1")
        assertEquals(0, reg.activeCount())
    }

    @Test
    fun `release on non-existent owner is no-op`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        reg.release("ghost")            // 不抛异常
        reg.release("ghost", "sess-x")  // 条件路径同样不抛
        assertEquals(0, reg.activeCount())
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
    }

    @Test
    fun `conditional release only evicts matching session`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        reg.tryAcquire("u1", now = 1_000L)
        reg.bind("u1", "sess-new")
        reg.release("u1", "sess-stale") // 旧请求的出错路径 release，不匹配则保留
        assertFalse(reg.tryAcquire("u1", now = 2_000L))
        reg.release("u1", "sess-new")
        assertTrue(reg.tryAcquire("u1", now = 3_000L))
    }

    @Test
    fun `bind refreshes lease clock`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        reg.tryAcquire("u1", now = 1_000L)
        reg.bind("u1", "sess-1") // bind 内部用 System.currentTimeMillis() 刷新租约时钟
        // 以 bind 时刻为基准留 1s 余量：59s 内仍占坑（若时钟未刷新，按 1000L 起算早被清扫），61s 后被清扫
        val bindNow = System.currentTimeMillis()
        assertFalse(reg.tryAcquire("u1", now = bindNow + 59_000L))
        assertTrue(reg.tryAcquire("u1", now = bindNow + 61_000L))
    }

    @Test
    fun `sweep evicts expired only, keeps fresh`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
        assertTrue(reg.tryAcquire("u2", now = 50_000L))
        // 62s：u1 过期被清扫，u2（50s 获取，仅过 12s）仍占坑
        assertTrue(reg.tryAcquire("u1", now = 62_000L))
        assertFalse(reg.tryAcquire("u2", now = 62_000L))
        assertTrue(reg.tryAcquire("u3", now = 62_000L)) // 清扫正常，第三人可进
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*BrowserConcurrencyRegistry*"`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现三个新文件 + 表**

`browser/BrowserConcurrencyRegistry.kt`：

```kotlin
package com.mamba.picme.server.browser

import java.util.concurrent.ConcurrentHashMap

/**
 * per-user 并发=1 登记（spec §7）：内存态租约，重启即清；
 * 租约过期兜底（App 崩溃/未 close 也能自愈），与 RateLimiter 同样单实例约束。
 */
class BrowserConcurrencyRegistry(private val leaseMs: Long = 15 * 60_000L) {

    private data class Lease(val sessionId: String?, val acquiredAt: Long)

    private val leases = ConcurrentHashMap<String, Lease>()

    @Synchronized
    fun tryAcquire(owner: String, now: Long = System.currentTimeMillis()): Boolean {
        sweep(now)
        return leases.putIfAbsent(owner, Lease(null, now)) == null
    }

    fun bind(owner: String, sessionId: String) {
        leases[owner] = Lease(sessionId, System.currentTimeMillis())
    }

    /**
     * 条件释放：sessionId 为 null（清理路径）或与现存租约的 sessionId 匹配时才移除；
     * 防止过期请求的出错路径 release 误删刚被重新获取的新租约。
     */
    fun release(owner: String, sessionId: String? = null) {
        leases.computeIfPresent(owner) { _, lease ->
            if (sessionId == null || lease.sessionId == sessionId) null else lease
        }
    }

    fun activeCount(): Int = leases.size

    private fun sweep(now: Long) {
        leases.entries.removeIf { now - it.value.acquiredAt > leaseMs }
    }
}
```

`browser/BrowserBridgeClient.kt`：

```kotlin
package com.mamba.picme.server.browser

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * xuxing browser-agent-bridge 的 HTTP 客户端（spec §2）：
 * 域名结果由 bridge 以 JSON status 承载，本类只做透传 + X-Bridge-Token 注入；
 * 网络异常抛给调用方（路由层统一映射 503 browser_unavailable）。
 *
 * 响应体所有权：调用方在每条路径（含非 2xx / 错误路径）都必须消费响应体
 * （bodyAsText）或取消响应，否则连接不会归还连接池，最终耗尽。
 */
class BrowserBridgeClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val bridgeToken: String,
) {
    val available: Boolean get() = baseUrl.isNotBlank() && bridgeToken.isNotBlank()

    suspend fun open(body: String): HttpResponse = post("$baseUrl/session", body)

    suspend fun action(sessionId: String, body: String): HttpResponse =
        post("$baseUrl/session/$sessionId/action", body)

    suspend fun close(sessionId: String): HttpResponse =
        post("$baseUrl/session/$sessionId/close", "{}")

    suspend fun frame(sessionId: String): HttpResponse =
        httpClient.get("$baseUrl/session/$sessionId/frame") {
            header("X-Bridge-Token", bridgeToken)
        }

    private suspend fun post(url: String, body: String): HttpResponse =
        httpClient.post(url) {
            header("X-Bridge-Token", bridgeToken)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}
```

`Tables.kt` 追加（仿 :160-165 ServerSettings 写法）：

```kotlin
object BrowserSessions : Table("browser_sessions") {
    val id = long("id").autoIncrement()
    val tokenHash = varchar("token_hash", 64)
    val sessionId = varchar("session_id", 64)
    val startedAt = long("started_at")
    val endedAt = long("ended_at").nullable()
    val outcome = varchar("outcome", 16).nullable() // closed / expired
    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, sessionId) // recordClose 按 session_id 定位
        index(isUnique = false, startedAt) // 概览按时间聚合
    }
}
```

`browser/BrowserSessionStats.kt`：

```kotlin
package com.mamba.picme.server.browser

import com.mamba.picme.server.db.BrowserSessions
import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/** browser 会话统计落库（spec §7 管理后台「概览」数据源）。 */
class BrowserSessionStats {

    suspend fun recordOpen(tokenHash: String, sessionId: String, now: Long = System.currentTimeMillis()) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            BrowserSessions.insert {
                it[BrowserSessions.tokenHash] = tokenHash
                it[BrowserSessions.sessionId] = sessionId
                it[startedAt] = now
            }
        }
    }

    suspend fun recordClose(sessionId: String, outcome: String, now: Long = System.currentTimeMillis()) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            BrowserSessions.update({ (BrowserSessions.sessionId eq sessionId) and BrowserSessions.endedAt.isNull() }) {
                it[endedAt] = now
                it[BrowserSessions.outcome] = outcome
            }
        }
    }
}
```

`Migrations.kt` 的 `SchemaUtils.create(...)` 表列表（:16-21）追加 `BrowserSessions`。

`server/migrations/011_browser_session.sql`（参考 DDL，运行时由 SchemaUtils 自动建表）：

```sql
-- 011_browser_session.sql — browser 会话统计（spec: browser-vnc 直播卡 §7）
-- 运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对。
CREATE TABLE IF NOT EXISTS browser_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token_hash VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    started_at BIGINT NOT NULL,
    ended_at BIGINT NULL,
    outcome VARCHAR(16) NULL
);
CREATE INDEX IF NOT EXISTS idx_browser_sessions_session_id ON browser_sessions(session_id);
CREATE INDEX IF NOT EXISTS idx_browser_sessions_started_at ON browser_sessions(started_at);
```

- [ ] **Step 4: 跑测试 + commit**

Run: `./gradlew -p server test --tests "*BrowserConcurrencyRegistry*" && ./gradlew -p server build`
Expected: PASS + BUILD SUCCESSFUL

```bash
git add server/src/main/kotlin/com/mamba/picme/server/browser/ server/src/main/kotlin/com/mamba/picme/server/db/ server/migrations/011_browser_session.sql server/src/test/kotlin/com/mamba/picme/server/browser/
git commit -m "feat(server): bridge client + per-user 并发登记 + browser_sessions 统计表"
```

### Task 7: BrowserRoute + Application 装配

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/routes/BrowserRoute.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/routes/BrowserRouteTest.kt`

- [x] **Step 1: 写失败测试 `BrowserRouteTest.kt`**（形态仿 `IssueReportRouteTest.kt` + `ClaudeRouteTestSupport.kt` 的 MockEngine 工厂）

```kotlin
package com.mamba.picme.server.routes

import com.mamba.picme.server.browser.BrowserBridgeClient
import com.mamba.picme.server.browser.BrowserConcurrencyRegistry
import com.mamba.picme.server.browser.BrowserSessionStats
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.util.TestDb
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.client.HttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRouteTest {

    private fun bridgeReturning(payload: String): BrowserBridgeClient =
        BrowserBridgeClient(
            HttpClient(MockEngine { respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            "http://bridge",
            "tok",
        )

    private fun TestApplicationBuilder.app(bridge: BrowserBridgeClient, concurrency: BrowserConcurrencyRegistry, limiter: RateLimiter) {
        // 鉴权拦截器与 IssueReportRouteTest 同形：X-App-Token 固定映射一个 tokenHash
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { browserRoute(bridge, limiter, concurrency, BrowserSessionStats()) }
        }
    }

    @Test
    fun `open proxies to bridge and binds concurrency on ok`() = testApplication {
        val concurrency = BrowserConcurrencyRegistry()
        val bridge = bridgeReturning("""{"status":"ok","sessionId":"s-1","currentUrl":"https://example.com"}""")
        app(bridge, concurrency, RateLimiter(20, 86_400_000L))
        val resp = client.post("/v1/browser/open") {
            header("X-App-Token", "test-token")
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://example.com","wantFrame":true}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"sessionId\":\"s-1\""))
        assertEquals(1, concurrency.activeCount())
    }

    @Test
    fun `open rejected when user already has active session`() = testApplication {
        val concurrency = BrowserConcurrencyRegistry()
        concurrency.tryAcquire("test-token-hash") // 预置：测试拦截器映射的 hash 需与实现一致
        app(bridgeReturning("{}"), concurrency, RateLimiter(20, 86_400_000L))
        val resp = client.post("/v1/browser/open") {
            header("X-App-Token", "test-token")
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://example.com"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("pool_exhausted"))
    }

    @Test
    fun `open rejected over daily quota`() = testApplication {
        val limiter = RateLimiter(0, 86_400_000L) // 配额 0
        app(bridgeReturning("{}"), BrowserConcurrencyRegistry(), limiter)
        val resp = client.post("/v1/browser/open") {
            header("X-App-Token", "test-token")
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://example.com"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
    }

    @Test
    fun `open with non-ok bridge result neither charges quota nor holds lease`() = testApplication {
        val concurrency = BrowserConcurrencyRegistry()
        val limiter = RateLimiter(1, 86_400_000L) // 配额 1：若非 ok 也计费，第二次必定 429
        app(bridgeReturning("""{"status":"pool_exhausted","errorCode":"pool"}"""), concurrency, limiter)
        repeat(2) {
            val resp = client.post("/v1/browser/open") {
                header("X-App-Token", "test-token")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"url":"https://example.com"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
        assertEquals(0, concurrency.activeCount())
    }

    @Test
    fun `open with unreachable bridge returns 503 and neither charges quota nor holds lease`() = testApplication {
        val concurrency = BrowserConcurrencyRegistry()
        val bridge = BrowserBridgeClient(
            HttpClient(MockEngine { throw java.net.ConnectException("refused") }),
            "http://bridge",
            "tok",
        )
        app(bridge, concurrency, RateLimiter(1, 86_400_000L))
        repeat(2) {
            val resp = client.post("/v1/browser/open") {
                header("X-App-Token", "test-token")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"url":"https://example.com"}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        }
        assertEquals(0, concurrency.activeCount())
    }
}
```

> 执行注意：测试里鉴权拦截器的具体形态照抄 `IssueReportRouteTest.kt:50-61`（含 TestDb/seedToken 或简化的固定 hash 注入）；`ownerTokenHash()` 取到的 owner 字符串是什么（sha256(token) 还是固定值）以该测试辅助代码为准，预置并发租约时用同一值。Stats 写库需要 `TestDb.init(Accounts, BrowserSessions)` 建表（仿 `util/TestDb.kt:13-20` 用法）。

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*BrowserRoute*"`
Expected: FAIL（browserRoute 不存在）

- [x] **Step 3: 实现 `routes/BrowserRoute.kt`**

```kotlin
package com.mamba.picme.server.routes

import com.mamba.picme.server.browser.BrowserBridgeClient
import com.mamba.picme.server.browser.BrowserConcurrencyRegistry
import com.mamba.picme.server.browser.BrowserSessionStats
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("picme-browser")

/**
 * 云端浏览器网关（spec §2/§6/§7）：
 * X-App-Token 鉴权（全局拦截器）→ 每日配额 peek（成功才 allow 计费）→ per-user 并发=1 →
 * 透传 xuxing bridge。bridge 域名结果（含 pool_exhausted/session_expired/action_failed）
 * 以 JSON status 原样透传给 App；bridge 不可达统一 503 browser_unavailable 且不计额度。
 */
fun Route.browserRoute(
    bridge: BrowserBridgeClient,
    dailyLimiter: RateLimiter,
    concurrency: BrowserConcurrencyRegistry,
    stats: BrowserSessionStats,
) {
    post("/v1/browser/open") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        if (!bridge.available) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@post
        }
        if (!dailyLimiter.peek(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "quota_exceeded", "tier" to "account"))
            return@post
        }
        if (!concurrency.tryAcquire(owner)) {
            call.respondText(BUSY_USER_PAYLOAD, ContentType.Application.Json, HttpStatusCode.OK)
            return@post
        }
        val upstream = try {
            bridge.open(call.receiveText())
        } catch (e: Throwable) {
            concurrency.release(owner)
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@post
        }
        val payload = upstream.bodyAsText()
        val sessionId = probeOkSessionId(payload)
        if (sessionId != null) {
            concurrency.bind(owner, sessionId)
            dailyLimiter.allow(owner) // 成功才计额度（spec §6：browser_unavailable 不计）
            // 统计写故障隔离：telemetry 挂不得破坏响应契约（租约已绑/额度已计）
            runCatching { stats.recordOpen(owner, sessionId) }
                .onFailure { logger.warn("browser stats recordOpen failed: sessionId=$sessionId", it) }
        } else {
            concurrency.release(owner)
        }
        call.respondText(payload, ContentType.Application.Json, upstream.status)
    }

    post("/v1/browser/action") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        val body = call.receiveText()
        val sessionId = probeSessionId(body) ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "sessionId required"))
            return@post
        }
        val upstream = try {
            bridge.action(sessionId, body)
        } catch (e: Throwable) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@post
        }
        val payload = upstream.bodyAsText()
        if (probeStatus(payload) == "session_expired") {
            concurrency.release(owner, sessionId) // 条件释放：不误删重新获取的新租约
            runCatching { stats.recordClose(sessionId, "expired") }
                .onFailure { logger.warn("browser stats recordClose failed: sessionId=$sessionId", it) }
        }
        call.respondText(payload, ContentType.Application.Json, upstream.status)
    }

    get("/v1/browser/frame") {
        call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@get
        }
        val sessionId = call.request.queryParameters["sessionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "sessionId required"))
            return@get
        }
        val upstream = try {
            bridge.frame(sessionId)
        } catch (e: Throwable) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@get
        }
        call.respondText(upstream.bodyAsText(), ContentType.Application.Json, upstream.status)
    }

    post("/v1/browser/close") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        val body = call.receiveText()
        val sessionId = probeSessionId(body) ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "sessionId required"))
            return@post
        }
        val upstream = try {
            bridge.close(sessionId)
        } catch (e: Throwable) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@post
        }
        concurrency.release(owner, sessionId) // 条件释放：不误删重新获取的新租约
        runCatching { stats.recordClose(sessionId, "closed") }
            .onFailure { logger.warn("browser stats recordClose failed: sessionId=$sessionId", it) }
        call.respondText(upstream.bodyAsText(), ContentType.Application.Json, upstream.status)
    }
}

private const val BUSY_USER_PAYLOAD =
    """{"status":"pool_exhausted","errorCode":"user_concurrency","reason":"active browser session exists, close it first"}"""

private val probeJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class StatusProbe(val status: String? = null, val sessionId: String? = null)

/** open 响应里提取成功会话 id（status==ok 且 sessionId 非空）；解析失败按非 ok 处理。 */
private fun probeOkSessionId(payload: String): String? =
    runCatching { probeJson.decodeFromString<StatusProbe>(payload) }.getOrNull()
        ?.takeIf { it.status == "ok" && !it.sessionId.isNullOrBlank() }?.sessionId

/** 响应 payload 里解析 status 字段；解析失败返回 null。 */
private fun probeStatus(payload: String): String? =
    runCatching { probeJson.decodeFromString<StatusProbe>(payload) }.getOrNull()?.status

/** 请求体里宽松提取 sessionId 字段（不入库的轻量解析）。 */
private fun probeSessionId(payload: String): String? =
    runCatching { probeJson.decodeFromString<StatusProbe>(payload) }.getOrNull()?.sessionId
```

> 执行注意：①`call.ownerTokenHash()` 若在 `ClaudeChatRoute.kt` 是 file-private，把其实现（:146-151）复制到本文件；②请求体 sessionId 用 `probeSessionId(body)` 宽松解析（M1 唯一需要）；③测试辅助里的鉴权拦截器要与 `IssueReportRouteTest` 同形注入 `TokenHashKey`；④`stats.recordOpen/recordClose` 为 suspend（Task 6 挂起事务化），在路由 handler 内直接调用即可，但必须 `runCatching` 包裹 + warn 日志（统计写故障隔离，不得破坏响应契约）；⑤open 路径两处 `release(owner)`（异常/非 ok）保持 null 清理形态——租约尚未 bind；action/close 路径用 `release(owner, sessionId)` 条件形态；⑥session_expired 判定用 `probeStatus(payload)` 解析式，不做子串匹配。

- [x] **Step 4: Application.kt 装配**

`module()` 依赖构造区（仿 :144-152 既有 client 构造）加：

```kotlin
val browserHttpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
    engine { requestTimeout = 30_000 }   // spec §6：网关 30s 超时
}
val browserBridge = BrowserBridgeClient(browserHttpClient, config.browserBridgeUrl, config.browserBridgeToken)
val browserConcurrency = BrowserConcurrencyRegistry()
val browserDailyLimiter = RateLimiter(config.browserDailyQuota, 24 * 60 * 60_000L)
val browserStats = BrowserSessionStats()
```

`routing {}` 块（:168-190）加：

```kotlin
browserRoute(browserBridge, browserDailyLimiter, browserConcurrency, browserStats)
```

> 注意：每日配额若要求 admin 热改生效，把 `RateLimiter(config.browserDailyQuota, ...)` 换成 `RateLimiter({ SettingsService.snapshot().browserDailyQuota }, ...)` 的 provider 构造（Task 4 已备）。优先用 provider 形态。

- [x] **Step 5: 跑全部 server 测试 + commit**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL（含 BrowserRouteTest 五用例）

```bash
git add server/src/main/kotlin/com/mamba/picme/server/routes/BrowserRoute.kt server/src/main/kotlin/com/mamba/picme/server/Application.kt server/src/test/kotlin/com/mamba/picme/server/routes/BrowserRouteTest.kt
git commit -m "feat(server): /v1/browser/* 网关——鉴权/配额/并发登记/透传/超时降级"
```

### Task 8: 管理后台「概览」browser 统计行

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`

- [x] **Step 1: AdminQueries 加统计查询**

```kotlin
data class BrowserOverview(val todayCount: Long, val activeCount: Long, val failureRate7d: Double)

fun browserOverview(now: Long = System.currentTimeMillis()): BrowserOverview = transaction {
    val dayStart = now - now % 86_400_000L
    val weekStart = now - 7 * 86_400_000L
    val today = BrowserSessions.selectAll().where { BrowserSessions.startedAt greaterEq dayStart }.count()
    val active = BrowserSessions.selectAll().where { BrowserSessions.endedAt.isNull() }.count()
    val weekTotal = BrowserSessions.selectAll().where { BrowserSessions.startedAt greaterEq weekStart }.count()
    val weekFailed = BrowserSessions.selectAll().where {
        (BrowserSessions.startedAt greaterEq weekStart) and (BrowserSessions.outcome eq "expired")
    }.count()
    BrowserOverview(
        todayCount = today,
        activeCount = active,
        failureRate7d = if (weekTotal == 0L) 0.0 else weekFailed.toDouble() / weekTotal,
    )
}
```

> 执行注意：Exposed 查询 DSL 细节（`selectAll().where{}`、`isNull()`、`greaterEq` 中缀）对照 `AdminQueries.kt` 现有查询写法对齐；`and` 需要 `org.jetbrains.exposed.sql.and` import。

- [x] **Step 2: AdminViews 概览页加一行**

在概览页现有统计行同款位置加（文案英文，与后台现有风格一致）：

```
Browser sessions: today N · active M · 7d failure X.X%
```

- [x] **Step 3: 编译 + 测试 + commit**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL（AdminQueriesTest/AdminViewsTest 若有构造变化同步更新）

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/
git commit -m "feat(server): 管理后台概览加 browser 会话统计（当日/活跃/7日失败率）"
```

---

## Phase C：shared commonMain（协议 + 工具闭环 + parts 管线）

背景事实：@Tool 规范见 `ChatToolService.kt:40-59`（customName 确定性、参数禁 Kotlin 默认值、suspend、dispatchCommand 薄封装）；命令注册三件套 = `AgentCommands.kt` sealed 子类 + `getMethodName` when 分支（:566-617，穷尽性编译强制）+ capability `supportedCommands()`；capability 返回 `AgentAction.TextReply(commandId, message)`（observation 回灌 LLM）/ `AgentAction.Error`；占位管线挂点 = `TurnPartsReducer.kt:150-156/164-169/175-181`；codec 8 值硬锁在 `MessagePartsCodecTest.kt:180-197`。

### Task 9: 协议 DTO（端云同源）

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/browser/BrowserProtocol.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/browser/BrowserProtocolTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class BrowserProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `action result round-trip with frame`() {
        val r = BrowserActionResult(
            status = BrowserStatus.OK,
            sessionId = "s-1",
            currentUrl = "https://example.com",
            pageTitle = "Example",
            frameJpegBase64 = "anFk",
            actionMs = 123,
        )
        val decoded = json.decodeFromString<BrowserActionResult>(json.encodeToString(BrowserActionResult.serializer(), r))
        assertEquals(r, decoded)
    }

    @Test
    fun `error result keeps structured codes`() {
        val decoded = json.decodeFromString<BrowserActionResult>(
            """{"status":"pool_exhausted","errorCode":"user_concurrency","reason":"busy","unknownField":1}"""
        )
        assertEquals(BrowserStatus.POOL_EXHAUSTED, decoded.status)
        assertEquals("user_concurrency", decoded.errorCode)
        assertNull(decoded.frameJpegBase64)
    }

    @Test
    fun `frame result round-trip`() {
        val r = BrowserFrameResult(status = BrowserStatus.OK, sessionId = "s", currentUrl = "u", pageTitle = "t", frameJpegBase64 = "Zg==")
        val decoded = json.decodeFromString<BrowserFrameResult>(json.encodeToString(BrowserFrameResult.serializer(), r))
        assertEquals(r, decoded)
    }

    @Test
    fun `action request round-trip with element target`() {
        val req = BrowserActionRequest(
            sessionId = "s-1",
            action = BrowserAction.CLICK,
            targetIndex = 3,
            wantFrame = true,
        )
        val decoded = json.decodeFromString<BrowserActionRequest>(json.encodeToString(BrowserActionRequest.serializer(), req))
        assertEquals(req, decoded)
    }

    @Test
    fun `action result carries interactive elements`() {
        val decoded = json.decodeFromString<BrowserActionResult>(
            """{"status":"ok","sessionId":"s","elements":[{"index":0,"tag":"a","text":"Sign in","href":"https://example.com/login","type":null}]}"""
        )
        assertEquals(1, decoded.elements?.size)
        assertEquals("Sign in", decoded.elements?.first()?.text)
        assertEquals("https://example.com/login", decoded.elements?.first()?.href)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*BrowserProtocolTest*"`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 `BrowserProtocol.kt`**

```kotlin
package com.mamba.picme.domain.browser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 云端浏览器协议（端云同源 SSOT，spec §2/§3）：
 * App ↔ picme-server ↔ xuxing browser-agent-bridge 三段共用同一 JSON 形态，
 * 域名结果一律以 [BrowserActionResult.status] 承载（HTTP 仅表达传输/鉴权层失败）。
 */

/** 结构化状态闭集（spec §6 错误分层）。 */
object BrowserStatus {
    const val OK = "ok"
    const val ACTION_FAILED = "action_failed"
    const val POOL_EXHAUSTED = "pool_exhausted"
    const val SESSION_EXPIRED = "session_expired"
    const val BROWSER_UNAVAILABLE = "browser_unavailable"
    const val QUOTA_EXCEEDED = "quota_exceeded"
}

/** 动作枚举（wire 值 = 工具名去 browser_ 前缀）。 */
object BrowserAction {
    const val NAVIGATE = "navigate"
    const val CLICK = "click"
    const val TYPE = "type"
    const val EXTRACT = "extract"
    const val SCREENSHOT = "screenshot"
}

/**
 * 动作请求 DTO（端 → 云统一形态，借鉴 openmuse：click/type 三模式定位）。
 * 定位优先级：targetIndex（extract 返回的元素序号）> targetText（可见文本匹配）> selector（CSS，兜底）。
 * 不用字段传 null；App 侧 @Tool 参数的空串/-1 哨兵由能力层归一为 null。
 * 🔴 [targetIndex] wire 字段名为 `index`（bridge `_locate` 读 `body.index`，网关逐字节透传），
 * Kotlin 属性名保持 targetIndex 仅为可读性；golden 测试钉桩防漂移。
 */
@Serializable
data class BrowserActionRequest(
    val sessionId: String,
    val action: String,
    val url: String? = null,
    val selector: String? = null,
    val targetText: String? = null,
    @SerialName("index") val targetIndex: Int? = null,
    val text: String? = null,
    val wantFrame: Boolean = false,
)

/** extract 返回的交互元素（LLM 凭 index/text 回指，不用猜盲 selector）。 */
@Serializable
data class BrowserElement(
    val index: Int,
    val tag: String,
    val text: String? = null,
    val href: String? = null,
    val type: String? = null,
)

/**
 * open/action/close 统一响应（[textExtract]/[frameJpegBase64]/[elements] 按动作与 wantFrame 可选出现）。
 * close 响应额外携带 [closed] 字段（bridge 返回 `{status:"ok",sessionId,closed,actionCount,lastGoodFrame}`）；
 * 其他消费方若自行解析须配 `ignoreUnknownKeys = true`（bridge 响应字段是本模型的超集演进方向）。
 */
@Serializable
data class BrowserActionResult(
    val status: String,
    val sessionId: String? = null,
    val currentUrl: String? = null,
    val pageTitle: String? = null,
    val textExtract: String? = null,
    val frameJpegBase64: String? = null,
    val elements: List<BrowserElement>? = null,
    val actionMs: Long? = null,
    val actionCount: Int? = null,
    val closed: Boolean? = null,
    val errorCode: String? = null,
    val reason: String? = null,
    val lastGoodFrame: String? = null,
)

/** watch 模式取帧响应（GET /v1/browser/frame）。 */
@Serializable
data class BrowserFrameResult(
    val status: String,
    val sessionId: String? = null,
    val currentUrl: String? = null,
    val pageTitle: String? = null,
    val frameJpegBase64: String? = null,
    val errorCode: String? = null,
    val reason: String? = null,
)

/** 传输层异常（网络失败/503）：能力层据此走 browser_unavailable 降级（spec §6）。 */
class BrowserUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

- [ ] **Step 4: 跑测试确认通过 + commit**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*BrowserProtocolTest*"`
Expected: PASS

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/browser/ shared/src/commonTest/kotlin/com/mamba/picme/domain/browser/
git commit -m "feat(shared): browser 协议 DTO——状态闭集 + 双响应 + 传输异常（端云同源）"
```

### Task 10: 命令模型 + BrowserSessionCapability

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/command/AgentCommands.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/capability/BrowserSessionCapability.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/capability/BrowserSessionCapabilityTest.kt`

- [x] **Step 1: AgentCommands.kt 加 7 个命令**

在 `RenderHtml`（:463-468）之后插入（KDoc 风格对齐现有命令）：

```kotlin
    // ==================== 云端浏览器命令（spec: browser-vnc 直播卡） ====================

    /**
     * 打开云端浏览器会话（headless Chromium，远程执行）。返回 sessionId 供后续动作复用。
     * 一次会话对应 Chat 内一张直播卡；任务完成必须调 [BrowserClose]。
     */
    data class BrowserOpen(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val url: String
    ) : AgentCommand()

    /** 会话内导航到新 URL。 */
    data class BrowserNavigate(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val sessionId: String,
        val url: String
    ) : AgentCommand()

    /** 点击元素（三模式定位，优先级 targetIndex > targetText > selector；空串/-1 为「未提供」哨兵）。 */
    data class BrowserClick(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val sessionId: String,
        val selector: String = "",
        val targetText: String = "",
        val targetIndex: Int = -1
    ) : AgentCommand()

    /** 向输入框键入文本（定位模式同 [BrowserClick]）。 */
    data class BrowserType(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val sessionId: String,
        val selector: String = "",
        val targetText: String = "",
        val targetIndex: Int = -1,
        val text: String
    ) : AgentCommand()

    /** 提取当前页面正文文本（截断 4000 字符，结果回灌 LLM，不产帧）。 */
    data class BrowserExtract(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val sessionId: String
    ) : AgentCommand()

    /** 抓当前页一帧（用户明确要截图时用；常规动作的帧由端侧策略自动控制）。 */
    data class BrowserScreenshot(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val sessionId: String
    ) : AgentCommand()

    /** 关闭会话并销毁远程实例（任务结束必须调用，否则 2 分钟空闲后服务端强制回收）。 */
    data class BrowserClose(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val sessionId: String
    ) : AgentCommand()
```

`getMethodName` when（:603 `is RenderHtml` 之后）加：

```kotlin
            is BrowserOpen -> "browser_open"
            is BrowserNavigate -> "browser_navigate"
            is BrowserClick -> "browser_click"
            is BrowserType -> "browser_type"
            is BrowserExtract -> "browser_extract"
            is BrowserScreenshot -> "browser_screenshot"
            is BrowserClose -> "browser_close"
```

- [x] **Step 2: 写失败测试（fake transport 驱动 capability）**

```kotlin
package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.domain.browser.BrowserActionRequest
import com.mamba.picme.domain.browser.BrowserActionResult
import com.mamba.picme.domain.browser.BrowserFrameResult
import com.mamba.picme.domain.browser.BrowserStatus
import com.mamba.picme.domain.browser.BrowserUnavailableException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrowserSessionCapabilityTest {

    private class FakeTransport(var result: BrowserActionResult) : BrowserTransport {
        val calls = mutableListOf<String>()
        var lastRequest: BrowserActionRequest? = null
        var failWith: BrowserUnavailableException? = null
        override suspend fun open(url: String, wantFrame: Boolean): BrowserActionResult {
            calls += "open:$url:$wantFrame"
            failWith?.let { throw it }
            return result
        }
        override suspend fun action(request: BrowserActionRequest): BrowserActionResult {
            calls += "action:${request.sessionId}:${request.action}:${request.wantFrame}"
            lastRequest = request
            failWith?.let { throw it }
            return result
        }
        override suspend fun frame(sessionId: String): BrowserFrameResult {
            calls += "frame:$sessionId"
            failWith?.let { throw it }
            return BrowserFrameResult(status = BrowserStatus.OK, sessionId = sessionId)
        }
        override suspend fun close(sessionId: String): BrowserActionResult {
            calls += "close:$sessionId"
            failWith?.let { throw it }
            return result
        }
    }

    private class RecordingDelegate : BrowserSessionDelegate {
        val events = mutableListOf<String>()
        override fun onBrowserSessionStarted(sessionId: String, url: String, title: String, frameJpegBase64: String?) { events += "start:$sessionId" }
        override fun onBrowserSessionAction(sessionId: String, action: String, selector: String?, text: String?, url: String, title: String, frameJpegBase64: String?) { events += "act:$sessionId:$action" }
        override fun onBrowserSessionFrame(sessionId: String, url: String, title: String, frameJpegBase64: String) { events += "frame:$sessionId" }
        override fun onBrowserSessionFailed(sessionId: String, reason: String) { events += "fail:$sessionId:$reason" }
        override fun onBrowserSessionClosed(sessionId: String, finalFrameJpegBase64: String?, actionCount: Int) { events += "close:$sessionId:$actionCount" }
    }

    private val context = AgentContext(scene = AgentScene.CHAT)

    @Test
    fun `open success emits started event and returns sessionId payload`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1", currentUrl = "https://a.com", pageTitle = "A", frameJpegBase64 = "Zg=="))
        val delegate = RecordingDelegate()
        val cap = BrowserSessionCapability(transport).also { it.setDelegate(delegate) }
        val result = cap.execute(AgentCommand.BrowserOpen(url = "https://a.com"), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertTrue(action.message.contains("s-1"))
        assertEquals(listOf("open:https://a.com:true"), transport.calls)
        assertEquals(listOf("start:s-1"), delegate.events)
    }

    @Test
    fun `extract does not request frame`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1", textExtract = "hello"))
        val cap = BrowserSessionCapability(transport)
        cap.execute(AgentCommand.BrowserExtract(sessionId = "s-1"), context, null)
        assertEquals(listOf("action:s-1:extract:false"), transport.calls)
    }

    @Test
    fun `pool exhausted maps to degradation text`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.POOL_EXHAUSTED, reason = "busy"))
        val cap = BrowserSessionCapability(transport)
        val result = cap.execute(AgentCommand.BrowserOpen(url = "https://a.com"), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertTrue(action.message.contains(BrowserStatus.POOL_EXHAUSTED))
    }

    @Test
    fun `transport exception maps to browser_unavailable degradation`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK)).also {
            it.failWith = BrowserUnavailableException("connect refused")
        }
        val cap = BrowserSessionCapability(transport)
        val result = cap.execute(AgentCommand.BrowserOpen(url = "https://a.com"), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertTrue(action.message.contains(BrowserStatus.BROWSER_UNAVAILABLE))
    }

    @Test
    fun `close emits closed event with action count`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1", actionCount = 7))
        val delegate = RecordingDelegate()
        val cap = BrowserSessionCapability(transport).also { it.setDelegate(delegate) }
        cap.execute(AgentCommand.BrowserClose(sessionId = "s-1"), context, null)
        assertEquals(listOf("close:s-1:7"), delegate.events)
    }

    @Test
    fun `click normalizes sentinel fields to null and prefers index`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1"))
        val cap = BrowserSessionCapability(transport)
        cap.execute(AgentCommand.BrowserClick(sessionId = "s-1", targetIndex = 3), context, null)
        val req = transport.lastRequest!!
        assertEquals(3, req.targetIndex)
        assertEquals(null, req.targetText)
        assertEquals(null, req.selector)
    }

    @Test
    fun `click requests frame`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1"))
        val cap = BrowserSessionCapability(transport)
        cap.execute(AgentCommand.BrowserClick(sessionId = "s-1", selector = "button"), context, null)
        assertEquals(listOf("action:s-1:click:true"), transport.calls)
    }

    @Test
    fun `supported commands cover all seven browser tools`() {
        val cap = BrowserSessionCapability(FakeTransport(BrowserActionResult(status = BrowserStatus.OK)))
        assertEquals(
            listOf("browser_open", "browser_navigate", "browser_click", "browser_type", "browser_extract", "browser_screenshot", "browser_close"),
            cap.supportedCommands(),
        )
    }
}
```

- [x] **Step 3: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*BrowserSessionCapabilityTest*"`
Expected: FAIL（类不存在）

- [x] **Step 4: 实现 `BrowserSessionCapability.kt`**

```kotlin
package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.domain.browser.BrowserAction
import com.mamba.picme.domain.browser.BrowserActionRequest
import com.mamba.picme.domain.browser.BrowserActionResult
import com.mamba.picme.domain.browser.BrowserFrameResult
import com.mamba.picme.domain.browser.BrowserStatus
import com.mamba.picme.domain.browser.BrowserUnavailableException
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

/** 浏览器传输层（commonMain 无 HTTP 手段，组合根注入平台实现；测试注入 fake）。 */
interface BrowserTransport {
    suspend fun open(url: String, wantFrame: Boolean): BrowserActionResult
    suspend fun action(request: BrowserActionRequest): BrowserActionResult
    suspend fun frame(sessionId: String): BrowserFrameResult
    suspend fun close(sessionId: String): BrowserActionResult
}

/** UI 侧会话事件出口（androidApp ChatViewModel 实现；帧只走本通道，不回灌 LLM）。 */
interface BrowserSessionDelegate {
    fun onBrowserSessionStarted(sessionId: String, url: String, title: String, frameJpegBase64: String?)
    fun onBrowserSessionAction(sessionId: String, action: String, selector: String?, text: String?, url: String, title: String, frameJpegBase64: String?)
    fun onBrowserSessionFrame(sessionId: String, url: String, title: String, frameJpegBase64: String)
    fun onBrowserSessionFailed(sessionId: String, reason: String)
    fun onBrowserSessionClosed(sessionId: String, finalFrameJpegBase64: String?, actionCount: Int)
}

/**
 * 云端浏览器能力（spec §2/§3/§6）。
 *
 * 帧策略（端侧决策，LLM 无感）：open/navigate/click/type/screenshot 带 wantFrame，
 * extract/close 不带；连续帧走 [BrowserTransport.frame]（watch 模式轮询）。
 *
 * 降级不变式：一切失败（池满/会话过期/不可达/动作失败）都映射为 TextReply 结构化文本
 * 交 LLM 降级处理，不抛异常穿透 ReAct 链（CancellationException 例外，原样 rethrow
 * 保护结构化并发/CommandExecutor 超时取消）。
 */
class BrowserSessionCapability(
    private val transport: BrowserTransport,
) : BaseCapability() {

    @Volatile
    private var delegate: BrowserSessionDelegate? = null

    fun setDelegate(value: BrowserSessionDelegate?) {
        delegate = value
    }

    override val name: String = "browser_session"
    override val description: String = "云端浏览器：打开网页、点击、输入、提取正文，过程画面回传直播卡"

    override fun supportedCommands(): List<String> = listOf(
        "browser_open", "browser_navigate", "browser_click", "browser_type",
        "browser_extract", "browser_screenshot", "browser_close",
    )

    override suspend fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext?): Result<AgentAction> {
        val reply = try {
            when (command) {
                is AgentCommand.BrowserOpen -> onOpen(command)
                is AgentCommand.BrowserNavigate -> onAction(
                    BrowserActionRequest(sessionId = command.sessionId, action = BrowserAction.NAVIGATE, url = command.url, wantFrame = true)
                )
                is AgentCommand.BrowserClick -> onAction(
                    BrowserActionRequest(
                        sessionId = command.sessionId, action = BrowserAction.CLICK, wantFrame = true,
                        selector = command.selector.ifEmpty { null },
                        targetText = command.targetText.ifEmpty { null },
                        targetIndex = command.targetIndex.takeIf { it >= 0 },
                    )
                )
                is AgentCommand.BrowserType -> onAction(
                    BrowserActionRequest(
                        sessionId = command.sessionId, action = BrowserAction.TYPE, wantFrame = true,
                        selector = command.selector.ifEmpty { null },
                        targetText = command.targetText.ifEmpty { null },
                        targetIndex = command.targetIndex.takeIf { it >= 0 },
                        text = command.text,
                    )
                )
                is AgentCommand.BrowserExtract -> onAction(
                    BrowserActionRequest(sessionId = command.sessionId, action = BrowserAction.EXTRACT, wantFrame = false)
                )
                is AgentCommand.BrowserScreenshot -> onAction(
                    BrowserActionRequest(sessionId = command.sessionId, action = BrowserAction.SCREENSHOT, wantFrame = true)
                )
                is AgentCommand.BrowserClose -> onClose(command.sessionId)
                else -> return Result.success(
                    AgentAction.Error(command.commandId, AgentErrorCode.METHOD_NOT_FOUND, "BrowserSessionCapability 不支持此命令")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrowserUnavailableException) {
            degradation(BrowserStatus.BROWSER_UNAVAILABLE, e.message ?: "network error")
        } catch (e: Exception) {
            degradation(BrowserStatus.ACTION_FAILED, e.message)
        }
        return Result.success(AgentAction.TextReply(commandId = command.commandId, message = reply))
    }

    private suspend fun onOpen(command: AgentCommand.BrowserOpen): String {
        val r = transport.open(command.url, wantFrame = true)
        if (r.status != BrowserStatus.OK || r.sessionId == null) return degradation(r.status, r.reason)
        delegate?.onBrowserSessionStarted(r.sessionId, r.currentUrl ?: command.url, r.pageTitle ?: "", r.frameJpegBase64)
        return "浏览器会话已开始 sessionId=${r.sessionId}，当前页面：${r.pageTitle ?: ""}（${r.currentUrl ?: command.url}）"
    }

    private suspend fun onAction(request: BrowserActionRequest): String {
        val r = transport.action(request)
        if (r.status != BrowserStatus.OK) {
            delegate?.onBrowserSessionFailed(request.sessionId, r.reason ?: r.status)
            return degradation(r.status, r.reason)
        }
        delegate?.onBrowserSessionAction(request.sessionId, request.action, request.selector, request.text, r.currentUrl ?: "", r.pageTitle ?: "", r.frameJpegBase64)
        return if (request.action == BrowserAction.EXTRACT) {
            val elementsHint = r.elements?.takeIf { it.isNotEmpty() }?.let { list ->
                "\n可交互元素（click/type 优先用 index 回指）：\n" +
                    list.joinToString("\n") { e ->
                        "[${e.index}] <${e.tag}> ${e.text ?: ""}${e.href?.let { h -> " -> $h" } ?: ""}${e.type?.let { t -> " (type=$t)" } ?: ""}"
                    }
            } ?: ""
            "页面正文：\n${r.textExtract ?: ""}$elementsHint"
        } else {
            "已执行 ${request.action}，当前页面：${r.pageTitle ?: ""}（${r.currentUrl ?: ""}）"
        }
    }

    private suspend fun onClose(sessionId: String): String {
        val r = transport.close(sessionId)
        delegate?.onBrowserSessionClosed(sessionId, r.lastGoodFrame, r.actionCount ?: 0)
        return "浏览器会话已结束，共执行 ${r.actionCount ?: 0} 个动作"
    }

    /** 结构化降级文本：状态码在前（机读），人话在后（LLM 组织致歉/替代方案）。 */
    private fun degradation(status: String, reason: String?): String = when (status) {
        BrowserStatus.POOL_EXHAUSTED ->
            "[$status] 云端浏览器资源繁忙：${reason ?: "pool full"}。请改用纯文本回答，并告知用户稍后再试。"
        BrowserStatus.SESSION_EXPIRED ->
            "[$status] 浏览器会话已过期或被回收：${reason ?: ""}。如需继续请重新 browser_open。"
        BrowserStatus.QUOTA_EXCEEDED ->
            "[$status] 今日浏览器会话额度已用完。请改用纯文本回答。"
        BrowserStatus.BROWSER_UNAVAILABLE ->
            "[$status] 云端浏览器暂不可达：${reason ?: ""}。请改用纯文本回答。"
        else ->
            "[${BrowserStatus.ACTION_FAILED}] 浏览器操作失败：${reason ?: "unknown"}。可重试一次或换策略。"
    }
}
```

- [x] **Step 5: 跑测试确认通过 + commit**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*BrowserSessionCapabilityTest*"`
Expected: PASS

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/command/AgentCommands.kt shared/src/commonMain/kotlin/com/mamba/picme/agent/core/capability/BrowserSessionCapability.kt shared/src/commonTest/kotlin/com/mamba/picme/agent/core/capability/BrowserSessionCapabilityTest.kt
git commit -m "feat(shared): 7 个 browser 命令 + BrowserSessionCapability（帧策略 + 降级不变式）"
```

### Task 11: Koog 工具表面（7 个 @Tool）+ prompt 规则

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/ChatToolService.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/prompt/ChatPromptRules.kt`

- [x] **Step 1: ChatToolService 加 7 个 @Tool**

在 `renderHtml`（:278-314）之后插入（@Tool 规范：customName 保线名、参数禁 Kotlin 默认值、@LLMDescription 逐参数、方法体一行 dispatchCommand）：

```kotlin
    @Tool(customName = "browser_open")
    @LLMDescription("打开云端浏览器会话并导航到指定 URL（远程 headless Chromium 执行，过程画面会以直播卡展示给用户）。返回 sessionId 供后续 browser_* 动作复用。需要浏览网页获取实时信息时使用；任务完成必须调 browser_close。")
    suspend fun browserOpen(
        @LLMDescription("要打开的完整 URL（http/https）")
        url: String,
    ): String =
        dispatchCommand(AgentCommand.BrowserOpen(url = url), timeoutMillis = BROWSER_DISPATCH_TIMEOUT_MS)

    @Tool(customName = "browser_navigate")
    @LLMDescription("云端浏览器会话内导航到新 URL。")
    suspend fun browserNavigate(
        @LLMDescription("browser_open 返回的会话 id")
        sessionId: String,
        @LLMDescription("目标完整 URL（http/https）")
        url: String,
    ): String =
        dispatchCommand(AgentCommand.BrowserNavigate(sessionId = sessionId, url = url), timeoutMillis = BROWSER_DISPATCH_TIMEOUT_MS)

    @Tool(customName = "browser_click")
    @LLMDescription("点击云端浏览器当前页面中的元素。三种定位方式按优先级选用：targetIndex（browser_extract 返回的元素序号，最可靠）> targetText（元素可见文本）> selector（CSS 选择器，兜底）。三者至少给一个，多余传空串/-1。")
    suspend fun browserClick(
        @LLMDescription("browser_open 返回的会话 id")
        sessionId: String,
        @LLMDescription("browser_extract 返回的元素序号；未使用传 -1")
        targetIndex: Int,
        @LLMDescription("元素可见文本（如 'Sign in'）；未使用传空串")
        targetText: String,
        @LLMDescription("CSS 选择器（如 'button.submit'）；未使用传空串")
        selector: String,
    ): String =
        dispatchCommand(AgentCommand.BrowserClick(sessionId = sessionId, targetIndex = targetIndex, targetText = targetText, selector = selector), timeoutMillis = BROWSER_DISPATCH_TIMEOUT_MS)

    @Tool(customName = "browser_type")
    @LLMDescription("向云端浏览器当前页面中的输入框键入文本。定位方式同 browser_click（优先 targetIndex）。")
    suspend fun browserType(
        @LLMDescription("browser_open 返回的会话 id")
        sessionId: String,
        @LLMDescription("browser_extract 返回的元素序号；未使用传 -1")
        targetIndex: Int,
        @LLMDescription("输入框可见文本/占位符；未使用传空串")
        targetText: String,
        @LLMDescription("CSS 选择器；未使用传空串")
        selector: String,
        @LLMDescription("要键入的文本")
        text: String,
    ): String =
        dispatchCommand(AgentCommand.BrowserType(sessionId = sessionId, targetIndex = targetIndex, targetText = targetText, selector = selector, text = text), timeoutMillis = BROWSER_DISPATCH_TIMEOUT_MS)

    @Tool(customName = "browser_extract")
    @LLMDescription("提取云端浏览器当前页面的正文文本（截断 4000 字符）与可交互元素清单（每个元素带 index/tag/text/href）。阅读页面内容用它，不要用 browser_screenshot 读内容；后续 click/type 优先用清单里的 index 定位。")
    suspend fun browserExtract(
        @LLMDescription("browser_open 返回的会话 id")
        sessionId: String,
    ): String =
        dispatchCommand(AgentCommand.BrowserExtract(sessionId = sessionId), timeoutMillis = BROWSER_DISPATCH_TIMEOUT_MS)

    @Tool(customName = "browser_screenshot")
    @LLMDescription("抓云端浏览器当前页一帧画面。仅当用户明确要截图时使用；常规动作的过程画面已自动回传。")
    suspend fun browserScreenshot(
        @LLMDescription("browser_open 返回的会话 id")
        sessionId: String,
    ): String =
        dispatchCommand(AgentCommand.BrowserScreenshot(sessionId = sessionId), timeoutMillis = BROWSER_DISPATCH_TIMEOUT_MS)

    @Tool(customName = "browser_close")
    @LLMDescription("关闭云端浏览器会话并销毁远程实例。浏览任务结束后必须调用（否则服务端 2 分钟空闲后强制回收）。")
    suspend fun browserClose(
        @LLMDescription("browser_open 返回的会话 id")
        sessionId: String,
    ): String =
        dispatchCommand(AgentCommand.BrowserClose(sessionId = sessionId), timeoutMillis = BROWSER_DISPATCH_TIMEOUT_MS)
```

- [x] **Step 2: ChatPromptRules 加 browser 行为规则段**

> **决策注记（2026-10-06 review 修订，两轮）**：browser_* 超时为**两层**结构——
> 外层 dispatch（ChatToolService：browser 工具 25s / 其余工具 5s）+ 内层命令执行
>（registry 级 `CommandExecutor`：显式 25s，CrossPageCommandQueue 共用同一实例一并受益）。
> 25s > 服务端 navTimeout 15s，< 网关 30s；既有工具外层 5s 保持最紧，内层放宽后不会单独触发，
> 行为不变。⚠️ 内层默认 10s 曾是最紧约束导致外层 25s 永不生效（复审发现的层叠缺陷）——
> 25s 常量为 `CommandExecutor.REGISTRY_COMMAND_TIMEOUT_MS` 单一来源，ChatToolService 的
> `BROWSER_DISPATCH_TIMEOUT_MS` 直接引用它，两处不漂移；层叠不变式「内层 ≥ browser 外层」
> 由 commonTest `CommandTimeoutLayeringTest` 钉住（读 registry 实例实际生效值，防构造点回退）。
> 外层实现方式为 `dispatchCommand`/`dispatchCommandWithTrace`/`dispatchCommandDetailed`
> 增加 `timeoutMillis` 可选参数（默认 `DISPATCH_TIMEOUT_MS`），7 个 browser 工具显式传 25s。
> 同时 browser_rules 节末尾补豁免句：浏览器会话期间不受 convergence_rules 约束
>（含调用次数上限与重复调用限制——否则 open→动作→close 最少 4 次、extract 合法重复的固定流程
> 与「每次请求最多 2 次工具调用」「绝不重复调用同一工具」矛盾，模型有中途放弃不 close 的风险）。
> 内嵌代码的 `dispatchCommand(...)` 一行方法体均已同步为显式传 `timeoutMillis = BROWSER_DISPATCH_TIMEOUT_MS`。

读 `ChatPromptRules.kt` 的分节结构，在工具使用规则相关节后追加一节（中英文按该文件现有语言风格对齐——若为中文规则文本则用下式）：

```
浏览器工具（browser_*）：用户的问题需要实时网页信息（价格、新闻、赛程、文档等）时使用。
流程固定为 browser_open → 若干动作（navigate/click/type/extract）→ browser_close；
一次会话聚焦一个任务，提取内容用 browser_extract 而非截图；
点击/输入优先用 browser_extract 返回的元素 index 定位，其次可见文本，CSS 选择器只作兜底；
任务结束（含中途放弃、额度/资源报错改纯文本回答）都必须 browser_close。
不要浏览用户未要求的站点，不要在网页上输入用户的账号密码等敏感信息。
浏览器会话期间（browser_open 到 browser_close 之间）不受下文收敛规则约束（含调用次数上限与重复调用限制）；browser_close 后立即总结回复。
```

- [x] **Step 3: 编译 + 重生成 prompt golden + 跑守卫测试**

```bash
JITPACK=true ./gradlew :shared:compileAndroidMain
POLANG_WRITE_GOLDEN=1 JITPACK=true ./gradlew :shared:jvmTest
JITPACK=true ./gradlew :shared:jvmTest
```

Expected: 第一次带 `POLANG_WRITE_GOLDEN=1` 重写 `shared/src/jvmTest/resources/golden/chat_system_prompt_golden.txt`（+可能 `chat_tool_inventory_golden.txt`）；第二次全绿（`ChatToolServiceInventoryTest` 自动覆盖新工具；`ChatToolManifestConsistencyTest` 锁的是 iOS manifest 8 工具集，不受影响——若其断言范围意外含 ChatToolService 反射总数，按测试内注释更新期望值并在 commit message 说明）。

- [x] **Step 4: 人工检查 golden diff（防 prompt 误伤）**

```bash
git diff shared/src/jvmTest/resources/golden/
```

Expected: 仅新增 browser_* 工具条目与规则段，无既有内容被误改；有误改先修再提交。

- [x] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/ChatToolService.kt shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/prompt/ChatPromptRules.kt shared/src/jvmTest/resources/golden/
git commit -m "feat(shared): 7 个 browser_* @Tool + prompt 规则段（golden 重生成）"
```

### Task 12: MessagePart.BrowserLive + codec 钉桩 8→9

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePart.kt`
- Modify: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/MessagePartsCodecTest.kt`

- [x] **Step 1: MessagePart.kt 加子类**

在 `OptimizeCandidates`（:149-157）前插入：

```kotlin
    /**
     * 云端浏览器直播卡（tool_browser，spec: browser-vnc 直播卡 §4）。
     * 瞬态轨：browser_open 占位 → 会话期间经 BrowserLiveOverlay 原位覆写（帧/动作流水）；
     * 持久轨：会话结束落最终帧 + 结果摘要 + 动作计数（state 恒 OUTPUT_AVAILABLE /
     * 失败 OUTPUT_ERROR）。帧永不回灌 LLM（回灌投影见 ChatModelInput）。
     */
    @Serializable
    @SerialName("tool_browser")
    data class BrowserLive(
        override val partId: String,
        val sessionId: String,
        val state: ToolPartState = ToolPartState.INPUT_STREAMING,
        val currentUrl: String = "",
        val pageTitle: String = "",
        val frameJpegBase64: String? = null,
        /** 最近动作流水（至多 3 条，新在尾）。 */
        val actions: List<BrowserActionEntry> = emptyList(),
        val actionCount: Int = 0,
        val resultSummary: String? = null,
        val errorReason: String? = null,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.TOOL
    }
```

sealed 块外（文件尾部 `PartState` 前）加：

```kotlin
/** 直播卡动作流水条目（description 已本地化——由 UI 侧组装，模型只搬运）。 */
@Serializable
data class BrowserActionEntry(
    val description: String,
    val ok: Boolean = true,
)
```

- [x] **Step 2: codec 测试改钉桩 + 加 round-trip 用例**

`MessagePartsCodecTest.kt`：
- `sealed descriptor locks the 8-value taxonomy`（:180-197）：`assertEquals(8, ...)` 改 `9`，serialNames 集合加 `"tool_browser"`，测试名改 9-value 措辞；
- 加 round-trip 用例：

```kotlin
    @Test
    fun `BrowserLive round-trip`() {
        val part = MessagePart.BrowserLive(
            partId = "call-1",
            sessionId = "s-1",
            state = ToolPartState.OUTPUT_AVAILABLE,
            currentUrl = "https://example.com",
            pageTitle = "Example",
            frameJpegBase64 = "anFk",
            actions = listOf(BrowserActionEntry("打开 example.com"), BrowserActionEntry("点击 价格")),
            actionCount = 2,
            resultSummary = "已查到价格",
        )
        val decoded = json.decodeFromString<MessagePart>(json.encodeToString(MessagePart.serializer(), part))
        assertEquals(part, decoded)
    }
```

> 执行注意：该测试文件的 Json 实例/断言风格以现有代码为准（kotlin.test）；serial name 前缀与 category 一致性测试（:200+）是硬编码 parts 清单遍历（非反射枚举），新子类须显式加一行 `MessagePart.BrowserLive("p0", sessionId = "s")` 才有 `tool_` ↔ TOOL 锁定。

- [x] **Step 3: 跑测试 + commit**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*MessagePartsCodecTest*"`
Expected: PASS

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePart.kt shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/MessagePartsCodecTest.kt
git commit -m "feat(shared): MessagePart.BrowserLive（tool_browser）——type 分类法 8→9"
```

### Task 13: 占位管线 + overlay + 拍平器 + Room 映射 + 回灌

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/streaming/TurnPartsReducer.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/BrowserLiveOverlay.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatListFlattener.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/MessagePartsConverter.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatModelInput.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/BrowserLiveOverlayTest.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/streaming/TurnPartsReducerBrowserTest.kt`

- [ ] **Step 1: 写失败测试**

`BrowserLiveOverlayTest.kt`：

```kotlin
package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserLiveOverlayTest {

    private fun msgWith(part: MessagePart): ChatMessage =
        ChatMessage(id = 1L, sessionId = 1L, type = "agent_text", role = "agent", content = "", timestamp = 0L, parts = listOf(part))

    @Test
    fun `live state overwrites matching session part in place`() {
        val base = MessagePart.BrowserLive(partId = "call-1", sessionId = "s-1", state = ToolPartState.INPUT_AVAILABLE)
        val live = base.copy(pageTitle = "A", frameJpegBase64 = "Zg==", actions = listOf(BrowserActionEntry("打开 a.com")))
        val out = msgWith(base).overlayLiveBrowserState(mapOf("s-1" to live))
        val part = out.parts.single() as MessagePart.BrowserLive
        assertEquals("A", part.pageTitle)
        assertEquals("Zg==", part.frameJpegBase64)
        assertEquals("call-1", part.partId) // partId 不动（LazyColumn key 恒定）
    }

    @Test
    fun `no live state returns same instance`() {
        val base = MessagePart.BrowserLive(partId = "call-1", sessionId = "s-1")
        val msg = msgWith(base)
        assertSame(msg, msg.overlayLiveBrowserState(emptyMap()))
        assertSame(msg, msg.overlayLiveBrowserState(mapOf("s-2" to base.copy(sessionId = "s-2"))))
    }

    @Test
    fun `non browser messages untouched`() {
        val msg = msgWith(MessagePart.Text(partId = "txt-0", markdown = "hi"))
        assertTrue(msg.overlayLiveBrowserState(mapOf("s-1" to MessagePart.BrowserLive(partId = "x", sessionId = "s-1"))).parts.single() is MessagePart.Text)
    }
}
```

`TurnPartsReducerBrowserTest.kt`：

```kotlin
package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ToolPartState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TurnPartsReducerBrowserTest {

    @Test
    fun `browser_open produces typed placeholder`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ToolInputStart(toolCallId = "call-1", toolName = TurnPartsReducer.TOOL_BROWSER_OPEN))
        val part = reducer.parts.single()
        assertIs<MessagePart.BrowserLive>(part)
        assertEquals(ToolPartState.INPUT_STREAMING, part.state)
        assertEquals("call-1", part.partId)
    }

    @Test
    fun `browser action tools produce no placeholder`() {
        val reducer = TurnPartsReducer()
        for (name in listOf("browser_navigate", "browser_click", "browser_type", "browser_extract", "browser_screenshot", "browser_close")) {
            reducer.apply(TurnStreamEvent.ToolInputStart(toolCallId = "c-$name", toolName = name))
        }
        assertTrue(reducer.parts.isEmpty())
    }

    @Test
    fun `placeholder fills in place and error marks OUTPUT_ERROR`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ToolInputStart(toolCallId = "call-1", toolName = TurnPartsReducer.TOOL_BROWSER_OPEN))
        val filled = MessagePart.BrowserLive(partId = "call-1", sessionId = "s-1", state = ToolPartState.INPUT_AVAILABLE, pageTitle = "A")
        reducer.apply(TurnStreamEvent.ToolOutputAvailable(toolCallId = "call-1", output = filled))
        assertEquals(filled, reducer.parts.single())

        reducer.reset()
        reducer.apply(TurnStreamEvent.ToolInputStart(toolCallId = "call-2", toolName = TurnPartsReducer.TOOL_BROWSER_OPEN))
        reducer.apply(TurnStreamEvent.ToolOutputError(toolCallId = "call-2", errorText = "boom"))
        assertEquals(ToolPartState.OUTPUT_ERROR, (reducer.parts.single() as MessagePart.BrowserLive).state)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*BrowserLiveOverlayTest*" --tests "*TurnPartsReducerBrowserTest*"`
Expected: FAIL（overlay 不存在 / reducer 无 browser 分支）

> 执行注意：`ChatMessage` 的构造参数以 `domain/chat/ChatMessage.kt` 实际定义为准（上式按 role/parts 字段推断，落地时对照修正具名参数）。

- [ ] **Step 3: TurnPartsReducer 三处改动**

```kotlin
// companion object 加常量
const val TOOL_BROWSER_OPEN = "browser_open"

// placeholderPart when 加分支（其余 6 个 browser 工具不落 else null 之外的任何处理）
TOOL_BROWSER_OPEN ->
    MessagePart.BrowserLive(partId = toolCallId, sessionId = "", state = ToolPartState.INPUT_STREAMING)

// withToolState when 加分支
is MessagePart.BrowserLive -> copy(state = state)
```

- [ ] **Step 4: 新建 `BrowserLiveOverlay.kt`**

```kotlin
package com.mamba.picme.domain.chat

/**
 * 浏览器直播卡 live 态挂载（spec §4）：会话期间的帧/动作流水更新走 BrowserLive part
 * **同 sessionId 原位覆写**（partId 不变，LazyColumn key 恒定），内存态不落 Room；
 * 持久化在会话结束时由调用方落最终卡（OUTPUT_AVAILABLE）。
 *
 * 形态与 [overlayLiveTaskState] 同构；纯函数：无 BrowserLive part / 无 live 态 → 原样返回。
 */
fun ChatMessage.overlayLiveBrowserState(live: Map<String, MessagePart.BrowserLive>): ChatMessage {
    if (live.isEmpty()) return this
    val index = parts.indexOfFirst { it is MessagePart.BrowserLive && it.sessionId in live }
    if (index < 0) return this
    val part = parts[index] as MessagePart.BrowserLive
    // partId 以流式轨占位为准（key 恒定）：live 条目 partId 约定为 ""（Task 16），
    // 归一化为占位 partId 后再比较/覆写——直接等值短路在该约定下恒失效
    val normalized = live.getValue(part.sessionId).copy(partId = part.partId)
    if (normalized == part) return this
    return copy(parts = parts.toMutableList().also { it[index] = normalized })
}
```

- [ ] **Step 5: ChatListFlattener 三处改动**

```kotlin
// toolStateOrNull 加分支
is MessagePart.BrowserLive -> state

// contentTypeOf 加分支（直播卡全状态自渲染：占位/运行/定格/错误都由卡片处理，不走通用 placeholder chip）
is MessagePart.BrowserLive -> ChatListItem.TYPE_BROWSER_LIVE

// companion 加常量
const val TYPE_BROWSER_LIVE = "browser_live"
```

另在 `isPersistedStreamingOutput`（:155-166）加 browser 双显跳过（产物行在场才跳）：

```kotlin
// browser 分支提前于顶层 OUTPUT_AVAILABLE 守卫判定：终态（OUTPUT_AVAILABLE/OUTPUT_ERROR）且产物行在场即跳
if (this is MessagePart.BrowserLive) {
    val terminal = state == ToolPartState.OUTPUT_AVAILABLE || state == ToolPartState.OUTPUT_ERROR
    return terminal && sessionId in persistedBrowserSessionIds
}
```

> 跳过口径与 chart/html 的差异：browser 的 **OUTPUT_ERROR 失败定格卡落库**（Task 16 Step 3 `emitBrowserCardMessage` 持久化终态），而 chart/html 的 OUTPUT_ERROR 是瞬态轨不落库——若沿用顶层「仅 OUTPUT_AVAILABLE 跳」守卫，流式错误卡会与持久化错误行双显。故 browser 分支语义 = `sessionId in persistedBrowserSessionIds && state 为终态`；sessionId 即产物行锚（content 列存整颗 JSON，不走负载等值匹配）。

> 执行注意：`flattenChatItems` 签名需仿 `persistedChartPayloads`/`persistedHtmlPayloads` 增加 `persistedBrowserSessionIds: Set<String>` 参数；androidApp 侧调用点（ChatViewModel displayMessages/ChatScreen 组装处）同步传 `_messages` 中 `type="tool_browser"` 行的 sessionId 集合。改签名属 shared+androidApp 双侧同步点，落地时全局搜 `flattenChatItems` 调用点一并更新。

- [ ] **Step 6: MessagePartsConverter 加 Room 映射**

`MessagePartsConverter.kt` type→part 映射表（:43-91，`"tool_html"` 分支 :68 后）加：

```kotlin
"tool_browser" -> runCatching {
    listOf(json.decodeFromString<MessagePart.BrowserLive>(content))
}.getOrElse { listOf(fallbackText(messageId, content)) }
```

> 执行注意：converter 内的 Json 实例与 `fallbackText` 形态以该文件现有代码为准（`"tool_html"` 分支用的是 `parseHtmlCardMeta` 等私有函数——browser 分支 content 列直接存 BrowserLive 整颗 JSON，meta 列留空）。

- [ ] **Step 7: ChatModelInput 回灌分支**

读 `domain/chat/ChatModelInput.kt` 的 tool part 回灌投影（tool-html/tool-task 分支形态），为 `MessagePart.BrowserLive` 加投影：

```kotlin
is MessagePart.BrowserLive -> ModelInputItem.ToolExchange(
    toolName = "browser_session",
    argsJson = "{}",
    resultText = part.resultSummary
        ?: part.errorReason?.let { "浏览器会话失败：$it" }
        ?: "浏览器会话（${part.pageTitle.ifBlank { part.currentUrl }}），共 ${part.actionCount} 个动作",
)
```

> 执行注意：`ModelInputItem` 的真实子类型名/字段以 `ChatModelInput.kt` 现状为准（上式为语义目标：tool-call/tool-result 配对、结果只含文本摘要、帧不回灌）；同步在 `ChatModelInputTest.kt` 加一条用例（BrowserLive → 单条 tool exchange、文本含 resultSummary、不含 frameJpegBase64 任何片段）。

- [ ] **Step 8: 跑全部 shared 测试 + assemble + commit**

```bash
JITPACK=true ./gradlew :shared:jvmTest && JITPACK=true ./gradlew :shared:assemble
```

Expected: 全绿（含 :shared:assemble 的 iOS metadata 编译——commonMain 纯度守卫零泄漏）

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/
git commit -m "feat(shared): tool_browser 占位管线 + live overlay + 拍平/持久化/回灌映射"
```

---

## Phase D：androidApp（transport + VM live 态 + 直播卡 UI + 五语）

背景事实：App→picme-server 业务 HTTP 先例 = `data/remote/picme/PoLangAuthClient.kt:12-140`（OkHttp + X-App-Token/X-Device-Id 头，`DEFAULT_BASE_URL = "https://api.polang.net"` :138）；capability 注册点 = `PoLangApplication.kt:752-792`；卡片工具排除名单 = `ChatViewModel.kt:1824-1827`；emit/feed 先例 = `onRenderHtml`（:2726-2745）+ `emitHtmlCardMessage`（:2632-2655）+ `feedToolOutput/feedToolError`（:1117-1140）；live 态 combine 先例 = `displayMessages`（:1165-1176，`sample(500)` 节流 :1158-1160）；拍平消费 = `ChatScreen.kt:281-283` + when 分发 :644-792；全屏预览 = `ChatImagePreviewOverlay`（:2757-2883）；位图解码先例 = `ChartSvgImage.kt:39-68`；轮询先例 = `CameraScreen.kt:1326-1335`（LaunchedEffect + while(isActive) + delay）；base URL SSOT 与 token 来源以 PoLangAuthClient 调用方为准。

### Task 14: BrowserSessionClient（OkHttp transport 实现）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/data/remote/picme/BrowserSessionClient.kt`

- [ ] **Step 1: 读 PoLangAuthClient 确认 token/deviceId 来源与 OkHttp 用法**（执行动作，不改代码）

打开 `data/remote/picme/PoLangAuthClient.kt:12-140` 与它的一个调用方（如 `AppContainer.kt:366-367` 及 ChatViewModel 中 claude-chat 相关调用），确认：①X-App-Token 从哪个仓库/字段读取；②OkHttpClient 实例怎么构造/共享；③base URL 常量。后续步骤按同一来源接线。

- [ ] **Step 2: 实现 BrowserSessionClient**

```kotlin
package com.mamba.picme.data.remote.picme

import com.mamba.picme.agent.core.capability.BrowserTransport
import com.mamba.picme.domain.browser.BrowserActionRequest
import com.mamba.picme.domain.browser.BrowserActionResult
import com.mamba.picme.domain.browser.BrowserFrameResult
import com.mamba.picme.domain.browser.BrowserUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * picme-server /v1/browser/* 客户端（shared [BrowserTransport] 的 Android 实现）。
 * 域名结果由 JSON status 承载（原样解析）；HTTP 非 2xx / IO 异常 → [BrowserUnavailableException]。
 * token 经 [tokenProvider] 调用时读取（账号登出/未登录 = null → 不可用降级）。
 */
class BrowserSessionClient(
    private val tokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?,
    private val baseUrl: String = PoLangAuthClient.DEFAULT_BASE_URL,
) : BrowserTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS) // 略大于网关 30s
        .build()

    override suspend fun open(url: String, wantFrame: Boolean): BrowserActionResult =
        post("/v1/browser/open", JSONObject().put("url", url).put("wantFrame", wantFrame).toString())

    override suspend fun action(request: BrowserActionRequest): BrowserActionResult =
        post("/v1/browser/action", json.encodeToString(BrowserActionRequest.serializer(), request))

    override suspend fun frame(sessionId: String): BrowserFrameResult = withContext(Dispatchers.IO) {
        val request = authedBuilder("$baseUrl/v1/browser/frame?sessionId=$sessionId").get().build()
        val body = execute(request)
        json.decodeFromString(BrowserFrameResult.serializer(), body)
    }

    override suspend fun close(sessionId: String): BrowserActionResult =
        post("/v1/browser/close", JSONObject().put("sessionId", sessionId).toString())

    private suspend fun post(path: String, bodyJson: String): BrowserActionResult = withContext(Dispatchers.IO) {
        val request = authedBuilder("$baseUrl$path")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val body = execute(request)
        json.decodeFromString(BrowserActionResult.serializer(), body)
    }

    private fun authedBuilder(url: String): Request.Builder {
        val token = tokenProvider() ?: throw BrowserUnavailableException("not logged in")
        return Request.Builder()
            .url(url)
            .header("X-App-Token", token)
            .apply { deviceIdProvider()?.let { header("X-Device-Id", it) } }
    }

    private fun execute(request: Request): String {
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 429) {
                    // 网关 429 = quota_exceeded / rate_limit：包装成结构体让能力层走配额降级文案
                    return """{"status":"quota_exceeded","reason":"$body"}"""
                }
                if (!resp.isSuccessful) throw BrowserUnavailableException("HTTP ${resp.code}")
                return body
            }
        } catch (e: BrowserUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw BrowserUnavailableException(e.message ?: "network error", e)
        }
    }
}
```

> 执行注意：`DEFAULT_BASE_URL` 是否带尾随斜杠以 PoLangAuthClient 现状为准（:138），拼接 `$baseUrl/v1/...` 时对齐（若常量已带 `/` 结尾则去掉路径前导 `/`）。

- [ ] **Step 3: 编译 + commit**

Run: `./gradlew :androidApp:compileDebugKotlin`（或仓库现行编译任务名）
Expected: BUILD SUCCESSFUL

```bash
git add androidApp/src/main/java/com/mamba/picme/data/remote/picme/BrowserSessionClient.kt
git commit -m "feat(app): BrowserSessionClient——OkHttp transport（配额/不可用结构化降级）"
```

### Task 15: capability 注册 + token 接线

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt`（`initializeCapabilities`，:752-792 区域）

- [ ] **Step 1: 构造 transport 并注册**

在 `initializeCapabilities()` 内现有 `orchestrator.registerCapability(...)` 序列中追加（token/deviceId provider 的来源与 Step 14-1 确认的一致——若现有 claude-chat 链路在 ViewModel 层持 token 而非 Application 层可得，则把 provider 实现为「读账号 DataStore 的挂起安全快照」，与 PoLangAuthClient 调用方同源）：

```kotlin
val browserTransport = BrowserSessionClient(
    tokenProvider = { /* 与 PoLangAuthClient 调用方同源的 token 读取 */ },
    deviceIdProvider = { /* 同上 deviceId */ },
)
orchestrator.registerCapability(BrowserSessionCapability(browserTransport))
```

> 执行注意：provider 必须同步返回（transport 在 IO 线程调用），若 token 存 DataStore（挂起读），用「内存缓存 + DataStore Flow 预热」模式（找现有先例，如 claude-chat 可用性检查的 token 获取方式）；不要把 runBlocking 放 provider 里。

- [ ] **Step 2: 编译 + commit**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt
git commit -m "feat(app): 注册 BrowserSessionCapability（组合根注入 OkHttp transport）"
```

### Task 16: ChatViewModel live 态收口

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/usecase/AiAgentUseCase.kt`（Step 0）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/common/chat/AgentChatComponents.kt`（Step 0）

- [ ] **Step 0: 补齐 AgentCommand 穷尽 when（Task 10 新增 7 子类的编译红收口）**

Task 10 在 shared 侧新增 7 个 `AgentCommand.Browser*` sealed 子类后，androidApp 两处无 else 的穷尽 when 编译失败（`:androidApp:compileDebugKotlin` 红），本 Step 显式认领：

1. `AiAgentUseCase.kt:178` 区域 `mapAgentCommandToLegacy`：7 个 Browser* 命令的 legacy 映射——browser_open 需映射出直播卡占位语义（对齐 Step 5 的卡片工具排除名单与 Task 13 占位管线），其余动作命令映射为不产 legacy UI 的形态（live 态由 delegate/overlay 通道承担）；按函数内既有命令的映射先例选最小语义。
2. `AgentChatComponents.kt:252/308` 区域 `getAgentCommandDisplayName`：7 个命令的显示名——受 [I18N] 红线约束，五语字符串 key 与 Task 17 Step 1 的 strings 新增共用一批（坐标对齐，不重复定义）；若既有命令显示名走 `chat_command_*` key 先例则沿用该命名族。

> 来源：Task 12 审查发现（这两处 when 不在任何 Task 文件清单内，红窗会从 Task 10 拖到 Task 20）。本 Step 完成后 `:androidApp:compileDebugKotlin` 必须恢复可编译（Task 15 的 PoLangApplication 注册缺失除外——若 Step 0 先于 Task 15 执行，capability 未注册不阻塞编译）。

- [ ] **Step 1: VM 实现 BrowserSessionDelegate + live 态 StateFlow**

```kotlin
// 字段区（仿 _engineerTasks 节流组合先例 :1158-1160）
private val _browserLiveSessions = MutableStateFlow<Map<String, MessagePart.BrowserLive>>(emptyMap())

// 实现 BrowserSessionDelegate（VM 声明处加接口，init 时
// BrowserSessionCapability 单例 setDelegate(this)，onCleared 时 setDelegate(null)）
override fun onBrowserSessionStarted(sessionId: String, url: String, title: String, frameJpegBase64: String?) {
    val entry = MessagePart.BrowserLive(
        partId = "", // 以流式占位为准（overlay 保留占位 partId）
        sessionId = sessionId,
        state = ToolPartState.INPUT_AVAILABLE,
        currentUrl = url,
        pageTitle = title,
        frameJpegBase64 = frameJpegBase64,
        actions = listOf(BrowserActionEntry(description = formatBrowserAction("open", null, url), ok = true)),
        actionCount = 1,
    )
    _browserLiveSessions.value = _browserLiveSessions.value + (sessionId to entry)
    // browser_open 占位原位填充（拿到 sessionId）
    feedToolOutput(TurnPartsReducer.TOOL_BROWSER_OPEN) { toolCallId ->
        entry.copy(partId = toolCallId)
    }
}

override fun onBrowserSessionAction(sessionId: String, action: String, selector: String?, text: String?, url: String, title: String, frameJpegBase64: String?) {
    val current = _browserLiveSessions.value[sessionId] ?: return
    val updated = current.copy(
        currentUrl = url.ifBlank { current.currentUrl },
        pageTitle = title.ifBlank { current.pageTitle },
        frameJpegBase64 = frameJpegBase64 ?: current.frameJpegBase64,
        actions = (current.actions + BrowserActionEntry(description = formatBrowserAction(action, selector, text ?: url))).takeLast(3),
        actionCount = current.actionCount + 1,
    )
    _browserLiveSessions.value = _browserLiveSessions.value + (sessionId to updated)
}

override fun onBrowserSessionFrame(sessionId: String, url: String, title: String, frameJpegBase64: String) {
    val current = _browserLiveSessions.value[sessionId] ?: return
    _browserLiveSessions.value = _browserLiveSessions.value +
        (sessionId to current.copy(currentUrl = url.ifBlank { current.currentUrl }, pageTitle = title.ifBlank { current.pageTitle }, frameJpegBase64 = frameJpegBase64))
}

override fun onBrowserSessionFailed(sessionId: String, reason: String) {
    val current = _browserLiveSessions.value[sessionId] ?: return
    _browserLiveSessions.value = _browserLiveSessions.value +
        (sessionId to current.copy(state = ToolPartState.OUTPUT_ERROR, errorReason = reason))
    feedToolError(TurnPartsReducer.TOOL_BROWSER_OPEN, reason)
}

override fun onBrowserSessionClosed(sessionId: String, finalFrameJpegBase64: String?, actionCount: Int) {
    val current = _browserLiveSessions.value[sessionId] ?: return
    val final = current.copy(
        state = if (current.state == ToolPartState.OUTPUT_ERROR) ToolPartState.OUTPUT_ERROR else ToolPartState.OUTPUT_AVAILABLE,
        frameJpegBase64 = finalFrameJpegBase64 ?: current.frameJpegBase64,
        actionCount = actionCount,
        resultSummary = if (current.errorReason == null) getString(R.string.browser_live_done_summary, actionCount) else null,
    )
    _browserLiveSessions.value = _browserLiveSessions.value + (sessionId to final)
    emitBrowserCardMessage(final)
}
```

> 执行注意：①`feedToolOutput`/`feedToolError` 的精确签名以 `ChatViewModel.kt:1117-1140` 现状为准（上式 lambda 形态对照 `emitHtmlCardMessage` 的用法 :2652-2654 对齐）；②VM 拿 `BrowserSessionCapability` 单例的方式——组合根注册的是 `BrowserSessionCapability(transport)` 构造实例，VM 侧从 `CapabilityRegistry.getInstance()` 按 name 反查或经组合根静态引用暴露，落地时选与 `ChatRunScriptCapability.getInstance()` 一致的暴露形态（若采用 companion 单例，Task 10 的类需补 `getInstance(transport)` 懒单例——以第一次构造为准缓存）；③`getString` = `context.getString`（VM 持有 Application context 的先例见 `ChatImageRenderer.kt:107`）；④`formatBrowserAction` 在 Task 17 定义。

- [ ] **Step 2: displayMessages combine 接入 overlay**

`displayMessages`（:1165-1176）combine 增加 browser live 流（仿 `throttledEngineerTasks` 500ms 节流、首值直通）：

```kotlin
val throttledBrowserLive = _browserLiveSessions.sample(500L).onStart { emit(_browserLiveSessions.value) }

// combine 内对每条消息追加：
msg.overlayLiveTaskState(liveTasks).overlayLiveBrowserState(liveBrowser)
```

并把 `flattenChatItems` 调用点（Task 13 Step 5 的新签名）的 `persistedBrowserSessionIds` 参数传：`_messages` 中 `type == "tool_browser"` 行 decode 出的 BrowserLive part 的 sessionId 集合（组装位置仿 chart/html payloads 集合的现有构建点）。

- [ ] **Step 3: emitBrowserCardMessage（持久化定格卡）**

仿 `emitHtmlCardMessage`（:2632-2655）：

```kotlin
private fun emitBrowserCardMessage(final: MessagePart.BrowserLive) {
    val content = browserPartJson.encodeToString(MessagePart.BrowserLive.serializer(), final.copy(partId = "p0"))
    insertMessageWithParts(
        type = "tool_browser",
        content = content,
        // 其余标量列（sessionId/role/timestamp 等）仿 emitHtmlCardMessage 逐字段对齐
        parts = listOf(final.copy(partId = "p0")),
    )
}

private val browserPartJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
```

定格后**保留**在 `_browserLiveSessions`（不要移除）：overlay 会持续把流式占位 part 覆写为 `OUTPUT_AVAILABLE` 终态，Task 13 的双显跳过（`sessionId in persistedBrowserSessionIds && state 为终态 OUTPUT_AVAILABLE/OUTPUT_ERROR`——browser 的 OUTPUT_ERROR 落库，与 chart/html 瞬态不同）依赖这个状态才能在产物行到达后隐去流式卡；若此时移除，流式卡会回退到 reducer 喂入的 INPUT_AVAILABLE 旧态、误显示为「进行中」并恢复轮询。清理统一在 turn 结束兜底（Step 5）做：

```kotlin
// 终态保留在 map；turn 结束兜底统一清理（见 Step 5）
```

> 执行注意：`insertMessageWithParts` 的精确签名以 `data/local/ChatMessageParts.kt:50-51` 现状为准。

- [ ] **Step 4: watch 模式帧轮询入口（供卡片 LaunchedEffect 调用）**

```kotlin
/** 卡片可见期间由 UI 以 ~1s 节拍驱动（spec §2.2 watch 模式）；不可见/退组合自动停止。 */
fun pollBrowserFrame(sessionId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val transport = browserTransportOrNull() ?: return@launch
        runCatching { transport.frame(sessionId) }
            .onSuccess { r ->
                if (r.status == BrowserStatus.OK && r.frameJpegBase64 != null) {
                    onBrowserSessionFrame(sessionId, r.currentUrl ?: "", r.pageTitle ?: "", r.frameJpegBase64)
                }
            }
        // 失败静默：轮询下个节拍自愈（spec §6 不穿透）
    }
}
```

> 执行注意：`browserTransportOrNull()` = 从 capability 单例取 transport（Task 15 注册的实例）；若单例不可达 transport，VM 字段缓存在 setDelegate 时一并注入。

- [ ] **Step 5: 卡片工具排除名单 + turn 结束兜底**

`ChatViewModel.kt:1824-1827` 的 `_pendingNonCardTool` 判定名单（卡片工具归 null）加入全部 7 个 `browser_*` 工具名（动作进度已由直播卡表达，不再出状态 chip）。

turn 结束兜底（streaming message DONE 的收口点，找现有 turn 完成钩子）：对 `_browserLiveSessions` 里仍 `state != OUTPUT_AVAILABLE && != OUTPUT_ERROR` 的会话，逐个 `transport.close(sessionId)` 后按 `onBrowserSessionClosed` 同路径定格落库（LLM 忘调 browser_close 时防服务器会话泄漏 + 卡片有终态）；全部处理完后清空 `_browserLiveSessions`（终态卡已落库，新 turn 的流式消息不再含旧占位 part）。

- [ ] **Step 6: 编译 + commit**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(app): ChatViewModel 直播卡 live 态收口——delegate/overlay/定格落库/帧轮询/兜底关闭"
```

### Task 17: 动作描述格式化器（纯函数 + 单测 + 五语）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/chat/BrowserActionFormatter.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/chat/BrowserActionFormatterTest.kt`
- Modify: `androidApp/src/main/res/values/strings.xml` + `values-zh-rCN` + `values-zh-rTW` + `values-es` + `values-fr`

- [ ] **Step 1: strings 五语（新增 key）**

`values/strings.xml`（EN）：

```xml
<string name="browser_action_open">Open %1$s</string>
<string name="browser_action_navigate">Go to %1$s</string>
<string name="browser_action_click">Tap %1$s</string>
<string name="browser_action_type">Type “%1$s”</string>
<string name="browser_action_extract">Read page content</string>
<string name="browser_action_screenshot">Take screenshot</string>
<string name="browser_live_running">Browsing…</string>
<string name="browser_live_done_summary">Browser session finished, %1$d actions</string>
<string name="browser_live_failed">Browser session failed</string>
<string name="browser_live_view_full">Tap to view full screen</string>
<string name="browser_live_title_default">Web page</string>
```

`values-zh-rCN/strings.xml`：

```xml
<string name="browser_action_open">打开 %1$s</string>
<string name="browser_action_navigate">跳转到 %1$s</string>
<string name="browser_action_click">点击 %1$s</string>
<string name="browser_action_type">输入「%1$s」</string>
<string name="browser_action_extract">读取页面内容</string>
<string name="browser_action_screenshot">截取画面</string>
<string name="browser_live_running">正在浏览…</string>
<string name="browser_live_done_summary">浏览器会话已完成，共 %1$d 步操作</string>
<string name="browser_live_failed">浏览器会话失败</string>
<string name="browser_live_view_full">点按全屏查看</string>
<string name="browser_live_title_default">网页</string>
```

`values-zh-rTW/strings.xml`：

```xml
<string name="browser_action_open">開啟 %1$s</string>
<string name="browser_action_navigate">前往 %1$s</string>
<string name="browser_action_click">點擊 %1$s</string>
<string name="browser_action_type">輸入「%1$s」</string>
<string name="browser_action_extract">讀取頁面內容</string>
<string name="browser_action_screenshot">擷取畫面</string>
<string name="browser_live_running">正在瀏覽…</string>
<string name="browser_live_done_summary">瀏覽器工作階段已完成，共 %1$d 步操作</string>
<string name="browser_live_failed">瀏覽器工作階段失敗</string>
<string name="browser_live_view_full">點按全螢幕檢視</string>
<string name="browser_live_title_default">網頁</string>
```

`values-es/strings.xml`：

```xml
<string name="browser_action_open">Abrir %1$s</string>
<string name="browser_action_navigate">Ir a %1$s</string>
<string name="browser_action_click">Tocar %1$s</string>
<string name="browser_action_type">Escribir «%1$s»</string>
<string name="browser_action_extract">Leer contenido de la página</string>
<string name="browser_action_screenshot">Capturar pantalla</string>
<string name="browser_live_running">Navegando…</string>
<string name="browser_live_done_summary">Sesión de navegador finalizada, %1$d acciones</string>
<string name="browser_live_failed">La sesión de navegador falló</string>
<string name="browser_live_view_full">Toca para ver a pantalla completa</string>
<string name="browser_live_title_default">Página web</string>
```

`values-fr/strings.xml`：

```xml
<string name="browser_action_open">Ouvrir %1$s</string>
<string name="browser_action_navigate">Aller à %1$s</string>
<string name="browser_action_click">Appuyer sur %1$s</string>
<string name="browser_action_type">Saisir « %1$s »</string>
<string name="browser_action_extract">Lire le contenu de la page</string>
<string name="browser_action_screenshot">Prendre une capture</string>
<string name="browser_live_running">Navigation…</string>
<string name="browser_live_done_summary">Session de navigation terminée, %1$d actions</string>
<string name="browser_live_failed">Échec de la session de navigation</string>
<string name="browser_live_view_full">Appuyer pour voir en plein écran</string>
<string name="browser_live_title_default">Page web</string>
```

- [ ] **Step 2: 写失败测试**

```kotlin
package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserActionFormatterTest {

    @Test
    fun `known actions map to their string keys`() {
        assertEquals(BrowserActionSpec(R.string.browser_action_open, "https://a.com"), browserActionSpec("open", null, "https://a.com"))
        assertEquals(BrowserActionSpec(R.string.browser_action_navigate, "https://b.com"), browserActionSpec("navigate", null, "https://b.com"))
        assertEquals(BrowserActionSpec(R.string.browser_action_click, "button.x"), browserActionSpec("click", "button.x", null))
        assertEquals(BrowserActionSpec(R.string.browser_action_type, "hello"), browserActionSpec("type", "input", "hello"))
        assertEquals(BrowserActionSpec(R.string.browser_action_extract, null), browserActionSpec("extract", null, null))
        assertEquals(BrowserActionSpec(R.string.browser_action_screenshot, null), browserActionSpec("screenshot", null, null))
    }

    @Test
    fun `type arg truncates long text`() {
        val spec = browserActionSpec("type", "input", "x".repeat(100))
        assertEquals(24, spec.arg!!.length)
    }

    @Test
    fun `unknown action falls back to navigate-like display`() {
        assertEquals(BrowserActionSpec(R.string.browser_action_navigate, null), browserActionSpec("hover", null, null))
    }
}
```

- [ ] **Step 3: 实现 `BrowserActionFormatter.kt`**

```kotlin
package com.mamba.picme.features.chat

import android.content.Context
import androidx.annotation.StringRes

/** 动作流水条目的资源规格（纯 Kotlin 可测；Context 格式化收口在 [formatBrowserAction]）。 */
data class BrowserActionSpec(
    @StringRes val templateRes: Int,
    val arg: String?,
)

/** browser_* 动作 → 文案规格；arg 截断 24 字符防流水行过长。 */
fun browserActionSpec(action: String, selector: String?, payload: String?): BrowserActionSpec {
    fun cut(s: String?): String? = s?.let { if (it.length > 24) it.take(21) + "..." else it }
    return when (action) {
        "open" -> BrowserActionSpec(R.string.browser_action_open, cut(payload))
        "navigate" -> BrowserActionSpec(R.string.browser_action_navigate, cut(payload))
        "click" -> BrowserActionSpec(R.string.browser_action_click, cut(selector))
        "type" -> BrowserActionSpec(R.string.browser_action_type, cut(payload))
        "extract" -> BrowserActionSpec(R.string.browser_action_extract, null)
        "screenshot" -> BrowserActionSpec(R.string.browser_action_screenshot, null)
        else -> BrowserActionSpec(R.string.browser_action_navigate, null)
    }
}

/** ChatViewModel 侧入口：本地化动作流水描述。 */
fun formatBrowserAction(context: Context, action: String, selector: String?, payload: String?): String {
    val spec = browserActionSpec(action, selector, payload)
    return if (spec.arg != null) context.getString(spec.templateRes, spec.arg) else context.getString(spec.templateRes)
}
```

> 执行注意：Task 16 里的 `formatBrowserAction(...)` 调用改为 `formatBrowserAction(context, ...)` 形态（本函数为准）。

- [ ] **Step 4: 跑测试 + commit**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*BrowserActionFormatterTest*"`
Expected: PASS

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/BrowserActionFormatter.kt androidApp/src/test/java/com/mamba/picme/features/chat/BrowserActionFormatterTest.kt androidApp/src/main/res/values*/strings.xml
git commit -m "feat(app): 动作流水格式化器 + browser 卡五语文案"
```

### Task 18: BrowserLiveCard Composable + 全屏帧预览

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/chat/components/BrowserLiveCard.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: 实现 BrowserLiveCard**

```kotlin
package com.mamba.picme.features.chat.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ToolPartState
import com.mamba.picme.features.chat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 云端浏览器直播卡（spec §4，INLINE 形态）：
 * 最新帧大图 + 页面标题/URL + 最近 3 步动作流水；会话中 1s 节拍轮询帧（watch 模式），
 * 定格/失败渲染终态。点按帧进全屏预览。
 */
@Composable
fun BrowserLiveCard(
    part: MessagePart.BrowserLive,
    onPollFrame: (String) -> Unit,
    onOpenFullPreview: (String) -> Unit, // 传 base64 帧
    modifier: Modifier = Modifier,
) {
    // watch 模式：仅会话进行中轮询；离开组合（划出视口）自动取消
    val running = part.state != ToolPartState.OUTPUT_AVAILABLE && part.state != ToolPartState.OUTPUT_ERROR
    LaunchedEffect(part.sessionId, running) {
        if (!running) return@LaunchedEffect
        while (isActive) {
            onPollFrame(part.sessionId)
            delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 头部：标题 + URL + 状态行
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = part.pageTitle.ifBlank { stringResource(R.string.browser_live_title_default) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (part.currentUrl.isNotBlank()) {
                    Text(
                        text = part.currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = when (part.state) {
                    ToolPartState.OUTPUT_ERROR -> stringResource(R.string.browser_live_failed)
                    ToolPartState.OUTPUT_AVAILABLE -> stringResource(R.string.browser_live_done_summary, part.actionCount)
                    else -> stringResource(R.string.browser_live_running)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (part.state == ToolPartState.OUTPUT_ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 帧区：16:9，base64 后台解码（先例 ChartSvgImage.kt:39-68）
        val frame = part.frameJpegBase64
        if (frame != null) {
            val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, frame) {
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        val bytes = Base64.decode(frame, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onOpenFullPreview(frame) },
                contentAlignment = Alignment.Center,
            ) {
                val current = bitmap
                if (current != null) {
                    Image(
                        bitmap = current,
                        contentDescription = stringResource(R.string.browser_live_view_full),
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        // 动作流水（最近 3 步，新在尾）
        if (part.actions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                part.actions.forEach { entry ->
                    Text(
                        text = "· " + entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 错误原因
        part.errorReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

> 执行注意：①import 的 R 包名以 androidApp 实际包名为准（`com.mamba.picme.R`）；②卡片间距/圆角若仓库有 design token（`core/designsystem/` DesignTokens），按 token 替换硬编码 dp（先例 EngineerTaskCard 的间距来源）；③`R.string.browser_live_*` 在 Task 17 已建。

- [ ] **Step 2: ChatScreen 分发分支 + 全屏预览**

`ChatScreen.kt` when 分发（:644-792，`TYPE_HTML_CARD` 分支 :665-689 后）加：

```kotlin
ChatListItem.TYPE_BROWSER_LIVE -> {
    val browserPart = item.part as? MessagePart.BrowserLive ?: return@itemsIndexed
    BrowserLiveCard(
        part = browserPart,
        onPollFrame = { sessionId -> viewModel.pollBrowserFrame(sessionId) },
        onOpenFullPreview = { frame -> browserFramePreview = frame },
        modifier = Modifier.padding(horizontal = 16.dp), // 与其他卡片同槽位间距，以 HtmlCard 分支现状为准
    )
}
```

预览状态（:312-319 区域）加 `var browserFramePreview by remember { mutableStateOf<String?>(null) }`，并纳入 `anyPreviewOpen`（:327-328）门控。

全屏 overlay（挂载点 :845-890 区域，形态仿 `ChatImagePreviewOverlay` :2757-2883 的单页版——base64 解码 + 双指缩放 + 关闭按钮 + BackHandler）：

```kotlin
browserFramePreview?.let { frame ->
    BrowserFramePreviewOverlay(frame = frame, onClose = { browserFramePreview = null })
}
```

`BrowserFramePreviewOverlay` 放 `features/chat/components/BrowserLiveCard.kt` 同文件（单帧版预览：produceState 解码 + `detectTransformGestures` 1x~5x 缩放 + 顶部关闭行，代码直接移植 `ChatImagePreviewOverlay` 的单页形态并改数据源为 base64——实现时照该 overlay 现有手势代码逐段对齐，不引入新交互）。

- [ ] **Step 3: 编译 + 截图自查 + commit**

```bash
./gradlew :androidApp:assembleDebug
```

装到设备/模拟器，chat 里触发任意 browser 任务（或临时 DEBUG 入口造一张 BrowserLive 卡）截屏自查：卡帧 16:9、流水 3 行、五语无缺字。随后：

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/components/BrowserLiveCard.kt androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(app): BrowserLiveCard 直播卡 + 全屏帧预览 + ChatScreen 分发"
```

---

## Phase E：文档同步 + 端到端验证

### Task 19: 活文档与 spec 同步

**Files:**
- Modify: `docs/superpowers/specs/2026-09-28-chat-type-taxonomy-design.md`（8 值 → 9 值登记，按其 §4.1 新增登记指引）
- Modify: `docs/superpowers/specs/2026-10-06-browser-vnc-live-card-design.md`（§2.2 watch 模式措辞修正：一次性 `page.screenshot` 取代 `Page.startScreencast`，1-2fps 轮询下体验等价、无 ack 状态机；>5fps 需求再升级 screencast）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（新增 tool_browser 卡条目：协议/parts 形态/回灌/渲染三要素 + 端到端例子）
- Modify: `shared/AGENTS.md`（§1 文件计数 + §2 commonMain 组件表加 domain/browser、BrowserSessionCapability；§2 顶部注记新增 browser 工具线）
- Modify: `server/AGENTS.md`（§3 路由清单加 4 条 /v1/browser/*；§1 核心职责加 browser 网关一行；版本 0.9.4 → 0.9.5 + 最后更新日期）
- Modify: `androidApp/AGENTS.md`（§2.1 Chat 行补直播卡句；§3.2 集成点表加 browser 直播卡一行）
- Modify: `docs/08-UI-SPECS/screens/chat.yaml`（若该文件有卡片登记段，按既有格式登记 browser_live 卡，供 iOS 跟随消费）

- [ ] **Step 1: 逐文件按上表同步**（每处改动对齐该文件既有措辞密度，不回填模块级细节到顶层 AGENTS.md）

- [ ] **Step 2: 跑文档门禁**

Run: `python3 scripts/check_doc_sync.py`
Expected: 全绿（活文档引用单向 + reviews 白名单无新增）

- [ ] **Step 3: Commit**

```bash
git add docs/ shared/AGENTS.md server/AGENTS.md androidApp/AGENTS.md
git commit -m "docs: browser 直播卡交付同步——taxonomy 9 值/卡片目录/模块 AGENTS/路由清单"
```

### Task 20: 端到端闭环（真机）

**前置**：bridge 已部署 xuxing（`infra/browser-bridge/deploy.sh` + `.env` 配好 BRIDGE_TOKEN/CHROME_PATH）；picme-server `/etc/picme/server.env` 配好 `BROWSER_BRIDGE_URL=http://<xuxing-tailscale-ip>:8788` + `BROWSER_BRIDGE_TOKEN=<同 bridge>` 并蓝绿切换（`server/deploy.sh` + `deploy-switch.sh`）。

- [ ] **Step 1: bridge 部署 + 冒烟**

```bash
cd infra/browser-bridge && ./deploy.sh
# xuxing 上：
ssh xuxing 'curl -s -H "X-Bridge-Token: $(grep ^BRIDGE_TOKEN /opt/browser-bridge/.env | cut -d= -f2)" http://127.0.0.1:8788/healthz'
```

Expected: `{"ok":true,"sessions":0}`

- [ ] **Step 2: 网关冒烟（开发机 curl，token 取测试账号）**

```bash
curl -s -X POST https://api.polang.net/v1/browser/open \
  -H "X-App-Token: <test-token>" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","wantFrame":true}' | head -c 200
```

Expected: `{"status":"ok","sessionId":"...","frameJpegBase64":"..."}`

负例三连：无 token → 401；同用户未 close 重复 open → `pool_exhausted`；`BROWSER_BRIDGE_URL` 置空重启 → 503 `browser_unavailable`。

- [ ] **Step 3: 真机 dev-loop 闭环**

```bash
./gradlew :androidApp:assembleDebug && adb install -r androidApp/build/outputs/apk/debug/*.apk
```

App 内 chat 发「帮我查一下 example.com 的标题是什么」，逐项验收（spec §4 状态机）：

- [ ] 直播卡出现（占位 → 帧逐步更新，页面标题/URL 正确）
- [ ] 动作流水逐行追加（至多 3 行）
- [ ] 会话结束卡片定格（最终帧 + 「共 N 步操作」）
- [ ] 冷启动重进会话，定格卡从 Room 恢复（type=tool_browser 行 + parts 双读）
- [ ] 点按帧进全屏预览、双指缩放、返回关闭
- [ ] 五语各切换一次：流水/状态文案无硬编码缺译
- [ ] 降级路径：xuxing bridge 停掉后发同类请求 → 无直播卡、Agent 纯文本致歉回答、不崩溃不白屏
- [ ] LLM 上下文检查（`polang_llm_log.db`）：browser 工具结果无 base64 帧片段（token 保护生效）

- [ ] **Step 4: 验收通过后合入**（遵循 finishing-a-development-branch skill：worktree 分支合 main / PR，按用户选择）

---

## 依赖与并行说明

- A（Task 1-3）与 B（Task 4-8）互不依赖，可并行；C（Task 9-13）依赖 B 的协议口径但代码零耦合，也可并行；D（Task 14-18）依赖 C 的 shared 类型（BrowserTransport/MessagePart.BrowserLive/overlay/拍平签名）必须先合；E 最后。
- Task 13 Step 5 改了 `flattenChatItems` 签名 → Task 16 Step 2 有对应调用点更新，顺序敏感。
- 每阶段验证门槛见文首「分阶段验证门槛」；任何门槛不通过不进入下一阶段。
