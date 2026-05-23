package org.edgo.audio.measure.gui;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * The application's main window.  Owns the menu bar, the
 * generator / oscilloscope / FFT panes, and the per-pane bottom toolbars
 * carrying the LED-style Play / Record toggle buttons (plus the oscilloscope's
 * vertical / horizontal / trigger control groups).
 *
 * <p>Layout:
 * <pre>
 *   ┌──────────────┬───────────────────────┐
 *   │              │     Oscilloscope      │
 *   │  Generator   ├───────────────────────┤
 *   │              │         FFT           │
 *   └──────────────┴───────────────────────┘
 * </pre>
 * The {@link Display} lifecycle stays with the caller ({@link GuiMain}); the
 * window's resources (icons) are released when its shell is disposed.
 */
@Log4j2
public final class MainWindow {

    private static final int ICON_SIZE         = 33;   // play / record LED (large)
    private static final int SMALL_ICON_SIZE   = 26;   // start LED inside trigger group
    private static final int TOGGLE_BUTTON     = 48;   // size of the Play / Record toggle button on Generator / FFT panes

    private final Display display;
    private final Shell   shell;
    private SashForm      hSplit;     // generator | (osc / fft)
    private SashForm      vSplit;     // osc / fft
    private OscilloscopeController oscController;
    // References used to compose oscilloscope-pane screenshots at any resolution.
    private Group              oscPane;
    private OscilloscopeView   oscView;
    /** Image bundle shared between the live OscilloscopePane and the offscreen one used for screenshots. */
    private OscilloscopePane.Images oscImages;
    /** True when the oscilloscope pane is collapsed to just its title bar. */
    private boolean            oscCollapsed;
    /** SashForm weights snapshot taken at collapse time so {@link #toggleOscCollapse()} can restore them on expand. */
    private int[]              preCollapseVSplitWeights;
    /** Approximate pixel height of the Group's title bar — used as the SashForm weight for the collapsed pane. */
    private static final int   COLLAPSED_OSC_HEIGHT = 28;
    /** Title-bar Y region (pixels from the top of the Group) where mouse clicks toggle collapse. */
    private static final int   GROUP_TITLE_BAR_PX   = 22;

    public MainWindow(Display display) {
        this.display = display;
        this.shell   = new Shell(display);
        shell.setText("ADC Calibration");
        shell.setLayout(new FillLayout());

        buildMenuBar();

        // LED-style 33×33 icons painted with vertical-gradient 3D shading.
        // Each toggle button swaps between dim ("LED off") and lit ("LED on")
        // images as the user presses/releases.
        Image playDim     = createPlayLed(  0, 200,  60, false, ICON_SIZE);
        Image playLit     = createPlayLed(  0, 235,  90, true,  ICON_SIZE);
        Image recordDim   = createRecordLed(200,  40,  40, false, ICON_SIZE);
        Image recordLit   = createRecordLed(255,   0,   0, true,  ICON_SIZE);
        // Smaller play LED for the Start button inside the Trigger group so
        // it fits the same square footprint as the rest of the group's buttons.
        Image bluePlayLit = createPlayLed( 60, 130, 230, true,  SMALL_ICON_SIZE);
        // Camera icon is rendered from icons/camera.svg.  The button itself
        // matches the toolbar-group button height, but is a touch wider
        // because the SVG viewBox is ~1.27 : 1.
        Image cameraIcon  = SvgIcon.render(display, "/icons/camera.svg",
                (int) Math.round(SMALL_ICON_SIZE * 1.27), SMALL_ICON_SIZE);
        shell.addDisposeListener(e -> {
            playDim.dispose();
            playLit.dispose();
            recordDim.dispose();
            recordLit.dispose();
            bluePlayLit.dispose();
            cameraIcon.dispose();
        });

        buildPanes(playDim, playLit, recordDim, recordLit, bluePlayLit, cameraIcon);
        applyMinimumShellSize();
        applySavedSashWeights();
        registerStatePersistence();
    }

