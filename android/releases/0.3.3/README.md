# 轻聊 Android 0.3.3 — 正式版（release 签名）

- 文件：`qingliao-0.3.3-release.apk`
- 文件大小：27,410,012 字节
- SHA-256：`dcbc2f1b4837760320f04949b485862bb299dd3ff2d47de5d81c81840e8d8597`
- 包名：`com.lyrnox.qingliao`；versionCode 24 / versionName 0.3.3；minSdk 26（Android 8.0+），targetSdk 35，compileSdk 35
- ABI：`arm64-v8a`、`x86_64`（不含 32 位 ABI）
- 签名：release keystore `CN=Qingliao Android Release, O=Lyrnox`；证书 SHA-256 `76c1f82df878d1712f5ca20f25f76ed8f3b6d135c3e004f9e7830f73a3f1be50`（非 debug key）
- 类型：原生 Android 客户端，直接连接 Cloudflare 聊天服务 `https://chat.lyrnox.com`；原生 UI 采用聊记/微信风格（暖米色底 `#FCF5EB` + 绿色发出气泡 `#95EC69`），与网页版新版主题一致。

## 说明

- 本 APK 为 Android 源码（`android/`，versionCode 24 / 0.3.3）的正式签名构建快照。
- 2026-11-28 本轮仅修改网页/Worker（暖米色+绿气泡主题、若干 bug 与安全头），**Android 源码未改动**——其配色本就与此主题一致（见 `ChatFragment.java` 的 `bg/topbar/mine/line/chip` 等色值）。
- 构建依赖：JDK 17、Android SDK platform/build-tools 35、NDK 27.2.12479018、CMake 3.22.1；首次联网解析 Gradle/OkHttp/WebRTC SDK 依赖。详见 `../../README.md`。

## 校验

```
sha256sum -c SHA256SUMS
```
