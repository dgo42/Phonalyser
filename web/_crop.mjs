import { chromium } from 'playwright';
import { readFileSync } from 'fs';
const [,, src, out, x, y, w, h, scale] = process.argv;
const b64 = readFileSync(src).toString('base64');
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: Math.ceil(+w * +scale), height: Math.ceil(+h * +scale) } });
await page.setContent(`<body style="margin:0;padding:0"><img id=i src="data:image/png;base64,${b64}"></body>`);
await page.evaluate(({ x, y, scale }) => {
  const img = document.getElementById('i');
  img.style.position = 'absolute';
  img.style.left = (-x * scale) + 'px';
  img.style.top = (-y * scale) + 'px';
  img.style.width = (img.naturalWidth * scale) + 'px';
  img.style.imageRendering = 'pixelated';
}, { x: +x, y: +y, scale: +scale });
await page.screenshot({ path: out, clip: { x: 0, y: 0, width: Math.ceil(+w * +scale), height: Math.ceil(+h * +scale) } });
await browser.close();
console.log('cropped', out);
