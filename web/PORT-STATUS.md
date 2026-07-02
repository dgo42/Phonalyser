# PORT-STATUS — ported-module → live-engine integration checklist

Status of the Java→JS port under `web/js/`, and the concrete rewiring each
ported module needs so the **live engine** (`web/js/audio/backend.js`, still a
self-contained prototype DSP) and the **UI** (`web/js/shell/app.js`,
`web/index.html`) call the faithful ports instead of the prototype math.

## State of the port

**All ten ported subsystems now have source files** (faithful, AGPL-headed,
`node --check`-clean). The blocker is no longer the port — it is the wiring:

- The live engine `audio/backend.js` still uses ONLY the prototype DSP:
  `dsp/window.js` (`WINDOWS`), `dsp/fll.js` (`GeneratorFLL`),
  `dsp/discontinuity.js`, `dsp/analyzer.js` (`computeMetrics`). It hand-rolls the
  FFT averaging / fundamental / THD path in `_processSpectrum` / `_finalize`.
- The UI `shell/app.js` imports only `audio/backend.js`, `ui/fft-view.js`,
  `ui/scope-view.js`. No store/preferences, no i18n, no file I/O, no generator
  DDS, no scope DSP, no freqresp.
- The output generator is the minimal single-sine `worklets/generator-processor.js`
  (`{inc, amp}` port protocol); the faithful `worklets/dds-processor.js` +
  `generator/dds-kernel.js` exist but are **not loaded** by `backend.js`.

**Verification.** A repo-wide grep for imports of `../fft/…`, `../generator/…`,
`../scope/…`, `../freqresp/…`, `../predistortion/…`, `../io/…`, `../store/…`,
`../i18n/…`, and the new `dsp/{mathutil,lanczos,savgol,riaa,tone-lobe-lift,mains}`
modules from `web/js/audio` and `web/js/shell` returns **zero matches**. Every
faithful module below is correct and unwired.

Inventory under `web/js/`:

```
dsp/   fft.js window.js fll.js discontinuity.js analyzer.js          (prototype, wired)
       mathutil.js lanczos.js savgol.js riaa.js tone-lobe-lift.js    (ported, unwired)
       mains/frequency-tracker.js mains/comb-filter.js               (ported, unwired)
fft/   imd-analyzer.js fft-result.js fft-analyzer.js                 (ported, unwired)
generator/ dds-kernel.js                                            (ported, unwired)
scope/ scope-trigger.js signal-measurements.js                      (ported, unwired)
freqresp/ farina-sweep.js deconvolve.js                             (ported, unwired)
predistortion/ harmonic-compensation.js intermod-compensation.js engine.js  (ported, unwired)
io/    wav.js frc.js fft-spectrum.js dpd.js scope-capture.js file-picker.js  (ported, unwired)
store/ preferences.js                                               (ported, unwired)
i18n/  i18n.js locales.js   (+ web/i18n/messages.properties)         (ported, unwired)
audio/ backend.js fft-worker.js                                     (prototype engine, wired)
       worklets/{capture,generator}-processor.js                    (prototype, wired)
       worklets/dds-processor.js                                    (ported, unwired)
shell/app.js  ui/fft-view.js  ui/scope-view.js                      (prototype UI, wired)
```

---

## Current loop — parts 1–5 (in progress)

Bringing five desktop features to completion in the web app, each compared back to Java
(via the sync-java-to-web loop) and iterated to close differences:

1. **Help system** — copy the desktop HTML help + lunr search (build-time index) + screenshots
   re-captured from the WEB app. **Framework DONE** (see below); screenshots captured per
   feature as parts 2–5 land.
