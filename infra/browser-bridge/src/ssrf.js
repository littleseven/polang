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
