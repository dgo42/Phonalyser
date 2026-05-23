package org.edgo.audio.measure.gui.scope;

import org.edgo.audio.measure.gui.common.AbstractMeasurementView;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBuffer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

/**
 * Condensed time-axis strip below the main {@link OscilloscopeView}.  Always
 * displays the most recent <strong>1 second</strong> of captured signal —
 * its full pixel width maps to exactly one second of audio.  Useful as an
 * overview while the main scope is zoomed in on a much shorter window.
 *
 * <p>The waveform's vertical scale auto-fills the strip: ±1.0 normalised
 * sample value uses the full strip height, so the user doesn't have to
 * adjust V/div to keep the overview visible.
 */
public final class CondensedView extends AbstractMeasurementView {

    private static final int DIVISIONS_X = 10;

    private final Color background;
    private final Color gridColor;
    private final Color midlineColor;
    private final Color leftChannelColor;
    private final Color rightChannelColor;

    private SignalBuffer buffer;
    private float[] leftBuf  = new float[0];
    private float[] rightBuf = new float[0];
    /** Same back-offset semantics as {@link OscilloscopeView#getViewBackOffsetFrames()}. */
    private volatile long viewBackOffsetFrames;

    public void setViewBackOffsetFrames(long v) { this.viewBackOffsetFrames = Math.max(0L, v); }

    public CondensedView(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        background        = new Color(getDisplay(),   0,   0,   0);
        gridColor         = new Color(getDisplay(),  60,  60,  60);
        midlineColor      = new Color(getDisplay(), 110, 110, 110);
        leftChannelColor  = new Color(getDisplay(),  40, 220, 240);   // cyan
        rightChannelColor = new Color(getDisplay(), 240, 220,  40);

        setBackground(background);
        addPaintListener(this::onPaint);
        addDisposeListener(e -> {
            background.dispose();
            gridColor.dispose();
            midlineColor.dispose();
            leftChannelColor.dispose();
            rightChannelColor.dispose();
        });
    }

    public void setBuffer(SignalBuffer buffer) {
        this.buffer = buffer;
    }

    private void onPaint(PaintEvent e) {
        Rectangle area = getClientArea();
        int w = area.width;
        int h = area.height;
        if (w <= 0 || h <= 0) return;
        paintCanvas(e.gc, w, h);
    }

    /**
     * Renders one full frame of the condensed strip (background, grid,
     * waveforms) to {@code gc} at the requested {@code (w, h)} resolution.
     * Decoupled from {@link #onPaint} so the screenshot dialog can re-render
     * the same view to an off-screen {@link Image} at an arbitrary size.
     */
    public void paintCanvas(GC gc, int w, int h) {
        gc.setAdvanced(true);
        gc.setBackground(background);
        gc.fillRectangle(0, 0, w, h);
        drawGrid(gc, w, h);
        drawWaveforms(gc, w, h);
    }

    /** Renders this view into a new off-screen image at the requested size. Caller disposes. */
    public Image renderToImage(Display display, int w, int h) {
        Image img = new Image(display, w, h);
        GC gc = new GC(img);
        try {
            paintCanvas(gc, w, h);
        } finally {
            gc.dispose();
        }
        return img;
    }

    private void drawGrid(GC gc, int w, int h) {
        double divW = (double) w / DIVISIONS_X;
        gc.setForeground(gridColor);
        for (int i = 0; i <= DIVISIONS_X; i++) {
            int x = (int) Math.round(i * divW);
            gc.drawLine(x, 0, x, h - 1);
        }
        gc.drawLine(0, 0,     w - 1, 0);
        gc.drawLine(0, h - 1, w - 1, h - 1);
        gc.setForeground(midlineColor);
        int cy = h / 2;
        gc.drawLine(0, cy, w - 1, cy);
    }

