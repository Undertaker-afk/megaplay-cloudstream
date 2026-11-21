// Checks that the given m3u8 URLs are live. Usage: node tools/check_m3u8.js <url> [url...]
const urls = process.argv.slice(2);
const headers = {
  'Referer': 'https://hanime.tv/',
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36',
};
(async () => {
  for (const url of urls) {
    try {
      const r = await fetch(url, { headers });
      const text = await r.text();
      const segs = text.split(/\r?\n/).filter(l => l.startsWith('http')).length;
      const key = (text.match(/#EXT-X-KEY:[^\n]*/) || [])[0];
      console.log(`${r.status} ${text.length}B segs=${segs} ${key ? 'AES' : 'NO-KEY'} ${url.slice(0, 60)}...`);
    } catch (e) {
      console.log(`ERR ${e.message} ${url.slice(0, 60)}...`);
    }
  }
})();
