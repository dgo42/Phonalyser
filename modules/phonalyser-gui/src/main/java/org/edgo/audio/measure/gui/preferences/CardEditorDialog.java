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

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;

/**
 * Modal create / edit dialog for one {@link AudioDeviceProfile} (a physical
 * soundcard).  Mirrors {@code CalibrationDialog} in shape — a {@code final} class
 * wrapping a {@link Shell}, built entirely in the constructor, driven by
 * {@link #open()}.
 *
 * <p>The dialog covers the model 1:1.  The user edits: the card <em>name</em>;
 * the <em>directions</em> it is calibrated for (input only / output only / both);
 * a card-level <em>Mono / Stereo</em> switch (a mono card is mono in every
 * direction it has); the ONE list of device-name <em>match</em> entries (one per
 * line — the unified recognition-and-binding list, a card is chosen when any entry
 * is a case-insensitive substring of the live device name); and, per enabled
 * direction, the Stereo range <em>coupling</em> (Linked / Independent — shown only
 * when the card is Stereo) and the editable <em>calibration-from-device</em> flag
 * (device-owned full-scale, e.g. a QA40x).  The two channel controls map onto the
 * stored {@link DeviceChannelMode}: Mono&nbsp;&rarr;&nbsp;{@code MONO},
 * Stereo+Linked&nbsp;&rarr;&nbsp;{@code LINKED},
 * Stereo+Independent&nbsp;&rarr;&nbsp;{@code INDEPENDENT}.  {@link #open()} returns
 * the assembled profile on OK, or {@code null} on Cancel.
 *
 * <p>The dialog never mutates the {@code seed} it is handed — the constructor
 * only reads it to prefill the widgets, and OK builds a fresh profile (deep-copying
 * the seed's endpoints so the seed's calibrated ranges survive).  Directions the
 * user keeps retain their ranges; a newly enabled direction is seeded with one
 * {@code default} range from the passed-in global full-scale; a direction the user
 * turns off has its ranges dropped — and if any of those ranges was calibrated the
 * user is warned via the house confirm dialog before OK proceeds.
 */
public final class CardEditorDialog {

    /** Which endpoint blocks a card carries ranges for — the direction radios. */
    public enum Capability {
        INPUT_ONLY,
        OUTPUT_ONLY,
        BOTH;

        private Capability() {}

        /** Derives the capability of an existing profile from which endpoints
         *  actually carry ranges — the edit-form initial selection. */
        public static Capability of(AudioDeviceProfile p) {
            boolean in  = !p.getInput().getRanges().isEmpty();
            boolean out = !p.getOutput().getRanges().isEmpty();
            if (in && out) return BOTH;
            if (out)       return OUTPUT_ONLY;
            return INPUT_ONLY;
        }
    }

    /** Width (px) of the name field. */
    private static final int NAME_FIELD_WIDTH  = 220;
    /** Height (px) of the multi-line match list (roughly four rows). */
    private static final int MATCH_FIELD_HEIGHT = 76;

    private final Shell dialog;
    /** The single content composite holding every widget — the snapshot target
     *  for a help capture, since a top-level Shell prints blank on Windows (a
     *  Composite prints its children). */
    @Getter
    private final Composite content;
    private final AudioDeviceProfile seed;
    private final List<String> existingNames;
    private final String originalName;
    private final double inputSeedFsVrms;
    private final double outputSeedFsVrms;

    private final Text nameField;
    private final Text matchField;
    private final Button inputOnlyRadio;
    private final Button outputOnlyRadio;
    private final Button bothRadio;
    private final Button monoRadio;
    private final Button stereoRadio;
    private final DirectionDetail inputDetail;
    private final DirectionDetail outputDetail;

    private AudioDeviceProfile result;

