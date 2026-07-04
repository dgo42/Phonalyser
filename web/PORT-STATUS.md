# PORT-STATUS — ported-module → live-engine integration checklist

Status of the Java→JS port under `web/js/`, and the wiring state of each ported
module in the **live engine** (`web/js/audio/backend.js` + controllers) and the
**UI** (`web/js/shell/app.js`, `web/index.html`).

## State of the port

**All ten ported subsystems are wired and live.** The engine runs on the
faithful ports, not the prototype math:

- `audio/fft-controller.js` + `audio/fft-worker.js` drive `fft/fft-analyzer.js`
  (`FftAnalyzer.analyze()` is the canonical per-window path); results flow as
  full `FftResult` payloads through `fft/fft-view-correction.js` (render-time
  `.frc` de-embed, mains spectral correction, live IMD) into `ui/fft-view.js`.
- The output generator is the faithful `worklets/dds-processor.js` +
  `generator/dds-kernel.js`, loaded unconditionally by
  `audio/generator-controller.js`. The prototype `generator-processor.js` is no
  longer loaded anywhere.
- The scope runs the ported trigger (`scope/scope-trigger.js`), measurements
  (`scope/signal-measurements.js`), Lanczos rendering (`dsp/lanczos.js`) and all
  three mains cancellers (`dsp/mains/*`).
- Preferences (`store/preferences.js`) and i18n (`i18n/i18n.js`, 32 locales)
  bootstrap the shell; all file I/O (`io/*`) is wired through the panes.

Inventory under `web/js/`:

```
dsp/   fft.js window.js fll.js discontinuity.js                       (wired)
       mathutil.js lanczos.js savgol.js riaa.js tone-lobe-lift.js     (ported, wired)
       mains/frequency-tracker.js mains/comb-filter.js                (ported, wired)
       mains/sync-subtract-filter.js mains/lms-filter.js mains/factory.js  (ported, wired)
fft/   imd-analyzer.js fft-result.js fft-analyzer.js fft-compensation.js
       fft-view-correction.js fft-tab-control.js                      (ported, wired)
generator/ dds-kernel.js generator-pane.js                            (ported, wired)
scope/ scope-trigger.js signal-measurements.js scope-tab-control.js scope-pane.js  (ported, wired)
freqresp/ farina-sweep.js deconvolve.js                               (ported, wired)
predistortion/ harmonic-compensation.js intermod-compensation.js engine.js  (ported, wired)
io/    wav.js frc.js fft-spectrum.js dpd.js scope-capture.js file-picker.js flac.js  (ported, wired)
store/ preferences.js                                                 (ported, wired)
i18n/  i18n.js locales.js   (+ web/i18n/messages*.properties ×32)     (ported, wired)
audio/ backend.js fft-controller.js generator-controller.js scope-controller.js
       fft-worker.js worklets/{capture,dds}-processor.js              (wired)
shell/ app.js freqresp-host.js predistortion-host.js predistortion-wizard.js
       preferences-dialog.js  ui/fft-view.js ui/scope-view.js         (wired)
```

---

## Current loop — parts 1–5

Five desktop features brought to completion in the web app, each compared back to
Java (sync-java-to-web loop) and iterated to close differences. **Java-delta
sync of 2026-07-03:** a full reconciliation pass audited every module's
integration TODOs against the live code and closed the remaining gaps (live
dual-tone IMD verified, `harmonicCount` from prefs verified, FFT pre-FFT
time-domain mains path made faithful to `FftAnalyzerWorker.mainsTimeFilter`,
DDS kernel phase-accumulator NaN bug fixed, help screenshots recaptured for all
12 specs, i18n key sets realigned across all 32 locale files).

1. **Help system** — ✅ framework DONE (see below). Screenshot capture now covers
   **all 12 specs** (`scripts/capture-help-screenshots.mjs`: gen-dualtone,
   gen-sweep, fft, freqresp, all 5 prefs tabs added 2026-07-03; 12 captured,
   0 skipped, all verified non-blank). Remaining: optional in-app `?hl=`
   search-term highlighter (the desktop HelpViewer injected one).
2. **Preferences** — ✅ DONE (Look & Feel / Oscilloscope / FFT / FreqResp tabs).
3. **Generator** — ✅ DONE vs `GeneratorPane`/`GeneratorController`/`SignalGenerator`
   (exhaustive 21-gap verified diff; e2e C32/C33 cover every form). Deferred
   (low): relocating the FLL/predistortion buttons out of the gen footer. Audio
   output still needs hardware to confirm.
4. **FFT** + wizard — ✅ DONE. FFT brain, live IMD readout, mains (all three
   modes incl. pre-FFT time-domain cancellers), `.fft`/`.frc` I/O, predistortion
   wizard all wired and verified in the 2026-07-03 reconciliation.
5. **FreqResp** + wizard — ✅ DONE. Sweep drive, deconvolution, RIAA overlay,
   calibration rows, presets, 3-page wizard, `.frc` save/load all wired and
   verified.

