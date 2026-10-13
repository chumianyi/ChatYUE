# AGENTS.md — DeepSeek Lite

本文档为 AI 编码助手（如 OpenCode、Claude Code、Cursor 等）在本仓库中协作时提供必要的上下文参考。每个条目均经过验证，是从实际源码、构建配置和测试文件中提取的高信号信息。

---

## 项目概述

- **项目名称**：DeepSeek Lite
- **包名**：`com.deepseeklite.chat`
- **类型**：Android 原生应用（Kotlin，传统 View 体系，**不使用 Jetpack Compose**）
- **最低 API 版本**：21（Android 5.0），**目标/编译 API 版本**：36（含 `minorApiLevel = 1`）
- **许可证**：GNU GPL v3.0
- **模块结构**：单模块项目，仅包含 `:app`，无多模块/多 flavor 结构

---

## 构建与运行命令

以下命令均在项目根目录执行：

```bash
# 编译调试版本
./gradlew assembleDebug

# 编译发布版本
./gradlew assembleRelease

# 运行所有单元测试（纯 JVM，不依赖设备/模拟器）
./gradlew test

# 运行单个单元测试类或方法（示例）
./gradlew test --tests "com.deepseeklite.chat.backend.DeepSeekChatBackendTest.streamSeparatesThinkingAndAnswerDeltas"

# 运行设备上的 Instrument 测试（需要连接设备或启动模拟器）
./gradlew connectedAndroidTest

# 清理构建产物
./gradlew clean
```

**注意事项**：
- `gradle.properties` 中已启用 Gradle Configuration Cache（`org.gradle.configuration-cache=true`），修改 `settings.gradle.kts` 或 `build.gradle.kts` 后可能需要 `./gradlew --stop` 清除缓存。
- Gradle JVM 内存设置为 `-Xmx2048m`，如果 OOM 可调整。
- 本项目使用 Gradle Version Catalog（`gradle/libs.versions.toml`），所有依赖版本集中管理，**不要**直接在 `app/build.gradle.kts` 中写死版本号。
- 新增依赖请在 `gradle/libs.versions.toml` 中声明，然后在 `app/build.gradle.kts` 中通过 `libs.xxx` 引用。
- `local.properties` 已被 `.gitignore` 忽略，包含本地 Android SDK 路径，**切勿提交**。

---

## 测试体系

### 单元测试（`app/src/test/`）

- 测试框架：JUnit 4（`junit:junit:4.13.2`）
- 测试运行在本地 JVM 上，**不需要真机/模拟器**
- `DeepSeekChatBackendTest`：使用 OkHttp 的 `MockWebServer` 模拟服务端 SSE 响应，测试流式解析、认证错误映射、请求体构建
- `ConversationFormatterTest`：测试标题截断（20 字符上限）和会话时间分组逻辑（今天/昨天/更早）
- 单元测试使用 `Executor { it.run() }` 让回调同步执行，避免异步等待问题

### Instrument 测试（`app/src/androidTest/`）

- 测试框架：AndroidJUnit4 + Espresso 3.5.1
- `ApiKeyStoreInstrumentedTest`：在设备上测试 AndroidKeyStore 加解密读写和清除
- `ExampleInstrumentedTest`：框架生成的模板测试

---

## 架构与代码组织

### 整体分层

```
MainActivity (View) → ChatViewModel (ViewModel) → LocalChatRepository (SQLite) + DeepSeekChatBackend (HTTP/SSE)
```

所有源文件在 `app/src/main/java/com/deepseeklite/chat/` 下：

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | 唯一的主 Activity，管理侧边栏、消息列表、输入面板、深度思考开关 |
| `ChatViewModel.kt` | 继承 `AndroidViewModel`，管理生成状态（`GenerationState` 密封类），协调整流请求和本地持久化 |
| `ChatModels.kt` | 应用层数据模型：`Conversation`、`ChatMessage`、`MessageRole`（含 `THINKING_ACTIVE`、`THINKING_FINISHED`）、`DrawerItem` 等 |
| `ChatRepository.kt` | 数据仓库接口定义，声明了 8 个数据操作方法 |
| `LocalChatRepository.kt` | `SQLiteOpenHelper` 实现，数据库名 `deepseek_lite.db`，版本号 1。`onUpgrade()` 目前只做 DROP + CREATE |
| `ConversationFormatter.kt` | 单例对象，标题生成（取前 20 字符）和会话时间分组 |
| `ApiKeyStore.kt` | 使用 AndroidKeyStore RSA-2048 非对称加密存储 API Key，密文以 Base64 存 SharedPreferences |
| `ApiKeySettingsActivity.kt` | API Key 设置页面，普通 `Activity`（非 AppCompatActivity） |
| `ConversationAdapter.kt` | 侧边栏会话列表的 RecyclerView Adapter，按"今天/昨天/更早"分 Section |
| `ChatMessageAdapter.kt` | 消息列表 RecyclerView Adapter，通过 `DiffUtil` 增量更新。三种 ViewType：用户消息、AI 回答、思考过程。AI 回答通过 **Markwon** 渲染 Markdown |
| `CompatSelectableTextView.kt` | API 21-22 的文本选择+复制替代方案，API 23+ 使用系统原生 `setTextIsSelectable` |
| `CompatPasteEditText.kt` | API 21-22 的输入框粘贴选择替代方案（防系统 clipboard menu 崩溃），API 23+ 使用系统原生能力 |
| `CompatToast.kt` | 统一的自定义 Toast 样式 |
| `backend/ChatBackend.kt` | 后端接口，定义 `sendMessage() → CancellableRequest` 和回调接口 |
| `backend/DeepSeekChatBackend.kt` | DeepSeek API SSE 实现，使用 OkHttp 同步分块读取 SSE 事件，解析 `reasoning_content` 和 `content` delta |
| `backend/BackendModels.kt` | 后端数据模型：`ChatRequest`、`BackendMessage`、`BackendAssistantMessage`、`BackendError` 等 |

