# DeepSeekOld / veryoldDpSk

同一仓库内的两个 Android 聊天客户端，共用一套固定接口与固定模型。

| 应用 | 面向设备 | 技术栈 | 最低系统 |
|------|----------|--------|----------|
| **DeepSeekOld** | 主流设备 | Kotlin + Jetpack Compose（HyperOS 设计语言） | Android 5.0（API 21） |
| **veryoldDpSk** | 超老设备 | 纯 Java，零第三方依赖 | Android 1.5（API 3） |

两者均由 [DeepSeek Lite](https://github.com/benzhoupo/deepseek-lite) 改造而来。

---

## 一、固定配置（两个应用一致）

接口地址、密钥、模型全部硬编码在源码里，应用内不提供任何查看与修改入口。

| 项 | 值 |
|----|----|
| 接口 | `http://103.236.99.177:24512/deepseekold/v1/chat/completions` |
| 模型 | `deepseek-ai/DeepSeek-R1`（不支持切换） |
| 密钥 | 固定值，硬编码于源码，界面不展示 |

打开即聊天：不需要选模型、不需要填 Key、不需要填接口地址。

## 二、共同功能

- 固定接口、固定密钥、固定模型，仅思考模式（不支持切换）；
- 同时展示 `reasoning_content` 思考过程与 `content` 正式回答；
- 流式输出，可中途停止；
- 右上角显示上下文占用百分比：中文 ×1.5、其余 ×0.25，上限 128K；
- 占用达到 **85%** 时自动调用模型，把前半段对话压缩成一条 system 摘要并替换原消息；
- 侧边栏：智能翻译（中文 ⇄ 英 / 韩 / 日 / 俄，简体 ⇄ 繁体）、一起作文（5 种风格 + 中英双语 + 100~2000 字数）；
- 检查更新与更新日志：读取 GitHub Pages 上的 `start.json`，不重新发版也能更新；
- 首次启动必须同意用户协议与隐私政策，点「不同意」直接退出；
- 附件：选择设备图片（自动提示模型仅支持识别文本）或读取 txt 文本文件；
- 对话记录按时间分组：今天 / 昨天 / 7 天内 / 30 天内 / 更早。

## 三、DeepSeekOld（主应用）

- 界面由 **Jetpack Compose** 重写，按小米 **HyperOS / Miuix** 设计规范自行实现顶栏、消息卡片、胶囊按钮与侧边栏；
- 跟随系统的深色模式；
- 构建链路：Kotlin 2.4.20 + AGP 8.13.2 + Compose 1.9.0；
- 源码位于仓库根目录。

## 四、veryoldDpSk（超老设备）

- 面向 **Android 1.5（API 3）及以上所有系统**，最低版本由 CI 强制校验，不允许被抬高；
- **零第三方依赖**：不使用 AndroidX、不使用 Kotlin、不使用 OkHttp，全部为系统自带控件与 `java.net`；
- 手写 SSE 流式解析与 JSON 解析，避免不同年代 ROM 上的实现差异；
- 图标使用 PNG 位图（老系统不支持矢量图），主题基于 `@android:style/Theme.Light.NoTitleBar`；
- 运行时权限（相册）通过反射申请 + 轮询结果，代码中不出现任何高于 API 3 的符号；
- 源码位于 `veryoldDpSk/` 目录，构建与主应用完全隔离（JDK 11 + AGP 7.4.2 + Gradle 7.5.1）。

## 五、构建

两个应用都由 GitHub Actions 云端构建，推送到 `main` 即自动执行：

| 工作流 | 产物 | 额外校验 |
|--------|------|----------|
| `.github/workflows/android.yml` | `DeepSeekOld-debug-apk` | 单元测试 |
| `.github/workflows/veryold.yml` | `veryoldDpSk-debug-apk` | minSdk 必须为 3；lint 不得出现 NewApi / InlinedApi |

## 六、网页资源（GitHub Pages）

站点部署在 `docs/` 目录：

- `start.json` — 版本与更新日志数据源
- `agreement.html` — 用户协议
- `privacy.html` — 隐私政策

## 七、许可

本项目使用 **GNU General Public License v3.0** 开源，详见 [LICENSE](LICENSE)。

- 原项目：[DeepSeek Lite](https://github.com/benzhoupo/deepseek-lite)
- 项目地址：https://github.com/chumianyi/DeepSeekOld

> 仓库根目录下的 `server.js` / `package.json` 为 API 代理服务代码，与两个 Android 客户端同仓保存。
