// DeepSeekOld - GLM proxy
const express = require('express');
const https = require('https');
const app = express();
const PORT = 3456;
const HOST = '127.0.0.1';

const CLIENT_KEY = 'sk-123456789012345678901234567890';
const UPSTREAM_KEY = 'd0a99ebaa97e4bac9e99e236211b15f5.m8eJh0XSXMVu2I8P';
const UPSTREAM_URL = 'https://open.bigmodel.cn/api/paas/v4/chat/completions';
const FORCED_MODEL = 'GLM-4.1V-Thinking-Flash';

// Concurrency limit: max 5 concurrent requests
let concurrent = 0;
const MAX_CONCURRENT = 20;

app.use(express.json({ limit: '50mb' }));
app.use(express.raw({ type: '*/*', limit: '50mb' }));

function checkAuth(req, res, next) {
  const auth = req.headers['authorization'] || '';
  const token = auth.replace(/^Bearer\s+/i, '');
  if (token !== CLIENT_KEY) return res.status(401).json({ error: { message: 'Invalid API key' } });
  next();
}

function concurrencyLimit(req, res, next) {
  if (concurrent >= MAX_CONCURRENT) {
    return res.status(429).json({ error: { message: '服务器繁忙，请稍后再试', type: 'rate_limit' } });
  }
  concurrent++;
  res.on('close', () => { concurrent--; });
  next();
}

app.all('/v1/chat/completions', checkAuth, concurrencyLimit, (req, res) => {
  let body = req.body;
  if (Buffer.isBuffer(body)) { try { body = JSON.parse(body.toString()); } catch(e) { body = {}; } }
  body.model = FORCED_MODEL;

  const isStream = body.stream === true;
  const bodyStr = JSON.stringify(body);
  const url = new URL(UPSTREAM_URL);
  const opts = { hostname: url.hostname, port: 443, path: url.pathname + url.search, method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + UPSTREAM_KEY, 'Content-Length': Buffer.byteLength(bodyStr) } };
  const pr = https.request(opts, (pres) => {
    res.status(pres.statusCode);
    for (const [k, v] of Object.entries(pres.headers)) {
      if (k.toLowerCase() !== 'content-length' && k.toLowerCase() !== 'transfer-encoding') res.setHeader(k, v);
    }
    if (isStream) {
      res.setHeader('Content-Type', 'text/event-stream');
      pres.pipe(res);
    } else {
      let rb = [];
      pres.on('data', (c) => rb.push(c));
      pres.on('end', () => { res.send(Buffer.concat(rb)); res.end(); });
    }
  });
  pr.on('error', (err) => { if (!res.headersSent) res.status(502).json({ error: { message: err.message } }); else res.end(); });
  pr.write(bodyStr); pr.end();
});

app.get('/', (req, res) => { res.type('text/plain').send('DeepSeekOld API Proxy - GLM-4.1V-Thinking-Flash'); });
app.listen(PORT, HOST, () => { console.log('DeepSeekOld on ' + HOST + ':' + PORT + ' model=' + FORCED_MODEL + ' maxConcurrent=' + MAX_CONCURRENT); });
