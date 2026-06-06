/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.preferences;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.AmplitudeUnit;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftMagnitudeUnit;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.LpfMode;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.TriggerEdge;
import org.edgo.audio.measure.enums.TriggerMode;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.gui.bind.Property;
import org.edgo.audio.measure.gui.enums.TabOrientation;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Process-wide GUI preferences.  Singleton — access via {@link #instance()}.
 *
 * <p>State is persisted as YAML to a file in the application's running
 * directory (see {@link #PREFS_FILE}).  The file is loaded the first time
 * {@code instance()} is called, and rewritten by {@link #save()} after every
 * user-visible change.  When the file is missing or malformed the defaults
 * remain.
 *
 * <p>Per-backend audio settings (input/output device name, sample rate, bit
 * depth) are stored in a {@link BackendPrefs} map keyed by
 * {@link AudioBackendType}, so switching backends preserves each one's
 * selections.  Oscilloscope toolbar state lives at the top level.
 *
 * <p>Devices are persisted by name (a string) rather than as
 * {@link org.edgo.audio.measure.sound.DeviceRef} objects because the
 * concrete implementations are backend-specific and not serialisable on
 * their own.  The dialog re-resolves a name to a live {@code DeviceRef} by
 * matching against the current device list.
 */
@Log4j2
public final class Preferences {

    /** Name of the YAML file in the working directory. */
    private static final String PREFS_FILE = "preferences.yaml";

    /** Debounce window for auto-save: a burst of bound-property changes
     *  (a drag, a resize, fast typing) coalesces into a single file write
     *  this many milliseconds after the last change. */
    private static final long SAVE_COALESCE_MS = 250;

    private static volatile Preferences instance;

    private final Property<AudioBackendType> backend = bound(AudioBackendType.WASAPI);

    /** UI language tag (BCP-47 or just the lowercase ISO 639-1 code:
     *  "en", "de", …).  Java's {@code ResourceBundle.getBundle} resolves
     *  this via the fallback chain {@code <lang>_<region>} → {@code <lang>} →
     *  default (English).  Empty / null → platform default. */
    private final Property<String> uiLanguage = bound("en");

    /** Where the main window's top-level tab strip sits — {@code "TOP"} for
     *  a conventional horizontal tab folder, {@code "LEFT"} for a vertical
     *  sidebar of large icon + label buttons.  Changing this triggers a
     *  shell recreate so the new layout takes effect immediately. */
    private final Property<TabOrientation> tabOrientation = bound(TabOrientation.TOP);

    /** Zero-based index of the most recently selected top-level tab,
     *  restored on the next launch.  Only honoured by the LEFT sidebar
     *  layout for now; the standard TabFolder remembers its own
     *  selection via SWT. */
    private final Property<Integer> activeTabIndex = bound(0);

    /** When true, the main tab strip draws icons at a smaller size — useful
     *  on dense displays or when the user wants more vertical/horizontal
     *  real estate for the actual measurement panes. */
    private final Property<Boolean> smallIconsInMainTab = bound(false);

    /** When true, the GUI checks GitHub releases for a newer version on
     *  startup.  Stays off by default so the app never makes network
     *  calls without explicit user opt-in. */
    private final Property<Boolean> checkForUpdatesOnStartup = bound(false);
    /** When true, beta / pre-release builds are also considered by the
     *  startup update check.  Ignored when
     *  {@link #checkForUpdatesOnStartup} is false. */
    private final Property<Boolean> includeBetaInUpdateChecks = bound(false);

    private final Map<AudioBackendType, BackendPrefs> perBackend =
            new EnumMap<>(AudioBackendType.class);

    // -------------------------------------------------------------------------
    // Oscilloscope toolbar state — not backend-specific.
    // -------------------------------------------------------------------------

    private final Property<Boolean> oscLeftChannelEnabled  = bound(true);
    private final Property<Boolean> oscRightChannelEnabled = bound(true);
    /** AC display mode per channel: remove the DC mean from the rendered trace. Measurements are unaffected. */
    private final Property<Boolean> oscLeftAcMode  = bound(false);
    private final Property<Boolean> oscRightAcMode = bound(false);
    /** Left-channel V/div as a raw double (volts).  Free-form value: not
     *  constrained to the standard 1-2-5-10 step list.  Defaults to
     *  100 mV/div, matching the legacy {@code oscLeftVoltsPerDivIdx = 15}. */
    private final Property<Double>  oscLeftVoltsPerDiv  = bound(0.1);
    private final Property<Double>  oscRightVoltsPerDiv = bound(0.1);
    /** Time per division (seconds).  Free-form double, defaults to 1 ms/div. */
    private final Property<Double>  oscTimePerDiv       = bound(1e-3);
    private final Property<Channel> oscTriggerChannel = bound(Channel.L);
    private final Property<TriggerEdge>    oscTriggerEdge    = bound(TriggerEdge.RISE);
    private final Property<TriggerMode>    oscTriggerMode    = bound(TriggerMode.AUTO);
    /** Trigger hysteresis in oscilloscope divisions; 0 disables hysteresis. */
    private final Property<Double>         oscTriggerHysteresisDiv = bound(0.0);
    /** When false, hysteresis is bypassed at runtime regardless of
     *  {@link #oscTriggerHysteresisDiv}; the stored div value is preserved
     *  so re-enabling restores the user's last setting. */
    private final Property<Boolean>        oscTriggerHysteresisEnabled = bound(false);
    /** When {@code true} (and the generator is in {@code DUAL_TONE}
     *  mode), the scope overlays the reconstructed {@code |F1-F2|}
     *  beat envelope on the live trace in the trigger channel's
     *  trace colour (20 % darker).  Has no effect outside DUAL_TONE
     *  mode — the overlay is gated on the form independently. */
    private final Property<Boolean>        oscShowReconstructedBeat = bound(false);
    /** Per-channel Lanczos sinc-interpolation toggle.  Each channel renders
     *  independently so the user can compare a sinc-reconstructed trace
     *  against a linearly-interpolated one side-by-side. */
    private final Property<Boolean>        oscLeftSincInterpEnabled  = bound(true);
    private final Property<Boolean>        oscRightSincInterpEnabled = bound(true);
    /** Per-channel mains-hum suppression mode (MainsSuppression enum name);
     *  filters the captured signal before scope display / trigger /
     *  measurement.  DC-preserving (removes only 50/60 Hz + harmonics). */
    private final Property<MainsSuppression> oscLeftMainsSuppression  = bound(MainsSuppression.NONE);
    private final Property<MainsSuppression> oscRightMainsSuppression = bound(MainsSuppression.NONE);
    /** Per-channel HF low-pass mode (LpfMode enum name) — strips spikes
     *  above the audio band before scope display / measurement. */
    private final Property<LpfMode>        oscLeftLpf   = bound(LpfMode.NONE);
    private final Property<LpfMode>        oscRightLpf  = bound(LpfMode.NONE);

    // Per-pane slider state.  All values are fractions of the visible window
    // (independent of V/div and t/div): 0.5 = centred, 0 = top/left edge,
    // 1 = bottom/right edge.  Offset is per-channel; the slider on the scope
    // canvas controls whichever channel is currently selected in the
    // measurement table.
    private final Property<Double> oscLeftOffsetFrac      = bound(0.5);
    private final Property<Double> oscRightOffsetFrac     = bound(0.5);
    private final Property<Double> oscTriggerLevelFrac    = bound(0.5);
    private final Property<Double> oscTriggerPositionFrac = bound(0.5);
    /** Sliding-window duration (seconds) for measurement avg / min / max / σ. */
    private final Property<Double>         oscMeasurementAverageSeconds = bound(5.0);
    /** Trace stroke width (pixels). */
    private final Property<Double>         oscLineWidth         = bound(2.0);
    /** Sample-dot diameter (pixels) when the inter-sample spacing exceeds 10 px. */
    private final Property<Integer>        oscDotDiameter       = bound(5);
    /** Packed RGB (0xRRGGBB) of the left and right channel traces. */
    private final Property<Integer>        oscLeftChannelColor  = bound(0x0057B7);
    private final Property<Integer>        oscRightChannelColor = bound(0xFFD700);

    /** Last screenshot resolution / folder, 0 / null = use the pane's current size / system default. */
    private final Property<Integer> screenshotWidth  = bound(0);
    private final Property<Integer> screenshotHeight = bound(0);
    private final Property<String>  screenshotFolder = bound(null);
    /** Channel whose live samples drive the measurement table (auto-flipped to the other when this one is disabled). */
    private final Property<Channel> oscMeasurementChannel = bound(Channel.L);
    /** Toggles the measurement table's stats columns (avg/min/max/σ); the worker keeps computing them in the background regardless. */
    private final Property<Boolean> oscShowStats = bound(true);
    /** Master toggle for the measurement table overlay in the scope view —
     *  when false, only the L / R channel-pick buttons and the table
     *  show / hide toggle are visible; the rows and the stats / reset
     *  buttons are hidden. */
    private final Property<Boolean> oscShowMeasurementTable = bound(true);
    /** ADC full-scale RMS voltage — calibration constant used to translate normalised samples into volts.  Persisted across launches. */
    private final Property<Double> adcFsVoltageRms = bound(1.7931);
    /** DAC full-scale RMS voltage — the voltage the DAC outputs at digital
     *  full-scale.  Calibrated by the user via the "Calibrate DAC" button in
     *  the generator pane.  Persisted across launches.  Default 2.79351
     *  matches the original hard-coded {@code SignalGenerator.FS_VOLTAGE}. */
    private final Property<Double> dacFsVoltageRms = bound(2.79351);

    /** Path to the most recently chosen scope "Save to…" file (last N seconds of capture). */
    private final Property<String> oscSavePath   = bound(null);
    /** Folder remembered for the scope's "Save to…" file dialog. */
    private final Property<String> oscSaveFolder = bound(null);
    /** Scope-save duration in seconds (how much of the recent capture to write to disk). */
    private final Property<Double> oscSaveDurationSeconds = bound(5.0);

    /** Path to the most recently chosen scope "Play from…" source file (WAV/FLAC/AIFF). */
    private final Property<String>  oscPlayFromPath   = bound(null);
    /** Folder remembered for the scope's "Play from…" file dialog. */
    private final Property<String>  oscPlayFromFolder = bound(null);
    /** Whether the scope file-player should loop on EOF. */
    private final Property<Boolean> oscPlayFromLoop   = bound(false);

    // -------------------------------------------------------------------------
    // Generator pane state — stored by enum name where possible so the file
    // stays human-readable.  Amplitude is canonical Vrms; the display unit
    // the user last entered is remembered separately so the field reformats
    // back into "their" unit on reload.
    // -------------------------------------------------------------------------
    private final Property<GenSignalForm> genSignalForm = bound(GenSignalForm.SINE);
    private final Property<Double> genFrequencyHz   = bound(1000.0);
    /** First tone of the {@code DUAL_TONE} waveform — Hz. */
    private final Property<Double> genDualToneFreq1Hz = bound(1000.0);
    /** Second tone of the {@code DUAL_TONE} waveform — Hz. */
    private final Property<Double> genDualToneFreq2Hz = bound(1300.0);
    /** Percentage of the total signal power going to the first tone of
     *  {@code DUAL_TONE}; the second tone receives {@code 100 − this}.
     *  Range [0, 100], default 50 = equal split. */
    private final Property<Double> genDualToneSplitPct = bound(50.0);
    private final Property<Double> genAmplitudeVrms = bound(0.5);
    /** Unit the amplitude field renders in: one of {@code mV}, {@code V}, {@code dBV}, {@code dBFS}. */
    private final Property<AmplitudeUnit> genAmplitudeUnit = bound(AmplitudeUnit.V);
    /** Dither bits 0..N; 0 means "Off". */
    private final Property<Integer> genDitherBits  = bound(0);
    /** Path to the harmonics-correction CSV, or {@code null} if none. */
    private final Property<String> genCorrectionsCsv    = bound(null);
    /** Folder remembered for the generator's "browse for corrections CSV" dialog. */
    private final Property<String> genCorrectionsFolder = bound(null);
    /** Rectangle / pulse duty cycle as a fraction in [0.001, 0.999].  Default 50 %. */
    private final Property<Double>  genRectangleDuty = bound(0.5);
    /** Triangle duty cycle (rise-portion fraction) in [0.001, 0.999].  Default 50 %
     *  = symmetric triangle; near 1.0 / 0.0 yields sawtooth-like waveforms. */
    private final Property<Double>  genTriangleDuty  = bound(0.5);
    /** Sweep (LINEAR_SWEEP / LOG_SWEEP) — start frequency in Hz. */
    private final Property<Double>  genSweepFreqStartHz   = bound(20.0);
    /** Sweep — stop frequency in Hz. */
    private final Property<Double>  genSweepFreqEndHz     = bound(20000.0);
    /** Sweep duration in seconds (one full cycle from start to end). */
    private final Property<Double>  genSweepDurationSec   = bound(1.0);
    /** Loop the sweep continuously vs play once then go silent. */
    private final Property<Boolean> genSweepLoop          = bound(true);
    /** Hann fade-in duration in seconds — smooths the start so the output
     *  doesn't click on transient onset. */
    private final Property<Double>  genSweepFadeInSec     = bound(0.01);
    /** Hann fade-out duration in seconds. */
    private final Property<Double>  genSweepFadeOutSec    = bound(0.01);
    /**
     * When true the generator's frequency input will be snapped to the
     * nearest exact FFT bin on a future analysis pass.  Persisted only —
     * the snap logic itself is wired by a future change.
     */
    private final Property<Boolean> genSnapToFftBin = bound(false);
    /** WAV-export duration in seconds. */
    private final Property<Double>  genWavDurationSeconds = bound(5.0);
    /** Path to the most recently chosen WAV-export target file. */
    private final Property<String>  genWavPath   = bound(null);
    /** Folder remembered for the generator's "Save WAV" dialog, separate from {@link #genCorrectionsFolder}. */
    private final Property<String>  genWavFolder = bound(null);

    /** Path to the most recently chosen "Play from…" source file (WAV/FLAC/AIFF). */
    private final Property<String>  genPlayFromPath   = bound(null);
    /** Folder remembered for the "Play from…" file picker. */
    private final Property<String>  genPlayFromFolder = bound(null);
    /** Whether the file-player should loop the source on EOF. */
    private final Property<Boolean> genPlayFromLoop = bound(false);

    // -------------------------------------------------------------------------
    // Window geometry — restored on the next launch.  Defaults of 0 / null mean
    // "no saved value yet"; the GUI falls back to its built-in defaults.
    // -------------------------------------------------------------------------

    private final Property<Integer> windowWidth  = bound(0);
    private final Property<Integer> windowHeight = bound(0);
    /** Generator pane width in pixels.  0 = no saved value, GUI uses its default. */
    private final Property<Integer> genPaneWidth = bound(0);
    /** Vertical split weights for the Multifunctional tab (oscilloscope / FFT). */
    @Getter @Setter private int[] multiVSplitWeights;
    /** Collapse state for the three Multifunctional-tab panes — restored on startup. */
    private final Property<Boolean> genPaneCollapsed = bound(false);
    private final Property<Boolean> oscPaneCollapsed = bound(false);
    private final Property<Boolean> fftPaneCollapsed = bound(true);

    /** User-saved oscilloscope presets, keyed by display name.  Insertion
     *  order is preserved so the combo box renders them in the order they
     *  were saved.  Mutations go through {@link #putOscPreset},
     *  {@link #removeOscPreset}, etc. so callers don't have to remember to
     *  call {@link #save()} themselves. */
    @Getter private final Map<String, OscPreset> oscPresets = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // FFT pane state — analyser knobs (FFT-tab + THD-tab), view state (axis
    // ranges, magnitude unit, phase visibility) and CSV save / load paths.
    // -------------------------------------------------------------------------

    private final Property<Integer> fftLength            = bound(65536);
    /** Number of FFT frames to average per analysis.  Special value
     *  {@code Double.POSITIVE_INFINITY} means "forever" (no fixed bound). */
    private final Property<Double>  fftAverages          = bound(4.0);
    private final Property<Boolean> fftStopAfterNEnabled = bound(false);
    private final Property<Integer> fftStopAfterN        = bound(10);
    private final Property<Boolean> fftFundFromGenerator = bound(false);
    private final Property<Boolean> fftLogFreqAxis       = bound(true);
    /** {@code WindowType} enum name. */
    private final Property<WindowType> fftWindow = bound(WindowType.HANN);
    /** {@code FftOverlap} enum name. */
    private final Property<FftOverlap> fftOverlap = bound(FftOverlap.PCT_0);
    private final Property<Boolean> fftCoherentAveraging = bound(true);
    /** {@code MainsSuppression} enum name — mains-hum filter applied to
     *  the captured signal before FFT averaging. */
    private final Property<MainsSuppression> fftMainsSuppression = bound(MainsSuppression.NONE);
    /** When true, the FFT-side frequency-lock loop drives the generator
     *  to keep the fundamental on the nearest FFT bin centre.  Only
     *  takes effect when {@code genSnapToFftBin} AND
     *  {@code fftFundFromGenerator} are also true — both prerequisites
     *  are required for the loop to know which bin to lock onto. */
    private final Property<AlignGenerator> fftAlignGenerator = bound(AlignGenerator.NONE);
    private final Property<Double>  fftDistMinHz         = bound(20.0);
    private final Property<Double>  fftDistMaxHz         = bound(20000.0);
    private final Property<Boolean> fftDistMinEnabled    = bound(false);
    private final Property<Boolean> fftDistMaxEnabled    = bound(false);
    private final Property<Integer> fftThdMaxHarmonic    = bound(9);
    private final Property<Integer> fftCalcMaxHarmonic   = bound(9);
    /** A spectral peak counts as a separate TONE (→ multi-tone averaging path)
     *  only if it is within this many dB of the strongest peak.  A clean tone's
     *  harmonics sit far below and are excluded, so a THD/cal signal stays on
     *  the single-reference phase-lock path; real dual-tone / IMD partners are
     *  comparable in level and survive. */
    private final Property<Double>  fftStrongToneRelDb   = bound(100.0);
    /** Generator frequency-lock-loop PID gains (Kp, Ki [1/s], Kd [s]) —
     *  defaults approximate the proven time-aware integrator; the PID
     *  autotune wizard overwrites them. */
    private final Property<Double>  fftFllKp             = bound(0.5);
    private final Property<Double>  fftFllKi             = bound(0.5);
    private final Property<Double>  fftFllKd             = bound(0.0);
    private final Property<Double>  fftManualFundVrms    = bound(1.0);
    /** Unit the manual-fundamental-amplitude field renders in: {@code mV}, {@code V}, or {@code dBV}. */
    private final Property<AmplitudeUnit> fftManualFundUnit = bound(AmplitudeUnit.V);
    private final Property<Boolean> fftManualFundEnabled = bound(false);
    /** Active analysis channel (L or R) — only one channel shown at a time. */
    private final Property<Channel> fftChannel = bound(Channel.L);
    /** {@code FftMagnitudeUnit} enum name: {@code V}, {@code V_SQRT_HZ}, {@code DBV}, {@code DBFS}. */
    private final Property<FftMagnitudeUnit> fftMagUnit = bound(FftMagnitudeUnit.DBV);
    /** Whether the THD overlay table is shown on top of the spectrum view. */
    private final Property<Boolean> fftDistortionTableVisible = bound(true);
    private final Property<Double>  fftFreqMinHz         = bound(20.0);
    private final Property<Double>  fftFreqMaxHz         = bound(20000.0);
    private final Property<Double>  fftMagTop            = bound(10.0);
    private final Property<Double>  fftMagBottom         = bound(-150.0);
    private final Property<String>  fftSavePath          = bound(null);
    private final Property<String>  fftSaveFolder        = bound(null);
    private final Property<String>  fftLoadPath          = bound(null);
    private final Property<String>  fftLoadFolder        = bound(null);

    /** Path to the row-0 FFT calibration file (.frc), or null when row
     *  0 is empty.  Mirrors the FreqResp pane's row-0 / extras-list
     *  pattern. */
    private final Property<String>  fftCalibrationPath   = bound(null);
    /** Paths to additional calibration files loaded into rows 1..N of
     *  the FFT pane's Load-calibration tab.  Empty strings represent
     *  empty rows that the user added but didn't yet populate. */
    @Getter @Setter private List<String> fftCalibrationPathsExtra = new ArrayList<>();
    /** Row-0 "Active" flag — when true the row's loaded calibration is
     *  applied. */
    private final Property<Boolean> fftCalibrationActive    = bound(false);
    /** Row-0 "With noise" flag — when true the row's correction
     *  applies to every FFT bin (noise floor included), not just the
     *  harmonic dot positions. */
    private final Property<Boolean> fftCalibrationWithNoise = bound(false);
    /** Per-row "Active" flags for the extras rows (rows 1..N). */
    @Getter @Setter private List<Boolean> fftCalibrationActiveExtra    = new ArrayList<>();
    /** Per-row "With noise" flags for the extras rows (rows 1..N). */
    @Getter @Setter private List<Boolean> fftCalibrationWithNoiseExtra = new ArrayList<>();
    /** Packed RGB of the "before-calibration" dot painted next to each
     *  fundamental / harmonic peak when at least one .frc calibration
     *  is loaded.  Default dark blue (0x00, 0x00, 0x80). */
    private final Property<Integer> fftBeforeCalDotColor      = bound(0x000080);
    /** Packed RGB of the "inverted calibration" overlay curve — the
     *  cascaded calibration response negated and anchored at the H2
     *  peak, drawn alongside the spectrum so the user can see what
     *  shape was subtracted.  Default green (0x00, 0x96, 0x00). */
    private final Property<Integer> fftCalOverlayColor        = bound(0x009600);

    // FFT view appearance (FFT tab in Preferences dialog).  Defaults mirror
    // the colours used by FftAnalyzer.exportChart via ChartStyle.
    /** Spectrum trace line width in pixels. */
    private final Property<Double>  fftLineWidth              = bound(1.0);
    /** Harmonic / fundamental dot diameter in pixels. */
    private final Property<Integer> fftHarmonicDotDiameter    = bound(9);
    /** Packed RGB (0xRRGGBB) of the spectrum trace line. */
    private final Property<Integer> fftLineColor              = bound(0x0064C8);  // (0,100,200) blue
    /** Packed RGB of the FFT chart background. */
    private final Property<Integer> fftChartBackgroundColor   = bound(0xFFFFFF);  // white
    /** Packed RGB of the harmonic / fundamental dots. */
    private final Property<Integer> fftHarmonicDotColor       = bound(0xFF0000);  // (255,0,0) blue
    /** Packed RGB of the frequency response / cal-overlay response line. */
    private final Property<Integer> fftFreqRespColor    = bound(0x009600);  // (0,150,0) blue

    /** User-saved FFT presets, same shape and conventions as {@link #oscPresets}. */
    @Getter private final Map<String, FftPreset> fftPresets = new LinkedHashMap<>();
    /** User-saved Frequency-Response presets — same insertion-order map
     *  semantics as the FFT / Scope preset maps so the dropdown shows
     *  entries in the order they were created. */
    @Getter private final Map<String, FreqRespPreset> freqRespPresets = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Frequency Response pane — sweep settings, view state, RIAA + calibration
    // -------------------------------------------------------------------------

    /** Sweep start frequency in Hz.  Default 20 Hz (audible bottom). */
    private final Property<Double>  freqRespStartHz          = bound(20.0);
    /** Sweep stop  frequency in Hz.  Default 20 kHz (audible top). */
    private final Property<Double>  freqRespStopHz           = bound(20000.0);
    /** Generator drive amplitude at the DAC, V RMS. */
    private final Property<Double>  freqRespAmplitudeVrms    = bound(0.5);
    /** Number of log-spaced output frequency points the deconvolution emits.
     *  Default 65536 (64 k) is a safe trade-off between resolution and
     *  memory for a single sweep. */
    private final Property<Integer> freqRespSweepPoints      = bound(65536);
    /** Sweep duration in seconds, excluding lead-in.  Derived from
     *  {@link #freqRespFftSize} + {@link #freqRespLeadInSec} + the half-
     *  second tail; the Settings tab keeps the two in sync.  The
     *  analyzer / wizard still read this field so the rest of the
     *  pipeline doesn't have to know about FFT size. */
    private final Property<Double>  freqRespDurationSec      = bound(5.5);
    /** Deconvolution FFT length (power of 2, 64k … 16M).  Primary control
     *  in the Settings tab — the sweep duration is derived from this so
     *  the analyzer's {@code nextPow2(leadIn + sweep + tail)} lands
     *  exactly on the chosen length (no wasted bins). */
    private final Property<Integer> freqRespFftSize          = bound(524288);
    /** TPDF dither bits applied to the generator before quantisation;
     *  0 disables.  Same convention as the generator pane. */
    private final Property<Integer> freqRespDitherBits       = bound(0);
    /** Silent lead-in prepended to the sweep, in seconds.  Lets the DAC →
     *  ADC chain settle before the first sweep sample lands. */
    private final Property<Double>  freqRespLeadInSec        = bound(0.2);

    /** Whether the left-channel trace is visible on the view (toggle on the
     *  view's header).  L + R are independent toggles, not single-choice. */
    private final Property<Boolean> freqRespLeftVisible      = bound(true);
    /** Whether the right-channel trace is visible on the view. */
    private final Property<Boolean> freqRespRightVisible     = bound(false);
    /** Whether the phase curve (right Y-axis ±180°) is painted. */
    private final Property<Boolean> freqRespPhaseVisible     = bound(false);

    /** Visible frequency window — left edge of the trace area in Hz. */
    private final Property<Double>  freqRespFreqMinHz        = bound(20.0);
    /** Visible frequency window — right edge of the trace area in Hz. */
    private final Property<Double>  freqRespFreqMaxHz        = bound(20000.0);
    /** Visible magnitude window — top edge in dB. */
    private final Property<Double>  freqRespMagTopDb         = bound(20.0);
    /** Visible magnitude window — bottom edge in dB. */
    private final Property<Double>  freqRespMagBotDb         = bound(-140.0);

    /** Maximum frequency the crosshair readout reports, expressed as a
     *  fraction of the sample rate (0.40–0.50).  Default 0.48 clips the
     *  readout at 0.48·Fs so the user doesn't see meaningless magnitude /
     *  phase numbers right at Nyquist where the deconvolution kernel has
     *  no usable energy. */
    private final Property<Double>  freqRespNyquistFraction  = bound(1.0);

    /** Window size (in points) of the moving-average smoothing applied
     *  to the compare-mode (measured − reference) curve.  Affects both
     *  the displayed trace and the anchor / min-max table that the
     *  auto-setup computes.  Clamped to [0, 100]; 0 disables smoothing. */
    private final Property<Integer> freqRespCompareSmoothWindow = bound(6);

    /** When true, the FreqResp view interpolates across each harmonic of
     *  {@link #freqRespNotchBaseHz} (50/60 Hz) before drawing — removes
     *  mains-hum spikes from the displayed response.  Applied per-channel
     *  on the way from raw to displayed copy, so toggling the flag
     *  redraws without re-measuring. */
    private final Property<Boolean> freqRespNotchEnabled = bound(false);
    /** Base frequency of the industrial-noise harmonic comb removed when
     *  {@link #freqRespNotchEnabled}.  Either 50 (EU mains) or 60 (US). */
    private final Property<Integer> freqRespNotchBaseHz  = bound(50);

    /** Trace colour for the measured signal (whichever channel is the
     *  active one — L and R are mutually-exclusive radio toggles, so a
     *  single colour covers both).  Packed RGB int, default {@code #0064C8}
     *  (a saturated blue). */
    private final Property<Integer> freqRespSignalColor     = bound(0x0064C8);
    /** Trace colour for the phase overlay.  Default {@code #FF0000} (red). */
    private final Property<Integer> freqRespPhaseColor      = bound(0xFF0000);
    /** Trace colour for the RIAA / IEC reference curve.  Default
     *  {@code #009600} (a saturated green). */
    private final Property<Integer> freqRespReferenceColor  = bound(0x009600);
    /** Chart background colour.  Default {@code #FFFFFF} (white). */
    private final Property<Integer> freqRespBackgroundColor = bound(0xFFFFFF);

    /** Show RIAA reference curve overlaid on the trace. */
    private final Property<Boolean> freqRespShowRiaa         = bound(false);
    /** When {@link #freqRespShowRiaa}, paint the reverse (playback) curve
     *  instead of the record curve.  Vertical mirror around 0 dB at 1 kHz. */
    private final Property<Boolean> freqRespReverseRiaa      = bound(false);
    /** When {@link #freqRespShowRiaa}, apply the IEC subsonic high-pass
     *  amendment (T4 = 7950 µs) on top of whichever direction is active. */
    private final Property<Boolean> freqRespIecAmendment     = bound(false);
    /** Comparison mode: show measured − reference subtraction trace in
     *  place of the live measurement; auto-zoom to 2 Hz–25 kHz, ±2 dB
     *  over min/max.  Only enabled when a measured result exists. */
    private final Property<Boolean> freqRespCompareMode      = bound(false);

    /** When {@code true}, fresh measurements get divided by the loaded
     *  calibration before being shown.  No effect if no calibration is
     *  loaded.  Defaults on so a loaded calibration takes effect by
     *  default. */
    private final Property<Boolean> freqRespApplyCalibration = bound(true);
    /** Path to the currently-loaded calibration CSV, or {@code null} when
     *  no calibration is active.  Treated as row 0 of the calibration tab's
     *  multi-row list; subsequent rows are stored in
     *  {@link #freqRespCalibrationPathsExtra}. */
    private final Property<String>  freqRespCalibrationPath  = bound(null);
    /** Row-0 "Active" flag — when true the row's loaded calibration is
     *  applied to the displayed trace. */
    private final Property<Boolean> freqRespCalibrationActive = bound(false);
    /** Per-row "Active" flags for the extras rows (1..N) of the
     *  FreqResp pane's Load-calibration tab. */
    @Getter @Setter private List<Boolean> freqRespCalibrationActiveExtra = new ArrayList<>();
    /** Paths to additional calibration files loaded beyond row 0.  Each
     *  entry is divided into the measured signal in sequence (linear-mag
     *  divide, phase subtract) at draw / save time.  Empty when only row 0
     *  is loaded; the row count in the UI is {@code 1 + size()}. */
    @Getter @Setter private List<String> freqRespCalibrationPathsExtra = new ArrayList<>();

    /** Last-used folder for the "Save to..." tab; persisted across launches. */
    private final Property<String>  freqRespSaveFolder = bound(null);
    /** Most-recently-chosen save-to path; restored into the path field at startup. */
    private final Property<String>  freqRespSavePath   = bound(null);
    /** Last-used folder for the "Load from..." tab. */
    private final Property<String>  freqRespLoadFolder = bound(null);
    /** Most-recently-chosen load-from path; restored into the path field at startup. */
    private final Property<String>  freqRespLoadPath   = bound(null);

    /** Zero-based index of the FreqResp pane's currently-selected tab. */
    private final Property<Integer> freqRespActiveTabIndex = bound(0);

    /** When {@code true} this instance is a throwaway copy handed to
     *  {@link PreferencesDialog} for editing: it never loads from / writes to
     *  disk and never starts the save thread.  Only the live {@link #instance()}
     *  singleton is non-detached. */
    private final boolean detached;

    private Preferences() {
        this.detached = false;
        load();
        // Flush any debounced save on JVM exit — saveScheduler is a daemon
        // thread the runtime abandons at shutdown, so a change made within the
        // coalesce window before close would otherwise be lost.
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "prefs-flush"));
    }

    /** Detached constructor for {@link #copyForDialog()}: skips {@link #load()}
     *  and the JVM-shutdown flush hook, and — via {@link #requestSave()} bailing
     *  out on {@code detached} — never starts the save thread. */
    private Preferences(boolean detached) {
        this.detached = detached;
    }

    public static Preferences instance() {
        Preferences local = instance;
        if (local == null) {
            synchronized (Preferences.class) {
                local = instance;
                if (local == null) {
                    local = new Preferences();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Returns the saved preferences for {@code type}, lazily creating a
     * default entry on first access.
     */
    public BackendPrefs prefsFor(AudioBackendType type) {
        synchronized (perBackend) {
            BackendPrefs p = perBackend.get(type);
            if (p == null) {
                p = new BackendPrefs();
                perBackend.put(type, p);
            }
            return p;
        }
    }

    /** Shorthand for {@code prefsFor(getBackend())}. */
    public BackendPrefs current() {
        return prefsFor(backend.get());
    }

    /**
     * Returns a DETACHED working copy seeded with every preference the
     * {@link PreferencesDialog} edits.  The copy never loads from / writes to
     * disk and never starts the save thread, so the dialog mutates it freely;
     * Cancel just drops it, and OK funnels the edited copy back through
     * {@link #applyFromDialog(Preferences)}.  Only for PreferencesDialog.
     */
    Preferences copyForDialog() {
        Preferences c = new Preferences(true);

        c.tabOrientation.set(tabOrientation.get());
        c.smallIconsInMainTab.set(smallIconsInMainTab.get());
        c.fftStrongToneRelDb.set(fftStrongToneRelDb.get());
        c.freqRespNyquistFraction.set(freqRespNyquistFraction.get());
        c.freqRespFreqMaxHz.set(freqRespFreqMaxHz.get());
        c.freqRespFreqMinHz.set(freqRespFreqMinHz.get());
        c.freqRespCompareSmoothWindow.set(freqRespCompareSmoothWindow.get());
        c.freqRespNotchEnabled.set(freqRespNotchEnabled.get());
        c.freqRespNotchBaseHz.set(freqRespNotchBaseHz.get());

        c.oscMeasurementAverageSeconds.set(oscMeasurementAverageSeconds.get());
        c.oscLineWidth.set(oscLineWidth.get());
        c.oscDotDiameter.set(oscDotDiameter.get());
        c.fftLineWidth.set(fftLineWidth.get());
        c.fftHarmonicDotDiameter.set(fftHarmonicDotDiameter.get());

        c.oscLeftChannelColor.set(oscLeftChannelColor.get());
        c.oscRightChannelColor.set(oscRightChannelColor.get());
        c.fftLineColor.set(fftLineColor.get());
        c.fftChartBackgroundColor.set(fftChartBackgroundColor.get());
        c.fftHarmonicDotColor.set(fftHarmonicDotColor.get());
        c.fftFreqRespColor.set(fftFreqRespColor.get());
        c.fftBeforeCalDotColor.set(fftBeforeCalDotColor.get());
        c.fftCalOverlayColor.set(fftCalOverlayColor.get());
        c.freqRespSignalColor.set(freqRespSignalColor.get());
        c.freqRespPhaseColor.set(freqRespPhaseColor.get());
        c.freqRespReferenceColor.set(freqRespReferenceColor.get());
        c.freqRespBackgroundColor.set(freqRespBackgroundColor.get());

        c.backend.set(backend.get());
        for (AudioBackendType t : AudioBackendType.values()) {
            c.prefsFor(t).copyFrom(prefsFor(t));
        }
        return c;
    }

    /**
     * Commits every preference the {@link PreferencesDialog} edited on
     * {@code edit} back into this live instance, firing the bound-property
     * listeners so the views refresh, then persists once.  The dialog's OK
     * handler (re)activates the chosen backend on {@code AudioBackend} — kept
     * there so this state class stays free of the sound/hardware layer.
     * Only for PreferencesDialog.
     */
    void applyFromDialog(Preferences edit) {
        // Backend selection first: the Nyquist clamp below reads current()'s
        // input sample rate, which must reflect the just-chosen backend.
        setBackend(edit.backend.get());
        for (AudioBackendType t : AudioBackendType.values()) {
            prefsFor(t).copyFrom(edit.prefsFor(t));
        }

        setTabOrientation(edit.tabOrientation.get());
        setSmallIconsInMainTab(edit.smallIconsInMainTab.get());
        setFftStrongToneRelDb(edit.fftStrongToneRelDb.get());
        setFreqRespCompareSmoothWindow(edit.freqRespCompareSmoothWindow.get());
        setFreqRespNotchEnabled(edit.freqRespNotchEnabled.get());
        setFreqRespNotchBaseHz(edit.freqRespNotchBaseHz.get());

        setOscMeasurementAverageSeconds(edit.oscMeasurementAverageSeconds.get());
        setOscLineWidth(edit.oscLineWidth.get());
        setOscDotDiameter(edit.oscDotDiameter.get());
        setFftLineWidth(edit.fftLineWidth.get());
        setFftHarmonicDotDiameter(edit.fftHarmonicDotDiameter.get());

        setOscLeftChannelColor(edit.oscLeftChannelColor.get());
        setOscRightChannelColor(edit.oscRightChannelColor.get());
        setFftLineColor(edit.fftLineColor.get());
        setFftChartBackgroundColor(edit.fftChartBackgroundColor.get());
        setFftHarmonicDotColor(edit.fftHarmonicDotColor.get());
        setFftFreqRespColor(edit.fftFreqRespColor.get());
        setFftBeforeCalDotColor(edit.fftBeforeCalDotColor.get());
        setFftCalOverlayColor(edit.fftCalOverlayColor.get());
        setFreqRespSignalColor(edit.freqRespSignalColor.get());
        setFreqRespPhaseColor(edit.freqRespPhaseColor.get());
        setFreqRespReferenceColor(edit.freqRespReferenceColor.get());
        setFreqRespBackgroundColor(edit.freqRespBackgroundColor.get());

        // FreqResp Nyquist fraction + freq-window clamp (lifted from the dialog's
        // old OK handler): if the new max-band drops below the current right
        // edge, pull freqMaxHz (and freqMinHz if needed) in.
        setFreqRespNyquistFraction(edit.freqRespNyquistFraction.get());
        int sr = current().getInputSampleRate();
        double maxBand = (sr > 0 ? sr * 0.5 : 24000.0) * getFreqRespNyquistFraction();
        if (getFreqRespFreqMaxHz() > maxBand) {
            setFreqRespFreqMaxHz(maxBand);
            if (getFreqRespFreqMinHz() > maxBand) {
                setFreqRespFreqMinHz(Math.max(1.0, maxBand * 0.5));
            }
        }

        // One write covers every applied value.
        save();
    }

    // -------------------------------------------------------------------------
    // YAML persistence
    // -------------------------------------------------------------------------

    /** Writes the current preferences to {@link #PREFS_FILE} in the working dir. */
    public synchronized void save() {
        Map<String, Object> root = toMap();
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        try (Writer w = Files.newBufferedWriter(prefsPath())) {
            new Yaml(opts).dump(root, w);
        } catch (IOException e) {
            log.warn("Failed to save preferences to {}: {}", PREFS_FILE, e.getMessage());
        }
    }

    // ─── Auto-saving observable properties (bidi-binding model) ──────────────
    //
    // A parameter that binds to a control is exposed as a Property; a real
    // change auto-persists via requestSave(), so neither a bound control nor the
    // model calls save() by hand.  The view subscribes to the property to react.

    /** Suppresses per-property auto-save while {@link #fromMap} applies a loaded
     *  file, so loading doesn't immediately re-write what it just read. */
    private boolean loading;

    /** Single daemon thread that performs the coalesced write off the caller's
     *  thread.  Per-property reads in {@link #toMap} are atomic reference reads
     *  against the {@code synchronized} {@link #save()}, so a concurrent set is
     *  benign — the next debounced write captures it. */
    private ScheduledExecutorService saveScheduler;
    private ScheduledFuture<?> pendingSave;

    /** Lazily creates the single-thread save scheduler.  A detached copy
     *  ({@link #copyForDialog()}) never reaches here — its {@link #requestSave()}
     *  bails on {@code detached} — so it never starts a thread. */
    private ScheduledExecutorService saveScheduler() {
        if (saveScheduler == null) {
            saveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "prefs-save");
                t.setDaemon(true);
                return t;
            });
        }
        return saveScheduler;
    }

    /** Creates an observable, auto-saving preference property: a real change to
     *  it triggers {@link #requestSave()}. */
    private <T> Property<T> bound(T initial) {
        Property<T> p = new Property<>(initial);
        p.addListener(v -> requestSave());
        return p;
    }

    /** Persists after a bound property changed — a no-op while {@link #load()}
     *  is applying a file.  Debounced: a continuous gesture (zoom / drag /
     *  resize / fast typing) coalesces into a single write
     *  {@link #SAVE_COALESCE_MS} ms after the last change, so binding a
     *  high-frequency parameter doesn't hammer the file. */
    private synchronized void requestSave() {
        if (detached || loading) {
            return;
        }
        if (pendingSave != null) {
            pendingSave.cancel(false);
        }
        pendingSave = saveScheduler().schedule(this::save, SAVE_COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    /** Writes a still-pending debounced save to disk immediately.  Registered
     *  as a JVM shutdown hook so a change made within {@link #SAVE_COALESCE_MS}
     *  of closing the app — whose write is otherwise a daemon-thread task the
     *  runtime abandons at exit — is never lost.  No-op when nothing is pending
     *  (the last change already reached disk, or already running). */
    public synchronized void flush() {
        if (pendingSave != null && pendingSave.cancel(false)) {
            save();
        }
    }

    public AudioBackendType getBackend()       { return backend.get(); }
    public void setBackend(AudioBackendType v) { backend.set(v); }
    public Property<AudioBackendType> backendProperty() { return backend; }

    public String getUiLanguage()              { return uiLanguage.get(); }
    public void setUiLanguage(String v)        { uiLanguage.set(v); }
    public Property<String> uiLanguageProperty() { return uiLanguage; }

    public int getActiveTabIndex()             { return activeTabIndex.get(); }
    public void setActiveTabIndex(int v)       { activeTabIndex.set(v); }
    public Property<Integer> activeTabIndexProperty() { return activeTabIndex; }

    public boolean isCheckForUpdatesOnStartup() { return checkForUpdatesOnStartup.get(); }
    public void setCheckForUpdatesOnStartup(boolean v) { checkForUpdatesOnStartup.set(v); }
    public Property<Boolean> checkForUpdatesOnStartupProperty() { return checkForUpdatesOnStartup; }

    public boolean isIncludeBetaInUpdateChecks() { return includeBetaInUpdateChecks.get(); }
    public void setIncludeBetaInUpdateChecks(boolean v) { includeBetaInUpdateChecks.set(v); }
    public Property<Boolean> includeBetaInUpdateChecksProperty() { return includeBetaInUpdateChecks; }

    public TabOrientation getTabOrientation()  { return tabOrientation.get(); }
    public void setTabOrientation(TabOrientation v) { tabOrientation.set(v); }
    public Property<TabOrientation> tabOrientationProperty() { return tabOrientation; }

    public boolean isSmallIconsInMainTab()     { return smallIconsInMainTab.get(); }
    public void setSmallIconsInMainTab(boolean v) { smallIconsInMainTab.set(v); }
    public Property<Boolean> smallIconsInMainTabProperty() { return smallIconsInMainTab; }

    public int getWindowWidth()                { return windowWidth.get(); }
    public void setWindowWidth(int v)          { windowWidth.set(v); }
    public Property<Integer> windowWidthProperty() { return windowWidth; }

    public int getWindowHeight()               { return windowHeight.get(); }
    public void setWindowHeight(int v)         { windowHeight.set(v); }
    public Property<Integer> windowHeightProperty() { return windowHeight; }

    public int getGenPaneWidth()               { return genPaneWidth.get(); }
    public void setGenPaneWidth(int v)         { genPaneWidth.set(v); }
    public Property<Integer> genPaneWidthProperty() { return genPaneWidth; }

    public boolean isGenPaneCollapsed()        { return genPaneCollapsed.get(); }
    public void setGenPaneCollapsed(boolean v) { genPaneCollapsed.set(v); }
    public Property<Boolean> genPaneCollapsedProperty() { return genPaneCollapsed; }

    public boolean isOscPaneCollapsed()        { return oscPaneCollapsed.get(); }
    public void setOscPaneCollapsed(boolean v) { oscPaneCollapsed.set(v); }
    public Property<Boolean> oscPaneCollapsedProperty() { return oscPaneCollapsed; }

    public boolean isFftPaneCollapsed()        { return fftPaneCollapsed.get(); }
    public void setFftPaneCollapsed(boolean v) { fftPaneCollapsed.set(v); }
    public Property<Boolean> fftPaneCollapsedProperty() { return fftPaneCollapsed; }

    public WindowType getFftWindow()                { return fftWindow.get(); }
    public void setFftWindow(WindowType w)          { fftWindow.set(w); }
    public Property<WindowType> fftWindowProperty() { return fftWindow; }

    public int getFftLength()                  { return fftLength.get(); }
    public void setFftLength(int v)            { fftLength.set(v); }
    public Property<Integer> fftLengthProperty() { return fftLength; }

    public double getFftAverages()             { return fftAverages.get(); }
    public void setFftAverages(double v)       { fftAverages.set(v); }
    public Property<Double> fftAveragesProperty() { return fftAverages; }

    public boolean isFftStopAfterNEnabled()    { return fftStopAfterNEnabled.get(); }
    public void setFftStopAfterNEnabled(boolean v) { fftStopAfterNEnabled.set(v); }
    public Property<Boolean> fftStopAfterNEnabledProperty() { return fftStopAfterNEnabled; }

    public int getFftStopAfterN()              { return fftStopAfterN.get(); }
    public void setFftStopAfterN(int v)        { fftStopAfterN.set(v); }
    public Property<Integer> fftStopAfterNProperty() { return fftStopAfterN; }

    public boolean isFftFundFromGenerator()    { return fftFundFromGenerator.get(); }
    public void setFftFundFromGenerator(boolean v) { fftFundFromGenerator.set(v); }
    public Property<Boolean> fftFundFromGeneratorProperty() { return fftFundFromGenerator; }

    public boolean isFftLogFreqAxis()          { return fftLogFreqAxis.get(); }
    public void setFftLogFreqAxis(boolean v)   { fftLogFreqAxis.set(v); }
    public Property<Boolean> fftLogFreqAxisProperty() { return fftLogFreqAxis; }

    public FftOverlap getFftOverlap()          { return fftOverlap.get(); }
    public void setFftOverlap(FftOverlap v)    { fftOverlap.set(v); }
    public Property<FftOverlap> fftOverlapProperty() { return fftOverlap; }

    public boolean isFftCoherentAveraging()    { return fftCoherentAveraging.get(); }
    public void setFftCoherentAveraging(boolean v) { fftCoherentAveraging.set(v); }
    public Property<Boolean> fftCoherentAveragingProperty() { return fftCoherentAveraging; }

    public MainsSuppression getFftMainsSuppression() { return fftMainsSuppression.get(); }
    public void setFftMainsSuppression(MainsSuppression v) { fftMainsSuppression.set(v); }
    public Property<MainsSuppression> fftMainsSuppressionProperty() { return fftMainsSuppression; }

    public AlignGenerator getFftAlignGenerator() { return fftAlignGenerator.get(); }
    public void setFftAlignGenerator(AlignGenerator v) { fftAlignGenerator.set(v); }
    public Property<AlignGenerator> fftAlignGeneratorProperty() { return fftAlignGenerator; }

    public double getFftDistMinHz()            { return fftDistMinHz.get(); }
    public void setFftDistMinHz(double v)      { fftDistMinHz.set(v); }
    public Property<Double> fftDistMinHzProperty() { return fftDistMinHz; }

    public double getFftDistMaxHz()            { return fftDistMaxHz.get(); }
    public void setFftDistMaxHz(double v)      { fftDistMaxHz.set(v); }
    public Property<Double> fftDistMaxHzProperty() { return fftDistMaxHz; }

    public boolean isFftDistMinEnabled()       { return fftDistMinEnabled.get(); }
    public void setFftDistMinEnabled(boolean v) { fftDistMinEnabled.set(v); }
    public Property<Boolean> fftDistMinEnabledProperty() { return fftDistMinEnabled; }

    public boolean isFftDistMaxEnabled()       { return fftDistMaxEnabled.get(); }
    public void setFftDistMaxEnabled(boolean v) { fftDistMaxEnabled.set(v); }
    public Property<Boolean> fftDistMaxEnabledProperty() { return fftDistMaxEnabled; }

    public int getFftThdMaxHarmonic()          { return fftThdMaxHarmonic.get(); }
    public void setFftThdMaxHarmonic(int v)    { fftThdMaxHarmonic.set(v); }
    public Property<Integer> fftThdMaxHarmonicProperty() { return fftThdMaxHarmonic; }

    public int getFftCalcMaxHarmonic()         { return fftCalcMaxHarmonic.get(); }
    public void setFftCalcMaxHarmonic(int v)   { fftCalcMaxHarmonic.set(v); }
    public Property<Integer> fftCalcMaxHarmonicProperty() { return fftCalcMaxHarmonic; }

    public double getFftStrongToneRelDb()      { return fftStrongToneRelDb.get(); }
    public void setFftStrongToneRelDb(double v) { fftStrongToneRelDb.set(v); }
    public Property<Double> fftStrongToneRelDbProperty() { return fftStrongToneRelDb; }

    public double getFftManualFundVrms()       { return fftManualFundVrms.get(); }
    public void setFftManualFundVrms(double v) { fftManualFundVrms.set(v); }
    public Property<Double> fftManualFundVrmsProperty() { return fftManualFundVrms; }

    public boolean isFftManualFundEnabled()    { return fftManualFundEnabled.get(); }
    public void setFftManualFundEnabled(boolean v) { fftManualFundEnabled.set(v); }
    public Property<Boolean> fftManualFundEnabledProperty() { return fftManualFundEnabled; }

    public double getFftFllKp()                { return fftFllKp.get(); }
    public void setFftFllKp(double v)          { fftFllKp.set(v); }
    public Property<Double> fftFllKpProperty() { return fftFllKp; }

    public double getFftFllKi()                { return fftFllKi.get(); }
    public void setFftFllKi(double v)          { fftFllKi.set(v); }
    public Property<Double> fftFllKiProperty() { return fftFllKi; }

    public double getFftFllKd()                { return fftFllKd.get(); }
    public void setFftFllKd(double v)          { fftFllKd.set(v); }
    public Property<Double> fftFllKdProperty() { return fftFllKd; }

    public AmplitudeUnit getFftManualFundUnit() { return fftManualFundUnit.get(); }
    public void setFftManualFundUnit(AmplitudeUnit v) { fftManualFundUnit.set(v); }
    public Property<AmplitudeUnit> fftManualFundUnitProperty() { return fftManualFundUnit; }

    public Channel getFftChannel()             { return fftChannel.get(); }
    public void setFftChannel(Channel v)       { fftChannel.set(v); }
    public Property<Channel> fftChannelProperty() { return fftChannel; }

    public FftMagnitudeUnit getFftMagUnit()    { return fftMagUnit.get(); }
    public void setFftMagUnit(FftMagnitudeUnit v) { fftMagUnit.set(v); }
    public Property<FftMagnitudeUnit> fftMagUnitProperty() { return fftMagUnit; }

    public boolean isFftDistortionTableVisible() { return fftDistortionTableVisible.get(); }
    public void setFftDistortionTableVisible(boolean v) { fftDistortionTableVisible.set(v); }
    public Property<Boolean> fftDistortionTableVisibleProperty() { return fftDistortionTableVisible; }

    public double getFftFreqMinHz()            { return fftFreqMinHz.get(); }
    public void setFftFreqMinHz(double v)      { fftFreqMinHz.set(v); }
    public Property<Double> fftFreqMinHzProperty() { return fftFreqMinHz; }

    public double getFftFreqMaxHz()            { return fftFreqMaxHz.get(); }
    public void setFftFreqMaxHz(double v)      { fftFreqMaxHz.set(v); }
    public Property<Double> fftFreqMaxHzProperty() { return fftFreqMaxHz; }

    public double getFftMagTop()               { return fftMagTop.get(); }
    public void setFftMagTop(double v)         { fftMagTop.set(v); }
    public Property<Double> fftMagTopProperty() { return fftMagTop; }

    public double getFftMagBottom()            { return fftMagBottom.get(); }
    public void setFftMagBottom(double v)      { fftMagBottom.set(v); }
    public Property<Double> fftMagBottomProperty() { return fftMagBottom; }

    public String getFftSavePath()             { return fftSavePath.get(); }
    public void setFftSavePath(String v)       { fftSavePath.set(v); }
    public Property<String> fftSavePathProperty() { return fftSavePath; }

    public String getFftSaveFolder()           { return fftSaveFolder.get(); }
    public void setFftSaveFolder(String v)     { fftSaveFolder.set(v); }
    public Property<String> fftSaveFolderProperty() { return fftSaveFolder; }

    public String getFftLoadPath()             { return fftLoadPath.get(); }
    public void setFftLoadPath(String v)       { fftLoadPath.set(v); }
    public Property<String> fftLoadPathProperty() { return fftLoadPath; }

    public String getFftLoadFolder()           { return fftLoadFolder.get(); }
    public void setFftLoadFolder(String v)     { fftLoadFolder.set(v); }
    public Property<String> fftLoadFolderProperty() { return fftLoadFolder; }

    public String getFftCalibrationPath()      { return fftCalibrationPath.get(); }
    public void setFftCalibrationPath(String v) { fftCalibrationPath.set(v); }
    public Property<String> fftCalibrationPathProperty() { return fftCalibrationPath; }

    public boolean isFftCalibrationActive()    { return fftCalibrationActive.get(); }
    public void setFftCalibrationActive(boolean v) { fftCalibrationActive.set(v); }
    public Property<Boolean> fftCalibrationActiveProperty() { return fftCalibrationActive; }

    public boolean isFftCalibrationWithNoise() { return fftCalibrationWithNoise.get(); }
    public void setFftCalibrationWithNoise(boolean v) { fftCalibrationWithNoise.set(v); }
    public Property<Boolean> fftCalibrationWithNoiseProperty() { return fftCalibrationWithNoise; }

    public double getFftLineWidth()            { return fftLineWidth.get(); }
    public void setFftLineWidth(double v)      { fftLineWidth.set(v); }
    public Property<Double> fftLineWidthProperty() { return fftLineWidth; }

    public int getFftHarmonicDotDiameter()     { return fftHarmonicDotDiameter.get(); }
    public void setFftHarmonicDotDiameter(int v) { fftHarmonicDotDiameter.set(v); }
    public Property<Integer> fftHarmonicDotDiameterProperty() { return fftHarmonicDotDiameter; }

    public int getFftLineColor()               { return fftLineColor.get(); }
    public void setFftLineColor(int v)         { fftLineColor.set(v); }
    public Property<Integer> fftLineColorProperty() { return fftLineColor; }

    public int getFftChartBackgroundColor()    { return fftChartBackgroundColor.get(); }
    public void setFftChartBackgroundColor(int v) { fftChartBackgroundColor.set(v); }
    public Property<Integer> fftChartBackgroundColorProperty() { return fftChartBackgroundColor; }

    public int getFftHarmonicDotColor()        { return fftHarmonicDotColor.get(); }
    public void setFftHarmonicDotColor(int v)  { fftHarmonicDotColor.set(v); }
    public Property<Integer> fftHarmonicDotColorProperty() { return fftHarmonicDotColor; }

    public int getFftFreqRespColor()           { return fftFreqRespColor.get(); }
    public void setFftFreqRespColor(int v)     { fftFreqRespColor.set(v); }
    public Property<Integer> fftFreqRespColorProperty() { return fftFreqRespColor; }

    public int getFftBeforeCalDotColor()       { return fftBeforeCalDotColor.get(); }
    public void setFftBeforeCalDotColor(int v) { fftBeforeCalDotColor.set(v); }
    public Property<Integer> fftBeforeCalDotColorProperty() { return fftBeforeCalDotColor; }

    public int getFftCalOverlayColor()         { return fftCalOverlayColor.get(); }
    public void setFftCalOverlayColor(int v)   { fftCalOverlayColor.set(v); }
    public Property<Integer> fftCalOverlayColorProperty() { return fftCalOverlayColor; }

    public boolean isOscLeftChannelEnabled()   { return oscLeftChannelEnabled.get(); }
    public void setOscLeftChannelEnabled(boolean v) { oscLeftChannelEnabled.set(v); }
    public Property<Boolean> oscLeftChannelEnabledProperty() { return oscLeftChannelEnabled; }

    public boolean isOscRightChannelEnabled()  { return oscRightChannelEnabled.get(); }
    public void setOscRightChannelEnabled(boolean v) { oscRightChannelEnabled.set(v); }
    public Property<Boolean> oscRightChannelEnabledProperty() { return oscRightChannelEnabled; }

    public boolean isOscLeftAcMode()           { return oscLeftAcMode.get(); }
    public void setOscLeftAcMode(boolean v)    { oscLeftAcMode.set(v); }
    public Property<Boolean> oscLeftAcModeProperty() { return oscLeftAcMode; }

    public boolean isOscRightAcMode()          { return oscRightAcMode.get(); }
    public void setOscRightAcMode(boolean v)   { oscRightAcMode.set(v); }
    public Property<Boolean> oscRightAcModeProperty() { return oscRightAcMode; }

    public double getOscLeftVoltsPerDiv()      { return oscLeftVoltsPerDiv.get(); }
    public void setOscLeftVoltsPerDiv(double v) { oscLeftVoltsPerDiv.set(v); }
    public Property<Double> oscLeftVoltsPerDivProperty() { return oscLeftVoltsPerDiv; }

    public double getOscRightVoltsPerDiv()     { return oscRightVoltsPerDiv.get(); }
    public void setOscRightVoltsPerDiv(double v) { oscRightVoltsPerDiv.set(v); }
    public Property<Double> oscRightVoltsPerDivProperty() { return oscRightVoltsPerDiv; }

    public double getOscTimePerDiv()           { return oscTimePerDiv.get(); }
    public void setOscTimePerDiv(double v)     { oscTimePerDiv.set(v); }
    public Property<Double> oscTimePerDivProperty() { return oscTimePerDiv; }

    public Channel getOscTriggerChannel()      { return oscTriggerChannel.get(); }
    public void setOscTriggerChannel(Channel v) { oscTriggerChannel.set(v); }
    public Property<Channel> oscTriggerChannelProperty() { return oscTriggerChannel; }

    public TriggerEdge getOscTriggerEdge()     { return oscTriggerEdge.get(); }
    public void setOscTriggerEdge(TriggerEdge v) { oscTriggerEdge.set(v); }
    public Property<TriggerEdge> oscTriggerEdgeProperty() { return oscTriggerEdge; }

    public TriggerMode getOscTriggerMode()     { return oscTriggerMode.get(); }
    public void setOscTriggerMode(TriggerMode v) { oscTriggerMode.set(v); }
    public Property<TriggerMode> oscTriggerModeProperty() { return oscTriggerMode; }

    public double getOscTriggerHysteresisDiv() { return oscTriggerHysteresisDiv.get(); }
    public void setOscTriggerHysteresisDiv(double v) { oscTriggerHysteresisDiv.set(v); }
    public Property<Double> oscTriggerHysteresisDivProperty() { return oscTriggerHysteresisDiv; }

    public boolean isOscTriggerHysteresisEnabled() { return oscTriggerHysteresisEnabled.get(); }
    public void setOscTriggerHysteresisEnabled(boolean v) { oscTriggerHysteresisEnabled.set(v); }
    public Property<Boolean> oscTriggerHysteresisEnabledProperty() { return oscTriggerHysteresisEnabled; }

    public boolean isOscShowReconstructedBeat() { return oscShowReconstructedBeat.get(); }
    public void setOscShowReconstructedBeat(boolean v) { oscShowReconstructedBeat.set(v); }
    public Property<Boolean> oscShowReconstructedBeatProperty() { return oscShowReconstructedBeat; }

    public boolean isOscLeftSincInterpEnabled() { return oscLeftSincInterpEnabled.get(); }
    public void setOscLeftSincInterpEnabled(boolean v) { oscLeftSincInterpEnabled.set(v); }
    public Property<Boolean> oscLeftSincInterpEnabledProperty() { return oscLeftSincInterpEnabled; }

    public boolean isOscRightSincInterpEnabled() { return oscRightSincInterpEnabled.get(); }
    public void setOscRightSincInterpEnabled(boolean v) { oscRightSincInterpEnabled.set(v); }
    public Property<Boolean> oscRightSincInterpEnabledProperty() { return oscRightSincInterpEnabled; }

    public MainsSuppression getOscLeftMainsSuppression() { return oscLeftMainsSuppression.get(); }
    public void setOscLeftMainsSuppression(MainsSuppression v) { oscLeftMainsSuppression.set(v); }
    public Property<MainsSuppression> oscLeftMainsSuppressionProperty() { return oscLeftMainsSuppression; }

    public MainsSuppression getOscRightMainsSuppression() { return oscRightMainsSuppression.get(); }
    public void setOscRightMainsSuppression(MainsSuppression v) { oscRightMainsSuppression.set(v); }
    public Property<MainsSuppression> oscRightMainsSuppressionProperty() { return oscRightMainsSuppression; }

    public LpfMode getOscLeftLpf()             { return oscLeftLpf.get(); }
    public void setOscLeftLpf(LpfMode v)       { oscLeftLpf.set(v); }
    public Property<LpfMode> oscLeftLpfProperty() { return oscLeftLpf; }

    public LpfMode getOscRightLpf()            { return oscRightLpf.get(); }
    public void setOscRightLpf(LpfMode v)      { oscRightLpf.set(v); }
    public Property<LpfMode> oscRightLpfProperty() { return oscRightLpf; }

    public double getOscMeasurementAverageSeconds() { return oscMeasurementAverageSeconds.get(); }
    public void setOscMeasurementAverageSeconds(double v) { oscMeasurementAverageSeconds.set(v); }
    public Property<Double> oscMeasurementAverageSecondsProperty() { return oscMeasurementAverageSeconds; }

    public double getOscLineWidth()            { return oscLineWidth.get(); }
    public void setOscLineWidth(double v)      { oscLineWidth.set(v); }
    public Property<Double> oscLineWidthProperty() { return oscLineWidth; }

    public int getOscDotDiameter()             { return oscDotDiameter.get(); }
    public void setOscDotDiameter(int v)       { oscDotDiameter.set(v); }
    public Property<Integer> oscDotDiameterProperty() { return oscDotDiameter; }

    public int getOscLeftChannelColor()        { return oscLeftChannelColor.get(); }
    public void setOscLeftChannelColor(int v)  { oscLeftChannelColor.set(v); }
    public Property<Integer> oscLeftChannelColorProperty() { return oscLeftChannelColor; }

    public int getOscRightChannelColor()       { return oscRightChannelColor.get(); }
    public void setOscRightChannelColor(int v) { oscRightChannelColor.set(v); }
    public Property<Integer> oscRightChannelColorProperty() { return oscRightChannelColor; }

    public double getOscLeftOffsetFrac()       { return oscLeftOffsetFrac.get(); }
    public void setOscLeftOffsetFrac(double v) { oscLeftOffsetFrac.set(v); }
    public Property<Double> oscLeftOffsetFracProperty() { return oscLeftOffsetFrac; }

    public double getOscRightOffsetFrac()      { return oscRightOffsetFrac.get(); }
    public void setOscRightOffsetFrac(double v) { oscRightOffsetFrac.set(v); }
    public Property<Double> oscRightOffsetFracProperty() { return oscRightOffsetFrac; }

    public double getOscTriggerLevelFrac()     { return oscTriggerLevelFrac.get(); }
    public void setOscTriggerLevelFrac(double v) { oscTriggerLevelFrac.set(v); }
    public Property<Double> oscTriggerLevelFracProperty() { return oscTriggerLevelFrac; }

    public double getOscTriggerPositionFrac()  { return oscTriggerPositionFrac.get(); }
    public void setOscTriggerPositionFrac(double v) { oscTriggerPositionFrac.set(v); }
    public Property<Double> oscTriggerPositionFracProperty() { return oscTriggerPositionFrac; }

    public int getScreenshotWidth()            { return screenshotWidth.get(); }
    public void setScreenshotWidth(int v)      { screenshotWidth.set(v); }
    public Property<Integer> screenshotWidthProperty() { return screenshotWidth; }

    public int getScreenshotHeight()           { return screenshotHeight.get(); }
    public void setScreenshotHeight(int v)     { screenshotHeight.set(v); }
    public Property<Integer> screenshotHeightProperty() { return screenshotHeight; }

    public String getScreenshotFolder()        { return screenshotFolder.get(); }
    public void setScreenshotFolder(String v)  { screenshotFolder.set(v); }
    public Property<String> screenshotFolderProperty() { return screenshotFolder; }

    public Channel getOscMeasurementChannel()  { return oscMeasurementChannel.get(); }
    public void setOscMeasurementChannel(Channel v) { oscMeasurementChannel.set(v); }
    public Property<Channel> oscMeasurementChannelProperty() { return oscMeasurementChannel; }

    public boolean isOscShowStats()            { return oscShowStats.get(); }
    public void setOscShowStats(boolean v)     { oscShowStats.set(v); }
    public Property<Boolean> oscShowStatsProperty() { return oscShowStats; }

    public boolean isOscShowMeasurementTable() { return oscShowMeasurementTable.get(); }
    public void setOscShowMeasurementTable(boolean v) { oscShowMeasurementTable.set(v); }
    public Property<Boolean> oscShowMeasurementTableProperty() { return oscShowMeasurementTable; }

    public double getAdcFsVoltageRms()         { return adcFsVoltageRms.get(); }
    public void setAdcFsVoltageRms(double v)   { adcFsVoltageRms.set(v); }
    public Property<Double> adcFsVoltageRmsProperty() { return adcFsVoltageRms; }

    public double getDacFsVoltageRms()         { return dacFsVoltageRms.get(); }
    public void setDacFsVoltageRms(double v)   { dacFsVoltageRms.set(v); }
    public Property<Double> dacFsVoltageRmsProperty() { return dacFsVoltageRms; }

    public String getOscSavePath()             { return oscSavePath.get(); }
    public void setOscSavePath(String v)       { oscSavePath.set(v); }
    public Property<String> oscSavePathProperty() { return oscSavePath; }

    public String getOscSaveFolder()           { return oscSaveFolder.get(); }
    public void setOscSaveFolder(String v)     { oscSaveFolder.set(v); }
    public Property<String> oscSaveFolderProperty() { return oscSaveFolder; }

    public String getOscPlayFromPath()         { return oscPlayFromPath.get(); }
    public void setOscPlayFromPath(String v)   { oscPlayFromPath.set(v); }
    public Property<String> oscPlayFromPathProperty() { return oscPlayFromPath; }

    public String getOscPlayFromFolder()       { return oscPlayFromFolder.get(); }
    public void setOscPlayFromFolder(String v) { oscPlayFromFolder.set(v); }
    public Property<String> oscPlayFromFolderProperty() { return oscPlayFromFolder; }

    public boolean isOscPlayFromLoop()         { return oscPlayFromLoop.get(); }
    public void setOscPlayFromLoop(boolean v)  { oscPlayFromLoop.set(v); }
    public Property<Boolean> oscPlayFromLoopProperty() { return oscPlayFromLoop; }

    public double getOscSaveDurationSeconds()  { return oscSaveDurationSeconds.get(); }
    public void setOscSaveDurationSeconds(double v) { oscSaveDurationSeconds.set(v); }
    public Property<Double> oscSaveDurationSecondsProperty() { return oscSaveDurationSeconds; }

    public GenSignalForm getGenSignalForm()    { return genSignalForm.get(); }
    public void setGenSignalForm(GenSignalForm v) { genSignalForm.set(v); }
    public Property<GenSignalForm> genSignalFormProperty() { return genSignalForm; }

    public double getGenFrequencyHz()          { return genFrequencyHz.get(); }
    public void setGenFrequencyHz(double v)    { genFrequencyHz.set(v); }
    public Property<Double> genFrequencyHzProperty() { return genFrequencyHz; }

    public double getGenDualToneFreq1Hz()      { return genDualToneFreq1Hz.get(); }
    public void setGenDualToneFreq1Hz(double v) { genDualToneFreq1Hz.set(v); }
    public Property<Double> genDualToneFreq1HzProperty() { return genDualToneFreq1Hz; }

    public double getGenDualToneFreq2Hz()      { return genDualToneFreq2Hz.get(); }
    public void setGenDualToneFreq2Hz(double v) { genDualToneFreq2Hz.set(v); }
    public Property<Double> genDualToneFreq2HzProperty() { return genDualToneFreq2Hz; }

    public double getGenDualToneSplitPct()     { return genDualToneSplitPct.get(); }
    public void setGenDualToneSplitPct(double v) { genDualToneSplitPct.set(v); }
    public Property<Double> genDualToneSplitPctProperty() { return genDualToneSplitPct; }

    public double getGenAmplitudeVrms()        { return genAmplitudeVrms.get(); }
    public void setGenAmplitudeVrms(double v)  { genAmplitudeVrms.set(v); }
    public Property<Double> genAmplitudeVrmsProperty() { return genAmplitudeVrms; }

    public AmplitudeUnit getGenAmplitudeUnit() { return genAmplitudeUnit.get(); }
    public void setGenAmplitudeUnit(AmplitudeUnit v) { genAmplitudeUnit.set(v); }
    public Property<AmplitudeUnit> genAmplitudeUnitProperty() { return genAmplitudeUnit; }

    public int getGenDitherBits()              { return genDitherBits.get(); }
    public void setGenDitherBits(int v)        { genDitherBits.set(v); }
    public Property<Integer> genDitherBitsProperty() { return genDitherBits; }

    public double getGenRectangleDuty()        { return genRectangleDuty.get(); }
    public void setGenRectangleDuty(double v)  { genRectangleDuty.set(v); }
    public Property<Double> genRectangleDutyProperty() { return genRectangleDuty; }

    public double getGenTriangleDuty()         { return genTriangleDuty.get(); }
    public void setGenTriangleDuty(double v)   { genTriangleDuty.set(v); }
    public Property<Double> genTriangleDutyProperty() { return genTriangleDuty; }

    public double getGenSweepFreqStartHz()     { return genSweepFreqStartHz.get(); }
    public void setGenSweepFreqStartHz(double v) { genSweepFreqStartHz.set(v); }
    public Property<Double> genSweepFreqStartHzProperty() { return genSweepFreqStartHz; }

    public double getGenSweepFreqEndHz()       { return genSweepFreqEndHz.get(); }
    public void setGenSweepFreqEndHz(double v) { genSweepFreqEndHz.set(v); }
    public Property<Double> genSweepFreqEndHzProperty() { return genSweepFreqEndHz; }

    public double getGenSweepDurationSec()     { return genSweepDurationSec.get(); }
    public void setGenSweepDurationSec(double v) { genSweepDurationSec.set(v); }
    public Property<Double> genSweepDurationSecProperty() { return genSweepDurationSec; }

    public boolean isGenSweepLoop()            { return genSweepLoop.get(); }
    public void setGenSweepLoop(boolean v)     { genSweepLoop.set(v); }
    public Property<Boolean> genSweepLoopProperty() { return genSweepLoop; }

    public double getGenSweepFadeInSec()       { return genSweepFadeInSec.get(); }
    public void setGenSweepFadeInSec(double v) { genSweepFadeInSec.set(v); }
    public Property<Double> genSweepFadeInSecProperty() { return genSweepFadeInSec; }

    public double getGenSweepFadeOutSec()      { return genSweepFadeOutSec.get(); }
    public void setGenSweepFadeOutSec(double v) { genSweepFadeOutSec.set(v); }
    public Property<Double> genSweepFadeOutSecProperty() { return genSweepFadeOutSec; }

    public boolean isGenSnapToFftBin()         { return genSnapToFftBin.get(); }
    public void setGenSnapToFftBin(boolean v)  { genSnapToFftBin.set(v); }
    public Property<Boolean> genSnapToFftBinProperty() { return genSnapToFftBin; }

    public double getGenWavDurationSeconds()   { return genWavDurationSeconds.get(); }
    public void setGenWavDurationSeconds(double v) { genWavDurationSeconds.set(v); }
    public Property<Double> genWavDurationSecondsProperty() { return genWavDurationSeconds; }

    public boolean isGenPlayFromLoop()         { return genPlayFromLoop.get(); }
    public void setGenPlayFromLoop(boolean v)  { genPlayFromLoop.set(v); }
    public Property<Boolean> genPlayFromLoopProperty() { return genPlayFromLoop; }

    public String getGenCorrectionsCsv()       { return genCorrectionsCsv.get(); }
    public void setGenCorrectionsCsv(String v) { genCorrectionsCsv.set(v); }
    public Property<String> genCorrectionsCsvProperty() { return genCorrectionsCsv; }

    public String getGenCorrectionsFolder()    { return genCorrectionsFolder.get(); }
    public void setGenCorrectionsFolder(String v) { genCorrectionsFolder.set(v); }
    public Property<String> genCorrectionsFolderProperty() { return genCorrectionsFolder; }

    public String getGenWavPath()              { return genWavPath.get(); }
    public void setGenWavPath(String v)        { genWavPath.set(v); }
    public Property<String> genWavPathProperty() { return genWavPath; }

    public String getGenWavFolder()            { return genWavFolder.get(); }
    public void setGenWavFolder(String v)      { genWavFolder.set(v); }
    public Property<String> genWavFolderProperty() { return genWavFolder; }

    public String getGenPlayFromPath()         { return genPlayFromPath.get(); }
    public void setGenPlayFromPath(String v)   { genPlayFromPath.set(v); }
    public Property<String> genPlayFromPathProperty() { return genPlayFromPath; }

    public String getGenPlayFromFolder()       { return genPlayFromFolder.get(); }
    public void setGenPlayFromFolder(String v) { genPlayFromFolder.set(v); }
    public Property<String> genPlayFromFolderProperty() { return genPlayFromFolder; }

    public double getFreqRespStartHz()         { return freqRespStartHz.get(); }
    public void setFreqRespStartHz(double v)   { freqRespStartHz.set(v); }
    public Property<Double> freqRespStartHzProperty() { return freqRespStartHz; }

    public double getFreqRespStopHz()          { return freqRespStopHz.get(); }
    public void setFreqRespStopHz(double v)    { freqRespStopHz.set(v); }
    public Property<Double> freqRespStopHzProperty() { return freqRespStopHz; }

    public double getFreqRespAmplitudeVrms()   { return freqRespAmplitudeVrms.get(); }
    public void setFreqRespAmplitudeVrms(double v) { freqRespAmplitudeVrms.set(v); }
    public Property<Double> freqRespAmplitudeVrmsProperty() { return freqRespAmplitudeVrms; }

    public int getFreqRespSweepPoints()        { return freqRespSweepPoints.get(); }
    public void setFreqRespSweepPoints(int v)  { freqRespSweepPoints.set(v); }
    public Property<Integer> freqRespSweepPointsProperty() { return freqRespSweepPoints; }

    public int getFreqRespFftSize()            { return freqRespFftSize.get(); }
    public void setFreqRespFftSize(int v)      { freqRespFftSize.set(v); }
    public Property<Integer> freqRespFftSizeProperty() { return freqRespFftSize; }

    public int getFreqRespDitherBits()         { return freqRespDitherBits.get(); }
    public void setFreqRespDitherBits(int v)   { freqRespDitherBits.set(v); }
    public Property<Integer> freqRespDitherBitsProperty() { return freqRespDitherBits; }

    public double getFreqRespLeadInSec()       { return freqRespLeadInSec.get(); }
    public void setFreqRespLeadInSec(double v) { freqRespLeadInSec.set(v); }
    public Property<Double> freqRespLeadInSecProperty() { return freqRespLeadInSec; }

    public double getFreqRespNyquistFraction() { return freqRespNyquistFraction.get(); }
    public void setFreqRespNyquistFraction(double v) { freqRespNyquistFraction.set(v); }
    public Property<Double> freqRespNyquistFractionProperty() { return freqRespNyquistFraction; }

    public int getFreqRespCompareSmoothWindow() { return freqRespCompareSmoothWindow.get(); }
    public void setFreqRespCompareSmoothWindow(int v) { freqRespCompareSmoothWindow.set(v); }
    public Property<Integer> freqRespCompareSmoothWindowProperty() { return freqRespCompareSmoothWindow; }

    public boolean isFreqRespNotchEnabled()    { return freqRespNotchEnabled.get(); }
    public void setFreqRespNotchEnabled(boolean v) { freqRespNotchEnabled.set(v); }
    public Property<Boolean> freqRespNotchEnabledProperty() { return freqRespNotchEnabled; }

    public int getFreqRespNotchBaseHz()        { return freqRespNotchBaseHz.get(); }
    public void setFreqRespNotchBaseHz(int v)  { freqRespNotchBaseHz.set(v); }
    public Property<Integer> freqRespNotchBaseHzProperty() { return freqRespNotchBaseHz; }

    public int getFreqRespSignalColor()        { return freqRespSignalColor.get(); }
    public void setFreqRespSignalColor(int v)  { freqRespSignalColor.set(v); }
    public Property<Integer> freqRespSignalColorProperty() { return freqRespSignalColor; }

    public int getFreqRespPhaseColor()         { return freqRespPhaseColor.get(); }
    public void setFreqRespPhaseColor(int v)   { freqRespPhaseColor.set(v); }
    public Property<Integer> freqRespPhaseColorProperty() { return freqRespPhaseColor; }

    public int getFreqRespReferenceColor()     { return freqRespReferenceColor.get(); }
    public void setFreqRespReferenceColor(int v) { freqRespReferenceColor.set(v); }
    public Property<Integer> freqRespReferenceColorProperty() { return freqRespReferenceColor; }

    public int getFreqRespBackgroundColor()    { return freqRespBackgroundColor.get(); }
    public void setFreqRespBackgroundColor(int v) { freqRespBackgroundColor.set(v); }
    public Property<Integer> freqRespBackgroundColorProperty() { return freqRespBackgroundColor; }

    public boolean isFreqRespShowRiaa()        { return freqRespShowRiaa.get(); }
    public void setFreqRespShowRiaa(boolean v) { freqRespShowRiaa.set(v); }
    public Property<Boolean> freqRespShowRiaaProperty() { return freqRespShowRiaa; }

    public boolean isFreqRespReverseRiaa()     { return freqRespReverseRiaa.get(); }
    public void setFreqRespReverseRiaa(boolean v) { freqRespReverseRiaa.set(v); }
    public Property<Boolean> freqRespReverseRiaaProperty() { return freqRespReverseRiaa; }

    public boolean isFreqRespIecAmendment()    { return freqRespIecAmendment.get(); }
    public void setFreqRespIecAmendment(boolean v) { freqRespIecAmendment.set(v); }
    public Property<Boolean> freqRespIecAmendmentProperty() { return freqRespIecAmendment; }

    public boolean isFreqRespCompareMode()     { return freqRespCompareMode.get(); }
    public void setFreqRespCompareMode(boolean v) { freqRespCompareMode.set(v); }
    public Property<Boolean> freqRespCompareModeProperty() { return freqRespCompareMode; }

    public double getFreqRespDurationSec()     { return freqRespDurationSec.get(); }
    public void setFreqRespDurationSec(double v) { freqRespDurationSec.set(v); }
    public Property<Double> freqRespDurationSecProperty() { return freqRespDurationSec; }

    public boolean isFreqRespLeftVisible()     { return freqRespLeftVisible.get(); }
    public void setFreqRespLeftVisible(boolean v) { freqRespLeftVisible.set(v); }
    public Property<Boolean> freqRespLeftVisibleProperty() { return freqRespLeftVisible; }

    public boolean isFreqRespRightVisible()    { return freqRespRightVisible.get(); }
    public void setFreqRespRightVisible(boolean v) { freqRespRightVisible.set(v); }
    public Property<Boolean> freqRespRightVisibleProperty() { return freqRespRightVisible; }

    public boolean isFreqRespPhaseVisible()    { return freqRespPhaseVisible.get(); }
    public void setFreqRespPhaseVisible(boolean v) { freqRespPhaseVisible.set(v); }
    public Property<Boolean> freqRespPhaseVisibleProperty() { return freqRespPhaseVisible; }

    public double getFreqRespFreqMinHz()       { return freqRespFreqMinHz.get(); }
    public void setFreqRespFreqMinHz(double v) { freqRespFreqMinHz.set(v); }
    public Property<Double> freqRespFreqMinHzProperty() { return freqRespFreqMinHz; }

    public double getFreqRespFreqMaxHz()       { return freqRespFreqMaxHz.get(); }
    public void setFreqRespFreqMaxHz(double v) { freqRespFreqMaxHz.set(v); }
    public Property<Double> freqRespFreqMaxHzProperty() { return freqRespFreqMaxHz; }

    public double getFreqRespMagTopDb()        { return freqRespMagTopDb.get(); }
    public void setFreqRespMagTopDb(double v)  { freqRespMagTopDb.set(v); }
    public Property<Double> freqRespMagTopDbProperty() { return freqRespMagTopDb; }

    public double getFreqRespMagBotDb()        { return freqRespMagBotDb.get(); }
    public void setFreqRespMagBotDb(double v)  { freqRespMagBotDb.set(v); }
    public Property<Double> freqRespMagBotDbProperty() { return freqRespMagBotDb; }

    public boolean isFreqRespApplyCalibration() { return freqRespApplyCalibration.get(); }
    public void setFreqRespApplyCalibration(boolean v) { freqRespApplyCalibration.set(v); }
    public Property<Boolean> freqRespApplyCalibrationProperty() { return freqRespApplyCalibration; }

    public String getFreqRespCalibrationPath() { return freqRespCalibrationPath.get(); }
    public void setFreqRespCalibrationPath(String v) { freqRespCalibrationPath.set(v); }
    public Property<String> freqRespCalibrationPathProperty() { return freqRespCalibrationPath; }

    public boolean isFreqRespCalibrationActive() { return freqRespCalibrationActive.get(); }
    public void setFreqRespCalibrationActive(boolean v) { freqRespCalibrationActive.set(v); }
    public Property<Boolean> freqRespCalibrationActiveProperty() { return freqRespCalibrationActive; }

    public String getFreqRespSaveFolder()      { return freqRespSaveFolder.get(); }
    public void setFreqRespSaveFolder(String v) { freqRespSaveFolder.set(v); }
    public Property<String> freqRespSaveFolderProperty() { return freqRespSaveFolder; }

    public String getFreqRespSavePath()        { return freqRespSavePath.get(); }
    public void setFreqRespSavePath(String v)  { freqRespSavePath.set(v); }
    public Property<String> freqRespSavePathProperty() { return freqRespSavePath; }

    public String getFreqRespLoadFolder()      { return freqRespLoadFolder.get(); }
    public void setFreqRespLoadFolder(String v) { freqRespLoadFolder.set(v); }
    public Property<String> freqRespLoadFolderProperty() { return freqRespLoadFolder; }

    public String getFreqRespLoadPath()        { return freqRespLoadPath.get(); }
    public void setFreqRespLoadPath(String v)  { freqRespLoadPath.set(v); }
    public Property<String> freqRespLoadPathProperty() { return freqRespLoadPath; }

    public int getFreqRespActiveTabIndex()     { return freqRespActiveTabIndex.get(); }
    public void setFreqRespActiveTabIndex(int v) { freqRespActiveTabIndex.set(v); }
    public Property<Integer> freqRespActiveTabIndexProperty() { return freqRespActiveTabIndex; }

    /** Loads preferences from {@link #PREFS_FILE} if present.  No-op if missing or unreadable. */
    public synchronized void load() {
        Path path = prefsPath();
        if (!Files.exists(path)) return;
        try (Reader r = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(r);
            if (loaded instanceof Map<?, ?> root) {
                loading = true;
                try {
                    fromMap(root);
                } finally {
                    loading = false;
                }
                log.info("Preferences loaded from {}", path.toAbsolutePath());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load preferences from {}: {}", path, e.getMessage());
        }
    }

    private Path prefsPath() {
        return Paths.get(System.getProperty("user.dir"), PREFS_FILE);
    }

    private Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("backend",                backend.get().name());
        if (uiLanguage.get() != null) root.put("uiLanguage", uiLanguage.get());
        root.put("tabOrientation", tabOrientation.get().name());
        root.put("activeTabIndex", activeTabIndex.get());
        root.put("smallIconsInMainTab", smallIconsInMainTab.get());
        root.put("checkForUpdatesOnStartup",  checkForUpdatesOnStartup.get());
        root.put("includeBetaInUpdateChecks", includeBetaInUpdateChecks.get());
        root.put("windowWidth",            windowWidth.get());
        root.put("windowHeight",           windowHeight.get());
        if (genPaneWidth.get() > 0) root.put("genPaneWidth", genPaneWidth.get());
        if (multiVSplitWeights != null) root.put("multiVSplitWeights", intArrayToList(multiVSplitWeights));
        root.put("genPaneCollapsed", genPaneCollapsed.get());
        root.put("oscPaneCollapsed", oscPaneCollapsed.get());
        root.put("fftPaneCollapsed", fftPaneCollapsed.get());
        root.put("oscLeftChannelEnabled",  oscLeftChannelEnabled.get());
        root.put("oscRightChannelEnabled", oscRightChannelEnabled.get());
        root.put("oscLeftAcMode",          oscLeftAcMode.get());
        root.put("oscRightAcMode",         oscRightAcMode.get());
        root.put("oscLeftVoltsPerDiv",  oscLeftVoltsPerDiv.get());
        root.put("oscRightVoltsPerDiv", oscRightVoltsPerDiv.get());
        root.put("oscTimePerDiv",       oscTimePerDiv.get());
        root.put("oscTriggerChannel",      oscTriggerChannel.get().name());
        root.put("oscTriggerEdge",         oscTriggerEdge.get().name());
        root.put("oscTriggerMode",         oscTriggerMode.get().name());
        root.put("oscTriggerHysteresisDiv",     oscTriggerHysteresisDiv.get());
        root.put("oscTriggerHysteresisEnabled", oscTriggerHysteresisEnabled.get());
        root.put("oscShowReconstructedBeat",    oscShowReconstructedBeat.get());
        root.put("oscLeftSincInterpEnabled",  oscLeftSincInterpEnabled.get());
        root.put("oscRightSincInterpEnabled", oscRightSincInterpEnabled.get());
        root.put("oscLeftMainsSuppression",  oscLeftMainsSuppression.get().name());
        root.put("oscRightMainsSuppression", oscRightMainsSuppression.get().name());
        root.put("oscLeftLpf",  oscLeftLpf.get().name());
        root.put("oscRightLpf", oscRightLpf.get().name());
        root.put("oscLeftOffsetFrac",      oscLeftOffsetFrac.get());
        root.put("oscRightOffsetFrac",     oscRightOffsetFrac.get());
        root.put("oscTriggerLevelFrac",    oscTriggerLevelFrac.get());
        root.put("oscTriggerPositionFrac", oscTriggerPositionFrac.get());
        root.put("oscMeasurementAverageSeconds", oscMeasurementAverageSeconds.get());
        root.put("oscMeasurementChannel",        oscMeasurementChannel.get().name());
        root.put("oscShowStats",                 oscShowStats.get());
        root.put("oscShowMeasurementTable",      oscShowMeasurementTable.get());
        root.put("adcFsVoltageRms",              adcFsVoltageRms.get());
        root.put("dacFsVoltageRms",              dacFsVoltageRms.get());
        root.put("genSignalForm",                genSignalForm.get().name());
        root.put("genFrequencyHz",               genFrequencyHz.get());
        root.put("genDualToneFreq1Hz",           genDualToneFreq1Hz.get());
        root.put("genDualToneFreq2Hz",           genDualToneFreq2Hz.get());
        root.put("genDualToneSplitPct",          genDualToneSplitPct.get());
        root.put("genAmplitudeVrms",             genAmplitudeVrms.get());
        root.put("genAmplitudeUnit",             genAmplitudeUnit.get().name());
        root.put("genDitherBits",                genDitherBits.get());
        if (genCorrectionsCsv.get()    != null) root.put("genCorrectionsCsv",    genCorrectionsCsv.get());
        if (genCorrectionsFolder.get() != null) root.put("genCorrectionsFolder", genCorrectionsFolder.get());
        root.put("genRectangleDuty",      genRectangleDuty.get());
        root.put("genTriangleDuty",       genTriangleDuty.get());
        root.put("genSweepFreqStartHz",   genSweepFreqStartHz.get());
        root.put("genSweepFreqEndHz",     genSweepFreqEndHz.get());
        root.put("genSweepDurationSec",   genSweepDurationSec.get());
        root.put("genSweepLoop",          genSweepLoop.get());
        root.put("genSweepFadeInSec",     genSweepFadeInSec.get());
        root.put("genSweepFadeOutSec",    genSweepFadeOutSec.get());
        root.put("genSnapToFftBin",       genSnapToFftBin.get());
        root.put("genWavDurationSeconds", genWavDurationSeconds.get());
        if (genWavPath.get()   != null) root.put("genWavPath",   genWavPath.get());
        if (genWavFolder.get() != null) root.put("genWavFolder", genWavFolder.get());
        if (genPlayFromPath.get()   != null) root.put("genPlayFromPath",   genPlayFromPath.get());
        if (genPlayFromFolder.get() != null) root.put("genPlayFromFolder", genPlayFromFolder.get());
        root.put("genPlayFromLoop", genPlayFromLoop.get());
        if (oscSavePath.get()   != null) root.put("oscSavePath",   oscSavePath.get());
        if (oscSaveFolder.get() != null) root.put("oscSaveFolder", oscSaveFolder.get());
        root.put("oscSaveDurationSeconds", oscSaveDurationSeconds.get());
        if (oscPlayFromPath.get()   != null) root.put("oscPlayFromPath",   oscPlayFromPath.get());
        if (oscPlayFromFolder.get() != null) root.put("oscPlayFromFolder", oscPlayFromFolder.get());
        root.put("oscPlayFromLoop", oscPlayFromLoop.get());
        root.put("oscLineWidth",                 oscLineWidth.get());
        root.put("oscDotDiameter",               oscDotDiameter.get());
        root.put("oscLeftChannelColor",          formatHtmlColor(oscLeftChannelColor.get()));
        root.put("oscRightChannelColor",         formatHtmlColor(oscRightChannelColor.get()));
        if (screenshotWidth.get()  > 0)   root.put("screenshotWidth",  screenshotWidth.get());
        if (screenshotHeight.get() > 0)   root.put("screenshotHeight", screenshotHeight.get());
        if (screenshotFolder.get() != null) root.put("screenshotFolder", screenshotFolder.get());

        if (!oscPresets.isEmpty()) {
            Map<String, Object> presetsMap = new LinkedHashMap<>();
            for (Map.Entry<String, OscPreset> e : oscPresets.entrySet()) {
                OscPreset p = e.getValue();
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("leftChannelEnabled",     p.isLeftChannelEnabled());
                pm.put("rightChannelEnabled",    p.isRightChannelEnabled());
                pm.put("leftAcMode",             p.isLeftAcMode());
                pm.put("rightAcMode",            p.isRightAcMode());
                pm.put("leftSincInterpEnabled",  p.isLeftSincInterpEnabled());
                pm.put("rightSincInterpEnabled", p.isRightSincInterpEnabled());
                pm.put("leftMainsSuppression",   p.getLeftMainsSuppression().name());
                pm.put("rightMainsSuppression",  p.getRightMainsSuppression().name());
                pm.put("leftLpf",                p.getLeftLpf().name());
                pm.put("rightLpf",               p.getRightLpf().name());
                pm.put("leftVoltsPerDiv",        p.getLeftVoltsPerDiv());
                pm.put("rightVoltsPerDiv",       p.getRightVoltsPerDiv());
                pm.put("leftOffsetFrac",         p.getLeftOffsetFrac());
                pm.put("rightOffsetFrac",        p.getRightOffsetFrac());
                pm.put("timePerDiv",             p.getTimePerDiv());
                pm.put("triggerPositionFrac",    p.getTriggerPositionFrac());
                pm.put("triggerChannel",         p.getTriggerChannel().name());
                pm.put("triggerEdge",            p.getTriggerEdge().name());
                pm.put("triggerMode",            p.getTriggerMode().name());
                pm.put("triggerLevelFrac",       p.getTriggerLevelFrac());
                presetsMap.put(e.getKey(), pm);
            }
            root.put("oscPresets", presetsMap);
        }

        // ---- FFT pane state (toMap)
        root.put("fftLength",                 fftLength.get());
        root.put("fftAverages",               fftAverages.get());
        root.put("fftStopAfterNEnabled",      fftStopAfterNEnabled.get());
        root.put("fftStopAfterN",             fftStopAfterN.get());
        root.put("fftFundFromGenerator",      fftFundFromGenerator.get());
        root.put("fftLogFreqAxis",            fftLogFreqAxis.get());
        root.put("fftWindow",                 fftWindow.get().name());
        root.put("fftOverlap",                fftOverlap.get().name());
        root.put("fftCoherentAveraging",      fftCoherentAveraging.get());
        root.put("fftMainsSuppression",       fftMainsSuppression.get().name());
        root.put("fftAlignGenerator",         fftAlignGenerator.get().name());
        root.put("fftDistMinHz",              fftDistMinHz.get());
        root.put("fftDistMaxHz",              fftDistMaxHz.get());
        root.put("fftDistMinEnabled",         fftDistMinEnabled.get());
        root.put("fftDistMaxEnabled",         fftDistMaxEnabled.get());
        root.put("fftThdMaxHarmonic",         fftThdMaxHarmonic.get());
        root.put("fftCalcMaxHarmonic",        fftCalcMaxHarmonic.get());
        root.put("fftStrongToneRelDb",        fftStrongToneRelDb.get());
        root.put("fftFllKp",                  fftFllKp.get());
        root.put("fftFllKi",                  fftFllKi.get());
        root.put("fftFllKd",                  fftFllKd.get());
        root.put("fftManualFundVrms",         fftManualFundVrms.get());
        root.put("fftManualFundUnit",         fftManualFundUnit.get().name());
        root.put("fftManualFundEnabled",      fftManualFundEnabled.get());
        root.put("fftChannel",                fftChannel.get().name());
        root.put("fftMagUnit",                fftMagUnit.get().name());
        root.put("fftDistortionTableVisible", fftDistortionTableVisible.get());
        root.put("fftFreqMinHz",              fftFreqMinHz.get());
        root.put("fftFreqMaxHz",              fftFreqMaxHz.get());
        root.put("fftMagTop",                 fftMagTop.get());
        root.put("fftMagBottom",              fftMagBottom.get());
        if (fftSavePath.get()   != null) root.put("fftSavePath",   fftSavePath.get());
        if (fftSaveFolder.get() != null) root.put("fftSaveFolder", fftSaveFolder.get());
        if (fftLoadPath.get()   != null) root.put("fftLoadPath",   fftLoadPath.get());
        if (fftLoadFolder.get() != null) root.put("fftLoadFolder", fftLoadFolder.get());
        if (fftCalibrationPath.get() != null) root.put("fftCalibrationPath", fftCalibrationPath.get());
        root.put("fftCalibrationActive",    fftCalibrationActive.get());
        root.put("fftCalibrationWithNoise", fftCalibrationWithNoise.get());
        if (fftCalibrationActiveExtra != null && !fftCalibrationActiveExtra.isEmpty()) {
            root.put("fftCalibrationActiveExtra", new ArrayList<>(fftCalibrationActiveExtra));
        }
        if (fftCalibrationWithNoiseExtra != null && !fftCalibrationWithNoiseExtra.isEmpty()) {
            root.put("fftCalibrationWithNoiseExtra", new ArrayList<>(fftCalibrationWithNoiseExtra));
        }
        if (fftCalibrationPathsExtra != null && !fftCalibrationPathsExtra.isEmpty()) {
            root.put("fftCalibrationPathsExtra", new ArrayList<>(fftCalibrationPathsExtra));
        }
        root.put("fftBeforeCalDotColor", fftBeforeCalDotColor.get());
        root.put("fftCalOverlayColor",   fftCalOverlayColor.get());
        root.put("fftLineWidth",              fftLineWidth.get());
        root.put("fftHarmonicDotDiameter",    fftHarmonicDotDiameter.get());
        root.put("fftLineColor",              formatHtmlColor(fftLineColor.get()));
        root.put("fftChartBackgroundColor",   formatHtmlColor(fftChartBackgroundColor.get()));
        root.put("fftHarmonicDotColor",       formatHtmlColor(fftHarmonicDotColor.get()));
        root.put("fftFreqRespColor",          formatHtmlColor(fftFreqRespColor.get()));

        // ---- Frequency Response pane --------------------------------------
        root.put("freqRespStartHz",           freqRespStartHz.get());
        root.put("freqRespStopHz",            freqRespStopHz.get());
        root.put("freqRespAmplitudeVrms",     freqRespAmplitudeVrms.get());
        root.put("freqRespSweepPoints",       freqRespSweepPoints.get());
        root.put("freqRespDurationSec",       freqRespDurationSec.get());
        root.put("freqRespFftSize",           freqRespFftSize.get());
        root.put("freqRespDitherBits",        freqRespDitherBits.get());
        root.put("freqRespLeadInSec",         freqRespLeadInSec.get());
        root.put("freqRespLeftVisible",       freqRespLeftVisible.get());
        root.put("freqRespRightVisible",      freqRespRightVisible.get());
        root.put("freqRespPhaseVisible",      freqRespPhaseVisible.get());
        root.put("freqRespFreqMinHz",         freqRespFreqMinHz.get());
        root.put("freqRespFreqMaxHz",         freqRespFreqMaxHz.get());
        root.put("freqRespMagTopDb",          freqRespMagTopDb.get());
        root.put("freqRespMagBotDb",          freqRespMagBotDb.get());
        root.put("freqRespNyquistFraction",   freqRespNyquistFraction.get());
        root.put("freqRespCompareSmoothWindow", freqRespCompareSmoothWindow.get());
        root.put("freqRespNotchEnabled",        freqRespNotchEnabled.get());
        root.put("freqRespNotchBaseHz",         freqRespNotchBaseHz.get());
        root.put("freqRespSignalColor",         freqRespSignalColor.get());
        root.put("freqRespPhaseColor",          freqRespPhaseColor.get());
        root.put("freqRespReferenceColor",      freqRespReferenceColor.get());
        root.put("freqRespBackgroundColor",     freqRespBackgroundColor.get());
        // Note: freqRespShowRiaa is intentionally NOT persisted — it always
        // starts unchecked on a fresh session.
        root.put("freqRespReverseRiaa",       freqRespReverseRiaa.get());
        root.put("freqRespIecAmendment",      freqRespIecAmendment.get());
        root.put("freqRespCompareMode",       freqRespCompareMode.get());
        root.put("freqRespApplyCalibration",  freqRespApplyCalibration.get());
        if (freqRespCalibrationPath.get() != null) root.put("freqRespCalibrationPath", freqRespCalibrationPath.get());
        root.put("freqRespCalibrationActive", freqRespCalibrationActive.get());
        if (freqRespCalibrationActiveExtra != null && !freqRespCalibrationActiveExtra.isEmpty()) {
            root.put("freqRespCalibrationActiveExtra", new ArrayList<>(freqRespCalibrationActiveExtra));
        }
        if (freqRespCalibrationPathsExtra != null && !freqRespCalibrationPathsExtra.isEmpty()) {
            root.put("freqRespCalibrationPathsExtra", new ArrayList<>(freqRespCalibrationPathsExtra));
        }
        if (freqRespSaveFolder.get()      != null) root.put("freqRespSaveFolder",      freqRespSaveFolder.get());
        if (freqRespSavePath.get()        != null) root.put("freqRespSavePath",        freqRespSavePath.get());
        if (freqRespLoadFolder.get()      != null) root.put("freqRespLoadFolder",      freqRespLoadFolder.get());
        if (freqRespLoadPath.get()        != null) root.put("freqRespLoadPath",        freqRespLoadPath.get());
        root.put("freqRespActiveTabIndex",    freqRespActiveTabIndex.get());

        if (!fftPresets.isEmpty()) {
            Map<String, Object> fpMap = new LinkedHashMap<>();
            for (Map.Entry<String, FftPreset> e : fftPresets.entrySet()) {
                FftPreset p = e.getValue();
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("channel",           p.getChannel().name());
                pm.put("magUnit",           p.getMagUnit().name());
                pm.put("logFreqAxis",       p.isLogFreqAxis());
                pm.put("freqMinHz",         p.getFreqMinHz());
                pm.put("freqMaxHz",         p.getFreqMaxHz());
                pm.put("magTop",            p.getMagTop());
                pm.put("magBottom",         p.getMagBottom());
                pm.put("fftLength",         p.getFftLength());
                pm.put("averages",          p.getAverages());
                pm.put("stopAfterNEnabled", p.isStopAfterNEnabled());
                pm.put("stopAfterN",        p.getStopAfterN());
                pm.put("fundFromGenerator", p.isFundFromGenerator());
                pm.put("window",            p.getWindow().name());
                pm.put("overlap",           p.getOverlap().name());
                pm.put("coherentAveraging", p.isCoherentAveraging());
                pm.put("distMinHz",         p.getDistMinHz());
                pm.put("distMaxHz",         p.getDistMaxHz());
                pm.put("distMinEnabled",    p.isDistMinEnabled());
                pm.put("distMaxEnabled",    p.isDistMaxEnabled());
                pm.put("thdMaxHarmonic",    p.getThdMaxHarmonic());
                pm.put("calcMaxHarmonic",   p.getCalcMaxHarmonic());
                pm.put("manualFundVrms",    p.getManualFundVrms());
                pm.put("manualFundUnit",    p.getManualFundUnit().name());
                pm.put("manualFundEnabled", p.isManualFundEnabled());
                fpMap.put(e.getKey(), pm);
            }
            root.put("fftPresets", fpMap);
        }

        if (!freqRespPresets.isEmpty()) {
            Map<String, Object> frMap = new LinkedHashMap<>();
            for (Map.Entry<String, FreqRespPreset> e : freqRespPresets.entrySet()) {
                FreqRespPreset p = e.getValue();
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("startHz",        p.getStartHz());
                pm.put("stopHz",         p.getStopHz());
                pm.put("amplitudeVrms",  p.getAmplitudeVrms());
                pm.put("sweepPoints",    p.getSweepPoints());
                pm.put("fftSize",        p.getFftSize());
                pm.put("leadInSec",      p.getLeadInSec());
                pm.put("ditherBits",     p.getDitherBits());
                pm.put("showRiaa",       p.isShowRiaa());
                pm.put("reverseRiaa",    p.isReverseRiaa());
                pm.put("iecAmendment",   p.isIecAmendment());
                pm.put("compareMode",    p.isCompareMode());
                frMap.put(e.getKey(), pm);
            }
            root.put("freqRespPresets", frMap);
        }

        Map<String, Object> perBackendMap = new LinkedHashMap<>();
        synchronized (perBackend) {
            for (Map.Entry<AudioBackendType, BackendPrefs> e : perBackend.entrySet()) {
                Map<String, Object> bp = new LinkedHashMap<>();
                BackendPrefs v = e.getValue();
                bp.put("inputDeviceName",  v.getInputDeviceName());
                bp.put("outputDeviceName", v.getOutputDeviceName());
                bp.put("inputSampleRate",  v.getInputSampleRate());
                bp.put("inputBitDepth",    v.getInputBitDepth());
                bp.put("outputSampleRate", v.getOutputSampleRate());
                bp.put("outputBitDepth",   v.getOutputBitDepth());
                perBackendMap.put(e.getKey().name(), bp);
            }
        }
        root.put("perBackend", perBackendMap);
        return root;
    }

    private void fromMap(Map<?, ?> root) {
        if (root.get("uiLanguage") instanceof String s) uiLanguage.set(s);
        if (root.get("tabOrientation") instanceof String s) tabOrientation.set(enumOr(TabOrientation.class, s, tabOrientation.get()));
        if (root.get("activeTabIndex") instanceof Number n) activeTabIndex.set(n.intValue());
        if (root.get("smallIconsInMainTab") instanceof Boolean b) smallIconsInMainTab.set(b);
        if (root.get("checkForUpdatesOnStartup")  instanceof Boolean b) checkForUpdatesOnStartup.set(b);
        if (root.get("includeBetaInUpdateChecks") instanceof Boolean b) includeBetaInUpdateChecks.set(b);
        if (root.get("backend") instanceof String s) {
            backend.set(enumOr(AudioBackendType.class, s, backend.get()));
        }
        if (root.get("windowWidth")            instanceof Integer i) windowWidth.set(i);
        if (root.get("windowHeight")           instanceof Integer i) windowHeight.set(i);
        if (root.get("genPaneWidth")           instanceof Number n) genPaneWidth.set(n.intValue());
        if (root.get("multiVSplitWeights")     instanceof List<?> l) multiVSplitWeights     = listToIntArray(l);
        if (root.get("genPaneCollapsed")       instanceof Boolean b) genPaneCollapsed.set(b);
        if (root.get("oscPaneCollapsed")       instanceof Boolean b) oscPaneCollapsed.set(b);
        if (root.get("fftPaneCollapsed")       instanceof Boolean b) fftPaneCollapsed.set(b);
        if (root.get("oscLeftChannelEnabled")  instanceof Boolean b) oscLeftChannelEnabled.set(b);
        if (root.get("oscLeftAcMode")          instanceof Boolean b) oscLeftAcMode.set(b);
        if (root.get("oscRightAcMode")         instanceof Boolean b) oscRightAcMode.set(b);
        if (root.get("oscRightChannelEnabled") instanceof Boolean b) oscRightChannelEnabled.set(b);
        // New double-valued V/div and t/div fields.  Free-form: not snapped
        // to the standard step list, so 45 mV/div stays 45 mV/div.
        if (root.get("oscLeftVoltsPerDiv")     instanceof Number n) oscLeftVoltsPerDiv.set(n.doubleValue());
        if (root.get("oscRightVoltsPerDiv")    instanceof Number n) oscRightVoltsPerDiv.set(n.doubleValue());
        if (root.get("oscTimePerDiv")          instanceof Number n) oscTimePerDiv.set(n.doubleValue());
        if (root.get("oscTriggerChannel")      instanceof String  s) oscTriggerChannel.set(enumOr(Channel.class, s, oscTriggerChannel.get()));
        if (root.get("oscTriggerEdge")         instanceof String  s) oscTriggerEdge.set(enumOr(TriggerEdge.class,    s, oscTriggerEdge.get()));
        if (root.get("oscTriggerMode")         instanceof String  s) oscTriggerMode.set(enumOr(TriggerMode.class,    s, oscTriggerMode.get()));
        if (root.get("oscTriggerHysteresisDiv")     instanceof Number  n) oscTriggerHysteresisDiv.set(n.doubleValue());
        if (root.get("oscTriggerHysteresisEnabled") instanceof Boolean b) oscTriggerHysteresisEnabled.set(b);
        if (root.get("oscShowReconstructedBeat")    instanceof Boolean b) oscShowReconstructedBeat.set(b);
        if (root.get("oscLeftSincInterpEnabled")  instanceof Boolean b) oscLeftSincInterpEnabled.set(b);
        if (root.get("oscRightSincInterpEnabled") instanceof Boolean b) oscRightSincInterpEnabled.set(b);
        if (root.get("oscLeftMainsSuppression")  instanceof String s) oscLeftMainsSuppression.set(enumOr(MainsSuppression.class, s, oscLeftMainsSuppression.get()));
        if (root.get("oscRightMainsSuppression") instanceof String s) oscRightMainsSuppression.set(enumOr(MainsSuppression.class, s, oscRightMainsSuppression.get()));
        if (root.get("oscLeftLpf")  instanceof String s) oscLeftLpf.set(enumOr(LpfMode.class, s, oscLeftLpf.get()));
        if (root.get("oscRightLpf") instanceof String s) oscRightLpf.set(enumOr(LpfMode.class, s, oscRightLpf.get()));
        if (root.get("oscLeftOffsetFrac")      instanceof Number n) oscLeftOffsetFrac.set(n.doubleValue());
        if (root.get("oscRightOffsetFrac")     instanceof Number n) oscRightOffsetFrac.set(n.doubleValue());
        if (root.get("oscTriggerLevelFrac")    instanceof Number n) oscTriggerLevelFrac.set(n.doubleValue());
        if (root.get("oscTriggerPositionFrac") instanceof Number n) oscTriggerPositionFrac.set(n.doubleValue());
        if (root.get("oscMeasurementAverageSeconds") instanceof Number n) oscMeasurementAverageSeconds.set(n.doubleValue());
        if (root.get("oscMeasurementChannel")        instanceof String s) oscMeasurementChannel.set(enumOr(Channel.class, s, oscMeasurementChannel.get()));
        if (root.get("oscShowStats")                 instanceof Boolean b) oscShowStats.set(b);
        if (root.get("oscShowMeasurementTable")      instanceof Boolean b) oscShowMeasurementTable.set(b);
        if (root.get("adcFsVoltageRms")              instanceof Number n) adcFsVoltageRms.set(n.doubleValue());
        if (root.get("dacFsVoltageRms")              instanceof Number n) dacFsVoltageRms.set(n.doubleValue());
        if (root.get("genSignalForm")                instanceof String s) genSignalForm.set(enumOr(GenSignalForm.class, s, genSignalForm.get()));
        if (root.get("genFrequencyHz")               instanceof Number n) genFrequencyHz.set(n.doubleValue());
        if (root.get("genDualToneFreq1Hz")           instanceof Number n) genDualToneFreq1Hz.set(n.doubleValue());
        if (root.get("genDualToneFreq2Hz")           instanceof Number n) genDualToneFreq2Hz.set(n.doubleValue());
        if (root.get("genDualToneSplitPct")          instanceof Number n) genDualToneSplitPct.set(n.doubleValue());
        if (root.get("genAmplitudeVrms")             instanceof Number n) genAmplitudeVrms.set(n.doubleValue());
        if (root.get("genAmplitudeUnit")             instanceof String s) genAmplitudeUnit.set(enumOr(AmplitudeUnit.class, s, genAmplitudeUnit.get()));
        if (root.get("genDitherBits")                instanceof Number n) genDitherBits.set(n.intValue());
        if (root.get("genCorrectionsCsv")            instanceof String s) genCorrectionsCsv.set(s);
        if (root.get("genCorrectionsFolder")         instanceof String s) genCorrectionsFolder.set(s);
        if (root.get("genRectangleDuty")             instanceof Number n) genRectangleDuty.set(n.doubleValue());
        if (root.get("genTriangleDuty")              instanceof Number n) genTriangleDuty.set(n.doubleValue());
        if (root.get("genSweepFreqStartHz")          instanceof Number n) genSweepFreqStartHz.set(n.doubleValue());
        if (root.get("genSweepFreqEndHz")            instanceof Number n) genSweepFreqEndHz.set(n.doubleValue());
        if (root.get("genSweepDurationSec")          instanceof Number n) genSweepDurationSec.set(n.doubleValue());
        if (root.get("genSweepLoop")                 instanceof Boolean b) genSweepLoop.set(b);
        if (root.get("genSweepFadeInSec")            instanceof Number n) genSweepFadeInSec.set(n.doubleValue());
        if (root.get("genSweepFadeOutSec")           instanceof Number n) genSweepFadeOutSec.set(n.doubleValue());
        if (root.get("genSnapToFftBin")              instanceof Boolean b) genSnapToFftBin.set(b);
        if (root.get("genWavDurationSeconds")        instanceof Number n) genWavDurationSeconds.set(n.doubleValue());
        if (root.get("genWavPath")                   instanceof String s) genWavPath.set(s);
        if (root.get("genWavFolder")                 instanceof String s) genWavFolder.set(s);
        if (root.get("genPlayFromPath")              instanceof String s) genPlayFromPath.set(s);
        if (root.get("genPlayFromFolder")            instanceof String s) genPlayFromFolder.set(s);
        if (root.get("genPlayFromLoop")              instanceof Boolean b) genPlayFromLoop.set(b);
        if (root.get("oscSavePath")                  instanceof String s) oscSavePath.set(s);
        if (root.get("oscSaveFolder")                instanceof String s) oscSaveFolder.set(s);
        if (root.get("oscSaveDurationSeconds")       instanceof Number n) oscSaveDurationSeconds.set(n.doubleValue());
        if (root.get("oscPlayFromPath")              instanceof String s)  oscPlayFromPath.set(s);
        if (root.get("oscPlayFromFolder")            instanceof String s)  oscPlayFromFolder.set(s);
        if (root.get("oscPlayFromLoop")              instanceof Boolean b) oscPlayFromLoop.set(b);
        if (root.get("oscLineWidth")                 instanceof Number n) oscLineWidth.set(n.doubleValue());
        if (root.get("oscDotDiameter")               instanceof Number n) oscDotDiameter.set(n.intValue());
        Object leftColorObj  = root.get("oscLeftChannelColor");
        Object rightColorObj = root.get("oscRightChannelColor");
        if (leftColorObj  instanceof String s) oscLeftChannelColor.set(parseHtmlColor(s, oscLeftChannelColor.get()));
        else if (leftColorObj  instanceof Number n) oscLeftChannelColor.set(n.intValue());
        if (rightColorObj instanceof String s) oscRightChannelColor.set(parseHtmlColor(s, oscRightChannelColor.get()));
        else if (rightColorObj instanceof Number n) oscRightChannelColor.set(n.intValue());
        if (root.get("screenshotWidth")              instanceof Number n) screenshotWidth.set(n.intValue());
        if (root.get("screenshotHeight")             instanceof Number n) screenshotHeight.set(n.intValue());
        if (root.get("screenshotFolder")             instanceof String s) screenshotFolder.set(s);

        if (root.get("oscPresets") instanceof Map<?, ?> presetsMap) {
            oscPresets.clear();
            for (Map.Entry<?, ?> e : presetsMap.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (!(e.getValue() instanceof Map<?, ?> pm)) continue;
                OscPreset p = new OscPreset();
                if (pm.get("leftChannelEnabled")     instanceof Boolean b) p.setLeftChannelEnabled(b);
                if (pm.get("rightChannelEnabled")    instanceof Boolean b) p.setRightChannelEnabled(b);
                if (pm.get("leftAcMode")             instanceof Boolean b) p.setLeftAcMode(b);
                if (pm.get("rightAcMode")            instanceof Boolean b) p.setRightAcMode(b);
                if (pm.get("leftSincInterpEnabled")  instanceof Boolean b) p.setLeftSincInterpEnabled(b);
                if (pm.get("rightSincInterpEnabled") instanceof Boolean b) p.setRightSincInterpEnabled(b);
                if (pm.get("leftMainsSuppression")   instanceof String  s) p.setLeftMainsSuppression(enumOr(MainsSuppression.class, s, p.getLeftMainsSuppression()));
                if (pm.get("rightMainsSuppression")  instanceof String  s) p.setRightMainsSuppression(enumOr(MainsSuppression.class, s, p.getRightMainsSuppression()));
                if (pm.get("leftLpf")                instanceof String  s) p.setLeftLpf(enumOr(LpfMode.class, s, p.getLeftLpf()));
                if (pm.get("rightLpf")               instanceof String  s) p.setRightLpf(enumOr(LpfMode.class, s, p.getRightLpf()));
                if (pm.get("leftVoltsPerDiv")        instanceof Number  n) p.setLeftVoltsPerDiv(n.doubleValue());
                if (pm.get("rightVoltsPerDiv")       instanceof Number  n) p.setRightVoltsPerDiv(n.doubleValue());
                if (pm.get("leftOffsetFrac")         instanceof Number  n) p.setLeftOffsetFrac(n.doubleValue());
                if (pm.get("rightOffsetFrac")        instanceof Number  n) p.setRightOffsetFrac(n.doubleValue());
                if (pm.get("timePerDiv")             instanceof Number  n) p.setTimePerDiv(n.doubleValue());
                if (pm.get("triggerPositionFrac")    instanceof Number  n) p.setTriggerPositionFrac(n.doubleValue());
                if (pm.get("triggerChannel")         instanceof String  s) p.setTriggerChannel(enumOr(Channel.class,     s, p.getTriggerChannel()));
                if (pm.get("triggerEdge")            instanceof String  s) p.setTriggerEdge   (enumOr(TriggerEdge.class, s, p.getTriggerEdge()));
                if (pm.get("triggerMode")            instanceof String  s) p.setTriggerMode   (enumOr(TriggerMode.class, s, p.getTriggerMode()));
                if (pm.get("triggerLevelFrac")       instanceof Number  n) p.setTriggerLevelFrac(n.doubleValue());
                oscPresets.put(key, p);
            }
        }

        // ---- FFT pane state (fromMap)
        if (root.get("fftLength")                 instanceof Number  n) fftLength.set(n.intValue());
        if (root.get("fftAverages")               instanceof Number  n) fftAverages.set(n.doubleValue());
        if (root.get("fftStopAfterNEnabled")      instanceof Boolean b) fftStopAfterNEnabled.set(b);
        if (root.get("fftStopAfterN")             instanceof Number  n) fftStopAfterN.set(n.intValue());
        if (root.get("fftFundFromGenerator")      instanceof Boolean b) fftFundFromGenerator.set(b);
        if (root.get("fftLogFreqAxis")            instanceof Boolean b) fftLogFreqAxis.set(b);
        if (root.get("fftWindow")                 instanceof String  s) fftWindow.set(enumOr(WindowType.class, s, fftWindow.get()));
        if (root.get("fftOverlap")                instanceof String  s) fftOverlap.set(enumOr(FftOverlap.class, s, fftOverlap.get()));
        if (root.get("fftCoherentAveraging")      instanceof Boolean b) fftCoherentAveraging.set(b);
        if (root.get("fftMainsSuppression")       instanceof String  s) fftMainsSuppression.set(enumOr(MainsSuppression.class, s, fftMainsSuppression.get()));
        if (root.get("fftAlignGenerator")         instanceof String  s) fftAlignGenerator.set(AlignGenerator.fromString(s));
        else if (root.get("fftAlignGenToFreqDiff") instanceof Boolean b)   // migrate the old checkbox
            fftAlignGenerator.set(b ? AlignGenerator.PID : AlignGenerator.NONE);
        if (root.get("fftDistMinHz")              instanceof Number  n) fftDistMinHz.set(n.doubleValue());
        if (root.get("fftDistMaxHz")              instanceof Number  n) fftDistMaxHz.set(n.doubleValue());
        if (root.get("fftDistMinEnabled")         instanceof Boolean b) fftDistMinEnabled.set(b);
        if (root.get("fftDistMaxEnabled")         instanceof Boolean b) fftDistMaxEnabled.set(b);
        if (root.get("fftThdMaxHarmonic")         instanceof Number  n) fftThdMaxHarmonic.set(n.intValue());
        if (root.get("fftCalcMaxHarmonic")        instanceof Number  n) fftCalcMaxHarmonic.set(n.intValue());
        if (root.get("fftStrongToneRelDb")        instanceof Number  n) fftStrongToneRelDb.set(n.doubleValue());
        if (root.get("fftFllKp")                  instanceof Number  n) fftFllKp.set(n.doubleValue());
        if (root.get("fftFllKi")                  instanceof Number  n) fftFllKi.set(n.doubleValue());
        if (root.get("fftFllKd")                  instanceof Number  n) fftFllKd.set(n.doubleValue());
        if (root.get("fftManualFundVrms")         instanceof Number  n) fftManualFundVrms.set(n.doubleValue());
        if (root.get("fftManualFundUnit")         instanceof String  s) fftManualFundUnit.set(enumOr(AmplitudeUnit.class, s, fftManualFundUnit.get()));
        if (root.get("fftManualFundEnabled")      instanceof Boolean b) fftManualFundEnabled.set(b);
        if (root.get("fftChannel")                instanceof String  s) fftChannel.set(enumOr(Channel.class, s, fftChannel.get()));
        if (root.get("fftMagUnit")                instanceof String  s) fftMagUnit.set(enumOr(FftMagnitudeUnit.class, s, fftMagUnit.get()));
        if (root.get("fftDistortionTableVisible") instanceof Boolean b) fftDistortionTableVisible.set(b);
        if (root.get("fftFreqMinHz")              instanceof Number  n) fftFreqMinHz.set(n.doubleValue());
        if (root.get("fftFreqMaxHz")              instanceof Number  n) fftFreqMaxHz.set(n.doubleValue());
        if (root.get("fftMagTop")                 instanceof Number  n) fftMagTop.set(n.doubleValue());
        if (root.get("fftMagBottom")              instanceof Number  n) fftMagBottom.set(n.doubleValue());
        if (root.get("fftSavePath")               instanceof String  s) fftSavePath.set(s);
        if (root.get("fftSaveFolder")             instanceof String  s) fftSaveFolder.set(s);
        if (root.get("fftLoadPath")               instanceof String  s) fftLoadPath.set(s);

        // ---- Frequency Response pane --------------------------------------
        if (root.get("freqRespStartHz")           instanceof Number  n) freqRespStartHz.set(n.doubleValue());
        if (root.get("freqRespStopHz")            instanceof Number  n) freqRespStopHz.set(n.doubleValue());
        if (root.get("freqRespAmplitudeVrms")     instanceof Number  n) freqRespAmplitudeVrms.set(n.doubleValue());
        if (root.get("freqRespSweepPoints")       instanceof Number  n) freqRespSweepPoints.set(n.intValue());
        if (root.get("freqRespDurationSec")       instanceof Number  n) freqRespDurationSec.set(n.doubleValue());
        if (root.get("freqRespFftSize")           instanceof Number  n) {
            // Snap any non-power-of-two value to the nearest legal one
            // (between 64k and 16M) so the UI combo can match a row.
            int v = n.intValue();
            v = Math.max(1 << 16, Math.min(1 << 24, v));
            // Round up to the next power of two so the combo's
            // selectionIndex maps cleanly.
            int p = 1 << 16;
            while (p < v) p <<= 1;
            freqRespFftSize.set(p);
        }
        if (root.get("freqRespDitherBits")        instanceof Number  n) freqRespDitherBits.set(n.intValue());
        if (root.get("freqRespLeadInSec")         instanceof Number  n) freqRespLeadInSec.set(n.doubleValue());
        if (root.get("freqRespLeftVisible")       instanceof Boolean b) freqRespLeftVisible.set(b);
        if (root.get("freqRespRightVisible")      instanceof Boolean b) freqRespRightVisible.set(b);
        if (root.get("freqRespPhaseVisible")      instanceof Boolean b) freqRespPhaseVisible.set(b);
        if (root.get("freqRespFreqMinHz")         instanceof Number  n) freqRespFreqMinHz.set(n.doubleValue());
        if (root.get("freqRespFreqMaxHz")         instanceof Number  n) freqRespFreqMaxHz.set(n.doubleValue());
        if (root.get("freqRespMagTopDb")          instanceof Number  n) freqRespMagTopDb.set(n.doubleValue());
        if (root.get("freqRespMagBotDb")          instanceof Number  n) freqRespMagBotDb.set(n.doubleValue());
        if (root.get("freqRespNyquistFraction")   instanceof Number  n) {
            // Clamp into the [0.83, 1.0] band the UI exposes — older
            // YAML files may still carry the legacy 0.40-0.50 range.
            double v = n.doubleValue();
            freqRespNyquistFraction.set(Math.max(0.83, Math.min(1.0, v < 0.83 ? 1.0 : v)));
        }
        if (root.get("freqRespCompareSmoothWindow") instanceof Number n) {
            freqRespCompareSmoothWindow.set(Math.max(0, Math.min(100, n.intValue())));
        }
        if (root.get("freqRespNotchEnabled") instanceof Boolean b) freqRespNotchEnabled.set(b);
        if (root.get("freqRespNotchBaseHz")  instanceof Number  n) {
            int v = n.intValue();
            // Snap any non-50/60 value back to 50 (EU default) — the UI
            // exposes only those two choices.
            freqRespNotchBaseHz.set((v == 60) ? 60 : 50);
        }
        Object signalColorObj = root.get("freqRespSignalColor");
        if (signalColorObj instanceof String s) freqRespSignalColor.set(parseHtmlColor(s, freqRespSignalColor.get()));
        else if (signalColorObj instanceof Number n) freqRespSignalColor.set(n.intValue());
        Object phaseColorObj  = root.get("freqRespPhaseColor");
        if (phaseColorObj  instanceof String s) freqRespPhaseColor.set(parseHtmlColor(s, freqRespPhaseColor.get()));
        else if (phaseColorObj  instanceof Number n) freqRespPhaseColor.set(n.intValue());
        Object refColorObj    = root.get("freqRespReferenceColor");
        if (refColorObj    instanceof String s) freqRespReferenceColor.set(parseHtmlColor(s, freqRespReferenceColor.get()));
        else if (refColorObj    instanceof Number n) freqRespReferenceColor.set(n.intValue());
        Object bgColorObj     = root.get("freqRespBackgroundColor");
        if (bgColorObj     instanceof String s) freqRespBackgroundColor.set(parseHtmlColor(s, freqRespBackgroundColor.get()));
        else if (bgColorObj     instanceof Number n) freqRespBackgroundColor.set(n.intValue());
        // freqRespShowRiaa is intentionally not loaded from disk — it
        // always starts unchecked on a fresh session.
        if (root.get("freqRespReverseRiaa")       instanceof Boolean b) freqRespReverseRiaa.set(b);
        if (root.get("freqRespIecAmendment")      instanceof Boolean b) freqRespIecAmendment.set(b);
        if (root.get("freqRespCompareMode")       instanceof Boolean b) freqRespCompareMode.set(b);
        if (root.get("freqRespApplyCalibration")  instanceof Boolean b) freqRespApplyCalibration.set(b);
        if (root.get("freqRespCalibrationPath")   instanceof String  s) freqRespCalibrationPath.set(s);
        if (root.get("freqRespCalibrationActive") instanceof Boolean b) freqRespCalibrationActive.set(b);
        if (root.get("freqRespCalibrationActiveExtra") instanceof List<?> raw) {
            freqRespCalibrationActiveExtra = new ArrayList<>();
            for (Object o : raw) if (o instanceof Boolean b) freqRespCalibrationActiveExtra.add(b);
        }
        if (root.get("freqRespCalibrationPathsExtra") instanceof List<?> raw) {
            freqRespCalibrationPathsExtra = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof String s) freqRespCalibrationPathsExtra.add(s);
            }
        }
        if (root.get("freqRespSaveFolder")        instanceof String  s) freqRespSaveFolder.set(s);
        if (root.get("freqRespSavePath")          instanceof String  s) freqRespSavePath.set(s);
        if (root.get("freqRespLoadFolder")        instanceof String  s) freqRespLoadFolder.set(s);
        if (root.get("freqRespLoadPath")          instanceof String  s) freqRespLoadPath.set(s);
        if (root.get("freqRespActiveTabIndex")    instanceof Number  n) freqRespActiveTabIndex.set(n.intValue());
        if (root.get("fftLoadFolder")             instanceof String  s) fftLoadFolder.set(s);
        if (root.get("fftCalibrationPath")        instanceof String  s) fftCalibrationPath.set(s);
        if (root.get("fftCalibrationActive")      instanceof Boolean b) fftCalibrationActive.set(b);
        if (root.get("fftCalibrationWithNoise")   instanceof Boolean b) fftCalibrationWithNoise.set(b);
        if (root.get("fftCalibrationActiveExtra") instanceof List<?> raw) {
            fftCalibrationActiveExtra = new ArrayList<>();
            for (Object o : raw) if (o instanceof Boolean b) fftCalibrationActiveExtra.add(b);
        }
        if (root.get("fftCalibrationWithNoiseExtra") instanceof List<?> raw) {
            fftCalibrationWithNoiseExtra = new ArrayList<>();
            for (Object o : raw) if (o instanceof Boolean b) fftCalibrationWithNoiseExtra.add(b);
        }
        if (root.get("fftCalibrationPathsExtra")  instanceof List<?> raw) {
            fftCalibrationPathsExtra = new ArrayList<>();
            for (Object o : raw) if (o instanceof String s) fftCalibrationPathsExtra.add(s);
        }
        Object beforeCalColorObj = root.get("fftBeforeCalDotColor");
        if (beforeCalColorObj instanceof String s) fftBeforeCalDotColor.set(parseHtmlColor(s, fftBeforeCalDotColor.get()));
        else if (beforeCalColorObj instanceof Number n) fftBeforeCalDotColor.set(n.intValue());
        Object calOverlayColorObj = root.get("fftCalOverlayColor");
        if (calOverlayColorObj instanceof String s) fftCalOverlayColor.set(parseHtmlColor(s, fftCalOverlayColor.get()));
        else if (calOverlayColorObj instanceof Number n) fftCalOverlayColor.set(n.intValue());
        if (root.get("fftLineWidth")              instanceof Number  n) fftLineWidth.set(n.doubleValue());
        if (root.get("fftHarmonicDotDiameter")    instanceof Number  n) fftHarmonicDotDiameter.set(n.intValue());
        Object fftLineColorObj   = root.get("fftLineColor");
        if (fftLineColorObj   instanceof String s) fftLineColor.set(parseHtmlColor(s, fftLineColor.get()));
        else if (fftLineColorObj   instanceof Number n) fftLineColor.set(n.intValue());
        Object fftBgColorObj     = root.get("fftChartBackgroundColor");
        if (fftBgColorObj     instanceof String s) fftChartBackgroundColor.set(parseHtmlColor(s, fftChartBackgroundColor.get()));
        else if (fftBgColorObj     instanceof Number n) fftChartBackgroundColor.set(n.intValue());
        Object fftDotColorObj    = root.get("fftHarmonicDotColor");
        if (fftDotColorObj    instanceof String s) fftHarmonicDotColor.set(parseHtmlColor(s, fftHarmonicDotColor.get()));
        else if (fftDotColorObj    instanceof Number n) fftHarmonicDotColor.set(n.intValue());
        Object fftFiltColorObj   = root.get("fftFreqRespColor");
        if (fftFiltColorObj   instanceof String s) fftFreqRespColor.set(parseHtmlColor(s, fftFreqRespColor.get()));
        else if (fftFiltColorObj   instanceof Number n) fftFreqRespColor.set(n.intValue());

        if (root.get("fftPresets") instanceof Map<?, ?> fpMap) {
            fftPresets.clear();
            for (Map.Entry<?, ?> e : fpMap.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (!(e.getValue() instanceof Map<?, ?> pm)) continue;
                FftPreset p = new FftPreset();
                if (pm.get("channel")           instanceof String  s) p.setChannel(enumOr(Channel.class, s, p.getChannel()));
                if (pm.get("magUnit")           instanceof String  s) p.setMagUnit(enumOr(FftMagnitudeUnit.class, s, p.getMagUnit()));
                if (pm.get("logFreqAxis")       instanceof Boolean b) p.setLogFreqAxis(b);
                if (pm.get("freqMinHz")         instanceof Number  n) p.setFreqMinHz(n.doubleValue());
                if (pm.get("freqMaxHz")         instanceof Number  n) p.setFreqMaxHz(n.doubleValue());
                if (pm.get("magTop")            instanceof Number  n) p.setMagTop(n.doubleValue());
                if (pm.get("magBottom")         instanceof Number  n) p.setMagBottom(n.doubleValue());
                if (pm.get("fftLength")         instanceof Number  n) p.setFftLength(n.intValue());
                if (pm.get("averages")          instanceof Number  n) p.setAverages(n.doubleValue());
                if (pm.get("stopAfterNEnabled") instanceof Boolean b) p.setStopAfterNEnabled(b);
                if (pm.get("stopAfterN")        instanceof Number  n) p.setStopAfterN(n.intValue());
                if (pm.get("fundFromGenerator") instanceof Boolean b) p.setFundFromGenerator(b);
                if (pm.get("window")            instanceof String  s) p.setWindow(enumOr(WindowType.class, s, p.getWindow()));
                if (pm.get("overlap")           instanceof String  s) p.setOverlap(enumOr(FftOverlap.class, s, p.getOverlap()));
                if (pm.get("coherentAveraging") instanceof Boolean b) p.setCoherentAveraging(b);
                if (pm.get("distMinHz")         instanceof Number  n) p.setDistMinHz(n.doubleValue());
                if (pm.get("distMaxHz")         instanceof Number  n) p.setDistMaxHz(n.doubleValue());
                if (pm.get("distMinEnabled")    instanceof Boolean b) p.setDistMinEnabled(b);
                if (pm.get("distMaxEnabled")    instanceof Boolean b) p.setDistMaxEnabled(b);
                if (pm.get("thdMaxHarmonic")    instanceof Number  n) p.setThdMaxHarmonic(n.intValue());
                if (pm.get("calcMaxHarmonic")   instanceof Number  n) p.setCalcMaxHarmonic(n.intValue());
                if (pm.get("manualFundVrms")    instanceof Number  n) p.setManualFundVrms(n.doubleValue());
                if (pm.get("manualFundUnit")    instanceof String  s) p.setManualFundUnit(enumOr(AmplitudeUnit.class, s, p.getManualFundUnit()));
                if (pm.get("manualFundEnabled") instanceof Boolean b) p.setManualFundEnabled(b);
                fftPresets.put(key, p);
            }
        }

        if (root.get("freqRespPresets") instanceof Map<?, ?> frMap) {
            freqRespPresets.clear();
            for (Map.Entry<?, ?> e : frMap.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (!(e.getValue() instanceof Map<?, ?> pm)) continue;
                FreqRespPreset p = new FreqRespPreset();
                if (pm.get("startHz")        instanceof Number  n) p.setStartHz(n.doubleValue());
                if (pm.get("stopHz")         instanceof Number  n) p.setStopHz(n.doubleValue());
                if (pm.get("amplitudeVrms")  instanceof Number  n) p.setAmplitudeVrms(n.doubleValue());
                if (pm.get("sweepPoints")    instanceof Number  n) p.setSweepPoints(n.intValue());
                if (pm.get("fftSize")        instanceof Number  n) p.setFftSize(n.intValue());
                if (pm.get("leadInSec")      instanceof Number  n) p.setLeadInSec(n.doubleValue());
                if (pm.get("ditherBits")     instanceof Number  n) p.setDitherBits(n.intValue());
                if (pm.get("showRiaa")       instanceof Boolean b) p.setShowRiaa(b);
                if (pm.get("reverseRiaa")    instanceof Boolean b) p.setReverseRiaa(b);
                if (pm.get("iecAmendment")   instanceof Boolean b) p.setIecAmendment(b);
                if (pm.get("compareMode")    instanceof Boolean b) p.setCompareMode(b);
                freqRespPresets.put(key, p);
            }
        }

        if (root.get("perBackend") instanceof Map<?, ?> pbm) {
            for (Map.Entry<?, ?> e : pbm.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                AudioBackendType type = enumOr(AudioBackendType.class, key, null);
                if (type == null) continue;
                BackendPrefs bp = prefsFor(type);
                if (e.getValue() instanceof Map<?, ?> bpMap) {
                    if (bpMap.get("inputDeviceName")  instanceof String  s) bp.setInputDeviceName(s);
                    if (bpMap.get("outputDeviceName") instanceof String  s) bp.setOutputDeviceName(s);
                    if (bpMap.get("inputSampleRate")  instanceof Integer i) bp.setInputSampleRate(i);
                    if (bpMap.get("inputBitDepth")    instanceof Integer i) bp.setInputBitDepth(i);
                    if (bpMap.get("outputSampleRate") instanceof Integer i) bp.setOutputSampleRate(i);
                    if (bpMap.get("outputBitDepth")   instanceof Integer i) bp.setOutputBitDepth(i);
                }
            }
        }
    }

    private <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Formats a packed 0xRRGGBB int as an HTML colour string ({@code #RRGGBB}). */
    private static String formatHtmlColor(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /** Parses {@code #RRGGBB} (or bare {@code RRGGBB}) into a packed 0xRRGGBB int.
     *  Returns {@code fallback} on any parse failure. */
    private static int parseHtmlColor(String s, int fallback) {
        if (s == null) return fallback;
        String h = s.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 6) return fallback;
        try {
            return Integer.parseInt(h, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<Integer> intArrayToList(int[] arr) {
        List<Integer> out = new ArrayList<>(arr.length);
        for (int v : arr) out.add(v);
        return out;
    }

    /** SnakeYAML deserialises a YAML list as {@code List<Object>}; pull out integer
     *  entries and copy them into an {@code int[]}, returning {@code null} when
     *  the list is empty or contains nothing usable. */
    private int[] listToIntArray(List<?> list) {
        if (list == null || list.isEmpty()) return null;
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (v instanceof Integer iv) out[i] = iv;
            else if (v instanceof Number n) out[i] = n.intValue();
        }
        return out;
    }

    /** Inserts or replaces the preset under {@code name} and persists. */
    public synchronized void putOscPreset(String name, OscPreset preset) {
        if (name == null || name.isEmpty() || preset == null) return;
        oscPresets.put(name, preset);
        save();
    }

    /** Removes the named preset (if present) and persists. */
    public synchronized void removeOscPreset(String name) {
        if (name == null) return;
        if (oscPresets.remove(name) != null) save();
    }

    /** Inserts or replaces the FFT preset under {@code name} and persists. */
    public synchronized void putFftPreset(String name, FftPreset preset) {
        if (name == null || name.isEmpty() || preset == null) return;
        fftPresets.put(name, preset);
        save();
    }

    /** Removes the named FFT preset (if present) and persists. */
    public synchronized void removeFftPreset(String name) {
        if (name == null) return;
        if (fftPresets.remove(name) != null) save();
    }

    /** Inserts or replaces the FreqResp preset under {@code name} and persists. */
    public synchronized void putFreqRespPreset(String name, FreqRespPreset preset) {
        if (name == null || name.isEmpty() || preset == null) return;
        freqRespPresets.put(name, preset);
        save();
    }

    /** Removes the named FreqResp preset (if present) and persists. */
    public synchronized void removeFreqRespPreset(String name) {
        if (name == null) return;
        if (freqRespPresets.remove(name) != null) save();
    }

}
