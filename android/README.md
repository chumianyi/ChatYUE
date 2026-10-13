# DeepSeekOld

DeepSeekOld 是一个 Android 原生（Kotlin + 传统 View 体系）的 DeepSeek R1 聊天客户端，最低支持 Android 5.0（API 21）。

本项目由 [DeepSeek Lite](https://github.com/benzhoupo/deepseek-lite) 改造而来，主要变化：

- 接口地址、密钥、模型**全部硬编码固定**，App 内不提供查看与修改入口；
- **只有思考模式**，不支持切换，思考过程（`reasoning_content`）与正式回答（`content`）分开展示；
- 新增**上下文用量指示与自动压缩**；
- 新增侧边栏四大功能：智能翻译、一起作文、海龟汤、成语接龙；
- 支持明文 HTTP 接口（`usesCleartextTraffic` + 网络安全配置）。

## 一、固定配置

接口、密钥、模型统一写死在 `app/src/main/java/com/deepseekold/chat/backend/FixedEndpoint.kt`：

| 项 | 值 |
|----|----|
| 接口 | `http://103.236.99.177:24512/deepseekold/v1/chat/completions` |
| 模型 | `deepseek-ai/DeepSeek-R1` |
| 密钥 | 固定值，硬编码于源码，界面不展示 |

App 内**没有**任何设置页、模型选择器或密钥输入框，用户打开即可直接聊天。

## 二、聊天

- 输入消息并发送，全部请求固定走 `deepseek-ai/DeepSeek-R1`；
- 流式输出，实时展示 `reasoning_content` 思考过程；
- 思考结束后继续展示正式回答 `content`（Markdown 渲染）；
- 思考内容不会作为历史消息重复发送给模型；
- 支持停止生成、本地会话历史、搜索与清空。

## 三、上下文管理

- 右上角实时显示上下文占用百分比；
- 按字符估算 token：
  - 中文（含中日韩统一表意文字与全角标点）：每字符 **1.5** token
  - 英文及其他字符：每字符 **0.25** token
- 上限 **128K**；
- 达到 **85%** 时自动压缩：
  1. 自动调用 AI，把前半段对话压缩成一条摘要；
  2. 摘要以 **system** 消息保存并替换掉被压缩的原始消息；
  3. 界面中会以「上下文摘要」卡片展示，并提示压缩前后的占用比例。

估算规则实现见 `ContextBudget.kt`，压缩逻辑见 `ChatViewModel.maybeCompressContext()`。

## 四、侧边栏四大功能

| 功能 | 说明 |
|------|------|
| **智能翻译** | 5 组互译：中文↔英文、中文↔韩文、中文↔日文、简体↔繁体、中文↔俄文；方向由系统提示词指定，AI 只输出译文 |
| **一起作文** | 输入题目/要求，滑块选择 100~2000 字，AI 生成不少于所选字数、且不超过 2000 字的作文 |
| **海龟汤** | AI 以 JSON 生成「谜面 + 谜底」，前端只展示谜面；用户提问，AI 只回答「是」「否」「提示」；3 次提示机会；猜中谜底即获胜 |
| **成语接龙** | AI 随机出四字成语，用户接龙（首字读音与上一成语末字相同即可），AI 判定合法性并接续；接不上则游戏结束 |

## 五、构建

```bash
# 编译调试包
./gradlew assembleDebug

# 运行单元测试（纯 JVM，无需设备）
./gradlew test
```

构建产物：`app/build/outputs/apk/debug/app-debug.apk`

> `local.properties`（Android SDK 路径）已被忽略，导入 Android Studio 后会自动生成。

## 六、许可

Copyright (C) 2026 benzhoupo

本项目基于 DeepSeek Lite 改造，沿用 **GNU General Public License v3.0**，详情请参阅 [LICENSE](LICENSE)。
