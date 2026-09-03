/*
 * Phonalyser web - file play is refused when the file's format is not the output's.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * A file is played at its OWN rate and depth - nothing is resampled and nothing is re-quantised -
 * so the lane is opened at the FILE's format. On a backend whose two directions share one clock
 * that drags the whole session onto the file's rate, and on any backend it silently replaces the
 * format the operator configured. So a mismatch is refused before ANY lane path, and the refusal
 * names both formats: "set the output to the file's format" is the operator's next move and they
 * cannot make it until they are told which two numbers disagree.
 *
 * The two things the Java round caught are pinned here too, because the web runs its own port of
 * MessageFormat: a rate handed over as a NUMBER comes back grouped ("44,100 Hz"), which no combo
 * in this application writes; and a lone apostrophe in a pattern is MessageFormat's quoting
 * character, which silently eats the quote and stops the placeholders substituting.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { GeneratorController } from '../js/generator/generator-controller.js';
import { Preferences } from '../js/store/preferences.js';
import { initBase, t } from '../js/i18n/i18n.js';

// The REAL English bundle, off the disk: what the operator is told is the whole subject here, so a
// test that asserted against key names would pass while the screen said nothing.
const I18N_DIR = fileURLToPath(new URL('../i18n/', import.meta.url));
globalThis.fetch = async (path) => {
  const name = String(path).replace(/^i18n\//, '');
  try {
    return { ok: true, text: async () => readFileSync(I18N_DIR + name, 'utf8') };
  } catch {
    return { ok: false, text: async () => '' };
  }
};
await initBase();

const CONFIGURED_RATE = 48000;
const CONFIGURED_BITS = 24;
const FILE_NAME = 'sweep-clip.wav';

/** The controller alone - it needs neither a sink nor a device to answer this. */
function controller() {
  return new GeneratorController(Preferences.instance(), { status: () => {} });
}

/** The configured output, in the per-backend block the controller reads through the singleton. */
function configureOutput(rate, bits) {
  const out = Preferences.instance().current();
  out.outputSampleRate = rate;
  out.outputBitDepth = bits;
  return out;
}

test('a file in the configured format is played', () => {
  configureOutput(CONFIGURED_RATE, CONFIGURED_BITS);
  assert.equal(
    controller().filePlayFormatMismatch(FILE_NAME, CONFIGURED_RATE, CONFIGURED_BITS), null,
    'a file that matches the configured output is exactly what this backend plays');
});

test('a different RATE is refused, and both formats are named ungrouped', () => {
  configureOutput(CONFIGURED_RATE, CONFIGURED_BITS);

  const refusal = controller().filePlayFormatMismatch(FILE_NAME, 44100, CONFIGURED_BITS);

  assert.ok(refusal != null, 'a 44.1 kHz file on a 48 kHz output cannot be played as it is');
  assert.ok(refusal.includes(FILE_NAME), `the operator is told WHICH file: ${refusal}`);
  assert.ok(refusal.includes('44100') && refusal.includes('48000'),
    `both rates, ungrouped - a "44,100" names a rate no combo shows: ${refusal}`);
  assert.ok(!refusal.includes('{'),
    `every placeholder substituted - a lone apostrophe stops that silently: ${refusal}`);
});

test('a different DEPTH alone is refused too', () => {
  configureOutput(CONFIGURED_RATE, CONFIGURED_BITS);

  const refusal = controller().filePlayFormatMismatch(FILE_NAME, CONFIGURED_RATE, 16);

  assert.ok(refusal != null, 'the depth decides the lane\'s format just as the rate does');
  assert.ok(refusal.includes('16') && refusal.includes('24'),
    `both depths reach the operator: ${refusal}`);
});

test('a caller with NO depth to declare is judged on the rate alone', () => {
  configureOutput(CONFIGURED_RATE, CONFIGURED_BITS);
  const gen = controller();
  // The bench path uploads the raw file and the far end decodes it, so the depth is not this
  // client's to know. An unknown depth is not evidence of a mismatch.
  assert.equal(gen.filePlayFormatMismatch(FILE_NAME, CONFIGURED_RATE, undefined), null);
  assert.ok(gen.filePlayFormatMismatch(FILE_NAME, 44100, undefined) != null,
    'the rate is still checked - that one the caller does know');
});

test('the refusal follows the CONFIGURED format, not a fixed one', () => {
  const gen = controller();
  configureOutput(CONFIGURED_RATE, CONFIGURED_BITS);
  assert.equal(gen.filePlayFormatMismatch(FILE_NAME, CONFIGURED_RATE, CONFIGURED_BITS), null);

  // The operator makes the move the message asks for.
  configureOutput(96000, 32);
  assert.ok(gen.filePlayFormatMismatch(FILE_NAME, CONFIGURED_RATE, CONFIGURED_BITS) != null,
    'the file that fitted the old configuration no longer fits this one');
  assert.equal(gen.filePlayFormatMismatch(FILE_NAME, 96000, 32), null,
    'and the one that fits the new configuration is played');
});

test('playFileBuffer REFUSES before any LOCAL lane path, and says so through filePlayError', async () => {
  configureOutput(CONFIGURED_RATE, CONFIGURED_BITS);
  const gen = controller();
  // A LOCAL sink: no emittedHz, so the bench branch answers null and the refusal below is the
  // local path's. open() must never be reached for a file that is refused.
  gen._openPlayback = () => ({
    open: async () => { throw new Error('no lane may be opened for a refused file'); },
  });
  const channels = [new Float32Array(8), new Float32Array(8)];

  await gen.playFileBuffer(channels, 44100, false, { name: FILE_NAME, bytes: new Uint8Array(0) }, 16);

  assert.ok(gen.filePlayError != null, 'the refusal is where the pane reads it');
  assert.equal(gen.filePlayError,
    t('generator.error.playFile.formatMismatch', FILE_NAME, '44100', '16', '48000', '24'));
  assert.equal(gen._lastFile, null, 'and nothing was retained for a prefs-bounce resume');
});

test('the ENGINE FACADE forwards the depth - the arity the pane actually calls', async () => {
  // The shipped path is pane -> AudioEngine.playFileBuffer -> controller. A facade that forwards
  // four of the five arguments drops the depth silently: the refusal then compares against
  // undefined, the depth half is dead, and a rate refusal prints the word undefined for {2}.
  const { AudioEngine } = await import('../js/shell/audio-engine.js');
  configureOutput(CONFIGURED_RATE, CONFIGURED_BITS);
  const engine = Object.create(AudioEngine.prototype);
  const seen = [];
  engine._gen = { playFileBuffer: async (...args) => { seen.push(args); } };

  await engine.playFileBuffer([new Float32Array(4)], 44100, false, { name: FILE_NAME }, 16);

  assert.equal(seen.length, 1);
  assert.equal(seen[0].length, 5, 'all five arguments cross the facade');
  assert.equal(seen[0][4], 16, 'and the fifth is the decoded depth');
});

test('the pattern doubles its apostrophes, so the web MessageFormat port renders one', () => {
  // The web port implements the same quoting rules as java.text.MessageFormat ('' -> ', and a lone
  // quote opens a verbatim span), which is why the bundles carry the Java values byte-for-byte.
  const rendered = t('generator.error.playFile.formatMismatch', FILE_NAME, '44100', '16', '48000', '24');
  assert.ok(rendered.includes("file's format"),
    `the apostrophe survives as exactly one: ${rendered}`);
  assert.ok(!rendered.includes("''"), 'and never as the doubled pair the pattern carries');
});
