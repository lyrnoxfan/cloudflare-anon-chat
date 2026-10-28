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


## 后续回归修复 — 2026-10-28

- 昵称门页现在由 C++ Engine 的 `gate` 状态决策驱动：会话过期且无会话时，Android 隐藏整块聊天室内容（包含旧大厅标题与输入栏），并从无障碍树中排除；隐藏解除时按现有分页状态恢复“加载更早消息”按钮。原来半透明遮罩会让旧聊天 UI 透出的问题已在匹配的 Android 源码中修复。
- 在主机 C++ 测试与 Android JNI instrumentation 各增加 3 个状态回归断言：过期且未登录、有效会话、过期标记但会话已恢复。
- 本次工作机没有 `javac`、Android SDK、Gradle 或 `adb`，因此不声称 Java/Android 构建、instrumentation 或模拟器验收通过；只运行现有 CMake/GCC 主机测试及 ASan/UBSan。

## 0.1.2 build and empty-lobby UI update — 2026-10-28

- Source base: `main` at `d26e1f335287c5783f3b281e7e856ead523e41a9`, including the nickname-gate fix. This update adds a native empty-lobby state in `ChatFragment`: the site-inspired quiet icon, “这里还很安静” title and “发一句话，开始今天的对话。” hint. Version is 0.1.2 / versionCode 3. No website, Worker, Cloudflare configuration or remote data was changed.
- A local isolated Linux build machine (3.8 GiB RAM; no `/dev/kvm`, no installed Android emulator) successfully ran `./gradlew --no-daemon assembleDebug assembleDebugAndroidTest lintDebug` with JDK 17, Gradle 8.9, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1. The build completed successfully; Java and JNI/native code compiled for `arm64-v8a` and `x86_64`; lint completed without errors. This is a build/lint result, not an Android runtime or UI acceptance test. `testDebugUnitTest` has no source tests; Android instrumentation was built but not run.
- Linux host C++ tests rebuilt with GCC 12, `-Wall -Wextra -Werror` and AddressSanitizer/UBSan. CTest 1/1 passed and the executable reported 29 native assertions passed. These do not exercise Android UI, the Cloudflare service or device media.
- APK metadata: package `com.lyrnox.qingliao`, version 0.1.2 / code 3, minSdk 26, targetSdk 35; native libraries include `arm64-v8a` and `x86_64`. The 4,450,954-byte Debug APK passed `apksigner verify`; APK Signature Scheme v2 is valid with the Android Debug certificate (not a release signature). SHA-256: `2607403919d9f62e67ed5fc7bf191bbda487ddf1e3ac87b5d77a900a77f3727f`.
- No safe emulator is available on the isolated build machine (`/dev/kvm` absent; no emulator executable). The VPS is intentionally not used because its Android emulator runs had previously exhausted memory and swap. Thus the new APK has not been installed or visually verified; do not claim gate-leak regression or empty-state visual runtime acceptance from this build alone. The prior 0.1.1 screenshot only verifies that earlier version’s nickname page.
- Still incomplete: full site-aligned chat UI, end-to-end chat on the service, substantially broader native C++ business logic, actual voice/video calling and device camera/microphone tests. No GitHub Actions workflow was triggered.


## 0.1.3 session-state vertical slice — 2026-10-28