    /**
     * @param parent            the owning shell
     * @param seed              the profile to prefill from (a fresh, name/match-seeded
     *                          profile for a create; the existing profile for an edit) —
     *                          never mutated
     * @param initial           the direction radios' initial selection
     * @param existingNames     every current card's logical name (for the uniqueness check)
     * @param originalName      the name being edited ({@code null} for a create), so an
     *                          unchanged edit name is not flagged as a duplicate
     * @param inputSeedFsVrms   full-scale (V RMS) seeded into a newly enabled input range
     * @param outputSeedFsVrms  full-scale (V RMS) seeded into a newly enabled output range
     */
    public CardEditorDialog(Shell parent, AudioDeviceProfile seed, Capability initial,
                            List<String> existingNames, String originalName,
                            double inputSeedFsVrms, double outputSeedFsVrms) {
        this.seed             = seed;
        this.existingNames    = existingNames;
        this.originalName     = originalName;
        this.inputSeedFsVrms  = inputSeedFsVrms;
        this.outputSeedFsVrms = outputSeedFsVrms;

        boolean editing = originalName != null;
        this.dialog = newShell(parent, editing ? "preferences.audio.card.edit" : "preferences.audio.card.new");
        this.content = newContent();

        Composite form = newForm();
        this.nameField = buildNameField(form);

        Group dirGroup = newRadioGroup("preferences.audio.card.direction",
                "preferences.audio.card.direction.tooltip");
        this.inputOnlyRadio  = buildRadio(dirGroup, "preferences.audio.card.direction.input");
        this.outputOnlyRadio = buildRadio(dirGroup, "preferences.audio.card.direction.output");
        this.bothRadio       = buildRadio(dirGroup, "preferences.audio.card.direction.both");
        selectDirection(initial);

        // Mono/Stereo is a CARD property (a mono card is mono in every direction it
        // has); the per-direction Linked/Independent coupling only exists when Stereo.
        Group channelsGroup = newRadioGroup("preferences.audio.card.channelMode",
                "preferences.audio.card.channelMode.tooltip");
        this.monoRadio   = buildRadio(channelsGroup, "preferences.audio.card.channelMode.mono");
        this.stereoRadio = buildRadio(channelsGroup, "preferences.audio.card.channelMode.stereo");
        selectMonoStereo(seedIsMono());

        this.matchField = buildMatchField();

        this.inputDetail  = buildDirectionDetail("preferences.input",
                seed.getInput().getChannels(),  seed.getInput().isCalibrationFromDevice());
        this.outputDetail = buildDirectionDetail("preferences.output",
                seed.getOutput().getChannels(), seed.getOutput().isCalibrationFromDevice());

        inputOnlyRadio.addListener(SWT.Selection,  e -> updateEnablement());
        outputOnlyRadio.addListener(SWT.Selection, e -> updateEnablement());
        bothRadio.addListener(SWT.Selection,       e -> updateEnablement());
        monoRadio.addListener(SWT.Selection,       e -> updateEnablement());
        stereoRadio.addListener(SWT.Selection,     e -> updateEnablement());
        updateEnablement();

        Button ok = buildButtonRow();
        dialog.setDefaultButton(ok);
    }

    private Shell newShell(Shell parent, String titleKey) {
        Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        ShellIcons.apply(shell);
        shell.setText(I18n.t(titleKey));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 0;
        gl.marginHeight = 0;
        shell.setLayout(gl);
        return shell;
    }

