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
  screencastQuality: 60,
  screencastMaxWidth: 1280,
  screencastMaxHeight: 720,
  screencastEveryNthFrame: 2,
};
