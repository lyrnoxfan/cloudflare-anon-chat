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


## 0.1.6 原生 WebRTC 发布/订阅实验切片 — 2026-10-28

- 应用源码基线为 `main` 提交 `820d10a073f45a93f28692c785be43f72898d2af`。本轮只更新 Android 验证记录及交付文件；没有访问线上服务、触发线上 HTTP/WebSocket/SFU 操作、修改网站/Worker/Cloudflare 配置或远端数据，也没有部署或创建 Release。
- 只读核对了仓库 `src/sfu.ts` 与 `public/call.js` 的信令字段；代码对接现有 `call-state`、`call-start`、`call-join`、`call-publish`、`call-ready`、`call-heartbeat`、`call-leave`/`call-cancel`，以及远端 `call-subscribe` 和 `call-answer`。SDK 依赖为 `io.github.webrtc-sdk:android:125.6422.07`。
- 实验通话代码包含本机音频/可选 Camera2 视频发布、按成员状态请求远端轨道、提交 SDP answer，以及远端视频渲染路径。C++ Engine 校验发布/订阅信令响应与内部阶段；服务端接受 answer 只证明信令步骤，不代表 ICE 或媒体连接成功。来电/接听体验和产品化通话界面尚未完成。
- C++ 主机回归（GCC 12、CMake 3.22.1、`-Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer`）已有 82 项断言通过、无 ASan/UBSan 诊断；本轮没有重复运行这组测试。此前 Gradle build 与 lint 也已通过；本轮重新运行的是下面列出的 APK 打包任务，而非完整 lint/主机测试。
- 此前 31 项 Android JNI instrumentation 通过，针对的是先前的 `.qa` 测试包；该包不是下面从当前 `820d10a` 应用源码重建的正式 applicationId APK，且其测试发生在最后一处 C++ 状态调整前。因此那 31 项结果不能算作当前 0.1.6 APK 的 instrumentation 结果。
- 按要求尝试用已授权 VPS 上的 Android 9 x86_64 模拟器安装当前 APK。模拟器已有 `com.lyrnox.qingliao` 0.1.1，且其签名证书与新 APK 不同；`adb install -r` 明确失败 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。为保护模拟器现有应用及其数据，没有卸载旧包、清除数据或尝试绕过签名检查；检查确认 0.1.1 仍在，故当前 APK 没有安装、启动或运行 instrumentation。未触达线上服务，也没有通过启动应用来发起网络请求。
- 本轮 APK 以 JDK 17 / Gradle 8.9 / Android SDK 35 在 `main` 的应用源码提交 `820d10a073f45a93f28692c785be43f72898d2af` 上打包。命令：`ANDROID_HOME=/home/user/android-sdk ANDROID_SDK_ROOT=/home/user/android-sdk ./gradlew --offline --no-daemon --max-workers=1 assembleDebug assembleDebugAndroidTest`；Gradle 报告 62 项任务，15 执行、47 up-to-date，成功。该步骤没有重跑 `lintDebug`。
- APK 元数据经 `aapt dump badging` 核对：`com.lyrnox.qingliao`，version 0.1.6 / versionCode 7，minSdk 26 / targetSdk 35；包含 `arm64-v8a`、`x86_64`。大小 29,088,791 字节，SHA-256 `cbd48723abf4de382e2124f5d6630cce211f26d43f4d50c9054269107af2f37a`。`apksigner verify --verbose --print-certs` 验证 APK Signature Scheme v2 有效；签名证书 SHA-256 `5ac2bc3b2b76d22e9d9df95dbbd63b09526a402e030765a1d3e776ca58b39fd1`，为 debug 签名，不是 release key。
- 配套 AndroidTest APK 大小 17,014 字节，SHA-256 `bdf921ab91599a0d8c5442e38d8496bd7af0d695fe10b7d41939ddd1da48bbd4`。它成功构建，但由于上述包签名不匹配，没有安装或运行。
- 对应完整 Android 源码归档包含 Gradle wrapper、构建配置、app 源码、测试和本验证文档；不含本机 SDK/缓存或签名密钥。源码 ZIP 的 SHA-256 与附件信息随本轮交付记录。