    /** The single content composite wrapping every widget, so a help capture can
     *  render the dialog via {@code Control.print} (a top-level Shell prints blank
     *  on Windows).  Carries the dialog padding the shell margins used to hold. */
    private Composite newContent() {
        Composite c = new Composite(dialog, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 16;
        gl.marginHeight = 16;
        gl.verticalSpacing = 10;
        c.setLayout(gl);
        return c;
    }

    /** The two-column top form holding the name field. */
    private Composite newForm() {
        Composite form = new Composite(content, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth  = 0;
        gl.marginHeight = 0;
        gl.horizontalSpacing = 8;
        form.setLayout(gl);
        form.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return form;
    }

    private Text buildNameField(Composite form) {
        Label lbl = new Label(form, SWT.NONE);
        lbl.setText(I18n.t("preferences.audio.card.name"));
        lbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Text field = new Text(form, SWT.BORDER | SWT.SINGLE);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = NAME_FIELD_WIDTH;
        field.setLayoutData(gd);
        if (seed.getName() != null) {
            field.setText(seed.getName());
            field.selectAll();
        }
        return field;
    }

    /** A horizontal radio-button group titled {@code titleKey} — shared by the
     *  direction (Input / Output / Both) and the card-level Mono / Stereo rows. */
    private Group newRadioGroup(String titleKey, String tooltipKey) {
        Group group = new Group(content, SWT.NONE);
        group.setText(I18n.t(titleKey));
        group.setToolTipText(I18n.t(tooltipKey));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 12;
        group.setLayout(rl);
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return group;
    }

    private Button buildRadio(Group group, String labelKey) {
        Button radio = new Button(group, SWT.RADIO);
        radio.setText(I18n.t(labelKey));
        return radio;
    }

    private void selectDirection(Capability initial) {
        inputOnlyRadio.setSelection(initial == Capability.INPUT_ONLY);
        outputOnlyRadio.setSelection(initial == Capability.OUTPUT_ONLY);
        bothRadio.setSelection(initial == Capability.BOTH);
    }

    /** Whether the seed profile is a mono card — any endpoint declared
     *  {@link DeviceChannelMode#MONO}.  A stereo card has both endpoints on a
     *  stereo mode (LINKED / INDEPENDENT). */
    private boolean seedIsMono() {
        return seed.getInput().getChannels() == DeviceChannelMode.MONO
                || seed.getOutput().getChannels() == DeviceChannelMode.MONO;
    }

    private void selectMonoStereo(boolean mono) {
        monoRadio.setSelection(mono);
        stereoRadio.setSelection(!mono);
    }

    /** The unified match list — one device-name entry per line, prefilled from the
     *  seed's {@code match} list (a create seeds the triggering device name). */
    private Text buildMatchField() {
        Label lbl = new Label(content, SWT.NONE);
        lbl.setText(I18n.t("preferences.audio.card.match"));
        lbl.setToolTipText(I18n.t("preferences.audio.card.match.tooltip"));
        lbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));

        Text field = new Text(content, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL);
        field.setToolTipText(I18n.t("preferences.audio.card.match.tooltip"));
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.heightHint = MATCH_FIELD_HEIGHT;
        field.setLayoutData(gd);
        field.setText(String.join(System.lineSeparator(), seed.getMatch()));
        return field;
    }

    /** One per-direction detail group: a Linked / Independent coupling combo (the
     *  range-coupling question, meaningful only when the card is Stereo) and an
     *  editable calibration-from-device checkbox, prefilled from that endpoint. */
    private DirectionDetail buildDirectionDetail(String titleKey, DeviceChannelMode mode,
                                                 boolean calFromDevice) {
        Group group = new Group(content, SWT.NONE);
        group.setText(I18n.t(titleKey));
        GridLayout gl = new GridLayout(2, false);
        gl.horizontalSpacing = 8;
        group.setLayout(gl);
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label lbl = new Label(group, SWT.NONE);
        lbl.setText(I18n.t("preferences.audio.card.coupling"));
        lbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Combo combo = new Combo(group, SWT.READ_ONLY);
        combo.add(I18n.t("preferences.audio.card.channelMode.linked"));       // index 0 → LINKED
        combo.add(I18n.t("preferences.audio.card.channelMode.independent"));  // index 1 → INDEPENDENT
        combo.setToolTipText(I18n.t("preferences.audio.card.channelMode.tooltip"));
        combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        combo.select(mode == DeviceChannelMode.INDEPENDENT ? 1 : 0);

        Button check = new Button(group, SWT.CHECK);
        check.setText(I18n.t("preferences.audio.card.calibrationFromDevice"));
        GridData cgd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        cgd.horizontalSpan = 2;
        check.setLayoutData(cgd);
        check.setSelection(calFromDevice);

        return new DirectionDetail(group, combo, check);
    }