### 关键架构细节

1. **ChatViewModel 不直接持有 Context**：它继承 `AndroidViewModel`，通过 `getApplication()` 获取上下文传给 `LocalChatRepository` 和 `ApiKeyStore`。
2. **思考过程处理**：`THINKING_ACTIVE` → 流式传输中；`THINKING_FINISHED` → 已完成的思考。思考内容**不作为对话历史**发送给 API（`sendMessage` 中过滤掉了 `THINKING_ACTIVE` 和 `THINKING_FINISHED`）。
3. **中断处理**：`ChatViewModel.init{}` 中调用 `repository.finalizeInterruptedThinking()` 将上次可能被中断的 `THINKING_ACTIVE` 消息转为 `THINKING_FINISHED`。
4. **流式消息 ID**：流式传输中的消息使用固定 ID（`STREAM_THINKING_ID = -1L`，`STREAM_ASSISTANT_ID = -2L`），与数据库持久化 ID 不冲突。
5. **滚动策略**：`followStreaming` 标志控制消息列表是否跟随滚动。用户手动上滑浏览历史时自动停止跟随，滑到底部时恢复跟随。
6. **取消请求**：`CancellableRequest` 是 `fun interface`，通过 `AtomicBoolean` + `Call.cancel()` 实现线程安全的请求取消。

---

## DeepSeek API 硬编码常量

**若不需要修改这些默认值，则忽略本节。**

在 `app/src/main/java/com/deepseeklite/chat/backend/DeepSeekChatBackend.kt:321-323` 的 companion object 中：

```kotlin
const val API_BASE_URL = "https://api.deepseek.com"
const val MODEL = "deepseek-v4-flash"
```

- API 地址固定为 `https://api.deepseek.com`，端点路径 `/chat/completions` 不可配置
- 默认模型 `deepseek-v4-flash`，每次请求在 JSON body 中发送
- `stream: true` 强制启用，不支持普通（非流式）响应
- 深度思考开启时即发送 `reasoning_effort: "high"`，该参数无法自定义
- 超时设置：连接 20s，写入 30s，**读超时 0（无限等待）**，全局 call 超时 10 分钟

---

## ViewBinding

- 项目使用 **ViewBinding**（`build.gradle.kts` 中 `viewBinding = true`），**不使用** `findViewById` 或 `kotlinx.synthetic`
- 布局文件在 `app/src/main/res/layout/` 下，生成的 Binding 类可直接通过驼峰命名引用
- 修改 layout 后需要重新 build 以生成最新的 Binding 类

---

## API 21-22 兼容性

- `CompatSelectableTextView` 和 `CompatPasteEditText` 仅对 API 21-22（`Build.VERSION.SDK_INT < 23`）生效
- 原因：API 21-22 的系统 `TextView` 选择菜单在创建 clipboard 操作时可能因缺少 contentDescription 而崩溃
- API 23+ 直接使用系统原生 `setTextIsSelectable()` 和 `ActionMode.Callback`
- `ApiKeySettingsActivity` 使用普通 `Activity`（而非 `AppCompatActivity`）以避免额外依赖

---

## API Key 加密存储

- 使用 AndroidKeyStore 生成 RSA-2048 密钥对（API 23+ 用 `KeyGenParameterSpec`，API 21-22 用已弃用的 `KeyPairGeneratorSpec`）
- 加密算法 `RSA/ECB/PKCS1Padding`，明文上限 190 字节
- 密文经 Base64 编码（`NO_WRAP`）存入 SharedPreferences（文件名 `deepseek_lite_secure_preferences`）
- API Key 格式要求：必须以 `sk-` 开头，长度 ≥ 16 字符，不含空白符；自动去除首尾空白、引号、`Bearer ` 前缀
- `getApiKey()` 解密失败时自动清除存储的密文，返回 `null`
- 应用数据被清除时，密钥对和密文一同丢失（密钥对存在 AndroidKeyStore 中，与 app 生命周期绑定）

