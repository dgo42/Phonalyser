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

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.TriggerEdge;
import org.edgo.audio.measure.enums.TriggerMode;
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

    private static volatile Preferences instance;

    @Getter @Setter
    private AudioBackendType backend = AudioBackendType.WASAPI;

    /** UI language tag (BCP-47 or just the lowercase ISO 639-1 code:
     *  "en", "de", …).  Java's {@code ResourceBundle.getBundle} resolves
     *  this via the fallback chain {@code <lang>_<region>} → {@code <lang>} →
     *  default (English).  Empty / null → platform default. */
    @Getter @Setter private String uiLanguage = "en";

    /** Where the main window's top-level tab strip sits — {@code "TOP"} for
     *  a conventional horizontal tab folder, {@code "LEFT"} for a vertical
     *  sidebar of large icon + label buttons.  Changing this triggers a
     *  shell recreate so the new layout takes effect immediately. */
    @Getter @Setter private String tabOrientation = "TOP";

    /** Zero-based index of the most recently selected top-level tab,
     *  restored on the next launch.  Only honoured by the LEFT sidebar
     *  layout for now; the standard TabFolder remembers its own
     *  selection via SWT. */
    @Getter @Setter private int activeTabIndex = 0;

    /** When true, the main tab strip draws icons at a smaller size — useful
     *  on dense displays or when the user wants more vertical/horizontal
     *  real estate for the actual measurement panes. */
    @Getter @Setter private boolean smallIconsInMainTab = false;

    /** When true, the GUI checks GitHub releases for a newer version on
     *  startup.  Stays off by default so the app never makes network
     *  calls without explicit user opt-in. */
    @Getter @Setter private boolean checkForUpdatesOnStartup = false;
    /** When true, beta / pre-release builds are also considered by the
     *  startup update check.  Ignored when
     *  {@link #checkForUpdatesOnStartup} is false. */
    @Getter @Setter private boolean includeBetaInUpdateChecks = false;

    private final Map<AudioBackendType, BackendPrefs> perBackend =
            new EnumMap<>(AudioBackendType.class);

    // -------------------------------------------------------------------------
    // Oscilloscope toolbar state — not backend-specific.
    // -------------------------------------------------------------------------

    @Getter @Setter private boolean oscLeftChannelEnabled  = true;
    @Getter @Setter private boolean oscRightChannelEnabled = true;
    /** AC display mode per channel: remove the DC mean from the rendered trace. Measurements are unaffected. */
    @Getter @Setter private boolean oscLeftAcMode  = false;
    @Getter @Setter private boolean oscRightAcMode = false;
    /** Left-channel V/div as a raw double (volts).  Free-form value: not
     *  constrained to the standard 1-2-5-10 step list.  Defaults to
     *  100 mV/div, matching the legacy {@code oscLeftVoltsPerDivIdx = 15}. */
    @Getter @Setter private double  oscLeftVoltsPerDiv  = 0.1;
    @Getter @Setter private double  oscRightVoltsPerDiv = 0.1;
    /** Time per division (seconds).  Free-form double, defaults to 1 ms/div. */
    @Getter @Setter private double  oscTimePerDiv       = 1e-3;
    @Getter @Setter private Channel oscTriggerChannel = Channel.L;
    @Getter @Setter private TriggerEdge    oscTriggerEdge    = TriggerEdge.RISE;
    @Getter @Setter private TriggerMode    oscTriggerMode    = TriggerMode.AUTO;
    /** Trigger hysteresis in oscilloscope divisions; 0 disables hysteresis. */
    @Getter @Setter private double         oscTriggerHysteresisDiv = 0.0;
    /** When false, hysteresis is bypassed at runtime regardless of
     *  {@link #oscTriggerHysteresisDiv}; the stored div value is preserved
     *  so re-enabling restores the user's last setting. */
    @Getter @Setter private boolean        oscTriggerHysteresisEnabled = false;
    /** Per-channel Lanczos sinc-interpolation toggle.  Each channel renders
     *  independently so the user can compare a sinc-reconstructed trace
     *  against a linearly-interpolated one side-by-side. */
    @Getter @Setter private boolean        oscLeftSincInterpEnabled  = true;
    @Getter @Setter private boolean        oscRightSincInterpEnabled = true;

    // Per-pane slider state.  All values are fractions of the visible window
    // (independent of V/div and t/div): 0.5 = centred, 0 = top/left edge,
    // 1 = bottom/right edge.  Offset is per-channel; the slider on the scope
    // canvas controls whichever channel is currently selected in the
    // measurement table.
    @Getter @Setter private double oscLeftOffsetFrac      = 0.5;
    @Getter @Setter private double oscRightOffsetFrac     = 0.5;
    @Getter @Setter private double oscTriggerLevelFrac    = 0.5;
    @Getter @Setter private double oscTriggerPositionFrac = 0.5;
    /** Sliding-window duration (seconds) for measurement avg / min / max / σ. */
    @Getter @Setter private double         oscMeasurementAverageSeconds = 5.0;
    /** Trace stroke width (pixels). */
    @Getter @Setter private double         oscLineWidth         = 2.0;
    /** Sample-dot diameter (pixels) when the inter-sample spacing exceeds 10 px. */
    @Getter @Setter private int            oscDotDiameter       = 5;
    /** Packed RGB (0xRRGGBB) of the left and right channel traces. */
    @Getter @Setter private int            oscLeftChannelColor  = 0x0057B7;
    @Getter @Setter private int            oscRightChannelColor = 0xFFD700;

    /** Last screenshot resolution / folder, 0 / null = use the pane's current size / system default. */
    @Getter @Setter private int    screenshotWidth;
    @Getter @Setter private int    screenshotHeight;
    @Getter @Setter private String screenshotFolder;
    /** Channel whose live samples drive the measurement table (auto-flipped to the other when this one is disabled). */
    @Getter @Setter private Channel oscMeasurementChannel = Channel.L;
    /** Toggles the measurement table's stats columns (avg/min/max/σ); the worker keeps computing them in the background regardless. */
    @Getter @Setter private boolean oscShowStats = true;
    /** Master toggle for the measurement table overlay in the scope view —
     *  when false, only the L / R channel-pick buttons and the table
     *  show / hide toggle are visible; the rows and the stats / reset
     *  buttons are hidden. */
    @Getter @Setter private boolean oscShowMeasurementTable = true;
    /** ADC full-scale RMS voltage — calibration constant used to translate normalised samples into volts.  Persisted across launches. */
    @Getter @Setter private double adcFsVoltageRms = 1.7931;
    /** DAC full-scale RMS voltage — the voltage the DAC outputs at digital
     *  full-scale.  Calibrated by the user via the "Calibrate DAC" button in
     *  the generator pane.  Persisted across launches.  Default 2.79351
     *  matches the original hard-coded {@code SignalGenerator.FS_VOLTAGE}. */
    @Getter @Setter private double dacFsVoltageRms = 2.79351;

    /** Path to the most recently chosen scope "Save to…" file (last N seconds of capture). */
    @Getter @Setter private String oscSavePath;
    /** Folder remembered for the scope's "Save to…" file dialog. */
    @Getter @Setter private String oscSaveFolder;
    /** Scope-save duration in seconds (how much of the recent capture to write to disk). */
    @Getter @Setter private double oscSaveDurationSeconds = 5.0;

    /** Path to the most recently chosen scope "Play from…" source file (WAV/FLAC/AIFF). */
    @Getter @Setter private String  oscPlayFromPath;
    /** Folder remembered for the scope's "Play from…" file dialog. */
    @Getter @Setter private String  oscPlayFromFolder;
    /** Whether the scope file-player should loop on EOF. */
    @Getter @Setter private boolean oscPlayFromLoop = false;

    // -------------------------------------------------------------------------
    // Generator pane state — stored by enum name where possible so the file
    // stays human-readable.  Amplitude is canonical Vrms; the display unit
    // the user last entered is remembered separately so the field reformats
    // back into "their" unit on reload.
    // -------------------------------------------------------------------------
    @Getter @Setter private String genSignalForm    = "SINE";
    @Getter @Setter private double genFrequencyHz   = 1000.0;
    @Getter @Setter private double genAmplitudeVrms = 0.5;
    /** Unit the amplitude field renders in: one of {@code mV}, {@code V}, {@code dBV}, {@code dBFS}. */
    @Getter @Setter private String genAmplitudeUnit = "V";
    /** Dither bits 0..N; 0 means "Off". */
    @Getter @Setter private int    genDitherBits    = 0;
    /** Path to the harmonics-correction CSV, or {@code null} if none. */
    @Getter @Setter private String genCorrectionsCsv;
    /** Folder remembered for the generator's "browse for corrections CSV" dialog. */
    @Getter @Setter private String genCorrectionsFolder;
    /** Rectangle / pulse duty cycle as a fraction in [0.001, 0.999].  Default 50 %. */
    @Getter @Setter private double  genRectangleDuty = 0.5;
    /** Triangle duty cycle (rise-portion fraction) in [0.001, 0.999].  Default 50 %
     *  = symmetric triangle; near 1.0 / 0.0 yields sawtooth-like waveforms. */
    @Getter @Setter private double  genTriangleDuty  = 0.5;
    /** Sweep (LINEAR_SWEEP / LOG_SWEEP) — start frequency in Hz. */
    @Getter @Setter private double  genSweepFreqStartHz   = 20.0;
    /** Sweep — stop frequency in Hz. */
    @Getter @Setter private double  genSweepFreqEndHz     = 20000.0;
    /** Sweep duration in seconds (one full cycle from start to end). */
    @Getter @Setter private double  genSweepDurationSec   = 1.0;
    /** Loop the sweep continuously vs play once then go silent. */
    @Getter @Setter private boolean genSweepLoop          = true;
    /** Hann fade-in duration in seconds — smooths the start so the output
     *  doesn't click on transient onset. */
    @Getter @Setter private double  genSweepFadeInSec     = 0.01;
    /** Hann fade-out duration in seconds. */
    @Getter @Setter private double  genSweepFadeOutSec    = 0.01;
    /**
     * When true the generator's frequency input will be snapped to the
     * nearest exact FFT bin on a future analysis pass.  Persisted only —
     * the snap logic itself is wired by a future change.
     */
    @Getter @Setter private boolean genSnapToFftBin = false;
    /** WAV-export duration in seconds. */
    @Getter @Setter private double  genWavDurationSeconds = 5.0;
    /** Path to the most recently chosen WAV-export target file. */
    @Getter @Setter private String  genWavPath;
    /** Folder remembered for the generator's "Save WAV" dialog, separate from {@link #genCorrectionsFolder}. */
    @Getter @Setter private String  genWavFolder;

    /** Path to the most recently chosen "Play from…" source file (WAV/FLAC/AIFF). */
    @Getter @Setter private String  genPlayFromPath;
    /** Folder remembered for the "Play from…" file picker. */
    @Getter @Setter private String  genPlayFromFolder;
    /** Whether the file-player should loop the source on EOF. */
    @Getter @Setter private boolean genPlayFromLoop = false;

    // -------------------------------------------------------------------------
    // Window geometry — restored on the next launch.  Defaults of 0 / null mean
    // "no saved value yet"; the GUI falls back to its built-in defaults.
    // -------------------------------------------------------------------------

    @Getter @Setter private int   windowWidth;
    @Getter @Setter private int   windowHeight;
    /** Generator pane width in pixels.  0 = no saved value, GUI uses its default. */
    @Getter @Setter private int   genPaneWidth;
    /** Vertical split weights for the Multifunctional tab (oscilloscope / FFT). */
    @Getter @Setter private int[] multiVSplitWeights;
    /** Collapse state for the three Multifunctional-tab panes — restored on startup. */
    @Getter @Setter private boolean genPaneCollapsed;
    @Getter @Setter private boolean oscPaneCollapsed;
    @Getter @Setter private boolean fftPaneCollapsed = true;

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

    @Getter @Setter private int     fftLength            = 65536;
    /** Number of FFT frames to average per analysis.  Special value
     *  {@code Double.POSITIVE_INFINITY} means "forever" (no fixed bound). */
    @Getter @Setter private double  fftAverages          = 4;
    @Getter @Setter private boolean fftStopAfterNEnabled = false;
    @Getter @Setter private int     fftStopAfterN        = 10;
    @Getter @Setter private boolean fftFundFromGenerator = false;
    @Getter @Setter private boolean fftLogFreqAxis       = true;
    /** {@code WindowType} enum name. */
    @Getter @Setter private String  fftWindow            = "HANN";
    /** {@code FftOverlap} enum name. */
    @Getter @Setter private String  fftOverlap           = "PCT_0";
    @Getter @Setter private boolean fftCoherentAveraging = true;
    @Getter @Setter private double  fftDistMinHz         = 20;
    @Getter @Setter private double  fftDistMaxHz         = 20000;
    @Getter @Setter private boolean fftDistMinEnabled    = false;
    @Getter @Setter private boolean fftDistMaxEnabled    = false;
    @Getter @Setter private int     fftThdMaxHarmonic    = 9;
    @Getter @Setter private int     fftCalcMaxHarmonic   = 9;
    @Getter @Setter private double  fftManualFundVrms    = 1.0;
    /** Unit the manual-fundamental-amplitude field renders in: {@code mV}, {@code V}, or {@code dBV}. */
    @Getter @Setter private String  fftManualFundUnit    = "V";
    @Getter @Setter private boolean fftManualFundEnabled = false;
    /** Active analysis channel (L or R) — only one channel shown at a time. */
    @Getter @Setter private Channel fftChannel    = Channel.L;
    /** {@code FftMagnitudeUnit} enum name: {@code V}, {@code V_SQRT_HZ}, {@code DBV}, {@code DBFS}. */
    @Getter @Setter private String  fftMagUnit           = "DBV";
    /** Whether the THD overlay table is shown on top of the spectrum view. */
    @Getter @Setter private boolean fftDistortionTableVisible = true;
    @Getter @Setter private double  fftFreqMinHz         = 20;
    @Getter @Setter private double  fftFreqMaxHz         = 20000;
    @Getter @Setter private double  fftMagTop            = 10;
    @Getter @Setter private double  fftMagBottom         = -150;
    @Getter @Setter private String  fftSavePath;
    @Getter @Setter private String  fftSaveFolder;
    @Getter @Setter private String  fftLoadPath;
    @Getter @Setter private String  fftLoadFolder;

    /** Path to the row-0 FFT calibration file (.frc), or null when row
     *  0 is empty.  Mirrors the FreqResp pane's row-0 / extras-list
     *  pattern. */
    @Getter @Setter private String  fftCalibrationPath;
    /** Paths to additional calibration files loaded into rows 1..N of
     *  the FFT pane's Load-calibration tab.  Empty strings represent
     *  empty rows that the user added but didn't yet populate. */
    @Getter @Setter private List<String> fftCalibrationPathsExtra = new ArrayList<>();
    /** When true, calibration subtraction applies to EVERY spectrum
     *  bin (including the noise floor).  When false, only the
     *  fundamental + harmonic dot positions are adjusted.  Bound to
     *  the "Calibrate with noise" checkbox on the THD Settings tab. */
    @Getter @Setter private boolean fftCalibrateWithNoise     = false;
    /** Packed RGB of the "before-calibration" dot painted next to each
     *  fundamental / harmonic peak when at least one .frc calibration
     *  is loaded.  Default dark blue (0x00, 0x00, 0x80). */
    @Getter @Setter private int     fftBeforeCalDotColor      = 0x000080;
    /** Packed RGB of the "inverted calibration" overlay curve — the
     *  cascaded calibration response negated and anchored at the H2
     *  peak, drawn alongside the spectrum so the user can see what
     *  shape was subtracted.  Default green (0x00, 0x96, 0x00). */
    @Getter @Setter private int     fftCalOverlayColor        = 0x009600;

    // FFT view appearance (FFT tab in Preferences dialog).  Defaults mirror
    // the colours used by FftAnalyzer.exportChart via ChartStyle.
    /** Spectrum trace line width in pixels. */
    @Getter @Setter private double  fftLineWidth              = 1.0;
    /** Harmonic / fundamental dot diameter in pixels. */
    @Getter @Setter private int     fftHarmonicDotDiameter    = 9;
    /** Packed RGB (0xRRGGBB) of the spectrum trace line. */
    @Getter @Setter private int     fftLineColor              = 0x0064C8;  // (0,100,200) blue
    /** Packed RGB of the FFT chart background. */
    @Getter @Setter private int     fftChartBackgroundColor   = 0xFFFFFF;  // white
    /** Packed RGB of the harmonic / fundamental dots. */
    @Getter @Setter private int     fftHarmonicDotColor       = 0xFF0000;  // (255,0,0) blue
    /** Packed RGB of the frequency response / cal-overlay response line. */
    @Getter @Setter private int     fftFreqRespColor    = 0x009600;  // (0,150,0) blue

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
    @Getter @Setter private double  freqRespStartHz          = 20.0;
    /** Sweep stop  frequency in Hz.  Default 20 kHz (audible top). */
    @Getter @Setter private double  freqRespStopHz           = 20000.0;
    /** Generator drive amplitude at the DAC, V RMS. */
    @Getter @Setter private double  freqRespAmplitudeVrms    = 0.5;
    /** Number of log-spaced output frequency points the deconvolution emits.
     *  Default 65536 (64 k) is a safe trade-off between resolution and
     *  memory for a single sweep. */
    @Getter @Setter private int     freqRespSweepPoints      = 65536;
    /** Sweep duration in seconds, excluding lead-in.  Derived from
     *  {@link #freqRespFftSize} + {@link #freqRespLeadInSec} + the half-
     *  second tail; the Settings tab keeps the two in sync.  The
     *  analyzer / wizard still read this field so the rest of the
     *  pipeline doesn't have to know about FFT size. */
    @Getter @Setter private double  freqRespDurationSec      = 5.5;
    /** Deconvolution FFT length (power of 2, 64k … 16M).  Primary control
     *  in the Settings tab — the sweep duration is derived from this so
     *  the analyzer's {@code nextPow2(leadIn + sweep + tail)} lands
     *  exactly on the chosen length (no wasted bins). */
    @Getter @Setter private int     freqRespFftSize          = 524288;
    /** TPDF dither bits applied to the generator before quantisation;
     *  0 disables.  Same convention as the generator pane. */
    @Getter @Setter private int     freqRespDitherBits       = 0;
    /** Silent lead-in prepended to the sweep, in seconds.  Lets the DAC →
     *  ADC chain settle before the first sweep sample lands. */
    @Getter @Setter private double  freqRespLeadInSec        = 0.2;

    /** Whether the left-channel trace is visible on the view (toggle on the
     *  view's header).  L + R are independent toggles, not single-choice. */
    @Getter @Setter private boolean freqRespLeftVisible      = true;
    /** Whether the right-channel trace is visible on the view. */
    @Getter @Setter private boolean freqRespRightVisible     = false;
    /** Whether the phase curve (right Y-axis ±180°) is painted. */
    @Getter @Setter private boolean freqRespPhaseVisible     = false;

    /** Visible frequency window — left edge of the trace area in Hz. */
    @Getter @Setter private double  freqRespFreqMinHz        = 20.0;
    /** Visible frequency window — right edge of the trace area in Hz. */
    @Getter @Setter private double  freqRespFreqMaxHz        = 20000.0;
    /** Visible magnitude window — top edge in dB. */
    @Getter @Setter private double  freqRespMagTopDb         = 20.0;
    /** Visible magnitude window — bottom edge in dB. */
    @Getter @Setter private double  freqRespMagBotDb         = -140.0;

    /** Maximum frequency the crosshair readout reports, expressed as a
     *  fraction of the sample rate (0.40–0.50).  Default 0.48 clips the
     *  readout at 0.48·Fs so the user doesn't see meaningless magnitude /
     *  phase numbers right at Nyquist where the deconvolution kernel has
     *  no usable energy. */
    @Getter @Setter private double  freqRespNyquistFraction  = 1.0;

    /** Window size (in points) of the moving-average smoothing applied
     *  to the compare-mode (measured − reference) curve.  Affects both
     *  the displayed trace and the anchor / min-max table that the
     *  auto-setup computes.  Clamped to [0, 100]; 0 disables smoothing. */
    @Getter @Setter private int     freqRespCompareSmoothWindow = 6;

    /** When true, the FreqResp view interpolates across each harmonic of
     *  {@link #freqRespNotchBaseHz} (50/60 Hz) before drawing — removes
     *  mains-hum spikes from the displayed response.  Applied per-channel
     *  on the way from raw to displayed copy, so toggling the flag
     *  redraws without re-measuring. */
    @Getter @Setter private boolean freqRespNotchEnabled = false;
    /** Base frequency of the industrial-noise harmonic comb removed when
     *  {@link #freqRespNotchEnabled}.  Either 50 (EU mains) or 60 (US). */
    @Getter @Setter private int     freqRespNotchBaseHz  = 50;

    /** Trace colour for the measured signal (whichever channel is the
     *  active one — L and R are mutually-exclusive radio toggles, so a
     *  single colour covers both).  Packed RGB int, default {@code #0064C8}
     *  (a saturated blue). */
    @Getter @Setter private int freqRespSignalColor     = 0x0064C8;
    /** Trace colour for the phase overlay.  Default {@code #FF0000} (red). */
    @Getter @Setter private int freqRespPhaseColor      = 0xFF0000;
    /** Trace colour for the RIAA / IEC reference curve.  Default
     *  {@code #009600} (a saturated green). */
    @Getter @Setter private int freqRespReferenceColor  = 0x009600;
    /** Chart background colour.  Default {@code #FFFFFF} (white). */
    @Getter @Setter private int freqRespBackgroundColor = 0xFFFFFF;

    /** Show RIAA reference curve overlaid on the trace. */
    @Getter @Setter private boolean freqRespShowRiaa         = false;
    /** When {@link #freqRespShowRiaa}, paint the reverse (playback) curve
     *  instead of the record curve.  Vertical mirror around 0 dB at 1 kHz. */
    @Getter @Setter private boolean freqRespReverseRiaa      = false;
    /** When {@link #freqRespShowRiaa}, apply the IEC subsonic high-pass
     *  amendment (T4 = 7950 µs) on top of whichever direction is active. */
    @Getter @Setter private boolean freqRespIecAmendment     = false;
    /** Comparison mode: show measured − reference subtraction trace in
     *  place of the live measurement; auto-zoom to 2 Hz–25 kHz, ±2 dB
     *  over min/max.  Only enabled when a measured result exists. */
    @Getter @Setter private boolean freqRespCompareMode      = false;

    /** When {@code true}, fresh measurements get divided by the loaded
     *  calibration before being shown.  No effect if no calibration is
     *  loaded.  Defaults on so a loaded calibration takes effect by
     *  default. */
    @Getter @Setter private boolean freqRespApplyCalibration = true;
    /** Path to the currently-loaded calibration CSV, or {@code null} when
     *  no calibration is active.  Treated as row 0 of the calibration tab's
     *  multi-row list; subsequent rows are stored in
     *  {@link #freqRespCalibrationPathsExtra}. */
    @Getter @Setter private String  freqRespCalibrationPath;
    /** Paths to additional calibration files loaded beyond row 0.  Each
     *  entry is divided into the measured signal in sequence (linear-mag
     *  divide, phase subtract) at draw / save time.  Empty when only row 0
     *  is loaded; the row count in the UI is {@code 1 + size()}. */
    @Getter @Setter private List<String> freqRespCalibrationPathsExtra = new ArrayList<>();

    /** Last-used folder for the "Save to..." tab; persisted across launches. */
    @Getter @Setter private String  freqRespSaveFolder;
    /** Most-recently-chosen save-to path; restored into the path field at startup. */
    @Getter @Setter private String  freqRespSavePath;
    /** Last-used folder for the "Load from..." tab. */
    @Getter @Setter private String  freqRespLoadFolder;
    /** Most-recently-chosen load-from path; restored into the path field at startup. */
    @Getter @Setter private String  freqRespLoadPath;

    /** Zero-based index of the FreqResp pane's currently-selected tab. */
    @Getter @Setter private int     freqRespActiveTabIndex   = 0;

    private Preferences() {
        load();
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
        return prefsFor(backend);
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

    /** Loads preferences from {@link #PREFS_FILE} if present.  No-op if missing or unreadable. */
    public synchronized void load() {
        Path path = prefsPath();
        if (!Files.exists(path)) return;
        try (Reader r = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(r);
            if (loaded instanceof Map<?, ?> root) {
                fromMap(root);
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
        root.put("backend",                backend.name());
        if (uiLanguage != null) root.put("uiLanguage", uiLanguage);
        if (tabOrientation != null) root.put("tabOrientation", tabOrientation);
        root.put("activeTabIndex", activeTabIndex);
        root.put("smallIconsInMainTab", smallIconsInMainTab);
        root.put("checkForUpdatesOnStartup",  checkForUpdatesOnStartup);
        root.put("includeBetaInUpdateChecks", includeBetaInUpdateChecks);
        root.put("windowWidth",            windowWidth);
        root.put("windowHeight",           windowHeight);
        if (genPaneWidth > 0) root.put("genPaneWidth", genPaneWidth);
        if (multiVSplitWeights != null) root.put("multiVSplitWeights", intArrayToList(multiVSplitWeights));
        root.put("genPaneCollapsed", genPaneCollapsed);
        root.put("oscPaneCollapsed", oscPaneCollapsed);
        root.put("fftPaneCollapsed", fftPaneCollapsed);
        root.put("oscLeftChannelEnabled",  oscLeftChannelEnabled);
        root.put("oscRightChannelEnabled", oscRightChannelEnabled);
        root.put("oscLeftAcMode",          oscLeftAcMode);
        root.put("oscRightAcMode",         oscRightAcMode);
        root.put("oscLeftVoltsPerDiv",  oscLeftVoltsPerDiv);
        root.put("oscRightVoltsPerDiv", oscRightVoltsPerDiv);
        root.put("oscTimePerDiv",       oscTimePerDiv);
        root.put("oscTriggerChannel",      oscTriggerChannel.name());
        root.put("oscTriggerEdge",         oscTriggerEdge.name());
        root.put("oscTriggerMode",         oscTriggerMode.name());
        root.put("oscTriggerHysteresisDiv",     oscTriggerHysteresisDiv);
        root.put("oscTriggerHysteresisEnabled", oscTriggerHysteresisEnabled);
        root.put("oscLeftSincInterpEnabled",  oscLeftSincInterpEnabled);
        root.put("oscRightSincInterpEnabled", oscRightSincInterpEnabled);
        root.put("oscLeftOffsetFrac",      oscLeftOffsetFrac);
        root.put("oscRightOffsetFrac",     oscRightOffsetFrac);
        root.put("oscTriggerLevelFrac",    oscTriggerLevelFrac);
        root.put("oscTriggerPositionFrac", oscTriggerPositionFrac);
        root.put("oscMeasurementAverageSeconds", oscMeasurementAverageSeconds);
        root.put("oscMeasurementChannel",        oscMeasurementChannel.name());
        root.put("oscShowStats",                 oscShowStats);
        root.put("oscShowMeasurementTable",      oscShowMeasurementTable);
        root.put("adcFsVoltageRms",              adcFsVoltageRms);
        root.put("dacFsVoltageRms",              dacFsVoltageRms);
        root.put("genSignalForm",                genSignalForm);
        root.put("genFrequencyHz",               genFrequencyHz);
        root.put("genAmplitudeVrms",             genAmplitudeVrms);
        root.put("genAmplitudeUnit",             genAmplitudeUnit);
        root.put("genDitherBits",                genDitherBits);
        if (genCorrectionsCsv    != null) root.put("genCorrectionsCsv",    genCorrectionsCsv);
        if (genCorrectionsFolder != null) root.put("genCorrectionsFolder", genCorrectionsFolder);
        root.put("genRectangleDuty",      genRectangleDuty);
        root.put("genTriangleDuty",       genTriangleDuty);
        root.put("genSweepFreqStartHz",   genSweepFreqStartHz);
        root.put("genSweepFreqEndHz",     genSweepFreqEndHz);
        root.put("genSweepDurationSec",   genSweepDurationSec);
        root.put("genSweepLoop",          genSweepLoop);
        root.put("genSweepFadeInSec",     genSweepFadeInSec);
        root.put("genSweepFadeOutSec",    genSweepFadeOutSec);
        root.put("genSnapToFftBin",       genSnapToFftBin);
        root.put("genWavDurationSeconds", genWavDurationSeconds);
        if (genWavPath   != null) root.put("genWavPath",   genWavPath);
        if (genWavFolder != null) root.put("genWavFolder", genWavFolder);
        if (genPlayFromPath   != null) root.put("genPlayFromPath",   genPlayFromPath);
        if (genPlayFromFolder != null) root.put("genPlayFromFolder", genPlayFromFolder);
        root.put("genPlayFromLoop", genPlayFromLoop);
        if (oscSavePath   != null) root.put("oscSavePath",   oscSavePath);
        if (oscSaveFolder != null) root.put("oscSaveFolder", oscSaveFolder);
        root.put("oscSaveDurationSeconds", oscSaveDurationSeconds);
        if (oscPlayFromPath   != null) root.put("oscPlayFromPath",   oscPlayFromPath);
        if (oscPlayFromFolder != null) root.put("oscPlayFromFolder", oscPlayFromFolder);
        root.put("oscPlayFromLoop", oscPlayFromLoop);
        root.put("oscLineWidth",                 oscLineWidth);
        root.put("oscDotDiameter",               oscDotDiameter);
        root.put("oscLeftChannelColor",          formatHtmlColor(oscLeftChannelColor));
        root.put("oscRightChannelColor",         formatHtmlColor(oscRightChannelColor));
        if (screenshotWidth  > 0)         root.put("screenshotWidth",  screenshotWidth);
        if (screenshotHeight > 0)         root.put("screenshotHeight", screenshotHeight);
        if (screenshotFolder != null)     root.put("screenshotFolder", screenshotFolder);

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
        root.put("fftLength",                 fftLength);
        root.put("fftAverages",               fftAverages);
        root.put("fftStopAfterNEnabled",      fftStopAfterNEnabled);
        root.put("fftStopAfterN",             fftStopAfterN);
        root.put("fftFundFromGenerator",      fftFundFromGenerator);
        root.put("fftLogFreqAxis",            fftLogFreqAxis);
        root.put("fftWindow",                 fftWindow);
        root.put("fftOverlap",                fftOverlap);
        root.put("fftCoherentAveraging",      fftCoherentAveraging);
        root.put("fftDistMinHz",              fftDistMinHz);
        root.put("fftDistMaxHz",              fftDistMaxHz);
        root.put("fftDistMinEnabled",         fftDistMinEnabled);
        root.put("fftDistMaxEnabled",         fftDistMaxEnabled);
        root.put("fftThdMaxHarmonic",         fftThdMaxHarmonic);
        root.put("fftCalcMaxHarmonic",        fftCalcMaxHarmonic);
        root.put("fftManualFundVrms",         fftManualFundVrms);
        root.put("fftManualFundUnit",         fftManualFundUnit);
        root.put("fftManualFundEnabled",      fftManualFundEnabled);
        root.put("fftChannel",                fftChannel.name());
        root.put("fftMagUnit",                fftMagUnit);
        root.put("fftDistortionTableVisible", fftDistortionTableVisible);
        root.put("fftFreqMinHz",              fftFreqMinHz);
        root.put("fftFreqMaxHz",              fftFreqMaxHz);
        root.put("fftMagTop",                 fftMagTop);
        root.put("fftMagBottom",              fftMagBottom);
        if (fftSavePath   != null) root.put("fftSavePath",   fftSavePath);
        if (fftSaveFolder != null) root.put("fftSaveFolder", fftSaveFolder);
        if (fftLoadPath   != null) root.put("fftLoadPath",   fftLoadPath);
        if (fftLoadFolder != null) root.put("fftLoadFolder", fftLoadFolder);
        if (fftCalibrationPath != null) root.put("fftCalibrationPath", fftCalibrationPath);
        if (fftCalibrationPathsExtra != null && !fftCalibrationPathsExtra.isEmpty()) {
            root.put("fftCalibrationPathsExtra", new ArrayList<>(fftCalibrationPathsExtra));
        }
        root.put("fftCalibrateWithNoise", fftCalibrateWithNoise);
        root.put("fftBeforeCalDotColor", fftBeforeCalDotColor);
        root.put("fftCalOverlayColor",   fftCalOverlayColor);
        root.put("fftLineWidth",              fftLineWidth);
        root.put("fftHarmonicDotDiameter",    fftHarmonicDotDiameter);
        root.put("fftLineColor",              formatHtmlColor(fftLineColor));
        root.put("fftChartBackgroundColor",   formatHtmlColor(fftChartBackgroundColor));
        root.put("fftHarmonicDotColor",       formatHtmlColor(fftHarmonicDotColor));
        root.put("fftFreqRespColor",          formatHtmlColor(fftFreqRespColor));

        // ---- Frequency Response pane --------------------------------------
        root.put("freqRespStartHz",           freqRespStartHz);
        root.put("freqRespStopHz",            freqRespStopHz);
        root.put("freqRespAmplitudeVrms",     freqRespAmplitudeVrms);
        root.put("freqRespSweepPoints",       freqRespSweepPoints);
        root.put("freqRespDurationSec",       freqRespDurationSec);
        root.put("freqRespFftSize",           freqRespFftSize);
        root.put("freqRespDitherBits",        freqRespDitherBits);
        root.put("freqRespLeadInSec",         freqRespLeadInSec);
        root.put("freqRespLeftVisible",       freqRespLeftVisible);
        root.put("freqRespRightVisible",      freqRespRightVisible);
        root.put("freqRespPhaseVisible",      freqRespPhaseVisible);
        root.put("freqRespFreqMinHz",         freqRespFreqMinHz);
        root.put("freqRespFreqMaxHz",         freqRespFreqMaxHz);
        root.put("freqRespMagTopDb",          freqRespMagTopDb);
        root.put("freqRespMagBotDb",          freqRespMagBotDb);
        root.put("freqRespNyquistFraction",   freqRespNyquistFraction);
        root.put("freqRespCompareSmoothWindow", freqRespCompareSmoothWindow);
        root.put("freqRespNotchEnabled",        freqRespNotchEnabled);
        root.put("freqRespNotchBaseHz",         freqRespNotchBaseHz);
        root.put("freqRespSignalColor",         freqRespSignalColor);
        root.put("freqRespPhaseColor",          freqRespPhaseColor);
        root.put("freqRespReferenceColor",      freqRespReferenceColor);
        root.put("freqRespBackgroundColor",     freqRespBackgroundColor);
        // Note: freqRespShowRiaa is intentionally NOT persisted — it always
        // starts unchecked on a fresh session.
        root.put("freqRespReverseRiaa",       freqRespReverseRiaa);
        root.put("freqRespIecAmendment",      freqRespIecAmendment);
        root.put("freqRespCompareMode",       freqRespCompareMode);
        root.put("freqRespApplyCalibration",  freqRespApplyCalibration);
        if (freqRespCalibrationPath != null) root.put("freqRespCalibrationPath", freqRespCalibrationPath);
        if (freqRespCalibrationPathsExtra != null && !freqRespCalibrationPathsExtra.isEmpty()) {
            root.put("freqRespCalibrationPathsExtra", new ArrayList<>(freqRespCalibrationPathsExtra));
        }
        if (freqRespSaveFolder      != null) root.put("freqRespSaveFolder",      freqRespSaveFolder);
        if (freqRespSavePath        != null) root.put("freqRespSavePath",        freqRespSavePath);
        if (freqRespLoadFolder      != null) root.put("freqRespLoadFolder",      freqRespLoadFolder);
        if (freqRespLoadPath        != null) root.put("freqRespLoadPath",        freqRespLoadPath);
        root.put("freqRespActiveTabIndex",    freqRespActiveTabIndex);

        if (!fftPresets.isEmpty()) {
            Map<String, Object> fpMap = new LinkedHashMap<>();
            for (Map.Entry<String, FftPreset> e : fftPresets.entrySet()) {
                FftPreset p = e.getValue();
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("channel",           p.getChannel().name());
                pm.put("magUnit",           p.getMagUnit());
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
                pm.put("window",            p.getWindow());
                pm.put("overlap",           p.getOverlap());
                pm.put("coherentAveraging", p.isCoherentAveraging());
                pm.put("distMinHz",         p.getDistMinHz());
                pm.put("distMaxHz",         p.getDistMaxHz());
                pm.put("distMinEnabled",    p.isDistMinEnabled());
                pm.put("distMaxEnabled",    p.isDistMaxEnabled());
                pm.put("thdMaxHarmonic",    p.getThdMaxHarmonic());
                pm.put("calcMaxHarmonic",   p.getCalcMaxHarmonic());
                pm.put("manualFundVrms",    p.getManualFundVrms());
                pm.put("manualFundUnit",    p.getManualFundUnit());
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
        if (root.get("uiLanguage") instanceof String s) uiLanguage = s;
        if (root.get("tabOrientation") instanceof String s) tabOrientation = s;
        if (root.get("activeTabIndex") instanceof Number n) activeTabIndex = n.intValue();
        if (root.get("smallIconsInMainTab") instanceof Boolean b) smallIconsInMainTab = b;
        if (root.get("checkForUpdatesOnStartup")  instanceof Boolean b) checkForUpdatesOnStartup  = b;
        if (root.get("includeBetaInUpdateChecks") instanceof Boolean b) includeBetaInUpdateChecks = b;
        if (root.get("backend") instanceof String s) {
            backend = enumOr(AudioBackendType.class, s, backend);
        }
        if (root.get("windowWidth")            instanceof Integer i) windowWidth            = i;
        if (root.get("windowHeight")           instanceof Integer i) windowHeight           = i;
        if (root.get("genPaneWidth")           instanceof Number n) genPaneWidth           = n.intValue();
        if (root.get("multiVSplitWeights")     instanceof List<?> l) multiVSplitWeights     = listToIntArray(l);
        if (root.get("genPaneCollapsed")       instanceof Boolean b) genPaneCollapsed       = b;
        if (root.get("oscPaneCollapsed")       instanceof Boolean b) oscPaneCollapsed       = b;
        if (root.get("fftPaneCollapsed")       instanceof Boolean b) fftPaneCollapsed       = b;
        if (root.get("oscLeftChannelEnabled")  instanceof Boolean b) oscLeftChannelEnabled  = b;
        if (root.get("oscLeftAcMode")          instanceof Boolean b) oscLeftAcMode          = b;
        if (root.get("oscRightAcMode")         instanceof Boolean b) oscRightAcMode         = b;
        if (root.get("oscRightChannelEnabled") instanceof Boolean b) oscRightChannelEnabled = b;
        // New double-valued V/div and t/div fields.  Free-form: not snapped
        // to the standard step list, so 45 mV/div stays 45 mV/div.
        if (root.get("oscLeftVoltsPerDiv")     instanceof Number n) oscLeftVoltsPerDiv  = n.doubleValue();
        if (root.get("oscRightVoltsPerDiv")    instanceof Number n) oscRightVoltsPerDiv = n.doubleValue();
        if (root.get("oscTimePerDiv")          instanceof Number n) oscTimePerDiv       = n.doubleValue();
        if (root.get("oscTriggerChannel")      instanceof String  s) oscTriggerChannel = enumOr(Channel.class, s, oscTriggerChannel);
        if (root.get("oscTriggerEdge")         instanceof String  s) oscTriggerEdge    = enumOr(TriggerEdge.class,    s, oscTriggerEdge);
        if (root.get("oscTriggerMode")         instanceof String  s) oscTriggerMode    = enumOr(TriggerMode.class,    s, oscTriggerMode);
        if (root.get("oscTriggerHysteresisDiv")     instanceof Number  n) oscTriggerHysteresisDiv     = n.doubleValue();
        if (root.get("oscTriggerHysteresisEnabled") instanceof Boolean b) oscTriggerHysteresisEnabled = b;
        if (root.get("oscLeftSincInterpEnabled")  instanceof Boolean b) oscLeftSincInterpEnabled  = b;
        if (root.get("oscRightSincInterpEnabled") instanceof Boolean b) oscRightSincInterpEnabled = b;
        if (root.get("oscLeftOffsetFrac")      instanceof Number n) oscLeftOffsetFrac      = n.doubleValue();
        if (root.get("oscRightOffsetFrac")     instanceof Number n) oscRightOffsetFrac     = n.doubleValue();
        if (root.get("oscTriggerLevelFrac")    instanceof Number n) oscTriggerLevelFrac    = n.doubleValue();
        if (root.get("oscTriggerPositionFrac") instanceof Number n) oscTriggerPositionFrac = n.doubleValue();
        if (root.get("oscMeasurementAverageSeconds") instanceof Number n) oscMeasurementAverageSeconds = n.doubleValue();
        if (root.get("oscMeasurementChannel")        instanceof String s) oscMeasurementChannel = enumOr(Channel.class, s, oscMeasurementChannel);
        if (root.get("oscShowStats")                 instanceof Boolean b) oscShowStats         = b;
        if (root.get("oscShowMeasurementTable")      instanceof Boolean b) oscShowMeasurementTable = b;
        if (root.get("adcFsVoltageRms")              instanceof Number n) adcFsVoltageRms      = n.doubleValue();
        if (root.get("dacFsVoltageRms")              instanceof Number n) dacFsVoltageRms      = n.doubleValue();
        if (root.get("genSignalForm")                instanceof String s) genSignalForm        = s;
        if (root.get("genFrequencyHz")               instanceof Number n) genFrequencyHz       = n.doubleValue();
        if (root.get("genAmplitudeVrms")             instanceof Number n) genAmplitudeVrms     = n.doubleValue();
        if (root.get("genAmplitudeUnit")             instanceof String s) genAmplitudeUnit     = s;
        if (root.get("genDitherBits")                instanceof Number n) genDitherBits        = n.intValue();
        if (root.get("genCorrectionsCsv")            instanceof String s) genCorrectionsCsv    = s;
        if (root.get("genCorrectionsFolder")         instanceof String s) genCorrectionsFolder    = s;
        if (root.get("genRectangleDuty")             instanceof Number n) genRectangleDuty       = n.doubleValue();
        if (root.get("genTriangleDuty")              instanceof Number n) genTriangleDuty        = n.doubleValue();
        if (root.get("genSweepFreqStartHz")          instanceof Number n) genSweepFreqStartHz    = n.doubleValue();
        if (root.get("genSweepFreqEndHz")            instanceof Number n) genSweepFreqEndHz      = n.doubleValue();
        if (root.get("genSweepDurationSec")          instanceof Number n) genSweepDurationSec    = n.doubleValue();
        if (root.get("genSweepLoop")                 instanceof Boolean b) genSweepLoop          = b;
        if (root.get("genSweepFadeInSec")            instanceof Number n) genSweepFadeInSec      = n.doubleValue();
        if (root.get("genSweepFadeOutSec")           instanceof Number n) genSweepFadeOutSec     = n.doubleValue();
        if (root.get("genSnapToFftBin")              instanceof Boolean b) genSnapToFftBin       = b;
        if (root.get("genWavDurationSeconds")        instanceof Number n) genWavDurationSeconds  = n.doubleValue();
        if (root.get("genWavPath")                   instanceof String s) genWavPath             = s;
        if (root.get("genWavFolder")                 instanceof String s) genWavFolder           = s;
        if (root.get("genPlayFromPath")              instanceof String s) genPlayFromPath        = s;
        if (root.get("genPlayFromFolder")            instanceof String s) genPlayFromFolder      = s;
        if (root.get("genPlayFromLoop")              instanceof Boolean b) genPlayFromLoop       = b;
        if (root.get("oscSavePath")                  instanceof String s) oscSavePath            = s;
        if (root.get("oscSaveFolder")                instanceof String s) oscSaveFolder          = s;
        if (root.get("oscSaveDurationSeconds")       instanceof Number n) oscSaveDurationSeconds = n.doubleValue();
        if (root.get("oscPlayFromPath")              instanceof String s)  oscPlayFromPath       = s;
        if (root.get("oscPlayFromFolder")            instanceof String s)  oscPlayFromFolder     = s;
        if (root.get("oscPlayFromLoop")              instanceof Boolean b) oscPlayFromLoop       = b;
        if (root.get("oscLineWidth")                 instanceof Number n) oscLineWidth         = n.doubleValue();
        if (root.get("oscDotDiameter")               instanceof Number n) oscDotDiameter       = n.intValue();
        Object leftColorObj  = root.get("oscLeftChannelColor");
        Object rightColorObj = root.get("oscRightChannelColor");
        if (leftColorObj  instanceof String s) oscLeftChannelColor  = parseHtmlColor(s, oscLeftChannelColor);
        else if (leftColorObj  instanceof Number n) oscLeftChannelColor  = n.intValue();
        if (rightColorObj instanceof String s) oscRightChannelColor = parseHtmlColor(s, oscRightChannelColor);
        else if (rightColorObj instanceof Number n) oscRightChannelColor = n.intValue();
        if (root.get("screenshotWidth")              instanceof Number n) screenshotWidth      = n.intValue();
        if (root.get("screenshotHeight")             instanceof Number n) screenshotHeight     = n.intValue();
        if (root.get("screenshotFolder")             instanceof String s) screenshotFolder     = s;

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
        if (root.get("fftLength")                 instanceof Number  n) fftLength            = n.intValue();
        if (root.get("fftAverages")               instanceof Number  n) fftAverages          = n.doubleValue();
        if (root.get("fftStopAfterNEnabled")      instanceof Boolean b) fftStopAfterNEnabled = b;
        if (root.get("fftStopAfterN")             instanceof Number  n) fftStopAfterN        = n.intValue();
        if (root.get("fftFundFromGenerator")      instanceof Boolean b) fftFundFromGenerator = b;
        if (root.get("fftLogFreqAxis")            instanceof Boolean b) fftLogFreqAxis       = b;
        if (root.get("fftWindow")                 instanceof String  s) fftWindow            = s;
        if (root.get("fftOverlap")                instanceof String  s) fftOverlap           = s;
        if (root.get("fftCoherentAveraging")      instanceof Boolean b) fftCoherentAveraging = b;
        if (root.get("fftDistMinHz")              instanceof Number  n) fftDistMinHz         = n.doubleValue();
        if (root.get("fftDistMaxHz")              instanceof Number  n) fftDistMaxHz         = n.doubleValue();
        if (root.get("fftDistMinEnabled")         instanceof Boolean b) fftDistMinEnabled    = b;
        if (root.get("fftDistMaxEnabled")         instanceof Boolean b) fftDistMaxEnabled    = b;
        if (root.get("fftThdMaxHarmonic")         instanceof Number  n) fftThdMaxHarmonic    = n.intValue();
        if (root.get("fftCalcMaxHarmonic")        instanceof Number  n) fftCalcMaxHarmonic   = n.intValue();
        if (root.get("fftManualFundVrms")         instanceof Number  n) fftManualFundVrms    = n.doubleValue();
        if (root.get("fftManualFundUnit")         instanceof String  s) fftManualFundUnit    = s;
        if (root.get("fftManualFundEnabled")      instanceof Boolean b) fftManualFundEnabled = b;
        if (root.get("fftChannel")                instanceof String  s) fftChannel           = enumOr(Channel.class, s, fftChannel);
        if (root.get("fftMagUnit")                instanceof String  s) fftMagUnit           = s;
        if (root.get("fftDistortionTableVisible") instanceof Boolean b) fftDistortionTableVisible = b;
        if (root.get("fftFreqMinHz")              instanceof Number  n) fftFreqMinHz         = n.doubleValue();
        if (root.get("fftFreqMaxHz")              instanceof Number  n) fftFreqMaxHz         = n.doubleValue();
        if (root.get("fftMagTop")                 instanceof Number  n) fftMagTop            = n.doubleValue();
        if (root.get("fftMagBottom")              instanceof Number  n) fftMagBottom         = n.doubleValue();
        if (root.get("fftSavePath")               instanceof String  s) fftSavePath          = s;
        if (root.get("fftSaveFolder")             instanceof String  s) fftSaveFolder        = s;
        if (root.get("fftLoadPath")               instanceof String  s) fftLoadPath          = s;

        // ---- Frequency Response pane --------------------------------------
        if (root.get("freqRespStartHz")           instanceof Number  n) freqRespStartHz          = n.doubleValue();
        if (root.get("freqRespStopHz")            instanceof Number  n) freqRespStopHz           = n.doubleValue();
        if (root.get("freqRespAmplitudeVrms")     instanceof Number  n) freqRespAmplitudeVrms    = n.doubleValue();
        if (root.get("freqRespSweepPoints")       instanceof Number  n) freqRespSweepPoints      = n.intValue();
        if (root.get("freqRespDurationSec")       instanceof Number  n) freqRespDurationSec      = n.doubleValue();
        if (root.get("freqRespFftSize")           instanceof Number  n) {
            // Snap any non-power-of-two value to the nearest legal one
            // (between 64k and 16M) so the UI combo can match a row.
            int v = n.intValue();
            v = Math.max(1 << 16, Math.min(1 << 24, v));
            // Round up to the next power of two so the combo's
            // selectionIndex maps cleanly.
            int p = 1 << 16;
            while (p < v) p <<= 1;
            freqRespFftSize = p;
        }
        if (root.get("freqRespDitherBits")        instanceof Number  n) freqRespDitherBits       = n.intValue();
        if (root.get("freqRespLeadInSec")         instanceof Number  n) freqRespLeadInSec        = n.doubleValue();
        if (root.get("freqRespLeftVisible")       instanceof Boolean b) freqRespLeftVisible      = b;
        if (root.get("freqRespRightVisible")      instanceof Boolean b) freqRespRightVisible     = b;
        if (root.get("freqRespPhaseVisible")      instanceof Boolean b) freqRespPhaseVisible     = b;
        if (root.get("freqRespFreqMinHz")         instanceof Number  n) freqRespFreqMinHz        = n.doubleValue();
        if (root.get("freqRespFreqMaxHz")         instanceof Number  n) freqRespFreqMaxHz        = n.doubleValue();
        if (root.get("freqRespMagTopDb")          instanceof Number  n) freqRespMagTopDb         = n.doubleValue();
        if (root.get("freqRespMagBotDb")          instanceof Number  n) freqRespMagBotDb         = n.doubleValue();
        if (root.get("freqRespNyquistFraction")   instanceof Number  n) {
            // Clamp into the [0.83, 1.0] band the UI exposes — older
            // YAML files may still carry the legacy 0.40-0.50 range.
            double v = n.doubleValue();
            freqRespNyquistFraction = Math.max(0.83, Math.min(1.0, v < 0.83 ? 1.0 : v));
        }
        if (root.get("freqRespCompareSmoothWindow") instanceof Number n) {
            freqRespCompareSmoothWindow = Math.max(0, Math.min(100, n.intValue()));
        }
        if (root.get("freqRespNotchEnabled") instanceof Boolean b) freqRespNotchEnabled = b;
        if (root.get("freqRespNotchBaseHz")  instanceof Number  n) {
            int v = n.intValue();
            // Snap any non-50/60 value back to 50 (EU default) — the UI
            // exposes only those two choices.
            freqRespNotchBaseHz = (v == 60) ? 60 : 50;
        }
        Object signalColorObj = root.get("freqRespSignalColor");
        if (signalColorObj instanceof String s) freqRespSignalColor = parseHtmlColor(s, freqRespSignalColor);
        else if (signalColorObj instanceof Number n) freqRespSignalColor = n.intValue();
        Object phaseColorObj  = root.get("freqRespPhaseColor");
        if (phaseColorObj  instanceof String s) freqRespPhaseColor  = parseHtmlColor(s, freqRespPhaseColor);
        else if (phaseColorObj  instanceof Number n) freqRespPhaseColor  = n.intValue();
        Object refColorObj    = root.get("freqRespReferenceColor");
        if (refColorObj    instanceof String s) freqRespReferenceColor = parseHtmlColor(s, freqRespReferenceColor);
        else if (refColorObj    instanceof Number n) freqRespReferenceColor = n.intValue();
        Object bgColorObj     = root.get("freqRespBackgroundColor");
        if (bgColorObj     instanceof String s) freqRespBackgroundColor = parseHtmlColor(s, freqRespBackgroundColor);
        else if (bgColorObj     instanceof Number n) freqRespBackgroundColor = n.intValue();
        // freqRespShowRiaa is intentionally not loaded from disk — it
        // always starts unchecked on a fresh session.
        if (root.get("freqRespReverseRiaa")       instanceof Boolean b) freqRespReverseRiaa      = b;
        if (root.get("freqRespIecAmendment")      instanceof Boolean b) freqRespIecAmendment     = b;
        if (root.get("freqRespCompareMode")       instanceof Boolean b) freqRespCompareMode      = b;
        if (root.get("freqRespApplyCalibration")  instanceof Boolean b) freqRespApplyCalibration = b;
        if (root.get("freqRespCalibrationPath")   instanceof String  s) freqRespCalibrationPath  = s;
        if (root.get("freqRespCalibrationPathsExtra") instanceof List<?> raw) {
            freqRespCalibrationPathsExtra = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof String s) freqRespCalibrationPathsExtra.add(s);
            }
        }
        if (root.get("freqRespSaveFolder")        instanceof String  s) freqRespSaveFolder       = s;
        if (root.get("freqRespSavePath")          instanceof String  s) freqRespSavePath         = s;
        if (root.get("freqRespLoadFolder")        instanceof String  s) freqRespLoadFolder       = s;
        if (root.get("freqRespLoadPath")          instanceof String  s) freqRespLoadPath         = s;
        if (root.get("freqRespActiveTabIndex")    instanceof Number  n) freqRespActiveTabIndex   = n.intValue();
        if (root.get("fftLoadFolder")             instanceof String  s) fftLoadFolder        = s;
        if (root.get("fftCalibrationPath")        instanceof String  s) fftCalibrationPath   = s;
        if (root.get("fftCalibrationPathsExtra")  instanceof List<?> raw) {
            fftCalibrationPathsExtra = new ArrayList<>();
            for (Object o : raw) if (o instanceof String s) fftCalibrationPathsExtra.add(s);
        }
        if (root.get("fftCalibrateWithNoise")     instanceof Boolean b) fftCalibrateWithNoise = b;
        Object beforeCalColorObj = root.get("fftBeforeCalDotColor");
        if (beforeCalColorObj instanceof String s) fftBeforeCalDotColor = parseHtmlColor(s, fftBeforeCalDotColor);
        else if (beforeCalColorObj instanceof Number n) fftBeforeCalDotColor = n.intValue();
        Object calOverlayColorObj = root.get("fftCalOverlayColor");
        if (calOverlayColorObj instanceof String s) fftCalOverlayColor = parseHtmlColor(s, fftCalOverlayColor);
        else if (calOverlayColorObj instanceof Number n) fftCalOverlayColor = n.intValue();
        if (root.get("fftLineWidth")              instanceof Number  n) fftLineWidth           = n.doubleValue();
        if (root.get("fftHarmonicDotDiameter")    instanceof Number  n) fftHarmonicDotDiameter = n.intValue();
        Object fftLineColorObj   = root.get("fftLineColor");
        if (fftLineColorObj   instanceof String s) fftLineColor            = parseHtmlColor(s, fftLineColor);
        else if (fftLineColorObj   instanceof Number n) fftLineColor       = n.intValue();
        Object fftBgColorObj     = root.get("fftChartBackgroundColor");
        if (fftBgColorObj     instanceof String s) fftChartBackgroundColor = parseHtmlColor(s, fftChartBackgroundColor);
        else if (fftBgColorObj     instanceof Number n) fftChartBackgroundColor = n.intValue();
        Object fftDotColorObj    = root.get("fftHarmonicDotColor");
        if (fftDotColorObj    instanceof String s) fftHarmonicDotColor     = parseHtmlColor(s, fftHarmonicDotColor);
        else if (fftDotColorObj    instanceof Number n) fftHarmonicDotColor = n.intValue();
        Object fftFiltColorObj   = root.get("fftFreqRespColor");
        if (fftFiltColorObj   instanceof String s) fftFreqRespColor  = parseHtmlColor(s, fftFreqRespColor);
        else if (fftFiltColorObj   instanceof Number n) fftFreqRespColor = n.intValue();

        if (root.get("fftPresets") instanceof Map<?, ?> fpMap) {
            fftPresets.clear();
            for (Map.Entry<?, ?> e : fpMap.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (!(e.getValue() instanceof Map<?, ?> pm)) continue;
                FftPreset p = new FftPreset();
                if (pm.get("channel")           instanceof String  s) p.setChannel(enumOr(Channel.class, s, p.getChannel()));
                if (pm.get("magUnit")           instanceof String  s) p.setMagUnit(s);
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
                if (pm.get("window")            instanceof String  s) p.setWindow(s);
                if (pm.get("overlap")           instanceof String  s) p.setOverlap(s);
                if (pm.get("coherentAveraging") instanceof Boolean b) p.setCoherentAveraging(b);
                if (pm.get("distMinHz")         instanceof Number  n) p.setDistMinHz(n.doubleValue());
                if (pm.get("distMaxHz")         instanceof Number  n) p.setDistMaxHz(n.doubleValue());
                if (pm.get("distMinEnabled")    instanceof Boolean b) p.setDistMinEnabled(b);
                if (pm.get("distMaxEnabled")    instanceof Boolean b) p.setDistMaxEnabled(b);
                if (pm.get("thdMaxHarmonic")    instanceof Number  n) p.setThdMaxHarmonic(n.intValue());
                if (pm.get("calcMaxHarmonic")   instanceof Number  n) p.setCalcMaxHarmonic(n.intValue());
                if (pm.get("manualFundVrms")    instanceof Number  n) p.setManualFundVrms(n.doubleValue());
                if (pm.get("manualFundUnit")    instanceof String  s) p.setManualFundUnit(s);
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
