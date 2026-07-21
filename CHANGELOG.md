# Changelog

All notable changes to **Phonalyser** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] — 2026-07-21

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
- **Per-card calibration profiles.** Full-scale calibration now belongs to the
  physical card, not the app: each card carries a device-name match list — so it
  is recognised across every backend — and a range table with one row per
  attenuator / DIP position (e.g. both settings of a Cosmos ADC); the crosshair
  calibrations write into the card's active range — creating the profile
  automatically on first calibrate — and switching devices or backends never
  mixes calibrations up. Cards without a profile keep using the previous shared
  values.
- **Dither depth in bits or dBV, checkable on the FFT.** The generator's dither
  control is a numeric field you drive in bits or directly in dBV. The dBV is
  full-scale-aware and also carries the FFT analysis window's
  equivalent-noise-bandwidth term, so it reads straight off the FFT noise floor
  — enter −100 dBV and, with incoherent (power) averaging, the floor sits at
  −100 dBV. Wheel/arrows step ±1 bit or ±10 dBV, and `Off` is typed straight in
  as `o` / `of` / `off` rather than picked from the old drop-down; the entered
  value is held when you change the FFT window or recalibrate.
- **Per-channel (left / right) calibration.** A stereo card calibrates each
  channel into its own full-scale: the calibration dialog — now one unified form
  for the ADC and the DAC, with values entered directly in nV / µV / mV / V —
  shows a Left row and a Right row (during an FFT calibration the row for the
  channel it is not analyzing is disabled). Every scope trace then uses its own
  channel's full-scale and the FFT dBV axis follows the analyzed channel. This
  now applies to range-linked stereo cards as well — shared range switching, but
  separate left / right values per row — not only cards whose channels switch
  range independently; the latter (the E1DA Cosmos ADC's independent left / right
  DIP) additionally get Left / Right active columns in the ranges table.
- **Card editor & automatic recognition.** A dialog in Preferences creates and
  edits cards — name, the device-name match list, mono / stereo, per-direction
  range coupling (linked or independent), and a flag for cards whose full-scale
  is supplied by the device itself. Selecting a device with no matching card
  offers to create one, pre-filled from that device; a device that does match a
  card selects it automatically.
