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

package org.edgo.audio.measure.gui.generator;

import java.io.File;
import java.util.Locale;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractPane;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.FftBinSnap;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
import org.edgo.audio.measure.gui.widgets.SignalFormCombo;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

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
public final class GeneratorPane extends AbstractPane {

    /** Minimum width (px) the pane will accept in the horizontal split. */
    public static final int MIN_WIDTH_PX = 200;

    /** Minimum generator / dual-tone frequency — a real floor so a field can
     *  never hold a degenerate (sub-0.01 Hz) value. */
    private static final double GEN_FREQ_MIN_HZ  = 0.01;

    // Display precision caps per the numeric-field spec: frequencies show
    // every significant digit, amplitudes up to 5, times / percentages up to 3.
    private static final int FREQ_MAX_DECIMALS    = 9;
    private static final int AMP_MAX_DECIMALS     = 5;
    private static final int TIME_MAX_DECIMALS    = 3;
    private static final int PERCENT_MAX_DECIMALS = 3;
    /** Duty-cycle / dual-tone-split bounds (%): a pulse never fully collapses. */
    private static final double DUTY_MIN_PCT = 0.001;
    private static final double DUTY_MAX_PCT = 99.999;
    /** Upper bound for every duration-type field (s). */
    private static final double TIME_MAX_SEC = 1_000_000;
    /** Amplitude floor (Vrms) — keeps log-unit (dBV) entry finite. */
    private static final double AMP_MIN_VRMS = 1e-9;

    /** Current dither values shown in the combo (rebuilt when output bit depth changes). */
    private int[] ditherBits;

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
    private Button                corrBrowseBtn;
    private Button                corrClearBtn;
    private final NumericStepField durationField;
    private final Text            wavPathField;
    private final Text            playFromPathField;
    private final Button          playFromBtn;
    private final Button          playFromLoopBtn;

    private final Image           tinyPlayDimImg;
    private final Image           tinyPlayLitImg;
    private final Image           playDimImg;
    private final Image           playLitImg;
    private final Image           floppyDiskIcon;
    private final Image           folderOpenIcon;
    private final Image           calibrateDacIcon;
    private final Button          playBtn;

    /** App-lifetime engine controller, injected by the host (lives in
     *  {@code UIEngines}) — survives the in-place content rebuilds, so a
     *  freshly built pane may find the tone already playing and only has
     *  to sync its visuals. */
    private final GeneratorController controller;

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
    /** Re-pulls the Nyquist-derived frequency-field ceilings after the
     *  Preferences dialog commits an audio-format change. */
    private Consumer<Void> audioFormatListener;

    /** Current pane pixel width as last seen by the sash filter, seeded
     *  from prefs in the constructor.  {@code -1} = not yet measured. */
    @Getter
    private int        paneWidthPx = -1;
    /** Pixel width remembered at collapse time so {@link #setCollapsed}
     *  can restore it on expand. */
    private int        preCollapseWidthPx;

