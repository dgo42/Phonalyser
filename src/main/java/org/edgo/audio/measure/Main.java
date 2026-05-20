package org.edgo.audio.measure;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sound.sampled.spi.MixerProvider;

import org.edgo.audio.measure.adc.AdcHistogram;
import org.edgo.audio.measure.adc.RegressionCalibrator;
import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.chart.ChartExporter;
import org.edgo.audio.measure.chart.ChartStyle;
import org.edgo.audio.measure.chart.DnlInlExporter;
import org.edgo.audio.measure.chart.HistogramExporter;
import org.edgo.audio.measure.chart.WaveformExporter;
import org.edgo.audio.measure.common.StereoSampleFloat;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.wav.WavReader;
import org.edgo.audio.measure.wav.WavWriter;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.AxisState;
import org.jfree.chart.axis.LogAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTick;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.axis.TickType;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jtransforms.fft.DoubleFFT_1D;

import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.WindowType;

/**
 * Usage:
 *
 *   Common type conventions in flag arguments:
 *     <hz>     frequency in Hertz                       <vrms>  amplitude in V_rms
 *     <s>      seconds                                  <ms>    milliseconds
 *     <n>      integer count                            <i>     audio device index (from --list)
 *     <px>     pixels                                   <bits>  bit depth (8/16/24/32)
 *     <db>     relative dB                              <dbv>   dB referenced to 1 V_rms
 *     <dbfs>   dB full scale (0 dBFS = ADC peak)        <pct>   percentage
 *     <file>/<csv>/<wav>   path to a file               <dir>   path to a directory
 *
 *   Audio backend (applies to every mode):
 *     [--backend wasapi|wdmks|javasound]
 *                                  default: wasapi on Windows, javasound elsewhere.
 *                                  wasapi    — direct WASAPI COM API via JNA (no extra
 *                                              native DLLs), exclusive mode with shared
 *                                              fallback; Windows only.
 *                                  wdmks     — PortAudio WDM-KS host API; Windows only,
 *                                              needs portaudio_x64.dll on java.library.path.
 *                                  javasound — cross-platform javax.sound.sampled mixer;
 *                                              works on Windows / Linux / macOS.
 *
 *   ── List devices ─────────────────────────────────────────────────────────
 *     java -jar phonalyser.jar --list
 *         Prints every input and output mixer the active backend exposes,
 *         with index, name, vendor, and supported PCM formats.
 *
 *   ── Histogram capture ───────────────────────────────────────────────────
 *     java -jar phonalyser.jar --histogram --samplerate <hz> --duration <s>
 *         --window <n> --width <px> --height <px>
 *         [--bits 8|16|24] [--scale <V>] [--adc-fs-vrms <vrms>] [--device <i>]
 *         [--sine-reference [--sine-amplitude <r>]]
 *         --histogram                  toggles histogram-capture mode
 *         --samplerate <hz>            ADC sample rate (must be in the supported set)
 *         --duration <s>               capture length in seconds
 *         --window <n>                 sliding-window size (samples) for the waveform plot
 *         --width <px> --height <px>   output chart size in pixels
 *         [--bits 8|16|24]             ADC resolution (default 24)
 *         [--scale <V>]                full-scale ADC peak-to-peak voltage (e.g. 5.0 for
 *                                      ±2.5 V_p).  Default: derived from
 *                                      AudioBackend.adcFsVoltageRms as 2·√2·V_rms
 *         [--adc-fs-vrms <vrms>]       ADC full-scale RMS voltage; sets the global default
 *                                      for --scale and other dBV-aware modes
 *         [--device <i>]               input device index; auto-selected when only one exists
 *         [--sine-reference]           weight bins against the theoretical arcsine PDF of
 *                                      a pure sine instead of the local moving average;
 *                                      use when the input is a calibrated sine (any
 *                                      amplitude — clipping is handled).  Replaces the
 *                                      moving-average behaviour driven by --window.
 *         [--sine-amplitude <ratio>]   sine peak relative to ADC FS (1.0 = exactly FS,
 *                                      1.05 = 5 % over).  Optional; if omitted the
 *                                      amplitude is estimated by inverting the sine
 *                                      CDF at multiple interior quantile points (see
 *                                      --sine-fit-points), avoiding the rails where
 *                                      drift / non-stable signal poisons the histogram.
 *                                      Only honoured with --sine-reference.
 *         [--sine-fit-points <n>]      number of quantile points used to estimate A from
 *                                      the empirical CDF (default 30).  Sampled in
 *                                      F ∈ [0.10, 0.40] ∪ [0.60, 0.90] — skipping the
 *                                      rails (poisoned tails) and the median (singular).
 *                                      Median of {V_i / sin(π·(F_i−1/2))} is used.
 *         [--sine-edge-bins <n>]       number of bins on each side (rail) whose weight is
 *                                      replaced with the mean of the next two trustworthy
 *                                      inner bins (default 2).  The first and last cells
 *                                      absorb both clipped overflow and the noise-smeared
 *                                      arcsine PDF singularity, so direct estimation is
 *                                      unreliable there — extrapolating from the inner
 *                                      neighbours preserves the local DNL trend.  Only
 *                                      honoured with --sine-reference.
 *         [--histogram-chart]          additionally render the RAW histogram (counts vs
 *                                      voltage, before any DNL weighting) as a PNG —
 *                                      lets you sanity-check the actual code distribution
 *                                      and rail-bin spikes from clipping
 *     Outputs: histogram CSV + DNL/INL/waveform charts under results/.
 *
 *   ── Load histogram from CSV (offline replay of a captured histogram) ─────
 *     java -jar phonalyser.jar --histogram --load <file> --bits 8|16|24
 *         --window <n> --width <px> --height <px>
 *         [--scale <V>] [--adc-fs-vrms <vrms>]
 *         --histogram                  toggles histogram mode
 *         --load <file>                histogram CSV produced by histogram-capture mode
 *         --bits 8|16|24               must match the bit depth that produced the CSV
 *         --window <n>                 waveform-plot window size (samples)
 *         --width <px> --height <px>   chart size
 *         [--scale <V>]                full-scale ADC peak-to-peak voltage (default:
 *                                      2·√2·AudioBackend.adcFsVoltageRms)
 *         [--adc-fs-vrms <vrms>]       ADC full-scale RMS voltage
 *
 *   ── Analyze histogram (DNL + INL from a captured histogram CSV) ─────────
 *     java -jar phonalyser.jar --analyze-histogram <file> --bits 8|16|24
 *         --window <n> --scale <V> --width <px> --height <px>
 *         --analyze-histogram <file>   histogram CSV
 *         --bits 8|16|24               ADC resolution corresponding to the CSV
 *         --window <n>                 moving-average window used for the default reference
 *         --scale <V>                  full-scale voltage range
 *         --width <px> --height <px>   chart size
 *         [--sine-reference]           use the theoretical arcsine PDF as the bin reference
 *                                      instead of the moving average (input must be a sine)
 *         [--sine-amplitude <ratio>]   sine peak relative to FS (1.0 = exactly FS,
 *                                      1.05 = 5 % over); auto-estimated when omitted
 *         [--sine-fit-points <n>]      quantile points used for the auto amplitude
 *                                      estimate (default 30; sampled in
 *                                      F ∈ [0.10, 0.40] ∪ [0.60, 0.90])
 *         [--sine-edge-bins <n>]       edge bins per side replaced with extrapolated weight
 *                                      (default 2)
 *         [--histogram-chart]          additionally render the raw histogram (counts vs
 *                                      voltage) as a PNG before weighting
 *     Outputs: DNL chart, INL chart, weighted-code-map CSV under results/.
 *
 *   ── FFT analysis of a captured WAV (THD, THD+N, SNR, ENOB, harmonics) ───
 *     java -jar phonalyser.jar --fft-analyze <wav_file> --fft-size <n>
 *         --width <px> --height <px>
 *         --fft-analyze <wav_file>     WAV recorded at the rate to be analysed
 *         --fft-size <n>               FFT length, power of 2 (e.g. 65536 … 2097152)
 *         --width <px> --height <px>   chart size
 *         [--harmonics <n>]            harmonics (H2..) to measure (default 10)
 *         [--window-fn <type>]         RECTANGULAR | HANN | BH4 | BH7 | FLAT_TOP |
 *                                      DC150 | DC200 (default HANN)
 *         [--overlap <pct>]            0 | 50 | 75 | 87.5 | 92.75 (default 0)
 *         [--dist-min <hz>]            lower bound for SNR / noise integration (default 0)
 *         [--dist-max <hz>]            upper bound for SNR / noise integration (default Fs/2)
 *         [--fund-v <vrms>]            true RMS voltage of the fundamental — anchors the dBV scale
 *         [--fund-dbv <dbv>]           true level of the fundamental in dBV (alternative)
 *         [--adc-fs-vrms <vrms>]       ADC full-scale V_rms; used to derive dBV from dBFS
 *                                      when neither --fund-v nor --fund-dbv is given
 *                                      (defaults to AudioBackend.adcFsVoltageRms)
 *         [--sub-harmonics <csv>]      subtract H2+ sinusoids loaded from this fft_harmonics CSV
 *                                      from the time-domain signal before the FFT runs
 *         [--sub-harmonics-reim]       derive subtraction phase from re/im columns of that CSV
 *                                      (default: phase_deg column)
 *         [--no-coherent]              incoherent power averaging across frames
 *         [--freq <hz>]                generator frequency — enables ΔF/ppm clock-mismatch
 *                                      report and seeds the fundamental-bin search (±10 bin
 *                                      window) so a notched fundamental is not mistaken
 *                                      for a louder spur
 *         [--comment <str>]            free-form comment annotated onto the chart
 *         [--adc-comp <csv>]           adc_correction CSV (from --deembed) — logs raw vs.
 *                                      ADC-corrected harmonics + THD
 *         [--cal <csv>]                filter-calibration CSV (from --freq-response);
 *                                      subtracts magnitude_db_rel from each FFT bin so a
 *                                      fundamental notched by an external filter is lifted
 *                                      back to its source level
 *         [--cal-noise]                also apply cal correction to noise/non-signal bins
 *                                      (default: only fundamental + harmonic bins ±4)
 *         [--load-weighted <csv>]      weighted-code-map CSV from --analyze-histogram;
 *                                      linearises each raw ADC code through the INL map
 *                                      before the FFT (same mapping --record-mapped-wav
 *                                      applies during capture)
 *
 *   ── Record raw WAV ──────────────────────────────────────────────────────
 *     java -jar phonalyser.jar --record-wav --samplerate <hz> --duration <s>
 *         [--bits 8|16|24] [--device <i>] [--output <file>]
 *         [--waveform-duration <ms>] [--scale <V>] [--width <px>] [--height <px>]
 *         [--adc-comp <csv> --fft-size <n> [--harmonics <n>]]
 *         --record-wav                 toggles record mode
 *         --samplerate <hz>            ADC sample rate
 *         --duration <s>               capture length in seconds
 *         [--bits 8|16|24]             ADC resolution (default 24)
 *         [--device <i>]               input device index
 *         [--output <file>]            WAV output path (default results/recorded_<ts>.wav)
 *         [--waveform-duration <ms>]   waveform-plot length in ms
 *         [--scale <V>]                full-scale ADC voltage (for the waveform y-axis)
 *         [--width <px>] [--height <px>]   waveform chart size
 *         [--adc-comp <csv>]           ADC distortion CSV — when present also writes
 *                                      <basename>_adc_corrected.wav with ADC harmonics
 *                                      subtracted in the FFT domain (requires --fft-size)
 *         [--fft-size <n>]             FFT size for the ADC-correction pass (power of 2)
 *         [--harmonics <n>]            harmonics to subtract (default 10)
 *
 *   ── Record mapped WAV (apply a code-weight INL map during capture) ──────
 *     java -jar phonalyser.jar --record-mapped-wav <weights.csv> --samplerate <hz>
 *         --duration <s> [--bits 8|16|24] [--device <i>] [--output <file>]
 *         [--waveform-duration <ms>] [--scale <V>] [--width <px>] [--height <px>]
 *         --record-mapped-wav <csv>    weighted-code-map CSV from --analyze-histogram
 *         --samplerate, --duration, [--bits, --device, --output, --waveform-duration,
 *           --scale, --width, --height]   same semantics as --record-wav
 *
 *   ── Process an existing WAV through a code map ──────────────────────────
 *     java -jar phonalyser.jar --process-wav <file> --load-weighted <weights.csv>
 *         [--output <file>] [--waveform-duration <ms>] [--scale <V>]
 *         [--width <px>] [--height <px>]
 *         --process-wav <file>         input WAV
 *         --load-weighted <csv>        code-weight map (from --analyze-histogram)
 *         [--output <file>]            output WAV path (default <input>_mapped.wav)
 *         [--waveform-duration <ms>]   waveform-plot length
 *         [--scale <V>]                full-scale voltage for the waveform y-axis
 *         [--width <px>] [--height <px>]   chart size
 *
 *   ── Regression calibration (sine-wave least-squares INL fit) ────────────
 *     java -jar phonalyser.jar --regress-calibrate <wav_file> --scale <V>
 *         [--width <px>] [--height <px>]
 *         --regress-calibrate <wav>    WAV containing a clean sine
 *         --scale <V>                  full-scale voltage range (e.g. 2.0 for ±1 V_p)
 *         [--width <px>] [--height <px>]   chart size
 *     Outputs: regression_calibration_<ts>.csv + regression_chart_<ts>.png
 *
 *   ── Deembed DAC ↔ ADC distortion (least-squares fit) ────────────────────
 *     java -jar phonalyser.jar --deembed --comp <csv> [--comp <csv> …]
 *         --gen-out <dac_comp.csv> --adc-out <adc_corr.csv>
 *         [--level <dbfs> …] [--orders <list>]
 *         --comp <csv>                 applied_compensation CSV from --iterative-compensate;
 *                                      pass once per ADC level used (need ≥ 1 + |orders|)
 *         --gen-out <csv>              output: DAC-side compensation (level-independent c_0)
 *         --adc-out <csv>              output: ADC-side correction (level-dependent c_k)
 *         [--level <dbfs> …]           override the H1 dBFS read from each --comp header,
 *                                      one value per --comp in the same order
 *         [--orders <list>]            ADC-order list (default "h-1").  Tokens:
 *                                        integer literal "1","3"        → fixed order
 *                                        "h"                            → same as harmonic
 *                                        "h±k"                          → harmonic ± offset
 *                                      Determines which level-power terms are fit.
 *         (legacy aliases --comp1/--comp2, --level1/--level2 still accepted)
 *     Fits r_h(L) = c_0 + Σ_k c_k · L^p_k(h):  c_0 → --gen-out, c_1..c_K → --adc-out.
 *
 *   ── Generate signal (DAC playback only, no capture) ─────────────────────
 *     java -jar phonalyser.jar --generate --samplerate <hz> --signal <form>
 *         --duration <s> [--bits 8|16|24|32] [--freq <hz>] [--amplitude <vrms>]
 *         [--dither <bits>] [--device <i>] [--harmonics-csv <file>]
 *         --generate                   toggles generate mode
 *         --samplerate <hz>            DAC sample rate
 *         --signal <form>              sine | triangle | rectangle | white_noise |
 *                                      pink_noise | pink_noise_linear | sine_compensated
 *         --duration <s>               playback length in seconds
 *         [--bits 8|16|24|32]          DAC resolution (default 32)
 *         [--freq <hz>]                fundamental frequency (default 1000)
 *         [--amplitude <vrms>]         output amplitude in V_rms (default 1.0)
 *         [--dither <bits>]            TPDF dither at this bit depth (default 0 = off)
 *         [--device <i>]               output device index
 *         [--harmonics-csv <file>]     fft_harmonics CSV — required for
 *                                      --signal sine_compensated; injects H2..Hn at
 *                                      inverted phase to pre-cancel DAC distortion
 *
 *   ── Gen + FFT (single shot: generate → capture → analyze; no WAV saved) ─
 *     java -jar phonalyser.jar --gen-fft --samplerate <hz> --freq <hz>
 *         --amplitude <vrms> --fft-size <n> --width <px> --height <px>
 *         --out-device <i> --in-device <i> [other-options …]
 *         --gen-fft                    toggles single-shot gen+fft mode
 *         --samplerate <hz>            DAC and ADC sample rate (must match)
 *         --freq <hz>                  generator fundamental — snapped to the nearest
 *                                      exact FFT bin so coherent averaging works
 *         --amplitude <vrms>           DAC output amplitude in V_rms
 *         --fft-size <n>               FFT size, power of 2
 *         --width <px> --height <px>   chart size
 *         --out-device <i>             output device index (DAC)
 *         --in-device <i>              input device index  (ADC)
 *         [--bits 8|16|24|32]          (default 32)
 *         [--signal <form>]            sine | triangle | rectangle | white_noise |
 *                                      pink_noise | pink_noise_linear | sine_compensated
 *                                      (default sine)
 *         [--harmonics-csv <file>]     fft_harmonics CSV (required for sine_compensated)
 *         [--duration <s>]             capture length (default: just enough for one FFT frame).
 *                                      Applies only to data collection; the generator runs
 *                                      throughout, including any --sync-pause window before it.
 *         [--sync-pause <s>]           seconds (0..60) to hold the recorder after the generator
 *                                      starts driving, giving an externally phase-locked analog
 *                                      source (e.g. a Wien-bridge slaved off the DAC sine) time
 *                                      to settle before useful data is collected (default 0)
 *         [--dither <bits>]            TPDF dither bit depth on generator (default 0)
 *         [--harmonics <n>]            harmonics to measure (default 10)
 *         [--window-fn <type>]         FFT window (default HANN)
 *         [--overlap <pct>]            frame overlap (default 0)
 *         [--dist-min <hz>] [--dist-max <hz>]   SNR/noise integration band
 *         [--fund-v <vrms>] [--fund-dbv <dbv>]  dBV anchor (alternative to --adc-fs-vrms)
 *         [--adc-fs-vrms <vrms>]       ADC full-scale V_rms (sets dBV scale when --fund-v
 *                                      / --fund-dbv absent)
 *         [--no-coherent]              incoherent power averaging
 *         [--cal <csv>]                filter calibration (see --freq-response)
 *         [--cal-noise]                apply cal to noise bins too (default: signal bins only)
 *         [--load-weighted <csv>]      weighted-code-map CSV from --analyze-histogram;
 *                                      linearises each captured ADC code through the INL
 *                                      map before the FFT
 *         [--comment <str>]            free-form chart comment
 *         [--output <dir>]             output directory (default results)
 *
 *   ── Iterative harmonic compensation (DAC pre-distortion loop) ───────────
 *     java -jar phonalyser.jar --iterative-compensate --samplerate <hz>
 *         --freq <hz> --amplitude <vrms> --fft-size <n>
 *         --width <px> --height <px> --out-device <i> --in-device <i>
 *         [other-options …]
 *         --iterative-compensate       toggles iterative-compensate mode
 *         --samplerate, --freq, --amplitude, --fft-size, --width, --height,
 *           --out-device, --in-device   same semantics as --gen-fft
 *         [--bits 8|16|24|32]          (default 32)
 *         [--dither <bits>]            generator TPDF dither (default 0)
 *         [--duration <s>]             seconds per iteration (default 60)
 *         [--harmonics <n>]            harmonics measured + compensated (default 10)
 *         [--window-fn <type>]         FFT window (default HANN)
 *         [--overlap <pct>]            frame overlap (default 0)
 *         [--dist-min <hz>]            lower SNR/noise integration bound
 *         [--dist-max <hz>]            upper SNR/noise integration bound
 *         [--fund-v <vrms>]            true fundamental V_rms (overrides cal/--amplitude
 *                                      for dBV scaling)
 *         [--fund-dbv <dbv>]           true fundamental level in dBV
 *         [--adc-fs-vrms <vrms>]       ADC full-scale V_rms (sets dBV scaling fallback
 *                                      when --fund-v / --fund-dbv absent)
 *         [--no-coherent]              incoherent averaging (disables phase compensation)
 *         [--accumulate]               aggregate harmonic corrections across iterations
 *                                      (default: each iteration uses only the latest residual)
 *         [--stop-after <n>]           stop after N consecutive THD-pct increases (default 4)
 *         [--target-thd <pct>]         stop once THD drops to or below this percentage
 *         [--compensation-snr-margin <db>]   skip harmonics within this many dB of the noise
 *                                      floor (default 10) — prevents the accumulator from
 *                                      random-walking once distortion is buried
 *         [--compensation-step <μ>]    LMS step size, 0 < μ ≤ 1 (default 1.0).  Use
 *                                      e.g. 0.5 to damp oscillations when phase
 *                                      estimation is imperfect (typical with --cal)
 *         [--cal <csv>]                filter calibration (see --freq-response)
 *         [--cal-noise]                apply cal to noise bins too (default: signal bins only)
 *     Each iteration writes fft_chart_iter<i>_<ts>.png; on stop the final harmonics
 *     are written to fft_harmonics_<ts>.csv (input for --signal sine_compensated
 *     and --deembed).
 *
 *   ── Frequency response measurement (Farina log-sweep → per-point H(f)) ──
 *     java -jar phonalyser.jar --freq-response --samplerate <hz>
 *         --amplitude <vrms> --out-device <i> --in-device <i>
 *         [--bits 8|16|24|32] [--sweep-start <hz>] [--sweep-end <hz>]
 *         [--sweep-duration <s>] [--sweep-points <n>] [--lead-in <s>]
 *         [--adc-fs-vrms <vrms>] [--dither <bits>] [--output <file>]
 *         [--sweep-wav <file>] [--width <px>] [--height <px>]
 *         --freq-response              toggles freq-response mode
 *         --samplerate <hz>            DAC and ADC sample rate
 *         --amplitude <vrms>           DAC drive amplitude — recorded in the cal CSV
 *                                      header so the apply path knows the reference
 *         --out-device <i>             output device index (DAC)
 *         --in-device <i>              input device index  (ADC)
 *         [--bits 8|16|24|32]          (default 24)
 *         [--sweep-start <hz>]         sweep start frequency (default 2)
 *         [--sweep-end <hz>]           sweep end frequency (default Fs/2)
 *         [--sweep-duration <s>]       Farina sweep length in seconds (default 5.5, min 0.5)
 *         [--sweep-points <n>]         log-spaced rows in the output CSV/chart (default 200)
 *         [--lead-in <s>]              silence before the sweep starts (default 0.2, min 0.05)
 *         [--adc-fs-vrms <vrms>]       ADC full-scale V_rms; written to the cal CSV
 *                                      header for downstream dBV scaling (default 1.7931)
 *         [--dither <bits>]            generator TPDF dither (default 0 = off)
 *         [--output <file>]            cal CSV output path (default results/filter_cal_<ts>.csv)
 *         [--sweep-wav <file>]         also dump the raw capture as a WAV
 *         [--width <px>] [--height <px>]   chart size (default 1920x600)
 *     The generator emits the lead-in silence then a single Farina exponential sweep
 *     x(t) = sin(K(eᵗ/ᴸ − 1)).  H(f) is extracted by FFT-domain deconvolution
 *     Y(f)·conj(X(f)) / |X(f)|² (one FFT pair, size = next pow2 ≥ recording length).
 *     The DAC↔ADC transport delay is removed via sub-sample parabolic peak fitting
 *     of the impulse response.  The per-point response is normalised to the DAC
 *     drive level so passband ≈ 0 dB; magnitude_db_rel is what the apply path
 *     subtracts from each FFT bin (corrected_dBFS = raw_dBFS − magnitude_db_rel).
 *     Outputs: filter_cal_<ts>.csv (+ matching .png chart).
 */
@Log4j2
public class Main {

    private static final int[] VALID_SAMPLE_RATES =
            {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000};

    /**
     * Loaded ADC-correction coefficient table.  Each harmonic h ≥ 2 carries K
     * complex coefficients (one per order in {@link #orders}); the ADC's
     * predicted contribution to harmonic h's bin (in the rotated frame where
     * fund → −j) is {@code Σ_k (a_k_re + j·a_k_im) · L^{p_k(h)}}.
     */
    @Value
    private static class AdcCorrection {
        List<OrderToken>                 orders;
        Map<Integer, double[]> coeffs;   // h → 2·|orders| doubles (re, im pairs)
    }

    /**
     * One harmonic row parsed from an applied_compensation CSV.
     * {@code re}/{@code im} are in the rotated frame where fundamental → (0, −1),
     * so they are already the harmonic complex value relative to the fundamental.
     */
    @Value
    private static class CompHarmonic {
        int    h;
        double freqHz;
        double re;
        double im;
    }

    /** Parsed CSV: header metadata + h=1 dBFS/freq + list of harmonic rows (h ≥ 2). */
    @Value
    private static class CompCsv {
        SignalGenerator.Metadata meta;
        double                   fundamentalDbFs;
        double                   fundamentalHz;
        List<CompHarmonic>       harmonics;
    }

    /**
     * In-memory representation of a filter calibration: a sparse set of measured
     * magnitude/phase points at log-spaced frequencies, produced by the stepped-sine
     * sweep in {@link #runFilterSweepMode}.
     *
     * <p>All three arrays are the same length N (the number of sweep points) and
     * {@code freqs} is strictly ascending.  {@code magLin[i]} is the **relative**
     * filter magnitude |H(freqs[i])| — i.e. the captured peak amplitude divided
     * by the DAC drive peak fraction, so the pass-band sits at unity (≈ 1.0)
     * and a notch reads e.g. 1e-4 (−80 dB).  {@code phaseRad[i]} is the filter
     * phase in radians, with capture-delay linear-phase ramp removed in
     * {@link #computeFilterCalibrationFromLogSweep}.
     *
     * <p>Callers wanting H at an arbitrary frequency interpolate in log-frequency
     * (dB magnitude, linear phase) between the surrounding two sweep points.
     */
    private static class FilterCalibration {
        final double[] freqs;       // length N, strictly ascending Hz
        final double[] magLin;      // |H| linear, passband normalised to 1.0
        final double[] phaseRad;    // phase in radians, unwrapped
        /** ADC full-scale RMS voltage at the time of measurement, from the CSV header
         *  ({@code adc_fs_voltage_rms}); {@link Double#NaN} when absent. */
        double         adcFsVoltageRms = Double.NaN;
        FilterCalibration(double[] freqs, double[] magLin, double[] phaseRad) {
            this.freqs      = freqs;
            this.magLin     = magLin;
            this.phaseRad   = phaseRad;
        }
    }

    /**
     * Per-iteration snapshot used by iterative-compensate so the user can
     * pick which iteration's compensation to save when the loop is stopped
     * manually. For accumulate mode, {@code appliedRe}/{@code appliedIm}
     * hold the compensation in effect AT THE START of {@code iter}
     * (zero arrays for iter 0).
     */
    @Value
    static class IterSnapshot {
        int iter;
        FftAnalyzer.Result result;
        double[] appliedRe;
        double[] appliedIm;
        double[] hFreqs;
    }