    /** Enables each direction's detail group only when that direction is on (so a
     *  disabled direction's coupling / flag can't be edited — its endpoint is
     *  dropped on OK anyway); the coupling combo is further gated on the card being
     *  Stereo, since range coupling does not exist for a mono card. */
    private void updateEnablement() {
        boolean stereo = stereoRadio.getSelection();
        inputDetail.setEnabled(!outputOnlyRadio.getSelection(), stereo);
        outputDetail.setEnabled(!inputOnlyRadio.getSelection(), stereo);
    }

    /** Button row — OK on the right (default), Cancel to its left.  Returns the
     *  OK button for the shell default. */
    private Button buildButtonRow() {
        Composite buttons = new Composite(content, SWT.NONE);
        GridLayout bl = new GridLayout(2, true);
        bl.marginWidth  = 0;
        bl.marginHeight = 0;
        buttons.setLayout(bl);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText(I18n.t("common.cancel"));
        cancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancel.addListener(SWT.Selection, e -> dialog.close());

        Button ok = new Button(buttons, SWT.PUSH);
        ok.setText(I18n.t("common.ok"));
        ok.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ok.addListener(SWT.Selection, e -> onOk());
        return ok;
    }

    /** Validates the name, assembles a fresh profile and closes on success;
     *  a bad name (blank / duplicate) or a declined shrink warning keeps the
     *  dialog open. */
    private void onOk() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            Dialogs.error(dialog, I18n.t("preferences.audio.card.error.title"),
                    I18n.t("preferences.audio.card.error.empty"));
            return;
        }
        if (nameTaken(name)) {
            Dialogs.error(dialog, I18n.t("preferences.audio.card.error.title"),
                    I18n.t("preferences.audio.card.error.duplicate"));
            return;
        }

        boolean wantInput  = !outputOnlyRadio.getSelection();
        boolean wantOutput = !inputOnlyRadio.getSelection();
        if (droppingCalibrated(seed.getInput(), wantInput)
                || droppingCalibrated(seed.getOutput(), wantOutput)) {
            int answer = Dialogs.confirm(dialog,
                    I18n.t("preferences.audio.card.dropCalibrated.title"),
                    I18n.t("preferences.audio.card.dropCalibrated.message"));
            if (answer != SWT.YES) return;
        }

        AudioDeviceProfile out = new AudioDeviceProfile();
        out.setName(name);
        out.setMatch(parseMatch());

        DeviceEndpointConfig inputEp  = seed.getInput().deepCopy();
        DeviceEndpointConfig outputEp = seed.getOutput().deepCopy();
        // Mono → MONO on every enabled endpoint; Stereo → each direction's own
        // coupling selector (LINKED / INDEPENDENT).
        boolean mono = monoRadio.getSelection();
        inputEp.setChannels(mono ? DeviceChannelMode.MONO : inputDetail.coupling());
        outputEp.setChannels(mono ? DeviceChannelMode.MONO : outputDetail.coupling());
        inputEp.setCalibrationFromDevice(inputDetail.calFromDevice.getSelection());
        outputEp.setCalibrationFromDevice(outputDetail.calFromDevice.getSelection());
        applyCapability(inputEp,  wantInput,  inputSeedFsVrms);
        applyCapability(outputEp, wantOutput, outputSeedFsVrms);
        out.setInput(inputEp);
        out.setOutput(outputEp);

        result = out;
        dialog.close();
    }

    /** The match list as trimmed, non-blank, case-insensitively de-duplicated
     *  entries in typed order — the recognition/binding list of the built card. */
    private List<String> parseMatch() {
        List<String> entries = new ArrayList<>();
        for (String line : matchField.getText().split("\\R")) {
            String entry = line.trim();
            if (entry.isEmpty()) continue;
            boolean dup = false;
            for (String seen : entries) {
                if (seen.equalsIgnoreCase(entry)) { dup = true; break; }
            }
            if (!dup) entries.add(entry);
        }
        return entries;
    }

    /** True when {@code name} clashes (case-insensitive) with a card OTHER than
     *  the one being edited — so an unchanged edit name is allowed but a rename
     *  onto another card is rejected. */
    private boolean nameTaken(String name) {
        if (name.equalsIgnoreCase(originalName)) return false;
        for (String existing : existingNames) {
            if (name.equalsIgnoreCase(existing)) return true;
        }
        return false;
    }

    /** True when turning off {@code want} would delete an endpoint that carries
     *  at least one real (calibrated) range. */
    private boolean droppingCalibrated(DeviceEndpointConfig ep, boolean want) {
        if (want) return false;
        for (DeviceRange range : ep.getRanges()) {
            if (range.isCalibrated()) return true;
        }
        return false;
    }

    /** Enforces the chosen direction on one endpoint: an off direction drops its
     *  ranges and active labels; an on direction with no ranges is seeded with a
     *  single {@code default} range at {@code seedFs}. */
    private void applyCapability(DeviceEndpointConfig ep, boolean want, double seedFs) {
        if (!want) {
            ep.getRanges().clear();
            ep.setActiveRange(null);
            ep.setActiveRangeRight(null);
            return;
        }
        if (ep.getRanges().isEmpty()) {
            DeviceRange range = new DeviceRange();
            range.setLabel(I18n.t("preferences.audio.range.default"));
            range.setFsLeft(seedFs);
            range.setFsRight(seedFs);
            ep.getRanges().add(range);
            ep.setActiveRange(range.getLabel());
            ep.setActiveRangeRight(range.getLabel());
        }
    }

    /** Opens the dialog modally and pumps the parent display loop until closed.
     *  Returns the assembled profile on OK, or {@code null} on Cancel / close. */
    public AudioDeviceProfile open() {
        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        while (!dialog.isDisposed()) {
            if (!dialog.getDisplay().readAndDispatch()) dialog.getDisplay().sleep();
        }
        return result;
    }

    /** Shows the already-built dialog non-modally for a help capture: pack, centre
     *  and open, WITHOUT the blocking modal loop, so the automation can snapshot
     *  {@link #getContent()} and dispose it.  {@link #open()} is the normal
     *  (blocking) entry that returns the assembled profile. */
    public Shell showForCapture() {
        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        return dialog;
    }

    /** One direction's detail widgets — the Linked / Independent coupling combo and
     *  the editable calibration-from-device checkbox, plus their enclosing group so
     *  the whole block greys out together when the direction is off. */
    private static final class DirectionDetail {
        private final Group group;
        private final Combo couplingCombo;
        private final Button calFromDevice;

        private DirectionDetail(Group group, Combo couplingCombo, Button calFromDevice) {
            this.group         = group;
            this.couplingCombo = couplingCombo;
            this.calFromDevice = calFromDevice;
        }

        /** The Stereo range-coupling this direction is set to — index 1 is
         *  INDEPENDENT, anything else (index 0) is LINKED.  Only consulted when the
         *  card is Stereo; a Mono card maps to {@link DeviceChannelMode#MONO}
         *  regardless. */
        private DeviceChannelMode coupling() {
            return couplingCombo.getSelectionIndex() == 1
                    ? DeviceChannelMode.INDEPENDENT
                    : DeviceChannelMode.LINKED;
        }

        private void setEnabled(boolean directionOn, boolean stereo) {
            group.setEnabled(directionOn);
            couplingCombo.setEnabled(directionOn && stereo);
            calFromDevice.setEnabled(directionOn);
        }
    }
}
