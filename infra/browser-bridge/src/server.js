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