    /** Order descriptor: literal integer power or {@code h+offset}/{@code h-offset}. */
    @Value
    private static class OrderToken {
        boolean hRelative;   // true → h + offset
        int     value;       // literal power (hRelative=false) OR offset added to h
        String  label;       // original textual form
        int resolve(int h)   { return hRelative ? h + value : value; }
    }

    public static void main(String[] args) throws Exception {
        new Main().run(args);
    }

    public void run(String[] args) throws Exception {
        if (hasArg(args, "--help") || args.length == 0) {
            printUsage();
            return;
        }
        // Resolve audio backend before any device lookup.  WASAPI is the default.
        AudioBackend.instance().setActive(AudioBackendType.fromString(getArgValue(args, "--backend")));
        if (hasArg(args, "--iterative-compensate")) {
            runIterativeCompensate(args);
            return;
        }
        if (hasArg(args, "--gen-fft")) {
            runGenFftMode(args);
            return;
        }
        if (hasArg(args, "--freq-response")) {
            runFilterSweepMode(args);
            return;
        }
        if (getArgValue(args, "--analyze-histogram") != null) {
            runAnalyzeHistogramMode(args);
            return;
        }
        if (getArgValue(args, "--fft-analyze") != null) {
            runFftAnalyzeMode(args);
            return;
        }
        if (hasArg(args, "--deembed")) {
            runDeembedMode(args);
            return;
        }
        if (getArgValue(args, "--regress-calibrate") != null) {
            runRegressCalibrateMode(args);
            return;
        }
        if (getArgValue(args, "--process-wav") != null) {
            runProcessWavMode(args);
            return;
        }
        logProviders();
        if (hasArg(args, "--list")) {
            listDevices();
            return;
        }
        if (hasArg(args, "--generate")) {
            runGenerate(args);
            return;
        }
        if (hasArg(args, "--record-wav") || getArgValue(args, "--record-mapped-wav") != null) {
            runRecordWavMode(args);
            return;
        }
        if (hasArg(args, "--histogram")) {
            runHistogramMode(args);
            return;
        }
        log.error("No mode flag given. Use --help for the list of modes.");
        System.exit(1);
    }

    private void runProcessWavMode(String[] args) throws Exception {
        String processWavArg       = getArgValue(args, "--process-wav");
        String loadWeightedArg     = getArgValue(args, "--load-weighted");
        String outputArg           = getArgValue(args, "--output");
        String waveformDurationArg = getArgValue(args, "--waveform-duration");
        String scaleArg            = getArgValue(args, "--scale");
        String widthArg            = getArgValue(args, "--width");
        String heightArg           = getArgValue(args, "--height");

        if (loadWeightedArg == null) {
            log.error("--load-weighted <csv> is required with --process-wav.");
            System.exit(1);
        }

        double waveformDurationMs = 0;
        if (waveformDurationArg != null) {
            waveformDurationMs = Double.parseDouble(waveformDurationArg);
            if (waveformDurationMs <= 0 || waveformDurationMs > 10.0) {
                log.error("--waveform-duration must be > 0 and <= 10 ms.");
                System.exit(1);
            }
        }
        float scaleVolts  = (scaleArg  != null) ? Float.parseFloat(scaleArg)  : 1.0f;
        int   chartWidth  = (widthArg  != null) ? Integer.parseInt(widthArg)  : 1920;
        int   chartHeight = (heightArg != null) ? Integer.parseInt(heightArg) : 400;

        WavReader reader = new WavReader(processWavArg);
        log.info("Mode      : process WAV → mapped WAV");
        log.info("Input     : {}", processWavArg);
        log.info("Weights   : {}", loadWeightedArg);
        if (waveformDurationMs > 0) {
            log.info("Waveform  : {} ms", waveformDurationMs);
        }

        WeightedBuffer weights = new WeightedBuffer(reader.getBitsPerSample());
        weights.loadCsv(loadWeightedArg);
        weights.buildCodeMap();
        runProcessWav(reader, scaleVolts, weights, outputArg, waveformDurationMs, chartWidth, chartHeight);
    }

    private void runRecordWavMode(String[] args) throws Exception {
        String mappedWeightedArg   = getArgValue(args, "--record-mapped-wav");
        String durationArg         = getArgValue(args, "--duration");
        String samplerateArg       = getArgValue(args, "--samplerate");
        String bitsArg             = getArgValue(args, "--bits");
        String outputArg           = getArgValue(args, "--output");
        String waveformDurationArg = getArgValue(args, "--waveform-duration");
        String scaleArg            = getArgValue(args, "--scale");
        String widthArg            = getArgValue(args, "--width");
        String heightArg           = getArgValue(args, "--height");
        String adcCompArg          = getArgValue(args, "--adc-comp");
        String fftSizeArg          = getArgValue(args, "--fft-size");
        String harmonicsArg        = getArgValue(args, "--harmonics");

        if (durationArg == null) {
            log.error("--duration is required for WAV recording.");
            System.exit(1);
        }
        if (samplerateArg == null) {
            log.error("--samplerate is required for WAV recording.");
            System.exit(1);
        }

        int durationSeconds = Integer.parseInt(durationArg);
        int sampleRate      = Integer.parseInt(samplerateArg);
        if (!isValidSampleRate(sampleRate)) {
            log.error("--samplerate must be one of: 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000");
            System.exit(1);
        }
        int bitDepth = (bitsArg != null) ? Integer.parseInt(bitsArg) : 24;
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32.");
            System.exit(1);
        }
        double waveformDurationMs = 0;
        if (waveformDurationArg != null) {
            waveformDurationMs = Double.parseDouble(waveformDurationArg);
            if (waveformDurationMs <= 0 || waveformDurationMs > 10.0) {
                log.error("--waveform-duration must be > 0 and <= 10 ms.");
                System.exit(1);
            }
        }
        float scaleVolts  = (scaleArg  != null) ? Float.parseFloat(scaleArg)  : 1.0f;
        int   chartWidth  = (widthArg  != null) ? Integer.parseInt(widthArg)  : 1920;
        int   chartHeight = (heightArg != null) ? Integer.parseInt(heightArg) : 400;

        DeviceRef mixer = selectMixer(args);
        if (mixer == null) {
            log.error("No suitable audio input device found.");
            System.exit(1);
        }

