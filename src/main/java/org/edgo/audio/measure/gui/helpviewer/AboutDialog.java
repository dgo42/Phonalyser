package org.edgo.audio.measure.gui.helpviewer;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.i18n.I18n;

/**
 * Simple About dialog: app name, version, vendor and a clickable link
 * to the GitHub repository.  Modal so the user dismisses it before
 * returning to the main UI.
 */
@Log4j2
@UtilityClass
public class AboutDialog {

    public void show(Shell parent) {
        Shell s = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        s.setText(I18n.t("help.about.title"));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 24; gl.marginHeight = 20; gl.verticalSpacing = 6;
        s.setLayout(gl);

        Label name = new Label(s, SWT.NONE);
        name.setText("Phonalyser " + Versions.appVersion());
        name.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Label tagline = new Label(s, SWT.NONE);
        tagline.setText(I18n.t("help.about.tagline"));
        tagline.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Label vendor = new Label(s, SWT.NONE);
        vendor.setText("© edgo.org");
        vendor.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Link link = new Link(s, SWT.NONE);
        link.setText("<a href=\"" + HelpUrls.REPO_URL + "\">" + HelpUrls.REPO_URL + "</a>");
        link.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        link.addListener(SWT.Selection, e -> Program.launch(e.text));

        Button close = new Button(s, SWT.PUSH);
        close.setText(I18n.t("common.ok"));
        GridData cd = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        cd.widthHint = 90;
        cd.verticalIndent = 12;
        close.setLayoutData(cd);
        close.addListener(SWT.Selection, e -> s.close());

        s.setDefaultButton(close);
        s.pack();
        Dialogs.centerOnParent(s);
        s.open();
    }
}