2. **Preferences** — Look & Feel / Oscilloscope / FFT / FreqResp tabs. ✅ DONE.
3. **Generator** — ✅ to completion vs `GeneratorPane`/`GeneratorController`/`SignalGenerator`
   (exhaustive 21-gap verified diff; e2e C32/C33 cover every form). Fixed: **emit frequency**
   per form (RECTANGLE sample-period-aligned `fs/round(fs/f)`, SINE/DUAL bin-snap only when
   snap on, else raw — and the live freq-edit drop bug); the **freq + duty bracket labels**
   (RECTANGLE corrected, SINE+snap, else plain; RECTANGLE duty sample-quantised); **per-form
   duty** (`genRectangleDuty`/`genTriangleDuty` seed/reload/write); **dual-tone** Frequency 1
   surfaced + per-tone snapped labels + snap below the freq block; **noise** disables the
   frequency field; **dither** list 0..outputBitDepth rebuilt on open; **sweep fields** →
   NumericStepFields (wheel/arrow/unit) + Sweep group label; **WAV export** uses the configured
   bit depth + sample-aligns RECTANGLE + truncates to whole periods; **.dpd compensation** row
   wired (browse/clear/form-tracking, applied via the worklet for COMP forms); **Calibrate-DAC**
   button + dialog; **ON-AIR** ~1 Hz blink; **FreqResp interlock** (gen play buttons locked
   during a sweep). Note: triangle/rectangle KERNEL math was already a faithful identical port.
   Deferred (low, part-4-coupled): relocating the FLL/predistortion buttons out of the gen
   footer. Audio output still needs hardware to confirm.
4. **FFT** + wizard — to completion.
5. **FreqResp** + wizard — to completion.

🔒 **No oscilloscope/Scope changes without explicit user review.** When building the
FFT/FreqResp **Utility** tabs, preserve the user's manual button/icon-size CSS (and generalize
the shared tab-toolbar classes — Presets / Utility / Save to… / Load from… / Load calibration…
— for reuse across Scope, FFT, FreqResp).

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
  running WEB app at a fixed **1280×768** window. Ready: app/scope/generator; the rest are
  skipped until their panes land.
- **sync-help** skill — the Java→web help refresh loop.
- Help menu (`#menuHelp`) + **F1** (contents) / **Ctrl+F1** (contextual) → open
  `help/<lang>/index.html` in a 1024×800 pop-up window, its top-left corner aligned to the
  app window's top-right corner.

**TODO (part 1):** capture the web screenshots per feature once its pane is complete; optional
in-app `?hl=` search-term highlighter (the desktop HelpViewer injected one).

> NOTE: the "State of the port" inventory above predates the live wiring done since (scope view,
> signal-measurements + the contiguous measurement pool, partial generator, FFT view). It is
> being reconciled feature-by-feature through this loop.

---

## 1. FFT analyzer brain — `web/js/fft/fft-analyzer.js` + `fft/fft-result.js`  ✅ PORTED, ⛔ UNWIRED

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

### What `analyze` produces that the engine needs
`fundamentalHzRefined`, `fundamental2HzRefined`, `fundamentalTrueDbFs`,
`amplitudeDbFs`, `freqResolution`, `re`/`im`, `imdProduct*`, `rawFund*`/`rawPeak*`
— i.e. the EXACT `r` object the IMD analyzer, both predistortion accumulators and
the .fft saver consume. Wiring this is the prerequisite for every dual-tone /
predistortion feature.

