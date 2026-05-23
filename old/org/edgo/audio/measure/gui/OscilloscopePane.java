package org.edgo.audio.measure.gui;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

/**
 * Builds the entire oscilloscope pane (Group frame + main scope view +
 * condensed strip + bottom toolbar with its V/T/Trigger/Screenshot groups
 * and Record toggle).  Extracted from {@link MainWindow} so the live pane
 * can stay attached to the {@link org.eclipse.swt.custom.SashForm} while a
 * second instance is constructed inside a hidden offscreen Shell for
 * screenshot rendering — that way the screenshot is a real SWT layout at
 * the target dimensions (buttons stay at their native pixel size, the scope
 * view's canvas claims the extra space) instead of a bitmap scale.
 *
 * <p>This class owns no audio-capture state and wires no controller: the
 * caller is responsible for attaching an {@link OscilloscopeController} to
 * {@link #getView()} / {@link #getCondensed()} and for hooking the Record
 * toggle (the screenshot pane skips both).
 */
@Log4j2
public final class OscilloscopePane {

    /** Side length of every small in-group toggle button (px). */
    public  static final int SQUARE_BUTTON = 32;
    /** Side length of the Record toggle (px). */
    public  static final int TOGGLE_BUTTON = 48;

    private static final String[] VOLT_PER_DIV = {
            "1 μV/div", "2 μV/div", "5 μV/div",
            "10 μV/div", "20 μV/div", "50 μV/div",
            "100 μV/div", "200 μV/div", "500 μV/div",
            "1 mV/div", "2 mV/div", "5 mV/div",
            "10 mV/div", "20 mV/div", "50 mV/div",
            "100 mV/div", "200 mV/div", "500 mV/div",
            "1 V/div", "2 V/div", "5 V/div",
            "10 V/div", "20 V/div", "50 V/div",
            "100 V/div", "200 V/div", "500 V/div"
    };
    private static final String[] TIME_PER_DIV = {
            "10 μs/div", "20 μs/div", "50 μs/div",
            "100 μs/div", "200 μs/div", "500 μs/div",
            "1 ms/div", "2 ms/div", "5 ms/div",
            "10 ms/div", "20 ms/div", "50 ms/div",
            "100 ms/div", "200 ms/div", "500 ms/div",
            "1 s/div"
    };

    /** Bundle of image resources passed in by the owner (loaded once, shared). */
    public static final class Images {
        public final Image recordDim;
        public final Image recordLit;
        public final Image bluePlayLit;
        public final Image cameraIcon;
        public Images(Image recordDim, Image recordLit, Image bluePlayLit, Image cameraIcon) {
            this.recordDim   = recordDim;
            this.recordLit   = recordLit;
            this.bluePlayLit = bluePlayLit;
            this.cameraIcon  = cameraIcon;
        }
    }

    private final Group                  group;
    private final OscilloscopeView       view;
    private final CondensedView          condensed;
    private final Composite              toolbar;
    private final Button                 recordButton;
    private final GridData               condensedGd;
    private final Images                 images;
    /**
     * Capture controller — owned and wired by the live pane.  {@code null}
     * on the screenshot-only pane variant ({@code liveCapture = false}),
     * which renders without ever opening an audio device.
     */
    private final OscilloscopeController controller;

