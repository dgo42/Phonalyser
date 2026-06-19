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

package org.edgo.audio.measure.gui.helpviewer;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Modeless help window: a single SWT {@link Browser} hosted in its own
 * shell, fed with the HTML at {@code help/<lang>/index.html}.  Only one
 * instance is open at a time — a second {@link #show} call brings the
 * existing window to the front (and navigates to a different page when
 * a hint is supplied via {@link #showForActiveItem}).
 *
 * <p>Help-bundle location, in priority order (mirrors {@link I18n}):
 * <ol>
 *   <li>{@code -Dhelp.dir=<path>} system property — set by jpackage to
 *       {@code $APPDIR/help}; lets the user edit / translate pages
 *       in-place without rebuilding.</li>
 *   <li>{@code help/} folder next to the running JAR (or class files
 *       in dev mode).</li>
 *   <li>Classpath fallback: when neither of the above exists, the
 *       bundled resources at {@code /help/<lang>/...} are extracted
 *       to a temp directory.  Used in {@code mvn exec:java} / IDE
 *       runs where the help files live in {@code target/classes/help}.</li>
 * </ol>
 *
 * <p>The browser always loads from a {@code file://} URL so relative
 * hrefs and image references resolve natively.
 */
@Log4j2
public final class HelpViewer {

    /** Sub-directory name inside the resolved help root that holds the
     *  per-language bundles. */
    private static final String FALLBACK_LANG = "en";
    /** Filename of the entry-point help document inside each language
     *  directory. */
    private static final String INDEX_FILE = "index.html";

    /** Injected into every loaded page: maps the mouse thumb buttons
     *  (X1 = back, X2 = forward; DOM {@code button} 3 / 4) and the
     *  horizontal tilt-wheel to history navigation.
     *  <ul>
     *    <li>Thumb buttons are hooked only on non-Chromium engines (the IE
     *        fallback) — WebView2 navigates on them natively, and a JS hook
     *        there would double-navigate.  Pointer events are preferred over
     *        mouse events because the IE engine delivers X-buttons more
     *        reliably through them.</li>
     *    <li>Tilt is reserved for navigation: {@code preventDefault} stops
     *        the engine's own horizontal scroll ({@code passive:false} so
     *        Chromium honours it).  deltaX accumulates against a threshold
     *        with a short cooldown so one tilt gesture navigates exactly
     *        once instead of per wheel tick.</li>
     *  </ul>
     *  Guarded by a window flag because the script is re-executed after
     *  every navigation, including in-page anchor jumps. */
    private static final String NAV_BUTTONS_SCRIPT = """
        if (!window.__phNavHooked) { window.__phNavHooked = true;
          if (!window.chrome) {
            var navUp = function (e) {
              if (e.button === 3)      { e.preventDefault(); history.back(); }
              else if (e.button === 4) { e.preventDefault(); history.forward(); }
            };
            document.addEventListener('mousedown', function (e) {
              if (e.button === 3 || e.button === 4) { e.preventDefault(); }
            });
            if (window.PointerEvent) { document.addEventListener('pointerup', navUp); }
            else                     { document.addEventListener('mouseup',  navUp); }
          }
          var tiltAcc = 0, tiltCoolUntil = 0;
          document.addEventListener('wheel', function (e) {
            if (!e.deltaX) { return; }
            e.preventDefault();
            var now = new Date().getTime();
            if (now < tiltCoolUntil) { return; }
            tiltAcc += e.deltaX;
            if (tiltAcc <= -120)     { tiltAcc = 0; tiltCoolUntil = now + 400; history.back(); }
            else if (tiltAcc >= 120) { tiltAcc = 0; tiltCoolUntil = now + 400; history.forward(); }
          }, { passive: false });
        }
        """;

    /** Injected into every loaded page: when the URL carries a
     *  {@code ?hl=<space-separated terms>} query (added by the search page to
     *  its result links), wraps every occurrence of those terms in
     *  {@code <mark>} and — when there is no {@code #anchor} steering the
     *  scroll — brings the first match into view.  Guarded per location so an
     *  in-page anchor jump doesn't double-wrap.  ES5 / IE11-safe (4-arg
     *  {@code createTreeWalker}, no arrow functions). */
    private static final String HIGHLIGHT_SCRIPT = """
        (function () {
          try {
            var m = /[?&]hl=([^#&]+)/.exec(location.search || location.href);
            if (!m || !document.body) { return; }
            var key = location.pathname + location.search;
            if (window.__phHlFor === key) { return; }
            window.__phHlFor = key;
            var raw = decodeURIComponent(m[1]).toLowerCase().split(/\\s+/);
            var terms = [];
            for (var i = 0; i < raw.length; i++) { if (raw[i].length > 1) terms.push(raw[i]); }
            if (!terms.length) { return; }
            function esc(s) { return s.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&'); }
            var pat = terms.map(esc).join('|');
            var reTest = new RegExp(pat, 'i');
            var reRepl = new RegExp('(' + pat + ')', 'ig');
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
            var nodes = [], n;
            while ((n = walker.nextNode())) {
              var p = n.parentNode; if (!p) { continue; }
              var tag = p.nodeName.toLowerCase();
              if (tag === 'script' || tag === 'style' || tag === 'mark') { continue; }
              if (reTest.test(n.nodeValue)) { nodes.push(n); }
            }
            var first = null;
            for (var k = 0; k < nodes.length; k++) {
              var node = nodes[k];
              var span = document.createElement('span');
              span.innerHTML = node.nodeValue.replace(reRepl, '<mark>$1</mark>');
              node.parentNode.replaceChild(span, node);
              if (!first) { first = span.getElementsByTagName('mark')[0]; }
            }
            if (first && !location.hash && first.scrollIntoView) {
              first.scrollIntoView();
              if (window.scrollBy) { window.scrollBy(0, -60); }
            }
          } catch (e) { }
        })();
        """;

    /** Classpath fallback files, used only when no external help dir
     *  is found.  Listing them explicitly keeps the extractor portable
     *  across packaging modes. */
    private static final String[] HTML_FILES = {
            "index.html",
            "howto.html",
            "generator.html",
            "oscilloscope.html",
            "fft.html",
            "freqresp.html",
            "preferences.html",
            "theory/index.html",
            "theory/audio-backend.html",
            "theory/ring-buffer.html",
            "theory/generator.html",
            "theory/oscilloscope.html",
            "theory/fft.html",
            "theory/derotation-accuracy.html",
            "theory/dac-predistortion.html",
            "theory/freq-resp.html",
            "theory/algorithms.html",
            "external/fft-analysis.html",
            "external/sine-sweep.html",
            "tips.html",
            "credits.html",
            "help-index.html",
            "search-index.js",
            "lunr.min.js",
            "style.css",
    };
    private static final String[] IMAGE_FILES = {
            "img/Generator - sweep mode.png",
            "img/Generator - dual tone.png",
            "img/Oscilloscope - Left.png",
            "img/Oscilloscope - Right.png",
            "img/Oscilloscope - Horizontal.png",
            "img/Oscilloscope - Trigger.png",
            "img/Oscilloscope - Presets.png",
            "img/Oscilloscope - Utility.png",
            "img/Oscilloscope - Save to.png",
            "img/Oscilloscope - Load signal.png",
            "img/FFT - FFT settings.png",
            "img/FFT - THD settings.png",
            "img/FFT - Presets.png",
            "img/FFT - Utility.png",
            "img/FFT - Load calibration.png",
            "img/FFT - Save to.png",
            "img/FFT - Load from.png",
            "img/freqresp-pane.png",
            "img/FreqResp - Settings.png",
            "img/FreqResp - RIAA IEC.png",
            "img/FreqResp - Presets.png",
            "img/FreqResp - Utility.png",
            "img/FreqResp - Load calibration.png",
            "img/FreqResp - Save to.png",
            "img/FreqResp - Load from.png",
            "img/Preferences Look and Feel.png",
            "img/Preferences Audio.png",
            "img/Preferences Oscilloscope.png",
            "img/Preferences FFT.png",
            "img/Preferences Frequency response.png",
            "img/multifunctional.png",
            "img/generator-pane.png",
            "img/oscilloscope-pane.png",
            "img/fft-pane.png",
            "img/dac-predistortion-live.png",
    };

    private static volatile HelpViewer instance;

    /** Single live instance — second invocations re-focus rather than
     *  open a duplicate window. */
    private Shell openShell;
    /** Cached language-specific help root (i.e. the directory holding
     *  the index.html / chapter files for the active language).
     *  Resolved once per JVM lifetime — see {@link #resolveLangRoot}. */
    private volatile Path langRoot;

    private HelpViewer() {}

    public static HelpViewer instance() {
        HelpViewer local = instance;
        if (local == null) {
            synchronized (HelpViewer.class) {
                local = instance;
                if (local == null) {
                    local = new HelpViewer();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Opens (or re-focuses) the help window on the entry-point page.
     */
    public void show(Shell parent) {
        showAt(parent, INDEX_FILE);
    }

    /**
     * Context-sensitive variant.  Walks the focused control's parent
     * chain looking for a {@code "helpAnchor"} data attribute (set on
     * widgets via {@code setData("helpAnchor", "fft.html#fft-length")}).
     * The hint is parsed as either {@code "file"} or {@code "file#anchor"};
     * an empty / missing hint goes to {@link #INDEX_FILE}.
     */
    public void showForActiveItem(Shell parent) {
        String hint = null;
        if (parent != null && parent.getDisplay() != null) {
            Control focus = parent.getDisplay().getFocusControl();
            while (focus != null && hint == null) {
                Object tag = focus.getData("helpAnchor");
                if (tag instanceof String s && !s.isEmpty()) hint = s;
                focus = focus.getParent();
            }
        }
        showAt(parent, hint != null ? hint : INDEX_FILE);
    }

    /** Internal: parse the hint to a (file, anchor) pair, resolve the
     *  file inside the active language root, and load it. */
    private void showAt(Shell parent, String hint) {
        String file;
        String anchor;
        int hash = hint.indexOf('#');
        if (hash < 0) { file = hint; anchor = null; }
        else          { file = hint.substring(0, hash); anchor = hint.substring(hash + 1); }
        if (file.isEmpty()) file = INDEX_FILE;

        Path root = resolveLangRoot();
        if (root == null) {
            Dialogs.error(parent, I18n.t("help.window.title"), I18n.t("help.window.notFound"));
            return;
        }
        Path target = root.resolve(file);
        if (!Files.isRegularFile(target)) {
            log.warn("Help: target {} not found in {}, falling back to {}",
                    file, root, INDEX_FILE);
            target = root.resolve(INDEX_FILE);
            if (!Files.isRegularFile(target)) {
                Dialogs.error(parent, I18n.t("help.window.title"), I18n.t("help.window.notFound"));
                return;
            }
        }
        String url = target.toUri().toString() + (anchor != null ? "#" + anchor : "");

        if (openShell != null && !openShell.isDisposed()) {
            openShell.setActive();
            openShell.forceActive();
            Browser b = findBrowser(openShell);
            if (b != null) b.setUrl(url);
            return;
        }

        Shell s = new Shell(parent, SWT.SHELL_TRIM);
        s.setText(I18n.t("help.window.title"));
        ShellIcons.apply(s);
        s.setLayout(new FillLayout());
        s.setSize(900, 700);
        // Open docked to the right of the main window, visible frames flush.
        // getBounds() is the OS window rect, which on Windows 10/11 includes an
        // invisible resize border (~7 px) beyond the painted edge — so a raw
        // main.x+main.width would leave that border (plus the help window's own
        // left border) as a visible gap.  toDisplay(0,0).x - bounds.x is the
        // left inset = invisible resize border + the ~1 px visible frame.  We
        // want the two VISIBLE frames to touch, which is one invisible border
        // per window, so we subtract 2·inset but add back the two ~1 px visible
        // frames the inset over-counts (without the +2 the help window overlaps
        // the main one by ~2 px).  Clamped to the monitor so it can't open
        // (partly) off-screen.
        if (parent != null && !parent.isDisposed()) {
            Rectangle main   = parent.getBounds();
            Rectangle mon    = parent.getMonitor().getClientArea();
            Point     size   = s.getSize();
            int       inset  = Math.max(0, Math.min(32, parent.toDisplay(0, 0).x - main.x));
            int       dock   = main.x + main.width - 2 * inset + 2;
            int x = Math.min(dock, mon.x + mon.width - size.x);
            int y = Math.min(main.y, mon.y + mon.height - size.y);
            s.setLocation(Math.max(mon.x, x), Math.max(mon.y, y));
        }

        Browser browser;
        try {
            browser = createBrowser(s);
        } catch (SWTError ex) {
            log.error("SWT Browser unavailable on this platform: {}", ex.getMessage());
            Dialogs.error(parent,
                    I18n.t("help.window.title"),
                    I18n.t("help.window.browserUnavailable"));
            s.dispose();
            return;
        }
        // Mouse back/forward (thumb buttons + tilt wheel): the native
        // browser control swallows SWT mouse events, so the mapping is a
        // script injected into each loaded document.  Re-executed on every
        // completed navigation; the script itself guards double-hooking.
        browser.addProgressListener(ProgressListener.completedAdapter(e -> {
            browser.execute(NAV_BUTTONS_SCRIPT);
            browser.execute(HIGHLIGHT_SCRIPT);
        }));
        browser.setUrl(url);

        s.addDisposeListener(e -> openShell = null);
        s.open();
        openShell = s;
    }

    /** Creates the browser widget, preferring the Edge (WebView2) engine on
     *  Windows: it renders sharper and handles the mouse thumb buttons
     *  (back / forward) natively.  Falls back to the platform default (the
     *  IE engine) when the WebView2 runtime is not installed; on Linux the
     *  EDGE style is meaningless and WebKit is used either way. */
    private Browser createBrowser(Shell s) {
        try {
            return new Browser(s, SWT.EDGE);
        } catch (SWTError edgeUnavailable) {
            log.info("Help: WebView2 unavailable ({}) — using the platform default browser",
                    edgeUnavailable.getMessage());
            return new Browser(s, SWT.NONE);
        }
    }

    /** Walks the shell's child tree looking for the embedded Browser. */
    private Browser findBrowser(Composite root) {
        for (Control c : root.getChildren()) {
            if (c instanceof Browser b) return b;
            if (c instanceof Composite child) {
                Browser b = findBrowser(child);
                if (b != null) return b;
            }
        }
        return null;
    }

    /** Resolves the language-specific help directory.  Tries an external
     *  source first ({@code -Dhelp.dir} or {@code help/} next to the
     *  running JAR) so user edits and translations show up without a
     *  rebuild; only falls back to the classpath bundle when nothing
     *  on disk is found.  Result cached for the JVM lifetime. */
    private synchronized Path resolveLangRoot() {
        if (langRoot != null && Files.isDirectory(langRoot)) return langRoot;
        String[] langs = resolveLanguageChain();
        for (Path base : externalHelpBases()) {
            for (String lang : langs) {
                Path candidate = base.resolve(lang);
                if (Files.isDirectory(candidate)
                        && Files.isRegularFile(candidate.resolve(INDEX_FILE))) {
                    log.info("Help: using external bundle at {}", candidate);
                    langRoot = candidate;
                    return langRoot;
                }
            }
        }
        // Classpath fallback (dev mode) — extract to temp dir.
        Path tmpLang = extractFromClasspath(langs);
        if (tmpLang != null) langRoot = tmpLang;
        return langRoot;
    }

    /** Help source dirs in priority order: the writable per-user copy (seeded
     *  from the bundle for the packaged app, so pages can be translated in
     *  place), then the read-only bundled dir as a fallback. */
    private List<Path> externalHelpBases() {
        Path bundled = resolveExternalHelpDir();
        List<Path> bases = new ArrayList<>();
        if (System.getProperty("help.dir") != null && bundled != null) {
            Path user = AppPaths.instance().helpDir();
            AppPaths.instance().seedDirIfEmpty(user, bundled);
            bases.add(user);
        }
        if (bundled != null) bases.add(bundled);
        return bases;
    }

    /** Mirror of {@link I18n#resolveExternalDir}: tries the
     *  {@code -Dhelp.dir} system property, then a {@code help/} folder
     *  alongside the running JAR.  Returns {@code null} when neither
     *  exists. */
    private Path resolveExternalHelpDir() {
        String prop = System.getProperty("help.dir");
        if (prop != null && !prop.isEmpty()) {
            Path p = Paths.get(prop);
            if (Files.isDirectory(p)) return p;
            log.warn("Help: help.dir = '{}' does not exist; falling back", prop);
        }
        try {
            URI src = HelpViewer.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path here = Paths.get(src);
            Path parent = Files.isDirectory(here) ? here : here.getParent();
            if (parent != null) {
                Path nextToJar = parent.resolve("help");
                if (Files.isDirectory(nextToJar)) return nextToJar;
            }
        } catch (Throwable ignored) {
            // CodeSource may be null for some classloaders; fall through.
        }
        return null;
    }

    /** Last-ditch classpath fallback used in {@code mvn exec:java} / IDE
     *  runs where help files live in {@code target/classes/help}.  Copies
     *  the active language bundle to a temp dir so the Browser can
     *  reach it via {@code file://}.  Returns the language-specific
     *  temp dir, or {@code null} when no usable bundle is on the
     *  classpath. */
    private Path extractFromClasspath(String[] langs) {
        for (String lang : langs) {
            if (HelpViewer.class.getResourceAsStream("/help/" + lang + "/" + INDEX_FILE) == null) {
                continue;
            }
            try {
                Path tmp = Files.createTempDirectory("phonalyser-help-");
                tmp.toFile().deleteOnExit();
                Files.createDirectories(tmp.resolve("img"));
                int extracted = 0;
                for (String f : HTML_FILES) {
                    if (copyResource("/help/" + lang + "/" + f, tmp.resolve(f))) extracted++;
                }
                for (String f : IMAGE_FILES) {
                    if (copyResource("/help/" + lang + "/" + f, tmp.resolve(f))) extracted++;
                }
                log.info("Help: extracted classpath bundle ({} files, lang={}) to {}",
                        extracted, lang, tmp);
                return tmp;
            } catch (IOException ex) {
                log.error("Help: failed to extract classpath bundle: {}", ex.getMessage(), ex);
                return null;
            }
        }
        return null;
    }

    private boolean copyResource(String resource, Path target) throws IOException {
        try (InputStream in = HelpViewer.class.getResourceAsStream(resource)) {
            if (in == null) return false;
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return true;
        }
    }

    /** Returns the language-fallback chain for the active UI language:
     *  full BCP-47 tag → primary subtag → {@value #FALLBACK_LANG}. */
    private String[] resolveLanguageChain() {
        String lang = Preferences.instance().getUiLanguage();
        if (lang == null || lang.isEmpty()) lang = FALLBACK_LANG;
        lang = lang.toLowerCase(Locale.ROOT);
        String primary = lang.split("[_-]", 2)[0];
        if (lang.equals(primary)) {
            return primary.equals(FALLBACK_LANG)
                    ? new String[]{ primary }
                    : new String[]{ primary, FALLBACK_LANG };
        }
        return primary.equals(FALLBACK_LANG)
                ? new String[]{ lang, FALLBACK_LANG }
                : new String[]{ lang, primary, FALLBACK_LANG };
    }
}