    /**
     * Computes the smallest shell size that fits the oscilloscope toolbar
     * without clipping and uses that as the shell's minimum size.  The
     * initial size is the saved window dimensions from
     * {@link Preferences} (if any, and at least the minimum), otherwise a
     * sensible default that's also at least the minimum.
     */
    private void applyMinimumShellSize() {
        Point natural = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        shell.setMinimumSize(natural);

        Preferences prefs = Preferences.instance();
        int savedW = prefs.getWindowWidth();
        int savedH = prefs.getWindowHeight();
        int w = savedW > 0 ? Math.max(natural.x, savedW) : Math.max(1280, natural.x);
        int h = savedH > 0 ? Math.max(natural.y, savedH) : Math.max(800, natural.y);
        shell.setSize(w, h);
    }

    /** Applies the saved sash positions to {@link #hSplit} / {@link #vSplit}. */
    private void applySavedSashWeights() {
        Preferences prefs = Preferences.instance();
        int[] hw = prefs.getHSplitWeights();
        if (isValidWeights(hw, 2)) hSplit.setWeights(hw);
        int[] vw = prefs.getVSplitWeights();
        if (isValidWeights(vw, 2)) vSplit.setWeights(vw);
    }

    private boolean isValidWeights(int[] arr, int expectedLen) {
        if (arr == null || arr.length != expectedLen) return false;
        int sum = 0;
        for (int v : arr) {
            if (v < 0) return false;
            sum += v;
        }
        return sum > 0;
    }

    /**
     * Tracks shell resizes so we always know the latest "normal" (non-maximised /
     * non-minimised) size, and registers a dispose listener that writes the
     * final window size + sash weights to {@link Preferences}.  We don't save
     * on every resize event because the dragging-the-edge case would write
     * hundreds of times per second; the dispose hook covers the user closing
     * the window, which is when the chosen layout is final.
     */
    private void registerStatePersistence() {
        Point initial = shell.getSize();
        int[] lastNormal = { initial.x, initial.y };

        shell.addControlListener(ControlListener.controlResizedAdapter(e -> {
            if (!shell.getMaximized() && !shell.getMinimized()) {
                Point s = shell.getSize();
                lastNormal[0] = s.x;
                lastNormal[1] = s.y;
            }
        }));

        shell.addDisposeListener(e -> {
            Preferences prefs = Preferences.instance();
            prefs.setWindowWidth (lastNormal[0]);
            prefs.setWindowHeight(lastNormal[1]);
            if (hSplit != null && !hSplit.isDisposed()) prefs.setHSplitWeights(hSplit.getWeights());
            if (vSplit != null && !vSplit.isDisposed()) prefs.setVSplitWeights(vSplit.getWeights());
            prefs.save();
        });
    }

    /** Shows the window and logs the start event.  Caller drives the SWT event loop. */
    public void open() {
        shell.open();
        log.info("GUI started.");
    }

    public boolean isDisposed() {
        return shell.isDisposed();
    }

    // -------------------------------------------------------------------------
    // Menu bar
    // -------------------------------------------------------------------------

    private void buildMenuBar() {
        Menu menuBar = new Menu(shell, SWT.BAR);
        shell.setMenuBar(menuBar);

        MenuItem fileCascade = new MenuItem(menuBar, SWT.CASCADE);
        fileCascade.setText("&File");
        Menu fileMenu = new Menu(shell, SWT.DROP_DOWN);
        fileCascade.setMenu(fileMenu);
        MenuItem exitItem = new MenuItem(fileMenu, SWT.PUSH);
        exitItem.setText("E&xit\tAlt+F4");
        exitItem.addListener(SWT.Selection, e -> shell.close());

        MenuItem toolsCascade = new MenuItem(menuBar, SWT.CASCADE);
        toolsCascade.setText("&Tools");
        Menu toolsMenu = new Menu(shell, SWT.DROP_DOWN);
        toolsCascade.setMenu(toolsMenu);
        MenuItem preferencesItem = new MenuItem(toolsMenu, SWT.PUSH);
        preferencesItem.setText("&Preferences...");
        preferencesItem.addListener(SWT.Selection, e -> {
            // Device enumeration inside the dialog (especially WDM-KS via
            // PortAudio) can disturb a running capture stream — samples stop
            // arriving at the SignalBuffer.  Pause the oscilloscope around
            // the dialog and resume on close so the user doesn't have to
            // toggle Record off and on manually.
            boolean wasRunning = oscController != null && oscController.isRunning();
            if (wasRunning) oscController.stop();
            new PreferencesDialog(shell).open(() -> {
                if (wasRunning) oscController.start();
            });
        });
    }

