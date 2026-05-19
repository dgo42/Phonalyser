package org.edgo.audio.measure.gui.helpviewer;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.gui.I18n;
import org.edgo.audio.measure.gui.Preferences;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

    /** Classpath fallback files, used only when no external help dir
     *  is found.  Listing them explicitly keeps the extractor portable
     *  across packaging modes. */
    private static final String[] HTML_FILES = {
            "index.html",
            "generator.html",
            "oscilloscope.html",
            "fft.html",
            "style.css",
    };
    private static final String[] IMAGE_FILES = {
            "img/Phonalyser.png",
            "img/Generator.png",
            "img/Generator - sweep mode.png",
            "img/Oscilloscope.png",
            "img/Oscilloscope - Left.png",
            "img/Oscilloscope - Right.png",
            "img/Oscilloscope - Horizontal.png",
            "img/Oscilloscope - Trigger.png",
            "img/Oscilloscope - Presets.png",
            "img/Oscilloscope - Utility.png",
            "img/Oscilloscope - Save to.png",
            "img/Oscilloscope - Load signal.png",
            "img/FFT Analyser.png",
            "img/FFT - FFT settings.png",
            "img/FFT - THD settings.png",
            "img/FFT - Presets.png",
            "img/FFT - Utility.png",
            "img/FFT - Save to.png",
            "img/FFT - Load from.png",
            "img/Preferences Audio.png",
            "img/Preferences Oscilloscope.png",
            "img/Preferences FFT.png",
    };

    /** Single live instance — second invocations re-focus rather than
     *  open a duplicate window. */
    private static Shell openShell;
    /** Cached language-specific help root (i.e. the directory holding
     *  the index.html / chapter files for the active language).
     *  Resolved once per JVM lifetime — see {@link #resolveLangRoot}. */
    private static volatile Path langRoot;

    private HelpViewer() {}

    /**
     * Opens (or re-focuses) the help window on the entry-point page.
     */
    public static void show(Shell parent) {
        showAt(parent, INDEX_FILE);
    }

    /**
     * Context-sensitive variant.  Walks the focused control's parent
     * chain looking for a {@code "helpAnchor"} data attribute (set on
     * widgets via {@code setData("helpAnchor", "fft.html#fft-length")}).
     * The hint is parsed as either {@code "file"} or {@code "file#anchor"};
     * an empty / missing hint goes to {@link #INDEX_FILE}.
     */
    public static void showForActiveItem(Shell parent) {
        String hint = null;
        if (parent != null && parent.getDisplay() != null) {
            org.eclipse.swt.widgets.Control focus = parent.getDisplay().getFocusControl();
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
    private static void showAt(Shell parent, String hint) {
        String file;
        String anchor;
        int hash = hint.indexOf('#');
        if (hash < 0) { file = hint; anchor = null; }
        else          { file = hint.substring(0, hash); anchor = hint.substring(hash + 1); }
        if (file.isEmpty()) file = INDEX_FILE;

        Path root = resolveLangRoot();
        if (root == null) {
            MessageBox box = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
            box.setText(I18n.t("help.window.title"));
            box.setMessage(I18n.t("help.window.notFound"));
            box.open();
            return;
        }
        Path target = root.resolve(file);
        if (!Files.isRegularFile(target)) {
            log.warn("Help: target {} not found in {}, falling back to {}",
                    file, root, INDEX_FILE);
            target = root.resolve(INDEX_FILE);
            if (!Files.isRegularFile(target)) {
                MessageBox box = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
                box.setText(I18n.t("help.window.title"));
                box.setMessage(I18n.t("help.window.notFound"));
                box.open();
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
        s.setLayout(new FillLayout());
        s.setSize(900, 700);

        Browser browser;
        try {
            browser = new Browser(s, SWT.NONE);
        } catch (SWTError ex) {
            log.error("SWT Browser unavailable on this platform: {}", ex.getMessage());
            MessageBox box = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
            box.setText(I18n.t("help.window.title"));
            box.setMessage(I18n.t("help.window.browserUnavailable"));
            box.open();
            s.dispose();
            return;
        }
        browser.setUrl(url);

        s.addDisposeListener(e -> openShell = null);
        s.open();
        openShell = s;
    }

    /** Walks the shell's child tree looking for the embedded Browser. */
    private static Browser findBrowser(org.eclipse.swt.widgets.Composite root) {
        for (org.eclipse.swt.widgets.Control c : root.getChildren()) {
            if (c instanceof Browser b) return b;
            if (c instanceof org.eclipse.swt.widgets.Composite child) {
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
    private static synchronized Path resolveLangRoot() {
        if (langRoot != null && Files.isDirectory(langRoot)) return langRoot;
        String[] langs = resolveLanguageChain();
        Path externalBase = resolveExternalHelpDir();
        if (externalBase != null) {
            for (String lang : langs) {
                Path candidate = externalBase.resolve(lang);
                if (Files.isDirectory(candidate)
                        && Files.isRegularFile(candidate.resolve(INDEX_FILE))) {
                    log.info("Help: using external bundle at {}", candidate);
                    langRoot = candidate;
                    return langRoot;
                }
            }
            log.warn("Help: external dir {} contains no usable language bundle for {}",
                    externalBase, String.join(", ", langs));
        }
        // Classpath fallback (dev mode) — extract to temp dir.
        Path tmpLang = extractFromClasspath(langs);
        if (tmpLang != null) langRoot = tmpLang;
        return langRoot;
    }

    /** Mirror of {@link I18n#resolveExternalDir}: tries the
     *  {@code -Dhelp.dir} system property, then a {@code help/} folder
     *  alongside the running JAR.  Returns {@code null} when neither
     *  exists. */
    private static Path resolveExternalHelpDir() {
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
    private static Path extractFromClasspath(String[] langs) {
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

    private static boolean copyResource(String resource, Path target) throws IOException {
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
    private static String[] resolveLanguageChain() {
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
