package org.edgo.audio.measure.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import org.edgo.audio.measure.gui.i18n.I18n;

import lombok.extern.log4j.Log4j2;

/**
 * Frequency-response tab — placeholder for the sweep-driven FR measurement
 * UI that will live here in a later iteration.  Today it just shows a
 * centered "coming soon" label so the tab can ship alongside the
 * tab-orientation refactor and reserve a slot in the navigation.
 */
@Log4j2
public final class FrequencyResponseTab {

    public FrequencyResponseTab(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        Label placeholder = new Label(parent, SWT.CENTER);
        placeholder.setText(I18n.t("tab.frequencyResponse.placeholder"));
        placeholder.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
    }
}
