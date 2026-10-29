# Android / 网页实现对照与集中验收

> 以下 0.2.4 对照与验收记录是历史，不是当前 0.2.5 的状态。0.2.5 已加入可运行的 Activity、真实本地 Worker 和双原生 SFU 验收工作流；最终结论请读本次交付的 `ACCEPTANCE-0.2.5.md`，不把历史失败或旧版通过当作当前结果。

此文件描述本次冻结版本 `0.2.4` 的代码覆盖及实际测试边界。网页端为仓库 `public/index.html`, `public/style.css`, `public/app.js`, `public/call.js`；Android 为 `app/src/main/java/com/lyrnox/qingliao/`。原生 UI 不承载网页 DOM，因此“对齐”指主要交互和视觉语言的原生实现，不是逐像素相同。

## 对照结果

| 网页行为/视觉 | Android 代码对应 | 当前结论 |
|---|---|---|
| 匿名昵称门、房间/大厅、房间创建和密码加入 | `ChatFragment` 会话门、抽屉、加入/建房表单 | 主要流程已实现；需运行真实服务回归确认 |
| WebSocket 实时消息、历史分页、断线补历史 | 原生 `ChatNet`、`ChatFragment` 与 C++ `NativeCore` | 已有实现；本轮构建/JNI mock 不能证明真实服务端到端 |
| 消息昵称/时间、连续分组、日期分隔、空大厅 | `MessageAdapter`、`MessageGrouping`、`MessageBubbleView` | 代码已覆盖；需要设备实看布局与无障碍行为 |
| 文本及表情、长按操作、复制/引用回复/删除本人消息 | `MessageAdapter`、系统弹层、原生消息协议校验 | 代码已覆盖；真实跨端消息/删除未在本轮联网验收 |
| 图片卡片、授权下载、内联缩略图和全屏查看 | 原生图片加载、缓存及 lightbox | 已实现；编码、尺寸与设备滚动表现需设备验证 |
| 网页内音频控制与进度 | 原生 `MediaPlayer` 播放、进度和拖动 | 已实现；真机编码/后台中断行为未验证 |
| 网页内 `<video controls playsInline>` 与放大视频 | 新增原生聊天内 `VideoView` 播放区域、系统媒体控制器及单独全屏入口 | 代码已补齐主要播放入口；播放器依本机编码支持且先下载授权文件，弱网和流式行为未证明等同网页 |
| 普通文件卡及保存 | 原生授权下载及系统保存选择器 | 已实现；真实服务文件下载/保存流程未在本轮验收 |
| 未读提示、回到底部、成员和通话控件 | 原生未读按钮、成员面板、通话视图 | 代码已覆盖；不能仅以布局存在证明真实通话完成 |
| 系统浅色/深色模式 | 新增 Android `values-night` theme 与网页深色色板 | 代码已覆盖；本轮无可启动 Android UI，未目视验证颜色、对比度和主题切换 |
| 网页桌面/手机自适应布局 | Android 原生抽屉、会话页、输入/消息布局 | 采用原生布局，不复制网页桌面两栏；触控/窄屏需实体设备验收，不宣称像素级一致 |

### 本轮代码改动

- `ChatFragment.java`: 深色调色板映射、视频消息内嵌播放与全屏入口、唯一活动播放器与临时文件清理、切房/离开/视图销毁和列表项脱离时的播放器生命周期处理。
- `MessageBubbleView.java`: 气泡颜色改为由当前浅/深色调色板传入。
- `MainActivity.java`, `res/values-night*/styles.xml`: 系统栏/主题夜间配色。
- `app/build.gradle`: version `0.2.4`, versionCode `15`。

## 集中验收记录

本节需在所有实现完成、源文件冻结后填写。至少记录确切 source commit、Android build 与 lint、host C++ ASan/UBSan、JNI instrumentation 是否实际运行、APK/Test APK 元数据与 SHA-256、设备/模拟器 UI 结果。若 Android runtime 不可用，明确记录，不得用构建或断言声称完成 UI/服务验收。

### 尚需真实设备/服务验证的边界

- 真实服务账号下，昵称、房间/口令、消息收发/历史、跨端删除、附件上传及授权下载。
- 安装后浅色/深色的实际外观、窄屏输入法/滚动/抽屉、视频内嵌播放器布局和媒体控制。
- 真机麦克风/摄像头权限、音频路由、来电/接听、双设备 WebRTC 音视频双向连通。
- 视频文件先下载再播放与网页内联流式播放的弱网/编码兼容差异。

本次没有授权触达或部署 Cloudflare 生产环境；代码测试用隔离本地 fixture/离线检查，不构成真实服务验收。无设备证据时仍保持项目开放。

### 0.2.4 冻结版本集中验收 — 2026-10-28

- 应用实现冻结于 `d237ada76e610bb8bab43a4513d347b5eb758538`（基线 `2220896bed5571ae20d4833b79a09e05e6527c06`），并已推送 `main`。本轮代码补齐系统深色配色、聊天内视频播放及播放器清理；完整对应关系见上表。源码在测试前冻结；随后本轮只补验证记录与交付文件，没有改 app 代码。没有发布 Release、部署或访问 Cloudflare 生产。
- 冻结源码的一次集中 QA：JDK 17 / Gradle 8.9 / SDK 35 / NDK 27.2.12479018 运行 `assembleDebug assembleQa assembleQaAndroidTest lintDebug lintQa`，BUILD SUCCESSFUL（114 tasks）；debug 与隔离 QA APK 均构建，QA instrumentation APK 已编译，debug/QA lint 通过。GCC/CMake C++ host test 在 ASan/UBSan 下 CTest 1/1 通过；`git diff --check` 通过。完整命令输出保存在 `deliver/g29-final/qa.log`。没有在设备运行 Android instrumentation；因此不把此前版本的 50 项断网 JNI/mock 检查算作本次冻结版测试。
- Android UI 烟测仍未到聊天页。之前隔离模拟器启动卡在 System UI；后续构建环境无 `/dev/kvm`，关闭硬件加速的 Android 9 AVD 仍未启动且 ADB 请求超时。无本轮截图、安装运行或页面/视频视觉验收。设备/模拟器和真实服务的剩余边界见下文。
- 本地 Debug APK：`com.lyrnox.qingliao`，`0.2.4` / versionCode 15，29,131,991 bytes，SHA-256 `e3a7e4df8214dc3a5e20deb1edd31591bb789a17c5fc0ce17abbf73a8f80f474`。隔离 QA APK：`com.lyrnox.qingliao.qa`，`0.2.4-qa` / versionCode 15，29,131,991 bytes，SHA-256 `e35d68d5450a46df469afca09a7e0b7d33e29b18093917c5c7f2bd3061e8e08a`。两者均为 Debug key 签名、非 Release。QA APK 使用本地测试配置，不作为线上安装版。Instrumentation APK `com.lyrnox.qingliao.qa.test`，33,404 bytes，SHA-256 `e69e1e01a7ba52c1589d51883829b1941bd1dbe4379926f732acf13cb54a74be`；仅确认编译成功，未运行。
- 完整网页视觉/功能 parity、实机主题及布局、视频编解码/弱网、真实服务聊天及上传下载、相机/麦克风权限和双端 WebRTC 仍需 Android 设备与真实服务验收。代码覆盖不等于已实测；本次不能称为完整网页对齐版，G29 保持开放。
