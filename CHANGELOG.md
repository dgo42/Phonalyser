# Changelog

All notable changes to **Phonalyser** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
  generator — running with no install at <https://phonalyser.github.io/web/>.
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
