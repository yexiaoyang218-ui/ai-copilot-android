# AI 沟通助手 (AI Social Copilot) — Android

一款中文聊天场景的 AI 沟通辅助应用。你在微信 / QQ / Telegram / Discord / WhatsApp / 短信里聊天时，
可以通过**截图框选、相册图片或手动粘贴**把聊天内容交给 AI，让它结合你的**人设**和**沟通意图**，
给出三条风格不同、可以直接复制发送的回复建议。

> 核心理念：AI 帮用户更好地表达，而不是替用户聊天或自动发送消息。
> 应用不读取其他应用的聊天记录，不自动发送、不自动回复。

---

## 更新日志

### v1.1.4（versionCode 6）

**框选整段上下文时，让 AI 真的读懂对话再回答**（本次重点）：

1. **Prompt 重做**：不再是"给一段文字让它回"，而是先要求还原对话——
   按时间顺序分句、判断每句是我说的还是对方说的、忽略时间戳/按钮/地图地名等 OCR 噪声。
2. **明确"该回哪一句"**：默认目标是**对方最后一条消息**，把它原样写进新字段 `replying_to`；
   只有当对方最后一条之后全是我说的话时，才改为补充我自己最后那句。
3. **截图被截断时**：明确要求"按已看到的内容判断，不要脑补缺失部分"。
4. **OCR 文本清洗**（`ChatTextCleaner`）：剔除时间戳、`(思考0秒)`、按钮文字、纯符号行、重复行；
   超长时保留**尾部最近的对话**并注明省略多少字。
5. **结果页显示「正在回应」**：把 `replying_to` 展示出来，你可以直接看出它有没有理解错。
   历史记录也会保存这一项（数据库升到 v3，带 2→3 迁移）。
6. 悬浮面板改为完全不透明，修掉背后聊天气泡文字透出来的问题。

### v1.1.3（versionCode 5）

数据库 v3（历史表新增 `replyingTo` 列）+ 结果页「正在回应」一栏。

### v1.1.2（versionCode 4）

真机回归中发现并修复：

1. **Android 14+ 截屏被限制成「单个应用」**（iQOO 12 / Android 15 实测）。
   Android 14 起 `createScreenCaptureIntent()` 默认只授权录一个 app，还会弹应用选择器，
   完全违背"截别人聊天窗口再裁剪"的用法。现在显式请求整个默认屏幕：
   ```kotlin
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
       manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
   }
   ```
   实测授权框从「单个应用」变成「整个屏幕」，截到了 QQ 的聊天内容。
2. **重装/重启后悬浮球不会自己回来**：开关还是"开"，但前台服务被系统杀了。
   现在 `MainActivity` 启动时会检查偏好并自动拉起服务；如果悬浮窗权限被回收，
   会把开关自动关掉，避免"开关说谎"。

### v1.1.1（versionCode 3）

版本号占位（构建产物未单独发布，修复合并进 1.1.2）。

### v1.1.0（versionCode 2）

1. **模型服务改成 7 家**：DeepSeek / GPT / Claude / Gemini / Kimi / Qwen / GLM，
   删掉「自定义兼容接口」。
2. **可以选模型了**：填好 API Key 后点「获取模型列表」，直接拉该账号真实可用的模型；
   推荐列表兜底，也能手写模型 ID。像 `deepseek-v4p`、`deepseek-v4.1-flash` 这种新模型上线即可选。
3. **悬浮球一条龙**：框选屏幕 → 本地 OCR → 按当前人设书生成 → 悬浮层弹出三条可点的回复 →
   点一条直接填进聊天输入框（需要时可开无障碍；不开就复制到剪贴板）。
4. **新增人设书**：预设之外，用自己的话写「我是谁、我怎么说话」，可多本、可切换，
   优先级高于模板人设；**保存即生效**，下一次识别就用新版。
5. 数据库升到 v2，新增 `persona_books` 表，带 1→2 迁移，升级不丢人设和历史。