- The native C++ Engine now owns the session lifecycle (`unknown`, `required`, `active`), validates the `/api/session` response shape (UUID-formatted ID, 32 UTF-16-unit nickname, integer future expiry), and controls whether the native chat tree is visible. Java still owns HTTPS, cookies and UI event/lifecycle handling. During unknown/restoring state, the nickname gate is visible instead of a blank hidden chat; a failed restoration surfaces its error there and re-enables nickname entry. Valid responses open the chat only after native validation.
- Added targeted C++ and JNI instrumentation checks for initial/required state, rejecting expired sessions, accepting/normalizing a valid session, preserving accepted state after malformed responses, and requiring the gate again. The C++ host executable now reports 38 assertions.
- GCC 12 host build with `-Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer`: CMake build succeeded, CTest 1/1 passed, executable reported 38 native assertions; no sanitizer diagnostic.
- JDK 17 / Android SDK 35 / NDK 27.2.12479018 / CMake 3.22.1: `./gradlew --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug` succeeded, compiling the Android app, JNI for `arm64-v8a` and `x86_64`, instrumentation APK and lint. No lint errors. The instrumentation APK was built, not executed.
- `adb devices -l` reported no attached devices; `/dev/kvm` is absent and no emulator executable is installed on this isolated build machine. The VPS remains intentionally unused due to its earlier memory/swap pressure. Thus there is no new on-device screenshot or Android runtime/session/chat end-to-end acceptance.
- Exact APK source commit: `f389387e230bd7ca4de616693fc4e5eb43ad328e`, pushed to `main`; `origin/main` was verified at the same SHA after the commit-bound build. Version is 0.1.3 / versionCode 4.
- The resulting Debug APK is 4,505,950 bytes, SHA-256 `3cecb6827487af30159f062af9859b67b9a6416c28cd38024a0d173f10fe3d7b`. `apksigner verify --verbose --print-certs` passed; APK Signature Scheme v2 is valid, signed with the Android Debug certificate (not a release signature). The verified package is `com.lyrnox.qingliao`; minSdk 26, targetSdk 35; APK contains native libraries for `arm64-v8a` and `x86_64`. AndroidTest APK was also built (22,656 bytes) but instrumentation was not run.
- The isolated build machine has no Android emulator executable; `/dev/kvm` is absent and `adb devices -l` lists no attached devices. The VPS was not used. Therefore this APK has not been installed or run; no Android UI, session restoration, or chat end-to-end runtime result is claimed. No GitHub Actions workflow was triggered, and the live site, Worker, Cloudflare configuration and remote data were not changed.

## 0.1.4 nickname-to-session API vertical slice — 2026-10-28

- Source base: `main` at 0f788525c7b0eb743e4fb470b78c585cbf1683b5; app changes add a contract-specific `/api/session` native request/response transition. The C++ Engine now creates the trimmed nickname payload, tracks pending creation vs active-session rename, rejects non-200 or malformed responses, validates session identity/nickname/expiry plus the server's `limits.nickname`, positive `limits.sessionMs`, and boolean `sfu`, and exposes the accepted normalized session. The native gate opens only after accepted response; failed initial creation retains the nickname gate, while failed active nickname updates preserve the existing session.
- Android Java keeps the HTTPS transport, Origin, and HttpOnly cookie jar. `ChatNet.sessionPost()` is restricted to `/api/session` and returns status plus raw JSON body to the native validator; the session dialog now restores its button and field error on failure. This avoids live-site test calls in the local integration checks.
- The C++ host regression suite covers request path/body normalization, 401 recovery, valid contract response/state transition, failed active nickname update, malformed success payload, and transport failure. GCC 12 with `-Wall -Wextra -Werror` and ASan/UBSan: CMake build succeeded, CTest 1/1 passed, executable reported 51 assertions passed with no sanitizer diagnostic.
- Android instrumentation adds a contract-shaped in-process mock transport for success and 401; it asserts the native request path/body and that the accepted response opens the chat state. This is local mock/JNI integration code, not a real HTTP server or live service test. JDK 17 / Android SDK 35 / NDK 27.2.12479018 / CMake 3.22.1: `./gradlew --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug` succeeded, compiling app, JNI for arm64-v8a and x86_64, and the instrumentation APK; lint completed without errors.
- No attached Android device (`adb devices -l` empty); no emulator executable or `/dev/kvm` is available on this build host. Android instrumentation was built but not run. No APK install, visual UI, Android runtime/session/chat end-to-end or real network test is claimed. VPS was not used. No GitHub Actions, production Cloudflare site/configuration or remote data was touched.
- Version 0.1.4 / versionCode 5. APK details and exact app source commit are recorded after the build below.
- Remaining: full website UI parity, broader C++ business responsibilities, real voice/video calling and camera/microphone tests, and an Android device/runtime run.

- App/source commit: `5714551ca502df3810f2580a2fcd1a1faf00b94e` on `main`; the APK was built from the identical Android source tree immediately before this commit. The following documentation-only commit records these receipts.
- APK: `com.lyrnox.qingliao`, version 0.1.4 / versionCode 5, minSdk 26, targetSdk 35; 4,554,110 bytes; SHA-256 `19c2203524962c09b696b7dcb4efafdfe1235bb7f4552ecda30029287a3ba79e`. `apksigner verify --verbose --print-certs` confirms APK Signature Scheme v2; signer is the Android Debug certificate, not a release key. Native libraries: `arm64-v8a` and `x86_64`.
- The matching source archive is assembled from this Android source revision; APK and Android source package are attached separately. AndroidTest APK is 23,781 bytes and was compiled but not installed/executed.

