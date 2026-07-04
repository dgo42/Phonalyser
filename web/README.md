# Phonalyser.web

A browser port of the Java/SWT **Phonalyser** precision audio‑measurement workbench.
Vanilla ES modules + **Bootstrap 5** + **jQuery** for the chrome/controls, **Canvas2D**
for the views, **Web Audio + AudioWorklet** (separate input/output `AudioContext`s) for
I/O, and a **Web Worker pool** for the multi‑threaded FFT. Served as static files — no bundler.

The DSP layer is a near‑mechanical port: JavaScript `number` is IEEE‑754 binary64, the
same type as the desktop's Java `double`, so the math is bit‑identical (the loopback
prototype reproduced the desktop's −188 dBFS coherent floor and sub‑0.1 ppm FLL lock in a tab).

## Status — runnable first slice

Implemented and wired end‑to‑end (the **Multifunctional** tab):

- **FFT analyzer, multi‑threaded.** Live capture → ring + **overlap** (0/50/75/87.5/93.75 %)
  → **pool of FFT workers** (one whole FFT per worker, frame‑parallel) → in‑order drain →
  **glitch‑frame rejection** → **FLL** steering the generator onto the captured tone →
  **coherent** (complex, de‑rotated) averaging once locked, else incoherent power.
- **Generator** (sine) via AudioWorklet on the output context, FLL‑steered.
- **Scope** (triggered, autoscaled) and **spectrum** (log‑frequency) Canvas2D views.
- Readout: fundamental / THD / harmonics / noise floor / SNR, plus FFT fps, worker count,
  per‑FFT ms, overlap/hop, dropped frames, and FLL lock/ppm.

**Frequency response** tab is a placeholder — next on the roadmap.

## Install & build

```
cd web
npm install        # installs deps and auto-vendors the libs into vendor/ (postinstall)
npm run build      # bundles the app with esbuild → html/web/ (the production build)
```

`npm install` copies the version-pinned npm libs (Bootstrap, jQuery, libFLAC) into
`vendor/` via the `vendor` script (run on `postinstall`, or on demand with
`npm run vendor`). `npm run build` then bundles the ES modules into the fewest files
(worker + worklets inlined) under **`html/web/`** and copies the static assets + `vendor/`
alongside, injecting the `package.json` version into `index.html`, `sw.js`, and
`version.json`.

In **production** serve `html/web/` (the bundled build — fewer HTTP requests). For
**development** you can still serve `web/` directly: it runs unbundled as plain ES
modules with no build step, so edits are live on reload.

## Run it

Web Audio needs a **secure context**. Serve `web/` (dev) or `html/web/` (prod) over
`https://` or `http://localhost` (opening `index.html` from `file://` will not get
microphone access). For example:

```
cd web
python -m http.server 8000
# open http://localhost:8000/
```

Then **Scan devices**, allow the microphone, pick your ADC/DAC + rates, and **Start**.
For a true loopback null test, patch a cable DAC‑out → ADC‑in.

> **⚠ Match the OS device rate to the app's rate (Windows).** Browsers play through the OS
> **shared mixer**, not WASAPI‑exclusive mode, so the AudioContext output is resampled to the
> device's shared‑mode rate. If the app's output rate (e.g. 384 kHz) does **not** match the
> Windows device rate, the OS resamples — and if the device is stuck at a lower rate, tones can
> play **slow** (a 1 kHz tone at a 48 kHz device under a 384 kHz request becomes 1000 × 48/384 =
> **125 Hz**). Fix: set the output **and** input device to the same rate/bit depth in
> **Sound control panel → (right‑click the device) → Properties → Advanced → Default Format**,
> e.g. **`32 bit, 384000 Hz`**. The generator logs a warning when the granted rate differs from
> the requested one — enable it with `phonalyserDebug(true)` in the browser console.

> Module Web Workers and AudioWorklets are required (modern Chrome/Edge/Firefox/Safari).
> Bootstrap/jQuery load from jsDelivr; vendor them under `vendor/` for offline use.

## Architecture (target tree from the synthesized blueprint)

```
web/
├── index.html            css/app.css
├── js/
│   ├── shell/            app shell, tabs, render loop, event bus        (app.js exists)
│   ├── dsp/              PURE math, DOM-free, worker-safe
│   │   ├── fft.js  window.js  fll.js  discontinuity.js  analyzer.js     ← done
│   │   └── (scope-trigger, signal-measurements, fft-analyzer, imd,
│   │        farina-sweep, deconvolve, frc, fft-compensation, mains/ …)
│   ├── audio/            device boundary
│   │   ├── fft-worker.js                  worklets/{capture,generator}-processor.js  ← done
│   │   ├── backend.js (AudioEngine)       ← done (to be split into devices/
│   │   │                                     input-context/output-context/fft-pool)
│   ├── generator/        controller + full DDS kernel + .dpd
│   ├── scope/            view + controls + measurement worker
│   ├── fft/              view + controls + live analyzer worker
│   ├── freqresp/         Farina sweep + deconvolution + wizard
│   ├── predistortion/    engine + harmonic/intermod compensation
│   ├── io/               wav / flac / aiff + .fft / .frc / .dpd  (the app's own formats)
│   ├── ui/               reusable widgets (numeric-step-field, etc.)    (fft-view, scope-view exist)
│   └── i18n/             JSON bundles from messages*.properties
└── help/                 static HTML help (en/de/uk …)
```

Conventions every new file follows: AGPL‑v3 header; JSDoc on exports; `Float64Array` for all
spectral/accumulator math (`Float32Array` only for raw capture); typed arrays **transferred**
across worker boundaries; faithful‑port comments naming the Java class.

## Data flow

```
ADC ─getUserMedia→ capture-processor ─chunks→ ring ─every hop→ frame
    → [FFT worker 1 … P]  (window → FFT → de-rotate, in parallel)
    → in-order drain → reject(glitch) → FLL(steer gen) → coherent/power average
    → metrics + magnitude → FftView / readout
generator-processor ─(FLL-steered)→ DAC
```

## Roadmap (phased milestones)

1. **FFT analyzer + generator + scope** — live, multi‑threaded.  ← *this slice*
2. **Scope DSP** — Schmitt+sinc trigger, Vpp/Vrms, Goertzel frequency, reconstructed beat.
3. **Full generator/DDS** — triangle/rect/dual‑tone/pink‑noise, units, save‑to‑file (WAV/FLAC).
4. **Frequency response** — Farina log‑sweep render + deconvolution, magnitude/phase, `.frc`.
5. **Predistortion** — harmonic + intermod compensation, `.dpd` round‑trip, wizard.
6. **Preferences** — settings persisted to `localStorage` (YAML schema → JSON).
7. **File IO** — the app's own `.fft` / `.frc` / `.dpd` + standard WAV/FLAC/AIFF import‑export via File System Access API.
8. **i18n** — 32 locale bundles generated from `messages*.properties`.
9. **Help** — static HTML help (en/de/uk) with lunr search.

The full per‑subsystem port specs were produced by the architecture workflow and drive each milestone.
