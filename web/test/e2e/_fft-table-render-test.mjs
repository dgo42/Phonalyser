// Deterministic render test for the THD/IMD tables now painted by the fft-view canvas
// (Java FftView.drawDistortionTable / drawImdTable). Feeds synthetic results straight into
// the view's draw methods through a recording 2D-context stub and asserts the exact strings -
// proving the THD/IMD audit fixes (verbatim harmonic %, dBV N row, IMDpwr label, toFixed(8),
// Span row, ' A' suffix, ENOB bits, the distinguishable-signal gate) without needing live audio.
import { FftView } from '../../js/fft/fft-view.js';
import { MessageBus } from '../../js/bus/message-bus.js';
import { Events } from '../../js/bus/events.js';

let pass = 0, fail = 0;
const ok = (name, cond) => { if (cond) { pass++; } else { fail++; console.log('FAIL:', name); } };
const has = (out, s) => out.includes(s) || ok('(missing) ' + s, false) === undefined && false;
const expect = (out, name, s) => ok(`${name} -> "${s}"`, out.includes(s));

// Recording 2D-context stub: collects every fillText string.
function stubCtx() {
  const texts = [];
  const noop = () => {};
  return { texts, font: '', fillStyle: '', strokeStyle: '', lineJoin: '', lineWidth: 0, textAlign: '', textBaseline: '',
    measureText: (s) => ({ width: String(s).length * 7 }), fillText: (s) => texts.push(String(s)), strokeText: noop,
    // Path / rect primitives exercised by render()'s graticule paint (empty-frame path).
    fillRect: noop, strokeRect: noop, beginPath: noop, moveTo: noop, lineTo: noop, stroke: noop, fill: noop, arc: noop };
}
const prop = (v) => ({ get: () => v });
const prefs = {
  fftThdMaxHarmonic: prop(9), dbvOffsetDb: 0,
  fftFundFromGenerator: prop(false), genFrequencyHz: prop(1000),
  genSnapToFftBin: prop(false), fftLength: prop(65536),
  genDualToneFreq1Hz: prop(1000), genDualToneFreq2Hz: prop(1100),
  fftDistortionTableVisible: prop(true),
  fftManualFundEnabled: prop(false),
  fftChannel: prop('L'),   // the ANALYZED channel every dBFS->display conversion de-references with
  getFftChannel() { return 'L'; },
  fftDistortionUnit: prop('DBV'),   // the THD table's level column unit
  getAdcFsVoltageRms() { return 1.0; },
  save() {},
  // Axis / magnitude / colour props read by render() - the constructor now paints the
  // empty graticule on its first paint (Java FftView.onPaint), so the full render path
  // runs before any result and these must resolve.
  fftLogFreqAxis: prop(true), fftFreqMinHz: prop(10), fftFreqMaxHz: prop(20000),
  fftMagTop: prop(0), fftMagBottom: prop(-200), fftMagUnit: prop('DBFS'),
  fftChartBackgroundColor: prop(0xffffff),
  fftDistMinEnabled: prop(false), fftDistMinHz: prop(20),
  fftDistMaxEnabled: prop(false), fftDistMaxHz: prop(20000),
  fftLineColor: prop(0x000000), fftLineWidth: prop(1),
  fftHarmonicDotColor: prop(0xff0000), fftHarmonicDotDiameter: prop(6),
  fftCalcMaxHarmonic: prop(9), fftBeforeCalDotColor: prop(0x888888),
  // Mirrors Preferences.convertFromDbFs (draw-time unit relabel of the y-axis ticks).
  binBwSqrt: 1,
  // The analysed channel's dBV offset (Preferences.getDbvOffsetDb(ch)) - one offset here.
  getDbvOffsetDb() { return this.dbvOffsetDb; },
  convertFromDbFs(dbFs, unit, binBwSqrt = null) {
    switch (unit) {
      case 'DBV': return dbFs + this.dbvOffsetDb;
      case 'V': return Math.pow(10.0, (dbFs + this.dbvOffsetDb) / 20.0);
      case 'V_SQRT_HZ':
        return Math.pow(10.0, (dbFs + this.dbvOffsetDb) / 20.0)
          / (binBwSqrt != null ? binBwSqrt : this.binBwSqrt);
      default: return dbFs;
    }
  },
};
// dataset / tabIndex: the drag-select zoom (RectZoom) marks and focuses its host canvas.
const canvas = { getContext: () => stubCtx(), addEventListener: () => {}, style: {}, dataset: {},
  tabIndex: 0, clientWidth: 1200, clientHeight: 440, width: 1200, height: 440 };
// The canvas readouts resolve the UI font from the --ui-font* CSS variables (ui-font.js); headless
// there is no stylesheet, so every variable reads empty and the font falls back to its default.
// hasFocus false: a page without focus draws no Ctrl+Z border, so the overlay asks nothing more.
globalThis.document = globalThis.document
  || { documentElement: {}, addEventListener: () => {}, hasFocus: () => false };
globalThis.getComputedStyle = globalThis.getComputedStyle || (() => ({ getPropertyValue: () => '' }));
// The drag-select zoom tracks drag + release on the window (a fast drag may leave the canvas).
globalThis.window = globalThis.window || { addEventListener: () => {} };
const view = new FftView(canvas, { prefs, genActive: () => false });