    // -------------------------------------------------------------------------
    // Panes
    // -------------------------------------------------------------------------

    private void buildPanes(Image playDim, Image playLit,
                            Image recordDim, Image recordLit,
                            Image bluePlayLit, Image cameraIcon) {
        hSplit = new SashForm(shell, SWT.HORIZONTAL);
        hSplit.setSashWidth(4);

        buildGeneratorPane(hSplit, playDim, playLit);

        vSplit = new SashForm(hSplit, SWT.VERTICAL);
        vSplit.setSashWidth(4);
        buildOscilloscopePane(vSplit, recordDim, recordLit, bluePlayLit, cameraIcon);
        buildFftPane(vSplit, recordDim, recordLit);

        vSplit.setWeights(new int[]{1, 1});
        hSplit.setWeights(new int[]{1, 3});

        // While the oscilloscope pane is collapsed the SashForm only knows
        // its weights, not its pixel size — so a window resize would scale
        // the collapsed pane proportionally and slowly grow it.  Re-pin the
        // weights to {COLLAPSED_OSC_HEIGHT, remaining-pixels} on every
        // resize so the collapsed pane stays at roughly the title-bar height.
        vSplit.addControlListener(ControlListener.controlResizedAdapter(e -> {
            if (oscCollapsed) {
                int total = Math.max(2, vSplit.getSize().y);
                int oscW  = Math.max(1, COLLAPSED_OSC_HEIGHT);
                int fftW  = Math.max(1, total - oscW);
                vSplit.setWeights(new int[]{ oscW, fftW });
            }
        }));
    }

    private void buildGeneratorPane(Composite parent, Image playDim, Image playLit) {
        Group pane = new Group(parent, SWT.NONE);
        pane.setText("Generator");
        pane.setLayout(paneLayout());

        // View area placeholder — content will land here as the generator UI grows.
        Composite view = new Composite(pane, SWT.NONE);
        view.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite toolbar = paneToolbar(pane, 1);
        addToggleButton(toolbar, playDim, playLit);
    }

    private void buildOscilloscopePane(Composite parent,
                                       Image recordDim, Image recordLit,
                                       Image bluePlayLit, Image cameraIcon) {
        this.oscImages = new OscilloscopePane.Images(recordDim, recordLit, bluePlayLit, cameraIcon);
        OscilloscopePane osp = new OscilloscopePane(parent, oscImages, /*liveCapture*/ true,
                this::openOscilloscopeScreenshotDialog);
        this.oscPane       = osp.getGroup();
        this.oscView       = osp.getView();
        this.oscController = osp.getController();

        // Clicks in the Group's native title-bar region toggle collapse.
        // (Collapse / expand is MainWindow state — the controller-and-record
        // wiring lives inside OscilloscopePane itself.)
        oscPane.addMouseListener(MouseListener.mouseDownAdapter(ev -> {
            if (ev.y < GROUP_TITLE_BAR_PX) toggleOscCollapse();
        }));
    }

    private void buildFftPane(Composite parent, Image recordDim, Image recordLit) {
        Group pane = new Group(parent, SWT.NONE);
        pane.setText("FFT");
        pane.setLayout(paneLayout());

        Composite view = new Composite(pane, SWT.NONE);
        view.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite toolbar = paneToolbar(pane, 1);
        addToggleButton(toolbar, recordDim, recordLit);
    }


