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

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.common.StoreUpdate;
import org.edgo.audio.measure.common.WindowsPackage;
import org.edgo.audio.measure.gui.i18n.I18n;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub-releases update check.  Hits {@link HelpUrls#RELEASES_API_URL} — the
 * releases list, newest first — extracts {@code tag_name}, {@code html_url} and
 * {@code prerelease} from the NEWEST release, compares the tag against the
 * running app's version via
 * {@link Versions#compare(String, String)} and, if a newer release exists,
 * pops a yes/no dialog offering to open the release page in the system
 * browser.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #checkNow(Shell)} — user invoked from the Help menu.
 *       Always surfaces a result (newer / up-to-date / error).</li>
 *   <li>{@link #checkOnStartup(Shell, boolean)} — silent background check
 *       triggered from the main window once it has opened.  Only surfaces
 *       the dialog when an update is available; up-to-date and error
 *       cases stay in the log.</li>
 * </ul>
 *
 * <p>Both paths run the network I/O on a daemon thread so the UI stays
 * responsive, and marshal the dialog back to the SWT thread via
 * {@link Display#asyncExec(Runnable)}.
 *
 * <p>JSON parsing is intentionally tiny — three regexes over the response
 * body — to avoid pulling in a JSON library for two fields and a boolean.
 * The list is sorted newest-first, so the first match of each pattern is the
 * newest release's field.
 */
@Log4j2
@UtilityClass
public class UpdateChecker {

    private final Pattern TAG_NAME_PAT   = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private final Pattern HTML_URL_PAT   = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");
    private final Pattern PRERELEASE_PAT = Pattern.compile("\"prerelease\"\\s*:\\s*(true|false)");

    private final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** User-triggered check via the Help menu.  Always shows a result.  A Store
     *  (MSIX) install checks the Microsoft Store directly instead of GitHub. */
    public void checkNow(Shell parent) {
        log.info("Manual update check requested");
        if (WindowsPackage.isPackaged()) {
            checkStoreInBackground(parent);
            return;
        }
        runCheckInBackground(parent, false, true);
    }

    /**
     * Startup-time silent check.  Only shows the dialog when a newer
     * release is available; up-to-date / network errors are logged but
     * not surfaced.  Skipped for a Microsoft Store (MSIX) install, which
     * the Store keeps up to date on its own.
     *
     * @param parent      shell to anchor the eventual dialog to
     * @param includeBeta when true, pre-release tags are also considered
     */
    public void checkOnStartup(Shell parent, boolean includeBeta) {
        if (WindowsPackage.isPackaged()) {
            log.debug("Microsoft Store (MSIX) package — skipping startup update check (Store auto-updates)");
            return;
        }
        log.debug("Startup update check, includeBeta={}", includeBeta);
        runCheckInBackground(parent, true, includeBeta);
    }

    /**
     * Direct Microsoft Store update check for a Store (MSIX) install.  Asks the
     * Store via {@link StoreUpdate}: if it confirms the app is up to date, says
     * so; otherwise — an update is available, or the query couldn't run — opens
     * the Store so the user can review / install it there.
     */
    private void checkStoreInBackground(Shell parent) {
        final Display display = parent.getDisplay();
        Thread t = new Thread(() -> {
            int count = StoreUpdate.availableUpdateCount();
            log.info("Store update check: availableUpdateCount={}", count);
            display.asyncExec(() -> {
                if (parent.isDisposed()) return;
                if (count == 0) {
                    showUpToDate(parent, Versions.appVersion());
                } else {
                    openStorePage();
                }
            });
        }, "store-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Open the Microsoft Store — the app's product page when its Store ID is
     *  configured, otherwise the Store's "Downloads &amp; updates" list. */
    private void openStorePage() {
        String uri = HelpUrls.STORE_PRODUCT_ID.isEmpty()
                ? "ms-windows-store://downloadsandupdates"
                : "ms-windows-store://pdp/?ProductId=" + HelpUrls.STORE_PRODUCT_ID;
        Program.launch(uri);
    }

    private void runCheckInBackground(Shell parent, boolean silentIfUpToDate, boolean includeBeta) {
        final Display display = parent.getDisplay();
        Thread t = new Thread(() -> {
            ReleaseInfo info;
            try {
                info = fetchLatestRelease();
            } catch (Exception ex) {
                log.warn("Update check failed: {}", ex.toString());
                if (!silentIfUpToDate) {
                    final String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    display.asyncExec(() -> { if (!parent.isDisposed()) showError(parent, reason); });
                }
                return;
            }
            if (info == null || info.tag == null) {
                log.warn("Update check: response did not contain tag_name");
                return;
            }
            final String current = Versions.appVersion();
            if (!includeBeta && info.preRelease) {
                log.info("Latest release {} is a pre-release; skipping (includeBeta=false)", info.tag);
                if (!silentIfUpToDate) {
                    display.asyncExec(() -> { if (!parent.isDisposed()) showUpToDate(parent, current); });
                }
                return;
            }
            int cmp = Versions.compare(info.tag, current);
            log.info("Update check: latest={}, current={}, cmp={}", info.tag, current, cmp);
            if (cmp > 0) {
                final ReleaseInfo found = info;
                display.asyncExec(() -> { if (!parent.isDisposed()) showNewer(parent, found, current); });
            } else if (!silentIfUpToDate) {
                display.asyncExec(() -> { if (!parent.isDisposed()) showUpToDate(parent, current); });
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(HelpUrls.RELEASES_API_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Phonalyser-update-check")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        String body = resp.body();
        return new ReleaseInfo(
                firstMatch(TAG_NAME_PAT, body),
                firstMatch(HTML_URL_PAT, body),
                "true".equals(firstMatch(PRERELEASE_PAT, body)));
    }

    private String firstMatch(Pattern p, String body) {
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private void showNewer(Shell parent, ReleaseInfo info, String current) {
        MessageBox box = new MessageBox(parent, SWT.ICON_INFORMATION | SWT.YES | SWT.NO);
        box.setText(I18n.t("help.update.title"));
        box.setMessage(I18n.t("help.update.newer.message", info.tag, current));
        if (box.open() == SWT.YES) {
            Program.launch(info.url != null ? info.url : HelpUrls.RELEASES_URL);
        }
    }

    private void showUpToDate(Shell parent, String current) {
        MessageBox box = new MessageBox(parent, SWT.ICON_INFORMATION | SWT.OK);
        box.setText(I18n.t("help.update.title"));
        box.setMessage(I18n.t("help.update.upToDate.message", current));
        box.open();
    }

    private void showError(Shell parent, String reason) {
        MessageBox box = new MessageBox(parent, SWT.ICON_WARNING | SWT.OK);
        box.setText(I18n.t("help.update.title"));
        box.setMessage(I18n.t("help.update.error.message", reason));
        box.open();
    }

    private record ReleaseInfo(String tag, String url, boolean preRelease) {}
}
