import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const MIME = { '.html':'text/html','.mjs':'text/javascript','.js':'text/javascript','.css':'text/css','.json':'application/json','.webmanifest':'application/manifest+json','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon','.wasm':'application/wasm','.properties':'text/plain' };
function startServer(){const s=http.createServer((req,res)=>{let u=decodeURIComponent((req.url||'/').split('?')[0]);if(u==='/')u='/index.html';const fp=normalize(join(WEB_ROOT,u));if(!fp.startsWith(WEB_ROOT)){res.writeHead(403);res.end();return;}if(!existsSync(fp)||!statSync(fp).isFile()){res.writeHead(404);res.end();return;}res.writeHead(200,{'Content-Type':(MIME[extname(fp).toLowerCase()]||'application/octet-stream')+'; charset=utf-8','Cache-Control':'no-store'});createReadStream(fp).pipe(res);});return new Promise(r=>s.listen(0,'127.0.0.1',()=>r({server:s,port:s.address().port})));}

const { chromium } = await import('playwright');
const { server, port } = await startServer();
const origin = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForTimeout(800);
// Force file mode show on the hscroll so it is visible + laid out.
await page.evaluate(() => { document.getElementById('scopeHScroll').classList.add('show'); });
await page.waitForTimeout(200);
// Force a redraw with the new code path, then scan the painted bitmap for the
// brightest (thumb) pixels to find where the thumb is DRAWN, then compare to where
// the hit-test believes the thumb is by reading the canvas + re-deriving _axisPx.
const info = await page.evaluate(() => {
  const c = document.getElementById('scopeHScroll');
  const r = c.getBoundingClientRect();
  const ctx = c.getContext('2d');
  const W = c.width, H = c.height;
  const img = ctx.getImageData(0, 0, W, H).data;
  // Thumb is rgb(160,160,160)+ ; arrows brighter (200). Find the contiguous run of
  // the MID-grey thumb in the middle band (exclude the two ARROW_SIZE=18 ends).
  const y = Math.floor(H / 2);
  let firstThumb = -1, lastThumb = -1;
  for (let x = 18; x < W - 18; x++) {
    const i = (y * W + x) * 4;
    const lum = (img[i] + img[i + 1] + img[i + 2]) / 3;
    if (lum > 120) { if (firstThumb < 0) firstThumb = x; lastThumb = x; }
  }
  return { rectLeft: r.left, rectWidth: r.width, canvasWidth: W, canvasHeight: H,
           drawnThumbStart: firstThumb, drawnThumbEnd: lastThumb };
});
console.log(JSON.stringify(info, null, 2));
console.log('expected content width =', info.rectWidth - 20, ' actual canvasWidth =', info.canvasWidth,
            info.canvasWidth === Math.round(info.rectWidth - 20) ? 'OK' : 'MISMATCH');
// Now move the real mouse to the screen x at the centre of the DRAWN thumb and
// check the widget reports the thumb hovered (thumbHovered -> redraw with brighter
// thumb rgb(200)). The thumb is drawn at canvas/content x; screen x = rectLeft + x.
const thumbMidContentX = (info.drawnThumbStart + info.drawnThumbEnd) / 2;
const screenX = info.rectLeft + thumbMidContentX;     // paddingLeft = 0
await page.mouse.move(screenX, /* mid height */ 0 + 9 + await page.evaluate(() => document.getElementById('scopeHScroll').getBoundingClientRect().top));
await page.waitForTimeout(120);
const hover = await page.evaluate(() => {
  const c = document.getElementById('scopeHScroll');
  const ctx = c.getContext('2d');
  const W = c.width, H = c.height; const y = Math.floor(H / 2);
  const img = ctx.getImageData(0, 0, W, H).data;
  // Brightest thumb lum: hovered thumb = rgb(200) ~200 lum; idle = 160.
  let maxLum = 0;
  for (let x = 18; x < W - 18; x++) { const i = (y * W + x) * 4; maxLum = Math.max(maxLum, (img[i]+img[i+1]+img[i+2])/3); }
  return { maxThumbLum: Math.round(maxLum) };
});
console.log('hover over drawn thumb -> maxThumbLum =', hover.maxThumbLum, hover.maxThumbLum >= 195 ? 'HOVER-HIT-OK' : 'HOVER-MISS');
await browser.close();
server.close();
