# Harmonic phase trace scripts

Offline analysis of the FFT worker's harmonic phase trace: how the phase of
each harmonic H2..H9 relative to the fundamental behaves frame by frame and
how the coherent accumulator settles over a collection.  Python 3, standard
library only.

## The trace they read

The scripts read the application log (`phonalyser.log` in the Phonalyser
log directory, `C:\Users\<user>\AppData\Roaming\Phonalyser\logs` on
Windows).  The trace lines exist only while the hard switch
`DebugSwitches.TRACE_HARMONIC_PHASE` (module phonalyser-gui-core) is set to
`true` and the application is rebuilt; with the switch on, every analysis
cycle of a coherent average logs two INFO lines from
`org.edgo.audio.measure.gui.fft.FftAnalyzerWorker`:

```
FFT harmonic phase trace, frame 12 (phase excess taken out): H2=-79.15 H3=145.05 ... H9=77.07
FFT harmonic phase trace, accumulator after frame 12: H2=-102.77 H3=145.88 ... H9=100.16
```

- the **frame line** reads that cycle's own spectrum: for h = 2..9 the
  value is wrap(phase(H_h) - h * (phase(F) - phiF)) in degrees, the phase
  of harmonic h relative to the fundamental with the fundamental's phase
  excess phiF (the notch's drift, as the phase alignment measures it) taken
  out.  The first frame of a collection is labelled
  `first accumulated frame, spectrum as it stands` and is traced with
  phiF = 0.
- the **accumulator line** reads the same relation from the accumulated
  spectrum after that frame was added, with no phiF term (the accumulated
  fundamental already carries the alignment).

A pure time shift of the whole capture leaves every value unchanged, so the
relation only moves when the fundamental moves alone - which is what a
notch's thermal drift does to it.

Frame numbers count analysis cycles since the recording started.  A
**collection** is one run of the accumulator: it begins at a
`first accumulated frame` line and ends at the next one or at the end of
the log.  Every script uses the **last collection of the newest log**
unless told otherwise.

## Files

| file | run directly | what it does |
|---|---|---|
| `phase_trace_common.py` | no | shared code the other three import: newest log, last collection, circular statistics, SNR, unwrapping, envelope fit, point thinning. It must stay in the same folder as the scripts. |
| `phase_trace_stats.py` | yes | prints a table per harmonic: the accumulator's phase after the last frame, the circular mean and std of the single frames, R, the single-frame SNR, and the difference accumulator minus mean. |
| `phase_trace_svg.py` | yes | writes `stat/phase_trace-<stamp>.svg`: the accumulator's phase of every harmonic over the frames, one colour per harmonic, with envelope curves and a legend. |
| `phase_trace_hx_svg.py` | yes | writes `stat/phase_trace_h<N>-<stamp>.svg`: two panels for one harmonic - the single-frame phase above, the accumulator's phase below. |

`stat/` is created next to the scripts; `<stamp>` is the collection's start
time from the log, `YYYY-MM-DD_HH-MM`, so a rerun on the same collection
overwrites its own pictures and different collections keep theirs.

## Usage

From the repository root (the scripts live in `doc/scripts/`):

```
python doc/scripts/phase_trace_stats.py  [log]
python doc/scripts/phase_trace_svg.py    [log] [out.svg]
python doc/scripts/phase_trace_hx_svg.py <harmonic 2..9> [out.svg] [log]
```

- `log`: a log file to read instead of the newest one; `-` stands for the
  newest when a later argument is given.
- `out.svg`: an output file instead of the default in `doc/scripts/stat/`;
  `-` keeps the default.
- `harmonic`: mandatory for the two-panel script, 2 to 9.

Examples:

```
python doc/scripts/phase_trace_svg.py
python doc/scripts/phase_trace_hx_svg.py 6
python doc/scripts/phase_trace_hx_svg.py 3 - "C:\Users\me\AppData\Roaming\Phonalyser\logs\phonalyser-2026-09-15.log"
python doc/scripts/phase_trace_stats.py "C:\Users\me\AppData\Roaming\Phonalyser\logs\phonalyser-2026-09-15.log"
```

Each script prints one line naming the log it read, the collection's frame
range and, for the figures, the file written.

## What the numbers mean

- **Single-frame circular mean and std**: the mean direction and the
  circular standard deviation (from the mean resultant length R:
  std = sqrt(-2 ln R), in degrees) of the frame-line values over all frames
  of the collection except the first.  Angles wrap, so ordinary averages
  would be wrong near +-180.
- **R**: the mean resultant length, 1 when every frame agrees, near 0 when
  the angles are uniformly spread.
- **Single-frame SNR**: the amplitude signal-to-noise ratio of one frame's
  harmonic bin, estimated from the phase spread: the phase spread of a
  phasor equals the noise quadrature over the amplitude, and with the noise
  magnitude sqrt(2) times its quadrature, SNR = 20 log10(1 / (sqrt(2) *
  std in radians)).  The formula is a small-angle one, so values near or
  below 0 dB only say that the harmonic sits at or below the single-frame
  noise floor; the accumulator still resolves it as the frames add up.
- **accumulator minus mean**: how far the accumulated relation sits from
  the circular mean of the frames, wrapped.  Over a long collection it
  should be within std / sqrt(frames); a systematic offset would point at a
  rotation between the frames and the accumulator.

## The figures

`phase_trace_svg.py` - the accumulator's phase to the fundamental after
each frame, one curve per harmonic in its own colour.  The curves are
unwrapped backwards from their settled value, so the settled tail sits
inside +-180 degrees and the early wobble may run beyond it; the vertical
axis grows to hold every curve.  Around each curve two envelope lines in
the same colour at 50 percent opacity, each fitted independently: a
decaying power law final +- A / (frame + 1)^p, fitted in the log-log domain
to the corner points of the running extremes seen from the end of the
collection, with the constant raised so the curve bounds every peak of its
side.  The horizontal axis is linear in frames and labels only the last
frame.  The legend, bottom right, gives each harmonic's final accumulator
phase and its single-frame SNR.

`phase_trace_hx_svg.py` - two panels for one harmonic, both traces black.
Upper: the single-frame phase, folded to +-180 degrees, with the circular
mean as a red line and no envelope.  Lower: the accumulator's phase,
unwrapped, with the two envelope curves and no mean line.  The legend,
bottom right, gives the final accumulator phase, the number of frames, the
single-frame SNR and the mean with its std.

The figures thin the accumulator curves for drawing (every point of the
first 300 frames, then every eighth); the statistics use every frame.
