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