### INTEGRATION TODO — FFT brain
1. **Replace the hand-rolled spectrum path.** In `backend.js`, the per-frame FFT
   today happens in `fft-worker.js` and the averaging/fundamental/THD in
   `_processSpectrum`/`_finalize`. Two viable shapes:
   - **(a) Buffer-and-analyze** (simplest, matches Java's frame loop): collect a
     window of `N·(frames)` capture samples into a `Float64Array`, then call
     `new FftAnalyzer().analyze(buf, inRate, fftSize, harmCount, windowToken,
     overlapToken, snrMin, snrMax, coherent, NaN, expectedFundHz)` on a worker.
     `analyze` does its own cross-frame coherent/incoherent averaging, so the
     engine's `accRe/accIm/pavg` accumulators become redundant.
   - **(b) Keep the streaming pool** but have each worker return `re/im`, and on
     the main thread average into an `FftResult` and call `recomputeStats(r)` for
     metrics. More code; only worth it if the realtime FPS of (a) is too low.
   Pick (a) unless profiling forces (b).
2. **Map UI tokens to enum tokens.** `index.html` uses `blackmanHarris/hann/rectangular`
   and overlap `0/50/75/87.5/93.75`. `buildWindow` wants `BH4/HANN/RECT/BH7/FT/…`;
   `overlap` wants `PCT_0/PCT_50/PCT_75/PCT_87_5/PCT_93_75` (or a fraction). Add a
   small map in `readConfig()` (or switch the `<option value>`s to the enum
   tokens directly). Expose the full 13-window list in the `#window` select.
3. **Feed the FLL.** The worker FLL today reads `r.fundIm/r.fundRe`. With the
   brain, derive the fundamental phasor from `result.re[fundamentalBin]` /
   `result.im[fundamentalBin]` (coherent path) and keep steering the generator —
   OR drive the FLL from `fundamentalHzRefined` directly (sub-bin, cleaner).
4. **Result payload.** Replace the ad-hoc `onResult({mag, binW, fundBin, metrics…})`
   with the `FftResult`. Update `ui/fft-view.js` + `app.js updateReadout` to read
   `amplitudeDbFs`, `fundamentalDbFs`, `fundamentalHzRefined`, `thdPct`, `thdDb`,
   `snrDb`, `sinadDb`, `avgNoiseFloorDbFs`, `harmonicDbFs[]`/`harmonicHz[]`.
5. `harmonicCount` should be `max(9, prefs.fftCalcMaxHarmonic)` once preferences
   is wired; default 9 until then.

---

## 2. IMD / dual-tone analyzer — `web/js/fft/imd-analyzer.js`  ✅ PORTED, ⛔ UNWIRED

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

### INTEGRATION TODO — IMD analyzer
1. **Generate the second tone.** Switch the engine's output node from
   `generator-processor` to the faithful `dds-processor` (module 3) and set
   `form: DUAL_TONE`, `frequency`, `frequency2`. (`generator-processor` cannot
   make two tones.) Gate `analyzeImd` on dual-tone mode (Java attaches `ImdResult`
   only in DUAL_TONE) — mirror that.
2. **Produce a dual-tone `r`.** With the FFT brain wired (module 1),
   `FftAnalyzer.setMultiTone(true)` + `setSecondToneHintHz(f2)` makes `analyze`
   populate `fundamental2HzRefined` and the IMD-product grid directly — pass that
   `FftResult` straight to `analyzeImd`. Until the brain is wired, hand-build
   `{amplitudeDbFs: mag, freqResolution: binW, fundamentalHzRefined: <refine
   fundBin> | NaN, fundamental2HzRefined: <refine tone2 bin> | NaN,
   fundamentalTrueDbFs: NaN}` (the analyzer falls back to its own local-peak
   refine when the refined estimates are NaN/≤0).
3. **Supply `dbvOffsetDb`.** Use `Preferences.instance().dbvOffsetDb` (module 9)
   once wired; ratios (`*Pct`, `diffHz`, `*DbFs`) are offset-invariant so `0` is a
   safe placeholder.
4. **Call + render.** In `_finalize` (dual-tone branch) attach `imd =
   analyzeImd(r, c.toneHz, c.tone2Hz, dbvOffset)` to the result payload. Add an
   "IMD" readout (the `#fftTabs` has an inert "THD settings" tile — add an
   analogous IMD tile) showing `f1/f2 Hz`, `f1/f2 DbV` + `Mag`, `diffHz`,
   `dfd2Pct/dfd3Pct`, `imdPwrPct`, `tdnPct`, and the `dnL*/dnH*` table (`2..5`).
   In `ui/fft-view.js` mark both tones at `f1DbFs`/`f2DbFs`.
5. **Mode switch** SINGLE↔DUAL_TONE changes generator structure → route through
   the same restart path as the structural FFT changes
   (`#fftSize,#window,#overlap,#threads` handler: `stop(); readConfig(); start()`).

---

## 3. Signal generator (DDS) — `web/js/generator/dds-kernel.js` + `audio/worklets/dds-processor.js`  ✅ PORTED, ⛔ UNWIRED

Faithful port of `SignalGenerator` (DDS kernels, 64-bit BigInt phase accumulator,
all waveforms, pink-noise Voss–McCartney, `.dpd` comp, dither). The worklet
registers `dds-processor` (does NOT replace `generator-processor`).

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
- Built from `processorOptions {form,frequency,amplitudeVRms,dacFsVoltageAmpl}`.
- `port.onmessage` protocol: `{form, frequency, frequency2, amplitudeVRms,
  dacFsVoltageAmpl, rectDuty, triDuty, dualAmp1Pct+dualAmp2Pct, linearSweep,
  logSweep, sweepParams, resetSweepPosition, compensation, dualToneCompensation,
  dpdText(+dpdFrequency), clearCompensation, type:'start'|'stop'}`.

### INTEGRATION TODO — DDS generator
1. **Swap the output worklet.** In `backend.start()` replace
   `addModule('./worklets/generator-processor.js')` + the `generator-processor`
   node with `./worklets/dds-processor.js` + a `dds-processor` node. Construct it
   with `processorOptions: {form, frequency: snapped, sampleRate: outRate,
   amplitudeVRms, dacFsVoltageAmpl}`. NOTE the kernel takes **V RMS** amplitude,
   not dBFS — convert the UI `ampDbfs` (or wire preferences `genAmplitudeVrms`).
2. **Rewrite `retuneGenerator()`** to post the DDS protocol
   (`{frequency}` / `{amplitudeVRms}`) instead of `{inc}` / `{amp}`. The FLL steer
   in `_processSpectrum` posts `{inc: …}` today → change to `{frequency: genFreq}`.
3. **UI controls.** `index.html` has a single `<option>Sine</option>` in
   `#signalForm` and disabled Duty / Dither / Corrections fields. Populate
   `#signalForm` from `GenSignalForm`; enable Duty (→ `setRectangleDuty`/
   `setTriangleDuty`), Dither (→ `genDitherBits` + `quantizePcm` on file render),
   and the Corrections file field (→ read `.dpd`, post `{dpdText}`). Add F2
   freq/amp inputs for DUAL_TONE.
4. **Sweeps / .dpd** are message-driven and ready; wire the freqresp tab (module 5)
   and the predistortion engine (module 6) to post `logSweep` / `compensation`.

---

## 4. Oscilloscope DSP — `web/js/scope/scope-trigger.js` + `scope/signal-measurements.js`  ✅ PORTED, ⛔ UNWIRED
Supporting: `dsp/lanczos.js`.

### Exports — `scope-trigger.js`
- `find(data, n, from, to, level, rising, sincRefine, hysteresis, minSpacingSamples=0) -> number` (rightmost crossing, −1 if none).
- `refine(data, n, a, b, level, rising) -> number` (10-iter sinc bisection).
- `linear(prev, curr, prevIdx, level) -> number`.

### Exports — `signal-measurements.js`
- `compute(data, n, sampleRate, peakVolts) -> {vpp,vrms,vmean,period,riseTime,fallTime,frequency,dutyCycle}`.
- `refineFrequencyAround(data, n, sampleRate, seedHz, halfHz) -> number`.
- `withFrequency(m, freq) -> m'`, `withoutTimes(m) -> m'`.
- `reconstructBeatSignal(data, available, sampleRate, f1Hz, f2Hz, scratch?) -> Float32Array`.

### Exports — `lanczos.js`
- `lanczos(data,n,t,scale) -> number`, `lanczosNaN(data,n,t,scale) -> number`, `sinc(x) -> number`;
  consts `LANCZOS_A`(16), `MAX_LANCZOS_DOWNSAMPLE`(5), `LANCZOS_PADDING`(80).

### INTEGRATION TODO — scope DSP
1. **Replace the prototype trigger in `ui/scope-view.js`.** It uses a bare rising
   zero-cross. Compute a half-amplitude `level`, then
   `find(buf, n, searchFrom, searchTo, level, rising, sincRefine, hysteresis)`
   with `searchFrom = LANCZOS_PADDING + leftHalf + 1`,
   `searchTo = available - rightHalf - LANCZOS_PADDING` (import `LANCZOS_PADDING`).
   Use `lanczos(buf, n, t, scale)` to draw band-limited sample dots.
2. **Measurements readout.** Call `compute(buf, n, sampleRate, peakVolts)`
   (`peakVolts = adcFsVoltageRms·√2`) and show Vpp/Vrms/Vmean/freq/period/duty/rise/fall
   in the scope tile (`#scopeTabs` Left/Right tiles show inert `100u ac sin` text today).
3. **DUAL_TONE beat view.** When dual-tone, feed `find` the output of
   `reconstructBeatSignal(buf, available, sampleRate, f1, f2)` with
   `sincRefine=false`, and on the measurements apply
   `withFrequency(m, refineFrequencyAround(rawSel, …))` / `withoutTimes(m)`.
4. The mains comb / settled-tail measurement (module 9 `mains/`) is a separate
   worker concern; not required for the basic scope readout.

---

## 5. Frequency response (Farina) — `web/js/freqresp/farina-sweep.js` + `freqresp/deconvolve.js`  ✅ PORTED, ✅ WIRED

> Wired in `web/js/shell/freqresp-host.js` (driven from `index.html` `#tab-fr`):
> drives a LOG_SWEEP through the live DDS (`engine.postGen({logSweep},{form:LOG_SWEEP})`),
> records the loopback via the engine's new capture-recording tap, deconvolves with
> `computeFromLogSweep(renderLogSweep(...))`, plots magLin→dB + phase on `#frPlot`,
> RIAA overlay via `dsp/riaa.evalDb`, save/load `.frc` (+ `divideInPlace` de-embed of
> a loaded `.frc`).
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

### INTEGRATION TODO — freqresp
1. **The `#tab-fr` pane is a placeholder `alert`.** Build the FR UI (sweep
   start/end/duration, run button, magnitude+phase plot, RIAA overlay toggle,
   save/load `.frc`).
2. **Drive a sweep.** Use the DDS generator (module 3): post `{logSweep:{f0,f1,
   sweepSamples,leadInSamples}}` then `{form:'LOG_SWEEP'}`; capture the loopback.
3. **Deconvolve.** Call `computeFromLogSweep(captured, renderLogSweep(f0,f1,…),
   leadIn, sampleRate, freqGrid, amplitudeVRms, adcFsVoltageRms,
   sweepFadeSamples(sweepSamples))`. Plot `magLin`→dB and `phaseRad`.
4. **RIAA / cal de-embed.** `evalDb(f, reverse, iec)` for the RIAA overlay;
   `divideInPlace(measured, loadFrc(...).left)` to de-embed a loaded `.frc`.
5. Save/load via `io/frc.js` (module 7) + `io/file-picker.js`.

---

## 6. DAC predistortion — `web/js/predistortion/{harmonic-compensation,intermod-compensation,engine}.js`  ✅ PORTED, ✅ WIRED

> Wired via `web/js/shell/predistortion-host.js` (a `PredistortionHost`) + the
> `#predistModal` wizard in `index.html`/`app.js`: `configureForRun` = coherent ∞
> gen-locked averaging (restart preserving config), `readResult` =
> `FftResult.deepCopy`, `imdPct` = `analyzeImd(...).imdPwrPct`, `applyCompensation`/
> `applyDualToneCompensation` post `{compensation:makeCompensation(...)}` /
> `{dualToneCompensation:makeDualToneComp(...)}` to `dds-processor`,
> `correctionEntries` = the shared loaded-`.frc` store (`frcStore`), anchors from
> `Preferences`. Run control = averages + target THD% with a live progress readout;
> `.dpd` saved via `io/dpd.write{Harmonic,Intermod}Dpd` + `file-picker.saveFile`.
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

### INTEGRATION TODO — predistortion
1. **Implement a `PredistortionHost`** in the shell, bridging the engine to the
   live engine + UI. The host methods must:
   - `configureForRun` → set the FFT to infinite coherent generator-locked
     averaging (needs module 1 wired); `startRecording` → ensure capture running.
   - `readResult()` → `FftResult.deepCopy()` of the last analyzer result (module 1).
   - `imdPct(r)` → `analyzeImd(r, f1, f2, dbvOffset).imdPwrPct` (module 2).
   - `applyCompensation` / `applyDualToneCompensation` → post `{compensation:
     makeCompensation(...)}` / `{dualToneCompensation: makeDualToneComp(...)}` to
     the `dds-processor` (module 3).
   - `correctionEntries` → loaded `.frc` store (`{calibration:{left,right}}`,
     `FreqRespCalibration`-shaped) for `_calResponseAt`.
   - `adcFsVoltageRms`/`genAmplitudeVrms`/`maxHarmonics`/`dualToneSplitPct` →
     `Preferences` getters (module 9).
2. **Wizard UI.** Add a "Run predistortion" control (averages + target THD%);
   wire `onRound`/`onFinished` listeners to a progress readout. The Generator
   pane's inert "Corrections" file field is the save target.
3. **Save `.dpd`.** After `runLoop`, `writeHarmonicDpd(engine.bestApplied, …)` /
   `writeIntermodDpd(engine.bestIntermod, …)` → `io/file-picker.saveFile`.
   `extraHeaderLines` (format_version / kind / provenance) are supplied by the
   caller, not generated by `dpd.js`.

---

## 7. File formats — `web/js/io/{wav,frc,fft-spectrum,dpd,scope-capture,file-picker}.js`  ✅ PORTED, ⛔ UNWIRED

### Exports
- `wav.js`: `readWav(ArrayBuffer|Uint8Array) -> {sampleRate,channels,bitsPerSample,frameCount,ch0,ch1}`;
  `class WavWriter(sampleRate,channels,bitsPerSample,floatFormat=false)` (`writeRaw`, `writeFloats`, `finish() -> Uint8Array`);
  `class AiffWriter(sampleRate,channels,bitsPerSample)` (`writeRaw`, `finish()`);
  `createFlacWriter(...)` (**stub — throws**; FLAC needs a WASM codec), `FLAC_MAX_SAMPLE_RATE`(655350).
- `frc.js`: `saveFrc(stereo, meta={}) -> string`, `loadFrc(text) -> {left,right}`, `FRC_FORMAT_VERSION`(1).
- `fft-spectrum.js`: `loadSpectrum(text, dbvOffsetDb, harmCount, parabolicBinInterp) -> {result, modeImd, tone1Hz, tone2Hz, dbvOffsetDb}`,
  `saveSpectrum(r, meta={}) -> string`, `FFT_FORMAT_VERSION`(1), `LOADED_FUND_MIN_HZ`(10).
- `dpd.js`: see module 6.
- `scope-capture.js`: `saveScopeCapture(left, right, actual, fileName, sampleRate, bitDepth, signalFrequencyHz=0) -> Uint8Array`;
  `formatForName`, `packStereo`, `decodeStereo`, `findFullPeriodWindow`, `CHANNELS`(2).
- `file-picker.js`: `async saveFile(data, suggestedName, types=[]) -> {name, saved}`,
  `async openFile(types=[]) -> {name, bytes}|null`, `bytesToText(bytes) -> string`.

### INTEGRATION TODO — file I/O
1. **Generator "Save to…"** → render N seconds via `DdsKernel.fill` (or the
   running worklet), `quantizePcm` per sample at `genDitherBits`, write with
   `WavWriter`/`AiffWriter`, hand to `saveFile`. FLAC paths throw → fall back to
   WAV/AIFF.
2. **Scope "Save to…"** → `saveScopeCapture(L, R, actual, name, rate, depth, freq)`
   then `saveFile`. "Load signal…" → `openFile` + `readWav` (or `decodeStereo`).
3. **FFT "Save to…"/"Load from…"** → `saveSpectrum(result, meta)` /
   `loadSpectrum(text, dbvOffsetDb, max(9,maxHarm), MathUtil.parabolicBinInterp)`
   (import `parabolicBinInterp` from `dsp/mathutil.js`); after load run
   `FftAnalyzer.recomputeStats(result)` and, for IMD files, `analyzeImd(...)`.
4. **FFT "Load calibration…"** → `loadFrc(text)` into the `.frc` store the
   predistortion engine + freqresp de-embed read.
5. All the inert file fields in `index.html` (Corrections, Save to…, Load from…,
   scope Save/Load, FFT Save/Load/Load-calibration) are the wiring targets.

---

## 8. Supporting DSP — `web/js/dsp/{mathutil,tone-lobe-lift}.js` + `dsp/mains/{frequency-tracker,comb-filter}.js`  ✅ PORTED, ⛔ UNWIRED

### Exports
- `mathutil.js`: `chebyshevT(n,x)`, `acosh(x)`, `parabolicBinInterp(re,im,peakBin,fftSize)`, `nextPow2(x)`, `besselI0(x)`.
- `tone-lobe-lift.js`: `class ToneLobeLift(floorGap=10, floorSpan=2048, maxLobeBins=2048)` —
  `localFloor(mag,peak,maxBin)`, `lobeBins(mag,peak,maxBin,floor) -> [lo,hi]`, `stretch(mag,floor,peak,factor)` (`mag` = `k=>magnitude` accessor).
- `mains/frequency-tracker.js`: `class MainsFrequencyTracker(sampleRate)` —
  `track(ref,len) -> number|NaN`, `getLockHz()`, `resetTracking()`; consts `MIN_MAINS_HZ`(45), `MAX_MAINS_HZ`(65).
- `mains/comb-filter.js`: `class MainsCombFilter(sampleRate, notchBandwidthHz)` —
  `getMainsHz`, `isTuned`, `magnitudeAt(f)`, `correctionDb(f)`,
  `applySpectrumCorrection(dbFs,dbV,freqResolution,f0Hz)`, `retune(f0)`, `track(ref,len)`,
  `process(data,len,absStart=0)`, `processPreservingDc(...)`, `reset`, `resetTracking`;
  const `DEFAULT_NOTCH_BANDWIDTH_HZ`(2.5).

### INTEGRATION TODO — supporting DSP
- `parabolicBinInterp` is a dependency of `io/fft-spectrum.loadSpectrum` (module 7).
- `mains/` is consumed by the scope worker (mains suppression, settled-tail) and
  the FFT mains-rejection path — wire when those features are exposed in the UI
  (`oscLeftMainsSuppression`/`fftMainsSuppression` prefs are already modelled,
  module 9). `factory.js` (`MainsFilters.of`) was NOT ported (its other cases need
  `MainsSyncSubtractFilter`/`MainsLmsFilter`, out of scope).
- `tone-lobe-lift.js` is a display enhancement (lobe lift in the FFT plot) — wire
  into `ui/fft-view.js` when desired; not on any critical path.

---

## 9. Preferences & persistence — `web/js/store/preferences.js`  ✅ PORTED, ⛔ UNWIRED

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

### INTEGRATION TODO — preferences
1. **Bootstrap.** `const prefs = Preferences.instance(); prefs.load();` at app
   start; `prefs.flush()` on `pagehide`/`beforeunload`.
2. **Bind the UI to `Property`s** instead of `engine.config`. Replace the
   transient `readConfig()` reads with `prefs.fftLength.get()`,
   `prefs.fftWindow.get()`, `prefs.genFrequencyHz.get()`,
   `prefs.genAmplitudeVrms.get()`, etc.; write back on change. The Preferences
   modal device/rate selects map to `prefs.current()` `BackendPrefs`
   (`inputDeviceName`/`outputDeviceName` = Web Audio `deviceId`,
   `inputSampleRate`/`outputSampleRate`).
3. **Supply the calibration anchors** every other module needs:
   `prefs.dbvOffsetDb` (IMD/FFT dBV), `prefs.adcFsVoltageRms` (scope peakVolts,
   predistortion), `prefs.dacFsVoltageAmpl` (DDS amplitude scale).
4. Dialog helpers `copyForDialog`/`applyFromDialog` were NOT ported (SWT-only);
   add them if a non-trivial Preferences-dialog apply/cancel is needed.

---

## 10. Internationalization — `web/js/i18n/{i18n,locales}.js` (+ `web/i18n/messages.properties`)  ✅ PORTED, ⛔ UNWIRED

Faithful port of `I18n` + language discovery.

### Exports
- `i18n.js`: `t(key, ...args) -> string`, `async setLocale(tag) -> Promise<void>`,
  `async initBase() -> Promise<void>`, `getLocale() -> string`,
  `parseProperties(text) -> Map`, `messageFormat(pattern, args) -> string`.
- `locales.js`: `LOCALES` (frozen `[{tag,file,endonym}]`, 32 entries),
  `localeByTag(tag) -> LocaleEntry|undefined`.

### INTEGRATION TODO — i18n
1. **Bootstrap.** `await initBase();` then `await setLocale(prefs.uiLanguage.get())`
   before first paint.
2. **Externalise strings.** `index.html` + `app.js` hard-code English. Replace
   visible strings with `t('key')` (keys already exist in
   `web/i18n/messages.properties`, 649 keys English base). Add a language picker
   populated from `LOCALES`, writing `prefs.uiLanguage`.
3. **Stage other locales.** Only `messages.properties` (English) is in `web/i18n/`.
   Copy `src/main/resources/i18n/messages_<tag>.properties` into `web/i18n/` to
   enable each language; `setLocale` fetches them on demand (falls back to base
   English per missing key).

---

## Modules referenced by the source app but NOT ported

- **`MainsFilters.of` factory** (`dsp/mains/factory.js`) — needs
  `MainsSyncSubtractFilter` / `MainsLmsFilter` (out of scope). Only the COMB case
  is ported.
- **FLAC encode** — `io/wav.js createFlacWriter` is a validating stub; needs a
  WASM codec. Save paths must fall back to WAV/AIFF.
- **`USE_IR_GATING` branch** in the freqresp deconvolution — compiled OFF in Java,
  intentionally omitted.
- **`copyForDialog`/`applyFromDialog`** Preferences SWT-dialog helpers — not ported.

---

## Summary

- **10 of 10** listed subsystems are ported, faithful, and syntax-clean.
- **0 of 10** are wired: `audio/backend.js` + `shell/app.js` + `index.html` still
  run entirely on the prototype DSP (`dsp/analyzer.js`, `dsp/fll.js`,
  `worklets/generator-processor.js`) and import none of the ported modules.
- **Critical path** to a faithful live app, in order:
  1. **FFT brain** (module 1) — the `FftResult` producer everything else consumes.
  2. **DDS generator** (module 3) — required for dual-tone, sweeps, .dpd, dither.
  3. **Preferences** (module 9) — supplies `dbvOffsetDb` / `adcFsVoltageRms` /
     `dacFsVoltageAmpl` and replaces the transient `engine.config`.
  4. IMD (2), scope DSP (4) and file I/O (7) layer on once 1+3 land.
  5. Freqresp (5) and predistortion (6) are end-features depending on 1+3+7+9.