### 仍未完成

- 当前 0.1.6 APK 未能在已有 Android 9 模拟器上安装或运行 instrumentation；拒绝通过卸载旧签名应用来避免潜在数据损失。
- 来电/接听 UI、通话生命周期产品化，以及真实双端音频/视频连接、摄像头/麦克风权限与媒体质量验收。
- 整体网页 UI 对齐与视觉回归；真实线上聊天、服务端和音视频端到端验证（本轮明确不访问线上服务）。
- 正式 release key 签名；当前仅为 debug 测试 APK。

## 0.1.7 incoming-call UI and accept path — 2026-10-28

- Application source commit: `65ca69fd088abdd479a332b88d1e8e306f62b4cb` on `main` (parent baseline `6bfb5d4f8416684713889edd3a62c0e74c120d1e`, which records the 0.1.6 source/APK relationship). This code was built from that exact worktree state. It adds incoming call-state polling and WebSocket refresh, invitation eligibility via the C++ engine, accept/join by the observed call ID, decline, in-call mute/camera controls, and remote video in an in-chat panel. `NativeCallSession` still performs Java/WebRTC networking and media work; the C++ core validates call snapshot structure and determines which invitation is eligible for this session. It does not implement the WebRTC media stack.
- Local contract comparison: `src/sfu.ts` defines `call-state`, `call-join`, and `call-decline` behavior and the snapshot fields; `public/call.js` supplies the webpage's polling cadence, recipient eligibility and panel controls. The Android work follows those existing contracts. No live service, Cloudflare configuration, deployment, release, or remote test data was touched.
- Host regression: GCC 12.2/CMake, `-Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer`; CTest 1/1 passed and `engine_tests` reported 89 native assertions, with no sanitizer report. New cases cover targeted invitation eligibility, other-guest exclusion, group join visibility, joined-call state, and malformed call id/type rejection.
- Android build: JDK 17 / Gradle 8.9 / SDK 35 / NDK 27.2.12479018. `ANDROID_HOME=/home/user/android-sdk ANDROID_SDK_ROOT=/home/user/android-sdk ./gradlew --offline --no-daemon --max-workers=2 :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` succeeded (71 tasks; 23 executed, 48 up-to-date). This builds the app and instrumentation APK and runs lint; it does not run instrumentation on a device.
- APK metadata: `com.lyrnox.qingliao`, version `0.1.7` / code 8, minSdk 26, targetSdk 35, ABIs `arm64-v8a` and `x86_64`. `apksigner verify --verbose --print-certs` confirms APK Signature Scheme v2; it uses the Android debug key, not a release key. Size 29,099,047 bytes; SHA-256 `904614b01f837d91dfa4865fc825171f34eb93b259e70abfada3c2e1380ce96d`.
- Instrumentation source contains four new JNI invitation cases; `assembleDebugAndroidTest` compiled/package-built them, but they were not executed. No Android installation, launch, visual/UI or real two-device audio/video acceptance occurred. The builder has the Android SDK but no `adb`, emulator binary or `/dev/kvm`; a read-only inspection of the separately authorized isolated VPS test environment likewise found no `adb` or `/dev/kvm`. We did not uninstall the existing 0.1.1 app, clear its data, or start a resource-intensive software emulator.
- Scope remains partial: this does not establish actual incoming-call delivery/acceptance, real bidirectional audio/video, or complete webpage UI parity. The call panel and controls are a native implementation slice; no call flip-camera control or elapsed timer was added. Hardware permissions, lifecycle behavior and remote video rendering still require Android device/emulator verification. This is not a completed G29 acceptance.

## 0.1.8 原生聊天导航与主屏交互切片 — 2026-10-28