    public GeneratorPane(Composite parent, GeneratorController controller) {
        super(parent);
        this.controller = controller;
        // Seed the tracked pane width from prefs BEFORE the host
        // SashForm's first layout pass — the controlListener that picks
        // up paneWidthPx fires immediately after construction and we
        // want it to honour the saved value instead of falling back to
        // the SashForm's construction-default weights.
        int savedWidth = Preferences.instance().getGenPaneWidth();
        if (savedWidth >= MIN_WIDTH_PX) this.paneWidthPx = savedWidth;

        Display d = parent.getDisplay();
        this.playDimImg     = IconUtils.icon(d, Icon.PLAY_DARK_BIG);
        this.playLitImg     = IconUtils.icon(d, Icon.PLAY_LIT_BIG);
        this.tinyPlayDimImg = IconUtils.icon(d, Icon.PLAY_DARK_SMALL);
        this.tinyPlayLitImg = IconUtils.icon(d, Icon.PLAY_LIT_SMALL);
        this.floppyDiskIcon = IconUtils.icon(d, Icon.FLOPPY_DISK);
        this.folderOpenIcon = IconUtils.icon(d, Icon.FOLDER_OPEN);
        this.calibrateDacIcon = IconUtils.icon(d, Icon.CROSSHAIR_BIG);

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
        freqField = new NumericStepField(group, UnitFamily.FREQUENCY,
                GEN_FREQ_MIN_HZ, currentOutputSampleRate() / 2.0, FREQ_MAX_DECIMALS, 160);
        freqField.setLayoutData(fillH());
        freqField.setToolTipText(I18n.t("generator.frequency.tooltip"));
        freqField.setEnabled(initialForm.isPeriodic());
        // Two-way bind the RAW (as-entered) frequency to the pref; the
        // bind auto-persists via Preferences.requestSave().  The live-apply
        // of the EFFECTIVE (snapped) frequency + the FFT-invalidation
        // publish are the controller's own subscription; the pane only
        // follows with its bracket labels.  A freq change shifts the
        // rectangle period (samples per cycle), which moves both the
        // corrected freq AND the duty grid.
        Bindings.stepField(freqField, prefs.genFrequencyHzProperty());
        Bindings.onChange(group, prefs.genFrequencyHzProperty(), v -> {
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
        dualToneFreq1Field = new NumericStepField(group, UnitFamily.FREQUENCY,
                GEN_FREQ_MIN_HZ, currentOutputSampleRate() / 2.0, FREQ_MAX_DECIMALS, 160);
        GridData dt1fGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dt1fGd.horizontalSpan = 2;
        dualToneFreq1Field.setLayoutData(dt1fGd);
        dualToneFreq1Field.setToolTipText(I18n.t("generator.dualTone.freq1.tooltip"));
        Bindings.stepField(dualToneFreq1Field, prefs.genDualToneFreq1HzProperty());
        Bindings.onChange(group, prefs.genDualToneFreq1HzProperty(), v -> {
            updateDualToneFreqLabels();
            updateFreqLabel();
        });

        dualToneFreq2Label = new Label(group, SWT.NONE);
        dualToneFreq2Label.setText(I18n.t("generator.dualTone.freq2"));
        GridData dt2lGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dt2lGd.horizontalSpan = 2;
        dualToneFreq2Label.setLayoutData(dt2lGd);
        dualToneFreq2Field = new NumericStepField(group, UnitFamily.FREQUENCY,
                GEN_FREQ_MIN_HZ, currentOutputSampleRate() / 2.0, FREQ_MAX_DECIMALS, 160);
        GridData dt2fGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        dt2fGd.horizontalSpan = 2;
        dualToneFreq2Field.setLayoutData(dt2fGd);
        dualToneFreq2Field.setToolTipText(I18n.t("generator.dualTone.freq2.tooltip"));
        Bindings.stepField(dualToneFreq2Field, prefs.genDualToneFreq2HzProperty());
        Bindings.onChange(group, prefs.genDualToneFreq2HzProperty(), v -> {
            updateDualToneFreqLabels();
            updateFreqLabel();
        });

        // ------- FFT-bin snap checkbox.  Persisted, and live-applied:
        // toggling on snaps the running sine to the nearest bin of the
        // FFT pane's current fftLength; toggling off restores the
        // entered frequency.
        fftSnapBtn = new Button(group, SWT.CHECK);
        fftSnapBtn.setText(I18n.t("generator.snapFft"));
        fftSnapBtn.setToolTipText(I18n.t("generator.snapFft.tooltip"));
        fftSnapBtn.setLayoutData(fillH());
        // Two-way bind the snap flag (auto-persists via requestSave).  The
        // running generator's re-snap + FFT-invalidation publish are the
        // controller's subscription; the pane follows with the single-tone
        // and per-tone bracket labels.
        Bindings.check(fftSnapBtn, prefs.genSnapToFftBinProperty());
        Bindings.onChange(group, prefs.genSnapToFftBinProperty(), v -> {
            updateFreqLabel();
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
        sweepStartField = new NumericStepField(sweepPanel, UnitFamily.FREQUENCY,
                GEN_FREQ_MIN_HZ, currentOutputSampleRate() / 2.0, FREQ_MAX_DECIMALS, 120);
        sweepStartField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepStartField.setToolTipText(I18n.t("generator.sweep.startFreq.tooltip"));
        Bindings.stepField(sweepStartField, prefs.genSweepFreqStartHzProperty());

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.stopFreq"));
        sweepEndField = new NumericStepField(sweepPanel, UnitFamily.FREQUENCY,
                GEN_FREQ_MIN_HZ, currentOutputSampleRate() / 2.0, FREQ_MAX_DECIMALS, 120);
        sweepEndField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepEndField.setToolTipText(I18n.t("generator.sweep.stopFreq.tooltip"));
        Bindings.stepField(sweepEndField, prefs.genSweepFreqEndHzProperty());

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.duration"));
        sweepDurationField = new NumericStepField(sweepPanel, UnitFamily.TIME,
                0.001, TIME_MAX_SEC, TIME_MAX_DECIMALS, 120);
        sweepDurationField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepDurationField.setToolTipText(I18n.t("generator.sweep.duration.tooltip"));
        Bindings.stepField(sweepDurationField, prefs.genSweepDurationSecProperty());

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.fadeIn"));
        sweepFadeInField = new NumericStepField(sweepPanel, UnitFamily.TIME,
                0, TIME_MAX_SEC, TIME_MAX_DECIMALS, 120);
        sweepFadeInField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepFadeInField.setToolTipText(I18n.t("generator.sweep.fadeIn.tooltip"));
        Bindings.stepField(sweepFadeInField, prefs.genSweepFadeInSecProperty());

        new Label(sweepPanel, SWT.NONE).setText(I18n.t("generator.sweep.fadeOut"));
        sweepFadeOutField = new NumericStepField(sweepPanel, UnitFamily.TIME,
                0, TIME_MAX_SEC, TIME_MAX_DECIMALS, 120);
        sweepFadeOutField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sweepFadeOutField.setToolTipText(I18n.t("generator.sweep.fadeOut.tooltip"));
        Bindings.stepField(sweepFadeOutField, prefs.genSweepFadeOutSecProperty());

        new Label(sweepPanel, SWT.NONE).setText("");   // spacer for col 0
        sweepLoopBtn = new Button(sweepPanel, SWT.CHECK);
        sweepLoopBtn.setText(I18n.t("generator.sweep.loop"));
        sweepLoopBtn.setToolTipText(I18n.t("generator.sweep.loop.tooltip"));
        sweepLoopBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.check(sweepLoopBtn, prefs.genSweepLoopProperty());

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
        dualToneAmp1Field = new NumericStepField(dualTonePanel, UnitFamily.PERCENT,
                DUTY_MIN_PCT, DUTY_MAX_PCT, PERCENT_MAX_DECIMALS, 160);
        dualToneAmp1Field.setValue(initAmp1);
        dualToneAmp1Field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        dualToneAmp1Field.setToolTipText(I18n.t("generator.dualTone.amp1.tooltip"));

        // --- Freq 2 amplitude % (label above field).
        Label dtAmp2Label = new Label(dualTonePanel, SWT.NONE);
        dtAmp2Label.setText(I18n.t("generator.dualTone.amp2"));
        dtAmp2Label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        dualToneAmp2Field = new NumericStepField(dualTonePanel, UnitFamily.PERCENT,
                DUTY_MIN_PCT, DUTY_MAX_PCT, PERCENT_MAX_DECIMALS, 160);
        dualToneAmp2Field.setValue(clampPct(100.0 - initAmp1));
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
        boolean initialIsDualTone = initialForm.isDualTone();
        setRegularFreqRowVisible(!initialIsSweep && !initialIsDualTone);
        setDualToneFreqRowsVisible(initialIsDualTone);
        setSnapBtnVisible(!initialIsSweep);   // snap applies to SINE AND DUAL_TONE
        setSweepPanelVisible(initialIsSweep);
        setDualTonePanelVisible(initialIsDualTone);

        // Form change: persist, then adjust the pane.  The engine side —
        // live-swap vs. stop+start (sweep / dual-tone set up dedicated DDS
        // state) and the FFT-invalidation publish — runs in the
        // controller's genSignalForm subscription the moment the pref is
        // written; everything below is visuals.
        formCombo.addSelectionListener(e -> {
            GenSignalForm f = formCombo.getSelectedForm();
            boolean wasRunning = controller.isRunning();
            prefs.setGenSignalForm(f);
            boolean newIsSweep    = f == GenSignalForm.LINEAR_SWEEP || f == GenSignalForm.LOG_SWEEP;
            boolean newIsDualTone = f.isDualTone();
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
            freqField.setEnabled(!newIsSweep && !newIsDualTone && f.isPeriodic());
            updateDutyFieldEnabled(f);
            // Reload duty field from the appropriate pref so RECTANGLE and
            // TRIANGLE each remember their own duty independently.
            reloadDutyForForm(f);
            // Parameter panels: each visible only for its own form.
            setSweepPanelVisible(newIsSweep);
            setDualTonePanelVisible(newIsDualTone);
            // Corrections row tracks the form: show its .dpd slot (or empty +
            // disabled for non-compensated forms).
            refreshCorrectionsRow();
            // Different forms get different bracket annotations (or none).
            updateFreqLabel();
            updateDutyLabel();
            // A not-live-swappable change restarted the generator inside
            // the pref write above — surface a failed restart.
            syncPlayButtonVisuals();
            if (wasRunning && !controller.isRunning()) {
                String err = controller.getLastStartError();
                if (err != null) {
                    Dialogs.error(group.getShell(), I18n.t("generator.error.restart"), err);
                }
            }
        });

        // --------------------------------------------------------- Amplitude
        addRowLabel(group, I18n.t("generator.amplitudeRms"));
        ampField = new NumericStepField(group, UnitFamily.AMPLITUDE,
                AMP_MIN_VRMS, prefs.getDacFsVoltageAmpl(), AMP_MAX_DECIMALS, 160);
        ampField.setLayoutData(fillH());
        ampField.setToolTipText(I18n.t("generator.amplitudeRms.tooltip"));
        // The field holds canonical Vrms (unit parsing / display switching is
        // internal) — two-way bind it like the frequency; live-apply and the
        // FFT-invalidation publish are the controller's subscription.
        Bindings.stepField(ampField, prefs.genAmplitudeVrmsProperty());
        // dBV display choice: seed from the persisted pref and persist the
        // user's typed unit (the field fires on display-unit changes too).
        ampField.setLogDisplay(prefs.isGenAmplitudeDbvDisplay());
        ampField.addSelectionListener(e ->
                prefs.setGenAmplitudeDbvDisplay(ampField.isLogDisplay()));
        // A DAC recalibration (Calibrate DAC dialog) changes the output full-scale:
        // recompute the running generator's amplitude against it so the commanded
        // Vrms still holds (no restart, controller subscription) — the pane
        // only moves the field's ceiling with the new full-scale.
        Bindings.onChange(group, prefs.dacFsVoltageAmplProperty(), ampField::setMax);

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
        dutyField = new NumericStepField(group, UnitFamily.PERCENT,
                DUTY_MIN_PCT, DUTY_MAX_PCT, PERCENT_MAX_DECIMALS, 160);
        dutyField.setValue(initialDutyPct);
        dutyField.setLayoutData(fillH());
        dutyField.setToolTipText(I18n.t("generator.dutyCycle.tooltip"));
        // Write only the active form's duty pref — the controller's per-pref
        // subscription live-applies it and publishes the FFT invalidation.
        dutyField.addSelectionListener(e -> {
            double frac = dutyField.getValue() / 100.0;
            if (formCombo.getSelectedForm() == GenSignalForm.TRIANGLE) {
                prefs.setGenTriangleDuty(frac);
            } else {
                prefs.setGenRectangleDuty(frac);
            }
            updateDutyLabel();
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
        ditherCombo.addListener(SWT.Selection, e ->
                prefs.setGenDitherBits(ditherBits[ditherCombo.getSelectionIndex()]));

        // ------------------------------------------------------- Corrections
        addRowLabel(group, I18n.t("generator.corrections"));
        Composite corrRow = new Composite(group, SWT.NONE);
        GridLayout corrGl = new GridLayout(3, false);
        corrGl.marginWidth = 0; corrGl.marginHeight = 0;
        corrGl.horizontalSpacing = 4;
        corrRow.setLayout(corrGl);
        corrRow.setLayoutData(fillH());

        correctionsField = new Text(corrRow, SWT.BORDER | SWT.READ_ONLY);
        GridData corrGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        corrGd.widthHint = 220;
        correctionsField.setLayoutData(corrGd);
        // Follow external changes to either .dpd slot — the predistortion
        // wizard's Apply writes the saved path here, and the field always shows
        // whichever slot matches the current form, without the user re-browsing.
        Bindings.onChange(group, prefs.genDpdProperty(),     v -> refreshCorrectionsRow());
        Bindings.onChange(group, prefs.genDpdDualProperty(), v -> refreshCorrectionsRow());

        corrBrowseBtn = new Button(corrRow, SWT.PUSH);
        corrBrowseBtn.setImage(folderOpenIcon);
        GridData corrBrowseGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        corrBrowseGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        corrBrowseBtn.setLayoutData(corrBrowseGd);
        corrBrowseBtn.setToolTipText(I18n.t("generator.corrections.browse"));
        corrBrowseBtn.addListener(SWT.Selection, e -> openCorrectionsBrowse());

        // Clear (×) — unloads the correction file so the field empties and the
        // compensated form stops pre-distorting.
        corrClearBtn = new Button(corrRow, SWT.PUSH);
        Image corrXmark = IconUtils.icon(corrRow.getDisplay(), Icon.RECTANGLE_XMARK);
        if (corrXmark != null) corrClearBtn.setImage(corrXmark);
        GridData corrClearGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        corrClearGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        corrClearBtn.setLayoutData(corrClearGd);
        corrClearBtn.setToolTipText(I18n.t("generator.corrections.clear"));
        corrClearBtn.addListener(SWT.Selection, e -> clearCorrections());
        // Initial state for the current form (path + enablement).
        refreshCorrectionsRow();

        // ----- Duration (seconds, used by Save WAV) ---------------------
        addRowLabel(group, I18n.t("generator.duration"));
        durationField = new NumericStepField(group, UnitFamily.TIME,
                0.001, TIME_MAX_SEC, TIME_MAX_DECIMALS, 160);
        durationField.setLayoutData(fillH());
        durationField.setToolTipText(I18n.t("generator.duration.tooltip"));
        // Pure persisted value (read only by Save-WAV) — two-way bind with
        // no side-effects; the bind auto-persists via requestSave().
        Bindings.stepField(durationField, prefs.genWavDurationSecondsProperty());

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
        GridData saveToGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        saveToGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        saveTo.setLayoutData(saveToGd);
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
        Bindings.check(playFromLoopBtn, prefs.genPlayFromLoopProperty());
        // The live-apply to a running playback (loop change takes effect at
        // the next EOF) is the controller's subscription.

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
        GridData loadFromGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        loadFromGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        loadFrom.setLayoutData(loadFromGd);
        loadFrom.setToolTipText(I18n.t("generator.loadFrom.browse"));
        loadFrom.addListener(SWT.Selection, e -> openPlayFromBrowse());

        playFromBtn = new Button(playFromRow, SWT.PUSH);
        playFromBtn.setImage(tinyPlayDimImg);
        GridData playFromGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        playFromGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        playFromBtn.setLayoutData(playFromGd);
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
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.FILE_PLAY_STOPPED, filePlayStoppedListener);

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
        GridData calBtnGd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        calBtnGd.widthHint = IconUtils.ACTION_BUTTON_PX;
        calBtnGd.heightHint = IconUtils.ACTION_BUTTON_PX;
        calibrateDacBtn.setLayoutData(calBtnGd);
        calibrateDacBtn.addListener(SWT.Selection, e -> openDacCalibrationDialog());

        playBtn = new Button(playRow, SWT.PUSH);
        GridData playBtnGd = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        playBtnGd.widthHint = IconUtils.ACTION_BUTTON_PX;
        playBtnGd.heightHint = IconUtils.ACTION_BUTTON_PX;
        playBtn.setLayoutData(playBtnGd);
        playBtn.setImage(playDimImg);                                  // start dim
        playBtn.setToolTipText(I18n.t("generator.play.start"));
        playBtn.addListener(SWT.Selection, e -> {
            if (controller.isRunning()) {
                controller.stop();
                syncPlayButtonVisuals();
            } else {
                // Generator DDS and Play-from-file share the audio output
                // device — start() stops a running file playback itself;
                // re-sync both buttons' visuals from the controller state.
                controller.start();
                syncPlayButtonVisuals();
                syncFilePlayVisuals();
                if (!controller.isRunning()) {
                    String err = controller.getLastStartError();
                    Dialogs.error(group.getShell(),
                            I18n.t("generator.error.start"),
                            err != null ? err : "Unknown error starting the generator.");
                }
            }
        });

        // FFT length changes re-snap the running tone in the controller;
        // the pane only follows with its bracket labels (they show the
        // bin-snapped frequency).  Stored as a field so the dispose
        // listener below can unsubscribe and let the bus release its
        // reference to this pane.
        fftLengthListener = ignored -> {
            if (group.isDisposed()) return;
            updateFreqLabel();
            updateDualToneFreqLabels();
        };
        bus.subscribe(Events.FFT_LENGTH_CHANGED, fftLengthListener);
        freqRespStartedListener = ignored -> onFreqRespMeasurementStarted();
        freqRespStoppedListener = ignored -> onFreqRespMeasurementStopped();
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED, freqRespStartedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, freqRespStoppedListener);
        // Audio-format edits (Preferences OK, UI thread) move the Nyquist
        // ceiling of every frequency field — re-pull it from the committed
        // prefs.  The fields re-clamp and echo a clamped value back to their
        // bound preference if the rate dropped below the entered frequency.
        audioFormatListener = ignored -> {
            if (group.isDisposed()) return;
            double nyquist = currentOutputSampleRate() / 2.0;
            freqField.setMax(nyquist);
            dualToneFreq1Field.setMax(nyquist);
            dualToneFreq2Field.setMax(nyquist);
            sweepStartField.setMax(nyquist);
            sweepEndField.setMax(nyquist);
        };
        bus.subscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);

        // Dispose-time: detach the pane's own bus subscriptions and tear
        // down the icon cache owned by this pane's display.  The injected
        // controller deliberately keeps running — a content rebuild
        // (language / font change) must not silence the tone; it is shut
        // down by UIEngines at application exit.
        group.addDisposeListener(e -> {
            bus.unsubscribe(Events.FFT_LENGTH_CHANGED,            fftLengthListener);
            bus.unsubscribe(Events.FILE_PLAY_STOPPED,             filePlayStoppedListener);
            bus.unsubscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
            bus.unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);
            bus.unsubscribe(Events.AUDIO_FORMAT_CHANGED,          audioFormatListener);
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

        // The injected controller survives content rebuilds — when this
        // pane is a rebuilt instance the tone / file playback may already
        // be running; light the Play LEDs + ON-AIR banner accordingly.
        syncPlayButtonVisuals();
        syncFilePlayVisuals();

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

    /** True while the DDS tone is playing. */
    public boolean isToneRunning() {
        return controller.isRunning();
    }

    /** Programmatically starts the DDS tone and syncs the Play button +
     *  ON-AIR visuals — the {@code gui.automation} scripts' Play.  Unlike
     *  the Play-button click a failure is only logged: a modal error
     *  dialog would hang an unattended run.  Callers can check
     *  {@link #isToneRunning()}. */
    public void startTone() {
        controller.start();
        syncPlayButtonVisuals();
        syncFilePlayVisuals();
        if (!controller.isRunning() && log.isWarnEnabled()) {
            log.warn("Generator start failed: {}", controller.getLastStartError());
        }
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
        boolean fileWasRunning = controller.isFilePlaying();
        if (genWasRunning || fileWasRunning) {
            stopOnAirBlink();
        }
        controller.stopEngines();
        syncPlayButtonVisuals();
        syncFilePlayVisuals();
        return () -> {
            if (genWasRunning) {
                controller.start();
                syncPlayButtonVisuals();
                if (!controller.isRunning()) {
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

    /** Records the pane's current pixel width — called by the host
     *  {@code SashForm} sash filter on every drag.  Values below
     *  {@link #MIN_WIDTH_PX} are ignored so a transient drag past the
     *  minimum doesn't corrupt the persisted width. */
    public void setPaneWidthPx(int px) {
        if (px >= MIN_WIDTH_PX) paneWidthPx = px;
    }

    /** Generator-pane collapse extra: snapshot the pixel width on the way
     *  down so {@link #onExpanding()} can restore it — the parent
     *  {@code SashForm} reads {@link #getPaneWidthPx()} for the weights.
     *  The child hide/restore itself is the base {@code setCollapsed}. */
    @Override
    protected void onCollapsing() {
        if (paneWidthPx >= MIN_WIDTH_PX) preCollapseWidthPx = paneWidthPx;
    }

    @Override
    protected void onExpanding() {
        if (preCollapseWidthPx >= MIN_WIDTH_PX) paneWidthPx = preCollapseWidthPx;
        else if (paneWidthPx < MIN_WIDTH_PX)    paneWidthPx = MIN_WIDTH_PX;
    }

    /** {@link Events#FREQRESP_MEASUREMENT_STARTED} handler — the controller
     *  stops both engines in its own subscription; here only the visuals:
     *  dim and gray both Play buttons while the sweep drives the DAC. */
    private void onFreqRespMeasurementStarted() {
        if (group.isDisposed()) return;
        stopOnAirBlink();
        syncPlayButtonVisuals();
        syncFilePlayVisuals();
        if (playBtn      != null && !playBtn.isDisposed())      playBtn.setEnabled(false);
        if (playFromBtn  != null && !playFromBtn.isDisposed())  playFromBtn.setEnabled(false);
    }

    /** Counterpart that re-enables both Play buttons after the sweep. */
    private void onFreqRespMeasurementStopped() {
        if (group.isDisposed()) return;
        if (playBtn     != null && !playBtn.isDisposed())     playBtn.setEnabled(true);
        if (playFromBtn != null && !playFromBtn.isDisposed()) playFromBtn.setEnabled(true);
    }

    /** Mirrors the DDS engine state on the main Play button and the ON-AIR
     *  banner — called after every controller command that may have started
     *  or stopped the tone (including implicitly, e.g. a form change
     *  restart or file playback claiming the device). */
    private void syncPlayButtonVisuals() {
        if (playBtn == null || playBtn.isDisposed()) return;
        boolean running = controller.isRunning();
        playBtn.setImage(running ? playLitImg : playDimImg);
        playBtn.setToolTipText(I18n.t(running ? "generator.play.stop" : "generator.play.start"));
        if (running) {
            startOnAirBlink();
        } else if (!controller.isFilePlaying()) {
            stopOnAirBlink();
        }
    }

    /** Mirrors the file-playback engine state on the small play LED. */
    private void syncFilePlayVisuals() {
        if (playFromBtn == null || playFromBtn.isDisposed()) return;
        boolean playing = controller.isFilePlaying();
        playFromBtn.setImage(playing ? tinyPlayLitImg : tinyPlayDimImg);
        playFromBtn.setToolTipText(I18n.t(playing ? "generator.loadFrom.stop"
                                                  : "generator.loadFrom.play"));
        if (playing) startOnAirBlink();
    }

    /**
     * Opens the DAC calibration dialog.  The dialog shows the
     * currently-commanded full-scale Vrms and lets the user enter the
     * voltage actually measured at the DAC output.  On accept, the new
     * full-scale is persisted to {@link Preferences} — the generator reads
     * {@link Preferences#getDacFsVoltageAmpl()} directly.
     */
    private void openDacCalibrationDialog() {
        Shell parent = (group == null || group.isDisposed()) ? null : group.getShell();
        if (parent == null) return;
        Preferences prefs = Preferences.instance();
        final double configuredVrms = prefs.getGenAmplitudeVrms();
        final double oldFs          = prefs.getDacFsVoltageAmpl();
        new DacCalibrationDialog(parent, configuredVrms, measuredVrms -> {
            // The DAC was commanded to output `configuredVrms` (computed
            // against the OLD DAC full-scale) and the user measured `measuredVrms`
            // at the output.  Output RMS scales linearly with FS, so the
            // true FS satisfies measured/configured = FS_true/FS_old.
            double newFs = oldFs * (measuredVrms / configuredVrms);
            prefs.setDacFsVoltageAmpl(newFs);
            // Writing the pref fires the dacFsVoltageRms binding, which recomputes
            // the running generator's amplitude against the new full-scale — so the
            // calibration takes effect immediately, without a restart.
        }).open();
    }

    // -------------------------------------------------------------------------
    // Frequency / duty correction labels (math lives in the controller)
    // -------------------------------------------------------------------------

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
            corrected = formatLabelHz(controller.correctedRectangleHz());
        } else if (form == GenSignalForm.SINE && fftSnapBtn.getSelection()) {
            corrected = formatLabelHz(controller.effectiveFrequency());
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
        // Only RECTANGLE is sample-quantised: its +1/−1 step edge can land only
        // ON a sample, so the emitted duty snaps to whole samples and the
        // bracket shows the adapted value.  TRIANGLE uses real continuous-phase
        // DDS (the samples ride the exact ramps, so the corner is sub-sample) —
        // its duty is exact and gets the plain label, like the non-duty forms.
        if (form != GenSignalForm.RECTANGLE) {
            dutyLabel.setText(I18n.t("generator.dutyCycle"));
            dutyLabel.getParent().layout();
            return;
        }
        int n = controller.periodSamples();
        int k = (int) Math.round(dutyField.getValue() / 100.0 * n);
        // Match the DDS kernel, which never collapses the pulse to a constant
        // level — at least one sample high and one low.  Without this clamp a
        // sub-one-sample duty rounds to 0 % in the label while the generator
        // actually emits ~one sample (the smallest achievable, f/fs).
        if (n > 1) k = Math.max(1, Math.min(n - 1, k));
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

    // -------------------------------------------------------------------------
    // Browse dialog (remembers its last folder separately from other dialogs)
    // -------------------------------------------------------------------------
    private void openCorrectionsBrowse() {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(group.getShell(), SWT.OPEN);
        fd.setText(I18n.t("generator.corrections.dialog"));
        fd.setFilterExtensions(new String[] { "*.dpd", "*.*" });
        fd.setFilterNames     (new String[] { "Predistortion (*.dpd)", "All files" });
        if (prefs.getGenDpdFolder() != null) {
            fd.setFilterPath(prefs.getGenDpdFolder());
        }
        String picked = fd.open();
        if (picked == null) return;
        // Store under the slot matching the current form (sine vs dual-tone .dpd).
        prefs.setGenDpd(prefs.getGenSignalForm(), picked);
        correctionsField.setText(picked);
        correctionsField.setToolTipText(picked);
        File parent = new File(picked).getParentFile();
        if (parent != null) prefs.setGenDpdFolder(parent.getAbsolutePath());
    }

    /** Unloads the {@code .dpd} for the current form: empties the path field and
     *  clears that form's preference so it no longer pre-distorts. */
    private void clearCorrections() {
        Preferences prefs = Preferences.instance();
        prefs.setGenDpd(prefs.getGenSignalForm(), null);
        correctionsField.setText("");
        correctionsField.setToolTipText(null);
    }

    /** Syncs the corrections row to the current form: shows that form's
     *  {@code .dpd} (the single-tone slot for {@code SINE_COMP}, the
     *  dual-tone slot for {@code DUAL_TONE_COMP}) and enables the
     *  field + browse/clear only for those two compensated forms — every other
     *  waveform has no predistortion file, so the row is emptied and disabled. */
    private void refreshCorrectionsRow() {
        if (correctionsField == null || correctionsField.isDisposed()) return;
        Preferences prefs = Preferences.instance();
        GenSignalForm f = prefs.getGenSignalForm();
        boolean compensated = f == GenSignalForm.SINE_COMP
                           || f == GenSignalForm.DUAL_TONE_COMP;
        String path = compensated ? nullToEmpty(prefs.getGenDpd(f)) : "";
        correctionsField.setText(path);
        correctionsField.setToolTipText(path.isEmpty() ? null : path);
        correctionsField.setEnabled(compensated);
        if (corrBrowseBtn != null && !corrBrowseBtn.isDisposed()) corrBrowseBtn.setEnabled(compensated);
        if (corrClearBtn  != null && !corrClearBtn.isDisposed())  corrClearBtn.setEnabled(compensated);
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
     *  and TRIANGLE so each form keeps its own remembered duty.  The field
     *  clamps to its own [0.001, 99.999] % bounds. */
    private void reloadDutyForForm(GenSignalForm form) {
        Preferences prefs = Preferences.instance();
        if (form == GenSignalForm.TRIANGLE) {
            dutyField.setValue(prefs.getGenTriangleDuty() * 100.0);
        } else if (form == GenSignalForm.RECTANGLE) {
            dutyField.setValue(prefs.getGenRectangleDuty() * 100.0);
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
            dualToneFreq1Label.setText(base1 + "  (" + formatLabelHz(snap1) + ")");
            dualToneFreq2Label.setText(base2 + "  (" + formatLabelHz(snap2) + ")");
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
        // The controller's split subscription live-applies both percentages
        // and publishes the FFT invalidation.
        prefs.setGenDualToneSplitPct(a1);
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
    }

    /**
     * Toggles file playback: if running, stops; otherwise starts on the
     * currently selected file with the saved In-loop flag.  Updates the
     * LED button image accordingly.
     */
    private void togglePlayFrom() {
        if (controller.isFilePlaying()) {
            controller.stopFilePlayback();
            syncFilePlayVisuals();
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
        // startFilePlayback stops the DDS tone itself (shared output
        // device) — re-sync both buttons from the controller state.
        controller.startFilePlayback(new File(path), prefs.isGenPlayFromLoop());
        syncPlayButtonVisuals();
        syncFilePlayVisuals();
        if (!controller.isFilePlaying()) {
            String err = controller.getFilePlayError();
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
    /** Writes the signal file via the controller; surfaces a failure
     *  reason in an error dialog. */
    private void saveWavNow() {
        Preferences prefs = Preferences.instance();
        String path = prefs.getGenWavPath();
        if (path == null || path.isEmpty()) {
            Dialogs.info(group.getShell(),
                    I18n.t("generator.error.save"),
                    I18n.t("generator.error.save.pickFirst"));
            return;
        }
        String err = controller.exportSignal(path);
        if (err != null) {
            Dialogs.error(group.getShell(),
                    I18n.t("generator.error.save.failed"),
                    I18n.t("generator.error.save.failedMessage", path, err));
        }
    }

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