- **Known-card catalog.** Cards the app already knows (E1DA Cosmos ADC,
  JLsounds I2SoverUSB) are recognised by device name and offered
  pre-configured with their nominal ranges; your own crosshair calibration
  then refines each unit's values. All card profiles live in `devices.yaml`
  next to the preferences file — seeded on first run, editable through the
  Preferences dialog or by hand in a compact, documented format (one line per
  range, per-channel value pairs; the help's Preferences chapter describes it).
  An upgrade adds newly known cards and ranges and refreshes the nominals of
  ranges you have not calibrated, while never altering a row you calibrated, a
  card you created, or your active-range selections — so no calibration you made
  is ever lost.
- **QA40x analyzer backend.** A QuantAsylum QA402 / QA403 can be driven
  directly over USB (libusb), with the vendor software closed — a new **QA40x**
  backend alongside WASAPI / WDM-KS / JavaSound. It runs the analyzer as one
  always-duplex session on the device's single sample-rate clock, so the input
  and output rates are held equal; delivers true 24-bit samples; and takes
  full-scale from the device's own range calibration rather than a crosshair
  calibration, so the dBV axis and scope readouts are right as soon as you pick
  the range. Where the native libusb library is absent the backend is simply
  reported unavailable; it ships bundled on Windows and macOS, while Linux uses
  the distribution's own libusb — there the `.deb` installer also sets up the
  analyzer's USB access permissions (udev) automatically, and the README
  carries the one-file manual setup for JAR installs.
- **Output-channel selection.** The signal generator, the frequency-response
  sweep and the notch tuner each gain a Left / Right / Both output selector that
  gates the driven lane live.
- **DSO-grade dense trace rendering.** Above one sample per pixel the scope now
  rasterises the whole capture window the way a digital-phosphor oscilloscope
  does — a per-pixel dwell histogram plus a round coverage pen of exactly the
  configured trace width, swept along the band-limited (sin x/x) crest and
  trough of every column — instead of decimating to one point per column.
  Narrow pulses, noise bands and dual-tone beat envelopes keep their true
  peak-to-peak at any zoom; steep flanks anti-alias with proper per-row edge
  ramps (Xiaolin-Wu style) on both sides; and because the result is a single
  blitted intensity image rather than a stroke of every period, a one-second
  window renders at full capture rate where brute-force drawing dropped to
  ~1.5 captures/s. The rasterisation runs on a half-pixel grid and the trace
  keeps the same brightness and AA fringe as the sparse sin x/x stroke, so
  nothing changes visually when zooming across the one-sample-per-pixel
  boundary.
- **Documentation.** Help chapters for the new Filters / Unevenness tabs and
  the residual view, the scope's per-channel mains-rejection and low-pass
  controls, and the per-card calibration setup with a worked two-card
  example — in English, German and Ukrainian.
- **Tip of the day learns the new features.** Seven new tips — the residual
  view, the ideal-filter overlay and Compare, the Unevenness readout, per-card
  calibration, the output-channel selectors, the DSO-grade dense rendering and
  the discontinuity guard's off switch — in English and all 31 translations,
  each naming the controls by that language's own UI labels. A few catalog
  defects went with it: two English typos, a stray Cyrillic letter, and a
  Ukrainian tip that had inverted the meaning of WASAPI exclusive mode. The
  web app gains the Tip-of-the-day popup itself — in the Help menu, and at
  startup under the same preference as the desktop.
- **Web version catch-up.** The browser port now carries the full per-card
  calibration system — cards, ranges, the card editor, the known-card catalog
  (read 1:1 from the very `devices.yaml` the desktop ships, parsed in the
  browser; the profile store lives in the browser's local storage and follows
  the same upgrade-merge rules), per-channel left / right calibration through
  the unified ADC / DAC dialog, and per-channel full-scale in every view. It
  also gains the Left / Right / Both output-channel selectors, the DSO-grade
  digital-phosphor dense trace renderer, scope display persistence (WebGL2)
  with the same sample-dots-defer-to-persistence rule, the dual-tone
  frequency-lock loop and IMD de-embedding with F1/F2 marker dots and
  pre-calibration dots, the manual-fundamental lobe stretch — the
  fundamental's whole main lobe lifted to the entered level with a blue dot
  marking the original height, "not measurable" (---) IMD readouts instead of
  fictitious floor values, sample-grid-aligned rectangle AND triangle
  generation with bracketed corrected frequency / duty labels, the dither
  field entered in bits or a full-scale-aware dBV level and applied live to
  the generated signal so it reads straight off the FFT floor, the typed
  named values on both numeric fields — dither `Off`, and FFT averages down
  to a single spectrum shown as `Off` plus `∞` averaging — each taken in full
  or in any short form (`o` / `of` / `off`, `i` / `in` / `inf`), a startup
  splash, Java-parity preferences in a fixed 640 × 480 dialog with free
  numeric entry, an output-sample-rate probe with an honest resampling
  warning, a Preferences audio-device or sample-rate change applied live to
  the running measurement — the capture restarts on an input change, a
  playing generator (tone or file) on an output change, at the new settings —
  scope V/div down to 1 nV/div, and a web-only help page on
  input-device sample rates (en / de / uk). The one desktop calibration
  feature the web does not support is device-provided full-scale (the
  QA40x-style flag) — a browser cannot reach a device's USB calibration
  interface; such cards keep their catalog values.
- **FFT averages: `Off` is reachable at all, and `∞` can be typed.** The averages
  field started at 2, so switching averaging off was not possible from the UI —
  even though the analyser already treats fewer than two averages as no
  averaging. The count now goes down to a single spectrum, shown as `Off`. And
  `∞`, until now only reachable by rolling the wheel or stepping with the arrows,
  can be typed directly. Both names are taken in full or in any short form —
  `o` / `of` / `off` and `i` / `in` / `inf`.

### Changed

- **Loaded `.frc` measurements** now take their analysis bandwidth from the
  file's own recorded sample rate (the live device rate only for legacy,
  header-less files).