When touching the FFT/FreqResp **Utility** tabs, preserve the manual
button/icon-size CSS (the shared tab-toolbar classes — Presets / Utility /
Save to… / Load from… / Load calibration… — are generalized for reuse across
Scope, FFT, FreqResp).

### Part 1 — Help system  ✅ FRAMEWORK DONE

The Java HTML help (`src/main/resources/help/{en,de,uk}`) is the source of truth; `web/help/`
is the web's own copy that diverges only in screenshots + the build-time-regenerated index.

- `scripts/import-help.mjs` (`npm run import-help`) — copy Java help → `web/help`, preserving
  web-captured `img/`.
- `scripts/build-help-index.mjs` — regenerate `window.HELP_DOCS` (one lunr doc per page intro +
  per anchored h2/h3) by parsing the HTML; **236 docs/lang**.
- `scripts/build-help.mjs` (`npm run build:copy-help`) — **separate** build target: regenerate
  index + copy `web/help` → `dist/help`. A regular `npm run build` does NOT touch help. See
  `HOWTO-BUILD.md`.
- `scripts/capture-help-screenshots.mjs` (`npm run capture-help`) — capture screenshots from the
  running WEB app at a fixed **1280×768** window. **All 12 specs ready and capturing**
  (app, scope, generator, gen-dualtone, gen-sweep, fft, freqresp, 5 prefs tabs).
- **sync-help** skill — the Java→web help refresh loop.
- Help menu (`#menuHelp`) + **F1** (contents) / **Ctrl+F1** (contextual) → open
  `help/<lang>/index.html` in a 1024×800 pop-up window, its top-left corner aligned to the
  app window's top-right corner.

**TODO (part 1):** optional in-app `?hl=` search-term highlighter (the desktop
HelpViewer injected one).

---

## 1. FFT analyzer brain — `web/js/fft/fft-analyzer.js` + `fft/fft-result.js`  ✅ PORTED, ✅ WIRED

Faithful port of `FftAnalyzer` (+ the `MathUtil` helpers) and `FftResult`.

### Exports

`fft-analyzer.js`:
- `class FftAnalyzer`
  - `analyze(samples, sampleRate, fftSize, harmonicCount, windowType='HANN', overlap='PCT_0', snrFreqMin=0, snrFreqMax=0, coherentAveraging=true, fundRefDbFs=NaN, expectedFundHz=NaN, outResult=new FftResult()) -> FftResult`
  - `recomputeStats(r)` — re-derive fundamental/harmonics/THD/SNR/SINAD/THD+N from a (mutated) spectrum.
  - `buildWindow(N, type) -> Float64Array` (13 tokens: RECT HANN BH4 BH7 FT HFT144D HFT248D KB24 KB38 DC150/200/250/300).
  - `setSecondToneHintHz(hz)`, `setMultiTone(bool)`, `setSpectrumOnly(bool)`.
  - `windowType`/`overlap` are the Java **enum name tokens** (`"BH4"`, `"PCT_0"`); `overlap` also accepts a raw fraction.

`fft-result.js`:
- `class FftResult` — all desktop fields (`re`/`im`/`amplitudeDbFs`/`phaseDeg` `Float64Array`, `harmonicBins` `Int32Array`, metrics, `imdProductA/B/Bin`, `rawFundRe/Im`, `rawPeakRe/Im`, gate snapshots; `fundamental2HzRefined`/`coherentKappa`/`fundamentalTrueDbFs`/`rawFund*` default NaN).
  - `ensureArrays(binCount, harmonicCount)`, `deepCopy() -> FftResult`, `noisePeakFloorDbFs() -> number`, `localNoiseFloorDbFs() -> number`, `rawHarmonicDbFs(i) -> number`, `captureRawPeaks()`.

### Done log — FFT brain (all integration TODOs closed)
1. Spectrum path — buffer-and-analyze shape (a): `fft-worker.js` runs
   `FftAnalyzer.analyze()` per dispatched window (`fft-controller.js
   _setupFftAnalysis` configures window/overlap/harmonicCount/coherent).
2. UI tokens — `index.html` `#window`/`#overlap` selects use the enum tokens
   directly (all 13 window types, `PCT_*` overlaps).
3. FLL — steers off `r.fundamentalHzRefined` (sub-bin), posts `{frequency}` to
   `dds-processor` (`fft-controller.js`). See §2 for the F2 caveat.
4. Result payload — `fft-worker.js postResult()` clones the full `FftResult`
   (spectra, refined fundamentals, harmonics, THD/SNR/SINAD, IMD grid);
   `fft-pane.js setResult(r)` renders it directly.
5. `harmonicCount` from prefs — `app.js:504`:
   `c.harmonicCount = Math.max(9, prefs.fftCalcMaxHarmonic.get()) - 1`
   (faithful to Java `FftAnalyzerWorker`, including the `-1`).

---

## 2. IMD / dual-tone analyzer — `web/js/fft/imd-analyzer.js`  ✅ PORTED, ✅ WIRED