    /**
     * Opens the screenshot dialog for the oscilloscope pane.  Initial
     * width/height come from preferences (last-used) and fall back to the
     * pane's current pixel size; the chosen size is persisted back on Copy
     * or Save.  The Save-As file dialog remembers the most recent folder.
     */
    private void openOscilloscopeScreenshotDialog() {
        if (oscPane == null || oscPane.isDisposed()) return;
        Rectangle b = oscPane.getBounds();
        Preferences prefs = Preferences.instance();
        new ScreenshotDialog(
                shell,
                prefs.getScreenshotWidth(),  prefs.getScreenshotHeight(),
                b.width, b.height,
                prefs.getScreenshotFolder(),
                this::renderOscilloscopePane,
                (w, h) -> {
                    prefs.setScreenshotWidth(w);
                    prefs.setScreenshotHeight(h);
                    prefs.save();
                },
                folder -> {
                    prefs.setScreenshotFolder(folder);
                    prefs.save();
                }
        ).open();
    }


    /**
     * Renders the oscilloscope pane into a fresh image at
     * {@code (targetW, targetH)} by constructing a brand-new
     * {@link OscilloscopePane} inside a hidden offscreen Shell, resizing that
     * Shell to the target dimensions, letting SWT lay out its children at
     * that size, and printing the result.  Because the chrome is laid out by
     * SWT (rather than bitmap-scaled), toolbar buttons stay at their native
     * pixel size with extra space distributed by the layout, and the scope
     * view's canvas claims all the remaining area.  No widget is stretched.
     *
     * <p>The screenshot pane is wired to the live {@link SignalBuffer} via
     * {@link OscilloscopeView#setBuffer(SignalBuffer)} so the trace, the
     * measurement table, and the condensed strip reflect the current input;
     * a no-op camera-button handler keeps the toolbar layout identical to
     * the live pane without re-opening the screenshot dialog.
     */
    private Image renderOscilloscopePane(Display d, int targetW, int targetH) {
        targetW = Math.max(1, targetW);
        targetH = Math.max(1, targetH);
        if (oscImages == null) {
            return new Image(d, targetW, targetH);
        }
        // Hidden Shell sized to the target — positioned far offscreen so it
        // never flashes onto the user's display.
        Shell offscreen = new Shell(d, SWT.NO_TRIM);
        offscreen.setLocation(-100000, -100000);
        offscreen.setLayout(new FillLayout());
        OscilloscopePane shotPane = new OscilloscopePane(offscreen, oscImages,
                /*liveCapture*/ false, () -> { /* no-op */ });
        Image output = new Image(d, targetW, targetH);
        try {
            // Wire the screenshot pane to the live signal so the trace and
            // measurement table render the same data the user sees.  The
            // measurement worker on the live view already populates the
            // buffer; the screenshot view just reads from it.
            if (oscView != null && !oscView.isDisposed()) {
                SignalBuffer liveBuffer = oscView.getBuffer();
                if (liveBuffer != null) {
                    shotPane.getView().setBuffer(liveBuffer);
                    shotPane.getCondensed().setBuffer(liveBuffer);
                }
                // The screenshot view has no measurement worker of its own
                // (we don't want a second thread reading from the live
                // buffer just to render one frame).  Copy the live view's
                // cached measurement snapshot + history so the screenshot's
                // measurement table renders the same values the user sees.
                shotPane.getView().copyMeasurementsFrom(oscView);
            }
            // Mirror the live Record-toggle visual state so the screenshot
            // doesn't show a dimmed Record LED while recording is active.
            shotPane.setRecordingState(oscController != null && oscController.isRunning());

            offscreen.setSize(targetW, targetH);
            offscreen.layout(true, true);
            offscreen.open();
            // open() schedules the shell visible but at -100000 it's offscreen
            // and not focused — needed so SWT actually paints children.
            offscreen.setVisible(false);
            // Force pending paint events to process at the new size.
            shotPane.getGroup().update();

            GC outGc = new GC(output);
            try {
                shotPane.getGroup().print(outGc);
            } finally {
                outGc.dispose();
            }
        } finally {
            offscreen.dispose();
        }
        return output;
    }

    // -------------------------------------------------------------------------
    // Layout / widget helpers
    // -------------------------------------------------------------------------