- **Compare auto-zoom** fits the difference curve with a symmetric 2 dB
  margin, and switching compare off re-fits the view to the measured curve.
- **Notch tuning** now measures both channels on every pass and lets you choose
  which channel the embedded view shows (Left / Right), with the null readout
  repositioned clear of the display controls.
- **Sample dots defer to persistence.** With display persistence active the
  scope no longer draws the per-sample dots — repeated frame after frame they
  piled into opaque blobs on the afterglow and buried the trace history it is
  there to show. They return, as configured, the moment persistence is off.

### Deprecated

- **Shared full-scale calibration.** The single shared ADC / DAC full-scale
  values (`adcFsVoltageRms` / `dacFsVoltageRms` in preferences.yaml) are
  superseded by the per-card calibration profiles. They remain only as the
  fallback for devices that have no card in `devices.yaml` and will be removed
  in the release after this one.

### Fixed

- **Linux: GTK input-method startup crash.** A configured ibus / fcitx input
  module whose daemon is dead or missing crashed the app at launch; the input
  method is now pre-flighted and falls back to XIM only when actually broken —
  working IME setups keep their input method.
- **Linux: GPU scope under Wayland.** The GL trace canvas could not obtain a
  context on a Wayland session; with GPU rendering enabled the GTK backend now
  switches to X11 (XWayland) automatically.
- **Glitch / discontinuity rejection.** The time-domain discontinuity guard
  behind the scope's glitch trigger and the FFT's frame rejection now references
  its threshold to the signal amplitude, so it no longer false-triggers on clean
  tones as their frequency rises — detection is flat across frequency and
  independent of level, while still catching the real sample-loss splices it is
  meant to reject. A new switch on the FFT's settings tab turns the guard off
  entirely.
- **Audio changes restart what was running.** Changing the backend, device,
  sample rate or bit depth in Preferences now stops every running module before
  the change and brings it back on the new settings afterwards — the FFT
  analyzer (previously left stopped), the scope capture, and a playing
  generator, tone or file alike (previously left playing into the old device).
- **Generator changes restart the FFT and clear the scope afterglow.** Changing
  the dither, the output level or the output-channel selection now restarts the
  FFT statistics and averaging accumulator and clears the scope display
  persistence — the same reset a frequency or amplitude change already performed
  — so an averaged measurement never mixes the previous signal with the new one.
- **Scope channel guards.** The measurements table and the trigger source can no
  longer be pointed at a disabled channel — selecting one auto-switches to a live
  channel, and the choice survives starting a capture and applying a preset.
- **Scope screenshots.** The built-in screenshot now includes the
  digital-phosphor persistence trails and draws the traces — main view and
  condensed overview alike — at the configured trace width (they always came
  out 1 px before, whatever the preference).
- **Held-trace horizontal pan.** After switching the trigger mode from Auto to
  Normal or Single with no trigger event yet, the held trace ignored horizontal
  moves (vertical worked); the held frame now pans and zooms exactly like a
  triggered one.
- **Beat envelope on frozen captures.** A held or frozen Single / Normal
  dual-tone capture now draws the reconstructed beat envelope over its captured
  samples — it previously vanished the moment the trace froze.
- **Full-scale markers at fine V/div.** The ±FS dashed lines anchor to the raw
  (virtual-capable) channel offset, so at fine resolution they keep tracking
  until ±FS/2 reaches the vertical middle instead of sticking early.
- **Triangle duty on the sample grid.** A triangle's duty corner has to land on
  a sample just like the rectangle's step edge — off an integer-sample period it
  drifts against the grid cycle to cycle and the tone smears. The triangle now
  runs at the nearest whole-samples-per-period frequency, the Frequency and
  Duty cycle labels show the corrected values in brackets exactly as for the
  rectangle, and WAV export uses the same aligned frequency so looped files
  have no corner seam.
