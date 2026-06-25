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

package org.edgo.audio.measure.gui.tips;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;

import java.util.Random;

/**
 * A small title-less "Tip of the day" popup docked over the bottom-left
 * corner of the main window.  It floats <b>on top but not modal</b> — the
 * user can keep working with the application while it is up.
 *
 * <p>When shown at startup it auto-closes after a {@value #AUTO_CLOSE_SECS}
 * second countdown (shown as "(only Ns)").  A <b>Next tip</b> button cycles
 * to the next tip and cancels the countdown for good.  It also closes on a
 * click in any free space, on {@code Esc}, and its "Don't show again"
 * checkbox clears the {@code showTipsAtStartup} preference.
 *
 * <p>Each {@link #open()} starts on a random tip, so re-opening it from the
 * Help menu shows a fresh one.
 */
public final class TipOfTheDayDialog {

    /** Wrap width for the tip text, in pixels. */
    private static final int TEXT_WIDTH = 360;
    /** Gap from the main window's left / bottom edges, in pixels. */
    private static final int MARGIN = 12;
    /** Auto-close countdown for the startup popup, in seconds. */
    private static final int AUTO_CLOSE_SECS = 20;

    private final Shell parent;
    private final Display display;
    private final Random random = new Random();
    private final Runnable tick = this::onTick;

    private Shell shell;
    private Label titleLabel;
    private Label bodyLabel;
    private Button closeButton;
    private Composite footer;
    private Font boldFont;

    private int count = 1;
    private int index;
    private int secondsLeft;
    private boolean countdownActive;

    public TipOfTheDayDialog(Shell parent) {
        this.parent = parent;
        this.display = parent != null ? parent.getDisplay() : null;
    }

    /** Builds and shows the popup, starting the auto-close countdown. */
    public void open() {
        if (parent == null || parent.isDisposed()) return;

        count = readCount();
        index = random.nextInt(count);

        // No ON_TOP: as a child of the main shell it already floats above the
        // main window, but must NOT sit above other applications (system-wide).
        shell = new Shell(parent, SWT.NO_TRIM | SWT.TOOL);
        shell.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
        FillLayout border = new FillLayout();
        border.marginWidth = 1;
        border.marginHeight = 1;
        shell.setLayout(border);

        Color bg = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND);
        Color fg = display.getSystemColor(SWT.COLOR_INFO_FOREGROUND);

        Composite body = new Composite(shell, SWT.NONE);
        body.setBackground(bg);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14;
        gl.marginHeight = 12;
        gl.verticalSpacing = 8;
        body.setLayout(gl);

        boldFont = boldOf(body.getFont());
        shell.addDisposeListener(e -> {
            display.timerExec(-1, tick);
            boldFont.dispose();
        });

        Label heading = new Label(body, SWT.NONE);
        style(heading, bg, fg);
        heading.setFont(boldFont);
        heading.setText(I18n.t("tip.dialog.heading"));

        titleLabel = new Label(body, SWT.NONE);
        style(titleLabel, bg, fg);
        titleLabel.setFont(boldFont);
        titleLabel.setLayoutData(textData());

        bodyLabel = new Label(body, SWT.NONE);
        style(bodyLabel, bg, fg);
        bodyLabel.setLayoutData(textData());

        // Footer: [Don't show again]  [Next tip]  [Close (Ns)]
        footer = new Composite(body, SWT.NONE);
        footer.setBackground(bg);
        GridLayout fl = new GridLayout(3, false);
        fl.marginWidth = 0;
        fl.marginHeight = 0;
        fl.horizontalSpacing = 10;
        footer.setLayout(fl);
        footer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button dontShow = new Button(footer, SWT.CHECK);
        style(dontShow, bg, fg);
        dontShow.setText(I18n.t("tip.dialog.dontShowAgain"));
        dontShow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Reflect the persisted choice so the box shows the real current state.
        dontShow.setSelection(!Preferences.instance().isShowTipsAtStartup());
        dontShow.addListener(SWT.Selection,
                e -> Preferences.instance().setShowTipsAtStartup(!dontShow.getSelection()));

        Button next = new Button(footer, SWT.PUSH);
        next.setText(I18n.t("tip.dialog.next"));
        next.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        next.addListener(SWT.Selection, e -> {
            stopCountdown();
            index = (index + 1) % count;
            showTip();
        });

