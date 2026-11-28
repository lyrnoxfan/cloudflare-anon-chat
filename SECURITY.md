# 安全策略 · Security Policy

## 报告安全问题

请**不要**通过公开 Issue 报告安全漏洞。请通过私下渠道联系维护者（仓库 Owner），并提供：

- 受影响的版本 / 提交
- 复现步骤或 PoC
- 影响评估与可能的修复建议

我们会在确认后尽快响应与修复，并在合适时致谢。

## 支持范围

| 版本 | 支持 |
|---|---|
| `main` 最新 | ✅ |
| 已归档的历史版本 | ❌（建议升级） |

## 安全设计要点

- 匿名会话：服务端随机 token，数据库仅存 SHA-256；`HttpOnly`、生产 `Secure`、`SameSite=Strict`。
- 私人房口令：PBKDF2-SHA256 + 每房随机盐 + 常量时间比较；创建口令 ≥ 8 位；按 IP/会话限速。
- CSRF / 同源：非 GET 请求校验 `Origin` + `SameSite=Strict`。
- 下载：绑定会话/房间/消息的 HMAC 短票据，强制 `attachment` + `nosniff`，不下发裸 R2 链接。
- 上传：申报大小/类型授权 → 确认时校验实际对象 → 写入不可覆盖 key。
- 限流：Durable Object 原子固定窗口（会话 / 消息 / 上传 / 通话 / 口令尝试）。
- 响应头：CSP、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`、`Permissions-Policy`。
- Secret 管理：`APP_SECRET` / SFU 密钥 / R2 凭据只放 Worker Secrets，绝不进入前端、日志或仓库。

## 已知边界（非缺陷）

- 无端到端加密（E2EE）：服务端会处理并保存聊天记录。
- 媒体不做病毒扫描或内容真伪鉴定。
- 固定窗口限流不构成强抗女巫方案；大规模公开前建议叠加 WAF / Turnstile。
