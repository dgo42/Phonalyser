package org.edgo.audio.measure.gui.helpviewer;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.gui.I18n;

/**
 * GitHub-releases update check.  The real implementation (HTTPS GET to
 * {@link HelpUrls#LATEST_RELEASE_API_URL}, JSON parse, version compare,
 * download prompt) is not wired yet — this stub keeps the UI working
 * by showing a "not available" message and offering to open the
 * releases page in the system browser.
 */
@Log4j2
public final class UpdateChecker {

    private UpdateChecker() {}

    /**
     * User-triggered check via the Help menu.  Future implementation
     * should call the GitHub releases API and compare against the
     * current app version; for now we just point the user at the
     * releases page so manual checking is one click away.
     */
    public static void checkNow(Shell parent) {
        log.info("Manual update check requested (stub — opening releases page)");
        MessageBox box = new MessageBox(parent, SWT.ICON_INFORMATION | SWT.YES | SWT.NO);
        box.setText(I18n.t("help.update.title"));
        box.setMessage(I18n.t("help.update.stub.message", HelpUrls.RELEASES_URL));
        if (box.open() == SWT.YES) {
            Program.launch(HelpUrls.RELEASES_URL);
        }
    }

    /**
     * Startup-time check, gated by the
     * {@code checkForUpdatesOnStartup} preference.  Currently a no-op
     * — the network logic isn't wired in yet, but the entry point
     * exists so the launcher can call it without conditional imports
     * once the feature lands.
     *
     * @param includeBeta when true, pre-release versions are also
     *                    considered candidates
     */
    public static void checkOnStartup(boolean includeBeta) {
        log.debug("Startup update check (stub) — includeBeta={}", includeBeta);
        // Intentionally silent: don't surface anything until the real
        // check is implemented.  When ready, this method should:
        //   1. HTTPS GET HelpUrls.LATEST_RELEASE_API_URL
        //   2. parse "tag_name" + "prerelease" fields
        //   3. compare against app.version
        //   4. if newer, post a toast / notification on the SWT thread
    }
}
