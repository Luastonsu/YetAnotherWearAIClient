# Yet Another Wear AI Client - YAWAC

面向 Android 智能手表的 AI 对话客户端，当前开发版 **1.33 / versionCode 34**，包名 **`com.yawac.client`**。继承 WearAIChat 1.19 的圆屏交互、历史记录、Markdown、阅读页及停止/重试机制。当前实机基线为 Android 11 的 OPPO OPWW234，尚未完成公开发行。

**交给其他 agent 继续开发：请先阅读 [当前接力文档](HANDOFF.md)**（2026-11-08 更新），包含源码导航、用户约束、构建/安装、数据兼容、验证边界及后续路线。

1.33 统一二级页面右滑返回：前景页面跟随手指、不淡出，上级页面保留在下层；短拖回弹，长拖或快速甩动返回。聊天选项和确认框优先关闭当前浮层，调试详情采用真实 Activity 返回栈。[实现与验证](docs/yawac-1.33-swipe-back.md)。

主页第二屏新增 **调试模式**：默认关闭，开启后记录新请求的上下文、搜索决策与关键词、原始搜索结果、实际参考资料、响应、服务商公开返回的思考内容和耗时。记录只在本机保存，可查看、导出和清除；有容量上限和明确截断提示。使用及 ADB 导出见 [1.32 调试说明](docs/yawac-1.32-debug-mode.md)。

1.31 的缓存、增量刷新与字体加载改动已保留；此前 OPPO 实测冷启动约 1.53s → 1.20s，但滚动改善不稳定，仍需优化。[测量及回归边界](docs/yawac-1.31-performance.md)。

从 1.24 起，新版与旧包 `com.wearai.chat` 独立安装、可以共存。新包不读取或迁移旧版的聊天、设置和密钥；首次使用需要重新填写 API 配置。以后升级 YAWAC 使用 `com.yawac.client`。

**首发范围（2026-10-17 用户确认）：仅 OPPO 手表应用商店。** UI、系统输入法、手势及实机回归优先适配 OPPO 手表；其他品牌和 Google Play / Wear OS 的专门适配留待后续。具体首批支持的 OPPO 机型与系统版本以验证结果和渠道要求确定。

## 使用自己的 AI 服务

首次打开先显示用户协议与数据说明，同意后进入真实操作引导：右滑设置 → 打开供应商设置并返回 → 左滑主页 → 新建练习对话 → 打开/关闭聊天选项 → 右滑回主页。动画提示会等待实际操作，支持跳过和中断恢复，不会自动发起 AI 请求。旧的功能简介已移除；旧版的“已看过简介”不等于同意新协议。已有聊天和 API 配置保留。

1. **在主页向右滑到第二屏「设置」**，打开 **模型供应商设置**。
2. 使用 DeepSeek 预设，或填入兼容 **OpenAI Chat Completions** 的 HTTPS URL。
3. 输入自己的 API Key 和模型 ID，保存后开始聊天。

URL 可填基础地址、带版本的地址或完整端点，只在缺少后缀时追加 `/chat/completions`：

```text
https://api.deepseek.com
https://your-provider.example/v1
https://your-provider.example/proxy/v1/chat/completions
```