## 0.1.5 原生纯文本发送/接收垂直切片 — 2026-10-28

- 基线为 `main` 的 0.1.4 源码提交 `39ac576`。核对仓库已有的服务端 `POST /api/r/{room}/send` 契约：JSON `{text, clientId}`，成功 `{message}`；返回消息包含 `id`、`nickname`、`client_id`、`kind`、`text`、`created`、`mine`、`deleted`。沿用既有真实 WebSocket `message` 事件与 `/history` 恢复路线；不访问线上服务。
- 新增 `MessageProtocol` 原生边界：Java/OkHttp 仅处理 HTTPS、Cookie 和原始状态码/响应体；C++ `Engine` 校验 HTTP 状态及 JSON 消息结构、房间代际、消息 ID、昵称、幂等 `client_id`、文本、时间、类型、发送者归属和删除状态。只有通过验证的成功响应才合并进 C++ 消息状态，UI 随后从 native snapshot 更新；重复 WebSocket 回执由既有 ID 去重，接收消息走既有 native event 合并路径。失败和结构无效响应不会清空输入或污染消息状态。客户端仍由 Java 管理网络与生命周期，未把 TLS/Android 能力危险地挪进 C++。
- 增加主机回归覆盖请求正规化、合法响应合并及顺序/实时重复去重、收到他人消息、client_id 不匹配、非 2xx 401、畸形成功响应及切房后的过期响应拒绝。GCC 12 编译启用 `-Wall -Wextra -Werror` 和 ASan/UBSan；构建成功，测试可执行文件报告 64 native assertions passed，无 sanitizer 诊断。此构建环境没有 `ctest` 命令，故这里只报告直接运行的完整测试二进制，不声称 CTest。
- Android 本地模拟传输 instrumentation 包含 `/api/r/lobby/send` 成功、native snapshot 展示已发送消息、WebSocket 收到消息、401 及畸形 200 不改状态场景。构建成功不等于 instrumentation 已运行。本机 `adb devices -l` 为空，无可用模拟器/设备；没有真实安装、运行、UI 截图或线上聊天 E2E 验收。
- Android Gradle 构建/lint、debug 签名、包元数据、ABI、APK hash、匹配源码归档及精确应用源提交将在下方 receipts 中记录。没有触发 GitHub Actions、没有 VPS、没有访问线上服务，也没有部署/发布。
- 整体网页 UI 对齐、完整聊天室功能回归、显著扩大 C++ 业务核心、真实语音/视频通话仍未完成。

### 0.1.5 build receipts

- App source commit: `1b705d1268b2bfaae2de2e33ea76c58792f25d2b` on `main`. This commit contains the app implementation and instrumentation; the following main commit is documentation-only.
- Build command: `./gradlew --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug`; Gradle 8.9, JDK 17.0.20.1, Android compile/target SDK 35, NDK 27.2.12479018, CMake 3.22.1. Build succeeded (71 tasks; 19 executed, 52 up-to-date), app and AndroidTest APK packaged, lint succeeded with no errors. Android instrumentation was compiled, not run.
- APK: `com.lyrnox.qingliao`, version 0.1.5 / versionCode 6, minSdk 26, targetSdk 35; 5,841,300 bytes. SHA-256 `165adc3f5dc61957a6cc891c8fddb23fcf6a73e2506db3937ab661c6cf8690d6`. `apksigner verify --verbose --print-certs` verified APK Signature Scheme v2; signer is Android Debug (`CN=Android Debug, O=Android, C=US`), not a release key. Included ABIs: `arm64-v8a`, `x86_64`.
- AndroidTest APK: 17,014 bytes; SHA-256 `8e27c72d367f6b57d73b81d97626432881bac96cc1e51cc3ec007ca01d371428`. It was built but not installed or run.
- Host C++ regression: GCC 12.2, CMake 3.22.1, `-Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer`; build succeeded and direct test executable reported 64 native assertions passed. This computer does not have `ctest`; no CTest claim is made.
- Runtime boundary: `/home/user/android-sdk/platform-tools/adb devices -l` returned no attached devices, `/dev/kvm` is absent, and no Android emulator executable is installed. The APK was not installed/launched; Android JNI instrumentation and actual chat service E2E remain unverified. No live endpoint was accessed, no Cloudflare service/config/data was changed, no VPS used, no GitHub Actions or Release triggered, and nothing deployed.
