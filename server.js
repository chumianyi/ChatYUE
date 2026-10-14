// DeepSeekOld - GLM proxy with banned word filter
const express = require('express');
const https = require('https');
const app = express();
const PORT = 3456;
const HOST = '127.0.0.1';
const CLIENT_KEY = 'sk-123456789012345678901234567890';
const UPSTREAM_KEY = 'd0a99ebaa97e4bac9e99e236211b15f5.m8eJh0XSXMVu2I8P';
const UPSTREAM_URL = 'https://open.bigmodel.cn/api/paas/v4/chat/completions';
const FORCED_MODEL = 'GLM-4.1V-Thinking-Flash';
const BANNED_WORDS = ['中共','六四','天安门','法轮功','台独','港独','制作炸弹','制毒','自杀方法','色情网站','嫓娼','毒品交易','赌博网站','黑客教程','诈骗教程'];
const bannedRegex = new RegExp(BANNED_WORDS.join('|'), 'gi');
function filterText(t) { if (!t || typeof t !== 'string') return t; return t.replace(bannedRegex, '***'); }
function filterMessages(ms) { if (!Array.isArray(ms)) return ms; return ms.map(m => { if (m && typeof m.content === 'string') return Object.assign({}, m, {content: filterText(m.content)}); return m; }); }
app.use(express.json({ limit: '50mb' }));
app.use(express.raw({ type: '*/*', limit: '50mb' }));
function checkAuth(req, res, next) {
  const auth = req.headers['authorization'] || '';
  const token = auth.replace(/^Bearer\s+/i, '');
  if (token !== CLIENT_KEY) return res.status(401).json({ error: { message: 'Invalid API key' } });
  next();
}
app.all('/v1/chat/completions', checkAuth, (req, res) => {
  let body = req.body;
  if (Buffer.isBuffer(body)) { try { body = JSON.parse(body.toString()); } catch(e) { body = {}; } }
  body.model = FORCED_MODEL;
  if (body.messages) body.messages = filterMessages(body.messages);
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
      let sb = '';
      pres.on('data', (chunk) => {
        sb += chunk.toString('utf8');
        let idx;
        while ((idx = sb.indexOf('\n')) >= 0) {
          const line = sb.slice(0, idx); sb = sb.slice(idx + 1);
          if (line.startsWith('data: ') && line !== 'data: [DONE]') {
            try {
              const j = JSON.parse(line.slice(6));
              if (j.choices && j.choices[0] && j.choices[0].delta) {
                const d = j.choices[0].delta;
                if (d.content) d.content = filterText(d.content);
                if (d.reasoning_content) d.reasoning_content = filterText(d.reasoning_content);
              }
              res.write('data: ' + JSON.stringify(j) + '\n\n');
            } catch(e) { res.write(line + '\n'); }
          } else { res.write(line + '\n'); }
        }
      });
      pres.on('end', () => { if (sb) res.write(sb); res.end(); });
    } else {
      let rb = [];
      pres.on('data', (c) => rb.push(c));
      pres.on('end', () => {
        try {
          const j = JSON.parse(Buffer.concat(rb).toString());
          if (j.choices && j.choices[0] && j.choices[0].message) {
            const m = j.choices[0].message;
            if (m.content) m.content = filterText(m.content);
            if (m.reasoning_content) m.reasoning_content = filterText(m.reasoning_content);
          }
          res.json(j);
        } catch(e) { res.send(Buffer.concat(rb)); }
        res.end();
      });
    }
  });
  pr.on('error', (err) => { if (!res.headersSent) res.status(502).json({ error: { message: err.message } }); else res.end(); });
  pr.write(bodyStr); pr.end();
});
app.get('/', (req, res) => { res.type('text/plain').send('DeepSeekOld API Proxy'); });
app.listen(PORT, HOST, () => { console.log('DeepSeekOld on ' + HOST + ':' + PORT + ' model=' + FORCED_MODEL); });