### v1.0.0（versionCode 1）

首版：文本/图片/截屏输入、本地 OCR、三风格回复、人设系统、多模型、悬浮球、隐私控制。

---

## 1. 快速开始

### 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 17 或 21 |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.11.1（仓库自带 wrapper） |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| Android SDK | platform 35 + build-tools 35.0.0 |

> ⚠️ **项目必须放在纯英文（ASCII）路径下**，例如 `G:\AI_Social_Copilot`。
> AGP 会直接拒绝包含中文的工程路径；而且 Gradle 的单元测试 worker 在中文路径下会
> 报 `ClassNotFoundException` 找不到测试类。这不是本项目的问题，但会挡住构建。

### 构建

```powershell
# 单元测试（Prompt / 响应解析）
.\gradlew.bat :app:testDebugUnitTest

# Debug APK
.\gradlew.bat assembleDebug

# Release APK（当前用 debug 签名，便于直接安装验证）
.\gradlew.bat assembleRelease
```

`gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 默认指向
`repo.huaweicloud.com` 的 Gradle 镜像（国内直连 `services.gradle.org` 会 302 到 GitHub，
多数网络下不可达）。如果你的网络能访问 GitHub，把那一行换成注释里的官方地址即可。

`local.properties` 里的 `sdk.dir` 是本机 SDK 路径，用 Android Studio 打开工程时会自动重写。

**本仓库已实测的构建结果（JDK 21 / Windows 11）**

| 命令 | 结果 |
| --- | --- |
| `gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL，**43 个单测全过**（解析 10 / Prompt 18 / 清洗 7 / 服务商 8） |
| `gradlew :app:assembleDebug` | BUILD SUCCESSFUL，`app-debug.apk` 50.7 MB |
| `gradlew :app:assembleRelease` | BUILD SUCCESSFUL（含 lintVital），`app-release.apk` 47.1 MB |
| `apksigner verify` | 两个 APK 签名校验通过 |
| `aapt dump badging` | `com.aisocial.copilot` 1.1.4 (6) / minSdk 26 / targetSdk 35 |

### 真机回归（vivo V2307A / iQOO 12 / Android 15 / SDK 35）

用 `adb` 真实安装并在机器上跑通的项：

| 验证项 | 结果 |
| --- | --- |
| 安装、冷启动、无崩溃 | ✅ `logcat -b crash` 全程为空，进程存活 |
| 首页、7 家模型服务顺序、无「自定义」 | ✅ 逐屏截图确认 |
| 无 Key 时「获取模型列表」 | ✅ 红字「请先填写 API Key，再获取模型列表。」 |
| 真实 Key 拉取模型列表 | ✅ 用户实测选到了账号里的 `deepseek-flash`（不在内置推荐表里） |
| 模型选择器弹窗 | ✅ 分组显示、带「当前」标记、点选回填 |
| 人设书：新建 → 保存 → 生效 | ✅ 字数计数、「生效中」角标、「当前启用：mybook」、首页同步 |
| 发送前确认弹窗 | ✅ 显示目标模型 + 内容预览 |
| 无 Key 生成 | ✅ 中文错误提示，不崩 |
| 悬浮球：前台服务 + 窗口 + 面板 | ✅ `types=0x40000000`，球与 6 项面板可见可点 |
| 悬浮球截屏 → OCR → 三条回复 | ✅ 在 QQ 上完整跑通，回复贴合上下文 |
| 点一条填入 QQ 输入框 | ✅ QQ 输入框出现「刚醒，手机拿反了发的。你那边怎么样」，**未自动发送** |
| 重装后数据保留 | ✅ 人设书、历史、Key 都还在（Room 1→2 迁移正常） |
| 重装后悬浮球自动恢复 | ✅ 强杀进程再打开，球自己回来 |

