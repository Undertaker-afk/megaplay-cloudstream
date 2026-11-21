// Reproduces the Hanime.tv provider handshake and prints the m3u8 links.
// Usage: node tools/hanime_m3u8.js [slug]
// Optional: HANIME_RELAY=https://your-relay node tools/hanime_m3u8.js [slug]
const crypto = require('crypto');

const slug = process.argv[2] || 'kaifuku-jutsushi-no-yarinaoshi-4';
const base = (process.env.HANIME_RELAY || 'https://auth.hanime.tv').replace(/\/$/, '');
const ORIGIN = 'https://hanime.tv';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36';

const b64u = (b) => Buffer.from(b).toString('base64url');
const unb64u = (s) => Buffer.from(s, 'base64url');

const MSG_KEY = crypto.createHash('sha256').update('htv-insecure-handshake-v1').digest();
const MSG_AAD = Buffer.from('htv-insecure-v1');

function seal(obj) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', MSG_KEY, iv, { authTagLength: 16 });
  cipher.setAAD(MSG_AAD);
  const data = Buffer.concat([cipher.update(JSON.stringify(obj), 'utf8'), cipher.final()]);
  return b64u(JSON.stringify({
    v: 1, alg: 'AES-256-GCM', iv: b64u(iv), tag: b64u(cipher.getAuthTag()), data: b64u(data),
  }));
}

function open(token) {
  const env = JSON.parse(unb64u(token).toString('utf8'));
  const decipher = crypto.createDecipheriv('aes-256-gcm', MSG_KEY, unb64u(env.iv), { authTagLength: 16 });
  decipher.setAAD(MSG_AAD);
  decipher.setAuthTag(unb64u(env.tag));
  return Buffer.concat([decipher.update(unb64u(env.data)), decipher.final()]).toString('utf8');
}

(async () => {
  const time = Math.floor(Date.now() / 1000);
  const sig = crypto.createHash('sha256')
    .update(`${time},Xkdi29,${ORIGIN},mn2,${time}`).digest('hex');
  const token = seal({ timestamp_unix: time, directive: 'htv_player_handshake', slug });
  const headers = {
    'content-type': 'application/json',
    'accept': 'application/json',
    'origin': ORIGIN,
    'referer': `${ORIGIN}/`,
    'user-agent': UA,
    'x-signature-version': 'web2',
    'x-signature': sig,
    'x-time': String(time),
    'x-csrf-token': 'null',
  };
  if (process.env.HANIME_RELAY_SECRET) headers.authorization = `Bearer ${process.env.HANIME_RELAY_SECRET}`;

  const resp = await fetch(`${base}/api/v11/handshake`, {
    method: 'POST', headers, body: JSON.stringify({ token }),
  });
  const xToken = resp.headers.get('x-token');
  console.log(`handshake: HTTP ${resp.status}, x-token ${xToken ? xToken.length : 'MISSING'}`);
  if (resp.status !== 200 || !xToken) {
    console.log((await resp.text()).slice(0, 300));
    process.exit(1);
  }
  const sources = JSON.parse(open(xToken)).sources.filter(s => s.kind === 'normal' && s.src);
  for (const s of sources) {
    const url = s.src.startsWith('http') ? s.src : ORIGIN + s.src;
    console.log(`${s.label} (${s.width}x${s.height}): ${url}`);
  }
})().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