    /**
     * Constructs the pane and all its children.
     * @param parent              host composite for the pane's Group.
     * @param images              icon bundle (Record dim/lit, Play, camera).
     * @param liveCapture         when {@code true}, creates an
     *                            {@link OscilloscopeController}, wires the
     *                            Record toggle to it (showing an error
     *                            MessageBox on capture-open failure), and
     *                            stops the controller on Group dispose.
     *                            The screenshot pane variant passes
     *                            {@code false}.
     * @param onScreenshotRequest invoked when the user clicks the camera
     *                            button on the toolbar; the screenshot pane
     *                            variant passes a no-op runnable here.
     */
    public OscilloscopePane(Composite parent, Images images, boolean liveCapture,
                            Runnable onScreenshotRequest) {
        this.images = images;
        group = new Group(parent, SWT.NONE);
        // Chevron prefix hints that the title bar is clickable to collapse /
        // expand the pane.  The owner can replace this text on toggle.
        group.setText("▼ Oscilloscope");
        group.setLayout(paneLayout());

        view = new OscilloscopeView(group);
        view.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Condensed overview strip just above the toolbar.  Its heightHint is
        // recomputed on every pane resize so the strip stays roughly 1.2 of
        // 11.2 divisions tall (the area above the toolbar).
        condensed = new CondensedView(group);
        condensedGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        condensed.setLayoutData(condensedGd);

        toolbar = paneToolbar(group, 5);
        buildVerticalGroup(toolbar);
        buildHorizontalGroup(toolbar);
        buildTriggerGroup(toolbar, images.bluePlayLit, view::armSingle);
        buildScreenshotGroup(toolbar, images.cameraIcon, onScreenshotRequest);
        recordButton = addToggleButton(toolbar, images.recordDim, images.recordLit);

        // Capture lifecycle — only on the live pane.  The screenshot pane
        // mirrors the live Record-button visual state externally via
        // setRecordingState() but never opens an audio device of its own.
        if (liveCapture) {
            controller = new OscilloscopeController(view, condensed);
            wireRecordButton();
            group.addDisposeListener(e -> controller.stop());
        } else {
            controller = null;
        }

        // Auto-size the condensed strip on every Group resize.
        group.addControlListener(ControlListener.controlResizedAdapter(e -> {
            int paneH    = group.getSize().y;
            int toolbarH = toolbar.getSize().y;
            int avail    = Math.max(0, paneH - toolbarH);
            int desired  = Math.max(20, (int) Math.round(avail * 1.2 / 11.2));
            if (condensedGd.heightHint != desired) {
                condensedGd.heightHint = desired;
                group.layout(true, true);
            }
        }));
    }

    /**
     * Wires the Record toggle to {@link #controller}: pressing it starts
     * capture; on failure the button snaps back to off and a MessageBox
     * shows the human-readable reason from
     * {@link OscilloscopeController#getLastStartError()}.
     */
    private void wireRecordButton() {
        recordButton.addListener(SWT.Selection, e -> {
            if (recordButton.getSelection()) {
                controller.start();
                if (!controller.isRunning()) {
                    recordButton.setSelection(false);
                    recordButton.setImage(images.recordDim);
                    String err = controller.getLastStartError();
                    if (err != null) {
                        Shell parentShell = group.getShell();
                        MessageBox mb = new MessageBox(parentShell, SWT.ICON_ERROR | SWT.OK);
                        mb.setText("Cannot start recording");
                        mb.setMessage(err);
                        mb.open();
                    }
                }
            } else {
                controller.stop();
            }
        });
    }

    /**
     * Visually mirrors a foreign Record-button state on this pane without
     * driving the controller — used by the screenshot pane so its rendering
     * shows the same Record-LED state the user sees on the live pane.
     */
    public void setRecordingState(boolean recording) {
        recordButton.setSelection(recording);
        recordButton.setImage(recording ? images.recordLit : images.recordDim);
    }

    public Group                  getGroup()        { return group; }
    public OscilloscopeView       getView()         { return view; }
    public CondensedView          getCondensed()    { return condensed; }
    public Composite              getToolbar()      { return toolbar; }
    public Button                 getRecordButton() { return recordButton; }
    /** Returns the capture controller for the live pane, or {@code null} on the screenshot-only variant. */
    public OscilloscopeController getController()   { return controller; }

    // -------------------------------------------------------------------------
    // Toolbar group builders
    // -------------------------------------------------------------------------

