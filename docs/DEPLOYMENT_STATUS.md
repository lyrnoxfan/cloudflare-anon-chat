# 部署状态与验收记录

更新时间：2026-10-28。测试目标为 Cloudflare Worker `blue-room-test`，用户站点为 `https://chat.lyrnox.com`。本项目没有把本次代码部署到其他 Worker、网站、自定义域名或套餐。

## 当前测试部署

- 最近一次已记录的测试 Worker 部署版本：`c32e2e02-5d47-4952-a1a7-5e07fe57ebe8`（2026-10-27 的 UI 改版部署记录；本轮未部署）。
- 部署检查确认目标为 `blue-room-test`，站点入口为 `chat.lyrnox.com`；保留现有 ChatRoom/Guard Durable Objects、`blue-room-test-db` D1、`blue-room-test-media` R2 和静态资源绑定。没有重建或清空这些资源。
- 当前 R2 上传实现支持在没有 S3 access key 时使用同源 Worker 流式上传；如果之后安全配置了 S3 凭据，代码仍可走签名直传分支。当前 Worker 未配置 `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY`，所以浏览器直传 S3 模式尚未启用。

## 本地检查

本次改动后的检查结果：

- `pnpm typecheck`：通过。
- `pnpm test`：18/18 通过，包含 SFU 接口契约及 R2 同源上传、大小和失败清理用例。
- `node --check public/app.js` 与 `node --check public/call.js`：通过。
- `pnpm exec wrangler deploy --dry-run --config wrangler.test.jsonc --outdir .build/test`：通过；预演检查了测试配置的入口、静态资源及现有 D1/R2/DO 绑定。

## 线上检查（`https://chat.lyrnox.com`）

`CHAT_TEST_BASE_URL=https://chat.lyrnox.com pnpm test:remote`：4/4 通过。

1. 首页返回 HTTP 200；无 Cookie 的 `/api/session` 返回预期 HTTP 401。
2. 两个独立浏览器验证实时文字消息、D1 持久化、重复请求幂等性、删除权限及 XSS 防护。
3. 私人房间验证口令及 HTTP、WebSocket、媒体/通话授权边界；另检查刷新恢复。
4. 调用通话启动并取消接口；通过 Worker 同源上传真实写入私有 R2，确认上传后取回内容一致，再删除对象；超限请求被拒绝（413），CSRF 请求被拒绝（403）。

这次线上 R2 验收证明的是 Worker 流式写入私有 R2、确认与授权读取可用；不是 S3 预签名 URL 直传验收。私有桶 `blue-room-test-media` 的 CORS 已有 `https://chat.lyrnox.com` 的 PUT 规则（`Content-Type`、`Content-Length` 请求头，暴露 ETag，120 秒预检缓存），但目前流式模式同源传输不依赖浏览器跨域直传。

远程回归会在隔离测试 D1 留下测试会话、消息及私人房间记录；本次媒体对象在验证后已删除。测试记录不代表真实用户消息。

## 实时通话与 SFU

- 专用 SFU App 的 ID 与加密密钥已配置在 `blue-room-test` Worker；密钥没有进入仓库、测试输出或聊天内容。
- 先前的真实双浏览器音视频验收中，两端均收到对端实时音轨和视频轨，双方视频解码宽度达到 640px；该轮双向通话通过。最近这次 R2 流式上传部署后的 4 项远程回归只验证了通话启动/取消接口，没有再次做完整媒体通话。
- 早前一次 502 期间，持久日志曾显示 Cloudflare 对过期/断开的 SFU session 返回 HTTP 410、`session_error`，描述为 `Session appears to be disconnected. Please check if the PeerConnection is connected.`；该次错误没有取得 502 发生时对应的 provider 原始响应，故不能把 410 断言为 502 的根因。后续真实双浏览器媒体已通过，但 502 原始故障成因仍未完全证实。

## 仍未验收或未启用

- S3 预签名直传模式未启用，也未创建新的 R2 S3 凭据。Cloudflare 一次性 Secret 的页面会进入浏览器工具截图/输出，当前没有可证明安全的转存通道；因此没有生成凭据，避免泄露。无需这些凭据的同源 Worker 流式上传路径已线上验证可用。
- 最近一次部署后未重跑完整双向 SFU 媒体流；上一轮完整双浏览器通话证据见上节。
- Android/iOS 真机、弱网、TURN-only 网络及多设备多人通话仍未验收。
- Cloudflare 账单金额未核实；远程测试可能消耗现有资源额度。
- `workers.dev` 入口此前返回 1101；本项目的线上验收使用 `chat.lyrnox.com`，不以该入口判断此 Worker 的可用性。

## 测试命令

仅对隔离测试 Worker 运行远程测试：

```sh
CHAT_TEST_BASE_URL=https://chat.lyrnox.com pnpm test:remote
```

默认远程测试地址是 `https://chat.lyrnox.com`；脚本仅接受 HTTPS 地址。部署目标配置为 `wrangler.test.jsonc`。
