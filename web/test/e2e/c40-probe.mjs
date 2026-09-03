// C40 diagnostic: load a sine WAV (file mode) and inspect which trace branch
// _drawTrace takes + the canvas pixel stats, vs the live trace. Headless probe.
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const MIME = { '.html':'text/html', '.mjs':'text/javascript', '.js':'text/javascript', '.css':'text/css', '.json':'application/json', '.webmanifest':'application/manifest+json', '.svg':'image/svg+xml', '.png':'image/png', '.ico':'image/x-icon', '.wasm':'application/wasm', '.properties':'text/plain' };
function startServer() {
  const server = http.createServer((req, res) => {
    let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    if (urlPath === '/') urlPath = '/index.html';
    const filePath = normalize(join(WEB_ROOT, urlPath));
    if (!filePath.startsWith(WEB_ROOT) || !existsSync(filePath) || !statSync(filePath).isFile()) { res.writeHead(404); res.end('nf'); return; }
    res.writeHead(200, { 'Content-Type': (MIME[extname(filePath).toLowerCase()]||'application/octet-stream')+'; charset=utf-8', 'Cache-Control':'no-store' });
    createReadStream(filePath).pipe(res);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, port: server.address().port })));
}

async function main() {
  const { chromium } = await import('playwright');
  const { server, port } = await startServer();
  const origin = `http://127.0.0.1:${port}`;
  const fakeWav = join(__dirname, 'fake-audio.wav');
  makeFakeAudioWav(fakeWav);
  const browser = await chromium.launch({ headless: true, args: ['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required',`--use-file-for-fake-audio-capture=${fakeWav}`] });
  const context = await browser.newContext({ permissions: ['microphone'], deviceScaleFactor: 2 });
  await context.grantPermissions(['microphone'], { origin });
  const page = await context.newPage();
  page.on('console', (m) => { if (m.type()==='error') console.log('PAGEERR', m.text()); });
  page.on('pageerror', (e) => console.log('PAGEERR', e.message));

  await page.goto(origin + '/index.html', { waitUntil: 'load' });
  await page.waitForSelector('.scope-pane .led-btn', { timeout: 15000 });
  await page.waitForTimeout(600);

  // Patch ScopeView.prototype._drawTrace to log branch + samplesPerPx for EVERY
  // instance (the running scopeView + the offscreen shot clone share the proto).
  await page.evaluate(async () => {
    window.__traceLog = [];
    const mod = await import('/js/scope/scope-view.js');
    const proto = mod.ScopeView.prototype;
    if (proto.__wrapped) return;
    const orig = proto._drawTrace;
    proto.__wrapped = true;
    proto._drawTrace = function(g,W,H,buf,n,startSample,windowSamples,cols,samplesPerPx,pxPerSample,sampleToY,sinc,hex,dd){
      const MAX=5;
      window.__traceLog.push({ fileMode: this.fileMode, n, startSample, windowSamples, cols, samplesPerPx, pxPerSample, sinc, branch: samplesPerPx<=MAX?'lanczos/linear':'minmax-bars' });
      return orig.call(this,g,W,H,buf,n,startSample,windowSamples,cols,samplesPerPx,pxPerSample,sampleToY,sinc,hex,dd);
    };
  });

  const { writeFileSync } = await import('node:fs');
  const snapEarly = async (name) => {
    const dataUrl = await page.evaluate(() => document.getElementById('scope').toDataURL('image/png'));
    writeFileSync(join(__dirname, name), Buffer.from(dataUrl.split(',')[1], 'base64'));
    console.log('wrote', name);
  };

  // --- Live first ---
  await page.click('.scope-pane .led-btn');
  await page.waitForTimeout(1500);
  const liveLog = await page.evaluate(() => { const l = window.__traceLog.slice(-4); window.__traceLog=[]; return l; });
  console.log('LIVE _drawTrace calls:', JSON.stringify(liveLog, null, 1));
  const liveCanvas = await page.evaluate(() => { const c=document.getElementById('scope'); return { w:c.width, h:c.height, cw:c.clientWidth, ch:c.clientHeight, dpr: window.devicePixelRatio, expectedW: Math.round(c.clientWidth*window.devicePixelRatio), expectedH: Math.round(c.clientHeight*window.devicePixelRatio) }; });
  console.log('LIVE canvas', JSON.stringify(liveCanvas));
  await snapEarly('c40-live.png');

  // --- Now load a sine WAV (file mode) ---
  await page.evaluate(async () => {
    const rate=48000, freq=1000, amp=0.5, secs=3.0;
    const n=Math.floor(rate*secs), bps=2, dataLen=n*bps;
    const buf=new ArrayBuffer(44+dataLen), dv=new DataView(buf);
    const ascii=(o,s)=>{for(let i=0;i<s.length;i++)dv.setUint8(o+i,s.charCodeAt(i));};
    ascii(0,'RIFF');dv.setUint32(4,36+dataLen,true);ascii(8,'WAVE');ascii(12,'fmt ');dv.setUint32(16,16,true);dv.setUint16(20,1,true);dv.setUint16(22,1,true);dv.setUint32(24,rate,true);dv.setUint32(28,rate*bps,true);dv.setUint16(32,bps,true);dv.setUint16(34,16,true);ascii(36,'data');dv.setUint32(40,dataLen,true);
    for(let i=0;i<n;i++){const s=amp*Math.sin(2*Math.PI*freq*i/rate);dv.setInt16(44+i*2,Math.round(s*32767),true);}
    const bytes=new Uint8Array(buf);
    const file=new File([bytes],'c40-sine.wav',{type:'audio/wav'});
    const dt=new DataTransfer();dt.items.add(file);
    const input=document.getElementById('scopeLoadGo');
    input.files=dt.files; input.dispatchEvent(new Event('change',{bubbles:true}));
    await new Promise(r=>setTimeout(r,600));
  });
  await page.waitForTimeout(600);
  const fileLog = await page.evaluate(() => window.__traceLog.slice(-4));
  console.log('FILE _drawTrace calls:', JSON.stringify(fileLog, null, 1));
  const fileCanvas = await page.evaluate(() => { const c=document.getElementById('scope'); return { w:c.width, h:c.height, cw:c.clientWidth, ch:c.clientHeight, dpr: window.devicePixelRatio }; });
  console.log('FILE canvas', JSON.stringify(fileCanvas));

  const snap = snapEarly;
  await snap('c40-filemode.png');

  // Zoom OUT in file mode by scrolling the wheel on the canvas (t/div up). Capture
  // a wide view where many periods fall in the window (samplesPerPx may exceed 5).
  for (let z = 0; z < 8; z++) {
    await page.evaluate(() => {
      const c = document.getElementById('scope');
      const r = c.getBoundingClientRect();
      c.dispatchEvent(new WheelEvent('wheel', { deltaY: 120, clientX: r.left + r.width/2, clientY: r.top + r.height/2, bubbles: true }));
    });
    await page.waitForTimeout(120);
  }
  await page.waitForTimeout(300);
  const fileLogWide = await page.evaluate(() => window.__traceLog.slice(-2));
  console.log('FILE WIDE _drawTrace:', JSON.stringify(fileLogWide, null, 1));
  await snap('c40-filemode-wide.png');

  await browser.close();
  server.close();
}
main().catch(e => { console.log('FATAL', e.stack||e); process.exit(1); });
