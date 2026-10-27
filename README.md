# 蓝间：Cloudflare 匿名聊天室

当前隔离部署与未完成事项见 [部署状态](docs/DEPLOYMENT_STATUS.md)。公开测试地址尚不可用，不能把源码交付当作上线完成。

一个独立的 TypeScript Worker 项目。页面与 API 同源，由一个 Worker 提供静态资源；房间 Durable Object 负责顺序写入后的广播与 WebSocket Hibernation，D1 保存房间/消息/匿名权限/通话记录，私有 R2 保存文件，Cloudflare Realtime SFU 转发媒体。不是静态聊天演示，没有预置消息、模拟在线人数或假通话。

## 交付状态：请先读

已经实际通过本机 Wrangler/D1/R2/DO 模拟器和 Chromium 双独立浏览器会话测试：免注册大厅、真实 WebSocket 收发、刷新历史、发送幂等、作者删除、私人房间口令/隔离、文件上传确认/短票据下载/删除、图片解码和浏览器录制视频的真实上传展示、麦克风 MediaRecorder 录制与音频解码、XSS 纯文本、错误及跨站请求校验。类型检查、JS 语法与 Wrangler 打包已实际执行。本次11项测试通过：9项本地集成与2项SFU适配器桩服务合同检查；后两项不是实际SFU传输测试。浏览器使用合成麦克风/摄像头，不等于手机真机验收。

SFU 发布/订阅、邀请、接听/拒绝、通话参与者租约、挂断、强制关闭轨道、TURN、音视频控制、设备切换与有限重连的代码均已编写，按官方 OpenAPI 实现，**隔离Worker已发布，但公开地址返回1101，且未配置SFU App/Secret和远端参与设备；没有验证实际SFU媒体传输，不能称线上可用或全功能生产验收完成**。缺少 SFU 配置时返回明确 503，不假装连通。真实 R2 S3 预签名跨域直传同样必须部署后验收；本地测试使用 R2 binding 模拟器端点而非冒充真实 S3。

未执行远程建库/开通付费产品/推送仓库/生产部署。下面包含操作者可以复现的步骤。

## 1. 安装与本地运行

使用 Node 22 或更新版本、pnpm 10（锁文件已固定；可用 `corepack enable` 后 `corepack prepare pnpm@10.32.1 --activate`）。

```sh
pnpm install --frozen-lockfile
cp .dev.vars.example .dev.vars
# 编辑 .dev.vars，DEV_LOCAL=true，APP_SECRET 用 openssl rand -hex 32 生成
pnpm db:local
pnpm dev
# 打开 http://localhost:8787，另开无痕窗口作为第二个人
```

`.dev.vars` 不进版本库。示例的 APP_SECRET 是占位符，不应直接用于公开部署。本地 HTTP 只允许 DEV_LOCAL=true；生产 Cookie 必须 Secure。不要把 DEV_LOCAL=true 放入生产配置。SFU 真实测试需额外填入下述配置，并在浏览器可用的 localhost/HTTPS 环境运行。

```sh
pnpm exec playwright install --with-deps chromium
pnpm check
# 单独执行：pnpm typecheck；pnpm test
# 打包检查（不部署）：pnpm exec wrangler deploy --dry-run --outdir .build
```

测试会启动 8787 端口本地 Wrangler；测试前关闭手动启动的本地实例。测试需要配置 .dev.vars；集成测试自动创建独立临时模拟库、执行迁移，结束后清理，不使用你的开发库或远程数据库。`tests/latest-run.txt` 是本次真实执行记录，截图在 `tests/`。CI workflow 只做本地验证，**不会部署**；未推送，所以未运行 GitHub Actions。执行 `pnpm test` 自身只打印测试结果，需要保存时可重定向到其他日志文件。

## 2. Cloudflare 初始化与部署（自行确认后执行）

以下命令会修改你的 Cloudflare 账号，创建资源或部署 Worker；交付过程中未执行。

```sh
pnpm exec wrangler login
pnpm exec wrangler whoami
pnpm exec wrangler d1 create chat
pnpm exec wrangler r2 bucket create anonymous-chat-media
```

将建库返回的 ID 填入 `wrangler.jsonc` 的 `d1_databases[0].database_id`。如果名称已存在，请选独立名称并同步修改 database_name、bucket_name 和 vars.R2_BUCKET；不要覆盖别人的应用。保持绑定名 `DB`、`MEDIA`、`ROOMS`、`GUARD`、`ASSETS` 与代码一致。Durable Object 类名为 `ChatRoom`、`Guard`，使用 SQLite-backed DO，首次迁移 `v1/new_sqlite_classes`。**已上线的 DO 迁移不可随意修改/删除旧 tag**。

