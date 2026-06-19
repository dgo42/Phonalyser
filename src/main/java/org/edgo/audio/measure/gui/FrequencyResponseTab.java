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

package org.edgo.audio.measure.gui;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;

import org.edgo.audio.measure.gui.freqresp.FreqRespPane;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Frequency-response tab — hosts the {@link FreqRespPane} (Phase 5+
 * skeleton).  Earlier this class was a "coming soon" placeholder
 * reserved for the future feature; now it just wraps the real pane in a
 * single-cell layout so {@link MainTab}'s tab host doesn't need to know
 * about the internals of the FreqResp implementation.
 */
@Log4j2
public final class FrequencyResponseTab {

    @Getter private final FreqRespPane pane;

    public FrequencyResponseTab(Composite parent) {
        parent.setLayout(new FillLayout());
        pane = new FreqRespPane(parent);
    }
}
