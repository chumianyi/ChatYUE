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