---

## 数据库（SQLite）

- 数据库文件：`deepseek_lite.db`，版本号 1
- 表结构：
  - `conversations(id, title, updated_at)` — 外键到，删除时 CASCADE
  - `messages(id, conversation_id, role, content, created_at)` — 外键到 conversations.id，删除时 CASCADE
- `role` 字段存储枚举名（`USER`、`ASSISTANT`、`THINKING_ACTIVE`、`THINKING_FINISHED`）
- `onUpgrade()` 当前实现：**直接 DROP 全部表后重建**（会丢失数据），未来如需迁移应在升级时改为 ALTER TABLE
- `seedIfNeeded()`：首次启动时清除旧版 App 遗留的 10 条演示会话标题（一次性操作，通过 SharedPreferences 标记）
- 外键约束已启用（`setForeignKeyConstraintsEnabled(true)`）

---

## 资源与布局

- 主要布局：`activity_main.xml`（聊天主界面）、`activity_api_key_settings.xml`（API Key 设置页）
- 消息列表 item：`item_message_user.xml`、`item_message_assistant.xml`、`item_message_thinking.xml`
- 侧边栏 item：`item_conversation.xml`、`item_section_header.xml`
- 图标全部用 XML VectorDrawable（`app/src/main/res/drawable/`），不使用 PNG
- 主题支持 API 23+ 的特性（`values-v23/themes.xml`、`values-v27/themes.xml`）
- Edge-to-Edge 配置：API 35+ 使用强制 E2E，API 35 以下使用 `setDecorFitsSystemWindows(false)`

---

## 注意事项与陷阱

1. **不要引入 Jetpack Compose**：本项目完全基于传统 View 体系，混用 Compose 会引入大量依赖并破坏代码一致性。
2. **不要使用 `findViewById`**：统一使用 ViewBinding。
3. **修改数据库表结构**时，必须升级 `DATABASE_VERSION` 并改写 `onUpgrade()` 逻辑（当前是 DROP 重建，如需保留数据需改为 ALTER TABLE 迁移）。
4. **流式消息的消息 ID 为负数**（`-1L`、`-2L`），与数据库中真实 ID 区分。新增流式占位符时注意不要使用已存在的负数 ID。
5. **单元测试中的回调执行器**：`DeepSeekChatBackendTest` 使用同步 `Executor`（`Executor { it.run() }`）以保证回调在测试线程中按序执行。如果修改 Badend 的回调分发方式，确保测试仍然通过。
6. **Markdown 渲染**由 Markwon 库处理，`ChatMessageAdapter` 传入 `Markwon.create(this)` 实例。修改 Markdown 相关行为时注意 Markwon 的配置方式。
7. **`ChatRepository` 接口**是 `LocalChatRepository` 的唯一抽象。如果需要切换到其他存储后端（如 Room），只需提供新的实现类并修改 `ChatViewModel` 中的字段类型。
8. **`GenerationState` 是密封类**，有 4 个子状态：`Idle`、`Active`、`Completed`、`Failed`。`MainActivity.observeGeneration()` 中的 `when` 必须穷尽所有分支，否则编译失败。
9. **`sendMessage()` 中过滤掉 THINKING 消息**：在构建 `ChatRequest` 的 `messages` 列表时，`THINKING_ACTIVE` 和 `THINKING_FINISHED` 返回 `null` 并被 `mapNotNull` 过滤，所以思考内容不会重复发给 API。
10. **发布版本默认关闭 ProGuard/R8 优化**：`release.optimization.enable = false`，如需开启代码混淆压缩需手动启用。

---

## 现有 AGENTS.md / 指令文件

- 仓库中暂无 `AGENTS.md`、`CLAUDE.md`、`.cursorrules`、`opencode.json` 等文件
- `.agents/` 目录存在但为空
- `README.md` 包含面向最终用户的使用说明，不包含开发相关指令

---

## 版本信息（截至编写时）

| 依赖 | 版本 |
|------|------|
| AGP (Android Gradle Plugin) | 9.2.1 |
| Kotlin | （由 AGP 管理） |
| Gradle | Wrapper 定义（`gradle-wrapper.properties`） |
| compileSdk | 36 (minorApiLevel=1) |
| minSdk | 21 |
| targetSdk | 36 |
| OkHttp | 4.12.0 |
| Markwon | 4.6.2 |
| Lifecycle (LiveData + ViewModel) | 2.8.7 |
| AppCompat | 1.7.1 |
| JUnit | 4.13.2 |
| Espresso | 3.5.1 |