    private void drawWaveforms(GC gc, int w, int h) {
        SignalBuffer b = buffer;
        if (b == null) return;

        Preferences prefs = Preferences.instance();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        if (!showL && !showR) return;

        int displaySamples = b.getSampleRate();  // exactly 1 second of audio
        if (displaySamples < 2) return;

        // Pull LANCZOS_PADDING extra samples on each side so the sinc kernel
        // near the pane edges has real context instead of a truncated tail
        // (sized for the widest downsample handled in the Path branch).
        int wanted = displaySamples + 2 * LANCZOS_PADDING;
        if (leftBuf.length < wanted) {
            leftBuf  = new float[wanted];
            rightBuf = new float[wanted];
        }
        long viewEndAbs = b.getWritePos() - viewBackOffsetFrames;
        int available = b.readEndingAt(viewEndAbs, wanted,
                showL ? leftBuf  : null,
                showR ? rightBuf : null);
        if (available < 2) return;

        int rightPad  = Math.min(LANCZOS_PADDING, Math.max(0, available - displaySamples));
        int dispEnd   = available - rightPad;
        int dispStart = Math.max(0, dispEnd - displaySamples);
        int dispCount = dispEnd - dispStart;
        if (dispCount < 2) return;

        double centerY = h / 2.0;
        double vScale  = h / 2.0;  // ±1.0 fills the full strip vertically

        // Antialias the curves themselves; the grid stays sharp because it
        // was drawn before this point with the default (no-AA) setting.
        // Round caps + joins keep the polyline continuous at sparse sample
        // densities (otherwise thick antialiased segments render as dashes).
        gc.setAntialias(SWT.ON);
        gc.setLineAttributes(new LineAttributes(2.0f, SWT.CAP_ROUND, SWT.JOIN_ROUND));
        if (showL) drawTrace(gc, leftBuf,  available, dispStart, dispCount, w, centerY, vScale, leftChannelColor);
        if (showR) drawTrace(gc, rightBuf, available, dispStart, dispCount, w, centerY, vScale, rightChannelColor);
    }

    /** See {@link OscilloscopeView#LANCZOS_A} — A=16 for sharper stop-band. */
    private static final int LANCZOS_A = 16;

    /** See {@link OscilloscopeView}'s {@code MAX_LANCZOS_DOWNSAMPLE} for rationale. */
    private static final int MAX_LANCZOS_DOWNSAMPLE = 5;

    /** Buffer padding (each side) so the widest kernel still has real context. */
    private static final int LANCZOS_PADDING = LANCZOS_A * MAX_LANCZOS_DOWNSAMPLE;

    /**
     * Lanczos-windowed sinc reconstruction with the kernel scaled to the
     * output rate ({@code scale = max(1, samplesPerPx)}) — see
     * {@link OscilloscopeView#lanczos} for the rationale.
     */
    private float lanczos(float[] data, int n, double t, double scale) {
        int halfWidth = (int) Math.ceil(LANCZOS_A * scale);
        int center = (int) Math.floor(t);
        int iLo = Math.max(0, center - halfWidth + 1);
        int iHi = Math.min(n - 1, center + halfWidth);
        double sum = 0.0;
        for (int i = iLo; i <= iHi; i++) {
            double x = (t - i) / scale;
            if (Math.abs(x) < 1e-9) {
                sum += data[i];
            } else if (Math.abs(x) < LANCZOS_A) {
                double pix  = Math.PI * x;
                double pixA = pix / LANCZOS_A;
                sum += data[i] * (Math.sin(pix) / pix) * (Math.sin(pixA) / pixA);
            }
        }
        return (float) (sum / scale);
    }

    /**
     * Draws a waveform.  Below {@code MAX_LANCZOS_DOWNSAMPLE} samples per pixel
     * we sinc-reconstruct with the kernel scaled to the output rate; above it
     * per-column min/max bars take over (which the condensed view almost always
     * uses, since 1 s of audio at typical rates is well into the dense regime).
     */
    private void drawTrace(GC gc, float[] data, int n,
                                  int dispStart, int dispCount, int width,
                                  double centerY, double vScale, Color color) {
        if (dispCount < 2 || width <= 0) return;
        gc.setForeground(color);
        double samplesPerPx = (double) dispCount / width;
        if (samplesPerPx <= MAX_LANCZOS_DOWNSAMPLE) {
            double scale = Math.max(1.0, samplesPerPx);
            Path path = new Path(gc.getDevice());
            try {
                for (int x = 0; x < width; x++) {
                    double t = dispStart + x * samplesPerPx;
                    float yp = (float) (centerY - lanczos(data, n, t, scale) * vScale);
                    if (x == 0) path.moveTo(0f, yp);
                    else        path.lineTo(x, yp);
                }
                gc.drawPath(path);
            } finally {
                path.dispose();
            }
        } else {
            int dispLimit = dispStart + dispCount;
            for (int x = 0; x < width; x++) {
                int startIdx = dispStart + (int) (x * samplesPerPx);
                int endIdx   = dispStart + (int) ((x + 1) * samplesPerPx);
                if (endIdx <= startIdx) endIdx = startIdx + 1;
                if (endIdx > dispLimit) endIdx = dispLimit;
                float min = data[startIdx];
                float max = data[startIdx];
                for (int i = startIdx + 1; i < endIdx; i++) {
                    float v = data[i];
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                int yMin = (int) Math.round(centerY - min * vScale);
                int yMax = (int) Math.round(centerY - max * vScale);
                gc.drawLine(x, Math.min(yMin, yMax), x, Math.max(yMin, yMax));
            }
        }
    }

    @Override
    protected void checkSubclass() {
        // Allow subclassing — Canvas is on SWT's restricted list otherwise.
    }
}