产物位置：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
dist/app-debug.apk          ← 已打包好的成品（可直接发到手机安装）
dist/app-release.apk
```

### 安装

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

或者把 APK 拷到手机上手动安装（需要允许「安装未知来源应用」）。

### 首次使用（3 步）

1. 打开应用 → 右上角 **设置 → 模型服务** → 选一个服务商（推荐 DeepSeek）→ 填入 API Key → **测试连接** → 保存。
2. 回到首页，选择一个人设（内置 8 个预设，可直接用）。
3. 点「框选屏幕 / 上传图片 / 手动输入」录入聊天内容 → **生成三条回复** → 复制。

---

## 2. 功能一览

### 输入

- 手动输入 / 粘贴聊天文本。
- 相册选图（系统 Photo Picker，**不需要存储权限**）。
- **系统屏幕捕获 + 框选裁剪**：拖拽边角调整选区，支持移动、重选、预览后确认。
- 多张图片合并为同一上下文（最多 3 张）。
- **本地 OCR**（ML Kit 中文 + 拉丁文字模型，完全离线），识别结果可在输入框里直接修改。

### AI 回复

- 一次生成 **3 条风格不同**的回复：自然日常 / 情绪回应 / 人设风格。
- 结构化输出：`analysis`（对方状态）、`tone`（语气判断）、`strategy`（建议策略）与可直接发送的回复**分开显示**。
- 每条回复支持：**复制、改写、更短、更详细、调整语气（更自然/更幽默/更主动/更含蓄/更冷静）、再生成一条、手动编辑、还原改写前版本**。
- 「再给我三条不一样的」会在避开已用回复的前提下补充新候选。
- 复制成功有轻量提示。

### 人设系统

- 创建 / 编辑 / 删除 / 复制 / 切换 / 保存多个人设。
- 配置项：名称、性格描述、语气、回复长度、情绪表达程度、主动程度、幽默程度、常用词句、禁用表达、自定义指令。
- 内置 8 个预设：自然、温柔、成熟、幽默、冷静、直球、暧昧、职场。
- 人设进入 **Prompt Builder**，影响之后每一次生成，不只是角色扮演。
- 删除后可在人设页「恢复预设」找回来。

### 人设书（v1.1 新增）

- 预设之外，用**你自己的话**写一段「我是谁、我怎么说话」，可写多本、随时切换。
- 人设书**优先级高于**模板人设：Prompt 里明确声明冲突时以人设书为准。
- 保存即启用：点「保存并立即启用」后，**下一次截屏识别就直接用新版**，不用重启、不用重新选择。
- 首页、人设页都有入口，并显示当前生效的是哪一本。

### 悬浮球（v1.1 重做）

- 屏幕边缘可拖动的悬浮球，松手自动吸附边、记住位置、可调透明度。
- **一键出回复**：点悬浮球 →「框选屏幕 · 出回复」→ 框选聊天区域 → 自动本地 OCR →
  按当前人设书和人设生成 → **直接在悬浮层弹出三条可点的回复**，全程不离开聊天界面。
- 点其中一条：文字立即复制到剪贴板；如果开启了「填入回复」无障碍服务，会**直接写进聊天输入框**。
- 面板里还有「换一批」重新生成，和「关闭」。
- 图片 / 文字输入 / 打开设置 / 隐藏 / 关闭悬浮球都在同一个面板。
- 截图时自动隐藏悬浮球，避免把自己的球拍进截图里。

### 多模型

统一 `ProviderClient` 抽象，三套协议覆盖全部服务商（v1.1 取消了「自定义兼容接口」，
改成 7 家开箱即用）：

| 服务商 | 协议 | 默认模型 | 图片 |
| --- | --- | --- | --- |
| DeepSeek | `POST /chat/completions` | `deepseek-chat` | ❌（走本地 OCR） |
| GPT (OpenAI) | `POST /chat/completions` | `gpt-4o-mini` | ✅ |
| Claude (Anthropic) | `POST /messages` | `claude-sonnet-4-5` | ✅ |
| Gemini (Google) | `POST /models/{m}:generateContent` | `gemini-2.5-flash` | ✅ |
| Kimi (Moonshot) | `POST /chat/completions` | `kimi-k2-0905-preview` | ✅ |
| 通义千问 (Qwen) | `POST /chat/completions` | `qwen-plus` | ✅ |
| 智谱 GLM | `POST /chat/completions` | `glm-4.5` | ✅ |

**模型不再写死**：填好 API Key 后点「获取模型列表」，应用会调用服务商自己的
`GET /models`，把该 Key 真实可用的模型列出来（已过滤掉 embedding / TTS / 图片等非聊天模型），
点一下就选中。像 `deepseek-v4p`、`deepseek-v4.1-flash` 这种新模型，只要服务商上了线就能直接选到；
列表里没有也可以手写模型 ID。

- 支持 Base URL、模型 ID、视觉模型 ID、启用/停用、默认模型、备用模型、超时、temperature。
- 按任务自动选择视觉或文本模型；模型不支持图片时自动降级为本地 OCR 文本，并在结果页说明。
- 主模型失败会按配置自动尝试备用模型，**两个都失败才报错**，错误信息是中文可读的。
- 每个服务商都有「测试连接」按钮。

### 隐私与安全

- **Local-first**：截图裁剪、OCR、人设、设置全部在本机完成。
- 默认不把原始截图写盘，截图只在内存里流转（`CaptureBus`），用完即释放。
- 请求前弹「发送前确认」预览框，明确显示将发给哪个服务商/模型、发送哪些内容。
- **API Key 用 Android Keystore 的 AES-256/GCM 加密**后存 DataStore；不硬编码、不写日志。
- 最小权限：不申请通讯录、短信、无障碍、存储权限。
- 可关闭历史记录、清除缓存、一键清除全部数据。

---

## 3. 项目结构

```
AI_Social_Copilot/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml            # 版本目录
├── local.properties                     # sdk.dir
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                         # 主题、图标、字符串
        └── java/com/aisocial/copilot/
            ├── AiCopilotApp.kt          # Application
            ├── MainActivity.kt          # 单 Activity 宿主（只有 setContent）
            ├── di/
            │   └── AppContainer.kt      # 手写 DI 图
            ├── core/
            │   ├── NetResult.kt         # 统一结果类型
            │   └── BitmapUtils.kt       # 解码/缩放/裁剪/JPEG Base64
            ├── domain/
            │   ├── Presets.kt           # 8 个人设预设
            │   └── model/Models.kt      # Persona / AnalysisResult / ProviderConfig / AiSettings ...
            ├── data/
            │   ├── local/AppDatabase.kt # Room v2：personas + history + persona_books
            │   ├── prefs/SettingsRepository.kt  # DataStore 设置
            │   ├── secure/SecretVault.kt        # Keystore AES-GCM
            │   └── repo/                # PersonaRepository / PersonaBookRepository / HistoryRepository
            ├── ai/
            │   ├── PromptBuilder.kt     # 唯一 Prompt 组装处（含人设书注入）
            │   ├── ResponseParser.kt    # 容错 JSON 解析
            │   ├── HttpCore.kt          # OkHttp + 中文错误映射
            │   ├── ProviderClient.kt    # 三套 wire 协议 + /models 拉取
            │   └── CopilotEngine.kt     # 用例层：选模型、备用模型、历史
            ├── ocr/OcrEngine.kt         # ML Kit 本地识别
            ├── capture/
            │   ├── CaptureBus.kt        # 内存中截图交接
            │   ├── MediaProjectionService.kt  # 单帧截取前台服务
            │   └── ScreenCaptureActivity.kt   # 透明宿主 + 框选裁剪 UI（app / overlay 两种模式）
            ├── overlay/
            │   ├── OverlayService.kt    # 悬浮球 + 快捷面板 + 截屏出回复结果面板
            │   └── ReplyFillService.kt  # 可选无障碍：把点选的那条写进输入框
            └── ui/
                ├── theme/Theme.kt        # Material 3 低饱和配色
                ├── nav/Navigation.kt     # Screen + 返回栈
                ├── session/SessionViewModel.kt
                ├── persona/PersonaViewModel.kt
                ├── settings/SettingsViewModel.kt
                ├── components/Common.kt  # 卡片 / 胶囊 / 设置行
                ├── screens/              # Home / Composer / Result / Personas / PersonaEdit /
                │                         # PersonaBooks / PersonaBookEdit / Settings / ProviderEdit
                └── AppRoot.kt            # 导航装配 + 剪贴板 + 相册
