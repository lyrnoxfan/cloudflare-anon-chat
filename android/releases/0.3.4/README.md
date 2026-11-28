# 轻聊 Android 0.3.4 — 正式版（release 签名）

- 文件：`qingliao-0.3.4-release.apk`
- 文件大小：27,410,012 字节
- SHA-256：`c1f0379bcedcb4d704b3f6b7eba7069f9129b5a5e10e699d2691cfc6ffb75ecd`
- 包名：`com.lyrnox.qingliao`；**versionCode 25 / versionName 0.3.4**；minSdk 26（Android 8.0+），targetSdk 35，compileSdk 35
- ABI：`arm64-v8a`、`x86_64`（不含 32 位）
- 签名：release keystore `CN=Qingliao Android Release, O=Lyrnox`；证书 SHA-256 `76c1f82df878d1712f5ca20f25f76ed8f3b6d135c3e004f9e7830f73a3f1be50`（非 debug key，与 0.3.3 同一把）
- 源码提交：`5c4e66cd6774f131c6ab946ca7d0de9f0d3a78ab`（main）
- 构建：GitHub Actions 工作流 `qingliao-android-sign-polish`（`assembleRelease` 后用上面的 release 密钥签名，证书摘要强校验）

## 0.3.4 相对 0.3.3 的修复

- **P0 崩溃**：上传取消/完成/失败回调在 `onDestroyView` 之后触碰已置空控件（旋转屏幕、退出页面时）会 NPE → 增加 `uploadPanel/uploadLabel` 空守卫。
- **断线“半死”**：WebSocket 断开后 `/state` 恢复探针只尝试一次即放弃，导致通话状态仍在轮询但新消息/成员永不再推送 → 改为探针失败也继续指数退避重建连接。
- **通话被瞬时音频打断即挂断**：通知音、导航播报、来电振铃等 `AUDIOFOCUS_LOSS_TRANSIENT` 会直接挂断整通电话 → 现在只有永久 `AUDIOFOCUS_LOSS` 才结束通话。
- **callClock 泄漏**：本地通话结束后仍每秒空转重绘 → 加 `nativeCall` 判空。
- **界面微调**：发送按钮改为微信绿底深绿图标（原为橙色）；去掉每条消息“已发送”重复 toast；插入表情后自动聚焦输入框并收起面板；身份徽标显示当前昵称；输入胶囊改为米白。

## 校验

```
sha256sum -c SHA256SUMS
```

APK 校验与签名信息见同目录 `signature.txt`、`apk-manifest.txt`。