        if (mappedWeightedArg != null) {
            log.info("Mode       : record + weighted map → WAV");
            log.info("Weights    : {}", mappedWeightedArg);
            log.info("Device     : {}", mixer.name());
            log.info("SampleRate : {} Hz", sampleRate);
            log.info("Bits       : {}", bitDepth);
            log.info("Duration   : {} second(s)", durationSeconds);
            if (waveformDurationMs > 0) {
                log.info("Waveform   : {} ms", waveformDurationMs);
            }
            WeightedBuffer weights = new WeightedBuffer(bitDepth);
            weights.loadCsv(mappedWeightedArg);
            weights.buildCodeMap();
            runRecordMappedWav(mixer, sampleRate, durationSeconds, bitDepth, scaleVolts, weights, outputArg,
                    waveformDurationMs, chartWidth, chartHeight);
        } else {
            log.info("Mode       : record raw → WAV");
            log.info("Device     : {}", mixer.name());
            log.info("SampleRate : {} Hz", sampleRate);
            log.info("Bits       : {}", bitDepth);
            log.info("Duration   : {} second(s)", durationSeconds);
            if (waveformDurationMs > 0) {
                log.info("Waveform   : {} ms", waveformDurationMs);
            }
            int    fftSize     = fftSizeArg   != null ? Integer.parseInt(fftSizeArg)   : 0;
            int    harmonics   = harmonicsArg != null ? Integer.parseInt(harmonicsArg) : 10;
            if (adcCompArg != null && fftSize == 0) {
                log.error("--adc-comp requires --fft-size to derive fundamental level/phase from the recording.");
                System.exit(1);
            }
            if (adcCompArg != null && Integer.bitCount(fftSize) != 1) {
                log.error("--fft-size must be a power of 2 (e.g. 65536, 131072).");
                System.exit(1);
            }
            runRecordWav(mixer, sampleRate, durationSeconds, bitDepth, outputArg,
                    waveformDurationMs, scaleVolts, chartWidth, chartHeight,
                    adcCompArg, fftSize, harmonics);
        }
    }

    private void runHistogramMode(String[] args) throws Exception {
        String loadArg       = getArgValue(args, "--load");
        String durationArg   = getArgValue(args, "--duration");
        String samplerateArg = getArgValue(args, "--samplerate");
        String windowArg     = getArgValue(args, "--window");
        String scaleArg      = getArgValue(args, "--scale");
        String widthArg      = getArgValue(args, "--width");
        String heightArg     = getArgValue(args, "--height");
        String bitsArg       = getArgValue(args, "--bits");
        String adcFsArg      = getArgValue(args, "--adc-fs-vrms");
        if (adcFsArg != null) {
            AudioBackend.adcFsVoltageRms = Double.parseDouble(adcFsArg);
        }

        boolean loadMode   = (loadArg != null);
        boolean recordMode = !loadMode;

        if (recordMode && durationArg == null) {
            log.error("Usage (record) : --histogram --duration <s> --samplerate <hz> --window <n> --width <px> --height <px> [--bits 8|16|24] [--scale <V>] [--adc-fs-vrms <vrms>] [--device <i>]");
            log.error("Usage (replay) : --histogram --load <file> --bits 8|16|24 --window <n> --width <px> --height <px> [--scale <V>] [--adc-fs-vrms <vrms>]");
            System.exit(1);
        }
        if (recordMode && samplerateArg == null) {
            log.error("--samplerate is required for recording mode.");
            System.exit(1);
        }
        if (windowArg == null || widthArg == null || heightArg == null) {
            log.error("--window, --width and --height are required.");
            System.exit(1);
        }

        int durationSeconds = recordMode ? Integer.parseInt(durationArg) : 0;
        if (recordMode && durationSeconds <= 0) {
            log.error("--duration must be a positive integer.");
            System.exit(1);
        }
        int sampleRate = recordMode ? Integer.parseInt(samplerateArg) : 0;
        if (recordMode && !isValidSampleRate(sampleRate)) {
            log.error("--samplerate must be one of: 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000");
            System.exit(1);
        }
        int windowLength = Integer.parseInt(windowArg);
        if (windowLength < 0) {
            log.error("--window must be a positive integer.");
            System.exit(1);
        }
        // --scale is the ADC's full-scale peak-to-peak voltage (e.g. 5.0 for ±2.5 V_p).
        // Default: derive from AudioBackend.adcFsVoltageRms — for an FS-RMS rating
        // of 1.7931 V the peak-to-peak range is 2·√2·V_rms ≈ 5.072 V.
        double scaleVolts = scaleArg != null
                ? Double.parseDouble(scaleArg)
                : 2.0 * Math.sqrt(2.0) * AudioBackend.adcFsVoltageRms;
        if (scaleVolts <= 0) {
            log.error("--scale must be a positive number.");
            System.exit(1);
        }
        int chartWidth = Integer.parseInt(widthArg);
        if (chartWidth <= 0) {
            log.error("--width must be a positive integer.");
            System.exit(1);
        }
        int chartHeight = Integer.parseInt(heightArg);
        if (chartHeight <= 0) {
            log.error("--height must be a positive integer.");
            System.exit(1);
        }
        int bitDepth = (bitsArg != null) ? Integer.parseInt(bitsArg) : 24;
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32.");
            System.exit(1);
        }

        boolean sineRef = hasArg(args, "--sine-reference");
        String  sineAmpArg = getArgValue(args, "--sine-amplitude");
        double  sineAmpFsRatio = sineAmpArg != null ? Double.parseDouble(sineAmpArg) : Double.NaN;
        String  sineEdgeArg = getArgValue(args, "--sine-edge-bins");
        int     sineEdgeBins = sineEdgeArg != null ? Integer.parseInt(sineEdgeArg) : 2;
        String  sineFitArg = getArgValue(args, "--sine-fit-points");
        int     sineFitPoints = sineFitArg != null ? Integer.parseInt(sineFitArg) : 30;
        boolean rawHistChart = hasArg(args, "--histogram-chart");

        if (loadMode) {
            log.info("Mode    : load histogram from CSV");
            log.info("File    : {}", loadArg);
            log.info("Bits    : {}", bitDepth);
            log.info("Window  : {} codes", windowLength);
            log.info("Scale   : {} V", scaleVolts);
            log.info("Chart   : {}x{} px", chartWidth, chartHeight);
            if (sineRef) {
                log.info("Weight  : sine-PDF reference{}, edge mask {} bin(s)/side",
                        sineAmpArg != null ? " (A=" + sineAmpArg + " FS)" : " (auto-estimate A)",
                        sineEdgeBins);
            }
            AdcHistogram histogram = new AdcHistogram(bitDepth);
            histogram.loadCsv(loadArg);
            processHistogram(histogram, windowLength, scaleVolts, chartWidth, chartHeight,
                    sineRef, sineAmpFsRatio, sineEdgeBins, sineFitPoints, rawHistChart);
            return;
        }

        DeviceRef selectedMixer = selectMixer(args);
        if (selectedMixer == null) {
            log.error("No suitable audio input device found.");
            System.exit(1);
        }

        log.info("Mode      : record");
        log.info("Device    : {}", selectedMixer.name());
        log.info("SampleRate: {} Hz", sampleRate);
        log.info("Bits      : {}", bitDepth);
        log.info("Duration  : {} second(s)", durationSeconds);
        log.info("Window    : {} codes", windowLength);
        log.info("Scale     : {} V", scaleVolts);
        log.info("Chart     : {}x{} px", chartWidth, chartHeight);

        runSession(selectedMixer, sampleRate, durationSeconds, windowLength, scaleVolts,
                chartWidth, chartHeight, bitDepth,
                sineRef, sineAmpFsRatio, sineAmpArg, sineEdgeBins, sineFitPoints, rawHistChart);
    }

    private void printUsage() {
        log.info("Usage:");
        log.info("");
        log.info("  Type conventions in flag arguments:");
        log.info("    <hz>=Hz  <vrms>=V_rms  <s>=seconds  <ms>=milliseconds  <n>=count");
        log.info("    <i>=audio device index  <px>=pixels  <bits>=8/16/24/32");
        log.info("    <db>=relative dB  <dbv>=dB referenced to 1 V_rms  <dbfs>=dB full-scale");
        log.info("    <pct>=percent  <file>/<csv>/<wav>=file path  <dir>=directory");
        log.info("");
        log.info("  Audio backend (applies to every mode):");
        log.info("    [--backend wasapi|wdmks|javasound]  default: wasapi on Windows, javasound elsewhere.");
        log.info("                              wasapi:    direct WASAPI COM access via JNA (exclusive mode");
        log.info("                                         with shared fallback); Windows only.");
        log.info("                              wdmks:     PortAudio WDM-KS host API; Windows only,");
        log.info("                                         needs portaudio_x64.dll on java.library.path.");
        log.info("                              javasound: cross-platform javax.sound.sampled mixer; works");
        log.info("                                         on Windows / Linux / macOS.");
        log.info("");
        log.info("  List devices:");
        log.info("    --list                    print all input/output mixers + supported PCM formats");
        log.info("");
        log.info("  Histogram capture:");
        log.info("    --histogram               toggles histogram-capture mode");
        log.info("    --samplerate <hz>         ADC sample rate (must be in the supported set)");
        log.info("    --duration <s>            capture length in seconds");
        log.info("    --window <n>              sliding-window size (samples) for the waveform plot");
        log.info("    --width <px> --height <px>  chart size in pixels");
        log.info("    [--bits 8|16|24]          ADC resolution (default 24)");
        log.info("    [--scale <V>]             full-scale ADC peak-to-peak voltage (e.g. 5.0 for");
        log.info("                              +/-2.5 V_p).  Default: 2*sqrt(2)*adcFsVoltageRms");
        log.info("    [--adc-fs-vrms <vrms>]    ADC full-scale RMS voltage (sets the --scale default)");
        log.info("    [--device <i>]            input device index (auto-selected if only one)");
        log.info("    [--sine-reference]        weight bins against theoretical arcsine PDF (sine input)");
        log.info("                              instead of the local moving average");
        log.info("    [--sine-amplitude <r>]    sine peak relative to FS (1.0=FS, 1.05=5 % over);");
        log.info("                              auto-estimated by inverting the sine CDF at");
        log.info("                              multiple interior quantiles when absent");
        log.info("    [--sine-fit-points <n>]   quantile points used for auto amplitude estimate");
        log.info("                              (default 30; F in [0.10, 0.40] U [0.60, 0.90] —");
        log.info("                              skips the poisoned rails and the median singularity)");
        log.info("    [--sine-edge-bins <n>]    edge bins per side replaced with extrapolated weight");
        log.info("                              (default 2) — fixes the noise-smeared arcsine");
        log.info("                              singularity + clipping overflow at the rails");
        log.info("    [--histogram-chart]       additionally render the raw histogram (counts vs");
        log.info("                              voltage, before DNL weighting) as a PNG");
        log.info("    Outputs: histogram CSV + DNL/INL/waveform charts under results/");
        log.info("");
        log.info("  Load histogram from CSV (offline replay):");
        log.info("    --histogram               toggles histogram mode");
        log.info("    --load <file>             histogram CSV from histogram capture");
        log.info("    --bits 8|16|24            must match the bit depth that produced the CSV");
        log.info("    --window <n>              waveform-plot window size");
        log.info("    --width <px> --height <px>");
        log.info("    [--scale <V>]             full-scale ADC peak-to-peak voltage (default:");
        log.info("                              2*sqrt(2)*adcFsVoltageRms)");
        log.info("    [--adc-fs-vrms <vrms>]    ADC full-scale RMS voltage");
        log.info("");
        log.info("  Analyze histogram (DNL + INL):");
        log.info("    --analyze-histogram <file>   histogram CSV");
        log.info("    --bits 8|16|24            ADC resolution");
        log.info("    --window <n>              moving-average window for the default reference");
        log.info("    --scale <V>               full-scale voltage range");
        log.info("    --width <px> --height <px>");
        log.info("    [--sine-reference]        weight bins against theoretical arcsine PDF (sine input)");
        log.info("                              instead of the moving average");
        log.info("    [--sine-amplitude <r>]    sine peak relative to FS (1.0=FS, 1.05=5 % over);");
        log.info("                              auto-estimated when absent");
        log.info("    [--sine-fit-points <n>]   quantile points used for auto amplitude estimate");
        log.info("                              (default 30)");
        log.info("    [--sine-edge-bins <n>]    edge bins per side replaced with extrapolated weight");
        log.info("                              (default 2)");
        log.info("    [--histogram-chart]       additionally render the raw histogram (counts vs");
        log.info("                              voltage) as a PNG before weighting");
        log.info("    Outputs: DNL chart, INL chart, weighted-code-map CSV");
        log.info("");
        log.info("  FFT analysis of a captured WAV (THD, THD+N A-weighted, SNR, ENOB, harmonics):");
        log.info("    --fft-analyze <wav_file>  WAV recorded at the rate to be analysed");
        log.info("    --fft-size <n>            FFT length, power of 2 (e.g. 65536 .. 2097152)");
        log.info("    --width <px> --height <px>  chart size");
        log.info("    [--harmonics <n>]         harmonics (H2..) to measure (default 10)");
        log.info("    [--window-fn <type>]      RECTANGULAR | HANN | BH4 | BH7 | FLAT_TOP |");
        log.info("                              DC150 | DC200 (default HANN)");
        log.info("    [--overlap <pct>]         0 | 50 | 75 | 87.5 | 92.75 (default 0)");
        log.info("    [--dist-min <hz>]         lower bound for SNR/noise integration (default 0)");
        log.info("    [--dist-max <hz>]         upper bound for SNR/noise integration (default Fs/2)");
        log.info("    [--fund-v <vrms>]         true RMS voltage of the fundamental — anchors dBV scale");
        log.info("    [--fund-dbv <dbv>]        true fundamental level in dBV (alternative to --fund-v)");
        log.info("    [--adc-fs-vrms <vrms>]    ADC full-scale V_rms (sets dBV scale when --fund-v/-dbv");
        log.info("                              absent; defaults to AudioBackend.adcFsVoltageRms)");
        log.info("    [--sub-harmonics <csv>]   subtract H2+ sinusoids loaded from this CSV before FFT");
        log.info("    [--sub-harmonics-reim]    use re/im columns of that CSV (default: phase_deg)");
        log.info("    [--no-coherent]           incoherent power averaging across frames");
        log.info("    [--freq <hz>]             generator freq — enables clock-mismatch report and");
        log.info("                              seeds fund-bin search (+/-10 bin window)");
        log.info("    [--comment <str>]         free-form chart comment");
        log.info("    [--adc-comp <csv>]        adc_correction CSV (from --deembed); logs raw vs.");
        log.info("                              ADC-corrected harmonics + THD");
        log.info("    [--cal <csv>]             filter-cal CSV (from --freq-response); subtracts");
        log.info("                              magnitude_db_rel from each FFT bin");
        log.info("    [--cal-noise]             also apply cal correction to noise/non-signal bins");
        log.info("                              (default: only fundamental + harmonic bins +/-4)");
        log.info("    [--load-weighted <csv>]   weighted-code-map CSV from --analyze-histogram;");
        log.info("                              linearises each raw ADC code through the INL map");
        log.info("                              before the FFT (same mapping as --record-mapped-wav)");
        log.info("");
        log.info("  Record raw WAV:");
        log.info("    --record-wav              toggles record mode");
        log.info("    --samplerate <hz>         ADC sample rate");
        log.info("    --duration <s>            capture length in seconds");
        log.info("    [--bits 8|16|24]          ADC resolution (default 24)");
        log.info("    [--device <i>]            input device index");
        log.info("    [--output <file>]         WAV output path (default results/recorded_<ts>.wav)");
        log.info("    [--waveform-duration <ms>]  waveform-plot length");
        log.info("    [--scale <V>]             full-scale voltage for the waveform y-axis");
        log.info("    [--width <px>] [--height <px>]");
        log.info("    [--adc-comp <csv>]        also writes <basename>_adc_corrected.wav with ADC");
        log.info("                              distortion subtracted (requires --fft-size)");
        log.info("    [--fft-size <n>]          FFT size for the ADC-correction pass");
        log.info("    [--harmonics <n>]         harmonics to subtract (default 10)");
        log.info("");
        log.info("  Record mapped WAV (apply a code-weight INL map during capture):");
        log.info("    --record-mapped-wav <csv>   weighted-code-map CSV from --analyze-histogram");
        log.info("    --samplerate, --duration, [--bits, --device, --output, --waveform-duration,");
        log.info("       --scale, --width, --height]   same as --record-wav");
        log.info("");
        log.info("  Process an existing WAV through a code map:");
        log.info("    --process-wav <file>      input WAV");
        log.info("    --load-weighted <csv>     code-weight map (from --analyze-histogram)");
        log.info("    [--output <file>]         output WAV path (default <input>_mapped.wav)");
        log.info("    [--waveform-duration <ms>]");
        log.info("    [--scale <V>]");
        log.info("    [--width <px>] [--height <px>]");
        log.info("");
        log.info("  Regression calibration (sine-wave least-squares INL fit):");
        log.info("    --regress-calibrate <wav>  WAV containing a clean sine");
        log.info("    --scale <V>               full-scale voltage range (e.g. 2.0 for +/-1 V_p)");
        log.info("    [--width <px>] [--height <px>]");
        log.info("    Output: regression_calibration_<ts>.csv + regression_chart_<ts>.png");
        log.info("");
        log.info("  Deembed DAC <-> ADC distortion (least-squares fit):");
        log.info("    --deembed                 toggles deembed mode");
        log.info("    --comp <csv>              applied_compensation CSV — pass once per ADC level");
        log.info("                              (need >= 1 + |orders| CSVs)");
        log.info("    --gen-out <csv>           output: DAC-side compensation (level-independent c_0)");
        log.info("    --adc-out <csv>           output: ADC-side correction (level-dependent c_k)");
        log.info("    [--level <dbfs> ...]      override the H1 dBFS read from each --comp header,");
        log.info("                              one value per --comp in the same order");
        log.info("    [--orders <list>]         ADC orders (default \"h-1\"). Tokens: integer literal");
        log.info("                              (\"1\",\"3\") or h-relative (\"h\",\"h-1\",\"h+1\")");
        log.info("    Legacy aliases: --comp1/--comp2, --level1/--level2");
        log.info("    Fits r_h(L) = c_0 + sum_k c_k * L^p_k(h):  c_0 -> --gen-out, c_1..c_K -> --adc-out");
        log.info("");
        log.info("  Generate signal (DAC playback only, no capture):");
        log.info("    --generate                toggles generate mode");
        log.info("    --samplerate <hz>         DAC sample rate");
        log.info("    --signal <form>           sine | triangle | rectangle | white_noise |");
        log.info("                              pink_noise | pink_noise_linear | sine_compensated");
        log.info("    --duration <s>            playback length");
        log.info("    [--bits 8|16|24|32]       DAC resolution (default 32)");
        log.info("    [--freq <hz>]             fundamental frequency (default 1000)");
        log.info("    [--amplitude <vrms>]      output amplitude in V_rms (default 1.0)");
        log.info("    [--dither <bits>]         TPDF dither at this bit depth (default 0 = off)");
        log.info("    [--device <i>]            output device index");
        log.info("    [--harmonics-csv <file>]  fft_harmonics CSV — required for sine_compensated;");
        log.info("                              injects H2..Hn at inverted phase to pre-cancel");
        log.info("                              DAC distortion");
        log.info("");
        log.info("  Gen + FFT (single shot: generate -> capture -> analyze; no WAV saved):");
        log.info("    --gen-fft                 toggles single-shot gen+fft mode");
        log.info("    --samplerate <hz>         DAC and ADC sample rate (must match)");
        log.info("    --freq <hz>               generator fundamental — snapped to the nearest exact");
        log.info("                              FFT bin");
        log.info("    --amplitude <vrms>        DAC output amplitude in V_rms");
        log.info("    --fft-size <n>            FFT size, power of 2");
        log.info("    --width <px> --height <px>  chart size");
        log.info("    --out-device <i>          output device index (DAC)");
        log.info("    --in-device <i>           input device index  (ADC)");
        log.info("    [--bits 8|16|24|32]       (default 32)");
        log.info("    [--signal <form>]         sine | triangle | rectangle | *_noise |");
        log.info("                              sine_compensated (default sine)");
        log.info("    [--harmonics-csv <file>]  fft_harmonics CSV (required for sine_compensated)");
        log.info("    [--duration <s>]          capture length (default: one FFT frame worth).");
        log.info("                              Applies only to data collection; the generator");
        log.info("                              runs throughout, including any --sync-pause window");
        log.info("    [--sync-pause <s>]        hold the recorder for N seconds (0..60) after the");
        log.info("                              generator starts driving, so an external phase-locked");
        log.info("                              analog source can settle before capture (default 0)");
        log.info("    [--dither <bits>]         TPDF dither bit depth (default 0)");
        log.info("    [--harmonics <n>]         harmonics to measure (default 10)");
        log.info("    [--window-fn <type>]      FFT window (default HANN)");
        log.info("    [--overlap <pct>]         frame overlap (default 0)");
        log.info("    [--dist-min <hz>] [--dist-max <hz>]   SNR/noise integration band");
        log.info("    [--fund-v <vrms>] [--fund-dbv <dbv>]  dBV anchor (alternative to --adc-fs-vrms)");
        log.info("    [--adc-fs-vrms <vrms>]    ADC full-scale V_rms (sets dBV scale when --fund-v/-dbv");
        log.info("                              absent)");
        log.info("    [--no-coherent]           incoherent power averaging");
        log.info("    [--cal <csv>]             filter calibration (see --freq-response)");
        log.info("    [--cal-noise]             apply cal to noise bins too (default: signal bins only)");
        log.info("    [--load-weighted <csv>]   weighted-code-map CSV from --analyze-histogram;");
        log.info("                              linearises each captured ADC code through the INL map");
        log.info("                              before the FFT");
        log.info("    [--comment <str>]         free-form chart comment");
        log.info("    [--output <dir>]          output directory (default results)");
        log.info("");
        log.info("  Iterative harmonic compensation (DAC pre-distortion loop):");
        log.info("    --iterative-compensate    toggles iterative-compensate mode");
        log.info("    --samplerate, --freq, --amplitude, --fft-size, --width, --height,");
        log.info("       --out-device, --in-device   same semantics as --gen-fft");
        log.info("    [--bits 8|16|24|32]       (default 32)");
        log.info("    [--dither <bits>]         generator TPDF dither (default 0)");
        log.info("    [--duration <s>]          seconds per iteration (default 60)");
        log.info("    [--harmonics <n>]         harmonics measured + compensated (default 10)");
        log.info("    [--window-fn <type>]      FFT window (default HANN)");
        log.info("    [--overlap <pct>]         frame overlap (default 0)");
        log.info("    [--dist-min <hz>]         lower SNR/noise integration bound");
        log.info("    [--dist-max <hz>]         upper SNR/noise integration bound");
        log.info("    [--fund-v <vrms>]         true fundamental V_rms (overrides --amplitude for dBV)");
        log.info("    [--fund-dbv <dbv>]        true fundamental level in dBV");
        log.info("    [--adc-fs-vrms <vrms>]    ADC full-scale V_rms (dBV fallback)");
        log.info("    [--no-coherent]           incoherent averaging (disables phase compensation)");
        log.info("    [--accumulate]            aggregate harmonic corrections across iterations");
        log.info("                              (default: each iteration uses only the latest residual)");
        log.info("    [--stop-after <n>]        stop after N consecutive THD-pct increases (default 4)");
        log.info("    [--target-thd <pct>]      stop once THD drops to or below this percent");
        log.info("    [--compensation-snr-margin <db>]");
        log.info("                              skip harmonics within this many dB of the noise floor");
        log.info("                              (default 10) — prevents random-walk divergence");
        log.info("    [--compensation-step <mu>]  LMS step size, 0 < mu <= 1 (default 1.0); use 0.5");
        log.info("                              to damp oscillations (helpful with --cal)");
        log.info("    [--cal <csv>]             filter calibration (see --freq-response)");
        log.info("    [--cal-noise]             apply cal to noise bins too (default: signal bins only)");
        log.info("    Outputs: fft_chart_iter<i>_<ts>.png per iteration; fft_harmonics_<ts>.csv");
        log.info("             on stop (input for --signal sine_compensated and --deembed)");
        log.info("");
        log.info("  Frequency response measurement (Farina log-sweep -> per-point H(f)):");
        log.info("    --freq-response           toggles freq-response mode");
        log.info("    --samplerate <hz>         DAC and ADC sample rate");
        log.info("    --amplitude <vrms>        DAC drive amplitude — recorded in the cal CSV header");
        log.info("                              so the apply path knows the reference");
        log.info("    --out-device <i>          output device index (DAC)");
        log.info("    --in-device <i>           input device index  (ADC)");
        log.info("    [--bits 8|16|24|32]       (default 24)");
        log.info("    [--sweep-start <hz>]      sweep start frequency (default 2)");
        log.info("    [--sweep-end <hz>]        sweep end frequency (default Fs/2)");
        log.info("    [--sweep-duration <s>]    Farina sweep length in seconds (default 5.5, min 0.5)");
        log.info("    [--sweep-points <n>]      log-spaced rows in the output CSV/chart (default 200)");
        log.info("    [--lead-in <s>]           silence before the sweep (default 0.2, min 0.05)");
        log.info("    [--adc-fs-vrms <vrms>]    ADC full-scale V_rms; written to the cal CSV header");
        log.info("                              for downstream dBV scaling (default 1.7931)");
        log.info("    [--dither <bits>]         generator TPDF dither (default 0)");
        log.info("    [--output <file>]         cal CSV path (default results/filter_cal_<ts>.csv)");
        log.info("    [--sweep-wav <file>]      also dump the raw capture as WAV");
        log.info("    [--width <px>] [--height <px>]   chart size (default 1920x600)");
        log.info("    Outputs: filter_cal_<ts>.csv (+ matching .png chart). The CSV columns are");
        log.info("    frequency_hz; magnitude_dbfs; magnitude_dbv; magnitude_db_rel; phase_deg.");
        log.info("    magnitude_db_rel is what apply paths subtract from each FFT bin");
        log.info("    (corrected_dBFS = raw_dBFS - magnitude_db_rel).");
    }

    private void logProviders() {
        log.info("Registered MixerProviders:");
        for (MixerProvider p : ServiceLoader.load(MixerProvider.class)) {
            log.info("  {}", p.getClass().getName());
        }
    }

    private void listDevices() {
        List<DeviceRef> inputs = AudioBackend.instance().listInputDevices();
        if (inputs.isEmpty()) {
            log.info("No audio input devices found.");
        } else {
            log.info("Available audio input devices ({}):", AudioBackend.instance().active());
            inputs.forEach(d -> {
                log.info("  [{}] {} ({}) — {}", d.index(), d.name(), d.description(), d.vendor());
                AudioBackend.instance().listSupportedInputFormats(d).forEach(f -> log.info("      {}", f));
            });
        }

        List<DeviceRef> outputs = AudioBackend.instance().listOutputDevices();
        if (outputs.isEmpty()) {
            log.info("No audio output devices found.");
        } else {
            log.info("Available audio output devices ({}):", AudioBackend.instance().active());
            outputs.forEach(d -> {
                log.info("  [{}] {} ({}) — {}", d.index(), d.name(), d.description(), d.vendor());
                AudioBackend.instance().listSupportedOutputFormats(d).forEach(f -> log.info("      {}", f));
            });
        }
    }

    private DeviceRef selectMixer(String[] args) {
        String indexArg = getArgValue(args, "--device");
        if (indexArg != null) {
            DeviceRef found = AudioBackend.instance().getDeviceByIndex(Integer.parseInt(indexArg), false);
            if (found == null) {
                log.error("Device not found at index: {}", indexArg);
                listDevices();
            }
            return found;
        }

        List<DeviceRef> devices = AudioBackend.instance().listInputDevices();
        if (devices.isEmpty()) {
            return null;
        }
        if (devices.size() == 1) {
            return AudioBackend.instance().getDeviceByIndex(devices.get(0).index(), false);
        }

        log.error("Multiple audio input devices found. Use --device <index> to select one:");
        devices.forEach(d -> log.error("  {}", d));
        return null;
    }

    private void runSession(DeviceRef device, int sampleRate, int durationSeconds, int windowLength,
                            double scaleVolts, int chartWidth, int chartHeight, int bitDepth,
                            boolean sineRef, double sineAmpFsRatio, String sineAmpArg,
                            int sineEdgeBins, int sineFitPoints, boolean rawHistChart) throws Exception {
        AdcHistogram histogram = new AdcHistogram(bitDepth);

        try (AudioCapture recorder = AudioBackend.instance().openCapture(device, sampleRate, bitDepth)) {
            final AdcHistogram histogramRef = histogram;
            final AtomicLong   nextLogAt    = new AtomicLong(System.currentTimeMillis());
            final int          skipFrames   = sampleRate / 100; // 10 ms warm-up
            final AtomicInteger skipped     = new AtomicInteger(0);
            recorder.setSampleListener(samples -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(samples.length);
                    return;
                }
                histogramRef.record(samples);
                long now = System.currentTimeMillis();
                if (now >= nextLogAt.get()) {
                    nextLogAt.set(now + 5_000L);
                    log.info("Samples: {}   Unique codes: {}   Min count: {}   Max count: {}",
                            String.format("%,d", histogramRef.getTotalCount()),
                            String.format("%,d", histogramRef.getUniqueCodes()),
                            String.format("%,d", histogramRef.getMinCount()),
                            String.format("%,d", histogramRef.getMaxCount()));
                }
            });

            recorder.open();
            recorder.startRecording();
            Thread.sleep(durationSeconds * 1000L);
            recorder.stopRecording();
        }

        histogram.exportCsv("results");
        if (sineRef) {
            log.info("Weight    : sine-PDF reference{}, edge mask {} bin(s)/side",
                    sineAmpArg != null ? " (A=" + sineAmpArg + " FS)" : " (auto-estimate A)",
                    sineEdgeBins);
        }
        processHistogram(histogram, windowLength, scaleVolts, chartWidth, chartHeight,
                sineRef, sineAmpFsRatio, sineEdgeBins, sineFitPoints, rawHistChart);
    }

    private void processHistogram(AdcHistogram histogram, int windowLength, double scaleVolts,
                                  int chartWidth, int chartHeight,
                                  boolean sineReference, double sineAmpFsRatio,
                                  int sineEdgeBins, int sineFitPoints,
                                  boolean rawHistogramChart) throws Exception {
        if (rawHistogramChart) {
            // Optional raw-histogram chart before any weighting — useful as a
            // sanity check (you can see the actual code distribution and the
            // rail-bin spikes from clipping before DNL processing collapses them).
            new HistogramExporter().export(histogram, scaleVolts, chartWidth, chartHeight, "results");
        }

        WeightedBuffer weighted = new WeightedBuffer(histogram.getBitDepth());
        if (sineReference) {
            weighted.computeSineReference(histogram, sineAmpFsRatio, sineEdgeBins, sineFitPoints);
        } else {
            weighted.compute(histogram, windowLength);
        }
        histogram = null;
        System.gc();
        log.info("Histogram released.");

        weighted.exportCsv("results", "weighted_raw");

        weighted.applyVoltageScale(scaleVolts);
        new ChartExporter().export(weighted, scaleVolts, chartWidth, chartHeight, "results");
        weighted.exportCsv("results", "weighted_scaled");
    }

    private void runRecordWav(DeviceRef device, int sampleRate, int durationSeconds, int bitDepth,
                              String outputPath, double waveformDurationMs,
                              double scaleVolts, int chartWidth, int chartHeight,
                              String adcCompPath, int fftSize, int harmonics) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File outFile = outputPath != null
                ? new File(outputPath)
                : new File("recording_" + ts + ".wav");

        final int   wfMax = waveformDurationMs > 0 ? (int) Math.ceil(waveformDurationMs * sampleRate / 1000.0) : 0;
        final int[] wfBuf = wfMax > 0 ? new int[wfMax] : null;
        final AtomicInteger wfCollected = new AtomicInteger(0);

        final int sampleBytes  = bitDepth / 8;
        final int frameSize    = sampleBytes * 2; // stereo
        final int skipFrames   = sampleRate / 100; // 10 ms warm-up
        final AtomicInteger skipped = new AtomicInteger(0);

        // For ADC correction we need ch1 samples in normalized float form.  Pre-allocate
        // the buffer up-front (allocation-free in the recorder thread); slot index is
        // tracked via AtomicInteger so the post-capture pass can trim to actual length.
        final int     totalFramesAlloc = adcCompPath != null
                ? (int) Math.min((long) sampleRate * (durationSeconds + 1), Integer.MAX_VALUE)
                : 0;
        final float[] capturedCh1 = totalFramesAlloc > 0 ? new float[totalFramesAlloc] : null;
        final AtomicInteger capPos = new AtomicInteger(0);
        final long halfRange = 1L << (bitDepth - 1);

        try (WavWriter wav = new WavWriter(outFile, sampleRate, 2, bitDepth, false);
             AudioCapture recorder = AudioBackend.instance().openCapture(device, sampleRate, bitDepth)) {
            recorder.setRawBytesListener(bytes -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(bytes.length / frameSize);
                    return;
                }
                try {
                    wav.writeRaw(bytes, bytes.length);
                } catch (IOException e) {
                    log.error("WAV write error", e);
                }
                if (wfBuf != null) {
                    int n = wfCollected.get();
                    if (n < wfMax) {
                        int frames = bytes.length / frameSize;
                        int toCopy = Math.min(frames, wfMax - n);
                        for (int f = 0; f < toCopy; f++) {
                            wfBuf[n + f] = recorder.readSample(bytes, f * frameSize);
                        }
                        wfCollected.addAndGet(toCopy);
                    }
                }
                if (capturedCh1 != null) {
                    int frames = bytes.length / frameSize;
                    int n      = capPos.get();
                    int toCopy = Math.min(frames, capturedCh1.length - n);
                    for (int f = 0; f < toCopy; f++) {
                        long code = recorder.readSample(bytes, f * frameSize + sampleBytes) & 0xFFFFFFFFL;
                        capturedCh1[n + f] = (float) ((code - halfRange) / (double) halfRange);
                    }
                    capPos.addAndGet(toCopy);
                }
            });
            recorder.open();
            recorder.startRecording();
            Thread.sleep(durationSeconds * 1000L);
            recorder.stopRecording();
        }

        if (wfBuf != null) {
            new WaveformExporter().export(wfBuf, sampleRate, bitDepth, scaleVolts, waveformDurationMs, chartWidth, chartHeight, ".");
        }

        if (adcCompPath != null) {
            int actual = capPos.get();
            if (actual < fftSize) {
                log.error("Captured {} samples — fewer than --fft-size {}; cannot apply ADC correction.",
                        actual, fftSize);
                return;
            }
            float[] samples = actual < capturedCh1.length
                    ? Arrays.copyOf(capturedCh1, actual)
                    : capturedCh1;

            log.info("ADC correction: loading coefficients from {}", adcCompPath);
            AdcCorrection adc = loadAdcCorrectionCsv(adcCompPath);
            log.info("ADC correction: running FFT (size={}, harmonics={}) to extract fundamental phase/level",
                    fftSize, harmonics);
            FftAnalyzer fftAnalyzer = new FftAnalyzer();
            FftAnalyzer.Result r = fftAnalyzer.analyze(samples, sampleRate, fftSize, harmonics,
                    WindowType.HANN, FftOverlap.PCT_0,
                    0.0, 0.0, true, Double.NaN);
            logAdcCorrectedHarmonics(r, adc);
            subtractAdcDistortionPerFrameInPlace(samples, sampleRate, fftSize, r, adc);

            String   baseName = outFile.getName().replaceFirst("\\.wav$", "");
            File corrFile = new File(outFile.getParentFile(), baseName + "_adc_corrected.wav");
            byte[] pcm = floatMonoToStereoPcmBytes(samples, bitDepth);
            try (WavWriter cw = new WavWriter(corrFile, sampleRate, 2, bitDepth, false)) {
                cw.writeRaw(pcm, pcm.length);
            }
            log.info("ADC-corrected WAV written: {}", corrFile.getAbsolutePath());
        }
    }

    private void runRecordMappedWav(DeviceRef device, int sampleRate, int durationSeconds, int bitDepth,
                                    float scaleVolts, WeightedBuffer weights, String outputPath,
                                    double waveformDurationMs, int chartWidth, int chartHeight) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File outFile = outputPath != null
                ? new File(outputPath)
                : new File("recording_mapped_" + ts + ".wav");

        final int   wfMax      = waveformDurationMs > 0 ? (int) Math.ceil(waveformDurationMs * sampleRate / 1000.0) : 0;
        final int[] wfBuf      = wfMax > 0 ? new int[wfMax] : null;
        final AtomicInteger wfCollected = new AtomicInteger(0);
        final int           skipFrames  = sampleRate / 100; // 10 ms warm-up
        final AtomicInteger skipped     = new AtomicInteger(0);

        try (WavWriter wav = new WavWriter(outFile, sampleRate, 2, bitDepth + 8, false);
             AudioCapture recorder = AudioBackend.instance().openCapture(device, sampleRate, bitDepth)) {
            recorder.setSampleListener(samples -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(samples.length);
                    return;
                }
                StereoSampleFloat[] mapped = new StereoSampleFloat[samples.length];
                for (int i = 0; i < samples.length; i++) {
                    mapped[i] = weights.correctedCode(samples[i]);
                }
                try {
                    wav.writeSamples(mapped, bitDepth);
                } catch (IOException e) {
                    log.error("WAV write error", e);
                }
                if (wfBuf != null) {
                    int n = wfCollected.get();
                    if (n < wfMax) {
                        int   toCopy = Math.min(mapped.length, wfMax - n);
                        int[] ch1Buf = new int[mapped.length];
                        for (int ch1BufPos = 0; ch1BufPos < mapped.length; ch1BufPos++) {
                            ch1Buf[ch1BufPos] = (int) Math.round(mapped[ch1BufPos].ch1);
                        }
                        System.arraycopy(ch1Buf, 0, wfBuf, n, toCopy);
                        wfCollected.addAndGet(toCopy);
                    }
                }
            });
            recorder.open();
            recorder.startRecording();
            Thread.sleep(durationSeconds * 1000L);
            recorder.stopRecording();
        }

        if (wfBuf != null) {
            new WaveformExporter().export(wfBuf, sampleRate, bitDepth, scaleVolts, waveformDurationMs, chartWidth, chartHeight, ".");
        }
    }

    private void runProcessWav(WavReader reader, float scaleVolts, WeightedBuffer weights,
                               String outputPath, double waveformDurationMs,
                               int chartWidth, int chartHeight) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File outFile = outputPath != null
                ? new File(outputPath)
                : new File("processed_" + ts + ".wav");

        int   bitDepth = reader.getBitsPerSample();
        int   wfMax    = waveformDurationMs > 0 ? (int) Math.ceil(waveformDurationMs * reader.getSampleRate() / 1000.0) : 0;
        int[] wfBuf    = wfMax > 0 ? new int[wfMax] : null;
        final AtomicInteger wfCollected = new AtomicInteger(0);

        try (WavWriter wav = new WavWriter(outFile, reader.getSampleRate(), reader.getChannels(), bitDepth + 8, false)) {
            reader.process(samples -> {
                StereoSampleFloat[] mapped = new StereoSampleFloat[samples.length];
                for (int i = 0; i < samples.length; i++) {
                    mapped[i] = weights.correctedCode(samples[i]);
                }
                try {
                    wav.writeSamples(mapped, bitDepth);
                } catch (IOException e) {
                    log.error("WAV write error", e);
                }
                if (wfBuf != null) {
                    int n = wfCollected.get();
                    if (n < wfMax) {
                        int toCopy = Math.min(mapped.length, wfMax - n);
                        for (int i = 0; i < toCopy; i++) {
                            wfBuf[n + i] = (int) mapped[i].ch0;
                        }
                        wfCollected.addAndGet(toCopy);
                    }
                }
            });
        }

        if (wfBuf != null) {
            new WaveformExporter().export(wfBuf, reader.getSampleRate(), bitDepth, scaleVolts, waveformDurationMs, chartWidth, chartHeight, ".");
        }
    }

    private void runAnalyzeHistogramMode(String[] args) throws Exception {
        String fileArg   = getArgValue(args, "--analyze-histogram");
        String bitsArg   = getArgValue(args, "--bits");
        String windowArg = getArgValue(args, "--window");
        String scaleArg  = getArgValue(args, "--scale");
        String widthArg  = getArgValue(args, "--width");
        String heightArg = getArgValue(args, "--height");
        boolean sineRef  = hasArg(args, "--sine-reference");
        String  sineAmpArg = getArgValue(args, "--sine-amplitude");
        double  sineAmpFsRatio = sineAmpArg != null ? Double.parseDouble(sineAmpArg) : Double.NaN;
        String  sineEdgeArg = getArgValue(args, "--sine-edge-bins");
        int     sineEdgeBins = sineEdgeArg != null ? Integer.parseInt(sineEdgeArg) : 2;
        String  sineFitArg = getArgValue(args, "--sine-fit-points");
        int     sineFitPoints = sineFitArg != null ? Integer.parseInt(sineFitArg) : 30;
        boolean rawHistChart = hasArg(args, "--histogram-chart");

        if (bitsArg == null) {
            log.error("--bits is required for --analyze-histogram.");
            System.exit(1);
        }
        if (windowArg == null) {
            log.error("--window is required for --analyze-histogram.");
            System.exit(1);
        }
        if (scaleArg == null) {
            log.error("--scale is required for --analyze-histogram.");
            System.exit(1);
        }
        if (widthArg == null) {
            log.error("--width is required for --analyze-histogram.");
            System.exit(1);
        }
        if (heightArg == null) {
            log.error("--height is required for --analyze-histogram.");
            System.exit(1);
        }

        int    bitDepth    = Integer.parseInt(bitsArg);
        int    windowLength = Integer.parseInt(windowArg);
        double scaleVolts  = Double.parseDouble(scaleArg);
        int    chartWidth  = Integer.parseInt(widthArg);
        int    chartHeight = Integer.parseInt(heightArg);

        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32.");
            System.exit(1);
        }

        log.info("Mode    : analyze histogram (DNL + INL)");
        log.info("File    : {}", fileArg);
        log.info("Bits    : {}", bitDepth);
        log.info("Window  : {} codes", windowLength);
        log.info("Scale   : {} V", scaleVolts);
        log.info("Chart   : {}x{} px", chartWidth, chartHeight);
        if (sineRef) {
            log.info("Weight  : sine-PDF reference{}, edge mask {} bin(s)/side",
                    sineAmpArg != null ? " (A=" + sineAmpArg + " FS)" : " (auto-estimate A)",
                    sineEdgeBins);
        }

        AdcHistogram histogram = new AdcHistogram(bitDepth);
        histogram.loadCsv(fileArg);
        if (rawHistChart) {
            new HistogramExporter().export(histogram, scaleVolts, chartWidth, chartHeight, "results");
        }
        WeightedBuffer weighted = new WeightedBuffer(bitDepth);
        if (sineRef) {
            weighted.computeSineReference(histogram, sineAmpFsRatio, sineEdgeBins, sineFitPoints);
        } else {
            weighted.compute(histogram, windowLength);
        }
        histogram = null;
        System.gc();
        log.info("Histogram released.");
        new DnlInlExporter().export(weighted, scaleVolts, chartWidth, chartHeight, "results");
    }

    private void runFftAnalyzeMode(String[] args) throws Exception {
        String fileArg      = getArgValue(args, "--fft-analyze");
        String fftSizeArg   = getArgValue(args, "--fft-size");
        String harmonicsArg = getArgValue(args, "--harmonics");
        String widthArg     = getArgValue(args, "--width");
        String heightArg    = getArgValue(args, "--height");
        String windowArg    = getArgValue(args, "--window-fn");
        String overlapArg   = getArgValue(args, "--overlap");
        String snrMinArg    = getArgValue(args, "--dist-min");
        String snrMaxArg    = getArgValue(args, "--dist-max");
        String fundVArg     = getArgValue(args, "--fund-v");
        String fundDbVArg   = getArgValue(args, "--fund-dbv");
        String  subHarmArg  = getArgValue(args, "--sub-harmonics");
        boolean subHarmReIm = hasArg(args, "--sub-harmonics-reim");
        boolean coherent    = !hasArg(args, "--no-coherent");
        String  commentArg  = getArgValue(args, "--comment");
        String  freqArg     = getArgValue(args, "--freq");
        String  adcCompArg  = getArgValue(args, "--adc-comp");
        String  filterCalArg = getArgValue(args, "--cal");
        boolean calNoise     = hasArg(args, "--cal-noise");
        String  adcFsArg     = getArgValue(args, "--adc-fs-vrms");
        String  loadWeightedArg = getArgValue(args, "--load-weighted");
        if (adcFsArg != null) {
            AudioBackend.adcFsVoltageRms = Double.parseDouble(adcFsArg);
        }

        if (fftSizeArg == null) {
            log.error("--fft-size is required for --fft-analyze.");
            System.exit(1);
        }
        if (widthArg == null) {
            log.error("--width is required for --fft-analyze.");
            System.exit(1);
        }
        if (heightArg == null) {
            log.error("--height is required for --fft-analyze.");
            System.exit(1);
        }

        int fftSize      = Integer.parseInt(fftSizeArg);
        int harmonics    = harmonicsArg != null ? Integer.parseInt(harmonicsArg) : 10;
        int chartWidth   = Integer.parseInt(widthArg);
        int chartHeight  = Integer.parseInt(heightArg);
        WindowType windowType = windowArg  != null
                ? WindowType.fromString(windowArg)
                : WindowType.HANN;
        FftOverlap overlap = overlapArg != null
                ? FftOverlap.fromString(overlapArg)
                : FftOverlap.PCT_0;

        if (Integer.bitCount(fftSize) != 1) {
            log.error("--fft-size must be a power of 2 (e.g. 65536, 131072).");
            System.exit(1);
        }

        WavReader reader = new WavReader(fileArg);
        int sampleRate   = reader.getSampleRate();
        int bitDepth     = reader.getBitsPerSample();
        double snrFreqMin = snrMinArg != null ? Double.parseDouble(snrMinArg) : 0.0;
        double snrFreqMax = snrMaxArg != null ? Double.parseDouble(snrMaxArg) : sampleRate / 2.0;
        double fundRefDbV;
        if (fundVArg != null) {
            double v = Double.parseDouble(fundVArg);
            fundRefDbV = 20.0 * Math.log10(v);
        } else if (fundDbVArg != null) {
            fundRefDbV = Double.parseDouble(fundDbVArg);
        } else {
            fundRefDbV = Double.NaN;
        }

        Double genFreqHz = freqArg != null ? Double.parseDouble(freqArg) : null;

        log.info("Mode      : FFT analysis");
        log.info("File      : {}", fileArg);
        log.info("SampleRate: {} Hz", sampleRate);
        log.info("Bits      : {}", bitDepth);
        log.info("FFT size  : {}", fftSize);
        log.info("Harmonics : {}", harmonics);
        log.info("Window    : {}", windowType);
        log.info("Overlap   : {}", overlap.label);
        log.info("SNR range : {}-{} Hz", snrFreqMin, snrFreqMax);
        log.info("Averaging : {}", coherent ? "coherent" : "incoherent");
        log.info("Chart     : {}x{} px", chartWidth, chartHeight);
        if (genFreqHz != null) log.info("Gen freq  : {} Hz", String.format(Locale.US, "%.6f", genFreqHz));

        // Optional code-map linearisation (--load-weighted): each raw ADC code is
        // run through the WeightedBuffer's offset-binary→linearised float mapping
        // before the offset-binary→signed→[-1,+1] conversion.  Same mapping
        // --record-mapped-wav applies during capture; lets you analyse a raw
        // WAV through an INL correction without first writing a mapped WAV.
        WeightedBuffer weights = null;
        if (loadWeightedArg != null) {
            weights = new WeightedBuffer(bitDepth);
            weights.loadCsv(loadWeightedArg);
            weights.buildCodeMap();
            log.info("Weighted  : {}", loadWeightedArg);
        }

        // Collect all frames into a float[] normalized to -1.0 … +1.0 (ch1)
        long totalFrames = reader.getFrameCount();
        float[] samples  = new float[(int) Math.min(totalFrames, Integer.MAX_VALUE)];
        long   halfRange = 1L << (bitDepth - 1);
        AtomicInteger pos = new AtomicInteger(0);

        final WeightedBuffer w = weights;
        reader.process(block -> {
            int n = pos.get();
            for (int i = 0; i < block.length && n + i < samples.length; i++) {
                // ch1 is offset-binary; convert to signed, then normalize
                double code = w != null
                        ? w.correctedCode(block[i]).ch1
                        : (double) block[i].ch1;
                samples[n + i] = (float) ((code - halfRange) / (double) halfRange);
            }
            pos.addAndGet(block.length);
        });

        int actualSamples = pos.get();
        float[] trimmed   = actualSamples < samples.length
                ? Arrays.copyOf(samples, actualSamples)
                : samples;

        FftAnalyzer fftAnalyzer = new FftAnalyzer();
        if (subHarmArg != null) {
            log.info("Sub-harm  : {}  (mode: {})", subHarmArg, subHarmReIm ? "re/im" : "amp+phase");
            fftAnalyzer.subtractHarmonicsCsv(trimmed, sampleRate, subHarmArg, subHarmReIm);
        }

        // Suppress the inner THD/SNR summary when ADC correction will run, so
        // the only THD line logged is the corrected one.
        // Suppress inner THD/SNR summary when any post-correction step will run,
        // so the only THD line logged is the final corrected one.
        boolean willPostCorrect = adcCompArg != null || filterCalArg != null;
        // Pass --freq as the expected-fundamental hint so the analyzer doesn't
        // latch onto a louder spur when the true fundamental sits in a deep
        // filter notch (and the spectrum is dominated by harmonics / mains).
        double expectedFundHz = genFreqHz != null ? genFreqHz : Double.NaN;
        FftAnalyzer.Result result = fftAnalyzer.analyze(trimmed, sampleRate, fftSize, harmonics,
                windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbV,
                !willPostCorrect, expectedFundHz);

        if (adcCompArg != null) {
            log.info("ADC comp  : {}", adcCompArg);
            AdcCorrection adc = loadAdcCorrectionCsv(adcCompArg);
            // Log per-harmonic raw vs corrected before mutating the result.
            logAdcCorrectedHarmonics(result, adc);
            // Mutate harmonic bins, amplitudes, phases, harmonic table, and
            // THD scalars so chart / CSV / downstream see the corrected values.
            applyFftAdcCorrectionInPlace(result, adc);
            log.info("THD   : {}%  ({} dB)  [after ADC correction]",
                    String.format(Locale.US, "%.8f", result.thdPct),
                    String.format(Locale.US, "%.2f", result.thdDb));
        }

        double[] overlayFreqs  = null;
        double[] overlayDbFs   = null;
        double[] preCorrFreqs  = null;
        double[] preCorrDbFs   = null;
        if (filterCalArg != null) {
            log.info("Filter cal: {}{}", filterCalArg, calNoise ? "  (--cal-noise: correct all bins)" : "");
            FilterCalibration cal = loadFilterCalibrationCsv(filterCalArg);
            // Capture pre-correction data BEFORE mutating the result.
            double[][] overlay  = computeCalOverlay(cal, result);
            double[][] prePeaks = capturePreCorrectionPeaks(result);
            if (overlay != null) {
                overlayFreqs = overlay[0];
                overlayDbFs  = overlay[1];
            }
            preCorrFreqs = prePeaks[0];
            preCorrDbFs  = prePeaks[1];
            applyFilterCompensationInPlace(result, cal, calNoise);
            log.info("THD   : {}%  ({} dB)  [after filter de-attenuation]",
                    String.format(Locale.US, "%.8f", result.thdPct),
                    String.format(Locale.US, "%.2f", result.thdDb));
        }

        applyDefaultDbvScaling(result);

        fftAnalyzer.exportFftCsv(result, "results");
        fftAnalyzer.exportHarmonicsCsv(result, "results");
        fftAnalyzer.exportChart(result, chartWidth, chartHeight, "results",
                commentArg, subHarmArg != null, null, genFreqHz,
                overlayFreqs, overlayDbFs, preCorrFreqs, preCorrDbFs);
        if (genFreqHz != null) {
            logClockMismatch(0, genFreqHz, result.fundamentalHzRefined, sampleRate);
        }
    }

    private void runRegressCalibrateMode(String[] args) throws Exception {
        String fileArg   = getArgValue(args, "--regress-calibrate");
        String scaleArg  = getArgValue(args, "--scale");
        String widthArg  = getArgValue(args, "--width");
        String heightArg = getArgValue(args, "--height");

        if (scaleArg == null) {
            log.error("--scale is required for --regress-calibrate (full-scale voltage range, e.g. 2.0 for ±1 V).");
            System.exit(1);
        }
        double scaleVolts = Double.parseDouble(scaleArg);
        int chartWidth    = widthArg  != null ? Integer.parseInt(widthArg)  : 1920;
        int chartHeight   = heightArg != null ? Integer.parseInt(heightArg) : 600;

        WavReader reader      = new WavReader(fileArg);
        int       sampleRate  = reader.getSampleRate();
        int       bitDepth    = reader.getBitsPerSample();
        long      totalFrames = reader.getFrameCount();

        log.info("Mode      : Regression calibration");
        log.info("File      : {}", fileArg);
        log.info("SampleRate: {} Hz", sampleRate);
        log.info("Bits      : {}", bitDepth);
        log.info("Samples   : {}", totalFrames);
        log.info("Scale     : {} V", scaleVolts);
        log.info("Chart     : {}x{} px", chartWidth, chartHeight);

        float[] samples   = new float[(int) Math.min(totalFrames, Integer.MAX_VALUE)];
        long    halfRange = 1L << (bitDepth - 1);
        AtomicInteger pos = new AtomicInteger(0);

        reader.process(block -> {
            int n = pos.get();
            for (int i = 0; i < block.length && n + i < samples.length; i++) {
                int signed = block[i].ch1 - (int) halfRange;
                samples[n + i] = (float) (signed / (double) halfRange);
            }
            pos.addAndGet(block.length);
        });

        int actualSamples = pos.get();
        float[] trimmed = actualSamples < samples.length
                ? Arrays.copyOf(samples, actualSamples)
                : samples;

        RegressionCalibrator calibrator = new RegressionCalibrator();
        RegressionCalibrator.Result result = calibrator.calibrate(trimmed, sampleRate, bitDepth);
        calibrator.exportCsv(result, scaleVolts, "results");
        calibrator.exportChart(result, scaleVolts, chartWidth, chartHeight, "results");
    }

    private void runGenerate(String[] args) throws Exception {
        String samplerateArg = getArgValue(args, "--samplerate");
        String bitsArg       = getArgValue(args, "--bits");
        String signalArg     = getArgValue(args, "--signal");
        String freqArg       = getArgValue(args, "--freq");
        String amplitudeArg  = getArgValue(args, "--amplitude");
        String durationArg   = getArgValue(args, "--duration");
        String ditherArg     = getArgValue(args, "--dither");
        String harmonicsCsv  = getArgValue(args, "--harmonics-csv");

        if (signalArg == null) {
            log.error("--signal is required for --generate.");
            System.exit(1);
        }
        if (durationArg == null) {
            log.error("--duration is required for --generate.");
            System.exit(1);
        }

        GenSignalForm form = GenSignalForm.fromString(signalArg);

        // --- For sine_compensated: read generator-parameter metadata from the
        //     CSV (sample rate, bit depth, amplitude, exact frequency).  These
        //     take priority as defaults but CLI flags override them.
        SignalGenerator.Metadata csvMeta = null;
        if (form == GenSignalForm.SINE_COMPENSATED && harmonicsCsv != null) {
            csvMeta = SignalGenerator.readAppliedCompensationMetadata(harmonicsCsv);
            if (csvMeta != null) {
                log.info("CSV metadata loaded from {}: sr={} Hz, bits={}, amp={} V RMS, freq={} Hz",
                        harmonicsCsv,
                        csvMeta.getSampleRateHz() > 0 ? csvMeta.getSampleRateHz() : "(absent)",
                        csvMeta.getBitDepth()     > 0 ? csvMeta.getBitDepth()     : "(absent)",
                        csvMeta.getAmplitudeVRms() > 0
                                ? String.format(Locale.US, "%.6f", csvMeta.getAmplitudeVRms()) : "(absent)",
                        csvMeta.getFrequencyHz() > 0
                                ? String.format(Locale.US, "%.6f", csvMeta.getFrequencyHz())   : "(absent)");
            }
        }

        // --- Parameter resolution: CLI > CSV > built-in default --------------
        int sampleRate;
        if (samplerateArg != null) {
            sampleRate = Integer.parseInt(samplerateArg);
        } else if (csvMeta != null && csvMeta.getSampleRateHz() > 0) {
            sampleRate = csvMeta.getSampleRateHz();
        } else {
            log.error("--samplerate is required for --generate (and no sample_rate_hz in CSV metadata).");
            System.exit(1);
            return;
        }
        if (!isValidSampleRate(sampleRate)) {
            log.error("--samplerate must be one of: 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000");
            System.exit(1);
        }

        int bitDepth;
        if (bitsArg != null) {
            bitDepth = Integer.parseInt(bitsArg);
        } else if (csvMeta != null && csvMeta.getBitDepth() > 0) {
            bitDepth = csvMeta.getBitDepth();
        } else {
            bitDepth = 16;
        }
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32.");
            System.exit(1);
        }

        double frequency;
        if (freqArg != null) {
            frequency = Double.parseDouble(freqArg);
        } else if (csvMeta != null && csvMeta.getFrequencyHz() > 0) {
            frequency = csvMeta.getFrequencyHz();
        } else {
            frequency = 1000.0;
        }

        double amplitudeVRms;
        if (amplitudeArg != null) {
            amplitudeVRms = Double.parseDouble(amplitudeArg);
        } else if (csvMeta != null && csvMeta.getAmplitudeVRms() > 0) {
            amplitudeVRms = csvMeta.getAmplitudeVRms();
        } else {
            amplitudeVRms = 1.0;
        }

        int    duration      = Integer.parseInt(durationArg);
        int    ditherBits    = (ditherArg    != null) ? Integer.parseInt(ditherArg)      : 0;

        DeviceRef mixer = selectOutputMixer(args);
        if (mixer == null) {
            log.error("No suitable audio output device found.");
            System.exit(1);
        }

        log.info("Mode      : generate");
        log.info("Device    : {}", mixer.name());
        log.info("SampleRate: {} Hz", sampleRate);
        log.info("Bits      : {}", bitDepth);
        log.info("Signal    : {}", form);
        log.info("Frequency : {} Hz", frequency);
        log.info("Amplitude : {} V RMS", amplitudeVRms);
        log.info("Duration  : {} second(s)", duration);
        if (ditherBits > 0) {
            log.info("Dither    : TPDF {} bit", ditherBits);
        }

        SignalGenerator generator;
        if (form == GenSignalForm.SINE_COMPENSATED) {
            if (harmonicsCsv == null) {
                log.error("--harmonics-csv <file> is required for --signal sine_compensated");
                System.exit(1);
            }
            log.info("Harmonics : {}", harmonicsCsv);
            generator = new SignalGenerator(frequency, sampleRate, amplitudeVRms, harmonicsCsv);
        } else {
            generator = new SignalGenerator(form, frequency, sampleRate, amplitudeVRms);
        }
        try (AudioPlayback ag = AudioBackend.instance().openPlayback(mixer, sampleRate, bitDepth, ditherBits)) {
            ag.open();
            ag.play(generator, duration);
        }
    }

    private DeviceRef selectOutputMixer(String[] args) {
        String indexArg = getArgValue(args, "--device");
        if (indexArg != null) {
            return AudioBackend.instance().getDeviceByIndex(Integer.parseInt(indexArg), true);
        }
        List<DeviceRef> devices = AudioBackend.instance().listOutputDevices();
        if (devices.isEmpty()) {
            return null;
        }
        if (devices.size() == 1) {
            return AudioBackend.instance().getDeviceByIndex(devices.get(0).index(), true);
        }
        log.error("Multiple audio output devices found. Use --device <index> to select one:");
        devices.forEach(d -> log.error("  {}", d));
        return null;
    }

    // =========================================================================
    // Gen + FFT single-shot mode (no WAV file)
    // =========================================================================

    /**
     * Plays a generated signal, captures it, runs one FFT analysis, and writes
     * the usual chart + CSV outputs — without saving the captured WAV.  This is
     * the same capture/analyze pipeline {@code --iterative-compensate} uses for
     * its iteration 0, without the iteration loop or accumulator machinery.
     */
    private void runGenFftMode(String[] args) throws Exception {
        String samplerateArg = getArgValue(args, "--samplerate");
        String bitsArg       = getArgValue(args, "--bits");
        String freqArg       = getArgValue(args, "--freq");
        String amplitudeArg  = getArgValue(args, "--amplitude");
        String ditherArg     = getArgValue(args, "--dither");
        String durationArg   = getArgValue(args, "--duration");
        String signalArg     = getArgValue(args, "--signal");
        String fftSizeArg    = getArgValue(args, "--fft-size");
        String harmonicsArg  = getArgValue(args, "--harmonics");
        String windowArg     = getArgValue(args, "--window-fn");
        String overlapArg    = getArgValue(args, "--overlap");
        String distMinArg    = getArgValue(args, "--dist-min");
        String distMaxArg    = getArgValue(args, "--dist-max");
        String fundVArg      = getArgValue(args, "--fund-v");
        String fundDbVArg    = getArgValue(args, "--fund-dbv");
        boolean coherent     = !hasArg(args, "--no-coherent");
        String widthArg      = getArgValue(args, "--width");
        String heightArg     = getArgValue(args, "--height");
        String filterCalArg  = getArgValue(args, "--cal");
        boolean calNoise     = hasArg(args, "--cal-noise");
        String adcFsArg      = getArgValue(args, "--adc-fs-vrms");
        String commentArg    = getArgValue(args, "--comment");
        String outputDirArg  = getArgValue(args, "--output");
        String harmonicsCsv  = getArgValue(args, "--harmonics-csv");
        String loadWeightedArg = getArgValue(args, "--load-weighted");
        String syncPauseArg  = getArgValue(args, "--sync-pause");
        if (adcFsArg != null) {
            AudioBackend.adcFsVoltageRms = Double.parseDouble(adcFsArg);
        }

        if (samplerateArg == null) { log.error("--samplerate required"); System.exit(1); }
        if (freqArg       == null) { log.error("--freq required");       System.exit(1); }
        if (amplitudeArg  == null) { log.error("--amplitude required");  System.exit(1); }
        if (fftSizeArg    == null) { log.error("--fft-size required");   System.exit(1); }
        if (widthArg      == null) { log.error("--width required");      System.exit(1); }
        if (heightArg     == null) { log.error("--height required");     System.exit(1); }

        int    sampleRate = Integer.parseInt(samplerateArg);
        int    bitDepth   = bitsArg    != null ? Integer.parseInt(bitsArg)    : 32;
        double frequency  = Double.parseDouble(freqArg);
        double amplitude  = Double.parseDouble(amplitudeArg);
        int    ditherBits = ditherArg  != null ? Integer.parseInt(ditherArg)  : 0;
        int    fftSize    = Integer.parseInt(fftSizeArg);
        int    harmonics  = harmonicsArg != null ? Integer.parseInt(harmonicsArg) : 10;
        int    chartWidth  = Integer.parseInt(widthArg);
        int    chartHeight = Integer.parseInt(heightArg);
        String outputDir   = outputDirArg != null ? outputDirArg : "results";
        GenSignalForm form = signalArg != null
                ? GenSignalForm.fromString(signalArg)
                : GenSignalForm.SINE;
        WindowType windowType = windowArg != null
                ? WindowType.fromString(windowArg) : WindowType.HANN;
        FftOverlap overlap = overlapArg != null
                ? FftOverlap.fromString(overlapArg)   : FftOverlap.PCT_0;

        if (!isValidSampleRate(sampleRate)) {
            log.error("--samplerate must be one of the supported rates"); System.exit(1);
        }
        if (Integer.bitCount(fftSize) != 1) {
            log.error("--fft-size must be a power of 2"); System.exit(1);
        }
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32"); System.exit(1);
        }

        double snrFreqMin = distMinArg != null ? Double.parseDouble(distMinArg) : 0.0;
        double snrFreqMax = distMaxArg != null ? Double.parseDouble(distMaxArg) : sampleRate / 2.0;
        double fundRefDbV;
        if (fundVArg != null) {
            fundRefDbV = 20.0 * Math.log10(Double.parseDouble(fundVArg));
        } else if (fundDbVArg != null) {
            fundRefDbV = Double.parseDouble(fundDbVArg);
        } else {
            fundRefDbV = Double.NaN;
        }

        // Snap frequency to nearest exact FFT bin (matches iterative-compensate).
        double freqRequested = frequency;
        long   alignedBin    = Math.round(frequency * fftSize / (double) sampleRate);
        if (alignedBin < 1) alignedBin = 1;
        frequency = alignedBin * sampleRate / (double) fftSize;

        // Duration defaults to just enough samples for one FFT frame, rounded up.
        int duration = durationArg != null
                ? Integer.parseInt(durationArg)
                : (int) Math.ceil(fftSize / (double) sampleRate) + 1;

        logProviders();
        DeviceRef outDevice = selectMixerByFlag(args, "--out-device", true);
        DeviceRef inDevice  = selectMixerByFlag(args, "--in-device",  false);
        if (outDevice == null) { log.error("No output device. Use --out-device <index>."); System.exit(1); }
        if (inDevice  == null) { log.error("No input device. Use --in-device <index>.");  System.exit(1); }

        FilterCalibration filterCal = filterCalArg != null
                ? loadFilterCalibrationCsv(filterCalArg) : null;

        log.info("=== Gen + FFT (single shot) ===");
        log.info("Out device : {}", outDevice.name());
        log.info("In device  : {}", inDevice.name());
        log.info("Sample rate: {} Hz", sampleRate);
        log.info("Bits       : {}",    bitDepth);
        log.info("Signal     : {}",    form);
        log.info("Frequency  : {} Hz (requested {} Hz, snapped to bin {})",
                String.format(Locale.US, "%.6f", frequency),
                String.format(Locale.US, "%.6f", freqRequested),
                alignedBin);
        log.info("Amplitude  : {} V RMS", amplitude);
        log.info("Duration   : {} s",     duration);
        log.info("FFT size   : {}",       fftSize);
        log.info("Harmonics  : {}",       harmonics);
        log.info("Window     : {}",       windowType);
        log.info("Overlap    : {}",       overlap.label);
        log.info("SNR range  : {}-{} Hz", snrFreqMin, snrFreqMax);
        log.info("Averaging  : {}",       coherent ? "coherent" : "incoherent");
        if (filterCalArg != null) {
            log.info("Filter cal : {}{}", filterCalArg, calNoise ? "  (--cal-noise: correct all bins)" : "");
        }
        if (harmonicsCsv != null) {
            log.info("Harmonics  : {}",   harmonicsCsv);
        }

        int syncPauseSec = 0;
        if (syncPauseArg != null) {
            syncPauseSec = Integer.parseInt(syncPauseArg);
            if (syncPauseSec < 0 || syncPauseSec > 60) {
                log.error("--sync-pause must be in [0, 60] seconds");
                System.exit(1);
            }
            if (syncPauseSec > 0) {
                log.info("Sync pause : {} s before capture (generator runs during this window)",
                        syncPauseSec);
            }
        }

        WeightedBuffer weights = null;
        if (loadWeightedArg != null) {
            weights = new WeightedBuffer(bitDepth);
            weights.loadCsv(loadWeightedArg);
            weights.buildCodeMap();
            log.info("Weighted   : {}", loadWeightedArg);
        }

        SignalGenerator gen;
        if (form == GenSignalForm.SINE_COMPENSATED) {
            if (harmonicsCsv == null) {
                log.error("--harmonics-csv <file> is required for --signal sine_compensated");
                System.exit(1);
            }
            gen = new SignalGenerator(frequency, sampleRate, amplitude, harmonicsCsv);
        } else {
            if (harmonicsCsv != null) {
                log.warn("--harmonics-csv is only used with --signal sine_compensated; ignoring");
            }
            gen = new SignalGenerator(form, frequency, sampleRate, amplitude);
        }
        float[] samples = captureWithGenerator(gen, outDevice, inDevice,
                sampleRate, bitDepth, ditherBits, duration, weights, syncPauseSec);

        FftAnalyzer fftAnalyzer = new FftAnalyzer();
        FftAnalyzer.Result result = fftAnalyzer.analyze(
                samples, sampleRate, fftSize, harmonics,
                windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbV,
                filterCal == null, frequency);

        double[] overlayFreqs = null;
        double[] overlayDbFs  = null;
        double[] preCorrFreqs = null;
        double[] preCorrDbFs  = null;
        if (filterCal != null) {
            double[][] ov = computeCalOverlay(filterCal, result);
            if (ov != null) { overlayFreqs = ov[0]; overlayDbFs = ov[1]; }
            double[][] pp = capturePreCorrectionPeaks(result);
            preCorrFreqs = pp[0];
            preCorrDbFs  = pp[1];
            applyFilterCompensationInPlace(result, filterCal, calNoise);
            log.info("THD   : {}%  ({} dB)  [after filter de-attenuation]",
                    String.format(Locale.US, "%.8f", result.thdPct),
                    String.format(Locale.US, "%.2f", result.thdDb));
        }
        applyDefaultDbvScaling(result);

        fftAnalyzer.exportFftCsv(result, outputDir);
        fftAnalyzer.exportHarmonicsCsv(result, outputDir);
        fftAnalyzer.exportChart(result, chartWidth, chartHeight, outputDir,
                commentArg, false, null, frequency,
                overlayFreqs, overlayDbFs, preCorrFreqs, preCorrDbFs);
        logClockMismatch(0, frequency, result.fundamentalHzRefined, sampleRate);
    }

    // =========================================================================
    // Iterative harmonic compensation workflow
    // =========================================================================

    private void runIterativeCompensate(String[] args) throws Exception {
        // --- Parse parameters ------------------------------------------------
        String samplerateArg = getArgValue(args, "--samplerate");
        String bitsArg       = getArgValue(args, "--bits");
        String freqArg       = getArgValue(args, "--freq");
        String amplitudeArg  = getArgValue(args, "--amplitude");
        String ditherArg     = getArgValue(args, "--dither");
        String durationArg   = getArgValue(args, "--duration");
        String fftSizeArg    = getArgValue(args, "--fft-size");
        String harmonicsArg  = getArgValue(args, "--harmonics");
        String windowArg     = getArgValue(args, "--window-fn");
        String overlapArg    = getArgValue(args, "--overlap");
        String distMinArg    = getArgValue(args, "--dist-min");
        String distMaxArg    = getArgValue(args, "--dist-max");
        String fundVArg      = getArgValue(args, "--fund-v");
        String fundDbVArg    = getArgValue(args, "--fund-dbv");
        boolean coherent     = !hasArg(args, "--no-coherent");
        boolean accumulate   = hasArg(args, "--accumulate");
        String stopAfterArg  = getArgValue(args, "--stop-after");
        String targetThdArg  = getArgValue(args, "--target-thd");
        String widthArg      = getArgValue(args, "--width");
        String heightArg     = getArgValue(args, "--height");
        String filterCalArg  = getArgValue(args, "--cal");
        boolean calNoise     = hasArg(args, "--cal-noise");
        String snrMarginArg  = getArgValue(args, "--compensation-snr-margin");
        String compStepArg   = getArgValue(args, "--compensation-step");
        double compSnrMargin = snrMarginArg != null ? Double.parseDouble(snrMarginArg) : 10.0;
        double compStep      = compStepArg  != null ? Double.parseDouble(compStepArg)  : 1.0;
        String adcFsArg      = getArgValue(args, "--adc-fs-vrms");
        if (adcFsArg != null) {
            AudioBackend.adcFsVoltageRms = Double.parseDouble(adcFsArg);
        }

        if (samplerateArg == null) { log.error("--samplerate required"); System.exit(1); }
        if (fftSizeArg    == null) { log.error("--fft-size required");   System.exit(1); }
        if (widthArg      == null) { log.error("--width required");      System.exit(1); }
        if (heightArg     == null) { log.error("--height required");     System.exit(1); }
        if (amplitudeArg  == null) { log.error("--amplitude required");  System.exit(1); }

        int    sampleRate  = Integer.parseInt(samplerateArg);
        int    bitDepth    = bitsArg      != null ? Integer.parseInt(bitsArg)        : 32;
        double frequency   = freqArg      != null ? Double.parseDouble(freqArg)      : 1000.0;
        double amplitude   = Double.parseDouble(amplitudeArg);
        int    ditherBits  = ditherArg    != null ? Integer.parseInt(ditherArg)      : 0;
        int    duration    = durationArg  != null ? Integer.parseInt(durationArg)    : 60;
        int    fftSize     = Integer.parseInt(fftSizeArg);
        int    harmonics   = harmonicsArg != null ? Integer.parseInt(harmonicsArg)   : 10;
        int    stopAfter  = stopAfterArg != null ? Integer.parseInt(stopAfterArg)  : 4;
        double targetThd  = targetThdArg != null ? Double.parseDouble(targetThdArg) : 0.0;
        int    chartWidth  = Integer.parseInt(widthArg);
        int    chartHeight = Integer.parseInt(heightArg);

        WindowType windowType = windowArg  != null
                ? WindowType.fromString(windowArg)  : WindowType.HANN;
        FftOverlap overlap = overlapArg != null
                ? FftOverlap.fromString(overlapArg)    : FftOverlap.PCT_0;

        double snrFreqMin = distMinArg != null ? Double.parseDouble(distMinArg) : 0.0;
        double snrFreqMax = distMaxArg != null ? Double.parseDouble(distMaxArg) : sampleRate / 2.0;

        // Fund reference: explicit --fund-v / --fund-dbv override only.  When
        // absent we leave fundRefDbV = NaN and let applyDefaultDbvScaling()
        // fall back to the calibrated ADC FS voltage AFTER each iteration's
        // measurement — using --amplitude as a default would be wrong when
        // the DAC drives a DUT/attenuator/filter whose output level differs
        // from the generator amplitude.
        double fundRefDbV;
        if (fundVArg != null) {
            fundRefDbV = 20.0 * Math.log10(Double.parseDouble(fundVArg));
        } else if (fundDbVArg != null) {
            fundRefDbV = Double.parseDouble(fundDbVArg);
        } else {
            fundRefDbV = Double.NaN;
        }

        if (!isValidSampleRate(sampleRate)) {
            log.error("--samplerate must be one of the supported rates");
            System.exit(1);
        }
        if (Integer.bitCount(fftSize) != 1) {
            log.error("--fft-size must be a power of 2");
            System.exit(1);
        }
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32");
            System.exit(1);
        }

        // --- Snap fundamental to nearest exact FFT bin -----------------------
        // bin = round(f · N / Fs); f_aligned = bin · Fs / N
        // Coherent FFT averaging requires the fundamental to land on an integer
        // bin (no leakage into neighbours). Example: f=1000 Hz, Fs=384 kHz,
        // N=2^21 → bin=5461, f_aligned ≈ 999.93896 Hz.
        double freqRequested = frequency;
        long   alignedBin    = Math.round(frequency * fftSize / (double) sampleRate);
        if (alignedBin < 1) alignedBin = 1;
        frequency = alignedBin * sampleRate / (double) fftSize;
        log.info("Frequency snap: requested {} Hz → bin {} → aligned {} Hz (Δ={} Hz, bin width={} Hz)",
                String.format(Locale.US, "%.6f", freqRequested),
                alignedBin,
                String.format(Locale.US, "%.10f", frequency),
                String.format(Locale.US, "%+.6f", frequency - freqRequested),
                String.format(Locale.US, "%.6f", sampleRate / (double) fftSize));

        // --- Device selection ------------------------------------------------
        logProviders();
        DeviceRef outDevice = selectMixerByFlag(args, "--out-device", true);
        DeviceRef inDevice  = selectMixerByFlag(args, "--in-device",  false);
        if (outDevice == null) { log.error("No output device. Use --out-device <index>."); System.exit(1); }
        if (inDevice  == null) { log.error("No input device. Use --in-device <index>.");  System.exit(1); }

        log.info("=== Iterative Harmonic Compensation Workflow ===");
        log.info("Out device : {}", outDevice.name());
        log.info("In device  : {}", inDevice.name());
        log.info("Sample rate: {} Hz",    sampleRate);
        log.info("Bits       : {}",        bitDepth);
        log.info("Frequency  : {} Hz",    frequency);
        log.info("Amplitude  : {} V RMS", amplitude);
        log.info("Duration   : {} s/iter", duration);
        log.info("FFT size   : {}",        fftSize);
        log.info("Harmonics  : {}",        harmonics);
        log.info("Window     : {}",        windowType);
        log.info("Overlap    : {}",        overlap.label);
        log.info("Dist range : {}-{} Hz",  snrFreqMin, snrFreqMax);
        log.info("Coherent   : {}",        coherent);
        log.info("Accumulate : {}",        accumulate);
        log.info("Stop after : {} consecutive THD increases", stopAfter);
        if (targetThd > 0) log.info("Target THD : {} %", String.format(Locale.US, "%.8f", targetThd));
        log.info("Comp. gate : harmonics quieter than noise floor + {} dB are skipped",
                String.format(Locale.US, "%.1f", compSnrMargin));
        log.info("Comp. step : {} (LMS μ; 1.0 = full residual per iteration, <1 damps oscillations)",
                String.format(Locale.US, "%.3f", compStep));
        if (filterCalArg != null) log.info("Filter cal : {}", filterCalArg);

        // Load filter calibration once — applied to every iteration's FFT result
        // so THD and harmonic amplitudes reflect the input-domain spectrum that
        // drives the compensation decisions.
        FilterCalibration filterCal = filterCalArg != null
                ? loadFilterCalibrationCsv(filterCalArg)
                : null;

        // One timestamp shared by every file produced in this workflow run
        String wfTs = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        log.info("Workflow timestamp: {}", wfTs);

        // If the fundamental exclusion zone is wider than this, the recording
        // contained a signal interruption and the iteration must be repeated.
        final double MAX_FUND_EXCLUSION_HZ = 500.0;
        // If |measured − generated| / generated exceeds this, the capture had
        // a clock glitch (e.g. dropped samples shifting the apparent frequency)
        // and the iteration must be repeated.
        final double MAX_FREQ_DRIFT_PPM = 3.0;

        // Harmonic correction accumulators — only used when --accumulate is set
        double[] accCorrRe = accumulate ? new double[harmonics] : null;
        double[] accCorrIm = accumulate ? new double[harmonics] : null;
        double[] hFreqs    = accumulate ? new double[harmonics] : null;

        List<Double> thdHistory = new ArrayList<>();
        FftAnalyzer.Result lastResult;
        FftAnalyzer.Result bestResult = null;
        double bestThd = Double.MAX_VALUE;
        double[] bestAccRe = accumulate ? new double[harmonics] : null;
        double[] bestAccIm = accumulate ? new double[harmonics] : null;
        double[] bestHFreqs = accumulate ? new double[harmonics] : null;
        // Snapshot of the accumulator as applied by the generator at the start
        // of the iteration that produced the best THD (i.e. the compensation
        // actually in effect when the best measurement was captured).
        double[] bestAppliedRe = accumulate ? new double[harmonics] : null;
        double[] bestAppliedIm = accumulate ? new double[harmonics] : null;

        FftAnalyzer fftAnalyzer = new FftAnalyzer();

        // --- Iteration 0: uncompensated sine ---------------------------------
        log.info("--- Iteration 0: uncompensated sine ---");
        FftAnalyzer.Result result0;
        double[] overlayFreqs0 = null;
        double[] overlayDbFs0  = null;
        double[] preCorrFreqs0 = null;
        double[] preCorrDbFs0  = null;
        for (int retry = 0; ; retry++) {
            float[] s0 = captureWithGenerator(
                    new SignalGenerator(GenSignalForm.SINE, frequency, sampleRate, amplitude),
                    outDevice, inDevice, sampleRate, bitDepth, ditherBits, duration);
            try {
                result0 = fftAnalyzer.analyze(
                        s0, sampleRate, fftSize, harmonics,
                        windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbV,
                        filterCal == null, frequency);
            } catch (IllegalStateException e) {
                log.warn("Iteration 0 retry {}: {} — signal interrupted, retrying",
                        retry + 1, e.getMessage());
                continue;
            }
            s0 = null;
            if (filterCal != null) {
                double[][] ov = computeCalOverlay(filterCal, result0);
                if (ov != null) {
                    overlayFreqs0 = ov[0];
                    overlayDbFs0  = ov[1];
                }
                double[][] pp = capturePreCorrectionPeaks(result0);
                preCorrFreqs0 = pp[0];
                preCorrDbFs0  = pp[1];
                applyFilterCompensationInPlace(result0, filterCal, calNoise);
            }
            applyDefaultDbvScaling(result0);
            if (result0.fundamentalDynExclusionHz > MAX_FUND_EXCLUSION_HZ) {
                log.warn("Iteration 0 retry {}: fundamental exclusion {} Hz > {} Hz — signal interrupted, retrying",
                        retry + 1,
                        String.format(Locale.US, "%.1f", result0.fundamentalDynExclusionHz),
                        String.format(Locale.US, "%.1f", MAX_FUND_EXCLUSION_HZ));
                continue;
            }
            double ppm0 = 1e6 * (result0.fundamentalHzRefined - frequency) / frequency;
            if (Math.abs(ppm0) > MAX_FREQ_DRIFT_PPM) {
                log.warn("Iteration 0 retry {}: frequency drift {} ppm > ±{} ppm (gen={} Hz, meas={} Hz) — clock glitch, retrying",
                        retry + 1,
                        String.format(Locale.US, "%+.2f", ppm0),
                        String.format(Locale.US, "%.2f", MAX_FREQ_DRIFT_PPM),
                        String.format(Locale.US, "%.6f", frequency),
                        String.format(Locale.US, "%.6f", result0.fundamentalHzRefined));
                continue;
            }
            break;
        }

        // Derive ADC full-scale voltage from the calibrated DAC amplitude and the
        // measured fundamental dBFS.  Generator output = amplitude V RMS; FFT sees
        // the same signal at fundamentalDbFs.  ADC FS = amplitude / 10^(dBFS/20).
        double signalRatio = Math.pow(10.0, result0.fundamentalDbFs / 20.0);
        double adcFsVrms   = amplitude / signalRatio;
        log.info("ADC FS derived: {} V RMS  ({} V peak)  — fundamental at {} dBFS, generator {} V RMS",
                String.format(Locale.US, "%.4f", adcFsVrms),
                String.format(Locale.US, "%.4f", adcFsVrms * Math.sqrt(2.0)),
                String.format(Locale.US, "%.2f", result0.fundamentalDbFs),
                String.format(Locale.US, "%.4f", amplitude));

        if (accumulate) {
            accumulateHarmonics(result0, accCorrRe, accCorrIm, hFreqs,
                    compSnrMargin, compStep);
        }

        fftAnalyzer.exportChart(result0, chartWidth, chartHeight, "results",
                "Iteration 0 (uncompensated)", false,
                "fft_chart_iter0_" + wfTs, frequency,
                overlayFreqs0, overlayDbFs0, preCorrFreqs0, preCorrDbFs0);
        logClockMismatch(0, frequency, result0.fundamentalHzRefined, sampleRate);

        thdHistory.add(result0.thdPct);
        lastResult = result0;
        bestResult = result0;
        bestThd = result0.thdPct;
        int bestIter = 0;
        if (accumulate) {
            System.arraycopy(accCorrRe, 0, bestAccRe, 0, harmonics);
            System.arraycopy(accCorrIm, 0, bestAccIm, 0, harmonics);
            System.arraycopy(hFreqs, 0, bestHFreqs, 0, harmonics);
        }
        log.info("Iteration 0 THD: {} %", String.format(Locale.US, "%.8f", result0.thdPct));

        // Per-iteration snapshots so the user can pick which one to save on
        // manual stop. For accumulate mode, applied[Re|Im] holds the
        // compensation in effect AT THE START of that iteration (zero for iter 0).
        List<IterSnapshot> snapshots = new ArrayList<>();
        snapshots.add(new IterSnapshot(0, result0,
                accumulate ? new double[harmonics] : null,
                accumulate ? new double[harmonics] : null,
                accumulate ? Arrays.copyOf(hFreqs, harmonics) : null));

        // --- Keyboard interrupt watcher --------------------------------------
        // Press Enter (or any key + Enter) on stdin to stop after current iteration.
        final AtomicBoolean stopRequested =
                new AtomicBoolean(false);
        Thread keyboardThread = new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(System.in));
                while (!stopRequested.get()) {
                    if (System.in.available() > 0) {
                        r.readLine();
                        stopRequested.set(true);
                        log.info("Keyboard stop requested — finishing current iteration then saving best result.");
                        return;
                    }
                    Thread.sleep(200);
                }
            } catch (Exception ignored) {}
        }, "keyboard-stop");
        keyboardThread.setDaemon(true);
        keyboardThread.start();
        log.info(">>> Press Enter at any time to stop the iterative loop and save the best result. <<<");

        // --- Iterative compensation loop -------------------------------------
        for (int iter = 1; ; iter++) {
            if (stopRequested.get()) {
                log.info("Stopping on keyboard request after {} iteration(s).", iter - 1);
                break;
            }
            log.info("--- Iteration {}: compensated sine{} ---", iter,
                    accumulate ? " (accumulated corrections)" : "");

            FftAnalyzer.Result result;
            double[] overlayFreqs = null;
            double[] overlayDbFs  = null;
            double[] preCorrFreqs = null;
            double[] preCorrDbFs  = null;
            for (int retry = 0; ; retry++) {
                SignalGenerator gen = accumulate
                        ? buildAccumulatedGenerator(frequency, sampleRate, amplitude,
                                accCorrRe, accCorrIm, hFreqs)
                        : new SignalGenerator(frequency, sampleRate, amplitude, lastResult);
                float[] s = captureWithGenerator(gen,
                        outDevice, inDevice, sampleRate, bitDepth, ditherBits, duration);
                try {
                    result = fftAnalyzer.analyze(
                            s, sampleRate, fftSize, harmonics,
                            windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbV,
                            filterCal == null, frequency);
                } catch (IllegalStateException e) {
                    log.warn("Iteration {} retry {}: {} — signal interrupted, retrying",
                            iter, retry + 1, e.getMessage());
                    continue;
                }
                s = null;
                if (filterCal != null) {
                    double[][] ov = computeCalOverlay(filterCal, result);
                    if (ov != null) {
                        overlayFreqs = ov[0];
                        overlayDbFs  = ov[1];
                    }
                    double[][] pp = capturePreCorrectionPeaks(result);
                    preCorrFreqs = pp[0];
                    preCorrDbFs  = pp[1];
                    applyFilterCompensationInPlace(result, filterCal, calNoise);
                }
                applyDefaultDbvScaling(result);
                if (result.fundamentalDynExclusionHz > MAX_FUND_EXCLUSION_HZ) {
                    log.warn("Iteration {} retry {}: fundamental exclusion {} Hz > {} Hz — signal interrupted, retrying",
                            iter, retry + 1,
                            String.format(Locale.US, "%.1f", result.fundamentalDynExclusionHz),
                            String.format(Locale.US, "%.1f", MAX_FUND_EXCLUSION_HZ));
                    continue;
                }
                double ppm = 1e6 * (result.fundamentalHzRefined - frequency) / frequency;
                if (Math.abs(ppm) > MAX_FREQ_DRIFT_PPM) {
                    log.warn("Iteration {} retry {}: frequency drift {} ppm > ±{} ppm (gen={} Hz, meas={} Hz) — clock glitch, retrying",
                            iter, retry + 1,
                            String.format(Locale.US, "%+.2f", ppm),
                            String.format(Locale.US, "%.2f", MAX_FREQ_DRIFT_PPM),
                            String.format(Locale.US, "%.6f", frequency),
                            String.format(Locale.US, "%.6f", result.fundamentalHzRefined));
                    continue;
                }
                break;
            }

            // Snapshot the accumulator state AS APPLIED to the generator for this
            // iteration (i.e. before this iteration's residuals are added).
            double[] appliedRe = null, appliedIm = null;
            if (accumulate) {
                appliedRe = Arrays.copyOf(accCorrRe, harmonics);
                appliedIm = Arrays.copyOf(accCorrIm, harmonics);
            }

            if (accumulate) {
                accumulateHarmonics(result, accCorrRe, accCorrIm, hFreqs,
                        compSnrMargin, compStep);
            }

            fftAnalyzer.exportChart(result, chartWidth, chartHeight, "results",
                    String.format("Iteration %d", iter), false,
                    String.format("fft_chart_iter%d_", iter) + wfTs, frequency,
                    overlayFreqs, overlayDbFs, preCorrFreqs, preCorrDbFs);
            logClockMismatch(iter, frequency, result.fundamentalHzRefined, sampleRate);

            thdHistory.add(result.thdPct);
            lastResult = result;
            if (result.thdPct < bestThd) {
                bestThd = result.thdPct;
                bestResult = result;
                bestIter = iter;
                if (accumulate) {
                    System.arraycopy(accCorrRe, 0, bestAccRe, 0, harmonics);
                    System.arraycopy(accCorrIm, 0, bestAccIm, 0, harmonics);
                    System.arraycopy(hFreqs, 0, bestHFreqs, 0, harmonics);
                    System.arraycopy(appliedRe, 0, bestAppliedRe, 0, harmonics);
                    System.arraycopy(appliedIm, 0, bestAppliedIm, 0, harmonics);
                }
            }
            log.info("Iteration {} THD: {} %",
                    iter, String.format(Locale.US, "%.8f", result.thdPct));

            // Record snapshot for this iteration so it can be picked manually.
            snapshots.add(new IterSnapshot(iter, result,
                    appliedRe == null ? null : Arrays.copyOf(appliedRe, harmonics),
                    appliedIm == null ? null : Arrays.copyOf(appliedIm, harmonics),
                    accumulate ? Arrays.copyOf(hFreqs, harmonics) : null));

            if (targetThd > 0 && result.thdPct <= targetThd) {
                log.info("THD {} % reached target {} % — stopping.",
                        String.format(Locale.US, "%.8f", result.thdPct),
                        String.format(Locale.US, "%.8f", targetThd));
                break;
            }

            // Stop if THD has grown for N consecutive iterations
            int n = thdHistory.size();
            if (n >= stopAfter + 1) {
                boolean growing = true;
                for (int g = 0; g < stopAfter && growing; g++) {
                    growing = thdHistory.get(n - 1 - g) > thdHistory.get(n - 2 - g);
                }
                if (growing) {
                    StringBuilder sb = new StringBuilder("THD growing: ");
                    for (int g = stopAfter; g >= 0; g--) {
                        if (g < stopAfter) sb.append(" → ");
                        sb.append(String.format(Locale.US, "%.8f", thdHistory.get(n - 1 - g)));
                    }
                    sb.append(" — stopping.");
                    log.info(sb.toString());
                    break;
                }
            }
        }

        // --- Manual pick on keyboard stop ------------------------------------
        // If the user stopped the loop manually they may want to save a
        // specific iteration's compensation rather than the best-by-THD one.
        if (stopRequested.get() && snapshots.size() > 1) {
            log.info("=== Pick iteration to save ===");
            log.info("idx |  THD %       | applied?");
            for (IterSnapshot snap : snapshots) {
                String mark = snap.getIter() == bestIter ? "  <-- BEST" : "";
                log.info("{} | {} | {}{}",
                        String.format("%3d", snap.getIter()),
                        String.format(Locale.US, "%12.8f", snap.getResult().thdPct),
                        snap.getAppliedRe() != null ? "yes" : "no",
                        mark);
            }
            log.info("Enter iteration number to save (blank = keep BEST iter {}): ", bestIter);
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(System.in));
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    int pick = Integer.parseInt(line.trim());
                    IterSnapshot chosen = null;
                    for (IterSnapshot snap : snapshots) {
                        if (snap.getIter() == pick) { chosen = snap; break; }
                    }
                    if (chosen == null) {
                        log.warn("Iteration {} not found in snapshots — keeping BEST iter {}.",
                                pick, bestIter);
                    } else {
                        bestIter   = chosen.getIter();
                        bestResult = chosen.getResult();
                        bestThd    = chosen.getResult().thdPct;
                        if (accumulate) {
                            System.arraycopy(chosen.getAppliedRe(), 0, bestAppliedRe, 0, harmonics);
                            System.arraycopy(chosen.getAppliedIm(), 0, bestAppliedIm, 0, harmonics);
                            System.arraycopy(chosen.getHFreqs(),    0, bestHFreqs,    0, harmonics);
                        }
                        log.info("User picked iteration {} (THD {} %).",
                                bestIter, String.format(Locale.US, "%.8f", bestThd));
                    }
                } else {
                    log.info("Keeping BEST iteration {} (THD {} %).",
                            bestIter, String.format(Locale.US, "%.8f", bestThd));
                }
            } catch (Exception e) {
                log.warn("Could not read iteration choice ({}). Keeping BEST iter {}.",
                        e.getMessage(), bestIter);
            }
        }

        // --- Save best harmonics CSV -----------------------------------------
        String csvPath = fftAnalyzer.exportHarmonicsCsv(bestResult, "results",
                "fft_harmonics_" + wfTs);
        log.info("Best-iteration residual harmonics CSV (iter {}): {}", bestIter, csvPath);

        // --- Save applied compensation CSV (only in accumulate mode) ---------
        if (accumulate) {
            String appliedCsv = exportAppliedCompensationCsv(
                    bestAppliedRe, bestAppliedIm, bestHFreqs,
                    bestResult.fundamentalHzRefined, bestResult.fundamentalDbFs,
                    sampleRate, bitDepth, amplitude,
                    "results", "applied_compensation_" + wfTs);
            log.info("Applied compensation for best iter {}: {}", bestIter, appliedCsv);
        }

        // --- Summary ---------------------------------------------------------
        log.info("=== THD history ===");
        for (int i = 0; i < thdHistory.size(); i++) {
            double thd = thdHistory.get(i);
            log.info("  Iter {}: {} %", i, String.format(Locale.US, "%.8f", thd));
        }
        log.info("Best: iteration {} with THD {} %",
                bestIter, String.format(Locale.US, "%.8f", bestThd));
    }

    /**
     * Extracts harmonic corrections from an FFT result and accumulates them
     * into the provided arrays as complex numbers in the DAC output reference frame.
     * Array index maps to harmonic order: index 0 → H2, index 1 → H3, etc.
     */
    /**
     * Updates {@code accRe}/{@code accIm} with this iteration's COMPLEX residual
     * per harmonic.  Pure vector update (no sign-flip heuristic) — the phase
     * built into the residual phasor points the accumulator in the correct
     * direction to cancel the DAC's intrinsic harmonic at that frequency, so
     * adding μ·residual each iteration converges geometrically.
     *
     * <p>Residual phasor for harmonic h is built from:
     * <ul>
     *   <li>amplitude = {@code r.harmonicPct[h]/100} (amplitude relative to fund)
     *   <li>phase     = {@code phiH + freqH·delayRadPerHz}, which removes the
     *       capture-delay linear-phase ramp estimated from the fundamental.
     *       After cal correction the remaining phase is the DAC-side intrinsic
     *       harmonic phase θ_h — exactly what the generator's
     *       {@code s -= amp·cos(2π·h·f·t + phase)} kernel needs.
     * </ul>
     *
     * <p>Only harmonics whose measured dBFS is at least {@code snrMarginDb}
     * above the average noise floor are touched — below-noise bins would
     * random-walk the accumulator and eventually diverge.
     *
     * @param snrMarginDb skip harmonics quieter than avgNoiseFloor + this many dB.
     * @param step        LMS step size μ (0 &lt; μ ≤ 1 typical; 1 = full residual
     *                    per iteration, &lt; 1 damps oscillations).
     */
    private void accumulateHarmonics(FftAnalyzer.Result r,
            double[] accRe, double[] accIm, double[] hFreqs,
            double snrMarginDb, double step) {
        double phi1   = Math.atan2(r.im[r.fundamentalBin], r.re[r.fundamentalBin]);
        double omegaD = -(phi1 + Math.PI / 2.0);
        double delayRadPerHz = omegaD / r.fundamentalHzRefined;

        int count = Math.min(r.harmonicCount, accRe.length);
        double skipBelowDbFs = r.avgNoiseFloorDbFs + snrMarginDb;

        for (int h = 0; h < count; h++) {
            int bin = r.harmonicBins[h];
            if (bin <= 0) continue;
            if (r.harmonicDbFs[h] < skipBelowDbFs) {
                log.info("H{} skipped ({} dBFS < noise floor {} dBFS + {} dB margin) — near noise",
                        h + 2,
                        String.format(Locale.US, "%.2f", r.harmonicDbFs[h]),
                        String.format(Locale.US, "%.2f", r.avgNoiseFloorDbFs),
                        String.format(Locale.US, "%.1f", snrMarginDb));
                continue;
            }
            double ampRatio = r.harmonicPct[h] / 100.0;
            double phiH     = Math.atan2(r.im[bin], r.re[bin]);
            double freqHz   = r.harmonicHz[h];
            double phiInit  = phiH + freqHz * delayRadPerHz;
            accRe[h]  += step * ampRatio * Math.cos(phiInit);
            accIm[h]  += step * ampRatio * Math.sin(phiInit);
            hFreqs[h]  = r.harmonicHz[h];
        }
    }

    /**
     * Builds a SignalGenerator from accumulated complex harmonic corrections.
     * Converts each (re, im) pair to (amplitude, phase) and passes them
     * to the array-based SignalGenerator constructor.
     */
    private SignalGenerator buildAccumulatedGenerator(
            double frequency, int sampleRate, double amplitude,
            double[] accRe, double[] accIm, double[] hFreqs) {
        int valid = 0;
        for (int h = 0; h < accRe.length; h++) {
            if (accRe[h] != 0.0 || accIm[h] != 0.0) valid++;
        }
        double[] ampRatios = new double[valid];
        double[] phiInits  = new double[valid];
        double[] freqs     = new double[valid];
        int i = 0;
        for (int h = 0; h < accRe.length; h++) {
            double re = accRe[h], im = accIm[h];
            if (re == 0.0 && im == 0.0) continue;
            ampRatios[i] = Math.sqrt(re * re + im * im);
            phiInits[i]  = Math.atan2(im, re);
            freqs[i]     = hFreqs[h];
            i++;
        }
        return new SignalGenerator(frequency, sampleRate, amplitude,
                ampRatios, phiInits, freqs);
    }

    /**
     * Writes an accumulated compensation (re/im corrections as applied to the
     * generator) to a CSV file compatible with {@link FftAnalyzer#loadHarmonics}.
     * Amplitude is stored as a ratio to the fundamental (%); phase is the
     * DAC-reference compensation angle derived from atan2(im, re).
     *
     * Generator parameters (sample rate, bit depth, amplitude, exact frequency)
     * are written as {@code #}-prefixed comment lines at the top so that
     * {@code --signal sine_compensated} can pick them up automatically.
     */
    private String exportAppliedCompensationCsv(
            double[] accRe, double[] accIm, double[] hFreqs,
            double fundamentalHz, double fundamentalDbFs,
            int sampleRate, int bitDepth, double amplitudeVRms,
            String directory, String filePrefix) throws IOException {
        File outFile = new File(directory, filePrefix + ".csv");
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(outFile)))) {
            // Generator-parameter metadata (parsed by SignalGenerator.readAppliedCompensationMetadata)
            pw.printf(Locale.US, "# sample_rate_hz=%d%n", sampleRate);
            pw.printf(Locale.US, "# bit_depth=%d%n",      bitDepth);
            pw.printf(Locale.US, "# amplitude_vrms=%.10f%n", amplitudeVRms);
            pw.printf(Locale.US, "# frequency_hz=%.10f%n",   fundamentalHz);
            // H1 is written with phase -90 deg (re=0, im=-1) so the CSV loader
            // computes omegaD = 0 and skips delay compensation: the stored re/im
            // of each harmonic are already DAC-frame (delay-corrected) values.
            pw.println("harmonic;frequency_hz;amplitude_dbfs;amplitude_pct;phase_deg;re;im");
            pw.printf(Locale.GERMAN, "1;%.6f;%.4f;100.000000;-90.0000;%.10e;%.10e%n",
                    fundamentalHz, fundamentalDbFs, 0.0, -1.0);
            for (int h = 0; h < accRe.length; h++) {
                double re = accRe[h], im = accIm[h];
                double amp = Math.sqrt(re * re + im * im);
                if (amp == 0.0) continue;
                double phaseDeg = Math.toDegrees(Math.atan2(im, re));
                double ampPct   = amp * 100.0;
                double ampDbFs  = fundamentalDbFs + 20.0 * Math.log10(amp);
                pw.printf(Locale.GERMAN, "%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        h + 2, hFreqs[h], ampDbFs, ampPct, phaseDeg, re, im);
            }
        }
        return outFile.getAbsolutePath();
    }

    /**
     * Reads an applied_compensation CSV into {@link CompCsv}.  Tolerates German
     * (comma) decimals so it consumes files written by {@link #exportAppliedCompensationCsv}.
     */
    private CompCsv readCompensationCsv(String path) throws IOException {
        SignalGenerator.Metadata meta = SignalGenerator.readAppliedCompensationMetadata(path);
        double fundDbFs = Double.NaN;
        double fundHz   = Double.NaN;
        List<CompHarmonic> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
                String[] cols = line.split(";");
                if (cols.length < 7) continue;
                int    h    = Integer.parseInt(cols[0].trim());
                double freq = Double.parseDouble(cols[1].trim().replace(',', '.'));
                double re   = Double.parseDouble(cols[5].trim().replace(',', '.'));
                double im   = Double.parseDouble(cols[6].trim().replace(',', '.'));
                if (h == 1) {
                    fundDbFs = Double.parseDouble(cols[2].trim().replace(',', '.'));
                    fundHz   = freq;
                } else {
                    rows.add(new CompHarmonic(h, freq, re, im));
                }
            }
        }
        return new CompCsv(meta, fundDbFs, fundHz, rows);
    }

    /**
     * Deembeds DAC vs ADC distortion from N ≥ 2 applied_compensation CSVs
     * captured at different ADC fundamental levels (same DAC drive).  For each
     * harmonic h ≥ 2 fits the complex model
     *
     *   r_h(L) = c_0 + Σ_k c_k · L^{p_k(h)}
     *
     * where p_k(h) is the k-th token from {@code --orders} resolved for h
     * (integer literal, or {@code h+offset}/{@code h-offset}).  c_0 is the
     * level-independent DAC-fixed term (written to {@code --gen-out}); the
     * remaining c_k are the level-dependent ADC coefficients (written to
     * {@code --adc-out}).
     *
     * Parameters:
     *   --comp <file>   repeatable; at least 2 CSVs required.
     *                   {@code --comp1}/{@code --comp2} accepted as aliases for
     *                   the first two slots.
     *   --level <dbfs>  repeatable; overrides the h=1 dBFS read from the matching
     *                   {@code --comp}.  {@code --level1}/{@code --level2} accepted.
     *   --orders <list> comma-separated ADC orders (default "h-1").
     *                   Tokens: integer literal ("1", "3"), or "h", "h-1", "h+1"…
     *   --gen-out <csv> applied_compensation-format CSV with D_h = c_0.
     *   --adc-out <csv> multi-order ADC coefficient CSV.
     *
     * Requires N ≥ 1 + |orders| CSVs to solve the system; with more CSVs it
     * becomes overdetermined and is solved via normal-equation least squares.
     */
    private void runDeembedMode(String[] args) throws Exception {
        // Collect CSV paths: --comp repeatable, plus legacy --comp1/--comp2.
        List<String> compPaths = new ArrayList<>(getArgValues(args, "--comp"));
        String comp1Arg = getArgValue(args, "--comp1");
        String comp2Arg = getArgValue(args, "--comp2");
        if (comp1Arg != null) compPaths.add(0, comp1Arg);
        if (comp2Arg != null) compPaths.add(Math.min(1, compPaths.size()), comp2Arg);

        // Collect level overrides in same order; legacy --level1/--level2 prepended.
        List<String> levelArgs = new ArrayList<>(getArgValues(args, "--level"));
        String level1Arg = getArgValue(args, "--level1");
        String level2Arg = getArgValue(args, "--level2");
        if (level1Arg != null) levelArgs.add(0, level1Arg);
        if (level2Arg != null) levelArgs.add(Math.min(1, levelArgs.size()), level2Arg);

        String ordersArg = getArgValue(args, "--orders");
        String genOutArg = getArgValue(args, "--gen-out");
        String adcOutArg = getArgValue(args, "--adc-out");

        if (compPaths.size() < 2 || genOutArg == null || adcOutArg == null) {
            log.error("--deembed requires --gen-out, --adc-out and at least 2 --comp <file> CSVs");
            System.exit(1);
        }

        List<OrderToken> orders = parseOrders(ordersArg != null ? ordersArg : "h-1");
        int nCsv      = compPaths.size();
        int nCoeffs   = 1 + orders.size();   // c_0 + one per ADC order
        if (nCsv < nCoeffs) {
            log.error("Need at least {} CSVs for {} coefficients (c_0 + orders={}), got {}",
                    nCoeffs, nCoeffs, orders, nCsv);
            System.exit(1);
        }

        // Load all CSVs and resolve per-CSV levels.
        CompCsv[] csvs  = new CompCsv[nCsv];
        double[]  dbFs  = new double[nCsv];
        double[]  L     = new double[nCsv];
        for (int i = 0; i < nCsv; i++) {
            csvs[i] = readCompensationCsv(compPaths.get(i));
            dbFs[i] = i < levelArgs.size() ? Double.parseDouble(levelArgs.get(i))
                                           : csvs[i].getFundamentalDbFs();
            L[i]    = Math.pow(10.0, dbFs[i] / 20.0);
        }

        log.info("Mode      : deembed DAC ↔ ADC (N={}, orders=[{}])", nCsv, renderOrders(orders));
        for (int i = 0; i < nCsv; i++) {
            log.info("CSV {} : {}  (L = {} dBFS = {})", i + 1, compPaths.get(i),
                    String.format(Locale.US, "%.4f", dbFs[i]),
                    String.format(Locale.US, "%.6f", L[i]));
        }

        // Need at least 2 distinct L's.
        double minL = L[0], maxL = L[0];
        for (double v : L) { if (v < minL) minL = v; if (v > maxL) maxL = v; }
        if (maxL - minL < 1e-6) {
            log.error("All fundamental levels are identical — deembedding requires distinct levels");
            System.exit(1);
        }

        // Index each CSV's harmonics by harmonic number for row matching.
        @SuppressWarnings("unchecked")
        Map<Integer, CompHarmonic>[] maps = new HashMap[nCsv];
        for (int i = 0; i < nCsv; i++) {
            maps[i] = new HashMap<>();
            for (CompHarmonic h : csvs[i].getHarmonics()) maps[i].put(h.getH(), h);
        }

        List<CompHarmonic>  dacRows     = new ArrayList<>();   // c_0 per harmonic
        List<double[]>      adcCoeffs   = new ArrayList<>();   // 2·|orders| entries
        List<Integer>       adcHList    = new ArrayList<>();
        List<Double>        adcFreqList = new ArrayList<>();

        for (CompHarmonic h1 : csvs[0].getHarmonics()) {
            int h = h1.getH();
            // Must be present in every CSV.
            boolean present = true;
            for (int i = 1; i < nCsv; i++) if (!maps[i].containsKey(h)) { present = false; break; }
            if (!present) {
                log.warn("H{} missing in at least one CSV — skipped", h);
                continue;
            }

            // Design matrix: rows = CSVs, cols = [1, L^p_1(h), L^p_2(h), ...].
            double[][] A     = new double[nCsv][nCoeffs];
            double[]   yRe   = new double[nCsv];
            double[]   yIm   = new double[nCsv];
            for (int i = 0; i < nCsv; i++) {
                CompHarmonic row = maps[i].get(h);
                A[i][0] = 1.0;
                for (int k = 0; k < orders.size(); k++) {
                    A[i][k + 1] = Math.pow(L[i], orders.get(k).resolve(h));
                }
                yRe[i] = row.getRe();
                yIm[i] = row.getIm();
            }
            double[] cRe = solveLeastSquares(A, yRe);
            double[] cIm = solveLeastSquares(A, yIm);

            // c_0 → DAC row.  c_1..c_K → ADC coefficients.
            dacRows.add(new CompHarmonic(h, h1.getFreqHz(), cRe[0], cIm[0]));
            double[] ab = new double[2 * orders.size()];
            for (int k = 0; k < orders.size(); k++) {
                ab[2 * k]     = cRe[k + 1];
                ab[2 * k + 1] = cIm[k + 1];
            }
            adcCoeffs.add(ab);
            adcHList.add(h);
            adcFreqList.add(h1.getFreqHz());

            // Per-CSV residual: resid_i = y_i − A_i·c.  In the rotated CSV
            // frame the fundamental is unit-magnitude by construction
            // (re=0, im=-1), so |resid| is already dBc vs the fundamental.
            // Peak across CSVs = worst-case fit error; RMS = overall fit quality.
            double sumResSq = 0.0;
            double peakResMag = 0.0;
            for (int i = 0; i < nCsv; i++) {
                double predRe = 0.0, predIm = 0.0;
                for (int k = 0; k < nCoeffs; k++) {
                    predRe += A[i][k] * cRe[k];
                    predIm += A[i][k] * cIm[k];
                }
                double rRe = yRe[i] - predRe;
                double rIm = yIm[i] - predIm;
                double rMag = Math.hypot(rRe, rIm);
                sumResSq += rMag * rMag;
                if (rMag > peakResMag) peakResMag = rMag;
            }
            double rmsRes = Math.sqrt(sumResSq / nCsv);

            // Log: D magnitude + each order's magnitude at L1 + fit residual.
            // All ratios are vs the fundamental, which is unit-magnitude in
            // the rotated CSV frame — so we just report the bare magnitudes.
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "|D|/|r_1|=%.3f", Math.hypot(cRe[0], cIm[0])));
            for (int k = 0; k < orders.size(); k++) {
                double mag = Math.hypot(cRe[k + 1], cIm[k + 1])
                        * Math.pow(L[0], orders.get(k).resolve(h));
                sb.append(String.format(Locale.US, "  |c_%s·L^%d|/|r_1|=%.3f",
                        orders.get(k).getLabel(), orders.get(k).resolve(h), mag));
            }
            double rmsResDb  = 20.0 * Math.log10(Math.max(1e-300, rmsRes));
            double peakResDb = 20.0 * Math.log10(Math.max(1e-300, peakResMag));
            sb.append(String.format(Locale.US, "  resid_rms=%.1fdBc  resid_peak=%.1fdBc",
                    rmsResDb, peakResDb));
            log.info("H{} : {}", h, sb);
        }

        writeDacCompensationCsv(dacRows, csvs[0], genOutArg);
        writeAdcCorrectionCsv(adcHList, adcFreqList, adcCoeffs, orders, csvs[0],
                adcOutArg, dbFs);
        log.info("Wrote DAC compensation : {}", genOutArg);
        log.info("Wrote ADC correction   : {}", adcOutArg);
    }

    /** Parses a comma-separated list of order tokens (integer literal or "h±k"). */
    private List<OrderToken> parseOrders(String spec) {
        List<OrderToken> out = new ArrayList<>();
        for (String tok : spec.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            if (t.equalsIgnoreCase("h")) {
                out.add(new OrderToken(true, 0, "h"));
            } else if (t.toLowerCase().startsWith("h+") || t.toLowerCase().startsWith("h-")) {
                int off = Integer.parseInt(t.substring(1));   // "+1", "-2"
                out.add(new OrderToken(true, off, t));
            } else {
                out.add(new OrderToken(false, Integer.parseInt(t), t));
            }
        }
        return out;
    }

    private String renderOrders(List<OrderToken> orders) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < orders.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(orders.get(i).getLabel());
        }
        return sb.toString();
    }

    /**
     * Solves {@code A·c = y} in the least-squares sense via the normal
     * equations {@code (AᵀA)·c = Aᵀy}.  System is small (K ≤ ~5); Gaussian
     * elimination with partial pivoting is adequate.
     */
    private double[] solveLeastSquares(double[][] a, double[] y) {
        int    n   = a.length;
        int    k   = a[0].length;
        double[][] ata = new double[k][k];
        double[]   aty = new double[k];
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < k; p++) {
                aty[p] += a[i][p] * y[i];
                for (int q = 0; q < k; q++) ata[p][q] += a[i][p] * a[i][q];
            }
        }
        // Gaussian elimination with partial pivoting.
        for (int i = 0; i < k; i++) {
            int    piv  = i;
            double maxv = Math.abs(ata[i][i]);
            for (int j = i + 1; j < k; j++) {
                if (Math.abs(ata[j][i]) > maxv) { maxv = Math.abs(ata[j][i]); piv = j; }
            }
            if (piv != i) {
                double[] tmp = ata[i]; ata[i] = ata[piv]; ata[piv] = tmp;
                double bt = aty[i]; aty[i] = aty[piv]; aty[piv] = bt;
            }
            double pv = ata[i][i];
            if (Math.abs(pv) < 1e-30) throw new IllegalStateException("Singular matrix in deembed least-squares");
            for (int j = i + 1; j < k; j++) {
                double f = ata[j][i] / pv;
                for (int p = i; p < k; p++) ata[j][p] -= f * ata[i][p];
                aty[j] -= f * aty[i];
            }
        }
        double[] x = new double[k];
        for (int i = k - 1; i >= 0; i--) {
            double s = aty[i];
            for (int j = i + 1; j < k; j++) s -= ata[i][j] * x[j];
            x[i] = s / ata[i][i];
        }
        return x;
    }

    /**
     * Writes DAC-only compensation in the same format as
     * {@link #exportAppliedCompensationCsv} so {@code --signal sine_compensated}
     * can load it directly.  Metadata (sample rate, bit depth, amplitude, freq)
     * is copied from {@code source}.
     */
    private void writeDacCompensationCsv(List<CompHarmonic> rows, CompCsv source,
                                                String path) throws IOException {
        SignalGenerator.Metadata meta = source.getMeta();
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(path)))) {
            if (meta != null) {
                pw.printf(Locale.US, "# sample_rate_hz=%d%n",   meta.getSampleRateHz());
                pw.printf(Locale.US, "# bit_depth=%d%n",        meta.getBitDepth());
                pw.printf(Locale.US, "# amplitude_vrms=%.10f%n", meta.getAmplitudeVRms());
                pw.printf(Locale.US, "# frequency_hz=%.10f%n",   meta.getFrequencyHz());
            }
            pw.println("harmonic;frequency_hz;amplitude_dbfs;amplitude_pct;phase_deg;re;im");
            // H1: re=0, im=−1 so SignalGenerator computes ωD = 0 (no delay rewinding;
            // re/im are emitted as written).
            pw.printf(Locale.GERMAN, "1;%.6f;%.4f;100.000000;-90.0000;%.10e;%.10e%n",
                    source.getFundamentalHz(), source.getFundamentalDbFs(), 0.0, -1.0);
            for (CompHarmonic r : rows) {
                double amp = Math.hypot(r.getRe(), r.getIm());
                if (amp == 0.0) continue;
                double phaseDeg = Math.toDegrees(Math.atan2(r.getIm(), r.getRe()));
                double ampPct   = amp * 100.0;
                double ampDbFs  = source.getFundamentalDbFs() + 20.0 * Math.log10(amp);
                pw.printf(Locale.GERMAN, "%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        r.getH(), r.getFreqHz(), ampDbFs, ampPct, phaseDeg, r.getRe(), r.getIm());
            }
        }
    }

    /**
     * Writes ADC harmonic coefficients (multi-order).  Format is intentionally
     * distinct from applied_compensation (kind=adc_correction) so the apply path
     * dispatches to a dedicated parser.  Predicted ADC contribution to harmonic
     * h at fundamental level L is {@code Σ_k (a_k_re + j·a_k_im) · L^{p_k(h)}}
     * in the rotated frame (fund → −j).
     */
    private void writeAdcCorrectionCsv(List<Integer> hList, List<Double> freqList,
                                              List<double[]> coeffs, List<OrderToken> orders,
                                              CompCsv source, String path,
                                              double[] levelsDbFs) throws IOException {
        SignalGenerator.Metadata meta = source.getMeta();
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(path)))) {
            pw.println("# kind=adc_correction");
            if (meta != null) {
                pw.printf(Locale.US, "# sample_rate_hz=%d%n", meta.getSampleRateHz());
                pw.printf(Locale.US, "# bit_depth=%d%n",      meta.getBitDepth());
            }
            pw.printf(Locale.US, "# fundamental_hz=%.10f%n", source.getFundamentalHz());
            StringBuilder lvl = new StringBuilder();
            for (int i = 0; i < levelsDbFs.length; i++) {
                if (i > 0) lvl.append(',');
                lvl.append(String.format(Locale.US, "%.4f", levelsDbFs[i]));
            }
            pw.printf(Locale.US, "# derived_from_levels_dbfs=%s%n", lvl);
            pw.printf(Locale.US, "# orders=%s%n", renderOrders(orders));
            pw.println("# Apply: harmonic h's predicted ADC contribution = sum_k (a_k_re + j*a_k_im) * L^p_k(h)");
            pw.println("# where p_k(h) is the k-th token of 'orders' resolved against h (literal or h+/-offset).");
            StringBuilder hdr = new StringBuilder("harmonic;frequency_hz");
            for (int k = 0; k < orders.size(); k++) {
                hdr.append(";a").append(k + 1).append("_re;a").append(k + 1).append("_im");
            }
            pw.println(hdr);
            for (int i = 0; i < hList.size(); i++) {
                StringBuilder row = new StringBuilder();
                row.append(String.format(Locale.GERMAN, "%d;%.6f", hList.get(i), freqList.get(i)));
                double[] ab = coeffs.get(i);
                for (int k = 0; k < orders.size(); k++) {
                    row.append(String.format(Locale.GERMAN, ";%.10e;%.10e", ab[2 * k], ab[2 * k + 1]));
                }
                pw.println(row);
            }
        }
    }

    /**
     * Parses an adc_correction CSV (kind=adc_correction) written by
     * {@link #runDeembedMode}.  Supports both the multi-order format with an
     * explicit {@code # orders=…} header and the legacy single-order (h-1)
     * format with a 4-column row.
     */
    private AdcCorrection loadAdcCorrectionCsv(String path) throws IOException {
        List<OrderToken> orders = null;
        Map<Integer, double[]> m = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    String body = line.substring(1).trim();
                    int eq = body.indexOf('=');
                    if (eq > 0 && body.substring(0, eq).trim().equals("orders")) {
                        orders = parseOrders(body.substring(eq + 1).trim());
                    }
                    continue;
                }
                if (!Character.isDigit(line.charAt(0))) continue;
                String[] cols = line.split(";");
                if (cols.length < 4) continue;
                int h = Integer.parseInt(cols[0].trim());
                int nCoeffs = (cols.length - 2) / 2;
                double[] ab = new double[2 * nCoeffs];
                for (int k = 0; k < nCoeffs; k++) {
                    ab[2 * k]     = Double.parseDouble(cols[2 + 2 * k].trim().replace(',', '.'));
                    ab[2 * k + 1] = Double.parseDouble(cols[3 + 2 * k].trim().replace(',', '.'));
                }
                m.put(h, ab);
            }
        }
        if (orders == null) orders = parseOrders("h-1");   // legacy single-order fallback
        return new AdcCorrection(orders, m);
    }

    /**
     * Applies multi-order ADC correction to an FFT result and returns
     * per-harmonic corrected amplitudes plus corrected THD.  Predicted bin
     * contribution is {@code [Σ_k c_k · L^{p_k(h)}] · |R_1| · exp(j·h·(φ₁+π/2))},
     * matching the rotated-frame convention used during calibration.
     */
    private double[] applyFftAdcCorrection(FftAnalyzer.Result r, AdcCorrection adc,
                                                  double[] outCorrectedAmpLin) {
        double phi1     = Math.atan2(r.im[r.fundamentalBin], r.re[r.fundamentalBin]);
        double r1Amp    = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double L        = r.fundamentalLinear;
        double sumPow   = 0.0;
        List<OrderToken> orders = adc.getOrders();
        for (int h = 0; h < r.harmonicCount; h++) {
            int    bin = r.harmonicBins[h];
            int    n   = h + 2;
            double[] ab = adc.getCoeffs().get(n);
            if (bin <= 0 || ab == null) {
                outCorrectedAmpLin[h] = bin > 0
                        ? r.fundamentalLinear * r.harmonicPct[h] / 100.0
                        : 0.0;
                if (Math.min(r.harmonicCount, 8) > h) sumPow += outCorrectedAmpLin[h] * outCorrectedAmpLin[h];
                continue;
            }
            // Sum complex coefficients times L^p_k(h) across orders.
            double abSumRe = 0.0, abSumIm = 0.0;
            for (int k = 0; k < orders.size(); k++) {
                double lp = Math.pow(L, orders.get(k).resolve(n));
                abSumRe += ab[2 * k]     * lp;
                abSumIm += ab[2 * k + 1] * lp;
            }
            double theta  = n * (phi1 + Math.PI / 2.0);
            double cosT   = Math.cos(theta);
            double sinT   = Math.sin(theta);
            double predRe = r1Amp * (abSumRe * cosT - abSumIm * sinT);
            double predIm = r1Amp * (abSumRe * sinT + abSumIm * cosT);
            double cRe    = r.re[bin] - predRe;
            double cIm    = r.im[bin] - predIm;
            double cAmp   = Math.hypot(cRe, cIm) * L / r1Amp;
            outCorrectedAmpLin[h] = cAmp;
            if (h < Math.min(r.harmonicCount, 8)) sumPow += cAmp * cAmp;
        }
        double thdPct = L > 0 ? Math.sqrt(sumPow) / L * 100.0 : 0.0;
        double thdDb  = thdPct > 0 ? 20.0 * Math.log10(thdPct / 100.0) : -300.0;
        return new double[]{thdPct, thdDb};
    }

    /**
     * Mutates {@code r} in place: replaces every harmonic bin's complex value
     * (and its derived amplitude / phase / harmonic-table entries) with the
     * ADC-corrected residual, and recomputes {@code thdPct} / {@code thdDb}
     * from the corrected harmonic amplitudes.
     *
     * <p>Coherent FFT-bin domain — preserves the −155 dB-class residuals
     * the live recorder reports.  Time-domain reconstruction is not used,
     * so per-frame phase/amplitude noise is not re-introduced.
     *
     * <p>SNR / noise / non-harmonic bins are unaffected because ADC
     * distortion only contributes at exact harmonic bins.
     */
    private void applyFftAdcCorrectionInPlace(FftAnalyzer.Result r, AdcCorrection adc) {
        double phi1     = Math.atan2(r.im[r.fundamentalBin], r.re[r.fundamentalBin]);
        double r1Amp    = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double L        = r.fundamentalLinear;
        // Bin → amplLinear scaling: amplLinear = mag * (2 * windowNormFactor).
        // Recover that scalar from the fundamental.
        double linPerMag = r1Amp > 0 ? L / r1Amp : 0.0;
        List<OrderToken> orders = adc.getOrders();
        double snrLo = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
        double snrHi = r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE;
        double harmPowerSum = 0.0;
        for (int h = 0; h < r.harmonicCount; h++) {
            int    bin = r.harmonicBins[h];
            int    n   = h + 2;
            double[] ab = adc.getCoeffs().get(n);
            if (bin <= 0 || ab == null) {
                if (bin > 0 && h < Math.min(r.harmonicCount, 8)
                        && r.harmonicHz[h] >= snrLo && r.harmonicHz[h] <= snrHi) {
                    double a = L * r.harmonicPct[h] / 100.0;
                    harmPowerSum += a * a;
                }
                continue;
            }
            double abSumRe = 0.0, abSumIm = 0.0;
            for (int k = 0; k < orders.size(); k++) {
                double lp = Math.pow(L, orders.get(k).resolve(n));
                abSumRe += ab[2 * k]     * lp;
                abSumIm += ab[2 * k + 1] * lp;
            }
            double theta  = n * (phi1 + Math.PI / 2.0);
            double cosT   = Math.cos(theta);
            double sinT   = Math.sin(theta);
            double predRe = r1Amp * (abSumRe * cosT - abSumIm * sinT);
            double predIm = r1Amp * (abSumRe * sinT + abSumIm * cosT);
            double newRe  = r.re[bin] - predRe;
            double newIm  = r.im[bin] - predIm;
            r.re[bin]     = newRe;
            r.im[bin]     = newIm;
            double newMag       = Math.hypot(newRe, newIm);
            double newAmplLin   = newMag * linPerMag;
            r.amplitudeDbFs[bin] = newAmplLin > 1e-15
                    ? 20.0 * Math.log10(newAmplLin)
                    : -300.0;
            r.phaseDeg[bin]      = Math.toDegrees(Math.atan2(newIm, newRe));
            r.harmonicDbFs[h]    = r.amplitudeDbFs[bin];
            r.harmonicPct[h]     = L > 0 ? newAmplLin / L * 100.0 : 0.0;
            if (h < Math.min(r.harmonicCount, 8)
                    && r.harmonicHz[h] >= snrLo && r.harmonicHz[h] <= snrHi) {
                harmPowerSum += newAmplLin * newAmplLin;
            }
        }
        r.thdPct = L > 0 ? Math.sqrt(harmPowerSum) / L * 100.0 : 0.0;
        r.thdDb  = r.thdPct > 0 ? 20.0 * Math.log10(r.thdPct / 100.0) : -300.0;
    }

    /**
     * Logs raw vs. ADC-corrected harmonic amplitudes and THD for an FFT result.
     */
    private void logAdcCorrectedHarmonics(FftAnalyzer.Result r, AdcCorrection adc) {
        double[] corrAmp = new double[r.harmonicCount];
        double[] thd     = applyFftAdcCorrection(r, adc, corrAmp);
        double L         = r.fundamentalLinear;
        log.info("--- ADC-corrected harmonics --------------------------------");
        for (int h = 0; h < r.harmonicCount; h++) {
            if (r.harmonicBins[h] <= 0) continue;
            double rawAmp  = L * r.harmonicPct[h] / 100.0;
            double rawDb   = rawAmp > 0 ? 20.0 * Math.log10(rawAmp) : -300.0;
            double corrDb  = corrAmp[h] > 0 ? 20.0 * Math.log10(corrAmp[h]) : -300.0;
            double rawPct  = r.harmonicPct[h];
            double corrPct = L > 0 ? corrAmp[h] / L * 100.0 : 0.0;
            log.info("H{}  raw {} dBFS ({} %)   corrected {} dBFS ({} %)",
                    String.format(Locale.US, "%2d", h + 2),
                    String.format(Locale.US, "%8.3f", rawDb),
                    String.format(Locale.US, "%.6f", rawPct),
                    String.format(Locale.US, "%8.3f", corrDb),
                    String.format(Locale.US, "%.6f", corrPct));
        }
        log.info("THD raw       : {} %  ({} dB)",
                String.format(Locale.US, "%.8f", r.thdPct),
                String.format(Locale.US, "%.4f", r.thdDb));
        log.info("THD corrected : {} %  ({} dB)",
                String.format(Locale.US, "%.8f", thd[0]),
                String.format(Locale.US, "%.4f", thd[1]));
    }

    /**
     * Per-frame ADC time-domain distortion subtraction: each frame
     * (HANN-windowed, 0 % overlap, same layout as the analysis FFT) extracts
     * its own {@code phi1} and {@code r1Amp}, then synthesises and subtracts
     * a frame-local correction sinusoid.  Tracks per-frame phase / amplitude
     * variation that the global single-coefficient version cannot, closing
     * the ~14 dB gap between live FFT-bin THD and re-analyzed-WAV THD.
     *
     * <p>Phase reference within each frame: sample 0 of the frame.  The
     * synthesised cosine therefore uses the frame-relative index, matching
     * the FFT's frame-0 phase convention.  Trailing samples that don't fill
     * a full frame are processed with the last frame's parameters so they
     * remain corrected (otherwise a re-FFT covering them would see
     * uncorrected harmonics).
     */
    private void subtractAdcDistortionPerFrameInPlace(
            float[] samples, int sampleRate, int fftSize,
            FftAnalyzer.Result r, AdcCorrection adc) {
        if (samples.length < fftSize) return;

        FftAnalyzer fftAnalyzer = new FftAnalyzer();

        // HANN window matching FftAnalyzer (uses N-1 denominator).
        double[] window = new double[fftSize];
        double   wsum   = 0.0;
        for (int i = 0; i < fftSize; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (fftSize - 1)));
            wsum += window[i];
        }
        // Bin → time-domain amplitude scale: A = |X[k]| · 2 / sum(window).
        double linPerMag = 2.0 / wsum;

        int    fundBin = r.fundamentalBin;
        int    hCount  = r.harmonicCount;
        int[]  hBins   = r.harmonicBins;
        List<OrderToken> orders = adc.getOrders();
        double w       = 2.0 * Math.PI * r.fundamentalHzRefined / sampleRate;

        int frameCount = samples.length / fftSize;   // 0 % overlap, sequential
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];
        double[] amp   = new double[hCount];
        double[] phase = new double[hCount];
        boolean[] used = new boolean[hCount];

        // Last-frame state, reused for trailing samples beyond the final whole frame.
        double lastPhi1 = 0.0, lastL = 0.0;
        boolean haveLast = false;

        for (int f = 0; f < frameCount; f++) {
            int start = f * fftSize;
            for (int j = 0; j < fftSize; j++) {
                re[j] = samples[start + j] * window[j];
                im[j] = 0.0;
            }
            fftAnalyzer.fft(re, im);
            double phi1F  = Math.atan2(im[fundBin], re[fundBin]);
            double r1AmpF = Math.hypot(re[fundBin], im[fundBin]);
            double LF     = r1AmpF * linPerMag;
            lastPhi1 = phi1F; lastL = LF; haveLast = true;

            for (int h = 0; h < hCount; h++) {
                int n = h + 2;
                double[] ab = adc.getCoeffs().get(n);
                if (ab == null || hBins[h] <= 0) { used[h] = false; continue; }
                double abSumRe = 0.0, abSumIm = 0.0;
                for (int k = 0; k < orders.size(); k++) {
                    double lp = Math.pow(LF, orders.get(k).resolve(n));
                    abSumRe += ab[2 * k]     * lp;
                    abSumIm += ab[2 * k + 1] * lp;
                }
                amp[h]   = Math.hypot(abSumRe, abSumIm) * LF;
                phase[h] = Math.atan2(abSumIm, abSumRe) + n * (phi1F + Math.PI / 2.0);
                used[h]  = true;
            }
            for (int j = 0; j < fftSize; j++) {
                double corr = 0.0;
                for (int h = 0; h < hCount; h++) {
                    if (!used[h]) continue;
                    int n = h + 2;
                    corr += amp[h] * Math.cos(n * w * j + phase[h]);
                }
                samples[start + j] = (float) (samples[start + j] - corr);
            }
        }

        // Trailing partial-frame: reuse last frame's parameters with frame-relative index.
        int trailingStart = frameCount * fftSize;
        if (trailingStart < samples.length && haveLast) {
            for (int h = 0; h < hCount; h++) {
                int n = h + 2;
                double[] ab = adc.getCoeffs().get(n);
                if (ab == null || hBins[h] <= 0) { used[h] = false; continue; }
                double abSumRe = 0.0, abSumIm = 0.0;
                for (int k = 0; k < orders.size(); k++) {
                    double lp = Math.pow(lastL, orders.get(k).resolve(n));
                    abSumRe += ab[2 * k]     * lp;
                    abSumIm += ab[2 * k + 1] * lp;
                }
                amp[h]   = Math.hypot(abSumRe, abSumIm) * lastL;
                phase[h] = Math.atan2(abSumIm, abSumRe) + n * (lastPhi1 + Math.PI / 2.0);
                used[h]  = true;
            }
            for (int i = trailingStart; i < samples.length; i++) {
                int j = i - trailingStart;
                double corr = 0.0;
                for (int h = 0; h < hCount; h++) {
                    if (!used[h]) continue;
                    int n = h + 2;
                    corr += amp[h] * Math.cos(n * w * j + phase[h]);
                }
                samples[i] = (float) (samples[i] - corr);
            }
        }
    }

    // =========================================================================
    // Notch / filter calibration (linear sweep)
    // =========================================================================

    /**
     * Runs a Farina exponential log-sweep through the filter under test and
     * extracts the per-frequency transfer function H(f) by direct frequency-domain
     * deconvolution Y(f)/X(f).  The resulting CSV can be passed to {@code --fft-analyze}
     * or {@code --iterative-compensate} via {@code --cal}; callers interpolate H at
     * each FFT-bin frequency to undo the filter's frequency-dependent attenuation.
     *
     * <p>Architecture: the generator emits {@code --lead-in} seconds of silence,
     * then a single Farina sweep {@code x(t)=sin(K(eᵗ/ᴸ−1))} from {@code --sweep-start}
     * to {@code --sweep-end} over {@code --sweep-duration} seconds.  After capture,
     * a single FFT pair (size = next pow2 ≥ recording length) deconvolves Y/X to
     * produce H at every bin; output rows are linearly interpolated to {@code
     * --sweep-points} log-spaced frequencies.  DAC↔ADC propagation delay shows up
     * as a benign linear-phase term and requires no marker.
     */
    private void runFilterSweepMode(String[] args) throws Exception {
        String samplerateArg    = getArgValue(args, "--samplerate");
        String bitsArg          = getArgValue(args, "--bits");
        String amplitudeArg     = getArgValue(args, "--amplitude");
        String sweepStartArg    = getArgValue(args, "--sweep-start");
        String sweepEndArg      = getArgValue(args, "--sweep-end");
        String sweepPointsArg   = getArgValue(args, "--sweep-points");
        String sweepDurationArg = getArgValue(args, "--sweep-duration");
        String leadInArg        = getArgValue(args, "--lead-in");
        String ditherArg        = getArgValue(args, "--dither");
        String outputArg        = getArgValue(args, "--output");
        String wavOutArg        = getArgValue(args, "--sweep-wav");
        String widthArg         = getArgValue(args, "--width");
        String heightArg        = getArgValue(args, "--height");
        String adcFsArg         = getArgValue(args, "--adc-fs-vrms");

        if (samplerateArg == null) { log.error("--samplerate required"); System.exit(1); }
        if (amplitudeArg  == null) { log.error("--amplitude required");  System.exit(1); }
        if (adcFsArg != null) {
            AudioBackend.adcFsVoltageRms = Double.parseDouble(adcFsArg);
        }

        int sampleRate = Integer.parseInt(samplerateArg);
        int bitDepth   = bitsArg != null ? Integer.parseInt(bitsArg) : 24;
        double amp     = Double.parseDouble(amplitudeArg);
        int    ditherBits = ditherArg != null ? Integer.parseInt(ditherArg) : 0;
        int    chartWidth  = widthArg  != null ? Integer.parseInt(widthArg)  : 1920;
        int    chartHeight = heightArg != null ? Integer.parseInt(heightArg) : 600;

        if (!isValidSampleRate(sampleRate)) {
            log.error("--samplerate must be one of the supported rates"); System.exit(1);
        }
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32"); System.exit(1);
        }

        double fStart   = sweepStartArg != null ? Double.parseDouble(sweepStartArg) : 2.0;
        double fEnd     = sweepEndArg   != null ? Double.parseDouble(sweepEndArg)   : sampleRate / 2.0;
        int    nPoints  = sweepPointsArg != null ? Integer.parseInt(sweepPointsArg) : 200;
        double sweepDurationSec = sweepDurationArg != null ? Double.parseDouble(sweepDurationArg) : 5.5;
        double leadInSec = leadInArg != null ? Double.parseDouble(leadInArg) : 0.2;

        if (fStart <= 0.0 || fEnd <= fStart) {
            log.error("--sweep-start must be > 0 and < --sweep-end"); System.exit(1);
        }
        if (nPoints < 4) {
            log.error("--sweep-points must be at least 4"); System.exit(1);
        }
        if (sweepDurationSec < 0.5) {
            log.error("--sweep-duration must be at least 0.5 s"); System.exit(1);
        }
        if (leadInSec < 0.05) {
            log.error("--lead-in must be at least 0.05 s"); System.exit(1);
        }

        // Log-spaced output frequencies (one CSV row each)
        double[] freqs = new double[nPoints];
        double logStart = Math.log(fStart);
        double logEnd   = Math.log(fEnd);
        for (int i = 0; i < nPoints; i++) {
            double t = i / (double) (nPoints - 1);
            freqs[i] = Math.exp(logStart + (logEnd - logStart) * t);
        }

        int  sweepSamples    = (int) Math.round(sweepDurationSec * sampleRate);
        int  leadInSamples   = (int) Math.round(leadInSec * sampleRate);
        int  tailSamples     = sampleRate / 2;   // 0.5 s tail to capture decay past sweep end
        long totalGenSamples = (long) leadInSamples + sweepSamples + tailSamples;
        int  durationSec     = (int) Math.ceil(totalGenSamples / (double) sampleRate);

        logProviders();
        DeviceRef outDevice = selectMixerByFlag(args, "--out-device", true);
        DeviceRef inDevice  = selectMixerByFlag(args, "--in-device",  false);
        if (outDevice == null) { log.error("No output device. Use --out-device <index>."); System.exit(1); }
        if (inDevice  == null) { log.error("No input device. Use --in-device <index>.");  System.exit(1); }

        log.info("=== Frequency Response Measurement: Farina log-sweep deconvolution ===");
        log.info("Out device  : {}", outDevice.name());
        log.info("In device   : {}", inDevice.name());
        log.info("Sample rate : {} Hz",   sampleRate);
        log.info("Bits        : {}",      bitDepth);
        log.info("Sweep range : {} → {} Hz",
                String.format(Locale.US, "%.3f", fStart),
                String.format(Locale.US, "%.3f", fEnd));
        log.info("Sweep length: {} s ({} samples)",
                String.format(Locale.US, "%.3f", sweepDurationSec), sweepSamples);
        log.info("Output rows : {} log-spaced points", nPoints);
        log.info("Lead-in     : {} s ({} samples)",
                String.format(Locale.US, "%.2f", leadInSec), leadInSamples);
        log.info("Duration    : {} s total", durationSec);
        log.info("Amplitude   : {} V RMS", amp);
        log.info("Chart       : {}x{} px", chartWidth, chartHeight);

        // --- Capture sweep response -----------------------------------------
        SignalGenerator gen = new SignalGenerator(fStart, fEnd, sweepSamples, leadInSamples, sampleRate, amp);
        float[] yRec = captureWithGenerator(gen, outDevice, inDevice, sampleRate, bitDepth, ditherBits, durationSec);
        log.info("Captured    : {} samples ({} s)", yRec.length,
                String.format(Locale.US, "%.4f", yRec.length / (double) sampleRate));

        // Optional: write captured WAV for inspection
        if (wavOutArg != null) {
            byte[] pcm = floatMonoToStereoPcmBytes(yRec, bitDepth);
            try (WavWriter w = new WavWriter(new File(wavOutArg), sampleRate, 2, bitDepth, false)) {
                w.writeRaw(pcm, pcm.length);
            }
            log.info("Sweep WAV   : {}", wavOutArg);
        }

        // --- FFT deconvolution: H(f) = Y(f)·conj(X(f)) / |X(f)|² -----------
        FilterCalibration cal = computeFilterCalibrationFromLogSweep(
                yRec, gen.getLogSweepBuffer(), leadInSamples, sampleRate, freqs, amp);

        // --- Save CSV --------------------------------------------------------
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String csvPath = outputArg != null
                ? outputArg
                : new File("results", "filter_cal_" + ts + ".csv").getPath();
        saveFilterCalibrationCsv(cal, csvPath, sampleRate, fStart, fEnd, nPoints, amp);
        log.info("Filter cal CSV saved: {}", csvPath);

        // --- Save frequency-response chart ----------------------------------
        String chartPath = csvPath.replaceFirst("\\.csv$", "") + ".png";
        exportFilterCalibrationChart(cal, chartWidth, chartHeight, chartPath);
        log.info("Filter cal chart saved: {}", chartPath);
    }

    /**
     * Direct frequency-domain deconvolution of a Farina log-sweep recording.
     * Computes H(f) = Y(f)·conj(X(f)) / |X(f)|² at every FFT bin, then linearly
     * interpolates magnitude and phase (in re/im) to the requested {@code freqs}.
     *
     * <p>The reference {@code sweepRef} is the unit-peak buffer the generator
     * played; padding it with {@code leadInSamples} of leading silence aligns it
     * with the captured recording (which began at the same wall-clock instant
     * the generator started emitting).  The DAC↔ADC propagation delay would
     * otherwise show up as a linear-phase ramp burying the filter's phase and
     * wrecking unwrap across notches — it is removed by locating the impulse
     * response's peak (IFFT of H) with sub-sample parabolic interpolation and
     * rotating the spectrum to cancel it.  Output phase is wrapped to [-π, π]
     * (matches REW / Spice convention), so physical ±180° steps at notches
     * stay visible.
     *
     * @param yRec          recorded mono samples in [-1, +1]
     * @param sweepRef      unit-peak Farina sweep buffer (no lead-in, no scaling)
     * @param leadInSamples silence prepended in front of {@code sweepRef} on the
     *                      generator side (so that yRec[0] aligns with t=0 on the
     *                      generator timeline modulo capture-priming jitter)
     * @param sampleRate    sample rate (Hz)
     * @param freqs         output frequencies (Hz), ascending
     * @param amplitudeVRms cal-time DAC drive level in V_rms; the returned
     *                      {@code magLin} is divided by the corresponding peak
     *                      DAC fraction so the returned values are the relative
     *                      filter response |H(f)| with passband ≈ 1.0
     */
    private FilterCalibration computeFilterCalibrationFromLogSweep(
            float[] yRec, float[] sweepRef, int leadInSamples,
            int sampleRate, double[] freqs, double amplitudeVRms) {
        int xLen   = leadInSamples + sweepRef.length;
        int needed = Math.max(yRec.length, xLen);
        int M      = nextPow2(needed);

        double[] xBuf = new double[M];
        for (int i = 0; i < sweepRef.length; i++) {
            xBuf[leadInSamples + i] = sweepRef[i];
        }
        double[] yBuf = new double[M];
        for (int i = 0; i < yRec.length; i++) {
            yBuf[i] = yRec[i];
        }

        DoubleFFT_1D fft = new DoubleFFT_1D(M);
        fft.realForward(xBuf);
        fft.realForward(yBuf);

        // JTransforms realForward packing for length M (M even):
        //   bin 0  : re=buf[0],   im=0
        //   bin M/2: re=buf[1],   im=0
        //   bin k  : re=buf[2k],  im=buf[2k+1]   (1 ≤ k < M/2)
        int halfBins = M / 2;
        double[] hRe = new double[halfBins + 1];
        double[] hIm = new double[halfBins + 1];
        for (int k = 0; k <= halfBins; k++) {
            double xr, xi, yr, yi;
            if (k == 0)            { xr = xBuf[0]; xi = 0.0; yr = yBuf[0]; yi = 0.0; }
            else if (k == halfBins){ xr = xBuf[1]; xi = 0.0; yr = yBuf[1]; yi = 0.0; }
            else                   { xr = xBuf[2*k]; xi = xBuf[2*k+1];
                                     yr = yBuf[2*k]; yi = yBuf[2*k+1]; }
            double xMag2 = xr*xr + xi*xi;
            if (xMag2 <= 0.0) {
                hRe[k] = 0.0;
                hIm[k] = 0.0;
            } else {
                hRe[k] = (yr*xr + yi*xi) / xMag2;
                hIm[k] = (yi*xr - yr*xi) / xMag2;
            }
        }

        // --- Remove DAC↔ADC transport delay --------------------------------
        // Y(f) = X(f)·H(f)·e^(-j2πfΔ), so H_measured carries a linear-phase term
        // from the propagation delay.  Left in, it accumulates thousands of
        // degrees across the sweep and buries the filter's own phase under a
        // monotone ramp — unwrap then wrongly bridges real ±180° steps at
        // notches.  Locate the delay as the peak of the impulse response
        // (IFFT of H_measured) and rotate it out in the frequency domain.
        double[] ir = new double[M];
        ir[0] = hRe[0];
        ir[1] = hRe[halfBins];
        for (int k = 1; k < halfBins; k++) {
            ir[2*k]     = hRe[k];
            ir[2*k + 1] = hIm[k];
        }
        fft.realInverse(ir, true);
        int    peakIdx = 0;
        double peakVal = 0.0;
        for (int i = 0; i < M; i++) {
            double v = Math.abs(ir[i]);
            if (v > peakVal) { peakVal = v; peakIdx = i; }
        }
        double delaySamples = peakIdx;
        if (peakIdx > 0 && peakIdx < M - 1) {
            double yM = Math.abs(ir[peakIdx - 1]);
            double y0 = Math.abs(ir[peakIdx]);
            double yP = Math.abs(ir[peakIdx + 1]);
            double denom = yM - 2.0 * y0 + yP;
            if (denom != 0.0) {
                delaySamples = peakIdx + 0.5 * (yM - yP) / denom;
            }
        }
        for (int k = 0; k <= halfBins; k++) {
            double theta = 2.0 * Math.PI * k * delaySamples / M;
            double c = Math.cos(theta);
            double s = Math.sin(theta);
            double re = hRe[k] * c - hIm[k] * s;
            double im = hRe[k] * s + hIm[k] * c;
            hRe[k] = re;
            hIm[k] = im;
        }

        int nPoints = freqs.length;
        double[] magLin   = new double[nPoints];
        double[] phaseRad = new double[nPoints];
        double binHz = sampleRate / (double) M;
        for (int i = 0; i < nPoints; i++) {
            double bin = freqs[i] / binHz;
            int k0 = (int) Math.floor(bin);
            int k1 = k0 + 1;
            if (k0 < 0)         k0 = 0;
            if (k0 > halfBins)  k0 = halfBins;
            if (k1 > halfBins)  k1 = halfBins;
            double frac = bin - Math.floor(bin);
            double re = hRe[k0] * (1.0 - frac) + hRe[k1] * frac;
            double im = hIm[k0] * (1.0 - frac) + hIm[k1] * frac;
            magLin[i]   = Math.hypot(re, im);
            phaseRad[i] = Math.atan2(im, re);
        }

        // Normalise to relative |H|: divide by DAC drive peak so passband ≈ 1.0.
        // The deconvolution Y/X gave us amp_dac_peak·|H_filter|, where the unit-
        // amplitude reference X carries no drive scaling.  Dividing by the same
        // peak fraction the generator applied recovers the bare |H_filter|.
        double dacDrivePeak = amplitudeVRms * Math.sqrt(2.0) / SignalGenerator.FS_VOLTAGE;
        if (dacDrivePeak > 0.0) {
            for (int i = 0; i < nPoints; i++) {
                magLin[i] /= dacDrivePeak;
            }
        }

        double minDb = Double.POSITIVE_INFINITY, maxDb = -Double.POSITIVE_INFINITY;
        double minF = 0.0, maxF = 0.0;
        for (int k = 0; k < nPoints; k++) {
            if (magLin[k] <= 0.0) continue;
            double db = 20.0 * Math.log10(magLin[k]);
            if (db < minDb) { minDb = db; minF = freqs[k]; }
            if (db > maxDb) { maxDb = db; maxF = freqs[k]; }
        }
        log.info("Filter cal: FFT M={} ({} bins, {} Hz/bin); transport delay {} samples ({} ms) removed",
                M, halfBins,
                String.format(Locale.US, "%.4f", binHz),
                String.format(Locale.US, "%.3f", delaySamples),
                String.format(Locale.US, "%.4f", 1000.0 * delaySamples / sampleRate));
        if (Double.isFinite(minDb)) {
            log.info("Filter cal: min {} dBFS @ {} Hz   max {} dBFS @ {} Hz",
                    String.format(Locale.US, "%.2f", minDb),
                    String.format(Locale.US, "%.3f", minF),
                    String.format(Locale.US, "%.2f", maxDb),
                    String.format(Locale.US, "%.3f", maxF));
        }

        return new FilterCalibration(freqs, magLin, phaseRad);
    }

    /** Smallest power of 2 ≥ {@code x}. Returns 1 for {@code x ≤ 1}. */
    private int nextPow2(int x) {
        if (x <= 1) return 1;
        int hi = Integer.highestOneBit(x);
        return hi == x ? x : hi << 1;
    }

    /**
     * Writes a filter calibration to CSV.  One row per measured sweep point.
     * Columns: {@code frequency_hz; magnitude_dbfs; magnitude_dbv; magnitude_lin; phase_deg}.
     *
     * <p>{@code magnitude_dbfs} = 20·log₁₀(peak_float)  (0 dBFS = full scale peak).
     *    Display only — absolute capture amplitude during the sweep.
     * <br>{@code magnitude_dbv}  = 20·log₁₀(peak_float · ADC_FS_VOLTAGE_RMS / 1 V_rms).
     *    Display only — absolute capture in V_rms terms.
     * <br>{@code magnitude_db_rel} = filter response in dB, normalised to the DAC
     *    drive level so passband ≈ 0 dB and a notch reads −80 dB / etc.  This is
     *    what the apply path uses for de-embedding (corrected_dBFS = raw_dBFS −
     *    magnitude_db_rel).  Computed as {@code magnitude_dbfs − 20·log₁₀(dacDrivePeak)},
     *    where {@code dacDrivePeak = amplitudeVRms·√2 / SignalGenerator.FS_VOLTAGE}.
     */
    private void saveFilterCalibrationCsv(FilterCalibration cal, String path,
                                                 int sampleRate,
                                                 double sweepStart, double sweepEnd,
                                                 int sweepPoints,
                                                 double amplitudeVRms)
            throws IOException {
        File outFile = new File(path);
        File parent  = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        // DAC peak fraction for the unit-amplitude sweep buffer played at amplitudeVRms:
        //   amplitude (peak / FS_peak) = amplitudeVRms · √2 / FS_VOLTAGE
        double dacDrivePeak  = amplitudeVRms * Math.sqrt(2.0) / SignalGenerator.FS_VOLTAGE;
        double dacDriveDbFs  = dacDrivePeak > 0.0 ? 20.0 * Math.log10(dacDrivePeak) : 0.0;
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(outFile)))) {
            pw.printf(Locale.US, "# kind=filter_calibration%n");
            pw.printf(Locale.US, "# format_version=4%n");
            pw.printf(Locale.US, "# sample_rate_hz=%d%n",  sampleRate);
            pw.printf(Locale.US, "# sweep_start_hz=%.6f%n", sweepStart);
            pw.printf(Locale.US, "# sweep_end_hz=%.6f%n",   sweepEnd);
            pw.printf(Locale.US, "# sweep_points=%d%n",     sweepPoints);
            pw.printf(Locale.US, "# adc_fs_voltage_rms=%.6f%n", AudioBackend.adcFsVoltageRms);
            pw.printf(Locale.US, "# dac_drive_v_rms=%.6f%n",    amplitudeVRms);
            pw.printf(Locale.US, "# dac_drive_dbfs=%.6f%n",     dacDriveDbFs);
            pw.println("frequency_hz;magnitude_dbfs;magnitude_dbv;magnitude_db_rel;phase_deg");
            double dbvScale = AudioBackend.adcFsVoltageRms;
            for (int i = 0; i < cal.freqs.length; i++) {
                // cal.magLin is the RELATIVE filter response (passband ≈ 1).
                // Multiply by dacDrivePeak to recover the absolute capture peak
                // amplitude that the display columns dBFS / dBV refer to.
                double relH      = cal.magLin[i];
                double absMag    = relH * dacDrivePeak;
                double dbfs      = absMag > 0.0 ? 20.0 * Math.log10(absMag)              : -300.0;
                double dbv       = absMag > 0.0 ? 20.0 * Math.log10(absMag * dbvScale)   : -300.0;
                double dbRel     = relH   > 0.0 ? 20.0 * Math.log10(relH)                : -300.0;
                double phaseDeg  = Math.toDegrees(cal.phaseRad[i]);
                pw.printf(Locale.GERMAN, "%.6f;%.6f;%.6f;%.6f;%.4f%n",
                        cal.freqs[i], dbfs, dbv, dbRel, phaseDeg);
            }
        }
    }

    /**
     * Loads a per-point filter calibration CSV written by
     * {@link #saveFilterCalibrationCsv}.  Reads {@code sample_rate_hz} from header
     * comments; row order defines ascending frequency.
     */
    private FilterCalibration loadFilterCalibrationCsv(String path) throws IOException {
        double adcFsVoltageRms = Double.NaN;
        List<double[]> rows = new ArrayList<>();   // {freq, magLin, phaseRad}
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    String body = line.substring(1).trim();
                    int eq = body.indexOf('=');
                    if (eq <= 0) continue;
                    String key = body.substring(0, eq).trim();
                    String val = body.substring(eq + 1).trim();
                    if (key.equals("adc_fs_voltage_rms"))
                        adcFsVoltageRms = Double.parseDouble(val.replace(',', '.'));
                    continue;
                }
                if (!Character.isDigit(line.charAt(0))) continue;     // skip header row
                String[] cols = line.split(";");
                if (cols.length < 5) continue;
                double freq     = Double.parseDouble(cols[0].trim().replace(',', '.'));
                // Column 3 is now magnitude_db_rel: filter response in dB normalised to
                // DAC drive level (passband ≈ 0 dB).  Convert to linear |H| — this is
                // what the apply path divides each FFT bin by, and what the chart /
                // overlay code expects in cal.magLin.
                double dbRel    = Double.parseDouble(cols[3].trim().replace(',', '.'));
                double magLin   = Math.pow(10.0, dbRel / 20.0);
                double phaseRad = Math.toRadians(Double.parseDouble(cols[4].trim().replace(',', '.')));
                rows.add(new double[]{ freq, magLin, phaseRad });
            }
        }
        if (rows.isEmpty()) {
            throw new IOException("Filter calibration CSV has no data rows: " + path);
        }
        double[] freqs    = new double[rows.size()];
        double[] magLin   = new double[rows.size()];
        double[] phaseRad = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            freqs[i]    = rows.get(i)[0];
            magLin[i]   = rows.get(i)[1];
            phaseRad[i] = rows.get(i)[2];
        }
        FilterCalibration cal = new FilterCalibration(freqs, magLin, phaseRad);
        cal.adcFsVoltageRms = adcFsVoltageRms;
        return cal;
    }

    /**
     * Mutates {@code r} in place: divides every FFT bin (within the swept range)
     * by H(f) interpolated from the per-point filter calibration, then calls
     * {@link FftAnalyzer#recomputeStats} so the fundamental level, harmonic table,
     * THD, THD+N, SNR, and noise stats all reflect the corrected spectrum.
     *
     * <p>Bins outside the swept range {@code [freqs[0], freqs[N-1]]} are left
     * untouched.  The DC bin (k=0) is always skipped.
     */
    private void applyFilterCompensationInPlace(FftAnalyzer.Result r, FilterCalibration cal,
                                                        boolean correctAllBins) {
        int half = r.fftSize / 2;
        double binWidth = r.sampleRate / (double) r.fftSize;
        double fLo = cal.freqs[0];
        double fHi = cal.freqs[cal.freqs.length - 1];

        // The raw FFT outputs r.re/r.im are unscaled — the analyzer applies a
        // normFactor (= 1/(fftSize·cohGain)) plus a single-sided ×2 to convert
        // hypot(re, im) into r.amplitudeDbFs.  Capture that scale once now from
        // the fundamental, so we can rebuild amplitudeDbFs after correction
        // without re-deriving normFactor or cohGain (which are window-dependent).
        double oldFundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double linPerMag  = oldFundMag > 0.0 ? r.fundamentalLinear / oldFundMag : 0.0;

        // By default only correct signal bins (fundamental + each harmonic +
        // small leakage window).  Leaving noise bins untouched is intentional:
        // the noise floor is residual buffer-amp / ADC self-noise generated
        // AFTER the filter, so it is not subject to the filter's attenuation
        // and applying the inverse response would falsely boost it (deeply,
        // at notch frequencies — adding tens of dB of fake noise that the
        // system never produced).  Pass {@code correctAllBins=true} to opt
        // back into correcting every bin in the cal range (useful when the
        // dominant noise source actually IS pre-filter, e.g. measuring an
        // external reference noise generator through the filter).  The ±4-bin
        // window matches the EXCL_BINS used by the analyzer's noise mask, so
        // leakage skirts of corrected peaks are also excluded from the noise
        // pool.
        final int LEAKAGE_BINS = 4;
        boolean[] correctBin = new boolean[half + 1];
        if (correctAllBins) {
            java.util.Arrays.fill(correctBin, true);
        } else {
            for (int d = -LEAKAGE_BINS; d <= LEAKAGE_BINS; d++) {
                int b = r.fundamentalBin + d;
                if (b > 0 && b <= half) correctBin[b] = true;
            }
            for (int hb : r.harmonicBins) {
                if (hb > 0) {
                    for (int d = -LEAKAGE_BINS; d <= LEAKAGE_BINS; d++) {
                        int b = hb + d;
                        if (b > 0 && b <= half) correctBin[b] = true;
                    }
                }
            }
        }

        int corrected = 0;
        for (int k = 1; k <= half; k++) {
            if (!correctBin[k]) continue;
            double f = k * binWidth;
            if (f < fLo || f > fHi) continue;

            // Cal magLin is the absolute capture amplitude H(f) measured at the
            // ADC during the sweep.  We divide raw bins by it directly — the
            // resulting corrected_dBFS = raw_dBFS − cal_dBFS, exactly the
            // simple subtraction the user reasons about.  The output dBFS is
            // then "dB above what the ADC saw at this frequency during cal";
            // for matching drive amplitudes the recovered fundamental sits
            // near 0 dBFS regardless of where the cal-time passband fell.
            double[] h = interpolateFilterCal(cal, f);   // [magLin (absolute), phaseRad]
            double hMag = h[0];
            if (hMag <= 0.0) continue;
            double hPhi = h[1];
            double cosP = Math.cos(hPhi);
            double sinP = Math.sin(hPhi);
            double hRe  = hMag * cosP;
            double hIm  = hMag * sinP;
            double hMagSq = hMag * hMag;

            // Z(k) = X(k) / H(f) = (Xre + jXim)(Hre − jHim) / (Hre² + Him²)
            double xRe = r.re[k];
            double xIm = r.im[k];
            double zRe = (xRe * hRe + xIm * hIm) / hMagSq;
            double zIm = (xIm * hRe - xRe * hIm) / hMagSq;
            r.re[k] = zRe;
            r.im[k] = zIm;
            double newAmpLin   = Math.hypot(zRe, zIm) * linPerMag;
            r.amplitudeDbFs[k] = newAmpLin > 1e-15
                    ? 20.0 * Math.log10(newAmpLin)
                    : -300.0;
            r.phaseDeg[k] = Math.toDegrees(Math.atan2(zIm, zRe));
            corrected++;
        }

        new FftAnalyzer().recomputeStats(r);

        // Calibrated absolute dBV for every bin: dBV = dBFS + 20·log10(FS_voltage).
        // The cal CSV carries the ADC full-scale RMS voltage at measurement time.
        // We anchor via fundRefDbV — the chart and harmonic-table formula
        //   bin_dBV = bin_dBFS + (fundRefDbV − fundDbFs)
        // then collapses to bin_dBFS + 20·log10(FS_voltage) for every bin.
        // Overrides any --fund-v / --fund-dbv anchor; the cal is authoritative.
        if (!Double.isNaN(cal.adcFsVoltageRms) && cal.adcFsVoltageRms > 0.0) {
            r.fundRefDbV = r.fundamentalDbFs + 20.0 * Math.log10(cal.adcFsVoltageRms);
        }

        log.info("Filter compensation applied to {} bins (out of {}, range {}-{} Hz); "
                        + "fundamental: {} dBFS / {} dBV, SNR: {} dB, THD: {}%",
                corrected, half,
                String.format(Locale.US, "%.3f", fLo),
                String.format(Locale.US, "%.3f", fHi),
                String.format(Locale.US, "%.2f", r.fundamentalDbFs),
                Double.isNaN(r.fundRefDbV) ? "n/a"
                        : String.format(Locale.US, "%.2f", r.fundRefDbV),
                String.format(Locale.US, "%.2f", r.snrDb),
                String.format(Locale.US, "%.6f", r.thdPct));
    }

    /**
     * Sets {@code result.fundRefDbV} from the global ADC full-scale RMS voltage
     * when no other source has anchored it (no {@code --fund-v} / {@code --fund-dbv},
     * no cal CSV).  Result: every bin's displayed dBV becomes the calibrated
     * absolute voltage at the ADC, regardless of what the DAC drove or what a
     * DUT/attenuator/filter did between them.  No-op when fundRefDbV is already set.
     */
    private void applyDefaultDbvScaling(FftAnalyzer.Result result) {
        if (Double.isNaN(result.fundRefDbV) && AudioBackend.adcFsVoltageRms > 0.0) {
            result.fundRefDbV = result.fundamentalDbFs
                    + 20.0 * Math.log10(AudioBackend.adcFsVoltageRms);
        }
    }

    /**
     * Snapshots the pre-correction fundamental + harmonic peak levels so the
     * chart can draw blue "before-cal" dots alongside the red "after-cal" dots.
     * Must be called with the pre-correction {@code result} (i.e. before
     * {@link #applyFilterCompensationInPlace}).  Returns {@code [freqs, dbFs]};
     * bins with no detected peak are excluded.
     */
    private double[][] capturePreCorrectionPeaks(FftAnalyzer.Result result) {
        int count = 1 + result.harmonicCount;
        double[] freqs = new double[count];
        double[] dbFs  = new double[count];
        freqs[0] = result.fundamentalHz;
        dbFs[0]  = result.fundamentalDbFs;
        for (int h = 0; h < result.harmonicCount; h++) {
            if (result.harmonicBins[h] > 0) {
                freqs[1 + h] = result.harmonicHz[h];
                dbFs[1 + h]  = result.harmonicDbFs[h];
            }
            // else leave freq=0 — exportChart filters those out.
        }
        return new double[][]{ freqs, dbFs };
    }

    /**
     * Builds an inverted-cal overlay for the FFT chart.  Returns a 2-element
     * array {@code [freqs, dbFs]} suitable for {@link FftAnalyzer#exportChart}'s
     * overlay parameters, or {@code null} if there's no H2 reference available.
     *
     * <p>Shape: {@code y(f) = −cal_dB(f) + offset}, where {@code offset} is chosen
     * so the curve passes through {@code (H2_freq, H2_rawDbFs)} — i.e. the
     * pre-correction H2 peak.  Physical reading: at every frequency the curve
     * shows the level the noise floor would sit at if it followed the filter's
     * inverse response from the H2 passband reference point.  Peaks at the
     * notch; decays toward passband on either side.
     *
     * <p>Must be called with the pre-correction {@code result} (so {@code
     * harmonicDbFs[0]} still reflects the filter-attenuated measurement).
     */
    private double[][] computeCalOverlay(FilterCalibration cal, FftAnalyzer.Result result) {
        if (result.harmonicCount == 0 || result.harmonicBins[0] <= 0) return null;
        double h2Freq = result.harmonicHz[0];
        double h2DbFs = result.harmonicDbFs[0];
        if (!(h2Freq > 0.0)) return null;
        double[] h = interpolateFilterCal(cal, h2Freq);
        double calDbAtH2 = h[0] > 0.0 ? 20.0 * Math.log10(h[0]) : -300.0;
        double offset   = h2DbFs + calDbAtH2;
        double[] freqs  = cal.freqs.clone();
        double[] dbFs   = new double[cal.magLin.length];
        for (int i = 0; i < cal.magLin.length; i++) {
            double calDb = cal.magLin[i] > 0.0 ? 20.0 * Math.log10(cal.magLin[i]) : -300.0;
            dbFs[i] = -calDb + offset;
        }
        return new double[][]{ freqs, dbFs };
    }

    /**
     * Interpolates the calibration's H(f) at an arbitrary frequency.  Uses
     * log-frequency as the interpolation variable.
     *
     * <p>Magnitude: interpolated in dB (robust at deep notches where adjacent
     * log-spaced points differ by 40+ dB).
     *
     * <p>Phase: interpolated in COMPLEX (re/im) space via the unit phasors
     * {@code e^{jφ₀}} and {@code e^{jφ₁}}, then converted back with atan2.  This
     * handles the ±180° wrap that sits at notch zeroes (a real pole/zero on the
     * jω axis flips the phase by π within the notch).  Naïve linear interpolation
     * of {@code phaseRad} across that wrap produces garbage — typically 0°
     * half-way between -54° and +125° — which, when used to rotate the
     * fundamental bin during cal correction, propagates as a phase error into
     * every harmonic compensation in {@code accumulateHarmonics}.
     *
     * @return {@code [magLin, phaseRad]}
     */
    private double[] interpolateFilterCal(FilterCalibration cal, double freq) {
        // Binary search for upper bracket
        int lo = 0, hi = cal.freqs.length - 1;
        if (freq <= cal.freqs[lo]) return new double[]{ cal.magLin[lo], cal.phaseRad[lo] };
        if (freq >= cal.freqs[hi]) return new double[]{ cal.magLin[hi], cal.phaseRad[hi] };
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cal.freqs[mid] <= freq) lo = mid; else hi = mid;
        }
        double f0 = cal.freqs[lo], f1 = cal.freqs[hi];
        double t  = (Math.log(freq) - Math.log(f0)) / (Math.log(f1) - Math.log(f0));

        double m0 = cal.magLin[lo], m1 = cal.magLin[hi];
        double db0 = m0 > 0.0 ? 20.0 * Math.log10(m0) : -300.0;
        double db1 = m1 > 0.0 ? 20.0 * Math.log10(m1) : -300.0;
        double db  = db0 + (db1 - db0) * t;
        double mag = Math.pow(10.0, db / 20.0);

        // Unit-phasor complex interpolation for phase.
        double re0 = Math.cos(cal.phaseRad[lo]), im0 = Math.sin(cal.phaseRad[lo]);
        double re1 = Math.cos(cal.phaseRad[hi]), im1 = Math.sin(cal.phaseRad[hi]);
        double reT = re0 + (re1 - re0) * t;
        double imT = im0 + (im1 - im0) * t;
        double phi = (reT == 0.0 && imT == 0.0)
                ? cal.phaseRad[lo]              // two phasors cancel (rare) — keep the lower one
                : Math.atan2(imT, reT);

        return new double[]{ mag, phi };
    }

    /**
     * Renders a frequency-response chart from a per-point filter calibration:
     *   Series 0 = magnitude in dB (left Y axis)
     *   Series 1 = phase in degrees (right Y axis), unwrapped
     * X axis is logarithmic frequency, matching the FFT chart style.
     */
    private void exportFilterCalibrationChart(FilterCalibration cal,
            int width, int height, String pngPath) throws IOException {
        XYSeries magSeries   = new XYSeries("Magnitude (dB)");
        XYSeries phaseSeries = new XYSeries("Phase (deg)");
        for (int i = 0; i < cal.freqs.length; i++) {
            double freq = cal.freqs[i];
            if (freq <= 0.0) continue;
            double mag  = cal.magLin[i];
            double db   = mag > 0.0 ? 20.0 * Math.log10(mag) : -300.0;
            double phaseDeg = Math.toDegrees(cal.phaseRad[i]);
            magSeries.add(freq,  db);
            phaseSeries.add(freq, phaseDeg);
        }

        XYSeriesCollection magData   = new XYSeriesCollection();
        magData.addSeries(magSeries);
        XYSeriesCollection phaseData = new XYSeriesCollection();
        phaseData.addSeries(phaseSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Filter Frequency Response", "Frequency (Hz)", "Magnitude (dB)",
                magData, PlotOrientation.VERTICAL,
                true, false, false);
        chart.getTitle().setFont(ChartStyle.CHART_TITLE_FONT);

        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(ChartStyle.PLOT_BACKGROUND);
        plot.setDomainGridlinePaint(ChartStyle.GRID_LINE_COLOR);
        plot.setRangeGridlinePaint(ChartStyle.GRID_LINE_COLOR);

        // Log frequency X axis with 1-2-3-5-7×decade ticks (same style as FFT chart)
        final double freqMin = cal.freqs[0];
        final double freqMax = cal.freqs[cal.freqs.length - 1];
        LogAxis freqAxis = new LogAxis("Frequency (Hz)") {
            @Override
            public List<NumberTick> refreshTicks(
                    Graphics2D g2,
                    AxisState state,
                    Rectangle2D dataArea,
                    RectangleEdge edge) {
                List<NumberTick> ticks = new ArrayList<>();
                double[] mults = {1, 2, 3, 5, 7};
                double decade = 1.0;
                while (decade <= getUpperBound() * 1.01) {
                    for (double m : mults) {
                        double f = decade * m;
                        if (f >= getLowerBound() && f <= getUpperBound()) {
                            String label = f >= 1000.0
                                    ? String.format(Locale.US, "%.0fk", f / 1000.0)
                                    : String.format(Locale.US, "%.0f", f);
                            ticks.add(new NumberTick(
                                    TickType.MAJOR, f, label,
                                    TextAnchor.TOP_CENTER,
                                    TextAnchor.CENTER, 0.0));
                        }
                    }
                    decade *= 10.0;
                }
                return ticks;
            }
        };
        freqAxis.setBase(10.0);
        freqAxis.setRange(freqMin, freqMax);
        freqAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        freqAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);
        plot.setDomainAxis(freqAxis);

        // Magnitude Y axis — dB.  Auto-scale lower bound so deep notches are visible.
        double minDb =  Double.POSITIVE_INFINITY;
        double maxDb = -Double.POSITIVE_INFINITY;
        for (int i = 0; i < cal.freqs.length; i++) {
            double mag = cal.magLin[i];
            if (mag <= 0.0) continue;
            double db = 20.0 * Math.log10(mag);
            if (db < minDb) minDb = db;
            if (db > maxDb) maxDb = db;
        }
        if (!Double.isFinite(minDb)) { minDb = -100.0; maxDb = 10.0; }
        double yMin = Math.floor((minDb - 5.0) / 10.0) * 10.0;
        double yMax = Math.ceil ((maxDb + 5.0) / 10.0) * 10.0;
        if (yMax - yMin < 20.0) yMax = yMin + 20.0;
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setRange(yMin, yMax);
        yAxis.setTickUnit(new NumberTickUnit(10.0));
        yAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        yAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);

        // Primary axis is relative filter response (passband ≈ 0 dB) — there's
        // no absolute reference to attach a dBV axis to here.  Use the CSV's
        // magnitude_dbv column for absolute capture readouts.

        // Phase Y axis on the right — auto-scale to the unwrapped range.
        double minPhase =  Double.POSITIVE_INFINITY;
        double maxPhase = -Double.POSITIVE_INFINITY;
        for (int i = 0; i < cal.freqs.length; i++) {
            double pd = Math.toDegrees(cal.phaseRad[i]);
            if (pd < minPhase) minPhase = pd;
            if (pd > maxPhase) maxPhase = pd;
        }
        if (!Double.isFinite(minPhase)) { minPhase = -180.0; maxPhase = 180.0; }
        double pSpan = Math.max(90.0, maxPhase - minPhase);
        double pad   = pSpan * 0.05;
        NumberAxis phaseAxis = new NumberAxis("Phase (deg)");
        phaseAxis.setRange(minPhase - pad, maxPhase + pad);
        phaseAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        phaseAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);
        plot.setRangeAxis(1, phaseAxis);
        plot.setDataset(1, phaseData);
        plot.mapDatasetToRangeAxis(1, 1);

        // Renderers
        XYLineAndShapeRenderer magRend =
                new XYLineAndShapeRenderer(true, false);
        magRend.setSeriesPaint(0, ChartStyle.SPECTRUM_COLOR);
        magRend.setSeriesStroke(0, ChartStyle.SPECTRUM_STROKE);
        plot.setRenderer(0, magRend);

        XYLineAndShapeRenderer phRend =
                new XYLineAndShapeRenderer(true, false);
        phRend.setSeriesPaint(0, new Color(0, 150, 0));
        phRend.setSeriesStroke(0, new BasicStroke(0.6f,
                BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                10.0f, new float[]{4f, 3f}, 0f));
        plot.setRenderer(1, phRend);

        File out = new File(pngPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        ChartUtils.saveChartAsPNG(out, chart, width, height);
    }

    /**
     * Quantises a normalized float[-1,+1] mono signal back to little-endian
     * signed-PCM bytes at {@code bitDepth}, duplicating the value into both
     * channels of a stereo frame.  Used to write the ADC-corrected WAV at the
     * same bit depth the recorder captured.
     */
    private byte[] floatMonoToStereoPcmBytes(float[] samples, int bitDepth) {
        int sampleBytes = bitDepth / 8;
        int frameSize   = sampleBytes * 2;
        byte[] out      = new byte[samples.length * frameSize];
        long maxPos     = (1L << (bitDepth - 1)) - 1;
        long minNeg     = -(1L << (bitDepth - 1));
        for (int i = 0; i < samples.length; i++) {
            long v = Math.round((double) samples[i] * (1L << (bitDepth - 1)));
            if (v >  maxPos) v = maxPos;
            if (v <  minNeg) v = minNeg;
            int off = i * frameSize;
            for (int ch = 0; ch < 2; ch++) {
                int chOff = off + ch * sampleBytes;
                for (int b = 0; b < sampleBytes; b++) {
                    out[chOff + b] = (byte) (v >> (8 * b));
                }
            }
        }
        return out;
    }

    /**
     * Simultaneously plays a signal on {@code outDevice} and records from {@code inDevice}
     * for {@code duration} seconds.  Returns the captured mono samples normalised to −1…+1.
     * Channel 0 (left / primary) of the input device is used.
     */
    private float[] captureWithGenerator(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                         int sampleRate, int bitDepth, int ditherBits,
                                         int duration) throws Exception {
        return captureWithGenerator(gen, outDevice, inDevice, sampleRate, bitDepth, ditherBits,
                duration, null, 0);
    }

    /**
     * Full overload.  When {@code weights} is non-null, each captured raw ADC code
     * is run through a {@link WeightedBuffer} code-map (offset-binary in →
     * linearised float code out) before the [-1, +1] normalisation.
     *
     * <p>{@code syncPauseSec} adds a configurable delay between the generator
     * starting (HW buffer pre-filled, DAC actively driving the line) and the
     * recorder starting capture.  Use this to give a downstream phase-locked
     * analog source (e.g. a Wien-bridge oscillator slaved off the DAC sine)
     * time to settle into lock before {@code duration} seconds of useful data
     * are collected.  Pass 0 to start capture immediately (matches the original
     * behaviour).
     */
    private float[] captureWithGenerator(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                         int sampleRate, int bitDepth, int ditherBits,
                                         int duration, WeightedBuffer weights,
                                         int syncPauseSec) throws Exception {
        int   maxSamples = (int) Math.min((long) duration * sampleRate + sampleRate, Integer.MAX_VALUE);
        float[] samples  = new float[maxSamples];
        long    halfRange = 1L << (bitDepth - 1);
        final AtomicInteger writePos = new AtomicInteger(0);
        final AtomicInteger skipped  = new AtomicInteger(0);
        // No skip — marker cross-correlation tolerates pop/glitch in the pre-roll.
        final int skipFrames = 0;

        AtomicBoolean   genStop  = new AtomicBoolean(false);
        CountDownLatch  genReady = new CountDownLatch(1);   // signalled after HW buffer pre-filled

        AudioPlayback audioGen = AudioBackend.instance().openPlayback(outDevice, sampleRate, bitDepth, ditherBits);
        audioGen.open();

        Thread genThread = new Thread(() -> {
            try {
                audioGen.play(gen, genStop, genReady);
            } catch (Exception e) {
                log.error("Generator error", e);
            } finally {
                audioGen.close();
            }
        }, "gen-thread");
        genThread.setPriority(Thread.MAX_PRIORITY);

        try (AudioCapture recorder = AudioBackend.instance().openCapture(inDevice, sampleRate, bitDepth)) {
            final WeightedBuffer w = weights;
            recorder.setSampleListener(stereo -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(stereo.length);
                    return;
                }
                int n = writePos.get();
                for (int i = 0; i < stereo.length && n + i < samples.length; i++) {
                    double code = w != null
                            ? w.correctedCode(stereo[i]).ch1
                            : (double) (stereo[i].ch1 & 0xFFFFFFFFL);
                    samples[n + i] = (float) ((code - halfRange) / (double) halfRange);
                }
                writePos.addAndGet(stereo.length);
            });

            recorder.open();
            genThread.start();
            // Wait for the generator's HW buffer to pre-fill (DAC is now driving),
            // then optionally wait for an external phase-locked source to settle.
            genReady.await(10, TimeUnit.SECONDS);
            if (syncPauseSec > 0) {
                log.info("Sync pause: holding capture for {} s while generator runs (analog sync settle)", syncPauseSec);
                Thread.sleep(syncPauseSec * 1000L);
            }
            recorder.startRecording();
            Thread.sleep(duration * 1000L);
            recorder.stopRecording();
        }
        genStop.set(true);       // signal generator to stop after its current buffer
        genThread.join(5000L);

        int actual = Math.min(writePos.get(), maxSamples);
        return actual < maxSamples ? Arrays.copyOf(samples, actual) : samples;
    }

    /**
     * Selects an audio device by the given CLI flag (e.g. {@code --out-device} or
     * {@code --in-device}).  Falls back to auto-selecting if exactly one device is available.
     */
    private DeviceRef selectMixerByFlag(String[] args, String flag, boolean isOutput) {
        String indexArg = getArgValue(args, flag);
        if (indexArg != null) {
            DeviceRef found = AudioBackend.instance().getDeviceByIndex(Integer.parseInt(indexArg), isOutput);
            if (found == null) {
                log.error("Device not found at index {} ({})", indexArg, flag);
                listDevices();
            }
            return found;
        }
        List<DeviceRef> devices = isOutput
                ? AudioBackend.instance().listOutputDevices()
                : AudioBackend.instance().listInputDevices();
        if (devices.isEmpty())  return null;
        if (devices.size() == 1) return AudioBackend.instance().getDeviceByIndex(devices.get(0).index(), isOutput);
        log.error("Multiple {} devices — use {} <index>:", isOutput ? "output" : "input", flag);
        devices.forEach(d -> log.error("  {}", d));
        return null;
    }

    /**
     * Logs the DAC↔ADC clock mismatch deduced from generator vs measured fundamental.
     * Maps the relative drift back to the master oscillator (22.5792 MHz for the
     * 44.1k sample-rate family, 24.576 MHz for the 48k family).
     */
    private void logClockMismatch(int iter, double genFreqHz, double measuredHz, int sampleRate) {
        if (genFreqHz <= 0.0 || measuredHz <= 0.0) return;
        double delta = measuredHz - genFreqHz;
        double ppm   = 1e6 * delta / genFreqHz;
        double osc;
        String oscName;
        if (sampleRate % 44100 == 0) {
            osc = 22.5792e6; oscName = "22.5792 MHz";
        } else if (sampleRate % 48000 == 0) {
            osc = 24.576e6;  oscName = "24.576 MHz";
        } else {
            log.info("Iter {} clock: ΔF={} Hz ({} ppm) — sample rate {} matches no standard oscillator family",
                    iter,
                    String.format(Locale.US, "%+.6f", delta),
                    String.format(Locale.US, "%+.2f", ppm),
                    sampleRate);
            return;
        }
        double oscDelta = osc * delta / genFreqHz;
        log.info("Iter {} clock: gen={} Hz, meas={} Hz, ΔF={} Hz ({} ppm), Δosc={} Hz @ {}",
                iter,
                String.format(Locale.US, "%.6f", genFreqHz),
                String.format(Locale.US, "%.6f", measuredHz),
                String.format(Locale.US, "%+.6f", delta),
                String.format(Locale.US, "%+.2f", ppm),
                String.format(Locale.US, "%+.2f", oscDelta),
                oscName);
    }

    private boolean isValidSampleRate(int rate) {
        for (int r : VALID_SAMPLE_RATES) {
            if (r == rate) {
                return true;
            }
        }
        return false;
    }

    private boolean hasArg(String[] args, String flag) {
        for (String a : args) {
            if (a.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }

    private String getArgValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(flag)) {
                return args[i + 1];
            }
        }
        return null;
    }

    /** Collects every value following {@code flag} in {@code args}, in order. */
    private List<String> getArgValues(String[] args, String flag) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(flag)) out.add(args[i + 1]);
        }
        return out;
    }

}