DeepSeek 预设 URL 为 `https://api.deepseek.com`，模型列表为 `deepseek-flash`、`deepseek-v4-pro`，也可手填其他模型 ID。列表按 2026-10-16 的 [DeepSeek 官方接入文档](https://api-docs.deepseek.com/guides/codex)整理，实际可用性以服务端和账户权限为准。

| 功能 | DeepSeek 官方地址 | 其他兼容服务 |
| --- | --- | --- |
| 普通文本对话 | 支持 | 支持 |
| 自动标题 | 使用选定模型 | 使用选定模型 |
| 思考强度滑条 | 关闭 / 低 / 高 / 最高 | 本版禁用 |
| 联网辅助 | 按需规划 + DeepSeek 独立 Search API | 本版禁用 |
| 停止、重试、错误码 | 支持 | 支持 |

通用请求使用 Bearer 认证、`model`、`messages`、`stream=false`，解析 `choices[0].message.content` 文本。暂不支持 Responses、通用 Anthropic、多模态、工具执行、流式输出、动态模型发现或无密钥服务。

下一次对话、重试和标题请求读取新配置；进行中的请求使用原配置快照，搜索与回答不会中途切换服务。切到自定义服务后，旧的联网/思考失败消息需要切回 DeepSeek 才能按原模式重试，也可关闭模式重新提问。

聊天选项中的思考滑条连续跟手、松手吸附，并有按压和档位渐变动画。低/高/最高分别传入 `reasoning_effort=low/high/max`。选择会保存；每条消息记录自己的档位，原位重试沿用原档，等待中改档只影响下一条新问题。关闭思考时保留上次启用档。强度较高可能增加耗时和用量，仍受现有输出/超时限制。详见 [1.26 思考滑条](docs/yawac-1.26.md)。

提问与有效历史直接发送到用户配置的服务。首次对话自动标题额外发送第一条消息并消耗额度；开启联网辅助还增加一次参考需求规划，实际需要资料时再调用搜索。当前没有开发者代理服务器。

开启「联网辅助」后，所选模型先判断是否需要外部事实。给定条件即可完成的计算、推导等通常直接独立作答；需要资料时，从最近上下文解析指代并提炼事实、概念、原理等关键词，避免把整道题当作原题搜索。用户明确要求寻找题目出处时允许按该意图检索。答案以用户条件和独立分析为主，网页用于核实与补充。

实际检索仍调用 `POST https://api.deepseek.com/v1/tools/search`，使用相同用户 Key 的 Bearer 认证。`query` 按官方限制最多 128 个 Unicode 码点，不再发送 `ori_question`；请求 5 条结果，`need_content=false`，不另行 Fetch。摘要、链接、日期与站点信息去重并限制长度后交给回答模型，完整有效历史仍参与回答。

无可用参考时继续独立分析，同时要求说明无法核实的外部事实；认证、网络、搜索响应错误仍明确报错，停止贯穿规划、检索与回答。开启辅助通常是两次模型请求，需要搜索时另加一次 Search，增加耗时和用量；自动标题另算。规划和答案仍可能判断失误，不能保证每次搜索都恰当。协议依据：[官方搜索指南](https://api-docs.deepseek.com/zh-cn/guides/search/)、[Search 接口参考](https://api-docs.deepseek.com/zh-cn/api/search/)、[JSON 输出](https://api-docs.deepseek.com/zh-cn/guides/json_mode/)。

## 密钥与数据

- 不再从 Gradle 属性或 `local.properties` 向 APK 注入 API Key，不自动继承旧测试包密钥，升级后需自行填写。
- 配置使用 Android Keystore + AES-GCM 加密，原子写入 `noBackupFilesDir/api-config.enc`，不随系统自动备份迁移；重装、换设备需重填。
- 设置页隐藏密钥并阻止截图及密钥视图状态保存；错误展示隐藏当前请求的密钥；禁止自动跟随 HTTP 重定向。
- 聊天仍是私有目录明文 JSON，保留原子写入与备份恢复；Manifest 的 `allowBackup=true` 尚未调整。配置加密不代表聊天已加密。
- 清除 API 配置不删除聊天，也不撤销已发出的请求。

## 已有交互

主页右滑进入设置第二屏，左滑回到对话列表；系统返回键先回到列表。聊天页保留自己的右滑返回，以及联网/思考/停止选项，不放全局设置入口。

主页常驻“右滑打开设置”文字已移除，改用底部双圆点标记页面并跟随拖动过渡：左侧对应设置，右侧对应对话。历史记录新增独立圆角卡片与描边，使用真实 Heytap 按钮承接点击和长按；标题、日期与摘要仍由本项目绘制。

- **模型供应商设置**：URL、Key、模型及 DeepSeek 预设。
- **对话页面设置**：聊天字号 11–19sp；我的气泡和 AI 气泡各有六种预设配色，正文颜色自动适配；实时预览、自动保存及恢复默认。阅读页字号独立。
- **存储管理**：对话/消息数量、聊天及备份占用、缓存占用；清理缓存；二次确认后清空全部聊天、草稿与备份，保留供应商和外观设置。

设置首页与三个子页共用 Heytap 原生按钮桥接及圆弧时钟；组件不可用时回退。主页两屏滑动、列表、编辑输入、预览气泡和存储逻辑由本项目实现，不宣称全部视图都是 OEM 控件。

保留多会话、独立草稿、长按删除、输入法确认发送、停止与原位重试、非流式完整回复、Markdown/表格/LaTeX、长回复阅读、独立阅读字号、历史位置保护和透明圆弧时间。

聊天页新增有限时长的交互动效：发送后输入框收拢成居中停止按钮，结束或取消后展开并恢复草稿；新消息和答案正文淡入上移；聊天选项浮层与回到最新按钮渐变过渡。状态计时局部刷新，不重复播放入场动画。动画关闭时直接切换状态，保留 Heytap 按钮桥接。

1.27 重做输入框与停止按钮的融合/分离：共用连续轮廓、连接处逐渐拉宽或收细，选项图标连续变形成停止图标；弹簧运动支持中途反向并保留速度。输入法焦点变化不再跳过动画，临时暂停后从原形态继续；过渡帧不再反复测量输入文本。详见 [1.27 动效记录](docs/yawac-1.27.md)。

1.28 内嵌用户提供的 MiSans Regular，统一页面、Heytap 文字控件及自绘标签的字体。聊天/阅读分别增加 2dp/3dp 行间距，调整输入框垂直留白；顶部时间改为 12sp，按可见字形对齐到安全区域顶部约 2dp。字体无需在线下载，字体原文件、版权与官方许可随包保留；「对话页面设置」底部注明字体来源。详见 [1.28 字体与时钟记录](docs/yawac-1.28.md)。

1.29 将模型供应商设置页的四个按钮统一为白色 14sp 文字，「保存」与「清除 API 配置」之间增加 12dp 间距，继续使用已有 Heytap 桥接。详见 [1.29 记录](docs/yawac-1.29.md)。

1.30 发送新问题后请求在列表布局完成时对齐到内容底部，避免旧阅读位置覆盖发送后的导航；用户主动拖动时取消待执行导航。回到底部按钮改到输入框上方中央，使用矢量「向下到底」图标，并在长回复尚有下方内容时保持可见。详见 [1.30 记录](docs/yawac-1.30.md)。

Heytap 控件仍通过已安装 OEM 应用运行时桥接，不可用时有自有回退。详见 [原生控件说明](docs/native-ui-integration.md)、可交给其他 AI 项目的 [独立适配与迁移文档](docs/heytap-ui-porting-guide.md)及 [上架路线](docs/release-roadmap.md)。

## 构建与验证

JDK 17、SDK 34、AGP 8.7.3、Gradle 8.11.1。`local.properties` 仅需本机 SDK 路径。

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:testDebugUnitTest --offline
```

首次没有缓存时去掉 `--offline`。APK 位于 `app/build/outputs/apk/`，JVM 报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`。

1.30 按用户要求仅构建 Debug APK 并覆盖安装到 OPWW234，不运行测试、不自动操作聊天或发起 AI 请求；Release 未在本轮重新构建，见 [1.30 记录](docs/yawac-1.30.md)。1.29 的按钮调整与安装见 [1.29 记录](docs/yawac-1.29.md)。1.28 的 70 项 JVM、54 项模拟器及加载真实 Heytap 来源 APK 的 27 项模拟器测试见 [1.28 记录](docs/yawac-1.28.md)；1.27 的 23 项 OPWW234 真机聊天记录见 [1.27 记录](docs/yawac-1.27.md)。这些自动化记录属于相应历史版本。持续帧率/耗电、真实输入法、弱网和其他机型仍需专项验收。

Release 仍使用 debug 签名，供开发验证。当前包名为 `com.yawac.client`；正式签名及未来可选的数据迁移按路线文档决定。

[旧项目接力原文](docs/handoff-wearaichat-1.19-original.md)与 [迁移基线](docs/migration-baseline.md)属于 1.19 快照；根目录 [HANDOFF.md](HANDOFF.md) 已更新为当前交接入口。各版本记录保留相应历史。联网与动效见 [1.23 记录](docs/yawac-1.23.md)，包名与共存见 [1.24 记录](docs/yawac-1.24.md)，协议、操作引导和主页卡片见 [1.25 记录](docs/yawac-1.25.md)，思考强度见 [1.26 记录](docs/yawac-1.26.md)，融合/分离动效见 [1.27 记录](docs/yawac-1.27.md)，字体与时钟见 [1.28 记录](docs/yawac-1.28.md)。当前行为以本文和源码为准。