- **Persistence keeps the anti-aliasing.** With display persistence on, each
  new frame was composited into the afterglow with source-over, so a
  stationary trace re-painted its own anti-aliased fringe pixels over
  themselves until they saturated solid — the persisted trace turned
  hard-edged and a fringe wider than the pen. The deposit is now
  brightest-wins (per-component maximum): a fringe pixel can never exceed its
  single-frame coverage, so the persisted trace keeps exactly the
  anti-aliasing and width of a persistence-off frame while decayed history
  fades underneath — and infinite persistence no longer saturates. Desktop
  (OpenGL) and web (WebGL2) alike.
- **Web.** `.frc` de-embedding now corrects dual-tone IMD product lobes too
  (readout table, marker dots and IMD power / DFD all read the corrected
  bins), the frequency-lock loop steers both dual tones instead of one, and
  dual tones entered high-frequency-first no longer cross the F1/F2 pairs —
  readout rows, marker dots, intermod product formulas and both lock loops all
  read the correctly sorted pair.
- **IMD readout.** Intermod products whose frequency falls outside the
  measurable range — SMPTE-style pairs put 2f1 − f2 below DC, high orders
  can land beyond the spectrum — no longer show a physically impossible
  −600 dBV: they read "---", a one-sided DFD3 still reports its measurable
  sideband, and the combined IMD power skips them. Fixed in the desktop
  app and the web version alike.
- **Low sample rates were never offered.** The selectable rate list was
  effectively floored at 44.1 kHz. The standard lower rates — 8000, 11025,
  16000 and 22050 Hz — are now probed on every backend and offered wherever
  the device actually supports them (except the QA40x, whose 48 / 96 / 192 kHz
  are fixed in hardware).
- **Blue dot missing on a manual-fundamental lobe.** With a manual fundamental
  set (and no calibration loaded), the fundamental lobe was stretched up to the
  entered level but the blue dot marking the original measured height was not
  drawn. It now appears, as it already does with a calibration loaded.
- **Scope rubber-band zoom at deep magnification.** At a few samples per
  screen — where the whole view is a sin x/x reconstruction between a handful
  of samples — the zoom box and the edge time marks were mapped over the
  continuous time-per-division product while the trace is drawn over a
  whole-sample window. That round-off, multiplied by a trigger parked screens
  outside the view, slid each successive zoom sideways and let the time marks
  disagree with the trace. Zoom capture, zoom commit and the edge marks now
  all use the exact sample window the renderer draws, so the boxed detail
  lands under the box at any depth and the marks match the trace.
- **Scope handles and value labels at the view edges.** The channel-offset and
  trigger-level triangles sat on the border pixel — half swallowed by the
  view frame — and their voltage labels clipped when a handle reached the top
  or bottom edge; both now stay fully visible just inside the view. The
  offset voltage label was also computed from the clamped on-screen handle
  position, so once a deep zoom parked the offset outside the grid the label
  froze and wheel moves appeared to do nothing; it now reads the true offset
  however far outside the grid it sits.
- **Glitch trigger selectable in Auto after a capture start.** Starting the
  scope re-enables the whole trigger toolbar, and that blanket enable
  resurrected the Glitch type button in Auto mode — where a caught glitch
  frame would be overwritten by the next free-run repaint, the very reason
  the combination is blocked. The Glitch-outside-Auto gate is re-applied now.
- **macOS: GPU scope window placement.** The GL trace window opened shifted
  upward until the first mouse-over or resize forced a reposition, and with
  the scope idle it did not follow the main window when dragged. It now
  re-tracks its pane while the opening window chrome settles and on every
  main-window move.
- **macOS: crash on exit.** Quitting could die with "Graphic is disposed":
  tearing down the GL scope window pumps the event loop mid-shutdown, which
  could deliver one last paint to a toolbar arrow whose icon was already
  disposed. That late paint is now skipped.

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
