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
      context.on('page', (popup) => { popup.close().catch(() => {}); });
      const page = await context.newPage();
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
      return this._result(session, { textExtract, elements, frame, actionMs: Date.now() - started });
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
    await target.click({ timeout: this.config.navTimeoutMs });
  }

  async _type(session, body) {
    const target = await this._locate(session, body);
    await target.pressSequentially(String(body.text == null ? '' : body.text), { timeout: this.config.navTimeoutMs });
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

  async _result(session, extra) {
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
