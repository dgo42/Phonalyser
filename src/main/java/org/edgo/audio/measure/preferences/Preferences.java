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

package org.edgo.audio.measure.preferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.common.FileVersions;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.FilterResponse;
import org.edgo.audio.measure.enums.FilterType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.LpfMode;
import org.edgo.audio.measure.enums.MagnitudeUnit;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.enums.PersistenceMode;
import org.edgo.audio.measure.enums.TabOrientation;
import org.edgo.audio.measure.enums.TriggerEdge;
import org.edgo.audio.measure.enums.TriggerMode;
import org.edgo.audio.measure.enums.TriggerType;
import org.edgo.audio.measure.enums.UnevenMode;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.gui.preferences.PreferencesDialog;
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

    /** Name of the per-card profile store, beside {@link #PREFS_FILE} in the
     *  user data dir, and of the bundled classpath seed of the same name. */
    private static final String DEVICES_FILE = "devices.yaml";

    /** Classpath location of the bundled {@code devices.yaml} seed. */
    private static final String DEVICES_SEED_RESOURCE = "/devices.yaml";

    /** Debounce window for auto-save: a burst of bound-property changes
     *  (a drag, a resize, fast typing) coalesces into a single file write
     *  this many milliseconds after the last change. */
    private static final long SAVE_COALESCE_MS = 250;

    /** Factory-default ADC full-scale RMS voltage, used until the user calibrates. */
    private static final double DEFAULT_ADC_FS_VRMS = 1.7931;

    /** Sentinel "unset" value for the rate-dependent FreqResp defaults
     *  (stop = Nyquist, points = FS/2).  A fresh install with no saved value
     *  keeps this until {@link #seedRateDependentFreqRespDefaults()} resolves it
     *  from the current device sample rate; any saved value is a real number and
     *  overrides it. */
    private static final double FREQRESP_RATE_DEFAULT_SENTINEL = 0.0;
    private static final int    FREQRESP_RATE_DEFAULT_SENTINEL_INT = 0;

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

    /** UI font specs, format {@code name|height|style} (style: normal /
     *  bold).  NORMAL covers axis labels, readouts and measurement-table
     *  body text across the FFT / scope / FreqResp views; BOLD the
     *  emphasised measurement-table text; CHANNEL the big L/R channel
     *  buttons (central creation point only — no dialog UI).  Changing
     *  the first two in the Preferences dialog triggers a shell recreate
     *  so every painter picks the new fonts up. */
    private final Property<String> uiFontNormal  = bound(defaultUiFont("normal", 0));
    private final Property<String> uiFontBold    = bound(defaultUiFont("bold", 0));
    private final Property<String> uiFontChannel = bound(defaultUiFont("bold", 3));

    /** When true, the GUI checks GitHub releases for a newer version on
     *  startup.  Stays off by default so the app never makes network
     *  calls without explicit user opt-in. */
    private final Property<Boolean> checkForUpdatesOnStartup = bound(false);
    /** When true, beta / pre-release builds are also considered by the
     *  startup update check.  Ignored when
     *  {@link #checkForUpdatesOnStartup} is false. */
    private final Property<Boolean> includeBetaInUpdateChecks = bound(false);
    /** When true, a "Tip of the day" popup is shown at startup.  Cleared by
     *  the dialog's "Don't show again" checkbox or in Preferences. */
    private final Property<Boolean> showTipsAtStartup = bound(true);
    /** When true, the oscilloscope renders on the GPU (NanoVG/OpenGL) where available
     *  — gated by {@code GpuSupport}, so it has no effect on a machine without a
     *  working GL context (the checkbox is disabled there). */
    private final Property<Boolean> useGpuAcceleration = bound(true);

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
    /** Trigger event type: EDGE = level crossing, GLITCH = dV/dt jump. */
    private final Property<TriggerType>    oscTriggerType    = bound(TriggerType.EDGE);
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
    /** Per-channel residual toggle.  When on, the scope paints the residual
     *  (displayed trace minus its best-fit single tone) instead of the
     *  captured trace; DC stays in the trace (the AC toggle handles DC). */
    private final Property<Boolean>        oscLeftResidualEnabled    = bound(false);
    private final Property<Boolean>        oscRightResidualEnabled   = bound(false);
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
    /** Display persistence ("digital phosphor") mode — GPU path only. */
    private final Property<PersistenceMode> oscPersistenceMode = bound(PersistenceMode.OFF);
    /** Manual persistence time (seconds) used when {@link #oscPersistenceMode} is MANUAL. */
    private final Property<Double>         oscPersistenceManualSeconds = bound(1.0);
    /** Trace stroke width (pixels). */
    private final Property<Double>         oscLineWidth         = bound(2.0);
    /** Sample-dot diameter (pixels) when the inter-sample spacing exceeds 10 px. */
    private final Property<Integer>        oscDotDiameter       = bound(5);
    /** Packed RGB (0xRRGGBB) of the left and right channel traces. Default left #00D7FF and right channels #FFD700 */
    private final Property<Integer>        oscLeftChannelColor  = bound(0x00D7FF);
    private final Property<Integer>        oscRightChannelColor = bound(0xFFD700);

    /** Last screenshot resolution / folder, 0 / null = use the pane's current size / system default. */
    private final Property<Integer> screenshotWidth  = bound(0);
    private final Property<Integer> screenshotHeight = bound(0);
    private final Property<String>  screenshotFolder = bound(null);
    /** Font for the screenshot comment caption as an SWT FontData string; null = system default. */
    private final Property<String>  screenshotCommentFont = bound(null);
    /** Channel whose live samples drive the measurement table (auto-flipped to the other when this one is disabled). */
    private final Property<Channel> oscMeasurementChannel = bound(Channel.L);
    /** Toggles the measurement table's stats columns (avg/min/max/σ); the worker keeps computing them in the background regardless. */
    private final Property<Boolean> oscShowStats = bound(true);
    /** Master toggle for the measurement table overlay in the scope view —
     *  when false, only the L / R channel-pick buttons and the table
     *  show / hide toggle are visible; the rows and the stats / reset
     *  buttons are hidden. */
    private final Property<Boolean> oscShowMeasurementTable = bound(true);
    /** ADC full-scale RMS voltage — calibration constant used to translate normalised samples into volts.
     *  This scalar is the LEFT channel value, the LINKED-mode value for both channels, and the legacy
     *  fallback read by every channel-less consumer.  Persisted across launches. */
    private final Property<Double> adcFsVoltageRms = bound(DEFAULT_ADC_FS_VRMS);
    /** RIGHT-channel ADC full-scale RMS voltage — the per-channel sibling of {@link #adcFsVoltageRms}.
     *  Equals the left value in LINKED mode (and for old preference files that lack the key); an
     *  INDEPENDENT card resolves it from its right active range. */
    private final Property<Double> adcFsVoltageRmsRight = bound(DEFAULT_ADC_FS_VRMS);
    /** Cached dBV↔dBFS offset (= {@code 20·log10(adcFsVoltageRms)}).  Initialised from the
     *  default and recomputed whenever the ADC full-scale is set / loaded, so it is valid even
     *  when no {@code preferences.yaml} exists (or lacks the key) and in detached dialog copies;
     *  {@code volatile} so the off-thread FFT consumers see the update.
     *  {@code dBV = dBFS + dbvOffsetDb}.  This is the LEFT / LINKED / fallback offset. */
    @Getter
    private volatile double dbvOffsetDb = 20.0 * Math.log10(DEFAULT_ADC_FS_VRMS);
    /** Cached RIGHT-channel dBV↔dBFS offset (= {@code 20·log10(adcFsVoltageRmsRight)}) — the
     *  per-channel sibling of {@link #dbvOffsetDb}, recomputed alongside it. */
    private volatile double dbvOffsetDbRight = 20.0 * Math.log10(DEFAULT_ADC_FS_VRMS);
    /** Cached √(bin bandwidth) of the live FFT config (= {@code √(inputSampleRate /
     *  fftLength)}) — the V→V/√Hz divisor in {@link #convertFromDbFs}.  Recomputed via
     *  the {@code fftLength} / {@code backend} property listeners (bidi-bound GUI edits
     *  bypass the setters) and after load / dialog-apply (the per-backend sample rate
     *  is a plain POJO write that fires no property event); {@code volatile} so
     *  off-thread consumers see the update. */
    @Getter
    private volatile double binBwSqrt = 1.0;
    /** When {@code true}, {@link #save()} is a no-op.  Set by the CLI so a
     *  {@code --adc-fs-vrms} (or any other) value injected into Preferences for
     *  one headless run is never written back to the user's YAML.  Default
     *  {@code false} — the GUI persists normally. */
    @Getter
    @Setter
    private volatile boolean transientMode;
    /** DAC full-scale PEAK amplitude voltage (= full-scale-sine RMS × √2) — the
     *  value the generator's amplitude scale divides by, so it is kept in this
     *  amplitude form in memory (suited for the calculation).  It is PERSISTED
     *  as RMS ({@code dacFsVoltageRms} in the yaml): converted ampl→RMS on save
     *  and RMS→ampl on load, at the {@link #toMap}/{@link #fromMap} boundary.
     *  Calibrated by the user via the "Calibrate DAC" button in the generator
     *  pane and read directly by {@code SignalGenerator}.  This scalar is the LEFT
     *  channel value, the MONO/LINKED-mirror value, and the legacy fallback. */
    private final Property<Double> dacFsVoltageAmpl = bound(2.79351);
    /** RIGHT-channel DAC full-scale PEAK amplitude — the per-channel sibling of
     *  {@link #dacFsVoltageAmpl}.  Equals the left value in MONO mode (and for old
     *  preference files that lack the key); LINKED reads both {@code fsLeft} /
     *  {@code fsRight} of the shared active row, an INDEPENDENT card resolves it
     *  from its right active range.  Persisted as RMS ({@code dacFsVoltageRmsRight}
     *  in the yaml), converted ampl↔RMS at the {@link #toMap}/{@link #fromMap} boundary. */
    private final Property<Double> dacFsVoltageAmplRight = bound(2.79351);

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
    /** True = the generator amplitude field displays in dBV (the user typed
     *  an explicit dBV suffix); persisted so a restart keeps the choice. */
    private final Property<Boolean> genAmplitudeDbvDisplay = bound(false);
    /** Unit the amplitude field renders in: one of {@code mV}, {@code V}, {@code dBV}, {@code dBFS}. */
    /** Dither depth in bits, 0..N (may be fractional); 0 means "Off". */
    private final Property<Double> genDitherBits  = bound(0.0);
    /** True = the generator dither field displays in dBV (the user typed an
     *  explicit dBV suffix); persisted so a restart keeps the choice. */
    private final Property<Boolean> genDitherDbvDisplay = bound(false);
    /** Which output lane(s) the generator drives — the encoder gate ({@code BOTH}
     *  by default = today's behaviour). */
    private final Property<OutputChannels> genOutputChannels = bound(OutputChannels.BOTH);
    /** Path to the SINGLE-tone predistortion {@code .dpd} used by
     *  {@code SINE_COMPENSATED}, or {@code null} if none. */
    private final Property<String> genDpd     = bound(null);
    /** Path to the DUAL-tone predistortion {@code .dpd} used by
     *  {@code DUAL_TONE_COMPENSATED}, kept separate so the two compensated forms
     *  each remember their own file, or {@code null} if none. */
    private final Property<String> genDpdDual = bound(null);
    /** Folder remembered for the generator's "browse for .dpd" dialog. */
    private final Property<String> genDpdFolder = bound(null);
    /** DAC predistortion wizard: FFT averages per round.  Persisted so the
     *  user's choice survives reopening the wizard and restarting the app. */
    private final Property<Integer> predistortionAverages    = bound(64);
    /** DAC predistortion wizard: target distortion to stop at (%); 0 = run to stall. */
    private final Property<Double>  predistortionTargetPct   = bound(0.000001);
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
    /** Folder remembered for the generator's "Save WAV" dialog, separate from {@link #genDpdFolder}. */
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
    private final Property<Boolean> fftDetectTimeDiscontinuity = bound(true);
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
    private final Property<Double>  fftManualFundVrms    = bound(1.0);
    /** True = the manual-fundamental field displays in dBV; persisted. */
    private final Property<Boolean> fftManualFundDbvDisplay = bound(false);
    /** Unit the manual-fundamental-amplitude field renders in: {@code mV}, {@code V}, or {@code dBV}. */
    private final Property<Boolean> fftManualFundEnabled = bound(false);
    /** Active analysis channel (L or R) — only one channel shown at a time. */
    private final Property<Channel> fftChannel = bound(Channel.L);
    /** {@code MagnitudeUnit} enum name: {@code V}, {@code V_SQRT_HZ}, {@code DBV}, {@code DBFS}. */
    private final Property<MagnitudeUnit> fftMagUnit = bound(MagnitudeUnit.DBV);
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

    /** FFT-pane Load-calibration rows, in display order.  Entry 0 is the
     *  former row-0 "primary" calibration; entries 1..N are the former
     *  extras.  Each entry carries its own path + Active / With-noise
     *  toggles; the pane renders one row per entry and the FFT view applies
     *  every active entry's correction in list order. */
    private final List<CalibrationEntry> fftCalibrations = new ArrayList<>();
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
    /** Per-filter-type filter parameters — the SINGLE source of truth for the
     *  FreqResp filter overlay's Mode/ripple/atten/edge/order/Q scalars.  The
     *  tab control binds its widgets directly to {@code map[currentType]}: a
     *  field edit writes {@link #putFreqRespFilterParams} (write-through +
     *  save), a Filter-type change loads {@link #getFreqRespFilterParams} for
     *  the new type.  A missing entry falls back to
     *  {@link FreqRespFilterTypeParams#fromType} defaults. */
    private final Map<FilterType, FreqRespFilterTypeParams> freqRespFilterParamsByType = new EnumMap<>(FilterType.class);

    /** Per-CARD calibration profiles, backend-independent — the ONE store of
     *  card profiles, persisted to a separate {@code devices.yaml} beside
     *  {@code preferences.yaml} (see {@link #DEVICES_FILE}) so it is editable by
     *  the user and from the UI.  A profile IS the physical soundcard;
     *  per-backend device-name strings are only aliases on it, and a well-known
     *  card also carries {@code match} recognition patterns.  Calibration
     *  full-scale values live in each direction's range table.  The legacy global
     *  {@code adcFsVoltageRms} / {@code dacFsVoltageAmpl} scalars stay the read
     *  fallback for a card with no profile entry; a new calibration write
     *  auto-creates a profile via {@link #storeAdcCalibration} /
     *  {@link #storeDacCalibration}.  Mutated via {@code synchronized}
     *  {@link #putAudioDeviceProfile} / {@link #removeAudioDeviceProfile} + a
     *  direct {@link #saveDevices()} (preset style), so a structural change can't
     *  race a save iterating this list. */
    private final List<AudioDeviceProfile> audioDevices = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Frequency Response pane — sweep settings, view state, RIAA + calibration
    // -------------------------------------------------------------------------

    /** Sweep start frequency in Hz.  Default 1 Hz (bottom of the usable band). */
    private final Property<Double>  freqRespStartHz          = bound(1.0);
    /** Sweep stop  frequency in Hz.  Sentinel {@code 0} = "unset" — resolved to
     *  the current device Nyquist ({@code rate/2}) at first use (see
     *  {@link #seedRateDependentFreqRespDefaults()}).  A saved value overrides. */
    private final Property<Double>  freqRespStopHz           = bound(FREQRESP_RATE_DEFAULT_SENTINEL);
    /** Generator drive amplitude at the DAC, V RMS. */
    private final Property<Double>  freqRespAmplitudeVrms    = bound(1.0);
    /** True = the sweep amplitude field displays in dBV; persisted. */
    private final Property<Boolean> freqRespAmplitudeDbvDisplay = bound(false);
    /** Number of log-spaced output frequency points the deconvolution emits.
     *  Sentinel {@code 0} = "unset" — resolved to the FS/2 point count
     *  ({@code rate/2}) at first use (see
     *  {@link #seedRateDependentFreqRespDefaults()}).  A saved value overrides. */
    private final Property<Integer> freqRespSweepPoints      = bound(FREQRESP_RATE_DEFAULT_SENTINEL_INT);
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
    private final Property<Integer> freqRespFftSize          = bound(4194304);
    /** TPDF dither bits applied to the generator before quantisation;
     *  0 disables.  Same convention as the generator pane. */
    private final Property<Integer> freqRespDitherBits       = bound(0);
    /** Silent lead-in prepended to the sweep, in seconds.  Lets the DAC →
     *  ADC chain settle before the first sweep sample lands. */
    private final Property<Double>  freqRespLeadInSec        = bound(0.05);
    /** Which output lane(s) the FreqResp sweep drives — the encoder gate
     *  ({@code BOTH} by default).  A one-sided sweep skips the un-driven capture
     *  channel's deconvolution. */
    private final Property<OutputChannels> freqRespOutputChannels = bound(OutputChannels.BOTH);

    /** Tune-notch wizard sweep start frequency in Hz; persisted independently
     *  of the main FreqResp pane so the dialog remembers its own fields. */
    private final Property<Double>  tuneNotchStartHz         = bound(900.0);
    /** Tune-notch wizard sweep stop frequency in Hz. */
    private final Property<Double>  tuneNotchStopHz          = bound(1100.0);
    /** Tune-notch wizard generator drive amplitude at the DAC, V RMS. */
    private final Property<Double>  tuneNotchAmplitudeVrms   = bound(1.0);
    /** Tune-notch wizard target (desired) notch frequency in Hz — the dashed
     *  marker the live readout is tuned onto. */
    private final Property<Double>  tuneNotchTargetHz        = bound(1000.0);
    /** Which output lane(s) the tune-notch sweep drives — the encoder gate
     *  ({@code BOTH} by default).  Kept independent of the main FreqResp pane, like
     *  the other {@code tuneNotch*} dialog fields (the tune-notch capture channel is
     *  unaffected — only its output lane is gated). */
    private final Property<OutputChannels> tuneNotchOutputChannels = bound(OutputChannels.BOTH);

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
    /** Magnitude / phase / RIAA / compare trace line width in pixels (0.5 steps). */
    private final Property<Double>  freqRespLineWidth       = bound(2.0);
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

    /** Show the ideal filter reference curve overlaid on the trace.  Like
     *  {@link #freqRespShowRiaa} this is deliberately NOT persisted top-level
     *  (always starts unchecked on a fresh session) but IS captured in a
     *  {@link FreqRespPreset}. */
    private final Property<Boolean> freqRespShowFilter        = bound(false);
    /** Filter compare (diff) mode: show measured − filter subtraction trace,
     *  like {@link #freqRespCompareMode} but against the ideal filter curve.
     *  Mutually exclusive with {@link #freqRespCompareMode} (tab-side). */
    private final Property<Boolean> freqRespFilterCompare     = bound(false);
    /** Passband shape of the ideal filter overlay (combo order = enum order). */
    private final Property<FilterType> freqRespFilterType     = bound(FilterType.LOW_PASS);
    /** Approximation family used to synthesise the filter magnitude response. */
    private final Property<FilterResponse> freqRespFilterResponse = bound(FilterResponse.BUTTERWORTH);

    /** Unevenness table mode: {@code OFF} = no readout computed or drawn,
     *  {@code LEVEL} = row 1 (±dB given → report the frequency span within
     *  that tolerance), {@code RANGE} = row 2 (frequency range given → report
     *  the ±dB spread over that range). */
    private final Property<UnevenMode> freqRespUnevenMode     = bound(UnevenMode.OFF);
    /** Treat the measured curve as a notch in LEVEL mode (explicit — no automatic shape detection). */
    private final Property<Boolean> freqRespUnevenNotch       = bound(false);
    /** Row-1 unevenness tolerance in dB.  Clamped to [0.001, 20] on load. */
    private final Property<Double>  freqRespUnevenDb          = bound(3.0);
    /** Row-2 start frequency in Hz for the range-given unevenness mode. */
    private final Property<Double>  freqRespUnevenStartHz     = bound(20.0);
    /** Row-2 stop frequency in Hz for the range-given unevenness mode. */
    private final Property<Double>  freqRespUnevenStopHz      = bound(20_000.0);

    /** When {@code true}, fresh measurements get divided by the loaded
     *  calibration before being shown.  No effect if no calibration is
     *  loaded.  Defaults on so a loaded calibration takes effect by
     *  default. */
    private final Property<Boolean> freqRespApplyCalibration = bound(true);
    /** FreqResp-pane Load-calibration rows, in display order.  Entry 0 is the
     *  former row-0 "primary" calibration; entries 1..N are the former extras.
     *  The FreqResp pane ignores each entry's With-noise toggle. */
    private final List<CalibrationEntry> freqRespCalibrations = new ArrayList<>();

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

    /** Overrides the {@code devices.yaml} location for a test that seeds /
     *  migrates / round-trips the store against a temp dir; {@code null} on the
     *  live singleton, where {@link #devicesPath()} falls back to
     *  {@code AppPaths}.  A detached copy never persists (it is gated on
     *  {@link #detached} exactly like the prefs save), so this stays null there. */
    @Setter
    private Path devicesPathOverride;

    /** The bundled-seed {@code contentVersion} this user store was last merged
     *  against, read from the store file's top-level {@code contentVersion} key
     *  (absent on a legacy store, or one from before this feature → {@code 0}, so
     *  every such store merges once and then records the current version).  The
     *  once-per-content-version seed merge runs when the live bundle's
     *  {@link SeedBundle#contentVersion} is strictly greater; it is
     *  refreshed and persisted after a merge.  NEVER present in the bundled seed
     *  resource — it is a user-store bookkeeping field only. */
    private int recordedContentVersion;

    /** The {@code formatVersion} the loaded store file carried, read in
     *  {@link #readDevicesFile} ({@code 0} when absent).  Only a FALLBACK for the
     *  writer: a written store copies the bundled seed's {@code formatVersion}
     *  (the single source of truth), and reaches for this loaded value — then 1 as
     *  the last resort — only when the seed is unreadable at write time. */
    private int storeFormatVersion;

    /** Overrides the bundled {@code devices.yaml} seed with a file on disk, so a
     *  merge test can drive {@link #mergeSeed} against a custom bundle (new card,
     *  new range) instead of the real bundled content.  {@code null} on the live
     *  singleton, where the seed is read from the classpath resource
     *  ({@link #DEVICES_SEED_RESOURCE}).  Mirrors {@link #devicesPathOverride}. */
    @Setter
    private Path seedPathOverride;

    private Preferences() {
        this.detached = false;
        // Bidi-bound GUI edits write these properties directly (no setter), so
        // the bin-bandwidth cache listens on the properties themselves.
        fftLength.addListener(v -> recomputeBinBw());
        backend.addListener(v -> recomputeBinBw());
        load();
        // The per-card profile store lives in its own devices.yaml: seed it from
        // the bundle on first run, then load it.
        loadDevices();
        // Covers the no-file / partial-file case AND the per-backend sample
        // rate, which is a plain POJO field the listeners can't observe.
        recomputeBinBw();
        // Resolve the rate-dependent FreqResp defaults (stop = Nyquist,
        // points = FS/2) on a fresh install where load() left the sentinels.
        seedRateDependentFreqRespDefaults();
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
        recomputeBinBw();
    }

    /** Resolves the rate-dependent FreqResp defaults left as sentinels after
     *  {@link #load()}: on a fresh install (no saved value) the stop frequency
     *  becomes the current device Nyquist ({@code rate/2}) and the sweep-points
     *  count becomes the FS/2 point count ({@code rate/2}).  A user with a
     *  stored value never hits the sentinel, so their choice is preserved. */
    private void seedRateDependentFreqRespDefaults() {
        int rate = current().getInputSampleRate();
        if (rate <= 0) return;
        double nyquist = rate / 2.0;
        if (freqRespStopHz.get() == FREQRESP_RATE_DEFAULT_SENTINEL) {
            freqRespStopHz.set(nyquist);
        }
        if (freqRespSweepPoints.get() == FREQRESP_RATE_DEFAULT_SENTINEL_INT) {
            freqRespSweepPoints.set((int) nyquist);
        }
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
    public Preferences copyForDialog() {
        Preferences c = new Preferences(true);

        c.tabOrientation.set(tabOrientation.get());
        c.smallIconsInMainTab.set(smallIconsInMainTab.get());
        c.showTipsAtStartup.set(showTipsAtStartup.get());
        c.useGpuAcceleration.set(useGpuAcceleration.get());
        c.uiFontNormal.set(uiFontNormal.get());
        c.uiFontBold.set(uiFontBold.get());
        c.fftStrongToneRelDb.set(fftStrongToneRelDb.get());
        c.freqRespNyquistFraction.set(freqRespNyquistFraction.get());
        c.freqRespFreqMaxHz.set(freqRespFreqMaxHz.get());
        c.freqRespFreqMinHz.set(freqRespFreqMinHz.get());
        c.freqRespCompareSmoothWindow.set(freqRespCompareSmoothWindow.get());
        c.freqRespNotchEnabled.set(freqRespNotchEnabled.get());
        c.freqRespNotchBaseHz.set(freqRespNotchBaseHz.get());

        c.oscMeasurementAverageSeconds.set(oscMeasurementAverageSeconds.get());
        c.oscPersistenceMode.set(oscPersistenceMode.get());
        c.oscPersistenceManualSeconds.set(oscPersistenceManualSeconds.get());
        c.oscLineWidth.set(oscLineWidth.get());
        c.oscDotDiameter.set(oscDotDiameter.get());
        c.fftLineWidth.set(fftLineWidth.get());
        c.freqRespLineWidth.set(freqRespLineWidth.get());
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

        // used for isolated FreqRespView
        c.setTuneNotchStartHz(tuneNotchStartHz.get());
        c.setTuneNotchStopHz(tuneNotchStopHz.get());
        c.setTuneNotchAmplitudeVrms(tuneNotchAmplitudeVrms.get());
        c.setTuneNotchTargetHz(tuneNotchTargetHz.get());
        c.setTuneNotchOutputChannels(tuneNotchOutputChannels.get());

        c.backend.set(backend.get());
        for (AudioBackendType t : AudioBackendType.values()) {
            c.prefsFor(t).copyFrom(prefsFor(t));
        }
        // Seed the global full-scale scalars so the dialog's "New card…" seeds a
        // range row from the CURRENT calibration (not the bound() defaults) —
        // setters keep the detached copy's dbvOffsetDb consistent, and requestSave
        // is inert while detached.
        c.setAdcFsVoltageRms(adcFsVoltageRms.get());
        c.setAdcFsVoltageRmsRight(adcFsVoltageRmsRight.get());
        c.setDacFsVoltageAmpl(dacFsVoltageAmpl.get());
        c.setDacFsVoltageAmplRight(dacFsVoltageAmplRight.get());
        // Deep-copy the per-card profiles so the dialog edits them freely.
        synchronized (audioDevices) {
            for (AudioDeviceProfile p : audioDevices) c.audioDevices.add(copyProfile(p));
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
    public void applyFromDialog(Preferences edit) {
        // Backend selection first: the Nyquist clamp below reads current()'s
        // input sample rate, which must reflect the just-chosen backend.
        setBackend(edit.backend.get());
        for (AudioBackendType t : AudioBackendType.values()) {
            prefsFor(t).copyFrom(edit.prefsFor(t));
        }
        // The copyFrom above may have changed the current backend's input
        // sample rate — a POJO write the property listeners can't observe.
        recomputeBinBw();

        // Commit the edited profile list back BEFORE the scalar setters, so a
        // subsequent resolution (or a re-open of the dialog) sees fresh profiles.
        // Persist the card store to devices.yaml (its own file — the prefs
        // save() below no longer carries the profiles).
        synchronized (audioDevices) {
            audioDevices.clear();
            for (AudioDeviceProfile p : edit.audioDevices) audioDevices.add(copyProfile(p));
        }
        saveDevices();

        setTabOrientation(edit.tabOrientation.get());
        setSmallIconsInMainTab(edit.smallIconsInMainTab.get());
        setShowTipsAtStartup(edit.showTipsAtStartup.get());
        setUseGpuAcceleration(edit.useGpuAcceleration.get());
        setUiFontNormal(edit.uiFontNormal.get());
        setUiFontBold(edit.uiFontBold.get());
        setFftStrongToneRelDb(edit.fftStrongToneRelDb.get());
        setFreqRespCompareSmoothWindow(edit.freqRespCompareSmoothWindow.get());
        setFreqRespNotchEnabled(edit.freqRespNotchEnabled.get());
        setFreqRespNotchBaseHz(edit.freqRespNotchBaseHz.get());

        setOscMeasurementAverageSeconds(edit.oscMeasurementAverageSeconds.get());
        setOscPersistenceMode(edit.oscPersistenceMode.get());
        setOscPersistenceManualSeconds(edit.oscPersistenceManualSeconds.get());
        setOscLineWidth(edit.oscLineWidth.get());
        setOscDotDiameter(edit.oscDotDiameter.get());
        setFftLineWidth(edit.fftLineWidth.get());
        setFreqRespLineWidth(edit.freqRespLineWidth.get());
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

    /** Writes the current preferences to {@link #PREFS_FILE} in the working dir.
     *  No-op in {@link #isTransientMode() transient mode} (CLI runs), so values
     *  injected for one headless run never overwrite the user's saved YAML. */
    public synchronized void save() {
        if (transientMode) return;
        Map<String, Object> root = toMap();
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        // Write to a sibling temp file and move it into place atomically, so
        // a JVM exit mid-write (the save daemon is killed hard at shutdown)
        // can never leave a truncated preferences.yaml behind.
        Path target = prefsPath();
        Path tmp    = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp)) {
                new Yaml(opts).dump(root, w);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
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

    /** Subscribes a calibration entry's two observable toggles to the
     *  debounced auto-save, so flipping Active / With-noise persists exactly
     *  like a scalar bound property.  The {@code path} is NOT observable —
     *  the pane saves explicitly after a browse / clear. */
    private void trackCalibration(CalibrationEntry entry) {
        entry.active().addListener(v -> requestSave());
        entry.withNoise().addListener(v -> requestSave());
    }

    /** Live FFT calibration list (entry 0 = former primary, 1..N = extras).
     *  The pane iterates this to build / rebuild its rows. */
    public List<CalibrationEntry> getFftCalibrations() {
        return fftCalibrations;
    }

    /** Appends an FFT calibration entry, wires its toggles to auto-save, and
     *  requests a save (covers the structural change).  Synchronized (like
     *  the preset mutators) so the structural change can't race the save
     *  daemon iterating the list in {@link #toMap()}. */
    public synchronized void addFftCalibration(CalibrationEntry entry) {
        fftCalibrations.add(entry);
        trackCalibration(entry);
        requestSave();
    }

    /** Removes an FFT calibration entry and requests a save. */
    public synchronized void removeFftCalibration(CalibrationEntry entry) {
        if (fftCalibrations.remove(entry)) {
            requestSave();
        }
    }

    /** Live FreqResp calibration list (entry 0 = former primary, 1..N = extras). */
    public List<CalibrationEntry> getFreqRespCalibrations() {
        return freqRespCalibrations;
    }

    /** Appends a FreqResp calibration entry, wires its toggles to auto-save,
     *  and requests a save. */
    public synchronized void addFreqRespCalibration(CalibrationEntry entry) {
        freqRespCalibrations.add(entry);
        trackCalibration(entry);
        requestSave();
    }

    /** Removes a FreqResp calibration entry and requests a save. */
    public synchronized void removeFreqRespCalibration(CalibrationEntry entry) {
        if (freqRespCalibrations.remove(entry)) {
            requestSave();
        }
    }

    /** Sets the path of the FreqResp primary (entry 0) calibration, creating
     *  entry 0 when the list is empty, then requests a save.  Used by the
     *  FreqResp wizard's Apply step to record the just-applied file without
     *  flipping the row's Active flag. */
    public synchronized void setFreqRespPrimaryCalibrationPath(String path) {
        if (freqRespCalibrations.isEmpty()) {
            addFreqRespCalibration(new CalibrationEntry());
        }
        freqRespCalibrations.get(0).setPath(path);
        requestSave();
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

    public boolean isShowTipsAtStartup() { return showTipsAtStartup.get(); }
    public void setShowTipsAtStartup(boolean v) { showTipsAtStartup.set(v); }
    public Property<Boolean> showTipsAtStartupProperty() { return showTipsAtStartup; }

    public boolean isUseGpuAcceleration() { return useGpuAcceleration.get(); }
    public void setUseGpuAcceleration(boolean v) { useGpuAcceleration.set(v); }
    public Property<Boolean> useGpuAccelerationProperty() { return useGpuAcceleration; }

    public TabOrientation getTabOrientation()  { return tabOrientation.get(); }
    public void setTabOrientation(TabOrientation v) { tabOrientation.set(v); }
    public Property<TabOrientation> tabOrientationProperty() { return tabOrientation; }
    /** Per-OS default UI font as a {@code "family|size|style"} string: Consolas 9 on
     *  Windows, Menlo 11 on macOS, DejaVu Sans Mono 11 elsewhere (Linux) — each
     *  platform's standard monospace face, present out of the box.  {@code sizeBump}
     *  enlarges the channel-button font above the base size. */
    private String defaultUiFont(String style, int sizeBump) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String family;
        int size;
        if (os.contains("mac")) {
            family = "Menlo";            size = 11;
        } else if (os.contains("win")) {
            family = "Consolas";         size = 9;
        } else {
            family = "DejaVu Sans Mono"; size = 11;
        }
        return family + '|' + (size + sizeBump) + '|' + style;
    }

    public String getUiFontNormal()            { return uiFontNormal.get(); }
    public void setUiFontNormal(String v)      { uiFontNormal.set(v); }
    public Property<String> uiFontNormalProperty() { return uiFontNormal; }
    public String getUiFontBold()              { return uiFontBold.get(); }
    public void setUiFontBold(String v)        { uiFontBold.set(v); }
    public Property<String> uiFontBoldProperty() { return uiFontBold; }
    public String getUiFontChannel()           { return uiFontChannel.get(); }
    public void setUiFontChannel(String v)     { uiFontChannel.set(v); }
    public Property<String> uiFontChannelProperty() { return uiFontChannel; }

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

    public boolean isFftDetectTimeDiscontinuity()        { return fftDetectTimeDiscontinuity.get(); }
    public void setFftDetectTimeDiscontinuity(boolean v) { fftDetectTimeDiscontinuity.set(v); }
    public Property<Boolean> fftDetectTimeDiscontinuityProperty() { return fftDetectTimeDiscontinuity; }

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
    public boolean isFftManualFundDbvDisplay() { return fftManualFundDbvDisplay.get(); }
    public void setFftManualFundDbvDisplay(boolean v) { fftManualFundDbvDisplay.set(v); }
    public Property<Boolean> fftManualFundDbvDisplayProperty() { return fftManualFundDbvDisplay; }

    public boolean isFftManualFundEnabled()    { return fftManualFundEnabled.get(); }
    public void setFftManualFundEnabled(boolean v) { fftManualFundEnabled.set(v); }
    public Property<Boolean> fftManualFundEnabledProperty() { return fftManualFundEnabled; }


    public Channel getFftChannel()             { return fftChannel.get(); }
    public void setFftChannel(Channel v)       { fftChannel.set(v); }
    public Property<Channel> fftChannelProperty() { return fftChannel; }

    public MagnitudeUnit getFftMagUnit()    { return fftMagUnit.get(); }
    public void setFftMagUnit(MagnitudeUnit v) { fftMagUnit.set(v); }
    public Property<MagnitudeUnit> fftMagUnitProperty() { return fftMagUnit; }

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

    public double getFftLineWidth()            { return fftLineWidth.get(); }
    public void setFftLineWidth(double v)      { fftLineWidth.set(v); }
    public Property<Double> fftLineWidthProperty() { return fftLineWidth; }

    public double getFreqRespLineWidth()       { return freqRespLineWidth.get(); }
    public void setFreqRespLineWidth(double v) { freqRespLineWidth.set(v); }
    public Property<Double> freqRespLineWidthProperty() { return freqRespLineWidth; }

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

    public TriggerType getOscTriggerType()     { return oscTriggerType.get(); }
    public void setOscTriggerType(TriggerType v) { oscTriggerType.set(v); }
    public Property<TriggerType> oscTriggerTypeProperty() { return oscTriggerType; }

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

    public boolean isOscLeftResidualEnabled() { return oscLeftResidualEnabled.get(); }
    public void setOscLeftResidualEnabled(boolean v) { oscLeftResidualEnabled.set(v); }
    public Property<Boolean> oscLeftResidualEnabledProperty() { return oscLeftResidualEnabled; }

    public boolean isOscRightResidualEnabled() { return oscRightResidualEnabled.get(); }
    public void setOscRightResidualEnabled(boolean v) { oscRightResidualEnabled.set(v); }
    public Property<Boolean> oscRightResidualEnabledProperty() { return oscRightResidualEnabled; }

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

    public PersistenceMode getOscPersistenceMode() { return oscPersistenceMode.get(); }
    public void setOscPersistenceMode(PersistenceMode v) { oscPersistenceMode.set(v); }
    public Property<PersistenceMode> oscPersistenceModeProperty() { return oscPersistenceMode; }

    public double getOscPersistenceManualSeconds() { return oscPersistenceManualSeconds.get(); }
    public void setOscPersistenceManualSeconds(double v) { oscPersistenceManualSeconds.set(v); }
    public Property<Double> oscPersistenceManualSecondsProperty() { return oscPersistenceManualSeconds; }

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

    public String getScreenshotCommentFont()       { return screenshotCommentFont.get(); }
    public void setScreenshotCommentFont(String v) { screenshotCommentFont.set(v); }
    public Property<String> screenshotCommentFontProperty() { return screenshotCommentFont; }

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
    public void setAdcFsVoltageRms(double v)   {
        if (!(v > 0.0)) {
            if (log.isWarnEnabled()) {
                log.warn("Rejecting invalid ADC full-scale {} Vrms — keeping {} Vrms", v, adcFsVoltageRms.get());
            }
            return;
        }
        adcFsVoltageRms.set(v);
        dbvOffsetDb = dbvOffsetFor(v);
    }
    public Property<Double> adcFsVoltageRmsProperty() { return adcFsVoltageRms; }

    public double getAdcFsVoltageRmsRight()       { return adcFsVoltageRmsRight.get(); }
    public void setAdcFsVoltageRmsRight(double v) {
        if (!(v > 0.0)) {
            if (log.isWarnEnabled()) {
                log.warn("Rejecting invalid RIGHT ADC full-scale {} Vrms — keeping {} Vrms", v, adcFsVoltageRmsRight.get());
            }
            return;
        }
        adcFsVoltageRmsRight.set(v);
        dbvOffsetDbRight = dbvOffsetFor(v);
    }
    public Property<Double> adcFsVoltageRmsRightProperty() { return adcFsVoltageRmsRight; }

    /** The ADC full-scale RMS voltage of {@code ch}: LEFT → the {@link #adcFsVoltageRms} scalar
     *  (also the LINKED / legacy value), RIGHT → {@link #adcFsVoltageRmsRight}. */
    public double getAdcFsVoltageRms(Channel ch) {
        return ch == Channel.R ? adcFsVoltageRmsRight.get() : adcFsVoltageRms.get();
    }

    /** The ±full-scale PEAK volts of {@code ch} (= {@code fs(ch) · √2}) — the ±1.0 → volts scale
     *  every scope / measurement site derives inline today from the single scalar. */
    public double getAdcPeakVolts(Channel ch) {
        return getAdcFsVoltageRms(ch) * Math.sqrt(2.0);
    }

    /** The cached dBV↔dBFS offset of {@code ch}: LEFT → {@link #dbvOffsetDb} (also the LINKED /
     *  legacy value), RIGHT → {@link #dbvOffsetDbRight}. */
    public double getDbvOffsetDb(Channel ch) {
        return ch == Channel.R ? dbvOffsetDbRight : dbvOffsetDb;
    }

    /** The dBV↔dBFS offset for a full-scale RMS voltage (= {@code 20·log10(fsVrms)}).  Shared by
     *  the left / right setters and by {@link #fromMap}.  A non-positive (or NaN) full-scale would
     *  put {@code -Infinity}/NaN into every dBV conversion, so it falls back to 0 dB (dBV ≡ dBFS). */
    private double dbvOffsetFor(double fsVrms) {
        if (!(fsVrms > 0.0)) {
            if (log.isWarnEnabled()) {
                log.warn("Invalid ADC full-scale {} Vrms — falling back to 0 dB dBV offset", fsVrms);
            }
            return 0.0;
        }
        return 20.0 * Math.log10(fsVrms);
    }

    /** Recomputes the cached {@link #binBwSqrt} from the live capture config.  Hooked to
     *  the {@code fftLength} / {@code backend} property listeners and called after
     *  {@link #load()} / {@link #applyFromDialog} — see the field doc for why both are
     *  needed.  Falls back to 1 (plain V) on a not-yet-valid config. */
    private void recomputeBinBw() {
        int rate = current().getInputSampleRate();
        int len  = getFftLength();
        binBwSqrt = (rate > 0 && len > 0) ? Math.sqrt((double) rate / len) : 1.0;
    }

    /** Live-spectrum variant of {@link #convertFromDbFs(double, MagnitudeUnit, Double)}:
     *  the V/√Hz scale comes from the cached live capture config. */
    public double convertFromDbFs(double dbFs, MagnitudeUnit unit) {
        return convertFromDbFs(dbFs, unit, (Double) null);
    }

    /**
     * Converts an analyser dBFS magnitude into {@code unit} for display.  Pure
     * cached-constant math, safe to call per bin in paint loops: dBV is a constant
     * offset from dBFS ({@link #dbvOffsetDb}), V is the same value read linearly
     * (0&nbsp;dBV = 1&nbsp;V), and V/√Hz additionally divides by √(bin bandwidth).
     *
     * @param binBwSqrt √(bin bandwidth in Hz) of the spectrum being converted —
     *                  carried by file-loaded results ({@code FftResult#binBwSqrt});
     *                  {@code null} uses the cached live config, which is bidi-bound
     *                  and restarts the FFT on change, so it always matches the live
     *                  measurement
     */
    public double convertFromDbFs(double dbFs, MagnitudeUnit unit, Double binBwSqrt) {
        return convertFromDbFs(dbFs, unit, binBwSqrt, Channel.L);
    }

    /** Live-spectrum, channel-aware variant of {@link #convertFromDbFs(double, MagnitudeUnit, Double, Channel)}:
     *  the V/√Hz scale comes from the cached live capture config. */
    public double convertFromDbFs(double dbFs, MagnitudeUnit unit, Channel ch) {
        return convertFromDbFs(dbFs, unit, null, ch);
    }

    /** Channel-aware {@link #convertFromDbFs(double, MagnitudeUnit, Double)}: the dBV offset
     *  follows {@code ch} (LEFT / LINKED / legacy → {@link #dbvOffsetDb}, RIGHT →
     *  {@link #dbvOffsetDbRight}), so the FFT absolute-dBV axis tracks the analysed channel. */
    public double convertFromDbFs(double dbFs, MagnitudeUnit unit, Double binBwSqrt, Channel ch) {
        double off = getDbvOffsetDb(ch);
        switch (unit) {
            case DBFS: return dbFs;
            case DBV:  return dbFs + off;
            case V:         return Math.pow(10.0, (dbFs + off) / 20.0);
            case V_SQRT_HZ: return Math.pow(10.0, (dbFs + off) / 20.0)
                    / (binBwSqrt != null ? binBwSqrt : this.binBwSqrt);
            default: return dbFs;
        }
    }

    public double getDacFsVoltageAmpl()         { return dacFsVoltageAmpl.get(); }
    public void setDacFsVoltageAmpl(double v)   {
        if (!(v > 0.0)) {
            if (log.isWarnEnabled()) {
                log.warn("Rejecting invalid DAC full-scale {} V (ampl) — keeping {} V", v, dacFsVoltageAmpl.get());
            }
            return;
        }
        dacFsVoltageAmpl.set(v);
    }
    public Property<Double> dacFsVoltageAmplProperty() { return dacFsVoltageAmpl; }

    public double getDacFsVoltageAmplRight()       { return dacFsVoltageAmplRight.get(); }
    public void setDacFsVoltageAmplRight(double v) {
        if (!(v > 0.0)) {
            if (log.isWarnEnabled()) {
                log.warn("Rejecting invalid RIGHT DAC full-scale {} V (ampl) — keeping {} V", v, dacFsVoltageAmplRight.get());
            }
            return;
        }
        dacFsVoltageAmplRight.set(v);
    }
    public Property<Double> dacFsVoltageAmplRightProperty() { return dacFsVoltageAmplRight; }

    /** The DAC full-scale PEAK amplitude of {@code ch}: LEFT → the {@link #dacFsVoltageAmpl}
     *  scalar (also the MONO/LINKED-mirror / legacy value), RIGHT → {@link #dacFsVoltageAmplRight}. */
    public double getDacFsVoltageAmpl(Channel ch) {
        return ch == Channel.R ? dacFsVoltageAmplRight.get() : dacFsVoltageAmpl.get();
    }

    /** The per-lane RIGHT output scale — {@code fsLeft/fsRight} so a card with
     *  distinct DAC full-scales emits the same physical level on both lanes;
     *  {@code 1.0} when the full-scales are equal or the card is mono (both
     *  scalars equal).  A driver's mono amplitude is computed against the LEFT
     *  full-scale, so the left lane always scales by 1.0.  Shared by every
     *  playback driver (generator, frequency-response sweep, tune-notch). */
    public double dacRightLaneScale() {
        double leftFs  = dacFsVoltageAmpl.get();
        double rightFs = dacFsVoltageAmplRight.get();
        return rightFs > 0.0 ? leftFs / rightFs : 1.0;
    }

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
    public boolean isGenAmplitudeDbvDisplay() { return genAmplitudeDbvDisplay.get(); }
    public void setGenAmplitudeDbvDisplay(boolean v) { genAmplitudeDbvDisplay.set(v); }


    public double getGenDitherBits()           { return genDitherBits.get(); }
    public void setGenDitherBits(double v)     { genDitherBits.set(v); }
    public Property<Double> genDitherBitsProperty() { return genDitherBits; }
    public boolean isGenDitherDbvDisplay()     { return genDitherDbvDisplay.get(); }
    public void setGenDitherDbvDisplay(boolean v) { genDitherDbvDisplay.set(v); }

    public OutputChannels getGenOutputChannels()       { return genOutputChannels.get(); }
    public void setGenOutputChannels(OutputChannels v) { genOutputChannels.set(v); }
    public Property<OutputChannels> genOutputChannelsProperty() { return genOutputChannels; }

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

    public String getGenDpd()       { return genDpd.get(); }
    public void setGenDpd(String v) { genDpd.set(v); }
    public Property<String> genDpdProperty() { return genDpd; }

    public String getGenDpdDual()       { return genDpdDual.get(); }
    public void setGenDpdDual(String v) { genDpdDual.set(v); }
    public Property<String> genDpdDualProperty() { return genDpdDual; }

    /** The {@code .dpd} for the given form: the dual-tone file for a dual-tone
     *  form, the single-tone file otherwise. */
    public String getGenDpd(GenSignalForm form) {
        return form.isDualTone() ? genDpdDual.get() : genDpd.get();
    }
    /** Stores {@code path} under the {@code .dpd} slot matching {@code form}. */
    public void setGenDpd(GenSignalForm form, String path) {
        if (form.isDualTone()) genDpdDual.set(path);
        else                   genDpd.set(path);
    }

    public String getGenDpdFolder()    { return genDpdFolder.get(); }
    public void setGenDpdFolder(String v) { genDpdFolder.set(v); }
    public Property<String> genDpdFolderProperty() { return genDpdFolder; }

    public int    getPredistortionAverages()           { return predistortionAverages.get(); }
    public void   setPredistortionAverages(int v)       { predistortionAverages.set(v); }
    public double getPredistortionTargetPct()          { return predistortionTargetPct.get(); }
    public void   setPredistortionTargetPct(double v)   { predistortionTargetPct.set(v); }

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
    public boolean isFreqRespAmplitudeDbvDisplay() { return freqRespAmplitudeDbvDisplay.get(); }
    public void setFreqRespAmplitudeDbvDisplay(boolean v) { freqRespAmplitudeDbvDisplay.set(v); }

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

    public OutputChannels getFreqRespOutputChannels()       { return freqRespOutputChannels.get(); }
    public void setFreqRespOutputChannels(OutputChannels v) { freqRespOutputChannels.set(v); }
    public Property<OutputChannels> freqRespOutputChannelsProperty() { return freqRespOutputChannels; }

    public double getTuneNotchStartHz()        { return tuneNotchStartHz.get(); }
    public void setTuneNotchStartHz(double v)  { tuneNotchStartHz.set(v); }
    public Property<Double> tuneNotchStartHzProperty() { return tuneNotchStartHz; }

    public double getTuneNotchStopHz()         { return tuneNotchStopHz.get(); }
    public void setTuneNotchStopHz(double v)   { tuneNotchStopHz.set(v); }
    public Property<Double> tuneNotchStopHzProperty() { return tuneNotchStopHz; }

    public double getTuneNotchAmplitudeVrms()  { return tuneNotchAmplitudeVrms.get(); }
    public void setTuneNotchAmplitudeVrms(double v) { tuneNotchAmplitudeVrms.set(v); }
    public Property<Double> tuneNotchAmplitudeVrmsProperty() { return tuneNotchAmplitudeVrms; }

    public double getTuneNotchTargetHz()       { return tuneNotchTargetHz.get(); }
    public void setTuneNotchTargetHz(double v) { tuneNotchTargetHz.set(v); }
    public Property<Double> tuneNotchTargetHzProperty() { return tuneNotchTargetHz; }

    public OutputChannels getTuneNotchOutputChannels()       { return tuneNotchOutputChannels.get(); }
    public void setTuneNotchOutputChannels(OutputChannels v) { tuneNotchOutputChannels.set(v); }
    public Property<OutputChannels> tuneNotchOutputChannelsProperty() { return tuneNotchOutputChannels; }

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

    public boolean isFreqRespShowFilter()      { return freqRespShowFilter.get(); }
    public void setFreqRespShowFilter(boolean v) { freqRespShowFilter.set(v); }
    public Property<Boolean> freqRespShowFilterProperty() { return freqRespShowFilter; }

    public boolean isFreqRespFilterCompare()   { return freqRespFilterCompare.get(); }
    public void setFreqRespFilterCompare(boolean v) { freqRespFilterCompare.set(v); }
    public Property<Boolean> freqRespFilterCompareProperty() { return freqRespFilterCompare; }

    public FilterType getFreqRespFilterType()  { return freqRespFilterType.get(); }
    public void setFreqRespFilterType(FilterType v) { freqRespFilterType.set(v); }
    public Property<FilterType> freqRespFilterTypeProperty() { return freqRespFilterType; }

    public FilterResponse getFreqRespFilterResponse() { return freqRespFilterResponse.get(); }
    public void setFreqRespFilterResponse(FilterResponse v) { freqRespFilterResponse.set(v); }
    public Property<FilterResponse> freqRespFilterResponseProperty() { return freqRespFilterResponse; }

    public UnevenMode getFreqRespUnevenMode()  { return freqRespUnevenMode.get(); }
    public void setFreqRespUnevenMode(UnevenMode v) { freqRespUnevenMode.set(v); }
    public Property<UnevenMode> freqRespUnevenModeProperty() { return freqRespUnevenMode; }

    public boolean isFreqRespUnevenNotch()     { return freqRespUnevenNotch.get(); }
    public void setFreqRespUnevenNotch(boolean v) { freqRespUnevenNotch.set(v); }
    public Property<Boolean> freqRespUnevenNotchProperty() { return freqRespUnevenNotch; }

    public double getFreqRespUnevenDb()        { return freqRespUnevenDb.get(); }
    public void setFreqRespUnevenDb(double v)  { freqRespUnevenDb.set(v); }
    public Property<Double> freqRespUnevenDbProperty() { return freqRespUnevenDb; }

    public double getFreqRespUnevenStartHz()   { return freqRespUnevenStartHz.get(); }
    public void setFreqRespUnevenStartHz(double v) { freqRespUnevenStartHz.set(v); }
    public Property<Double> freqRespUnevenStartHzProperty() { return freqRespUnevenStartHz; }

    public double getFreqRespUnevenStopHz()    { return freqRespUnevenStopHz.get(); }
    public void setFreqRespUnevenStopHz(double v) { freqRespUnevenStopHz.set(v); }
    public Property<Double> freqRespUnevenStopHzProperty() { return freqRespUnevenStopHz; }

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
        return AppPaths.instance().file(PREFS_FILE);
    }

    private Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion",          FileVersions.PREFERENCES_YAML);
        root.put("backend",                backend.get().name());
        if (uiLanguage.get() != null) root.put("uiLanguage", uiLanguage.get());
        root.put("tabOrientation", tabOrientation.get().name());
        root.put("uiFontNormal",  uiFontNormal.get());
        root.put("uiFontBold",    uiFontBold.get());
        root.put("uiFontChannel", uiFontChannel.get());
        root.put("activeTabIndex", activeTabIndex.get());
        root.put("smallIconsInMainTab", smallIconsInMainTab.get());
        root.put("checkForUpdatesOnStartup",  checkForUpdatesOnStartup.get());
        root.put("includeBetaInUpdateChecks", includeBetaInUpdateChecks.get());
        root.put("showTipsAtStartup",         showTipsAtStartup.get());
        root.put("useGpuAcceleration",        useGpuAcceleration.get());
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
        root.put("oscTriggerType",         oscTriggerType.get().name());
        root.put("oscTriggerMode",         oscTriggerMode.get().name());
        root.put("oscTriggerHysteresisDiv",     oscTriggerHysteresisDiv.get());
        root.put("oscTriggerHysteresisEnabled", oscTriggerHysteresisEnabled.get());
        root.put("oscShowReconstructedBeat",    oscShowReconstructedBeat.get());
        root.put("oscLeftSincInterpEnabled",  oscLeftSincInterpEnabled.get());
        root.put("oscRightSincInterpEnabled", oscRightSincInterpEnabled.get());
        root.put("oscLeftResidualEnabled",  oscLeftResidualEnabled.get());
        root.put("oscRightResidualEnabled", oscRightResidualEnabled.get());
        root.put("oscLeftMainsSuppression",  oscLeftMainsSuppression.get().name());
        root.put("oscRightMainsSuppression", oscRightMainsSuppression.get().name());
        root.put("oscLeftLpf",  oscLeftLpf.get().name());
        root.put("oscRightLpf", oscRightLpf.get().name());
        root.put("oscLeftOffsetFrac",      oscLeftOffsetFrac.get());
        root.put("oscRightOffsetFrac",     oscRightOffsetFrac.get());
        root.put("oscTriggerLevelFrac",    oscTriggerLevelFrac.get());
        root.put("oscTriggerPositionFrac", oscTriggerPositionFrac.get());
        root.put("oscMeasurementAverageSeconds", oscMeasurementAverageSeconds.get());
        root.put("oscPersistenceMode",           oscPersistenceMode.get().name());
        root.put("oscPersistenceManualSeconds",  oscPersistenceManualSeconds.get());
        root.put("oscMeasurementChannel",        oscMeasurementChannel.get().name());
        root.put("oscShowStats",                 oscShowStats.get());
        root.put("oscShowMeasurementTable",      oscShowMeasurementTable.get());
        // DEPRECATED shared full-scale calibration — the FALLBACK for devices with no
        // card in devices.yaml (per-card calibration owns everything else).  Kept
        // read AND written for backwards compatibility, per the help's Preferences
        // chapter; scheduled for removal in the release AFTER the next one.
        root.put("adcFsVoltageRms", adcFsVoltageRms.get());
        root.put("dacFsVoltageRms", dacFsVoltageAmpl.get() / Constants.SQRT2);
        root.put("genSignalForm",                genSignalForm.get().name());
        root.put("genFrequencyHz",               genFrequencyHz.get());
        root.put("genDualToneFreq1Hz",           genDualToneFreq1Hz.get());
        root.put("genDualToneFreq2Hz",           genDualToneFreq2Hz.get());
        root.put("genDualToneSplitPct",          genDualToneSplitPct.get());
        root.put("genAmplitudeVrms",             genAmplitudeVrms.get());
        root.put("genAmplitudeDbvDisplay",       genAmplitudeDbvDisplay.get());
        root.put("genDitherBits",                genDitherBits.get());
        root.put("genDitherDbvDisplay",          genDitherDbvDisplay.get());
        root.put("genOutputChannels",            genOutputChannels.get().name());
        if (genDpd.get()     != null) root.put("genDpd",     genDpd.get());
        if (genDpdDual.get() != null) root.put("genDpdDual", genDpdDual.get());
        if (genDpdFolder.get() != null) root.put("genDpdFolder", genDpdFolder.get());
        root.put("predistortionAverages",    predistortionAverages.get());
        root.put("predistortionTargetPct",   predistortionTargetPct.get());
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
        if (screenshotCommentFont.get() != null) root.put("screenshotCommentFont", screenshotCommentFont.get());

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
                pm.put("leftResidualEnabled",    p.isLeftResidualEnabled());
                pm.put("rightResidualEnabled",   p.isRightResidualEnabled());
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
        root.put("fftDetectTimeDiscontinuity", fftDetectTimeDiscontinuity.get());
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
        root.put("fftManualFundVrms",         fftManualFundVrms.get());
        root.put("fftManualFundDbvDisplay",   fftManualFundDbvDisplay.get());
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
        if (!fftCalibrations.isEmpty()) {
            List<Map<String, Object>> cals = new ArrayList<>();
            for (CalibrationEntry e : fftCalibrations) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (e.getPath() != null) m.put("path", e.getPath());
                m.put("active",    e.active().get());
                m.put("withNoise", e.withNoise().get());
                cals.add(m);
            }
            root.put("fftCalibrations", cals);
        }
        root.put("fftBeforeCalDotColor", fftBeforeCalDotColor.get());
        root.put("fftCalOverlayColor",   fftCalOverlayColor.get());
        root.put("fftLineWidth",              fftLineWidth.get());
        root.put("freqRespLineWidth",         freqRespLineWidth.get());
        root.put("fftHarmonicDotDiameter",    fftHarmonicDotDiameter.get());
        root.put("fftLineColor",              formatHtmlColor(fftLineColor.get()));
        root.put("fftChartBackgroundColor",   formatHtmlColor(fftChartBackgroundColor.get()));
        root.put("fftHarmonicDotColor",       formatHtmlColor(fftHarmonicDotColor.get()));
        root.put("fftFreqRespColor",          formatHtmlColor(fftFreqRespColor.get()));

        // ---- Frequency Response pane --------------------------------------
        root.put("freqRespStartHz",           freqRespStartHz.get());
        root.put("freqRespStopHz",            freqRespStopHz.get());
        root.put("freqRespAmplitudeVrms",     freqRespAmplitudeVrms.get());
        root.put("freqRespAmplitudeDbvDisplay", freqRespAmplitudeDbvDisplay.get());
        root.put("freqRespSweepPoints",       freqRespSweepPoints.get());
        root.put("freqRespDurationSec",       freqRespDurationSec.get());
        root.put("freqRespFftSize",           freqRespFftSize.get());
        root.put("freqRespDitherBits",        freqRespDitherBits.get());
        root.put("freqRespLeadInSec",         freqRespLeadInSec.get());
        root.put("freqRespOutputChannels",    freqRespOutputChannels.get().name());
        root.put("tuneNotchStartHz",          tuneNotchStartHz.get());
        root.put("tuneNotchStopHz",           tuneNotchStopHz.get());
        root.put("tuneNotchAmplitudeVrms",    tuneNotchAmplitudeVrms.get());
        root.put("tuneNotchTargetHz",         tuneNotchTargetHz.get());
        root.put("tuneNotchOutputChannels",   tuneNotchOutputChannels.get().name());
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
        // Note: freqRespShowFilter is intentionally NOT persisted — it always
        // starts unchecked on a fresh session (mirrors freqRespShowRiaa).
        root.put("freqRespFilterCompare",     freqRespFilterCompare.get());
        root.put("freqRespFilterType",        freqRespFilterType.get().name());
        root.put("freqRespFilterResponse",    freqRespFilterResponse.get().name());
        root.put("freqRespUnevenMode",        freqRespUnevenMode.get().name());
        root.put("freqRespUnevenNotch",       freqRespUnevenNotch.get());
        root.put("freqRespUnevenDb",          freqRespUnevenDb.get());
        root.put("freqRespUnevenStartHz",     freqRespUnevenStartHz.get());
        root.put("freqRespUnevenStopHz",      freqRespUnevenStopHz.get());
        root.put("freqRespApplyCalibration",  freqRespApplyCalibration.get());
        if (!freqRespCalibrations.isEmpty()) {
            List<Map<String, Object>> cals = new ArrayList<>();
            for (CalibrationEntry e : freqRespCalibrations) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (e.getPath() != null) m.put("path", e.getPath());
                m.put("active", e.active().get());
                cals.add(m);
            }
            root.put("freqRespCalibrations", cals);
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
                pm.put("manualFundDbvDisplay", p.isManualFundDbvDisplay());
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
                pm.put("showFilter",          p.isShowFilter());
                pm.put("filterCompare",       p.isFilterCompare());
                pm.put("filterType",          p.getFilterType().name());
                pm.put("filterResponse",      p.getFilterResponse().name());
                pm.put("filterParams",        writeFilterParams(p.getFilterParams()));
                pm.put("unevenMode",          p.getUnevenMode().name());
                pm.put("unevenNotch",         p.isUnevenNotch());
                pm.put("unevenDb",            p.getUnevenDb());
                pm.put("unevenStartHz",       p.getUnevenStartHz());
                pm.put("unevenStopHz",        p.getUnevenStopHz());
                frMap.put(e.getKey(), pm);
            }
            root.put("freqRespPresets", frMap);
        }

        if (!freqRespFilterParamsByType.isEmpty()) {
            Map<String, Object> fptMap = new LinkedHashMap<>();
            for (Map.Entry<FilterType, FreqRespFilterTypeParams> e : freqRespFilterParamsByType.entrySet()) {
                fptMap.put(e.getKey().name(), writeFilterParams(e.getValue()));
            }
            root.put("freqRespFilterParamsByType", fptMap);
        }

        // Per-card profiles live in their own devices.yaml (see saveDevices());
        // toMap deliberately does not write an audioDevices block.

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
        if (root.get("uiFontNormal")  instanceof String s) uiFontNormal.set(s);
        if (root.get("uiFontBold")    instanceof String s) uiFontBold.set(s);
        if (root.get("uiFontChannel") instanceof String s) uiFontChannel.set(s);
        if (root.get("activeTabIndex") instanceof Number n) activeTabIndex.set(n.intValue());
        if (root.get("smallIconsInMainTab") instanceof Boolean b) smallIconsInMainTab.set(b);
        if (root.get("checkForUpdatesOnStartup")  instanceof Boolean b) checkForUpdatesOnStartup.set(b);
        if (root.get("includeBetaInUpdateChecks") instanceof Boolean b) includeBetaInUpdateChecks.set(b);
        if (root.get("showTipsAtStartup")         instanceof Boolean b) showTipsAtStartup.set(b);
        if (root.get("useGpuAcceleration")        instanceof Boolean b) useGpuAcceleration.set(b);
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
        if (root.get("oscTriggerType")         instanceof String  s) oscTriggerType.set(enumOr(TriggerType.class,    s, oscTriggerType.get()));
        if (root.get("oscTriggerMode")         instanceof String  s) oscTriggerMode.set(enumOr(TriggerMode.class,    s, oscTriggerMode.get()));
        if (root.get("oscTriggerHysteresisDiv")     instanceof Number  n) oscTriggerHysteresisDiv.set(n.doubleValue());
        if (root.get("oscTriggerHysteresisEnabled") instanceof Boolean b) oscTriggerHysteresisEnabled.set(b);
        if (root.get("oscShowReconstructedBeat")    instanceof Boolean b) oscShowReconstructedBeat.set(b);
        if (root.get("oscLeftSincInterpEnabled")  instanceof Boolean b) oscLeftSincInterpEnabled.set(b);
        if (root.get("oscRightSincInterpEnabled") instanceof Boolean b) oscRightSincInterpEnabled.set(b);
        if (root.get("oscLeftResidualEnabled")  instanceof Boolean b) oscLeftResidualEnabled.set(b);
        if (root.get("oscRightResidualEnabled") instanceof Boolean b) oscRightResidualEnabled.set(b);
        if (root.get("oscLeftMainsSuppression")  instanceof String s) oscLeftMainsSuppression.set(enumOr(MainsSuppression.class, s, oscLeftMainsSuppression.get()));
        if (root.get("oscRightMainsSuppression") instanceof String s) oscRightMainsSuppression.set(enumOr(MainsSuppression.class, s, oscRightMainsSuppression.get()));
        if (root.get("oscLeftLpf")  instanceof String s) oscLeftLpf.set(enumOr(LpfMode.class, s, oscLeftLpf.get()));
        if (root.get("oscRightLpf") instanceof String s) oscRightLpf.set(enumOr(LpfMode.class, s, oscRightLpf.get()));
        if (root.get("oscLeftOffsetFrac")      instanceof Number n) oscLeftOffsetFrac.set(n.doubleValue());
        if (root.get("oscRightOffsetFrac")     instanceof Number n) oscRightOffsetFrac.set(n.doubleValue());
        if (root.get("oscTriggerLevelFrac")    instanceof Number n) oscTriggerLevelFrac.set(n.doubleValue());
        if (root.get("oscTriggerPositionFrac") instanceof Number n) oscTriggerPositionFrac.set(n.doubleValue());
        if (root.get("oscMeasurementAverageSeconds") instanceof Number n) oscMeasurementAverageSeconds.set(n.doubleValue());
        if (root.get("oscPersistenceMode")           instanceof String s) oscPersistenceMode.set(enumOr(PersistenceMode.class, s, oscPersistenceMode.get()));
        if (root.get("oscPersistenceManualSeconds")  instanceof Number n) oscPersistenceManualSeconds.set(n.doubleValue());
        if (root.get("oscMeasurementChannel")        instanceof String s) oscMeasurementChannel.set(enumOr(Channel.class, s, oscMeasurementChannel.get()));
        if (root.get("oscShowStats")                 instanceof Boolean b) oscShowStats.set(b);
        if (root.get("oscShowMeasurementTable")      instanceof Boolean b) oscShowMeasurementTable.set(b);
        // DEPRECATED shared full-scale fallback (unbound devices) — see toMap();
        // honoured so a pre-card preferences.yaml keeps its calibration.  The
        // setters validate and refresh the cached dBV offsets.
        if (root.get("adcFsVoltageRms") instanceof Number n) setAdcFsVoltageRms(n.doubleValue());
        if (root.get("dacFsVoltageRms") instanceof Number n) setDacFsVoltageAmpl(n.doubleValue() * Constants.SQRT2);
        if (root.get("genSignalForm")                instanceof String s) genSignalForm.set(enumOr(GenSignalForm.class, s, genSignalForm.get()));
        if (root.get("genFrequencyHz")               instanceof Number n) genFrequencyHz.set(n.doubleValue());
        if (root.get("genDualToneFreq1Hz")           instanceof Number n) genDualToneFreq1Hz.set(n.doubleValue());
        if (root.get("genDualToneFreq2Hz")           instanceof Number n) genDualToneFreq2Hz.set(n.doubleValue());
        if (root.get("genDualToneSplitPct")          instanceof Number n) genDualToneSplitPct.set(n.doubleValue());
        if (root.get("genAmplitudeVrms")             instanceof Number n) genAmplitudeVrms.set(n.doubleValue());
        if (root.get("genAmplitudeDbvDisplay")       instanceof Boolean b) genAmplitudeDbvDisplay.set(b);
        if (root.get("genDitherBits")                instanceof Number n) genDitherBits.set(n.doubleValue());
        if (root.get("genDitherDbvDisplay")          instanceof Boolean b) genDitherDbvDisplay.set(b);
        if (root.get("genOutputChannels")            instanceof String s) genOutputChannels.set(enumOr(OutputChannels.class, s, genOutputChannels.get()));
        if (root.get("genDpd")                        instanceof String s) genDpd.set(s);
        if (root.get("genDpdDual")                    instanceof String s) genDpdDual.set(s);
        if (root.get("genDpdFolder")                 instanceof String s) genDpdFolder.set(s);
        if (root.get("predistortionAverages")        instanceof Number n) predistortionAverages.set(n.intValue());
        if (root.get("predistortionTargetPct")       instanceof Number n) predistortionTargetPct.set(n.doubleValue());
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
        if (root.get("screenshotCommentFont")        instanceof String s) screenshotCommentFont.set(s);

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
                if (pm.get("leftResidualEnabled")    instanceof Boolean b) p.setLeftResidualEnabled(b);
                if (pm.get("rightResidualEnabled")   instanceof Boolean b) p.setRightResidualEnabled(b);
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
        if (root.get("fftDetectTimeDiscontinuity") instanceof Boolean b) fftDetectTimeDiscontinuity.set(b);
        if (root.get("fftWindow")                 instanceof String  s) fftWindow.set(enumOr(WindowType.class, s, fftWindow.get()));
        if (root.get("fftOverlap")                instanceof String  s) fftOverlap.set(enumOr(FftOverlap.class, s, fftOverlap.get()));
        if (root.get("fftCoherentAveraging")      instanceof Boolean b) fftCoherentAveraging.set(b);
        if (root.get("fftMainsSuppression")       instanceof String  s) fftMainsSuppression.set(enumOr(MainsSuppression.class, s, fftMainsSuppression.get()));
        if (root.get("fftAlignGenerator")         instanceof String  s) fftAlignGenerator.set(AlignGenerator.fromString(s));
        else if (root.get("fftAlignGenToFreqDiff") instanceof Boolean b)   // migrate the old checkbox
            fftAlignGenerator.set(b ? AlignGenerator.FLL : AlignGenerator.NONE);
        if (root.get("fftDistMinHz")              instanceof Number  n) fftDistMinHz.set(n.doubleValue());
        if (root.get("fftDistMaxHz")              instanceof Number  n) fftDistMaxHz.set(n.doubleValue());
        if (root.get("fftDistMinEnabled")         instanceof Boolean b) fftDistMinEnabled.set(b);
        if (root.get("fftDistMaxEnabled")         instanceof Boolean b) fftDistMaxEnabled.set(b);
        if (root.get("fftThdMaxHarmonic")         instanceof Number  n) fftThdMaxHarmonic.set(n.intValue());
        if (root.get("fftCalcMaxHarmonic")        instanceof Number  n) fftCalcMaxHarmonic.set(n.intValue());
        if (root.get("fftStrongToneRelDb")        instanceof Number  n) fftStrongToneRelDb.set(n.doubleValue());
        if (root.get("fftManualFundVrms")         instanceof Number  n) fftManualFundVrms.set(n.doubleValue());
        if (root.get("fftManualFundDbvDisplay")   instanceof Boolean b) fftManualFundDbvDisplay.set(b);
        if (root.get("fftManualFundEnabled")      instanceof Boolean b) fftManualFundEnabled.set(b);
        if (root.get("fftChannel")                instanceof String  s) fftChannel.set(enumOr(Channel.class, s, fftChannel.get()));
        if (root.get("fftMagUnit")                instanceof String  s) fftMagUnit.set(enumOr(MagnitudeUnit.class, s, fftMagUnit.get()));
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
        if (root.get("freqRespAmplitudeDbvDisplay") instanceof Boolean b) freqRespAmplitudeDbvDisplay.set(b);
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
        if (root.get("freqRespOutputChannels")    instanceof String  s) freqRespOutputChannels.set(enumOr(OutputChannels.class, s, freqRespOutputChannels.get()));
        if (root.get("tuneNotchStartHz")          instanceof Number  n) tuneNotchStartHz.set(n.doubleValue());
        if (root.get("tuneNotchStopHz")           instanceof Number  n) tuneNotchStopHz.set(n.doubleValue());
        if (root.get("tuneNotchAmplitudeVrms")    instanceof Number  n) tuneNotchAmplitudeVrms.set(n.doubleValue());
        if (root.get("tuneNotchTargetHz")         instanceof Number  n) tuneNotchTargetHz.set(n.doubleValue());
        if (root.get("tuneNotchOutputChannels")   instanceof String  s) tuneNotchOutputChannels.set(enumOr(OutputChannels.class, s, tuneNotchOutputChannels.get()));
        if (root.get("freqRespLeftVisible")       instanceof Boolean b) freqRespLeftVisible.set(b);
        if (root.get("freqRespRightVisible")      instanceof Boolean b) freqRespRightVisible.set(b);
        if (root.get("freqRespPhaseVisible")      instanceof Boolean b) freqRespPhaseVisible.set(b);
        if (root.get("freqRespFreqMinHz")         instanceof Number  n) freqRespFreqMinHz.set(n.doubleValue());
        if (root.get("freqRespFreqMaxHz")         instanceof Number  n) freqRespFreqMaxHz.set(n.doubleValue());
        if (root.get("freqRespMagTopDb")          instanceof Number  n) freqRespMagTopDb.set(n.doubleValue());
        if (root.get("freqRespMagBotDb")          instanceof Number  n) freqRespMagBotDb.set(n.doubleValue());
        if (root.get("freqRespNyquistFraction")   instanceof Number  n) {
            // Clamp into the [0.83, 1.0] band the UI exposes.
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
        // freqRespShowFilter is intentionally not loaded from disk — it
        // always starts unchecked on a fresh session (mirrors freqRespShowRiaa).
        if (root.get("freqRespFilterCompare")     instanceof Boolean b) freqRespFilterCompare.set(b);
        if (root.get("freqRespFilterType")        instanceof String  s) freqRespFilterType.set(enumOr(FilterType.class, s, freqRespFilterType.get()));
        if (root.get("freqRespFilterResponse")    instanceof String  s) freqRespFilterResponse.set(enumOr(FilterResponse.class, s, freqRespFilterResponse.get()));
        if (root.get("freqRespUnevenMode")        instanceof String  s) freqRespUnevenMode.set(enumOr(UnevenMode.class, s, freqRespUnevenMode.get()));
        if (root.get("freqRespUnevenNotch")       instanceof Boolean b) freqRespUnevenNotch.set(b);
        if (root.get("freqRespUnevenDb")          instanceof Number  n) {
            freqRespUnevenDb.set(Math.max(0.001, Math.min(20.0, n.doubleValue())));
        }
        if (root.get("freqRespUnevenStartHz")     instanceof Number  n) freqRespUnevenStartHz.set(n.doubleValue());
        if (root.get("freqRespUnevenStopHz")      instanceof Number  n) freqRespUnevenStopHz.set(n.doubleValue());
        // Sanity: start must sit below stop — otherwise reset both to defaults.
        if (freqRespUnevenStartHz.get() >= freqRespUnevenStopHz.get()) {
            freqRespUnevenStartHz.set(20.0);
            freqRespUnevenStopHz.set(20_000.0);
        }
        if (root.get("freqRespApplyCalibration")  instanceof Boolean b) freqRespApplyCalibration.set(b);
        if (root.get("freqRespCalibrations") instanceof List<?> raw) {
            freqRespCalibrations.clear();
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String  path   = m.get("path")   instanceof String  s ? s : null;
                boolean active = m.get("active") instanceof Boolean b && b;
                CalibrationEntry e = new CalibrationEntry(path, active, false);
                freqRespCalibrations.add(e);
                trackCalibration(e);
            }
        }
        if (root.get("freqRespSaveFolder")        instanceof String  s) freqRespSaveFolder.set(s);
        if (root.get("freqRespSavePath")          instanceof String  s) freqRespSavePath.set(s);
        if (root.get("freqRespLoadFolder")        instanceof String  s) freqRespLoadFolder.set(s);
        if (root.get("freqRespLoadPath")          instanceof String  s) freqRespLoadPath.set(s);
        if (root.get("freqRespActiveTabIndex")    instanceof Number  n) freqRespActiveTabIndex.set(n.intValue());
        if (root.get("fftLoadFolder")             instanceof String  s) fftLoadFolder.set(s);
        if (root.get("fftCalibrations") instanceof List<?> raw) {
            fftCalibrations.clear();
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String  path      = m.get("path")      instanceof String  s ? s : null;
                boolean active    = m.get("active")    instanceof Boolean b && b;
                boolean withNoise = m.get("withNoise") instanceof Boolean b && b;
                CalibrationEntry e = new CalibrationEntry(path, active, withNoise);
                fftCalibrations.add(e);
                trackCalibration(e);
            }
        }
        Object beforeCalColorObj = root.get("fftBeforeCalDotColor");
        if (beforeCalColorObj instanceof String s) fftBeforeCalDotColor.set(parseHtmlColor(s, fftBeforeCalDotColor.get()));
        else if (beforeCalColorObj instanceof Number n) fftBeforeCalDotColor.set(n.intValue());
        Object calOverlayColorObj = root.get("fftCalOverlayColor");
        if (calOverlayColorObj instanceof String s) fftCalOverlayColor.set(parseHtmlColor(s, fftCalOverlayColor.get()));
        else if (calOverlayColorObj instanceof Number n) fftCalOverlayColor.set(n.intValue());
        if (root.get("fftLineWidth")              instanceof Number  n) fftLineWidth.set(n.doubleValue());
        if (root.get("freqRespLineWidth")         instanceof Number  n) freqRespLineWidth.set(n.doubleValue());
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
                if (pm.get("magUnit")           instanceof String  s) p.setMagUnit(enumOr(MagnitudeUnit.class, s, p.getMagUnit()));
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
                if (pm.get("manualFundDbvDisplay") instanceof Boolean b) p.setManualFundDbvDisplay(b);
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
                if (pm.get("showFilter")          instanceof Boolean b) p.setShowFilter(b);
                if (pm.get("filterCompare")       instanceof Boolean b) p.setFilterCompare(b);
                if (pm.get("filterType")          instanceof String  s) p.setFilterType(enumOr(FilterType.class, s, p.getFilterType()));
                if (pm.get("filterResponse")      instanceof String  s) p.setFilterResponse(enumOr(FilterResponse.class, s, p.getFilterResponse()));
                if (pm.get("filterParams")        instanceof Map<?, ?> fpm) p.setFilterParams(readFilterParams(p.getFilterType(), fpm));
                if (pm.get("unevenMode")          instanceof String  s) p.setUnevenMode(enumOr(UnevenMode.class, s, p.getUnevenMode()));
                if (pm.get("unevenNotch")         instanceof Boolean b) p.setUnevenNotch(b);
                if (pm.get("unevenDb")            instanceof Number  n) p.setUnevenDb(n.doubleValue());
                if (pm.get("unevenStartHz")       instanceof Number  n) p.setUnevenStartHz(n.doubleValue());
                if (pm.get("unevenStopHz")        instanceof Number  n) p.setUnevenStopHz(n.doubleValue());
                freqRespPresets.put(key, p);
            }
        }

        if (root.get("freqRespFilterParamsByType") instanceof Map<?, ?> fptMap) {
            freqRespFilterParamsByType.clear();
            for (Map.Entry<?, ?> e : fptMap.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                FilterType type = enumOr(FilterType.class, key, null);
                if (type == null) continue;
                if (!(e.getValue() instanceof Map<?, ?> pm)) continue;
                freqRespFilterParamsByType.put(type, readFilterParams(type, pm));
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

    /** The filter parameters for {@code type}, or that type's pinned
     *  {@link FreqRespFilterTypeParams#fromType} defaults when nothing is
     *  stored yet.  Never mutates the map — the caller edits the returned
     *  snapshot and writes it back via {@link #putFreqRespFilterParams}. */
    public synchronized FreqRespFilterTypeParams getFreqRespFilterParams(FilterType type) {
        if (type == null) return FreqRespFilterTypeParams.fromType(FilterType.LOW_PASS);
        FreqRespFilterTypeParams p = freqRespFilterParamsByType.get(type);
        return p != null ? p : FreqRespFilterTypeParams.fromType(type);
    }

    /** Stores {@code params} under {@code type} and persists. */
    public synchronized void putFreqRespFilterParams(FilterType type, FreqRespFilterTypeParams params) {
        if (type == null || params == null) return;
        freqRespFilterParamsByType.put(type, params);
        save();
    }

    // -------------------------------------------------------------------------
    // Per-card calibration profiles — the store lives here in memory; only its
    // PERSISTENCE is the separate devices.yaml (seed-if-absent, load, migrate).
    // -------------------------------------------------------------------------

    /** The {@code devices.yaml} location: the test override when set, else the
     *  file beside {@code preferences.yaml} in the user data dir. */
    private Path devicesPath() {
        return devicesPathOverride != null
                ? devicesPathOverride
                : AppPaths.instance().file(DEVICES_FILE);
    }

    /** Establishes the per-card profile store on the live singleton at startup:
     *  a no-op in a {@link #detached} copy (mirrors {@link #load()} skipping
     *  there); otherwise delegates to {@link #loadDevicesFrom(Path)} against the
     *  real {@link #devicesPath()}. */
    private void loadDevices() {
        if (detached) return;
        loadDevicesFrom(devicesPath());
    }

    /** Establishes the per-card profile store from a {@code devices.yaml} at
     *  {@code path}: seeds the file from the bundled classpath resource on first
     *  run (reusing the {@code AppPaths} first-run seeding discipline), loads it,
     *  then runs the once-per-content-version {@link #mergeSeed seed merge} when
     *  the bundle's {@link SeedBundle#contentVersion} is greater than the one the
     *  store recorded: it adds cards / ranges the bundle gained and refreshes each
     *  existing range's nominal to the release value, keeping the user's channel
     *  mode, match list, active range, hand-added ranges and calibrated values.
     *  The file is rewritten when that seed merge changed the store.  This is the
     *  mechanism seam a test drives against a temp dir; the live singleton reaches
     *  it via {@link #loadDevices}. */
    private void loadDevicesFrom(Path path) {
        seedStoreIfAbsent(path);
        readDevicesFile(path);
        // ONE seed parse for the whole load: the merge gate, the merge source and
        // the rewrite below all consume the same SeedBundle.
        SeedBundle seed = readSeed();
        // Once-per-content-version seed merge: when the bundle catalog advanced
        // past the version this store last recorded, fold the bundle in — new
        // cards, new ranges, refreshed nominals — while keeping every user-owned
        // choice (channel mode, match list, active range, hand-added ranges,
        // calibrated values).  A store with no recorded version counts as 0, so it
        // merges once and then records the bundle's version; the store is
        // rewritten only when this merge changed it.
        if (seed.contentVersion > recordedContentVersion) {
            mergeSeed(seed.profiles);
            recordedContentVersion = seed.contentVersion;
            writeDevicesTo(path, seed);
        }
    }

    /** Everything the bundled {@code devices.yaml} seed contributes, from ONE
     *  parse: the profiles (merge source), the {@code contentVersion} (merge
     *  gate), the {@code formatVersion} (single source of truth for the written
     *  store's format marker — never a hardwired Java constant) and the leading
     *  header comment (copied into the user store so the editable file carries
     *  the seed's documentation).  Missing / garbled seed → empty profiles,
     *  versions {@code 0}, empty header. */
    private static final class SeedBundle {
        final List<AudioDeviceProfile> profiles;
        final int                      contentVersion;
        final int                      formatVersion;
        final String                   headerComment;

        private SeedBundle(List<AudioDeviceProfile> profiles, int contentVersion,
                           int formatVersion, String headerComment) {
            this.profiles       = profiles;
            this.contentVersion = contentVersion;
            this.formatVersion  = formatVersion;
            this.headerComment  = headerComment;
        }
    }

    /** Reads the bundled seed ONCE — bytes to text, header comment off the text,
     *  YAML parse off the same text, profiles via the shared
     *  {@link #readDeviceProfile} vocabulary — so the merge gate, the merge
     *  source and the store writer all consume one consistent snapshot. */
    private SeedBundle readSeed() {
        List<AudioDeviceProfile> profiles = new ArrayList<>();
        int contentVersion = 0;
        int formatVersion  = 0;
        StringBuilder header = new StringBuilder();
        try (InputStream in = openSeed()) {
            if (in == null) return new SeedBundle(profiles, 0, 0, "");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n", -1)) {
                if (!line.isBlank() && !line.startsWith("#")) break;
                header.append(line).append('\n');
            }
            Object loaded = new Yaml().load(text);
            if (loaded instanceof Map<?, ?> root) {
                if (root.get("contentVersion") instanceof Number n) contentVersion = n.intValue();
                if (root.get("formatVersion")  instanceof Number n) formatVersion  = n.intValue();
                if (root.get("audioDevices") instanceof List<?> raw) {
                    for (Object o : raw) {
                        if (!(o instanceof Map<?, ?> m)) continue;
                        AudioDeviceProfile p = readDeviceProfile(m);
                        if (p != null) profiles.add(p);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            if (log.isWarnEnabled()) log.warn("Could not read bundled seed: {}", e.getMessage());
            return new SeedBundle(new ArrayList<>(), 0, 0, "");
        }
        return new SeedBundle(profiles, contentVersion, formatVersion, header.toString());
    }

    /** Copies the bundled seed to {@code path} on first run (file absent).  Uses
     *  the {@link #seedPathOverride test override} file when set — copied through
     *  the same absent-only discipline — else the {@code AppPaths} classpath
     *  seeding of {@link #DEVICES_SEED_RESOURCE}. */
    private void seedStoreIfAbsent(Path path) {
        if (seedPathOverride == null) {
            AppPaths.instance().seedFileFromClasspathIfAbsent(path, DEVICES_SEED_RESOURCE);
            return;
        }
        if (Files.exists(path)) return;
        try {
            Files.createDirectories(path.getParent());
            Files.copy(seedPathOverride, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (log.isWarnEnabled()) log.warn("Could not seed {} from {}: {}", path, seedPathOverride, e.getMessage());
        }
    }

    /** Opens the bundled seed's bytes: the {@link #seedPathOverride test override}
     *  file when set, else the classpath resource ({@link #DEVICES_SEED_RESOURCE}).
     *  {@code null} when neither is available.  Sole consumer is
     *  {@link #readSeed()}, the one-parse snapshot of the bundle. */
    private InputStream openSeed() throws IOException {
        if (seedPathOverride != null) {
            return Files.exists(seedPathOverride) ? Files.newInputStream(seedPathOverride) : null;
        }
        return Preferences.class.getResourceAsStream(DEVICES_SEED_RESOURCE);
    }


    /** Once-per-content-version merge of the bundled seed catalog into the user
     *  store.  Each seed card is mapped to a store card by
     *  {@link #findStoreCardForSeed name or match-list overlap}: none found → the
     *  whole seed card is deep-copied in; found → its RANGE table is reconciled per
     *  direction (see {@link #mergeSeedRanges}) and any match entry the release
     *  added is UNIONED into the store card (see {@link #unionMatch} — add-only,
     *  case-insensitive dedupe).  The matched store card keeps its own name, channel
     *  mode and active-range selection; a user-created card (no seed maps onto it)
     *  is never visited, so it stays untouched. */
    private void mergeSeed(List<AudioDeviceProfile> bundle) {
        synchronized (audioDevices) {
            for (AudioDeviceProfile seed : bundle) {
                if (seed == null || seed.getName() == null || seed.getName().isEmpty()) continue;
                AudioDeviceProfile store = findStoreCardForSeed(seed);
                if (store == null) {
                    audioDevices.add(copyProfile(seed));
                } else {
                    unionMatch(store.getMatch(), seed.getMatch());
                    mergeSeedRanges(store.getInput(),  seed.getInput());
                    mergeSeedRanges(store.getOutput(), seed.getOutput());
                }
            }
        }
    }

    /** Add-only case-insensitive union of a seed card's {@code match} entries into
     *  the store card's list on the seed merge: existing entries keep their place
     *  and order, each seed entry the store lacks (compared case-insensitively) is
     *  appended.  No removals — a user-added recognition pattern is never dropped,
     *  so a released seed's new match entry reaches an existing store card. */
    private void unionMatch(List<String> into, List<String> from) {
        for (String s : from) {
            if (s != null && !s.isEmpty() && !containsIgnoreCase(into, s)) {
                into.add(s);
            }
        }
    }

    /** The store card the {@code seed} card maps onto — first by case-insensitive
     *  logical NAME, else by MATCH-list overlap (any {@code match} entry equal
     *  case-insensitively between the two lists), so a user who renamed a well-known
     *  card is still recognised by its recognition patterns.  {@code null} when the
     *  seed card is new to the store.  Called under the {@code audioDevices} lock
     *  {@link #mergeSeed} already holds. */
    private AudioDeviceProfile findStoreCardForSeed(AudioDeviceProfile seed) {
        for (AudioDeviceProfile p : audioDevices) {
            if (seed.getName().equalsIgnoreCase(p.getName())) return p;
        }
        for (AudioDeviceProfile p : audioDevices) {
            if (matchOverlap(seed.getMatch(), p.getMatch())) return p;
        }
        return null;
    }

    /** True when {@code a} and {@code b} share at least one entry compared
     *  case-insensitively — the match-list overlap {@link #findStoreCardForSeed}
     *  uses so a renamed card keeps its seed identity. */
    private boolean matchOverlap(List<String> a, List<String> b) {
        for (String s : a) {
            if (containsIgnoreCase(b, s)) return true;
        }
        return false;
    }

    /** Reconciles one direction's range table against the {@code seed} endpoint:
     *  each seed range is ADDED when the store lacks its label, else it REPLACES the
     *  store row in place with the seed's nominal definition — but a store row the
     *  user CALIBRATED carries its measured {@code fsLeft} / {@code fsRight} and the
     *  {@code calibrated} flag into the replacement (a user scalar-calibrated value
     *  lands on both channels; distinct left/right survive).  A device-provided
     *  endpoint ({@code calibrationFromDevice}) always takes the seed values (the
     *  device owns them).  Store rows the seed no longer lists are left in place, so
     *  hand-added ranges survive.  A {@code null} seed endpoint (the seed doesn't
     *  cover this direction) contributes nothing. */
    private void mergeSeedRanges(DeviceEndpointConfig storeEp, DeviceEndpointConfig seedEp) {
        if (seedEp == null || storeEp == null) return;
        boolean deviceOwned = seedEp.isCalibrationFromDevice();
        List<DeviceRange> storeRows = storeEp.getRanges();
        for (DeviceRange seedRow : seedEp.getRanges()) {
            DeviceRange merged = seedRow.deepCopy();          // nominal seed values + format, calibrated=false
            int idx = indexOfLabel(storeRows, seedRow.getLabel());
            if (idx < 0) {
                storeRows.add(merged);
                continue;
            }
            DeviceRange storeRow = storeRows.get(idx);
            if (!deviceOwned && storeRow.isCalibrated()) {
                merged.setFsLeft(storeRow.getFsLeft());
                merged.setFsRight(storeRow.getFsRight());
                merged.setCalibrated(true);
            }
            storeRows.set(idx, merged);
        }
    }

    /** The index of the first row labelled {@code label} (case-insensitive) in
     *  {@code rows}, or {@code -1} when none — the seed-merge lookup that
     *  distinguishes "add a new range" from "update one in place". */
    private int indexOfLabel(List<DeviceRange> rows, String label) {
        if (label == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (label.equalsIgnoreCase(rows.get(i).getLabel())) return i;
        }
        return -1;
    }

    /** Reads {@code devices.yaml} into {@link #audioDevices}, tolerantly: a
     *  missing file is a fresh start, a garbled root / entry is a guarded warn
     *  and skip, never a throw (a broken store can't stop the app starting). */
    private void readDevicesFile(Path path) {
        if (!Files.exists(path)) return;
        try (Reader r = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(r);
            if (!(loaded instanceof Map<?, ?> root)) return;
            if (root.get("contentVersion") instanceof Number n) recordedContentVersion = n.intValue();
            if (root.get("formatVersion")  instanceof Number n) storeFormatVersion     = n.intValue();
            if (!(root.get("audioDevices") instanceof List<?> raw)) return;
            synchronized (audioDevices) {
                for (Object o : raw) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    AudioDeviceProfile p = readDeviceProfile(m);
                    if (p != null) audioDevices.add(p);
                }
            }
            if (log.isInfoEnabled()) {
                log.info("Device profiles loaded from {}", path.toAbsolutePath());
            }
        } catch (IOException | RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to load device profiles from {}: {}", path, e.getMessage());
            }
        }
    }

    /** Persists the per-card profile store to {@code devices.yaml} — the policy
     *  gate the profile mutators call.  No-op in {@link #isTransientMode()
     *  transient mode} or a {@link #detached} copy, so a dialog edit copy / a CLI
     *  run never writes the store; otherwise delegates to
     *  {@link #writeDevicesTo(Path)} against {@link #devicesPath()}. */
    public synchronized void saveDevices() {
        if (transientMode || detached) return;
        writeDevicesTo(devicesPath());
    }

    /** Writes the per-card profile store to {@code path} using the same atomic
     *  temp-file-move discipline as {@link #save()} — the file mechanism, with no
     *  policy gate (a test drives it directly against a temp dir).
     *  {@code synchronized} (preset style) so a structural profile change can't
     *  race a concurrent write iterating the list. */
    private synchronized void writeDevicesTo(Path path) {
        writeDevicesTo(path, readSeed());
    }

    /** {@link #writeDevicesTo(Path)} with an already-parsed {@link SeedBundle}
     *  (one seed read per operation — the load path shares its bundle with the
     *  merge gate; standalone saves parse once via the delegate above). */
    private synchronized void writeDevicesTo(Path path, SeedBundle seed) {
        // HAND-EMITTED YAML: SnakeYAML's emitter cannot reproduce the seed's exact
        // style (one-line rows regardless of width, spaces inside the braces,
        // double-quoted labels), so the store text is emitted directly —
        // deterministic and byte-stable; the ordinary YAML reader parses it back.
        StringBuilder out = new StringBuilder();
        // The user-editable store carries the bundled seed's header comment
        // (vocabulary + upgrade behaviour), copied dynamically at write time
        // so seed-comment edits propagate on the next rewrite.
        out.append(seed.headerComment);
        // formatVersion is COPIED from the seed — the single source of truth for
        // the devices.yaml format marker; the loaded store's own version when the
        // seed is unreadable, 1 as the last resort.
        int formatVersion = seed.formatVersion > 0 ? seed.formatVersion
                : (storeFormatVersion > 0 ? storeFormatVersion : 1);
        out.append("formatVersion: ").append(formatVersion).append('\n');
        // User-store bookkeeping only — records the bundled seed catalog version we
        // last merged against, so the once-per-content-version merge fires exactly
        // once per bump.  Never present in the bundled seed resource.
        out.append("contentVersion: ").append(recordedContentVersion).append('\n');
        synchronized (audioDevices) {
            if (audioDevices.isEmpty()) {
                out.append("audioDevices: []\n");
            } else {
                out.append("audioDevices:\n");
                for (AudioDeviceProfile p : audioDevices) writeDeviceProfile(out, p);
            }
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(tmp, out.toString());
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to save device profiles to {}: {}", path, e.getMessage());
            }
        }
    }

    /** A defensive deep copy of the profile list, in insertion order.  The
     *  caller may mutate the returned profiles freely (e.g. the dialog copy)
     *  without touching the live model. */
    public List<AudioDeviceProfile> getAudioDeviceProfiles() {
        List<AudioDeviceProfile> out;
        synchronized (audioDevices) {
            out = new ArrayList<>(audioDevices.size());
            for (AudioDeviceProfile p : audioDevices) out.add(copyProfile(p));
        }
        return out;
    }

    /** The live profile with logical {@code name} (case-insensitive), or
     *  {@code null} when none is stored.  Returns the live object — callers that
     *  mutate then persist go through {@link #putAudioDeviceProfile}. */
    public AudioDeviceProfile findAudioDeviceProfile(String name) {
        if (name == null) return null;
        synchronized (audioDevices) {
            for (AudioDeviceProfile p : audioDevices) {
                if (name.equalsIgnoreCase(p.getName())) return p;
            }
        }
        return null;
    }

    /** Adds {@code p}, or replaces the existing profile with the same logical
     *  name (case-insensitive), then persists to {@code devices.yaml}.
     *  Synchronized (preset style) so the structural change can't race a
     *  concurrent {@link #saveDevices()} iterating the list. */
    public synchronized void putAudioDeviceProfile(AudioDeviceProfile p) {
        if (p == null || p.getName() == null || p.getName().isEmpty()) return;
        synchronized (audioDevices) {
            audioDevices.removeIf(e -> p.getName().equalsIgnoreCase(e.getName()));
            audioDevices.add(p);
        }
        saveDevices();
    }

    /** Removes the profile with logical {@code name} (case-insensitive), if
     *  present, then persists to {@code devices.yaml}. */
    public synchronized void removeAudioDeviceProfile(String name) {
        if (name == null) return;
        boolean removed;
        synchronized (audioDevices) {
            removed = audioDevices.removeIf(e -> name.equalsIgnoreCase(e.getName()));
        }
        if (removed) saveDevices();
    }

    /** Resolves a live device name to its owning card by the unified
     *  recognition-and-binding rule: a card matches when any of its
     *  {@link AudioDeviceProfile#getMatch match} entries is a case-insensitive
     *  substring of {@code deviceName}, and on overlap between cards the card with
     *  the LONGEST matching entry wins (most specific).  Returns {@code null} when
     *  no card matches.  Direction-independent — the caller reads whichever
     *  endpoint block it needs (an empty endpoint contributes no ranges). */
    public AudioDeviceProfile resolveDeviceProfile(String deviceName) {
        if (deviceName == null) return null;
        AudioDeviceProfile best = null;
        int bestStrength = -1;
        synchronized (audioDevices) {
            for (AudioDeviceProfile p : audioDevices) {
                int strength = p.matchStrength(deviceName);
                if (strength > bestStrength) {
                    best = p;
                    bestStrength = strength;
                }
            }
        }
        return best;
    }

    /**
     * Strips the backend-specific wrappers a driver puts around the bare card
     * name, so the same physical card matches across backends.  Removes the
     * WASAPI role wrappers {@code Line (X)} / {@code Speakers (X)} /
     * {@code Microphone (X)} / {@code Headphones (X)} down to {@code X}, drops an
     * ALSA {@code [plughw:x,y]} suffix, and trims.  The case is preserved in the
     * returned string (matching against it is case-insensitive).
     *
     * <p>Used only to derive the logical NAME of a freshly created card from the
     * bare device name; live recognition/binding is the substring rule in
     * {@link #resolveDeviceProfile} and does not normalise.
     */
    public String normalizeDeviceName(String rawName) {
        if (rawName == null) return null;
        String s = rawName.trim();
        // ALSA "[plughw:1,0]" / "[hw:1,0]" suffix.
        int bracket = s.indexOf('[');
        if (bracket > 0) s = s.substring(0, bracket).trim();
        // WASAPI role wrapper "Role (Card name)" → "Card name".
        s = unwrapRole(s);
        return s.trim();
    }

    /** Removes a leading WASAPI role wrapper — {@code Line (X)} /
     *  {@code Speakers (X)} / {@code Microphone (X)} / {@code Headphones (X)} —
     *  returning the inner {@code X}; returns {@code s} unchanged when it is not
     *  a recognised wrapper. */
    private String unwrapRole(String s) {
        if (!s.endsWith(")")) return s;
        int open = s.indexOf(" (");
        if (open <= 0) return s;
        String role = s.substring(0, open).trim().toLowerCase(Locale.ROOT);
        switch (role) {
            case "line":
            case "speakers":
            case "microphone":
            case "headphones":
                return s.substring(open + 2, s.length() - 1).trim();
            default:
                return s;
        }
    }

    /** True when {@code list} already carries {@code value} (case-insensitive) —
     *  the dedupe test the {@code match} union / alias absorption share. */
    private boolean containsIgnoreCase(List<String> list, String value) {
        if (value == null) return false;
        for (String s : list) {
            if (value.equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    /** Resolves the input device name to its owning card's per-channel
     *  active-range full-scales and pushes them through
     *  {@link #setAdcFsVoltageRms} / {@link #setAdcFsVoltageRmsRight}.  MONO: the
     *  single physical channel's {@code fsLeft} fills BOTH scalars.  LINKED: left
     *  and right from the SAME active row's {@code fsLeft} / {@code fsRight}.
     *  INDEPENDENT: left from the {@code activeRange} row's {@code fsLeft}, right
     *  from the {@code activeRangeRight} row's {@code fsRight} (a dangling right
     *  label falls back to the {@code activeRange} row's {@code fsRight}).  A no-op
     *  for BOTH channels (legacy global scalars stand) when the name is unbound or
     *  the profile has no usable range row. */
    public void applyInputDeviceProfile(String deviceName) {
        AudioDeviceProfile p = resolveDeviceProfile(deviceName);
        if (p == null) {
            logProfileUnresolved(true, deviceName);
            return;
        }
        DeviceEndpointConfig ep = p.getInput();
        DeviceRange rowLeft = activeRange(ep, Channel.L);
        if (rowLeft == null) {
            logProfileUnresolved(true, deviceName);
            return;
        }
        double fsLeft = rowLeft.getFsLeft();
        double fsRight;
        switch (ep.getChannels()) {
            case INDEPENDENT: fsRight = activeRange(ep, Channel.R).getFsRight(); break;
            case LINKED:      fsRight = rowLeft.getFsRight();                    break;
            default:          fsRight = fsLeft;                                  break;   // MONO: one value both sides
        }
        logProfileResolved(true, deviceName, p, rowLeft, fsLeft);
        setAdcFsVoltageRms(fsLeft);
        setAdcFsVoltageRmsRight(fsRight);
    }

    /** Resolves the output device name to its owning card's per-channel
     *  active-range full-scales (stored RMS) and pushes the
     *  peak-amplitude form ({@code × √2}) through {@link #setDacFsVoltageAmpl} /
     *  {@link #setDacFsVoltageAmplRight}.  MONO: the single physical channel's
     *  {@code fsLeft} fills BOTH scalars.  LINKED: left and right from the SAME
     *  active row's {@code fsLeft} / {@code fsRight}.  INDEPENDENT: left from the
     *  {@code activeRange} row's {@code fsLeft}, right from the
     *  {@code activeRangeRight} row's {@code fsRight} (a dangling right label falls
     *  back to the {@code activeRange} row's {@code fsRight}).  A no-op (legacy
     *  global scalars stand) when unbound or with no usable range row. */
    public void applyOutputDeviceProfile(String deviceName) {
        AudioDeviceProfile p = resolveDeviceProfile(deviceName);
        if (p == null) {
            logProfileUnresolved(false, deviceName);
            return;
        }
        DeviceEndpointConfig ep = p.getOutput();
        DeviceRange rowLeft = activeRange(ep, Channel.L);
        if (rowLeft == null) {
            logProfileUnresolved(false, deviceName);
            return;
        }
        double fsLeft = rowLeft.getFsLeft();
        double fsRight;
        switch (ep.getChannels()) {
            case INDEPENDENT: fsRight = activeRange(ep, Channel.R).getFsRight(); break;
            case LINKED:      fsRight = rowLeft.getFsRight();                    break;
            default:          fsRight = fsLeft;                                  break;   // MONO: one value both sides
        }
        logProfileResolved(false, deviceName, p, rowLeft, fsLeft);
        setDacFsVoltageAmpl(fsLeft * Constants.SQRT2);
        setDacFsVoltageAmplRight(fsRight * Constants.SQRT2);
    }

    /** Writes {@code fsVrms} as the current input device's ADC calibration:
     *  resolves the bound card, auto-creating one (logical name = the normalised
     *  device name, the exact device name appended to {@code match}, one
     *  {@code "default"} LINKED range row) when the device is unbound.  Stores the
     *  value into the active row (both channels, LINKED), ensures the active range
     *  is selected, then applies it to BOTH scalars ({@link #setAdcFsVoltageRms} /
     *  {@link #setAdcFsVoltageRmsRight}) and persists. */
    public synchronized void storeAdcCalibration(double fsVrms) {
        String deviceName = current().getInputDeviceName();
        AudioDeviceProfile p = resolveDeviceProfile(deviceName);
        if (p == null) {
            p = seedProfileFor(true, deviceName);
        }
        if (p.getInput().isCalibrationFromDevice()) {
            warnDeviceProvidedCalibration(p);
            return;
        }
        writeActiveRangeFs(p.getInput(), fsVrms);
        putAudioDeviceProfile(p);   // add/replace + save()
        setAdcFsVoltageRms(fsVrms);
        setAdcFsVoltageRmsRight(fsVrms);
    }

    /** Per-channel ADC calibrate for backend + current input device.  On a bound
     *  stereo card (LINKED or INDEPENDENT) it writes ONLY {@code ch}'s active-range
     *  field ({@code fsLeft} of the {@code activeRange} row for LEFT; {@code fsRight}
     *  of the {@code activeRangeRight} row for INDEPENDENT RIGHT, or the shared
     *  active row's {@code fsRight} for LINKED RIGHT) and applies only that channel's
     *  scalar.  On a MONO card it behaves like {@link #storeAdcCalibration(double)}
     *  for either channel (both row fields + both scalars move together). */
    public synchronized void storeAdcCalibration(Channel ch, double fsVrms) {
        String deviceName = current().getInputDeviceName();
        AudioDeviceProfile p = resolveDeviceProfile(deviceName);
        if (p == null) {
            p = seedProfileFor(true, deviceName);
        }
        DeviceEndpointConfig ep = p.getInput();
        if (ep.isCalibrationFromDevice()) {
            warnDeviceProvidedCalibration(p);
            return;
        }
        boolean mono = ep.getChannels() == DeviceChannelMode.MONO;
        writeActiveRangeFs(ep, ch, fsVrms);
        putAudioDeviceProfile(p);   // add/replace + save()
        if (mono) {
            // MONO card: one physical channel — both scalars move together.
            setAdcFsVoltageRms(fsVrms);
            setAdcFsVoltageRmsRight(fsVrms);
        } else if (ch == Channel.R) {
            setAdcFsVoltageRmsRight(fsVrms);
        } else {
            setAdcFsVoltageRms(fsVrms);
        }
    }

    /** Writes {@code fsAmpl} (peak amplitude) as the current backend + current
     *  output device's DAC calibration: like {@link #storeAdcCalibration(double)}
     *  but on the output endpoint, storing the RMS form ({@code ÷ √2}) on disk for
     *  BOTH channels (the MONO / LINKED-mirror / legacy write), then applying the
     *  amplitude to both scalars via {@link #setDacFsVoltageAmpl} /
     *  {@link #setDacFsVoltageAmplRight}. */
    public synchronized void storeDacCalibration(double fsAmpl) {
        String deviceName = current().getOutputDeviceName();
        AudioDeviceProfile p = resolveDeviceProfile(deviceName);
        if (p == null) {
            p = seedProfileFor(false, deviceName);
        }
        if (p.getOutput().isCalibrationFromDevice()) {
            warnDeviceProvidedCalibration(p);
            return;
        }
        writeActiveRangeFs(p.getOutput(), fsAmpl / Constants.SQRT2);
        putAudioDeviceProfile(p);   // add/replace + save()
        setDacFsVoltageAmpl(fsAmpl);
        setDacFsVoltageAmplRight(fsAmpl);
    }

    /** Per-channel DAC calibrate for backend + current output device — the mirror
     *  of {@link #storeAdcCalibration(Channel, double)} on the output endpoint.  On
     *  a bound stereo card (LINKED or INDEPENDENT) it writes ONLY {@code ch}'s
     *  active-range field (the RMS form {@code fsAmpl / √2}: {@code fsLeft} of the
     *  {@code activeRange} row for LEFT; {@code fsRight} of the
     *  {@code activeRangeRight} row for INDEPENDENT RIGHT, or the shared active row's
     *  {@code fsRight} for LINKED RIGHT) and applies only that channel's scalar.  On
     *  a MONO card it behaves like {@link #storeDacCalibration(double)} for either
     *  channel (both row fields + both scalars move together). */
    public synchronized void storeDacCalibration(Channel ch, double fsAmpl) {
        String deviceName = current().getOutputDeviceName();
        AudioDeviceProfile p = resolveDeviceProfile(deviceName);
        if (p == null) {
            p = seedProfileFor(false, deviceName);
        }
        DeviceEndpointConfig ep = p.getOutput();
        if (ep.isCalibrationFromDevice()) {
            warnDeviceProvidedCalibration(p);
            return;
        }
        boolean mono = ep.getChannels() == DeviceChannelMode.MONO;
        writeActiveRangeFs(ep, ch, fsAmpl / Constants.SQRT2);
        putAudioDeviceProfile(p);   // add/replace + save()
        if (mono) {
            // MONO card: one physical channel — both scalars move together.
            setDacFsVoltageAmpl(fsAmpl);
            setDacFsVoltageAmplRight(fsAmpl);
        } else if (ch == Channel.R) {
            setDacFsVoltageAmplRight(fsAmpl);
        } else {
            setDacFsVoltageAmpl(fsAmpl);
        }
    }

    /** Guarded WARN when a calibrate write targets a device-provided endpoint (a
     *  QA40x whose calibration is loaded from the device): the store rejects the
     *  write and leaves both the range values and the global scalars untouched. */
    private void warnDeviceProvidedCalibration(AudioDeviceProfile p) {
        if (log.isWarnEnabled()) {
            log.warn("Ignoring calibration write — calibration is device-provided for {}", p.getName());
        }
    }

    /** True when the active input device resolves to a card whose ADC full-scale
     *  is provided by the device itself (a QA40x) — its calibration is read-only,
     *  so the calibrate dialog opens view-only. */
    public synchronized boolean isAdcCalibrationFromDevice() {
        AudioDeviceProfile p = resolveDeviceProfile(current().getInputDeviceName());
        return p != null && p.getInput().isCalibrationFromDevice();
    }

    /** True when the active output device resolves to a card whose DAC full-scale
     *  is provided by the device itself (a QA40x) — its calibration is read-only,
     *  so the calibrate dialog opens view-only. */
    public synchronized boolean isDacCalibrationFromDevice() {
        AudioDeviceProfile p = resolveDeviceProfile(current().getOutputDeviceName());
        return p != null && p.getOutput().isCalibrationFromDevice();
    }

    /** Resolves the card for a first calibrate on an UNBOUND device: a card whose
     *  {@code match} entries already recognise {@code deviceName} (via
     *  {@link #resolveDeviceProfile}) is BOUND — the device name is
     *  {@link AudioDeviceProfile#bindDeviceName appended} to {@code match} when
     *  nothing already matches, and the card keeps its ranges; nothing recognised
     *  falls back to a fresh bare {@code "default"}-row card.  Returns the LIVE
     *  store object for a recognised card so the caller's range write + re-put
     *  updates that one card in place.  In both cases the caller then writes the
     *  measured full-scale into the active range row. */
    private AudioDeviceProfile seedProfileFor(boolean input, String deviceName) {
        AudioDeviceProfile recognised = resolveDeviceProfile(deviceName);
        if (recognised != null) {
            if (recognised.bindDeviceName(deviceName) && log.isDebugEnabled()) {
                log.debug("device '{}' recognised as card '{}' -> device name bound",
                        deviceName, recognised.getName());
            }
            return recognised;
        }
        return createProfileFor(input, deviceName);
    }

    /** Builds a fresh single-row LINKED card bound to the given direction: logical
     *  name = the normalised device name, the exact device name as the sole
     *  {@code match} entry, one {@code "default"} range row selected as active. */
    private AudioDeviceProfile createProfileFor(boolean input, String deviceName) {
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName(normalizeDeviceName(deviceName));
        if (deviceName != null) p.getMatch().add(deviceName);
        DeviceRange row = new DeviceRange();
        row.setLabel("default");
        DeviceEndpointConfig ep = input ? p.getInput() : p.getOutput();
        ep.setChannels(DeviceChannelMode.LINKED);
        ep.getRanges().add(row);
        ep.setActiveRange("default");
        return p;
    }

    /** Returns the active range row for {@code ch}.  LEFT (and any MONO / LINKED
     *  endpoint) uses {@link DeviceEndpointConfig#getActiveRange}; RIGHT on an
     *  INDEPENDENT endpoint uses {@link DeviceEndpointConfig#getActiveRangeRight},
     *  falling back to the {@code activeRange} row when the right label is unset or
     *  dangles.  Both fall back to the first row, or {@code null} when the table
     *  is empty. */
    private DeviceRange activeRange(DeviceEndpointConfig ep, Channel ch) {
        List<DeviceRange> rows = ep.getRanges();
        if (rows == null || rows.isEmpty()) return null;
        String label = ep.getActiveRange();
        if (ch == Channel.R && ep.getChannels() == DeviceChannelMode.INDEPENDENT
                && ep.getActiveRangeRight() != null) {
            for (DeviceRange r : rows) {
                if (ep.getActiveRangeRight().equals(r.getLabel())) return r;
            }
            // Dangling right label → fall back to the activeRange (left) row below.
        }
        if (label != null) {
            for (DeviceRange r : rows) {
                if (label.equals(r.getLabel())) return r;
            }
        }
        return rows.get(0);
    }

    /** Writes {@code fs} into the endpoint's active row for BOTH channels (the
     *  LINKED / legacy write) — the DAC calibrate path and the single-arg ADC
     *  calibrate use this.  Creates a {@code "default"} row and selects it when the
     *  table is empty, and selects the resolved row as active when no label was set. */
    private void writeActiveRangeFs(DeviceEndpointConfig ep, double fs) {
        DeviceRange row = seedableActiveRow(ep, Channel.L);
        row.setFsLeft(fs);
        row.setFsRight(fs);
        // A real calibration wrote this row — protect it from seed-merge refresh.
        row.setCalibrated(true);
    }

    /** Writes {@code fs} into ONLY {@code ch}'s active-range field on a bound stereo
     *  endpoint: LEFT → the {@code activeRange} row's {@code fsLeft}; RIGHT → the
     *  {@code activeRangeRight} row's {@code fsRight} for INDEPENDENT (dangling right
     *  label → the {@code activeRange} row), or the SAME active row's {@code fsRight}
     *  for LINKED (shared range switching, per-channel values).  On a MONO endpoint
     *  (one physical channel) it falls back to the legacy both-equal write so the
     *  single value lands on both row fields. */
    private void writeActiveRangeFs(DeviceEndpointConfig ep, Channel ch, double fs) {
        if (ep.getChannels() == DeviceChannelMode.MONO) {
            writeActiveRangeFs(ep, fs);
            return;
        }
        DeviceRange row = seedableActiveRow(ep, ch);
        if (ch == Channel.R) {
            row.setFsRight(fs);
        } else {
            row.setFsLeft(fs);
        }
        // A real calibration wrote this row — protect it from seed-merge refresh.
        row.setCalibrated(true);
    }

    /** Resolves {@code ch}'s active range row, seeding a {@code "default"} row (and
     *  selecting it as active) when the range table is empty — the shared setup for
     *  the two {@code writeActiveRangeFs} forms. */
    private DeviceRange seedableActiveRow(DeviceEndpointConfig ep, Channel ch) {
        DeviceRange row = activeRange(ep, ch);
        if (row == null) {
            row = new DeviceRange();
            row.setLabel("default");
            ep.getRanges().add(row);
        }
        if (ep.getActiveRange() == null) {
            ep.setActiveRange(row.getLabel());
        }
        return row;
    }

    /** Guarded debug log of a resolved profile application (plan logging point 6). */
    private void logProfileResolved(boolean input, String deviceName, AudioDeviceProfile p, DeviceRange row, double fs) {
        if (log.isDebugEnabled()) {
            log.debug("{} device '{}' -> card '{}' -> range '{}' -> FS {} Vrms",
                    input ? "input" : "output", deviceName, p.getName(), row.getLabel(), fs);
        }
    }

    /** Guarded debug log of an unresolved device (legacy global scalar stands). */
    private void logProfileUnresolved(boolean input, String deviceName) {
        if (log.isDebugEnabled()) {
            log.debug("{} device '{}' -> no profile; keeping the global full-scale",
                    input ? "input" : "output", deviceName);
        }
    }

    /** Deep-copies a profile: new match list, new endpoint configs, new range
     *  rows — shared by {@link #getAudioDeviceProfiles} and the dialog copy/apply
     *  so a mutation on one side never leaks to the other. */
    private AudioDeviceProfile copyProfile(AudioDeviceProfile src) {
        AudioDeviceProfile c = new AudioDeviceProfile();
        c.setName(src.getName());
        c.setMatch(new ArrayList<>(src.getMatch()));
        c.setInput(copyEndpoint(src.getInput()));
        c.setOutput(copyEndpoint(src.getOutput()));
        return c;
    }

    /** Deep-copies one endpoint config (new range-row objects) via
     *  {@link DeviceEndpointConfig#deepCopy()} — the state owner performs the
     *  copy. */
    private DeviceEndpointConfig copyEndpoint(DeviceEndpointConfig src) {
        return src.deepCopy();
    }

    /** Serialises one {@link FreqRespFilterTypeParams} to its YAML map — the
     *  ONE place that lists the filter-param field names for writing.  Shared
     *  by the per-type {@code freqRespFilterParamsByType} block and every
     *  {@link FreqRespPreset}'s embedded {@code filterParams}. */
    private Map<String, Object> writeFilterParams(FreqRespFilterTypeParams p) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("modeOrder",     p.isModeOrder());
        pm.put("rippleDb",      p.getRippleDb());
        pm.put("stopAttenDb",   p.getStopAttenDb());
        pm.put("centerHz",      p.getCenterHz());
        pm.put("passHz",        p.getPassHz());
        pm.put("stopHz",        p.getStopHz());
        pm.put("orderPassHz",   p.getOrderPassHz());
        pm.put("orderRippleDb", p.getOrderRippleDb());
        pm.put("order",         p.getOrder());
        pm.put("q",             p.getQ());
        return pm;
    }

    /** Deserialises one {@link FreqRespFilterTypeParams} from its YAML map,
     *  seeded with {@code type}'s pinned defaults and clamped to the valid
     *  ranges — the ONE place that lists the field names for reading.  Shared
     *  by the per-type block and every preset's embedded {@code filterParams}. */
    private FreqRespFilterTypeParams readFilterParams(FilterType type, Map<?, ?> pm) {
        FreqRespFilterTypeParams p = FreqRespFilterTypeParams.fromType(type);
        if (pm.get("modeOrder")     instanceof Boolean b) p.setModeOrder(b);
        if (pm.get("rippleDb")      instanceof Number  n) p.setRippleDb(Math.max(0.001, Math.min(20.0, n.doubleValue())));
        if (pm.get("stopAttenDb")   instanceof Number  n) p.setStopAttenDb(Math.max(0.0, Math.min(200.0, n.doubleValue())));
        if (pm.get("centerHz")      instanceof Number  n) p.setCenterHz(Math.max(0.0, n.doubleValue()));
        if (pm.get("passHz")        instanceof Number  n) p.setPassHz(Math.max(0.0, n.doubleValue()));
        if (pm.get("stopHz")        instanceof Number  n) p.setStopHz(Math.max(0.0, n.doubleValue()));
        if (pm.get("orderPassHz")   instanceof Number  n) p.setOrderPassHz(Math.max(0.0, n.doubleValue()));
        if (pm.get("orderRippleDb") instanceof Number  n) p.setOrderRippleDb(Math.max(0.001, Math.min(20.0, n.doubleValue())));
        if (pm.get("order")         instanceof Number  n) p.setOrder(Math.max(1, Math.min(32, n.intValue())));
        if (pm.get("q")             instanceof Number  n) p.setQ(Math.max(0.1, Math.min(100.0, n.doubleValue())));
        return p;
    }

    /** Emits one {@link AudioDeviceProfile} in the seed's exact style — the ONE
     *  place that lists the profile field names for writing.  The {@code match}
     *  list and endpoint blocks are omitted when empty; the legacy per-backend
     *  alias maps are never emitted (they are folded into {@code match} on read). */
    private void writeDeviceProfile(StringBuilder out, AudioDeviceProfile p) {
        out.append("  - name: ").append(plainOrQuoted(p.getName())).append('\n');
        List<String> match = p.getMatch();
        if (!match.isEmpty()) {
            out.append("    match: [ ");
            for (int i = 0; i < match.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(quoted(match.get(i)));
            }
            out.append(" ]\n");
        }
        writeEndpoint(out, "input",  p.getInput());
        writeEndpoint(out, "output", p.getOutput());
    }

    /** {@code s} as a YAML double-quoted scalar (backslash and quote escaped) —
     *  the style the seed uses for every match entry / range label / active-range
     *  label. */
    private String quoted(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** A card name in the seed's plain (unquoted) style when it is safely plain
     *  YAML — word character first, then letters / digits / spaces and a few
     *  benign punctuation marks, no trailing space — else double-quoted. */
    private String plainOrQuoted(String s) {
        return !s.endsWith(" ") && s.matches("[A-Za-z0-9][A-Za-z0-9 ()./+_-]*") ? s : quoted(s);
    }

    /** Deserialises one {@link AudioDeviceProfile} from its YAML map, or
     *  {@code null} for a garbled entry (no usable name) — the caller skips it
     *  rather than aborting the whole load.  The ONE place that lists the
     *  profile field names for reading. */
    private AudioDeviceProfile readDeviceProfile(Map<?, ?> m) {
        if (!(m.get("name") instanceof String name) || name.isEmpty()) {
            if (log.isWarnEnabled()) {
                log.warn("Skipping device profile with no usable name: {}", m.get("name"));
            }
            return null;
        }
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName(name);
        p.setMatch(readMatch(m.get("match")));
        if (m.get("input")  instanceof Map<?, ?> ie) p.setInput(readEndpoint(ie));
        if (m.get("output") instanceof Map<?, ?> oe) p.setOutput(readEndpoint(oe));
        return p;
    }

    /** The {@code match} entries as a fresh string list, dropping non-string /
     *  empty items; an empty list when the field is absent or not a list. */
    private List<String> readMatch(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /** Emits one endpoint block in the seed's exact style; an endpoint with no
     *  ranges is omitted entirely.  {@code activeRange} is ONE key in both shapes
     *  (see {@link #writeActiveRange}); the legacy {@code activeRangeRight} key is
     *  never written again.  The ONE place that lists the endpoint field names for
     *  writing. */
    private void writeEndpoint(StringBuilder out, String key, DeviceEndpointConfig ep) {
        if (ep == null || ep.getRanges().isEmpty()) return;
        out.append("    ").append(key).append(":\n");
        out.append("      channels: ").append(ep.getChannels().name()).append('\n');
        // Emitted ONLY when set — a device-provided endpoint (QA40x); an ordinary
        // endpoint stays clean, mirroring the range-level calibrated flag.
        if (ep.isCalibrationFromDevice()) out.append("      calibrationFromDevice: true\n");
        out.append("      ranges:\n");
        for (DeviceRange r : ep.getRanges()) writeDeviceRange(out, r);
        if (ep.getActiveRange() != null) {
            out.append("      activeRange: ").append(writeActiveRange(ep)).append('\n');
        }
    }

    /** The {@code activeRange} VALUE for one endpoint: an INDEPENDENT endpoint
     *  writes the ONE inline {@code { left: …, right: … }} flow map (each channel's
     *  own selected row label, double-quoted; an unset right selection mirrors the
     *  left label, matching the resolver's fallback) — replacing the legacy split
     *  {@code activeRange} + {@code activeRangeRight} key pair on disk.  LINKED and
     *  MONO keep the scalar (quoted) row label.  Only called with a non-null
     *  {@code activeRange}. */
    private String writeActiveRange(DeviceEndpointConfig ep) {
        if (ep.getChannels() != DeviceChannelMode.INDEPENDENT) return quoted(ep.getActiveRange());
        String right = ep.getActiveRangeRight() != null ? ep.getActiveRangeRight() : ep.getActiveRange();
        return "{ left: " + quoted(ep.getActiveRange()) + ", right: " + quoted(right) + " }";
    }

    /** Deserialises one {@link DeviceEndpointConfig} (channel mode via
     *  {@link #enumOr}, defaulting {@link DeviceChannelMode#LINKED}) — the ONE
     *  place that reads the endpoint YAML vocabulary, shared by the on-disk
     *  profiles and the bundled devices.yaml seed alike (they use one format).
     *  {@code activeRange} is accepted in both current forms: the
     *  {@code {left, right}} map (INDEPENDENT) and the scalar label (LINKED / MONO). */
    private DeviceEndpointConfig readEndpoint(Map<?, ?> m) {
        DeviceEndpointConfig ep = new DeviceEndpointConfig();
        if (m.get("channels") instanceof String s) {
            ep.setChannels(enumOr(DeviceChannelMode.class, s, DeviceChannelMode.LINKED));
        }
        // Tolerant: absent key means an ordinary (user-calibratable) endpoint.
        if (m.get("calibrationFromDevice") instanceof Boolean b) ep.setCalibrationFromDevice(b);
        if (m.get("ranges") instanceof List<?> raw) {
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> rm)) continue;
                DeviceRange r = readDeviceRange(rm);
                if (r != null) ep.getRanges().add(r);
            }
        }
        Object active = m.get("activeRange");
        if (active instanceof String s) {
            ep.setActiveRange(s);
        } else if (active instanceof Map<?, ?> am) {
            if (am.get("left")  instanceof String s) ep.setActiveRange(s);
            if (am.get("right") instanceof String s) ep.setActiveRangeRight(s);
        }
        return ep;
    }

    /** Emits one {@link DeviceRange} as ONE line in the seed's exact style —
     *  {@code - { label: "X", fsVrms: { left: A, right: B } }} — with {@code fsVrms}
     *  ALWAYS the {@code { left, right }} pair form (never a scalar) and the label
     *  double-quoted.  The ONE place that lists the range field names for writing.
     *  The reader still accepts a scalar {@code fsVrms} for backward
     *  compatibility. */
    private void writeDeviceRange(StringBuilder out, DeviceRange r) {
        out.append("        - { label: ").append(quoted(r.getLabel()))
           .append(", fsVrms: { left: ").append(r.getFsLeft())
           .append(", right: ").append(r.getFsRight()).append(" }");
        // Emitted ONLY when set by a real calibration — a nominal (seed) row stays clean.
        if (r.isCalibrated()) out.append(", calibrated: true");
        out.append(" }\n");
    }

    /** Deserialises one {@link DeviceRange}, or {@code null} for a garbled row
     *  (no label) — the ONE place that lists the range field names for reading.
     *  Accepts both the scalar {@code fsVrms} shorthand and the
     *  {@code {left, right}} map form. */
    private DeviceRange readDeviceRange(Map<?, ?> m) {
        if (!(m.get("label") instanceof String label)) {
            if (log.isWarnEnabled()) log.warn("Skipping device range with no label: {}", m);
            return null;
        }
        DeviceRange r = new DeviceRange();
        r.setLabel(label);
        Object fs = m.get("fsVrms");
        if (fs instanceof Number n) {
            r.setFsLeft(n.doubleValue());
            r.setFsRight(n.doubleValue());
        } else if (fs instanceof Map<?, ?> fm) {
            if (fm.get("left")  instanceof Number n) r.setFsLeft(n.doubleValue());
            if (fm.get("right") instanceof Number n) r.setFsRight(n.doubleValue());
        }
        // Tolerant: absent key means an uncalibrated (nominal) row.
        if (m.get("calibrated") instanceof Boolean b) r.setCalibrated(b);
        return r;
    }

}