    /**
     * Collapses or expands the Oscilloscope pane.  When collapsed, the inner
     * children (view, condensed strip, toolbar) are excluded from the layout
     * and the parent {@link SashForm}'s weights are adjusted so the pane
     * shrinks to roughly the height of its native title bar — leaving the
     * "▶ Oscilloscope" header clickable so the user can expand again.  The
     * pre-collapse weights are restored on expand.
     */
    private void toggleOscCollapse() {
        if (vSplit == null || vSplit.isDisposed())   return;
        if (oscPane == null || oscPane.isDisposed()) return;
        oscCollapsed = !oscCollapsed;
        for (org.eclipse.swt.widgets.Control c : oscPane.getChildren()) {
            c.setVisible(!oscCollapsed);
            if (c.getLayoutData() instanceof GridData gd) {
                gd.exclude = oscCollapsed;
            }
        }
        if (oscCollapsed) {
            preCollapseVSplitWeights = vSplit.getWeights();
            int total = Math.max(2, vSplit.getSize().y);
            int oscW  = Math.max(1, COLLAPSED_OSC_HEIGHT);
            int fftW  = Math.max(1, total - oscW);
            vSplit.setWeights(new int[]{ oscW, fftW });
            oscPane.setText("▶ Oscilloscope");
        } else {
            oscPane.setText("▼ Oscilloscope");
            int[] restore = preCollapseVSplitWeights;
            if (restore != null && restore.length == 2 && restore[0] > 0 && restore[1] > 0) {
                vSplit.setWeights(restore);
            } else {
                vSplit.setWeights(new int[]{ 1, 1 });
            }
            preCollapseVSplitWeights = null;
        }
        oscPane.layout(true);
        vSplit.layout(true);
    }

    /** Outer layout for a pane: one column, view area on top, toolbar at bottom. */
    private GridLayout paneLayout() {
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 4;
        gl.marginHeight = 4;
        gl.verticalSpacing = 4;
        return gl;
    }