Faithful port of `ImdAnalyzer` + `ImdResult`.

### Exports
- `analyzeImd(r, f1Cmd, f2Cmd, dbvOffsetDb) -> ImdResult | null`
  - `r` reads `amplitudeDbFs` (`Float64Array` dBFS), `freqResolution`,
    `fundamentalHzRefined`, `fundamental2HzRefined`, `fundamentalTrueDbFs`.
  - Returns `null` when `r`/`amplitudeDbFs` missing, `freqResolution<=0`, or a tone
    is out of range. **Callers MUST null-check.**
- `MAX_ORDER` (= 5).
- `ImdResult` (typedef): `f1Hz f2Hz f1DbFs f2DbFs f1DbV f2DbV f1Mag f2Mag diffHz
  dfd2Pct dfd3Pct imdPwrPct tdnPct` + per-order `Float64Array`s
  `dnLHz dnHHz dnLPct dnHPct dnLDbV dnHDbV` indexed `2..MAX_ORDER`.

### Done log — IMD analyzer (all integration TODOs closed)
1. Second tone — `dds-processor` generates DUAL_TONE (`generator-controller.js`
   posts `frequency2`/`dualAmp1Pct`/`dualAmp2Pct`); `analyzeImd` gated on
   `isDualTone(form)`.
2. Dual-tone `r` — worker calls `setMultiTone` + `setSecondToneHintHz` per
   dispatch; `analyze` populates `fundamental2HzRefined` + the IMD-product grid.
3. `dbvOffsetDb` — supplied live from `Preferences` (computed from
   `adcFsVoltageRms`, wired at `app.js:522`).
4. **Live IMD readout** — computed in the VIEW layer (correct per the Java
   architecture: off the DE-EMBEDDED spectrum, not the raw engine result):
   `fft-view-correction.js:66` — `r.imd = isDualTone(c.form) ? analyzeImd(r,
   c.toneHz, c.tone2Hz, c.dbvOffsetDb) : null`, invoked on every frame via
   `app.js engine.onResult → fftViewCorrection.apply(r)`; rendered by
   `ui/fft-view.js drawImdTable` + tone markers. Loaded `.fft` files get the
   same treatment in `fft-tab-control.js`.
5. Mode switch SINGLE↔DUAL_TONE — routed through `restartGenerator()`
   (structural worklet rebuild); `Events.GENERATOR_SIGNAL_CHANGED` resets the
   FFT accumulator + FLL.

### Open — accepted web limitation
- **Dual-tone F2 FLL steering.** Java runs a second `FrequencyAligner` (`fll2`)
  off `imd.f2`; the web steers only tone 1 (`fft-controller.js:873-877`
  documents this). A faithful F2 loop needs a structural second-loop +
  lock-state addition to `GeneratorController`. `fundamental2HzRefined` IS
  stamped; only the steering is missing.

---

## 3. Signal generator (DDS) — `web/js/generator/dds-kernel.js` + `audio/worklets/dds-processor.js`  ✅ PORTED, ✅ WIRED

Faithful port of `SignalGenerator` (DDS kernels, 64-bit phase accumulator,
all waveforms, pink-noise Voss–McCartney, `.dpd` comp, dither). The worklet
registers `dds-processor` — the ONLY output worklet the app loads.

### Exports — `dds-kernel.js`
- `GenSignalForm` (frozen string enum: SINE SINE_COMP TRIANGLE RECTANGLE
  WHITE_NOISE PINK_NOISE PINK_NOISE_LINEAR LINEAR_SWEEP LOG_SWEEP DUAL_TONE
  DUAL_TONE_COMP), `isDualTone(form) -> bool`, `formFromString(s) -> form`.
- `class DdsKernel({form, frequency, sampleRate, amplitudeVRms, dacFsVoltageAmpl, rng})`
  - `nextSample() -> number`, `fill(out, n)`.
  - setters: `setForm`, `setFrequency`, `setDualToneFrequency2`,
    `setDualToneAmplitudes(p1,p2)`, `setAmplitudeVrms`, `setDacFsVoltageAmpl`,
    `setRectangleDuty`, `setTriangleDuty`.
  - comp: `applyCompensation(compOrAmp,hNums?,phiInits?)`,
    `applyDualToneCompensation(compOrAmp,aCoef?,bCoef?,phiInits?)`, `clearCompensation`.
  - sweeps: `configureLinearSweep`, `configureLogSweep`, `getLogSweepBuffer`,
    `setSweepParams(loop,fadeIn,fadeOut)`, `resetSweepPosition`.
- `makeCompensation(ampRatios,hNums,phiInits)`, `makeDualToneComp(ampRatios,aCoef,bCoef,phiInits)`.
- `loadHarmonics(text,frequency)`, `loadIntermod(text)`, `isDualToneCorrectionFile(text) -> bool`.
- `renderLogSweep(f0,f1,sweepSamples,sampleRate) -> Float64Array`, `rawRms(form,w1=.5,w2=.5) -> number`.
- `tpdfNoise(ditherBits,rng=Math.random) -> number`, `quantizePcm(sample,bitDepth,ditherBits=0,rng) -> number`.