```

分层：**UI → ViewModel → (CopilotEngine | Repository) → Room / DataStore / OkHttp / ML Kit**。
业务逻辑没有一处写在 Activity 里。

---

## 4. 关键实现说明

### 4.1 屏幕捕获与框选

Android 14+ 要求 `MediaProjection` 必须在 `mediaProjection` 类型的前台服务里使用，
并且必须先 `startForeground()` 再 `getMediaProjection()`。流程：

1. `ScreenCaptureActivity`（`windowIsTranslucent`，**等待期间不绘制任何东西**，否则会被拍进截图）。
2. `MediaProjectionManager.createScreenCaptureIntent()` 拿到用户授权。
3. 把 resultCode/data 交给 `MediaProjectionService`，它在 320ms 稳定期后取**单帧**，
   立刻 `release()` 虚拟显示并 `stop()` 投影，然后 `stopSelf()`。
4. 帧通过 `CaptureBus.frame` 回到 Activity，显示框选层：拖角改大小、拖内部移动、全屏/预览/确定。
5. 裁剪结果写入 `CaptureBus.draft`，跳回 `MainActivity`，由 `SessionViewModel` 做本地 OCR。
6. 过程中悬浮球自动隐藏，结束后恢复。

### 4.2 结构化输出

Prompt 强制模型返回：

```json
{
  "analysis": "...",
  "tone": "...",
  "strategy": "...",
  "replies": [
    { "label": "自然日常", "text": "..." },
    { "label": "情绪回应", "text": "..." },
    { "label": "人设风格", "text": "..." }
  ]
}
```

`ResponseParser` 会去掉 ``` 代码块、截取第一个 `{` 到最后一个 `}` 解析；
解析失败则退化为按行提取最多 3 条候选，**任何情况下都不会崩**。