const N = 65536;
const cleanResult = {
  amplitudeDbFs: new Float64Array(N / 2).fill(-120),
  fundamentalDbFs: -6.02, fundamentalHzRefined: 1000.0, fundamentalTrueDbFs: NaN,
  // N+D is the one A-weighted figure (awThdNDb); THD+N reads the unweighted thdNDb and ENOB
  // its own SINAD basis (enobSinadDb), which carries no fundamental term.
  thdPct: 0.0012345678, thdNDb: -95.5, awThdNDb: -95.5, snrDb: 95.0, sinadDb: 94.0,
  enobSinadDb: 94.0, noisePower: 1e-10,
  harmonicDbFs: [-86, -92, -100, -105], harmonicPct: [0.005, 0.0025, 0.001, 0.0005],
  snrFreqMin: 0, snrFreqMax: 0, sampleRate: 48000,
  fundamentalBin: 1365, freqResolution: 48000 / N, fundamentalDynExclusionHz: 50,
};

// ── gate ────────────────────────────────────────────────────────────────────
ok('gate: clean signal distinguishable', view._hasDistinguishableSignal(cleanResult) === true);
ok('gate: THD>20% hides table', view._hasDistinguishableSignal({ ...cleanResult, thdPct: 50 }) === false);
ok('gate: fundamental at floor hides table',
  view._hasDistinguishableSignal({ ...cleanResult, fundamentalDbFs: -119 }) === false);

// ── single-tone THD table ─────────────────────────────────────────────────────
let ctx = stubCtx();
view.drawDistortionTable(ctx, cleanResult, 'DBFS', 6, 24);
let out = ctx.texts.join(' | ');
expect(out, 'fundamental dBV col', '1000.0000 Hz   -6.02 dBFS   -6.02 dBV');
expect(out, 'Span full', 'Span: full');
expect(out, 'THD harmonic-count label', 'THD H2..9:');
expect(out, 'THD% toFixed(8)', '0.00123457 %');
// N+D and SNR are dB RATIOS against the fundamental (Java FftView.fmtDb) - the level-unit
// selector does not apply to them; the A marks the one A-weighted figure.
expect(out, 'N+D dB ratio + A suffix', '-95.50 dB A');
expect(out, 'N row dBV (10log10+offset)', '-100.00 dBV');
expect(out, 'SNR dB ratio', '95.00 dB');
expect(out, 'ENOB bits toFixed(1)', '15.3 bits');
expect(out, 'harmonic H2 dBV', '-86.00 dBV');
expect(out, 'harmonic H2 % verbatim toFixed(8)', '0.00500000 %');
ok('no NaN in THD table', !/NaN/.test(out));

// span row when a band is set
ctx = stubCtx();
view.drawDistortionTable(ctx, { ...cleanResult, snrFreqMin: 20, snrFreqMax: 20000 }, 'DBFS', 6, 24);
expect(ctx.texts.join(' | '), 'Span range', 'Span: 20 .. 20000 Hz');

// ── dual-tone IMD table ───────────────────────────────────────────────────────
const imd = {
  f1Hz: 1000.0, f1DbFs: -6.02, f1DbV: -6.02, f1Mag: 0.5,
  f2Hz: 1100.0, f2DbFs: -6.04, f2DbV: -6.04, f2Mag: 0.5, diffHz: 100,
  imdPwrPct: 0.01, tdnPct: 0.02, dfd2Pct: 0.003, dfd3Pct: 0.004,
  dnLDbV: [, , -80, -90, -100, -110], dnLPct: [, , 0.05, 0.04, 0.03, 0.02], dnLHz: [, , 900, 800, 700, 600],
  dnHDbV: [, , -81, -91, -101, -111], dnHPct: [, , 0.051, 0.041, 0.031, 0.021], dnHHz: [, , 1200, 1300, 1400, 1500],
};
ctx = stubCtx();
view.drawImdTable(ctx, imd, cleanResult, 6, 24);
out = ctx.texts.join(' | ');
expect(out, 'IMD F1 order Hz/dBFS/dBV', 'F1: 1000.0000 Hz   -6.02 dBFS   -6.02 dBV');
expect(out, 'IMD F2 order Hz/dBFS/dBV', 'F2: 1100.0000 Hz   -6.04 dBFS   -6.04 dBV');
expect(out, 'IMDpwr label + toFixed(8)', 'IMDpwr:');
expect(out, 'IMDpwr value toFixed(8)', '0.01000000 %');
expect(out, 'TD+N toFixed(8)', '0.02000000 %');
expect(out, 'DFD2 toFixed(8)', '0.00300000 %');
expect(out, 'DFD3 toFixed(8)', '0.00400000 %');
expect(out, 'd2L dBV', '-80.00 dBV');
expect(out, 'd2L % toFixed(8)', '0.05000000 %');
expect(out, 'd2H dBV', '-81.00 dBV');
ok('no spacing-mislabel "Δf " bare row', !/\bΔf\b(?!1|2)/.test(out));
ok('no NaN in IMD table', !/NaN/.test(out));

// ── clock-drift rows only when gen active + fund-from-gen + the generator ANSWERS ───────────
// The view asks the bus what the generator emits (GENERATOR_EMITTED_HZ) instead of recomputing
// the snap; with no responder the two rows are not drawn at all, so one answers here.
const view2 = new FftView(canvas, { prefs: { ...prefs, fftFundFromGenerator: prop(true) }, genActive: () => true });
MessageBus.instance().registerResponder(Events.GENERATOR_EMITTED_HZ, () => [1000.0, 1100.0]);
ctx = stubCtx();
view2.drawImdTable(ctx, imd, cleanResult, 6, 24);
MessageBus.instance().unregisterResponder(Events.GENERATOR_EMITTED_HZ);
out = ctx.texts.join(' | ');
expect(out, 'IMD clock-drift Δf1 row', 'Δf1:');
expect(out, 'IMD clock-drift Δf2 row', 'Δf2:');
expect(out, 'IMD Δf1 ppm format', 'ppm');

console.log(`\nfft-table-render: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