        // The countdown lives on the Close button label, so the user always
        // has an explicit way to dismiss the popup (clicking it closes early).
        closeButton = new Button(footer, SWT.PUSH);
        closeButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        closeButton.addListener(SWT.Selection, e -> shell.dispose());

        // Click on any free space (everything but the controls) closes it.
        Listener closer = e -> shell.dispose();
        for (Control c : new Control[]{ shell, body, heading, titleLabel, bodyLabel, footer }) {
            c.addListener(SWT.MouseDown, closer);
        }
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) shell.dispose();
        });

        secondsLeft = AUTO_CLOSE_SECS;
        countdownActive = true;
        updateCloseButton();   // "Close (20s)" before pack, so the button fits
        showTip();
        shell.open();
        // On top, but NOT modal: don't steal activation from the main window.
        display.timerExec(1000, tick);
    }

    private void showTip() {
        int n = index + 1;
        int wrapWidth = contentWidth();
        titleLabel.setText(wrapText(titleLabel, I18n.t("tip." + n + ".title"), wrapWidth));
        bodyLabel.setText(wrapText(bodyLabel, I18n.t("tip." + n + ".body"), wrapWidth));
        shell.pack(true);
        placeBottomLeft();
    }

    /** The pixel width the tip text wraps to: at least {@link #TEXT_WIDTH}, but
     *  widened to the footer (checkbox + two buttons) when it needs more room,
     *  so the text fills the popup's real width instead of wrapping early. */
    private int contentWidth() {
        return Math.max(TEXT_WIDTH, footer.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
    }

    /** Word-wraps {@code text} to {@code width} pixels, measured in
     *  {@code owner}'s own font, by inserting line breaks at word boundaries.
     *  The native {@code SWT.WRAP} on a {@link Label} broke words mid-line for
     *  the tip text, so the wrapping is done here and the labels render the
     *  explicit lines — which also keeps each label's height exact, so the popup
     *  never carries a stale size (or blank space) over from the previous tip. */
    private String wrapText(Control owner, String text, int width) {
        GC gc = new GC(owner);
        gc.setFont(owner.getFont());
        try {
            StringBuilder out  = new StringBuilder();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && gc.textExtent(candidate).x > width) {
                    out.append(line).append('\n');
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            return out.append(line).toString();
        } finally {
            gc.dispose();
        }
    }

    // --- countdown -----------------------------------------------------------

    private void onTick() {
        if (shell == null || shell.isDisposed() || !countdownActive) return;
        secondsLeft--;
        if (secondsLeft <= 0) {
            shell.dispose();
            return;
        }
        updateCloseButton();
        display.timerExec(1000, tick);
    }

    /** Cancels the auto-close; the Close button drops its countdown and just
     *  reads "Close" (called once the user picks "Next tip"). */
    private void stopCountdown() {
        countdownActive = false;
        display.timerExec(-1, tick);
        closeButton.setText(I18n.t("tip.dialog.close"));
        closeButton.requestLayout();
    }

    private void updateCloseButton() {
        closeButton.setText(I18n.t("tip.dialog.closeCountdown", secondsLeft));
        closeButton.requestLayout();
    }

    // --- helpers -------------------------------------------------------------

    private int readCount() {
        try {
            return Math.max(1, Integer.parseInt(I18n.t("tip.count").trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private void style(Control c, Color bg, Color fg) {
        c.setBackground(bg);
        c.setForeground(fg);
    }

    private GridData textData() {
        return new GridData(SWT.FILL, SWT.TOP, true, false);
    }

    private Font boldOf(Font base) {
        FontData[] fd = base.getFontData();
        for (FontData d : fd) d.setStyle(d.getStyle() | SWT.BOLD);
        return new Font(display, fd);
    }

    /** Docks the popup over the main window's bottom-left corner, clamped to
     *  the monitor so it can never open off-screen.  Anchoring the bottom
     *  edge keeps the corner fixed as the tip text changes the height. */
    private void placeBottomLeft() {
        Rectangle pb = parent.getBounds();
        Rectangle mon = parent.getMonitor().getClientArea();
        Point size = shell.getSize();
        int x = pb.x + MARGIN;
        int y = pb.y + pb.height - size.y - MARGIN;
        x = Math.max(mon.x, Math.min(x, mon.x + mon.width - size.x));
        y = Math.max(mon.y, Math.min(y, mon.y + mon.height - size.y));
        shell.setLocation(x, y);
    }
}