- 从 `main` 的 `959faecc50c301f59a4e0d5917bc2aec7a77e26f` 继续实现；按网页当前的暖白/浅绿配色与聊天结构，在原生主聊天屏加入可展开房间抽屉，包含公共大厅、房号/在线人数、房间链接，以及大厅切换、房号加入、创建私密房间、成员列表、复制链接和昵称修改入口。房间创建/加入沿用仓库已有 Android 处理与服务端契约，不仅是静态页面。
- 主屏增加语音/视频快捷入口，仍走既有 Android 权限申请及 WebRTC 启动路径；抽屉和按钮沿用自定义原生 View，不引入 WebView、额外 Activity 或 C++ 网络实现。Java 继续负责 Android UI、权限、OkHttp/WebRTC 生命周期和媒体接口；C++ Engine 继续负责 JSON 协议检查、消息状态/排序/去重及通话邀请资格判断，本轮没有改动 C++ 代码。
- 本轮本地验证：Android `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 完成；当前版本仅改 UI 的 Java 文件和版本号，所以重新运行主机端 C++ ASan/UBSan 测试，CTest 1/1 成功、89 项断言通过，无 sanitizer 报告。Android instrumentation APK 构建成功但没有在设备执行。
- 这是一段主要聊天屏/导航的实际 UI 改造，不代表全站与网页完全对齐。未做本地 Android UI 截图、设备安装启动、线上聊天或真实双端通话验收；没有访问线上服务、Cloudflare 配置/数据、部署或发布。`deliver/` 是原有未跟踪目录，保留且未纳入修改。
- 源码提交：`ace4595d04dc697d0b7085f03afdae06c22e8c4f`，直接推送到 `main`，未建 PR。该提交本地产生的 Debug APK 为 `com.lyrnox.qingliao` 0.1.8 / versionCode 9，minSdk 26、targetSdk 35，SHA-256 `a485731959dce2ca8f1c54762985137855f8c11f71361a9ef4c317f988c59786`；通过 APK v2 签名校验，使用 debug key，非发布签名。GitHub Actions 手动运行 `40681940520` 已成功（针对上述源码提交，build/lint 与两个 artifact 上传步骤均通过）：[运行记录](https://github.com/lyrnoxfan/cloudflare-anon-chat/actions/runs/40681940520)。下载的 APK 元数据为 `com.lyrnox.qingliao` 0.1.8 / code 9，Actions 提供的 `SHA256SUMS` 与产物校验得到 SHA-256 `6da00c09ba2675ee07320f40b9d66858a5d38fcbbf40e98214f96f7765138e29`。Actions APK 与本地重建 APK 的哈希不同（CI: `6da00c…`；本地: `a48573…`），这是两次独立构建的产物，不能当成二进制逐字节相同；两者均来自同一源码提交。该 workflow 明确不做设备验收；测试报告 artifact 仅含 lint 证据。

## 0.1.9 原生聊天回复与消息分组 — 2026-10-28

- 本轮延续 `main` 上 0.1.8 房间抽屉/房号加入与创建/成员列表/复制链接/通话入口，不替换既有安装与数据。新增原生消息长按菜单中的“回复”闭环：显示回复对象与截断预览、可取消、输入正文后按网页约定把 `回复 昵称：引用文本\n正文` 一并发送到现有 `/api/r/{room}/send`，成功后清空回复草稿；正文失败/超长时仍保留可重试输入。回复前缀沿用网页的 160 字符截断约定。
- Android 消息列表现在按网页规则合并相邻气泡：同一发送者归属、同昵称、同本地日历日且相隔少于 5 分钟；分组首尾使用对应圆角和间距，并只在组尾显示时间。主要代码仍是单 Activity + 原生 Fragment/View；Java 承担视图、网络和 WebRTC，C++ Engine 继续负责既有协议/状态校验，本轮未夸大或迁移其职责，也没有修改 C++ 源码。
- 已核对 `public/app.js` 中网页回复文本格式与消息分组条件，并对照 Android 实现；这只对齐了这些明确行为，不等于整套网页视觉、功能或通话体验完全一致。已有 0.1.8 房间/通话 UI、WebRTC 信令/媒体代码仍需真实设备及双端测试。
- 本机 JDK 17 / Gradle 8.9 / Android SDK 35 / NDK 27.2.12479018 / CMake 3.22.1 执行 `ANDROID_HOME=/home/user/android-sdk ANDROID_SDK_ROOT=/home/user/android-sdk ./gradlew --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest` 成功（73 个 Gradle task，11 执行、62 up-to-date）。Java 应用、Android instrumentation APK、JNI/C++ 两种 ABI 都构建成功；lint 无 error。`testDebugUnitTest` 是 `NO-SOURCE`；instrumentation APK 编译成功，但因当前 `adb devices -l` 无设备、构建机无 Android emulator 可执行文件而未运行。此前工作树 QA 包对应 Android 9 隔离模拟器的 instrumentation 命令没有可恢复结果，不将它计作通过。没有卸载或清除 `com.lyrnox.qingliao` 0.1.1，也未改 `deliver/`。
- 另用 JDK 直接编译并执行这次真实生产源码 `MessageGrouping.java` 与 `ReplyDraft.java` 的主机测试，10 项断言通过，覆盖五分钟边界、发言人/本人边界、引用格式/正文、取消回复、媒体回退和长度截断。C++ 原生测试以 GCC `-Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer` 直接构建运行，89 项断言通过，无 ASan/UBSan 报告。该结果不代替 Android instrumentation/视觉或联网验收。
- 当前本机 `app-debug.apk` 为 `com.lyrnox.qingliao` 0.1.9 / versionCode 10，大小 29,099,047 bytes，SHA-256 `f90393cd421dedc41c484a55d4b03ddc02c441307b70e3ade2ac54b1525021df`；`apksigner verify --verbose --print-certs` 与 `aapt dump badging` 均通过。它由 Android Debug 证书签名，仅是 debug 侧载包，不是 release 签名。AndroidTest APK 为 32,352 bytes，SHA-256 `303a8e303f812053e14134b986c74af946665d689a9517ea920e583f2d850da7`。
- 设备和服务边界：当前 build computer 的 `adb devices -l` 为空且无 emulator。已有用户截图中的 Android 昵称页报告无法解析 `chat.lyrnox.com`；此轮禁止线上访问/部署，故没有启动联网或声称已修复 DNS。未验收本轮主界面实机外观、线上登录/消息、真实摄像头/麦克风、双端 WebRTC 通话、完整网页 UI/功能对齐或 Android 真机行为。没有 Cloudflare 生产或在线聊天室访问/部署，也没有 GitHub Release。
- 应用源码提交 `225f3c02d009ce319c3b430536730dfbee0b0bb5` 已直接推到 `main`，没有 PR。GitHub Actions 手动运行 `40686379701` 成功，checkout 的 `headSha` 与该源码提交完全一致；构建/lint 与 APK/checksum、lint 证据 artifact 上传步骤均通过：[运行记录](https://github.com/lyrnoxfan/cloudflare-anon-chat/actions/runs/40686379701)。
- 从此次 Actions 下载并核验的 APK 为 `com.lyrnox.qingliao` 0.1.9 / versionCode 10，大小 29,099,079 bytes；artifact `SHA256SUMS` 与重算结果一致，SHA-256 为 `c9bf84d2475be66d2832dcedd2b8acbc620d1111f317b1d5ac6abd7000d2a2d5`。`aapt dump badging` 与 `apksigner verify` 核实包元数据及 v2 debug 签名。Actions APK 是最终侧载交付文件；本地独立重建的 APK 哈希不同，故不混用。它仍是 Android Debug 证书签名、非正式 release 签名。
- 源码 ZIP 从 `main` 的 Android 子树导出，附验证记录，排除 `deliver/`、历史 APK 发布目录、机器配置/缓存和凭据；压缩包完整性检查通过。它基于 app 源码提交 `225f3c0`，随后只为记录 Actions 收据更新了验证文档，未改 app 源码。