### 4.3 安全

`SecretVault` 用 `AndroidKeyStore` 生成不可导出的 AES-256 key，
`AES/GCM/NoPadding` 加密后以 `iv.length | iv | ciphertext` 打包 Base64 存入 DataStore。
解密失败（例如系统重置了 Keystore）返回空串，界面提示重新填写，不会泄漏也不会崩溃。

---

## 5. 权限说明

| 权限 | 用途 | 什么时候申请 |
| --- | --- | --- |
| `INTERNET` | 调用你配置的模型 API | 安装时 |
| `SYSTEM_ALERT_WINDOW` | 悬浮球 | 打开「悬浮球」开关时跳系统设置页 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 保活悬浮球 | 自动 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 截屏服务 | 自动 |
| `POST_NOTIFICATIONS` | 前台服务通知 | Android 13+ 开启悬浮球时 |
| **无障碍服务**（`ReplyFillService`，可选） | 只在你在悬浮面板里点某条回复时，把那条文字写进当前输入框 | 你在设置里点「启用填入回复」时跳系统无障碍页 |

**无障碍服务的边界（写死在代码里）**：不订阅任何内容事件、不读取聊天记录、不保存、不上传、
从不点「发送」。不开启也不影响其它任何功能，只是点回复后需要自己长按粘贴。

**不申请**：通讯录、短信、存储（用 Photo Picker）、定位、麦克风、相机。

MediaProjection 的授权是**每次截图都要点一次系统弹窗**，这是 Android 的安全设计，应用无法绕过，也不会试图绕过。

---

