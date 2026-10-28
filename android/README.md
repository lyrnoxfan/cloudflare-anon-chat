# 轻聊 Android 0.1.3

现有 Cloudflare 聊天室的独立原生 Android 客户端。基于仓库 main `ec77d86412ff10afdf6ebdd61eb716c2f1a5a7ca` 的实际 API 实现；未改网站、Worker、D1、R2、域名或线上配置。不是 WebView 包壳，不内嵌网页。

## 架构

- 单 `MainActivity`，自定义 `ChatFragment` 管理前台生命周期；布局由 Java 动态组装，无 XML layout。`MessageBubbleView` 在 Canvas 绘制胶囊气泡，文字与辅助功能沿用原生 TextView。
- C++17 `Engine` 负责协议 JSON、消息索引/去重/稳定排序、删除墓碑（拒绝旧历史复活）、在线成员、输入/上传限制、UTF-16 长度边界、退避重连策略，并校验服务端会话响应（UUID、昵称、有效期）及管理未知/需昵称/有效三态。会话态决定原生聊天内容与无障碍树是否开放。不是仅供演示的空 JNI。
- JNI 仅传递不透明句柄和有上限的 JSON 字符串，显式严格转换 UTF-8/UTF-16，RAII 管理核心，Java/C++ 两侧串行化访问，异常转换为受控错误。没有裸指针暴露给 UI。
- Java/OkHttp 负责 Android TLS、Cookie、WebSocket、异步 I/O、系统文件选择、Keystore、麦克风与媒体解码。没有为增加 C++ 比例而把 Android 权限/TLS 绕过到不安全的 native 网络层。
- 前台心跳、断线退避、回前台补历史，房间代际检查阻止旧请求写进新房间。匿名 Cookie 以 Android Keystore AES-GCM 加密保存；关闭系统备份。消息/媒体仅位于进程内存与应用私有缓存，缓存文件不是端到端加密存储。

## 已编写的功能

昵称创建/修改、公共大厅、私房创建、房号/口令加入（支持单字符非空口令）、房间链接、在线成员、WebSocket 文本收发、历史分页、文本幂等重试、长按复制/引用回复/删除、参考表情面板及正文图片表情、系统文件/图片/视频选择和上传、AAC/M4A 语音录制、授权下载、原生图片查看/音频视频播放与系统文件保存。

服务器仍是唯一授权来源：私房口令不写入持久存储；前端限制不替代服务器校验；客户端无法删除他人的消息。文件上传前有明确目标房间确认。

## 安装与兼容

- Android 8.0 / API 26 及以上；target/compile SDK 35。
- APK 同时包含 `arm64-v8a` 和 `x86_64`。不包含 32 位 ARM/x86。
- 交付的是标准 Android debug-key 签名测试包（可侧载），不是 Play 商店正式发布包。不要将 debug key 当成发布签名；正式发布需所有者提供/自行保管 release keystore。
- 默认连到 `https://chat.lyrnox.com`。不会自动关联网页浏览器匿名身份；两边拥有不同匿名 Cookie，删除权也各自独立。
- 仅录音时请求麦克风权限；文件通过 SAF 选择/保存，不请求全盘存储或通讯录权限。

## 构建

需要 JDK 17、Android SDK platform 35 / build-tools 35.0.0、NDK 27.2.12479018、CMake 3.22.1，可由 Android Studio SDK Manager 安装；首次联网拉取 Gradle/AGP/OkHttp。

```sh
cd android
# 配置 ANDROID_HOME，或自行创建 local.properties 的 sdk.dir（不提交）
./gradlew assembleDebug lintDebug
# app/build/outputs/apk/debug/app-debug.apk
```

原生主机测试（不需要 Android SDK）：

```sh
cmake -S app/src/main/cpp -B .native-tests \
  -DCMAKE_CXX_FLAGS='-Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer'
cmake --build .native-tests
ctest --test-dir .native-tests --output-on-failure
```

设备 JNI 测试（不联网）：

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.lyrnox.qingliao.test/com.lyrnox.qingliao.NativeInstrumentation
```

显式开启真实测试站集成测试：给 instrument 增加 `-e network true`。它创建两个独立测试身份和一个全新私房，不读取既有私房、不操作他人消息；验证错误口令、WebSocket 收发、幂等、历史、删除同步与真实图片上传/授权下载字节比对。测试完成会删除测试身份自己的文本/图片；服务没有删房接口，测试私房及匿名记录会保留。不要在不允许写测试数据的服务上运行此模式。

## 重要边界

- 实时音视频通话没有实现，界面没有假通话按钮。它需要单独接入原生 WebRTC/SFU 并验证双向媒体，不把文件视频播放当成通话。
- 没有后台服务/推送；退后台断开实时连接，回来补历史。不是始终在线客户端。
- 没有消息数据库/离线发送队列；退出进程后重新取服务器历史。上传超时不自动重试，以免未知提交状态造成重复；请先看历史再决定是否重新发送。
- 语音最长 2 分钟；媒体播放受系统编码器支持约束；不能保证所有 WebM/视频编码跨设备可播，可保存原文件。
- 房间是服务端口令授权，不是端到端加密。清除应用数据会失去匿名身份和相应删除权。
- 当前原生布局沿用暖白浅绿/圆润方向，空大厅增加了与网页一致的安静提示；但不是网站逐像素复刻，头像/连续消息分组、深色主题仍未实现。没有伪造头像或在线人数。
- 表情图片沿用仓库 `public/emoji-ref`；公开分发前应确认原始素材许可。依赖许可见 THIRD_PARTY.md。

实际执行结果与未验证范围见 VALIDATION.md；构建成功不等于真机验收。
