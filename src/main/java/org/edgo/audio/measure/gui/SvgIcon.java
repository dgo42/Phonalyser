package org.edgo.audio.measure.gui;

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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal SVG renderer just good enough for the in-tree icons under
 * {@code src/main/resources/icons/}.  Parses path "{@code d}" attributes
 * (M / L / C / Z commands, absolute coordinates) and renders them with
 * SWT primitives — no external dependency.  The widget background colour
 * is used as the fill, so the icon picks up the current theme automatically.
 *
 * <p>Why not a real SVG renderer?  The icons we ship are single-colour and
 * use only those commands, so a 60-line parser is enough and avoids
 * pulling in Batik / jsvg (~MB-scale deps).
 */
@Log4j2
public final class SvgIcon {

    private SvgIcon() {}

    /**
     * Loads {@code resourcePath} (must point at an SVG with a numeric
     * {@code viewBox}), parses its paths, and rasterises them into a new
     * {@link Image} of {@code (width × height)} pixels.  The icon is centred
     * with the viewBox aspect ratio preserved (letter-boxed if the target's
     * aspect ratio differs).  Caller disposes the returned image.
     */
    public static Image render(Display display, String resourcePath, int width, int height) {
        return render(display, resourcePath, width, height, (RGB) null);
    }

    /**
     * Renders the icon at the given pixel height and a width derived
     * from the icon's actual path bounding-box aspect ratio.  Used so
     * push buttons in toolbars all share a uniform height while each
     * icon keeps its natural proportion (and SWT's button chrome adds
     * equal padding on all four sides).  Falls back to a square image
     * if the path bounding box is unreadable.
     */
    public static Image renderAtHeight(Display display, String resourcePath, int height, RGB fillColor) {
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
     * Mirror of {@link #renderAtHeight}: pins the rendered width and
     * derives the height from the icon's actual path bounding-box aspect
     * ratio.  Used for the small spinner arrows / drop carets where the
     * visible width is what we want to control (the icons are wider than
     * they are tall).
     */
    public static Image renderAtWidth(Display display, String resourcePath, int width, RGB fillColor) {
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

    /**
     * Same as {@link #render(Display, String, int, int)} but fills the
     * SVG paths with {@code fillColor} instead of the system widget
     * foreground.  Pass {@code null} for the default theme colour.
     */
    public static Image render(Display display, String resourcePath, int width, int height, RGB fillColor) {
        // Initially fully-transparent 32-bit ARGB image — paths painted
        // on top stay opaque, untouched pixels stay transparent.  This
        // is how we avoid the visible widget-background-colour padding
        // the previous fillRectangle-then-fillPath approach produced.
        PaletteData palette = new PaletteData(0x00FF0000, 0x0000FF00, 0x000000FF);
        ImageData transparentData = new ImageData(width, height, 32, palette);
        transparentData.alphaData = new byte[width * height];   // all 0 = transparent
        Image img = new Image(display, transparentData);
        GC gc = new GC(img);
        Color customFill = null;
        try {
            gc.setAntialias(SWT.ON);

            String svg = readResource(resourcePath);
            if (svg == null) return img;
            // Fit to the actual path bounding box rather than the SVG
            // viewBox.  Font-Awesome-style icons leave a fixed-grid
            // padding around the glyph inside their viewBox; using the
            // tight bbox makes the rendered icon fill the requested
            // pixel rectangle and removes the visual padding the user
            // sees inside the button.
            double[] vb = computePathBoundingBox(svg);
            if (vb == null) vb = parseViewBox(svg);
            if (vb == null) return img;

            // Aspect-preserving fit.
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

    private static String readResource(String resourcePath) {
        try (InputStream in = SvgIcon.class.getResourceAsStream(resourcePath)) {
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
    private static double[] parseViewBox(String svg) {
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
     * coordinates in the SVG.  Walks each command's numeric arguments
     * and tracks {@code min/max} x and y.  For curves (C / Q) the
     * control points are included — that overestimates slightly but
     * the visible glyph is always strictly within its control polygon,
     * so the resulting bbox is a safe (but tight) fit.  Returns
     * {@code [minX, minY, w, h]} or {@code null} if no coordinates
     * were found.
     */
    private static double[] computePathBoundingBox(String svg) {
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
                        // Two control points + endpoint.
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
                        // Unknown / lowercase — skip a single number to avoid stalling.
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
    private static List<String> extractPathData(String svg) {
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
    private static Path parsePath(Display display, String d) {
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
                    prev = 'L';  // subsequent number pairs are implicit lineTo per the spec
                    break;
                }
                case 'L': {
                    curX = t.nextNumber();
                    curY = t.nextNumber();
                    path.lineTo(curX, curY);
                    break;
                }
                case 'H': {
                    // Horizontal lineTo — y stays put.
                    curX = t.nextNumber();
                    path.lineTo(curX, curY);
                    break;
                }
                case 'V': {
                    // Vertical lineTo — x stays put.
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
                    // Unknown command: discard everything up to the next
                    // command letter so the outer loop can never re-feed
                    // the same numeric run against the same (still-unknown)
                    // command and spin forever.  Guaranteed to advance pos
                    // by at least one character per iteration.
                    t.skipUntilLetter();
                    prev = ' ';
                    break;
            }
        }
        return path;
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
            // Handle exponent sign (e.g. "1.2e-3").
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
        /**
         * Defensive forward-skip used after an unrecognised command letter:
         * advances {@code pos} until it hits the next letter or the end of
         * the string, so the outer parser can never loop on the same input.
         * Guaranteed to consume at least one character per call.
         */
        void skipUntilLetter() {
            if (pos < d.length()) pos++;
            while (pos < d.length() && !Character.isLetter(d.charAt(pos))) pos++;
        }
        private static boolean isNumPart(char c) {
            return Character.isDigit(c) || c == '.';
        }
    }
}