```sh
pnpm exec wrangler d1 migrations apply chat --remote
pnpm exec wrangler secret put APP_SECRET
# 输入随机生成的至少32字符安全密钥，不使用聊天室口令
```

R2 bucket 保持 Private，不开启 r2.dev public access 或公开域名。创建只读写这个 bucket 的 R2 S3 API Token（控制台 R2 → Manage R2 API Tokens → Object Read & Write → 指定该 bucket）。Worker 使用 binding 做确认与下载，S3 key 仅在服务端签预签名 PUT。配置：

```sh
pnpm exec wrangler secret put R2_ACCOUNT_ID
pnpm exec wrangler secret put R2_ACCESS_KEY_ID
pnpm exec wrangler secret put R2_SECRET_ACCESS_KEY
```

不要把 Cloudflare 全账号 API token 当成 R2 Access Key；不要粘贴到前端、Git 或聊天记录。设置 `wrangler.jsonc` 的 `R2_BUCKET` 与真实名称一致。

### R2 CORS 和清理

将 `r2-cors.example.json` 内的 Origin 改成你的真实站点源（含 https，无路径），临时预览域也需明确列出。不使用 `*`。

```sh
pnpm exec wrangler r2 bucket cors set anonymous-chat-media --file r2-cors.example.json
pnpm exec wrangler r2 bucket cors list anonymous-chat-media
```

浏览器直接以 PUT 上传到 R2 S3 endpoint。Content-Type 和 Content-Length 都签入，URL 有效 120 秒；浏览器自动发送实际 Content-Length，不要在 JS 手工设置这个受限头。签名不匹配、R2 CORS 或超时都会返回失败，不写正式消息。泄漏 URL 在过期前仍可重复 PUT，**不是一次性 URL，也不能声称立刻吊销**。对象先落随机 staging/ 路径，确认后通过 R2 stream 复制至另一个从未暴露写授权的 media/ 路径，再事务写消息并广播，避免旧 PUT 覆盖已发送文件。

15 分钟 Cron 清理待上传/取消/已完成 staging 及删除队列。已完成消息无自动 TTL。建议另设 R2 生命周期规则：仅 `staging/` 前缀 1 天过期，作为任务失败时兜底，**不要给整个 bucket 设置自动删除**。本项目每次 Cron 最多处理各100项；大规模使用需监控积压、增加批量预算/清理频次。删除后数据库消息立即变为 tombstone，下载立即拒绝；R2 文件由垃圾队列在下一次成功 Cron 删除，不冒称瞬间物理擦除。浏览器已下载内容及已经开始的下载无法撤销。

### Realtime SFU 与 TURN

控制台 Realtime → SFU → 创建独立 App，取得 App ID 与 App Secret（某些控制台仍称 Calls）。如果账号未开通、被限制或需接受计费条款，先由账号所有者完成；本任务没有开通或付费权限。配置：

```sh
pnpm exec wrangler secret put SFU_APP_ID
pnpm exec wrangler secret put SFU_APP_SECRET
# 推荐为限制性网络配置 Realtime TURN Key 与对应 API token
pnpm exec wrangler secret put TURN_KEY_ID
pnpm exec wrangler secret put TURN_API_TOKEN
```

TURN token 只需该 TURN Key 所需权限。TURN API 由服务端请求短期 iceServers，ttl=3600；未配置时仅 Cloudflare STUN，严格 NAT/防火墙可能失败，不保证全部网络连通。**SFU App Secret 永不下发**。浏览器持短期、会话/通话绑定 HMAC capability 访问同源媒体信令代理；代理重新检查房间访问权限和参与资格，远端 sessionId/trackName 只从服务端映射取，不能让客户端随意订阅别人的 SFU session。这里没有臆造 Cloudflare “SFU JWT 登录”接口；SFU REST 使用服务端 Bearer Secret。

SFU 适配层在 `src/sfu.ts`：`POST /sessions/new` → `POST /sessions/{id}/tracks/new` 发布 offer/answer；接收轨道由 SFU 返回 offer，浏览器生成 answer 后 `PUT /sessions/{id}/renegotiate`；退出通过 `PUT /tracks/close`，`force:true`。没有 session DELETE 接口，不伪造它。媒体 ID、待清理队列与租约写入 DO storage；参与记录写入 D1。会话失败而结果不明不自动重放同一个媒体写请求；已知资源关闭失败保留队列由 alarm 重试。若创建 session 的响应在获得 ID 前丢失，无法定向关闭未知 ID，只能依赖服务商空会话超时，这是外部 API 的不确定性边界。