    private void buildVerticalGroup(Composite parent) {
        Group g = groupCell(parent, "Vertical");
        g.setLayout(rowLayoutHorizontal(6));

        Preferences prefs = Preferences.instance();

        Button leftToggle = squareToggle(g, "L");
        leftToggle.setSelection(prefs.isOscLeftChannelEnabled());
        leftToggle.addListener(SWT.Selection, e -> {
            prefs.setOscLeftChannelEnabled(leftToggle.getSelection());
            prefs.save();
        });

        StepSelector leftScale = new StepSelector(g, VOLT_PER_DIV,
                prefs.getOscLeftVoltsPerDivIdx(), 90);
        leftScale.addSelectionListener(e -> {
            prefs.setOscLeftVoltsPerDivIdx(e.index);
            prefs.save();
        });

        Button leftAc = squareToggle(g, "AC");
        leftAc.setToolTipText("AC coupling for the left channel — removes DC offset from the trace only "
                + "(does not affect measurements).");
        leftAc.setSelection(prefs.isOscLeftAcMode());
        leftAc.addListener(SWT.Selection, e -> {
            prefs.setOscLeftAcMode(leftAc.getSelection());
            prefs.save();
        });

        Button rightToggle = squareToggle(g, "R");
        rightToggle.setSelection(prefs.isOscRightChannelEnabled());
        rightToggle.addListener(SWT.Selection, e -> {
            prefs.setOscRightChannelEnabled(rightToggle.getSelection());
            prefs.save();
        });

        StepSelector rightScale = new StepSelector(g, VOLT_PER_DIV,
                prefs.getOscRightVoltsPerDivIdx(), 90);
        rightScale.addSelectionListener(e -> {
            prefs.setOscRightVoltsPerDivIdx(e.index);
            prefs.save();
        });

        Button rightAc = squareToggle(g, "AC");
        rightAc.setToolTipText("AC coupling for the right channel — removes DC offset from the trace only "
                + "(does not affect measurements).");
        rightAc.setSelection(prefs.isOscRightAcMode());
        rightAc.addListener(SWT.Selection, e -> {
            prefs.setOscRightAcMode(rightAc.getSelection());
            prefs.save();
        });

        Image sincImg = createSincFractionImage(g.getDisplay());
        Button sincCheck = new Button(g, SWT.CHECK);
        sincCheck.setImage(sincImg);
        sincCheck.setToolTipText("Lanczos sinc interpolation (band-limited reconstruction). "
                + "When off, the trace and trigger fall back to linear interpolation between samples.");
        sincCheck.setSelection(prefs.isOscSincInterpEnabled());
        sincCheck.addListener(SWT.Selection, e -> {
            prefs.setOscSincInterpEnabled(sincCheck.getSelection());
            prefs.save();
        });
        sincCheck.addDisposeListener(e -> sincImg.dispose());
    }

    private void buildHorizontalGroup(Composite parent) {
        Group g = groupCell(parent, "Horizontal");
        g.setLayout(rowLayoutHorizontal(6));

        Preferences prefs = Preferences.instance();
        StepSelector timeScale = new StepSelector(g, TIME_PER_DIV,
                prefs.getOscTimePerDivIdx(), 90);
        timeScale.addSelectionListener(e -> {
            prefs.setOscTimePerDivIdx(e.index);
            prefs.save();
        });

        // Zero-width, SQUARE_BUTTON-tall spacer so the row measures at the
        // same 32-px height as the V-group's button row.
        Composite rowSpacer = new Composite(g, SWT.NONE);
        rowSpacer.setLayoutData(new RowData(0, SQUARE_BUTTON));
    }

