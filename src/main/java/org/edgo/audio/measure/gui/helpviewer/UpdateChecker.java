package org.edgo.audio.measure.gui.helpviewer;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.gui.i18n.I18n;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub-releases update check.  Hits {@link HelpUrls#LATEST_RELEASE_API_URL},
 * extracts {@code tag_name}, {@code html_url} and {@code prerelease} from the
 * JSON response, compares the tag against the running app's version via
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
 */
@Log4j2
@UtilityClass
public class UpdateChecker {

    private final Pattern TAG_NAME_PAT   = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private final Pattern HTML_URL_PAT   = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");
    private final Pattern PRERELEASE_PAT = Pattern.compile("\"prerelease\"\\s*:\\s*(true|false)");

    private final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** User-triggered check via the Help menu.  Always shows a result. */
    public void checkNow(Shell parent) {
        log.info("Manual update check requested");
        runCheckInBackground(parent, false, true);
    }

    /**
     * Startup-time silent check.  Only shows the dialog when a newer
     * release is available; up-to-date / network errors are logged but
     * not surfaced.
     *
     * @param parent      shell to anchor the eventual dialog to
     * @param includeBeta when true, pre-release tags are also considered
     */
    public void checkOnStartup(Shell parent, boolean includeBeta) {
        log.debug("Startup update check, includeBeta={}", includeBeta);
        runCheckInBackground(parent, true, includeBeta);
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
                .uri(URI.create(HelpUrls.LATEST_RELEASE_API_URL))
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