### Worklet — `dds-processor.js` (`registerProcessor('dds-processor')`)
- Built from `processorOptions {form,frequency,sampleRate,amplitudeVRms,dacFsVoltageAmpl}`.
- `port.onmessage` protocol: `{form, frequency, frequency2, amplitudeVRms,
  dacFsVoltageAmpl, rectDuty, triDuty, dualAmp1Pct+dualAmp2Pct, linearSweep,
  logSweep, sweepParams, resetSweepPosition, compensation, dualToneCompensation,
  dpdText(+dpdFrequency), clearCompensation, type:'start'|'stop'}`.

### Done log — DDS generator (all integration TODOs closed)
1. Worklet swap — `generator-controller.js:176` loads `dds-processor`
   unconditionally on start, constructed with
   `{form, frequency, sampleRate, amplitudeVRms: ampVrmsOf(c), dacFsVoltageAmpl}`.
2. Retune protocol — `retuneGenerator()` posts `{frequency, amplitudeVRms,
   rectDuty, triDuty, frequency2, dualAmp*Pct}` (not `{inc}`); the FLL steer
   posts `{frequency: genFreq}`.
3. UI controls — all `GenSignalForm` variants wired (form change →
   `restartGenerator()`), duty live-edits, dither (file render via
   `quantizePcm`, combo capped at output bit depth), `.dpd` corrections row
   (browse/clear/form-tracking, `{dpdText,dpdFrequency}` posted on start), F2
   freq/amps bin-snapped.
4. Sweeps / `.dpd` — `_postSweepConfig()` posts `{linearSweep|logSweep,
   sweepParams}` on start + retune; LOG_SWEEP param edits force a full restart
   (pre-rendered buffer); freqresp (§5) and predistortion (§6) post
   `logSweep` / `compensation` live.
