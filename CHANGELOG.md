# Changelog

All notable changes to **Phonalyser** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.4] — unreleased

### Added

- **Frequency-response ideal-filter overlay & compare.** Overlay an ideal
  low-pass / high-pass / band-pass / notch response (Butterworth, Chebyshev,
  inverse Chebyshev, elliptic, Bessel; designed by spec or by order) on the
  measured curve, anchored to it per type, and compare measured vs ideal the
  same way as against RIAA. Each filter type remembers its own parameters;
  presets carry them.
- **Frequency-response flatness readout.** New Unevenness modes: band edges
  where the response first leaves a ±X dB corridor from its peak (or, with the
  explicit Notch switch, from its minimum), or the ± deviation over a chosen
  range — with on-plot annotation lines and a detachable measurements window.
- **Oscilloscope residual view.** Per-channel captured-minus-best-fit-tone
  display that exposes distortion, noise and glitches hidden under the
  fundamental; works for single and dual tone, with both dual-tone
  frequencies measured from the capture itself, so independent DAC/ADC
  clocks (no FLL) cannot smear the subtraction.
- **Documentation.** Help chapters for the new Filters / Unevenness tabs and
  the residual view, plus the scope's per-channel mains-rejection and
  low-pass controls — in English, German and Ukrainian.

### Changed

- **Loaded `.frc` measurements** now take their analysis bandwidth from the
  file's own recorded sample rate (the live device rate only for legacy,
  header-less files).
- **Compare auto-zoom** fits the difference curve with a symmetric 2 dB
  margin, and switching compare off re-fits the view to the measured curve.

### Fixed

- Dual-tone residual crash at time bases above ~20 ms/div.
- **Linux: GTK input-method startup crash.** A configured ibus / fcitx input
  module whose daemon is dead or missing crashed the app at launch; the input
  method is now pre-flighted and falls back to XIM only when actually broken —
  working IME setups keep their input method.
- **Linux: GPU scope under Wayland.** The GL trace canvas could not obtain a
  context on a Wayland session; with GPU rendering enabled the GTK backend now
  switches to X11 (XWayland) automatically.

## [1.0.3] — 2026-07-04

### Added

- **Notch-filter tuning module.** Live tuning of a passive twin-T notch: a
  continuously looping log-sweep tracks the null in real time — its power-of-two
  period makes the transform shift-invariant, so no trigger or alignment is
  needed — and the notch's own response is then saved as a `.frc` calibration for
  de-embedding.
- **DAC pre-distortion calibration.** A closed-loop wizard that iteratively
  cancels the converter's own harmonics (and dual-tone IMD), pushing the playback
  chain's distortion far below what the DAC produces alone.
- **Web version.** A browser port of the analyzer — FFT, oscilloscope and signal
  generator — running with no install in the
  [web version](https://dgo42.github.io/Phonalyser/web/).
- **Oscilloscope.** Rectangular rubber-band zoom with `Ctrl+Z` rollback, and a
  glitch trigger backed by a signal-discontinuity gate.
- **Documentation.** Expanded Theory of operation (tune-notch measurement, ADC
  characterisation, adaptive mains cancellers, digital-phosphor persistence) and
  the `ALGORITHMS.md` engineering catalogue; the project landing site.

### Changed

- **Oscilloscope.** Whole-period integration for V<sub>mean</sub>/V<sub>rms</sub>
  (removes the partial-cycle scatter that grew with amplitude); exact horizontal
  pan/zoom positioning.

## [1.0.2] — 2026-06-29

### Added

- **Oscilloscope digital-phosphor persistence** (GPU path) with off / fixed /
  infinite / manual decay modes.
- **Scope pan/zoom engine** with connected-envelope rendering.
- **Microsoft Store (MSIX)** packaging and Store listing assets.

### Fixed

- Frequency response — restored the FS/2 sweep-points entry.
- Dropped unused JOGL/GlueGen dependencies (CI build fix).

## [1.0-RC1] — 2026-06-19

- First public release candidate: FFT analyzer (THD, THD+N, IMD, SNR, SINAD,
  ENOB, per-harmonic readout, coherent averaging, selectable windows, `.frc`
  calibration), oscilloscope, frequency response (Farina log-sweep
  deconvolution), signal generator (DDS), and multi-backend audio
  (WASAPI & WDM-KS on Windows, CoreAudio on macOS, JavaSound on Linux).
