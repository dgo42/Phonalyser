// Generates a 16-bit PCM mono WAV of a swept/modulated sine so that Chromium's
// --use-file-for-fake-audio-capture flag feeds the scope a NON-silent, CHANGING
// signal (the built-in fake device delivers pure silence on this platform, which
// makes the measurement-freeze assertion untestable). The amplitude is modulated
// so successive measurement frames differ over ~1s.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));

export function makeFakeAudioWav(outPath, {
  sampleRate = 48000, seconds = 6, freq = 440, modHz = 1.5,
} = {}) {
  const n = Math.floor(sampleRate * seconds);
  const bytesPerSample = 2;
  const dataLen = n * bytesPerSample;
  const buf = Buffer.alloc(44 + dataLen);
  // RIFF header
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + dataLen, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20);   // PCM
  buf.writeUInt16LE(1, 22);                                                     // mono
  buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * bytesPerSample, 28);
  buf.writeUInt16LE(bytesPerSample, 32); buf.writeUInt16LE(16, 34);
  buf.write('data', 36); buf.writeUInt32LE(dataLen, 40);
  for (let i = 0; i < n; i++) {
    const t = i / sampleRate;
    // amplitude modulation (slow) so per-frame Vpp/Vrms keep changing
    const amp = 0.35 * (0.6 + 0.4 * Math.sin(2 * Math.PI * modHz * t));
    const s = amp * Math.sin(2 * Math.PI * freq * t);
    buf.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(s * 32767))), 44 + i * 2);
  }
  writeFileSync(outPath, buf);
  return outPath;
}

// Allow standalone generation: `node make-fake-audio.mjs`
if (import.meta.url === `file://${process.argv[1]}` ||
    process.argv[1] && process.argv[1].endsWith('make-fake-audio.mjs')) {
  const out = join(__dirname, 'fake-audio.wav');
  makeFakeAudioWav(out);
  console.log('wrote ' + out);
}
