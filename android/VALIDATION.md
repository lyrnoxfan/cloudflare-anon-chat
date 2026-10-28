# 验证记录 — 2026-10-28

## 已实际执行

- 基线：原仓库 main `ec77d86412ff10afdf6ebdd61eb716c2f1a5a7ca`。新增 Android 目录位于独立本地 `feat/android-native` 分支。没有提交、推送、PR、合并或部署；网站、Cloudflare 配置和既有数据均未修改。
- Linux 主机 C++ 测试：CMake + `-Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer`。CTest 1/1 通过，测试程序 26 项断言通过，运行中没有 ASan/UBSan 报告。覆盖协议校验、Unicode/代理对、长度边界、短口令、排序、幂等去重、删除墓碑、重置、成员、上传大小和退避；不是 Android/JNI 或网络验收。
- JDK 17 / Gradle 8.9 / AGP 8.7.3：`assembleDebug assembleDebugAndroidTest lintDebug` 成功。编译了 Java 和两种 Android ABI 的 C++/JNI；生成 app 与测试 APK。lint 结果见 `evidence/lint.txt`，没有 lint error；剩余 warning 主要为动态中文文案国际化、手写 View 构造函数。
- Android build-tools `apksigner verify --verbose --print-certs` 验证 v2 签名有效，证书 `CN=Android Debug, O=Android, C=US`。不是正式发布签名。
- `aapt dump badging` 核对包 `com.lyrnox.qingliao`，0.1.0，minSdk 26 / targetSdk 35；APK 包含 `arm64-v8a`、`x86_64`，不含 32 位 ABI。具体输出见 evidence。
- 静态核对实际服务端代码的 session/room/state/history/send/delete、WebSocket 事件、上传/确认/授权下载协议；客户端使用真实 HTTPS 服务地址，没有模拟在线人数、预填聊天或 WebView。

## 未完成的验证

本机没有 `/dev/kvm`。尝试现有 Android API 30 x86_64 AVD 的无硬件加速启动：第一种渲染配置进程退出 139；第二种关闭 GPU 的 TCG 软件模拟出现 adb device，但未完成系统启动，包管理服务未就绪。继续运行会持续大量占用 CPU，已停止该进程。日志见 `evidence/emulator-startup.log`。

因此没有完成 APK 安装/启动、Android JNI instrumentation、原生布局目视检查，也没有真实 Android 双端联网、权限/麦克风/媒体、旋转恢复或 Android 15 验收。没有伪造模拟器截图。

提供了可执行的 JNI instrumentation 和显式启用的真实服务测试代码，但本轮没有执行网络模式，没有创建线上测试房间/身份/消息或上传媒体。网络测试须在可启动的 Android 设备/模拟器上运行；测试会留下新建的测试房间和匿名身份，细节见 README。

## 版本边界

这是可构建、可侧载的开发测试包，不是已经通过端到端验收的发布版。实时音视频通话、后台推送、离线数据库/发送队列、32 位 ABI、深色主题及完整连续消息分组未实现。媒体编码兼容和真机表现未知。表情素材许可须在公开分发前确认。

SDK、NDK、构建缓存、真实凭据和本机路径配置未装入源码归档。完整构建步骤在 README。APK 字节数、SHA-256 与签名检查输出随交付重新计算。