    private void buildTriggerGroup(Composite parent, Image bluePlayLit, Runnable onSingleStart) {
        Group g = groupCell(parent, "Trigger");
        g.setLayout(rowLayoutHorizontal(10));

        Preferences prefs = Preferences.instance();

        Composite chSet = new Composite(g, SWT.NONE);
        chSet.setLayout(flushRowLayoutHorizontal(2));
        Button chL = squareToggle(chSet, "L");
        Button chR = squareToggle(chSet, "R");
        chL.setSelection(prefs.getOscTriggerChannel() == TriggerChannel.L);
        chR.setSelection(prefs.getOscTriggerChannel() == TriggerChannel.R);
        makeDependentGroup(chL, chR);
        chL.addListener(SWT.Selection,
                e -> { if (chL.getSelection()) { prefs.setOscTriggerChannel(TriggerChannel.L); prefs.save(); } });
        chR.addListener(SWT.Selection,
                e -> { if (chR.getSelection()) { prefs.setOscTriggerChannel(TriggerChannel.R); prefs.save(); } });

        Composite edgeSet = new Composite(g, SWT.NONE);
        edgeSet.setLayout(flushRowLayoutHorizontal(2));
        Button edgeRise = squareToggle(edgeSet, "↑");
        Button edgeFall = squareToggle(edgeSet, "↓");
        edgeRise.setSelection(prefs.getOscTriggerEdge() == TriggerEdge.RISE);
        edgeFall.setSelection(prefs.getOscTriggerEdge() == TriggerEdge.FALL);
        makeDependentGroup(edgeRise, edgeFall);
        edgeRise.addListener(SWT.Selection,
                e -> { if (edgeRise.getSelection()) { prefs.setOscTriggerEdge(TriggerEdge.RISE); prefs.save(); } });
        edgeFall.addListener(SWT.Selection,
                e -> { if (edgeFall.getSelection()) { prefs.setOscTriggerEdge(TriggerEdge.FALL); prefs.save(); } });

        Composite modeSet = new Composite(g, SWT.NONE);
        modeSet.setLayout(flushRowLayoutHorizontal(2));
        Button modeAuto   = squareToggle(modeSet, "A");
        Button modeNormal = squareToggle(modeSet, "N");
        Button modeSingle = squareToggle(modeSet, "S");
        modeAuto  .setToolTipText("Auto");
        modeNormal.setToolTipText("Normal");
        modeSingle.setToolTipText("Single");
        modeAuto  .setSelection(prefs.getOscTriggerMode() == TriggerMode.AUTO);
        modeNormal.setSelection(prefs.getOscTriggerMode() == TriggerMode.NORMAL);
        modeSingle.setSelection(prefs.getOscTriggerMode() == TriggerMode.SINGLE);
        makeDependentGroup(modeAuto, modeNormal, modeSingle);
        modeAuto.addListener(SWT.Selection,
                e -> { if (modeAuto.getSelection())   { prefs.setOscTriggerMode(TriggerMode.AUTO);   prefs.save(); } });
        modeNormal.addListener(SWT.Selection,
                e -> { if (modeNormal.getSelection()) { prefs.setOscTriggerMode(TriggerMode.NORMAL); prefs.save(); } });
        modeSingle.addListener(SWT.Selection,
                e -> { if (modeSingle.getSelection()) { prefs.setOscTriggerMode(TriggerMode.SINGLE); prefs.save(); } });

        Button startBtn = new Button(g, SWT.PUSH);
        startBtn.setImage(bluePlayLit);
        startBtn.setToolTipText("Start single capture (active only in Single trigger mode)");
        startBtn.setLayoutData(new RowData(SQUARE_BUTTON, SQUARE_BUTTON));
        startBtn.setEnabled(modeSingle.getSelection());
        startBtn.addListener(SWT.Selection, e -> onSingleStart.run());

        Runnable syncStart = () -> startBtn.setEnabled(modeSingle.getSelection());
        modeAuto  .addListener(SWT.Selection, e -> syncStart.run());
        modeNormal.addListener(SWT.Selection, e -> syncStart.run());
        modeSingle.addListener(SWT.Selection, e -> syncStart.run());
    }

