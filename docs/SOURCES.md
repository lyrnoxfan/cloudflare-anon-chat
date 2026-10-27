# 官方文档依据

2026-10-27实际读取；文档是研究依据而不是账号已开通的证据。SFU的官方OpenAPI、连接模式和协商规则快照随项目保存，方便审计，部署前请检查是否已更新。

- SFU overview https://developers.cloudflare.com/realtime/sfu/
- API / OpenAPI https://developers.cloudflare.com/realtime/sfu/api/ （快照 realtime-api.yaml）
- Video-room example https://developers.cloudflare.com/realtime/sfu/examples/video-room/
- Connection patterns https://developers.cloudflare.com/realtime/sfu/get-started/connection-patterns/
- Renegotiation https://developers.cloudflare.com/realtime/sfu/concepts/negotiation/
- Limits https://developers.cloudflare.com/realtime/sfu/platform/limits/
- Pricing https://developers.cloudflare.com/realtime/sfu/platform/pricing/
- TURN keys credentials API https://developers.cloudflare.com/realtime/turn/generate-credentials/
- R2 presigned URLs https://developers.cloudflare.com/r2/api/s3/presigned-urls/
- R2 CORS https://developers.cloudflare.com/r2/buckets/cors/
- R2 limits https://developers.cloudflare.com/r2/platform/limits/
- DO hibernation https://developers.cloudflare.com/durable-objects/best-practices/websockets/
- Worker Assets https://developers.cloudflare.com/workers/static-assets/binding/
- D1 migrations https://developers.cloudflare.com/d1/reference/migrations/
- Pricing: https://developers.cloudflare.com/workers/platform/pricing/ ; https://developers.cloudflare.com/durable-objects/platform/pricing/ ; https://developers.cloudflare.com/d1/platform/pricing/ ; https://developers.cloudflare.com/r2/pricing/

实现只使用上述实际存在的SFU REST路径，没有自行发明临时SFU JWT接口。临时权限是本应用签发的HMAC capability；Cloudflare SFU Secret留在服务端。SFU App和TURN key/token需所有者创建。
