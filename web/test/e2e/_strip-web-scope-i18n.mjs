// One-shot i18n cleanup (sync-java-to-web rule #7): remove the web.scope.* duplicate
// keys (which shadow the real Java scope.* keys) from EVERY messages*.properties so the
// locales stay line-aligned. The base messages.properties was already cleaned by the user.
// Run from web/:  node test/e2e/_strip-web-scope-i18n.mjs
import { readdirSync, readFileSync, writeFileSync } from 'fs';
import { join } from 'path';

const dir = join(process.cwd(), 'i18n');
const files = readdirSync(dir).filter(f => /^messages.*\.properties$/.test(f));
let total = 0;
for (const f of files) {
  const p = join(dir, f);
  const raw = readFileSync(p, 'utf8');
  const eol = raw.includes('\r\n') ? '\r\n' : '\n';
  const lines = raw.split(/\r?\n/);
  const kept = lines.filter((l) => !/^web\.scope\./.test(l));
  const removed = lines.length - kept.length;
  if (removed > 0) {
    writeFileSync(p, kept.join(eol));
    total += removed;
    console.log(`${f}: -${removed}`);
  }
}
console.log(`total web.scope.* lines removed: ${total}`);
