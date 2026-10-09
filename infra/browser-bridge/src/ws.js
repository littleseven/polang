'use strict';

const { WebSocketServer } = require('ws');
const crypto = require('node:crypto');

/**
 * WebSocket 推流端点：App 连接后启动 CDP screencast，帧以 binary 推送。
 *
 * 协议：
 *   Server → Client  binary: JPEG 帧字节
 *   Server → Client  text:    {"type":"frame_meta",...} / {"type":"action_result",...} / {"type":"error",...}
 *   Client → Server  text:    {"type":"action","actionId":"...","action":{action:"clickAt",x,y,...}}
 *                             （action 体与 HTTP /session/{id}/action 同构，参数平铺顶层）
 *                             {"type":"watch_stop"} 停止 screencast
 *
 * 连接即启动 screencast（幂等）；最后一个听众断开时自动停止。
 *
 * 鉴权：query param token（与 HTTP X-Bridge-Token 同源 SHA-256 比较）。
 */
function setupWebSocket(server, manager, config) {
  const expectedTokenHash = crypto.createHash('sha256').update(config.token).digest();
  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname !== '/ws') return socket.destroy();

    // 时序安全 token 比较（与 HTTP 层同源）
    const providedHash = crypto.createHash('sha256').update(String(url.searchParams.get('token') || '')).digest();
    if (!config.token || !crypto.timingSafeEqual(expectedTokenHash, providedHash)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      return socket.destroy();
    }

    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit('connection', ws, req);
    });
  });

  wss.on('connection', (ws, req) => {
    const url = new URL(req.url, 'http://localhost');
    const sessionId = url.searchParams.get('sessionId');
    if (!sessionId) {
      ws.send(JSON.stringify({ type: 'error', code: 'bad_request', message: 'sessionId required' }));
      return ws.close();
    }

    let session;
    try {
      session = manager.get(sessionId);
    } catch {
      ws.send(JSON.stringify({ type: 'error', code: 'session_expired', message: 'session not found' }));
      return ws.close();
    }

    // 注册帧 listener → 推送到 WS
    const removeListener = manager.addScreencastListener(sessionId, (frameB64, meta) => {
      if (ws.readyState !== 1) return; // OPEN
      try {
        // binary JPEG
        ws.send(Buffer.from(frameB64, 'base64'));
        // metadata text
        ws.send(JSON.stringify({ type: 'frame_meta', ...meta }));
      } catch { /* WS 关闭中 */ }
    });

    // 启动 screencast（幂等）
    manager.startScreencast(sessionId).catch((err) => {
      ws.send(JSON.stringify({ type: 'error', code: 'screencast_failed', message: err.message }));
    });

    ws.on('message', async (data) => {
      let msg;
      try { msg = JSON.parse(data.toString()); } catch { return; }

      if (msg.type === 'watch_stop') {
        await manager.stopScreencast(sessionId).catch(() => {});
        return;
      }

      if (msg.type === 'action') {
        try {
          const result = await manager.act(session, msg.action);
          ws.send(JSON.stringify({
            type: 'action_result',
            actionId: msg.actionId,
            status: result.status,
            currentUrl: result.currentUrl,
            pageTitle: result.pageTitle,
            error: result.status !== 'ok' ? (result.reason || result.status) : undefined,
          }));
        } catch (err) {
          ws.send(JSON.stringify({
            type: 'action_result',
            actionId: msg.actionId,
            status: 'action_failed',
            error: err.message || 'unknown',
          }));
        }
        return;
      }
    });

    ws.on('close', async () => {
      removeListener();
      // 最后一个听众离开后停 screencast——否则无消费者仍持续抓帧直到 idle 回收
      try {
        const s = manager.get(sessionId);
        if (s.screencastListeners.size === 0) await manager.stopScreencast(sessionId);
      } catch { /* 会话可能已被回收 */ }
    });

    ws.on('error', () => { /* 忽略，close 事件会跟进 */ });
  });

  return wss;
}

module.exports = { setupWebSocket };