5. **2026-07-03 kernel fix** — phase-accumulator turn reconstruction rounded to
   exactly 1.0 at integer-ratio frequencies (e.g. 1000 Hz @ 48 kHz), so
   `SINE_TABLE[4096]` → NaN every cycle. Fixed faithful to Java:
   `phaseTurn53(hi,lo)` reconstructs the top 53 bits
   (`(phaseAcc >>> 11) * 2^-53`) and `ddsSineOf`/`ddsCosOf` mask the table index
   (`& (TABLE_SIZE-1)`, Java's top-12-bit extraction). All 81 kernel tests green.

---

## 4. Oscilloscope DSP — `web/js/scope/scope-trigger.js` + `scope/signal-measurements.js`  ✅ PORTED, ✅ WIRED
Supporting: `dsp/lanczos.js`.

### Exports — `scope-trigger.js`
- `find(data, n, from, to, level, rising, sincRefine, hysteresis, minSpacingSamples=0) -> number` (rightmost crossing, −1 if none).
- `findGlitch(data, from, to, rising, mergeSamples, omega) -> number`.
- `refine(data, n, a, b, level, rising) -> number` (10-iter sinc bisection).
- `linear(prev, curr, prevIdx, level) -> number`.

### Exports — `signal-measurements.js`
- `compute(data, n, sampleRate, peakVolts) -> {vpp,vrms,vmean,period,riseTime,fallTime,frequency,dutyCycle}`.
- `refineFrequencyAround(data, n, sampleRate, seedHz, halfHz) -> number`.
- `withFrequency(m, freq) -> m'`, `withoutTimes(m) -> m'`.
- `reconstructBeatSignal(data, available, sampleRate, f1Hz, f2Hz, scratch?) -> Float32Array`.
- `MeasurementStats`, `WindowedSignalAccumulator` + formatters `forVolts/forTime/forFreq/forPct`.

### Exports — `lanczos.js`
- `lanczos(data,n,t,scale) -> number`, `lanczosNaN(data,n,t,scale) -> number`, `sinc(x) -> number`;
  consts `LANCZOS_A`(16), `MAX_LANCZOS_DOWNSAMPLE`(5), `LANCZOS_PADDING`(80).

### Done log — scope DSP (all integration TODOs closed)
1. Trigger — `ui/scope-view.js` uses `find()`/`findGlitch()` with
   `LANCZOS_PADDING`-bounded search windows, hysteresis, `minSpacing` beat
   holdoff; band-limited sinc dots via `lanczos()`.
2. Measurements — `compute()` + persistent `WindowedSignalAccumulator` pool
   (streaming HF-LPF + mains comb), 8-row table (Vpp/Vrms/Vmean/Tp/Tr/Tf/f/Duty)
   throttled at READOUT_THROTTLE_MS.
3. DUAL_TONE beat view — `reconstructBeatSignal()` feeds the trigger with
   half-beat-cycle holdoff; measurements use
   `withFrequency(withoutTimes(m), refineFrequencyAround(...))`.
4. Mains suppression — all three `MainsSuppression` modes (IIR_COMB /
   SYNC_SUBTRACT / LMS) wired per channel into the display path, the live
   measurement pass (settled tail for the comb + raw-window ±2 Hz frequency
   re-pin — never derive frequency from the comb output) and the streaming
   amplitude pool; windows carry `absStart`/`measAbsStart` so the phase-locked
   cancellers stay aligned. See §8.

---

## 5. Frequency response (Farina) — `web/js/freqresp/farina-sweep.js` + `freqresp/deconvolve.js`  ✅ PORTED, ✅ WIRED

> Wired in `web/js/shell/freqresp-host.js` (driven from `index.html` `#tab-fr`):
> drives a LOG_SWEEP through the live DDS (`engine.postGen({logSweep},{form:LOG_SWEEP})`),
> records the loopback via the engine's capture-recording tap, deconvolves with
> `computeFromLogSweep(renderLogSweep(...))`, plots magLin→dB + phase on `#frPlot`,
> RIAA overlay via `dsp/riaa.evalDb`, save/load `.frc` (+ `divideInPlace` de-embed).
> Beyond the original scope: 3-page wizard (loopback → DUT → save+apply),
> calibration-tab multi-row `.frc` loader (chained de-embeds, persisted),
> presets subsystem, live sweep meter.
Supporting: `dsp/savgol.js`, `dsp/riaa.js`, `dsp/fft.js`.

### Exports — `farina-sweep.js`
- `renderLogSweep(f0,f1,sweepSamples,sampleRate) -> Float64Array`.
- `sweepFadeSamples(sweepSamples) -> number`, `sweepEnvelope(idx,cycleLength,fadeIn,fadeOut) -> number`,
  `renderWindowedLogSweep(f0,f1,sweepSamples,sampleRate,fadeSamples) -> Float64Array`,
  `SWEEP_FADE_FRACTION_PER_SIDE`(0.05).

### Exports — `deconvolve.js`
- `computeFromLogSweep(yRec, sweepRef, leadInSamples, sampleRate, freqs, amplitudeVRms, adcFsVoltageRms, fadeSamples=0) -> {freqs, magLin, phaseRad}` (FreqRespCalibration).
- `interpolate(cal, freq) -> [magLin, phaseRad]`, `divideInPlace(measured, calibration)`.

### Exports — `savgol.js` / `riaa.js`
- `savGolCoefficients(window,order)`, `applySavGol(arr,i,coeffs)`, `invertSquareMatrix(a)`.
- `evalDb(fHz, reverse, iec) -> number`; consts `T1_SEC..T4_SEC`, `REF_HZ`(1000).

### Done log — freqresp (all 5 integration TODOs closed; verified 2026-07-03)
FR UI + run button + magnitude/phase plot (1); sweep drive via
`postGen({logSweep},{form:LOG_SWEEP})` + capture recording (2); stereo
deconvolution `renderLogSweep` → `computeFromLogSweep` →
`makeFreqRespResult`/`setStereoResult` (3); RIAA overlay + `divideInPlace`
de-embed in wizard and Load-calibration paths (4); `.frc` round-trip via
`io/frc.js` + `file-picker.js` (5).

### Open — optional
- ADC/DAC calibration dialogs from the FR host are stubs (logging only,
  `freqresp-host.js:534-535`).

---

## 6. DAC predistortion — `web/js/predistortion/{harmonic-compensation,intermod-compensation,engine}.js`  ✅ PORTED, ✅ WIRED

> Wired via `web/js/shell/predistortion-host.js` (a `PredistortionHost`) + the
> `#predistModal` wizard in `index.html`/`app.js`: `configureForRun` = coherent ∞
> gen-locked averaging (restart preserving config), `readResult` =
> `FftResult.adopt(...).deepCopy()`, `imdPct` = `analyzeImd(...).imdPwrPct`,
> `applyCompensation`/`applyDualToneCompensation` post
> `{compensation:makeCompensation(...)}` / `{dualToneCompensation:makeDualToneComp(...)}`
> to `dds-processor`, `correctionEntries` = the shared loaded-`.frc` store
> (`frcStore`, populated by FFT "Load calibration", injected in `app.js:1099`),
> anchors from `Preferences`. Wizard: live phase state machine, 100 ms-polled
> convergence chart, run/stop/stop-round, best-round snapshot, `.dpd` save
> (`io/dpd.write{Harmonic,Intermod}Dpd` + full provenance header/filename) then
> Apply → posts correction + switches to SINE_COMP/DUAL_TONE_COMP.
Serialisation: `io/dpd.js`.

### Exports
- `class HarmonicCompensation(maxHarmonics)` — fields `accRe/accIm/hFreqs`;
  `accumulate(r, step, calFundPhaseRad)`, `hasCorrections() -> bool`, `copy()`,
  `toGeneratorCorrections(fundamentalHz, calResponseAt, adcFsVoltageRms, dacFundamentalVrms) -> {ampRatios, harmonicNumbers, phiInits}`.
- `class IntermodCompensation(maxHarmonics)` — fields `coefA/coefB/accRe/accIm/freqHz`;
  `accumulate(r, f1Hz, f2Hz, step, calF1PhaseRad, calF2PhaseRad)`, `hasCorrections()`, `copy()`,
  `toGeneratorCorrections(calResponseAt, adcFsVoltageRms, dacFundamentalVrms) -> {ampRatios, coefA, coefB, phiInits}`.
- `class PredistortionEngine(host, listener={})` — `async runLoop(baseAverages, targetThdPct)`,
  `stop()`, `stopRound()`, `getCollectRemainingAverages()`; `StopReason`, `Phase` enums; `interpolate(cal, freq)`.
- `PredistortionHost` typedef (injected): `maxHarmonics()`, `isDualTone()`,
  `effectiveFrequency()`, `adcFsVoltageRms()`, `genAmplitudeVrms()`,
  `dualToneSplitPct()`, `configureForRun()`, `startRecording()`,
  `clearCompensation()`, `resetStatistics()`, `resetStatisticsAfterSignalChange()`,
  `completedAnalyses()`, `readResult()`, `imdPct(r)`,
  `applyCompensation(ampRatios,harmonicNumbers,phiInits)`,
  `applyDualToneCompensation(ampRatios,coefA,coefB,phiInits)`, `correctionEntries`.

### Exports — `io/dpd.js`
- `writeHarmonicDpd(comp, extraHeaderLines, fundamentalHz, fundamentalDbFs, sampleRate, bitDepth, amplitudeVrms, calResponseAt, adcFsVoltageRms) -> string`.
- `writeIntermodDpd(comp, extraHeaderLines, f1Hz, f2Hz, fundamentalDbFs, sampleRate, bitDepth, amplitudeVrms, calResponseAt, adcFsVoltageRms, dacFundamentalVrms) -> string`.
- `readDpd(text, frequency, sampleRate) -> {dualTone, amp, phi, harmonicNumbers, coefA, coefB}`, `isDualToneDpd(text) -> bool`.

### Done log — predistortion (all integration TODOs closed; verified 2026-07-03)
Host bridge with all seven contract methods live (1); wizard UI with
averages + target THD%, `onRound`/`onFinished` progress, convergence chart (2);
`.dpd` save via `writeHarmonicDpd`/`writeIntermodDpd` → `file-picker.saveFile`,
then Apply + form switch (3). frcStore chain verified end-to-end:
`fft-tab-control.js:727` populates → `app.js:1099` injects →
`predistortion/engine.js _calResponseAt` consumes.

---

## 7. File formats — `web/js/io/{wav,frc,fft-spectrum,dpd,scope-capture,file-picker,flac}.js`  ✅ PORTED, ✅ WIRED

### Exports
- `wav.js`: `readWav(ArrayBuffer|Uint8Array) -> {sampleRate,channels,bitsPerSample,frameCount,ch0,ch1}`;
  `class WavWriter(sampleRate,channels,bitsPerSample,floatFormat=false)` (`writeRaw`, `writeFloats`, `finish() -> Uint8Array`);
  `class AiffWriter(sampleRate,channels,bitsPerSample)` (`writeRaw`, `finish()`);
  `createFlacWriter(...)` (validating stub — superseded by `io/flac.js`), `FLAC_MAX_SAMPLE_RATE`(655350).
- `frc.js`: `saveFrc(stereo, meta={}) -> string`, `loadFrc(text) -> {left,right}`, `FRC_FORMAT_VERSION`(1).
- `fft-spectrum.js`: `loadSpectrum(text, dbvOffsetDb, harmCount, parabolicBinInterp) -> {result, modeImd, tone1Hz, tone2Hz, dbvOffsetDb}`,
  `saveSpectrum(r, meta={}) -> string`, `FFT_FORMAT_VERSION`(1), `LOADED_FUND_MIN_HZ`(10).
- `dpd.js`: see module 6.
- `scope-capture.js`: `saveScopeCapture(left, right, actual, fileName, sampleRate, bitDepth, signalFrequencyHz=0) -> Uint8Array`;
  `formatForName`, `packStereo`, `decodeStereo`, `findFullPeriodWindow`, `CHANNELS`(2).
- `file-picker.js`: `async saveFile(data, suggestedName, types=[]) -> {name, saved}`,
  `async openFile(types=[]) -> {name, bytes}|null`, `bytesToText(bytes) -> string`.

### Done log — file I/O (all closed)
1. Generator "Save to…" — `generator/generator-pane.js` `#genSaveBtn`: renders
   via `DdsKernel.nextSample()` (dual-tone snap, duty, .dpd compensation,
   sweeps), `quantizePcm` at the dither setting, stereo-duplicates, writes
   through `saveScopeCapture` (container by chosen extension: WAV/AIFF/FLAC) —
   faithful to Java `SignalFileExporter`.
2. Scope "Save to…" / "Load signal…" — ScopeTabControl via `saveScopeCapture` +
   `readWav`/`readAiff`/`decodeFlac`.
3. FFT "Save to…"/"Load from…" — `saveSpectrum`/`loadSpectrum` + post-load
   `recomputeStats` / `analyzeImd`.
4. FFT "Load calibration…" — `loadFrc` → shared `frcStore` →
   `fftViewCorrection.setFrcEntries` + predistortion `correctionEntries`.
5. All file fields wired through `io/file-picker.js`.
6. FLAC — `io/flac.js` (libflacjs WASM) provides `decodeFlac`/`encodeFlac`;
   falls back to WAV/AIFF if the codec fails to load.

---

## 8. Supporting DSP — `web/js/dsp/{mathutil,tone-lobe-lift}.js` + `dsp/mains/{frequency-tracker,comb-filter,sync-subtract-filter,lms-filter,factory}.js`  ✅ PORTED, ✅ WIRED

### Exports
- `mathutil.js`: `chebyshevT(n,x)`, `acosh(x)`, `parabolicBinInterp(re,im,peakBin,fftSize)`, `nextPow2(x)`, `besselI0(x)`.
- `tone-lobe-lift.js`: `class ToneLobeLift(floorGap=10, floorSpan=2048, maxLobeBins=2048)` —
  `localFloor(mag,peak,maxBin)`, `lobeBins(mag,peak,maxBin,floor) -> [lo,hi]`, `stretch(mag,floor,peak,factor)` (`mag` = `k=>magnitude` accessor).
- `mains/frequency-tracker.js`: `class MainsFrequencyTracker(sampleRate)` —
  `track(ref,len) -> number|NaN`, `getLockHz()`, `resetTracking()`; consts `MIN_MAINS_HZ`(45), `MAX_MAINS_HZ`(65).
  Owned/used by the three filters below (same ownership as Java).
- `mains/comb-filter.js`: `class MainsCombFilter(sampleRate, notchBandwidthHz)` —
  `getMainsHz`, `isTuned`, `magnitudeAt(f)`, `correctionDb(f)`,
  `applySpectrumCorrection(dbFs,dbV,freqResolution,f0Hz)`, `retune(f0)`, `track(ref,len)`,
  `process(data,len,absStart=0)`, `processPreservingDc(...)`, `reset`, `resetTracking`;
  const `DEFAULT_NOTCH_BANDWIDTH_HZ`(2.5).
- `mains/sync-subtract-filter.js`: `class MainsSyncSubtractFilter(sampleRate)` —
  period-locked synchronous subtraction (512-bin template, MU 0.001); same
  track/process/reset contract, absStart-delta phase alignment.
- `mains/lms-filter.js`: `class MainsLmsFilter(sampleRate)` — adaptive quadrature
  LMS line canceller (harmonics ≤ 1 kHz, MU 5e-4); same contract (DC-free model,
  both process entry points identical).
- `mains/factory.js`: `mainsFilterOf(mode, sampleRate, combNotchBwHz) -> filter|null`
  (Java `MainsFilters.of`; `'NONE'` → null).

### Done log — supporting DSP (all closed)
- `parabolicBinInterp` — dependency of `io/fft-spectrum.loadSpectrum`, wired.
- Mains — **scope**: `ui/scope-view.js` selects the canceller by the
  per-channel `osc{Left,Right}MainsSuppression` pref (`#scopeLeftMains`/
  `#scopeRightMains` combos) in the display path (`_applyChannelFilters`,
  comb-only reset — Java ScopeView.applyMainsSuppression), the live measurement
  pass (own `<ch>Meas` filter state, settled tail for IIR_COMB + raw-window
  ±2 Hz frequency re-pin — Java ScopeMeasurementWorker) and the streaming pool
  (`_streamFilterGap`, persistent state + running absStart); windows carry
  `absStart`/`measAbsStart` (Java `bufStartAbs`) for phase-locked alignment.
  **FFT**: `audio/fft-controller.js _applyFftMains` (Java
  FftAnalyzerWorker.mainsTimeFilter) — IIR_COMB tracks-only + plot-time spectral
  correction (`fft-view-correction.js`); SYNC_SUBTRACT/LMS build the true
  canceller via `mainsFilterOf` (persistent state across windows,
  `absCapStart`-aligned `processPreservingDc`) and filter the window in place
  pre-FFT. (Java's `setFrameCache(null)` bypass has no web counterpart — the
  web port has no raw-frame FFT cache.)
- `tone-lobe-lift.js` — wired into the `.frc` de-embed pipeline
  (`fft/fft-compensation.js` per-tone lobe stretch, consumed by
  `fft-view-correction.js` at render time), matching Java FreqRespCalHelper —
  i.e. baked into the calibration correction, not a display-only toggle.

---

## 9. Preferences & persistence — `web/js/store/preferences.js`  ✅ PORTED, ✅ WIRED

Faithful port of `Preferences` (+ `BackendPrefs`, `OscPreset`, `FftPreset`,
`FreqRespPreset`, `CalibrationEntry`). Persists to `localStorage`.

### Exports / key API
- `PREFS_KEY`('phonalyser.preferences'), `PREFERENCES_FORMAT_VERSION`(1).
- `class BackendPrefs`, `OscPreset`, `FftPreset`, `FreqRespPreset`, `CalibrationEntry`.
- `class Preferences`:
  - `static instance()`, `prefsFor(type)`, `current()`.
  - `convertFromDbFs(dbFs, unit, binBwSqrt=null) -> number`.
  - `save()`, `load()`, `flush()`; `setAdcFsVoltageRms(v)` (recomputes `dbvOffsetDb`),
    `setDacFsVoltageAmpl(v)`, `getGenDpd(form)`/`setGenDpd(form,path)`.
  - calibration + preset mutators; cached `dbvOffsetDb`, `binBwSqrt`.
  - every setting is a public observable `Property` (e.g. `prefs.fftLength.get()/.set(v)`,
    `prefs.genAmplitudeVrms`, `prefs.genSignalForm`, `prefs.adcFsVoltageRms`, …).

### Done log — preferences (all closed)
1. Bootstrap — `Preferences.instance()` at app start (loads in the constructor);
   `prefs.flush()` in `teardown()` (pagehide).
2. UI ↔ Property binding — `bidiBind` helper + direct Property wiring in app.js
   / the pane controls; the Preferences modal device/rate selects map to
   `prefs.current()` `BackendPrefs`.
3. Calibration anchors — `prefs.dbvOffsetDb` / `adcFsVoltageRms` /
   `dacFsVoltageAmpl` flow into FftView, ScopeView, the engine config and the
   predistortion host; changing either FS voltage resets FFT/scope.
4. Dialog helpers `copyForDialog`/`applyFromDialog` were NOT ported (SWT-only);
   the web PreferencesDialog binds Propertys directly.

---

## 10. Internationalization — `web/js/i18n/{i18n,locales}.js` (+ `web/i18n/messages*.properties`)  ✅ PORTED, ✅ WIRED

Faithful port of `I18n` + language discovery.

### Exports
- `i18n.js`: `t(key, ...args) -> string`, `async setLocale(tag) -> Promise<void>`,
  `async initBase() -> Promise<void>`, `getLocale() -> string`,
  `parseProperties(text) -> Map`, `messageFormat(pattern, args) -> string`.
- `locales.js`: `LOCALES` (frozen `[{tag,file,endonym}]`, 32 entries),
  `localeByTag(tag) -> LocaleEntry|undefined`.

### Done log — i18n (all closed)
1. Bootstrap — app.js init: `await initBase()` then
   `await setLocale(prefs.uiLanguage.get())` before first paint.
2. Externalise strings — the shell + all panes/dialogs resolve through
   `t('key')`; the Language menu (`#langMenu`) is built from `LOCALES`, persists
   `prefs.uiLanguage` and live re-renders the chrome on switch.
3. Stage other locales — all 31 `messages_<tag>.properties` staged in
   `web/i18n/`, row-aligned with the web-only keys appended; `setLocale` fetches
   on demand (base-English fallback per key).
4. **2026-07-03 key realignment** — all 32 files verified to identical key sets
   (674 keys each): added `web.browser.unsupported.title/.message` translations
   to all 31 locales, removed the orphan `preferences.lookAndFeel.recreateNote`
   (no Java counterpart) and the unreferenced `web.utility.screenshot` (de);
   `web/test/i18n-check.mjs` passes.

---

## Modules referenced by the source app but NOT ported

- **`USE_IR_GATING` branch** in the freqresp deconvolution — compiled OFF in Java,
  intentionally omitted.
- **`copyForDialog`/`applyFromDialog`** Preferences SWT-dialog helpers — not ported
  (web binds Propertys directly).

(Formerly on this list, since ported: the `MainsFilters.of` factory +
SYNC_SUBTRACT/LMS cancellers — `dsp/mains/{factory,sync-subtract-filter,lms-filter}.js`;
FLAC encode — `io/flac.js` libflacjs WASM.)

---

## Summary

- **10 of 10** listed subsystems are ported, faithful, and syntax-clean.
- **10 of 10** are wired: the live app runs on the faithful engine — FFT brain +
  IMD in the worker/pool, DDS worklet generator, scope DSP + mains cancellers
  (scope AND pre-FFT), freqresp, predistortion, file I/O, Preferences-backed
  config and full i18n (32 locales, identical key sets).
- **Open items** (tracked above): dual-tone F2 FLL steering (§2, accepted web
  limitation pending a structural GeneratorController change); optional `?hl=`
  help highlighter (part 1); FR-host ADC/DAC calibration dialogs (§5, stubs);
  gen-footer FLL/predistortion button relocation (part 3, deferred); audio
  output hardware confirmation (part 3).
