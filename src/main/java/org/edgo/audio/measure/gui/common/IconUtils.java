package org.edgo.audio.measure.gui.common;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single owner of all SVG-icon and LED-style {@link Image} instances used
 * across the UI.  Instances are shared via an internal cache keyed by
 * (resource path, pixel size, fill colour) — every caller asking for the
 * same icon gets the same {@code Image}, so identical icons on different
 * panes don't cost extra memory and don't need per-call-site dispose
 * listeners.
 *
 * <h2>Lifecycle</h2>
 * <p>Call {@link #registerShell(Shell)} on the application's main shell
 * (or any shell whose lifetime bounds the icon cache).  When that shell
 * is disposed, all cached Images are disposed and the cache is cleared,
 * so a subsequent fresh shell (e.g. after a language switch) starts with
 * a clean slate.
 *
 * <h2>Thread safety</h2>
 * <p>{@link #instance()} uses double-checked locking with a {@code volatile}
 * field; the cache is a {@link ConcurrentHashMap} and the rasterising
 * helpers run inside {@code computeIfAbsent}, so concurrent requests for
 * the same key only render once.  Note however that SWT itself is single-
 * threaded — the {@link Image} objects must still be used from the UI
 * thread that owns the {@link Display}.
 */
@Log4j2
public final class IconUtils {

    private static volatile IconUtils instance;

    private final Map<CacheKey, Image> cache = new ConcurrentHashMap<>();

    private IconUtils() {}

    public static IconUtils instance() {
        IconUtils local = instance;
        if (local != null) return local;
        synchronized (IconUtils.class) {
            if (instance == null) instance = new IconUtils();
            return instance;
        }
    }

    /**
     * Hooks the given shell so that, when it is disposed, every cached
     * {@link Image} is disposed and the cache is cleared.  Safe to call
     * more than once — each registration installs its own dispose
     * listener and the cache simply ends up emptied on the first one
     * that fires.
     */
    public void registerShell(Shell shell) {
        shell.addDisposeListener(e -> clear());
    }

    /** Disposes every cached image and empties the cache. */
    public void clear() {
        for (Image img : cache.values()) {
            if (img != null && !img.isDisposed()) img.dispose();
        }
        cache.clear();
    }

    // -------------------------------------------------------------------------
    // SVG icons
    // -------------------------------------------------------------------------

    /**
     * Returns a cached {@link Image} of the icon at {@code resourcePath}
     * rasterised to {@code width × height} pixels with the SVG paths
     * painted in {@code fillColor} (or the system widget foreground when
     * {@code null}).  Calling again with the same arguments returns the
     * same {@code Image} instance.
     */
    public Image render(Display display, String resourcePath, int width, int height, RGB fillColor) {
        return cache.computeIfAbsent(
                new CacheKey(Kind.SVG, resourcePath, width, height, fillColor, false),
                k -> renderSvg(display, resourcePath, width, height, fillColor));
    }

    /** Convenience wrapper for {@link #render} that uses the system widget foreground. */
    public Image render(Display display, String resourcePath, int width, int height) {
        return render(display, resourcePath, width, height, null);
    }

    /**
     * Renders the icon at the given pixel {@code height}, deriving the
     * width from the icon's actual path bounding-box aspect ratio so a
     * row of toolbar buttons can share a height without bitmap-stretching
     * any of them.  Result is cached on (path, derivedWidth, height, color).
     */
    public Image renderAtHeight(Display display, String resourcePath, int height, RGB fillColor) {
        int width = height;
        String svg = readResource(resourcePath);
        if (svg != null) {
            double[] bbox = computePathBoundingBox(svg);
            if (bbox == null) bbox = parseViewBox(svg);
            if (bbox != null && bbox[3] > 0) {
                double aspect = bbox[2] / bbox[3];
                width = Math.max(1, (int) Math.round(height * aspect));
            }
        }
        return render(display, resourcePath, width, height, fillColor);
    }

    /**
     * Mirror of {@link #renderAtHeight}: pins the pixel width and derives
     * height from the icon's bounding box.  Used for the small spinner
     * arrows / drop carets whose visible width is what we want to control.
     */
    public Image renderAtWidth(Display display, String resourcePath, int width, RGB fillColor) {
        int height = width;
        String svg = readResource(resourcePath);
        if (svg != null) {
            double[] bbox = computePathBoundingBox(svg);
            if (bbox == null) bbox = parseViewBox(svg);
            if (bbox != null && bbox[2] > 0) {
                double aspect = bbox[2] / bbox[3];
                height = Math.max(1, (int) Math.round(width / aspect));
            }
        }
        return render(display, resourcePath, width, height, fillColor);
    }

    // -------------------------------------------------------------------------
    // LED-style painted icons
    // -------------------------------------------------------------------------

    /**
     * Draws a round LED at the given pixel {@code size} with vertical-gradient
     * shading, a darker outer rim, and (when {@code lit}) a small white
     * specular highlight near the top-left.  Cached on (r,g,b,lit,size).
     */
    public Image createRecordLed(Display display, int r, int g, int b, boolean lit, int size) {
        return cache.computeIfAbsent(
                new CacheKey(Kind.RECORD_LED, null, size, size, new RGB(r, g, b), lit),
                k -> paintRecordLed(display, r, g, b, lit, size));
    }

    /**
     * Draws a right-pointing triangle "play" arrow at the given pixel
     * {@code size} with edge highlights and shadows to suggest 3D bevelling.
     * Same dim/lit semantics as {@link #createRecordLed}.
     */
    public Image createPlayLed(Display display, int r, int g, int b, boolean lit, int size) {
        return cache.computeIfAbsent(
                new CacheKey(Kind.PLAY_LED, null, size, size, new RGB(r, g, b), lit),
                k -> paintPlayLed(display, r, g, b, lit, size));
    }

    // =========================================================================
    // Implementation
    // =========================================================================

    private Image renderSvg(Display display, String resourcePath, int width, int height, RGB fillColor) {
        // Initially fully-transparent 32-bit ARGB image — paths painted
        // on top stay opaque, untouched pixels stay transparent.
        PaletteData palette = new PaletteData(0x00FF0000, 0x0000FF00, 0x000000FF);
        ImageData transparentData = new ImageData(width, height, 32, palette);
        transparentData.alphaData = new byte[width * height];
        Image img = new Image(display, transparentData);
        GC gc = new GC(img);
        Color customFill = null;
        try {
            gc.setAntialias(SWT.ON);

            String svg = readResource(resourcePath);
            if (svg == null) return img;
            // Fit to the actual path bounding box (Font-Awesome viewBoxes
            // include grid padding around the glyph; using the tight bbox
            // makes the rendered icon fill the requested rectangle).
            double[] vb = computePathBoundingBox(svg);
            if (vb == null) vb = parseViewBox(svg);
            if (vb == null) return img;

            double sx = width  / vb[2];
            double sy = height / vb[3];
            double s = Math.min(sx, sy);
            float dx = (float) ((width  - vb[2] * s) / 2.0 - vb[0] * s);
            float dy = (float) ((height - vb[3] * s) / 2.0 - vb[1] * s);

            Transform t = new Transform(display);
            t.translate(dx, dy);
            t.scale((float) s, (float) s);
            gc.setTransform(t);
            if (fillColor != null) {
                customFill = new Color(display, fillColor);
                gc.setBackground(customFill);
            } else {
                gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            }
            gc.setFillRule(SWT.FILL_EVEN_ODD);

            for (String d : extractPathData(svg)) {
                Path p = parsePath(display, d);
                try { gc.fillPath(p); }
                finally { p.dispose(); }
            }

            gc.setTransform(null);
            t.dispose();
        } finally {
            gc.dispose();
            if (customFill != null) customFill.dispose();
        }
        return img;
    }

    private Image paintRecordLed(Display display, int r, int g, int b, boolean lit, int size) {
        Image img = newTransparentIcon(display, size);
        GC gc = new GC(img);
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);

        int dim = lit ? 0 : 50;
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

    private Image paintPlayLed(Display display, int r, int g, int b, boolean lit, int size) {
        Image img = newTransparentIcon(display, size);
        GC gc = new GC(img);
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);

        int dim = lit ? 0 : 50;
        int br = scale(r, 100 - dim);
        int bg = scale(g, 100 - dim);
        int bb = scale(b, 100 - dim);

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

    private Image newTransparentIcon(Display display, int size) {
        PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data = new ImageData(size, size, 32, palette);
        data.alphaData = new byte[size * size];
        return new Image(display, data);
    }

    private int scale(int channel, int pct) {
        int v = channel * pct / 100;
        return Math.max(0, Math.min(255, v));
    }

    private int lighten(int channel, int pct) {
        return Math.max(0, Math.min(255, channel + (255 - channel) * pct / 100));
    }

    private String readResource(String resourcePath) {
        try (InputStream in = IconUtils.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("SVG resource not found: {}", resourcePath);
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception ex) {
            log.warn("Failed to read SVG resource {}: {}", resourcePath, ex.getMessage());
            return null;
        }
    }

    /** Returns {@code [minX, minY, w, h]} from the SVG root {@code viewBox} attribute. */
    private double[] parseViewBox(String svg) {
        Matcher m = Pattern.compile("viewBox\\s*=\\s*\"([^\"]+)\"").matcher(svg);
        if (!m.find()) return null;
        String[] parts = m.group(1).trim().split("[\\s,]+");
        if (parts.length < 4) return null;
        return new double[]{
                Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]), Double.parseDouble(parts[3])
        };
    }

    /**
     * Computes a tight bounding box across all {@code <path>} {@code d}
     * coordinates in the SVG.  Curve control points are included — that
     * overestimates slightly but the visible glyph is always strictly
     * within its control polygon, so the resulting bbox is a safe fit.
     */
    private double[] computePathBoundingBox(String svg) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (String d : extractPathData(svg)) {
            PathTok t = new PathTok(d);
            char prev = ' ';
            float curX = 0, curY = 0;
            while (t.hasMore()) {
                char cmd = t.nextCommandOrSameAsPrev(prev);
                prev = cmd;
                switch (cmd) {
                    case 'M': case 'L':
                        curX = t.nextNumber(); curY = t.nextNumber();
                        if (cmd == 'M') prev = 'L';
                        minX = Math.min(minX, curX); minY = Math.min(minY, curY);
                        maxX = Math.max(maxX, curX); maxY = Math.max(maxY, curY);
                        any = true; break;
                    case 'H':
                        curX = t.nextNumber();
                        minX = Math.min(minX, curX); maxX = Math.max(maxX, curX);
                        any = true; break;
                    case 'V':
                        curY = t.nextNumber();
                        minY = Math.min(minY, curY); maxY = Math.max(maxY, curY);
                        any = true; break;
                    case 'C': {
                        for (int i = 0; i < 3; i++) {
                            float x = t.nextNumber(), y = t.nextNumber();
                            minX = Math.min(minX, x); minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                            curX = x; curY = y;
                        }
                        any = true; break;
                    }
                    case 'Z': case 'z': break;
                    default:
                        try { t.nextNumber(); } catch (Exception ex) { return null; }
                }
            }
        }
        if (!any || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                 || maxX <= minX || maxY <= minY) {
            return null;
        }
        return new double[]{ minX, minY, maxX - minX, maxY - minY };
    }

    /** Pulls every {@code d="…"} attribute out of {@code <path …/>} elements, in document order. */
    private List<String> extractPathData(String svg) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<path\\b[^>]*?\\bd\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL).matcher(svg);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Parses an SVG path "{@code d}" string into an SWT {@link Path}.  Handles
     * absolute M / L / H / V / C / Z (the commands the bundled icons use); a
     * lone M is followed by implicit L's per the SVG spec.  Unknown commands
     * are skipped along with any following numbers so a stray command never
     * stalls the cursor in an infinite loop.
     */
    private Path parsePath(Display display, String d) {
        Path path = new Path(display);
        PathTok t = new PathTok(d);
        char prev = ' ';
        float startX = 0, startY = 0;
        float curX = 0, curY = 0;
        while (t.hasMore()) {
            char cmd = t.nextCommandOrSameAsPrev(prev);
            prev = cmd;
            switch (cmd) {
                case 'M': {
                    curX = t.nextNumber();
                    curY = t.nextNumber();
                    startX = curX; startY = curY;
                    path.moveTo(curX, curY);
                    prev = 'L';
                    break;
                }
                case 'L': {
                    curX = t.nextNumber();
                    curY = t.nextNumber();
                    path.lineTo(curX, curY);
                    break;
                }
                case 'H': {
                    curX = t.nextNumber();
                    path.lineTo(curX, curY);
                    break;
                }
                case 'V': {
                    curY = t.nextNumber();
                    path.lineTo(curX, curY);
                    break;
                }
                case 'C': {
                    float c1x = t.nextNumber(), c1y = t.nextNumber();
                    float c2x = t.nextNumber(), c2y = t.nextNumber();
                    curX = t.nextNumber(); curY = t.nextNumber();
                    path.cubicTo(c1x, c1y, c2x, c2y, curX, curY);
                    break;
                }
                case 'Z':
                case 'z': {
                    path.close();
                    curX = startX; curY = startY;
                    prev = ' ';
                    break;
                }
                default:
                    t.skipUntilLetter();
                    prev = ' ';
                    break;
            }
        }
        return path;
    }

    private enum Kind { SVG, RECORD_LED, PLAY_LED }

    /** Cache key — equality across all six fields, with {@code resourcePath}
     *  null for the LED variants.  {@link RGB} already implements equals /
     *  hashCode on its three components. */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    private static final class CacheKey {
        private final Kind kind;
        private final String resourcePath;
        private final int width;
        private final int height;
        private final RGB color;
        private final boolean lit;
    }

    /** Cursor for SVG path data: yields commands (letters) and numbers in order. */
    private static final class PathTok {
        private final String d;
        private int pos;
        PathTok(String d) { this.d = d; this.pos = 0; }
        boolean hasMore() {
            skipSeparators();
            return pos < d.length();
        }
        /** Returns the next single-letter command, or repeats {@code prev} if a number is next. */
        char nextCommandOrSameAsPrev(char prev) {
            skipSeparators();
            if (pos < d.length() && Character.isLetter(d.charAt(pos))) {
                return d.charAt(pos++);
            }
            return prev;
        }
        float nextNumber() {
            skipSeparators();
            int start = pos;
            if (pos < d.length() && (d.charAt(pos) == '+' || d.charAt(pos) == '-')) pos++;
            while (pos < d.length() && isNumPart(d.charAt(pos))) pos++;
            if (pos < d.length() && (d.charAt(pos) == 'e' || d.charAt(pos) == 'E')) {
                pos++;
                if (pos < d.length() && (d.charAt(pos) == '+' || d.charAt(pos) == '-')) pos++;
                while (pos < d.length() && Character.isDigit(d.charAt(pos))) pos++;
            }
            return Float.parseFloat(d.substring(start, pos));
        }
        private void skipSeparators() {
            while (pos < d.length()) {
                char c = d.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') pos++;
                else break;
            }
        }
        void skipUntilLetter() {
            if (pos < d.length()) pos++;
            while (pos < d.length() && !Character.isLetter(d.charAt(pos))) pos++;
        }
        private static boolean isNumPart(char c) {
            return Character.isDigit(c) || c == '.';
        }
    }
}
