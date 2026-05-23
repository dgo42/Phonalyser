package org.edgo.audio.measure.gui;

import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.eclipse.swt.widgets.Display;

/**
 * SWT entry point for the interactive measurement GUI.  Construction of the
 * actual window (menu bar, panes, toggle buttons, icons) lives in
 * {@link MainWindow}; this class only owns the {@link Display} lifecycle and
 * the SWT event loop.
 */
@Log4j2
public final class GuiMain {

    private GuiMain() {}

    public static void main(String[] args) {
        // GUI runs without a terminal: detach the Console appender from the
        // root logger so log4j2 writes only to logs/adc-calibration.log.
        // The CLI Main still gets console output because it loads log4j2.xml
        // unmodified.
        LoggerContext logCtx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig rootLogger = logCtx.getConfiguration().getRootLogger();
        rootLogger.removeAppender("Console");
        logCtx.updateLoggers();

        Display display = new Display();
        MainWindow window = new MainWindow(display);
        window.open();
        while (!window.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        display.dispose();
    }
}
