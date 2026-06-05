package org.edgo.audio.measure.gui.generator;

import org.edgo.audio.measure.sound.AudioBackend;
import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.eclipse.swt.widgets.Display;

import java.io.File;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.edgo.audio.measure.enums.AmplitudeUnit;
import org.edgo.audio.measure.enums.GenChangeCause;

/**
 * Generator pane — UI mirror of the CLI generator.  Hosts:
 * <ul>
 *   <li>Signal-form combo (with 24×24 pictogram per entry)</li>
 *   <li>Frequency input — wheel ±5 %, arrows ±1 Hz, disabled for noise forms</li>
 *   <li>Amplitude input — accepts {@code mV}, {@code V}, {@code dBV},
 *       {@code dBFS} (case-insensitive); wheel ±5 %, arrows ±1 unit step</li>
 *   <li>Dither bits combo — {@code Off} shown instead of 0</li>
 *   <li>Harmonics-CSV picker — Text field + Browse (…) + Reset (×) buttons</li>
 *   <li>Play button — runs the {@link org.edgo.audio.measure.generator.SignalGenerator}
 *       on a background thread when clicked</li>
 * </ul>
 *
 * <p>State persists across launches via {@link Preferences}.  The pane
 * isn't wired to {@code SignalGenerator} yet — clicking Play currently
 * just snapshots the current settings into Preferences and logs them.
 */
@Log4j2
public final class GeneratorPane {

    /**
     * Resolves the set of dither bit options for the given output bit
     * depth: every integer from 0 to {@code outputBitDepth} inclusive.
     * 0 is rendered as "Off" in the combo; values above the DAC's
     * resolution would have no effect and are dropped.
     */
    private int[] ditherBitsFor(int outputBitDepth) {
        int cap = Math.max(0, outputBitDepth);
        int[] out = new int[cap + 1];
        for (int i = 0; i <= cap; i++) out[i] = i;
        return out;
    }

    /** Current dither values shown in the combo (rebuilt when output bit depth changes). */
    private int[] ditherBits;

    private final Composite       group;
    private PaneTitle             title;
    private final SignalFormCombo formCombo;
    private final Label           freqLabel;
    private final NumericStepField freqField;
    private final Button          fftSnapBtn;
    private final NumericStepField ampField;
    private final NumericStepField dutyField;
    private final Label           dutyLabel;
    /** Container for sweep-only controls (start/stop freq, duration, fade
     *  in/out, loop).  Shown when the selected form is LINEAR_SWEEP or
     *  LOG_SWEEP; hidden + excluded otherwise so the layout reflows. */
    private Composite             sweepPanel;
    private Label                 sweepLabel;
    private NumericStepField      sweepStartField;
    private NumericStepField      sweepEndField;
    private NumericStepField      sweepDurationField;
    private NumericStepField      sweepFadeInField;
    private NumericStepField      sweepFadeOutField;
    private Button                sweepLoopBtn;
    /** Dual-tone parameter block — single column, labels stacked above
     *  fields, in the user-requested order:
     *  Freq 1, Freq 2, snap-to-FFT-bin, Freq 1 amplitude %, Freq 2
     *  amplitude %.  amp1 + amp2 is constrained to 100 % so the two
     *  amplitude fields are coupled (editing one updates the other).
     *  Same show/hide pattern as the sweep panel: visible only when
     *  the selected form is {@link GenSignalForm#DUAL_TONE}. */
    private Composite             dualTonePanel;
    private NumericStepField      dualToneFreq1Field;
    private NumericStepField      dualToneFreq2Field;
    private NumericStepField      dualToneAmp1Field;
    private NumericStepField      dualToneAmp2Field;
    /** Freq 1 / Freq 2 labels inside the dual-tone panel — kept as
     *  fields so {@link #updateDualToneFreqLabels} can append the
     *  snap-corrected frequency in brackets when snap-to-FFT-bin is
     *  active.  Mirrors how {@link #updateFreqLabel} annotates the
     *  single-tone Frequency label. */
    private Label                 dualToneFreq1Label;
    private Label                 dualToneFreq2Label;
    private final Combo           ditherCombo;
    private final Text            correctionsField;
    private final NumericStepField durationField;
    private final Text            wavPathField;
    private final Text            playFromPathField;
    private final Button          playFromBtn;
    private final Button          playFromLoopBtn;
    /** Pixel height of the main play button at the bottom of the pane. */
    private static final int PLAY_LED_SIZE       = 33;
    /** Pixel height of the small play button in the Load-from row. */
    private static final int TINY_LED_SIZE       = 16;
    /** Pixel height of the floppy / folder glyphs that sit next to the
     *  file-path text fields. */
    private static final int FILE_ICON_HEIGHT    = 16;

    private final Image           tinyPlayDimImg;
    private final Image           tinyPlayLitImg;
    private final Image           playDimImg;
    private final Image           playLitImg;
    private final Image           floppyDiskIcon;
    private final Image           folderOpenIcon;
    private final Image           calibrateDacIcon;
    private final FilePlayController filePlayer = new FilePlayController();
    private final Button          playBtn;

    private final GeneratorController controller = new GeneratorController();

    private AmplitudeUnit         currentUnit;

    /** "ON AIR" banner LED + label at the top right of the pane.
     *  When active: LED is always solid #FF0000, the "ON AIR" text blinks
     *  between #AA0000 and #FF0000 at ~1 Hz.  When idle: both LED and
     *  text are grey.  All blink state lives on the SWT thread — no
     *  synchronisation needed. */
    private Canvas  onAirLed;
    private Label   onAirLabel;
    private Color   onAirRedColor;
    private Color   onAirRedDimColor;
    private Color   onAirGreyColor;
    private Font    onAirFont;
    private boolean onAirActive;
    private boolean onAirBlinkOn;
    /** Handler for {@link Events#FFT_LENGTH_CHANGED}.  Held as a field
     *  so the dispose listener can unsubscribe it from the bus — without
     *  that, the bus would keep this pane alive forever. */
    private Consumer<Void> fftLengthListener;
    /** Handler for {@link Events#FILE_PLAY_STOPPED}.  Fires on the
     *  play thread when the file player ends (user stop, EOF without
     *  loop, or playback error) — resets the play-from LED on the UI
     *  thread.  Held as a field so the dispose listener can unsubscribe. */
    private Consumer<Void> filePlayStoppedListener;
    /** Handler for {@link Events#FREQRESP_MEASUREMENT_STARTED} — stops
     *  the running generator (DDS tone + file player) and disables both
     *  play buttons so the FreqResp sweep can drive the DAC exclusively. */
    private Consumer<Void> freqRespStartedListener;
    /** Counterpart that re-enables both play buttons after the sweep. */
    private Consumer<Void> freqRespStoppedListener;
    /** Handler for {@link Events#GENERATOR_FREQ_TRIM} — sub-Hz frequency
     *  alignment from the FFT-side frequency-lock loop.  Live-applies
     *  the new freq to the DDS and republishes
     *  {@link Events#GENERATOR_SIGNAL_CHANGED} with cause
     *  {@link GenChangeCause#FLL_TRIM} so the FFT worker keeps its
     *  averaging accumulator alive (a USER_INPUT republish would
     *  trash it). */
    private Consumer<Double> freqTrimListener;
    /** Handler for {@link Events#GENERATOR_FREQ_TRIM_2} — the
     *  dual-tone second-tone variant of {@link #freqTrimListener}.
     *  Same FLL_TRIM republish so the FFT worker's averaging
     *  accumulator survives the per-tone correction. */
    private Consumer<Double> freqTrim2Listener;

