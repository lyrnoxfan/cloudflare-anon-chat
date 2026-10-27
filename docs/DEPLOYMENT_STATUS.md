# 部署状态与验收记录

更新时间：2026-10-27。线上验收目标为聊天室测试 Worker `blue-room-test`。用户报告已将 `chat.lyrnox.com` 指向该测试 Worker；本轮未改动域名、Worker、生产流量或 Cloudflare 资源。

## 自定义域名验收

- `https://chat.lyrnox.com/`：2026-10-27 15:12:16 UTC 返回 HTTP 200。Ray ID：`a512a3573f31f09d-DFW`。
- `https://chat.lyrnox.com/api/session`（无 Cookie）：同一时刻返回 HTTP 401，JSON 为 `{"error":"请先输入昵称"}`。Ray ID：`a512a358bca66970-ATL`。
- `pnpm test:remote`（默认目标 `https://chat.lyrnox.com`）：4/4 项通过。明确断言首页 HTTP 200、无 Cookie 的 `/api/session` 返回 JSON HTTP 401；双独立浏览器还验证实时消息、刷新后的持久化、幂等与删除权限、XSS 防护，以及私人房间口令与 HTTP/WebSocket 授权、CSRF 拒绝、超大上传拒绝和缺少媒体凭据时返回 HTTP 503。
- 用户另报告：用昵称创建会话成功写入 D1。自动化验收也通过网页创建两组测试会话，并验证消息可在另一浏览器接收、刷新后仍可见。

远程测试会在目标测试 Worker 的 D1 留下测试会话、消息及私人房间记录；部分测试消息会按测试内容删除，其他测试记录不保证自动清理。无须把这些测试数据当作真实用户消息。

## 代码及本地检查

- 修改仅涉及线上测试默认地址、测试脚本分流及说明文档；`pnpm test` 只跑本地集成测试和 SFU 接口桩，`pnpm test:remote` 才访问远程目标。
- 本地 `pnpm typecheck` 及 `pnpm test`：11/11 通过；前端 JavaScript 语法检查通过。
- 本轮没有部署 Worker，也没有改变生产/测试配置；远程测试命令不是发布。

## 仍未验收

- SFU 应用密钥尚未配置；真实通话没有测试，当前通话接口按预期返回 HTTP 503。
- R2 的真实 S3 直传、CORS 预检及媒体下载仍未通过线上验证；相关凭据/CORS 配置尚未齐备。本地模拟测试不等同于真实 R2 验收。
- Android/iOS 真机、弱网、TURN 网络限制及多人设备通话尚未验收。
- Cloudflare 账单金额仍未核实；远程测试可能消耗现有额度。
- `workers.dev` 地址此前仍报 1101；本轮改用自定义域名验收，没有重查其原因。用户将其归因于账号下 `workers.dev` 地址问题，但本轮没有独立验证这一平台侧原因。

`pnpm test:remote` 仅应对隔离测试 Worker 运行。默认地址是 `https://chat.lyrnox.com`；若目标不同，可用 `CHAT_TEST_BASE_URL=https://<测试域名>` 覆盖，脚本会拒绝非 HTTPS 地址。
