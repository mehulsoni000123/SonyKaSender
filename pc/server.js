// Sony Ka Sender — PC receiver + desk server (no external dependencies)
// Receives files flicked from the phone, shows them landing on a desk window.
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const dgram = require('dgram');
const { exec, spawn } = require('child_process');

const PORT = Number(process.env.SKS_PORT || 7777);
const DISCOVERY_PORT = 7778;
const HOME = os.homedir();
const RECEIVE_DIR = process.env.SKS_DIR || path.join(HOME, 'Downloads', 'Sony Ka Sender');
const DESK_FILE = path.join(RECEIVE_DIR, '.desk.json');
const DESK_HTML = path.join(__dirname, 'desk', 'index.html');
const PC_NAME = (process.env.SKS_NAME || `${os.userInfo().username}'s PC`).replace(/^./, c => c.toUpperCase());

fs.mkdirSync(RECEIVE_DIR, { recursive: true });

// ---------- desk state ----------
let desk = { cards: [] };
try { desk = JSON.parse(fs.readFileSync(DESK_FILE, 'utf8')); } catch { /* fresh desk */ }
desk.cards = (desk.cards || []).filter(c => c.kind === 'link' || c.kind === 'note' || (c.file && fs.existsSync(path.join(RECEIVE_DIR, c.file))));
let saveTimer = null;
function saveDesk() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => fs.writeFile(DESK_FILE, JSON.stringify(desk, null, 1), () => {}), 300);
}

// ---------- live events (SSE) ----------
const clients = new Set();
function broadcast(evt) {
  const line = `data: ${JSON.stringify(evt)}\n\n`;
  for (const res of clients) res.write(line);
}

// ---------- helpers ----------
function lanIPs() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal) out.push(a.address);
  }
  // real Wi-Fi/LAN first; VPN ranges (Tailscale 100.64/10) last
  const score = ip => /^192\.168\./.test(ip) ? 0 : /^10\./.test(ip) ? 1 : /^172\.(1[6-9]|2\d|3[01])\./.test(ip) ? 2 : /^100\./.test(ip) ? 9 : 5;
  return out.sort((a, b) => score(a) - score(b));
}
function safeName(name) {
  let n = path.basename(String(name || 'file')).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').trim();
  if (!n || n === '.' || n === '..') n = 'file';
  return n.slice(0, 180);
}
function uniquePath(dir, name) {
  const ext = path.extname(name);
  const base = name.slice(0, name.length - ext.length);
  let p = path.join(dir, name);
  for (let i = 2; fs.existsSync(p); i++) p = path.join(dir, `${base} (${i})${ext}`);
  return p;
}
function kindOf(mime, name) {
  mime = mime || '';
  if (mime.startsWith('image/') || /\.(jpe?g|png|gif|webp|heic|heif|bmp|avif)$/i.test(name)) return 'image';
  if (mime.startsWith('video/') || /\.(mp4|mov|m4v|webm|3gp|mkv)$/i.test(name)) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  return 'doc';
}
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.mp4': 'video/mp4', '.mov': 'video/quicktime', '.m4v': 'video/mp4', '.webm': 'video/webm', '.pdf': 'application/pdf', '.ico': 'image/x-icon', '.mp3': 'audio/mpeg', '.heic': 'image/heic' };
function readJson(req, limit = 4e6) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => { size += c.length; if (size > limit) { reject(new Error('too big')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}
function send(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify(obj));
}
function openWithShell(target) {
  // "start" via cmd handles files, folders and URLs with the default app
  spawn('cmd.exe', ['/c', 'start', '""', target], { detached: true, stdio: 'ignore', windowsVerbatimArguments: false }).unref();
}
function landingSpot() {
  // pick the most open spot near the middle of the desk (cards in flight count too)
  const taken = [...desk.cards, ...pending.values()];
  const rot = (Math.random() - 0.5) * 12;
  if (!taken.length) return { x: 0.5 + (Math.random() - 0.5) * 0.04, y: 0.5 + (Math.random() - 0.5) * 0.04, rot };
  let best = null, bestScore = -Infinity;
  for (let i = 0; i < 160; i++) {
    const x = 0.16 + Math.random() * 0.68, y = 0.24 + Math.random() * 0.46;
    const clear = Math.min(...taken.map(c => Math.hypot((c.x - x) * 1.6, c.y - y)));   // desk is wider than tall
    const score = Math.min(clear, 0.32) - Math.hypot(x - 0.5, y - 0.5) * 0.25;         // open, but stay central
    if (score > bestScore) { bestScore = score; best = { x, y }; }
  }
  return { ...best, rot };
}

const pending = new Map(); // id -> announced card (before upload completes)

// ---------- HTTP ----------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  try {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST' });
      return res.end();
    }
    if (p === '/' || p === '/desk') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      return fs.createReadStream(DESK_HTML).pipe(res);
    }
    if (p.startsWith('/static/')) {
      const f = path.join(__dirname, 'desk', path.basename(p));
      if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(f).toLowerCase()] || 'application/octet-stream' });
      return fs.createReadStream(f).pipe(res);
    }
    if (p === '/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      res.write(`data: ${JSON.stringify({ type: 'hello', name: PC_NAME, ips: lanIPs(), port: PORT, dir: RECEIVE_DIR })}\n\n`);
      clients.add(res);
      const ka = setInterval(() => res.write(': ka\n\n'), 20000);
      req.on('close', () => { clients.delete(res); clearInterval(ka); });
      return;
    }
    // install page for the phone: http://<pc-ip>:7777/get
    const APK = path.join(__dirname, '..', 'SonyKaSender.apk');
    if (p === '/SonyKaSender.apk' && fs.existsSync(APK)) {
      res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': fs.statSync(APK).size, 'Content-Disposition': 'attachment; filename="SonyKaSender.apk"' });
      return fs.createReadStream(APK).pipe(res);
    }
    if (p === '/get') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Get Sony Ka Sender</title>
