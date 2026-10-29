# Android / 网页行为、视觉与验收边界

本对照针对仓库当前网页源码 `public/index.html`, `public/style.css`, `public/app.js`, `public/call.js`，不是按旧截图或已发送过的阶段性 APK 推断。Android 是原生 Fragment/View 客户端；目标是覆盖相同用户流程并匹配当前移动网页的主要结构与色板，不宣称逐像素复制或内部架构完全相同。

## 0.2.9 集中收敛范围

本批更改集中处理多人视频通话中 Android 原先只保留一个远端视频轨道、覆盖前一个参与者的问题。现在按远端成员 ID 管理轨道和渲染器，通话画面按成员铺排并显示昵称；本轮 SFU 验收将使用三个 Android 客户端，要求每端收到两条不同参与者的视频轨道，实际 Activity 也检查两个远端画面及其昵称。此改动与前版页面布局/房间图标调整合并在当前基线之上，不采用逐项发 APK 的交付方式。

## 功能对照

| 当前网页功能/来源 | Android 原生实现 | 当前结论和证据边界 |
|---|---|---|
| 匿名会话、昵称、会话恢复和授权错误（`app.js` session / `enter` / `handleAuth`） | `MainActivity` / `ChatFragment` 会话页、会话 cookie 与授权状态 | 同一服务 API；最终集中验收中本地 Worker/QA 会覆盖服务与 Activity 流程；它不是生产服务部署验证 |
| 公共大厅、私人房间创建/口令加入、房号/成员信息（`index.html` dialogs、`app.js` `openPrivate` / `switchRoom` / `renderMembers`） | 房间抽屉及原生表单、成员与房间状态、通话对象选择 | 功能路径对应；原生系统返回/对话框和网页模态框行为不同。房间 UI 实际验收以该冻结版本报告为准 |
| WebSocket 实时消息、断线恢复、历史分页/重新同步、日期及连续消息分组（`app.js` `connect` / `history` / `resync` / `renderMessages`） | OkHttp WebSocket、重连/恢复、C++ 消息索引、Android 列表 | 协议和房间服务一致；UI 布局适应窄屏。逐像素、极端长历史与各网络环境未被同一次验收穷尽 |
| 文本发送、重试、引用/复制/删除、表情（`app.js` composer / message menu / emoji assets） | 原生输入、待发送/重试、引用草稿、长按操作、emoji | 行为语义对应；输入法、无障碍焦点和长按手势使用平台实现，视觉细节不同 |
| 图片缩略/懒加载/原图灯箱、视频内联及放大、音频进度控制（`renderMessage` / lightbox） | 授权媒体下载、图片查看器、`VideoView` 全屏/内联、`MediaPlayer`/SeekBar | 媒体内容和控制用途对应；Android 视频先下载再播放，网页可直接加载授权媒体 URL；格式、网络中断和画质无法由 UI 对照代替 |
| 文件上传/下载、图片/视频/音频保存（`file` input、`mediaURL`、附件卡片） | Android 文件选择器、上传 API、授权下载及 `ACTION_CREATE_DOCUMENT` 保存 | 系统 picker 与网页 picker 有平台差异。验收须报告服务操作和真实保存结果；历史通过不计入当前版本 |
| 语音录制并预听/发送/取消（`record` / `recorder` controls） | 原生 `MediaRecorder` 录制与发送/取消 | 核对录制 UI、编码和权限流程；系统授权/音频焦点与网页 MediaRecorder 不同 |
| 一对一/多人语音或视频，邀请、接听/拒绝、静音、相机开关、切镜、退出（`call.js`） | `NativeCallSession`、WebRTC/SFU、通话面板与相机/麦克风权限 | 本次扩展为每个远端成员单独保留视频渲染器，适配多人而不覆盖画面；三客户端 SFU 通过后只证明模拟器上的三方媒体轨道与控件，不代表真机质量 |
| 参与者视频布局与名称（`call.js` `localView` / `ontrack`，`style.css` `#videos` grid） | 原生本地及远端视频格，`FrameLayout` 昵称图注；多人使用两列网格 | 结构语义匹配；小屏固定高度及两列切分不会与网页 CSS 每种窗口尺寸完全一致，详见最终截图/验收报告 |
| 色板、顶栏、消息气泡、composer（`style.css` 当前主题及移动断点） | 0.2.7 已按当前网页移动布局调整间距；0.2.8 房间图标改为网页的描边气泡图标；Android 原生控件继续保留 | 当前网页是浅绿/暖白主题（如 `#edf0e7`、`#f8faf3`、`#d7e5bc`）；10/27 旧蓝白截图不是当前基线。截图相似不等于全页面视觉/行为对齐 |
| 后台通话、锁屏与媒体中断 | 依 Android 生命周期与音频焦点清理摄像头/麦克风/WebRTC | Android 主动停止后台采集，不承诺网页式后台响铃或持续通话；CI 模拟器无真实安全锁体验 |

## 当前架构差异与不能声称的完成项

Android 是原生 Java/Kotlin Activity/Fragment/View + WebRTC，不是 WebView。核心 UI/网络/通话大部分仍由 Java 管理，C++/JNI 主要承担既有协议状态和辅助逻辑；它没有做到“以大型 C/C++ 核心承载全部实现”的架构目标。该目标需要重新界定与单独规划，不能以流程覆盖或测试通过冒充已实现。

实体设备摄像头/麦克风与音频质量、听筒/扬声器/蓝牙路由、弱网端到端、带真实 PIN 的锁屏/OEM 中断、完整网页视觉/行为逐项对齐均仍需实际设备或逐页场景验证。Android 系统文件选择器、媒体授权/保存及后台限制会保留平台差异。Cloudflare Worker、D1、R2、Durable Objects 生产环境及配置在本轮未部署/修改；不把模拟器或本地隔离服务报告成生产验收。

## 验收纪律

当前批量改动完成后，`android.yml` 对冻结 commit 做一次集中构建、lint、C++ sanitizer、JNI、本地 Worker/服务与 Activity/UI/文件流程验收；`android-sfu.yml` 对同一 SHA 的隔离 QA build 做三台 Android 10 模拟器组通话验收。两条 workflow 的成功与附件 hash 才可支持“该提交通过”结论；不能用版本存在、动作被调度或某一测试层通过代替整体结果。确切 SHA、运行号、APK/源码/测试记录和限制见随包验收报告。
