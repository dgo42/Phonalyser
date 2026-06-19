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

package org.edgo.audio.measure.gui.i18n;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.edgo.audio.measure.common.AppPaths;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * Translation helper backed by a plain Java {@link ResourceBundle}.  Keys
 * live in {@code messages.properties} (English, default) and per-locale
 * variants {@code messages_<lang>.properties} (e.g. {@code messages_de.properties}).
 * Java's bundle loader handles the fallback chain {@code de_AT → de →
 * default} automatically.
 *
 * <p>The bundles are loaded from an external {@code i18n/} folder so users
 * can drop in new languages without rebuilding.  Lookup order:
 * <ol>
 *   <li>{@code -Di18n.dir=<path>} system property (set by the jpackage
 *       launcher to {@code $APPDIR/i18n}).</li>
 *   <li>{@code i18n/} folder next to the running JAR / classes directory.</li>
 *   <li>Classpath fallback (e.g. dev mode where {@code src/main/resources/i18n/}
 *       is on the classpath).</li>
 * </ol>
 *
 * <p>If a key is missing the key itself is returned (rather than throwing) —
 * makes it easy to spot un-translated strings at runtime without crashing
 * the GUI.
 */
@Log4j2
@UtilityClass
public class I18n {

    private static final String BUNDLE_NAME = "i18n.messages";

    /** Directory containing the external {@code messages*.properties} files,
     *  or {@code null} when only the classpath fallback is in play. */
    private static final Path EXTERNAL_DIR = resolveExternalDir();

    /** Classloader that overlays {@link #EXTERNAL_DIR}'s parent on top of the
     *  app's own classloader, so {@link ResourceBundle#getBundle} finds the
     *  on-disk properties files via the {@code i18n.messages} resource path.
     *  Falls back to the default classloader when no external dir was found. */
    private static final ClassLoader BUNDLE_CL = buildBundleClassLoader(EXTERNAL_DIR);

    private static volatile ResourceBundle bundle =
            ResourceBundle.getBundle(BUNDLE_NAME, Locale.getDefault(), BUNDLE_CL);
    private static volatile Locale currentLocale = Locale.getDefault();

    /** Switches the active locale.  Re-resolves the bundle so subsequent
     *  {@link #t} calls return strings from the new language.  Widgets
     *  already created keep their previous text — a restart (or a manual
     *  re-layout) is needed to pick up the new strings everywhere. */
    public static void setLocale(Locale locale) {
        if (locale == null) return;
        Locale.setDefault(locale);
        currentLocale = locale;
        bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale, BUNDLE_CL);
        log.info("UI language: {}", locale);
    }

    public static Locale getLocale() {
        return currentLocale;
    }

    /** Returns the translated string for {@code key}.  When {@code args}
     *  are supplied the pattern is run through {@link MessageFormat} so
     *  {@code {0}} / {@code {1,number,#.##}} placeholders work as expected. */
    public static String t(String key, Object... args) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException ex) {
            log.warn("Missing i18n key: {}", key);
            return key;
        }
        if (args == null || args.length == 0) return pattern;
        return MessageFormat.format(pattern, args);
    }

    /** External i18n directory if one was resolved at startup, else
     *  {@code null}.  Used by the language-menu builder to enumerate
     *  available bundles from disk. */
    public static Path externalDir() {
        return EXTERNAL_DIR;
    }

    /** Resolution order documented on the class-level Javadoc.  For the
     *  packaged app (jpackage sets {@code -Di18n.dir}) the bundled bundles are
     *  seeded into a writable per-user dir which becomes the primary source so
     *  translations can be edited; the bundled root stays in the classloader
     *  as a fallback.  In dev the classpath/target dir is left untouched. */
    private static Path resolveExternalDir() {
        Path bundled = resolveBundledDir();
        boolean packaged = System.getProperty("i18n.dir") != null;
        if (!packaged || bundled == null) return bundled;
        Path user = AppPaths.instance().i18nDir();
        AppPaths.instance().seedDirIfEmpty(user, bundled);
        return user;
    }

    /** The read-only bundled i18n dir: {@code -Di18n.dir} (jpackage sets it to
     *  {@code $APPDIR/i18n}), else an {@code i18n/} folder next to the JAR. */
    private static Path resolveBundledDir() {
        String prop = System.getProperty("i18n.dir");
        if (prop != null && !prop.isEmpty()) {
            Path p = Paths.get(prop);
            if (Files.isDirectory(p)) return p;
            log.warn("i18n.dir = '{}' does not exist; falling back", prop);
        }
        try {
            URI src = I18n.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path here = Paths.get(src);
            Path parent = Files.isDirectory(here) ? here : here.getParent();
            if (parent != null) {
                Path nextToJar = parent.resolve("i18n");
                if (Files.isDirectory(nextToJar)) return nextToJar;
            }
        } catch (Throwable ignored) {
            // CodeSource may be null for some classloaders; fall through.
        }
        return null;
    }

    private static ClassLoader buildBundleClassLoader(Path externalDir) {
        ClassLoader parent = I18n.class.getClassLoader();
        List<URL> urls = new ArrayList<>();
        addBundleRoot(urls, externalDir);          // user dir (overrides) when packaged
        addBundleRoot(urls, resolveBundledDir());  // bundled root (always a fallback)
        if (urls.isEmpty()) return parent;
        log.info("i18n external dir: {}", externalDir != null ? externalDir.toAbsolutePath() : "(classpath)");
        return new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    /** Adds the parent ("bundle root") of {@code bundleDir} to {@code urls} so
     *  {@link ResourceBundle} resolves {@code i18n/messages*.properties} under
     *  it.  Dedupes, so dev (external == bundled) yields a single entry. */
    private static void addBundleRoot(List<URL> urls, Path bundleDir) {
        if (bundleDir == null) return;
        Path root = bundleDir.getParent();
        if (root == null) return;
        try {
            URL u = root.toUri().toURL();
            if (!urls.contains(u)) urls.add(u);
        } catch (MalformedURLException ex) {
            log.warn("Could not build i18n classloader for {}: {}", root, ex.getMessage());
        }
    }
}
