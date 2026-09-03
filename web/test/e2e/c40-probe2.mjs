// C40 reproduction: load a BROADBAND signal and capture file-mode trace at several
// t/div levels, logging the _drawTrace branch + sampling pixel-column fill height.
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

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
  const { writeFileSync } = await import('node:fs');
  const { server, port } = await startServer();
  const origin = `http://127.0.0.1:${port}`;
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ deviceScaleFactor: 2 });
  const page = await context.newPage();
  page.on('console', (m) => { if (m.type()==='error') console.log('PAGEERR', m.text()); });
  page.on('pageerror', (e) => console.log('PAGEERR', e.message));

  await page.goto(origin + '/index.html', { waitUntil: 'load' });
  await page.waitForSelector('.scope-pane .led-btn', { timeout: 15000 });
  await page.waitForTimeout(600);

  await page.evaluate(async () => {
    window.__traceLog = [];
    const mod = await import('/js/scope/scope-view.js');
    const proto = mod.ScopeView.prototype;
    if (proto.__wrapped) return;
    const orig = proto._drawTrace;
    proto.__wrapped = true;
    proto._drawTrace = function(g,W,H,buf,n,startSample,windowSamples,cols,samplesPerPx,pxPerSample,sampleToY,sinc,hex,dd){
      const p=this.prefs;
      window.__traceLog.push({ fileMode: this.fileMode, n, windowSamples, tDivNow: p?p.oscTimePerDiv.get():null, samplesPerPx: +samplesPerPx.toFixed(2), sinc, branch: samplesPerPx<=5?'lanczos/linear':'minmax-bars' });
      return orig.call(this,g,W,H,buf,n,startSample,windowSamples,cols,samplesPerPx,pxPerSample,sampleToY,sinc,hex,dd);
    };
    const origR = proto.render;
    proto.render = function(buf, info){ const p=this.prefs; const sr=info.inRate; const td=p?p.oscTimePerDiv.get():null; window.__traceLog.push({ RENDER:true, fileMode:this.fileMode, inRate:sr, tDiv: td, computedWS: Math.round(td*10*sr), stack: new Error().stack.split('\n').slice(2,4).join(' | ') }); return origR.call(this, buf, info); };
  });

  // Load a BROADBAND mono signal: 300 Hz fundamental + harmonics + white noise.
  await page.evaluate(async () => {
    const rate=48000, secs=3.0;
    const n=Math.floor(rate*secs), bps=2, dataLen=n*bps;
    const buf=new ArrayBuffer(44+dataLen), dv=new DataView(buf);
    const ascii=(o,s)=>{for(let i=0;i<s.length;i++)dv.setUint8(o+i,s.charCodeAt(i));};
    ascii(0,'RIFF');dv.setUint32(4,36+dataLen,true);ascii(8,'WAVE');ascii(12,'fmt ');dv.setUint32(16,16,true);dv.setUint16(20,1,true);dv.setUint16(22,1,true);dv.setUint32(24,rate,true);dv.setUint32(28,rate*bps,true);dv.setUint16(32,bps,true);dv.setUint16(34,16,true);ascii(36,'data');dv.setUint32(40,dataLen,true);
    for(let i=0;i<n;i++){
      const t=i/rate;
      let s=0.4*Math.sin(2*Math.PI*300*t)+0.2*Math.sin(2*Math.PI*900*t)+0.15*Math.sin(2*Math.PI*4000*t)+0.1*(Math.random()*2-1);
      dv.setInt16(44+i*2,Math.max(-32768,Math.min(32767,Math.round(s*32767))),true);
    }
    const bytes=new Uint8Array(buf);
    const file=new File([bytes],'c40-broadband.wav',{type:'audio/wav'});
    const dt=new DataTransfer();dt.items.add(file);
    const input=document.getElementById('scopeLoadGo');
    input.files=dt.files; input.dispatchEvent(new Event('change',{bubbles:true}));
    await new Promise(r=>setTimeout(r,600));
  });
  await page.waitForTimeout(400);

  const snap = async (name) => {
    const dataUrl = await page.evaluate(() => document.getElementById('scope').toDataURL('image/png'));
    writeFileSync(join(__dirname, name), Buffer.from(dataUrl.split(',')[1], 'base64'));
    console.log('wrote', name);
  };
  // Capture at the loaded default (likely dense Lanczos).
  const renders = await page.evaluate(()=>window.__traceLog.filter(x=>x.RENDER));
  for (const r of renders) console.log('RENDER inRate=' + r.inRate + ' tDiv=' + r.tDiv + ' computedWS=' + r.computedWS + '  stack: ' + r.stack);
  console.log('LOAD trace:', JSON.stringify(await page.evaluate(()=>window.__traceLog.slice(-2))));
  await snap('c40-bb-load.png');

  // Now widen t/div with Ctrl+wheel (zoom time) so samplesPerPx climbs through the
  // Lanczos->minmax boundary. Capture at a couple of steps.
  for (let step = 0; step < 3; step++) {
    await page.evaluate(() => {
      window.__traceLog=[];
      const c = document.getElementById('scope');
      const r = c.getBoundingClientRect();
      for (let k=0;k<2;k++) c.dispatchEvent(new WheelEvent('wheel', { deltaY: 120, ctrlKey: true, clientX: r.left+r.width/2, clientY: r.top+r.height/2, bubbles: true, cancelable: true }));
    });
    await page.waitForTimeout(250);
    console.log(`ZOOM${step} trace:`, JSON.stringify(await page.evaluate(()=>window.__traceLog.slice(-2))));
    await snap(`c40-bb-zoom${step}.png`);
  }

  await browser.close();
  server.close();
}
main().catch(e => { console.log('FATAL', e.stack||e); process.exit(1); });
