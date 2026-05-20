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
import java.util.HashMap;
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
     * Renders the icon at the given pixel {@code size}, honouring each
     * element's own {@code fill} / {@code stroke} / {@code stroke-width}
     * attributes (or {@code style="fill:#…"}) instead of flattening to one
     * colour.  Use this for multi-colour illustrations (e.g. the swiss-army
     * knife) and for stroked-line icons (e.g. the RIAA curve, whose
     * {@code <polyline stroke="…">} would render blank under the default
     * monochrome path).  Caches independently of the tinted path so an
     * icon used in both modes doesn't cross-contaminate.
     */
    public Image renderAtHeightColored(Display display, String resourcePath, int height) {
        // Always rasterise to a SQUARE canvas — tab strips and sidebar
        // rows expect uniform tile sizes.  The SVG content is centered
        // inside the square with its own aspect preserved (the scale =
        // Math.min(sx, sy) inside renderSvgColored).
        return cache.computeIfAbsent(
                new CacheKey(Kind.SVG_COLORED, resourcePath, height, height, null, false),
                k -> renderSvgColored(display, resourcePath, height, height));
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

    /**
     * Renders the SVG with per-element fill / stroke colours from the
     * source instead of flattening to a single tint.  Handles both
     * {@code <path>} (with optional {@code stroke}) and {@code <polyline>}
     * (which the bundled custom RIAA-curve icon uses).  Attributes can be
     * specified as standalone {@code fill="#…"} / {@code stroke="#…"} or
     * folded into an inline {@code style="fill:#…;stroke:#…"} block.
     */
    private Image renderSvgColored(Display display, String resourcePath, int width, int height) {
        PaletteData palette = new PaletteData(0x00FF0000, 0x0000FF00, 0x000000FF);
        ImageData transparentData = new ImageData(width, height, 32, palette);
        transparentData.alphaData = new byte[width * height];
        Image img = new Image(display, transparentData);
        GC gc = new GC(img);
        List<Color> ownedColors = new ArrayList<>();
        try {
            gc.setAntialias(SWT.ON);
            String svg = readResource(resourcePath);
            if (svg == null) return img;
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
            gc.setFillRule(SWT.FILL_EVEN_ODD);

            for (SvgElement el : extractElements(svg)) {
                Path p = parsePath(display, el.pathData());
                try {
                    if (el.fillNone()) {
                        // explicit fill="none" — skip
                    } else if (el.fillColor() != null) {
                        Color c = new Color(display, el.fillColor());
                        ownedColors.add(c);
                        gc.setBackground(c);
                        gc.fillPath(p);
                    } else {
                        gc.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
                        gc.fillPath(p);
                    }
                    if (el.strokeColor() != null && !el.strokeNone()) {
                        Color c = new Color(display, el.strokeColor());
                        ownedColors.add(c);
                        gc.setForeground(c);
                        gc.setLineWidth(Math.max(1, Math.round(el.strokeWidth())));
                        gc.setLineCap(SWT.CAP_ROUND);
                        gc.setLineJoin(SWT.JOIN_ROUND);
                        gc.drawPath(p);
                    }
                } finally {
                    p.dispose();
                }
            }

            gc.setTransform(null);
            t.dispose();
        } finally {
            gc.dispose();
            for (Color c : ownedColors) c.dispose();
        }
        return img;
    }

    /** One drawable element parsed out of the SVG.  {@code fillColor} is
     *  the RGB to fill with; {@code null} means "no fill attribute was
     *  declared" (fall back to the SVG default).  {@code fillNone == true}
     *  records an explicit {@code fill="none"} so the renderer skips it.
     *  Same convention for stroke. */
    private record SvgElement(String pathData,
                              RGB fillColor, boolean fillNone,
                              RGB strokeColor, boolean strokeNone,
                              float strokeWidth) {}

    private static final Pattern DRAWABLE_TAG =
            Pattern.compile("<(path|polyline|polygon)\\b([^>]*?)/?>", Pattern.DOTALL);
    private static final Pattern ATTR_PATTERN =
            Pattern.compile("(\\w[\\w-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern STYLE_PROP_PATTERN =
            Pattern.compile("([\\w-]+)\\s*:\\s*([^;]+)");

    private List<SvgElement> extractElements(String svg) {
        List<SvgElement> out = new ArrayList<>();
        Matcher m = DRAWABLE_TAG.matcher(svg);
        while (m.find()) {
            String tag    = m.group(1);
            String attrs  = m.group(2);
            Map<String, String> a = new HashMap<>();
            Matcher am = ATTR_PATTERN.matcher(attrs);
            while (am.find()) a.put(am.group(1), am.group(2));
            String style = a.get("style");
            if (style != null) {
                Matcher sm = STYLE_PROP_PATTERN.matcher(style);
                while (sm.find()) {
                    String key = sm.group(1).trim();
                    String val = sm.group(2).trim();
                    a.put(key, val);
                }
            }
            String pathData;
            if ("path".equals(tag)) {
                String d = a.get("d");
                if (d == null) continue;
                pathData = d;
            } else {
                String pts = a.get("points");
                if (pts == null) continue;
                pathData = pointsToPathData(pts, "polygon".equals(tag));
            }
            String fillSpec   = a.get("fill");
            String strokeSpec = a.get("stroke");
            String swSpec     = a.get("stroke-width");
            RGB fill   = parseColorSpec(fillSpec);
            RGB stroke = parseColorSpec(strokeSpec);
            boolean fillNone   = fillSpec   != null && "none".equalsIgnoreCase(fillSpec.trim());
            boolean strokeNone = strokeSpec != null && "none".equalsIgnoreCase(strokeSpec.trim());
            float sw = 1f;
            if (swSpec != null) {
                try { sw = Float.parseFloat(swSpec.trim()); } catch (NumberFormatException ignored) {}
            }
            out.add(new SvgElement(pathData, fill, fillNone, stroke, strokeNone, sw));
        }
        return out;
    }

    /** Converts an SVG polyline / polygon {@code points="x,y x,y …"} (or
     *  whitespace-separated) into an equivalent {@code d="M … L … L …"}
     *  string so the existing path parser can take over.  When the source
     *  was a {@code <polygon>} the path is also closed with {@code Z}. */
    private String pointsToPathData(String points, boolean close) {
        String[] toks = points.trim().split("[,\\s]+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < toks.length; i += 2) {
            sb.append(i == 0 ? "M " : " L ");
            sb.append(toks[i]).append(' ').append(toks[i + 1]);
        }
        if (close) sb.append(" Z");
        return sb.toString();
    }

    /** Parses an SVG paint specifier — {@code "#RRGGBB"}, {@code "#RGB"},
     *  {@code "rgb(r,g,b)"} or a basic named colour — into an {@link RGB}.
     *  Returns {@code null} when the spec is missing, {@code "none"}, or
     *  unparseable so the caller can fall back. */
    private RGB parseColorSpec(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty() || "none".equalsIgnoreCase(s) || "transparent".equalsIgnoreCase(s)) return null;
        if (s.startsWith("#")) {
            try {
                if (s.length() == 7) {
                    return new RGB(Integer.parseInt(s.substring(1, 3), 16),
                                   Integer.parseInt(s.substring(3, 5), 16),
                                   Integer.parseInt(s.substring(5, 7), 16));
                }
                if (s.length() == 4) {
                    int r = Integer.parseInt(s.substring(1, 2), 16);
                    int g = Integer.parseInt(s.substring(2, 3), 16);
                    int b = Integer.parseInt(s.substring(3, 4), 16);
                    return new RGB(r * 17, g * 17, b * 17);
                }
            } catch (NumberFormatException ignored) { return null; }
        }
        if (s.toLowerCase().startsWith("rgb(")) {
            try {
                String inner = s.substring(4, s.indexOf(')'));
                String[] parts = inner.split(",");
                return new RGB(Integer.parseInt(parts[0].trim()),
                               Integer.parseInt(parts[1].trim()),
                               Integer.parseInt(parts[2].trim()));
            } catch (RuntimeException ignored) { return null; }
        }
        switch (s.toLowerCase()) {
            case "black": return new RGB(0, 0, 0);
            case "white": return new RGB(255, 255, 255);
            case "red":   return new RGB(255, 0, 0);
            case "green": return new RGB(0, 128, 0);
            case "blue":  return new RGB(0, 0, 255);
            default:      return null;
        }
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
        int pct = 80;
        Color top = new Color(display, lighten(br, pct), lighten(bg, pct), lighten(bb, pct));
        Color bot = new Color(display, scale(br, pct),   scale(bg, pct),   scale(bb, pct));
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
                boolean rel = Character.isLowerCase(cmd);
                char up = Character.toUpperCase(cmd);
                switch (up) {
                    case 'M': case 'L': {
                        float x = t.nextNumber(), y = t.nextNumber();
                        if (rel) { x += curX; y += curY; }
                        curX = x; curY = y;
                        if (up == 'M') prev = rel ? 'l' : 'L';
                        minX = Math.min(minX, curX); minY = Math.min(minY, curY);
                        maxX = Math.max(maxX, curX); maxY = Math.max(maxY, curY);
                        any = true; break;
                    }
                    case 'H': {
                        float x = t.nextNumber();
                        if (rel) x += curX;
                        curX = x;
                        minX = Math.min(minX, curX); maxX = Math.max(maxX, curX);
                        any = true; break;
                    }
                    case 'V': {
                        float y = t.nextNumber();
                        if (rel) y += curY;
                        curY = y;
                        minY = Math.min(minY, curY); maxY = Math.max(maxY, curY);
                        any = true; break;
                    }
                    case 'C': {
                        float c1x = t.nextNumber(), c1y = t.nextNumber();
                        float c2x = t.nextNumber(), c2y = t.nextNumber();
                        float x   = t.nextNumber(), y   = t.nextNumber();
                        if (rel) {
                            c1x += curX; c1y += curY;
                            c2x += curX; c2y += curY;
                            x   += curX; y   += curY;
                        }
                        minX = Math.min(Math.min(minX, c1x), Math.min(c2x, x));
                        minY = Math.min(Math.min(minY, c1y), Math.min(c2y, y));
                        maxX = Math.max(Math.max(maxX, c1x), Math.max(c2x, x));
                        maxY = Math.max(Math.max(maxY, c1y), Math.max(c2y, y));
                        curX = x; curY = y;
                        any = true; break;
                    }
                    case 'S': case 'Q': {
                        float p1x = t.nextNumber(), p1y = t.nextNumber();
                        float x   = t.nextNumber(), y   = t.nextNumber();
                        if (rel) {
                            p1x += curX; p1y += curY;
                            x   += curX; y   += curY;
                        }
                        minX = Math.min(Math.min(minX, p1x), x);
                        minY = Math.min(Math.min(minY, p1y), y);
                        maxX = Math.max(Math.max(maxX, p1x), x);
                        maxY = Math.max(Math.max(maxY, p1y), y);
                        curX = x; curY = y;
                        any = true; break;
                    }
                    case 'T': {
                        float x = t.nextNumber(), y = t.nextNumber();
                        if (rel) { x += curX; y += curY; }
                        minX = Math.min(minX, x); minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                        curX = x; curY = y;
                        any = true; break;
                    }
                    case 'Z': break;
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
     * Parses an SVG path "{@code d}" string into an SWT {@link Path}.  Supports
     * both ABSOLUTE (uppercase: M L H V C S Q T A Z) and RELATIVE (lowercase:
     * m l h v c s q t a z) commands.  Implicit-L after M and implicit-l after
     * m are handled per the SVG spec.  Unknown commands are skipped along with
     * any following numbers so a stray command never stalls the cursor in an
     * infinite loop.
     */
    private Path parsePath(Display display, String d) {
        Path path = new Path(display);
        PathTok t = new PathTok(d);
        char prev = ' ';
        float startX = 0, startY = 0;
        float curX = 0, curY = 0;
        // Tracks the last cubic control point for smooth-cubic (S/s) reflection.
        float lastC2x = Float.NaN, lastC2y = Float.NaN;
        // Tracks the last quadratic control point for smooth-quadratic (T/t) reflection.
        float lastQx = Float.NaN, lastQy = Float.NaN;
        while (t.hasMore()) {
            char cmd = t.nextCommandOrSameAsPrev(prev);
            prev = cmd;
            boolean rel = Character.isLowerCase(cmd);
            char up = Character.toUpperCase(cmd);
            switch (up) {
                case 'M': {
                    float x = t.nextNumber(), y = t.nextNumber();
                    if (rel) { x += curX; y += curY; }
                    curX = x; curY = y;
                    startX = curX; startY = curY;
                    path.moveTo(curX, curY);
                    // Subsequent coordinate pairs after an M are implicit L's.
                    prev = rel ? 'l' : 'L';
                    lastC2x = Float.NaN; lastQx = Float.NaN;
                    break;
                }
                case 'L': {
                    float x = t.nextNumber(), y = t.nextNumber();
                    if (rel) { x += curX; y += curY; }
                    curX = x; curY = y;
                    path.lineTo(curX, curY);
                    lastC2x = Float.NaN; lastQx = Float.NaN;
                    break;
                }
                case 'H': {
                    float x = t.nextNumber();
                    if (rel) x += curX;
                    curX = x;
                    path.lineTo(curX, curY);
                    lastC2x = Float.NaN; lastQx = Float.NaN;
                    break;
                }
                case 'V': {
                    float y = t.nextNumber();
                    if (rel) y += curY;
                    curY = y;
                    path.lineTo(curX, curY);
                    lastC2x = Float.NaN; lastQx = Float.NaN;
                    break;
                }
                case 'C': {
                    float c1x = t.nextNumber(), c1y = t.nextNumber();
                    float c2x = t.nextNumber(), c2y = t.nextNumber();
                    float x   = t.nextNumber(), y   = t.nextNumber();
                    if (rel) {
                        c1x += curX; c1y += curY;
                        c2x += curX; c2y += curY;
                        x   += curX; y   += curY;
                    }
                    path.cubicTo(c1x, c1y, c2x, c2y, x, y);
                    lastC2x = c2x; lastC2y = c2y;
                    lastQx = Float.NaN;
                    curX = x; curY = y;
                    break;
                }
                case 'S': {
                    // Smooth cubic: first control is reflection of previous c2.
                    float c1x, c1y;
                    if (Float.isNaN(lastC2x)) { c1x = curX; c1y = curY; }
                    else { c1x = 2 * curX - lastC2x; c1y = 2 * curY - lastC2y; }
                    float c2x = t.nextNumber(), c2y = t.nextNumber();
                    float x   = t.nextNumber(), y   = t.nextNumber();
                    if (rel) {
                        c2x += curX; c2y += curY;
                        x   += curX; y   += curY;
                    }
                    path.cubicTo(c1x, c1y, c2x, c2y, x, y);
                    lastC2x = c2x; lastC2y = c2y;
                    lastQx = Float.NaN;
                    curX = x; curY = y;
                    break;
                }
                case 'Q': {
                    float cx = t.nextNumber(), cy = t.nextNumber();
                    float x  = t.nextNumber(), y  = t.nextNumber();
                    if (rel) {
                        cx += curX; cy += curY;
                        x  += curX; y  += curY;
                    }
                    path.quadTo(cx, cy, x, y);
                    lastQx = cx; lastQy = cy;
                    lastC2x = Float.NaN;
                    curX = x; curY = y;
                    break;
                }
                case 'T': {
                    float cx, cy;
                    if (Float.isNaN(lastQx)) { cx = curX; cy = curY; }
                    else { cx = 2 * curX - lastQx; cy = 2 * curY - lastQy; }
                    float x = t.nextNumber(), y = t.nextNumber();
                    if (rel) { x += curX; y += curY; }
                    path.quadTo(cx, cy, x, y);
                    lastQx = cx; lastQy = cy;
                    lastC2x = Float.NaN;
                    curX = x; curY = y;
                    break;
                }
                case 'Z': {
                    path.close();
                    curX = startX; curY = startY;
                    prev = ' ';
                    lastC2x = Float.NaN; lastQx = Float.NaN;
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

    private enum Kind { SVG, SVG_COLORED, RECORD_LED, PLAY_LED }

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
