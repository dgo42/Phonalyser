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

package org.edgo.audio.measure.gui.common;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * Single owner of every UI {@link Image}, loaded from the bundled transparent
 * PNGs under {@code /icons}.  Each distinct icon (including its size / light-dark
 * variants) is a value of {@link Icon}; {@link #icon(Display, Icon)} loads it on
 * first request and caches the {@code Image} for the life of the {@link Display},
 * so identical icons on different panes share one instance and need no per-call
 * dispose.
 *
 * <p>Drawing icons with a {@code GC} into an {@code ImageData}-backed transparent
 * image proved unreliable on macOS SWT (the top band of an alpha buffer is left
 * unpainted / over-read), so the icons are pre-rendered PNGs with real alpha and
 * simply loaded here.
 *
 * <h2>Lifecycle</h2>
 * <p>Call {@link #registerShell(Shell)} on the main shell; when it is disposed
 * every cached Image is disposed and the cache cleared, so a fresh shell (e.g.
 * after a language switch) starts clean.  SWT is single-threaded — the cache is
 * only touched from the UI thread that owns the {@link Display}.
 */
@Log4j2
@UtilityClass
public class IconUtils {

    /** Square size (px) of a pane's action buttons (calibrate / play / screenshot). */
    public final int ACTION_BUTTON_PX = 43;
    /** Height (px) of the small file/utility buttons (browse / save / clear / ± / file-play). */
    public final int FILE_BUTTON_HEIGHT = 28;

    private final Map<Icon, Image> cache = new EnumMap<>(Icon.class);

    /**
     * Returns the cached {@link Image} for {@code icon}, loading its PNG from
     * {@code /icons} on first request.  The image is owned by this cache — never
     * dispose it at the call site.  Returns {@code null} (logged) if the
     * resource is missing.
     */
    public Image icon(Display display, Icon icon) {
        return cache.computeIfAbsent(icon, k -> load(display, k));
    }

    /**
     * Hooks {@code shell} so that, when it is disposed, every cached Image is
     * disposed and the cache cleared.  Safe to call more than once.
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

    private Image load(Display display, Icon icon) {
        String file = icon.getFileName();
        try (InputStream in = IconUtils.class.getResourceAsStream("/icons/" + file)) {
            if (in == null) {
                log.warn("Icon resource not found: /icons/{}", file);
                return null;
            }
            return new Image(display, in);
        } catch (Exception ex) {
            log.warn("Failed to load icon /icons/{}: {}", file, ex.getMessage());
            return null;
        }
    }
}
