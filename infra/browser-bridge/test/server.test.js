'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { createServer } = require('../src/server');
const { SessionManager } = require('../src/sessions');

const CFG = {
  token: 'test-token',
  maxSessions: 8, idleTimeoutMs: 60000, hardCapMs: 600000, navTimeoutMs: 5000,
  viewport: { width: 1280, height: 720 }, frameQuality: 60, maxExtractChars: 5, maxElements: 40,
  screencastQuality: 60, screencastMaxWidth: 1280, screencastMaxHeight: 720, screencastEveryNthFrame: 2,
};

function fakePage() {
  return {
    _url: 'https://example.com/',
    url() { return this._url; },
    title: async () => 'Example',
    goto: async () => {},
    setDefaultTimeout: () => {},
    on: () => {},
    screenshot: async () => Buffer.from('jpeg-bytes'),
    context: () => ({ newCDPSession: async () => ({ on: () => {}, send: async () => {}, detach: async () => {} }) }),
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

async function withServer(fn) {
  const page = fakePage();
  const manager = new SessionManager({ ...CFG }, fakeBrowserProvider(page));
  const server = createServer(manager, { ...CFG });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn({ manager, base, page });
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

const AUTH = { 'x-bridge-token': 'test-token' };

test('GET /session/{id}/status returns liveness for live session', async () => {
  await withServer(async ({ manager, base }) => {
    const session = await manager.open(null);
    const res = await fetch(`${base}/session/${session.id}/status`, { headers: AUTH });
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.status, 'ok');
    assert.strictEqual(body.sessionId, session.id);
    assert.strictEqual(body.currentUrl, 'https://example.com/');
    assert.strictEqual(typeof body.lastActivity, 'number');
    await manager.close(session.id);
  });
});

test('GET /session/{id}/status returns session_expired for unknown/dead session', async () => {
  await withServer(async ({ base }) => {
    const res = await fetch(`${base}/session/00000000-0000-0000-0000-000000000000/status`, { headers: AUTH });
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.status, 'session_expired');
    assert.strictEqual(body.errorCode, 'session_expired');
  });
});

test('GET /session/{id}/status rejects bad token', async () => {
  await withServer(async ({ manager, base }) => {
    const session = await manager.open(null);
    const res = await fetch(`${base}/session/${session.id}/status`, { headers: { 'x-bridge-token': 'wrong' } });
    assert.strictEqual(res.status, 401);
    await manager.close(session.id);
  });
});