每房默认最多8人，统一 SFU 发布/订阅，不使用全网状 P2P。每房同时1个通话；一对一只允许发起者与目标会话加入。选中在线成员再点语音/视频是一对一；不选成员为多人通话。多人房间成员点接听/加入。30秒无人/无人接听超时，45秒租约，15秒 alarm 清理。页面退出先停止本地媒体，再尽力发 keepalive；服务端即使没收到也通过租约清理。网络恢复优先等6秒，最多2次重新加入；一对一退出会结束该通话，可能需要重新呼叫，不保证无缝迁移。没有录制、屏幕共享或媒体长期保存。

### 发布与自定义域名

确认 R2/SFU 计费和数据政策后：

```sh
pnpm typecheck
pnpm exec wrangler deploy --dry-run --outdir .build
pnpm exec wrangler deploy
```

使用 Wrangler 实际返回的 `workers.dev` 地址，不要根据 name 猜测已经上线。自定义域名：Cloudflare Worker → Settings → Domains & Routes → Add Custom Domain，选择账号已有 zone 下的子域并完成证书；或者在配置添加 `routes: [{pattern:"chat.example.com", custom_domain:true}]`，再部署。同步更新 R2 CORS。域名变更后 Cookie 不跨域迁移，私人房间需重新验证口令。生产环境请保持 HTTPS。

## 3. 安全、数据和限制

- 昵称允许重名，不是可恢复账号。Cookie 为服务端随机 token（DB仅存SHA-256）、HttpOnly、SameSite=Strict、生产Secure，7天过期。刷新保留昵称和房间；本地存储仅保存昵称与房间ID，不存口令/授权token。匿名作者只能删除当前仍有效的浏览器会话所发消息，换设备或会话到期不能找回作者身份。
- 私人房间口令在服务端 PBKDF2-SHA256 100,000 次、每房随机盐。此迭代值为 Workers WebCrypto 可运行折中，不应称为最强防离线破解；要求至少8字符，建议使用高熵随机口令。Worker 按IP/会话限速口令尝试。私人房间访问授权2小时，原Cookie会话内刷新/断线重连可复用，到期重输。
- Worker每业务请求检查会话/房间，WS建连和客户端心跳重新检查；广播检查附件授权有效期；通话alarm也核验权限。HTTP写入与WS校验严格同源Origin；GET媒体仍需Cookie、房间授权和60秒下载票据。下载通过绑定流式转发，**不会给客户端裸R2 GET链接**，复制票据到另一个浏览器无效。
- 文本4000字符，昵称32，房名80。文件上限集中于 `src/shared.ts/LIMITS`：图片10MiB、语音10MiB、视频100MiB、普通文件50MiB。上传按申报大小限额并签实际大小，确认时再读实际R2对象size与Content-Type。Content-Type验证不是杀毒/内容真伪鉴定；不支持内嵌SVG/HTML文件执行，其他文件强制attachment和nosniff。服务不做恶意软件扫描或转码。
- Guard DO为服务端原子固定窗口限流（存储可跨休眠恢复）。每IP每小时最多30新会话、5私人房间、30上传/500MiB；每会话每分钟40消息、每小时10上传/200MiB；房间口令每IP与会话每分钟10次；发起呼叫每分钟6次；通话API每分钟120次。读历史50条游标分页。单房最多200个WS，按匿名会话去重人数，多标签页非重复用户。固定窗口/匿名IP不是强抗女巫方案；大范围公开前应加WAF/Turnstile、预算告警和管理员滥用处理。不能承诺只靠这些限额杜绝攻击。
- 幂等约束 `(room_id,author,client_id)`；文本先D1成功再广播，重试同id不重复插入。上传未确认不会创建消息；最终对象流式复制与D1不是跨服务事务，失败时保留上传行供重试/清扫。删除只保留最少元数据与tombstone，正文/对象引用清空，不是仅前端隐藏。
- 没有E2EE；服务端处理并保存聊天记录。默认无限期保存文本和确认媒体；必要通话元数据同样无自动TTL，**不保存通话媒体**。需要保留周期或人工删除房间，应先制定规则和备份策略再扩展。没有账号、管理员后台、房间口令重置、举报/审查、全站导出或加密备份界面。
- 没有在应用日志输出正文/口令/Secret/签名URL，observability默认关闭。自行开启访问日志/第三方统计时要过滤query票据及媒体签名。不要把真实Secrets放入测试记录。

