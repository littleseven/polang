'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { SessionManager, PoolExhaustedError, SessionExpiredError } = require('../src/sessions');

const CFG = {
  maxSessions: 1, idleTimeoutMs: 60000, hardCapMs: 600000, navTimeoutMs: 5000,
  viewport: { width: 1280, height: 720 }, frameQuality: 60, maxExtractChars: 5, maxElements: 40,
  screencastQuality: 60, screencastMaxWidth: 1280, screencastMaxHeight: 720, screencastEveryNthFrame: 2,
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
    mouse: {
      click: async (x, y) => { calls.push(`mouse.click:${x},${y}`); },
      wheel: async (dx, dy) => { calls.push(`mouse.wheel:${dx},${dy}`); },
      move: async (x, y) => { calls.push(`mouse.move:${x},${y}`); },
      down: async () => { calls.push('mouse.down'); },
      up: async () => { calls.push('mouse.up'); },
    },
    keyboard: {
      type: async (t) => { calls.push(`keyboard.type:${t}`); },
    },
    context: () => ({
      newCDPSession: async () => ({
        on: () => {},
        send: async (method) => { calls.push(`cdp:${method}`); },
        detach: async () => { calls.push('cdp:detach'); },
      }),
    }),
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

test('concurrent act calls on same session execute serially in order', async () => {
  const order = [];
  const page = fakePage();
  page.evaluate = async (fn) => {
    if (typeof fn !== 'function') return '';
    order.push('extract:start');
    await new Promise((r) => setTimeout(r, 20));
    order.push('extract:end');
    return 'body';
  };
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  const p1 = mgr.act(s, { action: 'extract' });
  const p2 = mgr.act(s, { action: 'extract' });
  await Promise.all([p1, p2]);
  assert.deepStrictEqual(order, ['extract:start', 'extract:end', 'extract:start', 'extract:end']);
  await mgr.close(s.id);
});

test('click with out-of-range index rejects with stale_element code', async () => {
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(fakePage()));
  const s = await mgr.open(null);
  await assert.rejects(
    () => mgr.act(s, { action: 'click', index: 99 }),
    (e) => e.code === 'stale_element'
  );
  await mgr.close(s.id);
});

test('detached cached handle maps to structured stale_element', async () => {
  const page = fakePage();
  page.$$ = async () => [
    { click: async () => { throw new Error('Element is not attached to the DOM'); }, pressSequentially: async () => {}, evaluate: async (fn) => fn({ tagName: 'A', innerText: 'X', value: '', getAttribute: () => null }) },
  ];
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  await mgr.act(s, { action: 'extract' });
  await assert.rejects(
    () => mgr.act(s, { action: 'click', index: 0 }),
    (e) => e.code === 'stale_element'
  );
  await mgr.close(s.id);
});

// ── 接管模式坐标 action ──────────────────────────────────────────────

test('clickAt sends mouse.click with coordinates', async () => {
  const page = fakePage();
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  const r = await mgr.act(s, { action: 'clickAt', x: 100, y: 200 });
  assert.strictEqual(r.status, 'ok');
  assert.ok(page.calls.includes('mouse.click:100,200'));
  await mgr.close(s.id);
});

test('clickAt rejects non-finite coordinates', async () => {
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(fakePage()));
  const s = await mgr.open(null);
  await assert.rejects(
    () => mgr.act(s, { action: 'clickAt', x: 'abc', y: 200 }),
    (e) => e.code === 'invalid_coords'
  );
  await mgr.close(s.id);
});

test('typeText sends keyboard.type', async () => {
  const page = fakePage();
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  const r = await mgr.act(s, { action: 'typeText', text: 'hello world' });
  assert.strictEqual(r.status, 'ok');
  assert.ok(page.calls.includes('keyboard.type:hello world'));
  await mgr.close(s.id);
});

test('scroll sends mouse.wheel', async () => {
  const page = fakePage();
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  const r = await mgr.act(s, { action: 'scroll', dx: 0, dy: -300 });
  assert.strictEqual(r.status, 'ok');
  assert.ok(page.calls.includes('mouse.wheel:0,-300'));
  await mgr.close(s.id);
});

test('drag sends mouse move/down/up sequence', async () => {
  const page = fakePage();
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  const r = await mgr.act(s, { action: 'drag', fromX: 10, fromY: 20, toX: 110, toY: 220 });
  assert.strictEqual(r.status, 'ok');
  assert.ok(page.calls.includes('mouse.move:10,20'));
  assert.ok(page.calls.includes('mouse.down'));
  assert.ok(page.calls.includes('mouse.move:110,220'));
  assert.ok(page.calls.includes('mouse.up'));
  await mgr.close(s.id);
});

// ── CDP Screencast ──────────────────────────────────────────────────

test('startScreencast is idempotent and stopScreencast cleans up', async () => {
  const page = fakePage();
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  await mgr.startScreencast(s.id);
  assert.strictEqual(mgr.isScreencastActive(s.id), true);
  await mgr.startScreencast(s.id); // idempotent
  assert.strictEqual(mgr.isScreencastActive(s.id), true);
  await mgr.stopScreencast(s.id);
  assert.strictEqual(mgr.isScreencastActive(s.id), false);
  assert.ok(page.calls.includes('cdp:Page.startScreencast'));
  assert.ok(page.calls.includes('cdp:Page.stopScreencast'));
  assert.ok(page.calls.includes('cdp:detach'));
  await mgr.close(s.id);
});

test('screencast listener receives frames', async () => {
  const page = fakePage();
  let frameHandler = null;
  const origContext = page.context;
  page.context = () => ({
    newCDPSession: async () => ({
      on: (event, handler) => { if (event === 'Page.screencastFrame') frameHandler = handler; },
      send: async () => {},
      detach: async () => {},
    }),
  });
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  const frames = [];
  mgr.addScreencastListener(s.id, (b64, meta) => frames.push({ b64, meta }));
  await mgr.startScreencast(s.id);
  // Simulate a CDP screencast frame
  frameHandler({
    data: Buffer.from('fake-jpeg').toString('base64'),
    sessionId: 1,
    metadata: { seq: 42, width: 1280, height: 720 },
  });
  assert.strictEqual(frames.length, 1);
  assert.strictEqual(frames[0].b64, Buffer.from('fake-jpeg').toString('base64'));
  assert.strictEqual(frames[0].meta.seq, 42);
  await mgr.close(s.id);
});

test('close stops screencast', async () => {
  const page = fakePage();
  const mgr = new SessionManager({ ...CFG, maxSessions: 8 }, fakeBrowserProvider(page));
  const s = await mgr.open(null);
  await mgr.startScreencast(s.id);
  await mgr.close(s.id);
  assert.strictEqual(mgr.isScreencastActive(s.id), false);
});