<body style="margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:18px;background:linear-gradient(#1d1550,#2b1b6e 45%,#0f1440);color:#f3f1ff;font-family:system-ui,sans-serif;text-align:center;padding:24px;box-sizing:border-box">
<img src="/static/icon.png" width="110" style="border-radius:26px"><h1 style="margin:6px 0 0;font-size:26px">Sony Ka Sender</h1>
<p style="margin:0;opacity:.7;max-width:300px">Tap the button, then open the downloaded file and tap <b>Install</b>.</p>
<a href="/SonyKaSender.apk" style="padding:16px 34px;border-radius:999px;background:#7b5cff;color:#fff;text-decoration:none;font-weight:700;font-size:17px">Download app</a></body>`);
    }
    if (p === '/api/info')return send(res, 200, { app: 'SonyKaSender', name: PC_NAME, port: PORT, ips: lanIPs() });
    if (p === '/api/desk' && req.method === 'GET') return send(res, 200, desk);
    if (p === '/api/desk' && req.method === 'POST') {
      const body = await readJson(req);
      if (Array.isArray(body.cards)) {
        const byId = new Map(desk.cards.map(c => [c.id, c]));
        for (const u of body.cards) { const c = byId.get(u.id); if (c) Object.assign(c, { x: u.x, y: u.y, rot: u.rot, scale: u.scale, z: u.z }); }
        saveDesk();
      }
      return send(res, 200, { ok: true });
    }

    // Phone announces a file before streaming it, so the desk can start the flight instantly
    if (p === '/api/announce' && req.method === 'POST') {
      const b = await readJson(req);
      const card = {
        id: String(b.id || Date.now()), name: safeName(b.name), mime: b.mime || '', size: Number(b.size) || 0,
        kind: kindOf(b.mime, b.name || ''), thumb: typeof b.thumb === 'string' && b.thumb.length < 600000 ? b.thumb : null,
        from: String(b.from || 'Phone').slice(0, 60), ...landingSpot(), scale: 1, ts: Date.now(),
      };
      pending.set(card.id, card);
      broadcast({ type: 'incoming', card });
      return send(res, 200, { ok: true });
    }

    if (p === '/api/upload' && req.method === 'POST') {
      const id = String(url.searchParams.get('id') || Date.now());
      const name = safeName(decodeURIComponent(url.searchParams.get('name') || 'file'));
      const mime = url.searchParams.get('mime') || '';
      const total = Number(req.headers['content-length'] || url.searchParams.get('size') || 0);
      const dest = uniquePath(RECEIVE_DIR, name);
      const tmp = dest + '.part';
      const out = fs.createWriteStream(tmp);
      let got = 0, lastSent = 0;
      req.on('data', c => {
        got += c.length;
        const now = Date.now();
        if (total && now - lastSent > 120) { lastSent = now; broadcast({ type: 'progress', id, p: got / total }); }
      });
      req.pipe(out);
      out.on('finish', () => {
        if (total && got < total) { fs.unlink(tmp, () => {}); broadcast({ type: 'failed', id }); return send(res, 400, { ok: false, error: 'incomplete' }); }
        fs.renameSync(tmp, dest);
        const base = pending.get(id) || { id, name, mime, kind: kindOf(mime, name), ...landingSpot(), scale: 1, from: 'Phone', ts: Date.now() };
        pending.delete(id);
        const card = { ...base, file: path.basename(dest), name: path.basename(dest), size: got };
        delete card.thumb;
        desk.cards = desk.cards.filter(c => c.id !== id);
        desk.cards.push(card);
        saveDesk();
        broadcast({ type: 'landed', card });
        console.log(`[received] ${card.name} (${(got / 1048576).toFixed(2)} MB) from ${card.from}`);
        send(res, 200, { ok: true, saved: dest });
      });
      req.on('aborted', () => { out.destroy(); fs.unlink(tmp, () => {}); broadcast({ type: 'failed', id }); });
      return;
    }

    if (p === '/api/text' && req.method === 'POST') {
      const b = await readJson(req);
      const text = String(b.text || '').slice(0, 20000);
      const m = text.match(/https?:\/\/[^\s]+/i);
      const card = { id: String(b.id || Date.now()), kind: m ? 'link' : 'note', text, url: m ? m[0] : null, name: m ? new URL(m[0]).hostname.replace(/^www\./, '') : 'Note', from: String(b.from || 'Phone').slice(0, 60), ...landingSpot(), scale: 1, ts: Date.now() };
      desk.cards.push(card); saveDesk();
      broadcast({ type: 'incoming', card });
      setTimeout(() => broadcast({ type: 'landed', card }), 50);
      if (m && b.open !== false) setTimeout(() => openWithShell(m[0]), 1300);
      return send(res, 200, { ok: true });
    }

    if (p.startsWith('/files/')) {
      const f = path.join(RECEIVE_DIR, path.basename(decodeURIComponent(p.slice(7))));
      if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
      const st = fs.statSync(f);
      const type = MIME[path.extname(f).toLowerCase()] || 'application/octet-stream';
      const range = req.headers.range;
      if (range) {
        const [s, e] = range.replace('bytes=', '').split('-');
        const start = Number(s); const end = e ? Number(e) : st.size - 1;
        res.writeHead(206, { 'Content-Type': type, 'Content-Range': `bytes ${start}-${end}/${st.size}`, 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1 });
        return fs.createReadStream(f, { start, end }).pipe(res);
      }
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Accept-Ranges': 'bytes' });
      return fs.createReadStream(f).pipe(res);
    }

    // ---- desk actions (only from this PC) ----
    const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
    if (p.startsWith('/api/') && !local && ['/api/open', '/api/reveal', '/api/save-folder', '/api/remove', '/api/open-dir'].includes(p)) return send(res, 403, { ok: false });

    if (p === '/api/open' && req.method === 'POST') {
      const b = await readJson(req);
      const c = desk.cards.find(x => x.id === b.id);
      if (!c) return send(res, 404, { ok: false });
      if (c.url) openWithShell(c.url); else if (c.file) openWithShell(path.join(RECEIVE_DIR, c.file));
      return send(res, 200, { ok: true });
    }
    if (p === '/api/reveal' && req.method === 'POST') {
      const b = await readJson(req);
      const c = desk.cards.find(x => x.id === b.id);
      if (c && c.file) spawn('explorer.exe', [`/select,${path.join(RECEIVE_DIR, c.file)}`], { detached: true, stdio: 'ignore' }).unref();
      return send(res, 200, { ok: true });
    }
    if (p === '/api/open-dir' && req.method === 'POST') { spawn('explorer.exe', [RECEIVE_DIR], { detached: true, stdio: 'ignore' }).unref(); return send(res, 200, { ok: true }); }
    if (p === '/api/remove' && req.method === 'POST') {
      // Removes cards from the desk only. Files stay in the received folder.
      const b = await readJson(req);
      const ids = new Set(b.ids || []);
      desk.cards = desk.cards.filter(c => !ids.has(c.id)); saveDesk();
      return send(res, 200, { ok: true });
    }
    if (p === '/api/save-folder' && req.method === 'POST') {
      const b = await readJson(req);
      const roots = { Desktop: path.join(HOME, 'Desktop'), Downloads: path.join(HOME, 'Downloads'), Pictures: path.join(HOME, 'Pictures'), Documents: path.join(HOME, 'Documents') };
      let root = roots[b.where] || roots.Desktop;
      if (!fs.existsSync(root)) { const od = path.join(HOME, 'OneDrive', path.basename(root)); if (fs.existsSync(od)) root = od; }
      const folder = uniquePath(root, safeName(b.name || 'New collection'));
      fs.mkdirSync(folder, { recursive: true });
      const ids = new Set(b.ids || []);
      const links = [];
      for (const c of desk.cards.filter(x => ids.has(x.id))) {
        if (c.file) fs.copyFileSync(path.join(RECEIVE_DIR, c.file), uniquePath(folder, c.file));
        else if (c.text) links.push(c.text);
      }
      if (links.length) fs.writeFileSync(path.join(folder, 'links and notes.txt'), links.join('\n\n'));
      spawn('explorer.exe', [folder], { detached: true, stdio: 'ignore' }).unref();
      return send(res, 200, { ok: true, folder });
    }

    res.writeHead(404); res.end('not found');
  } catch (e) {
    console.error(e);
    if (!res.headersSent) send(res, 500, { ok: false, error: String(e.message || e) });
  }
});

server.on('error', e => {
  if (e.code === 'EADDRINUSE') { console.log('Sony Ka Sender is already running.'); process.exit(0); }
  throw e;
});
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Sony Ka Sender desk running → http://localhost:${PORT}`);
  console.log(`PC name: ${PC_NAME} | LAN: ${lanIPs().join(', ') || 'none'} | saving to ${RECEIVE_DIR}`);
});

// ---------- discovery: phone broadcasts "SKS?" on UDP 7778, we answer ----------
const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
udp.on('message', (msg, rinfo) => {
  if (msg.toString().startsWith('SKS?')) {
    const reply = Buffer.from(JSON.stringify({ app: 'SonyKaSender', name: PC_NAME, port: PORT, ips: lanIPs() }));
    udp.send(reply, rinfo.port, rinfo.address);
  }
});
udp.on('error', e => console.log('discovery off:', e.message));
udp.bind(DISCOVERY_PORT, () => { try { udp.setBroadcast(true); } catch {} });
