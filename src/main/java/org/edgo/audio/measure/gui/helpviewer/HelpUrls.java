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

/**
 * GitHub project URLs used by the Help menu actions.  Change these in
 * one place when the project moves to a different repository.
 */
public final class HelpUrls {

    /** Repository root.  Used to compose the releases / issues URLs and
     *  shown in the About dialog. */
    public static final String REPO_URL     = "https://github.com/dgo42/Phonalyser";
    public static final String RELEASES_URL = REPO_URL + "/releases";
    public static final String ISSUES_URL   = REPO_URL + "/issues/new";
    /** GitHub API endpoint that lists releases newest-first, INCLUDING
     *  pre-releases; used by the update checker.  The {@code /releases/latest}
     *  endpoint is deliberately NOT used: it excludes pre-releases and drafts
     *  and 404s whenever the newest (or only) release is a pre-release (e.g. an
     *  {@code -RC} build), which also dead-ended the "include beta" option. */
    public static final String RELEASES_API_URL =
            "https://api.github.com/repos/dgo42/Phonalyser/releases";

    /** Microsoft Store product ID of the app's listing.  Fills the
     *  {@code ms-windows-store:} deep link the in-app update check opens when the
     *  app runs as a Store (MSIX) package — empty until the app is in the Store,
     *  in which case the generic Store "Downloads &amp; updates" page is opened. */
    public static final String STORE_PRODUCT_ID = "9NR1W5DKW71M";

    private HelpUrls() {}
}
