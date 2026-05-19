package org.edgo.audio.measure.gui.helpviewer;

/**
 * GitHub project URLs used by the Help menu actions.  Change these in
 * one place when the project moves to a different repository.
 */
public final class HelpUrls {

    private HelpUrls() {}

    /** Repository root.  Used to compose the releases / issues URLs and
     *  shown in the About dialog. */
    public static final String REPO_URL     = "https://github.com/edgo-org/phonalyser";
    public static final String RELEASES_URL = REPO_URL + "/releases";
    public static final String ISSUES_URL   = REPO_URL + "/issues/new";
    /** GitHub API endpoint that returns the latest release JSON; used by
     *  the update checker once that feature is implemented. */
    public static final String LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/edgo-org/phonalyser/releases/latest";
}