## 4. 文件结构

```
src/index.ts       HTTP路由/会话/房间/签名R2上传/受保护下载/清理/Guard
src/shared.ts      限制、校验、密码哈希、HMAC、数据库授权
src/room.ts        DO顺序写入、历史、WS广播/成员、文件确认/删除
src/sfu.ts         Realtime服务端适配、参会授权、轨道状态、清理与租约
public/            原生HTML/CSS/JS，含MediaRecorder和WebRTC
migrations/        D1 schema和固定公共大厅
wrangler.jsonc     与代码对应的Assets/D1/R2/DO/cron绑定
.dev.vars.example  环境示例，不含真实凭证
r2-cors.example.json
.github/workflows/check.yml  本地模拟器验证，不自动部署
tests/             可复现集成测试、截图及本次记录
docs/              官方API快照、阅读摘录、构建/迁移记录、验收清单
```

所有SQL参数化，无第三方UI框架，只有签名库aws4fetch为运行时依赖。生产bundle不包括Playwright。不要将 `.wrangler/`、`.build/`、`node_modules/` 或 `.dev.vars` 上传到Git。

## 5. 故障排查与真实验收

- `503 服务端安全密钥尚未配置`：检查APP_SECRET；“音视频服务未配置”：缺SFU配置，文字不受影响。
- 私人房间403：先验证口令；Cookie会话/授权过期需重进，不通过改前端状态绕过。
- R2上传403：检查token权限、bucket/account、S3 endpoint、CORS origin、Content-Type/Length和120秒过期。不要把API token输出到日志。取消后重选文件可获新授权；旧URL不会立刻失效，staging会自动清理。
- MediaRecorder/getUserMedia：仅HTTPS/localhost，Safari/Android格式不同，代码探测可录格式；拒绝设备权限仍能聊天。摄像头切换需要真实设备支持，不能凭桌面合成设备测试说手机通过。
- SFU 502：检查App配置和官方API响应；前端不接触App Secret。参会失败退出后重加入，待清理队列未清空时禁止新通话，防止无界泄露。检查TURN配置、UDP/防火墙、浏览器webrtc-internals。不要用发起成功/信令成功代替实际音视频通过。
- `429`：真实后端限流，等窗口或调整集中限制；多用户NAT共享IP可能误伤。不能生产简单关闭权限或限流来“修好”。

真实部署后按 `docs/ACCEPTANCE.md` 测：两个独立浏览器一对一、三台设备多人发布订阅、双方实际听见/看见、远端退出轨道停止、私房未授权直接请求被拒、过期/旧票据、R2 CORS与超限上传、弱网、设备拒绝、切换前后摄像头、页面关闭后设备灯熄灭。**这些远程与真机验收当前尚未执行。**

## 6. 费用（2026-10-27查阅官方文档，不是免费承诺）

- Workers Free：100,000请求/天、10ms CPU/次；付费Standard最低约US$5/月，包含1000万请求和3000万CPU-ms，超额分别$0.30/百万请求、$0.02/百万CPU-ms。PBKDF2、文件确认、DO/DB开销可能需要Paid；本地CPU不代表线上套餐可承受。
- SQLite DO可用于Free/Paid；请求、运行时长和SQLite行读写/存储分别计量，休眠期间不收duration但心跳/通话alarm会唤醒。Guard每次限流也增加DO存储/请求，不能当作零成本。配额以账号控制台与官方最新价目为准。
- D1 Free：每日500万行读/10万行写、总5GB；Paid包含每月250亿行读/5000万行写/5GB，超额$0.001/百万行读、$1/百万行写、$0.75/GB-month。文本长期增长、历史/心跳授权查询都占额度。
- R2 Standard：前10GB-month、每月100万Class A/1000万Class B免费；超额约$0.015/GB-month、$4.50/百万A、$0.36/百万B，R2自身互联网egress无流量费。上传确认的staging复制、删除及预览/下载都产生操作；中转Worker仍有其自身请求成本。
- Realtime SFU和TURN共享每月前1000GB免费egress，其后$0.05/GB；发布进入Cloudflare不收费，转发给每个订阅者的出站累计，视频多人尤其容易增长。不把R2免费egress误当SFU免费。
- 自定义域名注册费用、其他账号产品、税费另计。设置预算告警并监控Workers/DO/D1/R2/Realtime控制台；代码里的上传速率不是硬性账单上限。

官方依据及检索日期见 `docs/SOURCES.md`；价格可能变化，实际合同/账单优先。
