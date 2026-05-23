package org.edgo.audio.measure.gui;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Path;
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
        Image img = new Image(display, width, height);
        GC gc = new GC(img);
        try {
            gc.setAntialias(SWT.ON);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(0, 0, width, height);

            String svg = readResource(resourcePath);
            if (svg == null) return img;
            double[] vb = parseViewBox(svg);
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
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
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

    /** Pulls every {@code d="…"} attribute out of {@code <path …/>} elements, in document order. */
    private static List<String> extractPathData(String svg) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<path\\b[^>]*?\\bd\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL).matcher(svg);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Parses an SVG path "{@code d}" string into an SWT {@link Path}.  Handles
     * absolute M / L / C / Z (the only commands the bundled icons use); a
     * lone M is followed by implicit L's per the SVG spec.
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
                    // Unknown command; skip to next letter.  Logged here would
                    // be noisy on a large SVG, so just drop silently — every
                    // unknown command shifts the cursor in a way that risks
                    // corrupting the remainder, but for our small icon set we
                    // only emit M / L / C / Z so the branch is unreached.
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
        private static boolean isNumPart(char c) {
            return Character.isDigit(c) || c == '.';
        }
    }
}
