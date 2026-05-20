package org.edgo.audio.measure.gui.common;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Shell;

import java.io.InputStream;

/**
 * Applies the Phonalyser app icon to a {@link Shell}.  Picks the format
 * the running OS reads best:
 * <ul>
 *   <li>Windows — {@code phonalyser.ico} (multi-resolution; the OS
 *       chooses the sharpest embedded size for title bar / taskbar);</li>
 *   <li>everywhere else — {@code phonalyser.png}.</li>
 * </ul>
 * The {@code .icns} file is shipped alongside for jpackage to wire into
 * the macOS bundle's {@code Info.plist}; SWT itself doesn't read it at
 * runtime, so the Mac shell falls back to the {@code .png} like Linux.
 *
 * <p>All loaded {@link Image} instances are disposed automatically when
 * the shell is disposed.
 */
@Log4j2
public final class ShellIcons {

    private static final String ICO_PATH = "/icons/phonalyser.ico";
    private static final String PNG_PATH = "/icons/phonalyser.png";

    private ShellIcons() {}

    public static void apply(Shell target) {
        Image[] images = loadIcons(target);
        if (images.length == 0) return;
        target.setImages(images);
        target.addDisposeListener(e -> {
            for (Image img : images) {
                if (img != null && !img.isDisposed()) img.dispose();
            }
        });
    }

    /** Loads the platform-appropriate icon as one or more {@link Image}s.
     *  Returns an empty array on any I/O failure. */
    private static Image[] loadIcons(Shell target) {
        boolean windows = "win32".equals(SWT.getPlatform());
        String primary = windows ? ICO_PATH : PNG_PATH;
        Image[] fromPrimary = tryLoad(target, primary);
        if (fromPrimary.length > 0) return fromPrimary;
        // Fall back to the PNG when the platform-preferred file is
        // missing or unreadable — keeps the window iconised even if
        // the .ico was stripped from a slim build.
        if (!primary.equals(PNG_PATH)) {
            return tryLoad(target, PNG_PATH);
        }
        return new Image[0];
    }

    /** Reads every {@link ImageData} frame from {@code resourcePath}
     *  (an {@code .ico} packs multiple sizes; PNG packs one) and wraps
     *  each as an SWT {@link Image}. */
    private static Image[] tryLoad(Shell target, String resourcePath) {
        try (InputStream in = ShellIcons.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("Shell-icon resource not found: {}", resourcePath);
                return new Image[0];
            }
            ImageLoader loader = new ImageLoader();
            ImageData[] frames = loader.load(in);
            if (frames == null || frames.length == 0) return new Image[0];
            Image[] out = new Image[frames.length];
            for (int i = 0; i < frames.length; i++) {
                out[i] = new Image(target.getDisplay(), frames[i]);
            }
            return out;
        } catch (Exception ex) {
            log.warn("Could not load shell icon {}: {}", resourcePath, ex.getMessage());
            return new Image[0];
        }
    }
}
