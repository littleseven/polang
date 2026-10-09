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
    this.screencast = null;          // CDP screencast 客户端（WebSocket 推流模式）
    this.screencastListeners = new Set(); // frame 推送回调集合
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
      // 必须先 newPage 再注册：'page' 事件对 newPage() 创建的主页面同样触发，
      // 先注册会误杀主页面（real-Chrome 实证）
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
      else if (action === 'clickAt') await this._clickAt(session, body);
      else if (action === 'typeText') await this._typeText(session, body);
      else if (action === 'scroll') await this._scroll(session, body);
      else if (action === 'drag') await this._drag(session, body);
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

  /** 坐标点击（接管模式）：直接 mouse.click，不走 _locate。 */
  async _clickAt(session, body) {
    const x = Number(body.x), y = Number(body.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      throw new ActionFailedError('invalid_coords', 'clickAt 需要有限数值 x/y');
    }
    await session.page.mouse.click(x, y);
  }

  /** 全局键盘输入（接管模式）：无需定位元素。 */
  async _typeText(session, body) {
    const text = String(body.text == null ? '' : body.text);
    if (!text) return;
    await session.page.keyboard.type(text);
  }

  /** 滚轮（接管模式）：dx/dy 为像素增量。 */
  async _scroll(session, body) {
    const dx = Number(body.dx) || 0;
    const dy = Number(body.dy) || 0;
    await session.page.mouse.wheel(dx, dy);
  }

  /** 拖拽（接管模式）：from→to 连续移动。 */
  async _drag(session, body) {
    const fx = Number(body.fromX), fy = Number(body.fromY);
    const tx = Number(body.toX), ty = Number(body.toY);
    if (![fx, fy, tx, ty].every(Number.isFinite)) {
      throw new ActionFailedError('invalid_coords', 'drag 需要有限数值 fromX/fromY/toX/toY');
    }
    await session.page.mouse.move(fx, fy);
    await session.page.mouse.down();
    await session.page.mouse.move(tx, ty, { steps: 10 });
    await session.page.mouse.up();
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

  // ── CDP Screencast（WebSocket 推流模式）─────────────────────────────

  /**
   * 启动 screencast：Playwright CDP session 订阅 Page.screencastFrame，
   * 每帧推送到所有已注册 listener。幂等（重复调用直接返回）。
   */
  async startScreencast(sessionId) {
    const session = this.get(sessionId);
    if (session.screencast) return; // already running
    const cdp = await session.page.context().newCDPSession(session.page);
    session.screencast = cdp;
    cdp.on('Page.screencastFrame', (event) => {
      const frameB64 = event.data;
      session.lastGoodFrame = frameB64;
      const meta = {
        seq: event.metadata?.seq || 0,
        width: event.metadata?.width || this.config.viewport.width,
        height: event.metadata?.height || this.config.viewport.height,
        timestamp: Date.now(),
      };
      for (const listener of session.screencastListeners) {
        try { listener(frameB64, meta); } catch { /* listener 故障隔离 */ }
      }
      // ack 必须回，否则 Chromium 停止推帧
      cdp.send('Page.screencastFrameAck', { sessionId: event.sessionId }).catch(() => {});
    });
    await cdp.send('Page.startScreencast', {
      format: 'jpeg',
      quality: this.config.screencastQuality,
      maxWidth: this.config.screencastMaxWidth,
      maxHeight: this.config.screencastMaxHeight,
      everyNthFrame: this.config.screencastEveryNthFrame,
    });
  }

  /** 停止 screencast 并清理 CDP session。幂等。 */
  async stopScreencast(sessionId) {
    const session = this.sessions.get(sessionId);
    if (!session?.screencast) return;
    const cdp = session.screencast;
    session.screencast = null;
    try { await cdp.send('Page.stopScreencast'); } catch { /* already stopped */ }
    try { await cdp.detach(); } catch { /* already detached */ }
  }

  /** 注册 screencast 帧 listener，返回取消函数。 */
  addScreencastListener(sessionId, listener) {
    const session = this.get(sessionId);
    session.screencastListeners.add(listener);
    return () => { session.screencastListeners.delete(listener); };
  }

  /** 当前 screencast 是否在运行。 */
  isScreencastActive(sessionId) {
    const session = this.sessions.get(sessionId);
    return !!session?.screencast;
  }

  async captureFrame(session) {
    return this._serial(session, async () => {
      session.touch();
      return this._captureFrame(session);
    });
  }

  /** 会话快照：server.js 跨模块消费（公开方法）。 */
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
    await this.stopScreencast(id); // 先停 screencast，避免 CDP session 泄漏
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