    /** Single-button "Screenshot" group; click fires {@code onScreenshotRequest}. */
    private void buildScreenshotGroup(Composite parent, Image cameraIcon, Runnable onScreenshotRequest) {
        Group g = groupCell(parent, "Screenshot");
        g.setLayout(rowLayoutHorizontal(6));
        Button shotBtn = new Button(g, SWT.PUSH);
        shotBtn.setImage(cameraIcon);
        shotBtn.setToolTipText("Save a screenshot of the oscilloscope pane");
        int btnW = (int) Math.round(SQUARE_BUTTON * 1.27);
        shotBtn.setLayoutData(new RowData(btnW, SQUARE_BUTTON));
        shotBtn.addListener(SWT.Selection, e -> onScreenshotRequest.run());
    }

    /**
     * Paints a small image of {@code sin x / x} as a stacked fraction —
     * "sin x" over a horizontal bar over "x".  Used as the label for the
     * Vertical-group checkbox that toggles Lanczos sinc reconstruction.
     */
    private Image createSincFractionImage(Display display) {
        int w = 40, h = 28;
        Image img = new Image(display, w, h);
        GC gc = new GC(img);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(0, 0, w, h);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));

        Font font = new Font(display, "Serif", 9, SWT.ITALIC);
        gc.setFont(font);
        Point topSize = gc.textExtent("sin x");
        Point botSize = gc.textExtent("x");
        int barY = h / 2;
        int inset = 3;
        gc.drawText("sin x", (w - topSize.x) / 2, barY - topSize.y - 1, true);
        gc.drawLine(inset, barY, w - 1 - inset, barY);
        gc.drawText("x", (w - botSize.x) / 2, barY + 1, true);
        font.dispose();
        gc.dispose();
        return img;
    }

    // -------------------------------------------------------------------------
    // Layout / widget helpers (duplicated from MainWindow because the
    // oscilloscope pane has its own complete tree of widgets and doesn't
    // share state with the Generator / FFT panes).
    // -------------------------------------------------------------------------

    private GridLayout paneLayout() {
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 4;
        gl.marginHeight = 4;
        gl.verticalSpacing = 4;
        return gl;
    }

    private Composite paneToolbar(Composite pane, int columns) {
        Composite t = new Composite(pane, SWT.NONE);
        t.setLayoutData(new GridData(SWT.FILL, SWT.END, true, false));
        GridLayout gl = new GridLayout(columns, false);
        gl.marginWidth      = 0;
        gl.marginHeight     = 4;
        gl.horizontalSpacing = 6;
        t.setLayout(gl);
        return t;
    }

    private Button addToggleButton(Composite t, Image dim, Image lit) {
        Button btn = new Button(t, SWT.TOGGLE);
        btn.setImage(dim);
        GridData gd = new GridData(SWT.END, SWT.END, true, false);
        gd.widthHint  = TOGGLE_BUTTON;
        gd.heightHint = TOGGLE_BUTTON;
        btn.setLayoutData(gd);
        btn.addListener(SWT.Selection,
                e -> btn.setImage(btn.getSelection() ? lit : dim));
        return btn;
    }

    private RowLayout rowLayoutHorizontal(int spacing) {
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = spacing;
        rl.center  = true;
        return rl;
    }

    private RowLayout flushRowLayoutHorizontal(int spacing) {
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = spacing;
        rl.center  = true;
        rl.marginLeft   = 0;
        rl.marginRight  = 0;
        rl.marginTop    = 0;
        rl.marginBottom = 0;
        return rl;
    }

    private Group groupCell(Composite parent, String title) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(title);
        g.setLayoutData(new GridData(SWT.LEFT, SWT.FILL, false, false));
        return g;
    }

    private Button squareToggle(Composite parent, String label) {
        Button b = new Button(parent, SWT.TOGGLE);
        b.setText(label);
        b.setLayoutData(new RowData(SQUARE_BUTTON, SQUARE_BUTTON));
        return b;
    }

    private void makeDependentGroup(Button... buttons) {
        for (Button b : buttons) {
            b.addListener(SWT.Selection, e -> {
                if (!b.getSelection()) {
                    b.setSelection(true);
                    return;
                }
                for (Button other : buttons) {
                    if (other != b) other.setSelection(false);
                }
            });
        }
    }
}