## 6. 已知限制 / 未完成项

- **悬浮球截屏需要每次确认**：系统 MediaProjection 授权对话框每次都会弹出。如果要「一次授权、多次截屏」，
  需要让 `MediaProjectionService` 常驻并复用投影，本项目为隐私考虑选择一次一授权。
- **应用内直接截屏会截到自己**：从首页点「框选屏幕」截的是当前屏幕（即本应用）。
  要截聊天界面，请从悬浮球进入。
- **未做**（需求首期明确不做）：自动读取其他应用聊天、自动发送、自动回复、云同步、账号体系、
  长期记忆、语音。也没有 R8 混淆（release 里 `isMinifyEnabled = false`），便于验证。
- **「填入输入框」不是 100% 保证**：它走 `ACTION_SET_TEXT`。**QQ 已实测通过**（文字成功写进输入框且不发送）；
  微信 / Telegram 一般都是标准 EditText，理论同样可行但未逐一点测；个别自绘输入框会拒绝，
  这时会自动退回「已复制到剪贴板」，长按输入框粘贴即可。
- **悬浮球截屏依然每次都要授权**：这是 MediaProjection 的系统机制，应用不会绕过。
- ML Kit 中文模型打包在 APK 内，APK 体积约 40–60 MB，属于预期。
- `usesCleartextTraffic="true"` 是为了支持局域网自建网关（如 Ollama / one-api）。
  如果只用公网 HTTPS 服务，可以改成 `false`。
- 悬浮球服务类型使用 `specialUse`，上架 Google Play 需要提交用途声明；侧载安装不受影响。
- **验证范围**：编译、26 个单元测试、APK 打包与签名校验，以及 **iQOO 12（Android 15）真机全流程回归**
  都已跑通，逐项结果见上面的「真机回归」表。未覆盖的部分：微信 / 其他机型 / 低版本 Android 的实际点按。
  建议按 [`docs/TESTING.md`](docs/TESTING.md) 在自己常用的机型上再过一遍。

---

## 7. 验收对照

| 需求 | 状态 | 位置 |
| --- | --- | --- |
| 可安装可运行的 Android 应用 | ✅ | `assembleDebug` 产物 |
| 首页 + 设置页 | ✅ | `HomeScreen` / `SettingsScreen` |
| API Key 配置 + 真实模型接入 | ✅ | `ProviderEditScreen` / `ProviderClient` |
| 文本输入 + AI 回复 | ✅ | `ComposerScreen` → `CopilotEngine` |
| 三条不同风格回复 | ✅ | `PromptBuilder` |
| 复制 / 重写 / 调整语气 | ✅ | `ResultScreen` + `ReplyOp` |
| 人设创建、保存、切换 | ✅ | `PersonaEditScreen` / Room |
| 上传图片 + 本地 OCR | ✅ | Photo Picker + `OcrEngine` |
| OCR 内容编辑 | ✅ | 识别文本直接进可编辑输入框 |
| 屏幕捕获、框选裁剪、预览确认 | ✅ | `ScreenCaptureActivity` |
| 悬浮球及展开面板 | ✅ | `OverlayService` |
| 基础隐私控制、清除数据、错误提示 | ✅ | `SettingsScreen` / `HttpCore` |
| **v1.1** 7 家模型服务、取消自定义接口 | ✅ | `DefaultProviders` |
| **v1.1** 输入 Key 后可选择真实可用模型 | ✅ | `ProviderClient.listModels` + 模型选择器 |
| **v1.1** 悬浮球截屏后直接给三条可点回复 | ✅ | `OverlayService.startQuickFlow` |
| **v1.1** 点选后填入聊天输入框 | ✅ | `ReplyFillService`（可选无障碍） |
| **v1.1** 人设书：自写、多本、优先于预设、保存即生效 | ✅ | `PersonaBookRepository` + `PromptBuilder` |

更细的逐项测试步骤见 [`docs/TESTING.md`](docs/TESTING.md)。
