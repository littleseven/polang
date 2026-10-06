'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { isPublicIp, validatePublicUrl } = require('../src/ssrf');

// 按 SsrfError.code 匹配，而非耦合错误 message 文本
const rejectsWithCode = (promiseOrFn, code) =>
  assert.rejects(promiseOrFn, (e) => e instanceof Error && e.code === code, code);

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
  await rejectsWithCode(() => validatePublicUrl('file:///etc/passwd'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('http://8.8.8.8:8080/'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('https://user:pass@8.8.8.8/'), 'blocked_url');
});

test('validatePublicUrl: rejects private/内部 hostnames', async () => {
  await rejectsWithCode(() => validatePublicUrl('http://192.168.0.1/admin'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('http://127.0.0.1/'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('http://foo.localhost/'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('http://gateway.internal/'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('http://nas.lan/'), 'blocked_url');
});

// 钉住 WHATWG URL 规范化兜底行为：十六进制/十进制 IPv4、IPv4-mapped IPv6、尾点 localhost 一律拦截
test('validatePublicUrl: rejects URL-normalization bypass vectors', async () => {
  await rejectsWithCode(() => validatePublicUrl('http://0x7f000001/'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('http://2130706433/'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('http://[::ffff:127.0.0.1]/'), 'blocked_url');
  await rejectsWithCode(() => validatePublicUrl('http://localhost./'), 'blocked_url');
});

test('validatePublicUrl: rejects malformed url', async () => {
  await rejectsWithCode(() => validatePublicUrl('not a url'), 'blocked_url');
});

test('validatePublicUrl: accepts public literal IP on 443', async () => {
  const u = await validatePublicUrl('https://8.8.8.8/');
  assert.strictEqual(u.href, 'https://8.8.8.8/');
});

test('validatePublicUrl: dns failure maps to dns_failed', async () => {
  await rejectsWithCode(
    () => validatePublicUrl('https://nonexistent.invalid/', async () => { throw new Error('ENOTFOUND'); }),
    'dns_failed',
  );
});

// mock timers 确定性覆盖 5s DNS 超时路径（永不 settle 的 resolver + tick 触发超时）
test('validatePublicUrl: dns timeout maps to dns_failed', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const pending = validatePublicUrl('https://slow.example/', () => new Promise(() => {}));
  const assertion = rejectsWithCode(pending, 'dns_failed');
  t.mock.timers.tick(5000);
  await assertion;
});

test('validatePublicUrl: hostname resolving to private ip rejected', async () => {
  await rejectsWithCode(
    () => validatePublicUrl('https://evil.example/', async () => [{ address: '10.1.2.3', family: 4 }]),
    'blocked_url',
  );
});
