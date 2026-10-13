// DeepSeekOld - API Proxy Server
// Listens on 127.0.0.1:3456, proxies to SiliconFlow DeepSeek-R1
const express = require('express');
const https = require('https');

const app = express();
const PORT = 3456;
const HOST = '127.0.0.1';

const CLIENT_KEY = 'sk-123456789012345678901234567890';
const UPSTREAM_KEY = 'sk-iiqurprllifkwnctseuvldsqvqedymqhsmsvjneddigffyps';
const UPSTREAM_URL = 'https://api.siliconflow.cn/v1/chat/completions';
const FORCED_MODEL = 'deepseek-ai/DeepSeek-R1-0528-Qwen3-8B';

app.use(express.json({ limit: '50mb' }));
app.use(express.raw({ type: '*/*', limit: '50mb' }));

function checkAuth(req, res, next) {
  const auth = req.headers['authorization'] || '';
  const token = auth.replace(/^Bearer\s+/i, '');
  if (token !== CLIENT_KEY) {
    return res.status(401).json({ error: { message: 'Invalid API key', type: 'invalid_request_error' } });
  }
  next();
}

app.all('/v1/chat/completions', checkAuth, (req, res) => {
  let body = req.body;
  if (Buffer.isBuffer(body)) {
    try { body = JSON.parse(body.toString()); } catch(e) { body = {}; }
  }
  body.model = FORCED_MODEL;
  const bodyStr = JSON.stringify(body);
  const url = new URL(UPSTREAM_URL);
  const options = {
    hostname: url.hostname,
    port: 443,
    path: url.pathname + url.search,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${UPSTREAM_KEY}`,
      'Content-Length': Buffer.byteLength(bodyStr),
    }
  };
  const proxyReq = https.request(options, (proxyRes) => {
    res.status(proxyRes.statusCode);
    for (const [k, v] of Object.entries(proxyRes.headers)) {
      if (k.toLowerCase() !== 'content-length' && k.toLowerCase() !== 'transfer-encoding') {
        res.setHeader(k, v);
      }
    }
    proxyRes.pipe(res);
  });
  proxyReq.on('error', (err) => {
    console.error('Proxy error:', err.message);
    if (!res.headersSent) {
      res.status(502).json({ error: { message: 'Upstream error: ' + err.message } });
    } else { res.end(); }
  });
  proxyReq.write(bodyStr);
  proxyReq.end();
});

app.get('/', (req, res) => {
  res.type('text/plain').send('DeepSeekOld API Proxy - POST /v1/chat/completions with Bearer token');
});

app.listen(PORT, HOST, () => {
  console.log(`DeepSeekOld server running on http://${HOST}:${PORT}`);
});