    /**
     * Creates a horizontal toolbar Composite as a child row of a pane,
     * laid out with a {@code GridLayout} of the given column count.  Using
     * GridLayout (instead of RowLayout) prevents items from wrapping onto a
     * second line if the toolbar is narrower than the sum of its contents —
     * combined with {@link #shell}'s minimum size set in
     * {@link #applyMinimumShellSize()} the contents stay on one line.
     */
    private Composite paneToolbar(Composite pane, int columns) {
        Composite toolbar = new Composite(pane, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.END, true, false));
        GridLayout gl = new GridLayout(columns, false);
        gl.marginWidth     = 0;
        gl.marginHeight    = 4;   // breathing room top/bottom so the row sits centred
        gl.horizontalSpacing = 6;
        toolbar.setLayout(gl);
        return toolbar;
    }

    /**
     * Adds the LED-style {@code SWT.TOGGLE} button to the toolbar's rightmost
     * cell.  The cell grabs all spare horizontal space (END alignment) so the
     * button is anchored to the right edge.  Used by the Generator and FFT
     * panes (the Oscilloscope pane has its own equivalent inside
     * {@link OscilloscopePane}).
     */
    private Button addToggleButton(Composite toolbar, Image dim, Image lit) {
        Button btn = new Button(toolbar, SWT.TOGGLE);
        btn.setImage(dim);
        GridData gd = new GridData(SWT.END, SWT.END, true, false);
        gd.widthHint  = TOGGLE_BUTTON;
        gd.heightHint = TOGGLE_BUTTON;
        btn.setLayoutData(gd);
        btn.addListener(SWT.Selection,
                e -> btn.setImage(btn.getSelection() ? lit : dim));
        return btn;
    }

    // -------------------------------------------------------------------------
    // Icon painting
    // -------------------------------------------------------------------------

    /**
     * Builds an {@code n × n} transparent image with alpha channel ready to
     * be painted by the LED creators.  Returned image owns its pixel buffer;
     * dispose at shell shutdown.
     */
    private Image newTransparentIcon(int size) {
        PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data = new ImageData(size, size, 32, palette);
        data.alphaData = new byte[size * size]; // fully transparent
        return new Image(display, data);
    }

    /**
     * Draws a round LED at the given pixel {@code size} with vertical-gradient
     * shading (lighter top → darker bottom), a darker outer rim, and (when
     * {@code lit}) a small white specular highlight near the top-left to
     * suggest a 3D dome.
     */
    private Image createRecordLed(int r, int g, int b, boolean lit, int size) {
        Image img = newTransparentIcon(size);
        GC gc = new GC(img);
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);

        int dim = lit ? 0 : 50;            // "off" LED is darkened to ~50 % intensity
        int br = scale(r, 100 - dim);
        int bg = scale(g, 100 - dim);
        int bb = scale(b, 100 - dim);

        Color rim = new Color(display, scale(br, 30), scale(bg, 30), scale(bb, 30));
        gc.setBackground(rim);
        gc.fillOval(0, 0, size, size);
        rim.dispose();

        int inset = 2;
        int diam  = size - 2 * inset;
        Path clip = new Path(display);
        clip.addArc(inset, inset, diam, diam, 0, 360);
        gc.setClipping(clip);
        Color top = new Color(display, lighten(br, 60), lighten(bg, 60), lighten(bb, 60));
        Color bot = new Color(display, scale(br, 60),   scale(bg, 60),   scale(bb, 60));
        gc.setForeground(top);
        gc.setBackground(bot);
        gc.fillGradientRectangle(inset, inset, diam, diam, true);
        top.dispose();
        bot.dispose();
        gc.setClipping((Path) null);
        clip.dispose();

        if (lit) {
            Color spec = new Color(display, 255, 255, 255);
            gc.setBackground(spec);
            gc.setAlpha(180);
            gc.fillOval(size / 4, size / 5, size / 3, size / 5);
            gc.setAlpha(255);
            spec.dispose();
        }

        gc.dispose();
        return img;
    }

    /**
     * Draws a right-pointing triangle "play" arrow at the given pixel
     * {@code size} with edge highlights and shadows to suggest 3D bevelling.
     * Same dim/lit semantics as {@link #createRecordLed}.
     */
    private Image createPlayLed(int r, int g, int b, boolean lit, int size) {
        Image img = newTransparentIcon(size);
        GC gc = new GC(img);
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);

        int dim = lit ? 0 : 50;
        int br = scale(r, 100 - dim);
        int bg = scale(g, 100 - dim);
        int bb = scale(b, 100 - dim);

        // Scale margins down for smaller icons so the triangle still fills nicely.
        int marginL = Math.max(2, size / 5);
        int marginV = Math.max(2, size / 8);
        int tipX    = size - Math.max(2, size / 8);
        int midY    = size / 2;
        int[] body  = { marginL, marginV,
                        marginL, size - marginV,
                        tipX,    midY };

        Color base = new Color(display, br, bg, bb);
        gc.setBackground(base);
        gc.fillPolygon(body);
        base.dispose();

        Path clip = new Path(display);
        clip.moveTo(body[0], body[1]);
        clip.lineTo(body[4], body[5]);
        clip.lineTo(body[0] + (body[4] - body[0]) / 2, body[5]);
        clip.close();
        gc.setClipping(clip);
        Color hi = new Color(display, lighten(br, 60), lighten(bg, 60), lighten(bb, 60));
        gc.setBackground(hi);
        gc.fillPolygon(body);
        hi.dispose();
        gc.setClipping((Path) null);
        clip.dispose();

        gc.setLineWidth(2);
        Color edgeHi  = new Color(display, lighten(br, 80), lighten(bg, 80), lighten(bb, 80));
        Color edgeLow = new Color(display, scale(br, 30),   scale(bg, 30),   scale(bb, 30));
        gc.setForeground(edgeHi);
        gc.drawLine(body[0], body[1], body[4], body[5]);
        gc.setForeground(edgeLow);
        gc.drawLine(body[2], body[3], body[4], body[5]);
        gc.drawLine(body[0], body[1], body[2], body[3]);
        edgeHi.dispose();
        edgeLow.dispose();

        gc.dispose();
        return img;
    }

    /** Linear-scale an 8-bit channel by {@code pct} percent (clamped 0..255). */
    private int scale(int channel, int pct) {
        int v = channel * pct / 100;
        return Math.max(0, Math.min(255, v));
    }

    /** Brighten an 8-bit channel toward 255 by {@code pct} percent of the gap. */
    private int lighten(int channel, int pct) {
        return Math.max(0, Math.min(255, channel + (255 - channel) * pct / 100));
    }
}
