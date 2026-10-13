# DeepSeekOld

DeepSeek-R1 API proxy service.

## API Endpoint

```
POST http://103.236.99.177:24512/deepseekold/v1/chat/completions
Authorization: Bearer sk-123456789012345678901234567890
Content-Type: application/json

{
  "model": "deepseek-r1",
  "messages": [{"role": "user", "content": "你好"}],
  "stream": true
}
```

- Forwards to SiliconFlow DeepSeek-R1
- Supports SSE streaming with reasoning_content
- GitHub: https://github.com/chumianyi/DeepSeekOld

## Android 客户端

本仓库同时包含 DeepSeekOld 的 Android 客户端源码，位于 [`android/`](android/) 目录。

- 固定接口、固定密钥、固定模型（`deepseek-ai/DeepSeek-R1`），App 内不提供修改入口
- 仅思考模式，展示 `reasoning_content` 思考过程与 `content` 正式回答
- 支持流式输出、上下文百分比与 85% 自动压缩
- 侧边栏四大功能：智能翻译、一起作文、海龟汤、成语接龙

打包由 GitHub Actions 自动完成（见 `.github/workflows/android.yml`），每次推送到 `main` 都会运行单元测试并产物 `DeepSeekOld-debug-apk`。
