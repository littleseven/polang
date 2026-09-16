# xuxingzhiyuan.cn 国内官网镜像 — 设计文档

日期：2026-09-16
状态：已评审通过（等待备案后上线 Phase 2）

## 1. 背景与目标

新购腾讯云国内主机（82.157.166.5，Ubuntu 24.04，2C/3.6G/69G，ssh 别名 `xuxing`，用户 `ubuntu`），域名 `xuxingzhiyuan.cn`（新网 DNS，已解析到本机，含 www）。

**三个用途**：
1. 产品官网 —— 与 polang.net 内容一致（单源双部署）
2. 微信小程序服务端（**本期不做**，YAGNI，等小程序开发时再立项）
3. Apple 开发者账号备案官网（企业主体）

**关键约束**：域名未备案（企业主体，未提交）。国内主机 80/443 等标准 web 端口对未备案域名会被云厂商拦截；新备案期间域名必须不可公开访问。因此采用「备案前全部备好、备案通过当天上线」策略。

## 2. 方案对比

| 方案 | 结论 |
|------|------|
| A. 单源双部署：`docs-site/` 唯一源，新脚本 rsync 到新机 nginx | ✅ 采用 |
| B. 国内 nginx 反代 HK polang.net | 否：绕道 HK 失去国内加速意义，依赖旧机 |
| C. COS+CDN 静态托管 | 否：小程序后端仍需主机，双部署体系，YAGNI |

## 3. 架构

```
polang 仓库 docs-site/（唯一源，含 sync-docs.sh 产物 docsify 文档）
        ├── scripts/deploy-docs-site.sh    → HK 43.161.201.142（现有，不动）
        └── scripts/deploy-docs-site-cn.sh → xuxing (82.157.166.5)
                nginx → /var/www/xuxingzhiyuan/docs-site
                [备案前] 安全组：22 + 8100(预览)；80/443 关闭
                [备案后] 开 80/443 + certbot HTTPS，关 8100
```

## 4. 本期改动清单（备案前完成）

1. **服务器初始化**：apt 更新、nginx、目录 `/var/www/xuxingzhiyuan/docs-site`
2. **新部署脚本** `scripts/deploy-docs-site-cn.sh`：备份远端 → rsync（`--delete` 镜像）→ **远端 localhost curl 校验**（备案前不能校验公网域名）。与现有脚本同构，差异仅 HOST/目录/校验方式。
3. **预览机制（备案期间）**：nginx 监听 8100，HTTP basic auth（随机密码）；预览地址 `http://82.157.166.5:8100/`。安全组放行 8100。备案通过后关闭。
4. **安全加固**：SSH 禁用密码登录（密钥已验证可用）；不动服务器上既有 node(127.0.0.1:26972)/chrome(127.0.0.1:9222) 进程。

## 5. 备案路线（用户在腾讯云控制台操作）

- **前提自查**：服务器须包年包月且剩余有效期 ≥3 个月（按量付费无法生成备案服务码）
- **材料**：营业执照、法定代表人身份证正反面、网站负责人身份证（可为法人）、人脸核验
- **申报**：网站类型报「企业官网」；网站名称建议含企业字号或产品名；无需前置审批
- **备案期间**：保持域名解析但不开 80/443（当前状态即正确状态）

## 6. Phase 2：备案通过当天上线（目标 10 分钟）

1. 腾讯云控制台安全组放行 80/443（并关闭 8100）
2. nginx 切换：8100 预览 server 块 → 标准 80 server 块（xuxingzhiyuan.cn + www 301 到主域）
3. certbot 签发 Let's Encrypt 证书并配 443（HTTP-01，届时 80 已可开）
4. **页脚追加备案号 + 工信部链接**（beian.miit.gov.cn）—— 与 polang.net 唯一的内容差异，模板小改
5. 全站校验（首页标记、三语、docsify）

## 7. 验收标准（本期）

- [ ] `ssh xuxing` 免密登录可用（✅ 已达成）
- [ ] `scripts/deploy-docs-site-cn.sh` 一键部署：备份 + rsync + 远端校验全绿
- [ ] `http://82.157.166.5:8100/` basic auth 后可访问，内容与 polang.net 一致（首页标记「零图片上传隐私安全」、zh/en/tw 三语、docsify 文档站）
- [ ] 服务器 SSH 密码登录已禁用（`PasswordAuthentication no` 生效）
- [ ] Phase 2 上线手册已写入本文档第 6 节（无需临时决策）

## 8. 后续（不在本期）

- 微信小程序备案、iOS App 备案（App Store 中国区上架要求）—— 同一企业主体，同一备案系统
- 小程序服务端立项（形态届时定：平移 server/ Ktor 或新起）
- www 与主域的 HTTPS/跳转细节在 Phase 2 一并处理
