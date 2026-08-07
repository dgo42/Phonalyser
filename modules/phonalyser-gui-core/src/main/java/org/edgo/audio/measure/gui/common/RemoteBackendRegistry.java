/*
 * Phonalyser - precision audio measurement workbench.
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

import java.util.ServiceLoader;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * The one place that knows whether this build can reach backends on other
 * machines.  Indexes the single {@link RemoteBackendUi} the service loader can
 * find; {@link #getUi()} is {@code null} when none is on the class path, and the
 * Preferences dialog then simply shows no server button.
 *
 * <p>A singleton because the implementation it holds is not a lookup result but
 * a LIVE session: the connection to a server, its selected backend and the
 * device catalogue behind it all outlive the dialog that opened them.  Loading
 * the service afresh per dialog would hand the second dialog a second, unconnected
 * instance and quietly strand the first one's connection.
 *
 * <p>Only one implementation is meaningful - "which servers can I reach" is not
 * a per-backend question - so a second registration is logged and dropped rather
 * than silently replacing the first.
 */
@Log4j2
public final class RemoteBackendRegistry {

    private static volatile RemoteBackendRegistry instance;

    /** The remote-backend UI this build ships, or {@code null} when it ships
     *  none.  Final: the service set cannot change while the process runs. */
    @Getter
    private final RemoteBackendUi ui;

    private RemoteBackendRegistry() {
        RemoteBackendUi found = null;
        for (RemoteBackendUi candidate : ServiceLoader.load(RemoteBackendUi.class)) {
            if (found == null) {
                found = candidate;
            } else if (log.isWarnEnabled()) {
                log.warn("Remote backend UI: duplicate {} ignored (kept {})",
                        candidate.getClass().getName(), found.getClass().getName());
            }
        }
        ui = found;
        if (log.isInfoEnabled()) {
            log.info("Remote backend UI: {}", found == null ? "none" : found.getClass().getName());
        }
    }

    public static RemoteBackendRegistry instance() {
        RemoteBackendRegistry local = instance;
        if (local == null) {
            synchronized (RemoteBackendRegistry.class) {
                local = instance;
                if (local == null) {
                    local = new RemoteBackendRegistry();
                    instance = local;
                }
            }
        }
        return local;
    }
}