    public GeneratorPane(Composite parent) {
        // Seed the tracked pane width from prefs BEFORE the host
        // SashForm's first layout pass — the controlListener that picks
        // up paneWidthPx fires immediately after construction and we
        // want it to honour the saved value instead of falling back to
        // the SashForm's construction-default weights.
        int savedWidth = Preferences.instance().getGenPaneWidth();
        if (savedWidth >= MIN_WIDTH_PX) this.paneWidthPx = savedWidth;

        IconUtils icons = IconUtils.instance();
        Display d = parent.getDisplay();
        RGB greenDim = new RGB(0x00, 0xAA, 0x00);
        RGB greenLit = new RGB(0x00, 0xFF, 0x00);
        this.playDimImg     = icons.renderAtHeight(d, SvgPaths.PLAY, PLAY_LED_SIZE,  greenDim);
        this.playLitImg     = icons.renderAtHeight(d, SvgPaths.PLAY, PLAY_LED_SIZE,  greenLit);
        this.tinyPlayDimImg = icons.renderAtHeight(d, SvgPaths.PLAY, TINY_LED_SIZE,  greenDim);
        this.tinyPlayLitImg = icons.renderAtHeight(d, SvgPaths.PLAY, TINY_LED_SIZE,  greenLit);
        this.floppyDiskIcon = icons.renderAtHeight(d, SvgPaths.FLOPPY_DISK, FILE_ICON_HEIGHT, null);
        this.folderOpenIcon = icons.renderAtHeight(d, SvgPaths.FOLDER_OPEN, FILE_ICON_HEIGHT, null);
        this.calibrateDacIcon = icons.renderAtHeight(d, SvgPaths.CROSSHAIR, PLAY_LED_SIZE, null);

        // Composite + SWT.BORDER replaces the legacy Group widget — the
        // Group's GtkFrame label widget on GTK consumes title-bar mouse
        // clicks, breaking the collapse-on-title-click UX.  Visual frame
        // is preserved via SWT.BORDER.
        group = new Composite(parent, SWT.BORDER);
        GridLayout gl = new GridLayout(1, false);
        // 2 px padding on all four sides so the content doesn't touch the
        // SWT.BORDER.  verticalSpacing = 2 gives the same 2 px gap between
        // the title Label and the first form row (and between subsequent
        // form rows — those Composites carry their own internal padding).
        gl.marginWidth  = 2;
        gl.marginHeight = 2;
        gl.verticalSpacing = 2;
        group.setLayout(gl);
        title = new PaneTitle(group, Events.PANE_ID_GENERATOR,
                I18n.t("generator.title.expanded"),
                I18n.t("generator.title.collapsed"),
                I18n.t("generator.pane.toggle.tooltip"));

        Preferences prefs = Preferences.instance();

        // -------------------------------------------------------------- Form
        // Signal-form caption shares its row with the right-anchored on-air
        // banner.  LED blinks at ~1 Hz and the "ON AIR" text brightens to
        // red while the generator DDS or the file player is driving the
        // output device; both fade to grey when nothing is playing.
        // See {@link #startOnAirBlink} / {@link #stopOnAirBlink}.
        Composite formAndAirRow = new Composite(group, SWT.NONE);
        GridLayout farGl = new GridLayout(3, false);  // label | LED | "ON AIR"
        farGl.marginWidth  = 0;
        farGl.marginHeight = 0;
        farGl.horizontalSpacing = 6;
        formAndAirRow.setLayout(farGl);
        formAndAirRow.setLayoutData(fillH());

        Label formLabel = new Label(formAndAirRow, SWT.NONE);
        formLabel.setText(I18n.t("generator.signalForm"));
        formLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));

        onAirRedColor    = new Color(group.getDisplay(), 0xFF, 0x00, 0x00);
        onAirRedDimColor = new Color(group.getDisplay(), 0xAA, 0x00, 0x00);
        onAirGreyColor   = new Color(group.getDisplay(), 120, 120, 120);

        onAirLed = new Canvas(formAndAirRow, SWT.NONE);
        GridData ledGd = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        ledGd.widthHint  = 14;
        ledGd.heightHint = 14;
        onAirLed.setLayoutData(ledGd);
        onAirLed.addListener(SWT.Paint, this::paintOnAirLed);

        onAirLabel = new Label(formAndAirRow, SWT.NONE);
        onAirLabel.setText(I18n.t("generator.onair"));
        FontData fd0 = onAirLabel.getFont().getFontData()[0];
        onAirFont = new Font(group.getDisplay(),
                fd0.getName(), fd0.getHeight(), SWT.BOLD);
        onAirLabel.setFont(onAirFont);
        onAirLabel.setForeground(onAirGreyColor);
        onAirLabel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        GenSignalForm initialForm = prefs.getGenSignalForm();
        formCombo = new SignalFormCombo(group, initialForm);
        formCombo.setLayoutData(fillH());
        formCombo.setToolTipText(I18n.t("generator.signalForm.tooltip"));
        // Listener wired below after freqField is declared, since the form
        // change toggles the frequency field's enabled state.

        // --------------------------------------------------------- Frequency
        // Label is a field (not addRowLabel) so we can append the DDS- or
        // FFT-bin-corrected frequency in brackets as the user changes
        // form / freq.  See updateFreqLabel().
        freqLabel = new Label(group, SWT.NONE);
        freqLabel.setText(I18n.t("generator.frequency"));
        freqLabel.setLayoutData(fillH());
        freqField = new NumericStepField(group,
                Math.max(0.01, prefs.getGenFrequencyHz()),
                this::parseFrequency,
                this::formatFrequency,
                /* wheel:  ±5 % */ (v, dir) -> v * (1.0 + 0.05 * dir),
                /* arrows: ±1 Hz */ (v, dir) -> Math.max(0.0, v + dir),
                /* width: */ 160);
        freqField.setLayoutData(fillH());
        freqField.setToolTipText(I18n.t("generator.frequency.tooltip"));
        freqField.setEnabled(isPeriodic(initialForm));
        freqField.addSelectionListener(e -> {
            // Persist the EFFECTIVE (snapped if applicable) frequency
            // — GeneratorController.start() reads this pref at start
            // time, so writing the raw value here was making the first
            // emission un-snapped even when snap was enabled.  All
            // other forms / unchecked snap pass the entered value
            // through unchanged.
            persistRawFrequency();
            // Freq change shifts the rectangle period (samples per cycle),
            // which moves both the corrected freq AND the duty grid.
            updateFreqLabel();
            updateDutyLabel();
        });

        // ----- Dual-tone Freq 1 / Freq 2 rows.  Live in the outer
        // group (not inside dualTonePanel) so the snap-to-FFT-bin
        // checkbox keeps its position regardless of waveform —
        // visibility flips between the regular Frequency row above
        // and these two rows.  Labels are stacked above their fields
        // (horizontalSpan = 2) per the dual-tone styling.
        dualToneFreq1Label = new Label(group, SWT.NONE);
        dualToneFreq1Label.setText(I18n.t("generator.dualTone.freq1"));
        GridData dt1lGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dt1lGd.horizontalSpan = 2;
        dualToneFreq1Label.setLayoutData(dt1lGd);
        dualToneFreq1Field = new NumericStepField(group,
                Math.max(0.01, prefs.getGenDualToneFreq1Hz()),
                this::parseFrequency,
                this::formatFrequency,
                (v, dir) -> v * (1.0 + 0.05 * dir),
                (v, dir) -> Math.max(0.0, v + dir),
                160);
        GridData dt1fGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dt1fGd.horizontalSpan = 2;
        dualToneFreq1Field.setLayoutData(dt1fGd);
        dualToneFreq1Field.setToolTipText(I18n.t("generator.dualTone.freq1.tooltip"));
        dualToneFreq1Field.addSelectionListener(e -> {
            prefs.setGenDualToneFreq1Hz(dualToneFreq1Field.getValue());
            prefs.save();
            int sr = currentOutputSampleRate();
            double f1 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr,
                    dualToneFreq1Field.getValue());
            controller.setFrequency(f1);
            updateDualToneFreqLabels();
            updateFreqLabel();
            // Tone 1 moved — invalidate the FFT average / FLL.
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        dualToneFreq2Label = new Label(group, SWT.NONE);
        dualToneFreq2Label.setText(I18n.t("generator.dualTone.freq2"));
        GridData dt2lGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dt2lGd.horizontalSpan = 2;
        dualToneFreq2Label.setLayoutData(dt2lGd);
        dualToneFreq2Field = new NumericStepField(group,
                Math.max(0.01, prefs.getGenDualToneFreq2Hz()),
                this::parseFrequency,
                this::formatFrequency,
                (v, dir) -> v * (1.0 + 0.05 * dir),
                (v, dir) -> Math.max(0.0, v + dir),
                160);
        GridData dt2fGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dt2fGd.horizontalSpan = 2;
        dualToneFreq2Field.setLayoutData(dt2fGd);
        dualToneFreq2Field.setToolTipText(I18n.t("generator.dualTone.freq2.tooltip"));
        dualToneFreq2Field.addSelectionListener(e -> {
            prefs.setGenDualToneFreq2Hz(dualToneFreq2Field.getValue());
            prefs.save();
            int sr = currentOutputSampleRate();
            double f2 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr,
                    dualToneFreq2Field.getValue());
            controller.setDualToneFrequency2(f2);
            updateDualToneFreqLabels();
            updateFreqLabel();
            // Tone 2 moved — invalidate the FFT average / FLL.
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        // ------- FFT-bin snap checkbox.  Persisted, and live-applied:
        // toggling on snaps the running sine to the nearest bin of the
        // FFT pane's current fftLength; toggling off restores the
        // entered frequency.
        fftSnapBtn = new Button(group, SWT.CHECK);
        fftSnapBtn.setText(I18n.t("generator.snapFft"));
        fftSnapBtn.setToolTipText(I18n.t("generator.snapFft.tooltip"));
        fftSnapBtn.setLayoutData(fillH());
        fftSnapBtn.setSelection(prefs.isGenSnapToFftBin());
        fftSnapBtn.addListener(SWT.Selection, e -> {
            prefs.setGenSnapToFftBin(fftSnapBtn.getSelection());
            persistRawFrequency();
            updateFreqLabel();
            // Dual-tone uses the same snap pref — refresh both tones
            // on a running generator AND the per-tone label brackets.
            GenSignalForm form = prefs.getGenSignalForm();
            if (form == GenSignalForm.DUAL_TONE) {
                int sr = currentOutputSampleRate();
                controller.setFrequency(FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                        sr, prefs.getGenDualToneFreq1Hz()));
                controller.setDualToneFrequency2(FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                        sr, prefs.getGenDualToneFreq2Hz()));
            }
            updateDualToneFreqLabels();
        });

        // ----- Sweep controls (LINEAR_SWEEP / LOG_SWEEP only) -----------
        // Wrapped in their own panel that the form-combo listener
        // shows/hides — keeps the generator pane uncluttered when the
        // current waveform isn't a sweep.
        sweepLabel = new Label(group, SWT.NONE);
        sweepLabel.setText(I18n.t("generator.sweep"));
        sweepLabel.setLayoutData(fillH());
        sweepPanel = new Composite(group, SWT.NONE);
        sweepPanel.setLayoutData(fillH());
        GridLayout spGl = new GridLayout(2, false);
        spGl.marginWidth  = 0;
        spGl.marginHeight = 0;
        spGl.horizontalSpacing = 4;
        sweepPanel.setLayout(spGl);

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.startFreq"));
        sweepStartField = new NumericStepField(sweepPanel,
                Math.max(0.0, prefs.getGenSweepFreqStartHz()),
                this::parseFrequency,
                this::formatFrequency,
                /* wheel: ±5 % */ (v, dir) -> Math.max(0.0, v * (1.0 + 0.05 * dir)),
                /* arrows: ±1 Hz */ (v, dir) -> Math.max(0.0, v + dir),
                120);
        sweepStartField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepStartField.setToolTipText(I18n.t("generator.sweep.startFreq.tooltip"));
        sweepStartField.addSelectionListener(e -> {
            prefs.setGenSweepFreqStartHz(sweepStartField.getValue());
            prefs.save();
            controller.setSweepFreqStart(sweepStartField.getValue());
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.stopFreq"));
        sweepEndField = new NumericStepField(sweepPanel,
                Math.max(0.0, prefs.getGenSweepFreqEndHz()),
                this::parseFrequency,
                this::formatFrequency,
                (v, dir) -> Math.max(0.0, v * (1.0 + 0.05 * dir)),
                (v, dir) -> Math.max(0.0, v + dir),
                120);
        sweepEndField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepEndField.setToolTipText(I18n.t("generator.sweep.stopFreq.tooltip"));
        sweepEndField.addSelectionListener(e -> {
            prefs.setGenSweepFreqEndHz(sweepEndField.getValue());
            prefs.save();
            controller.setSweepFreqEnd(sweepEndField.getValue());
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.duration"));
        sweepDurationField = new NumericStepField(sweepPanel,
                Math.max(0.001, prefs.getGenSweepDurationSec()),
                this::parseSeconds,
                this::formatSeconds,
                (v, dir) -> Math.max(0.001, v * (1.0 + 0.05 * dir)),
                (v, dir) -> Math.max(0.001, v + dir),
                120);
        sweepDurationField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepDurationField.setToolTipText(I18n.t("generator.sweep.duration.tooltip"));
        sweepDurationField.addSelectionListener(e -> {
            prefs.setGenSweepDurationSec(sweepDurationField.getValue());
            prefs.save();
            controller.setSweepDurationSeconds(sweepDurationField.getValue());
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.fadeIn"));
        sweepFadeInField = new NumericStepField(sweepPanel,
                Math.max(0.0, prefs.getGenSweepFadeInSec()),
                this::parseSecondsOrZero,
                this::formatSeconds,
                (v, dir) -> Math.max(0.0, v + dir * 0.001),
                (v, dir) -> Math.max(0.0, v + dir * 0.001),
                120);
        sweepFadeInField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepFadeInField.setToolTipText(I18n.t("generator.sweep.fadeIn.tooltip"));
        sweepFadeInField.addSelectionListener(e -> {
            prefs.setGenSweepFadeInSec(sweepFadeInField.getValue());
            prefs.save();
            controller.setSweepFadeInSeconds(sweepFadeInField.getValue());
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.fadeOut"));
        sweepFadeOutField = new NumericStepField(sweepPanel,
                Math.max(0.0, prefs.getGenSweepFadeOutSec()),
                this::parseSecondsOrZero,
                this::formatSeconds,
                (v, dir) -> Math.max(0.0, v + dir * 0.001),
                (v, dir) -> Math.max(0.0, v + dir * 0.001),
                120);
        sweepFadeOutField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepFadeOutField.setToolTipText(I18n.t("generator.sweep.fadeOut.tooltip"));
        sweepFadeOutField.addSelectionListener(e -> {
            prefs.setGenSweepFadeOutSec(sweepFadeOutField.getValue());
            prefs.save();
            controller.setSweepFadeOutSeconds(sweepFadeOutField.getValue());
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        new Label(sweepPanel, SWT.NONE).setText("");   // spacer for col 0
        sweepLoopBtn = new Button(sweepPanel, SWT.CHECK);
        sweepLoopBtn.setText(I18n.t("generator.sweep.loop"));
        sweepLoopBtn.setToolTipText(I18n.t("generator.sweep.loop.tooltip"));
        sweepLoopBtn.setSelection(prefs.isGenSweepLoop());
        sweepLoopBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        sweepLoopBtn.addListener(SWT.Selection, e -> {
            prefs.setGenSweepLoop(sweepLoopBtn.getSelection());
            prefs.save();
            controller.setSweepLoop(sweepLoopBtn.getSelection());
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        // ----- Dual-tone amplitude controls (DUAL_TONE only) ----------
        // Only the two amplitude % fields live in this panel; Freq 1
        // and Freq 2 fields are placed earlier in the outer group so
        // the snap-to-FFT-bin checkbox between them keeps its
        // position regardless of waveform.
        dualTonePanel = new Composite(group, SWT.NONE);
        GridData dtPanelGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        dtPanelGd.horizontalSpan = 2;   // span both columns of the outer group's GridLayout
        dualTonePanel.setLayoutData(dtPanelGd);
        GridLayout dtGl = new GridLayout(1, false);
        dtGl.marginWidth  = 0;
        dtGl.marginHeight = 0;
        dtGl.verticalSpacing = 2;
        dualTonePanel.setLayout(dtGl);

        // --- Freq 1 amplitude % (label above field).
        Label dtAmp1Label = new Label(dualTonePanel, SWT.NONE);
        dtAmp1Label.setText(I18n.t("generator.dualTone.amp1"));
        dtAmp1Label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        double initAmp1 = clampPct(prefs.getGenDualToneSplitPct());
        dualToneAmp1Field = new NumericStepField(dualTonePanel,
                initAmp1,
                this::parsePercent,
                this::formatPercent,
                (v, dir) -> clampPct(v + 5.0 * dir),
                (v, dir) -> clampPct(v + dir),
                160);
        dualToneAmp1Field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        dualToneAmp1Field.setToolTipText(I18n.t("generator.dualTone.amp1.tooltip"));

        // --- Freq 2 amplitude % (label above field).
        Label dtAmp2Label = new Label(dualTonePanel, SWT.NONE);
        dtAmp2Label.setText(I18n.t("generator.dualTone.amp2"));
        dtAmp2Label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        dualToneAmp2Field = new NumericStepField(dualTonePanel,
                clampPct(100.0 - initAmp1),
                this::parsePercent,
                this::formatPercent,
                (v, dir) -> clampPct(v + 5.0 * dir),
                (v, dir) -> clampPct(v + dir),
                160);
        dualToneAmp2Field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        dualToneAmp2Field.setToolTipText(I18n.t("generator.dualTone.amp2.tooltip"));

        // Couple the two amplitude fields so they always sum to 100 %.
        dualToneAmp1Field.addSelectionListener(e -> applyDualToneAmpSplit(true));
        dualToneAmp2Field.addSelectionListener(e -> applyDualToneAmpSplit(false));

        // Initial visibility matches the saved form — hide the regular
        // freq row + FFT-bin-snap and show the sweep panel up-front when
        // the app starts on a sweep, instead of waiting for the user to
        // poke the form combo.
        boolean initialIsSweep    = initialForm == GenSignalForm.LINEAR_SWEEP
                                 || initialForm == GenSignalForm.LOG_SWEEP;
        boolean initialIsDualTone = initialForm == GenSignalForm.DUAL_TONE;
        setRegularFreqRowVisible(!initialIsSweep && !initialIsDualTone);
        setDualToneFreqRowsVisible(initialIsDualTone);
        setSnapBtnVisible(!initialIsSweep);   // snap applies to SINE AND DUAL_TONE
        setSweepPanelVisible(initialIsSweep);
        setDualTonePanelVisible(initialIsDualTone);

        // Form change: persist, grey-out frequency for noise forms, and
        // live-apply to the running generator when the target form is
        // safely live-swappable.  Sweep / SINE_COMPENSATED transitions
        // need a stop+start since their state machines aren't safe to
        // mutate on the audio thread — the controller's setForm() returns
        // a hint via canLiveSwitchForm() and we surface that as a tooltip.
        formCombo.addSelectionListener(e -> {
            GenSignalForm prevForm = prefs.getGenSignalForm();
            GenSignalForm f = formCombo.getSelectedForm();
            prefs.setGenSignalForm(f);
            prefs.save();
            boolean newIsSweep    = f == GenSignalForm.LINEAR_SWEEP || f == GenSignalForm.LOG_SWEEP;
            boolean newIsDualTone = f == GenSignalForm.DUAL_TONE;
            boolean prevWasSweep    = prevForm == GenSignalForm.LINEAR_SWEEP
                                   || prevForm == GenSignalForm.LOG_SWEEP;
            boolean prevWasDualTone = prevForm == GenSignalForm.DUAL_TONE;
            // Sweep + dual-tone each hijack the single-frequency input
            // — hide the regular Frequency row.  For DUAL_TONE, show
            // the Freq 1 / Freq 2 rows instead (placed just under the
            // regular Frequency row in the outer group).  Snap
            // checkbox stays visible for SINE and DUAL_TONE — and
            // because all three freq controls sit adjacent in the
            // same outer-group layout, the snap checkbox keeps its
            // exact on-screen position across the form switch.
            setRegularFreqRowVisible(!newIsSweep && !newIsDualTone);
            setDualToneFreqRowsVisible(newIsDualTone);
            setSnapBtnVisible(!newIsSweep);
            freqField.setEnabled(!newIsSweep && !newIsDualTone && isPeriodic(f));
            updateDutyFieldEnabled(f);
            // Reload duty field from the appropriate pref so RECTANGLE and
            // TRIANGLE each remember their own duty independently.
            reloadDutyForForm(f);
            // Parameter panels: each visible only for its own form.
            setSweepPanelVisible(newIsSweep);
            setDualTonePanelVisible(newIsDualTone);
            // Sweep + dual-tone transitions need a full stop+start
            // because their constructors set up dedicated DDS state
            // (dual-tone's second accumulator, sweep's state machine)
            // that the simple {@code controller.setForm()} can't
            // hot-swap.  Plain periodic forms still hot-swap.
            boolean needsRestart = (newIsSweep || prevWasSweep
                                 || newIsDualTone || prevWasDualTone);
            if (needsRestart && controller.isRunning()) {
                restartGenerator();
            } else {
                controller.setForm(f);
            }
            // Different forms get different bracket annotations (or none).
            updateFreqLabel();
            updateDutyLabel();
            // Notify subscribers (FFT view, scope, FreqResp pane) that the
            // generated signal changed.  Without this the form-combo
            // change wouldn't reach the FFT view's form-change detector
            // and stale stats (THD averages from the old waveform) would
            // pollute the new measurement.
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        // --------------------------------------------------------- Amplitude
        addRowLabel(group, I18n.t("generator.amplitudeRms"));
        currentUnit = prefs.getGenAmplitudeUnit();
        ampField = new NumericStepField(group,
                amplitudeDisplayValue(prefs.getGenAmplitudeVrms()),
                this::parseAmplitudeText,
                this::formatAmplitudeValue,
                /* wheel:  ±5 % of value (in current unit), then auto-rescale */
                (v, dir) -> rescaleAmplitudeUnit(clampAmplitudeSign(v * (1.0 + 0.05 * dir))),
                /* arrows: ±1 of base unit, then auto-rescale */
                (v, dir) -> rescaleAmplitudeUnit(clampAmplitudeSign(v + dir)),
                /* width: */ 160);
        ampField.setLayoutData(fillH());
        ampField.setToolTipText(I18n.t("generator.amplitudeRms.tooltip"));
        ampField.addSelectionListener(e -> {
            // ampField's value is in the current display unit; canonicalise
            // to Vrms before saving so the unit-of-record is consistent.
            double vrms = currentUnit.toVrms(ampField.getValue(),
                    AudioBackend.getAdcFsVoltageRms());
            prefs.setGenAmplitudeVrms(vrms);
            prefs.setGenAmplitudeUnit(currentUnit);
            prefs.save();
            // Live-apply if running.
            controller.setAmplitudeVrms(vrms);
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });

        // ----- Duty cycle (RECTANGLE or TRIANGLE) -----------------------
        // 1 to 99 percent with 3 decimal places.  Applies to RECTANGLE
        // (high-time fraction) and TRIANGLE (rise-time fraction); each
        // form has its own persisted duty so switching back keeps the
        // user's previous value.  Disabled for any other form.
        dutyLabel = new Label(group, SWT.NONE);
        dutyLabel.setText(I18n.t("generator.dutyCycle"));
        dutyLabel.setLayoutData(fillH());
        double initialDutyPct = (initialForm == GenSignalForm.TRIANGLE)
                ? prefs.getGenTriangleDuty()  * 100.0
                : prefs.getGenRectangleDuty() * 100.0;
        dutyField = new NumericStepField(group,
                clampDutyPercent(initialDutyPct),
                this::parseDutyPercent,
                this::formatDutyPercent,
                /* wheel:  ±1 sample of the current period */
                (v, dir) -> stepDutyBySamples(v, dir),
                /* arrows: ±1 sample of the current period */
                (v, dir) -> stepDutyBySamples(v, dir),
                160);
        dutyField.setLayoutData(fillH());
        dutyField.setToolTipText(I18n.t("generator.dutyCycle.tooltip"));
        dutyField.addSelectionListener(e -> {
            double frac = dutyField.getValue() / 100.0;
            GenSignalForm f = formCombo.getSelectedForm();
            if (f == GenSignalForm.TRIANGLE) {
                prefs.setGenTriangleDuty(frac);
                prefs.save();
                controller.setTriangleDuty(frac);
            } else {
                prefs.setGenRectangleDuty(frac);
                prefs.save();
                controller.setRectangleDuty(frac);
            }
            updateDutyLabel();
            // The emitted waveform changed — invalidate the FFT average / FLL.
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        });
        updateDutyFieldEnabled(initialForm);

        // ------------------------------------------------------------ Dither
        // Cap the dither options at the current output bit depth (16 / 24 /
        // 32 typically) — dither values higher than the DAC's resolution
        // are meaningless.  Rebuild the list right before each dropdown so
        // a bit-depth change in Preferences (made after the pane was
        // constructed) is picked up the next time the user opens the combo.
        addRowLabel(group, I18n.t("generator.dither"));
        ditherCombo = new Combo(group, SWT.READ_ONLY);
        ditherBits  = ditherBitsFor(prefs.current().getOutputBitDepth());
        rebuildDitherCombo(prefs.getGenDitherBits());
        ditherCombo.setLayoutData(fillH());
        ditherCombo.setToolTipText(I18n.t("generator.dither.tooltip"));
        ditherCombo.addListener(SWT.MouseDown, e -> refreshDitherList());
        ditherCombo.addListener(SWT.FocusIn,   e -> refreshDitherList());
        ditherCombo.addListener(SWT.Selection, e -> {
            int bits = ditherBits[ditherCombo.getSelectionIndex()];
            prefs.setGenDitherBits(bits);
            prefs.save();
            controller.setDitherBits(bits);
        });

        // ------------------------------------------------------- Corrections
        addRowLabel(group, I18n.t("generator.corrections"));
        Composite corrRow = new Composite(group, SWT.NONE);
        GridLayout corrGl = new GridLayout(2, false);
        corrGl.marginWidth = 0; corrGl.marginHeight = 0;
        corrGl.horizontalSpacing = 4;
        corrRow.setLayout(corrGl);
        corrRow.setLayoutData(fillH());

        correctionsField = new Text(corrRow, SWT.BORDER | SWT.READ_ONLY);
        String savedCorr = nullToEmpty(prefs.getGenCorrectionsCsv());
        correctionsField.setText(savedCorr);
        if (!savedCorr.isEmpty()) correctionsField.setToolTipText(savedCorr);
        GridData corrGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        corrGd.widthHint = 220;
        correctionsField.setLayoutData(corrGd);

        Button browseBtn = new Button(corrRow, SWT.PUSH);
        browseBtn.setImage(folderOpenIcon);
        browseBtn.setToolTipText(I18n.t("generator.corrections.browse"));
        browseBtn.addListener(SWT.Selection, e -> openCorrectionsBrowse());

        // ----- Duration (seconds, used by Save WAV) ---------------------
        addRowLabel(group, I18n.t("generator.duration"));
        durationField = new NumericStepField(group,
                Math.max(0.001, prefs.getGenWavDurationSeconds()),
                this::parseSeconds,
                this::formatSeconds,
                /* wheel: ±5 % */ (v, dir) -> Math.max(0.001, v * (1.0 + 0.05 * dir)),
                /* arrows: ±1 s */ (v, dir) -> Math.max(0.001, v + dir),
                160);
        durationField.setLayoutData(fillH());
        durationField.setToolTipText(I18n.t("generator.duration.tooltip"));
        durationField.addSelectionListener(e -> {
            prefs.setGenWavDurationSeconds(durationField.getValue());
            prefs.save();
        });

        // ----- Save-to: text + single saveTo (floppy-disk) button.
        // Picking a file in the dialog (and confirming overwrite when
        // needed) saves the file immediately — no separate Save click.
        // Format is picked by file extension (.wav / .flac / .aiff/.aif).
        addRowLabel(group, I18n.t("generator.saveTo"));
        Composite wavRow = new Composite(group, SWT.NONE);
        GridLayout wavGl = new GridLayout(2, false);
        wavGl.marginWidth = 0; wavGl.marginHeight = 0;
        wavGl.horizontalSpacing = 4;
        wavRow.setLayout(wavGl);
        wavRow.setLayoutData(fillH());

        wavPathField = new Text(wavRow, SWT.BORDER | SWT.READ_ONLY);
        String savedWavPath = nullToEmpty(prefs.getGenWavPath());
        wavPathField.setText(savedWavPath);
        if (!savedWavPath.isEmpty()) wavPathField.setToolTipText(savedWavPath);
        GridData wavGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        wavGd.widthHint = 220;
        wavPathField.setLayoutData(wavGd);

        Button saveTo = new Button(wavRow, SWT.PUSH);
        saveTo.setImage(floppyDiskIcon);
        saveTo.setToolTipText(I18n.t("generator.saveTo.tooltip"));
        saveTo.addListener(SWT.Selection, e -> doSaveToBrowseAndWrite());

        // --------------------------------------------------- Load-from row
        // Header row: "Load from…" label on the left, "In loop" checkbox
        // on the right (same vertical level).  Content row below it is
        // indented so the path/browse/play widgets sit slightly inset
        // — checkbox itself stays flush with the right edge.
        Composite loadFromHeader = new Composite(group, SWT.NONE);
        GridLayout hdrGl = new GridLayout(2, false);
        hdrGl.marginWidth  = 0;
        hdrGl.marginHeight = 0;
        loadFromHeader.setLayout(hdrGl);
        loadFromHeader.setLayoutData(fillH());
        Label loadFromLabel = new Label(loadFromHeader, SWT.NONE);
        loadFromLabel.setText(I18n.t("generator.loadFrom"));
        loadFromLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
        playFromLoopBtn = new Button(loadFromHeader, SWT.CHECK);
        playFromLoopBtn.setText(I18n.t("generator.loadFrom.loop"));
        playFromLoopBtn.setToolTipText(I18n.t("generator.loadFrom.loop.tooltip"));
        playFromLoopBtn.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        playFromLoopBtn.setSelection(prefs.isGenPlayFromLoop());
        playFromLoopBtn.addListener(SWT.Selection, e -> {
            boolean enabled = playFromLoopBtn.getSelection();
            prefs.setGenPlayFromLoop(enabled);
            prefs.save();
            // Live-apply so toggling the checkbox during playback takes
            // effect at the next EOF.
            filePlayer.setLoop(enabled);
        });

        // Content row: text + browse (folder-open icon) + play (LED).
        // No Clear (✕) button — user spec.
        Composite playFromRow = new Composite(group, SWT.NONE);
        GridLayout pfGl = new GridLayout(3, false);
        pfGl.marginWidth = 0; pfGl.marginHeight = 0;
        pfGl.horizontalSpacing = 4;
        playFromRow.setLayout(pfGl);
        playFromRow.setLayoutData(fillH());

        playFromPathField = new Text(playFromRow, SWT.BORDER | SWT.READ_ONLY);
        String savedPlayFromPath = nullToEmpty(prefs.getGenPlayFromPath());
        playFromPathField.setText(savedPlayFromPath);
        if (!savedPlayFromPath.isEmpty()) playFromPathField.setToolTipText(savedPlayFromPath);
        GridData playGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        playGd.widthHint = 220;
        playFromPathField.setLayoutData(playGd);

        Button loadFrom = new Button(playFromRow, SWT.PUSH);
        loadFrom.setImage(folderOpenIcon);
        loadFrom.setToolTipText(I18n.t("generator.loadFrom.browse"));
        loadFrom.addListener(SWT.Selection, e -> openPlayFromBrowse());

        playFromBtn = new Button(playFromRow, SWT.PUSH);
        playFromBtn.setImage(tinyPlayDimImg);
        playFromBtn.setToolTipText(I18n.t("generator.loadFrom.play"));
        playFromBtn.addListener(SWT.Selection, e -> togglePlayFrom());
        // Auto-reset the LED when playback ends naturally (EOF without
        // loop) or fails on the play thread.  The bus delivers
        // FILE_PLAY_STOPPED on the play thread; marshal to the UI thread
        // before touching widgets.
        filePlayStoppedListener = ignored -> {
            if (!playFromBtn.isDisposed()) {
                playFromBtn.getDisplay().asyncExec(() -> {
                    if (!playFromBtn.isDisposed()) {
                        playFromBtn.setImage(tinyPlayDimImg);
                        playFromBtn.setToolTipText(I18n.t("generator.loadFrom.play"));
                        stopOnAirBlink();
                    }
                });
            }
        };
        MessageBus.instance().subscribe(Events.FILE_PLAY_STOPPED, filePlayStoppedListener);

        // --------------------------------------------------------------- Play
        // Main play button sits on its own row, indented to the right
        // edge of the generator pane (mirrors the on-air banner that
        // will live in the same area in a later iteration).  Size of
        // the button itself is unchanged.
        Composite playRow = new Composite(group, SWT.NONE);
        GridLayout playGl = new GridLayout(2, false);
        playGl.marginWidth = 0; playGl.marginHeight = 0;
        playGl.horizontalSpacing = 8;
        playRow.setLayout(playGl);
        GridData playRowGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        playRowGd.verticalIndent = 8;
        playRow.setLayoutData(playRowGd);

        Button calibrateDacBtn = new Button(playRow, SWT.PUSH);
        calibrateDacBtn.setImage(calibrateDacIcon);
        calibrateDacBtn.setToolTipText(I18n.t("generator.calibrateDac.tooltip"));
        calibrateDacBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
        calibrateDacBtn.addListener(SWT.Selection, e -> openDacCalibrationDialog());

        playBtn = new Button(playRow, SWT.PUSH);
        playBtn.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        playBtn.setImage(playDimImg);                                  // start dim
        playBtn.setToolTipText(I18n.t("generator.play.start"));
        playBtn.addListener(SWT.Selection, e -> {
            if (controller.isRunning()) {
                controller.stop();
                playBtn.setImage(playDimImg);
                playBtn.setToolTipText(I18n.t("generator.play.start"));
                stopOnAirBlink();
            } else {
                // Generator DDS and Play-from-file share the audio output
                // device — only one of them may drive it at a time.
                if (filePlayer.isRunning()) {
                    filePlayer.stop();
                    playFromBtn.setImage(tinyPlayDimImg);
                    playFromBtn.setToolTipText(I18n.t("generator.loadFrom.play"));
                }
                // Re-apply the effective frequency at start so a freshly
                // loaded preset / persisted value goes out bin-snapped
                // when snap-to-FFT is on — without this, snap only kicked
                // in after the user manually toggled the checkbox.
                controller.setFrequency(effectiveGeneratorFrequency());
                controller.start();
                if (controller.isRunning()) {
                    playBtn.setImage(playLitImg);
                    playBtn.setToolTipText(I18n.t("generator.play.stop"));
                    startOnAirBlink();
                } else {
                    String err = controller.getLastStartError();
                    Dialogs.error(group.getShell(),
                            I18n.t("generator.error.start"),
                            err != null ? err : "Unknown error starting the generator.");
                }
            }
        });

        // Listen for FFT length changes published by the FFT pane —
        // re-snap the running tone onto a fresh bin without the user
        // having to toggle the snap checkbox.  The new length is read
        // from Preferences by reapplyFrequencySnap, so the event itself
        // carries no payload.  Stored as a field so the dispose
        // listener below can unsubscribe and let the bus release its
        // reference to this pane.
        fftLengthListener = ignored -> reapplyFrequencySnap();
        MessageBus.instance().subscribe(Events.FFT_LENGTH_CHANGED, fftLengthListener);
        freqRespStartedListener = ignored -> onFreqRespMeasurementStarted();
        freqRespStoppedListener = ignored -> onFreqRespMeasurementStopped();
        MessageBus.instance().subscribe(Events.FREQRESP_MEASUREMENT_STARTED, freqRespStartedListener);
        MessageBus.instance().subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, freqRespStoppedListener);
        // FLL trim: the FFT view publishes the new DDS frequency in Hz
        // after each result; we live-apply it and republish as
        // FLL_TRIM so the FFT worker keeps its averaging accumulator.
        freqTrimListener = newHz -> {
            if (newHz == null || !Double.isFinite(newHz)) return;
            controller.setFrequency(newHz);
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.FLL_TRIM);
        };
        MessageBus.instance().subscribe(Events.GENERATOR_FREQ_TRIM, freqTrimListener);
        // Companion listener for the dual-tone second-tone FLL — same
        // FLL_TRIM republish so the FFT worker stays averaging.
        freqTrim2Listener = newHz -> {
            if (newHz == null || !Double.isFinite(newHz)) return;
            controller.setDualToneFrequency2(newHz);
            MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.FLL_TRIM);
        };
        MessageBus.instance().subscribe(Events.GENERATOR_FREQ_TRIM_2, freqTrim2Listener);

        // Respond to "is the generator running?" requests from the FFT
        // controller so it can decide whether to anchor the fundamental
        // to the generator's frequency.  One responder per event name
        // — registerResponder replaces any prior registration, so
        // language-switch shell rebuilds re-bind cleanly.
        MessageBus.instance().registerResponder(Events.GENERATOR_RUNNING,
                (Supplier<Boolean>) this::isRunning);

        // Dispose-time: stop the playback thread and tear down the icon
        // cache owned by this pane's display.
        group.addDisposeListener(e -> {
            MessageBus.instance().unsubscribe(Events.FFT_LENGTH_CHANGED,            fftLengthListener);
            MessageBus.instance().unsubscribe(Events.FILE_PLAY_STOPPED,             filePlayStoppedListener);
            MessageBus.instance().unsubscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
            MessageBus.instance().unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);
            MessageBus.instance().unsubscribe(Events.GENERATOR_FREQ_TRIM,           freqTrimListener);
            MessageBus.instance().unsubscribe(Events.GENERATOR_FREQ_TRIM_2,         freqTrim2Listener);
            MessageBus.instance().unregisterResponder(Events.GENERATOR_RUNNING);
            controller.stop();
            filePlayer.stop();
            SignalFormIcon.instance().disposeAll(group.getDisplay());
            if (onAirRedColor    != null && !onAirRedColor.isDisposed())    onAirRedColor.dispose();
            if (onAirRedDimColor != null && !onAirRedDimColor.isDisposed()) onAirRedDimColor.dispose();
            if (onAirGreyColor   != null && !onAirGreyColor.isDisposed())   onAirGreyColor.dispose();
            if (onAirFont        != null && !onAirFont.isDisposed())        onAirFont.dispose();
        });

        // Initial label render — picks up the freq / duty values just
        // loaded from prefs and shows the correction bracket for the
        // saved form, if any.
        updateFreqLabel();
        updateDutyLabel();

        wireHelpAnchors();
    }

    /** Tags every interactive widget with a {@code "helpAnchor"} data
     *  attribute so the Ctrl+F1 context-help lookup can resolve the
     *  focused control to a specific chapter section.  The pane root
     *  carries the chapter-level fallback. */
    private void wireHelpAnchors() {
        group              .setData("helpAnchor", "generator.html");
        formCombo          .setData("helpAnchor", "generator.html#generator-form");
        onAirLabel         .setData("helpAnchor", "generator.html#generator-onair");
        freqField          .setData("helpAnchor", "generator.html#generator-frequency");
        fftSnapBtn         .setData("helpAnchor", "generator.html#generator-snap-fft-bin");
        sweepStartField    .setData("helpAnchor", "generator.html#generator-sweep-start");
        sweepEndField      .setData("helpAnchor", "generator.html#generator-sweep-stop");
        sweepDurationField .setData("helpAnchor", "generator.html#generator-sweep-duration");
        sweepFadeInField   .setData("helpAnchor", "generator.html#generator-sweep-fadein");
        sweepFadeOutField  .setData("helpAnchor", "generator.html#generator-sweep-fadeout");
        sweepLoopBtn       .setData("helpAnchor", "generator.html#generator-sweep-loop");
        ampField           .setData("helpAnchor", "generator.html#generator-amplitude");
        dutyField          .setData("helpAnchor", "generator.html#generator-duty");
        ditherCombo        .setData("helpAnchor", "generator.html#generator-dither");
        correctionsField   .setData("helpAnchor", "generator.html#generator-corrections");
        durationField      .setData("helpAnchor", "generator.html#generator-duration");
        wavPathField       .setData("helpAnchor", "generator.html#generator-save-to");
        playFromPathField  .setData("helpAnchor", "generator.html#generator-load-from");
        playFromLoopBtn    .setData("helpAnchor", "generator.html#generator-load-from");
        playFromBtn        .setData("helpAnchor", "generator.html#generator-play-file");
        playBtn            .setData("helpAnchor", "generator.html#generator-play");
    }

    /**
     * Stops the DDS generator + file player ahead of an operation that
     * mutates the audio backend (e.g. the Preferences dialog switching
     * between WASAPI / WDM-KS / csjsound).  Returns a {@link Runnable}
     * that restarts whichever of them was running before — call it once
     * the disruptive operation completes.  If the user changed backend
     * during the operation, the restart uses the new dispatch path.
     */
    public Runnable pauseAroundDialog() {
        boolean genWasRunning  = controller.isRunning();
        boolean fileWasRunning = filePlayer.isRunning();
        if (genWasRunning || fileWasRunning) {
            stopOnAirBlink();
        }
        if (genWasRunning) {
            controller.stop();
            playBtn.setImage(playDimImg);
            playBtn.setToolTipText(I18n.t("generator.play.start"));
        }
        if (fileWasRunning) {
            filePlayer.stop();
            playFromBtn.setImage(tinyPlayDimImg);
            playFromBtn.setToolTipText(I18n.t("generator.loadFrom.play"));
        }
        return () -> {
            if (genWasRunning) {
                controller.setFrequency(effectiveGeneratorFrequency());
                controller.start();
                if (controller.isRunning()) {
                    playBtn.setImage(playLitImg);
                    playBtn.setToolTipText(I18n.t("generator.play.stop"));
                    startOnAirBlink();
                } else {
                    String err = controller.getLastStartError();
                    Dialogs.error(group.getShell(),
                            I18n.t("generator.error.resume"),
                            err != null ? err : "Generator could not be restarted after backend change.");
                }
            }
            // File player isn't reopened automatically — it doesn't go
            // through AudioBackend (uses default JavaSound line), so
            // device enumeration didn't disturb it, and the user may
            // have changed file/loop settings in the dialog anyway.
        };
    }

    public Composite getGroup() { return group; }

    /** Minimum width (px) the pane will accept in the horizontal split. */
    public static final int MIN_WIDTH_PX = 200;

    /** True when this pane is collapsed to just its narrow title strip. */
    public boolean isCollapsed() { return collapsed; }

    /** Current pane pixel width as last reported by the host {@code SashForm}
     *  (or restored from prefs at construction).  {@code -1} when not yet
     *  measured. */
    public int getPaneWidthPx() { return paneWidthPx; }

    /** Records the pane's current pixel width — called by the host
     *  {@code SashForm} sash filter on every drag.  Values below
     *  {@link #MIN_WIDTH_PX} are ignored so a transient drag past the
     *  minimum doesn't corrupt the persisted width. */
    public void setPaneWidthPx(int px) {
        if (px >= MIN_WIDTH_PX) paneWidthPx = px;
    }

    /** Hides / shows every child except the title Label so the pane can
     *  be collapsed down to the title bar (or restored).  Persists each
     *  child's pre-collapse {@code visible} / {@code GridData.exclude}
     *  state so mode-specific visibility (e.g. sweep vs non-sweep fields)
     *  survives the round-trip.  Also snapshots the current pixel width
     *  on the way down and restores it on the way up — the parent
     *  {@code SashForm} reads {@link #getPaneWidthPx()} to apply the
     *  matching weights. */
    public void setCollapsed(boolean wantCollapsed) {
        if (collapsed == wantCollapsed) return;
        if (group == null || group.isDisposed()) return;
        collapsed = wantCollapsed;
        Control[] children = group.getChildren();
        if (collapsed) {
            if (paneWidthPx >= MIN_WIDTH_PX) preCollapseWidthPx = paneWidthPx;
            preCollapseChildVisible = new boolean[children.length];
            preCollapseChildExclude = new boolean[children.length];
            for (int i = 0; i < children.length; i++) {
                // Keep the clickable title visible — without it the
                // user can no longer expand the pane.
                if (children[i] == title) continue;
                preCollapseChildVisible[i] = children[i].getVisible();
                if (children[i].getLayoutData() instanceof GridData gd) {
                    preCollapseChildExclude[i] = gd.exclude;
                    gd.exclude = true;
                }
                children[i].setVisible(false);
            }
            title.setCollapsed(true);
        } else {
            if (preCollapseWidthPx >= MIN_WIDTH_PX) paneWidthPx = preCollapseWidthPx;
            else if (paneWidthPx < MIN_WIDTH_PX)    paneWidthPx = MIN_WIDTH_PX;
            if (preCollapseChildVisible != null
                    && preCollapseChildVisible.length == children.length) {
                for (int i = 0; i < children.length; i++) {
                    if (children[i] == title) continue;
                    children[i].setVisible(preCollapseChildVisible[i]);
                    if (children[i].getLayoutData() instanceof GridData gd) {
                        gd.exclude = preCollapseChildExclude[i];
                    }
                }
                preCollapseChildVisible = null;
                preCollapseChildExclude = null;
            }
            title.setCollapsed(false);
        }
        group.layout(true);
    }

    /** True when the pane is collapsed.  See {@link #setCollapsed(boolean)}. */
    private boolean    collapsed;
    /** Per-child visibility snapshot taken when the pane collapses,
     *  restored verbatim on expand. */
    private boolean[]  preCollapseChildVisible;
    private boolean[]  preCollapseChildExclude;
    /** Current pane pixel width as last seen by the sash filter, seeded
     *  from prefs in the constructor.  {@code -1} = not yet measured. */
    private int        paneWidthPx = -1;
    /** Pixel width remembered at collapse time so {@link #setCollapsed}
     *  can restore it on expand. */
    private int        preCollapseWidthPx;

    /** True when the audio generator is currently producing a signal —
     *  either the tone {@link GeneratorController} or the WAV file
     *  player is running.  Exposed so the FFT controller can fall back
     *  to auto-detection of the fundamental when the user isn't feeding
     *  in a signal. */
    public boolean isRunning() {
        return controller.isRunning() || filePlayer.isRunning();
    }

    /** {@link Events#FREQRESP_MEASUREMENT_STARTED} handler — stops the
     *  running DDS tone + file player and grays both Play buttons so the
     *  Frequency Response analyzer can drive the DAC exclusively. */
    private void onFreqRespMeasurementStarted() {
        if (group.isDisposed()) return;
        if (controller.isRunning()) {
            controller.stop();
            if (playBtn != null && !playBtn.isDisposed()) {
                playBtn.setImage(playDimImg);
                playBtn.setToolTipText(I18n.t("generator.play.start"));
            }
            stopOnAirBlink();
        }
        if (filePlayer.isRunning()) {
            filePlayer.stop();
            if (playFromBtn != null && !playFromBtn.isDisposed()) {
                playFromBtn.setImage(tinyPlayDimImg);
                playFromBtn.setToolTipText(I18n.t("generator.loadFrom.play"));
            }
        }
        if (playBtn      != null && !playBtn.isDisposed())      playBtn.setEnabled(false);
        if (playFromBtn  != null && !playFromBtn.isDisposed())  playFromBtn.setEnabled(false);
    }

    /** Counterpart that re-enables both Play buttons after the sweep. */
    private void onFreqRespMeasurementStopped() {
        if (group.isDisposed()) return;
        if (playBtn     != null && !playBtn.isDisposed())     playBtn.setEnabled(true);
        if (playFromBtn != null && !playFromBtn.isDisposed()) playFromBtn.setEnabled(true);
    }

    /**
     * Opens the DAC calibration dialog.  The dialog shows the
     * currently-commanded full-scale Vrms and lets the user enter the
     * voltage actually measured at the DAC output.  On accept, the new
     * full-scale is stored in {@link SignalGenerator#FS_VOLTAGE} and
     * persisted to {@link Preferences}.
     */
    private void openDacCalibrationDialog() {
        Shell parent = (group == null || group.isDisposed()) ? null : group.getShell();
        if (parent == null) return;
        final double configuredVrms = Preferences.instance().getGenAmplitudeVrms();
        final double oldFs          = SignalGenerator.FS_VOLTAGE;
        new DacCalibrationDialog(parent, configuredVrms, measuredVrms -> {
            // The DAC was commanded to output `configuredVrms` (computed
            // against the OLD FS_VOLTAGE) and the user measured `measuredVrms`
            // at the output.  Output RMS scales linearly with FS, so the
            // true FS satisfies measured/configured = FS_true/FS_old.
            double newFs = oldFs * (measuredVrms / configuredVrms);
            SignalGenerator.FS_VOLTAGE = newFs;
            Preferences.instance().setDacFsVoltageRms(newFs);
            Preferences.instance().save();
            // The running SignalGenerator captured its normalised `amplitude`
            // at start time using the OLD FS_VOLTAGE.  Re-applying the
            // user's requested Vrms recomputes it against the new FS so
            // the calibration takes effect immediately, without a restart.
            controller.setAmplitudeVrms(configuredVrms);
        }).open();
    }

    // -------------------------------------------------------------------------
    // Frequency / duty correction
    // -------------------------------------------------------------------------

    /** Returns the FFT size the snap should use — the active value from
     *  the FFT pane's Preferences.  Falls back to 65536 if the preference
     *  is unset (which shouldn't happen after first launch). */
    private int fftSizeForSnap() {
        int n = Preferences.instance().getFftLength();
        return (n >= 8 && (n & (n - 1)) == 0) ? n : 65536;
    }

    /**
     * Number of samples in one rectangle period at the currently
     * configured output sample rate and entered frequency.  Always
     * &ge; 2 so duty-cycle math has something to work with.
     */
    private int currentPeriodSamples() {
        int sr = Preferences.instance().current().getOutputSampleRate();
        double f = freqField.getValue();
        if (f <= 0.0 || sr <= 0) return 2;
        return Math.max(2, (int) Math.round(sr / (double) f));
    }

    /** Closest frequency the DDS rectangle can produce with an integer-sample period. */
    private double correctedRectangleHz() {
        int sr = Preferences.instance().current().getOutputSampleRate();
        return (double) sr / currentPeriodSamples();
    }

    /** Persists the RAW (as-entered) frequency to prefs and live-
     *  applies the EFFECTIVE (snapped) frequency to the running
     *  generator.  The two diverge when snap-to-FFT-bin is on with a
     *  SINE waveform: the user keeps seeing 1000 Hz in the input
     *  field while the generator emits e.g. 999.987 Hz (shown in the
     *  bracket).  The split matters across restarts — without it the
     *  field would reload at the snapped value and the raw user
     *  intent would be lost. */
    private void persistRawFrequency() {
        Preferences.instance().setGenFrequencyHz(freqField.getValue());
        Preferences.instance().save();
        controller.setFrequency(effectiveGeneratorFrequency());
        MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
    }

    /** Re-applies the FFT-bin snap to the current frequency.  Invoked
     *  by {@code FftPane} when the FFT length changes so the running
     *  generator slides onto a fresh-bin position without the user
     *  having to toggle the snap checkbox.  No-op when snap is off
     *  or the active waveform isn't a sine. */
    public void reapplyFrequencySnap() {
        if (fftSnapBtn == null || !fftSnapBtn.getSelection()) return;
        controller.setFrequency(effectiveGeneratorFrequency());
        updateFreqLabel();
        // Dual-tone uses the same FFT-length-driven bin grid — refresh
        // both tones' corrected-frequency brackets and (when DUAL_TONE
        // is the active form) live-re-snap the running tones too.
        Preferences prefs = Preferences.instance();
        if (prefs.getGenSignalForm() == GenSignalForm.DUAL_TONE) {
            int sr = currentOutputSampleRate();
            controller.setFrequency(FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                    sr, prefs.getGenDualToneFreq1Hz()));
            controller.setDualToneFrequency2(FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                    sr, prefs.getGenDualToneFreq2Hz()));
        }
        updateDualToneFreqLabels();
    }

    /** Returns the frequency the controller should actually emit — the
     *  bin-snapped value when SINE + snap-to-bin is selected, otherwise
     *  the raw entered value.  Other waveforms ignore the snap. */
    private double effectiveGeneratorFrequency() {
        if (fftSnapBtn != null && fftSnapBtn.getSelection()
                && formCombo.getSelectedForm() == GenSignalForm.SINE) {
            return correctedFftBinHz();
        }
        return freqField.getValue();
    }

    /** Closest FFT-bin frequency to the entered value, using the current
     *  {@code fftLength} from the FFT pane's preferences so the snap
     *  stays in sync with whatever FFT size the user has configured. */
    private double correctedFftBinHz() {
        int sr = Preferences.instance().current().getOutputSampleRate();
        double f = freqField.getValue();
        if (sr <= 0) return f;
        double binHz = (double) sr / fftSizeForSnap();
        if (binHz <= 0.0) return f;
        return Math.round(f / binHz) * binHz;
    }

    /**
     * One-sample step for the duty cycle field.  Reads the current
     * period in samples from the rate × freq, increments / decrements
     * the high-sample count by one, and converts back to a percent.
     * Clamped to the [1 %, 99 %] band the user specified.
     */
    private double stepDutyBySamples(double currentPct, int dir) {
        int n = currentPeriodSamples();
        int kMin = Math.max(1, (int) Math.ceil(n * 0.01));
        int kMax = Math.min(n - 1, (int) Math.floor(n * 0.99));
        if (kMax < kMin) { kMax = kMin; }
        int k = (int) Math.round(currentPct / 100.0 * n) + dir;
        if (k < kMin) k = kMin;
        if (k > kMax) k = kMax;
        return k * 100.0 / n;
    }

    /**
     * Refreshes the "Frequency" label text.  Appends a bracketed
     * correction for forms that have one: RECTANGLE shows the
     * integer-sample-period frequency, SINE (when "snap to FFT bin"
     * is checked) shows the FFT-bin-snapped frequency.  All other
     * forms show plain "Frequency".
     */
    private void updateFreqLabel() {
        GenSignalForm form = formCombo.getSelectedForm();
        String corrected = null;
        if (form == GenSignalForm.RECTANGLE) {
            corrected = formatLabelHz(correctedRectangleHz());
        } else if (form == GenSignalForm.SINE && fftSnapBtn.getSelection()) {
            corrected = formatLabelHz(correctedFftBinHz());
        }
        freqLabel.setText(corrected == null ? "Frequency"
                                            : "Frequency (" + corrected + ")");
        freqLabel.getParent().layout();
    }

    /**
     * Refreshes the "Duty cycle" label text.  For RECTANGLE shows the
     * one-sample-quantised duty the DDS will actually output; other
     * forms hide the bracket (the field is also disabled in that case).
     */
    private void updateDutyLabel() {
        GenSignalForm form = formCombo.getSelectedForm();
        if (form != GenSignalForm.RECTANGLE && form != GenSignalForm.TRIANGLE) {
            dutyLabel.setText(I18n.t("generator.dutyCycle"));
            dutyLabel.getParent().layout();
            return;
        }
        int n = currentPeriodSamples();
        int k = (int) Math.round(dutyField.getValue() / 100.0 * n);
        double pct = k * 100.0 / n;
        dutyLabel.setText(I18n.t("generator.dutyCycle.bracket", formatLabelPct(pct)));
        dutyLabel.getParent().layout();
    }

    private String formatLabelHz(double v) {
        return String.format(Locale.ROOT, "%.3f Hz", v);
    }

    private String formatLabelPct(double v) {
        return String.format(Locale.ROOT, "%.3f %%", v);
    }

    // -------------------------------------------------------------------------
    // Layout helpers
    // -------------------------------------------------------------------------
    private void addRowLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(fillH());
    }
    private GridData fillH() {
        return new GridData(SWT.FILL, SWT.CENTER, true, false);
    }

    // -------------------------------------------------------------------------
    // Frequency parse/format
    // -------------------------------------------------------------------------
    private Double parseFrequency(String s) {
        if (s == null) return null;
        // Strip an optional trailing "Hz" (case-insensitive) before parsing.
        String trimmed = s.trim();
        int hzAt = -1;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (Character.isLetter(c)) hzAt = i;
            else break;
        }
        String num = (hzAt >= 0 ? trimmed.substring(0, hzAt) : trimmed)
                .trim().replace(',', '.');
        if (num.isEmpty()) return null;
        try {
            double v = Double.parseDouble(num);
            return v < 0 ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    private String formatFrequency(double v) {
        return formatNumber(v) + " Hz";
    }

    /** Parses a percent value — accepts plain numbers and an optional
     *  trailing {@code %} or {@code percent} token.  Returns {@code null}
     *  when the input doesn't contain a valid number. */
    private Double parsePercent(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().replace(',', '.');
        if (trimmed.endsWith("%")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        if (trimmed.isEmpty()) return null;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    private String formatPercent(double v) {
        return formatNumber(v) + " %";
    }
    /** Clamps a percentage to {@code [0, 100]} — used as the value
     *  transform for the dual-tone split spinner so the field can't be
     *  scrolled out of physical bounds. */
    private double clampPct(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }

    /** Current output device sample rate, read from prefs.  Used by
     *  the dual-tone freq listeners to compute the snapped value for
     *  live-apply to a running generator. */
    private int currentOutputSampleRate() {
        return Preferences.instance().current().getOutputSampleRate();
    }

    // -------------------------------------------------------------------------
    // Amplitude parse/format
    // -------------------------------------------------------------------------
    /**
     * Parses {@code raw}.  If a unit token is present it switches the
     * field's working unit to that; the returned {@code Double} is the
     * numeric value in the (possibly new) working unit, NOT in Vrms.
     */
    private Double parseAmplitudeText(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        int unitStart = -1;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (Character.isLetter(c)) unitStart = i;
            else break;
        }
        String numPart, unitPart;
        if (unitStart < 0) {
            numPart  = trimmed;
            unitPart = null;
        } else {
            numPart  = trimmed.substring(0, unitStart).trim();
            unitPart = trimmed.substring(unitStart).trim();
        }
        if (numPart.isEmpty()) return null;
        double n;
        try {
            n = Double.parseDouble(numPart.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
        AmplitudeUnit u = (unitPart == null) ? currentUnit : AmplitudeUnit.fromString(unitPart);
        if (u == null) return null;
        // Linear voltage units carry an absolute RMS magnitude — negative
        // values are nonsensical (RMS is always ≥ 0).  dB units stay
        // signed because e.g. -20 dBFS is a perfectly normal level.
        if (u.isMetric() && n < 0) return null;
        currentUnit = u;
        return rescaleAmplitudeUnit(n);
    }
    private String formatAmplitudeValue(double v) {
        return formatNumber(v) + " " + currentUnit.display;
    }

    // -------------------------------------------------------------------------
    // ON-AIR banner — solid red LED + blinking bold label at top right of the
    // pane.  When active: LED is solid #FF0000, label alternates between
    // #FF0000 (bright) and #AA0000 (dim) at ~1 Hz.  Idle: both go grey.
    // -------------------------------------------------------------------------
    private void paintOnAirLed(org.eclipse.swt.widgets.Event e) {
        e.gc.setAntialias(SWT.ON);
        Color fill = onAirActive ? onAirRedColor : onAirGreyColor;
        e.gc.setBackground(fill);
        int side = Math.min(onAirLed.getSize().x, onAirLed.getSize().y);
        int inset = 1;
        e.gc.fillOval(inset, inset, side - 2 * inset, side - 2 * inset);
    }

    /** Begins blinking the on-air label at ~1 Hz, LED stays solid red. */
    private void startOnAirBlink() {
        if (onAirActive) return;
        onAirActive = true;
        onAirBlinkOn = true;
        if (onAirLabel != null && !onAirLabel.isDisposed()) {
            onAirLabel.setForeground(onAirRedColor);
        }
        if (onAirLed != null && !onAirLed.isDisposed()) onAirLed.redraw();
        scheduleOnAirBlink();
    }

    /** Stops the blink and dims label + LED back to grey. */
    private void stopOnAirBlink() {
        onAirActive = false;
        onAirBlinkOn = false;
        if (onAirLabel != null && !onAirLabel.isDisposed()) {
            onAirLabel.setForeground(onAirGreyColor);
        }
        if (onAirLed != null && !onAirLed.isDisposed()) onAirLed.redraw();
    }

    private void scheduleOnAirBlink() {
        if (onAirLed == null || onAirLed.isDisposed()) return;
        onAirLed.getDisplay().timerExec(500, () -> {
            if (onAirLabel == null || onAirLabel.isDisposed()) return;
            if (!onAirActive) return;
            onAirBlinkOn = !onAirBlinkOn;
            onAirLabel.setForeground(onAirBlinkOn ? onAirRedColor : onAirRedDimColor);
            scheduleOnAirBlink();
        });
    }

    /** Returns {@code vrms} converted into the current display unit. */
    private double amplitudeDisplayValue(double vrms) {
        double fs = AudioBackend.getAdcFsVoltageRms();
        return currentUnit.fromVrms(vrms, fs);
    }

    /**
     * Clamps the amplitude value to ≥ 0 when the current unit is one of
     * the linear voltage units (µV / mV / V), since RMS magnitude can't be
     * negative.  dB units pass through unchanged — e.g. arrow-down from
     * 0 dBV → -1 dBV is a legitimate -20 dB step.
     */
    private double clampAmplitudeSign(double v) {
        return currentUnit.isMetric() ? Math.max(0.0, v) : v;
    }

    /**
     * Auto-rescale within the µV / mV / V metric group so the displayed
     * value stays in a human-readable range (no "0.0005 V" or "5000 mV").
     * No-op for dB units (absolute log scales) and for zero values
     * (rescaling 0 V → 0 µV would be visually jarring).  Updates
     * {@link #currentUnit} as a side effect and returns the value
     * expressed in the (possibly new) unit.
     */
    private double rescaleAmplitudeUnit(double valueInCurrentUnit) {
        if (!currentUnit.isMetric()) return valueInCurrentUnit;
        double fs = AudioBackend.getAdcFsVoltageRms();
        double vrms = currentUnit.toVrms(valueInCurrentUnit, fs);
        if (Math.abs(vrms) < 1e-12) return valueInCurrentUnit;
        AmplitudeUnit best = AmplitudeUnit.bestMetricFor(Math.abs(vrms));
        if (best == currentUnit) return valueInCurrentUnit;
        currentUnit = best;
        return best.fromVrms(vrms, fs);
    }

    // -------------------------------------------------------------------------
    // Browse dialog (remembers its last folder separately from other dialogs)
    // -------------------------------------------------------------------------
    private void openCorrectionsBrowse() {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(group.getShell(), SWT.OPEN);
        fd.setText(I18n.t("generator.corrections.dialog"));
        fd.setFilterExtensions(new String[] { "*.csv", "*.*" });
        fd.setFilterNames     (new String[] { "CSV files (*.csv)", "All files" });
        if (prefs.getGenCorrectionsFolder() != null) {
            fd.setFilterPath(prefs.getGenCorrectionsFolder());
        }
        String picked = fd.open();
        if (picked == null) return;
        correctionsField.setText(picked);
        correctionsField.setToolTipText(picked);
        prefs.setGenCorrectionsCsv(picked);
        File parent = new File(picked).getParentFile();
        if (parent != null) prefs.setGenCorrectionsFolder(parent.getAbsolutePath());
        prefs.save();
    }

    // -------------------------------------------------------------------------
    // Duty / duration parse + format
    // -------------------------------------------------------------------------
    private Double parseDutyPercent(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t.replace(',', '.'));
            return clampDutyPercent(v);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    /** Renders a duty-percent with up to 3 decimal places, dropping trailing zeros and a "%" suffix. */
    private String formatDutyPercent(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        if (s.contains(".")) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            if (end > 0 && s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        return s + " %";
    }
    private double clampDutyPercent(double v) {
        if (Double.isNaN(v)) return 50.0;
        if (v < 1.0)  return 1.0;
        if (v > 99.0) return 99.0;
        return v;
    }
    /** Enables / disables the duty field+label depending on whether {@code form}
     *  is one of the duty-aware shapes (RECTANGLE or TRIANGLE). */
    private void updateDutyFieldEnabled(GenSignalForm form) {
        boolean on = form == GenSignalForm.RECTANGLE || form == GenSignalForm.TRIANGLE;
        dutyField.setEnabled(on);
        dutyLabel.setEnabled(on);
    }

    /** Refreshes the duty field's value from whichever preference is current
     *  for {@code form}; called when the user switches between RECTANGLE
     *  and TRIANGLE so each form keeps its own remembered duty. */
    private void reloadDutyForForm(GenSignalForm form) {
        Preferences prefs = Preferences.instance();
        if (form == GenSignalForm.TRIANGLE) {
            dutyField.setValue(clampDutyPercent(prefs.getGenTriangleDuty() * 100.0));
        } else if (form == GenSignalForm.RECTANGLE) {
            dutyField.setValue(clampDutyPercent(prefs.getGenRectangleDuty() * 100.0));
        }
    }

    private Double parseSeconds(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.endsWith("s")) t = t.substring(0, t.length() - 1).trim();
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t.replace(',', '.'));
            return v <= 0 ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    /** Like {@link #parseSeconds} but accepts 0 — used by the sweep
     *  fade-in / fade-out fields where 0 = no fade is a legitimate value. */
    private Double parseSecondsOrZero(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.endsWith("s")) t = t.substring(0, t.length() - 1).trim();
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t.replace(',', '.'));
            return v < 0 ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    private String formatSeconds(double v) {
        return formatNumber(v) + " s";
    }

    /** Stops the running generator and immediately starts it again so a
     *  not-live-swappable form change (e.g. SINE ↔ LINEAR_SWEEP, LINEAR_SWEEP
     *  ↔ LOG_SWEEP) actually takes effect.  On failure rolls the play
     *  button + on-air banner back to the stopped state and surfaces the
     *  controller error. */
    private void restartGenerator() {
        controller.stop();
        controller.setFrequency(effectiveGeneratorFrequency());
        controller.start();
        if (!controller.isRunning()) {
            playBtn.setImage(playDimImg);
            playBtn.setToolTipText(I18n.t("generator.play.start"));
            stopOnAirBlink();
            String err = controller.getLastStartError();
            if (err != null) {
                Dialogs.error(group.getShell(), I18n.t("generator.error.restart"), err);
            }
        }
    }

    /** Shows / hides the dual-tone Freq 1 / Freq 2 rows that live in
     *  the outer group right under the regular Frequency row.
     *  Visibility is flipped between the regular Frequency row and
     *  these two so the snap-to-FFT-bin checkbox below stays in the
     *  same on-screen position when the user switches between
     *  single-tone and dual-tone waveforms.  Layout reflows via
     *  {@code GridData.exclude}. */
    private void setDualToneFreqRowsVisible(boolean visible) {
        toggleRow(dualToneFreq1Label, visible);
        toggleRow(dualToneFreq1Field, visible);
        toggleRow(dualToneFreq2Label, visible);
        toggleRow(dualToneFreq2Field, visible);
        if (dualToneFreq1Label != null && !dualToneFreq1Label.isDisposed()
                && dualToneFreq1Label.getParent() != null) {
            dualToneFreq1Label.getParent().layout(true);
        }
    }

    /** Shows / hides ONLY the regular Frequency row (label + field).
     *  Used when DUAL_TONE wants its own two-frequency block to take
     *  over the freq area while leaving the FFT-bin-snap checkbox
     *  visible (snap still applies to both dual-tone frequencies). */
    private void setRegularFreqRowVisible(boolean visible) {
        toggleRow(freqLabel, visible);
        toggleRow(freqField, visible);
        if (freqLabel.getParent() != null && !freqLabel.getParent().isDisposed()) {
            freqLabel.getParent().layout(true);
        }
    }

    /** Shows / hides the FFT-bin-snap checkbox.  Visible for SINE and
     *  DUAL_TONE, hidden for everything else. */
    private void setSnapBtnVisible(boolean visible) {
        toggleRow(fftSnapBtn, visible);
        if (fftSnapBtn.getParent() != null && !fftSnapBtn.getParent().isDisposed()) {
            fftSnapBtn.getParent().layout(true);
        }
    }

    /** Shows / hides the dual-tone parameter panel.  Mirrors
     *  {@link #setSweepPanelVisible}; reflows via
     *  {@code GridData.exclude}.  Refreshes the corrected-frequency
     *  brackets on the Freq 1 / Freq 2 labels at the same time so
     *  they're current the moment the panel appears. */
    private void setDualTonePanelVisible(boolean visible) {
        if (dualTonePanel == null || dualTonePanel.isDisposed()) return;
        dualTonePanel.setVisible(visible);
        Object ld = dualTonePanel.getLayoutData();
        if (ld instanceof GridData) ((GridData) ld).exclude = !visible;
        if (visible) updateDualToneFreqLabels();
        dualTonePanel.getParent().layout(true);
    }

    /** Re-renders the Freq 1 / Freq 2 labels inside the dual-tone
     *  panel, appending the snap-corrected frequency in brackets when
     *  snap-to-FFT-bin is on (e.g. "Frequency 1 (17996.18 Hz)" when
     *  the user typed 18 kHz at 384 k / 2 M).  Reused on every freq
     *  field edit, on snap toggle, and on panel-show. */
    private void updateDualToneFreqLabels() {
        if (dualToneFreq1Label == null || dualToneFreq1Label.isDisposed()) return;
        Preferences prefs = Preferences.instance();
        int sr = currentOutputSampleRate();
        String base1 = I18n.t("generator.dualTone.freq1");
        String base2 = I18n.t("generator.dualTone.freq2");
        boolean snap = prefs.isGenSnapToFftBin();
        if (snap) {
            double raw1 = (dualToneFreq1Field != null && !dualToneFreq1Field.isDisposed())
                    ? dualToneFreq1Field.getValue() : prefs.getGenDualToneFreq1Hz();
            double raw2 = (dualToneFreq2Field != null && !dualToneFreq2Field.isDisposed())
                    ? dualToneFreq2Field.getValue() : prefs.getGenDualToneFreq2Hz();
            double snap1 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr, raw1);
            double snap2 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr, raw2);
            dualToneFreq1Label.setText(base1 + "  (" + formatFrequency(snap1) + ")");
            dualToneFreq2Label.setText(base2 + "  (" + formatFrequency(snap2) + ")");
        } else {
            dualToneFreq1Label.setText(base1);
            dualToneFreq2Label.setText(base2);
        }
        dualToneFreq1Label.getParent().layout(true);
    }

    /** Couples the two amplitude % fields so they always sum to 100 %.
     *  Called by both fields' selection listeners.  {@code editedFirst}
     *  is true when the user just modified the Freq 1 amplitude field;
     *  false when they modified Freq 2's.  The unedited field is set
     *  to {@code 100 − edited} and the pref of record
     *  ({@code genDualToneSplitPct}) tracks Freq 1's amplitude
     *  percentage. */
    private void applyDualToneAmpSplit(boolean editedFirst) {
        Preferences prefs = Preferences.instance();
        double a1, a2;
        if (editedFirst) {
            a1 = clampPct(dualToneAmp1Field.getValue());
            a2 = clampPct(100.0 - a1);
            dualToneAmp2Field.setValue(a2);
        } else {
            a2 = clampPct(dualToneAmp2Field.getValue());
            a1 = clampPct(100.0 - a2);
            dualToneAmp1Field.setValue(a1);
        }
        prefs.setGenDualToneSplitPct(a1);
        prefs.save();
        controller.setDualToneAmplitudes(a1, a2);
        // The tone balance changed — invalidate the FFT average / FLL.
        MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
    }

    /** Flips a control's GridData.exclude flag and visibility together. */
    private void toggleRow(Control c, boolean visible) {
        if (c == null || c.isDisposed()) return;
        c.setVisible(visible);
        Object ld = c.getLayoutData();
        if (ld instanceof GridData) ((GridData) ld).exclude = !visible;
    }

    /** Shows / hides the sweep parameter panel + its row label, reflowing
     *  the layout via GridData.exclude so the rest of the pane fills the
     *  freed space when the panel is hidden. */
    private void setSweepPanelVisible(boolean visible) {
        if (sweepPanel == null || sweepPanel.isDisposed()) return;
        sweepLabel.setVisible(visible);
        sweepPanel.setVisible(visible);
        Object ld1 = sweepLabel.getLayoutData();
        Object ld2 = sweepPanel.getLayoutData();
        if (ld1 instanceof GridData) ((GridData) ld1).exclude = !visible;
        if (ld2 instanceof GridData) ((GridData) ld2).exclude = !visible;
        sweepPanel.getParent().layout(true);
    }

    // -------------------------------------------------------------------------
    // WAV save dialog + write
    // -------------------------------------------------------------------------
    /**
     * Save-to flow: opens the file picker (with overwrite confirmation),
     * persists the chosen path, then writes the current generator
     * settings to disk immediately.  Replaces the old browse-then-Save
     * two-step.
     */
    private void doSaveToBrowseAndWrite() {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(group.getShell(), SWT.SAVE);
        fd.setText(I18n.t("generator.saveTo.dialog"));
        fd.setFilterExtensions(new String[] { "*.wav", "*.flac", "*.aiff;*.aif", "*.*" });
        fd.setFilterNames     (new String[] {
                "WAV files (*.wav)",
                "FLAC files (*.flac)",
                "AIFF files (*.aiff, *.aif)",
                "All files" });
        fd.setOverwrite(true);
        if (prefs.getGenWavFolder() != null) fd.setFilterPath(prefs.getGenWavFolder());
        fd.setFileName(buildSuggestedSaveName());
        String picked = fd.open();
        if (picked == null) return;
        // Append the filter's extension if the user didn't type one.
        String lower = picked.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".wav") || lower.endsWith(".flac")
              || lower.endsWith(".aiff") || lower.endsWith(".aif"))) {
            int idx = fd.getFilterIndex();
            String ext = (idx == 1) ? ".flac" : (idx == 2) ? ".aiff" : ".wav";
            picked += ext;
        }
        wavPathField.setText(picked);
        wavPathField.setToolTipText(picked);
        prefs.setGenWavPath(picked);
        File parent = new File(picked).getParentFile();
        if (parent != null) prefs.setGenWavFolder(parent.getAbsolutePath());
        prefs.save();
        // Auto-save: write immediately after the path is chosen.
        saveWavNow();
    }
    private void openPlayFromBrowse() {
        Preferences prefs = Preferences.instance();
        FileDialog fd =new FileDialog(group.getShell(), SWT.OPEN);
        fd.setText(I18n.t("generator.loadFrom.dialog"));
        fd.setFilterExtensions(new String[] { "*.wav;*.flac;*.aiff;*.aif", "*.wav", "*.flac", "*.aiff;*.aif", "*.*" });
        fd.setFilterNames     (new String[] {
                "All audio (*.wav, *.flac, *.aiff, *.aif)",
                "WAV files (*.wav)",
                "FLAC files (*.flac)",
                "AIFF files (*.aiff, *.aif)",
                "All files" });
        if (prefs.getGenPlayFromFolder() != null) fd.setFilterPath(prefs.getGenPlayFromFolder());
        String picked = fd.open();
        if (picked == null) return;
        playFromPathField.setText(picked);
        playFromPathField.setToolTipText(picked);
        prefs.setGenPlayFromPath(picked);
        File parent = new File(picked).getParentFile();
        if (parent != null) prefs.setGenPlayFromFolder(parent.getAbsolutePath());
        prefs.save();
    }

    /**
     * Toggles file playback: if running, stops; otherwise starts on the
     * currently selected file with the saved In-loop flag.  Updates the
     * LED button image accordingly.
     */
    private void togglePlayFrom() {
        if (filePlayer.isRunning()) {
            filePlayer.stop();
            playFromBtn.setImage(tinyPlayDimImg);
            playFromBtn.setToolTipText(I18n.t("generator.loadFrom.play"));
            stopOnAirBlink();
            return;
        }
        Preferences prefs = Preferences.instance();
        String path = prefs.getGenPlayFromPath();
        if (path == null || path.isEmpty()) {
            Dialogs.info(group.getShell(),
                    I18n.t("generator.loadFrom.dialog"),
                    I18n.t("generator.error.playFile.pickFirst"));
            return;
        }
        // Generator DDS and Play-from-file share the audio output device.
        if (controller.isRunning()) {
            controller.stop();
            if (playBtn != null && !playBtn.isDisposed()) {
                playBtn.setImage(playDimImg);
                playBtn.setToolTipText(I18n.t("generator.play.start"));
            }
        }
        filePlayer.start(new File(path), prefs.isGenPlayFromLoop());
        if (filePlayer.isRunning()) {
            playFromBtn.setImage(tinyPlayLitImg);
            playFromBtn.setToolTipText(I18n.t("generator.loadFrom.stop"));
            startOnAirBlink();
        } else {
            String err = filePlayer.getLastStartError();
            Dialogs.error(group.getShell(),
                    I18n.t("generator.error.playFile"),
                    err != null ? err : "Unknown error opening the file.");
        }
    }

    /** Builds a default file name encoding signal form + sample rate (kHz) + bit width.  WAV by default. */
    private String buildSuggestedSaveName() {
        Preferences prefs = Preferences.instance();
        GenSignalForm form     = prefs.getGenSignalForm();
        int        rateKhz  = prefs.current().getOutputSampleRate() / 1000;
        int        bitDepth = prefs.current().getOutputBitDepth();
        return String.format(Locale.ROOT, "%s_%dkHz_%dbit.wav",
                form.name().toLowerCase(Locale.ROOT), rateKhz, bitDepth);
    }
    /** Writes the WAV file using the current generator settings + saved duration. */
    private void saveWavNow() {
        Preferences prefs = Preferences.instance();
        String path = prefs.getGenWavPath();
        if (path == null || path.isEmpty()) {
            Dialogs.info(group.getShell(),
                    I18n.t("generator.error.save"),
                    I18n.t("generator.error.save.pickFirst"));
            return;
        }
        GenSignalForm form = prefs.getGenSignalForm();
        if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) {
            Dialogs.error(group.getShell(),
                    I18n.t("generator.error.save"),
                    I18n.t("generator.error.save.sweepUnsupported"));
            return;
        }
        int    sampleRate    = prefs.current().getOutputSampleRate();
        int    bitDepth      = prefs.current().getOutputBitDepth();
        int    ditherBits    = prefs.getGenDitherBits();
        double frequency     = prefs.getGenFrequencyHz();
        double amplitudeVRms = prefs.getGenAmplitudeVrms();
        double duration      = prefs.getGenWavDurationSeconds();
        try {
            SignalGenerator gen;
            if (form == GenSignalForm.SINE_COMPENSATED) {
                String csv = prefs.getGenCorrectionsCsv();
                if (csv == null || csv.isEmpty()) {
                    Dialogs.error(group.getShell(),
                            I18n.t("generator.error.save"),
                            I18n.t("generator.error.save.compensatedNeedsCsv"));
                    return;
                }
                gen = new SignalGenerator(frequency, sampleRate, amplitudeVRms, csv);
            } else {
                gen = new SignalGenerator(form, frequency, sampleRate, amplitudeVRms);
            }
            gen.setRectangleDuty(prefs.getGenRectangleDuty());
            // Periodic forms truncate to integer-period count; noise has
            // no period, pass 0 so the exporter uses the raw duration.
            double freqForTruncation = isPeriodic(form) ? frequency : 0.0;
            long bytes = SignalFileExporter.export(gen, new File(path),
                    sampleRate, bitDepth, duration, ditherBits, freqForTruncation);
            log.info("File saved: {} ({} bytes)", path, bytes);
        } catch (Exception ex) {
            log.warn("Save failed", ex);
            Dialogs.error(group.getShell(),
                    I18n.t("generator.error.save.failed"),
                    I18n.t("generator.error.save.failedMessage", path, ex.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------
    /**
     * Formats {@code v} with up to four non-zero decimal places, trimming
     * trailing zeros so an integer comes out clean.  Examples:
     * {@code 1000 → "1000"}, {@code 1000.5 → "1000.5"},
     * {@code 0.00012345 → "0.0001"}.
     */
    private String formatNumber(double v) {
        if (v == 0) return "0";
        String s = String.format(Locale.ROOT, "%.4f", v);
        if (s.contains(".")) {
            // Strip trailing zeros, then a dangling decimal point.
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            if (end > 0 && s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        return s;
    }

    private boolean isPeriodic(GenSignalForm f) {
        switch (f) {
            case WHITE_NOISE:
            case PINK_NOISE:
            case PINK_NOISE_LINEAR:
                return false;
            default:
                return true;
        }
    }

    /** Re-populates the dither combo with {@link #ditherBits} and selects {@code currentBits} (or 0 / "Off" if unavailable). */
    private void rebuildDitherCombo(int currentBits) {
        String[] items = new String[ditherBits.length];
        for (int i = 0; i < ditherBits.length; i++) {
            items[i] = ditherBits[i] == 0 ? "Off" : ditherBits[i] + " bits";
        }
        ditherCombo.setItems(items);
        int sel = 0;
        for (int i = 0; i < ditherBits.length; i++) {
            if (ditherBits[i] == currentBits) { sel = i; break; }
        }
        ditherCombo.select(sel);
    }

    /**
     * Refreshes the dither combo for a new output bit depth (e.g. after
     * the user changes it in Preferences).  Re-selects the previous bit
     * count if it still fits, otherwise falls back to "Off".
     */
    public void onOutputBitDepthChanged(int newOutputBitDepth) {
        ditherBits = ditherBitsFor(newOutputBitDepth);
        rebuildDitherCombo(Preferences.instance().getGenDitherBits());
    }

    /**
     * Rebuilds the dither combo only when the cached output bit depth no
     * longer matches the current preference — fired on mouse-down /
     * focus-in so a change to output bit depth (made via Preferences after
     * the pane was constructed) is reflected the next time the user
     * touches the combo.
     */
    private void refreshDitherList() {
        int currentDepth = Preferences.instance().current().getOutputBitDepth();
        if (ditherBits.length == currentDepth + 1) return;     // unchanged
        onOutputBitDepthChanged(currentDepth);
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }
}
