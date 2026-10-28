# 轻聊 Android 0.2.4

轻聊的原生 Android 客户端，直接调用仓库中的 Cloudflare 聊天服务；不是 WebView 包壳。此版在网页已有聊天行为上补齐系统深色配色和聊天消息内的视频播放器，并保留 Android 原生导航、输入、媒体权限和系统文件流。它不声称与网页逐像素一致，完整对照和未验收项见 [G29 parity / validation](G29-PARITY.md)。

## 架构

- 单 `MainActivity`；`ChatFragment` 以 Java 动态创建 Android View，无 XML layout。`MessageBubbleView` 在 Canvas 绘制气泡；文本/辅助功能使用原生 View。
- C++17 Engine/JNI 处理消息协议 JSON、身份与会话状态、消息索引/去重/稳定排序、删除墓碑、成员状态、输入/上传限制、退避重连、原生文本发送请求及响应校验。Java/OkHttp 负责 TLS、Cookie、WebSocket、异步 I/O、系统选择器和平台权限。C++ 并不实现 TLS、Android 权限或 WebRTC 媒体编解码。
- WebRTC SDK 用于本机媒体采集与远端媒体轨显示；C++ 校验信令契约。服务端接受信令不代表 ICE 或音视频已连通。
- 匿名 Cookie 以 Android Keystore AES-GCM 加密保存，系统备份关闭。消息仅留在内存并从服务端取回；媒体临时缓存位于应用私有目录，不是端到端加密存储。

## 原生端目前实现

- 昵称创建/修改、公共大厅、私房创建、按房号和口令加入、房间链接、在线成员、WebSocket 文本收发和历史分页。
- 消息分组/日期分隔、引用回复、复制/删除自己的消息、表情、未读跳转、加载更早消息。
- 图片上传、消息内缩略图/全屏预览；录音和音频进度播放；视频消息内播放及全屏播放；普通文件保存。下载走服务端授权路径，限制缓存大小并在播放器停止、切房或离开页面时清理临时视频。
- 原生语音/视频呼叫、接听控件、摄像头/麦克风采集以及远端视频视图已实现代码路径，但是否能在设备和当前服务上完整工作，仍以 G29 逐项验收说明为准。
- 页面依据 Android 系统深浅色模式使用对应的网页色板；用户仍可使用原生抽屉及布局，不是网页 DOM 的像素级复制。

服务端仍是唯一授权来源：私房口令不写入持久存储；客户端限制不替代服务端校验；不能删除他人消息。上传前会确认目标房间。应用默认连接 `https://chat.lyrnox.com`；不会关联网页匿名身份，两边使用各自的匿名 Cookie。

## 安装与兼容

- Android 8.0 / API 26 及以上；compile/target SDK 35。
- APK 包含 `arm64-v8a` 与 `x86_64`，不含 32 位 ABI。
- Debug-key 测试签名包，可侧载；不是 Play 发布包。不能把 debug key 当正式签名。
- 语音录制/通话需要麦克风权限；视频通话需摄像头权限。拒绝权限不影响文字聊天。文件选择与保存用系统 Storage Access Framework，不请求全盘存储权限。

## 构建与测试命令

依赖 JDK 17、Android SDK platform/build-tools 35、NDK 27.2.12479018、CMake 3.22.1；首次联网解析 Gradle、OkHttp 与 WebRTC SDK 依赖。

```sh
cd android
# 设置 ANDROID_HOME；按需创建不提交的 local.properties
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
```

宿主 C++ 测试：

```sh
cmake -S app/src/main/cpp -B .native-tests \
  -DCMAKE_CXX_FLAGS='-Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer'
cmake --build .native-tests
ctest --test-dir .native-tests --output-on-failure
```

默认 Android JNI instrumentation 不联网。若启用真实服务集成测试，需明确传 `-e network true`；它会在测试服务写入独立身份、房间和消息，不能用于生产环境。测试范围、证据和限制见 G29 文档。

## 边界

- 媒体解码依设备支持；编码不支持时可保存源文件。Android 端先安全取得授权媒体再在本机解码，尚未验证与网页浏览器的视频播放在流式/弱网/编码边界上的等价性。
- 无后台推送、离线数据库或离线发送队列；应用离开前台后断开实时连接，返回时补取历史。
- 没有 32 位 ABI。公开视频素材许可仍须在公开分发前核实，见 [THIRD_PARTY.md](THIRD_PARTY.md)。
