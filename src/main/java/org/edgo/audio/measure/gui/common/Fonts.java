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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.preferences.Preferences;

/**
 * Central creation point for the application's three UI fonts, configured
 * through {@link Preferences} as {@code name|height|style} specs:
 * <ul>
 *   <li><b>normal</b> — axis labels, readouts, measurement-table body text
 *       (FFT / scope / FreqResp views, MainTab sidebar labels, the
 *       FreqResp live meter);</li>
 *   <li><b>bold</b> — emphasised measurement-table text;</li>
 *   <li><b>channel</b> — the big L/R channel buttons (not user-editable in
 *       the Preferences dialog, but centralised here).</li>
 * </ul>
 *
 * <p>One {@link Font} per role is cached per {@link Display} and disposed
 * by a display-dispose hook — consumers must NOT dispose what they get
 * here.  A font-preference change invalidates the cache, but running
 * widgets keep their old Font objects; the Preferences dialog therefore
 * triggers an in-place content rebuild ({@code MainWindow.rebuildContent}),
 * and the rebuilt views pick the new fonts up on construction.
 */
public final class Fonts {

    private static final Fonts INSTANCE = new Fonts();

    /** Cache key per role; the value parses the spec lazily. */
    private enum Role {
        NORMAL, BOLD, CHANNEL;

        private Role() {}
    }

    /** Live Font per role for the (single) display that requested it. */
    private final Map<Role, Font> cache = new HashMap<>();
    /** Fonts retired by {@link #invalidate()} — widgets built before the
     *  spec change may still paint with them, so they are only disposed
     *  with the display ({@link #disposeAll()}). */
    private final List<Font> retired = new ArrayList<>();
    /** Display the cached fonts belong to; re-keyed when a fresh display
     *  appears (tests / restarts). */
    private Display cachedDisplay;

    private Fonts() {
        // Invalidate on any spec change — the recreated shell re-fetches.
        Preferences prefs = Preferences.instance();
        prefs.uiFontNormalProperty().addListener(v -> invalidate());
        prefs.uiFontBoldProperty().addListener(v -> invalidate());
        prefs.uiFontChannelProperty().addListener(v -> invalidate());
    }

    public static Fonts instance() {
        return INSTANCE;
    }

    /** Axis / readout / table-body font. */
    public Font normal(Display d) {
        return font(d, Role.NORMAL, Preferences.instance().getUiFontNormal());
    }

    /** Emphasised measurement-table font. */
    public Font bold(Display d) {
        return font(d, Role.BOLD, Preferences.instance().getUiFontBold());
    }

    /** L/R channel-button font. */
    public Font channel(Display d) {
        return font(d, Role.CHANNEL, Preferences.instance().getUiFontChannel());
    }

    /** Renders a spec for display in the Preferences dialog, e.g.
     *  {@code "Consolas 9 bold+italic"}. */
    public String describe(String spec) {
        String[] p = split(spec);
        return p[0] + " " + p[1] + ("normal".equals(p[2]) ? "" : " " + p[2]);
    }

    /** Spec → {@link FontData}, for seeding the SWT FontDialog. */
    public FontData toFontData(String spec) {
        String[] p = split(spec);
        return new FontData(p[0], parseHeight(p[1]), parseStyle(p[2]));
    }

    /** {@link FontData} → spec.  Bold and italic survive — they are the
     *  only styles an SWT {@link Font} can carry (underline / strikeout
     *  are text-layout attributes, not font attributes). */
    public String toSpec(FontData fd) {
        int st = fd.getStyle();
        String style = (st & SWT.BOLD) != 0 && (st & SWT.ITALIC) != 0 ? "bold+italic"
                : (st & SWT.BOLD)   != 0 ? "bold"
                : (st & SWT.ITALIC) != 0 ? "italic"
                : "normal";
        return fd.getName() + "|" + fd.getHeight() + "|" + style;
    }

    /** Style token(s) → SWT style bits; tokens may combine as
     *  {@code "bold+italic"}. */
    private int parseStyle(String token) {
        int style = SWT.NORMAL;
        if (token.contains("bold"))   style |= SWT.BOLD;
        if (token.contains("italic")) style |= SWT.ITALIC;
        return style;
    }

    private synchronized Font font(Display d, Role role, String spec) {
        if (cachedDisplay != d) {
            // A new display (app restart inside one JVM, tests): the old
            // display disposed our fonts with itself — just drop the refs.
            cache.clear();
            cachedDisplay = d;
            d.disposeExec(this::disposeAll);
        }
        Font f = cache.get(role);
        if (f == null || f.isDisposed()) {
            String[] p = split(spec);
            f = new Font(d, p[0], parseHeight(p[1]), parseStyle(p[2]));
            cache.put(role, f);
        }
        return f;
    }

    /** Splits a {@code name|height|style} spec, padding missing parts with
     *  the defaults of the NORMAL role so a hand-edited YAML can't break
     *  the UI. */
    private String[] split(String spec) {
        String[] out = { "Consolas", "9", "normal" };
        if (spec != null) {
            String[] p = spec.split("\\|");
            if (p.length > 0 && !p[0].isBlank()) out[0] = p[0].trim();
            if (p.length > 1 && !p[1].isBlank()) out[1] = p[1].trim();
            if (p.length > 2 && !p[2].isBlank()) out[2] = p[2].trim().toLowerCase(Locale.ROOT);
        }
        return out;
    }

    private int parseHeight(String s) {
        try {
            int h = Integer.parseInt(s);
            return Math.max(6, Math.min(72, h));
        } catch (NumberFormatException ex) {
            return 9;
        }
    }

    private synchronized void invalidate() {
        // Old fonts stay alive for widgets still holding them (disposed
        // with the display); fresh lookups re-parse the new specs.
        retired.addAll(cache.values());
        cache.clear();
    }

    private synchronized void disposeAll() {
        for (Font f : cache.values()) {
            if (f != null && !f.isDisposed()) f.dispose();
        }
        for (Font f : retired) {
            if (f != null && !f.isDisposed()) f.dispose();
        }
        cache.clear();
        retired.clear();
        cachedDisplay = null;
    }
}
